package com.kiosklauncher;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Does a download in the REAL Edge kiosk mode end up in the one folder, with no
 * dialog left for the customer? Starts Edge with exactly the flags the app
 * uses, on a throwaway desktop (no screen takeover), with the guard
 * (FileDialogWatchdog) running, pointed at a local HTTP server that forces a
 * download.
 *
 * The two FileModes (KioskConfig.FileMode):
 *   - DOWNLOAD (guard given a download folder): the file must appear in that
 *     folder, complete, and nowhere else (not in the system Downloads, not in
 *     Edge's own KioskDownloads), and no dialog may be left on the screen.
 *   - BLOCK (guard given no folder, so it cancels every dialog): the file must
 *     appear NOWHERE - Edge kiosk mode shows a "Save as" dialog for every
 *     download and a cancelled one never happens - and no dialog may be left
 *     either. The DOWNLOAD run proves a landed file would be detected, so a
 *     "nothing landed" result here means something.
 *
 * Optional: -Dext=pdf (other file type), -DviaPage=true (download started by a
 * page script instead of by the first navigation).
 *
 * Run: java -cp "target/JKioskLauncher.jar;target/test-classes" com.kiosklauncher.DownloadFolderSmokeTest
 */
public final class DownloadFolderSmokeTest {

    private static final String DESKTOP_NAME = "KioskDownloadTest";
    private static final long WAIT_REDIRECT_MS = 45_000;
    private static final long WAIT_CONTROL_MS = 20_000;

    public static void main(String[] args) throws Exception {
        String browser = BrowserLocator.find();
        if (browser == null) {
            System.out.println("No Edge/Chrome found - cannot run.");
            System.exit(2);
        }

        boolean download = run(browser, Mode.DOWNLOAD);
        boolean block = run(browser, Mode.BLOCK);

        boolean all = download && block;
        System.out.println(all ? "ALL PASSED" : "FAILED");
        System.exit(all ? 0 : 1);
    }

    private enum Mode {
        /** FileMode "download": the download lands in the one folder. */
        DOWNLOAD,
        /** FileMode "block": every dialog cancelled, so no download ever happens. */
        BLOCK
    }

    private interface U32 extends com.sun.jna.win32.StdCallLibrary {
        U32 I = com.sun.jna.Native.load("user32", U32.class, com.sun.jna.win32.W32APIOptions.UNICODE_OPTIONS);

        Pointer FindWindow(String className, String windowName);

        boolean SetThreadDesktop(Pointer hDesktop);
    }

    /** Is a native dialog (#32770) open on that desktop? FindWindow only sees the calling thread's desktop. */
    private static boolean dialogOpen(Pointer desktop) throws InterruptedException {
        boolean[] result = new boolean[1];
        Thread t = new Thread(() -> {
            if (U32.I.SetThreadDesktop(desktop)) {
                result[0] = U32.I.FindWindow("#32770", null) != null;
            }
        });
        t.start();
        t.join(3000);
        return result[0];
    }

    /** @return true if the run behaved as expected for its kind. */
    private static boolean run(String browser, Mode mode) throws Exception {
        boolean redirect = mode == Mode.DOWNLOAD;
        String fileName = "kiosktest-" + UUID.randomUUID() + "." + System.getProperty("ext", "bin");
        boolean viaPage = "true".equals(System.getProperty("viaPage"));

        Path work = Files.createTempDirectory("kiosk-dl-test-");
        Path pinned = work.resolve("pinned-downloads");
        Path profile = work.resolve("profile");
        Path inSystem = Path.of(System.getenv("USERPROFILE"), "Downloads", fileName);
        Path edgeKioskDir = Path.of(System.getenv("LOCALAPPDATA"), "Microsoft", "Edge", "KioskDownloads");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = new byte[2048];
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/" + fileName, ex -> {
            hits.incrementAndGet();
            ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
            ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/page.html", ex -> {
            byte[] html = ("<html><body>x<script>setTimeout(function(){location.href='/" + fileName
                    + "'},1500)</script></body></html>").getBytes();
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, html.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(html);
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + (viaPage ? "/page.html" : "/" + fileName);

        Pointer desktop = Win32.User32.INSTANCE.CreateDesktop(
                DESKTOP_NAME, Pointer.NULL, Pointer.NULL, 0, Win32.GENERIC_ALL, Pointer.NULL);
        KioskDesktopGuard guard = null;
        ProcessHandle browserProcess = null;
        try {
            if (desktop == null) {
                System.out.println("ERROR: CreateDesktop");
                return false;
            }
            KioskProfile.prepare(profile);

            guard = new KioskDesktopGuard(desktop, redirect ? pinned : null);
            guard.start();

            String commandLine = "\"" + browser + "\" --kiosk \"" + url + "\""
                    + " --user-data-dir=\"" + profile + "\""
                    + " --edge-kiosk-type=public-browsing --no-first-run --start-maximized";
            Win32.STARTUPINFO si = new Win32.STARTUPINFO();
            si.cb = si.size();
            si.lpDesktop = new WString(DESKTOP_NAME);
            Win32.PROCESS_INFORMATION pi = new Win32.PROCESS_INFORMATION();
            if (!Win32.Kernel32.INSTANCE.CreateProcess(null, (commandLine + "\0").toCharArray(), Pointer.NULL,
                    Pointer.NULL, false, 0, Pointer.NULL, null, si, pi)) {
                System.out.println("ERROR: CreateProcess");
                return false;
            }
            Win32.Kernel32.INSTANCE.CloseHandle(pi.hThread);
            Win32.Kernel32.INSTANCE.CloseHandle(pi.hProcess);
            browserProcess = ProcessHandle.of(Integer.toUnsignedLong(pi.dwProcessId)).orElse(null);

            // The control run is expected to see nothing, so it can't end early - it just waits.
            long deadline = System.currentTimeMillis() + (redirect ? WAIT_REDIRECT_MS : WAIT_CONTROL_MS);
            Path inPinned = pinned.resolve(fileName);
            while (System.currentTimeMillis() < deadline) {
                if (redirect && Files.exists(inPinned) && Files.size(inPinned) == body.length) {
                    break;
                }
                Thread.sleep(250);
            }

            boolean landedPinned = Files.exists(inPinned) && Files.size(inPinned) == body.length;
            boolean landedSystem = Files.exists(inSystem);
            boolean landedEdgeDir = Files.isDirectory(edgeKioskDir) && listing(edgeKioskDir).contains(fileName);
            boolean nothingLanded = !landedPinned && !landedSystem && !landedEdgeDir && hits.get() > 0;
            // In neither mode may the customer be left looking at a dialog.
            boolean dialogLeft = dialogOpen(desktop);
            boolean ok = !dialogLeft && switch (mode) {
                case DOWNLOAD -> landedPinned && !landedSystem && !landedEdgeDir;
                case BLOCK -> nothingLanded;
            };
            System.out.printf("[%-8s] server hits=%d pinned=%b system=%b edgeKioskDir=%b dialogLeft=%b -> %s%n",
                    mode, hits.get(), landedPinned, landedSystem, landedEdgeDir, dialogLeft, ok ? "PASS" : "FAIL");
            return ok;
        } finally {
            if (browserProcess != null) {
                browserProcess.descendants().forEach(ProcessHandle::destroyForcibly);
                browserProcess.destroyForcibly();
            }
            killBrowsersUsing(profile); // belt and braces for anything that escaped the tree
            if (guard != null) {
                guard.stop();
            }
            if (desktop != null) {
                Win32.User32.INSTANCE.CloseDesktop(desktop);
            }
            server.stop(0);
            Files.deleteIfExists(inSystem);
            deleteQuietly(work);
        }
    }

    private static String listing(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).toList().toString();
        } catch (IOException e) {
            return "";
        }
    }

    /** Kills only browser processes started with our throwaway profile - never the operator's own Edge. */
    private static void killBrowsersUsing(Path profile) throws InterruptedException {
        String marker = profile.toString();
        ProcessHandle.allProcesses()
                .filter(p -> p.info().commandLine().map(c -> c.contains(marker)).orElse(false))
                .forEach(ProcessHandle::destroyForcibly);
        TimeUnit.MILLISECONDS.sleep(1500); // let Windows release the profile's file handles
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort - it's a temp dir
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
