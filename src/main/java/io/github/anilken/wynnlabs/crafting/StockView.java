package io.github.anilken.wynnlabs.crafting;

import io.github.anilken.wynnlabs.model.ItemView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The player's crafting stock from the stacks the mod can see (the bag and every bank page it
 * has read): ingredients counted by name, materials by name and tier, and what a recipe still
 * needs.
 */
public final class StockView {

    /** One stack as read: the mod's view of it and how many. */
    public record Stack(ItemView view, int count) {}

    /** A material a recipe is short of: its shown name, the tier picked, how many it needs and how many there are. */
    public record Short(String material, int tier, int need, int have) {
        public int missing() {
            return need - have;
        }
    }

    private StockView() {}

    /** Builds the stock; profession levels by Wynntils' upper-case name, unknown ones (0 or −1) left out. */
    public static WhatCanICraft.Stock of(List<Stack> stacks, Map<String, Integer> levels) {
        Map<String, Integer> ingredients = new HashMap<>();
        Map<String, int[]> materials = new HashMap<>();
        for (Stack s : stacks) {
            ItemView v = s.view();
            if (v == null || v.name() == null || s.count() <= 0) {
                continue;
            }
            if (v.kind() == ItemView.Kind.INGREDIENT) {
                ingredients.merge(v.name(), s.count(), Integer::sum);
            } else if (v.kind() == ItemView.Kind.MATERIAL) {
                int tier = v.qualityTier() == null ? 1 : Math.max(1, Math.min(3, v.qualityTier()));
                materials.computeIfAbsent(v.name(), k -> new int[4])[tier] += s.count();
            }
        }
        Map<String, Integer> lv = new HashMap<>();
        levels.forEach((p, l) -> {
            if (l != null && l > 0) {          // 0: Wynntils has not read it yet
                lv.put(p.toLowerCase(Locale.ROOT), l);
            }
        });
        return new WhatCanICraft.Stock(Map.copyOf(ingredients), Map.copyOf(materials), Map.copyOf(lv));
    }

    /** The recipe's materials at the chosen tiers that the stock does not cover. */
    public static List<Short> missing(CraftData.Recipe recipe, int tier1, int tier2, WhatCanICraft.Stock stock) {
        List<Short> out = new ArrayList<>();
        int[] tiers = {tier1, tier2};
        for (int i = 0; i < 2; i++) {
            CraftData.Material m = recipe.materials().get(i);
            int have = stock.material(m.shown(), tiers[i]);
            if (have < m.amount()) {
                out.add(new Short(m.shown(), tiers[i], m.amount(), have));
            }
        }
        return out;
    }
}
