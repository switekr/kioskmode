package com.kiosklauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Plain main() test for DownloadCollector and the seeded Preferences JSON -
 * no browser, no desktop, no new dependencies.
 *
 * Run: java -cp "target/JKioskLauncher.jar;target/test-classes" com.kiosklauncher.DownloadCollectorTest
 */
public final class DownloadCollectorTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("kiosk-collector-test-");
        try {
            Path downloads = root.resolve("downloads");
            Files.createDirectories(downloads);

            // empty / missing folder: nothing to do, never throws
            check("empty folder -> null", DownloadCollector.collect(downloads) == null);
            check("missing folder -> null", DownloadCollector.collect(root.resolve("nope")) == null);

            write(downloads.resolve("statement.pdf"), "a");
            write(downloads.resolve("Report.PDF.crdownload"), "partial");
            write(downloads.resolve("noextension"), "b");

            Path handoff = DownloadCollector.collect(downloads);
            check("returns the handoff folder", handoff != null && handoff.getParent().equals(
                    downloads.toAbsolutePath().resolve("handoff")));
            check("finished files moved", handoff != null
                    && Files.exists(handoff.resolve("statement.pdf"))
                    && Files.exists(handoff.resolve("noextension")));
            check("download folder holds nothing but the handoff subfolder (partial dropped, not left behind)",
                    listing(downloads).equals(List.of(downloads.resolve("handoff"))));
            check("handoff log written", Files.readString(
                    handoff.getParent().resolve("handoff.log"), StandardCharsets.UTF_8).contains("statement.pdf"));
            check("second call is a no-op (the handoff subfolder is not swept into itself)",
                    DownloadCollector.collect(downloads) == null);

            // same name twice in one handoff folder must never overwrite
            write(downloads.resolve("dup.txt"), "one");
            Path first = DownloadCollector.collect(downloads);
            // force a collision: same second -> same target folder
            Files.createDirectories(first);
            write(first.resolve("dup.txt"), "existing");
            write(downloads.resolve("dup.txt"), "two");
            Path again = DownloadCollector.collect(downloads);
            // If the clock ticked over to the next second the second file goes to a new
            // folder instead - either way no content may be lost.
            java.util.Set<String> contents = new java.util.HashSet<>();
            try (Stream<Path> walk = Files.walk(first.getParent())) {
                for (Path p : (Iterable<Path>) walk.filter(f -> f.getFileName().toString().startsWith("dup"))::iterator) {
                    contents.add(Files.readString(p));
                }
            }
            check("collision never overwrites", again != null && contents.contains("existing") && contents.contains("two"));

            // "FileMode": block | download. Anything but "download" means block - a typo can only be stricter.
            KioskConfig.FileMode block = KioskConfig.FileMode.BLOCK;
            KioskConfig.FileMode download = KioskConfig.FileMode.DOWNLOAD;
            check("FileMode defaults to block", new KioskConfig().fileMode == block);
            check("settings file without the key -> block",
                    KioskConfig.parse("{\"Url\":\"https://x\",\"IdleTimeoutMinutes\":5}").fileMode == block);
            check("\"FileMode\": \"download\" -> download",
                    KioskConfig.parse("{ \"FileMode\" :  \"download\" }").fileMode == download);
            check("\"FileMode\": \"block\" -> block", KioskConfig.parse("{\"FileMode\":\"block\"}").fileMode == block);
            check("letter case and surrounding spaces don't matter",
                    KioskConfig.parse("{\"FileMode\":\" Download \"}").fileMode == download);
            check("a misspelled mode falls back to block",
                    KioskConfig.parse("{\"FileMode\":\"downlaod\"}").fileMode == block);
            check("the old FileGuard key is ignored (-> block, not silently download)",
                    KioskConfig.parse("{\"FileGuard\":true}").fileMode == block);
            check("an unquoted / null value falls back to block",
                    KioskConfig.parse("{\"FileMode\":null}").fileMode == block);
            KioskConfig dl = new KioskConfig();
            dl.fileMode = download;
            dl.downloadDir = "C:\\tmp";
            KioskConfig back = KioskConfig.parse(dl.toJson());
            check("save -> load round trip keeps FileMode=download and DownloadDir",
                    back.fileMode == download && "C:\\tmp".equals(back.downloadDir));
            check("FileMode is written to the file by default", new KioskConfig().toJson().contains("\"FileMode\": \"block\""));

            // session profile: wiped and re-created on every call
            Path profile = root.resolve("profile");
            Files.createDirectories(profile.resolve("Default"));
            write(profile.resolve("Default").resolve("leftover.txt"), "from the previous customer");
            KioskProfile.prepare(profile);
            check("profile is fresh", Files.isDirectory(profile) && listing(profile).isEmpty());
        } finally {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static List<Path> listing(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.toList();
        }
    }

    private static void write(Path p, String content) throws IOException {
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) {
            failures++;
        }
    }
}
