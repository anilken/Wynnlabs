package io.github.anilken.wynnlabs.build;

import io.github.anilken.wynnlabs.build.roadmap.Roadmap;
import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.SetData;
import io.github.anilken.wynnlabs.model.Slot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Set bonuses as Wynncraft counts them (the Wynntils set data in the recorded Shaman's item
 * data): a ring in both hands is two pieces, some sets pay from one piece, and a set can go on
 * in part when its bonus for fewer pieces is the better deal.
 */
class SetBonusTest {

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    private static void shaman() throws Exception {
        Path dir = Fixtures.root().resolve("shaman-44");
        assumeTrue(Files.exists(dir.resolve("state.json")), "no shaman-44 fixture");
        FixtureState.load(dir).install("en");
    }

    private static ItemData gear(String name) {
        return GameState.current().allGear().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }

    private static int stat(SetData.Bonus b, String key) {
        return b.minor().stream().filter(v -> v.key().equals(key)).mapToInt(SetData.StatValue::value).sum();
    }

    /** The model with and without the set's bonuses (the set's data emptied for the second). */
    private static Damage.Result[] withAndWithout(String set, List<Damage.Piece> pieces, Damage.Piece weapon, int level) {
        int[] assigned = new int[5];
        Damage.Result with = Damage.evaluate(pieces, weapon, assigned, level);
        SetData d = Damage.SETS.get(set);
        assertNotNull(d, set);
        try {
            Damage.SETS.put(set, new SetData(d.name(), d.cleanName(), d.pieces(), Map.of()));
            return new Damage.Result[] {with, Damage.evaluate(pieces, weapon, assigned, level)};
        } finally {
            Damage.SETS.put(set, d);
        }
    }

    @Test
    void aRingInBothHandsCountsTwice() throws Exception {
        shaman();
        SetData set = gear("Black Catalyst").set().orElseThrow();
        assertEquals(1, set.pieces(), "one item");
        assertEquals(650, stat(set.bonusFor(2), "rawHealth"), "two rings: the bonus at two");
        assertEquals(-5, stat(set.bonusFor(1), "combatExperience"), "one ring: the bonus at one");
        assertNull(set.bonusFor(0));
        assertEquals(2, set.full(), "shown as 2/2, not 2/1");
        Damage.Piece ring = Damage.piece(gear("Black Catalyst"));
        // with the Shaman's armour on, so the ring's own health loss keeps the total above zero
        List<Damage.Piece> worn = new ArrayList<>(List.of(ring, ring));
        Build.plan().pieces().forEach((slot, p) -> {
            if (p != null && !slot.startsWith("RING") && !slot.equals("WEAPON")) {
                worn.add(p);
            }
        });
        Damage.Result[] r = withAndWithout("Black Catalyst", worn, null, 90);
        assertEquals(650, r[0].health() - r[1].health(), 1e-6, "the two-piece health in the model");
    }

    @Test
    void constellationPaysWithTheLensTwice() throws Exception {
        shaman();
        SetData set = gear("Stargazing Lens").set().orElseThrow();
        assertEquals(1, set.pieces());
        assertEquals(400, stat(set.bonusFor(2), "lifeSteal"), "its only bonus is at two");
        assertEquals(0, stat(set.bonusFor(1), "lifeSteal"));
        Damage.Piece lens = Damage.piece(gear("Stargazing Lens"));
        Damage.Result[] r = withAndWithout("Constellation", List.of(lens, lens), null, 100);
        assertFalse(Arrays.equals(r[0].factors(), r[1].factors()) && r[0].objective() == r[1].objective(),
                "the bonus reaches the model");
    }

    @Test
    void jesterReachesItsTopTierWithTheRingTwice() throws Exception {
        shaman();
        SetData set = gear("Jester Ring").set().orElseThrow();
        assertEquals(3, set.pieces());
        assertEquals(120, stat(set.bonusFor(4), "lootBonus"), "ring, ring, bracelet, necklace");
        assertEquals(4, set.full());
        assertEquals(stat(set.bonusFor(4), "lootBonus"), stat(set.bonusFor(5), "lootBonus"), "past the top: the top");
    }

    @Test
    void goblinPaysFromOnePiece() throws Exception {
        shaman();
        SetData set = gear("Goblin Hood").set().orElseThrow();
        assertEquals(27, stat(set.bonusFor(1), "rawSpellDamage"));
        Build.Plan plan = Build.plan();
        assertNotNull(plan);
        Damage.Piece weapon = plan.pieces().get("WEAPON");
        assertNotNull(weapon, "the Shaman's weapon");
        Damage.Result[] r = withAndWithout("Goblin", List.of(Damage.piece(gear("Goblin Hood"))), weapon, 45);
        assertNotEquals(r[1].spellDps(), r[0].spellDps(), "one hood adds the one-piece bonus");
    }

    /** How many pieces of a set a build holds. */
    private static int count(Map<String, Damage.Piece> build, String set) {
        int n = 0;
        for (Damage.Piece p : build.values()) {
            n += p != null && p.stats().containsKey(Damage.SET + set) ? 1 : 0;
        }
        return n;
    }

    @Test
    void aSetIsAlsoTriedInPart() throws Exception {
        shaman();
        Build.Plan plan = Build.plan();
        assertNotNull(plan);
        Map<String, Damage.Piece> build = new LinkedHashMap<>(plan.pieces());
        int m = 45;
        int earned = (m - 1) * 2;
        assertEquals(0, count(build, "Goblin"), "the recorded Shaman wears no Goblin");
        Build.Eval cur = Build.allocate(build, m, earned);
        assertNotNull(cur);
        List<ItemData> goblin = List.of(gear("Goblin Hood"), gear("Goblin Cloak"), gear("Goblin Runners"));
        List<Roadmap.Seed> seeds = Roadmap.setSeeds(build, cur, Map.of(), Map.of("Goblin", goblin), Map.of(),
                Set.of(), Set.of(), m, earned, null);
        List<Roadmap.Seed> two = seeds.stream().filter(s -> count(s.build(), "Goblin") == 2).toList();
        assertEquals(1, two.size(), "one two-piece seed (the set's bonus at two)");
        assertEquals(1, seeds.stream().filter(s -> count(s.build(), "Goblin") == 3).count(), "and the whole set");
        // the two pieces that do most alone (by the quick score that orders them)
        List<ItemData> byAlone = new ArrayList<>(goblin);
        Map<String, Double> alone = new LinkedHashMap<>();
        for (ItemData info : goblin) {
            Build.Eval ev = Build.tryEval(build, cur.allocation(), Build.slotOf(info.slot(), 0), Damage.piece(info), m, earned);
            alone.put(info.name(), ev == null ? Double.NEGATIVE_INFINITY : Roadmap.judged(ev, null, m));
        }
        byAlone.sort(Comparator.comparingDouble((ItemData i) -> alone.get(i.name())).reversed());
        Map<String, Damage.Piece> b = two.get(0).build();
        for (int i = 0; i < 2; i++) {
            String slot = Build.slotOf(byAlone.get(i).slot(), 0);
            assertEquals(byAlone.get(i).name(), b.get(slot).name(), slot);
            assertTrue(two.get(0).held().contains(slot), slot + " is held by the set");
        }
        String rest = Build.slotOf(byAlone.get(2).slot(), 0);
        assertEquals(build.get(rest) == null ? null : build.get(rest).name(),
                b.get(rest) == null ? null : b.get(rest).name(), "the third slot keeps what it had");
    }

    @Test
    void aRingYouCanBuyFillsBothHands() throws Exception {
        shaman();
        Build.Plan plan = Build.plan();
        assertNotNull(plan);
        Map<String, Damage.Piece> build = new LinkedHashMap<>(plan.pieces());
        int m = 90;
        int earned = (m - 1) * 2;
        Build.Eval cur = Build.allocate(build, m, earned);
        assertNotNull(cur);
        ItemData bc = gear("Black Catalyst");
        assertEquals(Slot.RING, bc.slot());
        List<Roadmap.Seed> seeds = Roadmap.setSeeds(build, cur, Map.of(), Map.of("Black Catalyst", List.of(bc)),
                Map.of(), Set.of("Black Catalyst"), Set.of(), m, earned, null);
        assertTrue(seeds.stream().anyMatch(s -> count(s.build(), "Black Catalyst") == 2
                        && s.build().get("RING1").name().equals("Black Catalyst")
                        && s.build().get("RING2").name().equals("Black Catalyst")),
                "both hands: " + seeds.stream().map(s -> s.build().get("RING1").name() + "+" + s.build().get("RING2").name()).toList());
        // one you have once and cannot buy: one hand, no bonus at two to reach, no seed
        assertTrue(Roadmap.setSeeds(build, cur, Map.of(), Map.of("Black Catalyst", List.of(bc)),
                Map.of("Black Catalyst", 1), Set.of(), Set.of(), m, earned, null).isEmpty());
    }
}
