package io.github.anilken.wynnlabs.search;

import io.github.anilken.wynnlabs.market.Prices;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The tooltip's "From: …" line and the card's words for a source row (English, as tests read the language files). */
class SourceTextTest {

    private static final Prices.Price PRICE = new Prices.Price(null, 8601.6, 8601.6, null, null, 0L, null, 12);

    private static Source row(Source.Type t, String name, Prices.Price price, int lo, int hi) {
        return new Source(t, name, List.of(), SourceRank.effort(t), SourceRank.badge(t), 3, price, lo, hi, null);
    }

    @Test
    void normalDropSaysTheMobLevels() {
        ItemIndex idx = SearchFixtures.index();
        List<Source> rows = Sources.of(idx.byName("Papyrus"), idx.places(), Sources.Owned.NONE, null, null);
        assertEquals("From: Lv 73–81 mobs", SourceText.tooltip(rows, null));
    }

    @Test
    void marketPriceWhenItIsTheEasiest() {
        assertEquals("From: Trade Market ~2.1 LE", SourceText.tooltip(List.of(row(Source.Type.MARKET, null, PRICE, 0, 0)), null));
    }

    @Test
    void merchantCostComesFromTheWikiOnceLoaded() {
        Source merchant = row(Source.Type.DUNGEON_MERCHANT, "Fallen Factory", null, 0, 0);
        assertEquals("From: Fallen Factory merchant", SourceText.tooltip(List.of(merchant), null));
        ItemWiki.Info wiki = new ItemWiki.Info("", List.of(new ItemWiki.Cost("Fallen Factory Dungeon Merchant", "4 Chips")), 0, true,
                0, 0, "", List.of());
        assertEquals("From: Fallen Factory merchant · 4 Chips", SourceText.tooltip(List.of(merchant), wiki));
    }

    @Test
    void ownRowsAreSkippedAndUnknownGivesNoLine() {
        Source bag = row(Source.Type.BAG, null, null, 0, 0);
        Source bank = new Source(Source.Type.BANK, "Account p. 2", List.of(), 0, "building-bank", 5, null, 0, 0, null);
        Source mob = row(Source.Type.MOB, "Obelisk Of Inversion", null, 0, 0);
        assertEquals("From: Dropped by Obelisk Of Inversion", SourceText.tooltip(List.of(bag, bank, mob), null));
        assertNull(SourceText.tooltip(List.of(bag, row(Source.Type.UNKNOWN, null, null, 0, 0)), null));
        assertNull(SourceText.tooltip(List.of(), null));
    }

    @Test
    void cardRowsAndBriefHints() {
        assertEquals("In your bag ×3", SourceText.row(row(Source.Type.BAG, null, null, 0, 0), null));
        assertEquals("Lv 73–81 loot chests", SourceText.row(row(Source.Type.LOOT_CHEST, null, null, 73, 81), null));
        assertEquals("~2.1 LE", SourceText.brief(Source.Type.MARKET, PRICE, 77));
        assertEquals("Lv 73–81", SourceText.brief(Source.Type.MOB_BAND, null, 77));
        assertEquals("Merchant", SourceText.brief(Source.Type.MERCHANT, null, 77));
    }

    @Test
    void costPicksTheMerchantByName() {
        ItemWiki.Info wiki = new ItemWiki.Info("", List.of(new ItemWiki.Cost("Ragni Weapon Merchant", "40 Emeralds"),
                new ItemWiki.Cost("Detlas Weapon Merchant", "50 Emeralds")), 0, false, 0, 0, "", List.of());
        assertEquals("50 Emeralds", SourceText.cost(row(Source.Type.MERCHANT, "Detlas", null, 0, 0), wiki));
        assertNull(SourceText.cost(row(Source.Type.MERCHANT, "Almuj", null, 0, 0), wiki), "two merchants and neither named");
        assertNull(SourceText.cost(row(Source.Type.MOB, "Detlas", null, 0, 0), wiki));
    }
}
