package com.kiosklauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Hands the files a customer downloaded during a kiosk session over to the
 * operator, and leaves the download folder empty for the next customer.
 *
 * Runs after the browser has been killed, so nothing is writing to the
 * folder any more. Completed files are moved to
 * "handoff/yyyyMMdd-HHmmss" inside the download folder; unfinished
 * downloads (.crdownload and friends) are deleted - a half-written file is
 * useless to the operator and shouldn't be left lying around for the next
 * customer either.
 *
 * This limits exposure, it is NOT a security boundary: the kiosk runs as the
 * operator's own account, so anything the operator can read the customer's
 * browser can reach via file:///. See the README.
 */
final class DownloadCollector {

    private static final Set<String> PARTIAL_EXTENSIONS = Set.of(".crdownload", ".tmp", ".partial");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private DownloadCollector() {
    }

    /**
     * @return the folder the files were moved into, or null if there was nothing to hand over.
     *         Never throws: a failure on one file is logged and the rest are still handled.
     */
    static Path collect(Path downloadDir) {
        if (downloadDir == null || !Files.isDirectory(downloadDir)) {
            return null;
        }

        List<Path> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(downloadDir)) {
            list.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException e) {
            return null;
        }
        if (files.isEmpty()) {
            return null;
        }

        // A subfolder of the download folder. Files.list above only picks regular files,
        // so this subfolder is never swept into itself.
        Path handoffRoot = downloadDir.toAbsolutePath().resolve("handoff");
        Path target = handoffRoot.resolve(LocalDateTime.now().format(STAMP));
        Path log = handoffRoot.resolve("handoff.log");
        boolean movedAny = false;

        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                if (isPartial(name)) {
                    Files.deleteIfExists(file);
                    log(log, "DROPPED (unfinished download)  " + name);
                    continue;
                }
                Files.createDirectories(target);
                Path dest = uniqueTarget(target, name);
                Files.move(file, dest, StandardCopyOption.ATOMIC_MOVE);
                log(log, "MOVED  " + name + " -> " + dest);
                movedAny = true;
            } catch (IOException | RuntimeException e) {
                log(log, "FAILED  " + name + "  (" + e + ")");
            }
        }
        return movedAny ? target : null;
    }

    private static boolean isPartial(String name) {
        String lower = name.toLowerCase();
        return PARTIAL_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    /** name, or "stem (1).ext", "stem (2).ext", ... if it already exists - never overwrite. */
    private static Path uniqueTarget(Path dir, String name) {
        Path candidate = dir.resolve(name);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; Files.exists(candidate); i++) {
            candidate = dir.resolve(stem + " (" + i + ")" + ext);
        }
        return candidate;
    }

    private static void log(Path logFile, String message) {
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, LocalDateTime.now().withNano(0) + "  " + message + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // logging is best-effort; never let it break the handoff itself
        }
    }
}
