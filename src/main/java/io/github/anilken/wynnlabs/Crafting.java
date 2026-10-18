package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.crafting.CraftChecks;
import io.github.anilken.wynnlabs.crafting.CraftSearch;
import io.github.anilken.wynnlabs.crafting.Ingredient;
import io.github.anilken.wynnlabs.crafting.SavedRecipes;
import io.github.anilken.wynnlabs.crafting.StockView;
import io.github.anilken.wynnlabs.crafting.WhatCanICraft;
import io.github.anilken.wynnlabs.model.SkillKind;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the crafting tab reads from the game and the mod, in the crafting package's plain types:
 * stock from the bag and the reachable bank pages, profession levels and XP, skill points,
 * market prices, stations, and the saved recipes file. Game thread only; the tab hands copies
 * to the search thread.
 */
final class Crafting {

    private static SavedRecipes saved;

    private Crafting() {}

    /** The saved recipes file: one for every character, not under a character's folder. */
    static Path savedFile() {
        return Dirs.config().resolve("crafting").resolve("recipes-saved.json");
    }

    static SavedRecipes saved() {
        if (saved == null) {
            String json = null;
            try {
                Path f = savedFile();
                if (Files.exists(f)) {
                    json = Files.readString(f, StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                System.out.println("[WynnLabs] could not read saved recipes: " + e);
            }
            saved = SavedRecipes.fromJson(json);
        }
        return saved;
    }

    /** Writes the saved recipes a moment later, off the game thread. */
    static void persist() {
        String json = saved().toJson();
        Saver.mark(savedFile(), () -> json);
    }

    /** For tests: forget the loaded list so the next read comes from the file. */
    static void reload() {
        saved = null;
    }

    /** The bag and the bank pages this character can open (account bank and its own). */
    static List<StockView.Stack> stacks() {
        GameState g = GameState.current();
        List<StockView.Stack> out = new ArrayList<>();
        for (ItemStack s : g.inventory()) {
            if (s != null && !s.isEmpty()) {
                g.view(s).ifPresent(v -> out.add(new StockView.Stack(v, s.getCount())));
            }
        }
        for (String key : Vault.pageKeys()) {
            if (!key.startsWith("acct:") && !key.startsWith("char:" + Profile.id() + ":")) {
                continue;
            }
            for (Vault.Stored st : Vault.page(key)) {
                if (!"ingredient".equals(st.kind()) && !"material".equals(st.kind())) {
                    continue;
                }
                g.view(Vault.stackOf(st)).ifPresent(v -> out.add(new StockView.Stack(v, st.count())));
            }
        }
        return out;
    }

    static WhatCanICraft.Stock stock() {
        return StockView.of(stacks(), Professions.levels());
    }

    /** The player's points per skill (assigned, gear, tomes, set bonus), in requirement order. */
    static int[] points() {
        int[] p = new int[5];
        for (SkillKind s : SkillKind.values()) {
            p[s.ordinal()] = Build.totalPoints(s);
        }
        return p;
    }

    /** {xp into the level, xp the level needs} for a profession ("woodworking"). */
    static int[] professionXp(String profession) {
        return Wyn.professionXp(profession.toUpperCase(Locale.ROOT));
    }

    /** The nearest station of a profession to the player: {x, y, z, distance}, or null. */
    static int[] nearestStation(String profession) {
        List<int[]> st = Wyn.stations(profession.toUpperCase(Locale.ROOT));
        double[] pos = GameState.current().position();
        int i = CraftChecks.nearest(st, pos[0], pos[1]);
        if (i < 0) {
            return null;
        }
        int[] s = st.get(i);
        return new int[] {s[0], s[1], s[2], CraftChecks.distance(s, pos[0], pos[1])};
    }

    /** Points the compass at the nearest station; its distance, or -1 when there is none. */
    static int pinStation(String profession, String label) {
        int[] s = nearestStation(profession);
        if (s == null || !Waypoints.go(s[0], s[1], s[2], label)) {
            return -1;
        }
        return s[3];
    }

    /** A market price for the search: the lowest listing, marked old when the mod doubts it. */
    static CraftSearch.Price price(String name) {
        Prices.Price p = GameState.current().price(name);
        Double v = p == null ? null : p.typical();
        return v == null ? null : new CraftSearch.Price(Math.round(v), p.doubt() != null);
    }

    /** Prices for some names, read now, for a search on another thread. */
    static Map<String, CraftSearch.Price> prices(Iterable<Ingredient> ingredients) {
        Map<String, CraftSearch.Price> out = new HashMap<>();
        for (Ingredient i : ingredients) {
            CraftSearch.Price p = price(i.name());
            if (p != null) {
                out.put(i.name(), p);
            }
        }
        return out;
    }
}
