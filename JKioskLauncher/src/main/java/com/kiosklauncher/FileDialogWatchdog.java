package com.kiosklauncher;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.LongByReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Takes control of native file dialogs on the kiosk desktop, without the
 * customer ever seeing them.
 *
 * Why this exists: the kiosk browser runs as the operator's own account, so
 * an "Open" dialog (webmail "attach", Ctrl+O) would show - and let the
 * customer upload - anything in the operator's profile. Edge kiosk mode does
 * not lock these down, and the Edge policy that does
 * (AllowFileSelectionDialogs) is machine-wide, so it would also break
 * uploads in the operator's own Edge on the same PC.
 *
 * Downloads are the other half. Edge kiosk mode forces a "Save as" dialog for
 * every download (it ignores prompt_for_download=false in the profile and
 * pins its own folder), and a download whose dialog is cancelled simply never
 * happens. So Save dialogs are not cancelled but answered: the suggested file
 * name is kept, the path is replaced with one inside the single download
 * folder, and the dialog is confirmed.
 *
 * How - out-of-context WinEvent hooks installed from the guard thread, which
 * is already attached to the kiosk desktop and pumps messages (both are
 * required for the callbacks and the timer to fire):
 *
 *   1. EVENT_OBJECT_CREATE of a top-level "#32770" dialog: make it fully
 *      transparent right away. This beats the first show of the frame, so
 *      there is nothing to flash. We can't tell yet whether it is a file
 *      dialog - the frame has no children when it is created.
 *   2. EVENT_OBJECT_SHOW of the file-view control inside it (DirectUIHWND
 *      for the Vista-style IFileDialog, SHELLDLL_DefView for the classic
 *      GetOpenFileName): now we know it is a file dialog. It is hidden, then:
 *        - Save dialog (the "File name" box already holds a suggested name)
 *          and a download folder is configured -> rewrite the name to
 *          "<download folder>\<name>" and click OK;
 *        - anything else (Open dialog: the name box is empty) -> WM_CLOSE,
 *          which common dialogs treat as Cancel.
 *
 * Open vs. Save is told apart by the pre-filled name, not by the title or
 * button text, so it is independent of the system language; controls are
 * found by class and control id for the same reason.
 *
 * Any other native dialog (e.g. system print) is made visible again after
 * RESTORE_AFTER_MS, so it is merely delayed, never lost. If the file view
 * shows up after that (very slow machine) the dialog is still handled, it
 * just may have been visible briefly.
 *
 * File Explorer windows are closed too. The kiosk browser has a way to open
 * one ("Show in folder" in the downloads list, Ctrl+J): Edge starts a new
 * explorer.exe, and since there is no shell on the kiosk desktop it opens a
 * full folder window (CabinetWClass) right there - a file browser for the
 * whole operator profile. Such a window is made transparent the moment it is
 * created and then sent WM_CLOSE until it is gone. The explorer.exe that
 * opened it is killed too, but only if it was started after the watchdog was
 * armed - the operator's own shell (started at logon) is never touched.
 *
 * Not covered: file:/// navigation in the address bar (needs a URL policy),
 * and any other program the browser can launch (e.g. "Open file" on a
 * download starts the associated viewer).
 */
final class FileDialogWatchdog {

    private static final String DIALOG_CLASS = "#32770";
    private static final long RESTORE_AFTER_MS = 1500;
    private static final int TIMER_INTERVAL_MS = 100;
    // ~1.5 s for the dialog's controls to appear AND for the file name to be filled in; an upload
    // dialog (name box never filled) is cancelled after this long, hidden the whole time.
    private static final int MAX_UNDECIDED_TICKS = 15;
    private static final int MSG_TIMEOUT_MS = 1000;

    /** Window classes of File Explorer folder windows (Windows 7-11). */
    private static final java.util.Set<String> EXPLORER_CLASSES = java.util.Set.of("CabinetWClass", "ExploreWClass");
    private static final int MAX_EXPLORER_CLOSE_TICKS = 100; // ~10 s of WM_CLOSE retries

    private enum Decision { ACCEPTED, CANCEL, RETRY }

    /** A dialog we made transparent and haven't classified yet. */
    private static final class Pending {
        final Pointer hwnd;
        final long createdAtMs = System.currentTimeMillis();
        /** False if the window already had WS_EX_LAYERED, so we must not strip it on restore. */
        final boolean addedLayeredStyle;

        Pending(Pointer hwnd, boolean addedLayeredStyle) {
            this.hwnd = hwnd;
            this.addedLayeredStyle = addedLayeredStyle;
        }
    }

    /** An Explorer window we are closing; WM_CLOSE is re-sent every tick until it is gone. */
    private static final class Closing {
        final Pointer hwnd;
        int ticks;

        Closing(Pointer hwnd) {
            this.hwnd = hwnd;
        }
    }

    /** A file dialog (hidden) whose controls weren't all there yet. */
    private static final class Undecided {
        final Pointer root;
        int ticks;

        Undecided(Pointer root) {
            this.root = root;
        }
    }

    /** Where accepted Save dialogs write to; null = never accept, always cancel. */
    private final Path saveDir;

    // Strong references - JNA only keeps weak ones, and a collected callback
    // silently stops firing.
    private Win32.WinEventProc eventProc;
    private Win32.TimerProc timerProc;
    private Pointer hook;
    private Pointer timer;

    // Only ever touched from the guard thread (callbacks and timer run there).
    private final List<Pending> pending = new ArrayList<>();
    private final List<Undecided> undecided = new ArrayList<>();
    private final List<Closing> closing = new ArrayList<>();

    FileDialogWatchdog(Path saveDir) {
        this.saveDir = saveDir;
    }

    /** When the watchdog was armed; an explorer.exe younger than this was started during the kiosk session. */
    private java.time.Instant armedAt = java.time.Instant.MAX;

    /** Must be called on the thread that pumps messages for the kiosk desktop. */
    void install() {
        armedAt = java.time.Instant.now();
        eventProc = this::onEvent;
        hook = Win32.User32.INSTANCE.SetWinEventHook(
                Win32.EVENT_OBJECT_CREATE, Win32.EVENT_OBJECT_SHOW,
                Pointer.NULL, eventProc, 0, 0, Win32.WINEVENT_OUTOFCONTEXT);
        if (hook == null) {
            throw new IllegalStateException("SetWinEventHook nie powiodło się - okna wyboru pliku nie będą blokowane.");
        }

        timerProc = this::onTimer;
        timer = Win32.User32.INSTANCE.SetTimer(Pointer.NULL, Pointer.NULL, TIMER_INTERVAL_MS, timerProc);
        if (timer == null) {
            uninstall();
            throw new IllegalStateException("SetTimer nie powiodło się - strażnik okien wyboru pliku nie działa.");
        }
    }

    void uninstall() {
        if (timer != null) {
            Win32.User32.INSTANCE.KillTimer(Pointer.NULL, timer);
            timer = null;
        }
        if (hook != null) {
            Win32.User32.INSTANCE.UnhookWinEvent(hook);
            hook = null;
        }
        pending.clear();
        undecided.clear();
        closing.clear();
    }

    private void onEvent(Pointer hWinEventHook, int event, Pointer hwnd, int idObject, int idChild,
                         int idEventThread, int dwmsEventTime) {
        if (hwnd == null || idObject != Win32.OBJID_WINDOW) {
            return;
        }

        if (event == Win32.EVENT_OBJECT_CREATE) {
            onCreate(hwnd);
        } else if (event == Win32.EVENT_OBJECT_SHOW) {
            onShow(hwnd);
        }
    }

    private void onCreate(Pointer hwnd) {
        String cls = className(hwnd);
        if (EXPLORER_CLASSES.contains(cls) && isTopLevel(hwnd)) {
            closeExplorerWindow(hwnd);
            return;
        }
        if (!DIALOG_CLASS.equals(cls) || !isTopLevel(hwnd)) {
            return;
        }

        boolean added = makeTransparent(hwnd);
        pending.add(new Pending(hwnd, added));
    }

    /** Fully transparent via a layered-window alpha of 0; returns true if we had to add WS_EX_LAYERED. */
    private static boolean makeTransparent(Pointer hwnd) {
        int exStyle = Win32.User32.INSTANCE.GetWindowLong(hwnd, Win32.GWL_EXSTYLE);
        boolean added = (exStyle & Win32.WS_EX_LAYERED) == 0;
        if (added) {
            Win32.User32.INSTANCE.SetWindowLong(hwnd, Win32.GWL_EXSTYLE, exStyle | Win32.WS_EX_LAYERED);
        }
        Win32.User32.INSTANCE.SetLayeredWindowAttributes(hwnd, 0, (byte) 0, Win32.LWA_ALPHA);
        return added;
    }

    private void closeExplorerWindow(Pointer hwnd) {
        makeTransparent(hwnd);
        Win32.User32.INSTANCE.ShowWindow(hwnd, Win32.SW_HIDE);
        Win32.User32.INSTANCE.PostMessage(hwnd, Win32.WM_CLOSE, Pointer.NULL, Pointer.NULL);
        if (closing.stream().noneMatch(c -> c.hwnd.equals(hwnd))) {
            closing.add(new Closing(hwnd));
        }
        killIfStartedDuringSession(hwnd);
    }

    /**
     * Closing the window leaves the explorer.exe that opened it running, so each
     * "Show in folder" would leave another one behind. Kill the window's owner - but only
     * an explorer.exe that was started AFTER this watchdog was armed. The operator's own
     * shell was started at logon, long before, so this can never hit it.
     */
    private void killIfStartedDuringSession(Pointer hwnd) {
        com.sun.jna.ptr.IntByReference pid = new com.sun.jna.ptr.IntByReference();
        Win32.User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
        ProcessHandle.of(Integer.toUnsignedLong(pid.getValue())).ifPresent(p -> {
            boolean isExplorer = p.info().command().map(c -> c.toLowerCase().endsWith("\\explorer.exe")).orElse(false);
            boolean startedLater = p.info().startInstant().map(s -> s.isAfter(armedAt)).orElse(false);
            if (isExplorer && startedLater) {
                p.destroyForcibly();
            }
        });
    }

    private void onShow(Pointer hwnd) {
        String cls = className(hwnd);
        if (EXPLORER_CLASSES.contains(cls) && isTopLevel(hwnd)) {
            closeExplorerWindow(hwnd); // Explorer shows its window after creating it - hide it again
            return;
        }
        if (!"DirectUIHWND".equals(cls) && !"SHELLDLL_DefView".equals(cls)) {
            return;
        }

        Pointer root = Win32.User32.INSTANCE.GetAncestor(hwnd, Win32.GA_ROOT);
        if (root == null || !DIALOG_CLASS.equals(className(root))) {
            return;
        }

        pending.removeIf(p -> p.hwnd.equals(root));
        if (undecided.stream().anyMatch(u -> u.root.equals(root))) {
            return; // the second view control (classic dialogs have several) - already being handled
        }
        Win32.User32.INSTANCE.ShowWindow(root, Win32.SW_HIDE);

        trace("file dialog detected (view control " + cls + ")");
        Decision d = decide(root);
        if (d == Decision.RETRY) {
            undecided.add(new Undecided(root));
        } else if (d == Decision.CANCEL) {
            cancel(root);
        }
    }

    /** Timer tick: re-try undecided dialogs, and give back visibility to dialogs that turned out not to be file dialogs. */
    private void onTimer(Pointer hwnd, int uMsg, Pointer idEvent, int dwTime) {
        for (Iterator<Undecided> it = undecided.iterator(); it.hasNext(); ) {
            Undecided u = it.next();
            if (!Win32.User32.INSTANCE.IsWindow(u.root)) {
                it.remove();
                continue;
            }
            Decision d = decide(u.root);
            if (d == Decision.RETRY && ++u.ticks < MAX_UNDECIDED_TICKS) {
                continue;
            }
            it.remove();
            if (d != Decision.ACCEPTED) {
                trace("giving up on an undecided dialog after " + u.ticks + " ticks (last decision " + d + ") -> cancel");
                cancel(u.root); // couldn't make sense of it in time - fail closed
            }
        }

        for (Iterator<Closing> it = closing.iterator(); it.hasNext(); ) {
            Closing c = it.next();
            if (!Win32.User32.INSTANCE.IsWindow(c.hwnd) || ++c.ticks > MAX_EXPLORER_CLOSE_TICKS) {
                it.remove();
                continue;
            }
            Win32.User32.INSTANCE.ShowWindow(c.hwnd, Win32.SW_HIDE);
            Win32.User32.INSTANCE.PostMessage(c.hwnd, Win32.WM_CLOSE, Pointer.NULL, Pointer.NULL);
        }

        long now = System.currentTimeMillis();
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (!Win32.User32.INSTANCE.IsWindow(p.hwnd)) {
                it.remove();
            } else if (now - p.createdAtMs >= RESTORE_AFTER_MS) {
                restore(p);
                it.remove();
            }
        }
    }

    private static void cancel(Pointer root) {
        Win32.User32.INSTANCE.ShowWindow(root, Win32.SW_HIDE);
        Win32.User32.INSTANCE.PostMessage(root, Win32.WM_CLOSE, Pointer.NULL, Pointer.NULL);
    }

    /**
     * Save dialog with a suggested name and a download folder -> point it at that folder and
     * confirm; everything else -> cancel; controls not there yet -> try again on the next tick.
     */
    private Decision decide(Pointer root) {
        if (saveDir == null) {
            trace("decide: BLOCK mode (no download folder) -> CANCEL");
            return Decision.CANCEL;
        }

        Pointer edit = findFileNameEdit(root);
        Pointer ok = findOkButton(root);
        if (edit == null || ok == null) {
            trace("decide: controls not there yet (edit=" + (edit != null) + ", ok=" + (ok != null) + ") -> RETRY");
            return Decision.RETRY;
        }

        String suggested = readText(edit);
        if (suggested == null || suggested.isBlank()) {
            // An empty name box means an Open dialog (an upload) - never allowed. But NOT
            // yet: right after the dialog appears Edge's Save dialog has the box and not
            // its text (seen in ~1 of 5 downloads), and cancelling then would silently
            // drop a legitimate download. So wait; onTimer cancels it if the box is still
            // empty after MAX_UNDECIDED_TICKS - the window stays hidden meanwhile.
            trace("decide: name box empty (" + suggested + ") -> RETRY (an upload if it stays empty)");
            return Decision.RETRY;
        }

        Path target;
        try {
            target = uniqueTarget(leafName(suggested));
        } catch (IOException | RuntimeException e) {
            trace("decide: cannot build a target for '" + suggested + "': " + e + " -> CANCEL");
            return Decision.CANCEL;
        }

        if (!sendText(edit, target.toString())) {
            trace("decide: WM_SETTEXT failed -> CANCEL");
            return Decision.CANCEL;
        }
        // BM_CLICK is synchronous: it returns once the dialog has processed the click.
        Pointer clicked = Win32.User32.INSTANCE.SendMessageTimeout(ok, Win32.BM_CLICK, Pointer.NULL, Pointer.NULL,
                Win32.SMTO_ABORTIFHUNG, MSG_TIMEOUT_MS * 2, new LongByReference());
        trace("decide: '" + suggested + "' -> " + target + ", OK clicked (SendMessageTimeout ok=" + (clicked != null)
                + ") -> ACCEPTED");
        return Decision.ACCEPTED;
    }

    private static final boolean TRACE = Boolean.getBoolean("kiosk.trace");

    /** Debug aid: run with -Dkiosk.trace=true to see what the watchdog decides and why. */
    private static void trace(String message) {
        if (TRACE) {
            System.err.println("[watchdog " + System.currentTimeMillis() % 100000 + "] " + message);
        }
    }

    /** Suggested name reduced to a bare, valid file name - never a path, so it can only land in saveDir. */
    private static String leafName(String suggested) {
        String leaf = suggested.trim();
        int slash = Math.max(leaf.lastIndexOf('\\'), leaf.lastIndexOf('/'));
        if (slash >= 0) {
            leaf = leaf.substring(slash + 1);
        }
        leaf = leaf.replaceAll("[<>:\"|?*\\x00-\\x1f]", "_").trim();
        if (leaf.isEmpty() || leaf.equals(".") || leaf.equals("..")) {
            throw new IllegalArgumentException("unusable file name");
        }
        return leaf;
    }

    /** saveDir\name, or "name (1).ext", ... - the dialog would otherwise pop up an "overwrite?" box. */
    private Path uniqueTarget(String name) throws IOException {
        Files.createDirectories(saveDir);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        Path candidate = saveDir.resolve(name).toAbsolutePath();
        for (int i = 1; Files.exists(candidate); i++) {
            candidate = saveDir.resolve(stem + " (" + i + ")" + ext).toAbsolutePath();
        }
        return candidate;
    }

    // ---- locating and talking to the dialog's controls ----

    /** The "File name" box: classic dialogs give it id 0x480; Vista-style ones nest an Edit inside a ComboBox. */
    private static Pointer findFileNameEdit(Pointer root) {
        return findChild(root, h -> "Edit".equals(className(h))
                && (Win32.User32.INSTANCE.GetDlgCtrlID(h) == Win32.ID_FILENAME_EDIT
                || "ComboBox".equals(className(Win32.User32.INSTANCE.GetParent(h)))));
    }

    private static Pointer findOkButton(Pointer root) {
        return findChild(root, h -> "Button".equals(className(h))
                && Win32.User32.INSTANCE.GetDlgCtrlID(h) == Win32.IDOK);
    }

    private static Pointer findChild(Pointer root, java.util.function.Predicate<Pointer> match) {
        Pointer[] found = new Pointer[1];
        Win32.WndEnumProc proc = (h, lParam) -> {
            if (match.test(h)) {
                found[0] = h;
                return false;
            }
            return true;
        };
        Win32.User32.INSTANCE.EnumChildWindows(root, proc, Pointer.NULL);
        return found[0];
    }

    private static String readText(Pointer edit) {
        char[] buf = new char[1024];
        LongByReference copied = new LongByReference();
        Pointer ret = Win32.User32.INSTANCE.SendMessageTimeout(edit, Win32.WM_GETTEXT, new Pointer(buf.length), buf,
                Win32.SMTO_ABORTIFHUNG, MSG_TIMEOUT_MS, copied);
        if (ret == null && copied.getValue() == 0) {
            return null;
        }
        int len = (int) Math.min(Math.max(copied.getValue(), 0), buf.length);
        return new String(buf, 0, len);
    }

    private static boolean sendText(Pointer edit, String text) {
        LongByReference result = new LongByReference();
        Pointer ret = Win32.User32.INSTANCE.SendMessageTimeout(edit, Win32.WM_SETTEXT, Pointer.NULL, new WString(text),
                Win32.SMTO_ABORTIFHUNG, MSG_TIMEOUT_MS, result);
        // SendMessageTimeout returns 0 on failure/timeout; JNA maps a 0 Pointer to null.
        return ret != null;
    }

    private static void restore(Pending p) {
        Win32.User32.INSTANCE.SetLayeredWindowAttributes(p.hwnd, 0, (byte) 255, Win32.LWA_ALPHA);
        if (p.addedLayeredStyle) {
            int exStyle = Win32.User32.INSTANCE.GetWindowLong(p.hwnd, Win32.GWL_EXSTYLE);
            Win32.User32.INSTANCE.SetWindowLong(p.hwnd, Win32.GWL_EXSTYLE, exStyle & ~Win32.WS_EX_LAYERED);
        }
    }

    private static boolean isTopLevel(Pointer hwnd) {
        return hwnd.equals(Win32.User32.INSTANCE.GetAncestor(hwnd, Win32.GA_ROOT));
    }

    private static String className(Pointer hwnd) {
        if (hwnd == null) {
            return "";
        }
        char[] buf = new char[64];
        int len = Win32.User32.INSTANCE.GetClassName(hwnd, buf, buf.length);
        return len > 0 ? new String(buf, 0, len) : "";
    }
}
