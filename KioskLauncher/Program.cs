namespace KioskLauncher;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        using var singleInstance = new Mutex(true, "KioskLauncher-SingleInstance-3F1E2C7A", out bool isNew);
        if (!isNew)
        {
            MessageBox.Show("Kiosk Launcher już działa (sprawdź zasobnik systemowy).",
                "Kiosk Launcher", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        ApplicationConfiguration.Initialize();
        Application.Run(new TrayAppContext());
    }
}
