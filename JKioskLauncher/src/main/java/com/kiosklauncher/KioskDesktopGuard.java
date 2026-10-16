package com.kiosklauncher;

import com.sun.jna.Pointer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a dedicated thread attached to the kiosk desktop (via
 * SetThreadDesktop) for the entire time kiosk mode is active. That
 * attachment is what makes both of these actually receive input while the
 * kiosk desktop is showing:
 *
 *   1. the Ctrl+Alt+Shift+K exit hotkey.
 *   2. a low-level keyboard hook that swallows Alt+F4, so closing the
 *      kiosk browser window can't drop the customer onto a bare,
 *      shell-less desktop.
 *
 * A hook or hotkey registered from a thread on the operator's desktop
 * would go silent the moment we switch away from it - Windows deliberately
 * does not let hooks cross desktop boundaries (that's what broke the first
 * version of the C# app this was ported from).
 *
 * A plain `new Thread()` here is a real OS thread (the JVM does not use
 * green threads for these), so SetThreadDesktop pins exactly the thread we
 * expect it to - no extra work needed, unlike e.g. Go, where goroutines
 * need explicit LockOSThread() for this to be safe.
 */
final class KioskDesktopGuard {

    private static final int HOTKEY_ID = 1;

    private final Pointer desktop;
    private Thread thread;
    private final AtomicInteger threadId = new AtomicInteger(0);
    private final CountDownLatch ready = new CountDownLatch(1);

    // Keep a strong reference to the callback - JNA only holds a weak
    // reference internally, so a GC'd callback silently stops firing.
    private Win32.LowLevelKeyboardProc hookProc;
    private volatile Pointer hookHandle = Pointer.NULL;

    private volatile Runnable exitHandler;

    private final FileDialogWatchdog fileDialogWatchdog = new FileDialogWatchdog();

    /** Set when the guard thread could not set itself up; start() rethrows it. */
    private volatile RuntimeException failure;

    KioskDesktopGuard(Pointer desktop) {
        this.desktop = desktop;
    }

    void setExitHandler(Runnable handler) {
        this.exitHandler = handler;
    }

    void start() {
        thread = new Thread(this::runLoop, "KioskDesktopGuard");
        thread.setDaemon(true);
        thread.start();

        try {
            if (!ready.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Wątek strażnika kiosku nie wystartował w oczekiwanym czasie.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }

        RuntimeException error = failure;
        if (error != null) {
            throw error;
        }
    }

    private void runLoop() {
        if (!Win32.User32.INSTANCE.SetThreadDesktop(desktop)) {
            failure = new IllegalStateException("SetThreadDesktop nie powiodło się - strażnik kiosku nie działa.");
            ready.countDown();
            return;
        }

        threadId.set(Win32.Kernel32.INSTANCE.GetCurrentThreadId());

        // A message-only window (HWND_MESSAGE parent) is enough to receive
        // WM_HOTKEY; the built-in "STATIC" class needs no registration and
        // the window is never shown.
        Pointer messageWindow = Win32.User32.INSTANCE.CreateWindowEx(
                0, "STATIC", "", 0, 0, 0, 0, 0,
                Win32.HWND_MESSAGE, Pointer.NULL, Pointer.NULL, Pointer.NULL);

        Win32.User32.INSTANCE.RegisterHotKey(messageWindow, HOTKEY_ID,
                Win32.MOD_CONTROL | Win32.MOD_ALT | Win32.MOD_SHIFT, Win32.VK_K);

        hookProc = this::hookCallback;
        hookHandle = Win32.User32.INSTANCE.SetWindowsHookEx(Win32.WH_KEYBOARD_LL, hookProc, Pointer.NULL, 0);

        // Fail closed: without the dialog watchdog the customer could browse
        // and upload the operator's files, so don't let the kiosk start.
        try {
            fileDialogWatchdog.install();
        } catch (RuntimeException e) {
            failure = e;
            ready.countDown();
            cleanupHooks(messageWindow);
            return;
        }

        ready.countDown();

        Win32.MSG msg = new Win32.MSG();
        int result;
        while ((result = Win32.User32.INSTANCE.GetMessage(msg, Pointer.NULL, 0, 0)) > 0) {
            msg.read();
            if (msg.hwnd != null && messageWindow.equals(msg.hwnd) && msg.message == Win32.WM_HOTKEY) {
                Runnable handler = exitHandler;
                if (handler != null) {
                    handler.run();
                }
            }
            Win32.User32.INSTANCE.TranslateMessage(msg);
            Win32.User32.INSTANCE.DispatchMessage(msg);
        }

        cleanupHooks(messageWindow);
    }

    private void cleanupHooks(Pointer messageWindow) {
        fileDialogWatchdog.uninstall();
        // Note: in JNA, Pointer.NULL is a null reference, not a Pointer object.
        if (hookHandle != null) {
            Win32.User32.INSTANCE.UnhookWindowsHookEx(hookHandle);
        }
        Win32.User32.INSTANCE.UnregisterHotKey(messageWindow, HOTKEY_ID);
    }

    private Pointer hookCallback(int nCode, Pointer wParam, Pointer lParam) {
        if (nCode >= 0 && (Pointer.nativeValue(wParam) == Win32.WM_KEYDOWN
                || Pointer.nativeValue(wParam) == Win32.WM_SYSKEYDOWN)) {
            Win32.KBDLLHOOKSTRUCT data = new Win32.KBDLLHOOKSTRUCT(lParam);
            boolean altDown = (Win32.User32.INSTANCE.GetAsyncKeyState(Win32.VK_MENU) & 0x8000) != 0;
            boolean ctrlDown = (Win32.User32.INSTANCE.GetAsyncKeyState(Win32.VK_CONTROL) & 0x8000) != 0;

            if (altDown && data.vkCode == Win32.VK_F4) {
                return new Pointer(1); // swallow Alt+F4
            }
            // Ctrl+O / Ctrl+S would open a file dialog; swallowing them just
            // avoids a flash of the dialog - FileDialogWatchdog is what
            // actually guarantees it (menus and buttons open it too).
            if (ctrlDown && (data.vkCode == Win32.VK_O || data.vkCode == Win32.VK_S)) {
                return new Pointer(1);
            }
        }

        return Win32.User32.INSTANCE.CallNextHookEx(hookHandle, nCode, wParam, lParam);
    }

    void stop() {
        int id = threadId.get();
        if (id != 0) {
            Win32.User32.INSTANCE.PostThreadMessage(id, Win32.WM_QUIT, Pointer.NULL, Pointer.NULL);
        }
        if (thread != null) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        threadId.set(0);
    }
}
