package com.kiosklauncher;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * Runs entirely on the operator's normal desktop as a tray-only app (no
 * main window). Provides the controls to enter/exit kiosk mode: a tray
 * menu, and the Ctrl+Alt+Shift+K exit combo that works even while the
 * kiosk desktop is the active one (see KioskDesktopGuard for why that
 * requires its own dedicated thread).
 */
final class TrayApp {

    private final KioskController controller = new KioskController();
    private final KioskConfig config;

    private TrayIcon trayIcon;
    private MenuItem startItem;
    private MenuItem stopItem;

    TrayApp() {
        config = KioskConfig.load();
    }

    void start() throws AWTException {
        if (!SystemTray.isSupported()) {
            throw new IllegalStateException("SystemTray nie jest obsługiwany w tym środowisku.");
        }

        PopupMenu menu = new PopupMenu();

        startItem = new MenuItem("Uruchom kiosk");
        startItem.addActionListener(e -> startKiosk());
        menu.add(startItem);

        stopItem = new MenuItem("Zakończ kiosk   (Ctrl+Alt+Shift+K)");
        stopItem.setEnabled(false);
        stopItem.addActionListener(e -> stopKiosk());
        menu.add(stopItem);

        menu.addSeparator();

        MenuItem exitItem = new MenuItem("Zamknij aplikację");
        exitItem.addActionListener(e -> exitApp());
        menu.add(exitItem);

        trayIcon = new TrayIcon(createTrayImage(), "Kiosk Launcher", menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(e -> startKiosk()); // double-click default action

        SystemTray.getSystemTray().add(trayIcon);

        // Fires on KioskDesktopGuard's dedicated thread, not the AWT event
        // thread - onExitCombo marshals itself over before touching any UI.
        controller.setExitRequestedHandler(this::onExitCombo);
    }

    private void startKiosk() {
        if (controller.isActive()) {
            return;
        }
        try {
            controller.start(config);
            startItem.setEnabled(false);
            stopItem.setEnabled(true);
            trayIcon.displayMessage("Kiosk Launcher",
                    "Kiosk aktywny. Aby wrócić: Ctrl+Alt+Shift+K.", TrayIcon.MessageType.INFO);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "Nie udało się uruchomić kiosku:\n" + ex.getMessage(),
                    "Kiosk Launcher", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void stopKiosk() {
        if (!controller.isActive()) {
            return;
        }
        controller.stop();
        startItem.setEnabled(true);
        stopItem.setEnabled(false);
    }

    /** Ctrl+Alt+Shift+K handler - fires on KioskDesktopGuard's own thread. */
    private void onExitCombo() {
        SwingUtilities.invokeLater(this::stopKiosk);
    }

    private void exitApp() {
        if (controller.isActive()) {
            int result = JOptionPane.showConfirmDialog(null,
                    "Kiosk jest aktywny. Zakończyć go i wyjść z aplikacji?",
                    "Kiosk Launcher", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (result != JOptionPane.YES_OPTION) {
                return;
            }
            stopKiosk();
        }

        SystemTray.getSystemTray().remove(trayIcon);
        System.exit(0);
    }

    /** Small generated shield-ish icon - avoids bundling/loading an image resource for one 16x16 glyph. */
    private static Image createTrayImage() {
        int size = 16;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x2B, 0x57, 0x9A));
        g.fillRoundRect(1, 1, size - 2, size - 2, 4, 4);
        g.setColor(Color.WHITE);
        g.drawRoundRect(1, 1, size - 3, size - 3, 4, 4);
        g.dispose();
        return img;
    }
}
