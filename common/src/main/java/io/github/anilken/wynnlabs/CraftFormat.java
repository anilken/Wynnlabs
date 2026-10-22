package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.crafting.Craft;
import io.github.anilken.wynnlabs.crafting.CraftChecks;

import java.util.Locale;

/** The crafting tab's words and numbers: ranges, signs, percentages, costs, types and skills. */
final class CraftFormat {

    private CraftFormat() {}

    /** Each item type's icon in the type list and field. */
    static String typeIcon(String t) {
        return switch (t) {
            case "helmet" -> "helmet";
            case "chestplate" -> "shirt";
            case "leggings" -> "hanger";
            case "boots" -> "shoe";
            case "ring" -> "circle-dot";
            case "bracelet" -> "circles";
            case "necklace" -> "diamond";
            case "spear" -> "trident";
            case "dagger" -> "sword";
            case "bow" -> "bow";
            case "wand" -> "wand";
            case "relik" -> "crystal-ball";
            case "potion" -> "flask-2";
            case "scroll" -> "script";
            default -> "meat";
        };
    }

    static String typeName(String t) {
        return L.t("wynnlabs.craft.type." + t);
    }

    static String skill(int s) {
        return L.t("wynnlabs.craft.skill." + CraftChecks.SKILLS.get(s));
    }

    static String weightText(double w) {
        return w == 0.5 ? "½" : w == Math.rint(w) ? String.valueOf((int) w) : String.valueOf(w);
    }

    /** A range for reading: "47–51%", "−20 … −16%", or one value when both ends agree. */
    static String range(int a, int b, String unit) {
        if (a == b) {
            return signed(a) + unit;
        }
        if (a >= 0) {
            return a + "–" + b + unit;
        }
        return signed(a) + " … " + signed(b) + unit;
    }

    static String range(Craft.Range r, String unit) {
        return range(r.min(), r.max(), unit);
    }

    /** "Maliyet 1.2k", or "Maliyet: bilinmiyor" when nothing in it has a price: a bare "?" read as a placeholder. */
    static String costLine(String costText) {
        return costText.equals("?") ? L.t("wynnlabs.craft.cost_unknown") : L.t("wynnlabs.craft.cost", costText);
    }

    /** "1 EB", "1 EB +?" (some prices unknown) or "?" (none known). */
    static String costText(long cost, boolean anyPriced, boolean anyUnpriced) {
        if (!anyPriced) {
            return "?";
        }
        return Prices.fmt(cost) + (anyUnpriced ? " +?" : "");
    }

    static String signed(int v) {
        return v > 0 ? "+" + v : String.valueOf(v).replace('-', '−');
    }

    static String stars(int tier) {
        return "★".repeat(Math.max(1, tier));
    }

    private static boolean tr() {
        return L.code().startsWith("tr");
    }

    static String dec(double v, int places) {
        String s = String.format(Locale.ROOT, "%." + places + "f", v);
        return tr() ? s.replace('.', ',') : s;
    }

    static String thousands(int v) {
        return String.format(tr() ? Locale.forLanguageTag("tr") : Locale.ENGLISH, "%,d", v);
    }

    static String pct(int v) {
        return L.t("wynnlabs.screen.pct", v);
    }

    static String pctText(double p) {
        return L.t("wynnlabs.screen.pct", dec(p * 100, p < 0.1 ? 1 : 0));
    }
}
