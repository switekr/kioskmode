package com.kiosklauncher;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        SingleInstance.Result instance = SingleInstance.acquire();

        if (instance.otherRunning) {
            if (!resolveRunningLauncher(instance)) {
                return;
            }
        } else if (KioskSession.exists(KioskController.DESKTOP_NAME)) {
            if (!resolveOrphanedSession()) {
                return;
            }
        }

        SwingUtilities.invokeLater(() -> {
            try {
                new TrayApp().start();
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "Nie udało się uruchomić aplikacji:\n" + e.getMessage(),
                        "Kiosk Launcher", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    /**
     * Another launcher is already running. If it has a kiosk session up (we can see the operator
     * is on the normal desktop, e.g. after Ctrl+Alt+Del -> Task Manager), offer to go back to it or to
     * end it. Otherwise it is just sitting in the tray.
     *
     * @return true if this process may carry on as THE launcher (the other one has been ended).
     */
    private static boolean resolveRunningLauncher(SingleInstance.Result instance) {
        String who = instance.other != null ? " (proces " + instance.other.pid() + ")" : "";

        if (!KioskSession.exists(KioskController.DESKTOP_NAME)) {
            ask("Kiosk Launcher jest już uruchomiony" + who + " - sprawdź zasobnik systemowy.",
                    new String[] {"OK"}, 0);
            return false;
        }

        if (!instance.otherSessionActive) {
            // The desktop is there but the launcher says it runs no session: what remains is a leftover with
            // nobody guarding it, so the exit hotkey would not work on it. Switching to it would strand the
            // operator - the only sensible offer is to clear it away.
            int leftover = ask("Na pulpicie kiosku została pusta lub porzucona sesja" + who + ".\n"
                            + "Żadna aplikacja jej nie pilnuje, więc nie da się do niej bezpiecznie przełączyć "
                            + "(skrót Ctrl+Alt+Shift+K tam nie zadziała).\n\n"
                            + "Zakończyć ją? Działająca aplikacja zostanie zamknięta, a ta kopia przejmie jej miejsce.",
                    new String[] {"Zakończ sesję", "Anuluj"}, 1);
            return leftover == 0 && endLiveSession(instance);
        }

        int choice = ask("Sesja kiosku jest nadal aktywna" + who + ".\n\n"
                        + "Przełącz - wracasz do okna kiosku (wyjście: Ctrl+Alt+Shift+K, potem hasło).\n"
                        + "Zakończ - kończę tamtą sesję i przeglądarkę, a ta kopia aplikacji przejmuje jej miejsce.",
                new String[] {"Przełącz do sesji kiosku", "Zakończ sesję", "Anuluj"}, 2);

        if (choice == 0) {
            if (!KioskSession.switchTo(KioskController.DESKTOP_NAME)) {
                error("Nie udało się przełączyć na pulpit kiosku.");
            }
            return false;
        }
        return choice == 1 && endLiveSession(instance);
    }

    /**
     * Ends the other launcher and the session it left, so this process can take over. The browser is not
     * the launcher's child, so ending the launcher alone would leave it running on the kiosk desktop -
     * both go. Also what releases a desktop the other launcher could not close.
     */
    private static boolean endLiveSession(SingleInstance.Result instance) {
        if (instance.other == null || !instance.terminateOther()) {
            error("Nie udało się zakończyć działającej aplikacji. Zamknij ją ręcznie (Menedżer zadań, proces javaw/JKioskLauncher).");
            return false;
        }
        if (!KioskSession.end(KioskController.DESKTOP_NAME, KioskProfile.defaultProfile())) {
            error("Aplikacja została zakończona, ale pulpit kiosku nadal jest zajęty. Zamknij przeglądarkę ręcznie.");
            return false;
        }
        return true;
    }

    /**
     * No launcher is running, yet the kiosk desktop is still in use: its browser outlived the launcher
     * (the launcher was killed). Nothing guards that window, so switching to it would leave nobody a way
     * back out - the only sensible offer is to end it.
     *
     * @return true if it is gone and this process may start normally.
     */
    private static boolean resolveOrphanedSession() {
        int choice = ask("Na pulpicie kiosku nadal działa przeglądarka z poprzedniej sesji.\n"
                        + "Aplikacja, która ją uruchomiła, już nie działa (np. została zamknięta w Menedżerze zadań), "
                        + "więc nie ma już nad nią kontroli i nie da się do niej bezpiecznie przełączyć.\n\n"
                        + "Zakończyć tę sesję?",
                new String[] {"Zakończ sesję", "Anuluj"}, 1);
        if (choice != 0) {
            return false;
        }
        if (!KioskSession.end(KioskController.DESKTOP_NAME, KioskProfile.defaultProfile())) {
            error("Nie udało się zakończyć sesji. Zamknij przeglądarkę ręcznie (Menedżer zadań, msedge/chrome).");
            return false;
        }
        return true;
    }

    /** A modal choice dialog that comes to the front; returns the index of the pressed button (-1 if closed). */
    private static int ask(String message, String[] options, int defaultOption) {
        JOptionPane pane = new JOptionPane(message, JOptionPane.WARNING_MESSAGE, JOptionPane.DEFAULT_OPTION,
                null, options, options[defaultOption]);
        JDialog dialog = pane.createDialog("Kiosk Launcher");
        dialog.setAlwaysOnTop(true);
        dialog.setVisible(true);
        dialog.dispose();
        Object value = pane.getValue();
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(value)) {
                return i;
            }
        }
        return -1;
    }

    private static void error(String message) {
        JOptionPane.showMessageDialog(null, message, "Kiosk Launcher", JOptionPane.ERROR_MESSAGE);
    }
}
