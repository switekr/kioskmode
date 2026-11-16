package com.kiosklauncher;

import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Only one launcher per user at a time. Two of them would share one browser profile and one
 * kiosk desktop - the second one's wipe of the profile then fails on files the first one's
 * browser holds ("campaign_history-journal is locked by another process").
 *
 * A named mutex says THAT another launcher exists; it can't say WHO, and the second launcher
 * needs to know who in order to offer ending it. So the primary instance also writes its process
 * id and start time to a small info file. A mutex whose recorded owner is gone is stale (the
 * object lives as long as anyone holds a handle to it, e.g. an earlier second launcher that
 * has since exited) and does not count.
 *
 * The mutex lives in the Global\ namespace: the default (session-local) one would not see a
 * launcher the same user runs in another Windows session (RDP next to the console, fast user
 * switching), and that one shares the profile folder just the same. Per user, so one person's
 * launcher never blocks another account's.
 */
final class SingleInstance {

    private static final int ERROR_ALREADY_EXISTS = 183;
    private static final long KILL_WAIT_SECONDS = 5;

    // Handles are kept for the life of the process on purpose - closing one would let the mutex go.
    private static final List<Pointer> HELD = new ArrayList<>();

    private SingleInstance() {
    }

    /** What {@link #acquire} found. */
    static final class Result {
        /** True when another launcher (this app's, same user) is running. */
        final boolean otherRunning;
        /** Its process, or null when the mutex says it exists but it left no (valid) info behind. */
        final ProcessHandle other;
        private final Path infoFile;

        private Result(boolean otherRunning, ProcessHandle other, Path infoFile) {
            this.otherRunning = otherRunning;
            this.other = other;
            this.infoFile = infoFile;
        }

        /**
         * Ends the other launcher and everything it started, and makes this process the registered one.
         * The browser it launched is NOT a child process of it (CreateProcess hands it to nobody), so
         * callers still have to end the kiosk session itself - see {@link KioskSession#end}.
         *
         * @return false if the other launcher could not be ended (or there is nothing known to end).
         */
        boolean terminateOther() {
            if (other == null) {
                return false;
            }
            other.descendants().forEach(ProcessHandle::destroyForcibly);
            other.destroyForcibly();
            try {
                other.onExit().get(KILL_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return !other.isAlive();
            }
            writeInfo(infoFile);
            return true;
        }
    }

    /** Mutex name for the current user. */
    static String defaultMutexName() {
        String user = System.getProperty("user.name", "user").replaceAll("[^A-Za-z0-9_-]", "_");
        return "Global\\KioskLauncher-" + user + "-3F1E2C7A";
    }

    static Path defaultInfoFile() {
        return KioskConfig.dataDirectory().resolve("instance.txt");
    }

    static Result acquire() {
        return acquire(defaultMutexName(), defaultInfoFile());
    }

    /** Same with explicit names, so tests don't collide with a real launcher. */
    static Result acquire(String mutexName, Path infoFile) {
        Pointer mutex = Win32.Kernel32.INSTANCE.CreateMutex(Pointer.NULL, false, mutexName);
        int error = Native.getLastError();
        if (mutex == null) {
            // Could not even create it (should not happen). Failing the start over this would be worse
            // than the problem it guards against.
            writeInfo(infoFile);
            return new Result(false, null, infoFile);
        }
        HELD.add(mutex);

        if (error != ERROR_ALREADY_EXISTS) {
            writeInfo(infoFile);
            return new Result(false, null, infoFile);
        }

        // The mutex exists: is its owner really alive?
        Info recorded = readInfo(infoFile);
        if (recorded == null) {
            // Exists but no info: an older build (it never wrote one) or a launcher caught between creating
            // the mutex and writing the file. Treat it as running; we just can't name it.
            return new Result(true, null, infoFile);
        }
        ProcessHandle owner = ProcessHandle.of(recorded.pid).orElse(null);
        boolean alive = owner != null && owner.isAlive()
                && owner.info().startInstant().map(s -> Math.abs(s.toEpochMilli() - recorded.startMillis) < 2000)
                        .orElse(true);
        if (alive) {
            return new Result(true, owner, infoFile);
        }
        writeInfo(infoFile);
        return new Result(false, null, infoFile);
    }

    private record Info(long pid, long startMillis) {
    }

    private static Info readInfo(Path file) {
        try {
            long pid = -1;
            long start = 0;
            for (String line : Files.readAllLines(file)) {
                if (line.startsWith("pid=")) {
                    pid = Long.parseLong(line.substring(4).trim());
                } else if (line.startsWith("start=")) {
                    start = Long.parseLong(line.substring(6).trim());
                }
            }
            return pid > 0 ? new Info(pid, start) : null;
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    private static void writeInfo(Path file) {
        ProcessHandle self = ProcessHandle.current();
        long start = self.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, "pid=" + self.pid() + "\nstart=" + start + "\n");
        } catch (IOException e) {
            // Only costs the "end the other one" offer later - never worth failing the start.
        }
    }
}
