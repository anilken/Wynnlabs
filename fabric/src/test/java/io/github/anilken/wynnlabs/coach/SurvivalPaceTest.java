package io.github.anilken.wynnlabs.coach;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The survival warning's pace: a beep every 4 s outside raids, a silent line every 15 s inside. */
class SurvivalPaceTest {

    private static final long T = 1_000_000;

    @Test
    void outsideRaidBeepsEveryFourSeconds() {
        assertTrue(SurvivalPace.sound(false));
        assertFalse(SurvivalPace.due(T + 3_999, T, false));
        assertTrue(SurvivalPace.due(T + 4_000, T, false));
    }

    @Test
    void insideRaidSilentAndEveryFifteenSeconds() {
        assertFalse(SurvivalPace.sound(true));
        assertFalse(SurvivalPace.due(T + 4_000, T, true));
        assertFalse(SurvivalPace.due(T + 14_999, T, true));
        assertTrue(SurvivalPace.due(T + 15_000, T, true));
    }

    @Test
    void firstWarningShowsEitherWay() {
        assertTrue(SurvivalPace.due(T, 0, false));
        assertTrue(SurvivalPace.due(T, 0, true));
    }
}
