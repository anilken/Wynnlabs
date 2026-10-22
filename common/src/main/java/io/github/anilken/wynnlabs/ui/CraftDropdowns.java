package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.crafting.Craft;
import io.github.anilken.wynnlabs.crafting.CraftData;
import io.github.anilken.wynnlabs.crafting.CraftSearch;
import io.github.anilken.wynnlabs.crafting.Ingredient;
import io.github.anilken.wynnlabs.crafting.WhatCanICraft;
import io.github.anilken.wynnlabs.game.Professions;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.ui.CraftingTab.Act;
import io.github.anilken.wynnlabs.ui.CraftingTab.Drop;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.github.anilken.wynnlabs.ui.CraftFormat.dec;
import static io.github.anilken.wynnlabs.ui.CraftFormat.pct;
import static io.github.anilken.wynnlabs.ui.CraftFormat.typeIcon;
import static io.github.anilken.wynnlabs.ui.CraftFormat.typeName;

/** The crafting tab's drop-down lists: types, levels, a slot's ingredients, goal identifications, conditions. */
final class CraftDropdowns extends CraftPanel {

    CraftDropdowns(CraftingTab tab) {
        super(tab);
    }

    private static final List<String> TYPE_COL_A = List.of("#armour", "helmet", "chestplate", "leggings", "boots",
            "#accessory", "ring", "bracelet", "necklace");

    private static final List<String> TYPE_COL_B = List.of("#weapon", "spear", "dagger", "bow", "wand", "relik",
            "#consumable", "potion", "scroll", "food");

    void drawDrop(GuiGraphics g, int mx, int my, float pt) {
        if (tab.open == Drop.NONE || tab.openAnchor == null) {
            return;
        }
        g.nextStratum();
        switch (tab.open) {
            case TYPE -> dropTypes(g, mx, my);
            case LEVEL -> dropLevels(g, mx, my);
            case SLOT -> dropPicker(g, mx, my, pt);
            case GOAL -> dropGoal(g, mx, my);
            case CONDITIONS -> dropConditions(g, mx, my);
            default -> { }
        }
    }

    private int dropX(int w) {
        return Math.max(4, Math.min(tab.openAnchor[0], tab.screen.width - w - 4));
    }

    /** The list opens under its field, or above it when there is no room below. */
    private int dropY(int h) {
        int below = tab.openAnchor[1] + tab.openAnchor[3] + 2;
        if (below + h <= tab.screen.height - 4) {
            return below;
        }
        return Math.max(4, tab.openAnchor[1] - h - 2);
    }

    private void dropTypes(GuiGraphics g, int mx, int my) {
        int colW = 92;
        int rowH = 12;
        int rows = Math.max(TYPE_COL_A.size(), TYPE_COL_B.size());
        int w = 2 * colW + 8;
        int h = rows * rowH + 8;
        int x = dropX(w);
        int y = dropY(h);
        Kit.card(g, x, y, w, h, 0);
        tab.overlayBox = new int[] {x, y, w, h};
        java.util.Set<String> ready = new java.util.HashSet<>();
        for (WhatCanICraft.Entry e : tab.sectionsPanel.what) {
            if (e.craftable()) {
                ready.add(e.type());
            }
        }
        List<List<String>> cols = List.of(TYPE_COL_A, TYPE_COL_B);
        for (int c = 0; c < 2; c++) {
            int cx = x + 4 + c * (colW + 2);
            int ry = y + 4;
            for (String t : cols.get(c)) {
                if (t.startsWith("#")) {
                    text(g, L.t("wynnlabs.craft.group." + t.substring(1)), cx + 2, ry + 2, Theme.INK_3);
                } else {
                    boolean on = t.equals(tab.type);
                    boolean hover = in(mx, my, cx, ry, colW, rowH);
                    Kit.row(g, cx, ry, colW, rowH, 0, on || hover);
                    Art.icon(g, typeIcon(t), cx + 6, ry + 2, 8, Theme.paperRgb(on ? Theme.INK : Theme.INK_2));
                    text(g, typeName(t), cx + 18, ry + 2, on ? Theme.INK : Theme.INK_2);
                    if (ready.contains(t)) {
                        Art.icon(g, "check", cx + colW - 10, ry + 2, 8, Theme.paperRgb(Theme.GOOD));
                    }
                    String pick = t;
                    tab.overlay.add(new Act(cx, ry, colW, rowH, () -> {
                        if (!pick.equals(tab.type)) {
                            tab.type = pick;
                            tab.minLevel = tab.bestBracket(pick).minLevel();
                            Arrays.fill(tab.slots, null);
                        }
                        tab.open = Drop.NONE;
                    }, null, null));
                }
                ry += rowH;
            }
        }
    }

    private void dropLevels(GuiGraphics g, int mx, int my) {
        List<CraftData.Recipe> rs = CraftData.ofType(tab.type);
        int rowH = 12;
        int shown = Math.min(12, rs.size());
        int w = 96;
        int h = shown * rowH + 6;
        int x = dropX(w);
        int y = dropY(h);
        Kit.card(g, x, y, w, h, 0);
        tab.overlayBox = new int[] {x, y, w, h};
        tab.dropScroll = Math.max(0, Math.min(tab.dropScroll, rs.size() - shown));
        int lv = tab.level(rs.get(0).profession());
        for (int i = 0; i < shown; i++) {
            CraftData.Recipe r = rs.get(tab.dropScroll + i);
            int ry = y + 3 + i * rowH;
            boolean on = r.minLevel() == tab.minLevel;
            boolean hover = in(mx, my, x + 2, ry, w - 4, rowH);
            Kit.row(g, x + 2, ry, w - 4, rowH, i, on || hover);
            boolean locked = r.minLevel() > lv;
            if (locked) {
                Art.icon(g, "lock", x + 6, ry + 2, 8, Theme.paperRgb(Theme.INK_3));
            }
            text(g, r.bracket(), x + 17, ry + 2, locked ? Theme.INK_3 : Theme.INK);
            int pick = r.minLevel();
            tab.overlay.add(new Act(x + 2, ry, w - 4, rowH, () -> {
                tab.minLevel = pick;
                tab.open = Drop.NONE;
            }, null, locked ? List.of(L.c("wynnlabs.craft.locked_recipe",
                    Professions.name(r.profession().toUpperCase(Locale.ROOT)), r.minLevel(), lv)) : null));
        }
        scrollMarks(g, x + w - 3, y, h, tab.dropScroll, rs.size(), shown);
    }

    private void scrollMarks(GuiGraphics g, int x, int y, int h, int first, int total, int shown) {
        if (total <= shown) {
            return;
        }
        int knobH = Math.max(8, h * shown / total);
        int ky = y + (h - knobH) * first / Math.max(1, total - shown);
        g.fill(x, ky, x + 2, ky + knobH, Theme.WOOD);
    }

    /** The ingredient list of one slot: searchable, own ones first, then by what they add there. */
    private void dropPicker(GuiGraphics g, int mx, int my, float pt) {
        CraftData.Recipe r = tab.recipe();
        int lv = tab.level(r.profession());
        List<Ingredient> lay = tab.layout();
        int[] effNow = Craft.effectiveness(lay);
        int slotEff = effNow[tab.openSlot];
        String q = tab.pickSearch.getValue().strip().toLowerCase(Locale.ROOT);
        CraftSearch.Goal goal = tab.goal();
        record Row(Ingredient ing, double gain, int own) {}
        List<Row> rows = new ArrayList<>();
        for (Ingredient i : tab.ingredients) {
            if (!i.usableBy(r.profession()) || i.level() > r.maxLevel()) {
                continue;
            }
            if (!q.isEmpty() && !i.name().toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            double gain = 0;
            for (Map.Entry<String, Double> e : goal.weights().entrySet()) {
                Ingredient.Stat st = i.ids().get(e.getKey());
                if (st != null) {
                    gain += e.getValue() * Craft.scaled(st, slotEff).mean();
                }
            }
            rows.add(new Row(i, gain, tab.owned(i.name())));
        }
        rows.sort((a, b) -> {
            boolean la = a.ing().level() > lv;
            boolean lb = b.ing().level() > lv;
            if (la != lb) {
                return la ? 1 : -1;
            }
            boolean oa = a.own() > 0;
            boolean ob = b.own() > 0;
            if (oa != ob) {
                return oa ? -1 : 1;
            }
            int c = Double.compare(b.gain(), a.gain());
            return c != 0 ? c : a.ing().name().compareTo(b.ing().name());
        });
        int rowH = 12;
        int shown = Math.min(11, Math.max(1, rows.size()));
        int w = 210;
        int h = 18 + shown * rowH + 6;
        int x = dropX(w);
        int y = dropY(h);
        Kit.card(g, x, y, w, h, 0);
        tab.overlayBox = new int[] {x, y, w, h};
        // the search field
        Kit.field(g, x + 4, y + 4, w - 8, 12, true);
        tab.pickSearch.setX(x + 7);
        tab.pickSearch.setY(y + 6);
        tab.pickSearch.setWidth(w - 14);
        tab.pickSearch.render(g, mx, my, pt);
        if (tab.pickSearch.getValue().isEmpty()) {
            text(g, fit(L.t("wynnlabs.craft.picker_hint", pct(slotEff)), w - 14), x + 7, y + 6, Theme.INK_3);
        }
        tab.dropScroll = Math.max(0, Math.min(tab.dropScroll, rows.size() - shown));
        int ry = y + 19;
        if (rows.isEmpty()) {
            text(g, L.t("wynnlabs.craft.picker_empty"), x + 6, ry + 2, Theme.INK_3);
        }
        for (int i = 0; i < shown && tab.dropScroll + i < rows.size(); i++) {
            Row row = rows.get(tab.dropScroll + i);
            Ingredient in = row.ing();
            boolean locked = in.level() > lv;
            boolean hover = in(mx, my, x + 2, ry, w - 4, rowH);
            Kit.row(g, x + 2, ry, w - 4, rowH, i, hover);
            int tx = x + 6;
            if (locked) {
                Art.icon(g, "lock", tx, ry + 2, 8, Theme.paperRgb(Theme.INK_3));
                tx += 10;
            }
            text(g, fit(in.name(), 96 - (tx - x - 6)), tx, ry + 2, locked ? Theme.INK_3 : Theme.INK);
            String own = row.own() > 0 ? String.valueOf(row.own()) : "—";
            text(g, own, x + 108 - width(own), ry + 2, row.own() > 0 ? Theme.INK_2 : Theme.INK_3);
            CraftSearch.Price p = tab.prices.get(in.name());
            String price = locked ? "Lv " + in.level() : p == null ? "?" : Prices.fmt(p.emeralds());
            text(g, price, x + 158 - width(price), ry + 2, locked ? Theme.BAD : Theme.PRICE);
            String gain = (row.gain() >= 0 ? "+" : "") + dec(row.gain(), row.gain() == Math.rint(row.gain()) ? 0 : 1);
            text(g, gain, x + w - 6 - width(gain), ry + 2, row.gain() > 0 ? Theme.GOOD
                    : row.gain() < 0 ? Theme.BAD : Theme.INK_3);
            String name = in.name();
            tab.overlay.add(new Act(x + 2, ry, w - 4, rowH, () -> {
                tab.slots[tab.openSlot] = name;
                tab.open = Drop.NONE;
                tab.pickSearch.setFocused(false);
            }, null, card(in)));
            if (hover) {
                List<Ingredient> p2 = new ArrayList<>(lay);
                p2.set(tab.openSlot, in);
                tab.nextPreview = p2;
            }
            ry += rowH;
        }
        scrollMarks(g, x + w - 3, y + 19, shown * rowH, tab.dropScroll, rows.size(), shown);
    }

    /** Identifications that the recipe's profession can get, for the goal and filter lists. */
    private List<String> idsForProfession() {
        CraftData.Recipe r = tab.recipe();
        java.util.TreeMap<String, String> byShown = new java.util.TreeMap<>();
        for (Ingredient i : tab.ingredients) {
            if (i.usableBy(r.profession()) && i.level() <= r.maxLevel()) {
                for (String id : i.ids().keySet()) {
                    byShown.put(tab.statName(id) + "|" + id, id);
                }
            }
        }
        return new ArrayList<>(byShown.values());
    }

    private void dropGoal(GuiGraphics g, int mx, int my) {
        List<String> ids = idsForProfession();
        ids.removeAll(tab.weights.keySet());
        int rowH = 11;
        int shown = Math.min(14, Math.max(1, ids.size()));
        int w = 150;
        int h = shown * rowH + 6;
        int x = dropX(w);
        int y = dropY(h);
        Kit.card(g, x, y, w, h, 0);
        tab.overlayBox = new int[] {x, y, w, h};
        tab.dropScroll = Math.max(0, Math.min(tab.dropScroll, ids.size() - shown));
        for (int i = 0; i < shown && tab.dropScroll + i < ids.size(); i++) {
            String id = ids.get(tab.dropScroll + i);
            int ry = y + 3 + i * rowH;
            boolean hover = in(mx, my, x + 2, ry, w - 4, rowH);
            Kit.row(g, x + 2, ry, w - 4, rowH, i, hover);
            text(g, fit(tab.statName(id), w - 12), x + 6, ry + 2, Theme.INK);
            tab.overlay.add(new Act(x + 2, ry, w - 4, rowH, () -> {
                tab.weights.put(id, 1.0);
                tab.open = Drop.NONE;
            }, null, null));
        }
        scrollMarks(g, x + w - 3, y, h, tab.dropScroll, ids.size(), shown);
    }

    private void dropConditions(GuiGraphics g, int mx, int my) {
        List<String> ids = idsForProfession();
        int rowH = 12;
        int fixed = 3;
        int shown = Math.min(10, ids.size());
        int w = 190;
        int h = (fixed + shown) * rowH + 8;
        int x = dropX(w);
        int y = dropY(h);
        Kit.card(g, x, y, w, h, 0);
        tab.overlayBox = new int[] {x, y, w, h};
        int ry = y + 4;
        // the durability floor, the cap to the player's points, then "no negative" per identification
        String floor = L.t("wynnlabs.craft.cond_floor", CraftingTab.FLOORS[tab.floorIndex]);
        condRow(g, x, ry, w, floor, null, mx, my, () -> tab.floorIndex = (tab.floorIndex + 1) % CraftingTab.FLOORS.length);
        ry += rowH;
        condRow(g, x, ry, w, L.t("wynnlabs.craft.cond_points"), tab.capToPoints, mx, my, () -> tab.capToPoints = !tab.capToPoints);
        ry += rowH;
        text(g, L.t("wynnlabs.craft.cond_no_negative"), x + 6, ry + 2, Theme.INK_3);
        ry += rowH;
        tab.dropScroll = Math.max(0, Math.min(tab.dropScroll, ids.size() - shown));
        for (int i = 0; i < shown; i++) {
            String id = ids.get(tab.dropScroll + i);
            condRow(g, x, ry, w, tab.statName(id), tab.noNegative.contains(id), mx, my, () -> {
                if (!tab.noNegative.remove(id)) {
                    tab.noNegative.add(id);
                }
            });
            ry += rowH;
        }
        scrollMarks(g, x + w - 3, y + 3 * rowH + 4, shown * rowH, tab.dropScroll, ids.size(), shown);
    }

    private void condRow(GuiGraphics g, int x, int y, int w, String text, Boolean on, int mx, int my, Runnable click) {
        boolean hover = in(mx, my, x + 2, y, w - 4, 12);
        Kit.row(g, x + 2, y, w - 4, 12, 0, hover);
        text(g, fit(text, w - 34), x + 6, y + 2, Theme.INK);
        if (on != null) {
            Kit.toggle(g, x + w - 26, y + 1, on);
        }
        tab.overlay.add(new Act(x + 2, y, w - 4, 12, click, null, null));
    }
}
