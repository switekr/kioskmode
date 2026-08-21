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

    public static KioskConfig load() {
        Path path = configPath();
        KioskConfig cfg = new KioskConfig();

        if (Files.exists(path)) {
            try {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                cfg.url = extractString(json, "Url", cfg.url);
                cfg.browserPath = extractNullableString(json, "BrowserPath");
                cfg.kioskType = extractString(json, "KioskType", cfg.kioskType);
                cfg.idleTimeoutMinutes = extractInt(json, "IdleTimeoutMinutes", cfg.idleTimeoutMinutes);
                return cfg;
            } catch (IOException e) {
                // malformed/unreadable config file - fall through to defaults below
            }
        }

        cfg.save();
        return cfg;
    }

    public void save() {
        String browserPathJson = browserPath == null ? "null" : "\"" + escape(browserPath) + "\"";
        String json = "{\n"
                + "  \"Url\": \"" + escape(url) + "\",\n"
                + "  \"BrowserPath\": " + browserPathJson + ",\n"
                + "  \"KioskType\": \"" + escape(kioskType) + "\",\n"
                + "  \"IdleTimeoutMinutes\": " + idleTimeoutMinutes + "\n"
                + "}\n";
        try {
            Files.writeString(configPath(), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // best-effort - if we can't write it, the in-memory defaults are still used
        }
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

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
