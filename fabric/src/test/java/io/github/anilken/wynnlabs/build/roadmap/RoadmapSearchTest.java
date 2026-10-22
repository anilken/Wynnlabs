package io.github.anilken.wynnlabs.build.roadmap;

import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.build.Exclusive;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;
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
 * The road map's search over whole sets: it ends where no single swap of its pools helps, it
 * picks the same whatever the threads, and it finds a set built around a weapon that loses as a
 * swap into armour made for another (a Mythic that needs its own skill).
 */
class RoadmapSearchTest {

    @AfterEach
    void reset() {
        Roadmap.searched = null;
        FixtureState.uninstall();
        BeamCache.clear();
    }

    private static void shaman() throws Exception {
        BeamCache.clear();
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("en");
    }

    /** Two pieces of one exclusive group (Wynncraft refuses the second). */
    private static boolean clash(Map<String, Damage.Piece> set) {
        java.util.Set<String> groups = new java.util.HashSet<>();
        for (Damage.Piece p : set.values()) {
            String g = Exclusive.group(p);
            if (g != null && !groups.add(g)) {
                return true;
            }
        }
        return false;
    }

    private record Seen(int m, int earned, int[] prev, Map<String, List<Damage.Piece>> pools, Roadmap.Scored result) {}

    @Test
    void theSetItEndsOnHasNoSingleSwapLeft() throws Exception {
        shaman();
        List<Seen> seen = new ArrayList<>();
        Roadmap.searched = (m, earned, prev, pools, result) -> seen.add(new Seen(m, earned, prev, pools, result));
        Roadmap.build();
        Roadmap.searched = null;
        assertFalse(seen.isEmpty(), "no milestone was searched");
        List<String> bad = new ArrayList<>();
        int tried = 0;
        for (Seen s : seen) {
            for (var e : s.pools().entrySet()) {
                for (Damage.Piece p : e.getValue()) {
                    Damage.Piece there = s.result().set().get(e.getKey());
                    if (there != null && there.key().equals(p.key())) {
                        continue;
                    }
                    Map<String, Damage.Piece> t = new LinkedHashMap<>(s.result().set());
                    t.put(e.getKey(), p);
                    if (clash(t)) {
                        continue;
                    }
                    Build.Eval ev = Build.allocate(t, s.m(), s.earned());
                    tried++;
                    if (ev != null && Roadmap.judged(ev, s.prev(), s.m()) > s.result().j() * 1.002) {
                        bad.add("Lv " + s.m() + ": " + p.name() + " in " + e.getKey() + " "
                                + String.format("%+.2f%%", (Roadmap.judged(ev, s.prev(), s.m()) / s.result().j() - 1) * 100));
                    }
                }
            }
        }
        assertTrue(tried > 0, "no swap tried");
        assertTrue(bad.isEmpty(), "single swaps that still help: " + bad);
    }

    private static String sets(List<Roadmap.Step> steps) {
        StringBuilder s = new StringBuilder();
        for (Roadmap.Step st : steps) {
            s.append(st.level()).append(':');
            st.build().forEach((slot, p) -> s.append(slot).append('=').append(p == null ? "-" : p.key()).append(';'));
            st.gear().forEach(g -> s.append(g.slot()).append('>').append(g.info().name()).append(';'));
            s.append('\n');
        }
        return s.toString();
    }

    private static String withThreads(int n) {
        JsonObject rm = new JsonObject();
        rm.addProperty("searchThreads", n);
        JsonObject change = new JsonObject();
        change.add("roadmap", rm);
        Rules.update(change);
        BeamCache.clear();
        return sets(Roadmap.build());
    }

    @Test
    void theSameInputsGiveTheSamePicksOnAnyThreads() throws Exception {
        shaman();
        String one = withThreads(1);
        String again = withThreads(1);
        String four = withThreads(4);
        assertEquals(one, again, "twice on one thread");
        assertEquals(one, four, "one thread against four");
    }

    private static ItemData relik(String name, Tier tier, SkillKind skill, int need, String element, int low, int high) {
        return new ItemData(name, Slot.RELIK, tier, 0,
                new Requirement(100, Optional.of(Klass.SHAMAN), List.of(new Requirement.SkillReq(skill, need)), Optional.empty()),
                new BaseStats((low + high) / 2, 0, Optional.of("NORMAL"), Optional.empty(),
                        List.of(new BaseStats.Damage(element, low, high)), List.of()),
                List.of(), Optional.empty(), "NONE");
    }

    private static ItemData armour(String name, Slot slot, SkillKind skill, int need, String gives) {
        return new ItemData(name, slot, Tier.RARE, 0,
                new Requirement(100, Optional.empty(), List.of(new Requirement.SkillReq(skill, need)), Optional.empty()),
                new BaseStats(0, 2500, Optional.empty(), Optional.empty(), List.of(), List.of()),
                List.of(new StatRange(gives, gives, "RAW", false, 15, 15),
                        new StatRange("spellDamage", "Spell Damage", "PERCENT", false, 10, 10)),
                Optional.empty(), "NONE");
    }

    @Test
    void aSetBuiltAroundAMythicWinsWhenItShould() throws Exception {
        shaman();
        int m = 100;
        int earned = (100 - 1) * 2;
        ItemData tide = relik("Test Tide", Tier.UNIQUE, SkillKind.INTELLIGENCE, 40, "WATER", 200, 260);
        // needs 110 Strength: more than you can assign, so only with Strength gear on
        ItemData quake = relik("Test Quake", Tier.MYTHIC, SkillKind.STRENGTH, 110, "EARTH", 1300, 1500);
        Slot[] kinds = {Slot.HELMET, Slot.CHESTPLATE, Slot.LEGGINGS, Slot.BOOTS};
        Map<Slot, List<ItemData>> pools = new java.util.EnumMap<>(Slot.class);
        Map<Slot, List<ItemData>> wide = new java.util.EnumMap<>(Slot.class);
        Map<String, Damage.Piece> build = new LinkedHashMap<>();
        build.put("WEAPON", Damage.piece(tide));
        pools.put(Slot.RELIK, List.of(tide));
        wide.put(Slot.RELIK, List.of(tide, quake));
        for (Slot k : kinds) {
            ItemData wet = armour("Test Wet " + k.name(), k, SkillKind.INTELLIGENCE, 50, "rawIntelligence");
            ItemData dry = armour("Test Dry " + k.name(), k, SkillKind.STRENGTH, 40, "rawStrength");
            build.put(Build.slotOf(k, 0), Damage.piece(wet));
            pools.put(k, List.of(wet));                  // ranked against the water build: the earth pieces cut
            wide.put(k, List.of(wet, dry));
        }
        Map<String, Damage.Piece> alone = new LinkedHashMap<>(build);
        alone.put("WEAPON", Damage.piece(quake));
        assertNull(Build.allocate(alone, m, earned), "the Mythic alone in the water set cannot be worn");
        assertTrue(Build.whyNot(alone, earned, Build.reach(wide.values().stream().flatMap(List::stream)
                .map(Damage::piece).toList())).contains("10 Strength"), "it needs 10 Strength from gear, not impossible");
        Roadmap.Ctx c = new Roadmap.Ctx(new LinkedHashMap<>(build), pools, wide, Map.of(), Map.of(), java.util.Set.of(),
                new LinkedHashMap<>(build), java.util.Set.of(), m, earned, null, new java.util.concurrent.ConcurrentHashMap<>());
        Build.Eval cur = Build.allocate(build, m, earned);
        assertNotNull(cur);
        Roadmap.Scored r = Roadmap.searchBest(c, build, cur, cur);
        assertEquals("Test Quake", r.set().get("WEAPON").name(), "the set: " + r.set().values().stream().map(Damage.Piece::name).toList());
        long dry = r.set().values().stream().filter(p -> p.name().startsWith("Test Dry")).count();
        assertTrue(dry >= 1, "the Strength armour that carries it: " + r.set().values().stream().map(Damage.Piece::name).toList());
        assertTrue(r.j() > Roadmap.judged(cur, null, m), "better than the water set");
    }
}
