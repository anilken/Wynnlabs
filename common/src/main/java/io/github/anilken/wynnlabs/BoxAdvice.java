package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Klass;

import java.util.ArrayList;
import java.util.List;

/**
 * Verdicts for unidentified gear boxes: open, keep or sell, from the routes {@link BoxRoutes}
 * works out for the box's level range and type.
 */
final class BoxAdvice {

    private BoxAdvice() {}

    /**
     * Everything the box can hold, with its fee, its price listed unidentified (else identified)
     * and whether it is {@code wear}, the one you would wear. Asks for the prices it lacks.
     */
    static List<BoxRoutes.Outcome> outcomes(ItemView.Box box, String wear) {
        GameState state = GameState.current();
        List<String> names = new ArrayList<>();
        List<BoxRoutes.Outcome> out = new ArrayList<>();
        for (ItemData info : state.possibleGears(box)) {
            names.add(info.name());
            Prices.Price p = state.price(info.name());
            Double v = p == null ? null : p.unidTypical() != null ? p.unidTypical() : p.typical();
            out.add(new BoxRoutes.Outcome(info.name(), box.tier().identificationCost(info.level()), v,
                    Shopping.tradable(info), info.name().equals(wear)));
        }
        if (!names.isEmpty()) {
            state.requestPrices(names);
        }
        return out;
    }

    /** The verdict and note for the way {@link BoxRoutes} picked: the way first, then why. */
    static Advice.Entry boxRoute(ItemView.Box box, BoxRoutes.Choice c, String why, int fits, int all) {
        BoxRoutes.Value v = c.value();
        String cost = v.outcomes() == 0 ? box.tier().identificationCost(box.high()) + " E" : BoxRoutes.cost(v);
        String tail = why == null || why.isEmpty() ? "" : ". " + why;
        return switch (c.route()) {
            case IDENTIFIER -> new Advice.Entry(Verdict.IDENTIFY,
                    L.t("wynnlabs.auto.box_identify_wear", cost, c.wearable(), fits, all));
            case UNTRADABLE -> new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.sell_untradable", why));
            case REVEAL_MARKET -> AutoAdvice.market(new Advice.Entry(Verdict.SELL, v.expected() == null
                    ? L.t("wynnlabs.auto.box_reveal_no_price", cost) + tail
                    : L.t("wynnlabs.auto.box_reveal_market", cost, Prices.fmt(v.expected())) + unpriced(v) + tail),
                    v.net() == null ? Double.NaN : v.net());
            case BLACKSMITH -> new Advice.Entry(Verdict.SELL, v.expected() == null
                    ? L.t("wynnlabs.auto.box_blacksmith_no_price") + tail
                    : v.net() < 0
                    ? L.t("wynnlabs.auto.box_blacksmith_fee", Prices.fmt(v.expected()), cost) + unpriced(v) + tail
                    : L.t("wynnlabs.auto.box_blacksmith_small", Prices.fmt(v.net()), Prices.fmt(SellAdvice.revealMinProfit()))
                            + unpriced(v) + tail);
        };
    }

    /** " (Tarnkappe: no price)" or " (3/5 have a price)" when some outcomes have none; else "". */
    private static String unpriced(BoxRoutes.Value v) {
        List<String> missing = v.unpriced();
        if (missing.isEmpty()) {
            return "";
        }
        return " " + (missing.size() == 1 ? L.t("wynnlabs.auto.box_unpriced_one", missing.get(0))
                : L.t("wynnlabs.auto.box_unpriced_some", v.outcomes() - missing.size(), v.outcomes()));
    }

    static Advice.Entry box(ItemView.Box box, Klass cls, int level) {
        int tooLow = (int) Rules.num("tooLowLevels", 4);
        int tooHigh = (int) Rules.num("tooHighLevels", 5);
        int low = box.low();
        int high = box.high();
        if (high < level - tooLow) {
            // too old for you, maybe right for another of your characters (account bank is shared)
            for (ItemData info : GameState.current().possibleGears(box)) {
                Klass c = info.requirements().klass().orElse(info.slot().klass());
                String alt = AltCharacters.altOfLevel(c, low, high);
                if (alt != null) {
                    return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.box_for_alt", low, high, alt));
                }
            }
            return SellAdvice.sellBox(box, L.t("wynnlabs.auto.box_too_low", low, high));
        }
        int fits = 0;
        int all = 0;
        for (ItemData info : GameState.current().possibleGears(box)) {
            all++;
            Klass c = info.requirements().klass().orElse(info.slot().klass());
            if (c == null || c == Klass.NONE || c == cls) {
                fits++;
            }
        }
        if (all > 0 && fits == 0) {
            // not for you, but maybe for another of your characters (the account bank is shared)
            for (ItemData info : GameState.current().possibleGears(box)) {
                Klass c = info.requirements().klass().orElse(info.slot().klass());
                String alt = c == null ? null : AltCharacters.altOfClass(c, low, high);
                if (alt != null) {
                    return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.box_other_class_alt", AltCharacters.className(c), alt));
                }
            }
            return SellAdvice.sellBox(box, L.t("wynnlabs.auto.box_none_for_class", AltCharacters.className(cls)));
        }
        // If even the best outcome is weaker than what you wear, the box is for selling
        // the build model: does any outcome you could wear raise the build at its level? A box
        // whose outcomes all leave the build where it is gets sold, not identified
        Build.Plan plan = Build.plan();
        String wearName = null;
        if (all > 0 && plan != null && plan.result() != null && plan.result().objective() > 0) {
            double bestGain = Double.NEGATIVE_INFINITY;
            String bestName = null;
            for (ItemData info : GameState.current().possibleGears(box)) {
                Klass c = info.requirements().klass().orElse(info.slot().klass());
                if (c != null && c != Klass.NONE && c != cls) {
                    continue;
                }
                double g = GearScore.buildGain(plan, info, level);
                if (!Double.isNaN(g) && g > bestGain) {
                    bestGain = g;
                    bestName = info.name();
                }
            }
            if (bestName != null && bestGain < Rules.num("boxMinGainPercent", 3)) {
                return SellAdvice.sellBox(box, L.t("wynnlabs.auto.box_no_build_gain", bestName,
                        (bestGain >= 0 ? "+" : "") + Math.round(bestGain * 10) / 10.0));
            }
            wearName = bestName;
        }
        // too far ahead to identify now: kept for later only when an outcome will help (above)
        if (low > level + tooHigh) {
            return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.identify_near_level", low));
        }
        WornGear.Weakest wb = !GameState.current().inGame() ? null : WornGear.weakest(WornGear.worn(box.slot()));
        if (wb != null && wb.name() != null && all > 0) {
            double bestOutcome = Double.NEGATIVE_INFINITY;
            for (ItemData info : GameState.current().possibleGears(box)) {
                Klass c = info.requirements().klass().orElse(info.slot().klass());
                if (c == null || c == Klass.NONE || c == cls) {
                    bestOutcome = Math.max(bestOutcome, GearScore.score(info));
                }
            }
            if (bestOutcome < wb.score() - 0.5) {
                return SellAdvice.sellBox(box, L.t("wynnlabs.auto.box_worn_better", wb.name()));
            }
        }
        if (all == 0) {
            return new Advice.Entry(Verdict.IDENTIFY, L.t("wynnlabs.auto.box_identify", box.tier().identificationCost(high)));
        }
        // the one you would wear: the build's best pick, else the best-scoring outcome for you
        String wear = wearName;
        if (wear == null) {
            double best = Double.NEGATIVE_INFINITY;
            for (ItemData info : GameState.current().possibleGears(box)) {
                Klass c = info.requirements().klass().orElse(info.slot().klass());
                if ((c == null || c == Klass.NONE || c == cls) && info.level() <= level + tooHigh && GearScore.score(info) > best) {
                    best = GearScore.score(info);
                    wear = info.name();
                }
            }
        }
        if (wear == null) {
            return new Advice.Entry(Verdict.IDENTIFY, L.t("wynnlabs.auto.box_identify", box.tier().identificationCost(high)));
        }
        String tier = box.tier().name();
        boolean valuable = Rules.list("trade", "valuableTiers").contains(tier) || tier.equals("LEGENDARY");
        return boxRoute(box, BoxRoutes.choose(outcomes(box, wear), SellAdvice.marketFee(), SellAdvice.revealMinProfit(), valuable),
                null, fits, all);
    }
}
