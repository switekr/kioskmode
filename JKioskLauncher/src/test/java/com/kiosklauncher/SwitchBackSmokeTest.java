package com.kiosklauncher;

import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reproduces: kiosk session alive, operator ends up on the normal desktop (Ctrl+Alt+Del -> Task
 * Manager does that), a second launcher uses "Przełącz" -> does Ctrl+Alt+Shift+K still reach the
 * first launcher's guard?
 *
 * TAKES OVER THE REAL SCREEN for about 15 s (a separate rescue process switches back to the normal
 * desktop even if this one hangs). It never locks the workstation - the exit handler only logs.
 *
 * Run: java -cp "target/JKioskLauncher.jar;target/test-classes" com.kiosklauncher.SwitchBackSmokeTest
 */
public final class SwitchBackSmokeTest {

    private interface Keys extends StdCallLibrary {
        Keys I = com.sun.jna.Native.load("user32", Keys.class, W32APIOptions.UNICODE_OPTIONS);

        void keybd_event(byte vk, byte scan, int flags, Pointer extra);

        Pointer OpenDesktop(String name, int flags, boolean inherit, int access);

        boolean CloseDesktop(Pointer desktop);

        boolean SetThreadDesktop(Pointer desktop);
    }

    /** Types Ctrl+Alt+Shift+K the way hardware would reach the input desktop: from a thread attached to it. */
    static void injectCombo() throws Exception {
        Pointer d = Keys.I.OpenDesktop(KioskController.DESKTOP_NAME, 0, false, 0x10000000);
        Thread t = new Thread(() -> {
            System.out.println("inject: opened=" + (d != null) + " attached=" + (d != null && Keys.I.SetThreadDesktop(d)));
            int[] vks = {0x11, 0x12, 0x10, 0x4B};
            for (int vk : vks) {
                Keys.I.keybd_event((byte) vk, (byte) 0, 0, null);
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
            }
            for (int i = vks.length - 1; i >= 0; i--) {
                Keys.I.keybd_event((byte) vks[i], (byte) 0, 2, null);
            }
        });
        t.start();
        t.join();
        Keys.I.CloseDesktop(d); // a leaked handle keeps the desktop alive and would fake a "leftover session"
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("rescue")) {
            Thread.sleep(Long.parseLong(args[1]) * 1000);
            KioskSession.switchTo("Default");
            return;
        }
        if (args.length > 0 && args[0].equals("switch")) {
            // the second launcher's "Przełącz"
            System.out.println("switchTo -> " + KioskSession.switchTo(KioskController.DESKTOP_NAME));
            return;
        }

        Path log = Path.of(System.getProperty("java.io.tmpdir"), "switchback-hotkey.log");
        Files.deleteIfExists(log);
        String javaExe = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        String cp = System.getProperty("java.class.path");

        Process rescue = new ProcessBuilder(javaExe, "-cp", cp, "com.kiosklauncher.SwitchBackSmokeTest", "rescue", "40")
                .inheritIO().start();

        KioskConfig config = new KioskConfig();
        config.url = "about:blank";
        KioskController controller = new KioskController();
        controller.setExitRequestedHandler(() -> {
            try {
                Files.writeString(log, "HOTKEY " + System.currentTimeMillis() + "\n",
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception ignored) {
            }
        });

        try {
            controller.start(config);                     // screen -> kiosk desktop
            Thread.sleep(5000);
            System.out.println("1. kiosk started and visible");
            if (!(args.length > 0 && args[0].equals("control"))) {   // control run: no round trip
                System.out.println("2. back to Default (like Task Manager): " + KioskSession.switchTo("Default"));
                Thread.sleep(2000);
                Process sw = new ProcessBuilder(javaExe, "-cp", cp, "com.kiosklauncher.SwitchBackSmokeTest", "switch")
                        .inheritIO().start();             // 3. second launcher: Przełącz
                sw.waitFor();
                Thread.sleep(3000);
            }

            injectCombo();
            Thread.sleep(2500);

            boolean fired = Files.exists(log);
            System.out.println(fired ? "PASS  exit hotkey reached the guard after Przełącz"
                    : "FAIL  exit hotkey did NOT reach the guard after Przełącz");
            // the normal exit (minus the lock): is the kiosk desktop really gone afterwards?
            controller.stop(false);
            long t0 = System.currentTimeMillis();
            while (KioskSession.exists(KioskController.DESKTOP_NAME) && System.currentTimeMillis() - t0 < 20000) {
                Thread.sleep(250);
            }
            System.out.println("desktop gone after " + (System.currentTimeMillis() - t0) + " ms");
            boolean gone = !KioskSession.exists(KioskController.DESKTOP_NAME);
            System.out.println(gone ? "PASS  desktop gone after stop()"
                    : "FAIL  desktop still exists after stop() - a leftover session");
            if (!gone) {
                new ProcessBuilder("powershell", "-NoProfile", "-Command",
                        "Get-CimInstance Win32_Process | ? { $_.CommandLine -like '*JKioskLauncher*profile*' } | "
                                + "Select-Object ProcessId,Name,ParentProcessId | Format-Table -AutoSize | Out-String")
                        .inheritIO().start().waitFor();
            }
            KioskSession.end(KioskController.DESKTOP_NAME, KioskProfile.defaultProfile());
            System.exit(fired && gone ? 0 : 1);
        } finally {
            KioskSession.switchTo("Default");
            rescue.destroy();
        }
    }
}
