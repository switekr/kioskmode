namespace KioskLauncher;

/// <summary>
/// Runs entirely on the operator's normal desktop as a tray-only app (no
/// main window). Provides the controls to enter/exit kiosk mode: a tray
/// menu, and the Ctrl+Alt+Shift+K exit combo that works even while the
/// kiosk desktop is the active one (see KioskDesktopGuard for why that
/// requires its own dedicated thread).
/// </summary>
internal sealed class TrayAppContext : ApplicationContext
{
    private readonly NotifyIcon _trayIcon;
    private readonly KioskController _controller = new();
    private readonly KioskConfig _config;

    private readonly ToolStripMenuItem _startItem;
    private readonly ToolStripMenuItem _stopItem;

    public TrayAppContext()
    {
        _config = KioskConfig.Load();

        _startItem = new ToolStripMenuItem("Uruchom kiosk", null, (_, _) => StartKiosk());
        _stopItem = new ToolStripMenuItem("Zakończ kiosk   (Ctrl+Alt+Shift+K)", null, (_, _) => StopKiosk())
        {
            Enabled = false,
        };
        var exitItem = new ToolStripMenuItem("Zamknij aplikację", null, (_, _) => ExitApp());

        var menu = new ContextMenuStrip();
        menu.Items.Add(_startItem);
        menu.Items.Add(_stopItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(exitItem);

        _trayIcon = new NotifyIcon
        {
            Icon = SystemIcons.Shield,
            Text = "Kiosk Launcher",
            Visible = true,
            ContextMenuStrip = menu,
        };
        _trayIcon.DoubleClick += (_, _) => StartKiosk();

        // Fires on KioskDesktopGuard's dedicated thread, not the UI thread -
        // OnExitCombo marshals itself over before touching any control.
        _controller.ExitRequested += OnExitCombo;
    }

    private void StartKiosk()
    {
        if (_controller.IsActive) return;
        try
        {
            _controller.Start(_config);
            _startItem.Enabled = false;
            _stopItem.Enabled = true;
            _trayIcon.ShowBalloonTip(3000, "Kiosk Launcher",
                "Kiosk aktywny. Aby wrócić: Ctrl+Alt+Shift+K.", ToolTipIcon.Info);
        }
        catch (Exception ex)
        {
            MessageBox.Show($"Nie udało się uruchomić kiosku:\n{ex.Message}",
                "Kiosk Launcher", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void StopKiosk()
    {
        if (!_controller.IsActive) return;
        _controller.Stop();
        _startItem.Enabled = true;
        _stopItem.Enabled = false;
    }

    /// <summary>Ctrl+Alt+Shift+K handler - fires on KioskDesktopGuard's own thread.</summary>
    private void OnExitCombo()
    {
        if (_trayIcon.ContextMenuStrip!.InvokeRequired)
        {
            _trayIcon.ContextMenuStrip.BeginInvoke(new Action(OnExitCombo));
            return;
        }

        StopKiosk();
    }

    private void ExitApp()
    {
        if (_controller.IsActive)
        {
            var result = MessageBox.Show(
                "Kiosk jest aktywny. Zakończyć go i wyjść z aplikacji?",
                "Kiosk Launcher", MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
            if (result != DialogResult.Yes) return;
            StopKiosk();
        }

        _trayIcon.Visible = false;
        Application.Exit();
    }
}
