package io.github.anilken.wynnlabs.crafting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * "Our suggestions" as whole sets: for a goal, one craft per slot from head to toe (the class's
 * weapon, the four armour pieces, two rings, a bracelet, a necklace) plus a potion, at the same
 * bracket step from the combat level and from the same search tier. Inside a set the bag and
 * bank are shared: an ingredient copy used by one piece is not counted for the next.
 */
public final class SetSuggestions {

    /** One piece of a set: the slot type, the recipe and tiers, and the layout found (null: nothing worth it). */
    public record Piece(String type, CraftData.Recipe recipe, int tier1, int tier2, CraftSearch.Layout layout) {}

    /**
     * A set: its goal, the bracket step (0 = the highest wearable), the search tier, the pieces in
     * slot order, the gear pieces' identifications added up, the highest requirement per skill,
     * and every copy that must be bought.
     */
    public record CraftSet(Suggestions.GoalSet goal, int step, CraftSearch.Tier tier, List<Piece> pieces,
                       Map<String, Craft.Range> totals, int[] maxRequirements, List<String> bought) {

        public boolean fromStock() {
            return bought.isEmpty();
        }

        public int found() {
            int n = 0;
            for (Piece p : pieces) {
                if (p.layout() != null) {
                    n++;
                }
            }
            return n;
        }
    }

    private static final CraftSearch.Tier[] TIERS = {CraftSearch.Tier.OWN, CraftSearch.Tier.OWN_PLUS_TWO, CraftSearch.Tier.ANY};

    private SetSuggestions() {}

    /** The slots of a set, the class's weapon first; the potion last. */
    public static List<String> slots(String weapon) {
        return List.of(weapon, "helmet", "chestplate", "leggings", "boots", "ring", "ring", "bracelet", "necklace", "potion");
    }

    public static List<CraftSet> run(String weapon, List<Suggestions.GoalSet> goals, int combatLevel, WhatCanICraft.Stock stock,
                                 List<Ingredient> all, Function<String, CraftSearch.Price> prices, long millisEach,
                                 CraftSearch.Budget budget, Consumer<CraftSet> each) {
        List<CraftSet> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Suggestions.GoalSet g : goals) {
            for (int step = 0; step < 2; step++) {
                for (CraftSearch.Tier tier : TIERS) {
                    if (budget.cancelled()) {
                        return out;
                    }
                    CraftSet s = one(weapon, g, step, tier, combatLevel, stock, all, prices, millisEach, budget);
                    if (s == null || s.found() < 3) {
                        continue;
                    }
                    StringBuilder key = new StringBuilder();
                    for (Piece p : s.pieces()) {
                        key.append(p.recipe() == null ? "-" : p.recipe().id()).append(':')
                                .append(p.layout() == null ? "-" : p.layout().slots().stream().map(Ingredient::name).sorted().toList())
                                .append(';');
                    }
                    if (!seen.add(key.toString())) {
                        continue;
                    }
                    out.add(s);
                    if (each != null) {
                        each.accept(s);
                    }
                }
            }
        }
        return out;
    }

    static CraftSet one(String weapon, Suggestions.GoalSet g, int step, CraftSearch.Tier tier, int combatLevel,
                    WhatCanICraft.Stock stock, List<Ingredient> all, Function<String, CraftSearch.Price> prices,
                    long millisEach, CraftSearch.Budget budget) {
        Map<String, Integer> left = new HashMap<>(stock.ingredients());
        List<Piece> pieces = new ArrayList<>();
        Map<String, Craft.Range> totals = new LinkedHashMap<>();
        int[] maxReq = new int[5];
        List<String> bought = new ArrayList<>();
        for (String type : slots(weapon)) {
            if (budget.cancelled()) {
                return null;
            }
            List<CraftData.Recipe> brackets = Suggestions.brackets(type, combatLevel);
            if (brackets.isEmpty()) {
                pieces.add(new Piece(type, null, 1, 1, null));
                continue;
            }
            CraftData.Recipe r = brackets.get(Math.min(step, brackets.size() - 1));
            int t1 = Math.max(1, WhatCanICraft.bestTier(stock, r.materials().get(0)));
            int t2 = Math.max(1, WhatCanICraft.bestTier(stock, r.materials().get(1)));
            CraftSearch.Request q = new CraftSearch.Request(r, t1, t2, Craft.Speed.NORMAL, g.goal(), all, Map.copyOf(left),
                    stock.level(r.profession()), true, prices);
            CraftSearch.Layout l = CraftSearch.search(q, tier, new CraftSearch.Budget(millisEach, budget)).best();
            if (l == null || l.score() <= 0 || l.slots().stream().allMatch(Ingredient::empty)) {
                pieces.add(new Piece(type, r, t1, t2, null));
                continue;
            }
            pieces.add(new Piece(type, r, t1, t2, l));
            bought.addAll(l.bought());
            // the copies this piece uses are no longer there for the next one
            for (Ingredient in : l.slots()) {
                if (!in.empty()) {
                    left.computeIfPresent(in.name(), (k, v) -> v > 1 ? v - 1 : null);
                }
            }
            if (!r.consumable()) {
                l.result().ids().forEach((id, v) -> totals.merge(id, v, (a, b) -> new Craft.Range(a.min() + b.min(), a.max() + b.max())));
                for (int s = 0; s < 5; s++) {
                    maxReq[s] = Math.max(maxReq[s], l.result().requirements()[s]);
                }
            }
        }
        return new CraftSet(g, step, tier, List.copyOf(pieces), totals, maxReq, List.copyOf(bought));
    }
}
