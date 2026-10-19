package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.model.Slot;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The gear panel's window over the menu (a dimmed menu, a parchment page in its middle) with three
 * views: PICK, the items to build around from the game's whole item database (search, type
 * pills, the list of what the character can use, the tuning strip, the chosen chips and "Suggest
 * build", with a loading view while the search runs); RESULTS, the ranked builds with the
 * selected one's details and "Apply build N"; SAVED, the saved builds with Apply, Edit, Rename and
 * Delete. Esc closes a rename or the window, never the menu. Nothing here acts in the game: the
 * search reads the item data and your gear, Apply only changes the plan the panel shows.
 */
final class BuildWindow {

    static final BuildWindow INSTANCE = new BuildWindow();

    /** The screen's side: tooltips, item icons, a flash line, and the plan applied. */
    interface Host {
        void tip(int x, int y, int w, int h, List<Component> tip);

        ItemStack icon(ItemData info);

        void flash(String text);

        /** A plan was applied (or changed): the panel selects its pill. */
        void applied();
    }

    enum View { PICK, RESULTS, SAVED }

    /** A click area drawn this frame: its box, what it does, and on what. */
    private record Btn(int x, int y, int w, int h, String act, Object arg) {}

    private static final int PAD = 8;
    private static final int ROW_H = 18;
    private static final int SAVED_ROW_H = 30;

    private Font font;
    private boolean open;
    private View view = View.PICK;
    private final List<Btn> btns = new ArrayList<>();
    private EditBox search;
    private EditBox rename;

    // ---- PICK ----
    /** 0 all; then the weapon, helmet, chestplate, leggings, boots, ring, bracelet, necklace. */
    private int type;
    private int listScroll;
    private List<ItemData> chosen = new ArrayList<>();
    private Tuning.Choice tuning = Tuning.Choice.PLAIN;
    private List<ItemData> catalogue = List.of();
    private List<ItemData> shown = List.of();
    private String shownFor = "\u0000";
    private int catalogueLevel = -1;
    /** The generation of the search the window waits for (-1 none). */
    private int awaitGen = -1;

    // ---- RESULTS ----
    private Around.Result result;
    private int card;
    /** A saved build the picked result overwrites (Recompute), or null. */
    private String overwriteId;

    // ---- SAVED ----
    private int savedScroll;
    private String renameId;
    private String editId;
    private List<String> editLocks = List.of();
    private Tuning.Choice editTuning = Tuning.Choice.PLAIN;

    private BuildWindow() {}

    // =====================================================================================
    // the screen's calls

    /** The window's text boxes, for the screen to add as widgets (init: again after a resize). */
    List<EditBox> init(Font f) {
        font = f;
        String typed = search == null ? "" : search.getValue();
        search = new EditBox(f, 0, 0, 100, 12, L.c("wynnlabs.pick.search"));
        search.setMaxLength(40);
        search.setValue(typed);
        search.setResponder(v -> listScroll = 0);
        paperField(search);
        String renamed = rename == null ? "" : rename.getValue();
        rename = new EditBox(f, 0, 0, 100, 12, L.c("wynnlabs.saved.rename"));
        rename.setMaxLength(Plans.MAX_NAME);
        rename.setValue(renamed);
        paperField(rename);
        return List.of(search, rename);
    }

    private static void paperField(EditBox box) {
        box.setBordered(false);
        box.setTextColor(Theme.INK);
        box.setTextShadow(false);
    }

    boolean isOpen() {
        return open;
    }

    View view() {
        return view;
    }

    /** "Choose items": the last results while they are for the same choice, else the list. */
    void openPick() {
        loadChoice();
        open = true;
        overwriteId = null;
        Around.Result r = Around.result();
        if (r != null && matches(r)) {
            result = r;
            card = Math.min(card, Math.max(0, r.builds().size() - 1));
            view = View.RESULTS;
        } else {
            view = View.PICK;
        }
        focusSearch(view == View.PICK);
    }

    /** "My builds". */
    void openSaved() {
        open = true;
        view = View.SAVED;
        renameId = null;
        focusSearch(false);
    }

    /** The better-build hint: its results. */
    void openResults(Around.Result r) {
        if (r == null) {
            return;
        }
        open = true;
        result = r;
        card = 0;
        overwriteId = null;
        chosen = new ArrayList<>(Locks.resolve(GameState.current(), r.around()));
        tuning = r.tuning();
        view = View.RESULTS;
        focusSearch(false);
    }

    void close() {
        open = false;
        renameId = null;
        focusSearch(false);
        rename.setFocused(false);
    }

    private void focusSearch(boolean on) {
        if (search != null) {
            search.setFocused(on);
        }
    }

    /** The text box that should have the keyboard, or null. */
    EditBox focused() {
        if (!open) {
            return null;
        }
        if (renameId != null && view == View.SAVED) {
            return rename;
        }
        return view == View.PICK && search.isFocused() ? search : null;
    }

    private void loadChoice() {
        chosen = new ArrayList<>(Locks.resolve(GameState.current(), Locks.names()));
        tuning = Locks.tuning();
    }

    /** Whether a result is for the window's choice and strip. */
    private boolean matches(Around.Result r) {
        return r.around().equals(names(chosen)) && r.tuning().equals(tuning);
    }

    private static List<String> names(List<ItemData> items) {
        return items.stream().map(ItemData::name).toList();
    }

    /** The choice or the strip changed: saved for the character, the old results and a running search dropped. */
    private void changed() {
        Locks.set(names(chosen));
        Locks.setTuning(tuning);
        Around.forget();
        if (Around.running() && awaitGen >= 0) {
            Around.cancel();
        }
        awaitGen = -1;
    }

    /** The click areas drawn this frame, for the preview's layout check. */
    List<LayoutCheck.Area> clickAreas() {
        List<LayoutCheck.Area> out = new ArrayList<>();
        for (Btn b : btns) {
            out.add(new LayoutCheck.Area(b.x(), b.y(), b.w(), b.h(), b.act() + (b.arg() == null ? "" : ":" + b.arg())));
        }
        return out;
    }

    // =====================================================================================
    // drawing

    /**
     * Draws the window over the menu {@code menu} (x, y, w, h), {@code large} for the large menu.
     */
    void draw(GuiGraphics g, int[] menu, boolean large, Host host, int mx, int my, float pt) {
        btns.clear();
        if (!open) {
            return;
        }
        font = UiFont.get();
        // a finished search for the window: its results
        Around.Result r = Around.result();
        if (awaitGen >= 0 && r != null && r.gen() == awaitGen) {
            awaitGen = -1;
            result = r;
            card = 0;
            view = View.RESULTS;
            focusSearch(false);
        }
        g.nextStratum();                                // over the menu's text and items
        LayoutCheck.cover(g.pose(), menu[0] - 5, menu[1] - 5, menu[2] + 10, menu[3] + 10);
        g.fill(menu[0] - 5, menu[1] - 5, menu[0] + menu[2] + 5, menu[1] + menu[3] + 5, 0xA0000000);
        int ww = Math.min(menu[2] - 16, large ? 600 : 460);
        int wh = Math.min(menu[3] - 14, large ? 420 : 320);
        int wx = menu[0] + (menu[2] - ww) / 2;
        int wy = menu[1] + (menu[3] - wh) / 2;
        Kit.window(g, wx - 5, wy - 5, ww + 10, wh + 10);
        Kit.paper(g, wx, wy, ww, wh);
        int x = wx + PAD;
        int y = wy + PAD;
        int w = ww - 2 * PAD;
        int h = wh - 2 * PAD;
        // the close seal, top right inside the page
        boolean hover = in(mx, my, x + w - 11, y - 1, 11, 11);
        Kit.close(g, x + w - 11, y - 1, 11, hover);
        btn(x + w - 11, y - 1, 11, 11, "close", null);
        host.tip(x + w - 11, y - 1, 11, 11, List.of(L.c("wynnlabs.pick.close_tip").withStyle(ChatFormatting.GRAY)));
        switch (view) {
            case PICK -> drawPick(g, host, x, y, w - 14, w, h, mx, my, pt);
            case RESULTS -> drawResults(g, host, x, y, w, h, mx, my);
            case SAVED -> drawSaved(g, host, x, y, w, h, mx, my, pt);
        }
    }

    // ---- PICK ----

    private static final String[] TYPE_KEYS = {"wynnlabs.pick.type_all", null, "wynnlabs.road.slot_helmet",
        "wynnlabs.road.slot_chestplate", "wynnlabs.road.slot_leggings", "wynnlabs.road.slot_boots", "wynnlabs.road.slot_ring",
        "wynnlabs.road.slot_bracelet", "wynnlabs.road.slot_necklace"};
    private static final Slot[] TYPE_SLOTS = {null, null, Slot.HELMET, Slot.CHESTPLATE, Slot.LEGGINGS, Slot.BOOTS, Slot.RING,
        Slot.BRACELET, Slot.NECKLACE};

    /** The type pill's name: the class's weapon type for the second ("Relik"). */
    private static String typeName(int i) {
        if (i == 1) {
            Slot wpn = Slot.weaponOf(GameState.current().classType());
            return wpn == null ? L.t("wynnlabs.road.slot_weapon") : nice(wpn.name());
        }
        return L.t(TYPE_KEYS[i]);
    }

    private static String nice(String upper) {
        return upper.charAt(0) + upper.substring(1).toLowerCase(Locale.ROOT);
    }

    /** Every piece the character can use, from the item database (never your bag or bank), highest level first. */
    private List<ItemData> catalogue() {
        GameState state = GameState.current();
        int level = state.level();
        if (level != catalogueLevel || catalogue.isEmpty()) {
            Klass cls = state.classType();
            List<ItemData> all = new ArrayList<>(state.allGear().filter(i -> Locks.usable(i, level, cls)).toList());
            all.sort((a, b) -> a.level() != b.level() ? Integer.compare(b.level(), a.level()) : a.name().compareTo(b.name()));
            catalogue = all;
            catalogueLevel = level;
            shownFor = "\u0000";
        }
        String key = type + "|" + search.getValue().toLowerCase(Locale.ROOT).strip();
        if (!key.equals(shownFor)) {
            String q = search.getValue().toLowerCase(Locale.ROOT).strip();
            List<ItemData> out = new ArrayList<>();
            for (ItemData i : catalogue) {
                if (type == 1 && !i.slot().isWeapon() || type > 1 && i.slot() != TYPE_SLOTS[type]) {
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

    private void drawPick(GuiGraphics g, Host host, int x, int y, int titleW, int w, int h, int mx, int my, float pt) {
        int bottom = y + h;
        Component title = Component.literal(fit(L.t("wynnlabs.pick.title"), titleW - 12, true)).withStyle(UiFont::bold);
        Art.icon(g, "target", x, y, 8, CHOSEN);
        Kit.text(g, font, title, x + 11, y, Theme.INK);
        int cy = y + 14;
        // the search box
        Kit.field(g, x, cy, w, 12, search.isFocused());
        search.setX(x + 4);
        search.setY(cy + 2);
        search.setWidth(w - 8);
        search.render(g, mx, my, pt);
        if (search.getValue().isEmpty() && !search.isFocused()) {
            Kit.text(g, font, fit(L.t("wynnlabs.pick.search_hint"), w - 8, false), x + 4, cy + 2, Theme.INK_3);
        }
        btn(x, cy, w, 12, "search", null);
        cy += 16;
        // the type pills, on as many lines as they need
        int px = x;
        for (int i = 0; i < TYPE_KEYS.length; i++) {
            String t = typeName(i);
            int tw = font.width(t) + 10;
            if (px + tw > x + w && px > x) {
                px = x;
                cy += 14;
            }
            boolean on = i == type;
            boolean hov = in(mx, my, px, cy, tw, 12);
            Kit.button(g, px, cy, tw, 12, on ? Kit.ON : hov ? Kit.HOVER : Kit.REST);
            g.drawString(font, t, px + 5, cy + 2, Kit.buttonText(on ? Kit.ON : Kit.REST), false);
            btn(px, cy, tw, 12, "type", i);
            px += tw + 3;
        }
        cy += 15;
        // what the list holds: your level and class
        GameState state = GameState.current();
        Slot wpn = Slot.weaponOf(state.classType());
        Locks.Problem problem = Locks.problem(chosen, state.level());
        String note = problem != null ? L.t("wynnlabs.pick.problem", problem.text())
                : L.t("wynnlabs.pick.note", state.level(), wpn == null ? "?" : nice(wpn.name()));
        Kit.text(g, font, fit(note, w, false), x, cy, problem != null ? Theme.BAD : Theme.INK_3);
        cy += 12;
        // the bottom: the chips row with Suggest, the strip over it
        int chipsY = bottom - 14;
        int stripH = stripHeight(w);
        int stripY = chipsY - 4 - stripH;
        int listY = cy;
        int listH = stripY - 4 - listY;
        if (Around.running() && awaitGen >= 0 || Around.previewLoading != null) {
            drawLoading(g, host, x, listY, w, listH, mx, my);
        } else {
            drawList(g, host, x, listY, w, listH, mx, my);
        }
        strip(g, host, x, stripY, w, mx, my, true);
        drawChips(g, host, x, chipsY, w, problem, mx, my);
    }

    /** The item list: a row per piece (icon, name in its tier's colour, type and level), chosen ones lit purple. */
    private void drawList(GuiGraphics g, Host host, int x, int y, int w, int h, int mx, int my) {
        List<ItemData> items = catalogue();
        Kit.outline(g, x, y, w, h, Theme.LINE);
        int rows = Math.max(1, (h - 2) / ROW_H);
        int max = Math.max(0, items.size() - rows);
        listScroll = Math.max(0, Math.min(listScroll, max));
        if (items.isEmpty()) {
            Kit.text(g, font, fit(L.t("wynnlabs.pick.none"), w - 12, false), x + 6, y + 6, Theme.INK_3);
            return;
        }
        int rw = w - 2 - (max > 0 ? 5 : 0);
        for (int i = 0; i < rows && listScroll + i < items.size(); i++) {
            ItemData it = items.get(listScroll + i);
            int ry = y + 1 + i * ROW_H;
            boolean on = chosen.stream().anyMatch(c -> c.name().equals(it.name()));
            boolean hov = in(mx, my, x + 1, ry, rw, ROW_H);
            Kit.row(g, x + 1, ry, rw, ROW_H, i, hov);
            if (on) {
                g.fill(x + 1, ry, x + rw + 1, ry + ROW_H, 0x335A3A8A);
                g.fill(x + 1, ry, x + 3, ry + ROW_H, CHOSEN);
            }
            ItemStack icon = host.icon(it);
            if (!icon.isEmpty()) {
                g.renderItem(icon, x + 4, ry + 1);
            }
            String meta = typeOf(it) + " · Lv " + it.level();
            int metaW = font.width(meta);
            int nx = x + 24;
            int right = x + 1 + rw - 4;
            if (on) {
                Art.icon(g, "check", right - 8, ry + 5, 8, CHOSEN);
                right -= 11;
            }
            int nameRoom = right - metaW - 6 - nx;
            if (nameRoom < 40) {
                meta = "Lv " + it.level();
                metaW = font.width(meta);
                nameRoom = right - metaW - 6 - nx;
            }
            Kit.text(g, font, Component.literal(fit(it.name(), nameRoom, false)).withStyle(it.tier().colour()), nx, ry + 5, Theme.INK);
            Kit.text(g, font, meta, right - metaW, ry + 5, Theme.INK_3);
            btn(x + 1, ry, rw, ROW_H, "item", it);
            host.tip(x + 1, ry, rw, ROW_H, List.of(Component.literal(it.name()).withStyle(it.tier().colour()).withStyle(UiFont::bold),
                    Component.literal(typeOf(it) + " · Lv " + it.level()).withStyle(ChatFormatting.GRAY),
                    L.c(on ? "wynnlabs.pick.row_off" : "wynnlabs.pick.row_on").withStyle(ChatFormatting.DARK_GRAY)));
        }
        if (max > 0) {
            int bh = Math.max(12, (h - 2) * rows / items.size());
            int by = y + 1 + (h - 2 - bh) * listScroll / max;
            g.fill(x + w - 5, y + 1, x + w - 3, y + h - 1, 0x22000000);
            g.fill(x + w - 5, by, x + w - 3, by + bh, 0x88000000);
        }
    }

    private static String typeOf(ItemData i) {
        Slot s = i.slot();
        return switch (s) {
            case HELMET -> L.t("wynnlabs.road.slot_helmet");
            case CHESTPLATE -> L.t("wynnlabs.road.slot_chestplate");
            case LEGGINGS -> L.t("wynnlabs.road.slot_leggings");
            case BOOTS -> L.t("wynnlabs.road.slot_boots");
            case RING -> L.t("wynnlabs.road.slot_ring");
            case BRACELET -> L.t("wynnlabs.road.slot_bracelet");
            case NECKLACE -> L.t("wynnlabs.road.slot_necklace");
            default -> nice(s.name());
        };
    }

    /**
     * While the search runs, in the list's place: "Building around Bullseye…", a spinner, the
     * phase the engine is in, a bar of the milestones done with the sets tried, the best score so
     * far and Cancel. The preview freezes it (Around.previewLoading).
     */
    private void drawLoading(GuiGraphics g, Host host, int x, int y, int w, int h, int mx, int my) {
        Kit.outline(g, x, y, w, h, Theme.LINE);
        Roadmap.Job j = Around.progress();
        double[] pv = Around.previewLoading;
        double frac = pv != null ? pv[0] : j == null ? 0 : j.done / (double) Math.max(1, j.total);
        int phase = pv != null ? (int) pv[1] : j == null ? 0 : j.phase;
        long tried = pv != null ? (long) pv[2] : j == null ? 0 : j.tried.get();
        double best = pv != null ? pv[3] : j == null ? Double.NaN : j.best;
        List<String> forNames = pv != null ? names(chosen) : Around.runningFor();
        String who = forNames.isEmpty() ? L.t("wynnlabs.pick.loading_none") : L.t("wynnlabs.pick.loading", String.join(", ", forNames));
        int cx = x + w / 2;
        int ty = y + Math.max(6, (h - 74) / 2);
        Component t = Component.literal(fit(who, w - 40, true)).withStyle(UiFont::bold);
        int tw = font.width(t);
        spinner(g, cx - (tw + 14) / 2, ty + 4, pv != null);
        Kit.text(g, font, t, cx - (tw + 14) / 2 + 14, ty, CHOSEN);
        String ph = L.t(PHASES[Math.max(0, Math.min(PHASES.length - 1, phase))]);
        Kit.text(g, font, fit(ph, w - 16, false), cx - Math.min(font.width(ph), w - 16) / 2, ty + 14, Theme.INK_2);
        int bw = Math.min(220, w - 24);
        int bx = cx - bw / 2;
        Kit.bar(g, bx, ty + 28, bw, 5, Math.max(0, Math.min(1, frac)), 0xFFB79FE0, 0xFF8A6CC0, 0xFF5A3A8A, false);
        String count = L.n("wynnlabs.pick.tried", tried, String.format(Locale.ROOT, "%,d", tried));
        Kit.text(g, font, fit(count, w - 16, false), cx - Math.min(font.width(count), w - 16) / 2, ty + 37, Theme.INK_3);
        if (!Double.isNaN(best) && best > 0) {
            String b = L.t("wynnlabs.pick.best_so_far", String.format(Locale.ROOT, "%,d", Math.round(best)));
            Kit.text(g, font, fit(b, w - 16, false), cx - Math.min(font.width(b), w - 16) / 2, ty + 48, Theme.INK_3);
        }
        String c = L.t("wynnlabs.pick.cancel");
        int cw = font.width(c) + 14;
        int cby = Math.min(y + h - 16, ty + 60);
        boolean hov = in(mx, my, cx - cw / 2, cby, cw, 13);
        Kit.button(g, cx - cw / 2, cby, cw, 13, hov ? Kit.HOVER : Kit.REST);
        g.drawString(font, c, cx - cw / 2 + 7, cby + 3, Kit.buttonText(Kit.REST), false);
        btn(cx - cw / 2, cby, cw, 13, "cancelRun", null);
        host.tip(cx - cw / 2, cby, cw, 13, List.of(L.c("wynnlabs.pick.cancel_tip").withStyle(ChatFormatting.GRAY)));
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
            g.fill(cx + dx - 1, cy + dy - 1, cx + dx + 1, cy + dy + 1, alpha << 24 | (CHOSEN & 0xFFFFFF));
        }
    }

    /** The engine's phases (Roadmap.Job#phase), as the loading view names them. */
    private static final String[] PHASES = {"wynnlabs.pick.phase_items", "wynnlabs.pick.phase_slots", "wynnlabs.pick.phase_sets",
        "wynnlabs.pick.phase_points", "wynnlabs.pick.phase_budget", "wynnlabs.pick.phase_rank"};
    private static final String[] FOCUS_TIPS = {"wynnlabs.pick.focus_damage_tip", "wynnlabs.pick.focus_balanced_tip",
        "wynnlabs.pick.focus_tanky_tip"};

    /** A label's pill text (or its tooltip line). */
    private static String labelKey(String label, boolean tip) {
        return switch (label) {
            case "best" -> tip ? "wynnlabs.results.label_best_tip" : "wynnlabs.results.label_best";
            case "cheap" -> tip ? "wynnlabs.results.label_cheap_tip" : "wynnlabs.results.label_cheap";
            case "tanky" -> tip ? "wynnlabs.results.label_tanky_tip" : "wynnlabs.results.label_tanky";
            default -> tip ? "wynnlabs.results.label_fast_tip" : "wynnlabs.results.label_fast";
        };
    }

    // ---- the tuning strip ----

    /** The strip's parts as {label, tooltip key, action, arg}: the three foci, Mana, Speed, the budget. */
    private List<Object[]> stripParts(Tuning.Choice t) {
        List<Object[]> out = new ArrayList<>();
        String[] foci = {"wynnlabs.pick.focus_damage", "wynnlabs.pick.focus_balanced", "wynnlabs.pick.focus_tanky"};
        int[] order = {Tuning.DAMAGE, Tuning.BALANCED, Tuning.TANKY};
        for (int i = 0; i < 3; i++) {
            out.add(new Object[] {L.t(foci[i]), FOCUS_TIPS[i], "focus", order[i], t.focus() == order[i]});
        }
        out.add(new Object[] {L.t("wynnlabs.pick.mana"), "wynnlabs.pick.mana_tip", "mana", null, t.mana()});
        out.add(new Object[] {L.t("wynnlabs.pick.speed"), "wynnlabs.pick.speed_tip", "speed", null, t.speed()});
        out.add(new Object[] {L.t("wynnlabs.pick.budget", budgetText(t.budget())), "wynnlabs.pick.budget_tip", "budget", null,
            t.budget() > 0});
        return out;
    }

    static String budgetText(long budget) {
        return budget <= 0 ? L.t("wynnlabs.pick.budget_none") : Prices.fmt(budget);
    }

    /** The strip's height at width {@code w}: one row of 12, or two when it does not fit. */
    private int stripHeight(int w) {
        return stripRows(w) == 1 ? 12 : 26;
    }

    private int stripRows(int w) {
        int total = 0;
        for (Object[] p : stripParts(tuning)) {
            total += font.width((String) p[0]) + 10 + 3;
        }
        return total + 6 <= w ? 1 : 2;
    }

    /**
     * One row (two when narrow: the foci on the first): Damage / Balanced / Tanky as one
     * segmented control, then Mana and Speed, then the budget. {@code live}: clickable.
     */
    private void strip(GuiGraphics g, Host host, int x, int y, int w, int mx, int my, boolean live) {
        List<Object[]> parts = stripParts(tuning);
        boolean two = stripRows(w) == 2;
        int px = x;
        int py = y;
        for (int i = 0; i < parts.size(); i++) {
            Object[] p = parts.get(i);
            if (two && i == 3) {
                px = x;
                py += 14;
            } else if (i == 3) {
                px += 6;                                    // a little space after the segmented control
            }
            String label = (String) p[0];
            int tw = font.width(label) + 10;
            boolean on = (boolean) p[4];
            boolean hov = live && in(mx, my, px, py, tw, 12);
            Kit.button(g, px, py, tw, 12, on ? Kit.ON : hov ? Kit.HOVER : Kit.REST);
            g.drawString(font, label, px + 5, py + 2, Kit.buttonText(on ? Kit.ON : Kit.REST), false);
            if (live) {
                btn(px, py, tw, 12, (String) p[2], p[3]);
                host.tip(px, py, tw, 12, List.of(L.c((String) p[1]).withStyle(ChatFormatting.GRAY)));
            }
            px += tw + (i < 2 ? 1 : 3);
        }
    }

    /** The chosen items as chips (✕ takes one out), "Clear", and "Suggest build" on the right. */
    private void drawChips(GuiGraphics g, Host host, int x, int y, int w, Locks.Problem problem, int mx, int my) {
        String sug = L.t("wynnlabs.pick.suggest");
        int sw = font.width(sug) + 18;
        int sx = x + w - sw;
        boolean can = !chosen.isEmpty() && problem == null || chosen.isEmpty();
        boolean hov = can && in(mx, my, sx, y, sw, 14);
        Art.box(g, sx, y, sw, 14, 3, !can ? 0xFF9C8FB0 : hov ? 0xFF6E4CA6 : CHOSEN, 0xFF3E2766);
        Art.icon(g, "sparkles", sx + 4, y + 3, 8, CHOSEN_TEXT);
        g.drawString(font, sug, sx + 14, y + 3, CHOSEN_TEXT, false);
        if (can) {
            btn(sx, y, sw, 14, "suggest", null);
        }
        host.tip(sx, y, sw, 14, List.of(L.c(can ? "wynnlabs.pick.suggest_tip" : "wynnlabs.pick.suggest_off").withStyle(ChatFormatting.GRAY)));
        int cx = x;
        int room = sx - 6;
        if (chosen.isEmpty()) {
            Kit.text(g, font, fit(L.t("wynnlabs.pick.chips_none"), room - x, false), x, y + 3, Theme.INK_3);
            return;
        }
        String clear = L.t("wynnlabs.pick.clear");
        int clearW = font.width(clear) + 10;
        for (int i = 0; i < chosen.size(); i++) {
            ItemData it = chosen.get(i);
            int left = chosen.size() - i;
            String more = "+" + left;
            int moreW = font.width(more) + 6;
            int cw = Math.min(font.width(it.name()) + 18, 110);
            if (cx + cw + 3 + clearW + (left > 1 ? moreW + 3 : 0) > room) {
                Kit.text(g, font, more, cx, y + 3, Theme.INK_2);
                cx += moreW;
                break;
            }
            Art.box(g, cx, y, cw, 14, 3, 0xFFE4D8F2, CHOSEN);
            Kit.text(g, font, Component.literal(fit(it.name(), cw - 18, false)).withStyle(it.tier().colour()), cx + 4, y + 3, Theme.INK);
            Art.icon(g, "x", cx + cw - 11, y + 3, 8, CHOSEN);
            btn(cx, y, cw, 14, "unchoose", it);
            host.tip(cx, y, cw, 14, List.of(Component.literal(it.name()).withStyle(it.tier().colour()),
                    L.c("wynnlabs.pick.chip_tip").withStyle(ChatFormatting.DARK_GRAY)));
            cx += cw + 3;
        }
        boolean ch = in(mx, my, cx, y, clearW, 14);
        Kit.button(g, cx, y, clearW, 14, ch ? Kit.HOVER : Kit.REST);
        g.drawString(font, clear, cx + 5, y + 3, Kit.buttonText(Kit.REST), false);
        btn(cx, y, clearW, 14, "clear", null);
        host.tip(cx, y, clearW, 14, List.of(L.c("wynnlabs.pick.clear_tip").withStyle(ChatFormatting.GRAY)));
    }

    // ---- RESULTS ----

    private static final int CHOSEN = HomeTab.CHOSEN;
    private static final int CHOSEN_TEXT = HomeTab.CHOSEN_TEXT;

    private void drawResults(GuiGraphics g, Host host, int x, int y, int w, int h, int mx, int my) {
        Around.Result r = result;
        // the header: back to the items, the count found around them
        String back = "‹ " + L.t("wynnlabs.results.back");
        int bw = font.width(back) + 10;
        boolean bh = in(mx, my, x, y - 2, bw, 13);
        Kit.button(g, x, y - 2, bw, 13, bh ? Kit.HOVER : Kit.REST);
        g.drawString(font, back, x + 5, y + 1, Kit.buttonText(Kit.REST), false);
        btn(x, y - 2, bw, 13, "back", null);
        int n = r == null ? 0 : r.builds().size();
        String around = r == null || r.around().isEmpty() ? null : String.join(", ", r.around());
        String head = around == null ? L.n("wynnlabs.results.found_plain", n, n) : L.n("wynnlabs.results.found", n, n, around);
        Kit.text(g, font, Component.literal(fit(head, w - bw - 24, true)).withStyle(UiFont::bold), x + bw + 6, y, Theme.INK);
        int top = y + 16;
        int footY = y + h - 14;
        if (r == null || r.builds().isEmpty()) {
            String why = r != null && r.problem() != null ? L.t("wynnlabs.pick.problem", r.problem().text())
                    : r != null && r.tuning().budget() > 0 ? L.t("wynnlabs.results.none_budget", budgetText(r.tuning().budget()))
                    : L.t("wynnlabs.results.none");
            Kit.text(g, font, fit(why, w, false), x, top + 4, Theme.BAD);
            footer(g, host, x, footY, w, mx, my, false);
            return;
        }
        card = Math.max(0, Math.min(card, n - 1));
        int bodyH = footY - 4 - top;
        boolean two = w >= 380;
        if (two) {
            int lw = Math.max(150, Math.min(220, w * 40 / 100));
            int ch = Math.min(46, (bodyH - (n - 1) * 3) / n);
            for (int i = 0; i < n; i++) {
                cardBox(g, host, r.builds().get(i), i, x, top + i * (ch + 3), lw, ch, mx, my, false);
            }
            details(g, host, r.builds().get(card), x + lw + 8, top, w - lw - 8, bodyH);
        } else {
            // narrow: the cards side by side on top, the details under them
            int cw = (w - (n - 1) * 3) / n;
            int ch = 30;
            for (int i = 0; i < n; i++) {
                cardBox(g, host, r.builds().get(i), i, x + i * (cw + 3), top, cw, ch, mx, my, true);
            }
            details(g, host, r.builds().get(card), x, top + ch + 5, w, bodyH - ch - 5);
        }
        footer(g, host, x, footY, w, mx, my, true);
    }

    /** One build's card: rank and score with its label, the price, the nine slot squares. */
    private void cardBox(GuiGraphics g, Host host, Roadmap.Candidate c, int i, int x, int y, int w, int h, int mx, int my, boolean small) {
        boolean on = i == card;
        boolean hov = in(mx, my, x, y, w, h);
        Kit.card(g, x, y, w, h, 0, on || hov);
        if (on) {
            Kit.outline(g, x, y, w, h, CHOSEN);
        }
        String score = "#" + (i + 1) + " · " + (small ? shortNum(c.score()) : String.format(Locale.ROOT, "%,d", Math.round(c.score())));
        Kit.text(g, font, Component.literal(score).withStyle(UiFont::bold), x + 5, y + 4, Theme.INK);
        int sw = width(score, true);
        if (!c.labels().isEmpty()) {
            String lab = L.t(labelKey(c.labels().get(0), false));
            int lw = font.width(lab) + 8;
            if (x + 5 + sw + 4 + lw <= x + w - 4) {
                Kit.seal(g, x + w - 4 - lw, y + 3, lw, 11, labelColour(c.labels().get(0)));
                g.drawString(font, lab, x + w - 4 - lw + 4, y + 5, 0xFFFFFFFF, false);
            } else if (small) {
                g.fill(x + w - 6, y + 3, x + w - 3, y + 6, labelColour(c.labels().get(0)));
            }
        }
        int sy;
        if (!small) {
            String price = Double.isNaN(c.price()) ? L.t("wynnlabs.results.price_unknown")
                    : c.price() <= 0 ? L.t("wynnlabs.results.price_free") : "≈ " + Prices.fmt(c.price());
            Kit.text(g, font, fit(price, w - 10, false), x + 5, y + 15, Theme.PRICE);
            sy = y + h - 10;
        } else {
            sy = y + h - 10;
        }
        squares(g, x + 5, sy, w - 10, c.set(), c.fixed(), worn());
        btn(x, y, w, h, "card", i);
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(score).withStyle(ChatFormatting.WHITE));
        for (String l : c.labels()) {
            tip.add(L.c(labelKey(l, true)).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        tip.add(L.c("wynnlabs.results.squares_tip").withStyle(ChatFormatting.DARK_GRAY));
        host.tip(x, y, w, h, tip);
    }

    private static int labelColour(String l) {
        return switch (l) {
            case "best" -> 0xFF8A6A1C;
            case "cheap" -> Theme.SEAL_3;
            case "tanky" -> 0xFF2F5E8A;
            default -> 0xFF2C7A7E;
        };
    }

    private static String shortNum(double v) {
        return v >= 10_000 ? Math.round(v / 1000) + "k" : String.valueOf(Math.round(v));
    }

    /** What you wear now, by build slot (names). */
    private static Map<String, String> worn() {
        Map<String, String> out = new LinkedHashMap<>();
        Build.Plan p = Build.plan();
        if (p != null && p.worn() != null) {
            p.worn().forEach((s, piece) -> {
                if (piece != null) {
                    out.put(s, piece.name());
                }
            });
        }
        return out;
    }

    /**
     * Nine small squares in the panel's slot order: purple a chosen item, green a slot that
     * changes from what you wear, plain one that stays.
     */
    private static void squares(GuiGraphics g, int x, int y, int w, Map<String, Damage.Piece> set, java.util.Set<String> fixed,
            Map<String, String> worn) {
        int s = Math.max(3, Math.min(7, (w - 8) / 9));
        for (int i = 0; i < Plans.SLOTS.length; i++) {
            String slot = Plans.SLOTS[i];
            Damage.Piece p = set.get(slot);
            String name = p == null ? null : p.name();
            int c = fixed.contains(slot) ? CHOSEN : name != null && !name.equals(worn.get(slot)) ? Theme.GREEN : Theme.WELL;
            int sx = x + i * (s + 1);
            g.fill(sx, y, sx + s, y + s, Theme.INK_2);
            g.fill(sx + 1, y + 1, sx + s - 1, y + s - 1, c);
        }
    }

    /** The picked build: what changes against what you wear, its skill points, the equip order, and what to get. */
    private void details(GuiGraphics g, Host host, Roadmap.Candidate c, int x, int y, int w, int h) {
        Kit.outline(g, x, y, w, h, Theme.LINE);
        int ix = x + 5;
        int iw = w - 10;
        int ly = y + 4;
        int bottom = y + h - 4;
        Build.Plan plan = Build.plan();
        Damage.Result now = plan == null ? null : plan.current();
        Damage.Result to = c.eval().result();
        // the changes: damage, effective health, mana regen, walk speed
        List<Object[]> deltas = new ArrayList<>();
        if (now != null && now.factors().length > 0 && to.factors().length > 0) {
            deltas.add(new Object[] {L.t("wynnlabs.results.d_damage"), pct(now.factors()[0], to.factors()[0])});
        }
        if (now != null) {
            deltas.add(new Object[] {L.t("wynnlabs.results.d_ehp"), pct(now.ehp(), to.ehp())});
        }
        double regenNow = plan == null || plan.worn() == null ? 0 : stat(plan.worn(), "manaRegen");
        double regenTo = stat(c.set(), "manaRegen");
        if (Math.abs(regenTo - regenNow) >= 0.5) {
            deltas.add(new Object[] {L.t("wynnlabs.results.d_mana"), (regenTo - regenNow >= 0 ? "+" : "−") + Math.round(Math.abs(regenTo - regenNow))});
        }
        if (now != null && Math.abs(to.speed() - now.speed()) >= 1) {
            deltas.add(new Object[] {L.t("wynnlabs.results.d_speed"), (to.speed() - now.speed() >= 0 ? "+" : "−")
                + Math.round(Math.abs(to.speed() - now.speed())) + "%"});
        }
        int dx = ix;
        for (Object[] d : deltas) {
            String v = (String) d[1];
            String part = d[0] + " " + v;
            int pw = font.width(part) + 8;
            if (dx + pw > ix + iw && dx > ix) {
                dx = ix;
                ly += 10;
            }
            if (ly + 9 > bottom) {
                break;
            }
            Kit.text(g, font, (String) d[0], dx, ly, Theme.INK_2);
            Kit.text(g, font, v, dx + font.width(d[0] + " "), ly, v.startsWith("−") ? Theme.BAD : Theme.GOOD);
            dx += pw;
        }
        ly += 13;
        // the skill points to assign
        StringBuilder sp = new StringBuilder();
        int[] a = c.eval().allocation();
        for (SkillKind k : SkillKind.values()) {
            if (a[k.ordinal()] > 0) {
                sp.append(sp.length() > 0 ? " · " : "").append(k.displayName(), 0, 3).append(' ').append(a[k.ordinal()]);
            }
        }
        if (ly + 9 <= bottom) {
            Kit.text(g, font, fit(L.t("wynnlabs.results.points", sp.length() == 0 ? "—" : sp.toString()), iw, false), ix, ly, Theme.INK);
            ly += 11;
        }
        String order = equipOrder(c);
        if (order != null && ly + 9 <= bottom) {
            Kit.text(g, font, fit(L.t("wynnlabs.results.order", order), iw, false), ix, ly, Theme.WARN);
            ly += 11;
        }
        ly += 2;
        if (ly + 9 > bottom) {
            return;
        }
        Kit.text(g, font, Component.literal(L.t("wynnlabs.results.to_get")).withStyle(UiFont::bold), ix, ly, Theme.INK_2);
        ly += 11;
        Map<String, String> worn = worn();
        java.util.Set<String> have = haveNames();
        int left = 0;
        List<String> lines = new ArrayList<>();
        List<Integer> colours = new ArrayList<>();
        for (String slot : Plans.SLOTS) {
            Damage.Piece p = c.set().get(slot);
            if (p == null || p.name().equals(worn.get(slot))) {
                continue;
            }
            boolean owned = p.stack() != null || have.contains(p.name());
            lines.add(p.name() + " — " + (owned ? L.t("wynnlabs.results.have") : sourceText(p.name())));
            colours.add(owned ? Theme.GOOD : c.fixed().contains(slot) ? CHOSEN : Theme.INK);
        }
        if (lines.isEmpty()) {
            Kit.text(g, font, fit(L.t("wynnlabs.results.nothing_to_get"), iw, false), ix, ly, Theme.GOOD);
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            if (ly + 9 > bottom || ly + 19 > bottom && i < lines.size() - 1) {
                left = lines.size() - i;
                Kit.text(g, font, L.n("wynnlabs.results.more", left, left), ix, ly, Theme.INK_3);
                break;
            }
            Kit.text(g, font, fit("• " + lines.get(i), iw, false), ix, ly, colours.get(i));
            ly += 10;
        }
    }

    private static String pct(double from, double to) {
        if (from <= 0) {
            return "—";
        }
        double p = (to / from - 1) * 100;
        return (p >= 0 ? "+" : "−") + Math.round(Math.abs(p)) + "%";
    }

    private static double stat(Map<String, Damage.Piece> set, String key) {
        double s = 0;
        for (Damage.Piece p : set.values()) {
            if (p != null) {
                s += p.stats().getOrDefault(key, 0.0);
            }
        }
        return s;
    }

    /**
     * The order to put the pieces on when it matters: a piece whose skill requirement is met only
     * by another piece's skill bonus goes on after it ("Yume before Prologue"); null when any order works.
     */
    private static String equipOrder(Roadmap.Candidate c) {
        int[] a = c.eval().allocation();
        for (Damage.Piece q : c.set().values()) {
            if (q == null) {
                continue;
            }
            for (var e : q.reqs().entrySet()) {
                if (a[e.getKey().ordinal()] >= e.getValue()) {
                    continue;
                }
                String key = "raw" + e.getKey().displayName();
                for (Damage.Piece p : c.set().values()) {
                    if (p != null && p != q && p.helpsRequirements() && p.stats().getOrDefault(key, 0.0) > 0) {
                        return L.t("wynnlabs.results.before", p.name(), q.name());
                    }
                }
            }
        }
        return null;
    }

    /** Names of what you have in the bag and bank. */
    private static java.util.Set<String> haveNames() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            for (ItemStack s : GameState.current().inventory()) {
                if (!s.isEmpty()) {
                    out.add(ItemJson.clean(s.getHoverName().getString()).trim());
                }
            }
            for (Vault.Group gr : Vault.groups()) {
                out.add(gr.name());
            }
        } catch (Throwable ignored) {
            // what was read
        }
        return out;
    }

    /** Where a piece comes from: this week's raid or camp pool, its Trade Market price, a quest, else its source. */
    static String sourceText(String name) {
        String pool = poolOf(name);
        if (pool != null) {
            return L.t("wynnlabs.results.in_pool", pool);
        }
        ItemData info = GameState.current().allGear().filter(i -> i.name().equals(name)).findFirst().orElse(null);
        if (info == null) {
            return "?";
        }
        String src = Shopping.sourceOf(info);
        if (Shopping.tradable(info)) {
            Prices.Price p = GameState.current().price(name);
            if (p != null && p.typical() != null) {
                return L.t("wynnlabs.results.market", Prices.fmt(p.typical()));
            }
        }
        if (src != null && src.startsWith("QUEST")) {
            return L.t("wynnlabs.results.quest", Shopping.where(src));
        }
        return src == null ? (Shopping.tradable(info) ? L.t("wynnlabs.results.market_unknown") : "?") : Shopping.where(src);
    }

    private static Map<String, String> pools;
    private static long poolsAt;

    /** The raid whose pool this week holds the item, or null. */
    private static String poolOf(String name) {
        long now = System.currentTimeMillis();
        if (pools == null || now - poolsAt > 30_000) {
            Map<String, String> m = new java.util.HashMap<>();
            try {
                List<PoolView.Region> regions = PoolView.raidRegions();
                for (PoolView.Region reg : regions == null ? List.<PoolView.Region>of() : regions) {
                    for (PoolView.Group gr : PoolView.raid(reg.name(), false)) {
                        for (PoolView.Row row : gr.rows()) {
                            m.putIfAbsent(row.item().name(), reg.name());
                        }
                    }
                }
            } catch (Throwable ignored) {
                // no pool data
            }
            pools = m;
            poolsAt = now;
        }
        return pools.get(name);
    }

    /** "Cancel" and the purple "Apply build N" (or "Overwrite" for a saved build's recompute). */
    private void footer(GuiGraphics g, Host host, int x, int y, int w, int mx, int my, boolean any) {
        String apply = overwriteId != null ? L.t("wynnlabs.results.overwrite", card + 1) : L.t("wynnlabs.results.apply", card + 1);
        int aw = font.width(apply) + 18;
        int ax = x + w - aw;
        if (any) {
            boolean hov = in(mx, my, ax, y, aw, 14);
            Art.box(g, ax, y, aw, 14, 3, hov ? 0xFF6E4CA6 : CHOSEN, 0xFF3E2766);
            Art.icon(g, "check", ax + 4, y + 3, 8, CHOSEN_TEXT);
            g.drawString(font, apply, ax + 14, y + 3, CHOSEN_TEXT, false);
            btn(ax, y, aw, 14, "apply", null);
            host.tip(ax, y, aw, 14, List.of(L.c("wynnlabs.results.apply_tip").withStyle(ChatFormatting.GRAY)));
        }
        String cancel = L.t("wynnlabs.pick.cancel");
        int cw = font.width(cancel) + 12;
        int cx = any ? ax - 4 - cw : x + w - cw;
        boolean ch = in(mx, my, cx, y, cw, 14);
        Kit.button(g, cx, y, cw, 14, ch ? Kit.HOVER : Kit.REST);
        g.drawString(font, cancel, cx + 6, y + 3, Kit.buttonText(Kit.REST), false);
        btn(cx, y, cw, 14, "close", null);
        host.tip(cx, y, cw, 14, List.of(L.c("wynnlabs.results.cancel_tip").withStyle(ChatFormatting.GRAY)));
    }

    // ---- SAVED ----

    private void drawSaved(GuiGraphics g, Host host, int x, int y, int w, int h, int mx, int my, float pt) {
        List<Plans.Build> all = Plans.saved();
        Klass cls = GameState.current().classType();
        String title = L.t("wynnlabs.saved.title", cls == null ? "?" : nice(cls.name()), all.size());
        Art.icon(g, "bookmark", x, y, 8, Theme.INK_2);
        Kit.text(g, font, Component.literal(fit(title, w - 30, true)).withStyle(UiFont::bold), x + 11, y, Theme.INK);
        int top = y + 14;
        int footY = y + h - 14;
        int editH = editId == null ? 0 : 58;
        int listH = footY - 4 - editH - top;
        Plans.Build current = Plans.current();
        Map<String, String> worn = worn();
        Kit.outline(g, x, top, w, listH, Theme.LINE);
        int rows = Math.max(1, (listH - 2) / SAVED_ROW_H);
        int max = Math.max(0, all.size() - rows);
        savedScroll = Math.max(0, Math.min(savedScroll, max));
        int rw = w - 2 - (max > 0 ? 5 : 0);
        boolean renaming = false;
        if (all.isEmpty()) {
            Kit.text(g, font, fit(L.t("wynnlabs.saved.none"), w - 12, false), x + 6, top + 6, Theme.INK_3);
        }
        for (int i = 0; i < rows && savedScroll + i < all.size(); i++) {
            Plans.Build b = all.get(savedScroll + i);
            int ry = top + 1 + i * SAVED_ROW_H;
            boolean mine = current != null && current.id().equals(b.id());
            Kit.row(g, x + 1, ry, rw, SAVED_ROW_H, i, in(mx, my, x + 1, ry, rw, SAVED_ROW_H) || b.id().equals(editId));
            if (mine) {
                Kit.outline(g, x + 1, ry, rw, SAVED_ROW_H, CHOSEN);
            }
            // the buttons on the right: Apply (not on your plan), Edit, Rename, Delete
            int bx = x + 1 + rw - 4;
            String[][] all4 = {{"trash", "delete", "wynnlabs.saved.delete_tip"}, {"pencil", "rename", "wynnlabs.saved.rename_tip"},
                {"list-check", "edit", "wynnlabs.saved.edit_tip"}, {"check", "applySaved", "wynnlabs.saved.apply_tip"}};
            String[][] acts = mine ? java.util.Arrays.copyOf(all4, 3) : all4;
            for (String[] a : acts) {
                bx -= 14;
                boolean hov = in(mx, my, bx, ry + 3, 13, 12);
                Kit.button(g, bx, ry + 3, 13, 12, a[1].equals("applySaved") ? Kit.ON : hov ? Kit.HOVER : Kit.REST);
                Art.icon(g, a[0], bx + 2, ry + 5, 8, Kit.buttonText(a[1].equals("applySaved") ? Kit.ON : Kit.REST));
                btn(bx, ry + 3, 13, 12, a[1], b.id());
                host.tip(bx, ry + 3, 13, 12, List.of(L.c(a[2]).withStyle(ChatFormatting.GRAY)));
                bx -= 1;
            }
            int nx = x + 6;
            int nameRoom = bx - 4 - nx;
            if (b.id().equals(renameId)) {
                renaming = true;
                int fw = Math.max(40, nameRoom - 30);
                Kit.field(g, nx - 2, ry + 2, fw, 12, true);
                rename.setX(nx + 2);
                rename.setY(ry + 4);
                rename.setWidth(fw - 8);
                rename.render(g, mx, my, pt);
                rename.setFocused(true);
                int okX = nx - 2 + fw + 2;
                Kit.button(g, okX, ry + 2, 12, 12, Kit.ON);
                Art.icon(g, "check", okX + 2, ry + 4, 8, Kit.buttonText(Kit.ON));
                btn(okX, ry + 2, 12, 12, "renameOk", b.id());
                Kit.button(g, okX + 14, ry + 2, 12, 12, Kit.REST);
                Art.icon(g, "x", okX + 16, ry + 4, 8, Kit.buttonText(Kit.REST));
                btn(okX + 14, ry + 2, 12, 12, "renameCancel", b.id());
            } else {
                String suffix = mine ? " · " + L.t("wynnlabs.saved.your_plan") : "";
                int sw = font.width(suffix);
                Component nm = Component.literal(fit(b.name(), nameRoom - sw, true)).withStyle(UiFont::bold);
                Kit.text(g, font, nm, nx, ry + 4, Theme.INK);
                if (mine) {
                    Kit.text(g, font, suffix, nx + font.width(nm), ry + 4, CHOSEN);
                }
            }
            // the squares, the score and how much of it you wear
            boolean[] on = Plans.wornSlots(b.items(), worn);
            int wornN = 0;
            for (int k = 0; k < Plans.SLOTS.length; k++) {
                wornN += on[k] ? 1 : 0;
            }
            savedSquares(g, nx, ry + 18, b, on);
            String info = String.format(Locale.ROOT, "%,d", Math.round(b.score())) + " · " + wornN + "/" + b.items().size();
            Kit.text(g, font, fit(info, bx - 4 - (nx + 70), false), nx + 70, ry + 17, Theme.INK_3);
            host.tip(x + 1, ry, Math.max(1, bx - 2 - x), SAVED_ROW_H, savedTip(b));
        }
        if (!renaming) {
            rename.setFocused(false);
        }
        if (max > 0) {
            int bh = Math.max(12, (listH - 2) * rows / all.size());
            int by = top + 1 + (listH - 2 - bh) * savedScroll / max;
            g.fill(x + w - 5, top + 1, x + w - 3, top + listH - 1, 0x22000000);
            g.fill(x + w - 5, by, x + w - 3, by + bh, 0x88000000);
        }
        if (editId != null) {
            drawEdit(g, host, x, top + listH + 4, w, editH, mx, my);
        }
        // the footer: "+ Save current plan", and the undo line after a delete
        String add = "+ " + L.t("wynnlabs.saved.add");
        int aw = font.width(add) + 12;
        boolean can = current != null;
        boolean ah = can && in(mx, my, x, footY, aw, 14);
        Kit.button(g, x, footY, aw, 14, !can ? Kit.REST : ah ? Kit.HOVER : Kit.REST);
        g.drawString(font, add, x + 6, footY + 3, can ? Kit.buttonText(Kit.REST) : Theme.CREAM_DIM, false);
        if (can) {
            btn(x, footY, aw, 14, "addCurrent", null);
        }
        host.tip(x, footY, aw, 14, List.of(L.c(can ? "wynnlabs.saved.add_tip" : "wynnlabs.saved.add_none").withStyle(ChatFormatting.GRAY)));
        long now = System.currentTimeMillis();
        Plans.Build gone = Plans.undoable(now);
        if (gone != null) {
            String del = L.t("wynnlabs.saved.deleted") + " · ";
            String undo = L.t("wynnlabs.saved.undo");
            int uw = font.width(del) + font.width(undo);
            int ux = x + w - uw;
            if (ux > x + aw + 6) {
                Kit.text(g, font, del, ux, footY + 3, Theme.INK_3);
                Kit.text(g, font, undo, ux + font.width(del), footY + 3, Theme.LINK);
                btn(ux + font.width(del), footY + 1, font.width(undo), 12, "undo", null);
            }
        }
    }

    private void savedSquares(GuiGraphics g, int x, int y, Plans.Build b, boolean[] worn) {
        for (int i = 0; i < Plans.SLOTS.length; i++) {
            String name = b.items().get(Plans.SLOTS[i]);
            int c = name != null && b.locked(name) ? CHOSEN : worn[i] ? Theme.GREEN : Theme.WELL;
            int sx = x + i * 7;
            g.fill(sx, y, sx + 6, y + 6, Theme.INK_2);
            g.fill(sx + 1, y + 1, sx + 5, y + 5, c);
        }
    }

    private static List<Component> savedTip(Plans.Build b) {
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(b.name()).withStyle(ChatFormatting.LIGHT_PURPLE).withStyle(UiFont::bold));
        for (String slot : Plans.SLOTS) {
            String n = b.items().get(slot);
            if (n != null) {
                tip.add(Component.literal((b.locked(n) ? "◆ " : "  ") + n).withStyle(b.locked(n) ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GRAY));
            }
        }
        tip.add(L.c("wynnlabs.saved.row_tip", summary(b.tuning())).withStyle(ChatFormatting.DARK_GRAY));
        return tip;
    }

    /** "Balanced · Mana · Budget: 10 EB": a tuning in one line. */
    static String summary(Tuning.Choice t) {
        String f = L.t(t.focus() == Tuning.DAMAGE ? "wynnlabs.pick.focus_damage" : t.focus() == Tuning.TANKY ? "wynnlabs.pick.focus_tanky"
                : "wynnlabs.pick.focus_balanced");
        StringBuilder s = new StringBuilder(f);
        if (t.mana()) {
            s.append(" · ").append(L.t("wynnlabs.pick.mana"));
        }
        if (t.speed()) {
            s.append(" · ").append(L.t("wynnlabs.pick.speed"));
        }
        s.append(" · ").append(L.t("wynnlabs.pick.budget", budgetText(t.budget())));
        return s.toString();
    }

    /** The edit area under the list: the build's chosen items as chips with "+ item", its strip, and its three buttons. */
    private void drawEdit(GuiGraphics g, Host host, int x, int y, int w, int h, int mx, int my) {
        Plans.Build b = Plans.byId(editId);
        if (b == null) {
            editId = null;
            return;
        }
        Kit.card(g, x, y, w, h, CHOSEN);
        int cx = x + 5;
        int cy = y + 5;
        String head = L.t("wynnlabs.saved.editing", b.name());
        Kit.text(g, font, Component.literal(fit(head, w - 10, true)).withStyle(UiFont::bold), cx, cy, Theme.INK);
        cy += 12;
        String plus = "+ " + L.t("wynnlabs.saved.add_item");
        int plusW = font.width(plus) + 10;
        for (String n : editLocks) {
            int cw = Math.min(font.width(n) + 18, 110);
            if (cx + cw + 3 + plusW > x + w - 5) {
                break;
            }
            Art.box(g, cx, cy, cw, 13, 3, 0xFFE4D8F2, CHOSEN);
            Kit.text(g, font, fit(n, cw - 18, false), cx + 4, cy + 2, Theme.INK);
            Art.icon(g, "x", cx + cw - 11, cy + 2, 8, CHOSEN);
            btn(cx, cy, cw, 13, "editUnlock", n);
            cx += cw + 3;
        }
        boolean ph = in(mx, my, cx, cy, plusW, 13);
        Kit.button(g, cx, cy, plusW, 13, ph ? Kit.HOVER : Kit.REST);
        g.drawString(font, plus, cx + 5, cy + 2, Kit.buttonText(Kit.REST), false);
        btn(cx, cy, plusW, 13, "editAdd", null);
        host.tip(cx, cy, plusW, 13, List.of(L.c("wynnlabs.saved.add_item_tip").withStyle(ChatFormatting.GRAY)));
        cy += 16;
        // the buttons on the right of the strip's summary
        String[][] acts = {{"wynnlabs.saved.recompute", "editRecompute", "wynnlabs.saved.recompute_tip"},
            {"wynnlabs.saved.save_new", "editSaveNew", "wynnlabs.saved.save_new_tip"},
            {"wynnlabs.pick.cancel", "editCancel", "wynnlabs.saved.edit_cancel_tip"}};
        int bx = x + w - 5;
        for (String[] a : acts) {
            String t = L.t(a[0]);
            int bw = font.width(t) + 10;
            bx -= bw;
            boolean on = a[1].equals("editRecompute");
            boolean hov = in(mx, my, bx, cy, bw, 13);
            if (on) {
                Art.box(g, bx, cy, bw, 13, 3, hov ? 0xFF6E4CA6 : CHOSEN, 0xFF3E2766);
                g.drawString(font, t, bx + 5, cy + 2, CHOSEN_TEXT, false);
            } else {
                Kit.button(g, bx, cy, bw, 13, hov ? Kit.HOVER : Kit.REST);
                g.drawString(font, t, bx + 5, cy + 2, Kit.buttonText(Kit.REST), false);
            }
            btn(bx, cy, bw, 13, a[1], null);
            host.tip(bx, cy, bw, 13, List.of(L.c(a[2]).withStyle(ChatFormatting.GRAY)));
            bx -= 3;
        }
        Kit.text(g, font, fit(summary(editTuning), bx - 4 - (x + 5), false), x + 5, cy + 2, Theme.INK_3);
    }

    // =====================================================================================
    // input

    boolean mouseClicked(double mx, double my, int button) {
        if (!open) {
            return false;
        }
        Btn hit = null;
        for (int i = btns.size() - 1; i >= 0; i--) {
            Btn b = btns.get(i);
            if (in(mx, my, b.x(), b.y(), b.w(), b.h())) {
                hit = b;
                break;
            }
        }
        if (hit == null) {
            return true;                                // the page or the dimmed menu: nothing under it reacts
        }
        long now = System.currentTimeMillis();
        GameState state = GameState.current();
        switch (hit.act()) {
            case "close" -> close();
            case "search" -> search.setFocused(true);
            case "type" -> {
                type = (int) hit.arg();
                listScroll = 0;
            }
            case "item" -> {
                chosen = Locks.toggle(chosen, (ItemData) hit.arg());
                changed();
            }
            case "unchoose" -> {
                ItemData it = (ItemData) hit.arg();
                chosen.removeIf(c -> c.name().equals(it.name()));
                changed();
            }
            case "clear" -> {
                chosen.clear();
                changed();
            }
            case "focus" -> {
                tuning = tuning.withFocus((int) hit.arg());
                changed();
            }
            case "mana" -> {
                tuning = tuning.withMana(!tuning.mana());
                changed();
            }
            case "speed" -> {
                tuning = tuning.withSpeed(!tuning.speed());
                changed();
            }
            case "budget" -> {
                tuning = tuning.nextBudget();
                changed();
            }
            case "suggest" -> {
                Locks.set(names(chosen));
                Locks.setTuning(tuning);
                awaitGen = Around.request(names(chosen), tuning, false);
                search.setFocused(false);
            }
            case "cancelRun" -> {
                Around.cancel();
                awaitGen = -1;
                if (overwriteId != null) {
                    view = View.SAVED;
                    overwriteId = null;
                }
            }
            case "back" -> {
                view = View.PICK;
                if (overwriteId != null) {
                    view = View.SAVED;
                    overwriteId = null;
                }
            }
            case "card" -> card = (int) hit.arg();
            case "apply" -> apply(state, now);
            case "applySaved" -> {
                Plans.Build b = Plans.byId((String) hit.arg());
                if (b != null) {
                    Plans.apply(b);
                    hostApplied();
                }
            }
            case "edit" -> {
                Plans.Build b = Plans.byId((String) hit.arg());
                if (b != null) {
                    editId = b.id();
                    editLocks = new ArrayList<>(b.locks());
                    editTuning = b.tuning();
                }
            }
            case "rename" -> {
                Plans.Build b = Plans.byId((String) hit.arg());
                if (b != null) {
                    renameId = b.id();
                    rename.setValue(b.name());
                    rename.setFocused(true);
                }
            }
            case "renameOk" -> saveRename(now);
            case "renameCancel" -> renameId = null;
            case "delete" -> {
                Plans.delete((String) hit.arg(), now);
                if (hit.arg().equals(editId)) {
                    editId = null;
                }
            }
            case "undo" -> Plans.undo(now);
            case "addCurrent" -> {
                Plans.Build cur = Plans.current();
                if (cur != null) {
                    Plans.Build kept = Plans.save(cur.named(""), now);
                    Plans.apply(cur.withId(kept.id()).named(kept.name()));
                    savedScroll = Integer.MAX_VALUE;            // scrolled to it (clamped when drawn)
                }
            }
            case "editUnlock" -> {
                List<String> l = new ArrayList<>(editLocks);
                l.remove((String) hit.arg());
                editLocks = l;
            }
            case "editAdd", "editRecompute" -> {
                // the Choose-items list preloaded with this build's chosen items and strip
                chosen = new ArrayList<>(Locks.resolve(state, editLocks));
                tuning = editTuning;
                Locks.set(names(chosen));
                Locks.setTuning(tuning);
                overwriteId = editId;
                Around.forget();
                if (hit.act().equals("editRecompute")) {
                    awaitGen = Around.request(names(chosen), tuning, false);
                }
                view = View.PICK;
            }
            case "editSaveNew" -> {
                Plans.Build b = Plans.byId(editId);
                if (b != null) {
                    Plans.save(new Plans.Build("", "", editLocks, editTuning, b.items(), b.score(), b.level(), now, now), now);
                    savedScroll = Integer.MAX_VALUE;
                }
                editId = null;
            }
            case "editCancel" -> editId = null;
            default -> { }
        }
        return true;
    }

    private Host lastHost;

    void host(Host h) {
        lastHost = h;
    }

    private void hostApplied() {
        if (lastHost != null) {
            lastHost.applied();
        }
        close();
    }

    /** "Apply build N": the plan becomes that build (or, from a saved build's Recompute, overwrites it). */
    private void apply(GameState state, long now) {
        Around.Result r = result;
        if (r == null || r.builds().isEmpty()) {
            return;
        }
        Roadmap.Candidate c = r.builds().get(Math.min(card, r.builds().size() - 1));
        Map<String, String> items = new LinkedHashMap<>();
        for (String slot : Plans.SLOTS) {
            Damage.Piece p = c.set().get(slot);
            if (p != null) {
                items.put(slot, p.name());
            }
        }
        Plans.Build fresh = new Plans.Build("p" + now, "", r.around(), r.tuning(), items, c.score(), r.level(), now, now);
        if (overwriteId != null) {
            Plans.overwrite(overwriteId, fresh, now);
            overwriteId = null;
            editId = null;
            view = View.SAVED;
            if (lastHost != null) {
                lastHost.applied();
            }
            return;
        }
        Plans.apply(fresh.named(Plans.defaultName(fresh)));
        hostApplied();
    }

    private void saveRename(long now) {
        if (renameId != null) {
            Plans.rename(renameId, rename.getValue(), now);
        }
        renameId = null;
        rename.setFocused(false);
    }

    boolean mouseScrolled(double mx, double my, double v) {
        if (!open) {
            return false;
        }
        int step = (int) -Math.signum(v);
        if (view == View.PICK) {
            listScroll = Math.max(0, listScroll + step * 3);
        } else if (view == View.SAVED) {
            savedScroll = Math.max(0, Math.min(savedScroll, Math.max(0, Plans.saved().size() - 1)) + step);
        } else if (view == View.RESULTS && result != null && !result.builds().isEmpty()) {
            card = Math.max(0, Math.min(result.builds().size() - 1, card + step));
        }
        return true;
    }

    /** Esc closes a rename, else the window (never the menu); Enter saves a rename. True when the window took the key. */
    boolean keyPressed(int key) {
        if (!open) {
            return false;
        }
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            if (renameId != null) {
                renameId = null;
                rename.setFocused(false);
            } else {
                close();
            }
            return true;
        }
        if (renameId != null && (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) {
            saveRename(System.currentTimeMillis());
            return true;
        }
        return false;
    }

    // =====================================================================================
    // the preview

    /** The preview: the PICK view with a search typed and these items chosen. */
    void previewPick(String typed, List<String> items, Tuning.Choice t) {
        open = true;
        view = View.PICK;
        type = 0;
        listScroll = 0;
        search.setValue(typed);
        search.setFocused(false);
        chosen = new ArrayList<>(Locks.resolve(GameState.current(), items));
        tuning = t;
        awaitGen = -1;
    }

    /** The preview: the RESULTS view of {@code r}, card {@code pick}. */
    void previewResults(Around.Result r, int pick) {
        openResults(r);
        card = pick;
    }

    /** The preview: the SAVED view, scrolled {@code scroll} rows, renaming or editing a build (null: neither). */
    void previewSaved(int scroll, String renaming, String editing) {
        open = true;
        view = View.SAVED;
        savedScroll = scroll;
        renameId = renaming;
        if (renaming != null) {
            Plans.Build b = Plans.byId(renaming);
            rename.setValue(b == null ? "" : b.name() + " 2");
        }
        editId = editing;
        if (editing != null) {
            Plans.Build b = Plans.byId(editing);
            editLocks = b == null ? List.of() : new ArrayList<>(b.locks());
            editTuning = b == null ? Tuning.Choice.PLAIN : b.tuning();
        }
    }

    // =====================================================================================
    // small helpers

    private void btn(int x, int y, int w, int h, String act, Object arg) {
        btns.add(new Btn(x, y, w, h, act, arg));
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private String fit(String s, int w, boolean bold) {
        if (s == null || w <= 0) {
            return "";
        }
        if (width(s, bold) <= w) {
            return s;
        }
        String cut = font.plainSubstrByWidth(s, Math.max(0, w - width("…", bold)));
        while (!cut.isEmpty() && width(cut + "…", bold) > w) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    private int width(String s, boolean bold) {
        return bold ? font.width(Component.literal(s).withStyle(UiFont::bold)) : font.width(s);
    }
}
