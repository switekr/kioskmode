package com.kiosklauncher;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * "Show in folder" in the kiosk browser starts a new explorer.exe, which opens
 * a full Explorer window on the kiosk desktop. This test does exactly that
 * (explorer.exe /select,... started on a throwaway desktop - no screen
 * takeover) and checks the guard closes it:
 *
 *   - CONTROL, no guard: the Explorer window must still be there (proves the test can fail);
 *   - GUARDED: the window must be gone within a few seconds, must never have been
 *     visible for more than ~one frame, and no explorer.exe may be left over from the test.
 *
 * Only explorer.exe processes the test itself started are ever killed.
 *
 * Run: java -cp "target/JKioskLauncher.jar;target/test-classes" com.kiosklauncher.ExplorerWindowTest
 */
public final class ExplorerWindowTest {

    private static final String DESKTOP_NAME = "KioskExplorerTest";
    private static final String EXPLORER_CLASS = "CabinetWClass";
    private static final double FLICKER_BUDGET_MS = 16.7;

    private interface U32 extends StdCallLibrary {
        U32 I = Native.load("user32", U32.class, W32APIOptions.UNICODE_OPTIONS);

        Pointer FindWindow(String className, String windowName);

        boolean IsWindowVisible(Pointer hWnd);

        boolean IsWindow(Pointer hWnd);

        boolean SetThreadDesktop(Pointer hDesktop);
    }

    public static void main(String[] args) throws Exception {
        boolean control = run(false, null);
        // Explorer windows are closed in BOTH FileModes: BLOCK (no download folder) and DOWNLOAD.
        boolean block = run(true, null);
        boolean download = run(true, Path.of(System.getProperty("java.io.tmpdir")));
        boolean all = control && block && download;
        System.out.println(all ? "ALL PASSED" : "FAILED");
        System.exit(all ? 0 : 1);
    }

    private static Set<Long> explorerPids() {
        return ProcessHandle.allProcesses()
                .filter(p -> p.info().command().map(c -> c.toLowerCase().endsWith("\\explorer.exe")).orElse(false))
                .map(ProcessHandle::pid).collect(Collectors.toSet());
    }

    /** @param saveDir null = FileMode BLOCK; a folder = FileMode DOWNLOAD */
    private static boolean run(boolean withGuard, Path saveDir) throws Exception {
        Pointer desktop = Win32.User32.INSTANCE.CreateDesktop(
                DESKTOP_NAME, Pointer.NULL, Pointer.NULL, 0, Win32.GENERIC_ALL, Pointer.NULL);
        if (desktop == null) {
            System.out.println("ERROR: CreateDesktop");
            return false;
        }
        Set<Long> before = explorerPids();
        KioskDesktopGuard guard = null;
        Set<Long> leftover = new HashSet<>();
        try {
            if (withGuard) {
                guard = new KioskDesktopGuard(desktop, saveDir);
                guard.start();
            }

            // Poll for visibility on the same desktop while explorer starts and (maybe) gets closed.
            double[] visibleMs = new double[1];
            boolean[] everSeen = new boolean[1];
            AtomicBoolean stop = new AtomicBoolean();
            Thread probe = new Thread(() -> {
                if (!U32.I.SetThreadDesktop(desktop)) {
                    return;
                }
                long last = System.nanoTime();
                while (!stop.get()) {
                    long now = System.nanoTime();
                    Pointer h = U32.I.FindWindow(EXPLORER_CLASS, null);
                    if (h != null) {
                        everSeen[0] = true;
                        if (U32.I.IsWindowVisible(h)) {
                            visibleMs[0] += (now - last) / 1e6;
                        }
                    }
                    last = now;
                    Thread.onSpinWait();
                }
            }, "ExplorerProbe");
            probe.setDaemon(true);
            probe.start();

            Win32.STARTUPINFO si = new Win32.STARTUPINFO();
            si.cb = si.size();
            si.lpDesktop = new WString(DESKTOP_NAME);
            Win32.PROCESS_INFORMATION pi = new Win32.PROCESS_INFORMATION();
            String cmd = "explorer.exe /select,\"C:\\Windows\\notepad.exe\"";
            if (!Win32.Kernel32.INSTANCE.CreateProcess(null, (cmd + "\0").toCharArray(), Pointer.NULL, Pointer.NULL,
                    false, 0, Pointer.NULL, null, si, pi)) {
                System.out.println("ERROR: CreateProcess");
                return false;
            }
            Win32.Kernel32.INSTANCE.CloseHandle(pi.hThread);
            Win32.Kernel32.INSTANCE.CloseHandle(pi.hProcess);

            Thread.sleep(8000);
            stop.set(true);
            probe.join(2000);

            boolean windowStillThere = onDesktop(desktop, () -> U32.I.FindWindow(EXPLORER_CLASS, null) != null);
            leftover.addAll(explorerPids());
            leftover.removeAll(before);

            boolean ok;
            String detail;
            String label;
            if (withGuard) {
                ok = everSeen[0] && !windowStillThere && visibleMs[0] <= FLICKER_BUDGET_MS && leftover.isEmpty();
                detail = "window appeared=%b, still there after 8 s=%b, visible %.0f ms (budget %.1f), explorer.exe left over=%s"
                        .formatted(everSeen[0], windowStillThere, visibleMs[0], FLICKER_BUDGET_MS, leftover);
                label = saveDir == null ? "BLOCK mode -> closed" : "DOWNLOAD mode -> closed";
            } else {
                ok = windowStillThere;
                detail = "window appeared=%b, still there after 8 s=%b".formatted(everSeen[0], windowStillThere);
                label = "control, no guard -> stays open";
            }
            System.out.printf("%s  Explorer window, %s: %s%n", ok ? "PASS" : "FAIL", label, detail);
            return ok;
        } finally {
            // Only processes the test itself started - never the operator's own shell.
            Set<Long> mine = new HashSet<>(explorerPids());
            mine.removeAll(before);
            mine.forEach(pid -> ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly));
            Thread.sleep(1000);
            if (guard != null) {
                guard.stop();
            }
            Win32.User32.INSTANCE.CloseDesktop(desktop);
        }
    }

    /** Runs a check from a thread attached to the given desktop (FindWindow only sees the calling thread's desktop). */
    private static boolean onDesktop(Pointer desktop, java.util.function.BooleanSupplier check) throws InterruptedException {
        boolean[] result = new boolean[1];
        Thread t = new Thread(() -> {
            if (U32.I.SetThreadDesktop(desktop)) {
                result[0] = check.getAsBoolean();
            }
        });
        t.start();
        t.join(3000);
        return result[0];
    }
}
