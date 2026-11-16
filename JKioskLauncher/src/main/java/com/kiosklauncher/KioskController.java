package com.kiosklauncher;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Owns the lifecycle of the isolated kiosk desktop: creates it, launches
 * the browser on it, switches to it, and later tears it back down. The
 * operator's original desktop (and every window on it) is never touched -
 * it just isn't the active/input desktop while the kiosk one is showing.
 */
final class KioskController {

    static final String DESKTOP_NAME = "KioskSecureDesktop";

    private volatile Pointer kioskDesktop;
    private ProcessHandle browserProcess;
    private KioskDesktopGuard guard;
    private Path downloadDir;

    /** Fires on the guard's own thread (attached to the kiosk desktop) - subscribers touching UI must marshal themselves. */
    private volatile Runnable exitRequestedHandler;
    private volatile Runnable browserClosedHandler;

    /** Identifies the running session; null while none runs or while it is being ended on purpose. */
    private volatile Object sessionToken;

    /** A desktop whose CloseDesktop kept failing; retried at the next start - see {@link #closeDesktop}. */
    private Pointer staleDesktop;
    private Path profileDir;

    boolean isActive() {
        return kioskDesktop != null;
    }

    void setExitRequestedHandler(Runnable handler) {
        this.exitRequestedHandler = handler;
    }

    /** Fires (on some pool thread) when the browser's main process ends by itself, i.e. the window was closed. */
    void setBrowserClosedHandler(Runnable handler) {
        this.browserClosedHandler = handler;
    }

    void start(KioskConfig config) throws IOException {
        if (isActive()) {
            return;
        }

        // CreateDesktop on a name that already exists just opens that desktop - the new browser would then
        // land next to a session this launcher doesn't own. Main() deals with such a session at startup
        // (offers to end it), so seeing one here means it appeared since; don't build on top of it.
        if (staleDesktop != null && closeDesktop(staleDesktop)) {
            staleDesktop = null;
        }
        if (KioskSession.exists(DESKTOP_NAME)) {
            throw new IOException("Na pulpicie kiosku działa już inna sesja. Zamknij ją (uruchom aplikację ponownie)"
                    + " i spróbuj jeszcze raz.");
        }

        String browserPath = config.browserPath != null ? config.browserPath : BrowserLocator.find();
        if (browserPath == null) {
            throw new IOException("Nie znaleziono Edge ani Chrome. Ustaw browserPath w kiosk.settings.json.");
        }

        // FileMode "block": no download folder at all (null) - every file dialog is
        // cancelled, nothing is saved, and stop() has nothing to hand off.
        // FileMode "download": the one folder downloads go to. Anything a crashed
        // earlier session left in it must not be there for this customer to find.
        downloadDir = config.fileMode == KioskConfig.FileMode.DOWNLOAD ? config.resolvedDownloadDir() : null;
        DownloadCollector.collect(downloadDir);

        Path profile = KioskProfile.prepare();
        profileDir = profile;

        Pointer desktop = Win32.User32.INSTANCE.CreateDesktop(
                DESKTOP_NAME, Pointer.NULL, Pointer.NULL, 0, Win32.GENERIC_ALL, Pointer.NULL);
        if (desktop == null) {
            throw new IOException("CreateDesktop nie powiodło się (błąd " + Native.getLastError() + ").");
        }

        // Own profile (--user-data-dir): keeps the kiosk off the operator's
        // Edge - see KioskProfile. Downloads are steered by the guard.
        String commandLine = "\"" + browserPath + "\" --kiosk \"" + config.url + "\""
                + " --user-data-dir=\"" + profile.toAbsolutePath() + "\""
                + " --edge-kiosk-type=" + config.kioskType
                + " --kiosk-idle-timeout-minutes=" + config.idleTimeoutMinutes
                + " --no-first-run --start-maximized";

        // The new desktop has no shell/window manager on it, so nothing
        // else maximizes the window for us - request it explicitly, on top
        // of --start-maximized, so it reliably fills the whole screen.
        Win32.STARTUPINFO startupInfo = new Win32.STARTUPINFO();
        startupInfo.cb = startupInfo.size();
        startupInfo.lpDesktop = new WString(DESKTOP_NAME);
        startupInfo.dwFlags = Win32.STARTF_USESHOWWINDOW;
        startupInfo.wShowWindow = Win32.SW_MAXIMIZE;

        Win32.PROCESS_INFORMATION processInfo = new Win32.PROCESS_INFORMATION();

        // CreateProcess writes into this buffer - must be mutable, not a plain String.
        char[] commandLineBuffer = (commandLine + "\0").toCharArray();

        boolean created = Win32.Kernel32.INSTANCE.CreateProcess(
                null, commandLineBuffer, Pointer.NULL, Pointer.NULL, false,
                0, Pointer.NULL, null, startupInfo, processInfo);

        if (!created) {
            int err = Native.getLastError();
            Win32.User32.INSTANCE.CloseDesktop(desktop);
            throw new IOException("Nie udało się uruchomić przeglądarki (błąd " + err + ").");
        }

        Win32.Kernel32.INSTANCE.CloseHandle(processInfo.hThread);

        kioskDesktop = desktop;
        browserProcess = ProcessHandle.of(Integer.toUnsignedLong(processInfo.dwProcessId)).orElse(null);
        Win32.Kernel32.INSTANCE.CloseHandle(processInfo.hProcess);

        // The customer closing the browser window (Ctrl+W on the last tab, ...) ends the session the
        // same way the exit hotkey does: back to the normal desktop, behind the lock screen. Without
        // this the customer would be left looking at a bare desktop. Our own kill in stop() clears
        // sessionToken first, so it doesn't count.
        Object token = new Object();
        sessionToken = token;
        if (browserProcess != null) {
            browserProcess.onExit().thenRun(() -> {
                Runnable handler = browserClosedHandler;
                if (sessionToken == token && handler != null) {
                    handler.run();
                }
            });
        }

        guard = new KioskDesktopGuard(kioskDesktop, downloadDir);
        guard.setExitHandler(() -> {
            Runnable handler = exitRequestedHandler;
            if (handler != null) {
                handler.run();
            }
        });
        try {
            guard.start();
        } catch (RuntimeException e) {
            // Guard couldn't arm (hotkey/dialog watchdog) - never hand the
            // screen to the customer in that state. Undo everything; we're
            // still on the operator's desktop, so no lock is needed.
            sessionToken = null;
            guard.stop();
            guard = null;
            if (browserProcess != null) {
                killProcessTree(browserProcess);
                browserProcess = null;
            }
            KioskProfile.endBrowsersUsing(profile);
            if (!closeDesktop(kioskDesktop)) {
                staleDesktop = kioskDesktop;
            }
            kioskDesktop = null;
            throw new IOException("Nie udało się uzbroić strażnika kiosku: " + e.getMessage(), e);
        }

        Win32.User32.INSTANCE.SwitchDesktop(kioskDesktop);
        SingleInstance.markSession(true);
    }

    /**
     * The only way out: switches back to the operator's Default desktop
     * and immediately locks the session (same as Win+L), then tears the
     * kiosk browser and desktop down. Whoever is at the machine has to
     * enter the operator's password at the lock screen before anything
     * from the original session is usable again.
     *
     * Order matters here: Winlogon resumes, after a successful unlock,
     * whichever desktop was active *when LockWorkStation was called* - not
     * always "Default". Locking while KioskSecureDesktop was still active
     * (and then tearing it down) left the operator stranded on a dead,
     * empty desktop with no way back, since LockWorkStation resumed there
     * instead of Default. Switching to Default first - exactly like a
     * normal Win+L, which is always invoked from Default - avoids that.
     *
     * Deliberately NOT triggered by the browser process exiting on its own
     * (crash, etc.) - only by an explicit operator action - so it just
     * leaves the bare kiosk desktop up rather than silently unlocking.
     */
    void stop() {
        stop(true);
    }

    /** @param lock false only for tests that must not lock the machine they run on. */
    void stop(boolean lock) {
        if (!isActive()) {
            return;
        }

        sessionToken = null; // from here on the browser ending is our doing, not the customer's
        switchToDefaultDesktop();
        if (lock) {
            Win32.User32.INSTANCE.LockWorkStation();
        }

        if (guard != null) {
            guard.stop();
            guard = null;
        }

        if (browserProcess != null) {
            try {
                if (browserProcess.isAlive()) {
                    killProcessTree(browserProcess);
                    browserProcess.onExit().get(5, TimeUnit.SECONDS);
                }
            } catch (TimeoutException | java.util.concurrent.ExecutionException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // best-effort - proceed with teardown regardless
            }
            browserProcess = null;
        }

        // The browser's main process may be gone already (the window was closed) while helper processes
        // live on - they would hold the desktop and the profile. Whatever still runs on the profile goes.
        if (profileDir != null) {
            KioskProfile.endBrowsersUsing(profileDir);
        }

        // The browser is gone, so nothing is writing to the folder any more.
        // The session is already locked at this point - this doesn't delay it.
        DownloadCollector.collect(downloadDir);

        if (!closeDesktop(kioskDesktop)) {
            staleDesktop = kioskDesktop;
        }
        kioskDesktop = null;
        SingleInstance.markSession(false);
    }

    /**
     * CloseDesktop fails with ERROR_BUSY while a thread of this process is still attached to the desktop -
     * and the guard thread counts as attached for a moment after it has finished running (Thread.isAlive()
     * is already false; the OS is not done with it). Giving up on the first try left the desktop alive for
     * the life of the process: the next "session" was an empty desktop nobody guarded, and the exit hotkey
     * was gone. So retry for a while.
     */
    private static boolean closeDesktop(Pointer desktop) {
        for (int i = 0; i < 50; i++) {
            if (Win32.User32.INSTANCE.CloseDesktop(desktop)) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void killProcessTree(ProcessHandle handle) {
        handle.descendants().forEach(ProcessHandle::destroyForcibly);
        handle.destroyForcibly();
    }

    private static void switchToDefaultDesktop() {
        Pointer defaultDesktop = Win32.User32.INSTANCE.OpenDesktop(
                "Default", 0, false, Win32.DESKTOP_SWITCHDESKTOP);
        if (defaultDesktop == null) {
            return;
        }
        Win32.User32.INSTANCE.SwitchDesktop(defaultDesktop);
        Win32.User32.INSTANCE.CloseDesktop(defaultDesktop);
    }
}
