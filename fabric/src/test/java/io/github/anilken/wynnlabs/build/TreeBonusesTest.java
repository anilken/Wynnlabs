package io.github.anilken.wynnlabs.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.model.AbilityNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The ability tree's passive damage bonuses (TreeBonuses) as the game's own Archer and Shaman
 * trees word them: the proficiencies on the main attack, the masteries on their element, a
 * spell node's "+50%" on that spell and Crystal Knives on the puppets; what does not read
 * plainly is left out; and with the treeBonuses setting off the model is as it was.
 */
class TreeBonusesTest {

    private static final List<String> ARCHER_SPELLS = List.of("Arrow Bomb", "Escape", "Arrow Storm", "Arrow Shield");
    private static final List<String> SHAMAN_SPELLS = List.of("Totem", "Haul", "Uproot", "Aura");

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    private static Map<String, TreeBonuses.Bonus> bonuses(String cls, List<String> spells) throws IOException {
        List<AbilityNode> nodes = TreeModelTest.tree(cls);
        List<String> summons = new ArrayList<>();
        for (TreeModel.Read r : TreeModel.read(nodes, cls.toUpperCase(), spells)) {
            if (r.summon() && r.conv() != null && r.noun() != null) {
                summons.add(r.noun());
            }
        }
        return TreeBonuses.read(nodes, cls.toUpperCase(), spells, summons);
    }

    @Test
    void theGamesWordingIsRead() throws IOException {
        Map<String, TreeBonuses.Bonus> a = bonuses("archer", ARCHER_SPELLS);
        // "Main Attack Damage: +5%"
        assertEquals(5, a.get("Bow Proficiency").mainAttack());
        // "Earth Damage: +2-4 Earth Damage: +20%": the % on earth (the raw part is not counted)
        assertEquals(20, a.get("Earth Mastery").element()[1]);
        assertEquals(0, a.get("Earth Mastery").mainAttack());
        assertEquals(10, a.get("Thunder Mastery").element()[2]);
        // "Increase the damage of Arrow Bomb. Total Damage: +50% (of your DPS) ( Damage: +50%)"
        TreeBonuses.Bonus gunpowder = a.get("Refined Gunpowder");
        assertEquals("Arrow Bomb", gunpowder.spell());
        assertEquals(0.5, gunpowder.conv()[0], 1e-9);
        // "(of your DPS, for Arrow Shield)" names its spell
        assertEquals("Arrow Shield", a.get("Honed Shield").spell());
        // "+20% Thunder" on Shocking Bomb's +40%
        assertEquals(0.2, a.get("Shocking Bomb").conv()[2], 1e-9);

        // left out: lowerings that come with more arrows, bonuses that hold now and then, parts
        // that are not spells, more than one summon named, and a damage block without a "+"
        for (String n : List.of("Double Shots", "Triple Shots", "Nimble String", "Windstorm", "Focus", "Initiator",
                "Scorched Earth", "Vigilant Sentinels", "Beast Lore", "Fimbulwinter", "Mael's Quiver", "Pyrotechnics",
                "Arrow Wall", "Patient Hunter", "Call of the Hound", "Basaltic Trap")) {
            TreeBonuses.Bonus b = a.get(n);
            assertTrue(b == null || b.conv() == null && b.mainAttack() == 0, n + " is not counted: " + b);
        }

        Map<String, TreeBonuses.Bonus> s = bonuses("shaman", SHAMAN_SPELLS);
        assertEquals(5, s.get("Relik Proficiency I").mainAttack());
        assertEquals(15, s.get("Air Mastery").element()[5]);
        // "Increase Totem's damage. Total Damage: +4% (of your DPS)"
        assertEquals("Totem", s.get("Imbued Totem").spell());
        assertEquals(0.04, s.get("Imbued Totem").conv()[0], 1e-9);
        assertEquals(0.2, s.get("Shocking Aura").conv()[2], 1e-9);
        // "Your Puppet's knives become stronger … Total Damage: +5%": the puppets, no spell
        TreeBonuses.Bonus knives = s.get("Crystal Knives");
        assertNull(knives.spell());
        assertEquals("puppet", knives.summon());
        assertEquals(0.03, knives.conv()[0], 1e-9);
        assertEquals(0.02, knives.conv()[1], 1e-9);
        // Storm Dance names Aura and Totem; Mystic Masks and Bullwhip hold only now and then
        for (String n : List.of("Storm Dance", "Mystic Masks", "Bullwhip", "Vengeful Spirit", "Hand of the Shaman",
                "Exploding Puppets", "Totem")) {
            assertNull(s.get(n), n + " is not counted");
        }
    }

    @Test
    void theOverlayRaisesWhatTheBonusNames() throws IOException {
        List<TreeModel.Read> reads = TreeModel.read(TreeModelTest.tree("archer"), "ARCHER", ARCHER_SPELLS);
        Map<String, TreeBonuses.Bonus> b = bonuses("archer", ARCHER_SPELLS);
        List<String> taken = List.of("Arrow Bomb", "Arrow Storm", "Bow Proficiency", "Refined Gunpowder", "Earth Mastery");
        TreeModel.Overlay with = TreeModel.overlay(100, reads, ARCHER_SPELLS, taken, 1, 0, b);
        TreeModel.Overlay without = TreeModel.overlay(100, reads, ARCHER_SPELLS, taken, 1, 0);

        JsonArray rules = new JsonArray();
        rules.add(spell("Arrow Bomb", 1.6, 1));
        rules.add(spell("Arrow Storm", 0.5, 24));
        JsonArray s = with.spells(rules);
        // +50 % per bomb on the bomb only; Arrow Storm as it was
        assertEquals(2.1, s.get(0).getAsJsonObject().getAsJsonObject("conv").get("neutral").getAsDouble(), 1e-9);
        assertEquals(0.5, s.get(1).getAsJsonObject().getAsJsonObject("conv").get("neutral").getAsDouble(), 1e-9);
        assertEquals(1.6, rules.get(0).getAsJsonObject().getAsJsonObject("conv").get("neutral").getAsDouble(), 1e-9,
                "the rules stay as they are");

        Map<String, Double> stats = new HashMap<>();
        with.addStats(stats);
        assertEquals(5.0, stats.get("mainAttackDamage"));
        assertEquals(20.0, stats.get("earthDamage"));
        assertEquals(2, stats.size());
        assertNotEquals(with.key(), without.key());

        // no bonus node taken yet: the spells and the key as without bonuses
        TreeModel.Overlay early = TreeModel.overlay(10, reads, ARCHER_SPELLS, List.of("Arrow Bomb"), 1, 0, b);
        assertEquals(rules.toString(), early.spells(rules).toString().replace("\"share\":0.0", "\"share\":0.5"),
                "only Arrow Storm's share (not bought yet) differs");
        assertEquals(TreeModel.overlay(10, reads, ARCHER_SPELLS, List.of("Arrow Bomb"), 1, 0).key(), early.key(),
                "no bonus node taken: the key as before");

        // Crystal Knives: each puppet knife +5 % on its 20 %
        List<TreeModel.Read> sr = TreeModel.read(TreeModelTest.tree("shaman"), "SHAMAN", SHAMAN_SPELLS);
        Map<String, TreeBonuses.Bonus> sb = bonuses("shaman", SHAMAN_SPELLS);
        double base = TreeModel.overlay(60, sr, SHAMAN_SPELLS, List.of("Totem", "Puppet Master"), 1, 0, sb).passiveTotal();
        double knives = TreeModel.overlay(60, sr, SHAMAN_SPELLS, List.of("Totem", "Puppet Master", "Crystal Knives"), 1, 0, sb)
                .passiveTotal();
        assertEquals(1.25, knives / base, 1e-3);
    }

    private static JsonObject spell(String name, double neutral, int hits) {
        JsonObject s = new JsonObject();
        s.addProperty("name", name);
        s.addProperty("cost", 30);
        s.addProperty("share", 0.5);
        JsonObject conv = new JsonObject();
        conv.addProperty("neutral", neutral);
        s.add("conv", conv);
        if (hits > 1) {
            s.addProperty("hits", hits);
        }
        return s;
    }

    @Test
    void offLeavesTheModelAsItWas() throws IOException {
        var dir = Fixtures.root().resolve("shaman-44");
        assumeTrue(Files.exists(dir.resolve("state.json")), "no shaman-44 fixture");
        List<AbilityNode> nodes = TreeModelTest.tree("shaman");
        List<String> have = TreeModelTest.summonerAt44(nodes);
        FixtureState.load(dir).withTree(nodes, have).install("en");
        assertTrue(Settings.on("treeBonuses"), "on by default");
        TreeModel.Run on = TreeModel.run();
        assertNotNull(on);
        assertFalse(on.bonuses.isEmpty(), "the Shaman tree has bonuses");

        JsonObject off = Settings.json();
        off.addProperty("treeBonuses", false);
        Settings.use(off);
        TreeModel.Run run = TreeModel.run();
        assertTrue(run.bonuses.isEmpty());
        int level = GameState.current().level();
        TreeModel.Overlay o = run.at(level);
        TreeModel.Overlay before = TreeModel.overlay(level, run.reads, run.spellNames, run.taken(level),
                o.mainAttack, run.passiveCap);
        assertEquals(before.key(), o.key(), "the same overlay as without bonuses");
        assertTrue(o.bonus.isEmpty());
        JsonArray rules = new JsonArray();
        SHAMAN_SPELLS.forEach(n -> rules.add(spell(n, 1, 3)));
        assertEquals(before.spells(rules).toString(), o.spells(rules).toString());
        assertNotEquals(rules.toString(), on.at(100).spells(rules).toString(), "on, Imbued Totem and the rest raise spells");
        Map<String, Double> stats = new HashMap<>();
        o.addStats(stats);
        assertTrue(stats.isEmpty());
        assertNotEquals(on.at(100).key(), run.at(100).key(), "on, the plan's bonuses are in the key");
    }
}
