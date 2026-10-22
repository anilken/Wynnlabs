package io.github.anilken.wynnlabs.build;

import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.coach.Coach;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.GameState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The summons as the damage meter hears them (the shaman-44 recording as a Lv 44 Summoner):
 * a steady, weak background takes the summon calibration well below 0.5, the main attack is
 * taken off the background at its full modelled rate whatever the goal's main attack weight,
 * and the summons count only for the share of time they are alive, once, from the tree or
 * from the class profile.
 */
class SummonCalibrationFloorTest {

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    private static void calibration(double v) {
        JsonObject m = new JsonObject();
        m.addProperty("summonCalibration", v);
        JsonObject change = new JsonObject();
        change.add("model", m);
        Rules.update(change);
    }

    private static void alive(double v) {
        JsonObject m = new JsonObject();
        m.addProperty("summonAlive", v);
        JsonObject change = new JsonObject();
        change.add("model", m);
        Rules.update(change);
    }

    private static double written(JsonObject change) {
        assertNotNull(change, "a calibration is written");
        return change.getAsJsonObject("model").get("summonCalibration").getAsDouble();
    }

    @Test
    void aWeakSteadyBackgroundTakesTheSummonsWellDown() throws IOException {
        Build.Plan plan = SummonsCountedOnceTest.summoner();
        calibration(1.0);
        Damage.Breakdown b = plan.currentDetail(null, null).breakdown();
        assertTrue(b.summonRaw()[0] > 362, "the model's summons are far above what is heard: " + b.summonRaw()[0]);
        JsonObject measured = new JsonObject();
        Coach.bookBackground(measured, 362.0 * 600, 600);
        JsonObject model = Rules.get().getAsJsonObject("model");
        double half = written(Coach.calibrationChanges(measured, model, b));
        assertTrue(half < 0.5, "362/s steady: summon calibration " + half);
        assertTrue(half >= 0.05, "never below the floor: " + half);
    }

    @Test
    void theMainAttackIsTakenOffAtItsFullRate() throws IOException {
        Build.Plan plan = SummonsCountedOnceTest.summoner();
        calibration(1.0);
        Damage.Breakdown b = plan.currentDetail(null, null).breakdown();
        double raw = b.summonRaw()[0];
        double main = b.mainTotal();
        assertTrue(main > 0 && raw > 0);
        // what is heard: every main attack hit plus twice the model's summons
        JsonObject measured = new JsonObject();
        Coach.bookBackground(measured, (main + 2 * raw) * 600, 600);
        JsonObject model = Rules.get().getAsJsonObject("model");
        // whatever the goal's main attack weight: it is not an input any more
        assertEquals(2.0, written(Coach.calibrationChanges(measured, model, b)), 1e-9);
    }

    @Test
    void summonBoundsReachDownOnceSteady() {
        assertEquals(0.05, Coach.boundedSummon(0.0, true));
        assertEquals(0.05, Coach.boundedSummon(-3, true));
        assertEquals(0.12, Coach.boundedSummon(0.1234, true));
        assertEquals(10.0, Coach.boundedSummon(25, true));
        assertEquals(0.7, Coach.boundedSummon(0.1, false), "not steady: the narrow bounds stay");
        assertEquals(1.4, Coach.boundedSummon(5, false));
        assertEquals(0.5, Coach.bounded(0.1, true), "the spells keep their 0.5 floor");
    }

    @Test
    void theTreesSummonsCountTheAliveShareOnce() throws IOException {
        List<TreeModel.Read> reads = TreeModel.read(TreeModelTest.tree("shaman"), "SHAMAN",
                List.of("Totem", "Haul", "Uproot", "Aura"));
        TreeModel.Overlay o = TreeModel.overlay(44, reads, List.of("Totem", "Haul", "Uproot", "Aura"),
                List.of("Totem", "Puppet Master"), 1, 0);
        // 3 puppets × 2 knives a second × 16 % neutral, alive 0.6 of the time
        assertEquals(0.96 * 0.6, o.passive[0], 1e-9);
        assertEquals(1.0, o.adjust("summonAlive", 0.6), "already in the tree's numbers");
    }

    @Test
    void summonsCountWhileAliveOnceWithTheTreeAndWithout() throws IOException {
        Build.Plan plan = SummonsCountedOnceTest.summoner();
        List<Damage.Piece> armour = SummonsCountedOnceTest.armour(plan.working());
        Damage.Piece weapon = plan.working().get("WEAPON");
        int[] alloc = plan.wornAllocation();
        int level = plan.level();
        double[] tree = new double[2];
        double[] profile = new double[2];
        double[] values = {1.0, 0.6};
        for (int i = 0; i < 2; i++) {
            alive(values[i]);
            TreeModel.Overlay today = TreeModel.run().at(GameState.current().level());
            Damage.Breakdown t = TreeModel.with(today, () -> Damage.detail(armour, weapon, alloc, level)).breakdown();
            Damage.Breakdown p = TreeModel.with(null, () -> Damage.detail(armour, weapon, alloc, level)).breakdown();
            tree[i] = puppets(t);
            profile[i] = puppets(p);
            // the summon part the breakdown shows is the same summons
            assertEquals(t.summonRaw()[0] * Rules.sub("model", "summonCalibration", 1.0), t.summonTotal(),
                    1e-6 * t.summonTotal());
        }
        assertTrue(tree[0] > 0 && profile[0] > 0);
        assertEquals(0.6, tree[1] / tree[0], 1e-3, "tree summons × alive once, not twice");
        assertEquals(0.6, profile[1] / profile[0], 1e-3, "class profile summons × alive once");
    }

    /** The summons without the lasting spells' ticks (the Totem lasts on its own clock). */
    private static double puppets(Damage.Breakdown b) {
        double lasting = 0;
        for (Damage.SpellPart sp : b.spells()) {
            if (sp.lasting() && sp.duration() > 0) {
                lasting += sp.perCast() / sp.duration();
            }
        }
        return b.summonRaw()[0] - lasting;
    }
}
