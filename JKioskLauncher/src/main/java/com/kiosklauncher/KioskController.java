package com.kiosklauncher;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Owns the lifecycle of the isolated kiosk desktop: creates it, launches
 * the browser on it, switches to it, and later tears it back down. The
 * operator's original desktop (and every window on it) is never touched -
 * it just isn't the active/input desktop while the kiosk one is showing.
 */
final class KioskController {

    private static final String DESKTOP_NAME = "KioskSecureDesktop";

    private volatile Pointer kioskDesktop;
    private ProcessHandle browserProcess;
    private KioskDesktopGuard guard;

    /** Fires on the guard's own thread (attached to the kiosk desktop) - subscribers touching UI must marshal themselves. */
    private volatile Runnable exitRequestedHandler;

    boolean isActive() {
        return kioskDesktop != null;
    }

    void setExitRequestedHandler(Runnable handler) {
        this.exitRequestedHandler = handler;
    }

    void start(KioskConfig config) throws IOException {
        if (isActive()) {
            return;
        }

        String browserPath = config.browserPath != null ? config.browserPath : BrowserLocator.find();
        if (browserPath == null) {
            throw new IOException("Nie znaleziono Edge ani Chrome. Ustaw browserPath w kiosk.settings.json.");
        }

        Pointer desktop = Win32.User32.INSTANCE.CreateDesktop(
                DESKTOP_NAME, Pointer.NULL, Pointer.NULL, 0, Win32.GENERIC_ALL, Pointer.NULL);
        if (desktop == null) {
            throw new IOException("CreateDesktop nie powiodło się (błąd " + Native.getLastError() + ").");
        }

        String commandLine = "\"" + browserPath + "\" --kiosk \"" + config.url + "\""
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

        guard = new KioskDesktopGuard(kioskDesktop);
        guard.setExitHandler(() -> {
            Runnable handler = exitRequestedHandler;
            if (handler != null) {
                handler.run();
            }
        });
        guard.start();

        Win32.User32.INSTANCE.SwitchDesktop(kioskDesktop);
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
        if (!isActive()) {
            return;
        }

        switchToDefaultDesktop();
        Win32.User32.INSTANCE.LockWorkStation();

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

        Win32.User32.INSTANCE.CloseDesktop(kioskDesktop);
        kioskDesktop = null;
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
