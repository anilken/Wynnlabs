package io.github.anilken.wynnlabs.crafting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The layout optimiser: for a recipe and a goal, the six ingredients (and where they sit) that
 * score highest while the craft keeps its durability floor, the goal's filters and requirement
 * caps. Three tiers: own ingredients only, own plus at most two bought, and any.
 *
 * <p>Every number the search adds up is additive per slot (identification ranges, requirements
 * and durability all sum over the slots), so a layout's score is the sum of what each ingredient
 * gives at its slot's effectiveness. Ingredients with position effects ("meta") are placed first,
 * in every arrangement of up to three of them; the other slots are then filled by a depth-first
 * search over multisets with an upper bound and feasibility bounds that cut branches which
 * cannot win or cannot meet the floor and caps.
 */
public final class CraftSearch {

    public enum Tier { OWN, OWN_PLUS_TWO, ANY }

    /** Which end of an identification's range the goal counts. */
    public enum Pick { MIN, MEAN, MAX }

    public static final int MAX_BOUGHT = 2;

    /** The most meta arrangements tried in one search. */
    static final int MAX_CONFIGS = 200_000;

    /**
     * A goal: identification → weight, the end of the range to count, identifications that must
     * not go below zero, a cap per skill requirement ({@link #NO_CAP} for none, in strength,
     * dexterity, intelligence, defence, agility order) and the lowest durability allowed.
     */
    public record Goal(Map<String, Double> weights, Pick pick, Set<String> noNegative, int[] caps, int durabilityFloor) {

        public static final int NO_CAP = Integer.MAX_VALUE;

        public static Goal of(String id) {
            return new Goal(Map.of(id, 1.0), Pick.MEAN, Set.of(), uncapped(), 1);
        }

        public static int[] uncapped() {
            int[] c = new int[5];
            Arrays.fill(c, NO_CAP);
            return c;
        }

        double value(Craft.Range r) {
            return switch (pick) {
                case MIN -> r.min();
                case MAX -> r.max();
                case MEAN -> (r.min() + r.max()) / 2.0;
            };
        }

        /** The weighted sum over a craft's identifications. */
        public double score(Map<String, Craft.Range> ids) {
            double s = 0;
            for (Map.Entry<String, Double> w : weights.entrySet()) {
                Craft.Range r = ids.get(w.getKey());
                if (r != null) {
                    s += w.getValue() * value(r);
                }
            }
            return s;
        }

        /** True when a craft keeps the filters, the caps and (for gear) the durability floor. */
        public boolean allows(Craft.Result r) {
            for (String id : noNegative) {
                Craft.Range v = r.ids().get(id);
                if (v != null && v.min() < 0) {
                    return false;
                }
            }
            for (int s = 0; s < 5; s++) {
                if (caps[s] != NO_CAP && r.requirements()[s] > caps[s]) {
                    return false;
                }
            }
            return r.durability() == null || r.durability().min() >= durabilityFloor;
        }

        boolean capped() {
            for (int c : caps) {
                if (c != NO_CAP) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A market price in emeralds; {@code old} when it may be out of date. */
    public record Price(long emeralds, boolean old) {}

    /**
     * What to search: the recipe and its tiers, the goal, every known ingredient, how many of
     * each the player owns (by name), the player's level in the recipe's profession, whether
     * ingredients above it may be used, and market prices by name (null when unknown).
     */
    public record Request(CraftData.Recipe recipe, int tier1, int tier2, Craft.Speed speed, Goal goal,
                          List<Ingredient> ingredients, Map<String, Integer> owned, int professionLevel,
                          boolean allowLocked, Function<String, Price> prices) {

        int own(String name) {
            Integer n = owned.get(name);
            return n == null ? 0 : n;
        }
    }

    /**
     * A layout found: its slots, the craft, the goal score, what must be bought (one name per
     * copy) and what that costs, with the names whose price is unknown or old.
     */
    public record Layout(List<Ingredient> slots, Craft.Result result, double score, List<String> bought, long cost,
                         List<String> unpriced, List<String> oldPrices) {

        public boolean costComplete() {
            return unpriced.isEmpty();
        }
    }

    /**
     * One tier's answer; {@code best} is null when nothing meets the constraints. {@code complete}
     * is false when the budget stopped the search or there were too many metas to try every
     * arrangement: then the best may be beaten.
     */
    public record Outcome(Tier tier, Layout best, boolean complete) {}

    /** A deadline and a stop flag; the search reads them and writes its progress (0 to 1). */
    public static final class Budget {
        private final long deadline;
        private final Budget parent;
        private volatile boolean cancelled;
        private volatile double progress;

        public Budget(long millis) {
            this(millis, null);
        }

        /** A share of a larger budget: cancelling the parent stops this one too. */
        public Budget(long millis, Budget parent) {
            this.deadline = System.nanoTime() + millis * 1_000_000L;
            this.parent = parent;
        }

        public void cancel() {
            cancelled = true;
        }

        public boolean cancelled() {
            return cancelled || parent != null && parent.cancelled();
        }

        public double progress() {
            return progress;
        }

        boolean over() {
            return cancelled() || System.nanoTime() > deadline;
        }
    }

    private CraftSearch() {}

    // ---- candidates ------------------------------------------------------------------------

    /**
     * The ingredients a tier may use for a request: the recipe's profession, at or below the
     * recipe's level, within the player's profession level unless locked ones are allowed, owned
     * for the own-only tier, and of some use: a goal identification, a position effect, positive
     * durability, a lower requirement where one is capped, or a positive filtered identification.
     */
    public static List<Ingredient> candidates(Request q, Tier tier) {
        List<Ingredient> out = new ArrayList<>();
        boolean consumable = q.recipe().consumable();
        for (Ingredient ing : q.ingredients()) {
            if (ing.empty() || !ing.usableBy(q.recipe().profession()) || ing.level() > q.recipe().maxLevel()) {
                continue;
            }
            if (!q.allowLocked() && ing.level() > q.professionLevel()) {
                continue;
            }
            if (tier == Tier.OWN && q.own(ing.name()) <= 0) {
                continue;
            }
            if (useful(ing, q.goal(), consumable)) {
                out.add(ing);
            }
        }
        return out;
    }

    static boolean useful(Ingredient ing, Goal goal, boolean consumable) {
        if (ing.meta()) {
            return true;
        }
        for (String id : goal.weights().keySet()) {
            if (ing.ids().containsKey(id) && goal.weights().get(id) != 0) {
                return true;
            }
        }
        if (!consumable && ing.durability() > 0) {
            return true;
        }
        if (!consumable && goal.capped()) {
            for (int s = 0; s < 5; s++) {
                if (goal.caps()[s] != Goal.NO_CAP && ing.requirements()[s] < 0) {
                    return true;
                }
            }
        }
        for (String id : goal.noNegative()) {
            Ingredient.Stat st = ing.ids().get(id);
            if (st != null && st.max() > 0) {
                return true;
            }
        }
        return false;
    }

    // ---- the search ------------------------------------------------------------------------

    /** Runs one tier within a budget. */
    public static Outcome search(Request q, Tier tier, Budget budget) {
        return new Search(q, tier, budget).run();
    }

    /** All three tiers, one after another, sharing {@code millis} equally; {@code all} reports progress and stops them. */
    public static List<Outcome> searchAll(Request q, long millis, Budget all) {
        List<Outcome> out = new ArrayList<>();
        Tier[] tiers = Tier.values();
        for (int i = 0; i < tiers.length; i++) {
            out.add(search(q, tiers[i], new Budget(millis / tiers.length, all)));
            all.progress = (i + 1.0) / tiers.length;
        }
        return out;
    }

    /** What one ingredient adds at one effectiveness, in the numbers the search checks. */
    private static final class Gain {
        final int ing;          // index into the candidate list, -1 for an empty slot
        final double score;
        final int durability;
        final int[] req;
        final int[] floorMins;  // per filtered identification, the range's low end

        Gain(int ing, double score, int durability, int[] req, int[] floorMins) {
            this.ing = ing;
            this.score = score;
            this.durability = durability;
            this.req = req;
            this.floorMins = floorMins;
        }
    }

    /** The gains of every plain candidate at one effectiveness, best score first, the empty slot among them. */
    private static final class Table {
        final Gain[] gains;
        final double[] bestFrom;  // the best score at or after each position (never below 0: a slot can stay empty)
        final int maxDurability;
        final int[] minReq;
        final int[] maxFloorMins;

        Table(Gain[] gains, int filters) {
            this.gains = gains;
            bestFrom = new double[gains.length + 1];
            for (int i = gains.length - 1; i >= 0; i--) {
                bestFrom[i] = Math.max(Math.max(bestFrom[i + 1], gains[i].score), 0);
            }
            int md = 0;
            int[] mr = new int[5];
            int[] mf = new int[filters];
            for (Gain g : gains) {
                md = Math.max(md, g.durability);
                for (int s = 0; s < 5; s++) {
                    mr[s] = Math.min(mr[s], g.req[s]);
                }
                for (int f = 0; f < filters; f++) {
                    mf[f] = Math.max(mf[f], g.floorMins[f]);
                }
            }
            maxDurability = md;
            minReq = mr;
            maxFloorMins = mf;
        }
    }

    private static final class Search {
        final Request q;
        final Tier tier;
        final Budget budget;
        final Goal goal;
        final boolean consumable;
        final List<String> filters;
        final List<Ingredient> plain = new ArrayList<>();
        final List<Ingredient> metas = new ArrayList<>();
        final Map<Integer, Table> tables = new HashMap<>();
        final int baseDurability;
        final boolean capped;

        // the best so far
        double bestScore = Double.NEGATIVE_INFINITY;
        long bestCost = Long.MAX_VALUE;
        Ingredient[] best;
        boolean stopped;
        boolean truncated;
        long nodes;

        // the layout being built
        final Ingredient[] layout = new Ingredient[Craft.SLOTS];
        final Map<String, Integer> usedByName = new HashMap<>();
        int bought;

        Search(Request q, Tier tier, Budget budget) {
            this.q = q;
            this.tier = tier;
            this.budget = budget;
            this.goal = q.goal();
            this.consumable = q.recipe().consumable();
            this.filters = new ArrayList<>(goal.noNegative());
            this.capped = !consumable && goal.capped();
            for (Ingredient ing : candidates(q, tier)) {
                (ing.meta() ? metas : plain).add(ing);
            }
            Craft.Result empty = Craft.craft(q.recipe(), q.tier1(), q.tier2(), q.speed(), Craft.slots());
            this.baseDurability = empty.durability() == null ? 0 : empty.durability().min();
        }

        Gain gain(Ingredient ing, int index, int eff) {
            double score = 0;
            for (Map.Entry<String, Double> w : goal.weights().entrySet()) {
                Ingredient.Stat st = ing.ids().get(w.getKey());
                if (st != null) {
                    score += w.getValue() * goal.value(Craft.scaled(st, eff));
                }
            }
            int[] req = new int[5];
            if (!consumable) {
                for (int s = 0; s < 5; s++) {
                    req[s] = Craft.scaledRequirement(ing.requirements()[s], eff);
                }
            }
            int[] fm = new int[filters.size()];
            for (int f = 0; f < fm.length; f++) {
                Ingredient.Stat st = ing.ids().get(filters.get(f));
                fm[f] = st == null ? 0 : Craft.scaled(st, eff).min();
            }
            return new Gain(index, score, consumable ? 0 : ing.durability(), req, fm);
        }

        Table table(int eff) {
            return tables.computeIfAbsent(eff, e -> {
                List<Gain> gs = new ArrayList<>();
                Gain none = new Gain(-1, 0, 0, new int[5], new int[filters.size()]);
                for (int i = 0; i < plain.size(); i++) {
                    Gain g = gain(plain.get(i), i, e);
                    if (!dominatedByEmpty(g, none)) {
                        gs.add(g);
                    }
                }
                gs.add(none);
                gs.sort(Comparator.comparingDouble((Gain g) -> -g.score));
                return new Table(gs.toArray(new Gain[0]), filters.size());
            });
        }

        /** An ingredient that is no better than an empty slot in every way the search checks. */
        boolean dominatedByEmpty(Gain g, Gain none) {
            if (g.score > none.score || (!consumable && g.durability > 0)) {
                return false;
            }
            if (capped) {
                for (int s = 0; s < 5; s++) {
                    if (goal.caps()[s] != Goal.NO_CAP && g.req[s] < 0) {
                        return false;
                    }
                }
            }
            for (int f = 0; f < filters.size(); f++) {
                if (g.floorMins[f] > 0) {
                    return false;
                }
            }
            return true;
        }

        Outcome run() {
            List<int[]> configs = metaConfigs();
            // Score bound of each arrangement: its metas' own score plus the best each free slot can add.
            List<double[]> ranked = new ArrayList<>();
            for (int c = 0; c < configs.size(); c++) {
                Ingredient[] l = metaLayout(configs.get(c));
                int[] eff = Craft.effectiveness(Arrays.asList(l));
                double bound = 0;
                for (int s = 0; s < Craft.SLOTS; s++) {
                    bound += l[s] == null ? table(eff[s]).bestFrom[0] : gain(l[s], -1, eff[s]).score;
                }
                ranked.add(new double[] {bound, c});
            }
            ranked.sort(Comparator.comparingDouble((double[] r) -> -r[0]));
            int done = 0;
            for (double[] r : ranked) {
                if (r[0] < bestScore - 1e-9 || stopped) {
                    break;
                }
                if (budget.over()) {
                    stopped = true;
                    break;
                }
                fillConfig(configs.get((int) r[1]));
                budget.progress = (double) ++done / ranked.size();
            }
            return new Outcome(tier, best == null ? null : layoutOf(best), !stopped && !truncated);
        }

        /**
         * Every arrangement of metas, each as meta index per slot (-1 free): as many metas at
         * once as keeps the count of arrangements under {@link #MAX_CONFIGS}. When that cuts the
         * count below six, layouts with more metas are not tried and the outcome says so.
         */
        List<int[]> metaConfigs() {
            List<int[]> out = new ArrayList<>();
            int most = 0;
            double total = 1;
            for (int m = 1; m <= Craft.SLOTS; m++) {
                total += choose(Craft.SLOTS, m) * Math.pow(metas.size(), m);
                if (total > MAX_CONFIGS) {
                    break;
                }
                most = m;
            }
            truncated = !metas.isEmpty() && most < Craft.SLOTS;
            int[] cur = new int[Craft.SLOTS];
            Arrays.fill(cur, -1);
            out.add(cur.clone());
            placeMetas(out, cur, 0, 0, most);
            return out;
        }

        private static double choose(int n, int k) {
            double c = 1;
            for (int i = 0; i < k; i++) {
                c = c * (n - i) / (i + 1);
            }
            return c;
        }

        private void placeMetas(List<int[]> out, int[] cur, int fromSlot, int placed, int most) {
            if (placed == most) {
                return;
            }
            for (int s = fromSlot; s < Craft.SLOTS; s++) {
                for (int m = 0; m < metas.size(); m++) {
                    cur[s] = m;
                    if (affordable(cur)) {
                        out.add(cur.clone());
                        placeMetas(out, cur, s + 1, placed + 1, most);
                    }
                    cur[s] = -1;
                }
            }
        }

        /** True when the tier allows these metas: owned copies, then at most two bought. */
        private boolean affordable(int[] cfg) {
            if (tier == Tier.ANY) {
                return true;
            }
            Map<String, Integer> n = new HashMap<>();
            int buy = 0;
            for (int m : cfg) {
                if (m >= 0) {
                    String name = metas.get(m).name();
                    int k = n.merge(name, 1, Integer::sum);
                    if (k > q.own(name)) {
                        buy++;
                    }
                }
            }
            return tier == Tier.OWN ? buy == 0 : buy <= MAX_BOUGHT;
        }

        private Ingredient[] metaLayout(int[] cfg) {
            Ingredient[] l = new Ingredient[Craft.SLOTS];
            for (int s = 0; s < Craft.SLOTS; s++) {
                l[s] = cfg[s] < 0 ? null : metas.get(cfg[s]);
            }
            return l;
        }

        private void fillConfig(int[] cfg) {
            Ingredient[] l = metaLayout(cfg);
            int[] eff = Craft.effectiveness(Arrays.asList(l));
            double score = 0;
            int dur = 0;
            int[] req = new int[5];
            int[] fm = new int[filters.size()];
            usedByName.clear();
            bought = 0;
            List<Integer> free = new ArrayList<>();
            for (int s = 0; s < Craft.SLOTS; s++) {
                layout[s] = l[s];
                if (l[s] == null) {
                    free.add(s);
                    continue;
                }
                Gain g = gain(l[s], -1, eff[s]);
                score += g.score;
                dur += g.durability;
                add(req, g.req, 1);
                add(fm, g.floorMins, 1);
                if (usedByName.merge(l[s].name(), 1, Integer::sum) > q.own(l[s].name())) {
                    bought++;
                }
            }
            // Free slots, highest effectiveness first; equal ones form a group filled as a multiset.
            free.sort(Comparator.comparingInt((Integer s) -> -eff[s]));
            int n = free.size();
            int[] slot = new int[n];
            Table[] tab = new Table[n];
            boolean[] sameAsPrev = new boolean[n];
            for (int i = 0; i < n; i++) {
                slot[i] = free.get(i);
                tab[i] = table(eff[slot[i]]);
                sameAsPrev[i] = i > 0 && eff[slot[i]] == eff[slot[i - 1]];
            }
            fill(0, 0, slot, tab, sameAsPrev, score, dur, req, fm);
        }

        private static void add(int[] into, int[] from, int sign) {
            for (int i = 0; i < into.length; i++) {
                into[i] += sign * from[i];
            }
        }

        private void fill(int depth, int minPos, int[] slot, Table[] tab, boolean[] same, double score, int dur,
                          int[] req, int[] fm) {
            if ((++nodes & 4095) == 0 && budget.over()) {
                stopped = true;
            }
            if (stopped) {
                return;
            }
            int n = slot.length;
            if (depth == n) {
                leaf(score, dur, req, fm);
                return;
            }
            // Bounds over the slots still free: the most each can add, and the best case for every constraint.
            double ub = score;
            int durUb = dur;
            int[] reqLb = req.clone();
            int[] fmUb = fm.clone();
            for (int i = depth; i < n; i++) {
                int from = i == depth ? minPos : 0;
                ub += tab[i].bestFrom[Math.min(from, tab[i].gains.length)];
                durUb += tab[i].maxDurability;
                add(reqLb, tab[i].minReq, 1);
                add(fmUb, tab[i].maxFloorMins, 1);
            }
            if (ub < bestScore - 1e-9) {
                return;
            }
            if (!consumable && baseDurability + durUb < goal.durabilityFloor()) {
                return;
            }
            if (capped) {
                for (int s = 0; s < 5; s++) {
                    if (goal.caps()[s] != Goal.NO_CAP && reqLb[s] > goal.caps()[s]) {
                        return;
                    }
                }
            }
            for (int f = 0; f < fmUb.length; f++) {
                if (fmUb[f] < 0) {
                    return;
                }
            }
            Gain[] gains = tab[depth].gains;
            for (int p = minPos; p < gains.length && !stopped; p++) {
                Gain g = gains[p];
                if (score + g.score + restBest(depth + 1, tab) < bestScore - 1e-9) {
                    break;  // best score first: nothing after this can reach the best either
                }
                boolean buy = false;
                if (g.ing >= 0) {
                    Ingredient ing = plain.get(g.ing);
                    if (tier != Tier.ANY) {
                        int have = q.own(ing.name()) - usedByName.getOrDefault(ing.name(), 0);
                        if (have <= 0) {
                            if (tier == Tier.OWN || bought >= MAX_BOUGHT) {
                                continue;
                            }
                            buy = true;
                        }
                    }
                    layout[slot[depth]] = ing;
                    usedByName.merge(ing.name(), 1, Integer::sum);
                } else {
                    layout[slot[depth]] = null;
                }
                if (buy) {
                    bought++;
                }
                add(req, g.req, 1);
                add(fm, g.floorMins, 1);
                int next = depth + 1 < n && same[depth + 1] ? p : 0;
                fill(depth + 1, next, slot, tab, same, score + g.score, dur + g.durability, req, fm);
                add(req, g.req, -1);
                add(fm, g.floorMins, -1);
                if (buy) {
                    bought--;
                }
                if (g.ing >= 0) {
                    usedByName.merge(plain.get(g.ing).name(), -1, Integer::sum);
                }
                layout[slot[depth]] = null;
            }
        }

        private double restBest(int from, Table[] tab) {
            double s = 0;
            for (int i = from; i < tab.length; i++) {
                s += tab[i].bestFrom[0];
            }
            return s;
        }

        private void leaf(double score, int dur, int[] req, int[] fm) {
            if (!consumable && baseDurability + dur < goal.durabilityFloor()) {
                return;
            }
            if (capped) {
                for (int s = 0; s < 5; s++) {
                    if (goal.caps()[s] != Goal.NO_CAP && req[s] > goal.caps()[s]) {
                        return;
                    }
                }
            }
            for (int f : fm) {
                if (f < 0) {
                    return;
                }
            }
            if (score < bestScore - 1e-9) {
                return;
            }
            boolean better = score > bestScore + 1e-9;
            long cost = buyCost(layout);
            if (better || cost < bestCost || (cost == bestCost && count(layout) < count(best))) {
                bestScore = better ? score : Math.max(bestScore, score);
                bestCost = cost;
                best = layout.clone();
            }
        }

        private static int count(Ingredient[] l) {
            int n = 0;
            if (l != null) {
                for (Ingredient i : l) {
                    if (i != null) {
                        n++;
                    }
                }
            }
            return n;
        }

        /** What buying the missing copies costs, unknown prices counted high so priced layouts win ties. */
        private long buyCost(Ingredient[] l) {
            long c = 0;
            for (String name : missing(l, q)) {
                Price p = q.prices() == null ? null : q.prices().apply(name);
                c += p == null ? 1_000_000_000L : p.emeralds();
            }
            return c;
        }

        Layout layoutOf(Ingredient[] l) {
            List<Ingredient> slots = new ArrayList<>(Craft.SLOTS);
            for (Ingredient i : l) {
                slots.add(i == null ? Ingredient.NONE : i);
            }
            Craft.Result r = Craft.craft(q.recipe(), q.tier1(), q.tier2(), q.speed(), slots);
            List<String> missing = missing(l, q);
            long cost = 0;
            List<String> unpriced = new ArrayList<>();
            List<String> old = new ArrayList<>();
            for (String name : missing) {
                Price p = q.prices() == null ? null : q.prices().apply(name);
                if (p == null) {
                    if (!unpriced.contains(name)) {
                        unpriced.add(name);
                    }
                } else {
                    cost += p.emeralds();
                    if (p.old() && !old.contains(name)) {
                        old.add(name);
                    }
                }
            }
            return new Layout(Collections.unmodifiableList(slots), r, goal.score(r.ids()), List.copyOf(missing), cost,
                    List.copyOf(unpriced), List.copyOf(old));
        }
    }

    /** One name per copy a layout uses beyond what the player owns. */
    static List<String> missing(Ingredient[] l, Request q) {
        Map<String, Integer> used = new LinkedHashMap<>();
        List<String> out = new ArrayList<>();
        for (Ingredient i : l) {
            if (i == null || i.empty()) {
                continue;
            }
            if (used.merge(i.name(), 1, Integer::sum) > q.own(i.name())) {
                out.add(i.name());
            }
        }
        return out;
    }
}
