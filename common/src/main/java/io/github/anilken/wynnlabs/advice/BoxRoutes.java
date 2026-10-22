package io.github.anilken.wynnlabs.advice;

import java.util.ArrayList;
import java.util.List;

/**
 * Which way an unidentified gear box goes, and what it is worth.
 *
 * <p>A box ("Unidentified Chestplate") cannot be listed on the Trade Market as it is. There are
 * three ways out:
 * <ul>
 * <li>the Blacksmith buys it as it is, for a few emeralds;</li>
 * <li>the Trade Market reveals it for emeralds (the same amount the Item Identifier asks): you see
 * which item it is, its stats stay "?", and it can be listed straight away, unidentified;</li>
 * <li>the Item Identifier identifies it for emeralds: stats and roll percentages show. Worth it
 * when one of the outcomes is something you would wear.</li>
 * </ul>
 *
 * <p>Every outcome counts the same: Wynntils lists the possibilities without weights, and
 * nothing public says the game weighs them. An outcome without a market price is left out of
 * the expected value and named, never counted as 0. An untradable outcome counts as 0: it
 * cannot be sold on the Trade Market.
 */
final class BoxRoutes {

    private BoxRoutes() {}

    /**
     * One item the box can hold. {@code cost}: emeralds to reveal or identify it (Wynntils'
     * formula by tier and level, what the game charges). {@code price}: what it fetches listed
     * unidentified on the Trade Market, null when unknown. {@code wearable}: you would wear it.
     */
    record Outcome(String name, int cost, Double price, boolean tradable, boolean wearable) {}

    enum Route {
        /** Sell the box to the Blacksmith as it is. */
        BLACKSMITH,
        /** Reveal it at the Trade Market, then list it there. */
        REVEAL_MARKET,
        /** Identify it at the Item Identifier: an outcome is one you would wear. */
        IDENTIFIER,
        /** Nothing in it can be traded: only the Blacksmith takes it. */
        UNTRADABLE
    }

    /**
     * What the box is worth. {@code expected}: the mean price over the outcomes with a price
     * (untradable ones at 0), null when no tradable one has a price. {@code costLow}..{@code costHigh}: the
     * reveal or identify fee over the outcomes, {@code cost} its mean. {@code unpriced}: tradable
     * outcomes without a price. {@code net}: expected after the market fee, less the fee to
     * reveal; null without an expected value.
     */
    record Value(int outcomes, int priced, Double expected, int costLow, int costHigh, double cost,
            List<String> unpriced, Double net) {}

    record Choice(Route route, Value value, String wearable) {}

    static Value value(List<Outcome> outcomes, double taxPercent) {
        int priced = 0;
        int withPrice = 0;
        double sum = 0;
        int low = Integer.MAX_VALUE;
        int high = 0;
        double costSum = 0;
        List<String> unpriced = new ArrayList<>();
        for (Outcome o : outcomes) {
            low = Math.min(low, o.cost());
            high = Math.max(high, o.cost());
            costSum += o.cost();
            if (!o.tradable()) {
                priced++;                       // known: it fetches nothing on the market
            } else if (o.price() != null) {
                priced++;
                withPrice++;
                sum += o.price();
            } else {
                unpriced.add(o.name());
            }
        }
        if (outcomes.isEmpty()) {
            return new Value(0, 0, null, 0, 0, 0, unpriced, null);
        }
        double cost = costSum / outcomes.size();
        // no tradable outcome with a price: the box's worth is unknown, not 0
        Double expected = withPrice == 0 ? null : sum / priced;
        Double net = expected == null ? null : expected * (1 - taxPercent / 100.0) - cost;
        return new Value(outcomes.size(), priced, expected, low, high, cost, List.copyOf(unpriced), net);
    }

    /**
     * The way to go: the Item Identifier when you would wear an outcome; the Blacksmith when
     * nothing in it trades; else the Trade Market when revealing and listing leaves at least
     * {@code minProfit} emeralds over the fee, the Blacksmith otherwise. Without any price,
     * {@code valuable} (a tier that sells well) picks the Trade Market, else the Blacksmith.
     */
    static Choice choose(List<Outcome> outcomes, double taxPercent, double minProfit, boolean valuable) {
        Value v = value(outcomes, taxPercent);
        String wear = null;
        boolean anyTradable = outcomes.isEmpty();
        for (Outcome o : outcomes) {
            anyTradable |= o.tradable();
            if (o.wearable() && wear == null) {
                wear = o.name();
            }
        }
        if (wear != null) {
            return new Choice(Route.IDENTIFIER, v, wear);
        }
        if (!anyTradable) {
            return new Choice(Route.UNTRADABLE, v, null);
        }
        if (v.net() == null) {
            return new Choice(valuable ? Route.REVEAL_MARKET : Route.BLACKSMITH, v, null);
        }
        return new Choice(v.net() >= minProfit ? Route.REVEAL_MARKET : Route.BLACKSMITH, v, null);
    }

    /** "76 E", or "76-79 E" when the fee depends on which level the item inside is. */
    static String cost(Value v) {
        if (v.outcomes() == 0) {
            return "?";
        }
        return v.costLow() == v.costHigh() ? v.costLow() + " E" : v.costLow() + "-" + v.costHigh() + " E";
    }
}
