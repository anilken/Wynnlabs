package io.github.anilken.wynnlabs.build.roadmap;

import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.market.Shopping;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.Slot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a milestone may offer and what it costs: the tiers and sources it allows, the off-skill
 * limit, the Mythics no later version replaces, how listings and prices weigh a piece, what a
 * set's buys cost, and the tie-break between close picks.
 */
final class CandidateRules {

    private CandidateRules() {}

    /**
     * Skill points a level-{@code m} build may put into skills outside rules.shopping.preferredSkills
     * for one piece: the rule's floor or 15 % of the points earned by then.
     */
    static int maxOff(int m) {
        return (int) Math.max(Rules.sub("shopping", "maxOffSkillPoints", 10),
                Math.max(0, (Math.min(m, 101) - 1) * 2) * 0.15);
    }

    /**
     * Per Mythic name, the lowest level of a later version of it in the item data that you can
     * get ({@code gettable}): Masterwork Aftershock (Lv 100) after Aftershock (Lv 77), or an
     * Ascended one. A later version with no known source (Ascended Olympic) or over the budget
     * replaces nothing: the older Mythic stays the best of its kind.
     */
    static Map<String, Integer> laterVersions(GameState state, java.util.function.Predicate<ItemData> gettable) {
        Map<String, Integer> out = new HashMap<>();
        state.allGear().forEach(info -> {
            for (String p : new String[] {"Masterwork ", "Ascended "}) {
                if (info.name().startsWith(p) && gettable.test(info)) {
                    out.merge(info.name().substring(p.length()), info.requirements().level(), Math::min);
                }
            }
        });
        return out;
    }

    /**
     * A Mythic that no later version replaces by milestone {@code m} (Freedom, Ignis, Hadal at
     * Lv 120): still the best of its kind there however far below the level window it sits.
     */
    static boolean lastOfItsKind(ItemData info, int m, Map<String, Integer> laterVersion) {
        if (!info.tier().name().equals("MYTHIC")) {
            return false;
        }
        Integer later = laterVersion.get(info.name());
        return later == null || later > m;
    }

    /** From shopping.endgameLevel (80) on, every tier and raid gear count. */
    static boolean endgame(int m) {
        return m >= Rules.sub("shopping", "endgameLevel", 80);
    }

    static List<String> badTiers(int m) {
        return endgame(m) ? List.of() : Rules.list("shopping", "excludeTiers");
    }

    static List<String> badSources(int m) {
        List<String> out = new ArrayList<>(Rules.list("shopping", "excludeSources"));
        if (endgame(m)) {
            out.remove("RAID");
        }
        return out;
    }

    /**
     * Whether the comparison panel may show this piece at milestone {@code m}: the tiers and
     * sources that milestone leaves out stay out of its panel too (a Mythic under "Hide Mythic
     * upgrades" before the endgame level).
     */
    static boolean panelAllows(ItemData info, int m) {
        return !badTiers(m).contains(info.tier().name()) && !Shopping.excluded(info, badSources(m));
    }

    /**
     * How much a piece's gain counts for while you would buy it soon: 1 when it is listed now
     * (or its price is unknown); 0.9 for Wynnventory's cache (listed, no count), 0.85 for few
     * or old listings, 0.6 when nothing is listed now (an old price, or none).
     */
    static double availability(Prices.Price p) {
        if (p == null) {
            return 1;
        }
        return switch (p.confidence()) {
            case NONE, HISTORY -> 0.6;
            case THIN, STALE -> 0.85;
            case CACHE -> 0.9;
            case OK -> 1;
        };
    }

    /** A gain scaled by an availability factor: a gain shrinks, a loss grows. */
    static double scaled(double gain, double factor) {
        return gain >= 0 ? gain * factor : gain / factor;
    }

    /**
     * The points the piece's requirements ask for in skills outside {@code preferred}, beyond
     * what the set already holds there ({@code cover}: assigned plus the gear's, per skill; null
     * counts every requirement point).
     */
    static int offPoints(ItemData info, List<String> preferred, double[] cover) {
        int off = 0;
        for (var r : info.requirements().skills()) {
            if (!preferred.contains(r.skill().name())) {
                double have = cover == null ? 0 : cover[r.skill().ordinal()];
                off += (int) Math.max(0, Math.ceil(r.points() - have));
            }
        }
        return off;
    }

    /**
     * What a piece costs on the Trade Market under a budget: what buying it is likely to cost
     * (Prices.Price#buyPrice: the middle of the market for an old price), 0 for one that cannot
     * be traded and has none (a quest reward), -1 for a tradable piece with no known price
     * (never suggested under a budget).
     */
    static double cost(GameState state, ItemData info) {
        Prices.Price p = state.price(info.name());
        Double v = p == null ? null : p.buyPrice();
        if (v != null) {
            return v;
        }
        return Shopping.tradable(info) ? -1 : 0;
    }

    /** A tradable piece whose price is only history: nothing listed now, or only old listings. */
    static boolean uncertainPrice(GameState state, ItemData info) {
        Prices.Price p = state.price(info.name());
        return Shopping.tradable(info) && p != null && p.typical() != null && p.uncertain();
    }

    /**
     * What one powder of this tier costs on the Trade Market, in emeralds: a rough guess
     * (rules.shopping.powderPrice1 … powderPrice7), as powders are not priced item by item.
     */
    static double powderPrice(int tier) {
        double[] guess = {4, 16, 64, 128, 320, 1024, 4096};
        int t = Math.max(1, Math.min(7, tier));
        return Rules.sub("shopping", "powderPrice" + t, guess[t - 1]);
    }

    /**
     * What the buys in {@code set} cost: every piece outside {@code fixed} that you do not have,
     * with the powders it is judged with. The powders for the pieces you have are left out: the
     * start of every milestone has them too, and the first milestone's powder rows say what they
     * cost (counted here, a small budget was spent before anything was bought).
     */
    static double spend(GameState state, Map<String, Damage.Piece> set, java.util.Set<String> fixed, java.util.Set<String> owned) {
        double sum = 0;
        java.util.Set<String> handsHeld = new java.util.HashSet<>();     // a ring you have fills one hand, not both
        for (var e : set.entrySet()) {
            Damage.Piece p = e.getValue();
            if (p == null || fixed.contains(e.getKey())) {
                continue;
            }
            boolean ring = e.getKey().startsWith("RING");
            // what you have costs nothing; a fresh copy of it (buysOwned) is a buy
            if (p.stack() != null ? !ring || handsHeld.add(p.key())
                    : !ring && owned.contains(p.name()) && !OwnedGear.buysOwned(p)) {
                continue;
            }
            ItemData info = Roadmap.findGear(p.name());
            int[] powders = Damage.assumedCount(p, info);
            sum += powders[0] * powderPrice(powders[1]);
            double c = info == null ? 0 : cost(state, info);
            if (info != null && uncertainPrice(state, info)) {
                // an old price is a soft limit (the ranking pays for it): the set counts the
                // lowest one seen, or a single such piece would undo the whole milestone
                c = state.price(info.name()).typical();
            }
            sum += c < 0 ? Double.POSITIVE_INFINITY : c;
        }
        return sum;
    }

    /**
     * The pick among {@code cleared} ({info, eff, gain, slot}, every market piece over the bar)
     * within rules.shopping.costTieBreakPercent (15 %) of the best: what you have first, then,
     * when {@code prices} (bought soon), the cheaper; else the best. Null when nothing is close.
     */
    static Object[] closest(List<Object[]> cleared, double best, java.util.Set<String> owned, boolean prices) {
        double within = Rules.sub("shopping", "costTieBreakPercent", 15) / 100.0;
        if (within <= 0 || best <= 0) {
            return null;
        }
        Object[] out = null;
        for (Object[] c : cleared) {
            if ((double) c[1] < best * (1 - within)) {
                continue;
            }
            if (out == null || better(c, out, owned, prices)) {
                out = c;
            }
        }
        return out;
    }

    /** {@link #closest}'s order: what you have, then the cheaper (when {@code prices}), then the higher gain. */
    private static boolean better(Object[] a, Object[] b, java.util.Set<String> owned, boolean prices) {
        ItemData ia = (ItemData) a[0];
        ItemData ib = (ItemData) b[0];
        boolean oa = ia.slot() != Slot.RING && owned.contains(ia.name());
        boolean ob = ib.slot() != Slot.RING && owned.contains(ib.name());
        if (oa != ob) {
            return oa;
        }
        if (prices && !oa) {
            double ca = Shopping.buyCost(ia);
            double cb = Shopping.buyCost(ib);
            if (ca != cb) {
                return ca < cb;
            }
        }
        return (double) a[1] > (double) b[1];
    }
}
