package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.Slot;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The build window's PICK view: the search box, the type pills and the level range over the
 * list of items the class can use, the tuning strip, the chosen chips and "Suggest build"; a
 * loading view in the list's place while the search runs. It owns the list's filters and scroll.
 */
final class BuildPickView {

    private static final int ROW_H = 18;

    private final BuildWindow win;
    private final BuildControls ui;

    /** 0 all; then the weapon, helmet, chestplate, leggings, boots, ring, bracelet, necklace. */
    int type;
    int listScroll;
    private List<ItemData> catalogue = List.of();
    private List<ItemData> shown = List.of();
    private String shownFor = "\u0000";
    private int catalogueLevel = -1;
    final ScrollBar listBar = new ScrollBar();

    BuildPickView(BuildWindow win, BuildControls ui) {
        this.win = win;
        this.ui = ui;
    }

    private static final String[] TYPE_KEYS = {"wynnlabs.pick.type_all", null, "wynnlabs.road.slot_helmet",
        "wynnlabs.road.slot_chestplate", "wynnlabs.road.slot_leggings", "wynnlabs.road.slot_boots", "wynnlabs.road.slot_ring",
        "wynnlabs.road.slot_bracelet", "wynnlabs.road.slot_necklace"};
    private static final Slot[] TYPE_SLOTS = {null, null, Slot.HELMET, Slot.CHESTPLATE, Slot.LEGGINGS, Slot.BOOTS, Slot.RING,
        Slot.BRACELET, Slot.NECKLACE};

    /** The type pill's name: the class's weapon type for the second ("Relik"). */
    private static String typeName(int i) {
        if (i == 1) {
            Slot wpn = Slot.weaponOf(GameState.current().classType());
            return wpn == null ? L.t("wynnlabs.road.slot_weapon") : BuildText.nice(wpn.name());
        }
        return L.t(TYPE_KEYS[i]);
    }

    /** The level ranges of the list's range button: all, up to your level, 1–50, 51–80, 81–105, 106 and up. */
    static final int[][] RANGES = {{1, 999}, {1, -1}, {1, 50}, {51, 80}, {81, 105}, {106, 999}};
    /** The range picked (0: all levels, the default). */
    int range;

    /** A range's label: "All", "≤ Lv 44", "1–50" … */
    private static String rangeText(int i) {
        if (i == 0) {
            return L.t("wynnlabs.pick.range_all");
        }
        if (i == 1) {
            return L.t("wynnlabs.pick.range_mine", GameState.current().level());
        }
        int[] r = RANGES[i];
        return r[1] >= 999 ? r[0] + "+" : r[0] + "–" + r[1];
    }

    /**
     * Every piece the class can use at any level, from the item database (never your bag or
     * bank), highest level first; the type pill, the level range and the win.search narrow it.
     */
    private List<ItemData> catalogue() {
        GameState state = GameState.current();
        int level = state.level();
        if (level != catalogueLevel || catalogue.isEmpty()) {
            Klass cls = state.classType();
            List<ItemData> all = new ArrayList<>(state.allGear().filter(i -> Locks.usable(i, cls)).toList());
            all.sort((a, b) -> a.level() != b.level() ? Integer.compare(b.level(), a.level()) : a.name().compareTo(b.name()));
            catalogue = all;
            catalogueLevel = level;
            shownFor = "\u0000";
        }
        String key = type + "|" + range + "|" + win.search.getValue().toLowerCase(Locale.ROOT).strip();
        if (!key.equals(shownFor)) {
            String q = win.search.getValue().toLowerCase(Locale.ROOT).strip();
            int lo = RANGES[range][0];
            int hi = RANGES[range][1] < 0 ? level : RANGES[range][1];
            List<ItemData> out = new ArrayList<>();
            for (ItemData i : catalogue) {
                if (type == 1 && !i.slot().isWeapon() || type > 1 && i.slot() != TYPE_SLOTS[type]) {
                    continue;
                }
                if (i.level() < lo || i.level() > hi) {
                    continue;
                }
                if (!q.isEmpty() && !i.name().toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
                out.add(i);
            }
            shown = out;
            shownFor = key;
        }
        return shown;
    }

    void drawPick(GuiGraphics g, BuildWindow.Host host, int x, int y, int titleW, int w, int h, int mx, int my, float pt) {
        int bottom = y + h;
        Component title = Component.literal(ui.fit(L.t("wynnlabs.pick.title"), titleW - 12, true)).withStyle(UiFont::bold);
        Art.icon(g, "target", x, y, 8, BuildControls.CHOSEN);
        Kit.text(g, ui.font, title, x + 11, y, Theme.INK);
        int cy = y + 14;
        // the win.search box
        Kit.field(g, x, cy, w, 12, win.search.isFocused());
        win.search.setX(x + 4);
        win.search.setY(cy + 2);
        win.search.setWidth(w - 8);
        win.search.render(g, mx, my, pt);
        if (win.search.getValue().isEmpty() && !win.search.isFocused()) {
            Kit.text(g, ui.font, ui.fit(L.t("wynnlabs.pick.search_hint"), w - 8, false), x + 4, cy + 2, Theme.INK_3);
        }
        ui.btn(x, cy, w, 12, "search", null);
        cy += 16;
        // the type pills, on as many lines as they need
        int px = x;
        for (int i = 0; i < TYPE_KEYS.length; i++) {
            String t = typeName(i);
            int tw = ui.font.width(t) + 10;
            if (px + tw > x + w && px > x) {
                px = x;
                cy += 14;
            }
            boolean on = i == type;
            boolean hov = BuildControls.in(mx, my, px, cy, tw, 12);
            Kit.button(g, px, cy, tw, 12, on ? Kit.ON : hov ? Kit.HOVER : Kit.REST);
            g.drawString(ui.font, t, px + 5, cy + 2, Kit.buttonText(on ? Kit.ON : Kit.REST), false);
            ui.btn(px, cy, tw, 12, "type", i);
            px += tw + 3;
        }
        cy += 15;
        // what the list holds: your level and class
        GameState state = GameState.current();
        Slot wpn = Slot.weaponOf(state.classType());
        Locks.Problem problem = Locks.problem(win.chosen, BuildWindow.planLevel(win.chosen));
        // the level range on the note's line, right: a button that steps through the ranges
        String rt = rangeText(range);
        int rw = ui.font.width(rt) + 23;
        boolean rh = BuildControls.in(mx, my, x + w - rw, cy - 2, rw, 13);
        int rs = range > 0 ? Kit.ON : rh ? Kit.HOVER : Kit.REST;
        Kit.button(g, x + w - rw, cy - 2, rw, 13, rs);
        Art.icon(g, "filter", x + w - rw + 6, cy + 1, 8, Kit.buttonText(rs));
        g.drawString(ui.font, rt, x + w - rw + 17, cy + 1, Kit.buttonText(rs), false);
        ui.btn(x + w - rw, cy - 2, rw, 13, "range", null);
        List<Component> rtip = new ArrayList<>();
        rtip.add(L.c("wynnlabs.pick.range_tip").withStyle(ChatFormatting.WHITE));
        for (int i = 0; i < RANGES.length; i++) {
            rtip.add(Component.literal((i == range ? "▶ " : "   ") + rangeText(i)).withStyle(i == range ? ChatFormatting.GOLD : ChatFormatting.GRAY));
        }
        host.tip(x + w - rw, cy - 2, rw, 13, rtip);
        int noteW = w - rw - 6;
        String note = problem != null ? L.t("wynnlabs.pick.problem", problem.text())
                : L.t("wynnlabs.pick.note", wpn == null ? "?" : BuildText.nice(wpn.name()));
        if (problem == null && ui.font.width(note) > noteW) {
            note = L.t("wynnlabs.pick.note_short", wpn == null ? "?" : BuildText.nice(wpn.name()));
        }
        Kit.text(g, ui.font, ui.fit(note, noteW, false), x, cy, problem != null ? Theme.BAD : Theme.INK_3);
        cy += 13;
        // the bottom: the chips row with Suggest, the strip over it
        int chipsY = bottom - 14;
        int stripH = stripHeight(w);
        int stripY = chipsY - 4 - stripH;
        int listY = cy;
        int listH = stripY - 4 - listY;
        if (Around.running() && win.awaitGen >= 0 || Around.previewLoading != null) {
            drawLoading(g, host, x, listY, w, listH, mx, my);
        } else {
            drawList(g, host, x, listY, w, listH, mx, my);
        }
        strip(g, host, x, stripY, w, mx, my, true);
        drawChips(g, host, x, chipsY, w, problem, mx, my);
    }

    /** The item list: a row per piece (icon, name in its tier's colour, type and level), win.chosen ones lit purple. */
    private void drawList(GuiGraphics g, BuildWindow.Host host, int x, int y, int w, int h, int mx, int my) {
        List<ItemData> items = catalogue();
        Kit.outline(g, x, y, w, h, Theme.LINE);
        int rows = Math.max(1, (h - 2) / ROW_H);
        int max = Math.max(0, items.size() - rows);
        listScroll = Math.max(0, Math.min(listScroll, max));
        if (items.isEmpty()) {
            Kit.text(g, ui.font, ui.fit(L.t("wynnlabs.pick.none"), w - 12, false), x + 6, y + 6, Theme.INK_3);
            return;
        }
        int rw = w - 2 - (max > 0 ? ScrollBar.W + 2 : 0);
        for (int i = 0; i < rows && listScroll + i < items.size(); i++) {
            ItemData it = items.get(listScroll + i);
            int ry = y + 1 + i * ROW_H;
            boolean on = win.chosen.stream().anyMatch(c -> c.name().equals(it.name()));
            boolean hov = BuildControls.in(mx, my, x + 1, ry, rw, ROW_H);
            Kit.row(g, x + 1, ry, rw, ROW_H, i, hov);
            if (on) {
                g.fill(x + 1, ry, x + rw + 1, ry + ROW_H, 0x335A3A8A);
                g.fill(x + 1, ry, x + 3, ry + ROW_H, BuildControls.CHOSEN);
            }
            ItemStack icon = host.icon(it);
            if (!icon.isEmpty()) {
                g.renderItem(icon, x + 4, ry + 1);
            }
            String meta = BuildText.typeOf(it) + " · Lv " + it.level();
            int metaW = ui.font.width(meta);
            int nx = x + 24;
            int right = x + 1 + rw - 4;
            if (on) {
                Art.icon(g, "check", right - 8, ry + 5, 8, BuildControls.CHOSEN);
                right -= 11;
            }
            int nameRoom = right - metaW - 6 - nx;
            if (nameRoom < 40) {
                meta = "Lv " + it.level();
                metaW = ui.font.width(meta);
                nameRoom = right - metaW - 6 - nx;
            }
            Kit.text(g, ui.font, Component.literal(ui.fit(it.name(), nameRoom, false)).withStyle(it.tier().colour()), nx, ry + 5, Theme.INK);
            Kit.text(g, ui.font, meta, right - metaW, ry + 5, Theme.INK_3);
            ui.btn(x + 1, ry, rw, ROW_H, "item", it);
            if (DevHooks.ON) {
                DevHooks.part("pickrow", g.pose(), x + 1, ry, rw, ROW_H);
            }
            // the item's own tooltip (stats, ranges, requirements) with what a click does under it
            host.item(x + 1, ry, rw, ROW_H, it, icon, List.of(
                    L.c(on ? "wynnlabs.pick.row_off" : "wynnlabs.pick.row_on").withStyle(ChatFormatting.DARK_GRAY)));
        }
        // the scrollbar: drag its thumb, click its track for a page (ScrollBar)
        listBar.place(x + w - ScrollBar.W - 1, y + 1, h - 2, rows, items.size(), listScroll);
        listBar.draw(g, mx, my);
        if (listBar.shown()) {
            int[] t = listBar.track();
            ui.btn(t[0], t[1], t[2], t[3], "scrollbar", null);
        }
    }

    /**
     * While the win.search runs, in the list's place: "Building around Bullseye…", a spinner, the
     * phase the engine is in, a bar of the milestones done with the sets tried, the best score so
     * far and Cancel. The preview freezes it (Around.previewLoading).
     */
    private void drawLoading(GuiGraphics g, BuildWindow.Host host, int x, int y, int w, int h, int mx, int my) {
        Kit.outline(g, x, y, w, h, Theme.LINE);
        Roadmap.Job j = Around.progress();
        double[] pv = Around.previewLoading;
        double frac = pv != null ? pv[0] : j == null ? 0 : j.done / (double) Math.max(1, j.total);
        int phase = pv != null ? (int) pv[1] : j == null ? 0 : j.phase;
        long tried = pv != null ? (long) pv[2] : j == null ? 0 : j.tried.get();
        double best = pv != null ? pv[3] : j == null ? Double.NaN : j.best;
        List<String> forNames = pv != null ? BuildWindow.names(win.chosen) : Around.runningFor();
        String who = forNames.isEmpty() ? L.t("wynnlabs.pick.loading_none") : L.t("wynnlabs.pick.loading", String.join(", ", forNames));
        int cx = x + w / 2;
        // the lines that fit, top to bottom: the title, the phase, the bar, the count, the best, Cancel
        boolean hasBest = !Double.isNaN(best) && best > 0;
        boolean showPhase = h >= 50;
        boolean showCount = h >= 62;
        boolean showBest = hasBest && h >= 74;
        int need = 14 + (showPhase ? 14 : 0) + 9 + (showCount ? 11 : 0) + (showBest ? 11 : 0) + 15;
        int ty = y + Math.max(3, (h - need) / 2);
        Component t = Component.literal(ui.fit(who, w - 40, true)).withStyle(UiFont::bold);
        int tw = ui.font.width(t);
        spinner(g, cx - (tw + 14) / 2 + 4, ty + 4, pv != null);
        Kit.text(g, ui.font, t, cx - (tw + 14) / 2 + 14, ty, BuildControls.CHOSEN);
        int ly = ty + 14;
        if (showPhase) {
            String ph = L.t(PHASES[Math.max(0, Math.min(PHASES.length - 1, phase))]);
            Kit.text(g, ui.font, ui.fit(ph, w - 16, false), cx - Math.min(ui.font.width(ph), w - 16) / 2, ly, Theme.INK_2);
            ly += 14;
        }
        int bw = Math.min(220, w - 24);
        int bx = cx - bw / 2;
        Kit.bar(g, bx, ly, bw, 5, Math.max(0, Math.min(1, frac)), 0xFFB79FE0, 0xFF8A6CC0, 0xFF5A3A8A, false);
        ly += 9;
        if (showCount) {
            String count = L.n("wynnlabs.pick.tried", tried, String.format(Locale.ROOT, "%,d", tried));
            Kit.text(g, ui.font, ui.fit(count, w - 16, false), cx - Math.min(ui.font.width(count), w - 16) / 2, ly, Theme.INK_3);
            ly += 11;
        }
        if (showBest) {
            String b = L.t("wynnlabs.pick.best_so_far", String.format(Locale.ROOT, "%,d", Math.round(best)));
            Kit.text(g, ui.font, ui.fit(b, w - 16, false), cx - Math.min(ui.font.width(b), w - 16) / 2, ly, Theme.INK_3);
            ly += 11;
        }
        int cby = Math.min(y + h - 15, ly + 2);
        ui.control(g, host, cx - BuildControls.ICON_W / 2, cby, 14, "x", L.t("wynnlabs.pick.cancel"), BuildControls.WOOD, false, 0, "cancelRun", null,
                List.of(L.c("wynnlabs.pick.cancel_tip").withStyle(ChatFormatting.GRAY)), mx, my);
    }

    /**
     * A spinner of eight dots on a ring, one bright and two fading behind it, turning by the
     * clock (an eighth every 90 ms); still in the preview.
     */
    private static void spinner(GuiGraphics g, int cx, int cy, boolean still) {
        int head = still ? 0 : (int) (System.currentTimeMillis() / 90 % 8);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            int dx = (int) Math.round(Math.cos(a) * 4);
            int dy = (int) Math.round(Math.sin(a) * 4);
            int age = Math.floorMod(head - i, 8);
            int alpha = age == 0 ? 0xFF : age == 1 ? 0xB0 : age == 2 ? 0x70 : 0x30;
            g.fill(cx + dx - 1, cy + dy - 1, cx + dx + 1, cy + dy + 1, alpha << 24 | (BuildControls.CHOSEN & 0xFFFFFF));
        }
    }

    /** The engine's phases (Roadmap.Job#phase), as the loading view names them. */
    private static final String[] PHASES = {"wynnlabs.pick.phase_items", "wynnlabs.pick.phase_slots", "wynnlabs.pick.phase_sets",
        "wynnlabs.pick.phase_points", "wynnlabs.pick.phase_budget", "wynnlabs.pick.phase_rank"};
    private static final String[] FOCUS_TIPS = {"wynnlabs.pick.focus_damage_tip", "wynnlabs.pick.focus_balanced_tip",
        "wynnlabs.pick.focus_tanky_tip"};

    // ---- the win.tuning strip ----

    /**
     * The strip's parts as {icon, label, tooltip key, action, arg, on, text}: the three foci as
     * icons (sword, scale, shield), Mana (a drop), Speed (a shoe), and the budget (a coin with
     * its amount, "∞" for no limit). Only the budget shows text.
     */
    private List<Object[]> stripParts(Tuning.Choice t) {
        List<Object[]> out = new ArrayList<>();
        String[] foci = {"wynnlabs.pick.focus_damage", "wynnlabs.pick.focus_balanced", "wynnlabs.pick.focus_tanky"};
        String[] icons = {"sword", "scale", "shield"};
        int[] order = {Tuning.DAMAGE, Tuning.BALANCED, Tuning.TANKY};
        for (int i = 0; i < 3; i++) {
            out.add(new Object[] {icons[i], L.t(foci[i]), FOCUS_TIPS[i], "focus", order[i], t.focus() == order[i], null});
        }
        out.add(new Object[] {"droplet", L.t("wynnlabs.pick.mana"), "wynnlabs.pick.mana_tip", "mana", null, t.mana(), null});
        out.add(new Object[] {"shoe", L.t("wynnlabs.pick.speed"), "wynnlabs.pick.speed_tip", "speed", null, t.speed(), null});
        out.add(new Object[] {"coin", L.t("wynnlabs.pick.budget", BuildText.budgetText(t.budget())), "wynnlabs.pick.budget_tip", "budget", null,
            t.budget() > 0, t.budget() <= 0 ? "∞" : Prices.fmt(t.budget())});
        return out;
    }

    /** The strip's height: one row of buttons. */
    private int stripHeight(int w) {
        return 14;
    }

    /**
     * One row: Damage / Balanced / Tanky as one segmented control of icons, then Mana and Speed,
     * then the budget; each a wood button (gold when on) whose tooltip names it. {@code live}: clickable.
     */
    private void strip(GuiGraphics g, BuildWindow.Host host, int x, int y, int w, int mx, int my, boolean live) {
        List<Object[]> parts = stripParts(win.tuning);
        int px = x;
        for (int i = 0; i < parts.size(); i++) {
            Object[] p = parts.get(i);
            if (i == 3 || i == 5) {
                px += 5;                                    // apart from the segmented control and the toggles
            }
            String icon = (String) p[0];
            String text = (String) p[6];
            boolean on = (boolean) p[5];
            int bw = text == null ? BuildControls.ICON_W : 11 + ui.font.width(text) + 12;
            boolean hov = live && BuildControls.in(mx, my, px, y, bw, 14);
            int state = on ? Kit.ON : hov ? Kit.HOVER : Kit.REST;
            Kit.button(g, px, y, bw, 14, state);
            if (text == null) {
                Art.icon(g, icon, px + (bw - 8) / 2, y + 3, 8, Kit.buttonText(state));
            } else {
                Art.icon(g, icon, px + 6, y + 3, 8, Kit.buttonText(state));
                g.drawString(ui.font, text, px + 17, y + 3, Kit.buttonText(state), false);
            }
            if (live) {
                ui.btn(px, y, bw, 14, (String) p[3], p[4]);
                host.tip(px, y, bw, 14, List.of(Component.literal((String) p[1]).withStyle(ChatFormatting.WHITE),
                        L.c((String) p[2]).withStyle(ChatFormatting.GRAY)));
            }
            px += bw + (i < 2 ? 1 : 3);
        }
    }

    /**
     * The win.chosen items as chips (a click takes one out), Clear (an eraser), and the purple
     * "Suggest build" on the right (its sparkles alone when the row is tight).
     */
    private void drawChips(GuiGraphics g, BuildWindow.Host host, int x, int y, int w, Locks.Problem problem, int mx, int my) {
        boolean can = !win.chosen.isEmpty() && problem == null || win.chosen.isEmpty();
        String sug = L.t("wynnlabs.pick.suggest");
        int sw = ui.controlW("sparkles", sug, w / 3);
        int sx = x + w - sw;
        List<Component> sugTip = List.of(L.c(can ? "wynnlabs.pick.suggest_tip" : "wynnlabs.pick.suggest_off").withStyle(ChatFormatting.GRAY));
        if (can) {
            ui.control(g, host, sx, y, 14, "sparkles", sug, BuildControls.PRIMARY, false, w / 3, "suggest", null, sugTip, mx, my);
        } else {
            ui.control(g, host, sx, y, 14, "sparkles", sug, BuildControls.WOOD, false, w / 3, null, null, sugTip, mx, my);
        }
        int cx = x;
        int room = sx - 6;
        if (win.chosen.isEmpty()) {
            Kit.text(g, ui.font, ui.fit(L.t("wynnlabs.pick.chips_none"), room - x, false), x, y + 3, Theme.INK_3);
            return;
        }
        int clearW = BuildControls.ICON_W;
        for (int i = 0; i < win.chosen.size(); i++) {
            ItemData it = win.chosen.get(i);
            int left = win.chosen.size() - i;
            String more = "+" + left;
            int moreW = ui.font.width(more) + 6;
            int cw = Math.min(ui.font.width(it.name()) + 18, 110);
            if (cx + cw + 3 + clearW + (left > 1 ? moreW + 3 : 0) > room) {
                Kit.text(g, ui.font, more, cx, y + 3, Theme.INK_2);
                cx += moreW;
                break;
            }
            BuildControls.chip(g, cx, y, cw, 14, BuildControls.in(mx, my, cx, y, cw, 14));
            Kit.text(g, ui.font, Component.literal(ui.fit(it.name(), cw - 18, false)).withStyle(it.tier().colour()), cx + 5, y + 3, Theme.INK);
            Art.icon(g, "x", cx + cw - 11, y + 3, 8, BuildControls.CHOSEN);
            ui.btn(cx, y, cw, 14, "unchoose", it);
            host.tip(cx, y, cw, 14, List.of(Component.literal(it.name()).withStyle(it.tier().colour()),
                    L.c("wynnlabs.pick.chip_tip").withStyle(ChatFormatting.DARK_GRAY)));
            cx += cw + 3;
        }
        ui.control(g, host, cx, y, 14, "eraser", L.t("wynnlabs.pick.clear"), BuildControls.WOOD, false, 0, "clear", null,
                List.of(L.c("wynnlabs.pick.clear_tip").withStyle(ChatFormatting.GRAY)), mx, my);
    }
}
