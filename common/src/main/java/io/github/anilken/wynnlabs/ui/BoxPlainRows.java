package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.search.BoxMarket;
import io.github.anilken.wynnlabs.search.Entry;
import io.github.anilken.wynnlabs.search.ItemIndex;

import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * The listings of an item without rolls (an ingredient, a material) as a one-line table: unit
 * price, amount, total (unit × amount) and how long ago the listing was seen, under a small
 * header with the column names. Listings seen 3+ hours before the newest are faded, as in the
 * rolled rows.
 */
final class BoxPlainRows {

    /** One listing's line. */
    static final int ROW = 12;
    /** The column names' line above the list. */
    static final int HEAD = 12;

    private BoxPlainRows() {}

    /** The right edges of the amount and the total column; the unit price starts left, the age ends right. */
    private static int[] cols(int x, int w) {
        return new int[] {x + w * 42 / 100, x + w * 80 / 100};
    }

    static void header(BoxScreen s, GuiGraphics g, int x, int y, int w) {
        int[] c = cols(x, w);
        s.small(g, L.t("wynnlabs.box.col_unit"), x + 4, y + 1, Theme.INK_3);
        right(s, g, L.t("wynnlabs.box.col_amount"), c[0], y + 1);
        right(s, g, L.t("wynnlabs.box.col_total"), c[1], y + 1);
        right(s, g, L.t("wynnlabs.box.col_seen"), x + w - 4, y + 1);
        g.fill(x, y + HEAD - 2, x + w, y + HEAD - 1, Theme.LINE);
    }

    private static void right(BoxScreen s, GuiGraphics g, String t, int rx, int y) {
        s.small(g, t, rx - s.smallWidth(t), y, Theme.INK_3);
    }

    static void row(BoxScreen s, GuiGraphics g, BoxMarket.Listing l, int i, int x, int y, int w) {
        Kit.row(g, x, y, w, ROW, i, false);
        int[] c = cols(x, w);
        String n = "×" + l.amount();
        int nw = s.font().width(n);
        s.ink(g, n, c[0] - nw, y + 2, Theme.INK_2);
        cell(s, g, BoxListingsPanel.exact(l.price()), x + 4, c[0] - nw - x - 10, y, false);
        cell(s, g, BoxListingsPanel.exact((long) l.price() * l.amount()), c[1], c[1] - c[0] - 6, y, true);
        if (l.seenAt() > 0) {
            String a = age(Math.min(l.seenAt(), System.currentTimeMillis()));
            s.small(g, a, x + w - 4 - s.smallWidth(a), y + 3, l.possiblySold() ? Theme.WARN : Theme.INK_3);
        }
        if (l.possiblySold()) {
            g.fill(x, y, x + w, y + ROW, 0x99D8C595);              // faded: paper over it
        }
    }

    /**
     * A price in its column ({@code at} is the left edge, or the right edge when {@code right}):
     * at the normal size when it fits, else at the description size, else cut with "…".
     */
    private static void cell(BoxScreen s, GuiGraphics g, String t, int at, int room, int y, boolean right) {
        if (s.font().width(t) <= room) {
            s.ink(g, t, right ? at - s.font().width(t) : at, y + 2, Theme.PRICE);
            return;
        }
        String f = s.smallWidth(t) <= room ? t : s.fit(t, (int) (room / MenuPaint.DESC_SCALE));
        s.small(g, f, right ? at - s.smallWidth(f) : at, y + 3, Theme.PRICE);
    }

    /** "8 dk", "2 sa", "3 g": the short age of a listing. */
    static String age(long t) {
        long min = Math.max(0, (System.currentTimeMillis() - t) / 60000);
        if (min < 60) {
            return L.t("wynnlabs.box.age_min", min);
        }
        if (min < 24 * 60) {
            return L.t("wynnlabs.box.age_hours", min / 60);
        }
        return L.t("wynnlabs.box.age_days", min / 1440);
    }

    /** How many items the listings hold together. */
    static int stock(List<BoxMarket.Listing> listings) {
        int n = 0;
        for (BoxMarket.Listing l : listings) {
            n += l.amount();
        }
        return n;
    }

    /** " ✫✫" for an ingredient's tier, else "". */
    static String stars(String name) {
        Entry e = ItemIndex.get().byName(name);
        return e == null || e.stars() <= 0 ? "" : " " + "✫".repeat(e.stars());
    }
}
