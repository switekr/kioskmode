package com.kiosklauncher;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.util.Arrays;
import java.util.List;

/**
 * JNA declarations for the Win32 desktop-object and low-level keyboard
 * hook APIs this app is built around. A "desktop" here is the
 * window-station object (CreateDesktop/SwitchDesktop) - the same isolation
 * primitive Windows uses for the secure UAC prompt: windows created on one
 * desktop are invisible and unreachable (Alt+Tab, taskbar, window
 * enumeration) from another desktop in the same session.
 *
 * Direct port of NativeMethods.cs from the C# version - same functions,
 * same constants, same struct layouts.
 */
public final class Win32 {

    private Win32() {
    }

    // ---- constants ---------------------------------------------------

    public static final int GENERIC_ALL = 0x10000000;
    public static final int DESKTOP_SWITCHDESKTOP = 0x0100;

    public static final int STARTF_USESHOWWINDOW = 0x00000001;
    public static final short SW_MAXIMIZE = 3;

    public static final int WH_KEYBOARD_LL = 13;
    public static final int WM_KEYDOWN = 0x0100;
    public static final int WM_SYSKEYDOWN = 0x0104;
    public static final int WM_HOTKEY = 0x0312;
    public static final int WM_QUIT = 0x0012;

    public static final int VK_MENU = 0x12;   // Alt
    public static final int VK_CONTROL = 0x11;
    public static final int VK_SHIFT = 0x10;
    public static final int VK_K = 0x4B;
    public static final int VK_F4 = 0x73;

    public static final int MOD_ALT = 0x0001;
    public static final int MOD_CONTROL = 0x0002;
    public static final int MOD_SHIFT = 0x0004;

    /** (HWND)-3 - marks a message-only window; never shown, needs no registered class. */
    public static final Pointer HWND_MESSAGE = new Pointer(-3);

    // ---- structs -------------------------------------------------------

    public static class STARTUPINFO extends Structure {
        public int cb;
        public WString lpReserved;
        public WString lpDesktop;
        public WString lpTitle;
        public int dwX;
        public int dwY;
        public int dwXSize;
        public int dwYSize;
        public int dwXCountChars;
        public int dwYCountChars;
        public int dwFillAttribute;
        public int dwFlags;
        public short wShowWindow;
        public short cbReserved2;
        public Pointer lpReserved2;
        public Pointer hStdInput;
        public Pointer hStdOutput;
        public Pointer hStdError;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("cb", "lpReserved", "lpDesktop", "lpTitle", "dwX", "dwY", "dwXSize", "dwYSize",
                    "dwXCountChars", "dwYCountChars", "dwFillAttribute", "dwFlags", "wShowWindow", "cbReserved2",
                    "lpReserved2", "hStdInput", "hStdOutput", "hStdError");
        }
    }

    public static class PROCESS_INFORMATION extends Structure {
        public Pointer hProcess;
        public Pointer hThread;
        public int dwProcessId;
        public int dwThreadId;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("hProcess", "hThread", "dwProcessId", "dwThreadId");
        }
    }

    /** Overlays an existing native KBDLLHOOKSTRUCT (owned by the OS) - never allocate one yourself. */
    public static class KBDLLHOOKSTRUCT extends Structure {
        public int vkCode;
        public int scanCode;
        public int flags;
        public int time;
        public Pointer dwExtraInfo;

        public KBDLLHOOKSTRUCT(Pointer p) {
            super(p);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("vkCode", "scanCode", "flags", "time", "dwExtraInfo");
        }
    }

    public static class MSG extends Structure {
        public Pointer hwnd;
        public int message;
        public Pointer wParam;
        public Pointer lParam;
        public int time;
        public int ptX;
        public int ptY;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("hwnd", "message", "wParam", "lParam", "time", "ptX", "ptY");
        }
    }

    // ---- hook callback ---------------------------------------------------

    public interface LowLevelKeyboardProc extends StdCallLibrary.StdCallCallback {
        Pointer callback(int nCode, Pointer wParam, Pointer lParam);
    }

    // ---- user32.dll --------------------------------------------------------

    public interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class, W32APIOptions.UNICODE_OPTIONS);

        Pointer CreateDesktop(String lpszDesktop, Pointer lpszDevice, Pointer pDevmode, int dwFlags,
                               int dwDesiredAccess, Pointer lpsa);

        Pointer OpenDesktop(String lpszDesktop, int dwFlags, boolean fInherit, int dwDesiredAccess);

        boolean SwitchDesktop(Pointer hDesktop);

        boolean CloseDesktop(Pointer hDesktop);

        // Associates the calling thread with a desktop. Low-level keyboard
        // hooks and RegisterHotKey are scoped to "whatever desktop the
        // installing thread is attached to" - this is what lets a thread
        // actually receive input while a non-default desktop is active
        // (Windows deliberately does NOT let hooks cross desktop
        // boundaries; that's the same protection that keeps the secure UAC
        // desktop safe from keyloggers on the normal desktop).
        boolean SetThreadDesktop(Pointer hDesktop);

        // Locks the whole session (same as Win+L) - the operator must enter
        // their Windows password to get back, instead of the kiosk just
        // handing the unlocked desktop straight back to whoever is standing
        // there.
        boolean LockWorkStation();

        boolean RegisterHotKey(Pointer hWnd, int id, int fsModifiers, int vk);

        boolean UnregisterHotKey(Pointer hWnd, int id);

        Pointer SetWindowsHookEx(int idHook, LowLevelKeyboardProc lpfn, Pointer hMod, int dwThreadId);

        boolean UnhookWindowsHookEx(Pointer hhk);

        Pointer CallNextHookEx(Pointer hhk, int nCode, Pointer wParam, Pointer lParam);

        short GetAsyncKeyState(int vKey);

        int GetMessage(MSG lpMsg, Pointer hWnd, int wMsgFilterMin, int wMsgFilterMax);

        boolean TranslateMessage(MSG lpMsg);

        Pointer DispatchMessage(MSG lpMsg);

        boolean PostThreadMessage(int idThread, int msg, Pointer wParam, Pointer lParam);

        Pointer CreateWindowEx(int dwExStyle, String lpClassName, String lpWindowName, int dwStyle,
                                int x, int y, int nWidth, int nHeight,
                                Pointer hWndParent, Pointer hMenu, Pointer hInstance, Pointer lpParam);
    }

    // ---- kernel32.dll --------------------------------------------------------

    public interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class, W32APIOptions.UNICODE_OPTIONS);

        // lpCommandLine must be a mutable buffer - CreateProcess writes into
        // it. A plain Java String would be immutable/interned, so this
        // takes a char[] instead (JNA marshals char[] as a mutable native
        // wchar_t* buffer).
        boolean CreateProcess(String lpApplicationName, char[] lpCommandLine, Pointer lpProcessAttributes,
                               Pointer lpThreadAttributes, boolean bInheritHandles, int dwCreationFlags,
                               Pointer lpEnvironment, String lpCurrentDirectory,
                               STARTUPINFO lpStartupInfo, PROCESS_INFORMATION lpProcessInformation);

        boolean CloseHandle(Pointer hObject);

        int GetCurrentThreadId();

        Pointer CreateMutex(Pointer lpMutexAttributes, boolean bInitialOwner, String lpName);
    }
}
