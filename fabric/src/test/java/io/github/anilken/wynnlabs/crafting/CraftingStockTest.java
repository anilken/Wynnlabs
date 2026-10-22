package io.github.anilken.wynnlabs.crafting;

import io.github.anilken.wynnlabs.core.Profile;
import io.github.anilken.wynnlabs.core.Saver;
import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;
import io.github.anilken.wynnlabs.game.Professions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The crafting stock read from a recorded character's bag and bank, and the saved recipes file. */
class CraftingStockTest {

    @TempDir
    Path dir;

    private static WhatCanICraft.Stock fixtureStock(FixtureState fx) {
        List<StockView.Stack> stacks = new ArrayList<>();
        for (var s : fx.inventory()) {
            if (s != null && !s.isEmpty()) {
                fx.view(s).ifPresent(v -> stacks.add(new StockView.Stack(v, s.getCount())));
            }
        }
        for (FixtureState.BankItem b : fx.bank()) {
            stacks.add(new StockView.Stack(b.view(), b.count()));
        }
        return StockView.of(stacks, Professions.levels());
    }

    @Test
    void stockFromBagAndBank() throws Exception {
        FixtureState fx = FixtureState.load(Fixtures.root().resolve("shaman-44"));
        fx.install("en");
        WhatCanICraft.Stock stock = fixtureStock(fx);
        assertEquals(45, stock.ingredients().get("Putrid Gunk"));
        assertEquals(44, stock.ingredients().size());
        assertEquals(83, stock.materials().get("Copper Ingot")[2]);
        assertEquals(16, stock.materials().get("Copper Ingot")[3]);
        assertEquals(66, stock.materials().get("Diamond Gem")[2]);
        assertEquals(0, stock.materials().get("Diamond Gem")[1]);
        assertFalse(stock.levels().isEmpty());

        // Spear 1-3: one copper ingot (owned at ★★★) and two oak planks (none).
        CraftData.Recipe spear = CraftData.byId("Spear1-3");
        assertEquals(List.of(new StockView.Short("Oak Planks", 1, 2, 0)), StockView.missing(spear, 3, 1, stock));
        assertEquals(List.of(new StockView.Short("Copper Ingot", 1, 1, 0), new StockView.Short("Oak Planks", 1, 2, 0)),
                StockView.missing(spear, 1, 1, stock), "no ★ copper, only ★★ and ★★★");

        // "What can I craft" on this stock: no type has both its materials, so each says what it lacks.
        Ingredient gunk = new Ingredient("Putrid Gunk", 0, 1, List.of("scribing"),
                Map.of("spellDamage", new Ingredient.Stat(1, 2, "", "")), new int[6], 0, 0, 0, new int[5]);
        List<WhatCanICraft.Entry> all = WhatCanICraft.run(stock, CraftSearch.Goal.of("spellDamage"), List.of(gunk), 200,
                new CraftSearch.Budget(10_000), null);
        assertEquals(15, all.size());
        for (WhatCanICraft.Entry e : all) {
            assertFalse(e.lacks().isEmpty(), e.type());
        }
        WhatCanICraft.Entry scroll = all.stream().filter(e -> e.type().equals("scroll")).findFirst().orElseThrow();
        assertTrue(scroll.lacks().stream().anyMatch(l -> l.kind() == WhatCanICraft.Lack.Kind.MATERIAL), scroll.toString());
        assertFalse(scroll.lacks().stream().anyMatch(l -> l.kind() == WhatCanICraft.Lack.Kind.INGREDIENTS),
                "scribing has the gunk: only materials are short");
    }

    @Test
    void shoppingListNamesUnknownPrices() throws Exception {
        FixtureState fx = FixtureState.load(Fixtures.root().resolve("shaman-44"));
        fx.install("en");
        WhatCanICraft.Stock stock = fixtureStock(fx);
        Ingredient gunk = new Ingredient("Putrid Gunk", 0, 4, List.of("scribing"), Map.of(), new int[6], 0, 0, 0, new int[5]);
        Ingredient rare = new Ingredient("Rare Thing", 3, 4, List.of("scribing"), Map.of(), new int[6], 0, 0, 0, new int[5]);
        Ingredient priced = new Ingredient("Priced Thing", 1, 4, List.of("scribing"), Map.of(), new int[6], 0, 0, 0, new int[5]);
        Shopping.Needs n = Shopping.of(CraftData.byId("Scroll1-3"), 1, 1, List.of(gunk, rare, priced, priced,
                Ingredient.NONE, Ingredient.NONE), stock, name -> name.equals("Priced Thing")
                        ? new CraftSearch.Price(300, true) : null);
        assertEquals(2, n.lines().stream().filter(Shopping.Line::material).count(), "oil and paper are both short");
        assertTrue(n.lines().stream().noneMatch(l -> l.name().equals("Putrid Gunk")), "45 gunk owned");
        assertEquals(600, n.cost());
        assertEquals(List.of("Rare Thing"), n.unpriced());
        assertTrue(n.lines().stream().anyMatch(l -> l.name().equals("Priced Thing") && l.count() == 2 && l.old()));
    }

    @Test
    void savedRecipesRoundTripAndShareOneFile() throws Exception {
        SavedRecipes r = new SavedRecipes();
        CraftSearch.Goal goal = new CraftSearch.Goal(Map.of("spellDamage", 1.0, "manaRegen", 0.5), CraftSearch.Pick.MIN,
                java.util.Set.of("healthRegenRaw"), new int[] {CraftSearch.Goal.NO_CAP, CraftSearch.Goal.NO_CAP, 60,
                        CraftSearch.Goal.NO_CAP, CraftSearch.Goal.NO_CAP}, 50);
        SavedRecipes.Saved relik = new SavedRecipes.Saved("Relik büyü", "relik", 103, 3, 2, "FAST",
                Arrays.asList("Foul Fairy Dust", null, "Gone From The Game", null, null, null), SavedRecipes.SavedGoal.of(goal), 1L);
        assertTrue(r.save(relik, false));
        assertTrue(r.save(new SavedRecipes.Saved("Yüzük mana", "ring", 50, 1, 1, "NORMAL",
                Arrays.asList(new String[6]), SavedRecipes.SavedGoal.of(CraftSearch.Goal.of("manaRegen")), 2L), false));
        assertFalse(r.save(relik, false), "a taken name is not overwritten without asking");
        assertEquals("Relik büyü (2)", r.copy("Relik büyü").name());
        assertFalse(r.rename("Relik büyü (2)", "Yüzük mana"));
        assertTrue(r.rename("Relik büyü (2)", "Relik yedek"));
        assertTrue(r.delete("Relik yedek"));

        Path f = dir.resolve("recipes-saved.json");
        Saver.writeNow(f, r.toJson());
        SavedRecipes back = SavedRecipes.fromJson(Files.readString(f, StandardCharsets.UTF_8));
        assertEquals(List.of("Relik büyü", "Yüzük mana"), back.all().stream().map(SavedRecipes.Saved::name).toList());
        SavedRecipes.Saved b = back.get("Relik büyü");
        assertEquals(Craft_speed("FAST"), b.attackSpeed());
        assertEquals("Gone From The Game", b.slots().get(2), "an unknown ingredient is kept");
        assertEquals(6, b.slots().size());
        CraftSearch.Goal g = b.goal().goal();
        assertEquals(goal.weights(), g.weights());
        assertEquals(goal.pick(), g.pick());
        assertEquals(goal.noNegative(), g.noNegative());
        assertEquals(60, g.caps()[2]);
        assertEquals(50, g.durabilityFloor());
        assertEquals(CraftData.byId("Relik103-105"), b.recipe());
        assertTrue(SavedRecipes.fromJson("not json").all().isEmpty());

        // The file is not in a character's folder: switching characters reads the same one.
        var current = Profile.class.getDeclaredField("current");
        current.setAccessible(true);
        Object was = current.get(null);
        try {
            current.set(null, "aaaa1111");
            Path a = Crafting.savedFile();
            current.set(null, "bbbb2222");
            assertEquals(a, Crafting.savedFile());
            assertTrue(a.endsWith(Path.of("wynnlabs", "crafting", "recipes-saved.json")), a.toString());
        } finally {
            current.set(null, was);
        }
    }

    private static io.github.anilken.wynnlabs.crafting.Craft.Speed Craft_speed(String s) {
        return io.github.anilken.wynnlabs.crafting.Craft.Speed.valueOf(s);
    }
}
