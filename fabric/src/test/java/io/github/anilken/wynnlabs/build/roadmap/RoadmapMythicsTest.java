package io.github.anilken.wynnlabs.build.roadmap;

import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.market.Shopping;
import io.github.anilken.wynnlabs.model.BaseStats;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.Requirement;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.model.Slot;
import io.github.anilken.wynnlabs.model.StatRange;
import io.github.anilken.wynnlabs.model.Tier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mythics reach the road map's search instead of being thrown out before it: an older Mythic
 * whose later version you cannot get stays in below the level window, a weapon that only this
 * set cannot wear is left to the search's sets around weapons (it needs a few points from gear),
 * only one no set can wear is skipped as impossible, Immolation's -650 % healing no longer makes
 * its score NaN, and the trace speaks the mod's language throughout.
 */
class RoadmapMythicsTest {

    @AfterEach
    void reset() {
        Roadmap.trace = null;
        FixtureState.uninstall();
        BeamCache.clear();
    }

    private static void shaman(String language) throws Exception {
        BeamCache.clear();
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install(language);
    }

    private static ItemData gear(String name) {
        return GameState.current().allGear().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }

    /** The text of a one-argument language key before its argument. */
    private static String head(String key) {
        String t = L.t(key, "\u0001");
        return t.substring(0, t.indexOf('\u0001'));
    }

    /** The text of a one-argument language key after its argument. */
    private static String tail(String key) {
        String t = L.t(key, "\u0001");
        return t.substring(t.indexOf('\u0001') + 1);
    }

    @Test
    void anOlderMythicStaysWhenItsLaterVersionCannotBeHad() throws Exception {
        shaman("en");
        ItemData olympic = gear("Olympic");
        // Masterwork Olympic (Lv 109) has no known source: the fixture's rules leave UNKNOWN out
        Map<String, Integer> any = Roadmap.laterVersions(GameState.current(), i -> true);
        assertEquals(109, any.get("Olympic"));
        assertFalse(Roadmap.lastOfItsKind(olympic, 120, any), "with every later version counted, Olympic is replaced");
        List<String> bad = Roadmap.badSources(120);
        Map<String, Integer> gettable = Roadmap.laterVersions(GameState.current(), i -> !Shopping.excluded(i, bad));
        assertNull(gettable.get("Olympic"), "the Masterwork one cannot be had");
        assertTrue(Roadmap.lastOfItsKind(olympic, 120, gettable), "so Olympic stays the best of its kind at Lv 120");
    }

    @Test
    void immolationScoresAsANumber() throws Exception {
        shaman("en");
        // -650 % Healing Efficiency made the heal negative and its log NaN: "does not fit the build"
        Map<String, Damage.Piece> b = new LinkedHashMap<>();
        b.put("WEAPON", Damage.piece(gear("Immolation")));
        Build.Eval e = Build.allocate(b, 110, 200);
        assertNotNull(e);
        assertFalse(Double.isNaN(e.result().objective()), "Immolation's score");
        assertTrue(e.result().objective() > 0);
    }

    private static ItemData relik(String name, Map<SkillKind, Integer> needs, String element, int low, int high) {
        List<Requirement.SkillReq> reqs = new ArrayList<>();
        needs.forEach((k, v) -> reqs.add(new Requirement.SkillReq(k, v)));
        return new ItemData(name, Slot.RELIK, Tier.MYTHIC, 0,
                new Requirement(100, Optional.of(Klass.SHAMAN), reqs, Optional.empty()),
                new BaseStats((low + high) / 2, 0, Optional.of("NORMAL"), Optional.empty(),
                        List.of(new BaseStats.Damage(element, low, high)), List.of()),
                List.of(), Optional.empty(), "NONE");
    }

    private static ItemData armour(String name, Slot slot, SkillKind skill, int need, String gives, int amount) {
        return new ItemData(name, slot, Tier.RARE, 0,
                new Requirement(100, Optional.empty(), List.of(new Requirement.SkillReq(skill, need)), Optional.empty()),
                new BaseStats(0, 2500, Optional.empty(), Optional.empty(), List.of(), List.of()),
                List.of(new StatRange(gives, gives, "RAW", false, amount, amount),
                        new StatRange("spellDamage", "Spell Damage", "PERCENT", false, 10, 10)),
                Optional.empty(), "NONE");
    }

    @Test
    void impossibleIsJudgedOnTheWeaponAloneWithTheBestGear() throws Exception {
        shaman("en");
        int earned = 200;
        // Transfiguration's 70 / 70 / 55: 195 points, wearable; in an Intelligence set it was
        // "impossible: 237 points" because the set's own requirements counted too
        Damage.Piece trans = Damage.piece(relik("Test Trans", Map.of(SkillKind.STRENGTH, 70, SkillKind.DEXTERITY, 70,
                SkillKind.AGILITY, 55), "EARTH", 300, 400));
        int[] none = new int[SkillKind.values().length];
        assertNull(Build.impossible(trans, earned, none));
        Map<String, Damage.Piece> set = new LinkedHashMap<>();
        set.put("WEAPON", trans);
        set.put("HELMET", Damage.piece(armour("Test Wet Helmet", Slot.HELMET, SkillKind.INTELLIGENCE, 45, "rawIntelligence", 5)));
        String why = Build.whyNot(set, earned, none);
        assertNotNull(why, "this set cannot wear it");
        assertTrue(why.contains("with this set") && !why.contains("impossible"), why);
        // five skills of 50: 250 points, more than any set gives
        Damage.Piece greedy = Damage.piece(relik("Test Greedy", Map.of(SkillKind.STRENGTH, 50, SkillKind.DEXTERITY, 50,
                SkillKind.INTELLIGENCE, 50, SkillKind.DEFENCE, 50, SkillKind.AGILITY, 50), "EARTH", 300, 400));
        String no = Build.impossible(greedy, earned, none);
        assertNotNull(no);
        assertTrue(no.startsWith("impossible") && no.contains("250"), no);
        // with gear that gives 10 in each, 200 are left to assign: wearable after all
        int[] tens = new int[SkillKind.values().length];
        java.util.Arrays.fill(tens, 10);
        assertNull(Build.impossible(greedy, earned, tens));
        // past 100 by more than gear can give
        Damage.Piece huge = Damage.piece(relik("Test Huge", Map.of(SkillKind.STRENGTH, 125), "EARTH", 300, 400));
        assertNull(Build.impossible(huge, earned, new int[] {30, 0, 0, 0, 0}));
        assertTrue(Build.impossible(huge, earned, new int[] {20, 0, 0, 0, 0}).contains("125 Strength"));
    }

    @Test
    void aMythicNeedingAFewPointsFromGearWinsWhenItIsBetter() throws Exception {
        shaman("en");
        int m = 100;
        int earned = (100 - 1) * 2;
        ItemData tide = relik("Test Tide", Map.of(SkillKind.INTELLIGENCE, 40), "WATER", 200, 260);
        // 105 Strength: 5 more than you can assign, so only with a Strength piece on
        ItemData quake = relik("Test Quake", Map.of(SkillKind.STRENGTH, 105), "EARTH", 1300, 1500);
        Slot[] kinds = {Slot.HELMET, Slot.CHESTPLATE, Slot.LEGGINGS, Slot.BOOTS};
        Map<Slot, List<ItemData>> pools = new java.util.EnumMap<>(Slot.class);
        Map<Slot, List<ItemData>> wide = new java.util.EnumMap<>(Slot.class);
        Map<String, Damage.Piece> build = new LinkedHashMap<>();
        build.put("WEAPON", Damage.piece(tide));
        pools.put(Slot.RELIK, List.of(tide));
        wide.put(Slot.RELIK, List.of(tide, quake));
        for (Slot k : kinds) {
            ItemData wet = armour("Test Wet " + k.name(), k, SkillKind.INTELLIGENCE, 50, "rawIntelligence", 15);
            ItemData dry = armour("Test Dry " + k.name(), k, SkillKind.STRENGTH, 40, "rawStrength", 5);
            build.put(Build.slotOf(k, 0), Damage.piece(wet));
            pools.put(k, List.of(wet));
            wide.put(k, List.of(wet, dry));
        }
        Map<String, Damage.Piece> alone = new LinkedHashMap<>(build);
        alone.put("WEAPON", Damage.piece(quake));
        assertNull(Build.allocate(alone, m, earned), "the Mythic in the water set cannot be worn");
        int[] reach = Build.reach(wide.values().stream().flatMap(List::stream).map(Damage::piece).toList());
        assertNull(Build.impossible(Damage.piece(quake), earned, reach), "it is not impossible");
        assertTrue(Build.whyNot(alone, earned, reach).contains("5 Strength"), "it needs 5 Strength from gear");
        Roadmap.Ctx c = new Roadmap.Ctx(new LinkedHashMap<>(build), pools, wide, Map.of(), Map.of(), java.util.Set.of(),
                new LinkedHashMap<>(build), java.util.Set.of(), m, earned, null, new java.util.concurrent.ConcurrentHashMap<>());
        Build.Eval cur = Build.allocate(build, m, earned);
        assertNotNull(cur);
        Roadmap.Scored r = Roadmap.searchBest(c, build, cur, cur);
        List<String> names = r.set().values().stream().map(Damage.Piece::name).toList();
        assertEquals("Test Quake", r.set().get("WEAPON").name(), "the set: " + names);
        assertTrue(names.stream().anyMatch(n -> n.startsWith("Test Dry")), "a Strength piece carries it: " + names);
        assertTrue(r.j() > Roadmap.judged(cur, null, m), "better than the water set");
    }

    @Test
    void theMythicsReachTheSearchAndTheTraceSpeaksOneLanguage() throws Exception {
        shaman("tr");
        List<String> relik = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> notes = java.util.Collections.synchronizedList(new ArrayList<>());
        Roadmap.trace = (m, t, line) -> {
            if (m >= 100) {
                (t == Slot.RELIK ? relik : notes).add("Lv " + m + " " + line);
            }
        };
        try {
            Roadmap.build();
        } finally {
            Roadmap.trace = null;
        }
        assertFalse(relik.isEmpty() || notes.isEmpty(), "no trace");
        String skip = head("wynnlabs.road.trace_skip");
        String around = tail("wynnlabs.road.trace_around");
        // Toxoplasmosis (110 Strength) and Absolution (115 Defence) need a few points from gear:
        // no skip, the search builds sets around them
        for (String name : new String[] {"Toxoplasmosis Lv96", "Absolution Lv97"}) {
            for (int m : new int[] {100, 120}) {
                String p = "Lv " + m + " " + name + ": ";
                List<String> lines = relik.stream().filter(l -> l.startsWith(p)).toList();
                assertFalse(lines.isEmpty(), "no line for " + p);
                // never thrown out: built around where the set cannot give the points, ranked where
                // it can (and maybe not among the best 6: the Lv 120 set after Dragon's Tear wears it)
                String notBest = L.t("wynnlabs.road.trace_skip", L.t("wynnlabs.road.trace_not_best", 6));
                lines.forEach(l -> assertTrue(l.endsWith(around) || !l.startsWith(p + skip) || l.equals(p + notBest), l));
                String start = L.t("wynnlabs.road.trace_start_weapon", name.substring(0, name.indexOf(" Lv")));
                assertTrue(notes.stream().anyMatch(l -> l.startsWith("Lv " + m + " ") && l.contains(start)),
                        "Lv " + m + ": no start around " + name);
            }
        }
        // Transfiguration fits 200 points on its own: never "impossible" because of the set it met
        List<String> trans = relik.stream().filter(l -> l.startsWith("Lv 120 Transfiguration Lv117: ")).toList();
        assertFalse(trans.isEmpty(), "no line for Transfiguration at Lv 120");
        trans.forEach(l -> assertFalse(l.contains(skip), l));
        // Olympic (Lv 93) at Lv 120: below the window, but no Masterwork one can be had
        String window = "Lv 120 Olympic Lv93: " + L.t("wynnlabs.road.trace_skip", L.t("wynnlabs.road.trace_window"));
        assertTrue(relik.stream().anyMatch(l -> l.startsWith("Lv 120 Olympic Lv93: ")) && !relik.contains(window),
                "Olympic was dropped at Lv 120: " + relik.stream().filter(l -> l.contains("Olympic Lv93")).toList());
        assertTrue(notes.stream().anyMatch(l -> l.startsWith("Lv 120 ") && l.contains(L.t("wynnlabs.road.trace_start_weapon", "Olympic"))),
                "no start around Olympic at Lv 120");
        // Immolation scores: no NaN anywhere
        assertTrue(notes.stream().noneMatch(l -> l.contains("NaN")), notes.stream().filter(l -> l.contains("NaN")).toList().toString());
        assertTrue(notes.stream().anyMatch(l -> l.startsWith("Lv 110 ") && l.contains(L.t("wynnlabs.road.trace_start_weapon", "Immolation"))),
                "no start around Immolation at Lv 110");
        // one language: nothing English in a Turkish trace
        List<String> english = new ArrayList<>();
        for (List<String> list : List.of(relik, notes)) {
            for (String l : list) {
                // the reason after the item ("Timeskip Lv115: …" is a name, not a word)
                String text = l.substring(l.indexOf(' ', 3) + 1).replaceFirst("^.*? Lv\\d+: ", "");
                for (String w : new String[] {"skip:", "impossible", "needs ", "ranked ", "judged ", "start ", "searched",
                        "too far", "does not", "with this set", "over the", "not in the best"}) {
                    if (text.contains(w)) {
                        english.add(l);
                        break;
                    }
                }
            }
        }
        assertTrue(english.isEmpty(), "English in the Turkish trace: " + english.stream().limit(10).toList());
    }
}
