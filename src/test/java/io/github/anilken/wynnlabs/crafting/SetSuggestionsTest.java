package io.github.anilken.wynnlabs.crafting;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetSuggestionsTest {

    /** A synthetic list where every ingredient may be used by every profession. */
    private static List<Ingredient> everyProfession() {
        List<Ingredient> out = new ArrayList<>();
        List<String> all = List.of("armouring", "tailoring", "weaponsmithing", "woodworking", "jeweling", "alchemism",
                "scribing", "cooking");
        for (Ingredient i : CraftSearchTest.synthetic(300, 11)) {
            out.add(new Ingredient(i.name(), i.tier(), i.level(), all, i.ids(), i.positions(), i.durability(), i.charges(),
                    i.duration(), i.requirements()));
        }
        return out;
    }

    @Test
    void wholeSetsWithSharedStock() {
        List<Ingredient> all = everyProfession();
        // one copy of a strong spell ingredient: only one piece of an own-stock set may use it
        String one = all.get(5).name();
        WhatCanICraft.Stock stock = new WhatCanICraft.Stock(Map.of(one, 1), Map.of(), Map.of());
        List<SetSuggestions.CraftSet> sets = SetSuggestions.run("relik", Suggestions.goalSets(List.of("spell", "health")), 60,
                stock, all, n -> null, 60, new CraftSearch.Budget(60_000), null);
        assertFalse(sets.isEmpty());
        for (SetSuggestions.CraftSet s : sets) {
            assertEquals(10, s.pieces().size());
            assertEquals(List.of("relik", "helmet", "chestplate", "leggings", "boots", "ring", "ring", "bracelet", "necklace",
                    "potion"), s.pieces().stream().map(SetSuggestions.Piece::type).toList());
            assertTrue(s.found() >= 3);
            int uses = 0;
            for (SetSuggestions.Piece p : s.pieces()) {
                if (p.layout() != null) {
                    assertTrue(p.recipe().maxLevel() <= 60);
                    uses += (int) p.layout().slots().stream().filter(i -> i.name().equals(one)).count();
                }
            }
            if (s.tier() == CraftSearch.Tier.OWN) {
                assertTrue(uses <= 1, "the single copy is used once in the whole set: " + uses);
                assertTrue(s.fromStock());
            }
            // the totals are the gear pieces' ranges added up
            Map<String, Craft.Range> sum = new HashMap<>();
            for (SetSuggestions.Piece p : s.pieces()) {
                if (p.layout() != null && !p.recipe().consumable()) {
                    p.layout().result().ids().forEach((k, v) -> sum.merge(k, v,
                            (a, b) -> new Craft.Range(a.min() + b.min(), a.max() + b.max())));
                }
            }
            assertEquals(sum, s.totals());
        }
    }
}
