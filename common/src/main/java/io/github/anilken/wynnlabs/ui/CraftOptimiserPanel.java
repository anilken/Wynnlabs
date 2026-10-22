package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.crafting.Craft;
import io.github.anilken.wynnlabs.crafting.CraftChecks;
import io.github.anilken.wynnlabs.crafting.CraftData;
import io.github.anilken.wynnlabs.crafting.CraftSearch;
import io.github.anilken.wynnlabs.crafting.Ingredient;
import io.github.anilken.wynnlabs.crafting.SearchRunner;
import io.github.anilken.wynnlabs.ui.CraftingTab.Drop;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.anilken.wynnlabs.ui.CraftFormat.dec;
import static io.github.anilken.wynnlabs.ui.CraftFormat.range;
import static io.github.anilken.wynnlabs.ui.CraftFormat.skill;
import static io.github.anilken.wynnlabs.ui.CraftFormat.weightText;

/** The crafting tab's optimiser: the goal chips, the search and its three best layouts. */
final class CraftOptimiserPanel extends CraftPanel {

    private static final double[] WEIGHT_STEPS = {1.0, 0.5, 2.0};
    private final SearchRunner runner = new SearchRunner();
    private CraftSearch.Budget searching;
    String resultsFor = "";

    CraftOptimiserPanel(CraftingTab tab) {
        super(tab);
    }

    /** The menu was closed: a running search stops. */
    void stop() {
        runner.cancel();
    }

    int drawOptimiser(GuiGraphics g, int x, int y, int w, int mx, int my) {
        int yy = y;
        section(g, L.t("wynnlabs.craft.best"), x, yy, w);
        yy += 11;
        // goal chips, the conditions chip and the search button; the chips wrap onto more rows
        // instead of running under the button when there are many
        boolean running = searching != null && !searching.cancelled() && searching.progress() < 1.0;
        String go = L.t(running ? "wynnlabs.craft.stop" : "wynnlabs.craft.search");
        int gw = width(go) + 12;
        int gx = x + w - gw;
        int rowY = yy;
        boolean gh = in(mx, my, gx, rowY, gw, ROW_H);
        Kit.button(g, gx, rowY, gw, ROW_H, gh ? Kit.HOVER : Kit.ON);
        wood(g, go, gx + 6, rowY + 2, gh ? Theme.CREAM : Theme.INK, gh);
        clipped(gx, rowY, gw, ROW_H, running ? this::stopSearch : this::startSearch, null,
                List.of(L.c("wynnlabs.craft.search_tip")));
        int limit = gx - 4;
        int cx = x;
        for (Map.Entry<String, Double> e : new ArrayList<>(tab.weights.entrySet())) {
            String label = shortStat(e.getKey()) + " ×" + weightText(e.getValue());
            int bw = Math.min(width(label) + 10, 90);
            if (cx > x && cx + bw > limit) {
                cx = x;
                yy += ROW_H + 3;
            }
            boolean hover = in(mx, my, cx, yy, bw, ROW_H);
            Kit.button(g, cx, yy, bw, ROW_H, Kit.ON);
            wood(g, fit(label, bw - 8), cx + 4, yy + 2, Theme.INK, false);
            String id = e.getKey();
            clipped(cx, yy, bw, ROW_H, () -> cycleWeight(id), () -> tab.weights.remove(id),
                    List.of(Component.literal(tab.statName(id)), L.c("wynnlabs.craft.goal_tip")));
            if (hover) {
                Kit.outline(g, cx, yy, bw, ROW_H, Theme.GOLD_EDGE);
            }
            cx += bw + 3;
        }
        int conds = tab.noNegative.size() + (tab.capToPoints ? 1 : 0) + (tab.floorIndex > 0 ? 1 : 0);
        String cl = L.t("wynnlabs.craft.conditions", conds);
        int cw = width(cl) + 10;
        if (cx > x && cx + 15 + cw > limit) {
            cx = x;
            yy += ROW_H + 3;
        }
        int[] addAnchor = {cx, yy, 12, ROW_H};
        chip(g, cx, yy, 12, "+", mx, my, () -> tab.toggle(Drop.GOAL, -1, addAnchor), List.of(L.c("wynnlabs.craft.goal_add")));
        cx += 15;
        int[] condAnchor = {cx, yy, cw, ROW_H};
        chip(g, cx, yy, cw, cl, mx, my, () -> tab.toggle(Drop.CONDITIONS, -1, condAnchor), List.of(L.c("wynnlabs.craft.conditions_tip")));
        yy += ROW_H + 4;
        if (running) {
            Kit.tealBar(g, x, yy + 2, w, 6, searching.progress(), false);
            text(g, L.t("wynnlabs.craft.searching"), x, yy + 10, Theme.INK_3);
            yy += 22;
            return yy - y;
        }
        if (tab.results.isEmpty()) {
            text(g, fit(L.t("wynnlabs.craft.search_first"), w), x, yy, Theme.INK_3);
            yy += 12;
            return yy - y;
        }
        String[] names = {L.t("wynnlabs.craft.tier_own"), L.t("wynnlabs.craft.tier_two"), L.t("wynnlabs.craft.tier_any")};
        String mainId = tab.weights.isEmpty() ? null : tab.weights.keySet().iterator().next();
        for (int i = 0; i < tab.results.size(); i++) {
            CraftSearch.Outcome o = tab.results.get(i);
            int ry = yy;
            boolean hover = in(mx, my, x, ry, w, 13);
            Kit.row(g, x, ry, w, 13, i, hover);
            text(g, fit(names[i], 62), x + 4, ry + 3, Theme.INK);
            if (o.best() == null) {
                text(g, L.t("wynnlabs.craft.no_layout"), x + 70, ry + 3, Theme.INK_3);
                yy += 14;
                continue;
            }
            CraftSearch.Layout l = o.best();
            if (l.slots().stream().allMatch(Ingredient::empty)) {
                text(g, L.t(i == 0 ? "wynnlabs.craft.no_own" : "wynnlabs.craft.no_layout"), x + 70, ry + 3,
                        Theme.INK_3);
                yy += 14;
                continue;
            }
            Craft.Range main = mainId == null ? null : l.result().ids().get(mainId);
            String val = main == null ? dec(l.score(), 1) : range(main, tab.unit(mainId));
            if (!o.complete()) {
                val = "≈ " + val;
            }
            text(g, val, x + 70, ry + 3, Theme.INK);
            int mx2 = x + 70 + width(val) + 6;
            List<CraftChecks.Gap> gaps = CraftChecks.gaps(l.result().requirements(), tab.points);
            Art.icon(g, gaps.isEmpty() ? "check" : "x", mx2, ry + 2, 8, Theme.paperRgb(gaps.isEmpty() ? Theme.GOOD : Theme.BAD));
            mx2 += 11;
            if (!l.bought().isEmpty()) {
                String cost = "~" + tab.liveCost(l.bought());
                text(g, cost, mx2, ry + 3, Theme.PRICE);
            }
            String apply = L.t("wynnlabs.craft.apply");
            int aw = width(apply) + 10;
            int ax = x + w - aw - 1;
            boolean ah = in(mx, my, ax, ry + 1, aw, 11);
            Kit.button(g, ax, ry + 1, aw, 11, ah ? Kit.HOVER : Kit.REST);
            wood(g, apply, ax + 5, ry + 2, Theme.CREAM, true);
            List<Component> tip = layoutTip(o, gaps);
            clipped(ax, ry + 1, aw, 11, () -> apply(l), null, tip);
            clipped(x, ry, w - aw - 2, 13, null, null, tip);
            if (hover) {
                tab.nextPreview = l.slots();
            }
            yy += 14;
        }
        fine(g, fitFine(L.t("wynnlabs.craft.best_hint"), w), x, yy + 1, Theme.INK_3);
        yy += 11;
        return yy - y;
    }

    private List<Component> layoutTip(CraftSearch.Outcome o, List<CraftChecks.Gap> gaps) {
        List<Component> tip = new ArrayList<>();
        for (Ingredient i : o.best().slots()) {
            tip.add(Component.literal(i.empty() ? "—" : i.name()).withStyle(i.empty() ? ChatFormatting.GRAY : ChatFormatting.WHITE));
        }
        for (CraftChecks.Gap g : gaps) {
            tip.add(L.c("wynnlabs.craft.gap", skill(g.skill()), g.need(), g.have()).withStyle(ChatFormatting.RED));
        }
        if (!o.best().bought().isEmpty()) {
            tip.add(L.c("wynnlabs.craft.to_buy", String.join(", ", o.best().bought())).withStyle(ChatFormatting.GOLD));
        }
        for (String u : o.best().unpriced()) {
            tip.add(L.c("wynnlabs.craft.no_price", u).withStyle(ChatFormatting.GRAY));
        }
        for (String u : o.best().oldPrices()) {
            tip.add(L.c("wynnlabs.craft.old_price", u).withStyle(ChatFormatting.GRAY));
        }
        if (!o.complete()) {
            tip.add(L.c("wynnlabs.craft.maybe_not_best").withStyle(ChatFormatting.GRAY));
        }
        return tip;
    }

    private void chip(GuiGraphics g, int x, int y, int w, String text, int mx, int my, Runnable click, List<Component> tip) {
        int state = in(mx, my, x, y, w, ROW_H) ? Kit.HOVER : Kit.REST;
        Kit.button(g, x, y, w, ROW_H, state);
        wood(g, text, x + (w - width(text)) / 2, y + 2, Kit.buttonText(state), true);
        clipped(x, y, w, ROW_H, click, null, tip);
    }

    private void cycleWeight(String id) {
        double w = tab.weights.getOrDefault(id, 1.0);
        int i = 0;
        for (int k = 0; k < WEIGHT_STEPS.length; k++) {
            if (Math.abs(WEIGHT_STEPS[k] - w) < 1e-9) {
                i = k;
            }
        }
        tab.weights.put(id, WEIGHT_STEPS[(i + 1) % WEIGHT_STEPS.length]);
    }

    void startSearch() {
        if (tab.weights.isEmpty()) {
            tab.flash.accept(L.t("wynnlabs.craft.goal_empty"));
            return;
        }
        CraftData.Recipe r = tab.recipe();
        Map<String, CraftSearch.Price> pr = new HashMap<>(tab.prices);
        CraftSearch.Request q = new CraftSearch.Request(r, tab.tier1, tab.tier2, tab.speed, tab.goal(), tab.ingredients, tab.stock.ingredients(),
                tab.level(r.profession()), false, pr::get);
        tab.results = List.of();
        resultsFor = tab.key();
        String forKey = resultsFor;
        searching = runner.submit(q, 3000, out -> {
            if (forKey.equals(resultsFor)) {
                tab.results = List.copyOf(out);
            }
        });
    }

    private void stopSearch() {
        runner.cancel();
        searching = null;
    }

    void apply(CraftSearch.Layout l) {
        for (int i = 0; i < Craft.SLOTS; i++) {
            Ingredient in = l.slots().get(i);
            tab.slots[i] = in.empty() ? null : in.name();
        }
        tab.flash.accept(L.t("wynnlabs.craft.applied"));
    }
}
