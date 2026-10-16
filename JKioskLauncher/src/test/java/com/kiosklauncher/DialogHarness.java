package com.kiosklauncher;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.ByteByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test helper: opens a real OpenFileDialog (via powershell.exe) on a
 * throwaway desktop, optionally with a KioskDesktopGuard running, and
 * optionally measures how long the dialog frame was actually visible.
 * Never switches desktops, so it doesn't take over the screen.
 */
final class DialogHarness {

    private static final String DESKTOP_NAME = "KioskSmokeTest";
    static final long DEFAULT_TIMEOUT_MS = 15_000;

    record Outcome(String result, double visibleMs) {
    }

    /** The name every Save dialog in these tests is pre-filled with (Edge pre-fills the suggested download name). */
    static final String SUGGESTED_NAME = "report.txt";

    enum Kind {
        FILE_DIALOG_VISTA("OpenFileDialog", "$d.AutoUpgradeEnabled = $true;"),
        FILE_DIALOG_CLASSIC("OpenFileDialog", "$d.AutoUpgradeEnabled = $false;"),
        /** Save dialog with a suggested name, like Edge's download "Save as" - the guard answers it. */
        SAVE_DIALOG_VISTA("SaveFileDialog", "$d.AutoUpgradeEnabled = $true; $d.FileName = '" + SUGGESTED_NAME + "';"),
        SAVE_DIALOG_CLASSIC("SaveFileDialog", "$d.AutoUpgradeEnabled = $false; $d.FileName = '" + SUGGESTED_NAME + "';"),
        /** Save dialog with an empty name box - indistinguishable from an upload, must be cancelled. */
        SAVE_DIALOG_EMPTY_NAME("SaveFileDialog", "$d.AutoUpgradeEnabled = $true;"),
        /** A plain #32770 that is NOT a file dialog - must be left alone (just delayed). */
        MESSAGE_BOX(null, "");

        final String dialogClass;
        final String style;

        Kind(String dialogClass, String style) {
            this.dialogClass = dialogClass;
            this.style = style;
        }

        /** Result file content: "Cancel", "OK|<chosen path>" for the dialogs, or the MessageBox button. */
        String script(Path resultFile) {
            String load = "Add-Type -AssemblyName System.Windows.Forms;";
            if (dialogClass != null) {
                return load + "$d = New-Object System.Windows.Forms." + dialogClass + ";" + style
                        + "$r = $d.ShowDialog();"
                        + "Set-Content -Path '" + resultFile + "' -Value ($r.ToString() + '|' + $d.FileName)";
            }
            return load + "$r = [System.Windows.Forms.MessageBox]::Show('kiosk test');"
                    + "Set-Content -Path '" + resultFile + "' -Value ($r.ToString() + '|')";
        }
    }

    /** Test-only user32 declarations (not needed by the app itself). */
    private interface U32 extends StdCallLibrary {
        U32 I = Native.load("user32", U32.class, W32APIOptions.UNICODE_OPTIONS);

        Pointer FindWindow(String className, String windowName);

        boolean IsWindowVisible(Pointer hWnd);

        int GetWindowLong(Pointer hWnd, int nIndex);

        boolean GetLayeredWindowAttributes(Pointer hWnd, IntByReference crKey, ByteByReference alpha,
                                           IntByReference flags);

        boolean SetThreadDesktop(Pointer hDesktop);
    }

    /**
     * Polls the throwaway desktop for a top-level #32770 and accumulates the
     * time it is "visible": WS_VISIBLE and not fully transparent. That is an
     * upper bound on what the user could see (DWM may not have composed a
     * frame yet), and one 60 Hz frame is ~16.7 ms.
     */
    private static final class VisibilityProbe extends Thread {
        private final Pointer desktop;
        private final AtomicBoolean stop = new AtomicBoolean();
        volatile double visibleMs;

        VisibilityProbe(Pointer desktop) {
            super("VisibilityProbe");
            setDaemon(true);
            this.desktop = desktop;
        }

        @Override
        public void run() {
            if (!U32.I.SetThreadDesktop(desktop)) {
                return;
            }
            long last = System.nanoTime();
            while (!stop.get()) {
                long now = System.nanoTime();
                Pointer h = U32.I.FindWindow("#32770", null);
                if (h != null && effectivelyVisible(h)) {
                    visibleMs += (now - last) / 1e6;
                }
                last = now;
                Thread.onSpinWait();
            }
        }

        private static boolean effectivelyVisible(Pointer h) {
            if (!U32.I.IsWindowVisible(h)) {
                return false;
            }
            int exStyle = U32.I.GetWindowLong(h, -20); // GWL_EXSTYLE
            if ((exStyle & 0x00080000) == 0) {          // WS_EX_LAYERED
                return true;
            }
            ByteByReference alpha = new ByteByReference();
            IntByReference flags = new IntByReference();
            if (!U32.I.GetLayeredWindowAttributes(h, null, alpha, flags)) {
                return true;
            }
            return (flags.getValue() & 0x2) == 0 || (alpha.getValue() & 0xFF) > 0; // LWA_ALPHA
        }

        void finish() throws InterruptedException {
            stop.set(true);
            join(2000);
        }
    }

    /**
     * @param measure   run the visibility probe (skipped for runs that are expected to sit open
     *                  until the timeout, since the probe spins a CPU core)
     * @param timeoutMs how long to wait for the dialog to go away; on expiry the result is "TIMEOUT"
     */
    static Outcome run(Kind kind, boolean withGuard, java.nio.file.Path saveDir, boolean measure, long timeoutMs)
            throws Exception {
        Pointer desktop = Win32.User32.INSTANCE.CreateDesktop(
                DESKTOP_NAME, Pointer.NULL, Pointer.NULL, 0, Win32.GENERIC_ALL, Pointer.NULL);
        if (desktop == null) {
            return new Outcome("ERROR: CreateDesktop", 0);
        }

        KioskDesktopGuard guard = null;
        VisibilityProbe probe = null;
        ProcessHandle process = null;
        Path resultFile = Files.createTempFile("kiosk-smoke-", ".txt");
        Files.deleteIfExists(resultFile);

        try {
            if (withGuard) {
                guard = new KioskDesktopGuard(desktop, saveDir);
                guard.start();
            }
            if (measure) {
                probe = new VisibilityProbe(desktop);
                probe.start();
            }

            String commandLine = "powershell.exe -NoProfile -STA -Command \"" + kind.script(resultFile) + "\"";

            Win32.STARTUPINFO si = new Win32.STARTUPINFO();
            si.cb = si.size();
            si.lpDesktop = new WString(DESKTOP_NAME);
            Win32.PROCESS_INFORMATION pi = new Win32.PROCESS_INFORMATION();

            boolean created = Win32.Kernel32.INSTANCE.CreateProcess(
                    null, (commandLine + "\0").toCharArray(), Pointer.NULL, Pointer.NULL, false,
                    0, Pointer.NULL, null, si, pi);
            if (!created) {
                return new Outcome("ERROR: CreateProcess", 0);
            }
            Win32.Kernel32.INSTANCE.CloseHandle(pi.hThread);
            Win32.Kernel32.INSTANCE.CloseHandle(pi.hProcess);
            process = ProcessHandle.of(Integer.toUnsignedLong(pi.dwProcessId)).orElse(null);
            if (process == null) {
                return new Outcome("ERROR: process gone", 0);
            }

            try {
                process.onExit().get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                if (probe != null) {
                    probe.finish();
                }
                return new Outcome("TIMEOUT", probe != null ? probe.visibleMs : 0);
            }

            String result = Files.exists(resultFile)
                    ? Files.readString(resultFile, StandardCharsets.UTF_8).trim()
                    : "ERROR: no result file";
            if (probe != null) {
                probe.finish();
            }
            return new Outcome(result, probe != null ? probe.visibleMs : 0);
        } finally {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try {
                    process.onExit().get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // best effort
                }
            }
            if (probe != null) {
                probe.finish();
            }
            if (guard != null) {
                guard.stop();
            }
            Win32.User32.INSTANCE.CloseDesktop(desktop);
            Files.deleteIfExists(resultFile);
        }
    }
}
