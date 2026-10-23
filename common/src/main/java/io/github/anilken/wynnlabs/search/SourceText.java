package io.github.anilken.wynnlabs.search;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.market.Prices;

import java.util.List;
import java.util.Locale;

/**
 * Words for a {@link Source} row, in the player's language: the card's line ("Fallen Factory
 * merchant · 4 chips"), the results table's short hint ("~2.1 LE", "Lv 73–81") and the tooltip's
 * "From: …" line. Names stay in English as in the data. A merchant's cost comes from the row or,
 * when the row has none, from the item's wiki page once it is loaded.
 */
public final class SourceText {

    private SourceText() {}

    /** The type in a word or two ("Merchant", "Dungeon"). */
    public static String type(Source.Type t) {
        return L.t("wynnlabs.search.type." + key(t));
    }

    /** The card's line for a row: what and where, with a merchant's cost or the market price. */
    public static String row(Source s, ItemWiki.Info wiki) {
        String text = switch (s.type()) {
            case BAG -> L.t("wynnlabs.search.src.bag", s.count());
            case BANK -> L.t("wynnlabs.search.src.bank", s.name() == null ? "" : s.name(), s.count());
            case MARKET -> s.price() != null && s.price().typical() != null
                    ? L.t("wynnlabs.search.src.market", Prices.fmt(s.price().typical()))
                    : L.t("wynnlabs.search.src.market_listed");
            case MOB_BAND -> L.t("wynnlabs.search.src.mob_band", s.minLevel(), s.maxLevel());
            case LOOT_CHEST -> L.t("wynnlabs.search.src.loot_chest", s.minLevel(), s.maxLevel());
            case UNKNOWN, UNAVAILABLE -> type(s.type());
            default -> s.name() == null || s.name().isBlank() ? type(s.type())
                    : L.t("wynnlabs.search.src." + key(s.type()), s.name());
        };
        String cost = cost(s, wiki);
        return cost == null ? text : text + " · " + cost;
    }

    /** The results table's hint beside the badge: the price, the level band, else the type. */
    public static String brief(Source.Type t, Prices.Price price, int level) {
        if (t == Source.Type.MARKET && price != null && price.typical() != null) {
            return "~" + Prices.fmt(price.typical());
        }
        if ((t == Source.Type.MOB_BAND || t == Source.Type.LOOT_CHEST) && level > 0) {
            return "Lv " + Math.max(1, level - Sources.BAND) + "–" + (level + Sources.BAND);
        }
        return type(t);
    }

    /**
     * The tooltip's line for an item: "From: " and the easiest way that is not the player's own
     * bag or bank; null when nothing is known (the item gets no line).
     */
    public static String tooltip(List<Source> ranked, ItemWiki.Info wiki) {
        for (Source s : ranked) {
            if (s.type() == Source.Type.BAG || s.type() == Source.Type.BANK) {
                continue;
            }
            if (s.type() == Source.Type.UNKNOWN) {
                return null;
            }
            return L.t("wynnlabs.search.tooltip_from", row(s, wiki));
        }
        return null;
    }

    /**
     * What a merchant asks: the row's own cost, else the wiki's cost for a merchant whose name
     * holds the row's name (or its only cost); null for other rows or when unknown.
     */
    public static String cost(Source s, ItemWiki.Info wiki) {
        if (s.cost() != null && !s.cost().isBlank()) {
            return s.cost();
        }
        if (s.type() != Source.Type.MERCHANT && s.type() != Source.Type.DUNGEON_MERCHANT || wiki == null || wiki.costs().isEmpty()) {
            return null;
        }
        String want = s.name() == null ? "" : s.name().toLowerCase(Locale.ROOT);
        for (ItemWiki.Cost c : wiki.costs()) {
            if (!want.isEmpty() && c.merchant().toLowerCase(Locale.ROOT).contains(want)) {
                return c.price();
            }
        }
        return wiki.costs().size() == 1 ? wiki.costs().get(0).price() : null;
    }

    /** "dungeon_merchant" for DUNGEON_MERCHANT: the language keys' spelling of a type. */
    static String key(Source.Type t) {
        return t.name().toLowerCase(Locale.ROOT);
    }
}
