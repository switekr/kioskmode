package com.kiosklauncher;

import com.kiosklauncher.DialogHarness.Kind;
import com.kiosklauncher.DialogHarness.Outcome;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Smoke test for FileDialogWatchdog - no JUnit, just a main() so the
 * project keeps its single JNA dependency. It never switches desktops, so
 * it doesn't take over the screen.
 *
 * Opens real common dialogs (via powershell.exe) on a throwaway desktop, in the
 * two FileModes (see KioskConfig.FileMode):
 *
 *   DOWNLOAD mode (guard given a download folder):
 *   - OPEN dialogs (uploads): CONTROL run without the guard must hang
 *     (proves the test can fail); GUARDED runs must come back Cancel and never
 *     be visible for more than ~one frame (no flicker, measured by a probe);
 *   - SAVE dialogs with a suggested name (what Edge shows for a download): the
 *     guard must confirm them with "<download folder>\<suggested name>" - and
 *     with "name (1).ext" if that file already exists, never an overwrite;
 *   - SAVE dialog with an empty name box: cancelled (that is an upload);
 *
 *   BLOCK mode (no download folder): EVERY file dialog is cancelled - open and
 *   save, with or without a suggested name - so nothing is uploaded or saved;
 *
 *   both modes: a MessageBox (not a file dialog) is left alone and visible again later.
 *
 * Run: java -cp "target/JKioskLauncher.jar;target/test-classes" com.kiosklauncher.FileDialogWatchdogSmokeTest
 */
public final class FileDialogWatchdogSmokeTest {

    /** About one 60 Hz frame: anything shorter than this is unlikely to be noticed. */
    private static final double FLICKER_BUDGET_MS = 16.7;
    private static final int GUARDED_RUNS = 5;
    private static final long T = DialogHarness.DEFAULT_TIMEOUT_MS;

    private static boolean allPassed = true;

    public static void main(String[] args) throws Exception {
        Path saveDir = Files.createTempDirectory("kiosk-savedir-");
        try {
            for (Kind kind : new Kind[] {Kind.FILE_DIALOG_VISTA, Kind.FILE_DIALOG_CLASSIC}) {
                Outcome control = DialogHarness.run(kind, false, null, false, T);
                report("%s control, no guard".formatted(kind), "TIMEOUT".equals(control.result()),
                        control.result(), "OK", "TEST INVALID");
                openDialogCancelledWithoutFlicker(kind, saveDir, "DOWNLOAD mode: ");
            }

            // Save dialog + download folder -> confirmed into that folder.
            Path expected = saveDir.resolve(DialogHarness.SUGGESTED_NAME).toAbsolutePath();
            for (Kind kind : new Kind[] {Kind.SAVE_DIALOG_VISTA, Kind.SAVE_DIALOG_CLASSIC}) {
                Outcome o = DialogHarness.run(kind, true, saveDir, true, T);
                check("%s -> confirmed into the download folder".formatted(kind),
                        sameFile(o.result(), "OK|", expected), o.result() + " (visible " + (int) o.visibleMs() + " ms)");
            }

            // Name already taken -> "report (1).txt", not the dialog's overwrite prompt.
            Files.writeString(expected, "already here");
            Outcome taken = DialogHarness.run(Kind.SAVE_DIALOG_VISTA, true, saveDir, false, T);
            check("SAVE_DIALOG_VISTA, name taken -> unique name, no overwrite prompt",
                    sameFile(taken.result(), "OK|", saveDir.resolve("report (1).txt").toAbsolutePath()),
                    taken.result());
            check("existing file untouched", "already here".equals(Files.readString(expected)), "");

            // DOWNLOAD mode still refuses an empty name (that is an upload, not a download).
            Outcome empty = DialogHarness.run(Kind.SAVE_DIALOG_EMPTY_NAME, true, saveDir, false, T);
            check("DOWNLOAD mode: SAVE_DIALOG_EMPTY_NAME -> cancelled", result(empty).equals("Cancel|"), empty.result());

            // BLOCK mode (no download folder, saveDir = null): NOTHING gets through - not even a Save
            // dialog with a perfectly good suggested name, so no download is ever written.
            // (FileName keeps the pre-filled name even when cancelled, so only the prefix is compared.)
            java.util.Set<String> filesBefore = names(saveDir);
            for (Kind kind : new Kind[] {Kind.SAVE_DIALOG_VISTA, Kind.SAVE_DIALOG_CLASSIC, Kind.SAVE_DIALOG_EMPTY_NAME}) {
                Outcome o = DialogHarness.run(kind, true, null, false, T);
                check("BLOCK mode: %s -> cancelled".formatted(kind), result(o).startsWith("Cancel|"), o.result());
            }
            for (Kind kind : new Kind[] {Kind.FILE_DIALOG_VISTA, Kind.FILE_DIALOG_CLASSIC}) {
                openDialogCancelledWithoutFlicker(kind, null, "BLOCK mode: ");
            }
            check("BLOCK mode: the download folder was not touched", filesBefore.equals(names(saveDir)),
                    names(saveDir).toString());

            // MessageBox must survive the guard: still open at the timeout, and
            // visible for most of the time after the restore delay.
            Outcome box = DialogHarness.run(Kind.MESSAGE_BOX, true, saveDir, true, 5_000);
            check("MESSAGE_BOX (not a file dialog) left alone",
                    "TIMEOUT".equals(box.result()) && box.visibleMs() > 1_500,
                    box.result() + ", visible " + (int) box.visibleMs() + " ms (expect still open, > 1500)");
        } finally {
            try (var walk = Files.walk(saveDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }

        System.out.println(allPassed ? "ALL PASSED" : "FAILED");
        System.exit(allPassed ? 0 : 1);
    }

    private static java.util.Set<String> names(Path dir) throws java.io.IOException {
        try (var s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
        }
    }

    private static void openDialogCancelledWithoutFlicker(Kind kind, Path saveDir, String modeLabel) throws Exception {
        StringBuilder visible = new StringBuilder();
        double worst = 0;
        boolean cancelled = true;
        for (int i = 0; i < GUARDED_RUNS; i++) {
            Outcome o = DialogHarness.run(kind, true, saveDir, true, T);
            if (!result(o).equals("Cancel|")) {
                System.out.printf("FAIL  %s guarded run %d: %s%n", kind, i + 1, o.result());
                cancelled = false;
                continue;
            }
            visible.append(String.format("%.0f ", o.visibleMs()));
            worst = Math.max(worst, o.visibleMs());
        }
        check("%s%s guarded -> Cancel, no flicker".formatted(modeLabel, kind), cancelled && worst <= FLICKER_BUDGET_MS,
                "visible ms per run: %s(worst %.0f, budget %.1f)".formatted(visible, worst, FLICKER_BUDGET_MS));
    }

    /** Dialog result in the "Cancel|" / "OK|path" form the harness writes, or the raw marker (TIMEOUT, ERROR...). */
    private static String result(Outcome o) {
        return o.result();
    }

    private static boolean sameFile(String result, String prefix, Path expected) {
        if (!result.startsWith(prefix)) {
            return false;
        }
        return Path.of(result.substring(prefix.length())).toAbsolutePath().normalize().equals(expected.normalize());
    }

    private static void report(String what, boolean ok, String detail, String okLabel, String failLabel) {
        System.out.printf("%s  %s : %s -> %s%n", ok ? "PASS" : "FAIL", what, detail, ok ? okLabel : failLabel);
        if (!ok) {
            allPassed = false;
        }
    }

    private static void check(String what, boolean ok, String detail) {
        System.out.printf("%s  %s%s%n", ok ? "PASS" : "FAIL", what, detail.isEmpty() ? "" : "  [" + detail + "]");
        if (!ok) {
            allPassed = false;
        }
    }
}
