package io.github.anilken.wynnlabs.advice;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Klass;

import java.util.Locale;

/**
 * The box verdict for the box contents window, from the very call the tooltip makes
 * ({@link BoxAdvice#decide}), so the two never disagree: identify (the Item Identifier, or the
 * Trade Market's reveal, which costs the same) or sell (the Blacksmith), with the route, the
 * box's worth and its fee range.
 */
public final class BoxVerdict {

    private BoxVerdict() {}

    /** The chip: "Tanımla", "Sat", or "Sakla" for a box the tooltip says to keep. */
    public enum Label { IDENTIFY, SELL, KEEP }

    /**
     * {@code route}: the route's name in lower case ("identifier", "reveal_market", "blacksmith",
     * "untradable"), null when no route was weighed. {@code value}: what it fetches revealed and
     * listed unidentified (the mean over its priced outcomes), null when unknown. {@code costLow}
     * .. {@code costHigh}: the fee to reveal or identify. {@code note}: the tooltip's line.
     */
    public record Result(Label label, String route, Double value, int costLow, int costHigh, String note) {

        /** The chip's word in the player's language. */
        public String text() {
            return L.t("wynnlabs.box.verdict." + label.name().toLowerCase(Locale.ROOT));
        }

        /** The route in words for the chip's hint; null without one. */
        public String routeText() {
            return route == null ? null : L.t("wynnlabs.box.route." + route);
        }

        /** The chip's tooltip: the route when there is one, what the word means, and the note (why). */
        public java.util.List<String> tipLines() {
            java.util.List<String> out = new java.util.ArrayList<>();
            if (route != null) {
                out.add(routeText());
            }
            out.add(Verdict.valueOf(label.name()).meaning());
            if (note != null && !note.isBlank()) {
                out.add(note);
            }
            return out;
        }
    }

    /** The verdict for a box, as the tooltip has it, for the player's class and level. */
    public static Result of(ItemView.Box box, Klass cls, int level) {
        return from(BoxAdvice.decide(box, cls, level), box);
    }

    static Result from(BoxAdvice.Decision d, ItemView.Box box) {
        BoxRoutes.Choice c = d.choice();
        BoxRoutes.Value v = c != null ? c.value() : BoxRoutes.value(BoxAdvice.outcomes(box, null), SellAdvice.marketFee());
        Label label = c != null ? label(c.route()) : switch (d.entry().verdict()) {
            case IDENTIFY -> Label.IDENTIFY;
            case SELL -> Label.SELL;
            default -> Label.KEEP;
        };
        int top = box.tier().identificationCost(box.high());
        int low = v.outcomes() == 0 ? top : v.costLow();
        int high = v.outcomes() == 0 ? top : v.costHigh();
        return new Result(label, c == null ? null : c.route().name().toLowerCase(Locale.ROOT), v.expected(), low, high,
                d.entry().note());
    }

    /** The Item Identifier and the Trade Market's reveal open the box: "Tanımla"; the Blacksmith: "Sat". */
    static Label label(BoxRoutes.Route r) {
        return switch (r) {
            case IDENTIFIER, REVEAL_MARKET -> Label.IDENTIFY;
            case BLACKSMITH, UNTRADABLE -> Label.SELL;
        };
    }
}
