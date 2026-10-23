package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.game.WynntilsItems;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.search.BoxMarket;
import io.github.anilken.wynnlabs.search.Entry;
import io.github.anilken.wynnlabs.search.ItemIndex;
import io.github.anilken.wynnlabs.search.Kind;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The box window's left list: one row per item the box can hold, with its real icon, its name
 * in its rarity's colour, and under it the level, how many listings there are now, the lowest
 * price and the best roll; or why there is no number ("loading", "no listings", "price unknown",
 * "untradable"). A click picks the row whose listings the right side shows.
 */
final class BoxItemsPanel {

    static final int ROW = 22;

    private final BoxScreen s;
    private final ScrollBar bar = new ScrollBar();
    private final SearchIcons icons = new SearchIcons();
    private final Map<String, ItemStack> fallback = new HashMap<>();
    private int scroll;
    private int[] area = new int[4];

    BoxItemsPanel(BoxScreen s) {
        this.s = s;
    }

    void draw(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        area = new int[] {x, y, w, h};
        List<BoxMarket.Row> rows = s.rows;
        String head = L.n("wynnlabs.box.items_head", rows.size());
        s.ink(g, s.fit(head, w), x, y, Theme.INK_2);
        int top = y + 12;
        int listH = h - 12;
        int total = rows.size() * ROW;
        scroll = Math.max(0, Math.min(ScrollBar.max(listH, total), scroll));
        g.enableScissor(x, top, x + w, top + listH);
        int ry = top - scroll;
        for (int i = 0; i < rows.size(); i++) {
            if (ry + ROW > top && ry < top + listH) {
                row(g, rows.get(i), i, x, ry, w - ScrollBar.W - 2, mx, my, top, listH);
            }
            ry += ROW;
        }
        g.disableScissor();
        bar.place(x + w - ScrollBar.W, top, listH, listH, total, scroll);
        bar.draw(g, mx, my);
    }

    private void row(GuiGraphics g, BoxMarket.Row r, int i, int x, int y, int w, int mx, int my, int top, int listH) {
        boolean chosen = r.name().equals(s.selected);
        boolean hover = mx >= x && mx < x + w && my >= Math.max(y, top) && my < Math.min(y + ROW, top + listH);
        Kit.row(g, x, y, w, ROW, i, chosen || hover);
        ItemStack icon = icon(r.item());
        g.renderItem(icon, x + 4, y + 3);
        if (hover && mx < x + 22) {
            g.setTooltipForNextFrame(s.font(), icon, mx, my);
        }
        // the price on the right of the name line, the details under it
        String price = r.lowest() != null ? Prices.fmt(r.lowest())
                : r.pastPrice() != null ? Prices.past("~" + Prices.fmt(r.pastPrice())) : "";
        int pw = price.isEmpty() ? 0 : s.font().width(price);
        int nameX = x + 24;
        s.ink(g, s.fit(r.name(), w - 28 - pw - 4), nameX, y + 2, HomePart.tierColour(r.item().tier()));
        if (pw > 0) {
            s.ink(g, price, x + w - 4 - pw, y + 2, r.lowest() != null ? Theme.PRICE : Theme.INK_3);
        }
        s.small(g, s.fit(detail(r), (int) ((w - 28) / MenuPaint.DESC_SCALE)), nameX, y + 12,
                r.state() == BoxMarket.State.READY ? Theme.INK_2 : Theme.INK_3);
        int ty = Math.max(y, top);
        s.hits.add(new BoxScreen.Hit(x, ty, w, Math.min(y + ROW, top + listH) - ty, () -> {
            s.selected = r.name();
            s.right.reset();
        }));
    }

    /** "Lv 72 · 7 ilan · en iyi %86", or the state in words. */
    private static String detail(BoxMarket.Row r) {
        String lv = "Lv " + r.item().level();
        return switch (r.state()) {
            case UNTRADABLE -> lv + " · " + L.t("wynnlabs.box.untradable");
            case LOADING -> lv + " · " + L.t("wynnlabs.box.loading");
            case UNKNOWN -> lv + " · " + L.t("wynnlabs.box.price_unknown");
            case NONE -> lv + " · " + L.t("wynnlabs.box.no_listings");
            case READY -> r.count() == 0 ? lv + " · " + L.t("wynnlabs.box.no_listings")
                    : lv + " · " + L.n("wynnlabs.box.listings", r.count())
                            + (r.bestRoll() == null ? "" : " · " + L.t("wynnlabs.box.best_roll", BoxScreen.pct(r.bestRoll())));
        };
    }

    /** The item's real icon: the search index's (Wynntils' guide stack or its model), else its type's base item. */
    private ItemStack icon(BoxMarket.Item it) {
        Entry e = ItemIndex.get().byName(it.name());
        if (e != null) {
            return icons.of(e);
        }
        return fallback.computeIfAbsent(it.name(), n -> {
            if (GameState.current().running()) {
                try {
                    ItemStack guide = WynntilsItems.guideStack(Kind.GEAR, n);
                    if (guide != null && !guide.isEmpty()) {
                        return guide;
                    }
                } catch (LinkageError ignored) {
                    // Wynntils changed: the base item below
                }
            }
            return new ItemStack(switch (it.slot()) {
                case HELMET -> Items.LEATHER_HELMET;
                case CHESTPLATE -> Items.LEATHER_CHESTPLATE;
                case LEGGINGS -> Items.LEATHER_LEGGINGS;
                case BOOTS -> Items.LEATHER_BOOTS;
                case RING, BRACELET, NECKLACE, ACCESSORY -> Items.GOLD_NUGGET;
                case BOW -> Items.BOW;
                default -> Items.STICK;
            });
        });
    }

    boolean mouseClicked(double mx, double my) {
        return bar.mouseClicked(mx, my) && keep();
    }

    boolean mouseDragged(double my) {
        return bar.mouseDragged(my) && keep();
    }

    private boolean keep() {
        scroll = bar.offset();
        return true;
    }

    void mouseReleased() {
        bar.mouseReleased();
    }

    boolean mouseScrolled(double mx, double my, double v) {
        if (mx < area[0] || mx >= area[0] + area[2] || my < area[1] || my >= area[1] + area[3]) {
            return false;
        }
        scroll -= (int) Math.signum(v) * ROW;
        return true;
    }
}
