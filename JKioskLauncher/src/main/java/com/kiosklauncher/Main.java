package com.kiosklauncher;

import com.sun.jna.Native;
import com.sun.jna.Pointer;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

public final class Main {

    private static final String MUTEX_NAME = "KioskLauncher-SingleInstance-3F1E2C7A";
    private static final int ERROR_ALREADY_EXISTS = 183;

    // Kept only for its side effect (owning the named mutex for the life of
    // the process); Windows releases it automatically on exit, same as the
    // C# version's `using` scope around Application.Run.
    @SuppressWarnings("unused")
    private static Pointer singleInstanceMutex;

    private Main() {
    }

    public static void main(String[] args) {
        singleInstanceMutex = Win32.Kernel32.INSTANCE.CreateMutex(Pointer.NULL, false, MUTEX_NAME);
        boolean alreadyRunning = Native.getLastError() == ERROR_ALREADY_EXISTS;

        if (alreadyRunning) {
            JOptionPane.showMessageDialog(null, "Kiosk Launcher już działa (sprawdź zasobnik systemowy).",
                    "Kiosk Launcher", JOptionPane.INFORMATION_MESSAGE);
            return;
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
}
