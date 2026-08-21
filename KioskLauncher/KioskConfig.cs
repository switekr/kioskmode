using System.Text.Json;

namespace KioskLauncher;

/// <summary>Settings loaded from kiosk.settings.json next to the exe (created with defaults on first run).</summary>
public sealed class KioskConfig
{
    public string Url { get; set; } = "https://outlook.com";

    /// <summary>Full path to msedge.exe/chrome.exe. Null = auto-detect.</summary>
    public string? BrowserPath { get; set; }

    /// <summary>"public-browsing" (multi-tab, navigation allowed) or "fullscreen" (single site, digital-signage style).</summary>
    public string KioskType { get; set; } = "public-browsing";

    public int IdleTimeoutMinutes { get; set; } = 5;

    private static string ConfigPath => Path.Combine(AppContext.BaseDirectory, "kiosk.settings.json");

    public static KioskConfig Load()
    {
        try
        {
            if (File.Exists(ConfigPath))
            {
                var json = File.ReadAllText(ConfigPath);
                var cfg = JsonSerializer.Deserialize<KioskConfig>(json);
                if (cfg is not null) return cfg;
            }
        }
        catch
        {
            // malformed config file - fall through to defaults below
        }

        var defaults = new KioskConfig();
        defaults.Save();
        return defaults;
    }

    public void Save()
    {
        var json = JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(ConfigPath, json);
    }
}
