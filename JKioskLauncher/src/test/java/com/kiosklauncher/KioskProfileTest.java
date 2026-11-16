package com.kiosklauncher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * KioskProfile.prepare() when the profile folder holds a file that another process has open - the
 * "campaign_history-journal is locked by another process" start failure. The holder is a real
 * PowerShell process that opens the file with FileShare.None (Java itself can't simulate this:
 * it opens files with share-delete, so a Java-held file can still be deleted).
 *
 *   1. holder started ON this profile (its command line carries the path, like a leftover kiosk
 *      browser) -> prepare() ends it, wipes, and returns the very same folder;
 *   2. holder that cannot be recognised (path passed through an environment variable, like an
 *      antivirus scan) -> prepare() must NOT fail: it returns a fresh sibling folder, and the
 *      stale one is swept on the next prepare() once the holder is gone.
 *
 * Run: java -cp "target/JKioskLauncher.jar;target/test-classes" com.kiosklauncher.KioskProfileTest
 */
public final class KioskProfileTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("kiosk-profile-test-");
        try {
            Path profile = root.resolve("profile");

            // 0. no locks at all: plain wipe, same folder, previous customer's data gone
            Files.createDirectories(profile.resolve("Default"));
            Files.writeString(profile.resolve("Default").resolve("leftover.txt"), "previous customer");
            Path p0 = KioskProfile.prepare(profile);
            check("no lock: same folder, emptied", p0.equals(profile) && listing(profile).isEmpty());

            // 1. a browser still running on this profile (command line contains the path) holds a file
            Path journal = profile.resolve("Nurturing").resolve("campaign_history-journal");
            Files.createDirectories(journal.getParent());
            Files.writeString(journal, "");
            Process holder = holdOpenWithPathOnCommandLine(journal);
            try {
                check("holder really blocks deletion", waitUntilLocked(journal));
                long t0 = System.currentTimeMillis();
                Path p1 = KioskProfile.prepare(profile);
                check("leftover browser on the profile is ended, same folder reused",
                        p1.equals(profile) && listing(profile).isEmpty() && !holder.isAlive(),
                        "returned " + p1.getFileName() + ", holder alive=" + holder.isAlive()
                                + ", took " + (System.currentTimeMillis() - t0) + " ms");
            } finally {
                holder.destroyForcibly();
            }

            // 2. an unrecognisable holder: must not fail, must move to a fresh sibling
            Files.createDirectories(journal.getParent());
            Files.writeString(journal, "");
            Process stranger = holdOpenHiddenPath(journal);
            Path p2;
            try {
                check("stranger really blocks deletion", waitUntilLocked(journal));
                p2 = KioskProfile.prepare(profile);
                check("unkillable holder: kiosk still starts, on a fresh sibling folder",
                        !p2.equals(profile) && p2.getParent().equals(root) && Files.isDirectory(p2)
                                && p2.getFileName().toString().startsWith("profile-") && listing(p2).isEmpty(),
                        "returned " + p2.getFileName());
                check("the locked file is still there (nothing was forced)", Files.exists(journal));
            } finally {
                stranger.destroyForcibly();
                stranger.waitFor();
            }

            // 3. holder gone: next prepare() goes back to the normal folder and sweeps the fallback
            Thread.sleep(500);
            Path p3 = KioskProfile.prepare(profile);
            check("next start is back on the normal folder, stale folder and fallback swept",
                    p3.equals(profile) && listing(profile).isEmpty() && !Files.exists(journal)
                            && !Files.exists(p2),
                    "folders now: " + listing(root).stream().map(x -> x.getFileName().toString()).toList());

            // 4. the real thing: a kiosk Edge left running on the profile (app killed from Task Manager,
            //    a crash) - exactly what produced "campaign_history-journal is locked by another process".
            realEdgeLeftRunning(root);
        } finally {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void realEdgeLeftRunning(Path root) throws Exception {
        String browser = BrowserLocator.find();
        if (browser == null) {
            System.out.println("SKIP  real Edge case - no Edge/Chrome installed");
            return;
        }
        Path profile = root.resolve("edge-profile");
        KioskProfile.prepare(profile);

        String desktopName = "KioskProfileTest";
        com.sun.jna.Pointer desktop = Win32.User32.INSTANCE.CreateDesktop(
                desktopName, com.sun.jna.Pointer.NULL, com.sun.jna.Pointer.NULL, 0, Win32.GENERIC_ALL,
                com.sun.jna.Pointer.NULL);
        ProcessHandle edge = null;
        try {
            // Same flags the app uses; on a throwaway desktop, so nothing appears on the real screen.
            String cmd = "\"" + browser + "\" --kiosk \"about:blank\" --user-data-dir=\"" + profile
                    + "\" --edge-kiosk-type=public-browsing --no-first-run";
            Win32.STARTUPINFO si = new Win32.STARTUPINFO();
            si.cb = si.size();
            si.lpDesktop = new com.sun.jna.WString(desktopName);
            Win32.PROCESS_INFORMATION pi = new Win32.PROCESS_INFORMATION();
            boolean started = Win32.Kernel32.INSTANCE.CreateProcess(null, (cmd + "\0").toCharArray(),
                    com.sun.jna.Pointer.NULL, com.sun.jna.Pointer.NULL, false, 0, com.sun.jna.Pointer.NULL, null, si, pi);
            check("started Edge on the profile", started);
            if (!started) {
                return;
            }
            Win32.Kernel32.INSTANCE.CloseHandle(pi.hThread);
            Win32.Kernel32.INSTANCE.CloseHandle(pi.hProcess);
            edge = ProcessHandle.of(Integer.toUnsignedLong(pi.dwProcessId)).orElse(null);

            // Wait until Edge really holds files: a plain wipe must fail while it runs.
            long deadline = System.currentTimeMillis() + 30_000;
            boolean blocked = false;
            while (System.currentTimeMillis() < deadline && !blocked) {
                Thread.sleep(500);
                blocked = !canDeleteAll(profile);
            }
            check("running Edge really blocks deleting its profile (the reported error)", blocked);

            long t0 = System.currentTimeMillis();
            Path p = KioskProfile.prepare(profile);
            long ms = System.currentTimeMillis() - t0;
            Thread.sleep(500);
            check("left-over Edge ended, profile wiped, same folder reused",
                    p.equals(profile) && listing(profile).isEmpty() && (edge == null || !edge.isAlive()),
                    "returned " + p.getFileName() + ", Edge alive=" + (edge != null && edge.isAlive()) + ", took " + ms + " ms");
        } finally {
            if (edge != null) {
                edge.descendants().forEach(ProcessHandle::destroyForcibly);
                edge.destroyForcibly();
            }
            KioskProfile.endBrowsersUsing(profile);
            Thread.sleep(1000);
            if (desktop != null) {
                Win32.User32.INSTANCE.CloseDesktop(desktop);
            }
        }
    }

    /**
     * True if no file under the folder is held by another process - probed WITHOUT deleting anything:
     * Windows refuses to rename (like delete) a file another process has open without share-delete, so
     * renaming each file to a temporary name and straight back changes nothing but reveals a holder.
     */
    private static boolean canDeleteAll(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                Path tmp = p.resolveSibling(p.getFileName() + ".probe");
                try {
                    Files.move(p, tmp);
                    Files.move(tmp, p);
                } catch (IOException e) {
                    return false;
                }
            }
        }
        return true;
    }

    /** PowerShell holding the file exclusively; the path is literally on its command line. */
    private static Process holdOpenWithPathOnCommandLine(Path file) throws IOException {
        return new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                "$f=[IO.File]::Open('" + file + "','Open','Read','None'); Start-Sleep 120").start();
    }

    /** Same, but the path travels in the environment, so the command line gives nothing away. */
    private static Process holdOpenHiddenPath(Path file) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                "$f=[IO.File]::Open($env:KP,'Open','Read','None'); Start-Sleep 120");
        pb.environment().put("KP", file.toString());
        return pb.start();
    }

    /** True once the file can no longer be opened exclusively, i.e. the holder has it. */
    private static boolean waitUntilLocked(Path file) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            try (var ignored = new java.io.RandomAccessFile(file.toFile(), "rw")) {
                // Java can open it (share-delete), so this proves nothing about deletion - test deletion itself:
            } catch (IOException e) {
                return true;
            }
            try {
                Path probe = file.resolveSibling(file.getFileName() + ".probe");
                Files.copy(file, probe); // copying needs a read handle, which FileShare.None forbids
                Files.deleteIfExists(probe);
            } catch (IOException e) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    private static List<Path> listing(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.toList();
        }
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
