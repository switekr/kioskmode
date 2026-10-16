package com.kiosklauncher;

import com.sun.jna.Pointer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Closes native "open/save file" dialogs the moment they appear on the
 * kiosk desktop, without the customer ever seeing them.
 *
 * Why this exists: the kiosk browser runs as the operator's own account, so
 * a file dialog (webmail "attach", Ctrl+O, "Save as") would show - and let
 * the customer upload - anything in the operator's profile. Edge kiosk mode
 * does not lock these dialogs down on its own, and the Edge policy that
 * does (AllowFileSelectionDialogs) is machine-wide, so it would also break
 * uploads in the operator's own Edge on the same PC.
 *
 * How: out-of-context WinEvent hooks installed from the guard thread, which
 * is already attached to the kiosk desktop and pumps messages - both are
 * required for the callbacks (and the timer) to fire. Two steps:
 *
 *   1. EVENT_OBJECT_CREATE of a top-level "#32770" dialog: make it fully
 *      transparent right away. This beats the first show of the frame, so
 *      there is nothing to flash. We can't tell yet whether it is a file
 *      dialog - the frame has no children when it is created.
 *   2. EVENT_OBJECT_SHOW of the file-view control inside it (DirectUIHWND
 *      for the Vista-style IFileDialog, SHELLDLL_DefView for the classic
 *      GetOpenFileName): it is a file dialog, so hide it and send WM_CLOSE,
 *      which common dialogs treat as Cancel - to the browser it looks like
 *      the user dismissed it.
 *
 * Matching is by window class, not title, so it is independent of the system
 * language. Any other native dialog (e.g. system print) is made visible
 * again after RESTORE_AFTER_MS, so it is merely delayed, never lost.
 *
 * If the file view shows up after RESTORE_AFTER_MS (very slow machine) the
 * dialog is still closed, it just may have been visible briefly.
 *
 * Not covered: file:/// navigation in the address bar (needs a URL policy).
 */
final class FileDialogWatchdog {

    private static final String DIALOG_CLASS = "#32770";
    private static final long RESTORE_AFTER_MS = 1500;
    private static final int TIMER_INTERVAL_MS = 100;

    /** A dialog we made transparent and haven't classified yet. */
    private static final class Pending {
        final Pointer hwnd;
        final long createdAtMs = System.currentTimeMillis();
        /** False if the window already had WS_EX_LAYERED, so we must not strip it on restore. */
        final boolean addedLayeredStyle;

        Pending(Pointer hwnd, boolean addedLayeredStyle) {
            this.hwnd = hwnd;
            this.addedLayeredStyle = addedLayeredStyle;
        }
    }

    // Strong references - JNA only keeps weak ones, and a collected callback
    // silently stops firing.
    private Win32.WinEventProc eventProc;
    private Win32.TimerProc timerProc;
    private Pointer hook;
    private Pointer timer;

    // Only ever touched from the guard thread (callbacks and timer run there).
    private final List<Pending> pending = new ArrayList<>();

    /** Must be called on the thread that pumps messages for the kiosk desktop. */
    void install() {
        eventProc = this::onEvent;
        hook = Win32.User32.INSTANCE.SetWinEventHook(
                Win32.EVENT_OBJECT_CREATE, Win32.EVENT_OBJECT_SHOW,
                Pointer.NULL, eventProc, 0, 0, Win32.WINEVENT_OUTOFCONTEXT);
        if (hook == null) {
            throw new IllegalStateException("SetWinEventHook nie powiodło się - okna wyboru pliku nie będą blokowane.");
        }

        timerProc = this::onTimer;
        timer = Win32.User32.INSTANCE.SetTimer(Pointer.NULL, Pointer.NULL, TIMER_INTERVAL_MS, timerProc);
        if (timer == null) {
            uninstall();
            throw new IllegalStateException("SetTimer nie powiodło się - strażnik okien wyboru pliku nie działa.");
        }
    }

    void uninstall() {
        if (timer != null) {
            Win32.User32.INSTANCE.KillTimer(Pointer.NULL, timer);
            timer = null;
        }
        if (hook != null) {
            Win32.User32.INSTANCE.UnhookWinEvent(hook);
            hook = null;
        }
        pending.clear();
    }

    private void onEvent(Pointer hWinEventHook, int event, Pointer hwnd, int idObject, int idChild,
                         int idEventThread, int dwmsEventTime) {
        if (hwnd == null || idObject != Win32.OBJID_WINDOW) {
            return;
        }

        if (event == Win32.EVENT_OBJECT_CREATE) {
            onCreate(hwnd);
        } else if (event == Win32.EVENT_OBJECT_SHOW) {
            onShow(hwnd);
        }
    }

    private void onCreate(Pointer hwnd) {
        if (!DIALOG_CLASS.equals(className(hwnd)) || !isTopLevel(hwnd)) {
            return;
        }

        int exStyle = Win32.User32.INSTANCE.GetWindowLong(hwnd, Win32.GWL_EXSTYLE);
        boolean added = (exStyle & Win32.WS_EX_LAYERED) == 0;
        if (added) {
            Win32.User32.INSTANCE.SetWindowLong(hwnd, Win32.GWL_EXSTYLE, exStyle | Win32.WS_EX_LAYERED);
        }
        Win32.User32.INSTANCE.SetLayeredWindowAttributes(hwnd, 0, (byte) 0, Win32.LWA_ALPHA);
        pending.add(new Pending(hwnd, added));
    }

    private void onShow(Pointer hwnd) {
        String cls = className(hwnd);
        if (!"DirectUIHWND".equals(cls) && !"SHELLDLL_DefView".equals(cls)) {
            return;
        }

        Pointer root = Win32.User32.INSTANCE.GetAncestor(hwnd, Win32.GA_ROOT);
        if (root == null || !DIALOG_CLASS.equals(className(root))) {
            return;
        }

        pending.removeIf(p -> p.hwnd.equals(root));
        Win32.User32.INSTANCE.ShowWindow(root, Win32.SW_HIDE);
        Win32.User32.INSTANCE.PostMessage(root, Win32.WM_CLOSE, Pointer.NULL, Pointer.NULL);
    }

    /** Gives back visibility to dialogs that turned out not to be file dialogs. */
    private void onTimer(Pointer hwnd, int uMsg, Pointer idEvent, int dwTime) {
        long now = System.currentTimeMillis();
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (!Win32.User32.INSTANCE.IsWindow(p.hwnd)) {
                it.remove();
            } else if (now - p.createdAtMs >= RESTORE_AFTER_MS) {
                restore(p);
                it.remove();
            }
        }
    }

    private static void restore(Pending p) {
        Win32.User32.INSTANCE.SetLayeredWindowAttributes(p.hwnd, 0, (byte) 255, Win32.LWA_ALPHA);
        if (p.addedLayeredStyle) {
            int exStyle = Win32.User32.INSTANCE.GetWindowLong(p.hwnd, Win32.GWL_EXSTYLE);
            Win32.User32.INSTANCE.SetWindowLong(p.hwnd, Win32.GWL_EXSTYLE, exStyle & ~Win32.WS_EX_LAYERED);
        }
    }

    private static boolean isTopLevel(Pointer hwnd) {
        return hwnd.equals(Win32.User32.INSTANCE.GetAncestor(hwnd, Win32.GA_ROOT));
    }

    private static String className(Pointer hwnd) {
        char[] buf = new char[64];
        int len = Win32.User32.INSTANCE.GetClassName(hwnd, buf, buf.length);
        return len > 0 ? new String(buf, 0, len) : "";
    }
}
