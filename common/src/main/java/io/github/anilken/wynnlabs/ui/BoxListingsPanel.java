package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.market.Listings;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.search.BoxMarket;

import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * The box window's listings of the chosen item: per listing its overall roll as a bar coloured
 * by the roll, the roll % of its key stats, rerolls, price and how long ago it was seen. High
 * rolls (rules boxHighRoll, 80 %) are lit; listings seen 3+ hours before the newest are faded and
 * marked "may be sold". Under the list, "x / total" and a "more" row while the market has more
 * pages; that row, or scrolling to the end, asks for the next page. No seller is ever shown.
 */
final class BoxListingsPanel {

    static final int ROW = 22;
    private static final int MORE = 14;

    private final BoxScreen s;
    private final ScrollBar bar = new ScrollBar();
    private int scroll;
    private int max;
    private int[] area = new int[4];

    BoxListingsPanel(BoxScreen s) {
        this.s = s;
    }

    /** Back to the top (another item was chosen). */
    void reset() {
        scroll = 0;
    }

    void draw(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        area = new int[] {x, y, w, h};
        BoxMarket.Row r = s.selectedRow();
        if (r == null) {
            return;
        }
        // the head: the item and how many of its listings are held of the market's total
        String count = L.t("wynnlabs.box.held_of", r.held(), Math.max(r.held(), r.count()));
        int cw = s.font().width(count);
        s.ink(g, count, x + w - cw, y, Theme.INK_3);
        s.ink(g, s.fit(r.name(), w - cw - 6), x, y, HomePart.tierColour(r.item().tier()));
        int top = y + 12;
        int listH = h - 12;
        if (r.listings().isEmpty()) {
            String why = switch (r.state()) {
                case UNTRADABLE -> L.t("wynnlabs.box.untradable");
                case LOADING -> L.t("wynnlabs.box.loading");
                case UNKNOWN -> Listings.live() ? L.t("wynnlabs.box.price_unknown") : L.t("wynnlabs.box.live_off");
                default -> L.t("wynnlabs.box.no_listings");
            };
            s.ink(g, s.fit(why, w), x, top + 4, Theme.INK_3);
            if (r.pastPrice() != null) {
                s.ink(g, s.fit(Prices.past("~" + Prices.fmt(r.pastPrice())), w), x, top + 16, Theme.PRICE);
            }
            return;
        }
        boolean more = r.hasMore();
        int total = r.listings().size() * ROW + (more ? MORE : 0);
        max = ScrollBar.max(listH, total);
        scroll = Math.max(0, Math.min(max, scroll));
        int rw = w - ScrollBar.W - 2;
        g.enableScissor(x, top, x + w, top + listH);
        int ry = top - scroll;
        double high = BoxScreen.highRoll();
        for (int i = 0; i < r.listings().size(); i++) {
            if (ry + ROW > top && ry < top + listH) {
                listing(g, r.listings().get(i), i, x, ry, rw, high);
            }
            ry += ROW;
        }
        if (more) {
            boolean waiting = Listings.loadingMore(r.name());
            String t = waiting ? L.t("wynnlabs.box.loading") : L.t("wynnlabs.box.more");
            boolean hover = !waiting && mx >= x && mx < x + rw && my >= Math.max(ry, top) && my < Math.min(ry + MORE, top + listH);
            s.ink(g, t, x + (rw - s.font().width(t)) / 2, ry + 3, hover ? Theme.GOLD_EDGE : Theme.LINK);
            if (!waiting && ry < top + listH) {
                String name = r.name();
                int ty = Math.max(ry, top);
                s.hits.add(new BoxScreen.Hit(x, ty, rw, Math.min(ry + MORE, top + listH) - ty, () -> Listings.requestMore(name)));
            }
        }
        g.disableScissor();
        bar.place(x + w - ScrollBar.W, top, listH, listH, total, scroll);
        bar.draw(g, mx, my);
    }

    private void listing(GuiGraphics g, BoxMarket.Listing l, int i, int x, int y, int w, double high) {
        boolean lit = BoxMarket.highRoll(l, high);
        if (lit) {
            g.fill(x, y, x + w, y + ROW, Theme.SELECTED);
            g.fill(x, y, x + 2, y + ROW, Theme.GOLD_DEEP);
        } else {
            Kit.row(g, x, y, w, ROW, i, false);
        }
        // line 1: the roll (or "unidentified"), then amount and price on the right
        if (!s.rolled()) {
            // no rolls on this kind of item: the line starts with nothing, the price says it all
        } else if (l.unidentified() || l.overallRoll() == null) {
            s.ink(g, l.unidentified() ? L.t("wynnlabs.box.unidentified") : L.t("wynnlabs.box.no_roll"), x + 4, y + 2, Theme.INK_3);
        } else {
            double roll = l.overallRoll();
            int[] c = rollColours(roll);
            Kit.bar(g, x + 4, y + 3, 40, 7, roll / 100.0, c[0], c[1], c[2], false);
            s.ink(g, BoxScreen.pct(Math.round(roll)), x + 48, y + 2, lit ? Theme.GOOD : Theme.INK);
        }
        String price = Prices.fmt(l.price()) + (l.amount() > 1 ? " ×" + l.amount() : "");
        int pw = s.font().width(price);
        s.ink(g, price, x + w - 4 - pw, y + 2, Theme.PRICE);
        // line 2: the key stats, then rerolls and age (or "may be sold")
        List<String> tail = new ArrayList<>();
        if (l.rerolls() > 0) {
            tail.add(L.n("wynnlabs.box.rerolls", l.rerolls()));
        }
        if (l.possiblySold()) {
            tail.add(L.t("wynnlabs.box.possibly_sold"));
        }
        if (l.seenAt() > 0) {
            tail.add(MenuPaint.ago(Math.min(l.seenAt(), System.currentTimeMillis())));
        }
        String right = String.join(" · ", tail);
        int tw = s.smallWidth(right);
        if (!right.isEmpty()) {
            s.small(g, right, x + w - 4 - tw, y + 12, l.possiblySold() ? Theme.WARN : Theme.INK_3);
        }
        List<String> stats = new ArrayList<>();
        for (BoxMarket.StatRoll st : BoxMarket.keyStats(l, Rules::statWeight, 3)) {
            stats.add(SearchResultsPanel.statName(st.key()) + " " + BoxScreen.pct(Math.round(st.percent())));
        }
        if (!stats.isEmpty()) {
            s.small(g, s.fit(String.join(" · ", stats), (int) ((w - 12 - tw) / MenuPaint.DESC_SCALE)), x + 4, y + 12, Theme.INK_2);
        }
        if (l.possiblySold()) {
            g.fill(x, y, x + w, y + ROW, 0x99D8C595);          // faded: paper over it
        }
    }

    /** One item: its icon, its name in its rarity's colour, its tier and level. */
    void singleHeader(GuiGraphics g, int x, int y, int room, BoxScreen.Single single, net.minecraft.world.item.ItemStack icon) {
        BoxMarket.Item it = single.item();
        g.renderItem(icon, x, y - 5);
        String name = s.fit(it.name(), room - 20);
        s.ink(g, name, x + 20, y, HomePart.tierColour(it.tier()));
        String rest = (it.tier() == io.github.anilken.wynnlabs.model.Tier.NORMAL ? "" : " · " + MenuPaint.nice(it.tier().name()))
                + (it.level() > 0 ? " · Lv " + it.level() : "");
        s.ink(g, s.fit(rest, room - 20 - s.font().width(name)), x + 20 + s.font().width(name), y, Theme.INK_2);
    }

    /**
     * One item's chips: its listing count, lowest and middle price, best roll, and for a rolled
     * piece its own roll against the market ("Seninki %72 · piyasada 3 daha iyi").
     */
    void singleChips(GuiGraphics g, int x, int y, BoxScreen.Single single) {
        BoxMarket.Row r = s.rows.isEmpty() ? null : s.rows.get(0);
        int cx = x;
        int count = r == null ? 0 : r.count();
        cx += s.chip(g, cx, y, L.n("wynnlabs.box.listings", count), Theme.PLAQUE, Theme.INK) + 4;
        cx += s.chip(g, cx, y, L.t("wynnlabs.box.lowest", BoxScreen.money(r == null || r.lowest() == null ? null : (double) r.lowest())),
                Theme.PLAQUE, Theme.INK) + 4;
        cx += s.chip(g, cx, y, L.t("wynnlabs.box.median", BoxScreen.money(r == null || r.median() == null ? null : (double) r.median())),
                Theme.PLAQUE, Theme.INK) + 4;
        if (r != null && r.bestRoll() != null) {
            cx += s.chip(g, cx, y, L.t("wynnlabs.box.best_roll", BoxScreen.pct(r.bestRoll())), Theme.PLAQUE, Theme.INK) + 4;
        }
        if (single.ownRoll() != null && r != null) {
            int better = BoxMarket.better(r.listings(), single.ownRoll());
            s.chip(g, cx, y, L.t("wynnlabs.box.own_roll", BoxScreen.pct(Math.round(single.ownRoll())), better), Theme.SEAL_2, Theme.SEAL_TEXT);
        }
    }

    /** The bar's three tones for a roll: red under 50 %, amber under the high-roll mark, green above. */
    private static int[] rollColours(double roll) {
        if (roll >= BoxScreen.highRoll()) {
            return new int[] {Theme.GREEN_HI, Theme.GREEN, Theme.GREEN_LO};
        }
        if (roll >= 50) {
            return new int[] {Theme.AMBER_HI, Theme.AMBER, Theme.AMBER_LO};
        }
        return new int[] {0xFFE89080, 0xFFC85040, 0xFF983020};
    }

    boolean mouseClicked(double mx, double my) {
        return bar.mouseClicked(mx, my) && moved();
    }

    boolean mouseDragged(double my) {
        return bar.mouseDragged(my) && moved();
    }

    void mouseReleased() {
        bar.mouseReleased();
    }

    boolean mouseScrolled(double mx, double my, double v) {
        if (mx < area[0] || mx >= area[0] + area[2] || my < area[1] || my >= area[1] + area[3]) {
            return false;
        }
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(v) * ROW));
        atEnd();
        return true;
    }

    private boolean moved() {
        scroll = bar.offset();
        atEnd();
        return true;
    }

    /** Scrolled to the end while the market has more: the next page (asked once; Listings keeps the rest out). */
    private void atEnd() {
        BoxMarket.Row r = s.selectedRow();
        if (r != null && r.hasMore() && max > 0 && scroll >= max) {
            Listings.requestMore(r.name());
        }
    }
}
