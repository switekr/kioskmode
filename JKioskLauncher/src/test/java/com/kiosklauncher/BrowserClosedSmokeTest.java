package com.kiosklauncher;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The customer closes the browser window (Ctrl+W on the last tab): Edge's main process ends but its
 * helper processes (crashpad, GPU, ...) can outlive it, and nothing is left to put the screen right.
 *
 *   - the launcher must notice the window is gone and end the session (handler fires);
 *   - ending must leave NOTHING behind: no browser process on the profile, no kiosk desktop - a
 *     leftover keeps the desktop alive without any guard (no exit hotkey) and locks the profile.
 *
 * TAKES OVER THE REAL SCREEN for about 10 s (a rescue process switches back even if this hangs);
 * never locks the workstation.
 *
 * Run: java -cp "target/JKioskLauncher.jar;target/test-classes" com.kiosklauncher.BrowserClosedSmokeTest
 */
public final class BrowserClosedSmokeTest {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("rescue")) {
            Thread.sleep(Long.parseLong(args[1]) * 1000);
            KioskSession.switchTo("Default");
            return;
        }
        String javaExe = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        Process rescue = new ProcessBuilder(javaExe, "-cp", System.getProperty("java.class.path"),
                "com.kiosklauncher.BrowserClosedSmokeTest", "rescue", "45").inheritIO().start();

        boolean ok = false;
        try {
            KioskConfig config = new KioskConfig();
            config.url = "about:blank";
            KioskController controller = new KioskController();
            AtomicBoolean closedNoticed = new AtomicBoolean();
            controller.setBrowserClosedHandler(() -> closedNoticed.set(true));
            AtomicBoolean hotkey = new AtomicBoolean();
            controller.setExitRequestedHandler(() -> hotkey.set(true));

            java.util.Set<Long> before = new java.util.HashSet<>();
            ProcessHandle.allProcesses().forEach(h -> before.add(h.pid()));
            controller.start(config);
            Thread.sleep(4000);

            // "the window was closed": only the browser's main process ends, helpers live on
            ProcessHandle root = ProcessHandle.current().children()
                    .filter(p -> p.info().command().map(c -> c.toLowerCase().contains("msedge")
                            || c.toLowerCase().contains("chrome")).orElse(false))
                    .findFirst().orElse(null);
            check("found the browser's main process", root != null);
            long helpers = root.descendants().count();
            root.destroyForcibly();
            Thread.sleep(2500);
            System.out.println("      helpers that outlived the main process: "
                    + countOnProfile() + " (they had " + helpers + " descendants)");

            boolean noticed = closedNoticed.get();
            check("launcher noticed the browser window is gone", noticed);

            // the guard must still answer on the now empty kiosk desktop (nothing else to leave through)
            SwitchBackSmokeTest.injectCombo();
            Thread.sleep(1500);
            check("exit hotkey still works on the empty kiosk desktop", hotkey.get());

            KioskSession.switchTo("Default");
            controller.stop(false); // what the exit/auto-close does, minus the lock
            long t0 = System.currentTimeMillis();
            while (KioskSession.exists(KioskController.DESKTOP_NAME) && System.currentTimeMillis() - t0 < 20000) {
                Thread.sleep(250);
            }
            System.out.println("      desktop gone after " + (System.currentTimeMillis() - t0) + " ms");
            if (KioskSession.exists(KioskController.DESKTOP_NAME)) {
                ProcessHandle.allProcesses().filter(h -> !before.contains(h.pid())).forEach(h ->
                        System.out.println("      NEW process " + h.pid() + " " + h.info().command().orElse("?")
                                + " parent=" + h.parent().map(ProcessHandle::pid).orElse(-1L)));
                Thread.getAllStackTraces().forEach((t, st) -> {

                });
            }
            boolean none = countOnProfile() == 0;
            boolean gone = !KioskSession.exists(KioskController.DESKTOP_NAME);
            new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "Get-Process msedge,chrome -ErrorAction SilentlyContinue | Select-Object Id,ProcessName,StartTime | Format-Table -AutoSize | Out-String")
                    .inheritIO().start().waitFor();
            check("no browser process left on the profile after ending", none, "left=" + countOnProfile());
            check("kiosk desktop is gone after ending", gone);
            ok = noticed && none && gone;
        } finally {
            KioskSession.switchTo("Default");
            KioskSession.end(KioskController.DESKTOP_NAME, KioskProfile.defaultProfile());
            rescue.destroy();
        }
        System.out.println(ok ? "ALL PASSED" : "FAILED");
        System.exit(ok ? 0 : 1);
    }

    private static long countOnProfile() throws Exception {
        Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                "(Get-CimInstance Win32_Process | ? { $_.CommandLine -like '*JKioskLauncher*profile*' "
                        + "-and $_.Name -match 'msedge|chrome' } | measure).Count")
                .start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return Long.parseLong(out);
    }

    private static void check(String what, boolean ok) {
        check(what, ok, "");
    }

    private static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what + (detail.isEmpty() ? "" : "  [" + detail + "]"));
    }
}
