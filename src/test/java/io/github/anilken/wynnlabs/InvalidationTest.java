package io.github.anilken.wynnlabs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** What invalidates what: prices only touch price-dependent caches; equal calibrations change nothing. */
class InvalidationTest {

    @Test
    void aPriceChangeMovesTheStampButNotTheRules() {
        int rules = Rules.version;
        int stamp = Stamp.now();
        Prices.version++;
        assertEquals(rules, Rules.version, "the build plan and damage report key on Rules.version only");
        assertNotEquals(stamp, Stamp.now(), "advice that weighs prices rebuilds");
    }

    @Test
    void anAdviceChangeMovesTheStamp() {
        int stamp = Stamp.now();
        Advice.version++;
        assertNotEquals(stamp, Stamp.now());
    }

    @Test
    void theRulesHashFollowsTheRules() {
        assertEquals(Rules.get().toString().hashCode(), Rules.hash());
        Rules.version++;                            // a change: worked out again, same document, same hash
        assertEquals(Rules.get().toString().hashCode(), Rules.hash());
    }

    @Test
    void anEqualCalibrationIsNoChange() {
        // measured 1100 per cast, the model said 1000: 1.10, already stored
        assertNull(Coach.calibrationChange(1.10, 1100, 1000));
        assertNull(Coach.calibrationChange(1.10, 1102, 1000), "rounds to the stored value");
    }

    @Test
    void aDifferentCalibrationIsWrittenWithinBounds() {
        var change = Coach.calibrationChange(1.0, 1200, 1000);
        assertNotNull(change);
        assertEquals(1.2, change.getAsJsonObject("model").get("spellCalibration").getAsDouble(), 1e-9);
        var capped = Coach.calibrationChange(1.0, 5000, 1000);
        assertEquals(1.4, capped.getAsJsonObject("model").get("spellCalibration").getAsDouble(), 1e-9);
    }
}
