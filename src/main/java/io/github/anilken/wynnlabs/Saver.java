package io.github.anilken.wynnlabs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Writes the mod's JSON files in the background, a few seconds after they change: a burst of
 * changes (several chests opened at once) becomes one write, the game thread never waits for
 * the disk, and a file whose text did not change is not written again. Each write goes to a
 * temporary file first and is then moved over the old one, so a crash never leaves half a file.
 * {@link #flush} writes everything pending at once (the game closing).
 *
 * <p>Callers hand a supplier that builds the file's text from a copy taken when they call
 * {@link #mark}: the supplier runs on the writer thread.
 */
final class Saver {

    /** How long after the first change a file is written. */
    static final long DELAY_MS = 3000;

    private static final Map<Path, Supplier<String>> PENDING = new ConcurrentHashMap<>();
    private static final Set<Path> SCHEDULED = ConcurrentHashMap.newKeySet();
    /** The text each file was last written with, to skip writing the same text again. */
    private static final Map<Path, String> LAST = new ConcurrentHashMap<>();
    /** Real writes, for tests and /perf. */
    static final AtomicInteger WRITES = new AtomicInteger();
    private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "WynnLabs saver");
        t.setDaemon(true);
        return t;
    });

    private Saver() {}

    /** The file at {@code path} changed: write {@code content} (built on the writer thread) soon. */
    static void mark(Path path, Supplier<String> content) {
        PENDING.put(path, content);
        if (SCHEDULED.add(path)) {
            EXEC.schedule(() -> writePending(path), DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static void writePending(Path path) {
        SCHEDULED.remove(path);
        Supplier<String> s = PENDING.remove(path);
        if (s == null) {
            return;
        }
        try {
            writeNow(path, s.get());
        } catch (Throwable t) {
            System.out.println("[WynnLabs] could not save " + path.getFileName() + ": " + t);
        }
    }

    /** Writes now, unless the text is what the file was last written with. Returns whether it wrote. */
    static synchronized boolean writeNow(Path path, String text) throws IOException {
        if (text.equals(LAST.get(path))) {
            return false;
        }
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
        LAST.put(path, text);
        WRITES.incrementAndGet();
        return true;
    }

    /** The file was read from disk with this text: a save with the same text can be skipped. */
    static void known(Path path, String text) {
        LAST.put(path, text);
    }

    /** Writes this file now if a write is waiting: call before reading it back. */
    static void settle(Path path) {
        if (PENDING.containsKey(path)) {
            writePending(path);
        }
    }

    /** Writes every pending file now, on the calling thread (the game closing). */
    static void flush() {
        for (Path p : new ArrayList<>(PENDING.keySet())) {
            writePending(p);
        }
    }

    /** For tests: whether a write is waiting. */
    static boolean pending(Path path) {
        return PENDING.containsKey(path);
    }
}
