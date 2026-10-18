package io.github.anilken.wynnlabs.crafting;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.anilken.wynnlabs.crafting.Ings.ing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhatCanICraftTest {

    static final Ingredient RING_GEM = ing("Ring Gem", List.of("jeweling"), new Object[][] {{"spellDamage", 3, 5}},
            new int[6], -10, 0, 0, new int[5]);
    static final Ingredient WAND_TWIG = ing("Wand Twig", List.of("woodworking"), new Object[][] {{"spellDamage", 4, 6}},
            new int[6], -20, 0, 0, new int[5]);

    /** Rings and wands have ingredients and materials; nothing else does. Every profession is level 30. */
    static WhatCanICraft.Stock stock() {
        Map<String, int[]> mats = new HashMap<>();
        for (String type : List.of("ring", "wand")) {
            for (CraftData.Recipe r : CraftData.ofType(type)) {
                for (CraftData.Material m : r.materials()) {
                    mats.put(m.shown(), new int[] {0, 0, 20, 0});
                }
            }
        }
        Map<String, Integer> levels = new HashMap<>();
        for (String p : List.of("armouring", "tailoring", "weaponsmithing", "woodworking", "jeweling", "alchemism",
                "scribing", "cooking")) {
            levels.put(p, 30);
        }
        return new WhatCanICraft.Stock(Map.of("Ring Gem", 6, "Wand Twig", 2), mats, levels);
    }

    @Test
    void typesWithoutIngredientsSayWhatTheyLack() {
        List<WhatCanICraft.Entry> streamed = new ArrayList<>();
        List<WhatCanICraft.Entry> all = WhatCanICraft.run(stock(), CraftSearch.Goal.of("spellDamage"),
                List.of(RING_GEM, WAND_TWIG), 300, new CraftSearch.Budget(10_000), streamed::add);
        assertEquals(15, all.size());
        assertEquals(all, streamed, "each type is handed over as it finishes");
        for (WhatCanICraft.Entry e : all) {
            switch (e.type()) {
                case "ring", "wand" -> {
                    assertTrue(e.craftable(), e.toString());
                    assertNotNull(e.best());
                    assertEquals(30, e.recipe().minLevel(), "the highest bracket level 30 allows");
                    assertEquals(2, e.tier1());
                }
                case "helmet" -> {
                    assertFalse(e.craftable());
                    assertTrue(e.lacks().contains(new WhatCanICraft.Lack(WhatCanICraft.Lack.Kind.MATERIAL, "Sandstone Ingot", 3)),
                            e.toString());
                    assertEquals(30, e.recipe().minLevel(), "short, so the highest bracket the level allows");
                    assertTrue(e.lacks().stream().anyMatch(l -> l.kind() == WhatCanICraft.Lack.Kind.INGREDIENTS));
                }
                default -> {
                    // Bracelets, necklaces, bows and reliks share the professions; the rest have no ingredient at all.
                    boolean hasIngredient = List.of("jeweling", "woodworking").contains(e.recipe().profession());
                    boolean hasMaterials = e.lacks().stream().noneMatch(l -> l.kind() == WhatCanICraft.Lack.Kind.MATERIAL);
                    assertEquals(hasIngredient && hasMaterials, e.craftable(), e.toString());
                    assertEquals(!hasIngredient, e.lacks().contains(new WhatCanICraft.Lack(WhatCanICraft.Lack.Kind.INGREDIENTS,
                            e.recipe().profession(), 0)), e.toString());
                }
            }
        }
        long wandTwigs = all.stream().filter(e -> e.type().equals("wand")).findFirst().orElseThrow()
                .best().slots().stream().filter(i -> i.name().equals("Wand Twig")).count();
        assertEquals(2, wandTwigs, "only the two twigs owned");
    }

    @Test
    void levelGatesTheBracket() {
        WhatCanICraft.Stock s = stock();
        Map<String, Integer> levels = new HashMap<>(s.levels());
        levels.put("jeweling", 0);
        WhatCanICraft.Entry ring = WhatCanICraft.one("ring", new WhatCanICraft.Stock(s.ingredients(), s.materials(), levels),
                CraftSearch.Goal.of("spellDamage"), List.of(RING_GEM), new CraftSearch.Budget(1000));
        assertEquals(List.of(new WhatCanICraft.Lack(WhatCanICraft.Lack.Kind.LEVEL, "jeweling", 1)), ring.lacks());
    }
}
