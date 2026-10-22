package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.model.AbilityNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The ability tree's summons (TreeModel) and the damage meter's summon calibration (Coach) on
 * one model: the shaman-44 recording as a Lv 44 Summoner with the game's Shaman tree. The
 * Totem's ticks are counted once (a lasting spell, never a tree summon too), the damage meter
 * compares the background with the very summons the build is scored with, and the calibration
 * it writes corrects them once: measured again, it stays where it is.
 */
class SummonsCountedOnceTest {

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    private static Path shaman44() {
        Path dir = Fixtures.root().resolve("shaman-44");
        assumeTrue(Files.exists(dir.resolve("state.json")), "no shaman-44 fixture");
        return dir;
    }

    /** The recording as a Lv 44 Summoner with the class profile read from the tree, its plan made. */
    private static Build.Plan summoner() throws IOException {
        List<AbilityNode> nodes = TreeModelTest.tree("shaman");
        List<String> have = TreeModelTest.summonerAt44(nodes);
        FixtureState.load(shaman44()).withTree(nodes, have).install("en");
        JsonObject tree = new JsonObject();
        tree.addProperty("archetype", "Summoner");
        JsonObject pick = new JsonObject();
        pick.add("tree", tree);
        Rules.update(pick);
        Rules.update(ClassProfiles.rulesFor("Summoner", false));
        Build.Plan plan = Build.compute();
        Build.use(plan);
        assertNotNull(plan.tree(), "the plan is scored with the tree you have");
        assertTrue(plan.tree().passiveTotal() > 0, "Puppet Master at 44");
        return plan;
    }

    /** A build's pieces but the weapon, in the order the plan sums them. */
    private static List<Damage.Piece> armour(java.util.Map<String, Damage.Piece> build) {
        List<Damage.Piece> out = new java.util.ArrayList<>();
        build.forEach((k, v) -> {
            if (!k.equals("WEAPON") && v != null) {
                out.add(v);
            }
        });
        return out;
    }

    private static Damage.SpellPart totem(Damage.Breakdown b) {
        Damage.SpellPart found = null;
        int n = 0;
        for (Damage.SpellPart sp : b.spells()) {
            if (sp.name().equals("Totem")) {
                found = sp;
                n++;
            }
        }
        assertEquals(1, n, "one Totem in the spells");
        return found;
    }

    @Test
    void theTotemIsCountedOnce() throws IOException {
        Build.Plan plan = summoner();
        // the tree reads no summon out of the Totem's own node: its ticks are the spell's
        List<TreeModel.Read> reads = TreeModel.run().reads;
        for (TreeModel.Read r : reads) {
            if (r.name().equals("Totem")) {
                assertNull(r.conv(), "the Totem is no tree summon");
            }
        }
        Damage.Breakdown with = plan.currentDetail(null, null).breakdown();
        Damage.SpellPart totem = totem(with);
        assertTrue(totem.lasting(), "the Totem lasts its 30 s alongside the casts");
        assertTrue(totem.duration() > 0);

        // the same build with the Totem left out of the spells: the summons lose exactly its ticks
        JsonObject model = Rules.get().getAsJsonObject("model");
        JsonArray spells = model.getAsJsonArray("spells");
        JsonArray noTotem = new JsonArray();
        spells.forEach(e -> {
            if (!e.getAsJsonObject().get("name").getAsString().equals("Totem")) {
                noTotem.add(e.deepCopy());
            }
        });
        JsonObject m2 = new JsonObject();
        m2.add("spells", noTotem);
        JsonObject change = new JsonObject();
        change.add("model", m2);
        Rules.update(change);
        Damage.Breakdown without = plan.currentDetail(null, null).breakdown();
        double puppets = without.summonRaw()[0];
        assertTrue(puppets > 0, "the puppets are still there");
        assertEquals(puppets + totem.perCast() / totem.duration(), with.summonRaw()[0], 1e-6 * with.summonRaw()[0],
                "summons = the tree's puppets + the Totem's ticks, each once");
    }

    @Test
    void theSummonCalibrationCorrectsTheTreesSummonsOnce() throws IOException {
        Build.Plan plan = summoner();
        // the plan's model is the one the road map scores today's level with
        TreeModel.Overlay today = TreeModel.run().at(GameState.current().level());
        assertEquals(today.key(), plan.tree().key());
        // and what weighs a piece against the plan with no overlay of its own (a verdict, a shop
        // or bank pick) takes the plan's, not the rules' whole-plan summons
        assertTrue(TreeModel.active() == plan.tree(), "the plan's tree is in force by default");
        Damage.Detail base = plan.currentDetail(null, null);
        Damage.Detail scored = TreeModel.with(today, () -> plan.currentDetail(null, null));
        assertEquals(scored.breakdown().summonRaw()[0], base.breakdown().summonRaw()[0], 1e-9);
        // without the tree the rules' summons are the whole plan's (Lv 120 puppets): not what is measured against
        double whole = TreeModel.with(null, () -> Damage.detail(armour(plan.working()), plan.working().get("WEAPON"),
                plan.wornAllocation(), plan.level())).breakdown().summonRaw()[0];
        assertTrue(Math.abs(whole - base.breakdown().summonRaw()[0]) > 1e-6,
                "the tree of today changes the summons: " + whole + " vs " + base.breakdown().summonRaw()[0]);

        double raw = base.breakdown().summonRaw()[0];
        double weight = DamageReport.mainAttackWeight();
        assertEquals(plan.model("mainAttackWeight", 0.5), weight, 1e-12);
        double main = base.breakdown().mainTotal() * weight;
        // the summons really deal 1.6 times the model's: the background between casts says so
        JsonObject measured = new JsonObject();
        Coach.bookBackground(measured, (main + 1.6 * raw) * 400, 400);
        JsonObject change = Coach.calibrationChanges(measured, Rules.get().getAsJsonObject("model"), base.breakdown(), weight);
        assertNotNull(change);
        assertEquals(1.6, change.getAsJsonObject("model").get("summonCalibration").getAsDouble(), 1e-9);
        Rules.update(change);

        Build.Plan again = Build.compute();
        Build.use(again);
        Damage.Breakdown after = again.currentDetail(null, null).breakdown();
        assertEquals(raw, after.summonRaw()[0], 1e-6 * raw, "the uncalibrated summons stay the tree's");
        assertEquals(1.6 * raw, after.summonTotal(), 1e-6 * raw, "the calibration applied once");
        // what the model now puts between casts is what was heard, so measuring again changes nothing
        assertEquals(main + 1.6 * raw, after.mainTotal() * DamageReport.mainAttackWeight() + after.summonTotal(),
                1e-6 * (main + raw));
        JsonObject twice = Coach.calibrationChanges(measured, Rules.get().getAsJsonObject("model"), after,
                DamageReport.mainAttackWeight());
        assertTrue(twice == null || !twice.has("model") || !twice.getAsJsonObject("model").has("summonCalibration"),
                "no second correction on top of the first: " + twice);
        // and the road map's scoring at today's level sees the same calibrated summons
        Damage.Detail road = TreeModel.with(today, () -> Damage.detail(armour(again.working()),
                again.working().get("WEAPON"), again.wornAllocation(), again.level()));
        assertEquals(after.summonTotal(), road.breakdown().summonTotal(), 1e-9);
    }
}
