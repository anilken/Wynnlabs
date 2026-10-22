package io.github.anilken.wynnlabs.ui;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The hotbar's per-slot memory works a badge out once per stack and stamp, not every frame. */
class HotbarBadgesTest {

    @Test
    void sameStackAndStampIsBuiltOnce() {
        var memo = new HotbarBadges.Memo<Object, String>(9, 1000);
        AtomicInteger builds = new AtomicInteger();
        Object stack = new Object();
        for (int frame = 0; frame < 100; frame++) {
            assertEquals("SELL", memo.get(3, stack, 7, frame, s -> {
                builds.incrementAndGet();
                return "SELL";
            }));
        }
        assertEquals(1, builds.get());
    }

    @Test
    void aNewStackANewStampOrOldAgeRebuilds() {
        var memo = new HotbarBadges.Memo<Object, Integer>(9, 1000);
        AtomicInteger builds = new AtomicInteger();
        java.util.function.Function<Object, Integer> build = s -> builds.incrementAndGet();
        Object a = new Object();
        memo.get(0, a, 1, 0, build);
        memo.get(0, new Object(), 1, 10, build);        // the game sent the slot a new stack
        assertEquals(2, builds.get());
        Object b = new Object();
        memo.get(1, b, 1, 10, build);
        memo.get(1, b, 2, 20, build);                   // advice, rules or prices changed
        assertEquals(4, builds.get());
        memo.get(1, b, 2, 1020, build);                 // a second on: worked out again
        assertEquals(5, builds.get());
        memo.get(0, a, 1, 30, build);                   // slots keep their own memory
        assertEquals(6, builds.get(), "slot 0 now holds another stack than a");
    }

    @Test
    void wordStaysSharpAtEveryGuiScale() {
        assertEquals(1f, HotbarBadges.wordScale(1));
        assertEquals(0.5f, HotbarBadges.wordScale(2));
        assertEquals(2f / 3f, HotbarBadges.wordScale(3));
        assertEquals(0.5f, HotbarBadges.wordScale(4));
        assertEquals(0.6f, HotbarBadges.wordScale(5));
        assertEquals(0.5f, HotbarBadges.wordScale(6));
    }
}
