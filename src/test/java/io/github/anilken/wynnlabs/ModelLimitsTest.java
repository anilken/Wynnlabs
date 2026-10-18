package io.github.anilken.wynnlabs;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The model's limits: walk speed levels off, feedback stops at its cap, old nudges are clamped once. */
class ModelLimitsTest {

    @AfterEach
    void reset() {
        Rules.use(new JsonObject());
    }

    private static JsonObject model(String json) {
        JsonObject o = new JsonObject();
        o.add("model", com.google.gson.JsonParser.parseString(json).getAsJsonObject());
        return o;
    }

    @Test
    void walkSpeedLevelsOff() {
        Rules.use(new JsonObject());
        assertTrue(Damage.speedFactor(146) < 1.35, "factor at +146%: " + Damage.speedFactor(146));
        assertTrue(Damage.speedFactor(161) / Damage.speedFactor(146) < 1.03, "+15% at +146% moves the score too much");
        double slope = (Damage.speedFactor(1) - Damage.speedFactor(0)) * 100;
        assertEquals(0.3, slope, 0.01, "slope at 0 is speedWeight");
        assertTrue(Damage.speedFactor(-20) < 1, "a penalty still costs");
    }

    @Test
    void feedbackStopsAtItsCap() {
        Rules.use(model("{\"speedWeight\": 0.3}"));
        for (int i = 0; i < 10; i++) {
            JsonObject change = new JsonObject();
            Feedback.nudge(change, "speedWeight", 0.3, new ArrayList<>(), "speed");
            if (change.has("speedWeight")) {
                JsonObject m = new JsonObject();
                m.add("model", change);
                Rules.update(m);
            }
        }
        assertEquals(0.45, Rules.sub("model", "speedWeight", 0), 1e-6);
    }

    @Test
    void oldNudgesAreClampedOnceButHandValuesStay() {
        Rules.use(model("{\"speedWeight\": 1.0, \"xpWeight\": 1.15}"));
        assertEquals(0.45, Rules.sub("model", "speedWeight", 0), 1e-6);
        assertEquals(1.15, Rules.sub("model", "xpWeight", 0), 1e-6);
        assertTrue(Rules.clamped.contains("speedWeight"));
        Rules.use(model("{\"speedWeight\": 1.0, \"byHand\": [\"speedWeight\"]}"));
        assertEquals(1.0, Rules.sub("model", "speedWeight", 0), 1e-6);
        Rules.use(model("{\"speedWeight\": 1.0, \"capsApplied\": true}"));
        assertEquals(1.0, Rules.sub("model", "speedWeight", 0), 1e-6, "clamped only once");
        Rules.clamped = List.of();
    }
}
