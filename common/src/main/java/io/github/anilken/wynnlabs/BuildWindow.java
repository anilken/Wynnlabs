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

        /**
         * An item icon's hover: the item's own tooltip (Wynntils' for its guide stack, else
         * {@link #itemTooltip}) with {@code extra} lines under it.
         */
        void item(int x, int y, int w, int h, ItemData info, ItemStack stack, List<Component> extra);

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
    /** The compact menu this frame: the result view in one column. */
    private boolean compact;
    /** The preview: the first chosen item's icon drawn this frame (x, y, w, h), for a hover shot. */
    int[] firstIcon;
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
    /**
     * "Choose items": the picker with the chosen items and the strip as they were. The results
     * only when a search finished while the window was closed and they were not seen yet; a
     * result list once seen and cancelled is gone (Suggest build again shows new ones).
     */
    void openPick() {
        editingPlan = null;
        loadChoice();
        open = true;
        overwriteId = null;
        Around.Result r = Around.result();
        if (unseenGen >= 0 && r != null && r.gen() == unseenGen) {
            result = r;
            card = 0;
            fromHint = false;
            view = View.RESULTS;
        } else {
            view = View.PICK;
        }
        unseenGen = -1;
        focusSearch(view == View.PICK);
    }

    /** A finished search not shown yet (it finished while the window was closed); -1 none. */
    private int unseenGen = -1;
    /** The result list on show came from the better-build hint. */
    private boolean fromHint;

    /**
     * The result list is done with (Cancel, ✕, Esc, back, Apply): dropped, so the next
     * "Choose items" opens the picker. The hint's own list goes with its hint.
     */
    private void discardResults() {
        if (result == null) {
            return;
        }
        if (fromHint) {
            Around.forgetCheck();
            Plans.HINT.dismiss();
        } else {
            Around.forget();
        }
        result = null;
        fromHint = false;
        unseenGen = -1;
    }

    /** Tests and the preview: whether a result list is held. */
    boolean hasResults() {
        return result != null;
    }

    /**
     * The plan's Edit: the picker with the plan's chosen items and strip. Suggest build, then
     * Apply replaces the plan, and updates its copy in My builds when it has one.
     */
    void editPlan(Plans.Build plan) {
        if (plan == null) {
            return;
        }
        open = true;
        overwriteId = null;
        editingPlan = Plans.isSaved(plan) ? plan.id() : "";
        chosen = new ArrayList<>(Locks.resolve(GameState.current(), plan.locks()));
        tuning = plan.tuning();
        Locks.set(names(chosen));
        Locks.setTuning(tuning);
        Around.forget();
        result = null;
        unseenGen = -1;
        view = View.PICK;
        focusSearch(true);
    }

    /** Editing the plan: "" for an unsaved one, its saved copy's id, null when not editing it. */
    private String editingPlan;

    /** Tests: the items and the strip the picker holds. */
    List<String> chosenNames() {
        return names(chosen);
    }

    Tuning.Choice tuning() {
        return tuning;
    }

    /** "My builds". */
    void openSaved() {
        editingPlan = null;
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
        fromHint = r.check();
        editingPlan = null;
        chosen = new ArrayList<>(Locks.resolve(GameState.current(), r.around()));
        tuning = r.tuning();
        view = View.RESULTS;
        focusSearch(false);
    }

    void close() {
        open = false;
        renameId = null;
        focusSearch(false);
        if (rename != null) {
            rename.setFocused(false);
        }
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

    /** The level a set around these items is planned for: yours, or the highest chosen item's. */
    static int planLevel(List<ItemData> items) {
        return Math.max(GameState.current().level(), items.stream().mapToInt(ItemData::level).max().orElse(0));
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

    /**
     * Each frame while the window is closed: a search it started has finished, so say so; the
     * results wait for the window to open again.
     */
    void poll(Host host) {
        Around.Result r = Around.result();
        if (!open && awaitGen >= 0 && r != null && r.gen() == awaitGen) {
            awaitGen = -1;
            unseenGen = r.gen();                    // shown once when the window opens again
            host.flash(L.n("wynnlabs.pick.ready", r.builds().size(), r.builds().size()));
        }
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
    void draw(GuiGraphics g, int[] menu, boolean large, boolean compact, Host host, int mx, int my, float pt) {
        this.compact = compact;
        firstIcon = null;
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

    /** The level ranges of the list's range button: all, up to your level, 1–50, 51–80, 81–105, 106 and up. */
    private static final int[][] RANGES = {{1, 999}, {1, -1}, {1, 50}, {51, 80}, {81, 105}, {106, 999}};
    /** The range picked (0: all levels, the default). */
    private int range;

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
     * bank), highest level first; the type pill, the level range and the search narrow it.
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
        String key = type + "|" + range + "|" + search.getValue().toLowerCase(Locale.ROOT).strip();
        if (!key.equals(shownFor)) {
            String q = search.getValue().toLowerCase(Locale.ROOT).strip();
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
        Locks.Problem problem = Locks.problem(chosen, planLevel(chosen));
        // the level range on the note's line, right: a button that steps through the ranges
        String rt = rangeText(range);
        int rw = font.width(rt) + 23;
        boolean rh = in(mx, my, x + w - rw, cy - 2, rw, 13);
        int rs = range > 0 ? Kit.ON : rh ? Kit.HOVER : Kit.REST;
        Kit.button(g, x + w - rw, cy - 2, rw, 13, rs);
        Art.icon(g, "filter", x + w - rw + 6, cy + 1, 8, Kit.buttonText(rs));
        g.drawString(font, rt, x + w - rw + 17, cy + 1, Kit.buttonText(rs), false);
        btn(x + w - rw, cy - 2, rw, 13, "range", null);
        List<Component> rtip = new ArrayList<>();
        rtip.add(L.c("wynnlabs.pick.range_tip").withStyle(ChatFormatting.WHITE));
        for (int i = 0; i < RANGES.length; i++) {
            rtip.add(Component.literal((i == range ? "▶ " : "   ") + rangeText(i)).withStyle(i == range ? ChatFormatting.GOLD : ChatFormatting.GRAY));
        }
        host.tip(x + w - rw, cy - 2, rw, 13, rtip);
        int noteW = w - rw - 6;
        String note = problem != null ? L.t("wynnlabs.pick.problem", problem.text())
                : L.t("wynnlabs.pick.note", wpn == null ? "?" : nice(wpn.name()));
        if (problem == null && font.width(note) > noteW) {
            note = L.t("wynnlabs.pick.note_short", wpn == null ? "?" : nice(wpn.name()));
        }
        Kit.text(g, font, fit(note, noteW, false), x, cy, problem != null ? Theme.BAD : Theme.INK_3);
        cy += 13;
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
        int rw = w - 2 - (max > 0 ? ScrollBar.W + 2 : 0);
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
            // the item's own tooltip (stats, ranges, requirements) with what a click does under it
            host.item(x + 1, ry, rw, ROW_H, it, icon, List.of(
                    L.c(on ? "wynnlabs.pick.row_off" : "wynnlabs.pick.row_on").withStyle(ChatFormatting.DARK_GRAY)));
        }
        // the scrollbar: drag its thumb, click its track for a page (ScrollBar)
        listBar.place(x + w - ScrollBar.W - 1, y + 1, h - 2, rows, items.size(), listScroll);
        listBar.draw(g, mx, my);
        if (listBar.shown()) {
            int[] t = listBar.track();
            btn(t[0], t[1], t[2], t[3], "scrollbar", null);
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
        // the lines that fit, top to bottom: the title, the phase, the bar, the count, the best, Cancel
        boolean hasBest = !Double.isNaN(best) && best > 0;
        boolean showPhase = h >= 50;
        boolean showCount = h >= 62;
        boolean showBest = hasBest && h >= 74;
        int need = 14 + (showPhase ? 14 : 0) + 9 + (showCount ? 11 : 0) + (showBest ? 11 : 0) + 15;
        int ty = y + Math.max(3, (h - need) / 2);
        Component t = Component.literal(fit(who, w - 40, true)).withStyle(UiFont::bold);
        int tw = font.width(t);
        spinner(g, cx - (tw + 14) / 2 + 4, ty + 4, pv != null);
        Kit.text(g, font, t, cx - (tw + 14) / 2 + 14, ty, CHOSEN);
        int ly = ty + 14;
        if (showPhase) {
            String ph = L.t(PHASES[Math.max(0, Math.min(PHASES.length - 1, phase))]);
            Kit.text(g, font, fit(ph, w - 16, false), cx - Math.min(font.width(ph), w - 16) / 2, ly, Theme.INK_2);
            ly += 14;
        }
        int bw = Math.min(220, w - 24);
        int bx = cx - bw / 2;
        Kit.bar(g, bx, ly, bw, 5, Math.max(0, Math.min(1, frac)), 0xFFB79FE0, 0xFF8A6CC0, 0xFF5A3A8A, false);
        ly += 9;
        if (showCount) {
            String count = L.n("wynnlabs.pick.tried", tried, String.format(Locale.ROOT, "%,d", tried));
            Kit.text(g, font, fit(count, w - 16, false), cx - Math.min(font.width(count), w - 16) / 2, ly, Theme.INK_3);
            ly += 11;
        }
        if (showBest) {
            String b = L.t("wynnlabs.pick.best_so_far", String.format(Locale.ROOT, "%,d", Math.round(best)));
            Kit.text(g, font, fit(b, w - 16, false), cx - Math.min(font.width(b), w - 16) / 2, ly, Theme.INK_3);
            ly += 11;
        }
        int cby = Math.min(y + h - 15, ly + 2);
        control(g, host, cx - ICON_W / 2, cby, 14, "x", L.t("wynnlabs.pick.cancel"), WOOD, false, 0, "cancelRun", null,
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
        out.add(new Object[] {"coin", L.t("wynnlabs.pick.budget", budgetText(t.budget())), "wynnlabs.pick.budget_tip", "budget", null,
            t.budget() > 0, t.budget() <= 0 ? "∞" : Prices.fmt(t.budget())});
        return out;
    }

    static String budgetText(long budget) {
        return budget <= 0 ? L.t("wynnlabs.pick.budget_none") : Prices.fmt(budget);
    }

    /** The strip's height: one row of buttons. */
    private int stripHeight(int w) {
        return 14;
    }

    /**
     * One row: Damage / Balanced / Tanky as one segmented control of icons, then Mana and Speed,
     * then the budget; each a wood button (gold when on) whose tooltip names it. {@code live}: clickable.
     */
    private void strip(GuiGraphics g, Host host, int x, int y, int w, int mx, int my, boolean live) {
        List<Object[]> parts = stripParts(tuning);
        int px = x;
        for (int i = 0; i < parts.size(); i++) {
            Object[] p = parts.get(i);
            if (i == 3 || i == 5) {
                px += 5;                                    // apart from the segmented control and the toggles
            }
            String icon = (String) p[0];
            String text = (String) p[6];
            boolean on = (boolean) p[5];
            int bw = text == null ? ICON_W : 11 + font.width(text) + 12;
            boolean hov = live && in(mx, my, px, y, bw, 14);
            int state = on ? Kit.ON : hov ? Kit.HOVER : Kit.REST;
            Kit.button(g, px, y, bw, 14, state);
            if (text == null) {
                Art.icon(g, icon, px + (bw - 8) / 2, y + 3, 8, Kit.buttonText(state));
            } else {
                Art.icon(g, icon, px + 6, y + 3, 8, Kit.buttonText(state));
                g.drawString(font, text, px + 17, y + 3, Kit.buttonText(state), false);
            }
            if (live) {
                btn(px, y, bw, 14, (String) p[3], p[4]);
                host.tip(px, y, bw, 14, List.of(Component.literal((String) p[1]).withStyle(ChatFormatting.WHITE),
                        L.c((String) p[2]).withStyle(ChatFormatting.GRAY)));
            }
            px += bw + (i < 2 ? 1 : 3);
        }
    }

    /**
     * The chosen items as chips (a click takes one out), Clear (an eraser), and the purple
     * "Suggest build" on the right (its sparkles alone when the row is tight).
     */
    private void drawChips(GuiGraphics g, Host host, int x, int y, int w, Locks.Problem problem, int mx, int my) {
        boolean can = !chosen.isEmpty() && problem == null || chosen.isEmpty();
        String sug = L.t("wynnlabs.pick.suggest");
        int sw = controlW("sparkles", sug, w / 3);
        int sx = x + w - sw;
        List<Component> sugTip = List.of(L.c(can ? "wynnlabs.pick.suggest_tip" : "wynnlabs.pick.suggest_off").withStyle(ChatFormatting.GRAY));
        if (can) {
            control(g, host, sx, y, 14, "sparkles", sug, PRIMARY, false, w / 3, "suggest", null, sugTip, mx, my);
        } else {
            control(g, host, sx, y, 14, "sparkles", sug, WOOD, false, w / 3, null, null, sugTip, mx, my);
        }
        int cx = x;
        int room = sx - 6;
        if (chosen.isEmpty()) {
            Kit.text(g, font, fit(L.t("wynnlabs.pick.chips_none"), room - x, false), x, y + 3, Theme.INK_3);
            return;
        }
        int clearW = ICON_W;
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
            chip(g, cx, y, cw, 14, in(mx, my, cx, y, cw, 14));
            Kit.text(g, font, Component.literal(fit(it.name(), cw - 18, false)).withStyle(it.tier().colour()), cx + 5, y + 3, Theme.INK);
            Art.icon(g, "x", cx + cw - 11, y + 3, 8, CHOSEN);
            btn(cx, y, cw, 14, "unchoose", it);
            host.tip(cx, y, cw, 14, List.of(Component.literal(it.name()).withStyle(it.tier().colour()),
                    L.c("wynnlabs.pick.chip_tip").withStyle(ChatFormatting.DARK_GRAY)));
            cx += cw + 3;
        }
        control(g, host, cx, y, 14, "eraser", L.t("wynnlabs.pick.clear"), WOOD, false, 0, "clear", null,
                List.of(L.c("wynnlabs.pick.clear_tip").withStyle(ChatFormatting.GRAY)), mx, my);
    }

    /** A chosen item's chip: the kit's pill, washed purple, with its purple outline (lighter on hover). */
    private static void chip(GuiGraphics g, int x, int y, int w, int h, boolean hover) {
        Kit.seal(g, x, y, w, h, hover ? 0xFFEFE6FA : 0xFFE4D8F2);
        Kit.pillLine(g, x, y, w, h, CHOSEN);
    }

    // ---- RESULTS ----

    private static final int CHOSEN = HomeTab.CHOSEN;
    private static final int CHOSEN_TEXT = HomeTab.CHOSEN_TEXT;

    private void drawResults(GuiGraphics g, Host host, int x, int y, int w, int h, int mx, int my) {
        Around.Result r = result;
        // the header: back to the items, the count found around them
        int bw = control(g, host, x, y - 3, 14, "arrow-left", L.t("wynnlabs.results.back"), WOOD, false, 0, "back", null,
                List.of(L.c("wynnlabs.results.back_tip").withStyle(ChatFormatting.GRAY)), mx, my);
        int n = r == null ? 0 : r.builds().size();
        String around = r == null || r.around().isEmpty() ? null : String.join(", ", r.around());
        // planned for a level above yours when a chosen item needs it
        String planned = r != null && r.level() > GameState.current().level() ? L.t("wynnlabs.results.planned_for", r.level()) : null;
        int headRoom = w - bw - 24;
        String plain = L.n("wynnlabs.results.found_plain", n, n);
        String[] tries = around == null ? new String[] {plain}
                : new String[] {L.n("wynnlabs.results.found", n, n, around), plain};
        String head = tries[tries.length - 1];
        for (String c : tries) {
            if (width(c, true) + (planned == null ? 0 : 8 + font.width(planned)) <= headRoom) {
                head = c;
                break;
            }
        }
        if (around != null && !head.contains(around)) {
            host.tip(x + bw + 6, y, Math.min(headRoom, width(head, true)), 9,
                    List.of(Component.literal(around).withStyle(ChatFormatting.LIGHT_PURPLE)));
        }
        Kit.text(g, font, Component.literal(fit(head, headRoom, true)).withStyle(UiFont::bold), x + bw + 6, y, Theme.INK);
        if (planned != null && width(head, true) + 8 + font.width(planned) <= headRoom) {
            Kit.text(g, font, planned, x + bw + 6 + width(head, true) + 8, y, CHOSEN);
        }
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
        if (r.problem() != null) {
            // the chosen items could not be held: these are the builds without them
            Kit.text(g, font, fit(L.t("wynnlabs.pick.problem", r.problem().text()), w, false), x, top, Theme.BAD);
            top += 12;
        }
        int bodyH = footY - 4 - top;
        boolean two = !compact && w >= 380;             // the compact menu: one column, the cards on top
        if (two) {
            int lw = Math.max(176, Math.min(220, w * 40 / 100));
            int ch = Math.min(CARD_H, (bodyH - (n - 1) * 3) / n);
            for (int i = 0; i < n; i++) {
                cardBox(g, host, r.builds().get(i), i, x, top + i * (ch + 3), lw, ch, mx, my, false);
            }
            details(g, host, r.builds().get(card), x + lw + 8, top, w - lw - 8, bodyH, 16);
        } else {
            // narrow: the cards two by two on top, the details under them
            int cols = Math.min(2, n);
            int rowsN = (n + cols - 1) / cols;
            int cw = (w - (cols - 1) * 3) / cols;
            int ch = SMALL_CARD_H;
            for (int i = 0; i < n; i++) {
                cardBox(g, host, r.builds().get(i), i, x + (i % cols) * (cw + 3), top + (i / cols) * (ch + 3), cw, ch, mx, my, true);
            }
            int used = rowsN * (ch + 3) + 2;
            details(g, host, r.builds().get(card), x, top + used, w, bodyH - used, 12);
        }
        footer(g, host, x, footY, w, mx, my, true);
    }

    /** A card's height: its score line, its price line and the row of item icons. */
    private static final int CARD_H = 47;
    /** A compact card's: the score line, then the 12px icons with the price beside them. */
    private static final int SMALL_CARD_H = 34;

    /**
     * One build's card: rank and score with its label, the price (coin and amount), and its nine
     * pieces as item icons in the panel's slot order, each framed by what it is (purple a chosen
     * item, green a change from what you wear, plain unchanged); an icon's hover is the item's tooltip.
     */
    private void cardBox(GuiGraphics g, Host host, Roadmap.Candidate c, int i, int x, int y, int w, int h, int mx, int my, boolean small) {
        boolean on = i == card;
        boolean hov = in(mx, my, x, y, w, h);
        Kit.card(g, x, y, w, h, 0, on || hov);
        if (on) {
            Kit.outline(g, x, y, w, h, CHOSEN);
        }
        String score = "#" + (i + 1) + " · " + String.format(Locale.ROOT, "%,d", Math.round(c.score()));
        if (small && width(score, true) > w / 2) {
            score = "#" + (i + 1) + " · " + shortNum(c.score());
        }
        Kit.text(g, font, Component.literal(score).withStyle(UiFont::bold), x + 5, y + 4, Theme.INK);
        int sw = width(score, true);
        if (!c.labels().isEmpty()) {
            // the label as a small icon on a seal of its colour (star, coin, shield, shoe); its name in the tooltip
            String l = c.labels().get(0);
            Kit.seal(g, x + w - 4 - 14, y + 3, 14, 11, labelColour(l));
            Art.icon(g, labelIcon(l), x + w - 4 - 11, y + 4, 8, 0xFFFFFFFF);
            host.tip(x + w - 18, y + 3, 14, 11, List.of(L.c(labelKey(l, false)).withStyle(ChatFormatting.WHITE),
                    L.c(labelKey(l, true)).withStyle(ChatFormatting.GRAY)));
        }
        int s = small ? 12 : 16;
        int iy = small ? y + 17 : y + h - s - 5;
        int iconsW = icons(g, host, x + 5, iy, s, c.set(), c.fixed(), worn(), w - 10);
        // the price: beside the icons on a compact card, on its own line otherwise
        int px = small ? x + 5 + iconsW + 4 : x + 5;
        int py = small ? iy + (s - 8) / 2 : y + 16;
        price(g, c.price(), px, py, x + w - 4 - px);
        btn(x, y, w, h, "card", i);
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(score).withStyle(ChatFormatting.WHITE));
        for (String l : c.labels()) {
            tip.add(L.c(labelKey(l, true)).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        tip.add(L.c("wynnlabs.results.squares_tip").withStyle(ChatFormatting.DARK_GRAY));
        // the card's own tooltip on its text only: the icons have the items'
        host.tip(x, y, w, iy - y - 1, tip);
    }

    /** "1.2 LE" after the coin, "nothing to buy", or "price unknown", within {@code room}. */
    private void price(GuiGraphics g, double price, int x, int y, int room) {
        if (Double.isNaN(price) || price <= 0) {
            String t = L.t(Double.isNaN(price) ? "wynnlabs.results.price_unknown" : "wynnlabs.results.price_free");
            Kit.text(g, font, fit(t, room, false), x, y, Theme.INK_3);
            return;
        }
        if (room < 20) {
            return;
        }
        Art.icon(g, "coin", x, y, 8, Theme.PRICE);
        Kit.text(g, font, fit(Prices.fmt(price), room - 10, false), x + 10, y, Theme.PRICE);
    }

    private static String labelIcon(String l) {
        return switch (l) {
            case "best" -> "star-filled";
            case "cheap" -> "coin";
            case "tanky" -> "shield";
            default -> "shoe";
        };
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

    private static Map<String, String> wornCache = Map.of();
    private static long wornAt;

    /** What you wear now, by build slot (names); read again each second. */
    private static Map<String, String> worn() {
        long now = System.currentTimeMillis();
        if (now - wornAt < 1000) {
            return wornCache;
        }
        Map<String, String> out = new LinkedHashMap<>();
        Build.Plan p = Build.plan();
        if (p != null && p.worn() != null) {
            p.worn().forEach((s, piece) -> {
                if (piece != null) {
                    out.put(s, piece.name());
                }
            });
        }
        wornCache = out;
        wornAt = now;
        return out;
    }

    /** The item database's piece of this name (looked up once). */
    private static final Map<String, java.util.Optional<ItemData>> INFO = new java.util.concurrent.ConcurrentHashMap<>();

    static ItemData itemOf(String name) {
        return INFO.computeIfAbsent(name, n -> GameState.current().allGear().filter(i -> i.name().equals(n)).findFirst()).orElse(null);
    }

    /**
     * The nine pieces as item icons of {@code s} pixels in the panel's slot order, each in a 1px
     * frame: purple a chosen item, green a change from what you wear, the slot's edge colour when
     * it stays; an empty slot is its frame alone. Each icon's hover is the item's own tooltip
     * with where it comes from. Returns the width drawn; fewer gaps when {@code room} is tight.
     */
    private int icons(GuiGraphics g, Host host, int x, int y, int s, Map<String, Damage.Piece> set, java.util.Set<String> fixed,
            Map<String, String> worn, int room) {
        int gap = room >= 9 * (s + 2) + 8 * 2 ? 2 : 1;
        int step = s + 2 + gap;
        for (int i = 0; i < Plans.SLOTS.length; i++) {
            String slot = Plans.SLOTS[i];
            Damage.Piece p = set.get(slot);
            String name = p == null ? null : p.name();
            boolean locked = fixed.contains(slot);
            int frame = locked ? CHOSEN : name != null && !name.equals(worn.get(slot)) ? Theme.GREEN : Theme.INK_3;
            int fx = x + i * step;
            // the kit's item slot; its edge in the slot's colour (none when it stays as you wear it)
            Kit.slot(g, fx, y - 1, s + 2, frame == Theme.INK_3 ? 0 : frame);
            if (name == null) {
                continue;
            }
            ItemData info = itemOf(name);
            ItemStack icon = info == null ? ItemStack.EMPTY : host.icon(info);
            if (!icon.isEmpty()) {
                renderIcon(g, icon, fx + 1, y, s);
                host.item(fx, y - 1, s + 2, s + 2, info, icon, itemExtra(name, locked, p.stack() != null));
                if (locked && firstIcon == null) {
                    firstIcon = new int[] {fx, y - 1, s + 2, s + 2};
                }
            }
        }
        return 9 * step - gap;
    }

    /** An item icon at 16 or 12 pixels (scaled down whole). */
    private static void renderIcon(GuiGraphics g, ItemStack stack, int x, int y, int s) {
        if (s == 16) {
            g.renderItem(stack, x, y);
            return;
        }
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(s / 16f, s / 16f);
        g.renderItem(stack, 0, 0);
        g.pose().popMatrix();
    }

    /**
     * An item's tooltip from the item data, for when Wynntils has no stack to draw one (the
     * preview, a Wynntils change): its name in its tier's colour, tier, type and level, the
     * class and skill requirements, the base stats and the identifications' ranges.
     */
    static List<Component> itemTooltip(ItemData info) {
        List<Component> out = new ArrayList<>();
        out.add(Component.literal(info.name()).withStyle(info.tier().colour()).withStyle(UiFont::bold));
        out.add(Component.literal(nice(info.tier().name()) + " " + typeOf(info)).withStyle(info.tier().colour()));
        out.add(Component.literal("Combat Lv. Min: " + info.level()).withStyle(ChatFormatting.GRAY));
        info.requirements().klass().ifPresent(k -> out.add(Component.literal("Class Req: " + nice(k.name())).withStyle(ChatFormatting.GRAY)));
        for (var r : info.requirements().skills()) {
            out.add(Component.literal(r.skill().displayName() + " Min: " + r.points()).withStyle(ChatFormatting.GRAY));
        }
        var base = info.base();
        if (base.health() != 0) {
            out.add(Component.literal("❤ Health: " + (base.health() > 0 ? "+" : "") + base.health()).withStyle(ChatFormatting.DARK_RED));
        }
        for (var d : base.damages()) {
            out.add(Component.literal(nice(d.type()) + " Damage: " + d.low() + "-" + d.high()).withStyle(ChatFormatting.GOLD));
        }
        for (var d : base.defences()) {
            out.add(Component.literal(nice(d.element()) + " Defence: " + (d.value() > 0 ? "+" : "") + d.value()).withStyle(ChatFormatting.GRAY));
        }
        base.attackSpeed().ifPresent(a -> out.add(Component.literal(nice(a.replace('_', ' ')) + " Attack Speed").withStyle(ChatFormatting.GRAY)));
        int shown = 0;
        for (var st : info.stats()) {
            if (shown++ >= 14) {
                out.add(Component.literal("…").withStyle(ChatFormatting.DARK_GRAY));
                break;
            }
            String unit = "PERCENT".equals(st.unit()) ? "%" : "";
            String range = st.fixed() ? signed(st.min()) + unit : signed(st.min()) + unit + " to " + signed(st.max()) + unit;
            boolean good = st.inverted() ? st.max() <= 0 : st.min() >= 0;
            out.add(Component.literal(range + " " + st.name()).withStyle(good ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
        base.majorId().ifPresent(m -> out.add(Component.literal("+" + m).withStyle(ChatFormatting.AQUA)));
        return out;
    }

    private static String signed(int v) {
        return v > 0 ? "+" + v : String.valueOf(v);
    }

    /** The lines under an item's own tooltip: where it comes from, and "chosen by you" for a chosen one. */
    private List<Component> itemExtra(String name, boolean locked, boolean ownedStack) {
        List<Component> out = new ArrayList<>();
        boolean have = ownedStack || haveNames().contains(name) || worn().containsValue(name);
        Object[] src = source(name);
        String where = have ? L.t("wynnlabs.results.have")
                : src[1] != null ? L.t("wynnlabs.results.market", Prices.fmt((double) src[1])) : (String) src[0];
        out.add(Component.literal(where).withStyle(have ? ChatFormatting.GREEN : ChatFormatting.DARK_AQUA));
        if (locked) {
            out.add(L.c("wynnlabs.results.chosen_by_you").withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        return out;
    }

    /**
     * The picked build: its nine pieces as icons, what changes against what you wear, its skill
     * points, the equip order, and what to get (each with its icon and where it comes from, a
     * price after the coin). {@code s}: the icon size.
     */
    private void details(GuiGraphics g, Host host, Roadmap.Candidate c, int x, int y, int w, int h, int s) {
        Kit.outline(g, x, y, w, h, Theme.LINE);
        int ix = x + 5;
        int iw = w - 10;
        int ly = y + 5;
        int bottom = y + h - 4;
        Map<String, String> worn = worn();
        icons(g, host, ix, ly + 1, s, c.set(), c.fixed(), worn, iw);
        ly += s + 6;
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
        // the changes are against what you wear now
        String vs = L.t("wynnlabs.results.vs_now");
        int dx = ix + font.width(vs) + 6;
        Kit.text(g, font, vs, ix, ly, Theme.INK_3);
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
        ly += 12;
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
        java.util.Set<String> have = haveNames();
        List<String> slots = new ArrayList<>();
        for (String slot : Plans.SLOTS) {
            Damage.Piece p = c.set().get(slot);
            if (p != null && !p.name().equals(worn.get(slot))) {
                slots.add(slot);
            }
        }
        if (slots.isEmpty()) {
            Kit.text(g, font, fit(L.t("wynnlabs.results.nothing_to_get"), iw, false), ix, ly, Theme.GOOD);
            return;
        }
        int lineH = s + 2;
        for (int i = 0; i < slots.size(); i++) {
            if (ly + lineH > bottom || ly + 2 * lineH > bottom && i < slots.size() - 1) {
                int left = slots.size() - i;
                Kit.text(g, font, L.n("wynnlabs.results.more", left, left), ix, ly + (lineH - 8) / 2, Theme.INK_3);
                break;
            }
            String slot = slots.get(i);
            Damage.Piece p = c.set().get(slot);
            boolean locked = c.fixed().contains(slot);
            boolean owned = p.stack() != null || have.contains(p.name());
            ItemData info = itemOf(p.name());
            ItemStack icon = info == null ? ItemStack.EMPTY : host.icon(info);
            if (!icon.isEmpty()) {
                renderIcon(g, icon, ix, ly, s);
                host.item(ix, ly, s, s, info, icon, itemExtra(p.name(), locked, p.stack() != null));
            }
            int tx = ix + s + 4;
            int ty = ly + (s - 8) / 2;
            Component nm = Component.literal(p.name()).withStyle(info == null ? ChatFormatting.WHITE : info.tier().colour());
            String nameText = fit(p.name(), Math.max(30, iw - (tx - ix)) * 55 / 100, false);
            nm = Component.literal(nameText).withStyle(nm.getStyle());
            Kit.text(g, font, nm, tx, ty, Theme.INK);
            tx += font.width(nm);
            if (locked) {
                Art.icon(g, "lock", tx + 3, ty, 8, CHOSEN);
                tx += 11;
            }
            int room = ix + iw - tx - 10;
            if (owned) {
                Kit.text(g, font, fit("— " + L.t("wynnlabs.results.have"), room + 6, false), tx + 4, ty, Theme.GOOD);
            } else {
                Object[] src = source(p.name());
                if (src[1] != null) {
                    Kit.text(g, font, "—", tx + 4, ty, Theme.INK_3);
                    int cx = tx + 4 + font.width("— ");
                    price(g, (double) src[1], cx, ty, ix + iw - cx);
                } else {
                    Kit.text(g, font, fit("— " + src[0], room + 6, false), tx + 4, ty, Theme.INK_3);
                }
            }
            ly += lineH;
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

    private static java.util.Set<String> haveCache = java.util.Set.of();
    private static long haveAt;

    /** Names of what you have in the bag and bank; read again each second. */
    private static java.util.Set<String> haveNames() {
        long now = System.currentTimeMillis();
        if (now - haveAt < 1000) {
            return haveCache;
        }
        haveAt = now;
        java.util.Set<String> out = new java.util.HashSet<>();
        haveCache = out;
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

    /**
     * The icon of where a piece comes from, by the same order as {@link #source}: this week's
     * pool (the raid trophy), the Trade Market (a coin), a merchant (the cart), a quest or a quest
     * item (the scroll), a cave or loot chest (the box), mobs (the sword), a dungeon (the
     * skull); a question mark when nothing is known.
     */
    static String sourceIcon(String name) {
        if (poolOf(name) != null) {
            return "trophy";
        }
        ItemData info = itemOf(name);
        if (info == null) {
            return "question-mark";
        }
        boolean tradable = Shopping.tradable(info);
        if (tradable) {
            Prices.Price p = GameState.current().price(name);
            if (p != null && p.typical() != null) {
                return "coin";
            }
        }
        String src = Shopping.sourceOf(info);
        boolean known = src != null && !src.isBlank() && !src.toUpperCase(Locale.ROOT).startsWith("UNKNOWN");
        if (known) {
            return switch (Shopping.kind(src)) {
                case "merchant" -> "shopping-cart";
                case "quest" -> "script";
                case "chest" -> "box";
                case "drop" -> "sword";
                case "dungeon" -> "skull";
                default -> "question-mark";
            };
        }
        if ("QUEST_ITEM".equals(info.restriction())) {
            return "script";
        }
        return tradable ? "coin" : "question-mark";
    }

    /**
     * One more line about where a piece comes from, under {@link #sourceText}, or null: the mob
     * area of a drop or the region of a chest (the line itself names neither), or, when the
     * line is the Trade Market's price, the place it also comes from.
     */
    static String sourceDetail(String name) {
        if (poolOf(name) != null) {
            return null;
        }
        ItemData info = itemOf(name);
        if (info == null) {
            return null;
        }
        String src = Shopping.sourceOf(info);
        if (src == null || src.isBlank() || src.toUpperCase(Locale.ROOT).startsWith("UNKNOWN")) {
            return null;
        }
        if (source(name)[1] != null) {
            return L.t("wynnlabs.homev.source_also", Shopping.where(src));
        }
        String[] parts = src.split(": ", 2);
        String place = parts.length > 1 ? parts[1].trim() : "";
        if (place.isEmpty()) {
            return null;
        }
        return switch (parts[0]) {
            case "NORMAL_MOB_DROP", "MOB_REGION" -> L.t("wynnlabs.homev.source_area", place);
            case "CAVE_LOOT_CHEST", "CAVE_COMPLETION", "LOOT_CHEST" -> L.t("wynnlabs.homev.source_region", place);
            default -> null;
        };
    }

    /** Where a piece comes from, as one line (a Trade Market price written out). */
    static String sourceText(String name) {
        Object[] s = source(name);
        return s[1] != null ? L.t("wynnlabs.results.market", Prices.fmt((double) s[1])) : (String) s[0];
    }

    /**
     * Where a piece comes from: {text, Trade Market price (null when none)}: this week's raid
     * pool, else its Trade Market price, a quest, its merchant, chest or drop; with no known
     * source, a quest item as a quest reward, an untradable piece as such, a tradable one as
     * the Trade Market with no price; "price unknown" only when nothing is known.
     */
    static Object[] source(String name) {
        String pool = poolOf(name);
        if (pool != null) {
            return new Object[] {L.t("wynnlabs.results.in_pool", pool), null};
        }
        ItemData info = itemOf(name);
        if (info == null) {
            return new Object[] {L.t("wynnlabs.results.price_unknown"), null};
        }
        String src = Shopping.sourceOf(info);
        boolean tradable = Shopping.tradable(info);
        if (tradable) {
            Prices.Price p = GameState.current().price(name);
            if (p != null && p.typical() != null) {
                return new Object[] {"", p.typical()};
            }
        }
        boolean known = src != null && !src.isBlank() && !src.toUpperCase(Locale.ROOT).startsWith("UNKNOWN");
        if (known && src.startsWith("QUEST")) {
            return new Object[] {L.t("wynnlabs.results.quest", Shopping.where(src)), null};
        }
        if (known) {
            return new Object[] {L.cap(Shopping.where(src)), null};
        }
        if ("QUEST_ITEM".equals(info.restriction())) {
            return new Object[] {L.t("wynnlabs.results.quest_reward"), null};
        }
        if (!tradable) {
            return new Object[] {L.t("wynnlabs.results.untradable"), null};
        }
        return new Object[] {L.t("wynnlabs.results.market_unknown"), null};
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
        int aw = controlW("check", apply, w / 2);
        int ax = x + w - aw;
        if (any) {
            control(g, host, ax, y, 14, "check", apply, PRIMARY, false, w / 2, "apply", null,
                    List.of(L.c("wynnlabs.results.apply_tip").withStyle(ChatFormatting.GRAY)), mx, my);
        }
        int cx = any ? ax - 4 - ICON_W : x + w - ICON_W;
        control(g, host, cx, y, 14, "x", L.t("wynnlabs.pick.cancel"), WOOD, false, 0, "close", null,
                List.of(L.c("wynnlabs.results.cancel_tip").withStyle(ChatFormatting.GRAY)), mx, my);
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
        int editH = editId == null ? 0 : 54;
        int listH = footY - (editId == null ? 4 : 8) - editH - top;
        Plans.Build current = Plans.current();
        Map<String, String> worn = worn();
        Kit.outline(g, x, top, w, listH, Theme.LINE);
        int rows = Math.max(1, (listH - 2) / SAVED_ROW_H);
        int max = Math.max(0, all.size() - rows);
        savedScroll = Math.max(0, Math.min(savedScroll, max));
        int rw = w - 2 - (max > 0 ? ScrollBar.W + 2 : 0);
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
                {"edit", "edit", "wynnlabs.saved.edit_tip"}, {"check", "applySaved", "wynnlabs.saved.apply_tip"}};
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
        savedBar.place(x + w - ScrollBar.W - 1, top + 1, listH - 2, rows, all.size(), savedScroll);
        savedBar.draw(g, mx, my);
        if (savedBar.shown()) {
            int[] t = savedBar.track();
            btn(t[0], t[1], t[2], t[3], "scrollbar", null);
        }
        if (editId != null) {
            drawEdit(g, host, x, top + listH + 4, w, editH, mx, my);
        }
        // the footer: "+ Save current plan", and the undo line after a delete
        boolean can = current != null;
        List<Component> addTip = List.of(L.c(can ? "wynnlabs.saved.add_tip" : "wynnlabs.saved.add_none").withStyle(ChatFormatting.GRAY));
        int aw = control(g, host, x, footY, 14, "plus", L.t("wynnlabs.saved.add"), can ? PRIMARY : WOOD, false, w / 2,
                can ? "addCurrent" : null, null, addTip, mx, my);
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
        int plusW = controlW("plus", L.t("wynnlabs.saved.add_item"), 70);
        for (String n : editLocks) {
            int cw = Math.min(font.width(n) + 18, 110);
            if (cx + cw + 3 + plusW > x + w - 5) {
                break;
            }
            chip(g, cx, cy, cw, 14, in(mx, my, cx, cy, cw, 14));
            Kit.text(g, font, fit(n, cw - 18, false), cx + 5, cy + 3, Theme.INK);
            Art.icon(g, "x", cx + cw - 11, cy + 3, 8, CHOSEN);
            btn(cx, cy, cw, 14, "editUnlock", n);
            host.tip(cx, cy, cw, 14, List.of(Component.literal(n).withStyle(ChatFormatting.LIGHT_PURPLE),
                    L.c("wynnlabs.pick.chip_tip").withStyle(ChatFormatting.DARK_GRAY)));
            cx += cw + 3;
        }
        control(g, host, cx, cy, 14, "plus", L.t("wynnlabs.saved.add_item"), WOOD, false, 70, "editAdd", null,
                List.of(L.c("wynnlabs.saved.add_item_tip").withStyle(ChatFormatting.GRAY)), mx, my);
        cy += 17;
        // the buttons on the right of the strip's summary: Recompute (purple), Save as new (a disk), Cancel (✕)
        int bx = x + w - 5;
        int rw = controlW("refresh", L.t("wynnlabs.saved.recompute"), w / 2);
        bx -= rw;
        control(g, host, bx, cy, 14, "refresh", L.t("wynnlabs.saved.recompute"), PRIMARY, false, w / 2, "editRecompute", null,
                List.of(L.c("wynnlabs.saved.recompute_tip").withStyle(ChatFormatting.GRAY)), mx, my);
        bx -= 3 + ICON_W;
        control(g, host, bx, cy, 14, "device-floppy", L.t("wynnlabs.saved.save_new"), WOOD, false, 0, "editSaveNew", null,
                List.of(L.c("wynnlabs.saved.save_new_tip").withStyle(ChatFormatting.GRAY)), mx, my);
        bx -= 3 + ICON_W;
        control(g, host, bx, cy, 14, "x", L.t("wynnlabs.pick.cancel"), WOOD, false, 0, "editCancel", null,
                List.of(L.c("wynnlabs.saved.edit_cancel_tip").withStyle(ChatFormatting.GRAY)), mx, my);
        bx -= 3;
        cy += 1;
        Kit.text(g, font, fit(summary(editTuning), bx - 4 - (x + 5), false), x + 5, cy + 2, Theme.INK_3);
    }

    // =====================================================================================
    // input

    boolean mouseClicked(double mx, double my, int button) {
        if (!open) {
            return false;
        }
        // the scrollbars first: a press there never reaches the rows
        ScrollBar bar = view == View.PICK ? listBar : view == View.SAVED ? savedBar : null;
        if (bar != null && button == 0 && bar.mouseClicked(mx, my)) {
            syncScroll();
            return true;
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
            case "close" -> {
                if (view == View.RESULTS) {
                    discardResults();               // Cancel and ✕ drop the list: the picker next time
                }
                close();
            }
            case "search" -> search.setFocused(true);
            case "type" -> {
                type = (int) hit.arg();
                listScroll = 0;
            }
            case "range" -> {
                range = (range + (button == 1 ? RANGES.length - 1 : 1)) % RANGES.length;
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
                discardResults();
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
            discardResults();
            view = View.SAVED;
            if (lastHost != null) {
                lastHost.applied();
            }
            return;
        }
        if (editingPlan != null && !editingPlan.isEmpty() && Plans.byId(editingPlan) != null) {
            // the plan's Edit: its saved copy follows (name kept), and it stays the plan
            Plans.Build kept = Plans.overwrite(editingPlan, fresh, now);
            Plans.apply(kept);
        } else {
            Plans.apply(fresh.named(Plans.defaultName(fresh)));
        }
        editingPlan = null;
        discardResults();                           // applied: the plan holds it now
        hostApplied();
    }

    private void saveRename(long now) {
        if (renameId != null) {
            Plans.rename(renameId, rename.getValue(), now);
        }
        renameId = null;
        rename.setFocused(false);
    }

    /** A drag with the button held: a scrollbar's thumb follows it. True when a bar took it. */
    boolean mouseDragged(double mx, double my) {
        if (!open) {
            return false;
        }
        boolean took = listBar.mouseDragged(my) || savedBar.mouseDragged(my);
        if (took) {
            syncScroll();
        }
        return took;
    }

    /** The button let go: any drag ends. */
    void mouseReleased() {
        listBar.mouseReleased();
        savedBar.mouseReleased();
    }

    /** The lists follow their bars. */
    private void syncScroll() {
        if (view == View.PICK) {
            listScroll = listBar.offset();
        } else if (view == View.SAVED) {
            savedScroll = savedBar.offset();
        }
    }

    /** The preview and tests: the Choose-items list's bar and where the list is. */
    ScrollBar listBar() {
        return listBar;
    }

    int listScroll() {
        return listScroll;
    }

    private final ScrollBar listBar = new ScrollBar();
    private final ScrollBar savedBar = new ScrollBar();

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
                if (view == View.RESULTS) {
                    discardResults();
                }
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

    /** A wood button (Kit.button). */
    static final int WOOD = 0;
    /** The purple primary button (Kit.primary). */
    static final int PRIMARY = 1;
    /** An icon-only button's width. */
    private static final int ICON_W = 16;

    /**
     * One control in the mod's button sprites: {@code text} after the icon when the whole of it
     * fits {@code maxW} comfortably, else the icon alone, centred, with the label as the first
     * tooltip line (the one rule for every control here: text when it fits, never cut). Returns
     * its width. {@code on}: a selected wood control (gold); {@code text} null: always the icon.
     */
    private int control(GuiGraphics g, Host host, int x, int y, int h, String icon, String text, int kind, boolean on, int maxW,
            String act, Object arg, List<Component> tip, int mx, int my) {
        int full = text == null ? Integer.MAX_VALUE : (icon == null ? 0 : 11) + font.width(text) + 12;
        boolean words = text != null && full <= maxW;
        int w = words ? full : ICON_W;
        boolean hov = in(mx, my, x, y, w, h);
        int state = on ? Kit.ON : hov ? Kit.HOVER : Kit.REST;
        if (kind == PRIMARY) {
            Kit.primary(g, x, y, w, h, hov);
        } else {
            Kit.button(g, x, y, w, h, state);
        }
        int ink = kind == PRIMARY ? Theme.CREAM : Kit.buttonText(state);
        int iy = y + (h - 8) / 2;
        if (words) {
            int tx = x + 6;
            if (icon != null) {
                Art.icon(g, icon, tx, iy, 8, ink);
                tx += 11;
            }
            g.drawString(font, text, tx, y + (h - 8) / 2, ink, false);
        } else if (icon != null) {
            Art.icon(g, icon, x + (w - 8) / 2, iy, 8, ink);
        }
        if (act != null) {
            btn(x, y, w, h, act, arg);
        }
        List<Component> lines = new ArrayList<>();
        if (!words && text != null) {
            lines.add(Component.literal(text).withStyle(ChatFormatting.WHITE));
        }
        if (tip != null) {
            lines.addAll(tip);
        }
        if (!lines.isEmpty()) {
            host.tip(x, y, w, h, lines);
        }
        return w;
    }

    /** {@link #control}'s width without drawing it. */
    private int controlW(String icon, String text, int maxW) {
        int full = text == null ? Integer.MAX_VALUE : (icon == null ? 0 : 11) + font.width(text) + 12;
        return text != null && full <= maxW ? full : ICON_W;
    }

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
