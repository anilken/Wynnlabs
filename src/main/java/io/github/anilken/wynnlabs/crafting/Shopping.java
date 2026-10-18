package io.github.anilken.wynnlabs.crafting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * What the current recipe still lacks: materials at the chosen tiers and ingredients on the
 * grid the player does not own, with counts and what the ingredients cost on the market.
 * Materials have no market price here (they are gathered or bought in bulk), so only
 * ingredients add to the cost; an ingredient without a price is named.
 */
public final class Shopping {

    /** One line: a material ({@code tier} 1-3) or an ingredient ({@code tier} 0), how many, and the price of all of them (null: unknown). */
    public record Line(String name, int tier, int count, Long cost, boolean old) {
        public boolean material() {
            return tier > 0;
        }
    }

    /** The lines, the cost of the priced ones, and the ingredients whose price is unknown. */
    public record Needs(List<Line> lines, long cost, List<String> unpriced) {
        public boolean empty() {
            return lines.isEmpty();
        }
    }

    private Shopping() {}

    public static Needs of(CraftData.Recipe recipe, int tier1, int tier2, List<Ingredient> slots, WhatCanICraft.Stock stock,
                           Function<String, CraftSearch.Price> prices) {
        List<Line> lines = new ArrayList<>();
        for (StockView.Short s : StockView.missing(recipe, tier1, tier2, stock)) {
            lines.add(new Line(s.material(), s.tier(), s.missing(), null, false));
        }
        Map<String, Integer> need = new LinkedHashMap<>();
        for (Ingredient i : slots) {
            if (i != null && !i.empty()) {
                need.merge(i.name(), 1, Integer::sum);
            }
        }
        long cost = 0;
        List<String> unpriced = new ArrayList<>();
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            Integer owned = stock.ingredients().get(e.getKey());
            int lacking = e.getValue() - (owned == null ? 0 : owned);
            if (lacking <= 0) {
                continue;
            }
            CraftSearch.Price p = prices == null ? null : prices.apply(e.getKey());
            Long c = p == null ? null : p.emeralds() * lacking;
            if (c == null) {
                unpriced.add(e.getKey());
            } else {
                cost += c;
            }
            lines.add(new Line(e.getKey(), 0, lacking, c, p != null && p.old()));
        }
        return new Needs(List.copyOf(lines), cost, List.copyOf(unpriced));
    }
}
