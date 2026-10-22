package io.github.anilken.wynnlabs.advice;

import io.github.anilken.wynnlabs.coach.Today;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.model.ItemView;

import java.util.List;

/**
 * Where an item you will not use goes: the Trade Market after its fee, a reveal first when
 * that pays, or the Blacksmith.
 */
final class SellAdvice {

    private SellAdvice() {}

    /** {@code price} less the market fee. */
    static double afterFee(double price) {
        return price * (1 - marketFee() / 100.0);
    }

    /**
     * SAT, and where: the Trade Market when something makes the item worth more than the
     * Blacksmith pays (shiny, perfect, a valuable tier, a high roll), else the Blacksmith.
     */
    static Advice.Entry sell(ItemView gear, String why) {
        String restriction = gear.data().restriction();
        if (restriction.equals("QUEST_ITEM")) {
            return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.quest_item"));
        }
        // One of your other characters would wear it: keep it for them (bank is shared), when
        // the player asks for that ("Keep items for your other characters")
        String alt = Settings.on("altAdvice") ? Today.forAlt(gear.data()) : null;
        if (alt != null && !restriction.equals("SOULBOUND")) {
            return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.keep_for_alt", why, alt));
        }
        if (restriction.equals("UNTRADABLE") || restriction.equals("SOULBOUND")) {
            return new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.sell_untradable", why));
        }
        // A known market price beats every guess below; a week-old one is no longer known
        GameState state = GameState.current();
        boolean high = AutoAdvice.highRoll(gear);
        Prices.Price price = state.price(gear.data().name());
        if (price == null || state.now() - price.seen() > 7L * 24 * 3600 * 1000) {
            state.requestPrices(List.of(gear.data().name()));
            price = price != null && state.now() - price.seen() > 7L * 24 * 3600 * 1000 ? null : price;
        }
        if (price != null && gear.unidentified()) {
            Advice.Entry revealed = sellRevealed(gear, why, price);
            if (revealed != null) {
                return revealed;
            }
        }
        if (price != null) {
            Double typical = price.typical();
            if (typical != null) {
                if (typical >= Rules.sub("trade", "minTradeMarketPrice", 1920)) {
                    return AutoAdvice.market(new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.sell_market_price", why,
                            Prices.fmt(typical))), afterFee(typical));
                }
                if (high) {
                    // the price is the cheapest listing, any roll: a high roll sells for more than that
                    return AutoAdvice.market(new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.sell_market_high_roll", why,
                            Math.round(gear.rolled().overallPercent()), Prices.fmt(typical))), afterFee(typical));
                }
                return new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.sell_blacksmith_market_price", why, Prices.fmt(typical)));
            }
        }
        String where;
        ItemView.Rolled gi = gear.rolled();
        String tier = gear.data().tier().name();
        float pct = gi != null && gi.hasOverall() ? gi.overallPercent() : -1;
        if (gi != null && gi.shiny()) {
            where = L.t("wynnlabs.auto.sell_shiny");
        } else if (gi != null && gi.perfect()) {
            where = L.t("wynnlabs.auto.sell_perfect");
        } else if (Rules.list("trade", "valuableTiers").contains(tier)) {
            where = L.t("wynnlabs.auto.sell_valuable_tier", AutoAdvice.nice(tier));
        } else if (high) {
            where = L.t("wynnlabs.auto.sell_market_high_roll_no_price", Math.round(pct));
        } else if (tier.equals("LEGENDARY") && pct >= Rules.sub("trade", "legendaryMinPercent", 80)
                || pct >= Rules.sub("trade", "anyMinPercent", 95)) {
            // without a price the 30 EB rule cannot be checked: say so instead of guessing
            where = L.t("wynnlabs.auto.sell_blacksmith_high_roll", Math.round(pct),
                    Prices.fmt(Rules.sub("trade", "minTradeMarketPrice", 1920)));
        } else {
            where = L.t("wynnlabs.auto.sell_blacksmith_no_price");
        }
        return new Advice.Entry(Verdict.SELL, why + ". " + where);
    }

    /**
     * A revealed piece you will not wear (named, stats "?"): it lists on the Trade Market as it
     * is, but buyers cannot see its rolls. Identify it first only when identified copies sell
     * for clearly more (at least rules.trade.revealMinProfit over the fee). Null without a price.
     */
    private static Advice.Entry sellRevealed(ItemView gear, String why, Prices.Price price) {
        Double unid = price.unidTypical();
        Double identified = price.typical();
        int cost = gear.data().tier().identificationCost(gear.data().level());
        if (unid != null && identified != null
                && (identified - unid) * (1 - marketFee() / 100.0) - cost >= revealMinProfit()) {
            return new Advice.Entry(Verdict.IDENTIFY, L.t("wynnlabs.auto.unid_identify_then_sell", cost,
                    Prices.fmt(identified), Prices.fmt(unid), why));
        }
        Double asIs = unid != null ? unid : identified;
        if (asIs == null) {
            return null;
        }
        if (asIs >= Rules.sub("trade", "minTradeMarketPrice", 1920)) {
            return AutoAdvice.market(new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.sell_market_unidentified", why,
                    Prices.fmt(asIs))), afterFee(asIs));
        }
        return new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.sell_blacksmith_market_price", why, Prices.fmt(asIs)));
    }

    static Advice.Entry sellBox(ItemView.Box box, String why) {
        // Boss altar and similar drops cannot be traded: if nothing in the box can, only the
        // Blacksmith takes it (The Scarecrow's Vest from Bovine Barn)
        String tier = box.tier().name();
        boolean valuable = Rules.list("trade", "valuableTiers").contains(tier) || tier.equals("LEGENDARY");
        BoxRoutes.Choice c = BoxRoutes.choose(BoxAdvice.outcomes(box, null), marketFee(), revealMinProfit(), valuable);
        return BoxAdvice.boxRoute(box, c, why, 0, 0);
    }

    /** The market fee the advice takes off a sale, in % (rules.trade.marketFeePercent). */
    static double marketFee() {
        return Rules.sub("trade", "marketFeePercent", 5);
    }

    /** Emeralds a reveal and listing must leave over its fee (rules.trade.revealMinProfit). */
    static double revealMinProfit() {
        return Rules.sub("trade", "revealMinProfit", 640);
    }
}
