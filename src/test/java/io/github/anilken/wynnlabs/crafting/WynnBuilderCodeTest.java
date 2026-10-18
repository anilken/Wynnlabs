package io.github.anilken.wynnlabs.crafting;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Codes made on WynnBuilder's crafter (October 2026) and what they hold. */
class WynnBuilderCodeTest {

    private static RecipeCode.Recipe r(String type, int lv, int t1, int t2, Craft.Speed s, String... slots) {
        List<String> l = Arrays.asList(new String[6]);
        for (int i = 0; i < slots.length; i++) {
            l.set(i, slots[i]);
        }
        return new RecipeCode.Recipe(type, lv, t1, t2, s, l);
    }

    @Test
    void codesFromTheSite() {
        check("CR-40w3w3w3w3w3wF3040", r("spear", 1, 1, 1, Craft.Speed.NORMAL));
        check("CR-40w3w3w3w3w3wF3850", r("spear", 1, 3, 3, Craft.Speed.NORMAL));
        check("CR-40w3w3w3w3w3wVT890", r("spear", 70, 3, 3, Craft.Speed.FAST));
        check("CR-40w3w3w3w3w3wFH01", r("potion", 43, 1, 3, Craft.Speed.NORMAL));
        check("CR-4GZ0w3w3w3w3wF201", r("potion", 1, 1, 3, Craft.Speed.NORMAL, "Bat Heart"));
        check("CR-4OC0w3w3w3w3wF201", r("potion", 1, 1, 3, Craft.Speed.NORMAL, "Active Fireball"));
        check("CR-4ac0w3w3w3w3wFH01", r("potion", 43, 1, 3, Craft.Speed.NORMAL, "Major's Badge"));
        check("CR-4KbucanKlaxibGde40", r("wand", 103, 3, 2, Craft.Speed.NORMAL, "Foul Fairy Dust", "Obelisk Core",
                "Dizzying Void", "Depurinated Genome", "Exquisite Marsh Scale", "Gunkfueled Core"));
    }

    private static void check(String code, RecipeCode.Recipe recipe) {
        assertEquals(code, WynnBuilderCode.encode(recipe), recipe.toString());
        assertEquals(recipe, WynnBuilderCode.decode(code), code);
        assertEquals(recipe, WynnBuilderCode.decode(" " + code.substring(3) + "\n"), "without the CR- prefix");
    }

    @Test
    void leggingsAndHighBrackets() {
        for (int lv : new int[] {1, 50, 117}) {
            RecipeCode.Recipe x = r("leggings", lv, 2, 1, Craft.Speed.NORMAL, "Major's Badge");
            assertEquals(x, WynnBuilderCode.decode(WynnBuilderCode.encode(x)));
        }
    }

    @Test
    void garbageAndUnknownsAreRefused() {
        assertNull(WynnBuilderCode.decode(null));
        assertNull(WynnBuilderCode.decode("CR-"));
        assertNull(WynnBuilderCode.decode("hello world"));
        assertNull(WynnBuilderCode.decode("CR-40w3w"), "cut short");
        assertNull(WynnBuilderCode.encode(r("wand", 103, 1, 1, Craft.Speed.NORMAL, "Not An Ingredient")));
    }
}
