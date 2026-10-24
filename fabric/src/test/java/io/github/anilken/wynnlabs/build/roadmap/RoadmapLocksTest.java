package io.github.anilken.wynnlabs.build.roadmap;

import io.github.anilken.wynnlabs.build.Around;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.build.Locks;
import io.github.anilken.wynnlabs.build.Tuning;
import io.github.anilken.wynnlabs.core.Saver;
import io.github.anilken.wynnlabs.game.Expected;
import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.model.Slot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * "Build around the items I choose" on the recorded Shaman (Lv 44, an Intelligence and Agility
 * relik worn): a chosen piece stays in its slot on every milestone, the rest of the set moves
 * to suit it, nothing chosen gives the map as before, and pieces that cannot be worn together
 * give a reason instead of a broken map.
 */
public class RoadmapLocksTest {

    /** A Dexterity relik (Dex 25) the recorded character does not wear. */
    public static final String DEX_RELIK = "Covetous Thunder";

    @AfterEach
    void reset() {
        Locks.use(null);
        Locks.useTuning(null);
        Locks.rootOverride = null;
        Locks.forget();
        FixtureState.uninstall();
    }

    public static Path shaman() {
        Path dir = Fixtures.root().resolve("shaman-44");
        assumeTrue(Files.exists(dir.resolve("state.json")), "no shaman-44 fixture");
        return dir;
    }

    /** The whole road map around {@code names}, nothing else tuned. */
    static Roadmap.Computed around(List<String> names) {
        return Roadmap.compute(new Roadmap.Inputs(names, Tuning.Choice.PLAIN, false, 0), null);
    }

    /** Every milestone's set as "level slot=name", in order. */
    public static List<String> sets(List<Roadmap.Step> steps) {
        List<String> out = new ArrayList<>();
        for (Roadmap.Step s : steps) {
            s.build().forEach((slot, p) -> out.add(s.level() + " " + slot + "=" + (p == null ? "-" : p.name())));
        }
        return out;
    }

    /** Every gear pick as "level slot name", in order. */
    static List<String> picks(List<Roadmap.Step> steps) {
        List<String> out = new ArrayList<>();
        for (Roadmap.Step s : steps) {
            s.gear().forEach(g -> out.add(s.level() + " " + g.slot() + " " + g.info().name()));
        }
        return out;
    }

    @Test
    void aChosenItemStaysInItsSlot() throws Exception {
        FixtureState.load(shaman()).install("en");
        Roadmap.Computed c = around(List.of(DEX_RELIK));
        List<Roadmap.Step> steps = c.steps();
        assertFalse(steps.isEmpty(), "steps");
        assertEquals(List.of(DEX_RELIK), c.around());
        assertNull(c.problem());
        for (Roadmap.Step s : steps) {
            Damage.Piece w = s.build().get("WEAPON");
            assertNotNull(w, "Lv " + s.level() + " has a weapon");
            assertEquals(DEX_RELIK, w.name(), "Lv " + s.level() + " keeps the chosen relik");
            // a set with an allocation: every skill point it asks for, the relik's Dexterity too, is met
            assertNotNull(s.set(), "Lv " + s.level() + ": the set around it can be worn");
            for (Roadmap.Gear g : s.gear()) {
                if (g.info().slot().isWeapon()) {
                    assertEquals(DEX_RELIK, g.info().name(), "Lv " + s.level() + ": no other weapon is picked");
                }
            }
        }
        // the first milestone shows it as a row (you do not wear it), with its price and source as any pick
        Roadmap.Gear row = steps.get(0).gear().stream().filter(g -> g.info().name().equals(DEX_RELIK)).findFirst().orElse(null);
        assertNotNull(row, "a row for the chosen relik");
        assertEquals("RELIK", row.slot());
        assertTrue(Double.isNaN(row.gain()), "chosen, not picked: no % of its own");
        assertNotNull(row.source());
    }

    @Test
    void theOtherSlotsChangeToFitIt() throws Exception {
        FixtureState.load(shaman()).install("en");
        List<String> plain = sets(around(List.of()).steps());
        List<Roadmap.Step> steps = around(List.of(DEX_RELIK)).steps();
        List<String> changed = new ArrayList<>();
        for (String line : sets(steps)) {
            if (!line.contains(" WEAPON=") && !plain.contains(line)) {
                changed.add(line);
            }
        }
        Fixtures.note("around " + DEX_RELIK + ": " + changed);
        assertFalse(changed.isEmpty(), "another slot moves to suit the Dexterity relik");
        // the set is scored with the relik in it: its points go to Dexterity
        int dex = steps.get(0).set().allocation()[SkillKind.DEXTERITY.ordinal()];
        assertTrue(dex >= 15, "Dexterity spent for the relik: " + dex);
    }

    @Test
    void aChosenRingHoldsOneHand() throws Exception {
        FixtureState.load(shaman()).install("en");
        Roadmap.Computed c = around(List.of("Morph-Emerald"));
        assertNull(c.problem());
        for (Roadmap.Step s : c.steps()) {
            boolean held = s.build().get("RING1") != null && s.build().get("RING1").name().equals("Morph-Emerald")
                    || s.build().get("RING2") != null && s.build().get("RING2").name().equals("Morph-Emerald");
            assertTrue(held, "Lv " + s.level() + " wears the chosen ring");
            assertTrue(s.gear().stream().filter(g -> g.slot().equals("RING")).count() <= 1, "Lv " + s.level() + " one RING row");
            assertTrue(s.gear().stream().filter(g -> g.slot().equals(Roadmap.RING2)).count() <= 1, "Lv " + s.level() + " one RING2 row");
        }
    }

    @Test
    void nothingChosenGivesTodaysMap() throws Exception {
        Path dir = shaman();
        FixtureState.load(dir).install("en");
        List<String> diff = Expected.check(dir, "roadmap.en.json", Roadmap.json(), Expected::json);
        assertTrue(diff.isEmpty(), String.join("\n", diff));
        Roadmap.Computed c = around(List.of());
        assertTrue(c.around().isEmpty());
        assertNull(c.problem());
        assertEquals(picks(Roadmap.build()), picks(c.steps()), "the engine with nothing chosen is the J menu's map");
    }

    @Test
    void piecesThatCannotBeWornTogetherGiveAReason() throws Exception {
        FixtureState.load(shaman()).install("en");
        List<String> plain = picks(around(List.of()).steps());
        // Strength 40, Agility 35 and Dexterity 25 at Lv 44: 100 points, 86 earned
        List<String> chosen = List.of("Psychopomp's Pileus", "Outlaw Tunic", DEX_RELIK);
        List<ItemData> items = Locks.resolve(GameState.current(), chosen);
        assertEquals(3, items.size(), "all three can be chosen alone");
        assertNotNull(Locks.problem(items, 44), "together they need more points than earned");
        Roadmap.Computed c = around(chosen);
        assertFalse(c.steps().isEmpty(), "still a map");
        assertNotNull(c.problem(), "the map says why");
        assertTrue(c.around().isEmpty(), "nothing held");
        assertEquals(plain, picks(c.steps()), "the map as without them");
        String why = c.problem().text();
        assertTrue(why.contains("86"), why);
    }

    @Test
    void aChosenItemAboveYourLevelPlansForItsLevel() throws Exception {
        // a Lv 90 relik (Dexterity 70) chosen on a Lv 66 character
        FixtureState.load(shaman()).atLevel(66).install("en");
        Roadmap.Computed c = Roadmap.compute(new Roadmap.Inputs(List.of("Loaded Question"), Tuning.Choice.PLAIN, true, Around.BUILDS), null);
        assertNull(c.problem(), "it can be worn at Lv 90");
        assertEquals(90, c.level(), "planned for the chosen item's level");
        assertEquals(90, c.steps().get(0).level());
        assertFalse(c.builds().isEmpty());
        int earned90 = (Math.min(90, 101) - 1) * 2;
        int earned66 = (66 - 1) * 2;
        for (Roadmap.Candidate b : c.builds()) {
            assertEquals("Loaded Question", b.set().get("WEAPON").name());
            int used = java.util.Arrays.stream(b.eval().allocation()).sum();
            assertTrue(used <= earned90, "within the Lv 90 points: " + used);
            assertTrue(used > earned66, "more than a Lv 66 character has: " + used);
            boolean above = false;
            for (var e : b.set().entrySet()) {
                if (e.getValue() != null) {
                    assertTrue(e.getValue().level() <= 90, e.getKey() + " " + e.getValue().name() + " Lv " + e.getValue().level());
                    above |= !e.getKey().equals("WEAPON") && e.getValue().level() > 66;
                }
            }
            assertTrue(above, "another slot goes above Lv 66");
        }
        // the window lists it although it is above the character's level
        assertTrue(Locks.usable(GameState.current().allGear().filter(i -> i.name().equals("Loaded Question")).findFirst().orElseThrow(),
                GameState.current().classType()));
    }

    @Test
    void aChoiceKeepsOnePiecePerSlot() throws Exception {
        FixtureState.load(shaman()).install("en");
        GameState st = GameState.current();
        java.util.function.Function<String, ItemData> gear = n -> st.allGear().filter(i -> i.name().equals(n)).findFirst().orElseThrow();
        List<ItemData> sel = new ArrayList<>();
        sel = Locks.toggle(sel, gear.apply("Outlaw Tunic"));
        sel = Locks.toggle(sel, gear.apply("Psychopomp's Pileus"));
        ItemData otherChest = st.allGear().filter(i -> i.slot() == Slot.CHESTPLATE && !i.name().equals("Outlaw Tunic"))
                .findFirst().orElseThrow();
        sel = Locks.toggle(sel, otherChest);
        assertEquals(List.of("Psychopomp's Pileus", otherChest.name()), sel.stream().map(ItemData::name).toList(),
                "a second chestplate replaces the first");
        List<ItemData> rings = st.allGear().filter(i -> i.slot() == Slot.RING).limit(3).toList();
        for (ItemData r : rings) {
            sel = Locks.toggle(sel, r);
        }
        assertEquals(2, sel.stream().filter(i -> i.slot() == Slot.RING).count(), "two rings");
        assertTrue(sel.contains(rings.get(1)) && sel.contains(rings.get(2)), "the third ring replaces the first");
        sel = Locks.toggle(sel, rings.get(2));
        assertFalse(sel.contains(rings.get(2)), "a click again takes it out");
    }

    @Test
    void twoOfTheSameRingStayTwo(@TempDir Path tmp) throws Exception {
        // a WynnBuilder link can wear the same ring on both hands
        FixtureState.load(shaman()).install("en");
        Locks.rootOverride = tmp;
        Locks.forget();
        Locks.set(List.of("Morph-Emerald", "Morph-Emerald", "Morph-Emerald"));
        assertEquals(List.of("Morph-Emerald", "Morph-Emerald"), Locks.names(), "two, never more");
        List<ItemData> items = Locks.resolve(GameState.current(), Locks.names());
        assertEquals(2, items.size(), "both hands");
        Locks.clear();
        Saver.flush();
        Locks.forget();
        Locks.rootOverride = null;
    }

    @Test
    void theChoiceIsSavedPerCharacter(@TempDir Path tmp) throws Exception {
        FixtureState.load(shaman()).install("en");
        Locks.rootOverride = tmp;
        Locks.forget();
        assertTrue(Locks.names().isEmpty());
        Locks.set(List.of(DEX_RELIK, "Morph-Emerald"));
        Tuning.Choice t = new Tuning.Choice(Tuning.TANKY, true, false, 4096);
        Locks.setTuning(t);
        Saver.flush();
        Path f = tmp.resolve(GameState.current().characterId()).resolve("build-around.json");
        assertTrue(Files.exists(f), "written to the character's folder");
        Locks.forget();
        assertEquals(List.of(DEX_RELIK, "Morph-Emerald"), Locks.names(), "read back after a restart");
        assertEquals(t, Locks.tuning(), "the strip too");
        Locks.clear();
        Saver.flush();
        Locks.forget();
        assertTrue(Locks.names().isEmpty());
        assertEquals(t, Locks.tuning(), "clearing the items keeps the strip");
        // a broken file reads as nothing chosen
        Files.writeString(f, "{not json", java.nio.charset.StandardCharsets.UTF_8);
        Locks.forget();
        assertTrue(Locks.names().isEmpty());
    }
}
