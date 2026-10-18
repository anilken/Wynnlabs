package io.github.anilken.wynnlabs.crafting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shipped recipes: every type in every bracket, cleaned of the API's quirks. */
class CraftDataTest {

    @Test
    void everyTypeHasItsFortyEightBrackets() {
        assertEquals(720, CraftData.all().size());
        assertEquals(15, CraftData.countByType().size());
        for (String type : Types.ALL) {
            assertEquals(48, CraftData.ofType(type).size(), type);
        }
        assertFalse(CraftData.fetched().isEmpty());
    }

    @Test
    void spearOneToThree() {
        CraftData.Recipe r = CraftData.find("spear", 1);
        assertNotNull(r);
        assertEquals("weaponsmithing", r.profession());
        assertEquals(3, r.maxLevel());
        assertEquals("Copper Ingot", r.materials().get(0).shown());
        assertEquals(1, r.materials().get(0).amount());
        assertEquals("Oak Planks", r.materials().get(1).shown());
        assertEquals(2, r.materials().get(1).amount());
        assertArrayEquals(new int[] {4, 6}, r.healthOrDamage());
        assertNull(r.duration());
    }

    @Test
    void quirksAreFixed() {
        for (CraftData.Recipe r : CraftData.all()) {
            for (int[] range : new int[][] {r.healthOrDamage(), r.durability(), r.duration(), r.basicDuration()}) {
                if (range != null) {
                    assertTrue(range[0] <= range[1], r.id());
                }
            }
            if (r.durability() != null) {
                assertTrue(r.durability()[1] < 5000, "durability is no longer x1000: " + r.id());
            }
            assertEquals(r.consumable(), r.durability() == null, r.id());
            assertEquals(r.consumable(), r.duration() != null, r.id());
        }
    }

    @Test
    void accessoriesHaveNoBaseHealth() {
        assertNull(CraftData.find("ring", 1).healthOrDamage());
    }
}
