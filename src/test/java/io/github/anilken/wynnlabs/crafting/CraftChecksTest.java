package io.github.anilken.wynnlabs.crafting;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CraftChecksTest {

    @Test
    void requirementGaps() {
        assertEquals(List.of(new CraftChecks.Gap(2, 52, 44)),
                CraftChecks.gaps(new int[] {10, 0, 52, -5, 0}, new int[] {12, 0, 44, 0, 0}));
        assertEquals(8, CraftChecks.gaps(new int[] {0, 0, 52, 0, 0}, new int[] {0, 0, 44, 0, 0}).get(0).missing());
        assertEquals(List.of(), CraftChecks.gaps(new int[] {10, 3, 0, -40, 1}, new int[] {10, 3, 0, 0, 1}), "all met");
    }

    @Test
    void craftsToNextLevel() {
        assertEquals(8, CraftChecks.craftsToLevel(5250, 0, 40_000), "40000 / 5250 = 7.6, so 8");
        assertEquals(8, CraftChecks.craftsToLevel(5250, 10_000, 50_000));
        assertEquals(1, CraftChecks.craftsToLevel(5250, 49_999, 50_000));
        assertEquals(0, CraftChecks.craftsToLevel(5250, 50_000, 50_000));
        assertEquals(-1, CraftChecks.craftsToLevel(0, 0, 50_000), "no XP per craft: cannot tell");
        assertEquals(-1, CraftChecks.craftsToLevel(100, 0, 0), "no level data");
    }

    @Test
    void nearestStation() {
        List<int[]> st = List.of(new int[] {-1000, 60, -1500}, new int[] {120, 70, -1580}, new int[] {500, 40, 500});
        assertEquals(1, CraftChecks.nearest(st, 100, -1600));
        assertEquals(28, CraftChecks.distance(st.get(1), 100, -1600));
        assertEquals(-1, CraftChecks.nearest(List.of(), 0, 0));
    }
}
