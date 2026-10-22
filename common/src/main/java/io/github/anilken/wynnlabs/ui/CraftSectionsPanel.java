package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.crafting.Craft;
import io.github.anilken.wynnlabs.crafting.CraftChecks;
import io.github.anilken.wynnlabs.crafting.CraftData;
import io.github.anilken.wynnlabs.crafting.CraftSearch;
import io.github.anilken.wynnlabs.crafting.Crafting;
import io.github.anilken.wynnlabs.crafting.Ingredient;
import io.github.anilken.wynnlabs.crafting.Rolls;
import io.github.anilken.wynnlabs.crafting.SearchRunner;
import io.github.anilken.wynnlabs.crafting.Shopping;
import io.github.anilken.wynnlabs.crafting.WhatCanICraft;
import io.github.anilken.wynnlabs.game.Professions;
import io.github.anilken.wynnlabs.market.Prices;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.anilken.wynnlabs.ui.CraftFormat.costText;
import static io.github.anilken.wynnlabs.ui.CraftFormat.dec;
import static io.github.anilken.wynnlabs.ui.CraftFormat.pctText;
import static io.github.anilken.wynnlabs.ui.CraftFormat.range;
import static io.github.anilken.wynnlabs.ui.CraftFormat.signed;
import static io.github.anilken.wynnlabs.ui.CraftFormat.thousands;
import static io.github.anilken.wynnlabs.ui.CraftFormat.typeIcon;
import static io.github.anilken.wynnlabs.ui.CraftFormat.typeName;

/**
 * The crafting tab's folding sections under the calculator: roll chances with their slider, the
 * shopping list with the station button, and "what can I craft now".
 */
final class CraftSectionsPanel extends CraftPanel {

    /** A section this wide is on the large layout: its bars and lines stop short of the far edge. */
    private static final int WIDE = 600;

    private final SearchRunner whatRunner = new SearchRunner();
    final List<WhatCanICraft.Entry> what = new CopyOnWriteArrayList<>();
    private CraftSearch.Budget whatSearching;
    boolean foldDist = true;
    private boolean foldShop = true;
    String distId;
    Integer distTarget;
    private String stationText;

    CraftSectionsPanel(CraftingTab tab) {
        super(tab);
    }

    /** The menu was closed: a running search stops. */
    void stop() {
        whatRunner.cancel();
    }

    int drawSections(GuiGraphics g, int x, int y, int w, int mx, int my, float pt) {
        int yy = y;
        yy += boxed(g, 0, x, yy, w, (bx, by, bw) -> drawDistribution(g, bx, by, bw, mx, my, pt)) + 5;
        yy += boxed(g, 1, x, yy, w, (bx, by, bw) -> drawShopping(g, bx, by, bw, mx, my)) + 5;
        yy += boxed(g, 2, x, yy, w, (bx, by, bw) -> drawWhat(g, bx, by, bw, mx, my));
        return yy - y;
    }

    interface Section {
        int draw(int x, int y, int w);
    }

    /** Each folding section's height last frame: its card is drawn first, at that height. */
    private final int[] sectionH = new int[3];

    /** A section on its own light card, as in the mock-up. */
    int boxed(GuiGraphics g, int i, int x, int y, int w, Section s) {
        if (sectionH[i] > 0) {
            Kit.card(g, x, y, w, sectionH[i], 0);
        }
        int h = s.draw(x + 6, y + 4, w - 12) + 6;
        sectionH[i] = h;
        return h;
    }

    private int fold(GuiGraphics g, int x, int y, int w, boolean opened, String title, String right, int mx, int my,
                     Runnable toggle) {
        boolean hover = in(mx, my, x, y, w, 12);
        Art.icon(g, opened ? "chevron-down" : "chevron-right", x, y + 1, 8, Theme.paperRgb(hover ? Theme.INK : Theme.INK_2));
        section(g, title, x + 10, y + 1, w - 10 - (right == null ? 0 : width(right) + 6));
        if (right != null) {
            text(g, right, x + w - width(right), y + 1, Theme.INK_3);
        }
        clipped(x, y, w, 12, toggle, null, null);
        return 14;
    }

    int drawDistribution(GuiGraphics g, int x, int y, int w, int mx, int my, float pt) {
        Craft.Result c = tab.result();
        if (distId == null || !c.ids().containsKey(distId)) {
            distId = c.ids().isEmpty() ? null : c.ids().keySet().iterator().next();
        }
        String title = L.t("wynnlabs.craft.dist") + (distId == null ? "" : " · " + tab.statName(distId));
        int yy = y + fold(g, x, y, w, foldDist, title, null, mx, my, () -> foldDist = !foldDist);
        if (!foldDist) {
            return yy - y;
        }
        if (distId == null) {
            text(g, L.t("wynnlabs.craft.dist_none"), x + 10, yy, Theme.INK_3);
            return yy + 12 - y;
        }
        Rolls.Dist d = Rolls.of(distId, tab.layout());
        // a typed value outside the range is asked as typed (above the top: 0%), the knob stays on the range
        int target = distTarget == null ? d.high() : distTarget;
        String u = tab.unit(distId);
        String line = L.t("wynnlabs.craft.dist_line", dec(d.mean(), 2) + u, signed(target) + u, pctText(d.exactly(target)),
                pctText(d.atLeast(target)));
        text(g, fit(line, w - 10), x + 10, yy, Theme.INK);
        yy += 12;
        if (d.high() == d.low()) {
            // every roll gives the same value: nothing to slide
            text(g, fit(L.t("wynnlabs.craft.dist_fixed", signed(d.low()) + u), w - 10), x + 10, yy, Theme.INK_3);
            sliderBox = null;
            return yy + 14 - y;
        }
        // the slider over the range (click or drag), and the typed value
        int bx = x + 10;
        // on a large page no longer than 300px: the range reads no better, and the box stays near it
        int bw = w >= WIDE ? Math.min(300, w - 10 - 46) : w - 10 - 46;
        int span = Math.max(1, d.high() - d.low());
        double frac = Math.max(0, Math.min(1, (target - d.low()) / (double) span));
        Kit.bar(g, bx, yy + 2, bw, 6, frac, Theme.AMBER_HI, Theme.AMBER, Theme.AMBER_LO, false);
        int kx = bx + (int) Math.round((bw - 2) * frac);
        g.fill(kx, yy, kx + 3, yy + 10, Theme.WOOD_EDGE);
        text(g, signed(d.low()), bx, yy + 11, Theme.INK_3);
        String hi = signed(d.high());
        text(g, hi, bx + bw - width(hi), yy + 11, Theme.INK_3);
        sliderBox = new int[] {bx, bw, d.low(), span};
        clipped(bx, yy - 2, bw, 14, () -> {
            slideTo(lastX);
            sliderDrag = true;                          // held: the knob follows the mouse
        }, null, List.of(L.c("wynnlabs.craft.dist_slider")));
        int fx = bx + bw + 6;
        Kit.field(g, fx, yy - 1, 40, 12, tab.distBox.isFocused());
        tab.distBox.setX(fx + 3);
        tab.distBox.setY(yy + 1);
        tab.distBox.setWidth(34);
        if (visible(fx, yy - 1, 40, 12)) {
            tab.distBox.render(g, mx, my, pt);
            if (tab.distBox.getValue().isEmpty() && !tab.distBox.isFocused()) {
                text(g, fit(L.t("wynnlabs.craft.dist_value"), 34), fx + 3, yy + 1, Theme.INK_3);
            }
        }
        clipped(fx, yy - 1, 40, 12, () -> {
            tab.screen.setFocused(tab.distBox);
            tab.distBox.setFocused(true);
        }, null, List.of(L.c("wynnlabs.craft.dist_target")));
        yy += 22;
        return yy - y;
    }

    double lastX;

    /** The probability slider this frame: {x, width, low, span}; null when there is none. */
    private int[] sliderBox;

    private boolean sliderDrag;

    private void slideTo(double mx) {
        int[] b = sliderBox;
        if (b == null) {
            return;
        }
        double f = Math.max(0, Math.min(1, (mx - b[0]) / (double) b[1]));
        distTarget = b[2] + (int) Math.round(f * b[3]);
        tab.distBox.setValue(String.valueOf(distTarget));
    }

    /** A drag in the tab: moves the slider's knob while it is held; true when it did. */
    boolean mouseDragged(double mx, double my) {
        if (!sliderDrag) {
            return false;
        }
        slideTo(mx);
        return true;
    }

    void mouseReleased() {
        sliderDrag = false;
    }

    int drawShopping(GuiGraphics g, int x, int y, int w, int mx, int my) {
        CraftData.Recipe r = tab.recipe();
        Shopping.Needs n = Shopping.of(r, tab.tier1, tab.tier2, tab.layout(), tab.stock, tab.prices::get);
        tab.wantedPrices.addAll(n.unpriced());
        String right = n.lines().stream().anyMatch(l -> !l.material())
                ? costText(n.cost(), n.lines().stream().anyMatch(l -> !l.material() && l.cost() != null),
                        !n.unpriced().isEmpty())
                : null;
        int yy = y + fold(g, x, y, w, foldShop, L.t("wynnlabs.craft.shopping"), right, mx, my, () -> foldShop = !foldShop);
        if (!foldShop) {
            return yy - y;
        }
        if (n.empty()) {
            text(g, L.t("wynnlabs.craft.shopping_none"), x + 10, yy, Theme.GOOD);
            yy += 12;
        }
        // a large page keeps each line's status near its name
        int lineW = w >= WIDE ? 310 : w;
        for (Shopping.Line l : n.lines()) {
            String name = fit(l.count() + "x " + l.name(), lineW - 140);
            text(g, name, x + 10, yy, Theme.INK);
            if (l.material()) {
                starIcons(g, x + 13 + width(name), yy + 1, l.tier(), Theme.PRICE);
            }
            String where = l.material() ? L.t("wynnlabs.craft.gather_or_buy")
                    : l.cost() == null ? L.t("wynnlabs.craft.price_unknown")
                    : "~" + Prices.fmt(l.cost()) + (l.old() ? " (" + L.t("wynnlabs.craft.old") + ")" : "");
            text(g, where, x + lineW - width(where), yy, l.cost() == null && !l.material() ? Theme.INK_3 : Theme.PRICE);
            yy += 11;
        }
        // the station button and profession XP
        String st = stationText != null ? stationText : L.t("wynnlabs.craft.station");
        int sw = width(st) + 22;
        int sx = x + 10;
        boolean sh = in(mx, my, sx, yy + 1, sw, 14);
        Kit.button(g, sx, yy + 1, sw, 14, sh ? Kit.HOVER : Kit.REST);
        Art.icon(g, "map-pin", sx + 5, yy + 4, 8, Theme.CREAM);
        wood(g, st, sx + 16, yy + 4, Theme.CREAM, true);
        clipped(sx, yy + 1, sw, 14, this::pinStation, null, List.of(L.c("wynnlabs.craft.station_tip",
                Professions.name(r.profession().toUpperCase(Locale.ROOT)))));
        int[] xp = Crafting.professionXp(r.profession());
        int crafts = CraftChecks.craftsToLevel(r.xp(), xp[0], xp[1]);
        String xpLine = crafts < 0 ? L.t("wynnlabs.craft.xp_only", thousands(r.xp()))
                : L.t("wynnlabs.craft.xp_line", thousands(r.xp()), crafts);
        text(g, fit(xpLine, w - sw - 24), sx + sw + 8, yy + 4, Theme.INK_2);
        yy += 18;
        return yy - y;
    }

    private void pinStation() {
        CraftData.Recipe r = tab.recipe();
        int d = Crafting.pinStation(r.profession(), L.t("wynnlabs.craft.station_label",
                Professions.name(r.profession().toUpperCase(Locale.ROOT))));
        stationText = d < 0 ? L.t("wynnlabs.craft.station_none") : L.t("wynnlabs.craft.station_pinned", d);
    }

    int drawWhat(GuiGraphics g, int x, int y, int w, int mx, int my) {
        long ready = what.stream().filter(WhatCanICraft.Entry::craftable).count();
        String right = what.isEmpty() ? null : L.t("wynnlabs.craft.what_ready", ready);
        int yy = y + fold(g, x, y, w, tab.foldWhat, L.t("wynnlabs.craft.what"), right, mx, my, () -> {
            tab.foldWhat = !tab.foldWhat;
            if (tab.foldWhat) {
                startWhat();
            }
        });
        if (!tab.foldWhat) {
            return yy - y;
        }
        if (what.isEmpty()) {
            text(g, L.t("wynnlabs.craft.searching"), x + 10, yy, Theme.INK_3);
            return yy + 12 - y;
        }
        for (int i = 0; i < what.size(); i++) {
            WhatCanICraft.Entry e = what.get(i);
            boolean hover = in(mx, my, x + 8, yy, w - 8, 12);
            Kit.row(g, x + 8, yy, w - 8, 12, i, hover);
            Art.icon(g, typeIcon(e.type()), x + 12, yy + 2, 8,
                    Theme.paperRgb(e.craftable() ? Theme.INK : Theme.INK_3));
            text(g, typeName(e.type()) + (e.recipe() == null ? "" : " " + e.recipe().bracket()), x + 24, yy + 2,
                    e.craftable() ? Theme.INK : Theme.INK_3);
            String right2;
            int col;
            if (e.craftable()) {
                String main = tab.weights.isEmpty() ? null : tab.weights.keySet().iterator().next();
                Craft.Range v = main == null ? null : e.best().result().ids().get(main);
                right2 = v == null ? "✔" : shortStat(main) + " " + range(v, tab.unit(main));
                col = Theme.GOOD;
            } else {
                right2 = lackText(e);
                col = Theme.INK_3;
            }
            right2 = fit(right2, w / 2);
            text(g, right2, x + w - 4 - width(right2), yy + 2, col);
            clipped(x + 8, yy, w - 8, 12, () -> loadWhat(e), null, List.of(L.c("wynnlabs.craft.what_tip")));
            yy += 12;
        }
        return yy + 2 - y;
    }

    private String lackText(WhatCanICraft.Entry e) {
        List<String> parts = new ArrayList<>();
        for (WhatCanICraft.Lack l : e.lacks()) {
            parts.add(switch (l.kind()) {
                case LEVEL -> L.t("wynnlabs.craft.lack_level", Professions.name(l.what().toUpperCase(Locale.ROOT)), l.count());
                case MATERIAL -> L.t("wynnlabs.craft.lack_material", l.count(), l.what());
                case INGREDIENTS -> L.t("wynnlabs.craft.lack_ingredients");
            });
        }
        return String.join(" · ", parts);
    }

    void startWhat() {
        what.clear();
        WhatCanICraft.Stock s = CraftingTab.previewLevels.isEmpty() ? tab.stock
                : new WhatCanICraft.Stock(tab.stock.ingredients(), tab.stock.materials(), CraftingTab.previewLevels);
        CraftSearch.Goal goal = tab.goal();
        List<Ingredient> all = tab.ingredients;
        whatSearching = whatRunner.run(15 * 400, b -> WhatCanICraft.run(s, goal, all, 300, b, what::add));
    }

    private void loadWhat(WhatCanICraft.Entry e) {
        if (e.recipe() == null) {
            return;
        }
        tab.type = e.type();
        tab.minLevel = e.recipe().minLevel();
        tab.tier1 = Math.max(1, e.tier1());
        tab.tier2 = Math.max(1, e.tier2());
        Arrays.fill(tab.slots, null);
        if (e.best() != null) {
            tab.optimiserPanel.apply(e.best());
        }
        tab.scroll = 0;
    }
}
