package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.crafting.Craft;
import io.github.anilken.wynnlabs.crafting.CraftChecks;
import io.github.anilken.wynnlabs.crafting.CraftData;
import io.github.anilken.wynnlabs.crafting.Shopping;
import io.github.anilken.wynnlabs.crafting.StockView;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.anilken.wynnlabs.ui.CraftFormat.costLine;
import static io.github.anilken.wynnlabs.ui.CraftFormat.costText;
import static io.github.anilken.wynnlabs.ui.CraftFormat.dec;
import static io.github.anilken.wynnlabs.ui.CraftFormat.range;
import static io.github.anilken.wynnlabs.ui.CraftFormat.signed;
import static io.github.anilken.wynnlabs.ui.CraftFormat.skill;
import static io.github.anilken.wynnlabs.ui.CraftFormat.stars;
import static io.github.anilken.wynnlabs.ui.CraftFormat.typeName;

/** The crafting tab's crafted item card: marks, health or damage, requirements, identifications, cost. */
final class CraftResultPanel extends CraftPanel {

    CraftResultPanel(CraftingTab tab) {
        super(tab);
    }

    int drawResult(GuiGraphics g, int x, int y, int w, int mx, int my) {
        CraftData.Recipe r = tab.recipe();
        Craft.Result c = Craft.craft(r, tab.tier1, tab.tier2, tab.speed, tab.preview != null ? tab.preview : tab.layout());
        List<CraftChecks.Gap> gaps = r.consumable() ? List.of() : CraftChecks.gaps(c.requirements(), tab.points);
        List<StockView.Short> shortMats = StockView.missing(r, tab.tier1, tab.tier2, tab.stock);
        int lv = tab.level(r.profession());
        int ids = c.ids().size();
        int h = 6 + 11 + 10 + 10 + ID_ROWS * 10 + 10 + 10 + 3;
        idMax = Math.max(0, ids - ID_ROWS);
        idScroll = Math.min(idScroll, idMax);
        cardRect = new int[] {x, y, w, h};
        Kit.card(g, x, y, w, h, Theme.GOLD_DEEP);
        int ix = x + 6;
        int yy = y + 6;
        String title = L.t("wynnlabs.craft.crafted", typeName(tab.type));
        // the two marks: can wear, can craft (or what is missing)
        String wear = r.consumable() ? L.t("wynnlabs.craft.can_use") : gaps.isEmpty() ? L.t("wynnlabs.craft.can_wear")
                : L.t("wynnlabs.craft.cannot_wear", skill(gaps.get(0).skill()), gaps.get(0).missing());
        String craft;
        boolean canCraft;
        if (r.minLevel() > lv) {
            craft = L.t("wynnlabs.craft.locked_short", r.minLevel());
            canCraft = false;
        } else if (!shortMats.isEmpty()) {
            craft = L.t("wynnlabs.craft.cannot_craft", shortMats.get(0).missing(), shortMats.get(0).material());
            canCraft = false;
        } else {
            String lacking = lackingIngredient();
            canCraft = lacking == null;
            craft = canCraft ? L.t("wynnlabs.craft.can_craft") : L.t("wynnlabs.craft.cannot_craft", 1, lacking);
        }
        int cxr = x + w - 6;
        cxr -= tag(g, cxr, yy - 1, craft, canCraft, true) + 3;
        cxr -= tag(g, cxr, yy - 1, wear, gaps.isEmpty(), true) + 3;
        text(g, Component.literal(fit(title, cxr - ix - 4)).withStyle(UiFont::bold), ix, yy, Theme.INK);
        List<Component> markTip = new ArrayList<>();
        for (CraftChecks.Gap gap : gaps) {
            markTip.add(L.c("wynnlabs.craft.gap", skill(gap.skill()), gap.need(), gap.have()));
        }
        for (StockView.Short s : shortMats) {
            markTip.add(L.c("wynnlabs.craft.short_material", s.missing(), s.material(), stars(s.tier())));
        }
        if (!markTip.isEmpty()) {
            clipped(cxr, yy - 1, x + w - 6 - cxr, 10, null, null, markTip);
        }
        yy += 11;
        // health or damage, and the level
        String main;
        if (c.damage() != null) {
            main = L.t("wynnlabs.craft.damage", range(c.damageLow(), ""), range(c.damageHigh(), ""), r.bracket());
            Art.icon(g, "sword", ix, yy, 8, Theme.paperRgb(Theme.INK_2));
        } else if (c.health() != null) {
            main = L.t("wynnlabs.craft.health", range(c.health(), ""), r.bracket());
            Art.icon(g, "heart", ix, yy, 8, Theme.paperRgb(Theme.BAD));
        } else {
            main = L.t("wynnlabs.craft.level_only", r.bracket());
            Art.icon(g, "user", ix, yy, 8, Theme.paperRgb(Theme.INK_2));
        }
        text(g, fit(main, w - 22), ix + 11, yy, Theme.INK);
        yy += 10;
        // requirements with the player's points
        int rx = ix;
        boolean any = false;
        for (int s = 0; s < 5; s++) {
            int need = c.requirements()[s];
            if (need <= 0) {
                continue;
            }
            any = true;
            boolean met = tab.points[s] >= need;
            String t = skill(s) + " " + need;
            text(g, t, rx, yy, Theme.INK);
            rx += width(t) + 2;
            Art.icon(g, met ? "check" : "x", rx, yy, 8, Theme.paperRgb(met ? Theme.GOOD : Theme.BAD));
            rx += 9;
            String have = String.valueOf(tab.points[s]);
            text(g, have, rx, yy, met ? Theme.GOOD : Theme.BAD);
            rx += width(have) + 8;
        }
        if (!any) {
            text(g, L.t(r.consumable() ? "wynnlabs.craft.no_reqs_consumable" : "wynnlabs.craft.no_reqs"), ix, yy,
                    Theme.INK_3);
        }
        yy += 10;
        // identifications: min, name, max, mean, and ▸ for the roll chances
        int minCol = 24;
        int idsTop = yy;
        int index = 0;
        for (Map.Entry<String, Craft.Range> e : c.ids().entrySet()) {
            if (index++ < idScroll || index > idScroll + ID_ROWS) {
                continue;
            }
            Craft.Range v = e.getValue();
            String id = e.getKey();
            boolean sel = id.equals(tab.sectionsPanel.distId);
            if (sel) {
                g.fill(x + 2, yy - 1, x + w - 2, yy + 9, 0x55FFF3C8);
            }
            String unit = tab.unit(id);
            String lo = signed(v.min()) + unit;
            String hi = signed(v.max()) + unit;
            int col = v.max() < 0 ? Theme.BAD : Theme.GOOD;
            text(g, lo, ix + minCol - width(lo), yy, col);
            String mean = "⌀" + dec(v.mean(), 1);
            int meanW = width(mean) + 10;
            int hiW = width(hi);
            String name = fit(tab.statName(id), w - 12 - minCol - 8 - hiW - meanW - 8);
            text(g, name, ix + minCol + 6, yy, Theme.INK_2);
            int hx = x + w - 6 - meanW - 6 - hiW;
            text(g, hi, hx, yy, col);
            text(g, mean + " ▸", x + w - 6 - meanW, yy, sel ? Theme.ACCENT : Theme.INK_3);
            clipped(x + 2, yy - 1, w - 4, 10, () -> {
                tab.sectionsPanel.distId = id;
                tab.sectionsPanel.distTarget = null;
                tab.distBox.setValue("");
                tab.sectionsPanel.foldDist = true;
            }, null, List.of(L.c("wynnlabs.craft.id_tip")));
            yy += 10;
        }
        if (ids == 0) {
            text(g, L.t("wynnlabs.craft.no_ids"), ix, yy, Theme.INK_3);
        }
        if (ids > ID_ROWS) {
            // a thin bar at the card's edge shows where the list is
            int trackH = ID_ROWS * 10;
            int knobH = Math.max(6, trackH * ID_ROWS / ids);
            int knobY = idsTop + (trackH - knobH) * idScroll / (ids - ID_ROWS);
            g.fill(x + w - 4, idsTop, x + w - 3, idsTop + trackH, 0x30624E3C);
            g.fill(x + w - 4, knobY, x + w - 3, knobY + knobH, Theme.WOOD);
        }
        yy = idsTop + ID_ROWS * 10;
        // powder and durability, or duration and charges
        String foot;
        boolean bad = false;
        if (r.consumable()) {
            foot = L.n("wynnlabs.craft.consumable_foot", c.charges(), range(c.duration(), ""), c.charges());
        } else {
            bad = c.durability().min() <= 0;
            foot = (c.powderSlots() > 0 ? L.t("wynnlabs.craft.powder", c.powderSlots()) + " · " : "")
                    + L.t("wynnlabs.craft.durability", range(c.durability(), ""));
        }
        text(g, fit(foot, w - 12), ix, yy, bad ? Theme.BAD : Theme.INK_2);
        if (bad) {
            clipped(ix, yy, w - 12, 10, null, null, List.of(L.c("wynnlabs.craft.durability_low")));
        }
        yy += 10;
        // cost and how many can be made from the stock
        Shopping.Needs n = Shopping.of(r, tab.tier1, tab.tier2, tab.layout(), tab.stock, tab.prices::get);
        int times = craftableTimes(r);
        boolean buying = n.lines().stream().anyMatch(l -> !l.material());
        boolean priced = n.lines().stream().anyMatch(l -> !l.material() && l.cost() != null);
        String cost = !buying ? L.t("wynnlabs.craft.cost_none")
                : costLine(costText(n.cost(), priced, !n.unpriced().isEmpty()));
        String line = cost + " · " + L.t("wynnlabs.craft.times", times);
        text(g, fit(line, w - 12), ix, yy, Theme.PRICE);
        return h + 6;
    }

    /** The first grid ingredient there are not enough of, or null. */
    private String lackingIngredient() {
        Map<String, Integer> need = new HashMap<>();
        for (String s : tab.slots) {
            if (s != null) {
                need.merge(s, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            if (tab.owned(e.getKey()) < e.getValue()) {
                return e.getKey();
            }
        }
        return null;
    }

    /** How many times the recipe can be made from the stock as laid out now. */
    private int craftableTimes(CraftData.Recipe r) {
        int n = Integer.MAX_VALUE;
        int[] tiers = {tab.tier1, tab.tier2};
        for (int i = 0; i < 2; i++) {
            CraftData.Material m = r.materials().get(i);
            n = Math.min(n, tab.stock.material(m.shown(), tiers[i]) / m.amount());
        }
        Map<String, Integer> need = new HashMap<>();
        for (String s : tab.slots) {
            if (s != null) {
                need.merge(s, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            n = Math.min(n, tab.owned(e.getKey()) / e.getValue());
        }
        return n == Integer.MAX_VALUE ? 0 : n;
    }

    /** Identification rows the item card shows; more scroll inside it. */
    private static final int ID_ROWS = 8;

    int idScroll;
    int idMax;
    int[] cardRect;
}
