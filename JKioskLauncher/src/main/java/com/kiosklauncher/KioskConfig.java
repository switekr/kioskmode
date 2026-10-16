package com.kiosklauncher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Settings loaded from kiosk.settings.json next to the jar (created with
 * defaults on first run). Field/key names intentionally match the C#
 * version's JSON exactly (Url, BrowserPath, KioskType, IdleTimeoutMinutes),
 * so a settings file written by one app works with the other.
 *
 * This is a hand-rolled, minimal reader/writer for this one flat 4-field
 * shape - not a general JSON parser. Kept that way on purpose so the whole
 * project depends on nothing but JNA.
 */
public final class KioskConfig {

    public String url = "https://outlook.com";

    /** Full path to msedge.exe/chrome.exe. Null = auto-detect. */
    public String browserPath;

    /** "public-browsing" (multi-tab, navigation allowed) or "fullscreen" (single site, digital-signage style). */
    public String kioskType = "public-browsing";

    public int idleTimeoutMinutes = 5;

    /**
     * How the kiosk treats files. There is deliberately no "off": the kiosk
     * runs as the operator's own account, so a native file dialog would
     * expose the operator's files either way.
     */
    public enum FileMode {
        /** No file work at all: uploads, downloads and Explorer windows are all blocked. */
        BLOCK,
        /** Downloads only, and only into {@link KioskConfig#downloadDir}; uploads and Explorer stay blocked. */
        DOWNLOAD
    }

    /**
     * "FileMode" in kiosk.settings.json: "block" or "download". Absent or unrecognised
     * means BLOCK - a typo can only ever make the kiosk stricter, never looser.
     * Extra key vs. the C# version, which simply ignores it.
     */
    public FileMode fileMode = FileMode.BLOCK;

    /**
     * The one folder downloads go to in FileMode "download" (ignored in "block").
     * Null = default (%LOCALAPPDATA%\JKioskLauncher\downloads). Extra key vs. the
     * C# version, which simply ignores it.
     */
    public String downloadDir;

    /** Per-user data root for the kiosk profile, downloads and handoff folders. */
    static Path dataDirectory() {
        String local = System.getenv("LOCALAPPDATA");
        Path base = local != null ? Path.of(local) : Path.of(System.getProperty("user.home"), "AppData", "Local");
        return base.resolve("JKioskLauncher");
    }

    /** The configured download folder, or the default under {@link #dataDirectory()}. */
    Path resolvedDownloadDir() {
        return downloadDir != null && !downloadDir.isBlank()
                ? Path.of(downloadDir)
                : dataDirectory().resolve("downloads");
    }

    public static KioskConfig load() {
        Path path = configPath();

        if (Files.exists(path)) {
            try {
                return parse(Files.readString(path, StandardCharsets.UTF_8));
            } catch (IOException e) {
                // malformed/unreadable config file - fall through to defaults below
            }
        }

        KioskConfig cfg = new KioskConfig();
        cfg.save();
        return cfg;
    }

    /** Reads the settings from JSON text; any key that is absent keeps its default. */
    static KioskConfig parse(String json) {
        KioskConfig cfg = new KioskConfig();
        cfg.url = extractString(json, "Url", cfg.url);
        cfg.browserPath = extractNullableString(json, "BrowserPath");
        cfg.kioskType = extractString(json, "KioskType", cfg.kioskType);
        cfg.idleTimeoutMinutes = extractInt(json, "IdleTimeoutMinutes", cfg.idleTimeoutMinutes);
        cfg.fileMode = parseFileMode(extractString(json, "FileMode", null));
        cfg.downloadDir = extractNullableString(json, "DownloadDir");
        return cfg;
    }

    public void save() {
        try {
            Files.writeString(configPath(), toJson(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // best-effort - if we can't write it, the in-memory defaults are still used
        }
    }

    String toJson() {
        String browserPathJson = browserPath == null ? "null" : "\"" + escape(browserPath) + "\"";
        String downloadDirJson = downloadDir == null ? "null" : "\"" + escape(downloadDir) + "\"";
        String json = "{\n"
                + "  \"Url\": \"" + escape(url) + "\",\n"
                + "  \"BrowserPath\": " + browserPathJson + ",\n"
                + "  \"KioskType\": \"" + escape(kioskType) + "\",\n"
                + "  \"IdleTimeoutMinutes\": " + idleTimeoutMinutes + ",\n"
                + "  \"FileMode\": \"" + fileMode.name().toLowerCase() + "\",\n"
                + "  \"DownloadDir\": " + downloadDirJson + "\n"
                + "}\n";
        return json;
    }

    private static Path configPath() {
        return appDirectory().resolve("kiosk.settings.json");
    }

    /** Directory the jar (or, in dev, the compiled classes) lives in - the Java analogue of AppContext.BaseDirectory. */
    private static Path appDirectory() {
        try {
            var location = KioskConfig.class.getProtectionDomain().getCodeSource().getLocation();
            Path path = Path.of(location.toURI());
            return Files.isDirectory(path) ? path : path.getParent();
        } catch (URISyntaxException | NullPointerException | UncheckedIOException e) {
            return Path.of(System.getProperty("user.dir"));
        }
    }

    private static String extractString(String json, String key, String fallback) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? unescape(m.group(1)) : fallback;
    }

    private static String extractNullableString(String json, String key) {
        if (Pattern.compile("\"" + key + "\"\\s*:\\s*null").matcher(json).find()) {
            return null;
        }
        return extractString(json, key, null);
    }

    private static int extractInt(String json, String key, int fallback) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    /** "download" (any letter case) -> DOWNLOAD; everything else, including absent or misspelled, -> BLOCK. */
    static FileMode parseFileMode(String value) {
        return value != null && value.trim().equalsIgnoreCase("download") ? FileMode.DOWNLOAD : FileMode.BLOCK;
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
