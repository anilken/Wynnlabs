package io.github.anilken.wynnlabs.build;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.build.roadmap.BeamCache;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;
import io.github.anilken.wynnlabs.game.SyntheticTree;
import io.github.anilken.wynnlabs.model.AbilityNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The ability tree in the damage model (TreeModel): the game's own Archer and Shaman trees
 * (resources/trees, Wynntils' data) read for cost changes, summons and traps, the spells you
 * have by a level, applied per road map milestone; the class profile's archetype keys; and the
 * archetype a character leans to.
 */
class TreeModelTest {

    private record TreeFile(List<AbilityNode> nodes) {}

    private static final List<String> ARCHER_SPELLS = List.of("Arrow Bomb", "Escape", "Arrow Storm", "Arrow Shield");
    private static final List<String> SHAMAN_SPELLS = List.of("Totem", "Haul", "Uproot", "Aura");

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    static List<AbilityNode> tree(String cls) throws IOException {
        Path f = Fixtures.root().resolveSibling("trees").resolve(cls + ".json");
        return new Gson().fromJson(Files.readString(f, StandardCharsets.UTF_8), TreeFile.class).nodes();
    }

    private static TreeModel.Overlay archer(List<String> taken, double cap) throws IOException {
        List<TreeModel.Read> reads = TreeModel.read(tree("archer"), "ARCHER", ARCHER_SPELLS);
        return TreeModel.overlay(100, reads, ARCHER_SPELLS, taken, 1, cap);
    }

    private static JsonObject spell(String name, double cost, double share) {
        JsonObject s = new JsonObject();
        s.addProperty("name", name);
        s.addProperty("cost", cost);
        s.addProperty("share", share);
        s.add("conv", new JsonObject());
        return s;
    }

    @Test
    void cheaperNodesLowerTheirSpellAndManaTrapRaisesArrowBomb() throws IOException {
        TreeModel.Overlay o = archer(List.of("Arrow Bomb", "Cheaper Arrow Bomb", "Arrow Shield", "Cheaper Arrow Shield"), 0);
        assertEquals(-10.0, o.cost.get("Arrow Bomb"));
        assertEquals(-5.0, o.cost.get("Arrow Shield"));
        assertEquals(0.0, o.manaPer5);
        // Mana Trap names no spell: its "Traps" are Basaltic Trap's, which Arrow Bomb leaves
        TreeModel.Overlay later = archer(List.of("Arrow Bomb", "Cheaper Arrow Bomb", "Cheaper Arrow Bomb II",
                "Arrow Shield", "Cheaper Arrow Shield", "Basaltic Trap", "Mana Trap"), 0);
        assertEquals(-5.0, later.cost.get("Arrow Bomb"));
        assertEquals(2.5, later.manaPer5, 1e-9, "0.5 mana per second is 2.5 every 5 s");
        assertEquals(27.5, later.adjust("baseManaPer5s", 25), 1e-9);

        JsonArray rules = new JsonArray();
        rules.add(spell("Arrow Bomb", 45, 0.333));
        rules.add(spell("Escape", 20, 0));
        rules.add(spell("Arrow Storm", 35, 0.333));
        rules.add(spell("Arrow Shield", 30, 0.333));
        JsonArray s = o.spells(rules);
        assertEquals(35, s.get(0).getAsJsonObject().get("cost").getAsDouble());
        assertEquals(25, s.get(3).getAsJsonObject().get("cost").getAsDouble());
        assertEquals(0, s.get(2).getAsJsonObject().get("share").getAsDouble(), "Arrow Storm not bought yet");
        assertEquals(0.333, s.get(0).getAsJsonObject().get("share").getAsDouble());
        assertEquals(45, rules.get(0).getAsJsonObject().get("cost").getAsDouble(), "the rules stay as they are");
        assertTrue(o.spells(rules) == s, "the same rules give the same array back");
    }

    @Test
    void trapperSummonsAndTrapsHitOnTheirOwnClock() throws IOException {
        // Basaltic Trap detonates with Arrow Bomb: no clock of its own, no passive damage
        assertEquals(0, archer(List.of("Arrow Bomb", "Basaltic Trap"), 0).passiveTotal());
        // the Hound bites every 0.25 s for 40 % of your DPS, plain damage
        TreeModel.Overlay hound = archer(List.of("Arrow Shield", "Call of the Hound"), 0);
        assertEquals(1.6, hound.passive[0], 1e-9);
        assertEquals(1.6, hound.passiveTotal(), 1e-9);
        assertEquals(1.0, hound.adjust("passiveDpsFactor", 0));
        assertEquals(1.0, hound.adjust("summonHitsPerSecond", 6));
        // a second Hound
        assertEquals(3.2, archer(List.of("Arrow Shield", "Call of the Hound", "Canine Colleague"), 0).passive[0], 1e-9);
        // the Snake spits every 1 s, a third earth
        TreeModel.Overlay snake = archer(List.of("Arrow Storm", "Bryophyte Roots", "Ivyroot Mamba"), 0);
        assertTrue(snake.passive[1] > 0.3, "earth from the Snake and the roots: " + java.util.Arrays.toString(snake.passive));
        // Chilling Snare brings water, which no Trapper spell has
        TreeModel.Overlay snare = archer(List.of("Arrow Bomb", "Basaltic Trap", "Chilling Snare"), 0);
        assertTrue(snare.passive[3] > 0, "water from Chilling Snare");
        assertEquals(0, archer(List.of("Arrow Bomb", "Basaltic Trap"), 0).passive[3]);
        // past the cap the passive damage scales down, its mix of elements kept
        TreeModel.Overlay all = archer(List.of("Arrow Shield", "Call of the Hound", "Canine Colleague", "Arrow Storm",
                "Bryophyte Roots", "Ivyroot Mamba", "Arrow Bomb", "Basaltic Trap", "Chilling Snare"), 2.5);
        assertEquals(2.5, all.passiveTotal(), 1e-3);
        // no summons: the factor goes to 0 whatever the rules say
        assertEquals(0.0, archer(List.of("Arrow Bomb"), 2.5).adjust("passiveDpsFactor", 1));
    }

    @Test
    void puppetsComeOutAsTheOldSummonerNumbers() throws IOException {
        List<TreeModel.Read> reads = TreeModel.read(tree("shaman"), "SHAMAN", SHAMAN_SPELLS);
        // the Totem is a spell (its damage is the spell's), so Puppet Master is the only summon
        TreeModel.Overlay o = TreeModel.overlay(44, reads, SHAMAN_SPELLS, List.of("Totem", "Puppet Master"), 1, 0);
        // 3 puppets × 2 knives a second × (16 % neutral, 2 % earth, 2 % air): rules.model's old 6 hits of 0.16/0.02/0.02
        assertEquals(0.96, o.passive[0], 1e-9);
        assertEquals(0.12, o.passive[1], 1e-9);
        assertEquals(0.12, o.passive[5], 1e-9);
        TreeModel.Overlay more = TreeModel.overlay(44, reads, SHAMAN_SPELLS,
                List.of("Totem", "Puppet Master", "More Puppets", "Cheaper Totem"), 1, 0);
        assertEquals(1.28, more.passive[0], 1e-9, "a fourth puppet");
        assertEquals(-10.0, more.cost.get("Totem"));
    }

    @Test
    void spellsComeWithTheLevelThePlanTakesThem() throws IOException {
        List<AbilityNode> nodes = tree("archer");
        List<TreeModel.Read> reads = TreeModel.read(nodes, "ARCHER", ARCHER_SPELLS);
        List<TreePlanner.Step> plan = List.of(new TreePlanner.Step("Escape", 1, 4, ""),
                new TreePlanner.Step("Arrow Storm", 1, 8, ""), new TreePlanner.Step("Cheaper Arrow Bomb", 1, 10, ""),
                new TreePlanner.Step("Earth Mastery", 1, 12, "Trapper"), new TreePlanner.Step("Arrow Shield", 1, 15, ""));
        double early = ClassProfiles.mainAttackWeight("ARCHER", "", nodes) / ClassProfiles.mainAttackWeight("ARCHER", "Trapper", nodes);
        TreeModel.Run run = new TreeModel.Run(reads, ARCHER_SPELLS, List.of("Arrow Bomb"), plan, 3, "Trapper", early, 2.5);
        TreeModel.Overlay now = run.at(3);
        assertEquals(List.of("Arrow Bomb"), List.copyOf(now.unlocked));
        assertTrue(now.cost.isEmpty());
        TreeModel.Overlay lv10 = run.at(10);
        assertEquals(List.of("Arrow Bomb", "Escape", "Arrow Storm"), List.copyOf(lv10.unlocked));
        assertEquals(-10.0, lv10.cost.get("Arrow Bomb"));
        assertTrue(run.at(20).unlocked.contains("Arrow Shield"));
        // before the Trapper's first node the main attack weighs as for no archetype, then as a Trapper's
        assertTrue(early > 1, "an Archer's main attack counts more with no archetype: " + early);
        assertEquals(early, lv10.adjust("mainAttackWeight", 1), 1e-9);
        assertEquals(1, run.at(12).adjust("mainAttackWeight", 1), 1e-9);
        assertNotEquals(lv10.key(), run.at(12).key());
        assertEquals(run.at(10).key(), lv10.key(), "the same level, the same overlay");
    }

    @Test
    void aTreeWithoutTheGamesTextChangesNothing() {
        assertFalse(TreeModel.readable(SyntheticTree.nodes()));
        assertFalse(TreeModel.readable(null));
        assertEquals(0.5, TreeModel.adjust("mainAttackWeight", 0.5), "no overlay in force");
        assertEquals("", TreeModel.key());
    }

    // ---- the shaman-44 recording with the game's Shaman tree --------------------------------

    private static Path shaman44() {
        Path dir = Fixtures.root().resolve("shaman-44");
        assumeTrue(Files.exists(dir.resolve("state.json")), "no shaman-44 fixture");
        return dir;
    }

    /** What a Lv 44 Summoner has: the plan from the first node up to Lv 44. */
    static List<String> summonerAt44(List<AbilityNode> nodes) throws IOException {
        FixtureState.load(shaman44()).withTree(nodes, List.of()).install("en");
        List<String> have = new ArrayList<>();
        for (TreePlanner.Step s : TreePlanner.wholePlan("Summoner")) {
            if (s.level() <= 44) {
                have.add(s.name());
            }
        }
        FixtureState.uninstall();
        return have;
    }

    @Test
    void theShamanTreeChangesTheModelFromMilestoneToMilestone() throws IOException {
        List<AbilityNode> nodes = tree("shaman");
        List<String> have = summonerAt44(nodes);
        assertTrue(have.contains("Totem") && have.contains("Puppet Master"), "a Lv 44 Summoner: " + have);
        FixtureState.load(shaman44()).withTree(nodes, have).install("en");
        TreeModel.Run run = TreeModel.run();
        assertNotNull(run);
        assertEquals("Summoner", run.archetype);
        TreeModel.Overlay now = run.at(44);
        TreeModel.Overlay later = run.at(100);
        assertEquals(new java.util.HashSet<>(SHAMAN_SPELLS), new java.util.HashSet<>(now.known));
        assertTrue(now.passiveTotal() > 0, "Puppet Master at 44");
        assertTrue(later.passiveTotal() >= now.passiveTotal(), "more puppets later");
        assertNotEquals(now.key(), later.key());
        for (String s : SHAMAN_SPELLS) {
            assertTrue(later.cost.getOrDefault(s, 0.0) <= now.cost.getOrDefault(s, 0.0), s + " costs no more later");
        }
    }

    @Test
    void eachMilestoneIsScoredWithItsOwnTree() throws IOException {
        List<AbilityNode> nodes = tree("shaman");
        List<String> have = summonerAt44(nodes);
        FixtureState.load(shaman44()).withTree(nodes, have).install("en");
        TreeModel.Run run = TreeModel.run();
        List<Roadmap.Step> steps = Roadmap.build();
        assumeTrue(steps.size() > 2, "no road map");
        int checked = 0;
        for (Roadmap.Step s : steps) {
            if (s.set() == null || s.build().isEmpty() || s.level() == 44
                    || run.at(s.level()).key().equals(run.at(44).key())) {
                continue;
            }
            int earned = Math.max(0, (Math.min(s.level(), 101) - 1) * 2);
            double own = TreeModel.with(run.at(s.level()),
                    () -> Build.allocateBest(s.build(), s.level(), earned)).result().objective();
            double asNow = TreeModel.with(run.at(44),
                    () -> Build.allocateBest(s.build(), s.level(), earned)).result().objective();
            assertEquals(s.set().result().objective(), own, Math.abs(own) * 1e-9, "Lv " + s.level());
            assertNotEquals(own, asNow, "Lv " + s.level() + " scored as with today's tree");
            checked++;
        }
        assertTrue(checked > 0, "no later milestone with another tree");
        // no milestone's overlay left after the run: the build plan's own (today's tree) again
        assertTrue(TreeModel.active() == Build.plan().tree(), "no milestone's overlay left after the run");
    }

    // ---- the class profile -------------------------------------------------------------------

    @Test
    void anArchetypeChangeReplacesWhatTheLastOneOwned() throws IOException {
        List<AbilityNode> nodes = tree("shaman");
        FixtureState.load(shaman44()).withTree(nodes, summonerAt44(nodes)).install("en");
        JsonObject old = new JsonObject();
        JsonObject model = new JsonObject();
        JsonObject feel = new JsonObject();
        feel.addProperty("SLOW", 1.0);
        model.add("attackSpeedFeel", feel);
        JsonArray prio = new JsonArray();
        prio.add("DEFENCE");
        prio.add("AGILITY");
        model.add("skillPriority", prio);
        model.addProperty("summonNeutral", 0.5);
        model.addProperty("summonHitsPerSecond", 6);
        old.add("model", model);
        Rules.update(old);

        Rules.update(ClassProfiles.rulesFor("Summoner", false));
        JsonObject m = Rules.get().getAsJsonObject("model");
        assertFalse(m.has("attackSpeedFeel"), "a melee archetype's attack speed feel is gone");
        assertNotEquals(prio, m.get("skillPriority"), "the skill priority follows the archetype");
        assertEquals(1.0, m.get("passiveDpsFactor").getAsDouble(), "the Summoner's puppets");
        assertEquals(1.0, m.get("summonHitsPerSecond").getAsDouble());
        assertTrue(m.get("summonNeutral").getAsDouble() > 0 && m.get("summonNeutral").getAsDouble() != 0.5);
        assertEquals(4, m.getAsJsonArray("spells").size());

        Rules.update(ClassProfiles.rulesFor("Acolyte", false));
        m = Rules.get().getAsJsonObject("model");
        double acolyte = m.get("passiveDpsFactor").getAsDouble();
        if (acolyte == 0) {
            assertFalse(m.has("summonNeutral"), "no summons: their numbers go");
            assertFalse(m.has("summonHitsPerSecond"));
        }
        assertTrue(m.has("skillPriority"));
    }

    @Test
    void mainAttackWeightComesFromTheArchetypesNodes() throws IOException {
        List<AbilityNode> archer = tree("archer");
        double sharp = ClassProfiles.mainAttackWeight("ARCHER", "Sharpshooter", archer);
        double trapper = ClassProfiles.mainAttackWeight("ARCHER", "Trapper", archer);
        assertTrue(sharp > trapper, "Sharpshooter " + sharp + " vs Trapper " + trapper);
        assertEquals(0.25, trapper, 1e-9, "no Trapper node speaks of the main attack");
        assertEquals(1.0, ClassProfiles.mainAttackWeight("ARCHER", "Boltslinger", archer), "melee, weighed by hand");
        assertEquals(0.2, ClassProfiles.mainAttackWeight("SHAMAN", "Summoner", tree("shaman")));
    }

    // ---- the archetype a character leans to --------------------------------------------------

    @Test
    void aLowCharacterWithNoArchetypeNodeLeansToNone() throws IOException {
        List<AbilityNode> nodes = tree("shaman");
        FixtureState.load(shaman44()).withTree(nodes, List.of("Totem", "Cheaper Totem")).atLevel(3).install("en");
        assertEquals("", TreePlanner.mostUnlocked(), "Lv 3, no archetype node: none (not the tree's first)");
        FixtureState.uninstall();

        FixtureState.load(shaman44()).withTree(nodes, List.of("Totem", "Cheaper Totem", "Earth Mastery")).atLevel(3)
                .install("en");
        assertEquals("Summoner", TreePlanner.mostUnlocked(), "its first archetype node");
        FixtureState.uninstall();

        FixtureState.load(shaman44()).withTree(nodes, List.of("Totem")).atLevel(44).install("en");
        assertFalse(TreePlanner.mostUnlocked().isEmpty(), "Lv 44 with none read: the tree's first as before");
    }

    @Test
    void theGuessIsMadeOnceTheFirstArchetypeNodeIsBought() throws IOException {
        List<AbilityNode> nodes = tree("shaman");
        FixtureState low = FixtureState.load(shaman44()).withTree(nodes, List.of("Totem")).atLevel(3);
        low.install("en");
        JsonObject tree = new JsonObject();
        tree.addProperty("archetype", "");
        tree.addProperty("guess", 1);
        JsonObject change = new JsonObject();
        change.add("tree", tree);
        Rules.update(change);
        assertEquals("", Setup.reguess(), "nothing to lean to yet");
        assertEquals("", TreePlanner.chosen());
        assertEquals("", TreeModel.archetype());

        low.withTree(nodes, List.of("Totem", "Earth Mastery"));
        assertEquals("Summoner", Setup.reguess());
        assertEquals("Summoner", TreePlanner.chosen());
        assertFalse(Rules.get().getAsJsonObject("tree").has("guess"), "guessed once");
        assertTrue(Rules.get().getAsJsonObject("model").has("spells"), "the class profile followed");
        assertEquals("", Setup.reguess(), "a chosen archetype is never guessed again");
    }

    /**
     * Debug only (-PdebugDump): the model per milestone and the road map's picks with and without
     * the Shaman tree, in fabric/build/tree-dump.txt.
     */
    @Test
    void dump() throws IOException {
        assumeTrue(Boolean.getBoolean("wynnlabs.debugDump"), "debug dump off");
        List<AbilityNode> nodes = tree("shaman");
        List<String> have = summonerAt44(nodes);
        StringBuilder out = new StringBuilder("unlocked at 44: " + have + "\n");
        // a new character's plan from the first node: the spells by level
        FixtureState.load(shaman44()).withTree(nodes, List.of()).fresh().install("en");
        TreeModel.Run fresh = TreeModel.run();
        for (int lv : new int[] {1, 4, 8, 12, 20, 30, 44, 60, 80, 100, 120}) {
            TreeModel.Overlay o = fresh.at(lv);
            out.append(String.format(java.util.Locale.ROOT, "fresh Lv %d spells=%s cost=%s passive=%.2f main×%.2f%n",
                    lv, o.unlocked, o.cost, o.passiveTotal(), o.mainAttack));
        }
        FixtureState.uninstall();
        // the first pass warms the JIT up; each pass searches anew (no beam cache)
        for (int pass = 0; pass < 3; pass++) {
            FixtureState fs = FixtureState.load(shaman44());
            if (pass == 2) {
                fs.withTree(nodes, have);
            }
            fs.install("en");
            BeamCache.clear();
            TreeModel.Run run = TreeModel.run();
            long t0 = System.nanoTime();
            List<Roadmap.Step> steps = Roadmap.build();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            out.append(pass == 0 ? "== warm-up" : pass == 1 ? "== without the tree" : "== with the Shaman tree")
                    .append(" (").append(ms).append(" ms)\n");
            for (Roadmap.Step s : steps) {
                out.append("Lv ").append(s.level());
                TreeModel.Overlay o = run == null ? null : run.at(s.level());
                if (o != null) {
                    out.append(" spells=").append(o.unlocked).append(" cost=").append(o.cost)
                            .append(String.format(java.util.Locale.ROOT, " passive=%.2f mana+%.1f main×%.2f",
                                    o.passiveTotal(), o.manaPer5, o.mainAttack));
                }
                if (s.set() != null && s.set().result().pieces() != null) {
                    Damage.Result r = s.set().result();
                    Damage.Detail d = TreeModel.with(o, () -> Damage.detail(r.pieces(), r.weapon(), s.set().allocation(), s.level()));
                    out.append(String.format(java.util.Locale.ROOT, " | dps spell %.0f main %.0f summon %.0f (summon share %.0f%%)",
                            d.breakdown().spellTotal(), d.breakdown().mainTotal(), d.breakdown().summonTotal(),
                            100 * d.breakdown().summonTotal() / Math.max(1e-9, d.breakdown().total())));
                }
                out.append('\n');
                Map<String, Damage.Piece> b = s.build();
                b.forEach((slot, p) -> out.append("   ").append(slot).append('=').append(p == null ? "-" : p.name()).append('\n'));
            }
            FixtureState.uninstall();
        }
        Path f = Path.of(System.getProperty("wynnlabs.projectDir", "."), "fabric", "build", "tree-dump.txt");
        Files.createDirectories(f.getParent());
        Files.writeString(f, out, StandardCharsets.UTF_8);
    }
}
