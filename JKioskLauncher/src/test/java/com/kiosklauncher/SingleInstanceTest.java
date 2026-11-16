package com.kiosklauncher;

import com.sun.jna.Pointer;
import com.sun.jna.WString;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Only one launcher at a time, and what the second one can do about an existing session.
 *
 *   1. SingleInstance: the first caller is primary; a second one is told who is running (a real child JVM
 *      holds the mutex) and can end it; stale info (dead pid, reused pid) is not mistaken for a live
 *      launcher; a mutex with no info at all counts as running but cannot be ended.
 *   2. KioskSession: a desktop is detected while it exists; an ORPHANED session (its launcher gone,
 *      real Edge still running on the desktop) is ended and the desktop disappears - while an unrelated
 *      process is left alone. The screen is never switched (that would blank it); switchTo only gets its
 *      failure path here.
 *
 * Run: java -cp "target/JKioskLauncher.jar;target/test-classes" com.kiosklauncher.SingleInstanceTest
 */
public final class SingleInstanceTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("hold")) {
            // child: be "the running launcher" until killed
            SingleInstance.Result r = SingleInstance.acquire(args[1], Path.of(args[2]));
            System.out.println(r.otherRunning ? "NOT-PRIMARY" : "PRIMARY");
            System.out.flush();
            Thread.sleep(120_000);
            return;
        }

        if (args.length == 1 && args[0].equals("orphan")) {
            // helper for manual end-to-end checks: a browser on the REAL kiosk desktop and real profile,
            // started the way the app does and then left running with no launcher. Never switches the screen.
            orphanOnKioskDesktop();
            return;
        }

        Path root = Files.createTempDirectory("kiosk-instance-test-");
        try {
            singleInstance(root);
            kioskSession(root);
        } finally {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---- SingleInstance --------------------------------------------------------------------

    private static void singleInstance(Path root) throws Exception {
        String mutex = "Global\\KioskLauncherTest-" + UUID.randomUUID();
        Path info = root.resolve("instance.txt");

        // A real other launcher: a child JVM that takes the mutex first.
        Process child = new ProcessBuilder(javaExe(), "-cp", System.getProperty("java.class.path"),
                "com.kiosklauncher.SingleInstanceTest", "hold", mutex, info.toString())
                .redirectErrorStream(true).start();
        String childSays = new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream())).readLine();
        check("child launcher became primary", "PRIMARY".equals(childSays), String.valueOf(childSays));

        SingleInstance.Result second = SingleInstance.acquire(mutex, info);
        check("second launcher sees the running one and knows its process",
                second.otherRunning && second.other != null && second.other.pid() == child.pid(),
                "otherRunning=" + second.otherRunning + " other=" + (second.other == null ? null : second.other.pid())
                        + " child=" + child.pid());

        boolean ended = second.terminateOther();
        check("the other launcher is ended (and nothing it ran is left)", ended && !child.isAlive());
        check("info file now names this process", Files.readString(info).contains("pid=" + ProcessHandle.current().pid()));

        // A launcher that is idle says so; one running a session says that - this is what lets a second
        // launcher tell a guarded session (offer Przełącz) from a leftover desktop (offer only to end it).
        check("idle launcher: session flag is off", Files.readString(info).contains("session=none"));
        SingleInstance.markSession(true);
        check("launcher with a session: flag on in the info file", Files.readString(info).contains("session=active"));
        SingleInstance.Result seen = SingleInstance.acquire(mutex, info);
        check("a newcomer reads that the other launcher has a live session",
                seen.otherRunning && seen.otherSessionActive);
        SingleInstance.markSession(false);
        SingleInstance.Result seenIdle = SingleInstance.acquire(mutex, info);
        check("...and that it has none after the session ended", seenIdle.otherRunning && !seenIdle.otherSessionActive);

        // This process is registered now: a further launcher would see US.
        SingleInstance.Result third = SingleInstance.acquire(mutex, info);
        check("after the take-over, a newcomer sees this process as the running launcher",
                third.otherRunning && third.other != null && third.other.pid() == ProcessHandle.current().pid());

        // Stale: the recorded process is gone. The mutex object still exists (this process holds a handle).
        Process gone = new ProcessBuilder("cmd", "/c", "exit").start();
        gone.waitFor();
        Files.writeString(info, "pid=" + gone.pid() + "\nstart=1\n");
        SingleInstance.Result stale = SingleInstance.acquire(mutex, info);
        check("recorded launcher is dead: mutex is stale, this one becomes primary", !stale.otherRunning);

        // Pid reuse: the pid is alive but it is a different process (start time doesn't match).
        Files.writeString(info, "pid=" + ProcessHandle.current().pid() + "\nstart=1\n");
        SingleInstance.Result reused = SingleInstance.acquire(mutex, info);
        check("recorded pid belongs to a different process now: not mistaken for a launcher", !reused.otherRunning);

        // No info at all (older build): counts as running, but cannot be named or ended.
        Files.deleteIfExists(info);
        SingleInstance.Result unknown = SingleInstance.acquire(mutex, info);
        check("mutex but no info: running, owner unknown, cannot be ended",
                unknown.otherRunning && unknown.other == null && !unknown.terminateOther());
    }

    // ---- KioskSession ----------------------------------------------------------------------

    private static void kioskSession(Path root) throws Exception {
        String name = "KioskSessionTest" + (System.currentTimeMillis() % 100000);

        check("no such desktop: not a session", !KioskSession.exists(name));
        check("switching to a desktop that doesn't exist fails cleanly", !KioskSession.switchTo(name));

        Pointer desktop = Win32.User32.INSTANCE.CreateDesktop(
                name, Pointer.NULL, Pointer.NULL, 0, Win32.GENERIC_ALL, Pointer.NULL);
        check("created test desktop", desktop != null);
        if (desktop == null) {
            return;
        }
        check("desktop in use: detected as a session", KioskSession.exists(name));
        Win32.User32.INSTANCE.CloseDesktop(desktop);
        check("nobody uses it any more: gone", !KioskSession.exists(name));

        String browser = BrowserLocator.find();
        if (browser == null) {
            System.out.println("SKIP  orphaned-session case - no Edge/Chrome installed");
            return;
        }

        // An orphaned session: Edge on the kiosk desktop whose launcher is gone. Our handle to the desktop is
        // closed right after CreateProcess, so the browser alone keeps the desktop alive - as in real life.
        Path profile = root.resolve("orphan-profile");
        KioskProfile.prepare(profile);
        Process decoy = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                "Start-Sleep 120 # " + root.resolve("somebody-elses-profile")).start();

        desktop = Win32.User32.INSTANCE.CreateDesktop(
                name, Pointer.NULL, Pointer.NULL, 0, Win32.GENERIC_ALL, Pointer.NULL);
        try {
            String cmd = "\"" + browser + "\" --kiosk \"about:blank\" --user-data-dir=\"" + profile
                    + "\" --edge-kiosk-type=public-browsing --no-first-run";
            Win32.STARTUPINFO si = new Win32.STARTUPINFO();
            si.cb = si.size();
            si.lpDesktop = new WString(name);
            Win32.PROCESS_INFORMATION pi = new Win32.PROCESS_INFORMATION();
            boolean started = Win32.Kernel32.INSTANCE.CreateProcess(null, (cmd + "\0").toCharArray(),
                    Pointer.NULL, Pointer.NULL, false, 0, Pointer.NULL, null, si, pi);
            check("started Edge on the test desktop", started);
            if (!started) {
                return;
            }
            Win32.Kernel32.INSTANCE.CloseHandle(pi.hThread);
            Win32.Kernel32.INSTANCE.CloseHandle(pi.hProcess);
            Thread.sleep(3000);
        } finally {
            Win32.User32.INSTANCE.CloseDesktop(desktop);
        }

        boolean orphaned = KioskSession.exists(name);
        check("launcher gone, browser still there: desktop still exists (an orphaned session)", orphaned);

        long t0 = System.currentTimeMillis();
        boolean ended = KioskSession.end(name, profile);
        check("orphaned session ended: browser stopped, desktop gone", ended && !KioskSession.exists(name),
                "took " + (System.currentTimeMillis() - t0) + " ms");
        check("an unrelated process is left alone", decoy.isAlive());

        decoy.destroyForcibly();
        Thread.sleep(500);
    }

    private static void orphanOnKioskDesktop() throws Exception {
        String browser = BrowserLocator.find();
        Path profile = KioskProfile.prepare();
        Pointer desktop = Win32.User32.INSTANCE.CreateDesktop(KioskController.DESKTOP_NAME, Pointer.NULL,
                Pointer.NULL, 0, Win32.GENERIC_ALL, Pointer.NULL);
        String cmd = "\"" + browser + "\" --kiosk \"about:blank\" --user-data-dir=\"" + profile.toAbsolutePath()
                + "\" --edge-kiosk-type=public-browsing --no-first-run";
        Win32.STARTUPINFO si = new Win32.STARTUPINFO();
        si.cb = si.size();
        si.lpDesktop = new WString(KioskController.DESKTOP_NAME);
        Win32.PROCESS_INFORMATION pi = new Win32.PROCESS_INFORMATION();
        boolean ok = Win32.Kernel32.INSTANCE.CreateProcess(null, (cmd + " ").toCharArray(), Pointer.NULL,
                Pointer.NULL, false, 0, Pointer.NULL, null, si, pi);
        Thread.sleep(3000);
        Win32.User32.INSTANCE.CloseDesktop(desktop);
        System.out.println("orphan started=" + ok);
    }

    private static String javaExe() {
        return new File(System.getProperty("java.home"), "bin/java.exe").getPath();
    }

    private static void check(String what, boolean ok) {
        check(what, ok, "");
    }

    private static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        if (!ok) {
            failures++;
        }
    }
}
