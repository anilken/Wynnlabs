package io.github.anilken.wynnlabs.crafting;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuggestionsTest {

    @Test
    void manyDistinctUsefulSuggestions() {
        List<Ingredient> all = CraftSearchTest.synthetic(400, 7);
        WhatCanICraft.Stock stock = new WhatCanICraft.Stock(Map.of("Synthetic 3", 2, "Synthetic 9", 1), Map.of(),
                Map.of("woodworking", 80));
        List<Suggestions.Suggestion> streamed = new ArrayList<>();
        List<Suggestions.Suggestion> out = Suggestions.run(List.of("wand", "relik", "bow"),
                Suggestions.goalSets(List.of("spell", "mana", "health", "melee")), 80, stock, all, n -> null, 100,
                new CraftSearch.Budget(60_000), streamed::add);
        assertEquals(out, streamed, "each suggestion is handed over as it is found");
        assertTrue(out.size() >= 20, "plenty for pages: " + out.size());
        Set<String> keys = new HashSet<>();
        for (Suggestions.Suggestion s : out) {
            assertTrue(s.layout().score() > 0);
            assertTrue(s.recipe().maxLevel() <= 80);
            assertTrue(keys.add(s.recipe().id() + s.layout().slots().stream().map(Ingredient::name).toList()),
                    "no layout twice");
            if (s.tier() == CraftSearch.Tier.OWN) {
                assertTrue(s.fromStock());
            }
        }
    }

    @Test
    void bracketsFollowTheCombatLevelNotTheProfession() {
        // Lv 59: 57-59 is the highest wearable, then 55-57, whatever woodworking is
        assertEquals(List.of(57, 55), Suggestions.brackets("wand", 59).stream().map(CraftData.Recipe::minLevel).toList());
        assertEquals(List.of(50, 47), Suggestions.brackets("wand", 53).stream().map(CraftData.Recipe::minLevel).toList());
        assertEquals(List.of(1), Suggestions.brackets("wand", 1).stream().map(CraftData.Recipe::minLevel).toList());
    }
}
