package io.github.anilken.wynnlabs.crafting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * "Our suggestions": ready recipes for the player, many of them. Every item type that fits the
 * class, at the highest bracket the player can use at their combat level and the one below
 * (whatever their profession levels: a locked recipe is marked, not hidden), for each goal
 * set (in the class's order), made from own stock and as the strongest with up to two bought.
 * The same layout found twice is kept once, and a layout that adds nothing to its goal is
 * dropped. Each search gets a short budget; suggestions are handed over as they come.
 */
public final class Suggestions {

    /** A named goal ("spell", "health" …) the suggestions are made for. */
    public record GoalSet(String key, CraftSearch.Goal goal) {}

    /**
     * One suggestion: the goal set it is for, the recipe and tiers, the layout found, whether it
     * needs nothing bought, and the search tier it came from.
     */
    public record Suggestion(GoalSet goal, CraftData.Recipe recipe, int tier1, int tier2, CraftSearch.Layout layout,
                             CraftSearch.Tier tier, int order) {

        public boolean fromStock() {
            return layout.bought().isEmpty();
        }
    }

    private static final CraftSearch.Tier[] TIERS = {CraftSearch.Tier.OWN, CraftSearch.Tier.OWN_PLUS_TWO, CraftSearch.Tier.ANY};

    private Suggestions() {}

    /**
     * Runs every search one after another within {@code budget}; {@code each} gets every kept
     * suggestion as soon as it is found.
     */
    public static List<Suggestion> run(List<String> types, List<GoalSet> goals, int combatLevel, WhatCanICraft.Stock stock,
                                       List<Ingredient> all, Function<String, CraftSearch.Price> prices,
                                       long millisEach, CraftSearch.Budget budget, Consumer<Suggestion> each) {
        List<Suggestion> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int order = 0;
        for (GoalSet g : goals) {
            for (String type : types) {
                for (CraftData.Recipe r : brackets(type, combatLevel)) {
                    int t1 = Math.max(1, WhatCanICraft.bestTier(stock, r.materials().get(0)));
                    int t2 = Math.max(1, WhatCanICraft.bestTier(stock, r.materials().get(1)));
                    for (CraftSearch.Tier tier : TIERS) {
                        if (budget.cancelled()) {
                            return out;
                        }
                        CraftSearch.Request q = new CraftSearch.Request(r, t1, t2, Craft.Speed.NORMAL, g.goal(), all,
                                stock.ingredients(), stock.level(r.profession()), true, prices);
                        CraftSearch.Outcome o = CraftSearch.search(q, tier, new CraftSearch.Budget(millisEach, budget));
                        CraftSearch.Layout l = o.best();
                        if (l == null || l.score() <= 0 || l.slots().stream().allMatch(Ingredient::empty)) {
                            continue;
                        }
                        // the same ingredients one bracket lower are the same suggestion: keep the first (higher)
                        String key = r.type() + "|" + l.slots().stream().map(Ingredient::name).sorted().toList();
                        if (!seen.add(key)) {
                            continue;
                        }
                        Suggestion s = new Suggestion(g, r, t1, t2, l, tier, order++);
                        out.add(s);
                        if (each != null) {
                            each.accept(s);
                        }
                    }
                }
            }
        }
        return out;
    }

    /** The highest bracket of a type usable at a combat level (its top level at most that), and the one below it. */
    static List<CraftData.Recipe> brackets(String type, int combatLevel) {
        List<CraftData.Recipe> rs = CraftData.ofType(type);
        if (rs.isEmpty()) {
            return List.of();
        }
        int top = -1;
        for (int i = 0; i < rs.size(); i++) {
            if (rs.get(i).maxLevel() <= Math.max(3, combatLevel)) {
                top = i;
            }
        }
        if (top < 0) {
            return List.of();
        }
        return top == 0 ? List.of(rs.get(0)) : List.of(rs.get(top), rs.get(top - 1));
    }

    /** The goal sets offered, as weights over identifications. */
    public static List<GoalSet> goalSets(List<String> order) {
        Map<String, Map<String, Double>> sets = Map.of(
                "spell", Map.of("spellDamage", 1.0, "rawSpellDamage", 0.1, "manaRegen", 2.0),
                "melee", Map.of("mainAttackDamage", 1.0, "rawMainAttackDamage", 0.1, "rawAttackSpeed", 5.0),
                "health", Map.of("rawHealth", 0.02, "healthRegenRaw", 0.05, "healthRegen", 0.5),
                "mana", Map.of("manaRegen", 3.0, "manaSteal", 3.0, "spellDamage", 0.2),
                "speed", Map.of("walkSpeed", 1.0, "rawAgility", 0.5),
                "loot", Map.of("lootBonus", 1.0, "lootQuality", 1.0),
                "xp", Map.of("xpBonus", 1.0, "lootBonus", 0.2),
                "lifesteal", Map.of("lifeSteal", 1.0, "healthRegenRaw", 0.2));
        List<GoalSet> out = new ArrayList<>();
        for (String k : order) {
            Map<String, Double> w = sets.get(k);
            if (w != null) {
                out.add(new GoalSet(k, new CraftSearch.Goal(w, CraftSearch.Pick.MEAN, Set.of(), CraftSearch.Goal.uncapped(), 1)));
            }
        }
        return out;
    }
}
