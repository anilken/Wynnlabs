package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.ItemData;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The gear panel's window over the menu (a dimmed menu, a parchment page in its middle) with three
 * views: PICK, the items to build around from the game's whole item database (search, type
 * pills, the list of what the character can use, the tuning strip, the chosen chips and "Suggest
 * build", with a loading view while the search runs); RESULTS, the ranked builds with the
 * selected one's details and "Apply build N"; SAVED, the saved builds with Apply, Edit, Rename and
 * Delete. Esc closes a rename or the window, never the menu. Nothing here acts in the game: the
 * search reads the item data and your gear, Apply only changes the plan the panel shows.
 *
 * <p>This class holds the window's state, the screen's calls and the input; the views draw in
 * {@link BuildPickView}, {@link BuildResultsView} and {@link BuildSavedView}, with the shared
 * controls in {@link BuildControls}, the item sources in {@link BuildSources} and the text in
 * {@link BuildText}.
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

    private static final int PAD = 8;

    private boolean open;
    /** The compact menu this frame: the result view in one column. */
    boolean compact;
    /** The preview: the first chosen item's icon drawn this frame (x, y, w, h), for a hover shot. */
    int[] firstIcon;
    private View view = View.PICK;
    EditBox search;
    EditBox rename;

    private final BuildControls ui = new BuildControls();
    private final BuildPickView pick = new BuildPickView(this, ui);
    private final BuildResultsView results = new BuildResultsView(this, ui);
    private final BuildSavedView saved = new BuildSavedView(this, ui);

    // ---- PICK ----
    List<ItemData> chosen = new ArrayList<>();
    Tuning.Choice tuning = Tuning.Choice.PLAIN;
    /** The generation of the search the window waits for (-1 none). */
    int awaitGen = -1;

    // ---- RESULTS ----
    Around.Result result;
    int card;
    /** A saved build the picked result overwrites (Recompute), or null. */
    String overwriteId;

    // ---- SAVED ----
    String renameId;
    String editId;
    List<String> editLocks = List.of();
    Tuning.Choice editTuning = Tuning.Choice.PLAIN;

    private BuildWindow() {}

    // =====================================================================================
    // the screen's calls

    /** The window's text boxes, for the screen to add as widgets (init: again after a resize). */
    List<EditBox> init(Font f) {
        ui.font = f;
        String typed = search == null ? "" : search.getValue();
        search = new EditBox(f, 0, 0, 100, 12, L.c("wynnlabs.pick.search"));
        search.setMaxLength(40);
        search.setValue(typed);
        search.setResponder(v -> pick.listScroll = 0);
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

    /** An item's tooltip from the item data (BuildText). */
    static List<Component> itemTooltip(ItemData info) {
        return BuildText.itemTooltip(info);
    }

    /** Where a piece comes from (BuildSources). */
    static String sourceIcon(String name) {
        return BuildSources.sourceIcon(name);
    }

    static String sourceDetail(String name) {
        return BuildSources.sourceDetail(name);
    }

    static String sourceText(String name) {
        return BuildSources.sourceText(name);
    }

    static Object[] source(String name) {
        return BuildSources.source(name);
    }

    static List<String> names(List<ItemData> items) {
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
        for (BuildControls.Btn b : ui.btns) {
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
        ui.btns.clear();
        if (!open) {
            return;
        }
        ui.font = UiFont.get();
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
        // (the dev screenshot tool, while it runs: the dim in the frame's own shape, its corners clear)
        if (!DevHooks.ON || !DevHooks.dim(g, menu[0] - 5, menu[1] - 5, menu[2] + 10, menu[3] + 10, 0xA0000000)) {
            g.fill(menu[0] - 5, menu[1] - 5, menu[0] + menu[2] + 5, menu[1] + menu[3] + 5, 0xA0000000);
        }
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
        boolean hover = BuildControls.in(mx, my, x + w - 11, y - 1, 11, 11);
        Kit.close(g, x + w - 11, y - 1, 11, hover);
        ui.btn(x + w - 11, y - 1, 11, 11, "close", null);
        host.tip(x + w - 11, y - 1, 11, 11, List.of(L.c("wynnlabs.pick.close_tip").withStyle(ChatFormatting.GRAY)));
        switch (view) {
            case PICK -> pick.drawPick(g, host, x, y, w - 14, w, h, mx, my, pt);
            case RESULTS -> results.drawResults(g, host, x, y, w, h, mx, my);
            case SAVED -> saved.drawSaved(g, host, x, y, w, h, mx, my, pt);
        }
    }

    // =====================================================================================
    // input

    boolean mouseClicked(double mx, double my, int button) {
        if (!open) {
            return false;
        }
        // the scrollbars first: a press there never reaches the rows
        ScrollBar bar = view == View.PICK ? pick.listBar : view == View.SAVED ? saved.savedBar : null;
        if (bar != null && button == 0 && bar.mouseClicked(mx, my)) {
            syncScroll();
            return true;
        }
        BuildControls.Btn hit = null;
        for (int i = ui.btns.size() - 1; i >= 0; i--) {
            BuildControls.Btn b = ui.btns.get(i);
            if (BuildControls.in(mx, my, b.x(), b.y(), b.w(), b.h())) {
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
                pick.type = (int) hit.arg();
                pick.listScroll = 0;
            }
            case "range" -> {
                pick.range = (pick.range + (button == 1 ? BuildPickView.RANGES.length - 1 : 1)) % BuildPickView.RANGES.length;
                pick.listScroll = 0;
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
                    saved.savedScroll = Integer.MAX_VALUE;            // scrolled to it (clamped when drawn)
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
                    saved.savedScroll = Integer.MAX_VALUE;
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
        boolean took = pick.listBar.mouseDragged(my) || saved.savedBar.mouseDragged(my);
        if (took) {
            syncScroll();
        }
        return took;
    }

    /** The button let go: any drag ends. */
    void mouseReleased() {
        pick.listBar.mouseReleased();
        saved.savedBar.mouseReleased();
    }

    /** The lists follow their bars. */
    private void syncScroll() {
        if (view == View.PICK) {
            pick.listScroll = pick.listBar.offset();
        } else if (view == View.SAVED) {
            saved.savedScroll = saved.savedBar.offset();
        }
    }

    /** The preview and tests: the Choose-items list's bar and where the list is. */
    ScrollBar listBar() {
        return pick.listBar;
    }

    int listScroll() {
        return pick.listScroll;
    }

    boolean mouseScrolled(double mx, double my, double v) {
        if (!open) {
            return false;
        }
        int step = (int) -Math.signum(v);
        if (view == View.PICK) {
            pick.listScroll = Math.max(0, pick.listScroll + step * 3);
        } else if (view == View.SAVED) {
            saved.savedScroll = Math.max(0, Math.min(saved.savedScroll, Math.max(0, Plans.saved().size() - 1)) + step);
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
        pick.type = 0;
        pick.listScroll = 0;
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
        saved.savedScroll = scroll;
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
}
