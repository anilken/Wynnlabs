package io.github.anilken.wynnlabs.build;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.anilken.wynnlabs.coach.Feedback;
import io.github.anilken.wynnlabs.core.Rules;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * XP weighs little on the fighting goals: Damage and Boss scale the XP weight by 0.3, rules saved
 * before that get it once (a value set by hand stays), and feedback never raises the XP weight
 * while a fighting goal is set.
 */
class FightingGoalXpTest {

    private static final int GOAL_XP = 6;

    @AfterEach
    void reset() {
        Rules.use(new JsonObject());
    }

    private static JsonObject rules(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void fightingGoalsWeighXpLittle() {
        assertEquals(0.3, Setup.weights("DAMAGE")[GOAL_XP], 1e-9);
        assertEquals(0.3, Setup.weights("BOSS")[GOAL_XP], 1e-9);
        // Safe is a survival goal that still levels; the levelling goals keep theirs
        assertEquals(1, Setup.weights("SAFE")[GOAL_XP], 1e-9);
        assertEquals(3, Setup.weights("XP")[GOAL_XP], 1e-9);
    }

    @Test
    void oldRulesGetTheGoalXpOnceAndHandValuesStay() {
        JsonObject old = rules("{\"goalPreset\": \"DAMAGE\", \"model\": {\"survivalModel\": 2, \"goalXp\": 1, \"xpWeight\": 1.5}}");
        Setup.upgrade(old);
        JsonObject m = old.getAsJsonObject("model");
        assertEquals(0.3, m.get("goalXp").getAsDouble(), 1e-9);
        assertEquals(1.5, m.get("xpWeight").getAsDouble(), 1e-9, "the player's own XP weight is not the goal's");
        assertEquals(Setup.SURVIVAL_MODEL, m.get("survivalModel").getAsInt());
        // once: a goal factor changed after the upgrade stays
        m.addProperty("goalXp", 0.8);
        Setup.upgrade(old);
        assertEquals(0.8, m.get("goalXp").getAsDouble(), 1e-9);

        JsonObject boss = rules("{\"goalPreset\": \"BOSS\", \"model\": {\"survivalModel\": 2, \"goalXp\": 0.5}}");
        Setup.upgrade(boss);
        assertEquals(0.3, boss.getAsJsonObject("model").get("goalXp").getAsDouble(), 1e-9);

        JsonObject hand = rules("{\"goalPreset\": \"DAMAGE\", \"model\": {\"survivalModel\": 2, \"goalXp\": 1, \"byHand\": [\"goalXp\"]}}");
        Setup.upgrade(hand);
        assertEquals(1, hand.getAsJsonObject("model").get("goalXp").getAsDouble(), 1e-9);
    }

    @Test
    void theStepFromTwoKeepsWhatFeedbackTaught() {
        // rules at 2 already have the survival model of 2: only the goal XP factor is new, and
        // the summon calibration was fitted against summons that never died
        JsonObject two = rules("{\"goalPreset\": \"DAMAGE\", \"model\": {\"survivalModel\": 2, \"goalXp\": 1,"
                + " \"survivalWeight\": 0.33, \"survivalPower\": 0.4, \"ehpFloorScale\": 1.2, \"xpLog\": 0,"
                + " \"summonCalibration\": 3.5}}");
        Setup.upgrade(two);
        JsonObject m = two.getAsJsonObject("model");
        assertEquals(0.3, m.get("goalXp").getAsDouble(), 1e-9);
        assertEquals(0.33, m.get("survivalWeight").getAsDouble(), 1e-9, "taught survival weight kept");
        assertEquals(0.4, m.get("survivalPower").getAsDouble(), 1e-9);
        assertEquals(1.2, m.get("ehpFloorScale").getAsDouble(), 1e-9);
        assertEquals(0, m.get("xpLog").getAsDouble(), 1e-9);
        assertEquals(1, m.get("summonCalibration").getAsDouble(), 1e-9, "summon calibration starts over");

        // below 2 the whole list is reset as before
        JsonObject one = rules("{\"goalPreset\": \"DAMAGE\", \"model\": {\"survivalModel\": 1, \"survivalWeight\": 0.33}}");
        Setup.upgrade(one);
        assertEquals(Setup.weights("DAMAGE")[0], one.getAsJsonObject("model").get("survivalWeight").getAsDouble(), 1e-9);
    }

    private static Damage.Result result(double xp, double speed) {
        return new Damage.Result(1000, 100, 10, 1, 500, xp, speed, 50, new double[0], 500, 300);
    }

    @Test
    void feedbackLeavesTheXpWeightOnAFightingGoal() {
        Rules.use(rules("{\"goalPreset\": \"DAMAGE\", \"model\": {\"xpLog\": 1}}"));
        List<String> nudged = new ArrayList<>();
        JsonObject change = Feedback.nudges(result(10, 0), result(40, 5), 0.23, nudged);
        assertFalse(change.has("xpWeight"), "XP weight nudged on a fighting goal: " + change);
        assertTrue(change.has("speedWeight"), "other nudges still apply: " + change);

        // a levelling goal still learns that you like XP
        Rules.use(rules("{\"goalPreset\": \"XP\", \"model\": {\"xpLog\": 0}}"));
        change = Feedback.nudges(result(10, 0), result(40, 5), 0.23, new ArrayList<>());
        assertTrue(change.has("xpWeight"), "levelling goal: " + change);
    }
}
