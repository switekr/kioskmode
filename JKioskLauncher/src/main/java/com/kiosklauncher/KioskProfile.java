package com.kiosklauncher;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A throwaway browser profile for one kiosk session, passed to the browser
 * with --user-data-dir. Lives under %LOCALAPPDATA%\JKioskLauncher\profile.
 *
 * It is wiped on every start, so nothing carries over from one customer to
 * the next, and it keeps the kiosk off the operator's own Edge profile: a
 * second start on a profile that is already open is handed over by Chromium
 * to the first process - on the first one's desktop - so with the operator's
 * Edge running, the kiosk window would never appear on the kiosk desktop.
 *
 * Wiping must never be what stops the kiosk from starting. Windows refuses to
 * delete a file another process has open, and that happens for ordinary reasons:
 * the previous session's browser still shutting down, or still running because
 * the app was killed from Task Manager (the documented emergency exit), or an
 * antivirus/indexer scanning the folder. So prepare() (1) ends any browser still
 * running on this profile - the command line carries the profile path, so it can
 * only be ours, never the operator's Edge - (2) retries the wipe while Windows
 * releases handles, and (3) if something unkillable still holds a file, starts on
 * a fresh sibling folder instead of failing; the stale one is swept on a later start.
 *
 * What it does NOT do is pin the download folder. That was the original idea
 * (seed download.default_directory in Preferences) and it works for a normal
 * Edge window, but kiosk mode overrides it with its own folder and forces a
 * "Save as" dialog for every download. Downloads are therefore handled by
 * FileDialogWatchdog, which answers that dialog - see its javadoc.
 */
final class KioskProfile {

    private static final int WIPE_ATTEMPTS = 6;
    private static final long WIPE_RETRY_MS = 300;
    private static final long KILL_WAIT_SECONDS = 3;

    private KioskProfile() {
    }

    /** Where the session profile lives. */
    static Path defaultProfile() {
        return KioskConfig.dataDirectory().resolve("profile");
    }

    /** Prepares an empty session profile and returns its directory. */
    static Path prepare() throws IOException {
        return prepare(defaultProfile());
    }

    /** Same, at an explicit location (used by tests so they don't touch the real profile). */
    static Path prepare(Path profile) throws IOException {
        Path parent = profile.toAbsolutePath().getParent();
        String base = profile.getFileName().toString();

        Path chosen = profile;
        // Normal case: nothing holds the folder and this is instant. Only when it fails is it worth
        // the (slower) search for a leftover browser, then a patient retry, then the fallback.
        if (!wipe(profile, 1)) {
            endBrowsersUsing(profile);
            if (!wipe(profile, WIPE_ATTEMPTS)) {
                // Something we can't end holds a file. Don't refuse to start the kiosk over a stale
                // folder: use a fresh sibling and leave this one to be swept next time.
                chosen = parent.resolve(base + "-" + System.currentTimeMillis());
            }
        }
        Files.createDirectories(chosen);

        sweepStaleSiblings(parent, base, chosen);
        return chosen;
    }

    /**
     * Ends browser processes started on this profile (or on one of its "profile-..." fallbacks:
     * the path prefix matches both). Matching on our own folder's path is what makes this safe -
     * the operator's own Edge never has it on its command line.
     *
     * Done through WMI because Java cannot do it: on Windows ProcessHandle.info().commandLine() is
     * always empty. The path goes to PowerShell in an environment variable, not on its command line -
     * otherwise the PowerShell process would match itself and kill itself. Best effort: if PowerShell
     * is missing or blocked by policy, nothing is ended and prepare() falls back to a fresh folder.
     */
    static void endBrowsersUsing(Path profile) {
        String script = "Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and "
                + "$_.CommandLine.IndexOf($env:KIOSK_PROFILE, [StringComparison]::OrdinalIgnoreCase) -ge 0 } | "
                + "ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }";
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script);
        pb.environment().put("KIOSK_PROFILE", profile.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        try {
            Process ps = pb.start();
            if (!ps.waitFor(KILL_WAIT_SECONDS * 4, TimeUnit.SECONDS)) {
                ps.destroyForcibly();
            }
        } catch (IOException e) {
            // no PowerShell - nothing to do, the fallback folder covers it
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Deletes the folder, retrying while Windows releases handles; false if it could not be fully removed. */
    private static boolean wipe(Path dir, int attempts) {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                deleteRecursively(dir);
                return true;
            } catch (IOException e) {
                if (attempt == attempts) {
                    return false;
                }
                try {
                    Thread.sleep(WIPE_RETRY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /** Best effort: remove leftover "profile-<time>" fallbacks from earlier starts; a locked one is simply skipped. */
    private static void sweepStaleSiblings(Path parent, String base, Path keep) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent, base + "-*")) {
            for (Path stale : stream) {
                if (!stale.equals(keep) && Files.isDirectory(stale)) {
                    wipe(stale, 1);
                }
            }
        } catch (IOException ignored) {
            // nothing to sweep, or the folder is unreadable - not worth failing the start
        }
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
