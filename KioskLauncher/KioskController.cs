using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace KioskLauncher;

/// <summary>
/// Owns the lifecycle of the isolated kiosk desktop: creates it, launches
/// the browser on it, switches to it, and later tears it back down. The
/// operator's original desktop (and every window on it) is never touched -
/// it just isn't the active/input desktop while the kiosk one is showing.
/// </summary>
internal sealed class KioskController
{
    private const string DesktopName = "KioskSecureDesktop";

    private IntPtr _kioskDesktop = IntPtr.Zero;
    private Process? _browserProcess;
    private KioskDesktopGuard? _guard;

    public bool IsActive => _kioskDesktop != IntPtr.Zero;

    /// <summary>
    /// Raised when the operator presses Ctrl+Alt+Shift+K. Fires on the
    /// guard's own thread (attached to the kiosk desktop) - subscribers
    /// touching WinForms UI must marshal back to the UI thread themselves.
    /// </summary>
    public event Action? ExitRequested;

    public void Start(KioskConfig config)
    {
        if (IsActive) return;

        string browserPath = config.BrowserPath ?? BrowserLocator.Find()
            ?? throw new FileNotFoundException("Nie znaleziono Edge ani Chrome. Ustaw BrowserPath w kiosk.settings.json.");

        IntPtr desktop = NativeMethods.CreateDesktop(
            DesktopName, IntPtr.Zero, IntPtr.Zero, 0, NativeMethods.GENERIC_ALL, IntPtr.Zero);
        if (desktop == IntPtr.Zero)
        {
            throw new InvalidOperationException($"CreateDesktop nie powiodło się (błąd {Marshal.GetLastWin32Error()}).");
        }

        var commandLine = new StringBuilder(
            $"\"{browserPath}\" --kiosk \"{config.Url}\" --edge-kiosk-type={config.KioskType} " +
            $"--kiosk-idle-timeout-minutes={config.IdleTimeoutMinutes} --no-first-run --start-maximized");

        // The new desktop has no shell/window manager on it, so nothing else
        // maximizes the window for us - request it explicitly, on top of
        // --start-maximized, so it reliably fills the whole screen.
        var startupInfo = new NativeMethods.STARTUPINFO
        {
            cb = Marshal.SizeOf<NativeMethods.STARTUPINFO>(),
            lpDesktop = DesktopName,
            dwFlags = NativeMethods.STARTF_USESHOWWINDOW,
            wShowWindow = NativeMethods.SW_MAXIMIZE,
        };

        bool created = NativeMethods.CreateProcess(
            null, commandLine, IntPtr.Zero, IntPtr.Zero, false,
            0, IntPtr.Zero, null, ref startupInfo, out var processInfo);

        if (!created)
        {
            int err = Marshal.GetLastWin32Error();
            NativeMethods.CloseDesktop(desktop);
            throw new InvalidOperationException($"Nie udało się uruchomić przeglądarki (błąd {err}).");
        }

        NativeMethods.CloseHandle(processInfo.hThread);

        _kioskDesktop = desktop;
        _browserProcess = Process.GetProcessById(processInfo.dwProcessId);
        NativeMethods.CloseHandle(processInfo.hProcess);

        _guard = new KioskDesktopGuard(_kioskDesktop);
        _guard.ExitRequested += () => ExitRequested?.Invoke();
        _guard.Start();

        NativeMethods.SwitchDesktop(_kioskDesktop);
    }

    /// <summary>
    /// The only way out: switches back to the operator's Default desktop
    /// and immediately locks the session (same as Win+L), then tears the
    /// kiosk browser and desktop down in the background. Whoever is at the
    /// machine has to enter the operator's password at the lock screen
    /// before anything from the original session is usable again.
    ///
    /// Order matters here: Winlogon resumes, after a successful unlock,
    /// whichever desktop was active *when LockWorkStation was called* - not
    /// always "Default". Locking while KioskSecureDesktop was still active
    /// (and then tearing it down) left the operator stranded on a dead,
    /// empty desktop with no way back, since LockWorkStation resumed there
    /// instead of Default. Switching to Default first - exactly like a
    /// normal Win+L, which is always invoked from Default - avoids that.
    /// The switch-then-lock gap is the same couple of milliseconds Win+L
    /// itself has; nothing is clickable on the kiosk desktop by the time the
    /// switch happens, since input for it is already gone with the switch.
    ///
    /// Deliberately NOT triggered by the browser process exiting on its own
    /// (crash, etc.) - only by an explicit operator action - so it just
    /// leaves the bare kiosk desktop up rather than silently unlocking.
    /// </summary>
    public void Stop()
    {
        if (!IsActive) return;

        SwitchToDefaultDesktop();
        NativeMethods.LockWorkStation();

        _guard?.Stop();
        _guard = null;

        if (_browserProcess is not null)
        {
            try
            {
                if (!_browserProcess.HasExited)
                {
                    _browserProcess.Kill(entireProcessTree: true);
                    _browserProcess.WaitForExit(5000);
                }
            }
            catch (InvalidOperationException)
            {
                // process already gone - nothing to do
            }
            _browserProcess.Dispose();
            _browserProcess = null;
        }

        NativeMethods.CloseDesktop(_kioskDesktop);
        _kioskDesktop = IntPtr.Zero;
    }

    private static void SwitchToDefaultDesktop()
    {
        IntPtr defaultDesktop = NativeMethods.OpenDesktop(
            "Default", 0, false, NativeMethods.DESKTOP_SWITCHDESKTOP);
        if (defaultDesktop == IntPtr.Zero) return;

        NativeMethods.SwitchDesktop(defaultDesktop);
        NativeMethods.CloseDesktop(defaultDesktop);
    }
}
