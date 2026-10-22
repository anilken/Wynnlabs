package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.Slot;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Elemental defences count in the build's survival (design decision 6) while effective health
 * stays WynnBuilder's; and the allocator says why a build does not fit (design decision 8).
 */
class ElementalDefenceTest {

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    private static Damage.Piece ring(String name, Map<String, Double> stats) {
        return new Damage.Piece(ItemStack.EMPTY, name, name, Slot.RING, 1, stats, Map.of(), 0, Map.of(), 0);
    }

    @Test
    void negativeAirDefenceLowersSurvivalButNotTheReferenceHealth() throws Exception {
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("en");
        int[] points = new int[io.github.anilken.wynnlabs.model.SkillKind.values().length];
        // a weapon, or the damage and with it the score is 0 on both sides
        Damage.Piece weapon = Damage.piece(GameState.current().allGear()
                .filter(i -> i.name().equals("Heavensent")).findFirst().orElseThrow());
        Damage.Result plain = Damage.evaluate(List.of(ring("plain", new HashMap<>())), weapon, points, 50);
        Map<String, Double> weak = new HashMap<>();
        weak.put("airDefence", -35.0);
        Damage.Result cursed = Damage.evaluate(List.of(ring("cursed", weak)), weapon, points, 50);
        assertEquals(plain.ehp(), cursed.ehp(), 1e-9, "WynnBuilder's effective health leaves elemental defences out");
        assertTrue(cursed.ehpElemental() < plain.ehpElemental(), "−35 % air defence must lower the elemental view");
        // a tenth of the damage is air; −35 % on it is 3.5 % more taken
        assertEquals(plain.ehpElemental() / 1.035, cursed.ehpElemental(), plain.ehpElemental() * 1e-6);
        assertTrue(cursed.objective() < plain.objective(), "and the score with it");
        Map<String, Double> strong = new HashMap<>();
        strong.put("elementalDefence", 30.0);
        Damage.Result armoured = Damage.evaluate(List.of(ring("armoured", strong)), weapon, points, 50);
        assertEquals(plain.ehpElemental() / 0.85, armoured.ehpElemental(), plain.ehpElemental() * 1e-6,
                "+30 % to every element covers the half that is elemental");
    }

    @Test
    void theAllocatorSaysWhyABuildDoesNotFit() throws Exception {
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("en");
        Map<String, Damage.Piece> b = new LinkedHashMap<>();
        b.put("RING1", ring("plain", new HashMap<>()));
        assertNull(Build.whyNot(b, 96));
        b.put("HELMET", new Damage.Piece(ItemStack.EMPTY, "big", "big", Slot.HELMET, 1, new HashMap<>(),
                Map.of(io.github.anilken.wynnlabs.model.SkillKind.INTELLIGENCE, 90,
                        io.github.anilken.wynnlabs.model.SkillKind.AGILITY, 90), 0, Map.of(), 0));
        String why = Build.whyNot(b, 96);
        assertNotNull(why);
        assertTrue(why.contains("180 points") && why.contains("96 earned"), why);
        b.put("HELMET", new Damage.Piece(ItemStack.EMPTY, "over", "over", Slot.HELMET, 1, new HashMap<>(),
                Map.of(io.github.anilken.wynnlabs.model.SkillKind.INTELLIGENCE, 110), 0, Map.of(), 0));
        why = Build.whyNot(b, 200);
        assertNotNull(why);
        assertTrue(why.contains("110 Intelligence"), why);
    }
}
