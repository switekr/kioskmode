using System.Runtime.InteropServices;

namespace KioskLauncher;

/// <summary>
/// P/Invoke declarations for the Win32 desktop-object and low-level
/// keyboard hook APIs this app is built around. A "desktop" here is the
/// window-station object (CreateDesktop/SwitchDesktop) - the same
/// isolation primitive Windows uses for the secure UAC prompt: windows
/// created on one desktop are invisible and unreachable (Alt+Tab, taskbar,
/// window enumeration) from another desktop in the same session.
/// </summary>
internal static class NativeMethods
{
    public const uint DESKTOP_CREATEWINDOW = 0x0002;
    public const uint DESKTOP_SWITCHDESKTOP = 0x0100;
    public const uint DESKTOP_ENUMERATE = 0x0040;
    public const uint DESKTOP_WRITEOBJECTS = 0x0080;
    public const uint DESKTOP_READOBJECTS = 0x0001;
    public const uint GENERIC_ALL = 0x10000000;

    public const uint CREATE_NEW_CONSOLE = 0x00000010;
    public const uint CREATE_UNICODE_ENVIRONMENT = 0x00000400;

    public const int STARTF_USESHOWWINDOW = 0x00000001;
    public const short SW_MAXIMIZE = 3;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct STARTUPINFO
    {
        public int cb;
        public string? lpReserved;
        public string? lpDesktop;
        public string? lpTitle;
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
        public IntPtr lpReserved2;
        public IntPtr hStdInput;
        public IntPtr hStdOutput;
        public IntPtr hStdError;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct PROCESS_INFORMATION
    {
        public IntPtr hProcess;
        public IntPtr hThread;
        public int dwProcessId;
        public int dwThreadId;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct SECURITY_ATTRIBUTES
    {
        public int nLength;
        public IntPtr lpSecurityDescriptor;
        public bool bInheritHandle;
    }

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    public static extern IntPtr CreateDesktop(
        string lpszDesktop,
        IntPtr lpszDevice,
        IntPtr pDevmode,
        int dwFlags,
        uint dwDesiredAccess,
        IntPtr lpsa);

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    public static extern IntPtr OpenDesktop(
        string lpszDesktop,
        int dwFlags,
        bool fInherit,
        uint dwDesiredAccess);

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool SwitchDesktop(IntPtr hDesktop);

    // Locks the whole session (same as Win+L) - the operator must enter
    // their Windows password to get back, instead of the kiosk just handing
    // the unlocked desktop straight back to whoever is standing there.
    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool LockWorkStation();

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool CloseDesktop(IntPtr hDesktop);

    // lpCommandLine must be a mutable buffer - CreateProcess writes into it.
    // A plain C# string could point at an interned literal, so this takes a
    // StringBuilder instead.
    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    public static extern bool CreateProcess(
        string? lpApplicationName,
        System.Text.StringBuilder lpCommandLine,
        IntPtr lpProcessAttributes,
        IntPtr lpThreadAttributes,
        bool bInheritHandles,
        uint dwCreationFlags,
        IntPtr lpEnvironment,
        string? lpCurrentDirectory,
        ref STARTUPINFO lpStartupInfo,
        out PROCESS_INFORMATION lpProcessInformation);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool CloseHandle(IntPtr hObject);

    [DllImport("user32.dll", SetLastError = true)]
    public static extern IntPtr SetWindowsHookEx(int idHook, LowLevelKeyboardProc lpfn, IntPtr hMod, uint dwThreadId);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool UnhookWindowsHookEx(IntPtr hhk);

    [DllImport("user32.dll")]
    public static extern IntPtr CallNextHookEx(IntPtr hhk, int nCode, IntPtr wParam, IntPtr lParam);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    public static extern IntPtr GetModuleHandle(string lpModuleName);

    public delegate IntPtr LowLevelKeyboardProc(int nCode, IntPtr wParam, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    public struct KBDLLHOOKSTRUCT
    {
        public uint vkCode;
        public uint scanCode;
        public uint flags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    public const int WH_KEYBOARD_LL = 13;
    public const int WM_KEYDOWN = 0x0100;
    public const int WM_SYSKEYDOWN = 0x0104;

    public const int VK_LWIN = 0x5B;
    public const int VK_RWIN = 0x5C;
    public const int VK_TAB = 0x09;
    public const int VK_ESCAPE = 0x1B;
    public const int VK_F4 = 0x73;
    public const int VK_MENU = 0x12;   // Alt
    public const int VK_CONTROL = 0x11;
    public const int VK_SHIFT = 0x10;
    public const int VK_K = 0x4B;

    [DllImport("user32.dll")]
    public static extern short GetAsyncKeyState(int vKey);

    // Associates the calling thread with a desktop. Low-level keyboard
    // hooks and RegisterHotKey are scoped to "whatever desktop the
    // installing thread is attached to" - this is what lets a thread
    // actually receive input while a non-default desktop is active
    // (Windows deliberately does NOT let hooks cross desktop boundaries;
    // that's the same protection that keeps the secure UAC desktop safe
    // from keyloggers on the normal desktop).
    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool SetThreadDesktop(IntPtr hDesktop);

    [DllImport("kernel32.dll")]
    public static extern uint GetCurrentThreadId();

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool UnregisterHotKey(IntPtr hWnd, int id);

    [StructLayout(LayoutKind.Sequential)]
    public struct MSG
    {
        public IntPtr hwnd;
        public uint message;
        public IntPtr wParam;
        public IntPtr lParam;
        public uint time;
        public int ptX;
        public int ptY;
    }

    [DllImport("user32.dll")]
    public static extern int GetMessage(out MSG lpMsg, IntPtr hWnd, uint wMsgFilterMin, uint wMsgFilterMax);

    [DllImport("user32.dll")]
    public static extern bool TranslateMessage(ref MSG lpMsg);

    [DllImport("user32.dll")]
    public static extern IntPtr DispatchMessage(ref MSG lpMsg);

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool PostThreadMessage(uint idThread, uint msg, IntPtr wParam, IntPtr lParam);

    public const uint WM_QUIT = 0x0012;
    public const uint MOD_ALT = 0x0001;
    public const uint MOD_CONTROL = 0x0002;
    public const uint MOD_SHIFT = 0x0004;

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    public static extern IntPtr CreateWindowEx(
        int dwExStyle,
        string lpClassName,
        string lpWindowName,
        int dwStyle,
        int x, int y, int nWidth, int nHeight,
        IntPtr hWndParent,
        IntPtr hMenu,
        IntPtr hInstance,
        IntPtr lpParam);
}
