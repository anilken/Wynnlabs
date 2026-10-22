package io.github.anilken.wynnlabs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.anilken.wynnlabs.model.Slot;
import io.github.anilken.wynnlabs.model.SkillKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Staying alive in the score: the health floor by level and goal (raised by deaths), what deaths
 * teach kept apart from the goal and fading after a quiet week, rules from before brought up to
 * date, and XP with diminishing returns for the fighting goals.
 */
class SurvivalTest {

    private static final long DAY = 24 * 3600_000L;

    @AfterEach
    void reset() {
        Rules.use(new JsonObject());
    }

    private static JsonObject rules(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void theFloorFollowsLevelGoalAndDeaths() {
        Rules.use(rules("{\"goalPreset\": \"DAMAGE\"}"));
        assertEquals(6500, Damage.ehpFloor(70), 1);
        assertEquals(9000, Damage.ehpFloor(80), 1);
        assertEquals(16000, Damage.ehpFloor(100), 1);
        assertEquals(10500, Damage.ehpFloor(85), 1);
        Rules.use(rules("{\"goalPreset\": \"TANK\"}"));
        assertEquals(9000 * 1.6, Damage.ehpFloor(80), 1);
        // deaths raise it past the goal's, at most twice the table; 0 in the road map's knob turns it off
        Rules.use(rules("{\"goalPreset\": \"DAMAGE\", \"model\": {\"deathFloorRatio\": 1.5}}"));
        assertEquals(13500, Damage.ehpFloor(80), 1);
        Rules.use(rules("{\"goalPreset\": \"DAMAGE\", \"model\": {\"deathFloorRatio\": 5}}"));
        assertEquals(18000, Damage.ehpFloor(80), 1);
        Rules.use(rules("{\"roadmap\": {\"ehpFloorScale\": 0}}"));
        assertEquals(0, Damage.ehpFloor(80));
    }

    @Test
    void belowTheFloorTheScoreFallsSteeply() {
        Rules.use(rules("{\"goalPreset\": \"DAMAGE\"}"));
        int[] points = new int[SkillKind.values().length];
        Damage.Piece weapon = new Damage.Piece(null, "w", "w", Slot.RELIK, 80, Map.of(), Map.of(), 500,
                Map.of("neutral", 1.0), 0);
        // health for about 6.7k and 9.6k elemental effective health at Lv 80 (a Shaman takes 1.4×)
        Damage.CLASS_FOR_THREAD.set(io.github.anilken.wynnlabs.model.Klass.SHAMAN);
        try {
            Damage.Result thin = Damage.evaluate(List.of(armour(9000)), weapon, points, 80);
            Damage.Result safe = Damage.evaluate(List.of(armour(13000)), weapon, points, 80);
            assertTrue(thin.ehpElemental() < 9000 && safe.ehpElemental() > 9000, thin.ehpElemental() + " " + safe.ehpElemental());
            double more = safe.ehpElemental() / thin.ehpElemental();
            // +43 % health across the floor is worth far more than the +7 % the log alone gave
            assertTrue(safe.objective() / thin.objective() > Math.pow(more, 1.5),
                    "score " + safe.objective() / thin.objective() + " for health ×" + more);
        } finally {
            Damage.CLASS_FOR_THREAD.remove();
        }
    }

    private static Damage.Piece armour(double health) {
        return new Damage.Piece(null, "a" + health, "a", Slot.CHESTPLATE, 80, Map.of(), Map.of(), 0, Map.of(), health);
    }

    @Test
    void xpCountsWithDiminishingReturnsForFightingGoals() {
        int[] points = new int[SkillKind.values().length];
        Damage.Piece weapon = new Damage.Piece(null, "w", "w", Slot.RELIK, 80, Map.of(), Map.of(), 500,
                Map.of("neutral", 1.0), 0);
        Damage.Piece xp = new Damage.Piece(null, "x", "x", Slot.RING, 80, Map.of("xpBonus", 300.0), Map.of(), 0,
                Map.of(), 0);
        Rules.use(rules("{\"goalPreset\": \"DAMAGE\"}"));
        assertEquals(1 + Math.log(4), Damage.evaluate(List.of(xp), weapon, points, 80).factors()[4], 1e-6);
        Rules.use(rules("{\"goalPreset\": \"BALANCED\"}"));
        assertEquals(4, Damage.evaluate(List.of(xp), weapon, points, 80).factors()[4], 1e-6);
    }

    @Test
    void oldRulesGetTheGoalsSurvivalAndKeepWhatDeathsTaught() {
        Rules.use(rules("{\"goalPreset\": \"SAFE\", \"model\": {\"capsApplied\": true, \"survivalWeight\": 1.61,"
                + " \"survivalWeightBase\": 1.4, \"survivalPower\": 0}}"));
        assertEquals(0.7, Rules.sub("model", "survivalWeight", 0), 1e-9);
        assertEquals(0.6, Rules.sub("model", "survivalPower", 0), 1e-9);
        assertEquals(1.3, Rules.sub("model", "ehpFloorScale", 0), 1e-9);
        assertEquals(1.15, Rules.sub("model", "survivalLearned", 0), 1e-9);
        assertFalse(Rules.get().getAsJsonObject("model").has("survivalWeightBase"));
        // a weight set by hand stays
        Rules.use(rules("{\"goalPreset\": \"DAMAGE\", \"model\": {\"survivalWeight\": 0.9, \"byHand\": [\"survivalWeight\"]}}"));
        assertEquals(0.9, Rules.sub("model", "survivalWeight", 0), 1e-9);
        assertEquals(0.25, Rules.sub("model", "survivalPower", 0), 1e-9);
    }

    @Test
    void choosingAGoalKeepsWhatDeathsTaught() {
        Rules.use(rules("{\"goalPreset\": \"DAMAGE\", \"model\": {\"survivalLearned\": 1.5}}"));
        Setup.setGoal("BALANCED");
        assertEquals(1.5, Rules.sub("model", "survivalLearned", 0), 1e-9);
        assertEquals(0.4, Rules.sub("model", "survivalWeight", 0), 1e-9);
    }

    @Test
    void theLessonFadesAfterAQuietWeek() {
        long t0 = 1_000 * DAY;
        assertEquals(1.6, DeathCoach.decay(1.6, t0, t0, t0 + 3 * DAY)[0], 1e-9);
        // a death three days ago resets the week
        assertEquals(1.6, DeathCoach.decay(1.6, t0, t0 + 5 * DAY, t0 + 8 * DAY)[0], 1e-9);
        double[] once = DeathCoach.decay(1.6, t0, t0, t0 + 8 * DAY);
        assertEquals(1.3, once[0], 1e-9);
        assertEquals(t0 + 8 * DAY, (long) once[1]);
        assertEquals(1.0, DeathCoach.decay(1.03, t0, t0, t0 + 8 * DAY)[0], 1e-9);
    }

    @Test
    void deathsInFightsRaiseTheFloor() {
        long now = 1_000 * DAY;
        List<DeathCoach.Death> deaths = new ArrayList<>();
        // three Lv 70 fights lost in 3 s at 6k effective health: 2k a second, 8k to last 4 s
        for (int i = 0; i < 3; i++) {
            deaths.add(death(now - i * 3600_000L, 6000, 3, List.of("Mob")));
        }
        assertEquals(8000 / 6500.0, DeathCoach.floorRatio(deaths, now), 1e-6);
        // falls (no mobs), instant deaths and old ones say nothing
        List<DeathCoach.Death> noise = new ArrayList<>(deaths.subList(0, 2));
        noise.add(death(now, 6000, 3, List.of()));
        noise.add(death(now, 6000, 0.2, List.of("Mob")));
        noise.add(death(now - 8 * DAY, 6000, 3, List.of("Mob")));
        assertEquals(0, DeathCoach.floorRatio(noise, now));
    }

    private static DeathCoach.Death death(long time, double ehp, double seconds, List<String> mobs) {
        return new DeathCoach.Death(time, 0, 70, 0, 70, "", false, 0, ehp, 4000, seconds, 5, mobs, new ArrayList<>());
    }
}
