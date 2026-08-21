using System.Runtime.InteropServices;

namespace KioskLauncher;

/// <summary>
/// Runs a dedicated thread attached to the kiosk desktop (via
/// SetThreadDesktop) for the entire time kiosk mode is active. That
/// attachment is what makes both of these actually receive input while the
/// kiosk desktop is showing:
///
///   1. the Ctrl+Alt+Shift+K exit hotkey.
///   2. a low-level keyboard hook that swallows Alt+F4, so closing the
///      kiosk browser window can't drop the customer onto a bare, shell-less
///      desktop.
///
/// A hook or hotkey registered from a thread on the operator's desktop
/// would go silent the moment we switch away from it - that's the bug that
/// broke the first version of this app.
/// </summary>
internal sealed class KioskDesktopGuard : IDisposable
{
    private const int HOTKEY_ID = 1;
    private const int WM_HOTKEY = 0x0312;

    private readonly IntPtr _desktop;
    private Thread? _thread;
    private volatile uint _threadId;
    private readonly ManualResetEventSlim _ready = new(false);

    private NativeMethods.LowLevelKeyboardProc? _hookProc;
    private IntPtr _hookHandle;

    public event Action? ExitRequested;

    public KioskDesktopGuard(IntPtr desktop) => _desktop = desktop;

    public void Start()
    {
        _thread = new Thread(RunLoop) { IsBackground = true, Name = "KioskDesktopGuard" };
        _thread.SetApartmentState(ApartmentState.STA);
        _thread.Start();

        if (!_ready.Wait(TimeSpan.FromSeconds(3)))
        {
            throw new InvalidOperationException("Wątek strażnika kiosku nie wystartował w oczekiwanym czasie.");
        }
    }

    private void RunLoop()
    {
        if (!NativeMethods.SetThreadDesktop(_desktop))
        {
            _ready.Set();
            return;
        }

        _threadId = NativeMethods.GetCurrentThreadId();

        IntPtr messageWindow = CreateMessageOnlyWindow();

        NativeMethods.RegisterHotKey(
            messageWindow, HOTKEY_ID,
            NativeMethods.MOD_CONTROL | NativeMethods.MOD_ALT | NativeMethods.MOD_SHIFT,
            NativeMethods.VK_K);

        _hookProc = HookCallback;
        _hookHandle = NativeMethods.SetWindowsHookEx(NativeMethods.WH_KEYBOARD_LL, _hookProc, IntPtr.Zero, 0);

        _ready.Set();

        while (NativeMethods.GetMessage(out var msg, IntPtr.Zero, 0, 0) > 0)
        {
            if (msg.hwnd == messageWindow && msg.message == WM_HOTKEY)
            {
                ExitRequested?.Invoke();
            }

            NativeMethods.TranslateMessage(ref msg);
            NativeMethods.DispatchMessage(ref msg);
        }

        if (_hookHandle != IntPtr.Zero)
        {
            NativeMethods.UnhookWindowsHookEx(_hookHandle);
        }
        NativeMethods.UnregisterHotKey(messageWindow, HOTKEY_ID);
    }

    private static IntPtr CreateMessageOnlyWindow()
    {
        // A message-only window (HWND_MESSAGE parent) is enough to receive
        // WM_HOTKEY; it needs no window class registration beyond the
        // built-in "STATIC" class, and it's never shown.
        return NativeMethods.CreateWindowEx(
            0, "STATIC", string.Empty, 0, 0, 0, 0, 0,
            new IntPtr(-3) /* HWND_MESSAGE */, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
    }

    private IntPtr HookCallback(int nCode, IntPtr wParam, IntPtr lParam)
    {
        if (nCode >= 0 && (wParam == NativeMethods.WM_KEYDOWN || wParam == NativeMethods.WM_SYSKEYDOWN))
        {
            var data = Marshal.PtrToStructure<NativeMethods.KBDLLHOOKSTRUCT>(lParam);
            bool altDown = (NativeMethods.GetAsyncKeyState(NativeMethods.VK_MENU) & 0x8000) != 0;

            if (altDown && data.vkCode == NativeMethods.VK_F4)
            {
                return (IntPtr)1; // swallow Alt+F4
            }
        }

        return NativeMethods.CallNextHookEx(_hookHandle, nCode, wParam, lParam);
    }

    public void Stop()
    {
        if (_threadId != 0)
        {
            NativeMethods.PostThreadMessage(_threadId, NativeMethods.WM_QUIT, IntPtr.Zero, IntPtr.Zero);
        }
        _thread?.Join(TimeSpan.FromSeconds(2));
        _threadId = 0;
    }

    public void Dispose() => Stop();
}
