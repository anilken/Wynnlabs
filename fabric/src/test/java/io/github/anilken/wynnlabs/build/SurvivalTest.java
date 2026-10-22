package io.github.anilken.wynnlabs.build;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.anilken.wynnlabs.coach.DeathCoach;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.model.Slot;
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
        // deaths raise it past the goal's, at most twice the table (1.2× on Damage, which keeps
        // its damage build); 0 in the road map's knob turns it off
        Rules.use(rules("{\"goalPreset\": \"SAFE\", \"model\": {\"deathFloorRatio\": 1.5}}"));
        assertEquals(13500, Damage.ehpFloor(80), 1);
        Rules.use(rules("{\"goalPreset\": \"SAFE\", \"model\": {\"deathFloorRatio\": 5}}"));
        assertEquals(18000, Damage.ehpFloor(80), 1);
        Rules.use(rules("{\"goalPreset\": \"DAMAGE\", \"model\": {\"deathFloorRatio\": 1.1}}"));
        assertEquals(9900, Damage.ehpFloor(80), 1);
        Rules.use(rules("{\"goalPreset\": \"DAMAGE\", \"model\": {\"deathFloorRatio\": 1.5}}"));
        assertEquals(10800, Damage.ehpFloor(80), 1);
        Rules.use(rules("{\"roadmap\": {\"ehpFloorScale\": 0}}"));
        assertEquals(0, Damage.ehpFloor(80));
    }

    @Test
    void belowTheFloorTheScoreFallsButNotByTheCube() {
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
            // +43 % health across the floor is worth far more than the +7 % the log alone gave, yet
            // less than the cube once asked (more^3 with the log on top): that cube made the
            // search pour every free point into Defence
            double gain = safe.objective() / thin.objective();
            assertTrue(gain > Math.pow(more, 0.5) && gain < Math.pow(more, 1.5),
                    "score " + gain + " for health ×" + more);
        } finally {
            Damage.CLASS_FOR_THREAD.remove();
        }
    }

    /** A Lv 80 Shaman with a chestplate of this much health, under these rules. */
    private static Damage.Result lifeAt(String json, double health) {
        Rules.use(rules(json));
        int[] points = new int[SkillKind.values().length];
        Damage.Piece weapon = new Damage.Piece(null, "w", "w", Slot.RELIK, 80, Map.of(), Map.of(), 500,
                Map.of("neutral", 1.0), 0);
        Damage.CLASS_FOR_THREAD.set(io.github.anilken.wynnlabs.model.Klass.SHAMAN);
        try {
            return Damage.evaluate(List.of(armour(health)), weapon, points, 80);
        } finally {
            Damage.CLASS_FOR_THREAD.remove();
        }
    }

    /** The chestplate health that gives this share of the Lv 80 Damage floor (9000); elemental health is affine in health. */
    private static double healthFor(double share) {
        String damage = "{\"goalPreset\": \"DAMAGE\"}";
        double e1 = lifeAt(damage, 5000).ehpElemental();
        double e2 = lifeAt(damage, 15000).ehpElemental();
        return 5000 + (share * 9000 - e1) * 10000 / (e2 - e1);
    }

    /** The floor's own share of the life factor: the floor on over the same build with it off. */
    private static double floorShare(double health) {
        double on = lifeAt("{\"goalPreset\": \"DAMAGE\"}", health).factors()[1];
        double off = lifeAt("{\"goalPreset\": \"DAMAGE\", \"roadmap\": {\"ehpFloorScale\": 0}}", health).factors()[1];
        return on / off;
    }

    @Test
    void nearTheFloorTheLifeFactorFallsGently() {
        double before = lifeAt("{\"goalPreset\": \"DAMAGE\"}", healthFor(1.0)).factors()[1];
        assertEquals(1, floorShare(healthFor(1.0)), 1e-3);
        assertEquals(1, floorShare(healthFor(1.2)), 1e-9);
        for (int pct = 99; pct >= 95; pct--) {
            Damage.Result r = lifeAt("{\"goalPreset\": \"DAMAGE\"}", healthFor(pct / 100.0));
            assertEquals(pct / 100.0, r.ehpElemental() / 9000, 1e-3);
            double life = r.factors()[1];
            assertTrue(life <= before, "at " + pct + " % of the floor the life factor rose: " + life + " from " + before);
            assertTrue(life / before > 0.99, "at " + pct + " % of the floor the life factor fell "
                    + (1 - life / before) * 100 + " % in one 1 % step");
            assertTrue(floorShare(healthFor(pct / 100.0)) >= 0.4);
            before = life;
        }
    }

    @Test
    void farBelowTheFloorTheLifeFactorKeepsItsBound() {
        double tenth = floorShare(healthFor(0.1));
        assertTrue(tenth >= 0.4 && tenth < 0.5, "floor share at 10 % of the floor: " + tenth);
        double none = floorShare(1);
        assertTrue(none >= 0.4, "floor share with next to no health: " + none);
        assertTrue(floorShare(healthFor(0.5)) > tenth);
    }

    @Test
    void deathLessonsAreCappedOnTheDamageGoal() {
        String off = ", \"roadmap\": {\"ehpFloorScale\": 0}";
        // on Damage a lesson of 2 weighs survival like one of 1.2; the stored value is left alone
        double capped = lifeAt("{\"goalPreset\": \"DAMAGE\", \"model\": {\"survivalLearned\": 1.2}" + off + "}", 9000).factors()[1];
        double full = lifeAt("{\"goalPreset\": \"DAMAGE\", \"model\": {\"survivalLearned\": 2.0}" + off + "}", 9000).factors()[1];
        assertEquals(capped, full, 1e-9);
        assertEquals(2.0, Rules.sub("model", "survivalLearned", 0), 1e-9);
        // Safe keeps the full 2
        double safe12 = lifeAt("{\"goalPreset\": \"SAFE\", \"model\": {\"survivalLearned\": 1.2}" + off + "}", 9000).factors()[1];
        double safe20 = lifeAt("{\"goalPreset\": \"SAFE\", \"model\": {\"survivalLearned\": 2.0}" + off + "}", 9000).factors()[1];
        assertTrue(safe20 > safe12 * 1.01, "Safe: " + safe20 + " vs " + safe12);
        // and the floor deaths raise: at most 1.2× the table on Damage, 2× elsewhere
        Rules.use(rules("{\"goalPreset\": \"DAMAGE\", \"model\": {\"deathFloorRatio\": 2.0}}"));
        assertEquals(9000 * 1.2, Damage.ehpFloor(80), 1);
        Rules.use(rules("{\"goalPreset\": \"SAFE\", \"model\": {\"deathFloorRatio\": 2.0}}"));
        assertEquals(9000 * 2.0, Damage.ehpFloor(80), 1);
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
        // the Damage goal leaves XP out (Setup.weights goalXp 0); Boss weighs it at 0.3 of the XP weight
        assertEquals(1, Damage.evaluate(List.of(xp), weapon, points, 80).factors()[4], 1e-6);
        Rules.use(rules("{\"goalPreset\": \"BOSS\"}"));
        assertEquals(1 + 0.3 * Math.log(4), Damage.evaluate(List.of(xp), weapon, points, 80).factors()[4], 1e-6);
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

    @Test
    void aNaNInTheRulesFallsBackToTheDefault() {
        // Math.min and Math.max hand a NaN on, so it passed every clamp after the read
        JsonObject model = new JsonObject();
        model.addProperty("summonAlive", Double.NaN);
        model.addProperty("ehpFloorMin", Double.POSITIVE_INFINITY);
        JsonObject r = new JsonObject();
        r.add("model", model);
        Rules.use(r);
        assertEquals(0.6, TreeModel.summonAlive(), 1e-9);
        assertEquals(0.4, Rules.sub("model", "ehpFloorMin", 0.4), 1e-9);
    }

    private static DeathCoach.Death death(long time, double ehp, double seconds, List<String> mobs) {
        return new DeathCoach.Death(time, 0, 70, 0, 70, "", false, 0, ehp, 4000, seconds, 5, mobs, new ArrayList<>());
    }
}
