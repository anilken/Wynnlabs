package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.crafting.Craft;
import io.github.anilken.wynnlabs.crafting.CraftData;
import io.github.anilken.wynnlabs.crafting.CraftSearch;
import io.github.anilken.wynnlabs.crafting.Crafting;
import io.github.anilken.wynnlabs.crafting.Ingredient;
import io.github.anilken.wynnlabs.crafting.SetSuggestions;
import io.github.anilken.wynnlabs.crafting.Suggestions;
import io.github.anilken.wynnlabs.crafting.WhatCanICraft;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.game.Wyn;
import io.github.anilken.wynnlabs.model.Klass;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The J menu's crafting tab (openspec crafting-tab, mockup v3): on the left the recipe (type and
 * level lists, materials with tier pickers, the 2×3 grid where ingredients go), on the right the
 * crafted item and the optimiser's three layouts, below them roll chances, the shopping list and
 * "what can I craft now"; the saved recipes in a panel beside the menu, or a drawer when the
 * screen is too narrow. Its state lives here between openings of the menu. It never feeds the
 * build plan, the road map or any advice.
 */
public final class CraftingTab {

    /** One clickable or hoverable area. */
    record Act(int x, int y, int w, int h, Runnable left, Runnable right, List<Component> tip) {
        boolean in(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    enum Drop { NONE, TYPE, LEVEL, SLOT, GOAL, CONDITIONS }

    public static final CraftingTab INSTANCE = new CraftingTab();

    /** For the preview without the game: ingredients to use when Wynntils has none. */
    static volatile List<Ingredient> previewIngredients = List.of();
    /** For the preview: profession levels to use instead of the recording's. */
    static volatile Map<String, Integer> previewLevels = Map.of();

    static final int[] FLOORS = {1, 50, 100, 200, 300};
    static final int TWO_COLUMNS_MIN = 440;

    // ---- the recipe ----
    String type;
    int minLevel;
    int tier1 = 1;
    int tier2 = 1;
    Craft.Speed speed = Craft.Speed.NORMAL;
    final String[] slots = new String[Craft.SLOTS];

    // ---- the goal ----
    final LinkedHashMap<String, Double> weights = new LinkedHashMap<>(Map.of("spellDamage", 1.0));
    final Set<String> noNegative = new LinkedHashSet<>();
    boolean capToPoints;
    int floorIndex;

    // ---- search results ----
    volatile List<CraftSearch.Outcome> results = List.of();
    final List<Suggestions.Suggestion> suggestions = new CopyOnWriteArrayList<>();
    final List<SetSuggestions.CraftSet> sets = new CopyOnWriteArrayList<>();

    // ---- the page ----
    Drop open = Drop.NONE;
    int openSlot = -1;
    int[] openAnchor;
    int dropScroll;
    int scroll;
    private int scrollMax;
    boolean foldWhat;
    int subTab;
    boolean drawerOpen;
    /** The calculator (0) or the suggestions (1). */
    int view;
    /** Suggestions as whole sets (0) or single pieces (1). */
    int suggestMode;
    SetSuggestions.CraftSet setDetail;
    int pageCount = 1;
    /** Whether the calculator is in two columns this frame (one on a narrow page). */
    boolean twoColumns = true;
    List<Ingredient> preview;            // a layout drawn dashed on the grid (hovered row)
    List<Ingredient> nextPreview;

    // ---- caches ----
    List<Ingredient> ingredients = List.of();
    final Map<String, Ingredient> byName = new HashMap<>();
    WhatCanICraft.Stock stock = new WhatCanICraft.Stock(Map.of(), Map.of(), Map.of());
    private long stockAt;
    int[] points = new int[5];
    Map<String, CraftSearch.Price> prices = Map.of();
    /** Ingredients on screen to buy whose price is not known yet: asked for at the next refresh. */
    final Set<String> wantedPrices = new LinkedHashSet<>();

    // ---- per frame ----
    Screen screen;
    Font font;
    EditBox pickSearch;
    EditBox saveName;
    EditBox distBox;
    final List<Act> acts = new ArrayList<>();
    final List<Act> overlay = new ArrayList<>();
    int[] overlayBox;
    int[] clip;
    java.util.function.Consumer<String> flash = s -> {};

    // ---- the page's parts ----
    private final CraftHeaderPanel headerPanel = new CraftHeaderPanel(this);
    final CraftRecipePanel recipePanel = new CraftRecipePanel(this);
    final CraftResultPanel resultPanel = new CraftResultPanel(this);
    final CraftOptimiserPanel optimiserPanel = new CraftOptimiserPanel(this);
    final CraftSectionsPanel sectionsPanel = new CraftSectionsPanel(this);
    final CraftSuggestionsPanel suggestionsPanel = new CraftSuggestionsPanel(this);
    final CraftSetsPanel setsPanel = new CraftSetsPanel(this);
    final CraftSavedPanel savedPanel = new CraftSavedPanel(this);
    private final CraftDropdowns dropdowns = new CraftDropdowns(this);

    private CraftingTab() {}

    /** The text boxes, made again each time the menu opens; the screen adds them as widgets. */
    List<EditBox> init(Screen screen, Font font, java.util.function.Consumer<String> flash) {
        this.screen = screen;
        this.font = font;
        this.flash = flash;
        String typed = pickSearch == null ? "" : pickSearch.getValue();
        pickSearch = new EditBox(font, 0, 0, 100, 12, L.c("wynnlabs.craft.search_hint"));
        pickSearch.setMaxLength(40);
        pickSearch.setValue(typed);
        pickSearch.setResponder(v -> dropScroll = 0);
        paper(pickSearch);
        String name = saveName == null ? "" : saveName.getValue();
        saveName = new EditBox(font, 0, 0, 100, 12, L.c("wynnlabs.craft.saved_name"));
        saveName.setMaxLength(40);
        saveName.setValue(name);
        paper(saveName);
        distBox = new EditBox(font, 0, 0, 30, 12, L.c("wynnlabs.craft.dist_target"));
        distBox.setMaxLength(7);
        distBox.setFilter(s -> s.matches("-?\\d*"));
        distBox.setResponder(v -> {
            try {
                sectionsPanel.distTarget = v.isEmpty() || v.equals("-") ? null : Integer.parseInt(v);
            } catch (NumberFormatException e) {
                sectionsPanel.distTarget = null;
            }
        });
        paper(distBox);
        stockAt = 0;
        return List.of(pickSearch, saveName, distBox);
    }

    private static void paper(EditBox box) {
        box.setBordered(false);
        box.setTextColor(Theme.INK);
        box.setTextShadow(false);
    }

    /** The menu was closed: running searches stop. */
    void closed() {
        optimiserPanel.stop();
        sectionsPanel.stop();
        suggestionsPanel.stop();
        setsPanel.stop();
        setDetail = null;
        open = Drop.NONE;
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        if (now - stockAt < 5000 && type != null) {
            return;
        }
        stockAt = now;
        List<Ingredient> all = Wyn.craftIngredients();
        if (all.isEmpty()) {
            all = previewIngredients;
        }
        if (all != ingredients) {
            ingredients = all;
            byName.clear();
            for (Ingredient i : all) {
                byName.put(i.name(), i);
            }
        }
        try {
            stock = Crafting.stock();
            points = Crafting.points();
        } catch (Throwable t) {
            // a recording without a bank or skills: an empty stock
        }
        prices = Crafting.prices(all);
        if (!wantedPrices.isEmpty()) {
            // ask Wynnventory (with the player's own key, when switched on) for prices shown without one
            List<String> ask = new ArrayList<>();
            for (String n : wantedPrices) {
                if (!prices.containsKey(n)) {
                    ask.add(n);
                }
            }
            wantedPrices.clear();
            if (!ask.isEmpty()) {
                GameState.current().requestPrices(ask);
            }
        }
        if (type == null) {
            type = defaultType();
            minLevel = bestBracket(type).minLevel();
        }
    }

    static String defaultType() {
        Klass k;
        try {
            k = GameState.current().classType();
        } catch (Throwable t) {
            k = Klass.NONE;
        }
        return switch (k) {
            case MAGE -> "wand";
            case ARCHER -> "bow";
            case WARRIOR -> "spear";
            case ASSASSIN -> "dagger";
            case SHAMAN -> "relik";
            default -> "ring";
        };
    }

    /** The highest bracket of a type the profession level allows (the first when none does). */
    CraftData.Recipe bestBracket(String t) {
        List<CraftData.Recipe> rs = CraftData.ofType(t);
        CraftData.Recipe best = rs.get(0);
        int lv = level(rs.get(0).profession());
        for (CraftData.Recipe r : rs) {
            if (r.minLevel() <= lv) {
                best = r;
            }
        }
        return best;
    }

    /** The profession level as read, 0 when not known yet. */
    int knownLevel(String profession) {
        Integer l = previewLevels.isEmpty() ? stock.levels().get(profession) : previewLevels.get(profession);
        return l == null ? 0 : l;
    }

    /**
     * The level that gates recipes and ingredients: an unknown level (Wynntils reads it from the
     * character menu) locks nothing rather than everything.
     */
    int level(String profession) {
        int l = knownLevel(profession);
        return l > 0 ? l : 200;
    }

    /** The profession of the recipe on the calculator ("WOODWORKING"), or null before the tab was opened. */
    String profession() {
        return type == null ? null : recipe().profession().toUpperCase(Locale.ROOT);
    }

    CraftData.Recipe recipe() {
        CraftData.Recipe r = CraftData.find(type, minLevel);
        return r != null ? r : CraftData.ofType(type).get(0);
    }

    Ingredient ing(String name) {
        if (name == null) {
            return Ingredient.NONE;
        }
        Ingredient i = byName.get(name);
        return i == null ? Ingredient.NONE : i;
    }

    List<Ingredient> layout() {
        List<Ingredient> out = new ArrayList<>(Craft.SLOTS);
        for (String s : slots) {
            out.add(ing(s));
        }
        return out;
    }

    CraftSearch.Goal goal() {
        int[] caps = CraftSearch.Goal.uncapped();
        if (capToPoints) {
            caps = points.clone();
        }
        return new CraftSearch.Goal(new LinkedHashMap<>(weights), CraftSearch.Pick.MEAN, Set.copyOf(noNegative), caps,
                FLOORS[floorIndex]);
    }

    int owned(String name) {
        Integer n = stock.ingredients().get(name);
        return n == null ? 0 : n;
    }

    /** What the recipe and goal look like now: a new search is needed when this changes. */
    String key() {
        return type + minLevel + tier1 + tier2 + speed + weights + noNegative + capToPoints + floorIndex;
    }

    /**
     * Draws the page into {@code x, y, w, h}; {@code titleY} is the page title's line (the
     * header's buttons sit on it) and {@code frame} the menu panel {x, y, w, h} (the saved
     * recipes go beside it).
     */
    void draw(GuiGraphics g, int x, int y, int w, int h, int titleY, int[] frame, int screenW, int mx, int my, float pt) {
        acts.clear();
        overlay.clear();
        overlayBox = null;
        preview = nextPreview;
        nextPreview = null;
        refresh();
        headerPanel.drawHeader(g, x, titleY, w, mx, my);
        if (ingredients.isEmpty()) {
            headerPanel.noData(g, x, y, w, h);
            return;
        }
        if (!optimiserPanel.resultsFor.equals(key()) && !results.isEmpty()) {
            results = List.of();                    // the recipe or goal changed: old layouts no longer apply
        }
        // the two pages: the calculator and our suggestions, as ledger tabs over the page
        y += headerPanel.viewTabs(g, x, y, w, mx, my) + 4;
        h -= 18;
        if (view == 1) {
            suggestionsPanel.drawSuggestions(g, x, y, w, h, mx, my);
            savedPanel.drawSaved(g, frame, screenW, x, y, w, h, w >= TWO_COLUMNS_MIN, mx, my);
            showTooltip(g, mx, my);
            return;
        }
        boolean two = w >= TWO_COLUMNS_MIN;
        twoColumns = two;
        clip = new int[] {x, y, w, h};
        g.enableScissor(x, y, x + w, y + h);
        int top = y - scroll;
        int cw = w - 6;                             // room for the scroll bar
        int used;
        if (two) {
            int colW = (cw - 10) / 2;
            int left = recipePanel.drawRecipe(g, x, top, colW, mx, my);
            int rx = x + colW + 10;
            int right = resultPanel.drawResult(g, rx, top, colW, mx, my);
            right += 6 + optimiserPanel.drawOptimiser(g, rx, top + right + 6, colW, mx, my);
            int by = top + Math.max(left, right) + 8;
            by += sectionsPanel.drawSections(g, x, by, cw, mx, my, pt);
            used = by - top;
        } else {
            int sy = top;
            sy += headerPanel.subTabs(g, x, sy, cw, mx, my) + 6;
            switch (subTab) {
                case 1 -> {
                    sy += resultPanel.drawResult(g, x, sy, cw, mx, my) + 6;
                    sy += sectionsPanel.boxed(g, 0, x, sy, cw, (bx, by, bw) -> sectionsPanel.drawDistribution(g, bx, by, bw, mx, my, pt));
                }
                case 2 -> {
                    sy += optimiserPanel.drawOptimiser(g, x, sy, cw, mx, my) + 6;
                    sy += sectionsPanel.boxed(g, 2, x, sy, cw, (bx, by, bw) -> sectionsPanel.drawWhat(g, bx, by, bw, mx, my));
                }
                default -> {
                    sy += recipePanel.drawRecipe(g, x, sy, Math.min(cw, 260), mx, my) + 6;
                    sy += sectionsPanel.boxed(g, 1, x, sy, cw, (bx, by, bw) -> sectionsPanel.drawShopping(g, bx, by, bw, mx, my));
                }
            }
            used = sy - top;
        }
        g.disableScissor();
        scrollMax = Math.max(0, used - h + 4);
        scroll = Math.min(scroll, scrollMax);
        if (scrollMax > 0) {
            int trackH = h;
            int knobH = Math.max(14, trackH * h / (used + 4));
            int knobY = y + (trackH - knobH) * scroll / scrollMax;
            g.fill(x + w - 3, y, x + w - 1, y + h, 0x30624E3C);
            g.fill(x + w - 3, knobY, x + w - 1, knobY + knobH, Theme.WOOD);
        }
        savedPanel.drawSaved(g, frame, screenW, x, y, w, h, two, mx, my);
        dropdowns.drawDrop(g, mx, my, pt);
        showTooltip(g, mx, my);
    }

    Craft.Result result() {
        return Craft.craft(recipe(), tier1, tier2, speed, layout());
    }

    /** What the suggestions are worked out for: a new run is needed when this changes. */
    String suggestKey() {
        return stock.ingredients().hashCode() + "|" + stock.levels() + "|" + defaultType() + "|" + CraftSuggestionsPanel.combatLevel();
    }

    void toggle(Drop d, int slot, int[] anchor) {
        if (open == d && openSlot == slot) {
            open = Drop.NONE;
            return;
        }
        open = d;
        openSlot = slot;
        openAnchor = anchor;
        dropScroll = 0;
        if (d == Drop.LEVEL) {
            List<CraftData.Recipe> rs = CraftData.ofType(type);
            for (int i = 0; i < rs.size(); i++) {
                if (rs.get(i).minLevel() == minLevel) {
                    dropScroll = Math.max(0, i - 4);
                }
            }
        }
    }

    // the preview drives these by name (reflection): they stay here

    private void startSearch() {
        optimiserPanel.startSearch();
    }

    private void startWhat() {
        sectionsPanel.startWhat();
    }

    /** For the preview: start working the suggestions out now. */
    private void startSuggestionsNow() {
        suggestionsPanel.startSuggestions(true);
    }

    /** For the preview: start working the sets out now. */
    private void startSetsNow() {
        setsPanel.startSets(true);
    }

    // ---- input ------------------------------------------------------------------------------

    /** A drag in the tab: moves the slider's knob while it is held; true when it did. */
    boolean mouseDragged(double mx, double my) {
        return sectionsPanel.mouseDragged(mx, my);
    }

    void mouseReleased() {
        sectionsPanel.mouseReleased();
    }

    /**
     * For the preview's layout check: the click areas of this frame. With an overlay open only its
     * own (it takes every click); a tip-only area is not one.
     */
    List<LayoutCheck.Area> clickAreas() {
        List<LayoutCheck.Area> out = new ArrayList<>();
        for (Act a : overlayBox != null ? overlay : acts) {
            if (a.left() != null || a.right() != null) {
                out.add(new LayoutCheck.Area(a.x(), a.y(), a.w(), a.h(),
                        a.tip() != null && !a.tip().isEmpty() ? a.tip().get(0).getString() : "act@" + a.x() + "," + a.y()));
            }
        }
        return out;
    }

    boolean mouseClicked(double mx, double my, int button) {
        sectionsPanel.lastX = mx;
        if (overlayBox != null) {
            Runnable r = target(overlay, mx, my, button);
            if (r != null) {
                r.run();
                return true;
            }
            if (!CraftPanel.in(mx, my, overlayBox[0], overlayBox[1], overlayBox[2], overlayBox[3])) {
                boolean wasDrawer = drawerOpen && open == Drop.NONE;
                open = Drop.NONE;
                drawerOpen = false;
                setDetail = null;
                pickSearch.setFocused(false);
                if (!wasDrawer) {
                    return true;                    // a click outside closes the list, nothing else
                }
            } else {
                return true;
            }
        }
        Runnable r = target(acts, mx, my, button);
        if (r != null) {
            r.run();
            return true;
        }
        if (saveName.isFocused() && !saveName.isMouseOver(mx, my)) {
            saveName.setFocused(false);
            String typed = saveName.getValue().strip();
            if (savedPanel.renaming != null && (typed.isEmpty() || typed.equals(savedPanel.renaming))) {
                savedPanel.stopRenaming();                         // clicked away without a new name
            }
        }
        if (distBox.isFocused() && !distBox.isMouseOver(mx, my)) {
            distBox.setFocused(false);
        }
        return false;
    }

    /**
     * What a click runs: of the areas under it that act on this button, the smallest, so a row's
     * buttons win over the row however they were added; a tip-only area lets the click through.
     */
    private static Runnable target(List<Act> list, double mx, double my, int button) {
        Act best = null;
        for (int i = list.size() - 1; i >= 0; i--) {
            Act a = list.get(i);
            Runnable r = button == 1 ? a.right() : a.left();
            if (r != null && a.in(mx, my) && (best == null || a.w() * a.h() < best.w() * best.h())) {
                best = a;
            }
        }
        return best == null ? null : button == 1 ? best.right() : best.left();
    }

    boolean mouseScrolled(double mx, double my, double v) {
        int step = (int) Math.signum(v);
        if (view == 1 && open == Drop.NONE && !drawerOpen && setDetail == null) {
            // the wheel turns pages
            if (suggestMode == 0) {
                setsPanel.setPage = Math.max(0, Math.min(pageCount - 1, setsPanel.setPage - step));
            } else {
                suggestionsPanel.suggestPage = Math.max(0, Math.min(pageCount - 1, suggestionsPanel.suggestPage - step));
            }
            return true;
        }
        if (open != Drop.NONE && overlayBox != null && CraftPanel.in(mx, my, overlayBox[0], overlayBox[1], overlayBox[2], overlayBox[3])) {
            dropScroll = Math.max(0, dropScroll - step);
            return true;
        }
        if (resultPanel.cardRect != null && CraftPanel.in(mx, my, resultPanel.cardRect[0], resultPanel.cardRect[1], resultPanel.cardRect[2], resultPanel.cardRect[3])) {
            int next = Math.max(0, Math.min(resultPanel.idMax, resultPanel.idScroll - step));
            if (next != resultPanel.idScroll) {
                resultPanel.idScroll = next;                    // the card's own list scrolls first
                return true;
            }
        }
        if (clip != null && CraftPanel.in(mx, my, clip[0], clip[1], clip[2], clip[3])) {
            scroll = Math.max(0, Math.min(scrollMax, scroll - 12 * step));
            open = Drop.NONE;
        }
        return true;
    }

    /** Enter in the name box saves; Escape closes an open list first. */
    boolean keyPressed(int key) {
        if ((key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) && saveName.isFocused()) {
            savedPanel.saveCurrent();
            return true;
        }
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && savedPanel.renaming != null) {
            savedPanel.stopRenaming();                             // Esc leaves renaming, not the menu
            return true;
        }
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && (open != Drop.NONE || drawerOpen || setDetail != null)) {
            open = Drop.NONE;
            drawerOpen = false;
            setDetail = null;
            pickSearch.setFocused(false);
            return true;
        }
        return false;
    }

    /** True while a text box of the tab takes the keys (so key binds such as J do not fire). */
    boolean typing() {
        return pickSearch != null && (pickSearch.isFocused() || saveName.isFocused() || distBox.isFocused());
    }

    private void showTooltip(GuiGraphics g, int mx, int my) {
        List<Component> tip = null;
        if (overlayBox != null) {
            for (int i = overlay.size() - 1; i >= 0 && tip == null; i--) {
                if (overlay.get(i).in(mx, my)) {
                    tip = overlay.get(i).tip();
                    if (tip == null) {
                        return;
                    }
                }
            }
            if (CraftPanel.in(mx, my, overlayBox[0], overlayBox[1], overlayBox[2], overlayBox[3]) && tip == null) {
                return;
            }
        }
        if (tip == null) {
            for (int i = acts.size() - 1; i >= 0; i--) {
                if (acts.get(i).in(mx, my) && acts.get(i).tip() != null) {
                    tip = acts.get(i).tip();
                    break;
                }
            }
        }
        if (tip == null || tip.isEmpty()) {
            return;
        }
        int maxW = Math.max(160, Math.min(260, screen.width / 2));
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component c : tip) {
            List<FormattedCharSequence> part = font.split(c, maxW);
            lines.addAll(part.isEmpty() ? List.of(FormattedCharSequence.EMPTY) : part);
        }
        g.setTooltipForNextFrame(font, lines, mx, my);
    }

    /** An identification's shown name, from Wynntils' data ("Spell Damage"). */
    String statName(String id) {
        for (Ingredient i : ingredients) {
            Ingredient.Stat s = i.ids().get(id);
            if (s != null && s.shown() != null && !s.shown().isEmpty()) {
                return s.shown();
            }
        }
        return id;
    }

    String unit(String id) {
        for (Ingredient i : ingredients) {
            Ingredient.Stat s = i.ids().get(id);
            if (s != null) {
                return s.unit() == null ? "" : s.unit();
            }
        }
        return "";
    }

    /**
     * What buying these copies costs at today's prices (they may have come in since the layout
     * was found); the unknown ones are asked for and shown as "+?".
     */
    String liveCost(List<String> bought) {
        long cost = 0;
        boolean priced = false;
        boolean unknown = false;
        for (String n : bought) {
            CraftSearch.Price p = prices.get(n);
            if (p == null) {
                unknown = true;
                wantedPrices.add(n);
            } else {
                cost += p.emeralds();
                priced = true;
            }
        }
        return CraftFormat.costText(cost, priced, unknown);
    }
}
