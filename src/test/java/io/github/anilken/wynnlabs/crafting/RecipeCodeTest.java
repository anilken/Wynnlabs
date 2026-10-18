package io.github.anilken.wynnlabs.crafting;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeCodeTest {

    private static final List<String> KNOWN = Ings.ALL.stream().map(Ingredient::name).toList();

    @Test
    void roundTrip() {
        RecipeCode.Recipe r = new RecipeCode.Recipe("wand", 103, 3, 2, Craft.Speed.FAST,
                Arrays.asList("Foul Fairy Dust", "Obelisk Core", null, "Depurinated Genome", null, "Gunkfueled Core"));
        String code = RecipeCode.encode(r);
        assertTrue(code.startsWith("wl1"));
        assertTrue(code.length() <= 46, code);
        assertEquals(r, RecipeCode.decode(code, KNOWN));
        assertEquals(r, RecipeCode.decode("  " + code.toUpperCase() + "\n", KNOWN), "pasted with spaces and in capitals");

        for (String type : Types.ALL) {
            for (CraftData.Recipe rec : CraftData.ofType(type)) {
                for (Craft.Speed s : Craft.Speed.values()) {
                    RecipeCode.Recipe e = new RecipeCode.Recipe(type, rec.minLevel(), 1 + rec.minLevel() % 3, 3, s,
                            Arrays.asList(null, null, null, null, null, null));
                    assertEquals(e, RecipeCode.decode(RecipeCode.encode(e), KNOWN));
                }
            }
        }
    }

    @Test
    void garbageIsRefused() {
        String code = RecipeCode.encode(new RecipeCode.Recipe("ring", 50, 1, 1, Craft.Speed.NORMAL,
                Arrays.asList("Major's Badge", null, null, null, null, null)));
        assertNull(RecipeCode.decode("", KNOWN));
        assertNull(RecipeCode.decode(null, KNOWN));
        assertNull(RecipeCode.decode("hello there", KNOWN));
        assertNull(RecipeCode.decode("CR-40w3w3w3w3w3wF3850", KNOWN), "another crafter's code");
        assertNull(RecipeCode.decode(code.substring(0, code.length() - 1), KNOWN), "cut short");
        char[] typo = code.toCharArray();
        typo[5] = typo[5] == '0' ? '1' : '0';
        assertNull(RecipeCode.decode(new String(typo), KNOWN), "one letter changed");
        assertNull(RecipeCode.decode(code, List.of("Bat Heart")), "an ingredient this game data does not have");
    }
}
