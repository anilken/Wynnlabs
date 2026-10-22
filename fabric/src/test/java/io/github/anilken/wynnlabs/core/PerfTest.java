package io.github.anilken.wynnlabs.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The section timer records only while on, and costs a 0 mark while off. */
class PerfTest {

    @AfterEach
    void off() {
        Perf.on = false;
        Perf.reset();
    }

    @Test
    void recordsWhileOn() throws InterruptedException {
        Perf.on = true;
        Perf.reset();
        long t = Perf.start();
        Thread.sleep(2);
        Perf.end("test.section", t);
        Perf.time("test.section", () -> { });
        long[] s = Perf.section("test.section");
        assertEquals(2, s[0]);
        assertTrue(s[2] >= 1_000_000, "worst is at least the sleep");
        assertTrue(s[1] >= s[2]);
        assertEquals("test.section", Perf.json().getAsJsonArray("sections").get(0).getAsJsonObject().get("section").getAsString());
    }

    @Test
    void recordsNothingWhileOff() {
        Perf.on = false;
        Perf.reset();
        long t = Perf.start();
        assertEquals(0L, t);
        Perf.end("test.off", t);
        Perf.time("test.off", () -> { });
        assertNull(Perf.section("test.off"));
    }

    @Test
    void resetClears() {
        Perf.on = true;
        Perf.end("test.reset", Perf.start());
        Perf.reset();
        assertNull(Perf.section("test.reset"));
    }
}
