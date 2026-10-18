package io.github.anilken.wynnlabs.crafting;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.anilken.wynnlabs.crafting.Ings.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reference crafts. Every expected number was read from a published crafter's results for the
 * same recipe, tiers and layout (WynnBuilder's crafter, October 2026; its numbers only, not its
 * code), except where a comment says it follows from the rules.
 */
class CraftTest {

    private static Craft.Range r(int a, int b) {
        return new Craft.Range(a, b);
    }

    private static Craft.Result craft(String id, int t1, int t2, Craft.Speed speed, Ingredient... slots) {
        return Craft.craft(CraftData.byId(id), t1, t2, speed, Craft.slots(slots));
    }

    @Test
    void emptySpearAtEachTier() {
        Craft.Result one = craft("Spear1-3", 1, 1, Craft.Speed.NORMAL);
        assertEquals(r(3, 4), one.damageLow());
        assertEquals(r(5, 6), one.damageHigh());
        assertEquals(r(175, 182), one.durability());
        assertEquals(1, one.powderSlots());
        assertTrue(one.ids().isEmpty());
        assertArrayEquals(new int[5], one.requirements());
        assertNull(one.health());
        assertNull(one.duration());

        Craft.Result three = craft("Spear1-3", 3, 3, Craft.Speed.NORMAL);
        assertEquals(r(4, 5), three.damageLow());
        assertEquals(r(7, 8), three.damageHigh());
        assertEquals(r(245, 255), three.durability(), "durability is rounded, not floored (254.8 -> 255)");
    }

    @Test
    void weaponAtThreeAttackSpeeds() {
        Craft.Result normal = craft("Spear70-73", 3, 3, Craft.Speed.NORMAL);
        assertEquals(r(173, 212), normal.damageLow());
        assertEquals(r(180, 221), normal.damageHigh());
        assertEquals(r(584, 598), normal.durability());
        assertEquals(3, normal.powderSlots());

        Craft.Result slow = craft("Spear70-73", 3, 3, Craft.Speed.SLOW);
        assertEquals(r(236, 289), slow.damageLow());
        assertEquals(r(246, 301), slow.damageHigh());

        Craft.Result fast = craft("Spear70-73", 3, 3, Craft.Speed.FAST);
        assertEquals(r(142, 173), fast.damageLow());
        assertEquals(r(147, 180), fast.damageHigh());
    }

    @Test
    void powderSlotsByBracket() {
        assertEquals(1, craft("Spear27-29", 1, 1, Craft.Speed.NORMAL).powderSlots());
        assertEquals(2, craft("Spear30-33", 1, 1, Craft.Speed.NORMAL).powderSlots());
        assertEquals(2, craft("Spear67-69", 1, 1, Craft.Speed.NORMAL).powderSlots());
        assertEquals(3, craft("Spear70-73", 1, 1, Craft.Speed.NORMAL).powderSlots());
        assertEquals(0, craft("Ring70-73", 3, 3, Craft.Speed.NORMAL).powderSlots(), "accessories take no powder");
    }

    @Test
    void positionEffectsAddUp() {
        // Major's Badge in the top-left slot: right −180 and touching +35 on its neighbour, under −20
        // and touching on the slot below, not touching −30 on the rest, under −20 again further down.
        assertArrayEquals(new int[] {100, -45, 115, 70, 50, 70}, Craft.effectiveness(Craft.slots(MAJORS_BADGE)));
        // Obelisk Core's left −200 inverts the slot on its left; Dizzying Void adds above +115 and
        // touching −115 to the slot above it, and touching −115 to its right-hand neighbour.
        assertArrayEquals(new int[] {-100, 100, 100, -15, 100, 100}, Craft.effectiveness(wandLayout()));
    }

    @Test
    void invertedAndMetaWand() {
        Craft.Result w = Craft.craft(CraftData.byId("Wand103-105"), 3, 2, Craft.Speed.NORMAL, wandLayout());
        assertEquals(130, w.materialWeight());
        assertEquals(r(171, 172), w.damage());
        assertEquals(r(153, 188), w.damageLow());
        assertEquals(r(154, 189), w.damageHigh());
        assertEquals(Map.of(
                "rawStrength", r(11, 14),
                "rawHealth", r(-1200, -1000),
                "healthRegenRaw", r(-180, -150),
                "healingEfficiency", r(-5, -4),
                "manaRegen", r(7, 8),
                "exploding", r(10, 15),
                "spellDamage", r(-20, -16),
                "mainAttackDamage", r(-4, -4)), w.ids());
        // Strength 1.5 from the −15% genome rounds up to 2; intelligence −30 from the inverted dust.
        assertArrayEquals(new int[] {24, 2, -39, -48, 2}, w.requirements());
        // From the rules: round(525·1.3)=683, round(529·1.3)=688, minus 834 from the ingredients.
        // The published crafter shows it clamped to 0-0 with a "needs more durability" warning.
        assertEquals(r(-151, -146), w.durability());
        assertEquals(3, w.powderSlots());
        assertEquals(105, w.level());
    }

    @Test
    void consumableWithoutIngredientsHeals() {
        Craft.Result p = craft("Potion43-45", 1, 3, Craft.Speed.NORMAL);
        assertEquals(127, p.materialWeight());
        assertEquals(r(697, 795), p.health());
        assertEquals(r(4, 4), p.duration(), "the short base duration, rounded: 3·1.267 = 3.8");
        assertEquals(3, p.charges());
        assertNull(p.durability());
        assertEquals(0, p.powderSlots());
        assertEquals(r(30, 41), craft("Potion1-3", 1, 3, Craft.Speed.NORMAL).health());
    }

    @Test
    void consumableChargesAndDuration() {
        Craft.Result bat = craft("Potion1-3", 1, 3, Craft.Speed.NORMAL, BAT_HEART);
        assertEquals(1, bat.charges(), "1 by level, −1 from the heart, never below 1");
        assertEquals(r(546, 561), bat.duration());
        assertNull(bat.health(), "an effect potion does not heal");
        assertEquals(r(24, 28), bat.ids().get("walkSpeed"));
        assertArrayEquals(new int[5], bat.requirements());

        Craft.Result fire = craft("Potion1-3", 1, 3, Craft.Speed.NORMAL, ACTIVE_FIREBALL);
        assertEquals(1, fire.charges());
        assertEquals(r(296, 311), fire.duration());
        assertEquals(r(2, 4), fire.ids().get("fireDamage"));

        assertEquals(2, craft("Potion43-45", 1, 3, Craft.Speed.NORMAL, ACTIVE_FIREBALL).charges());
        assertEquals(r(615, 630), craft("Potion43-45", 1, 3, Craft.Speed.NORMAL, ACTIVE_FIREBALL).duration());

        Craft.Result badge = craft("Potion43-45", 1, 3, Craft.Speed.NORMAL, MAJORS_BADGE);
        assertEquals(2, badge.charges());
        assertEquals(r(475, 490), badge.duration(), "an ingredient without identifications still makes it an effect potion");
        assertNull(badge.health());

        assertEquals(3, craft("Potion103-105", 1, 3, Craft.Speed.NORMAL, ACTIVE_FIREBALL).charges());
    }

    @Test
    void materialTiersRaiseTheBase() {
        CraftData.Recipe spear = CraftData.byId("Spear70-73");
        int last = -1;
        for (int t = 1; t <= 3; t++) {
            Craft.Result c = Craft.craft(spear, t, t, Craft.Speed.NORMAL, Craft.slots());
            assertTrue(c.damage().min() > last);
            last = c.damage().min();
        }
        assertEquals(100, Craft.weightPercent(spear, 1, 1));
        assertEquals(125, Craft.weightPercent(spear, 2, 2));
        assertEquals(140, Craft.weightPercent(spear, 3, 3));
    }
}
