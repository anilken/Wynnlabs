package io.github.anilken.wynnlabs.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The background writer: bursts become one write, equal text is skipped, flush writes at once. */
class SaverTest {

    @TempDir
    Path dir;

    @Test
    void aBurstIsWrittenOnceWithTheLastText() throws Exception {
        Path f = dir.resolve("burst.json");
        int before = Saver.WRITES.get();
        for (int i = 0; i < 20; i++) {
            int n = i;
            Saver.mark(f, () -> "{\"n\":" + n + "}");
        }
        assertFalse(Files.exists(f), "nothing is written right away");
        Thread.sleep(Saver.DELAY_MS + 1500);
        assertEquals("{\"n\":19}", Files.readString(f, StandardCharsets.UTF_8));
        assertEquals(before + 1, Saver.WRITES.get(), "twenty changes, one write");
    }

    @Test
    void theSameTextIsNotWrittenAgain() throws Exception {
        Path f = dir.resolve("same.json");
        assertTrue(Saver.writeNow(f, "abc"));
        assertFalse(Saver.writeNow(f, "abc"));
        assertTrue(Saver.writeNow(f, "abd"));
        Path g = dir.resolve("read.json");
        Saver.known(g, "loaded");
        assertFalse(Saver.writeNow(g, "loaded"), "what was just read is not written back");
        assertFalse(Files.exists(dir.resolve("same.json.tmp")), "no temporary file is left");
    }

    @Test
    void flushWritesPendingFilesAtOnce() throws Exception {
        Path f = dir.resolve("quit.json");
        Saver.mark(f, () -> "last chest");
        assertTrue(Saver.pending(f));
        Saver.flush();
        assertFalse(Saver.pending(f));
        assertEquals("last chest", Files.readString(f, StandardCharsets.UTF_8));
    }
}
