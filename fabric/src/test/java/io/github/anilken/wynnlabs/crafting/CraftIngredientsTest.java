package io.github.anilken.wynnlabs.crafting;

import io.github.anilken.wynnlabs.game.Wyn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Without Wynntils' ingredient model the crafting tab gets an empty list, not a crash. */
class CraftIngredientsTest {

    @Test
    void noWynntilsGivesNoIngredients() {
        assertTrue(Wyn.craftIngredients().isEmpty());
    }
}
