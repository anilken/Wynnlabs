package io.github.anilken.wynnlabs.crafting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * "What can I craft now": for each item type, the best craft the player can make from their own
 * stock at a bracket their profession level allows, or what they lack. Types run one after
 * another, each with a short budget, and every answer is handed over as soon as it is ready.
 */
public final class WhatCanICraft {

    /** Something a type lacks: profession level, a material (how many short) or any ingredient for the goal. */
    public record Lack(Kind kind, String what, int count) {
        public enum Kind { LEVEL, MATERIAL, INGREDIENTS }
    }

    /**
     * One type's answer: the recipe chosen (null when even the first bracket is locked), the
     * material tiers used, the best own-stock layout (null when there is none worth making) and
     * what is lacking.
     */
    public record Entry(String type, CraftData.Recipe recipe, int tier1, int tier2, CraftSearch.Layout best,
                        List<Lack> lacks) {

        public boolean craftable() {
            return lacks.isEmpty() && best != null;
        }
    }

    /**
     * The player's stock: ingredient counts by name, material counts by shown name per tier
     * ({@code counts[t]} for tiers 1-3, index 0 unused) and profession levels by profession.
     */
    public record Stock(Map<String, Integer> ingredients, Map<String, int[]> materials, Map<String, Integer> levels) {

        public int material(String shown, int tier) {
            int[] c = materials.get(shown);
            return c == null || tier < 1 || tier >= c.length ? 0 : c[tier];
        }

        /** A profession's level; one not known yet gates nothing. */
        public int level(String profession) {
            Integer l = levels.get(profession);
            return l == null ? 200 : l;
        }
    }

    private WhatCanICraft() {}

    /** Runs every type; stops early when the budget is cancelled. */
    public static List<Entry> run(Stock stock, CraftSearch.Goal goal, List<Ingredient> all, long millisEach,
                                  CraftSearch.Budget budget, Consumer<Entry> each) {
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < Types.ALL.size(); i++) {
            if (budget.cancelled()) {
                break;
            }
            Entry e = one(Types.ALL.get(i), stock, goal, all, new CraftSearch.Budget(millisEach, budget));
            out.add(e);
            if (each != null && !budget.cancelled()) {
                each.accept(e);
            }
        }
        return out;
    }

    /** The highest material tier with enough copies for the recipe, or 0. */
    static int bestTier(Stock stock, CraftData.Material m) {
        for (int t = 3; t >= 1; t--) {
            if (stock.material(m.shown(), t) >= m.amount()) {
                return t;
            }
        }
        return 0;
    }

    static Entry one(String type, Stock stock, CraftSearch.Goal goal, List<Ingredient> all, CraftSearch.Budget budget) {
        List<CraftData.Recipe> brackets = CraftData.ofType(type);
        if (brackets.isEmpty()) {
            return new Entry(type, null, 0, 0, null, List.of());
        }
        String profession = brackets.get(0).profession();
        int level = stock.level(profession);
        // The highest bracket the level allows whose materials the player has; else that highest one, short.
        CraftData.Recipe top = null;
        CraftData.Recipe chosen = null;
        for (int i = brackets.size() - 1; i >= 0; i--) {
            CraftData.Recipe r = brackets.get(i);
            if (r.minLevel() > level) {
                continue;
            }
            if (top == null) {
                top = r;
            }
            if (bestTier(stock, r.materials().get(0)) > 0 && bestTier(stock, r.materials().get(1)) > 0) {
                chosen = r;
                break;
            }
        }
        if (top == null) {
            return new Entry(type, null, 0, 0, null,
                    List.of(new Lack(Lack.Kind.LEVEL, profession, brackets.get(0).minLevel())));
        }
        List<Lack> lacks = new ArrayList<>();
        CraftData.Recipe r = chosen != null ? chosen : top;
        int t1 = bestTier(stock, r.materials().get(0));
        int t2 = bestTier(stock, r.materials().get(1));
        if (chosen == null) {
            for (CraftData.Material m : r.materials()) {
                int have = Math.max(Math.max(stock.material(m.shown(), 1), stock.material(m.shown(), 2)),
                        stock.material(m.shown(), 3));
                if (have < m.amount()) {
                    lacks.add(new Lack(Lack.Kind.MATERIAL, m.shown(), m.amount() - have));
                }
            }
        }
        CraftSearch.Request q = new CraftSearch.Request(r, Math.max(1, t1), Math.max(1, t2), Craft.Speed.NORMAL, goal, all,
                stock.ingredients(), level, false, null);
        CraftSearch.Outcome o = CraftSearch.search(q, CraftSearch.Tier.OWN, budget);
        CraftSearch.Layout best = o.best();
        if (best == null || best.score() <= 0) {
            lacks.add(new Lack(Lack.Kind.INGREDIENTS, profession, 0));
            best = null;
        }
        return new Entry(type, r, t1, t2, best, List.copyOf(lacks));
    }
}
