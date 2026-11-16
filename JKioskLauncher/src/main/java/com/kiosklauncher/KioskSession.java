package com.kiosklauncher;

import com.sun.jna.Pointer;

import java.nio.file.Path;

/**
 * Is a kiosk session already there, and what can be done about it from another launcher process?
 *
 * The kiosk desktop is the evidence. It exists for as long as anything uses it - the launcher's
 * guard, or the browser it holds - so it shows a live session AND one orphaned when the launcher
 * died (killed from Task Manager, the documented emergency exit) while its browser carried on.
 */
final class KioskSession {

    private static final long END_WAIT_MS = 10_000;

    private KioskSession() {
    }

    /** True if a desktop of that name exists in this window station. */
    static boolean exists(String desktopName) {
        Pointer desktop = Win32.User32.INSTANCE.OpenDesktop(desktopName, 0, false, Win32.DESKTOP_SWITCHDESKTOP);
        if (desktop == null) {
            return false;
        }
        Win32.User32.INSTANCE.CloseDesktop(desktop);
        return true;
    }

    /**
     * Puts the screen on that desktop. Only meant for a session whose launcher is alive: its guard is
     * armed, so Ctrl+Alt+Shift+K works. An orphaned browser has no guard - nobody could get back out.
     */
    static boolean switchTo(String desktopName) {
        Pointer desktop = Win32.User32.INSTANCE.OpenDesktop(desktopName, 0, false, Win32.DESKTOP_SWITCHDESKTOP);
        if (desktop == null) {
            return false;
        }
        try {
            return Win32.User32.INSTANCE.SwitchDesktop(desktop);
        } finally {
            Win32.User32.INSTANCE.CloseDesktop(desktop);
        }
    }

    /**
     * Ends whatever still runs on the session's profile and waits until the desktop is gone.
     * Safe for the operator's own Edge - only processes carrying our profile path are touched.
     *
     * @return true if the desktop is gone afterwards.
     */
    static boolean end(String desktopName, Path profile) {
        KioskProfile.endBrowsersUsing(profile);
        long deadline = System.currentTimeMillis() + END_WAIT_MS;
        while (exists(desktopName) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return !exists(desktopName);
    }
}
