package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.CraftingTab.Act;
import io.github.anilken.wynnlabs.crafting.Craft;
import io.github.anilken.wynnlabs.crafting.CraftChecks;
import io.github.anilken.wynnlabs.crafting.CraftSearch;
import io.github.anilken.wynnlabs.crafting.Ingredient;
import io.github.anilken.wynnlabs.crafting.RecipeCode;
import io.github.anilken.wynnlabs.crafting.SavedRecipes;
import io.github.anilken.wynnlabs.crafting.SearchRunner;
import io.github.anilken.wynnlabs.crafting.SetSuggestions;
import io.github.anilken.wynnlabs.crafting.Suggestions;
import io.github.anilken.wynnlabs.crafting.Types;
import io.github.anilken.wynnlabs.crafting.WhatCanICraft;
import io.github.anilken.wynnlabs.crafting.WynnBuilderCode;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.github.anilken.wynnlabs.CraftFormat.costLine;
import static io.github.anilken.wynnlabs.CraftFormat.costText;
import static io.github.anilken.wynnlabs.CraftFormat.range;
import static io.github.anilken.wynnlabs.CraftFormat.skill;
import static io.github.anilken.wynnlabs.CraftFormat.typeIcon;
import static io.github.anilken.wynnlabs.CraftFormat.typeName;

/** The crafting tab's suggested whole sets: their cards, the detail list over the page, copying and saving. */
final class CraftSetsPanel extends CraftPanel {

    private final SearchRunner setRunner = new SearchRunner();
    CraftSearch.Budget setting;
    private String setsFor = "";
    int setPage;

    CraftSetsPanel(CraftingTab tab) {
        super(tab);
    }

    /** The menu was closed: a running search stops. */
    void stop() {
        setRunner.cancel();
        setting = null;
        setsFor = "";
    }

    void startSets(boolean force) {
        String k = tab.suggestKey();
        if (!force && k.equals(setsFor) && (setting != null || !tab.sets.isEmpty())) {
            return;
        }
        setsFor = k;
        tab.sets.clear();
        setPage = 0;
        WhatCanICraft.Stock st = CraftingTab.previewLevels.isEmpty() ? tab.stock
                : new WhatCanICraft.Stock(tab.stock.ingredients(), tab.stock.materials(), CraftingTab.previewLevels);
        List<Ingredient> all = tab.ingredients;
        Map<String, CraftSearch.Price> pr = new HashMap<>(tab.prices);
        String weapon = tab.suggestionsPanel.suggestTypes().get(0);
        List<Suggestions.GoalSet> goals = Suggestions.goalSets(tab.suggestionsPanel.goalOrder());
        int combat = CraftSuggestionsPanel.combatLevel();
        setting = setRunner.run(300_000, b -> {
            SetSuggestions.run(weapon, goals, combat, st, all, pr::get, 120, b, tab.sets::add);
            b.cancel();
        });
    }

    private boolean setLocked(SetSuggestions.CraftSet s) {
        for (SetSuggestions.Piece pc : s.pieces()) {
            if (pc.layout() != null && pieceLocked(pc)) {
                return true;
            }
        }
        return false;
    }

    private boolean pieceLocked(SetSuggestions.Piece pc) {
        int lv = tab.level(pc.recipe().profession());
        if (pc.recipe().minLevel() > lv) {
            return true;
        }
        for (Ingredient in : pc.layout().slots()) {
            if (!in.empty() && in.level() > lv) {
                return true;
            }
        }
        return false;
    }

    private boolean setShown(SetSuggestions.CraftSet s) {
        return switch (CraftSuggestionsPanel.SUGGEST_FILTERS[tab.suggestionsPanel.suggestFilter]) {
            case "craftable" -> !setLocked(s);
            case "stock" -> s.fromStock();
            case "cheap" -> setCost(s)[0] < 64 * 9 && setCost(s)[2] == 0;
            default -> true;
        };
    }

    /** {cost, priced copies, unpriced copies} of what a set needs bought, at today's prices. */
    private long[] setCost(SetSuggestions.CraftSet s) {
        long cost = 0;
        long priced = 0;
        long unknown = 0;
        for (String n : s.bought()) {
            CraftSearch.Price p = tab.prices.get(n);
            if (p == null) {
                unknown++;
                tab.wantedPrices.add(n);
            } else {
                cost += p.emeralds();
                priced++;
            }
        }
        return new long[] {cost, priced, unknown};
    }

    void drawSets(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        List<SetSuggestions.CraftSet> list = new ArrayList<>();
        for (SetSuggestions.CraftSet s : tab.sets) {
            if (setShown(s)) {
                list.add(s);
            }
        }
        List<String> goals = tab.suggestionsPanel.goalOrder();
        list.sort((a, b) -> {
            if (a.fromStock() != b.fromStock()) {
                return a.fromStock() ? -1 : 1;
            }
            int ga = goals.indexOf(a.goal().key());
            int gb = goals.indexOf(b.goal().key());
            if (ga != gb) {
                return Integer.compare(ga, gb);
            }
            return Integer.compare(a.step(), b.step());
        });
        boolean running = setting != null && !setting.cancelled();
        int cols = w >= CraftingTab.TWO_COLUMNS_MIN ? 2 : 1;
        int pageBar = 16;
        int gap = 6;
        // as many rows of cards (up to 3) as fit their pieces, totals and buttons (110px each)
        int rows = Math.max(1, Math.min(3, (h - pageBar + gap) / (110 + gap)));
        int per = rows * cols;
        int cardH = (h - pageBar - (rows - 1) * gap) / rows;
        int cardW = (w - (cols - 1) * 8) / cols;
        int pages = Math.max(1, (list.size() + per - 1) / per);
        setPage = Math.max(0, Math.min(setPage, pages - 1));
        if (list.isEmpty()) {
            String t = L.t(running ? "wynnlabs.craft.set_working" : "wynnlabs.craft.suggest_none");
            text(g, t, x + (w - width(t)) / 2, y + 40, Theme.INK_3);
        }
        for (int i = 0; i < per && setPage * per + i < list.size(); i++) {
            int cx = x + (i % cols) * (cardW + 8);
            int cy = y + (i / cols) * (cardH + gap);
            setCard(g, list.get(setPage * per + i), cx, cy, cardW, cardH, mx, my);
        }
        int by = y + h - pageBar + 2;
        String count = L.n(running ? "wynnlabs.craft.set_count_running" : "wynnlabs.craft.set_count", list.size(), list.size(),
                setPage + 1, pages);
        fine(g, count, x, by + 2, Theme.INK_3);
        pageButtons(g, x + w, by, setPage, pages, pg -> setPage = pg, mx, my);
        tab.pageCount = pages;
        if (tab.setDetail != null) {
            setDetail(g, x, y, w, h, mx, my);
        }
    }

    /** The ‹ 1 2 3 › buttons ending at {@code right}. */
    private void pageButtons(GuiGraphics g, int right, int by, int page, int pages,
                             java.util.function.IntConsumer go, int mx, int my) {
        int px = right;
        List<int[]> buttons = new ArrayList<>();
        buttons.add(new int[] {page + 1, -1});
        int from = Math.max(0, Math.min(page - 3, pages - 7));
        for (int pg = Math.min(pages - 1, from + 6); pg >= from; pg--) {
            buttons.add(new int[] {pg, 0});
        }
        buttons.add(new int[] {page - 1, -2});
        for (int[] b : buttons) {
            String label = b[1] < 0 ? "" : String.valueOf(b[0] + 1);
            int bw = Math.max(14, width(label) + 8);
            px -= bw;
            boolean usable = b[0] >= 0 && b[0] < pages;
            boolean on = b[1] == 0 && b[0] == page;
            int state = on ? Kit.ON : usable && in(mx, my, px, by, bw, ROW_H) ? Kit.HOVER : Kit.REST;
            Kit.button(g, px, by, bw, ROW_H, state);
            if (b[1] < 0) {
                Art.icon(g, b[1] == -1 ? "chevron-right" : "chevron-left", px + (bw - 8) / 2, by + 2, 8,
                        usable ? Kit.buttonText(state) : Theme.CREAM_DIM);
            } else {
                wood(g, label, px + (bw - width(label)) / 2, by + 1, Kit.buttonText(state), !on);
            }
            if (usable) {
                int target = b[0];
                tab.acts.add(new Act(px, by, bw, ROW_H, () -> go.accept(target), null, null));
            }
            px -= 2;
        }
    }

    private String setTitle(SetSuggestions.CraftSet s) {
        String bracket = "";
        for (SetSuggestions.Piece pc : s.pieces()) {
            if (pc.recipe() != null && Types.armour(pc.type())) {
                bracket = pc.recipe().bracket();
                break;
            }
        }
        return L.t("wynnlabs.craft.set_name", L.t("wynnlabs.craft.goalset." + s.goal().key()), bracket);
    }

    /** The main value of a piece for the set's goal: its first goal identification's range. */
    private String pieceValue(SetSuggestions.CraftSet s, SetSuggestions.Piece pc) {
        if (pc.layout() == null) {
            return "—";
        }
        for (String id : s.goal().goal().weights().keySet()) {
            Craft.Range v = pc.layout().result().ids().get(id);
            if (v != null) {
                return range(v, tab.unit(id));
            }
        }
        return "✔";
    }

    private void setCard(GuiGraphics g, SetSuggestions.CraftSet s, int x, int y, int w, int h, int mx, int my) {
        boolean hover = in(mx, my, x, y, w, h);
        Kit.card(g, x, y, w, h, s.fromStock() ? Theme.GOOD : Theme.GOLD_DEEP, hover);
        int ix = x + 6;
        // title and marks
        int notWorn = 0;
        for (SetSuggestions.Piece pc : s.pieces()) {
            if (pc.layout() != null && !pc.recipe().consumable()
                    && !CraftChecks.gaps(pc.layout().result().requirements(), tab.points).isEmpty()) {
                notWorn++;
            }
        }
        boolean locked = setLocked(s);
        int right = x + w - 6;
        String stockTag = s.fromStock() ? L.t("wynnlabs.craft.suggest_in_stock")
                : L.t("wynnlabs.craft.suggest_to_buy", s.bought().size());
        right -= tag(g, right, y + 3, stockTag, s.fromStock(), true) + 3;
        String wear = notWorn == 0 ? L.t("wynnlabs.craft.set_all_wear") : L.t("wynnlabs.craft.set_not_worn", notWorn);
        right -= tag(g, right, y + 3, wear, notWorn == 0, true) + 3;
        int tx = ix;
        if (locked) {
            Art.icon(g, "lock", tx, y + 4, 8, Theme.paperRgb(Theme.BAD));
            tx += 10;
        }
        text(g, Component.literal(fit(setTitle(s), right - tx - 4)).withStyle(UiFont::bold), tx, y + 4, Theme.INK);
        String why = L.t("wynnlabs.craft.suggest_tier." + s.tier().name().toLowerCase(Locale.ROOT));
        fine(g, why, ix, y + 15, Theme.ACCENT);
        // the pieces, two columns
        int py = y + 25;
        int colW = (w - 12 - 6) / 2;
        int rowsH = Math.max(8, Math.min(10, (h - 25 - 34) / 5));
        for (int i = 0; i < s.pieces().size(); i++) {
            SetSuggestions.Piece pc = s.pieces().get(i);
            int px = ix + (i / 5) * (colW + 6);
            int ry = py + (i % 5) * rowsH;
            boolean pl = pc.layout() != null && pieceLocked(pc);
            Art.icon(g, typeIcon(pc.type()), px, ry, 8, Theme.paperRgb(pc.layout() == null ? Theme.INK_3 : Theme.INK_2));
            String nm = typeName(pc.type());
            fine(g, fitFine(nm, colW / 2), px + 10, ry + 1, pc.layout() == null ? Theme.INK_3 : pl ? Theme.BAD : Theme.INK);
            String v = pieceValue(s, pc);
            fine(g, fitFine(v, colW / 2 - 4), px + colW - fineWidth(fitFine(v, colW / 2 - 4)), ry + 1,
                    pc.layout() == null ? Theme.INK_3 : Theme.GOOD);
        }
        // totals and the highest requirement
        int ty = py + 5 * rowsH + 2;
        StringBuilder tot = new StringBuilder();
        int shownIds = 0;
        for (String id : s.goal().goal().weights().keySet()) {
            Craft.Range v = s.totals().get(id);
            if (v == null || shownIds == 3) {
                continue;
            }
            tot.append(tot.isEmpty() ? "" : " · ").append(tab.statName(id)).append(' ').append(range(v, tab.unit(id)));
            shownIds++;
        }
        fine(g, fitFine(L.t("wynnlabs.craft.set_total") + " " + (tot.isEmpty() ? "—" : tot), w - 12), ix, ty, Theme.INK);
        int maxSkill = -1;
        for (int k = 0; k < 5; k++) {
            if (s.maxRequirements()[k] > 0 && (maxSkill < 0 || s.maxRequirements()[k] > s.maxRequirements()[maxSkill])) {
                maxSkill = k;
            }
        }
        String req = maxSkill < 0 ? L.t("wynnlabs.craft.set_no_req")
                : L.t("wynnlabs.craft.set_req", skill(maxSkill), s.maxRequirements()[maxSkill], tab.points[maxSkill]);
        boolean reqOk = maxSkill < 0 || tab.points[maxSkill] >= s.maxRequirements()[maxSkill];
        fine(g, fitFine(req, w - 12), ix, ty + 9, reqOk ? Theme.INK_2 : Theme.BAD);
        // cost and buttons
        long[] c = setCost(s);
        String cost = s.bought().isEmpty() ? L.t("wynnlabs.craft.cost_none")
                : costLine(costText(c[0], c[1] > 0, c[2] > 0));
        int by = y + h - 14;
        fine(g, cost, ix, by + 2, s.bought().isEmpty() ? Theme.GOOD : Theme.PRICE);
        String viewL = L.t("wynnlabs.craft.set_view");
        String saveL = L.t("wynnlabs.craft.save");
        int vw = width(viewL) + 10;
        int sw = width(saveL) + 10;
        int vx = x + w - 6 - vw;
        int sx = vx - 3 - sw;
        int vs = in(mx, my, vx, by, vw, 11) ? Kit.HOVER : Kit.ON;
        Kit.button(g, vx, by, vw, 11, vs);
        wood(g, viewL, vx + 5, by + 1, vs == Kit.ON ? Theme.INK : Theme.CREAM, vs != Kit.ON);
        int ss = in(mx, my, sx, by, sw, 11) ? Kit.HOVER : Kit.REST;
        Kit.button(g, sx, by, sw, 11, ss);
        wood(g, saveL, sx + 5, by + 1, Kit.buttonText(ss), true);
        tab.acts.add(new Act(vx, by, vw, 11, () -> tab.setDetail = s, null, List.of(L.c("wynnlabs.craft.set_view_tip"))));
        tab.acts.add(new Act(sx, by, sw, 11, () -> saveSet(s), null, List.of(L.c("wynnlabs.craft.set_save_tip"))));
    }

    /** The set's pieces in a list over the page: open or copy each, copy all codes, save all. */
    private void setDetail(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        SetSuggestions.CraftSet s = tab.setDetail;
        g.nextStratum();
        Kit.card(g, x, y, w, h, Theme.GOLD_DEEP);
        tab.overlayBox = new int[] {x, y, w, h};
        int ix = x + 8;
        text(g, Component.literal(setTitle(s)).withStyle(UiFont::bold), ix, y + 6, Theme.INK);
        // close, copy all, save all on the title line
        int bx = x + w - 8;
        String[][] bs = {{"x", "", "close"}, {"device-floppy", L.t("wynnlabs.craft.save"), "save"},
            {"copy", L.t("wynnlabs.craft.set_copy_all"), "codes"}};
        for (String[] b : bs) {
            int bw = b[1].isEmpty() ? 14 : width(b[1]) + 18;
            bx -= bw;
            int st = in(mx, my, bx, y + 4, bw, ROW_H) ? Kit.HOVER : Kit.REST;
            Kit.button(g, bx, y + 4, bw, ROW_H, st);
            Art.icon(g, b[0], bx + (b[1].isEmpty() ? 3 : 4), y + 6, 8, Kit.buttonText(st));
            if (!b[1].isEmpty()) {
                wood(g, b[1], bx + 14, y + 6, Kit.buttonText(st), true);
            }
            String what = b[2];
            tab.overlay.add(new Act(bx, y + 4, bw, ROW_H, () -> {
                switch (what) {
                    case "close" -> tab.setDetail = null;
                    case "save" -> saveSet(s);
                    default -> copySetCodes(s);
                }
            }, null, null));
            bx -= 3;
        }
        int ry = y + 22;
        int rowH = Math.max(14, Math.min(20, (h - 30) / s.pieces().size()));
        for (int i = 0; i < s.pieces().size(); i++) {
            SetSuggestions.Piece pc = s.pieces().get(i);
            boolean hover = in(mx, my, x + 4, ry, w - 8, rowH);
            Kit.row(g, x + 4, ry, w - 8, rowH, i, hover);
            int ty = ry + (rowH - 8) / 2;
            Art.icon(g, typeIcon(pc.type()), ix, ty, 8, Theme.paperRgb(Theme.INK_2));
            String nm = typeName(pc.type()) + (pc.recipe() == null ? "" : " " + pc.recipe().bracket());
            text(g, nm, ix + 11, ty, pc.layout() == null ? Theme.INK_3 : Theme.INK);
            if (pc.layout() == null) {
                fine(g, L.t("wynnlabs.craft.set_piece_none"), ix + 120, ty + 1, Theme.INK_3);
                ry += rowH;
                continue;
            }
            String v = pieceValue(s, pc);
            text(g, v, ix + 120, ty, Theme.GOOD);
            boolean pl = pieceLocked(pc);
            String note = pl ? L.t("wynnlabs.craft.suggest_locked", Professions.name(pc.recipe().profession()
                    .toUpperCase(Locale.ROOT)), pieceNeeds(pc))
                    : pc.layout().bought().isEmpty() ? L.t("wynnlabs.craft.suggest_in_stock")
                    : L.t("wynnlabs.craft.suggest_to_buy", pc.layout().bought().size());
            fine(g, note, ix + 210, ty + 1, pl ? Theme.BAD : pc.layout().bought().isEmpty() ? Theme.GOOD : Theme.PRICE);
            String openL = L.t("wynnlabs.craft.suggest_open");
            String codeL = L.t("wynnlabs.craft.code");
            int ow = width(openL) + 10;
            int cw = width(codeL) + 10;
            int ox = x + w - 10 - ow;
            int cx = ox - 3 - cw;
            int by = ry + (rowH - 11) / 2;
            int os = in(mx, my, ox, by, ow, 11) ? Kit.HOVER : Kit.ON;
            Kit.button(g, ox, by, ow, 11, os);
            wood(g, openL, ox + 5, by + 1, os == Kit.ON ? Theme.INK : Theme.CREAM, os != Kit.ON);
            int cs = in(mx, my, cx, by, cw, 11) ? Kit.HOVER : Kit.REST;
            Kit.button(g, cx, by, cw, 11, cs);
            wood(g, codeL, cx + 5, by + 1, Kit.buttonText(cs), true);
            List<Component> tip = new ArrayList<>();
            for (Ingredient in : pc.layout().slots()) {
                tip.add(Component.literal(in.empty() ? "—" : in.name() + (tab.owned(in.name()) > 0 ? " ×" + tab.owned(in.name()) : ""))
                        .withStyle(in.empty() ? ChatFormatting.GRAY : ChatFormatting.WHITE));
            }
            SetSuggestions.Piece piece = pc;
            tab.overlay.add(new Act(ox, by, ow, 11, () -> {
                openPiece(s, piece);
                tab.setDetail = null;
            }, null, List.of(L.c("wynnlabs.craft.suggest_open_tip"))));
            tab.overlay.add(new Act(cx, by, cw, 11, () -> {
                Minecraft.getInstance().keyboardHandler.setClipboard(pieceCode(piece));
                tab.flash.accept(L.t("wynnlabs.craft.code_copied"));
            }, null, null));
            tab.overlay.add(new Act(x + 4, ry, w - 8, rowH, null, null, tip));
            ry += rowH;
        }
    }

    private static int pieceNeeds(SetSuggestions.Piece pc) {
        int n = pc.recipe().minLevel();
        for (Ingredient in : pc.layout().slots()) {
            n = Math.max(n, in.empty() ? 0 : in.level());
        }
        return n;
    }

    private RecipeCode.Recipe pieceRecipe(SetSuggestions.Piece pc) {
        List<String> names = new ArrayList<>();
        for (Ingredient in : pc.layout().slots()) {
            names.add(in.empty() ? null : in.name());
        }
        return new RecipeCode.Recipe(pc.recipe().type(), pc.recipe().minLevel(), pc.tier1(), pc.tier2(), Craft.Speed.NORMAL,
                names);
    }

    private String pieceCode(SetSuggestions.Piece pc) {
        RecipeCode.Recipe r = pieceRecipe(pc);
        String code = WynnBuilderCode.encode(r);
        return code != null ? code : RecipeCode.encode(r);
    }

    private void copySetCodes(SetSuggestions.CraftSet s) {
        List<String> lines = new ArrayList<>();
        for (SetSuggestions.Piece pc : s.pieces()) {
            if (pc.layout() != null) {
                lines.add(typeName(pc.type()) + ": " + pieceCode(pc));
            }
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(String.join("\n", lines));
        tab.flash.accept(L.n("wynnlabs.craft.set_codes_copied", lines.size()));
    }

    private void openPiece(SetSuggestions.CraftSet s, SetSuggestions.Piece pc) {
        tab.type = pc.recipe().type();
        tab.minLevel = pc.recipe().minLevel();
        tab.tier1 = pc.tier1();
        tab.tier2 = pc.tier2();
        tab.speed = Craft.Speed.NORMAL;
        for (int i = 0; i < Craft.SLOTS; i++) {
            Ingredient in = pc.layout().slots().get(i);
            tab.slots[i] = in.empty() ? null : in.name();
        }
        tab.weights.clear();
        tab.weights.putAll(s.goal().goal().weights());
        tab.view = 0;
        tab.scroll = 0;
        tab.flash.accept(L.t("wynnlabs.craft.applied"));
    }

    /** Saves every piece as "Spell set – Helmet" …; the second ring only once. */
    private void saveSet(SetSuggestions.CraftSet s) {
        SavedRecipes saved = Crafting.saved();
        String goal = L.t("wynnlabs.craft.goalset." + s.goal().key());
        java.util.Set<String> done = new java.util.HashSet<>();
        int n = 0;
        for (SetSuggestions.Piece pc : s.pieces()) {
            if (pc.layout() == null || !done.add(pc.type())) {
                continue;
            }
            RecipeCode.Recipe r = pieceRecipe(pc);
            String name = saved.free(L.t("wynnlabs.craft.set_piece_name", goal, typeName(pc.type())));
            saved.save(new SavedRecipes.Saved(name, r.type(), r.minLevel(), r.tier1(), r.tier2(), r.speed().name(),
                    java.util.Collections.unmodifiableList(new ArrayList<>(r.slots())),
                    SavedRecipes.SavedGoal.of(s.goal().goal()), System.currentTimeMillis()), false);
            n++;
        }
        Crafting.persist();
        tab.flash.accept(L.n("wynnlabs.craft.set_saved", n));
    }
}
