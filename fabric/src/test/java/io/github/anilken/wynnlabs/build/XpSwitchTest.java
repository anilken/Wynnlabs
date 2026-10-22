package io.github.anilken.wynnlabs.build;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.anilken.wynnlabs.core.Rules;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The home's XP switch: off by default on the Damage goal, on for a goal that counts XP; on gives
 * the goal's own XP factor back (Damage 0.3), off leaves it at 0; a new goal sets it again, and
 * an upgrade keeps what the player chose.
 */
class XpSwitchTest {

    @AfterEach
    void reset() {
        Rules.use(new JsonObject());
    }

    private static double goalXp() {
        return Rules.sub("model", "goalXp", -1);
    }

    @Test
    void defaultsFollowTheGoal() {
        Rules.use(new JsonObject());
        Setup.setGoal("DAMAGE");
        assertFalse(Setup.countsXp(), "Damage leaves XP out by default");
        Setup.setGoal("BOSS");
        assertTrue(Setup.countsXp());
        assertEquals(0.3, goalXp(), 1e-9);
        Setup.setGoal("XP");
        assertTrue(Setup.countsXp());
    }

    @Test
    void onAndOff() {
        Rules.use(new JsonObject());
        Setup.setGoal("DAMAGE");
        Setup.setCountsXp(true);
        assertTrue(Setup.countsXp());
        assertEquals(0.3, goalXp(), 1e-9, "Damage counts XP at 0.3 when on");
        Setup.setCountsXp(false);
        assertFalse(Setup.countsXp());
        assertEquals(0, goalXp(), 1e-9);

        Setup.setGoal("XP");
        Setup.setCountsXp(false);
        assertEquals(0, goalXp(), 1e-9);
        Setup.setCountsXp(true);
        assertEquals(3, goalXp(), 1e-9, "on gives the goal's own factor back");
    }

    @Test
    void aNewGoalResetsTheSwitch() {
        Rules.use(new JsonObject());
        Setup.setGoal("DAMAGE");
        Setup.setCountsXp(true);
        assertTrue(Rules.list("model", "byHand").contains("GOALXP"));
        Setup.setGoal("BALANCED");
        assertTrue(Setup.countsXp());
        assertEquals(1, goalXp(), 1e-9);
        assertFalse(Rules.list("model", "byHand").contains("GOALXP"), "the goal's default again");
        Setup.setCountsXp(false);
        Setup.setGoal("DAMAGE");
        assertFalse(Setup.countsXp());
    }

    @Test
    void anUpgradeKeepsTheChoice() {
        // rules from step 3 with the switch turned on: Damage's 0.3 stays, by hand
        Rules.use(new JsonObject());
        Setup.setGoal("DAMAGE");
        Setup.setCountsXp(true);
        JsonObject saved = Rules.get().deepCopy();
        saved.getAsJsonObject("model").addProperty("survivalModel", 3);
        Setup.upgrade(saved);
        assertEquals(0.3, saved.getAsJsonObject("model").get("goalXp").getAsDouble(), 1e-9);

        // and one other byHand entry stays alongside
        Rules.use(JsonParser.parseString("{\"goalPreset\": \"DAMAGE\", \"model\": {\"survivalModel\": 4,"
                + " \"goalXp\": 0, \"byHand\": [\"xpWeight\"]}}").getAsJsonObject());
        Setup.setCountsXp(true);
        assertTrue(Rules.list("model", "byHand").contains("XPWEIGHT"));
        Setup.setGoal("SAFE");
        assertTrue(Rules.list("model", "byHand").contains("XPWEIGHT"));
        assertFalse(Rules.list("model", "byHand").contains("GOALXP"));
    }
}
