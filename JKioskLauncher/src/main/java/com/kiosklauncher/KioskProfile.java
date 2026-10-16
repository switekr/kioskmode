package com.kiosklauncher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * A throwaway browser profile for one kiosk session, passed to the browser
 * with --user-data-dir.
 *
 * It is wiped on every start, so nothing carries over from one customer to
 * the next, and it keeps the kiosk off the operator's own Edge profile: a
 * second start on a profile that is already open is handed over by Chromium
 * to the first process - on the first one's desktop - so with the operator's
 * Edge running, the kiosk window would never appear on the kiosk desktop.
 *
 * What it does NOT do is pin the download folder. That was the original idea
 * (seed download.default_directory in Preferences) and it works for a normal
 * Edge window, but kiosk mode overrides it with its own folder and forces a
 * "Save as" dialog for every download. Downloads are therefore handled by
 * FileDialogWatchdog, which answers that dialog - see its javadoc.
 */
final class KioskProfile {

    private KioskProfile() {
    }

    /** Wipes and re-creates the session profile; returns its directory. */
    static Path prepare() throws IOException {
        return prepare(KioskConfig.dataDirectory().resolve("profile"));
    }

    /** Same, at an explicit location (used by tests so they don't touch the real profile). */
    static Path prepare(Path profile) throws IOException {
        deleteRecursively(profile);
        Files.createDirectories(profile);
        return profile;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }
}
