package com.kiosklauncher;

import com.kiosklauncher.DialogHarness.Kind;
import com.kiosklauncher.DialogHarness.Outcome;

/**
 * Smoke test for FileDialogWatchdog - no JUnit, just a main() so the
 * project keeps its single JNA dependency. It never switches desktops, so
 * it doesn't take over the screen.
 *
 * For each file-dialog style it opens a real OpenFileDialog (via
 * powershell.exe) on a throwaway desktop:
 *   - CONTROL run, no guard: the dialog must still be open at the timeout
 *     (proves the test can actually fail);
 *   - GUARDED runs: the dialog must be dismissed as Cancel, and must never
 *     have been visible for more than ~one frame (no flicker).
 *
 * Plus a MessageBox with the guard on: it is not a file dialog, so it must
 * NOT be closed and must become visible again after the watchdog's delay.
 *
 * Run: java -cp "target/JKioskLauncher.jar;target/test-classes" com.kiosklauncher.FileDialogWatchdogSmokeTest
 */
public final class FileDialogWatchdogSmokeTest {

    /** About one 60 Hz frame: anything shorter than this is unlikely to be noticed. */
    private static final double FLICKER_BUDGET_MS = 16.7;
    private static final int GUARDED_RUNS = 5;

    public static void main(String[] args) throws Exception {
        boolean allPassed = true;

        for (Kind kind : new Kind[] {Kind.FILE_DIALOG_VISTA, Kind.FILE_DIALOG_CLASSIC}) {
            Outcome control = DialogHarness.run(kind, false, false, DialogHarness.DEFAULT_TIMEOUT_MS);
            boolean controlOk = "TIMEOUT".equals(control.result());
            System.out.printf("[%s] control, no guard : %s -> %s%n",
                    kind, control.result(), controlOk ? "OK" : "TEST INVALID");
            allPassed &= controlOk;

            StringBuilder visible = new StringBuilder();
            double worst = 0;
            boolean cancelled = true;
            for (int i = 0; i < GUARDED_RUNS; i++) {
                Outcome guarded = DialogHarness.run(kind, true, true, DialogHarness.DEFAULT_TIMEOUT_MS);
                if (!"Cancel".equals(guarded.result())) {
                    System.out.printf("[%s] guarded run %d     : %s -> FAIL%n", kind, i + 1, guarded.result());
                    cancelled = false;
                    continue;
                }
                visible.append(String.format("%.0f ", guarded.visibleMs()));
                worst = Math.max(worst, guarded.visibleMs());
            }
            boolean noFlicker = worst <= FLICKER_BUDGET_MS;
            System.out.printf("[%s] guarded, visible ms per run: %s(worst %.0f, budget %.1f) -> %s%n",
                    kind, visible, worst, FLICKER_BUDGET_MS,
                    cancelled && noFlicker ? "PASS" : "FAIL");
            allPassed &= cancelled && noFlicker;
        }

        // MessageBox must survive the guard: still open at the timeout, and
        // visible for most of the time after the restore delay.
        Outcome box = DialogHarness.run(Kind.MESSAGE_BOX, true, true, 5_000);
        boolean boxOk = "TIMEOUT".equals(box.result()) && box.visibleMs() > 1_500;
        System.out.printf("[MESSAGE_BOX] guarded, not a file dialog: %s, visible %.0f ms (expect still open, visible > 1500) -> %s%n",
                box.result(), box.visibleMs(), boxOk ? "PASS" : "FAIL");
        allPassed &= boxOk;

        System.out.println(allPassed ? "ALL PASSED" : "FAILED");
        System.exit(allPassed ? 0 : 1);
    }
}
