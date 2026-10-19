package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.crafting.Craft;
import io.github.anilken.wynnlabs.crafting.CraftChecks;
import io.github.anilken.wynnlabs.crafting.CraftData;
import io.github.anilken.wynnlabs.crafting.CraftSearch;
import io.github.anilken.wynnlabs.crafting.Ingredient;
import io.github.anilken.wynnlabs.crafting.RecipeCode;
import io.github.anilken.wynnlabs.crafting.Rolls;
import io.github.anilken.wynnlabs.crafting.SavedRecipes;
import io.github.anilken.wynnlabs.crafting.SearchRunner;
import io.github.anilken.wynnlabs.crafting.SetSuggestions;
import io.github.anilken.wynnlabs.crafting.Shopping;
import io.github.anilken.wynnlabs.crafting.Suggestions;
import io.github.anilken.wynnlabs.crafting.StockView;
import io.github.anilken.wynnlabs.crafting.Types;
import io.github.anilken.wynnlabs.crafting.WhatCanICraft;
import io.github.anilken.wynnlabs.crafting.WynnBuilderCode;
import io.github.anilken.wynnlabs.model.Klass;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Arrays;
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
final class CraftingTab {

    /** One clickable or hoverable area. */
    private record Act(int x, int y, int w, int h, Runnable left, Runnable right, List<Component> tip) {
        boolean in(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private enum Drop { NONE, TYPE, LEVEL, SLOT, GOAL, CONDITIONS }

    static final CraftingTab INSTANCE = new CraftingTab();

    /** For the preview without the game: ingredients to use when Wynntils has none. */
    static volatile List<Ingredient> previewIngredients = List.of();
    /** For the preview: profession levels to use instead of the recording's. */
    static volatile Map<String, Integer> previewLevels = Map.of();

    private static final List<String> TYPE_COL_A = List.of("#armour", "helmet", "chestplate", "leggings", "boots",
            "#accessory", "ring", "bracelet", "necklace");
    private static final List<String> TYPE_COL_B = List.of("#weapon", "spear", "dagger", "bow", "wand", "relik",
            "#consumable", "potion", "scroll", "food");
    private static final int[] FLOORS = {1, 50, 100, 200, 300};
    private static final double[] WEIGHT_STEPS = {1.0, 0.5, 2.0};
    private static final int TWO_COLUMNS_MIN = 440;
    /** A section this wide is on the large layout: its bars and lines stop short of the far edge. */
    private static final int WIDE = 600;
    private static final int SIDE_W = 120;

    // ---- the recipe ----
    private String type;
    private int minLevel;
    private int tier1 = 1;
    private int tier2 = 1;
    private Craft.Speed speed = Craft.Speed.NORMAL;
    private final String[] slots = new String[Craft.SLOTS];

    // ---- the goal ----
    private final LinkedHashMap<String, Double> weights = new LinkedHashMap<>(Map.of("spellDamage", 1.0));
    private final Set<String> noNegative = new LinkedHashSet<>();
    private boolean capToPoints;
    private int floorIndex;

    // ---- searches ----
    private final SearchRunner runner = new SearchRunner();
    private final SearchRunner whatRunner = new SearchRunner();
    private final SearchRunner suggestRunner = new SearchRunner();
    private volatile List<CraftSearch.Outcome> results = List.of();
    private CraftSearch.Budget searching;
    private final List<WhatCanICraft.Entry> what = new CopyOnWriteArrayList<>();
    private CraftSearch.Budget whatSearching;
    private String resultsFor = "";

    // ---- the page ----
    private Drop open = Drop.NONE;
    private int openSlot = -1;
    private int[] openAnchor;
    private int dropScroll;
    private int scroll;
    private int scrollMax;
    private boolean foldDist = true;
    private boolean foldShop = true;
    private boolean foldWhat;
    private int subTab;
    private String distId;
    private Integer distTarget;
    private boolean drawerOpen;
    private String deleteArmed;
    private long deleteArmedAt;
    private String renaming;
    private String stationText;
    private List<Ingredient> preview;            // a layout drawn dashed on the grid (hovered row)
    private List<Ingredient> nextPreview;

    // ---- caches ----
    private List<Ingredient> ingredients = List.of();
    private final Map<String, Ingredient> byName = new HashMap<>();
    private WhatCanICraft.Stock stock = new WhatCanICraft.Stock(Map.of(), Map.of(), Map.of());
    private long stockAt;
    private int[] points = new int[5];
    private Map<String, CraftSearch.Price> prices = Map.of();
    /** Ingredients on screen to buy whose price is not known yet: asked for at the next refresh. */
    private final Set<String> wantedPrices = new LinkedHashSet<>();

    // ---- per frame ----
    private Screen screen;
    private Font font;
    private EditBox pickSearch;
    private EditBox saveName;
    private EditBox distBox;
    private final List<Act> acts = new ArrayList<>();
    private final List<Act> overlay = new ArrayList<>();
    private int[] overlayBox;
    private int[] clip;
    private List<Component> tooltip;
    private java.util.function.Consumer<String> flash = s -> {};

    private CraftingTab() {}

    // =====================================================================================
    // set-up, called from RoadmapScreen

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
                distTarget = v.isEmpty() || v.equals("-") ? null : Integer.parseInt(v);
            } catch (NumberFormatException e) {
                distTarget = null;
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
        runner.cancel();
        whatRunner.cancel();
        suggestRunner.cancel();
        suggesting = null;
        suggestedFor = "";
        setRunner.cancel();
        setting = null;
        setsFor = "";
        setDetail = null;
        open = Drop.NONE;
    }

    // =====================================================================================
    // data

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

    private static String defaultType() {
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
    private CraftData.Recipe bestBracket(String t) {
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
    private int knownLevel(String profession) {
        Integer l = previewLevels.isEmpty() ? stock.levels().get(profession) : previewLevels.get(profession);
        return l == null ? 0 : l;
    }

    /**
     * The level that gates recipes and ingredients: an unknown level (Wynntils reads it from the
     * character menu) locks nothing rather than everything.
     */
    private int level(String profession) {
        int l = knownLevel(profession);
        return l > 0 ? l : 200;
    }

    /** The profession of the recipe on the calculator ("WOODWORKING"), or null before the tab was opened. */
    String profession() {
        return type == null ? null : recipe().profession().toUpperCase(Locale.ROOT);
    }

    private CraftData.Recipe recipe() {
        CraftData.Recipe r = CraftData.find(type, minLevel);
        return r != null ? r : CraftData.ofType(type).get(0);
    }

    private Ingredient ing(String name) {
        if (name == null) {
            return Ingredient.NONE;
        }
        Ingredient i = byName.get(name);
        return i == null ? Ingredient.NONE : i;
    }

    private List<Ingredient> layout() {
        List<Ingredient> out = new ArrayList<>(Craft.SLOTS);
        for (String s : slots) {
            out.add(ing(s));
        }
        return out;
    }

    private CraftSearch.Goal goal() {
        int[] caps = CraftSearch.Goal.uncapped();
        if (capToPoints) {
            caps = points.clone();
        }
        return new CraftSearch.Goal(new LinkedHashMap<>(weights), CraftSearch.Pick.MEAN, Set.copyOf(noNegative), caps,
                FLOORS[floorIndex]);
    }

    private int owned(String name) {
        Integer n = stock.ingredients().get(name);
        return n == null ? 0 : n;
    }

    /** What the recipe and goal look like now: a new search is needed when this changes. */
    private String key() {
        return type + minLevel + tier1 + tier2 + speed + weights + noNegative + capToPoints + floorIndex;
    }

    // =====================================================================================
    // drawing

    /**
     * Draws the page into {@code x, y, w, h}; {@code titleY} is the page title's line (the
     * header's buttons sit on it) and {@code frame} the menu panel {x, y, w, h} (the saved
     * recipes go beside it).
     */
    void draw(GuiGraphics g, int x, int y, int w, int h, int titleY, int[] frame, int screenW, int mx, int my, float pt) {
        acts.clear();
        overlay.clear();
        overlayBox = null;
        tooltip = null;
        preview = nextPreview;
        nextPreview = null;
        refresh();
        drawHeader(g, x, titleY, w, mx, my);
        if (ingredients.isEmpty()) {
            noData(g, x, y, w, h);
            return;
        }
        if (!resultsFor.equals(key()) && !results.isEmpty()) {
            results = List.of();                    // the recipe or goal changed: old layouts no longer apply
        }
        // the two pages: the calculator and our suggestions, as ledger tabs over the page
        y += viewTabs(g, x, y, w, mx, my) + 4;
        h -= 18;
        if (view == 1) {
            drawSuggestions(g, x, y, w, h, mx, my);
            drawSaved(g, frame, screenW, x, y, w, h, w >= TWO_COLUMNS_MIN, mx, my);
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
            int left = drawRecipe(g, x, top, colW, mx, my);
            int rx = x + colW + 10;
            int right = drawResult(g, rx, top, colW, mx, my);
            right += 6 + drawOptimiser(g, rx, top + right + 6, colW, mx, my);
            int by = top + Math.max(left, right) + 8;
            by += drawSections(g, x, by, cw, mx, my, pt);
            used = by - top;
        } else {
            int sy = top;
            sy += subTabs(g, x, sy, cw, mx, my) + 6;
            switch (subTab) {
                case 1 -> {
                    sy += drawResult(g, x, sy, cw, mx, my) + 6;
                    sy += boxed(g, 0, x, sy, cw, (bx, by, bw) -> drawDistribution(g, bx, by, bw, mx, my, pt));
                }
                case 2 -> {
                    sy += drawOptimiser(g, x, sy, cw, mx, my) + 6;
                    sy += boxed(g, 2, x, sy, cw, (bx, by, bw) -> drawWhat(g, bx, by, bw, mx, my));
                }
                default -> {
                    sy += drawRecipe(g, x, sy, Math.min(cw, 260), mx, my) + 6;
                    sy += boxed(g, 1, x, sy, cw, (bx, by, bw) -> drawShopping(g, bx, by, bw, mx, my));
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
        drawSaved(g, frame, screenW, x, y, w, h, two, mx, my);
        drawDrop(g, mx, my, pt);
        showTooltip(g, mx, my);
    }

    private void noData(GuiGraphics g, int x, int y, int w, int h) {
        int cw = Math.min(w - 40, 320);
        int cx = x + (w - cw) / 2;
        int cy = y + Math.max(10, h / 2 - 40);
        Kit.card(g, cx, cy, cw, 62, Theme.GOLD_DEEP);
        Art.icon(g, "hammer", cx + (cw - 16) / 2, cy + 8, 16, Theme.paperRgb(Theme.INK_2));
        String t = L.t("wynnlabs.craft.no_data");
        text(g, Component.literal(t).withStyle(UiFont::bold), cx + (cw - width(t)) / 2, cy + 30, Theme.INK);
        String s = fit(L.t("wynnlabs.craft.no_data_hint"), cw - 16);
        text(g, s, cx + (cw - width(s)) / 2, cy + 44, Theme.INK_3);
    }

    // ---- header: profession progress and the code buttons --------------------------------

    private void drawHeader(GuiGraphics g, int x, int ty, int w, int mx, int my) {
        int bx = x + w;
        String[][] buttons = {{"refresh", L.t("wynnlabs.craft.reset"), "reset"}, {"clipboard", L.t("wynnlabs.craft.paste"), "paste"},
            {"copy", L.t("wynnlabs.craft.code"), "copy"}};
        for (String[] b : buttons) {
            int bw = width(b[1]) + 18;
            bx -= bw;
            int by = ty - 2;
            boolean hover = in(mx, my, bx, by, bw, 12);
            int state = hover ? Kit.HOVER : Kit.REST;
            Kit.button(g, bx, by, bw, 12, state);
            Art.icon(g, b[0], bx + 4, by + 2, 8, Kit.buttonText(state));
            wood(g, b[1], bx + 14, by + 2, Kit.buttonText(state), true);
            String what = b[2];
            acts.add(new Act(bx, by, bw, 12, () -> header(what), null,
                    List.of(L.c("wynnlabs.craft.tip." + what))));
            bx -= 3;
        }
        if (type == null) {
            return;
        }
        CraftData.Recipe r = recipe();
        int known = knownLevel(r.profession());
        Object lv = known > 0 ? known : "?";
        int[] xp = Crafting.professionXp(r.profession());
        int crafts = known > 0 ? CraftChecks.craftsToLevel(r.xp(), xp[0], xp[1]) : -1;
        String prof = Professions.name(r.profession().toUpperCase(Locale.ROOT));
        String line = crafts < 0 ? L.t("wynnlabs.craft.prof_line_unknown", prof, lv, thousands(r.xp()))
                : L.n("wynnlabs.craft.prof_line", crafts, prof, known, known + 1, crafts, thousands(r.xp()));
        int titleW = font.width(L.t("wynnlabs.screen.tab_craft")) + 12;
        int room = bx - (x + titleW) - 6;
        String shown = fitFine(line, room);
        fine(g, shown, bx - 6 - fineWidth(shown), ty + 1, Theme.INK_3);
        acts.add(new Act(bx - 6 - fineWidth(shown), ty - 2, fineWidth(shown), 12, null, null,
                List.of(Component.literal(line), L.c("wynnlabs.craft.xp_estimate"))));
    }

    private void header(String what) {
        switch (what) {
            case "copy" -> {
                List<String> names = new ArrayList<>();
                for (String s : slots) {
                    names.add(s);
                }
                RecipeCode.Recipe here = new RecipeCode.Recipe(type, minLevel, tier1, tier2, speed, names);
                // WynnBuilder's code, the one everyone shares; the mod's own when WynnBuilder lacks an id
                String code = WynnBuilderCode.encode(here);
                if (code == null) {
                    code = RecipeCode.encode(here);
                }
                Minecraft.getInstance().keyboardHandler.setClipboard(code);
                flash.accept(L.t("wynnlabs.craft.code_copied"));
            }
            case "paste" -> {
                String text = Minecraft.getInstance().keyboardHandler.getClipboard();
                RecipeCode.Recipe r = WynnBuilderCode.looksLike(text) ? WynnBuilderCode.decode(text)
                        : RecipeCode.decode(text, byName.keySet());
                if (r == null) {
                    flash.accept(L.t("wynnlabs.craft.code_bad"));
                    return;
                }
                type = r.type();
                minLevel = r.minLevel();
                tier1 = r.tier1();
                tier2 = r.tier2();
                speed = r.speed();
                for (int i = 0; i < Craft.SLOTS; i++) {
                    slots[i] = r.slots().get(i);
                }
                flash.accept(L.t("wynnlabs.craft.code_read"));
            }
            default -> {
                Arrays.fill(slots, null);
                tier1 = 1;
                tier2 = 1;
                speed = Craft.Speed.NORMAL;
                results = List.of();
            }
        }
    }

    // ---- left: the recipe --------------------------------------------------------------

    private static final int ROW_H = 12;               // fields, buttons and segments
    private static final int SLOT_BG = 0xFF3B332A;      // the grid's dark slots (mock-up v3)
    private static final int SLOT_EDGE = 0xFF241E16;
    private static final int SLOT_TEXT = 0xFFF2E6CC;
    private static final int SLOT_DIM = 0xFFCBB995;
    private static final int SLOT_EMPTY = 0xFF9C8C70;
    private static final int SLOT_UP = 0xFF9FE07A;
    private static final int SLOT_DOWN = 0xFFFF9A8A;
    private static final int STAR = 0xFFF0D27A;

    private int drawRecipe(GuiGraphics g, int x, int y, int w, int mx, int my) {
        CraftData.Recipe r = recipe();
        int lv = level(r.profession());
        int yy = y;
        // type and level as drop-down fields
        String typeLabel = L.t("wynnlabs.craft.type");
        text(g, typeLabel, x, yy + 2, Theme.INK_2);
        int fx = x + width(typeLabel) + 4;
        int tw = 78;
        dropField(g, fx, yy, tw, typeName(type), open == Drop.TYPE, mx, my, false);
        Art.icon(g, typeIcon(type), fx + tw - 22, yy + 2, 8, Theme.paperRgb(Theme.INK_2));
        int[] typeAnchor = {fx, yy, tw, ROW_H};
        clipped(fx, yy, tw, ROW_H, () -> toggle(Drop.TYPE, -1, typeAnchor), null, null);
        int lx = fx + tw + 8;
        String levelLabel = L.t("wynnlabs.craft.level");
        text(g, levelLabel, lx, yy + 2, Theme.INK_2);
        int lfx = lx + width(levelLabel) + 4;
        int lw = Math.min(58, x + w - lfx);
        boolean locked = r.minLevel() > lv;
        dropField(g, lfx, yy, lw, r.bracket(), open == Drop.LEVEL, mx, my, locked);
        int[] levelAnchor = {lfx, yy, lw, ROW_H};
        clipped(lfx, yy, lw, ROW_H, () -> toggle(Drop.LEVEL, -1, levelAnchor), null, locked
                ? List.of(L.c("wynnlabs.craft.locked_recipe", Professions.name(r.profession().toUpperCase(Locale.ROOT)),
                        r.minLevel(), lv)) : null);
        yy += ROW_H + 4;
        if (Types.weapon(type)) {
            String sl = L.t("wynnlabs.craft.speed");
            text(g, sl, x, yy + 2, Theme.INK_2);
            int sx = x + width(sl) + 4;
            for (Craft.Speed s : Craft.Speed.values()) {
                String name = L.t("wynnlabs.craft.speed." + s.name().toLowerCase(Locale.ROOT));
                int bw = width(name) + 10;
                boolean on = s == speed;
                int state = on ? Kit.ON : in(mx, my, sx, yy, bw, ROW_H) ? Kit.HOVER : Kit.REST;
                Kit.button(g, sx, yy, bw, ROW_H, state);
                wood(g, name, sx + 5, yy + 2, Kit.buttonText(state), !on);
                clipped(sx, yy, bw, ROW_H, () -> speed = s, null, null);
                sx += bw + 2;
            }
            yy += ROW_H + 4;
        }
        // the two materials, each with its amount and a three-part tier picker showing what is owned
        int segW = 36;
        for (int m = 0; m < 2; m++) {
            CraftData.Material mat = r.materials().get(m);
            int chosen = m == 0 ? tier1 : tier2;
            Component amount = Component.literal(mat.amount() + "x ").withStyle(UiFont::bold);
            text(g, amount, x, yy + 2, Theme.INK);
            int segX = x + w - 3 * segW;
            int nameX = x + width(amount);
            text(g, fit(mat.shown(), segX - nameX - 4), nameX, yy + 2, Theme.INK);
            int mi = m;
            for (int t = 1; t <= 3; t++) {
                int have = stock.material(mat.shown(), t);
                int sx = segX + (t - 1) * segW;
                boolean on = t == chosen;
                boolean hover = in(mx, my, sx, yy, segW - 1, ROW_H);
                Kit.button(g, sx, yy, segW - 1, ROW_H, on ? Kit.ON : hover ? Kit.HOVER : Kit.REST);
                starIcons(g, sx + 4, yy + 3, t, on ? 0xFF7A4A00 : STAR);
                String c = String.valueOf(have);
                boolean shortOf = have < mat.amount();
                int col = on ? (shortOf ? Theme.BAD : Theme.INK) : (shortOf ? 0xFFFFB0A0 : Theme.CREAM);
                text(g, c, sx + segW - 3 - width(c), yy + 2, col);
                int tier = t;
                clipped(sx, yy, segW, ROW_H, () -> {
                    if (mi == 0) {
                        tier1 = tier;
                    } else {
                        tier2 = tier;
                    }
                }, null, List.of(L.c("wynnlabs.craft.tier_tip", mat.shown(), tier, have, mat.amount()),
                        L.c("wynnlabs.craft.tier_weights")));
            }
            yy += ROW_H + 3;
        }
        String mult = L.t("wynnlabs.craft.multiplier", dec(Craft.weightPercent(r, tier1, tier2) / 100.0, 2));
        fine(g, mult, x + w - fineWidth(mult), yy, Theme.INK_3);
        yy += 10;
        yy += drawGrid(g, x, yy, w, mx, my);
        // the hint under the grid; one column (a small window) scrolls it out of view, so it goes
        if (twoColumns) {
            fine(g, fitFine(L.t("wynnlabs.craft.grid_hint"), w), x, yy, Theme.INK_3);
            yy += 10;
        }
        return yy - y;
    }

    private void dropField(GuiGraphics g, int x, int y, int w, String text, boolean opened, int mx, int my, boolean locked) {
        boolean hover = in(mx, my, x, y, w, ROW_H);
        Kit.field(g, x, y, w, ROW_H, opened || hover);
        int tx = x + 4;
        if (locked) {
            Art.icon(g, "lock", tx, y + 2, 8, Theme.paperRgb(Theme.BAD));
            tx += 10;
        }
        text(g, fit(text, x + w - 14 - tx), tx, y + 2, locked ? Theme.BAD : Theme.INK);
        Art.icon(g, "chevron-down", x + w - 11, y + 2, 8, Theme.paperRgb(Theme.INK_2));
    }

    /** Each meta's letter colour, in the order the metas sit on the grid (A, B, C …). */
    private static final int[] META_HUES = {0xFF77D1D7, 0xFFF0B04A, 0xFFD8BCF5, 0xFF9FE07A, 0xFFF09595, 0xFF85B7EB};
    private static final String[] POS_KEYS = {"left", "right", "above", "under", "touching", "not_touching"};

    /**
     * What each meta gives each slot in this layout, in effectiveness points: {@code give[from][to]},
     * 0 where it changes nothing. A meta's own slot is 0.
     */
    private static int[][] gives(List<Ingredient> lay) {
        int[][] give = new int[Craft.SLOTS][Craft.SLOTS];
        for (int from = 0; from < Craft.SLOTS; from++) {
            Ingredient m = lay.get(from);
            if (m.empty() || !m.meta()) {
                continue;
            }
            List<Ingredient> alone = new ArrayList<>(Craft.slots());
            alone.set(from, m);
            int[] only = Craft.effectiveness(alone);
            for (int to = 0; to < Craft.SLOTS; to++) {
                give[from][to] = to == from ? 0 : only[to] - 100;
            }
        }
        return give;
    }

    /** A meta's effects that reach no slot from where it sits (left of the left column …), as position indices. */
    private static List<Integer> wasted(Ingredient m, int slot) {
        List<Integer> out = new ArrayList<>();
        int row = slot / 2;
        int col = slot % 2;
        int[] p = m.positions();
        if (p[Ingredient.LEFT] != 0 && col == 0) {
            out.add(Ingredient.LEFT);
        }
        if (p[Ingredient.RIGHT] != 0 && col == 1) {
            out.add(Ingredient.RIGHT);
        }
        if (p[Ingredient.ABOVE] != 0 && row == 0) {
            out.add(Ingredient.ABOVE);
        }
        if (p[Ingredient.UNDER] != 0 && row == 2) {
            out.add(Ingredient.UNDER);
        }
        return out;
    }

    /**
     * The 2×3 grid of dark slots (option B with mini-maps): a meta carries a letter and a small
     * map of the slots it pushes up (green) or down (red); every slot shows its effectiveness on a
     * coloured chip and what each meta gives it ("A+60 B+25 C−15").
     */
    private int drawGrid(GuiGraphics g, int x, int y, int w, int mx, int my) {
        List<Ingredient> lay = layout();
        List<Ingredient> shown = preview != null ? preview : lay;
        int[] eff = Craft.effectiveness(shown);
        int[] base = Craft.effectiveness(lay);
        int[][] give = gives(shown);
        int[] letter = new int[Craft.SLOTS];
        int metas = 0;
        for (int s = 0; s < Craft.SLOTS; s++) {
            letter[s] = shown.get(s).meta() ? metas++ : -1;
        }
        int gap = 3;
        int cellW = (w - gap) / 2;
        int cellH = 26;
        CraftData.Recipe r = recipe();
        int lv = level(r.profession());
        for (int s = 0; s < Craft.SLOTS; s++) {
            int cx = x + (s % 2) * (cellW + gap);
            int cy = y + (s / 2) * (cellH + gap);
            Ingredient in = shown.get(s);
            boolean hover = in(mx, my, cx, cy, cellW, cellH);
            boolean opened = open == Drop.SLOT && openSlot == s;
            boolean changed = preview != null && (eff[s] != base[s] || !in.name().equals(lay.get(s).name()));
            int e = eff[s];
            g.fill(cx, cy, cx + cellW, cy + cellH, hover ? 0xFF4A4034 : SLOT_BG);
            Kit.outline(g, cx, cy, cellW, cellH, SLOT_EDGE);
            Kit.outline(g, cx + 1, cy + 1, cellW - 2, cellH - 2, e <= 0 ? 0xFFE0503C : SLOT_EDGE);
            if (opened) {
                Kit.outline(g, cx, cy, cellW, cellH, 0xFFE3BD55);
                Kit.outline(g, cx + 1, cy + 1, cellW - 2, cellH - 2, 0xFFE3BD55);
            } else if (changed) {
                dashed(g, cx, cy, cellW, cellH, 0xFFE3BD55);
            }
            // line 1: the meta's letter, the name, how many are owned, the meta's map
            int nx = cx + 5;
            int right = cx + cellW - 5;
            if (letter[s] >= 0) {
                int hue = META_HUES[letter[s] % META_HUES.length];
                // the letter in the name's own font and line, its square around the letter's height
                Component l = Component.literal(String.valueOf((char) ('A' + letter[s]))).withStyle(UiFont::bold);
                int lw = width(l);
                g.fill(nx, cy + 4, nx + lw + 4, cy + 13, hue);
                text(g, l, nx + 2, cy + 4, 0xFF241E16);
                nx += lw + 7;
                miniMap(g, right - 9, cy + 4, s, give[s]);
                right -= 13;
            }
            if (in.empty()) {
                String unknown = slots[s] != null && preview == null ? slots[s] : null;
                text(g, unknown != null ? fit("? " + unknown, right - nx) : L.t("wynnlabs.craft.add"), nx, cy + 4,
                        unknown != null ? SLOT_DOWN : SLOT_EMPTY);
            } else {
                boolean locked = in.level() > lv;
                String count = "×" + owned(in.name());
                if (locked) {
                    Art.icon(g, "lock", nx, cy + 4, 8, SLOT_DOWN);
                    nx += 10;
                }
                int cw = fineWidth(count);
                fine(g, count, right - cw, cy + 5, owned(in.name()) > 0 ? SLOT_DIM : SLOT_EMPTY);
                text(g, fit(in.name(), right - cw - 4 - nx), nx, cy + 4, locked ? SLOT_EMPTY : SLOT_TEXT);
            }
            // line 2: the % chip, then what each meta gives this slot
            int chipX = cx + 4;
            int chipY = cy + 15;
            int[] band = band(e);
            g.fill(chipX, chipY, chipX + 30, chipY + 9, band[0]);
            String pc = pct(e);
            Component pcc = band[2] == 1 ? Component.literal(pc).withStyle(UiFont::bold) : Component.literal(pc);
            Kit.text(g, font, sized(pcc, FINE), chipX + (30 - font.width(sized(pcc, FINE))) / 2, chipY + 1, band[1]);
            int tx = chipX + 34;
            for (int from = 0; from < Craft.SLOTS; from++) {
                int d = give[from][s];
                if (d == 0 || letter[from] < 0) {
                    continue;
                }
                String l = String.valueOf((char) ('A' + letter[from]));
                String v = (d > 0 ? "+" : "−") + Math.abs(d);
                int need = fineWidth(l) + fineWidth(v) + 4;
                if (tx + need > cx + cellW - 4) {
                    fine(g, "…", tx, chipY + 1, SLOT_DIM);
                    break;
                }
                fine(g, l, tx, chipY + 1, META_HUES[letter[from] % META_HUES.length]);
                tx += fineWidth(l);
                fine(g, v, tx, chipY + 1, d > 0 ? SLOT_UP : SLOT_DOWN);
                tx += fineWidth(v) + 4;
            }
            int slot = s;
            int[] anchor = {cx, cy, cellW, cellH};
            List<Component> tip = new ArrayList<>(breakdown(shown, give, letter, s));
            if (in.empty()) {
                tip.add(L.c("wynnlabs.craft.slot_tip", pct(e)));
            } else {
                tip.addAll(card(in));
            }
            clipped(cx, cy, cellW, cellH, () -> {
                toggle(Drop.SLOT, slot, anchor);
                pickSearch.setValue("");
                screen.setFocused(pickSearch);
                pickSearch.setFocused(true);
            }, () -> slots[slot] = null, tip);
        }
        return 3 * cellH + 2 * gap + 3;
    }

    /** {fill, text colour, bold} of the % chip: strong green, green, neutral, pink, and red at 0% or below. */
    private static int[] band(int e) {
        if (e >= 200) {
            return new int[] {0xFF3E6B26, 0xFFE4FFC9, 1};
        }
        if (e > 100) {
            return new int[] {0xFF2F4423, 0xFF9FE07A, 0};
        }
        if (e == 100) {
            return new int[] {0xFF2E2820, 0xFFCBB995, 0};
        }
        if (e > 0) {
            return new int[] {0xFF4A2C22, 0xFFFF9A8A, 0};
        }
        return new int[] {0xFF7A2A1C, 0xFFFFE3DA, 1};
    }

    /** A meta's 2×3 map (9×11): its own slot gold, slots it pushes up green, down red, the rest dark. */
    private static void miniMap(GuiGraphics g, int x, int y, int self, int[] give) {
        for (int t = 0; t < Craft.SLOTS; t++) {
            int sx = x + (t % 2) * 5;
            int sy = y + (t / 2) * 4;
            int c = t == self ? 0xFFE3BD55 : give[t] > 0 ? 0xFF5FB040 : give[t] < 0 ? 0xFFE0503C : SLOT_EDGE;
            g.fill(sx, sy, sx + 4, sy + 3, c);
        }
    }

    /** The tooltip's first lines for a slot: how its % adds up, and for a meta what it gives and wastes. */
    private List<Component> breakdown(List<Ingredient> lay, int[][] give, int[] letter, int s) {
        List<Component> out = new ArrayList<>();
        int e = Craft.effectiveness(lay)[s];
        StringBuilder sb = new StringBuilder("100");
        boolean any = false;
        for (int from = 0; from < Craft.SLOTS; from++) {
            int d = give[from][s];
            if (d != 0) {
                any = true;
                sb.append(' ').append(d > 0 ? "+" : "−").append(Math.abs(d)).append(' ')
                        .append((char) ('A' + letter[from])).append(' ').append(lay.get(from).name());
            }
        }
        if (any) {
            out.add(L.c("wynnlabs.craft.slot_sum", pct(e), sb.toString()).withStyle(ChatFormatting.GRAY));
        }
        Ingredient m = lay.get(s);
        if (m.meta()) {
            List<String> parts = new ArrayList<>();
            for (int to = 0; to < Craft.SLOTS; to++) {
                if (give[s][to] != 0) {
                    parts.add((give[s][to] > 0 ? "+" : "−") + Math.abs(give[s][to]) + " → " + (to + 1));
                }
            }
            if (!parts.isEmpty()) {
                out.add(L.c("wynnlabs.craft.slot_gives", (char) ('A' + letter[s]), String.join(", ", parts))
                        .withStyle(ChatFormatting.GRAY));
            }
            for (int p : wasted(m, s)) {
                out.add(L.c("wynnlabs.craft.slot_wasted", L.t("wynnlabs.craft.pos." + POS_KEYS[p],
                        (m.positions()[p] > 0 ? "+" : "−") + Math.abs(m.positions()[p])))
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        return out;
    }

    /** {@code n} small filled stars from {@code x}. */
    private static void starIcons(GuiGraphics g, int x, int y, int n, int colour) {
        for (int i = 0; i < n; i++) {
            Art.icon(g, "star-filled", x + i * 6, y, 6, colour);
        }
    }

    private static void dashed(GuiGraphics g, int x, int y, int w, int h, int c) {
        for (int i = 0; i < w; i += 4) {
            g.fill(x + i, y, x + Math.min(w, i + 2), y + 1, c);
            g.fill(x + i, y + h - 1, x + Math.min(w, i + 2), y + h, c);
        }
        for (int i = 0; i < h; i += 4) {
            g.fill(x, y + i, x + 1, y + Math.min(h, i + 2), c);
            g.fill(x + w - 1, y + i, x + w, y + Math.min(h, i + 2), c);
        }
    }

    // ---- right: the crafted item --------------------------------------------------------

    private Craft.Result result() {
        return Craft.craft(recipe(), tier1, tier2, speed, layout());
    }

    private int drawResult(GuiGraphics g, int x, int y, int w, int mx, int my) {
        CraftData.Recipe r = recipe();
        Craft.Result c = Craft.craft(r, tier1, tier2, speed, preview != null ? preview : layout());
        List<CraftChecks.Gap> gaps = r.consumable() ? List.of() : CraftChecks.gaps(c.requirements(), points);
        List<StockView.Short> shortMats = StockView.missing(r, tier1, tier2, stock);
        int lv = level(r.profession());
        int ids = c.ids().size();
        int h = 6 + 11 + 10 + 10 + ID_ROWS * 10 + 10 + 10 + 3;
        idMax = Math.max(0, ids - ID_ROWS);
        idScroll = Math.min(idScroll, idMax);
        cardRect = new int[] {x, y, w, h};
        Kit.card(g, x, y, w, h, Theme.GOLD_DEEP);
        int ix = x + 6;
        int yy = y + 6;
        String title = L.t("wynnlabs.craft.crafted", typeName(type));
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
            boolean met = points[s] >= need;
            String t = skill(s) + " " + need;
            text(g, t, rx, yy, Theme.INK);
            rx += width(t) + 2;
            Art.icon(g, met ? "check" : "x", rx, yy, 8, Theme.paperRgb(met ? Theme.GOOD : Theme.BAD));
            rx += 9;
            String have = String.valueOf(points[s]);
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
            boolean sel = id.equals(distId);
            if (sel) {
                g.fill(x + 2, yy - 1, x + w - 2, yy + 9, 0x55FFF3C8);
            }
            String unit = unit(id);
            String lo = signed(v.min()) + unit;
            String hi = signed(v.max()) + unit;
            int col = v.max() < 0 ? Theme.BAD : Theme.GOOD;
            text(g, lo, ix + minCol - width(lo), yy, col);
            String mean = "⌀" + dec(v.mean(), 1);
            int meanW = width(mean) + 10;
            int hiW = width(hi);
            String name = fit(statName(id), w - 12 - minCol - 8 - hiW - meanW - 8);
            text(g, name, ix + minCol + 6, yy, Theme.INK_2);
            int hx = x + w - 6 - meanW - 6 - hiW;
            text(g, hi, hx, yy, col);
            text(g, mean + " ▸", x + w - 6 - meanW, yy, sel ? Theme.ACCENT : Theme.INK_3);
            clipped(x + 2, yy - 1, w - 4, 10, () -> {
                distId = id;
                distTarget = null;
                distBox.setValue("");
                foldDist = true;
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
        Shopping.Needs n = Shopping.of(r, tier1, tier2, layout(), stock, prices::get);
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
        for (String s : slots) {
            if (s != null) {
                need.merge(s, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            if (owned(e.getKey()) < e.getValue()) {
                return e.getKey();
            }
        }
        return null;
    }

    /** How many times the recipe can be made from the stock as laid out now. */
    private int craftableTimes(CraftData.Recipe r) {
        int n = Integer.MAX_VALUE;
        int[] tiers = {tier1, tier2};
        for (int i = 0; i < 2; i++) {
            CraftData.Material m = r.materials().get(i);
            n = Math.min(n, stock.material(m.shown(), tiers[i]) / m.amount());
        }
        Map<String, Integer> need = new HashMap<>();
        for (String s : slots) {
            if (s != null) {
                need.merge(s, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            n = Math.min(n, owned(e.getKey()) / e.getValue());
        }
        return n == Integer.MAX_VALUE ? 0 : n;
    }

    /** A small coloured tag ending at {@code right}; returns its width. */
    private int tag(GuiGraphics g, int right, int y, String text, boolean good, boolean draw) {
        int tw = fineWidth(text) + 8;
        if (draw) {
            Art.pill(g, right - tw, y, tw, 10, good ? 0xFFD9E7C7 : 0xFFF3D0C8, 0);
            fine(g, text, right - tw + 4, y + 1, good ? Theme.GOOD : Theme.BAD);
        }
        return tw;
    }

    // ---- right: the optimiser -----------------------------------------------------------

    private int drawOptimiser(GuiGraphics g, int x, int y, int w, int mx, int my) {
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
        for (Map.Entry<String, Double> e : new ArrayList<>(weights.entrySet())) {
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
            clipped(cx, yy, bw, ROW_H, () -> cycleWeight(id), () -> weights.remove(id),
                    List.of(Component.literal(statName(id)), L.c("wynnlabs.craft.goal_tip")));
            if (hover) {
                Kit.outline(g, cx, yy, bw, ROW_H, Theme.GOLD_EDGE);
            }
            cx += bw + 3;
        }
        int conds = noNegative.size() + (capToPoints ? 1 : 0) + (floorIndex > 0 ? 1 : 0);
        String cl = L.t("wynnlabs.craft.conditions", conds);
        int cw = width(cl) + 10;
        if (cx > x && cx + 15 + cw > limit) {
            cx = x;
            yy += ROW_H + 3;
        }
        int[] addAnchor = {cx, yy, 12, ROW_H};
        chip(g, cx, yy, 12, "+", mx, my, () -> toggle(Drop.GOAL, -1, addAnchor), List.of(L.c("wynnlabs.craft.goal_add")));
        cx += 15;
        int[] condAnchor = {cx, yy, cw, ROW_H};
        chip(g, cx, yy, cw, cl, mx, my, () -> toggle(Drop.CONDITIONS, -1, condAnchor), List.of(L.c("wynnlabs.craft.conditions_tip")));
        yy += ROW_H + 4;
        if (running) {
            Kit.tealBar(g, x, yy + 2, w, 6, searching.progress(), false);
            text(g, L.t("wynnlabs.craft.searching"), x, yy + 10, Theme.INK_3);
            yy += 22;
            return yy - y;
        }
        if (results.isEmpty()) {
            text(g, fit(L.t("wynnlabs.craft.search_first"), w), x, yy, Theme.INK_3);
            yy += 12;
            return yy - y;
        }
        String[] names = {L.t("wynnlabs.craft.tier_own"), L.t("wynnlabs.craft.tier_two"), L.t("wynnlabs.craft.tier_any")};
        String mainId = weights.isEmpty() ? null : weights.keySet().iterator().next();
        for (int i = 0; i < results.size(); i++) {
            CraftSearch.Outcome o = results.get(i);
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
            String val = main == null ? dec(l.score(), 1) : range(main, unit(mainId));
            if (!o.complete()) {
                val = "≈ " + val;
            }
            text(g, val, x + 70, ry + 3, Theme.INK);
            int mx2 = x + 70 + width(val) + 6;
            List<CraftChecks.Gap> gaps = CraftChecks.gaps(l.result().requirements(), points);
            Art.icon(g, gaps.isEmpty() ? "check" : "x", mx2, ry + 2, 8, Theme.paperRgb(gaps.isEmpty() ? Theme.GOOD : Theme.BAD));
            mx2 += 11;
            if (!l.bought().isEmpty()) {
                String cost = "~" + liveCost(l.bought());
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
                nextPreview = l.slots();
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
        double w = weights.getOrDefault(id, 1.0);
        int i = 0;
        for (int k = 0; k < WEIGHT_STEPS.length; k++) {
            if (Math.abs(WEIGHT_STEPS[k] - w) < 1e-9) {
                i = k;
            }
        }
        weights.put(id, WEIGHT_STEPS[(i + 1) % WEIGHT_STEPS.length]);
    }

    private void startSearch() {
        if (weights.isEmpty()) {
            flash.accept(L.t("wynnlabs.craft.goal_empty"));
            return;
        }
        CraftData.Recipe r = recipe();
        Map<String, CraftSearch.Price> pr = new HashMap<>(prices);
        CraftSearch.Request q = new CraftSearch.Request(r, tier1, tier2, speed, goal(), ingredients, stock.ingredients(),
                level(r.profession()), false, pr::get);
        results = List.of();
        resultsFor = key();
        String forKey = resultsFor;
        searching = runner.submit(q, 3000, out -> {
            if (forKey.equals(resultsFor)) {
                results = List.copyOf(out);
            }
        });
    }

    private void stopSearch() {
        runner.cancel();
        searching = null;
    }

    private void apply(CraftSearch.Layout l) {
        for (int i = 0; i < Craft.SLOTS; i++) {
            Ingredient in = l.slots().get(i);
            slots[i] = in.empty() ? null : in.name();
        }
        flash.accept(L.t("wynnlabs.craft.applied"));
    }

    // ---- below: roll chances, shopping, what can I craft --------------------------------

    private int drawSections(GuiGraphics g, int x, int y, int w, int mx, int my, float pt) {
        int yy = y;
        yy += boxed(g, 0, x, yy, w, (bx, by, bw) -> drawDistribution(g, bx, by, bw, mx, my, pt)) + 5;
        yy += boxed(g, 1, x, yy, w, (bx, by, bw) -> drawShopping(g, bx, by, bw, mx, my)) + 5;
        yy += boxed(g, 2, x, yy, w, (bx, by, bw) -> drawWhat(g, bx, by, bw, mx, my));
        return yy - y;
    }

    private interface Section {
        int draw(int x, int y, int w);
    }

    /** Each folding section's height last frame: its card is drawn first, at that height. */
    private final int[] sectionH = new int[3];

    /** A section on its own light card, as in the mock-up. */
    private int boxed(GuiGraphics g, int i, int x, int y, int w, Section s) {
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

    private int drawDistribution(GuiGraphics g, int x, int y, int w, int mx, int my, float pt) {
        Craft.Result c = result();
        if (distId == null || !c.ids().containsKey(distId)) {
            distId = c.ids().isEmpty() ? null : c.ids().keySet().iterator().next();
        }
        String title = L.t("wynnlabs.craft.dist") + (distId == null ? "" : " · " + statName(distId));
        int yy = y + fold(g, x, y, w, foldDist, title, null, mx, my, () -> foldDist = !foldDist);
        if (!foldDist) {
            return yy - y;
        }
        if (distId == null) {
            text(g, L.t("wynnlabs.craft.dist_none"), x + 10, yy, Theme.INK_3);
            return yy + 12 - y;
        }
        Rolls.Dist d = Rolls.of(distId, layout());
        // a typed value outside the range is asked as typed (above the top: 0%), the knob stays on the range
        int target = distTarget == null ? d.high() : distTarget;
        String u = unit(distId);
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
        Kit.field(g, fx, yy - 1, 40, 12, distBox.isFocused());
        distBox.setX(fx + 3);
        distBox.setY(yy + 1);
        distBox.setWidth(34);
        if (visible(fx, yy - 1, 40, 12)) {
            distBox.render(g, mx, my, pt);
            if (distBox.getValue().isEmpty() && !distBox.isFocused()) {
                text(g, fit(L.t("wynnlabs.craft.dist_value"), 34), fx + 3, yy + 1, Theme.INK_3);
            }
        }
        clipped(fx, yy - 1, 40, 12, () -> {
            screen.setFocused(distBox);
            distBox.setFocused(true);
        }, null, List.of(L.c("wynnlabs.craft.dist_target")));
        yy += 22;
        return yy - y;
    }

    private double lastX;
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
        distBox.setValue(String.valueOf(distTarget));
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
    /** Whether the calculator is in two columns this frame (one on a narrow page). */
    private boolean twoColumns = true;
    /** Identification rows the item card shows; more scroll inside it. */
    private static final int ID_ROWS = 8;
    private int idScroll;
    private int idMax;
    private int[] cardRect;

    private int drawShopping(GuiGraphics g, int x, int y, int w, int mx, int my) {
        CraftData.Recipe r = recipe();
        Shopping.Needs n = Shopping.of(r, tier1, tier2, layout(), stock, prices::get);
        wantedPrices.addAll(n.unpriced());
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
        CraftData.Recipe r = recipe();
        int d = Crafting.pinStation(r.profession(), L.t("wynnlabs.craft.station_label",
                Professions.name(r.profession().toUpperCase(Locale.ROOT))));
        stationText = d < 0 ? L.t("wynnlabs.craft.station_none") : L.t("wynnlabs.craft.station_pinned", d);
    }

    private int drawWhat(GuiGraphics g, int x, int y, int w, int mx, int my) {
        long ready = what.stream().filter(WhatCanICraft.Entry::craftable).count();
        String right = what.isEmpty() ? null : L.t("wynnlabs.craft.what_ready", ready);
        int yy = y + fold(g, x, y, w, foldWhat, L.t("wynnlabs.craft.what"), right, mx, my, () -> {
            foldWhat = !foldWhat;
            if (foldWhat) {
                startWhat();
            }
        });
        if (!foldWhat) {
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
                String main = weights.isEmpty() ? null : weights.keySet().iterator().next();
                Craft.Range v = main == null ? null : e.best().result().ids().get(main);
                right2 = v == null ? "✔" : shortStat(main) + " " + range(v, unit(main));
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

    private void startWhat() {
        what.clear();
        WhatCanICraft.Stock s = previewLevels.isEmpty() ? stock
                : new WhatCanICraft.Stock(stock.ingredients(), stock.materials(), previewLevels);
        CraftSearch.Goal goal = goal();
        List<Ingredient> all = ingredients;
        whatSearching = whatRunner.run(15 * 400, b -> WhatCanICraft.run(s, goal, all, 300, b, what::add));
    }

    private void loadWhat(WhatCanICraft.Entry e) {
        if (e.recipe() == null) {
            return;
        }
        type = e.type();
        minLevel = e.recipe().minLevel();
        tier1 = Math.max(1, e.tier1());
        tier2 = Math.max(1, e.tier2());
        Arrays.fill(slots, null);
        if (e.best() != null) {
            apply(e.best());
        }
        scroll = 0;
    }

    // ---- our suggestions --------------------------------------------------------------------

    private int view;
    private final List<Suggestions.Suggestion> suggestions = new CopyOnWriteArrayList<>();
    private CraftSearch.Budget suggesting;
    private String suggestedFor = "";
    private int suggestPage;
    private int suggestFilter;
    private static final String[] SUGGEST_FILTERS = {"all", "craftable", "stock", "cheap", "weapon", "armour", "accessory",
        "consumable"};

    private int viewTabs(GuiGraphics g, int x, int y, int w, int mx, int my) {
        String[][] tabs = {{"hammer", L.t("wynnlabs.craft.view_calc")}, {"star-filled", L.t("wynnlabs.craft.view_suggest")}};
        int tx = x;
        for (int i = 0; i < tabs.length; i++) {
            int bw = width(tabs[i][1]) + 24;
            boolean on = i == view;
            int state = on ? Kit.ON : in(mx, my, tx, y, bw, 14) ? Kit.HOVER : Kit.REST;
            Kit.ledger(g, tx, y, bw, 14, on);
            Art.icon(g, tabs[i][0], tx + 6, y + 3, 8, on ? Theme.INK : Theme.CREAM);
            wood(g, tabs[i][1], tx + 17, y + 3, Kit.buttonText(state), !on);
            int v = i;
            acts.add(new Act(tx, y, bw, 14, () -> {
                view = v;
                open = Drop.NONE;
                if (v == 1) {
                    if (suggestMode == 0) {
                        startSets(false);
                    } else {
                        startSuggestions(false);
                    }
                }
            }, null, null));
            tx += bw + 3;
        }
        g.fill(x, y + 14, x + w, y + 15, Theme.GOLD_EDGE);
        return 14;
    }

    /** The class's order of goal sets: spell first for casters, melee first for the others. */
    private List<String> goalOrder() {
        Klass k;
        try {
            k = GameState.current().classType();
        } catch (Throwable t) {
            k = Klass.NONE;
        }
        return k == Klass.MAGE || k == Klass.SHAMAN
                ? List.of("spell", "mana", "health", "melee", "speed", "lifesteal", "loot", "xp")
                : List.of("melee", "health", "spell", "mana", "speed", "lifesteal", "loot", "xp");
    }

    /** The class's weapon, every armour piece and accessory, and the consumables. */
    private List<String> suggestTypes() {
        List<String> t = new ArrayList<>();
        t.add(defaultType());
        if (t.get(0).equals("ring")) {
            t.clear();
            t.addAll(Types.WEAPONS);
        }
        t.addAll(Types.ARMOUR);
        t.addAll(Types.ACCESSORIES);
        t.addAll(Types.CONSUMABLES);
        return t;
    }

    private String suggestKey() {
        return stock.ingredients().hashCode() + "|" + stock.levels() + "|" + defaultType() + "|" + combatLevel();
    }

    private static int combatLevel() {
        try {
            return Math.max(1, GameState.current().level());
        } catch (Throwable t) {
            return 1;
        }
    }

    /** A suggestion whose recipe or an ingredient needs a higher profession level than the player has. */
    private boolean locked(Suggestions.Suggestion s) {
        int lv = level(s.recipe().profession());
        if (s.recipe().minLevel() > lv) {
            return true;
        }
        for (Ingredient in : s.layout().slots()) {
            if (!in.empty() && in.level() > lv) {
                return true;
            }
        }
        return false;
    }

    /** The profession level a suggestion needs: its recipe's or its highest ingredient's. */
    private static int needs(Suggestions.Suggestion s) {
        int n = s.recipe().minLevel();
        for (Ingredient in : s.layout().slots()) {
            n = Math.max(n, in.empty() ? 0 : in.level());
        }
        return n;
    }

    private void startSuggestions(boolean force) {
        String k = suggestKey();
        if (!force && k.equals(suggestedFor) && (suggesting != null || !suggestions.isEmpty())) {
            return;
        }
        suggestedFor = k;
        suggestions.clear();
        suggestPage = 0;
        WhatCanICraft.Stock st = previewLevels.isEmpty() ? stock
                : new WhatCanICraft.Stock(stock.ingredients(), stock.materials(), previewLevels);
        List<Ingredient> all = ingredients;
        Map<String, CraftSearch.Price> pr = new HashMap<>(prices);
        List<String> types = suggestTypes();
        List<Suggestions.GoalSet> goals = Suggestions.goalSets(goalOrder());
        int combat = combatLevel();
        suggesting = suggestRunner.run(120_000, b -> {
            Suggestions.run(types, goals, combat, st, all, pr::get, 150, b, suggestions::add);
            b.cancel();                              // done: the page stops showing progress
        });
    }

    /** For the preview: start working the suggestions out now. */
    private void startSuggestionsNow() {
        startSuggestions(true);
    }

    private boolean suggestShown(Suggestions.Suggestion s) {
        String t = s.recipe().type();
        return switch (SUGGEST_FILTERS[suggestFilter]) {
            case "craftable" -> !locked(s);
            case "stock" -> s.fromStock();
            case "cheap" -> s.layout().cost() < 64 && s.layout().costComplete();
            case "weapon" -> Types.weapon(t);
            case "armour" -> Types.armour(t);
            case "accessory" -> Types.accessory(t);
            case "consumable" -> Types.consumable(t);
            default -> true;
        };
    }

    private void drawSuggestions(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        clip = null;
        // sets or single pieces, then the filters
        int fx = x;
        String[] modes = {L.t("wynnlabs.craft.suggest_mode.sets"), L.t("wynnlabs.craft.suggest_mode.singles")};
        for (int i = 0; i < modes.length; i++) {
            int bw = width(modes[i]) + 10;
            boolean on = i == suggestMode;
            int state = on ? Kit.ON : in(mx, my, fx, y, bw, ROW_H) ? Kit.HOVER : Kit.REST;
            Kit.button(g, fx, y, bw, ROW_H, state);
            wood(g, modes[i], fx + 5, y + 2, Kit.buttonText(state), !on);
            int m = i;
            acts.add(new Act(fx, y, bw, ROW_H, () -> {
                suggestMode = m;
                suggestPage = 0;
                setPage = 0;
                if (m == 0) {
                    startSets(false);
                } else {
                    startSuggestions(false);
                }
            }, null, null));
            fx += bw + 2;
        }
        fx += 8;
        for (int i = 0; i < SUGGEST_FILTERS.length; i++) {
            if (suggestMode == 0 && i > 3) {
                break;                              // type filters are for single pieces
            }
            String name = L.t("wynnlabs.craft.suggest_filter." + SUGGEST_FILTERS[i]);
            int bw = width(name) + 10;
            if (fx + bw > x + w - 40) {
                break;
            }
            boolean on = i == suggestFilter;
            int state = on ? Kit.ON : in(mx, my, fx, y, bw, ROW_H) ? Kit.HOVER : Kit.REST;
            Kit.button(g, fx, y, bw, ROW_H, state);
            wood(g, name, fx + 5, y + 2, Kit.buttonText(state), !on);
            int f = i;
            acts.add(new Act(fx, y, bw, ROW_H, () -> {
                suggestFilter = f;
                suggestPage = 0;
            }, null, null));
            fx += bw + 3;
        }
        String again = L.t("wynnlabs.craft.suggest_refresh");
        int aw = width(again) + 18;
        int ax = x + w - aw;
        int as = in(mx, my, ax, y, aw, ROW_H) ? Kit.HOVER : Kit.REST;
        Kit.button(g, ax, y, aw, ROW_H, as);
        Art.icon(g, "refresh", ax + 4, y + 2, 8, Kit.buttonText(as));
        wood(g, again, ax + 14, y + 2, Kit.buttonText(as), true);
        acts.add(new Act(ax, y, aw, ROW_H, () -> {
            if (suggestMode == 0) {
                startSets(true);
            } else {
                startSuggestions(true);
            }
        }, null, List.of(L.c("wynnlabs.craft.suggest_refresh_tip"))));
        int top = y + ROW_H + 6;
        if (suggestMode == 0) {
            drawSets(g, x, top, w, y + h - top, mx, my);
            return;
        }

        List<Suggestions.Suggestion> list = new ArrayList<>();
        for (Suggestions.Suggestion s : suggestions) {
            if (suggestShown(s)) {
                list.add(s);
            }
        }
        List<String> goals = goalOrder();
        list.sort((a, b) -> {
            if (a.fromStock() != b.fromStock()) {
                return a.fromStock() ? -1 : 1;
            }
            int ga = goals.indexOf(a.goal().key());
            int gb = goals.indexOf(b.goal().key());
            if (ga != gb) {
                return Integer.compare(ga, gb);
            }
            return Double.compare(b.layout().score(), a.layout().score());
        });
        boolean running = suggesting != null && !suggesting.cancelled();
        int cols = w >= TWO_COLUMNS_MIN ? 2 : 1;
        int cardH = 49;
        int gap = 5;
        int pageBar = 16;
        int rows = Math.max(1, (h - (top - y) - pageBar) / (cardH + gap));
        int per = rows * cols;
        int pages = Math.max(1, (list.size() + per - 1) / per);
        suggestPage = Math.max(0, Math.min(suggestPage, pages - 1));
        int cardW = (w - (cols - 1) * 8) / cols;
        if (list.isEmpty()) {
            String t = L.t(running ? "wynnlabs.craft.suggest_working" : "wynnlabs.craft.suggest_none");
            text(g, t, x + (w - width(t)) / 2, top + 30, Theme.INK_3);
        }
        for (int i = 0; i < per && suggestPage * per + i < list.size(); i++) {
            Suggestions.Suggestion s = list.get(suggestPage * per + i);
            int cx = x + (i % cols) * (cardW + 8);
            int cy = top + (i / cols) * (cardH + gap);
            suggestCard(g, s, cx, cy, cardW, cardH, mx, my);
        }
        // the page bar: ‹ 1 2 3 … › and how many there are
        int by = y + h - pageBar + 2;
        String count = L.n(running ? "wynnlabs.craft.suggest_count_running" : "wynnlabs.craft.suggest_count", list.size(),
                list.size(), suggestPage + 1, pages);
        fine(g, count, x, by + 2, Theme.INK_3);
        int px = x + w;
        List<int[]> buttons = new ArrayList<>();             // {page, width}
        buttons.add(new int[] {suggestPage + 1, -1});        // next (›)
        int from = Math.max(0, Math.min(suggestPage - 3, pages - 7));
        for (int pg = Math.min(pages - 1, from + 6); pg >= from; pg--) {
            buttons.add(new int[] {pg, 0});
        }
        buttons.add(new int[] {suggestPage - 1, -2});        // previous (‹)
        for (int[] b : buttons) {
            String label = b[1] < 0 ? "" : String.valueOf(b[0] + 1);
            int bw = Math.max(14, width(label) + 8);
            px -= bw;
            boolean usable = b[0] >= 0 && b[0] < pages;
            boolean on = b[1] == 0 && b[0] == suggestPage;
            int state = on ? Kit.ON : usable && in(mx, my, px, by, bw, ROW_H) ? Kit.HOVER : Kit.REST;
            Kit.button(g, px, by, bw, ROW_H, state);
            if (b[1] < 0) {
                Art.icon(g, b[1] == -1 ? "chevron-right" : "chevron-left", px + (bw - 8) / 2, by + 2, 8,
                        usable ? Kit.buttonText(state) : Theme.CREAM_DIM);
            } else {
                // digits have no descenders: a pixel up looks centred
                wood(g, label, px + (bw - width(label)) / 2, by + 1, Kit.buttonText(state), !on);
            }
            if (usable) {
                int target = b[0];
                acts.add(new Act(px, by, bw, ROW_H, () -> suggestPage = target, null, null));
            }
            px -= 2;
        }
        pageCount = pages;
    }

    private int pageCount = 1;

    private void suggestCard(GuiGraphics g, Suggestions.Suggestion s, int x, int y, int w, int h, int mx, int my) {
        CraftData.Recipe r = s.recipe();
        CraftSearch.Layout l = s.layout();
        Kit.card(g, x, y, w, h, s.fromStock() ? Theme.GOOD : 0, in(mx, my, x, y, w, h));
        int ix = x + 6;
        // line 1: icon, name, the two marks
        Art.icon(g, typeIcon(r.type()), ix, y + 4, 8, Theme.paperRgb(Theme.INK_2));
        String name = L.t("wynnlabs.craft.suggest_name", L.t("wynnlabs.craft.goalset." + s.goal().key()), typeName(r.type()));
        List<CraftChecks.Gap> gaps = r.consumable() ? List.of() : CraftChecks.gaps(l.result().requirements(), points);
        String wear = r.consumable() ? L.t("wynnlabs.craft.can_use") : gaps.isEmpty() ? L.t("wynnlabs.craft.can_wear")
                : L.t("wynnlabs.craft.cannot_wear", skill(gaps.get(0).skill()), gaps.get(0).missing());
        String stockTag = s.fromStock() ? L.t("wynnlabs.craft.suggest_in_stock")
                : L.t("wynnlabs.craft.suggest_to_buy", l.bought().size());
        int right = x + w - 6;
        right -= tag(g, right, y + 3, stockTag, s.fromStock(), true) + 3;
        right -= tag(g, right, y + 3, wear, gaps.isEmpty(), true) + 3;
        text(g, Component.literal(fit(name, right - ix - 14)).withStyle(UiFont::bold), ix + 11, y + 4, Theme.INK);
        // line 2: bracket and tiers, the search it came from
        String sub = typeName(r.type()) + " " + r.bracket();
        text(g, sub, ix, y + 15, Theme.INK_2);
        int sx = ix + width(sub) + 5;
        starIcons(g, sx, y + 16, s.tier1(), Theme.PRICE);
        starIcons(g, sx + 6 * s.tier1() + 4, y + 16, s.tier2(), Theme.PRICE);
        boolean locked = locked(s);
        String why = locked ? L.t("wynnlabs.craft.suggest_locked",
                Professions.name(r.profession().toUpperCase(Locale.ROOT)), needs(s))
                : L.t("wynnlabs.craft.suggest_tier." + s.tier().name().toLowerCase(Locale.ROOT));
        if (locked) {
            Art.icon(g, "lock", x + w - 6 - fineWidth(why) - 10, y + 16, 8, Theme.paperRgb(Theme.BAD));
        }
        fine(g, why, x + w - 6 - fineWidth(why), y + 16, locked ? Theme.BAD : Theme.ACCENT);
        // line 3: the goal's two strongest identifications
        int vx = ix;
        int shownIds = 0;
        for (String id : s.goal().goal().weights().keySet()) {
            Craft.Range v = l.result().ids().get(id);
            if (v == null || shownIds == 2) {
                continue;
            }
            String t = statName(id) + " ";
            text(g, t, vx, y + 26, Theme.INK_2);
            vx += width(t);
            String val = range(v, unit(id));
            text(g, val, vx, y + 26, v.max() < 0 ? Theme.BAD : Theme.GOOD);
            vx += width(val) + 8;
            shownIds++;
        }
        // line 4: cost, and Open / Copy
        String cost = l.bought().isEmpty() ? L.t("wynnlabs.craft.cost_none")
                : costLine(liveCost(l.bought()));
        fine(g, cost, ix, y + 36, l.bought().isEmpty() ? Theme.GOOD : Theme.PRICE);
        String openL = L.t("wynnlabs.craft.suggest_open");
        String copyL = L.t("wynnlabs.craft.code");
        int ow = width(openL) + 10;
        int cw = width(copyL) + 10;
        int oxp = x + w - 6 - ow;
        int cxp = oxp - 3 - cw;
        int by = y + h - 14;
        int os = in(mx, my, oxp, by, ow, 11) ? Kit.HOVER : Kit.ON;
        Kit.button(g, oxp, by, ow, 11, os);
        wood(g, openL, oxp + 5, by + 1, os == Kit.ON ? Theme.INK : Theme.CREAM, os != Kit.ON);
        int cs = in(mx, my, cxp, by, cw, 11) ? Kit.HOVER : Kit.REST;
        Kit.button(g, cxp, by, cw, 11, cs);
        wood(g, copyL, cxp + 5, by + 1, Kit.buttonText(cs), true);
        List<Component> tip = new ArrayList<>();
        for (Ingredient in : l.slots()) {
            tip.add(Component.literal(in.empty() ? "—" : in.name() + (owned(in.name()) > 0 ? " ×" + owned(in.name()) : ""))
                    .withStyle(in.empty() ? ChatFormatting.GRAY : ChatFormatting.WHITE));
        }
        acts.add(new Act(oxp, by, ow, 11, () -> openSuggestion(s), null, List.of(L.c("wynnlabs.craft.suggest_open_tip"))));
        acts.add(new Act(cxp, by, cw, 11, () -> copySuggestion(s), null, List.of(L.c("wynnlabs.craft.tip.copy"))));
        acts.add(new Act(x, y, w, h, null, null, tip));
    }

    private RecipeCode.Recipe codeOf(Suggestions.Suggestion s) {
        List<String> names = new ArrayList<>();
        for (Ingredient in : s.layout().slots()) {
            names.add(in.empty() ? null : in.name());
        }
        return new RecipeCode.Recipe(s.recipe().type(), s.recipe().minLevel(), s.tier1(), s.tier2(), Craft.Speed.NORMAL, names);
    }

    private void copySuggestion(Suggestions.Suggestion s) {
        RecipeCode.Recipe r = codeOf(s);
        String code = WynnBuilderCode.encode(r);
        Minecraft.getInstance().keyboardHandler.setClipboard(code != null ? code : RecipeCode.encode(r));
        flash.accept(L.t("wynnlabs.craft.code_copied"));
    }

    private void openSuggestion(Suggestions.Suggestion s) {
        type = s.recipe().type();
        minLevel = s.recipe().minLevel();
        tier1 = s.tier1();
        tier2 = s.tier2();
        speed = Craft.Speed.NORMAL;
        for (int i = 0; i < Craft.SLOTS; i++) {
            Ingredient in = s.layout().slots().get(i);
            slots[i] = in.empty() ? null : in.name();
        }
        weights.clear();
        weights.putAll(s.goal().goal().weights());
        view = 0;
        scroll = 0;
        flash.accept(L.t("wynnlabs.craft.applied"));
    }


    // ---- suggestions as whole sets -----------------------------------------------------------

    private int suggestMode;
    private final List<SetSuggestions.CraftSet> sets = new CopyOnWriteArrayList<>();
    private final SearchRunner setRunner = new SearchRunner();
    private CraftSearch.Budget setting;
    private String setsFor = "";
    private int setPage;
    private SetSuggestions.CraftSet setDetail;

    /** For the preview: start working the sets out now. */
    private void startSetsNow() {
        startSets(true);
    }

    private void startSets(boolean force) {
        String k = suggestKey();
        if (!force && k.equals(setsFor) && (setting != null || !sets.isEmpty())) {
            return;
        }
        setsFor = k;
        sets.clear();
        setPage = 0;
        WhatCanICraft.Stock st = previewLevels.isEmpty() ? stock
                : new WhatCanICraft.Stock(stock.ingredients(), stock.materials(), previewLevels);
        List<Ingredient> all = ingredients;
        Map<String, CraftSearch.Price> pr = new HashMap<>(prices);
        String weapon = suggestTypes().get(0);
        List<Suggestions.GoalSet> goals = Suggestions.goalSets(goalOrder());
        int combat = combatLevel();
        setting = setRunner.run(300_000, b -> {
            SetSuggestions.run(weapon, goals, combat, st, all, pr::get, 120, b, sets::add);
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
        int lv = level(pc.recipe().profession());
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
        return switch (SUGGEST_FILTERS[suggestFilter]) {
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
            CraftSearch.Price p = prices.get(n);
            if (p == null) {
                unknown++;
                wantedPrices.add(n);
            } else {
                cost += p.emeralds();
                priced++;
            }
        }
        return new long[] {cost, priced, unknown};
    }

    private void drawSets(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        List<SetSuggestions.CraftSet> list = new ArrayList<>();
        for (SetSuggestions.CraftSet s : sets) {
            if (setShown(s)) {
                list.add(s);
            }
        }
        List<String> goals = goalOrder();
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
        int cols = w >= TWO_COLUMNS_MIN ? 2 : 1;
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
        pageCount = pages;
        if (setDetail != null) {
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
                acts.add(new Act(px, by, bw, ROW_H, () -> go.accept(target), null, null));
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
                return range(v, unit(id));
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
                    && !CraftChecks.gaps(pc.layout().result().requirements(), points).isEmpty()) {
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
            tot.append(tot.isEmpty() ? "" : " · ").append(statName(id)).append(' ').append(range(v, unit(id)));
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
                : L.t("wynnlabs.craft.set_req", skill(maxSkill), s.maxRequirements()[maxSkill], points[maxSkill]);
        boolean reqOk = maxSkill < 0 || points[maxSkill] >= s.maxRequirements()[maxSkill];
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
        acts.add(new Act(vx, by, vw, 11, () -> setDetail = s, null, List.of(L.c("wynnlabs.craft.set_view_tip"))));
        acts.add(new Act(sx, by, sw, 11, () -> saveSet(s), null, List.of(L.c("wynnlabs.craft.set_save_tip"))));
    }

    /** The set's pieces in a list over the page: open or copy each, copy all codes, save all. */
    private void setDetail(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        SetSuggestions.CraftSet s = setDetail;
        g.nextStratum();
        Kit.card(g, x, y, w, h, Theme.GOLD_DEEP);
        overlayBox = new int[] {x, y, w, h};
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
            overlay.add(new Act(bx, y + 4, bw, ROW_H, () -> {
                switch (what) {
                    case "close" -> setDetail = null;
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
                tip.add(Component.literal(in.empty() ? "—" : in.name() + (owned(in.name()) > 0 ? " ×" + owned(in.name()) : ""))
                        .withStyle(in.empty() ? ChatFormatting.GRAY : ChatFormatting.WHITE));
            }
            SetSuggestions.Piece piece = pc;
            overlay.add(new Act(ox, by, ow, 11, () -> {
                openPiece(s, piece);
                setDetail = null;
            }, null, List.of(L.c("wynnlabs.craft.suggest_open_tip"))));
            overlay.add(new Act(cx, by, cw, 11, () -> {
                Minecraft.getInstance().keyboardHandler.setClipboard(pieceCode(piece));
                flash.accept(L.t("wynnlabs.craft.code_copied"));
            }, null, null));
            overlay.add(new Act(x + 4, ry, w - 8, rowH, null, null, tip));
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
        flash.accept(L.n("wynnlabs.craft.set_codes_copied", lines.size()));
    }

    private void openPiece(SetSuggestions.CraftSet s, SetSuggestions.Piece pc) {
        type = pc.recipe().type();
        minLevel = pc.recipe().minLevel();
        tier1 = pc.tier1();
        tier2 = pc.tier2();
        speed = Craft.Speed.NORMAL;
        for (int i = 0; i < Craft.SLOTS; i++) {
            Ingredient in = pc.layout().slots().get(i);
            slots[i] = in.empty() ? null : in.name();
        }
        weights.clear();
        weights.putAll(s.goal().goal().weights());
        view = 0;
        scroll = 0;
        flash.accept(L.t("wynnlabs.craft.applied"));
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
        flash.accept(L.n("wynnlabs.craft.set_saved", n));
    }

    private int subTabs(GuiGraphics g, int x, int y, int w, int mx, int my) {
        String[] names = {L.t("wynnlabs.craft.sub_recipe"), L.t("wynnlabs.craft.sub_result"), L.t("wynnlabs.craft.sub_search")};
        int bw = (w - 8) / 3;
        for (int i = 0; i < 3; i++) {
            int bx = x + i * (bw + 4);
            boolean on = i == subTab;
            int state = on ? Kit.ON : in(mx, my, bx, y, bw, 14) ? Kit.HOVER : Kit.REST;
            Kit.ledger(g, bx, y, bw, 14, on);
            wood(g, names[i], bx + (bw - width(names[i])) / 2, y + 3, Kit.buttonText(state), !on);
            int t = i;
            clipped(bx, y, bw, 14, () -> {
                subTab = t;
                scroll = 0;
            }, null, null);
        }
        return 14;
    }

    // ---- saved recipes: beside the menu, or a drawer ------------------------------------

    private void drawSaved(GuiGraphics g, int[] frame, int screenW, int px, int py, int pw, int ph, boolean two,
                           int mx, int my) {
        int fx = frame[0];
        int fy = frame[1];
        int fw = frame[2];
        int fh = frame[3];
        int sideX = fx + fw + 5 + 4;
        boolean room = sideX + SIDE_W + 4 <= screenW;
        if (room) {
            Kit.window(g, sideX - 5, fy - 5, SIDE_W + 10, fh + 10);
            Kit.paper(g, sideX, fy, SIDE_W, fh);
            savedList(g, sideX + 8, fy + 10, SIDE_W - 16, fh - 20, mx, my, false);
            drawerOpen = false;
            return;
        }
        // the ribbon on the menu's right edge
        // (over the frame's edge when the window ends there)
        int rx = Math.min(fx + fw + 1, screenW - 9);
        int ry = fy + 24;
        LayoutCheck.frame(g.pose(), rx, ry, 9, 40);
        if (DevHooks.ON) {
            DevHooks.frame(g.pose(), rx, ry, 9, 40);         // the dev screenshot tool's crop keeps the ribbon
        }
        boolean rh = in(mx, my, rx, ry, 9, 40);
        Kit.button(g, rx, ry, 9, 40, drawerOpen ? Kit.ON : rh ? Kit.HOVER : Kit.REST);
        Art.icon(g, "bookmark", rx + 1, ry + 16, 7, drawerOpen ? Theme.INK : Theme.CREAM);
        acts.add(new Act(rx, ry, 9, 40, () -> drawerOpen = !drawerOpen, null, List.of(L.c("wynnlabs.craft.saved"))));
        if (drawerOpen) {
            int dw = two ? Math.min(150, pw / 2) : Math.min(170, pw);
            int dx = px + pw - dw;
            g.nextStratum();
            Kit.card(g, dx, py, dw, ph, Theme.GOLD_DEEP);
            overlayBox = new int[] {dx, py, dw, ph};
            savedList(g, dx + 6, py + 6, dw - 12, ph - 12, mx, my, true);
        }
    }

    private void savedList(GuiGraphics g, int x, int y, int w, int h, int mx, int my, boolean inDrawer) {
        List<Act> into = inDrawer ? overlay : acts;
        SavedRecipes saved = Crafting.saved();
        text(g, Component.literal(L.t("wynnlabs.craft.saved")).withStyle(UiFont::bold), x, y, Theme.INK);
        int yy = y + 13;
        String save = L.t(renaming != null ? "wynnlabs.craft.rename" : "wynnlabs.craft.save");
        int bw = width(save) + 10;
        int fw = w - bw - 3;
        Kit.field(g, x, yy, fw, 12, saveName.isFocused());
        saveName.setX(x + 3);
        saveName.setY(yy + 2);
        saveName.setWidth(fw - 6);
        saveName.render(g, mx, my, 0);
        if (saveName.getValue().isEmpty() && !saveName.isFocused()) {
            text(g, fit(L.t("wynnlabs.craft.saved_name"), fw - 6), x + 3, yy + 2, Theme.INK_3);
        }
        into.add(new Act(x, yy, fw, 12, () -> {
            screen.setFocused(saveName);
            saveName.setFocused(true);
        }, null, null));
        int sbx = x + fw + 3;
        boolean sh = in(mx, my, sbx, yy - 1, bw, 14);
        Kit.button(g, sbx, yy - 1, bw, 14, sh ? Kit.HOVER : Kit.ON);
        wood(g, save, sbx + 5, yy + 2, sh ? Theme.CREAM : Theme.INK, sh);
        into.add(new Act(sbx, yy - 1, bw, 14, this::saveCurrent, null, null));
        yy += 18;
        if (saved.all().isEmpty()) {
            for (FormattedCharSequence line : font.split(sized(L.c("wynnlabs.craft.saved_empty"), FINE), w)) {
                g.drawString(font, Kit.ink(line), x, yy, Theme.INK_3, false);
                yy += 10;
            }
        }
        int rowH = 32;
        int bottom = y + h - 22;
        for (int i = 0; i < saved.all().size() && yy + rowH <= bottom; i++) {
            SavedRecipes.Saved s = saved.all().get(i);
            boolean hover = in(mx, my, x - 2, yy, w + 4, rowH - 2);
            Kit.row(g, x - 2, yy, w + 4, rowH - 2, i, hover);
            text(g, Component.literal(fit(s.name(), w - (hover ? 34 : 2))).withStyle(UiFont::bold), x, yy + 2, Theme.INK);
            CraftData.Recipe r = s.recipe();
            List<Ingredient> lay = new ArrayList<>();
            for (String n : s.slots()) {
                lay.add(ing(n));
            }
            String sub = typeName(s.type()) + " " + (r == null ? "?" : r.bracket());
            String wear = "";
            boolean wearOk = true;
            String mats = "";
            boolean matsOk = true;
            if (r != null) {
                Craft.Result c = Craft.craft(r, s.tier1(), s.tier2(), s.attackSpeed(), lay);
                String main = s.goal() == null || s.goal().weights() == null || s.goal().weights().isEmpty() ? null
                        : s.goal().weights().keySet().iterator().next();
                Craft.Range v = main == null ? null : c.ids().get(main);
                if (v != null) {
                    sub += " · " + range(v, unit(main));
                }
                List<CraftChecks.Gap> gaps = r.consumable() ? List.of() : CraftChecks.gaps(c.requirements(), points);
                wearOk = gaps.isEmpty();
                wear = r.consumable() ? L.t("wynnlabs.craft.can_use") : wearOk ? L.t("wynnlabs.craft.can_wear")
                        : L.t("wynnlabs.craft.cannot_wear", skill(gaps.get(0).skill()), gaps.get(0).missing());
                matsOk = StockView.missing(r, s.tier1(), s.tier2(), stock).isEmpty();
                mats = " · " + (matsOk ? L.t("wynnlabs.craft.mats_ok") : L.t("wynnlabs.craft.mats_short"));
            }
            text(g, fit(sub, w), x, yy + 11, Theme.INK_2);
            String wearShown = fit(wear, w);
            text(g, wearShown, x, yy + 20, wearOk ? Theme.GOOD : Theme.BAD);
            int mx3 = x + width(wearShown);
            text(g, fit(mats, x + w - mx3), mx3, yy + 20, matsOk ? Theme.GOOD : Theme.BAD);
            String name = s.name();
            int ry = yy;
            // the row opens the recipe; its buttons, added after, are found first on a click
            into.add(new Act(x - 2, yy, w + 4, rowH - 2, () -> openSaved(s), null, null));
            if (hover) {
                // copy, rename, delete on the hovered row
                int ix = x + w - 30;
                boolean armed = name.equals(deleteArmed) && System.currentTimeMillis() - deleteArmedAt < 3000;
                Art.icon(g, "copy", ix, ry + 2, 8, Theme.paperRgb(Theme.INK_2));
                Art.icon(g, "pencil", ix + 11, ry + 2, 8, Theme.paperRgb(Theme.INK_2));
                Art.icon(g, "trash", ix + 22, ry + 2, 8, Theme.paperRgb(armed ? Theme.BAD : Theme.INK_2));
                into.add(new Act(ix, ry + 1, 10, 10, () -> {
                    SavedRecipes.Saved c = saved.copy(name);
                    Crafting.persist();
                    if (c != null) {
                        flash.accept(L.t("wynnlabs.craft.copied", c.name()));
                    }
                }, null, List.of(L.c("wynnlabs.craft.copy_tip"))));
                into.add(new Act(ix + 11, ry + 1, 10, 10, () -> {
                    if (name.equals(renaming)) {
                        stopRenaming();                 // the pencil again: back to saving
                        return;
                    }
                    renaming = name;
                    saveName.setValue(name);
                    screen.setFocused(saveName);
                    saveName.setFocused(true);
                }, null, List.of(L.c("wynnlabs.craft.rename_tip"))));
                into.add(new Act(ix + 22, ry + 1, 10, 10, () -> deleteClick(name), null,
                        List.of(L.c(armed ? "wynnlabs.craft.delete_again" : "wynnlabs.craft.delete_tip"))));
            }
            yy += rowH;
        }
        for (FormattedCharSequence line : font.split(sized(L.c("wynnlabs.craft.saved_hint"), FINE), w)) {
            if (bottom + 10 > y + h) {
                break;
            }
            g.drawString(font, Kit.ink(line), x, bottom, Theme.INK_3, false);
            bottom += 10;
        }
    }

    private void deleteClick(String name) {
        long now = System.currentTimeMillis();
        if (name.equals(deleteArmed) && now - deleteArmedAt < 3000) {
            Crafting.saved().delete(name);
            Crafting.persist();
            deleteArmed = null;
            flash.accept(L.t("wynnlabs.craft.deleted", name));
        } else {
            deleteArmed = name;
            deleteArmedAt = now;
        }
    }

    /** Back from renaming a saved recipe to saving a new one: the button reads "Kaydet" again. */
    private void stopRenaming() {
        renaming = null;
        saveName.setValue("");
        saveName.setFocused(false);
    }

    private void saveCurrent() {
        String name = saveName.getValue().strip();
        if (name.isEmpty()) {
            screen.setFocused(saveName);
            saveName.setFocused(true);
            flash.accept(L.t("wynnlabs.craft.name_first"));
            return;
        }
        SavedRecipes saved = Crafting.saved();
        if (renaming != null) {
            if (name.equals(renaming)) {
                stopRenaming();                         // the same name: nothing to change
                return;
            }
            if (saved.rename(renaming, name)) {
                flash.accept(L.t("wynnlabs.craft.renamed", name));
                renaming = null;
                saveName.setValue("");
                Crafting.persist();
            } else {
                flash.accept(L.t("wynnlabs.craft.name_taken", name));
            }
            return;
        }
        SavedRecipes.Saved s = new SavedRecipes.Saved(name, type, minLevel, tier1, tier2, speed.name(),
                java.util.Collections.unmodifiableList(Arrays.asList(slots.clone())), SavedRecipes.SavedGoal.of(goal()),
                System.currentTimeMillis());
        boolean replace = name.equals(deleteArmed);      // a second click on Save with the same taken name replaces it
        if (!saved.save(s, replace)) {
            deleteArmed = name;
            deleteArmedAt = System.currentTimeMillis();
            flash.accept(L.t("wynnlabs.craft.name_taken_replace", name));
            return;
        }
        deleteArmed = null;
        Crafting.persist();
        saveName.setValue("");
        flash.accept(L.t("wynnlabs.craft.saved_one", name));
    }

    private void openSaved(SavedRecipes.Saved s) {
        if (s.recipe() == null) {
            return;
        }
        type = s.type();
        minLevel = s.minLevel();
        tier1 = s.tier1();
        tier2 = s.tier2();
        speed = s.attackSpeed();
        for (int i = 0; i < Craft.SLOTS; i++) {
            slots[i] = i < s.slots().size() ? s.slots().get(i) : null;
        }
        if (s.goal() != null) {
            CraftSearch.Goal goal = s.goal().goal();
            weights.clear();
            weights.putAll(goal.weights());
            noNegative.clear();
            noNegative.addAll(goal.noNegative());
            floorIndex = 0;
            for (int i = 0; i < FLOORS.length; i++) {
                if (FLOORS[i] == goal.durabilityFloor()) {
                    floorIndex = i;
                }
            }
        }
        drawerOpen = false;
        flash.accept(L.t("wynnlabs.craft.opened", s.name()));
    }

    // ---- drop-down lists -----------------------------------------------------------------

    private void toggle(Drop d, int slot, int[] anchor) {
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

    private void drawDrop(GuiGraphics g, int mx, int my, float pt) {
        if (open == Drop.NONE || openAnchor == null) {
            return;
        }
        g.nextStratum();
        switch (open) {
            case TYPE -> dropTypes(g, mx, my);
            case LEVEL -> dropLevels(g, mx, my);
            case SLOT -> dropPicker(g, mx, my, pt);
            case GOAL -> dropGoal(g, mx, my);
            case CONDITIONS -> dropConditions(g, mx, my);
            default -> { }
        }
    }

    private int dropX(int w) {
        return Math.max(4, Math.min(openAnchor[0], screen.width - w - 4));
    }

    /** The list opens under its field, or above it when there is no room below. */
    private int dropY(int h) {
        int below = openAnchor[1] + openAnchor[3] + 2;
        if (below + h <= screen.height - 4) {
            return below;
        }
        return Math.max(4, openAnchor[1] - h - 2);
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
        overlayBox = new int[] {x, y, w, h};
        java.util.Set<String> ready = new java.util.HashSet<>();
        for (WhatCanICraft.Entry e : what) {
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
                    boolean on = t.equals(type);
                    boolean hover = in(mx, my, cx, ry, colW, rowH);
                    Kit.row(g, cx, ry, colW, rowH, 0, on || hover);
                    Art.icon(g, typeIcon(t), cx + 6, ry + 2, 8, Theme.paperRgb(on ? Theme.INK : Theme.INK_2));
                    text(g, typeName(t), cx + 18, ry + 2, on ? Theme.INK : Theme.INK_2);
                    if (ready.contains(t)) {
                        Art.icon(g, "check", cx + colW - 10, ry + 2, 8, Theme.paperRgb(Theme.GOOD));
                    }
                    String pick = t;
                    overlay.add(new Act(cx, ry, colW, rowH, () -> {
                        if (!pick.equals(type)) {
                            type = pick;
                            minLevel = bestBracket(pick).minLevel();
                            Arrays.fill(slots, null);
                        }
                        open = Drop.NONE;
                    }, null, null));
                }
                ry += rowH;
            }
        }
    }

    private void dropLevels(GuiGraphics g, int mx, int my) {
        List<CraftData.Recipe> rs = CraftData.ofType(type);
        int rowH = 12;
        int shown = Math.min(12, rs.size());
        int w = 96;
        int h = shown * rowH + 6;
        int x = dropX(w);
        int y = dropY(h);
        Kit.card(g, x, y, w, h, 0);
        overlayBox = new int[] {x, y, w, h};
        dropScroll = Math.max(0, Math.min(dropScroll, rs.size() - shown));
        int lv = level(rs.get(0).profession());
        for (int i = 0; i < shown; i++) {
            CraftData.Recipe r = rs.get(dropScroll + i);
            int ry = y + 3 + i * rowH;
            boolean on = r.minLevel() == minLevel;
            boolean hover = in(mx, my, x + 2, ry, w - 4, rowH);
            Kit.row(g, x + 2, ry, w - 4, rowH, i, on || hover);
            boolean locked = r.minLevel() > lv;
            if (locked) {
                Art.icon(g, "lock", x + 6, ry + 2, 8, Theme.paperRgb(Theme.INK_3));
            }
            text(g, r.bracket(), x + 17, ry + 2, locked ? Theme.INK_3 : Theme.INK);
            int pick = r.minLevel();
            overlay.add(new Act(x + 2, ry, w - 4, rowH, () -> {
                minLevel = pick;
                open = Drop.NONE;
            }, null, locked ? List.of(L.c("wynnlabs.craft.locked_recipe",
                    Professions.name(r.profession().toUpperCase(Locale.ROOT)), r.minLevel(), lv)) : null));
        }
        scrollMarks(g, x + w - 3, y, h, dropScroll, rs.size(), shown);
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
        CraftData.Recipe r = recipe();
        int lv = level(r.profession());
        List<Ingredient> lay = layout();
        int[] effNow = Craft.effectiveness(lay);
        int slotEff = effNow[openSlot];
        String q = pickSearch.getValue().strip().toLowerCase(Locale.ROOT);
        CraftSearch.Goal goal = goal();
        record Row(Ingredient ing, double gain, int own) {}
        List<Row> rows = new ArrayList<>();
        for (Ingredient i : ingredients) {
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
            rows.add(new Row(i, gain, owned(i.name())));
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
        overlayBox = new int[] {x, y, w, h};
        // the search field
        Kit.field(g, x + 4, y + 4, w - 8, 12, true);
        pickSearch.setX(x + 7);
        pickSearch.setY(y + 6);
        pickSearch.setWidth(w - 14);
        pickSearch.render(g, mx, my, pt);
        if (pickSearch.getValue().isEmpty()) {
            text(g, fit(L.t("wynnlabs.craft.picker_hint", pct(slotEff)), w - 14), x + 7, y + 6, Theme.INK_3);
        }
        dropScroll = Math.max(0, Math.min(dropScroll, rows.size() - shown));
        int ry = y + 19;
        if (rows.isEmpty()) {
            text(g, L.t("wynnlabs.craft.picker_empty"), x + 6, ry + 2, Theme.INK_3);
        }
        for (int i = 0; i < shown && dropScroll + i < rows.size(); i++) {
            Row row = rows.get(dropScroll + i);
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
            CraftSearch.Price p = prices.get(in.name());
            String price = locked ? "Lv " + in.level() : p == null ? "?" : Prices.fmt(p.emeralds());
            text(g, price, x + 158 - width(price), ry + 2, locked ? Theme.BAD : Theme.PRICE);
            String gain = (row.gain() >= 0 ? "+" : "") + dec(row.gain(), row.gain() == Math.rint(row.gain()) ? 0 : 1);
            text(g, gain, x + w - 6 - width(gain), ry + 2, row.gain() > 0 ? Theme.GOOD
                    : row.gain() < 0 ? Theme.BAD : Theme.INK_3);
            String name = in.name();
            overlay.add(new Act(x + 2, ry, w - 4, rowH, () -> {
                slots[openSlot] = name;
                open = Drop.NONE;
                pickSearch.setFocused(false);
            }, null, card(in)));
            if (hover) {
                List<Ingredient> p2 = new ArrayList<>(lay);
                p2.set(openSlot, in);
                nextPreview = p2;
            }
            ry += rowH;
        }
        scrollMarks(g, x + w - 3, y + 19, shown * rowH, dropScroll, rows.size(), shown);
    }

    /** Identifications that the recipe's profession can get, for the goal and filter lists. */
    private List<String> idsForProfession() {
        CraftData.Recipe r = recipe();
        java.util.TreeMap<String, String> byShown = new java.util.TreeMap<>();
        for (Ingredient i : ingredients) {
            if (i.usableBy(r.profession()) && i.level() <= r.maxLevel()) {
                for (String id : i.ids().keySet()) {
                    byShown.put(statName(id) + "|" + id, id);
                }
            }
        }
        return new ArrayList<>(byShown.values());
    }

    private void dropGoal(GuiGraphics g, int mx, int my) {
        List<String> ids = idsForProfession();
        ids.removeAll(weights.keySet());
        int rowH = 11;
        int shown = Math.min(14, Math.max(1, ids.size()));
        int w = 150;
        int h = shown * rowH + 6;
        int x = dropX(w);
        int y = dropY(h);
        Kit.card(g, x, y, w, h, 0);
        overlayBox = new int[] {x, y, w, h};
        dropScroll = Math.max(0, Math.min(dropScroll, ids.size() - shown));
        for (int i = 0; i < shown && dropScroll + i < ids.size(); i++) {
            String id = ids.get(dropScroll + i);
            int ry = y + 3 + i * rowH;
            boolean hover = in(mx, my, x + 2, ry, w - 4, rowH);
            Kit.row(g, x + 2, ry, w - 4, rowH, i, hover);
            text(g, fit(statName(id), w - 12), x + 6, ry + 2, Theme.INK);
            overlay.add(new Act(x + 2, ry, w - 4, rowH, () -> {
                weights.put(id, 1.0);
                open = Drop.NONE;
            }, null, null));
        }
        scrollMarks(g, x + w - 3, y, h, dropScroll, ids.size(), shown);
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
        overlayBox = new int[] {x, y, w, h};
        int ry = y + 4;
        // the durability floor, the cap to the player's points, then "no negative" per identification
        String floor = L.t("wynnlabs.craft.cond_floor", FLOORS[floorIndex]);
        condRow(g, x, ry, w, floor, null, mx, my, () -> floorIndex = (floorIndex + 1) % FLOORS.length);
        ry += rowH;
        condRow(g, x, ry, w, L.t("wynnlabs.craft.cond_points"), capToPoints, mx, my, () -> capToPoints = !capToPoints);
        ry += rowH;
        text(g, L.t("wynnlabs.craft.cond_no_negative"), x + 6, ry + 2, Theme.INK_3);
        ry += rowH;
        dropScroll = Math.max(0, Math.min(dropScroll, ids.size() - shown));
        for (int i = 0; i < shown; i++) {
            String id = ids.get(dropScroll + i);
            condRow(g, x, ry, w, statName(id), noNegative.contains(id), mx, my, () -> {
                if (!noNegative.remove(id)) {
                    noNegative.add(id);
                }
            });
            ry += rowH;
        }
        scrollMarks(g, x + w - 3, y + 3 * rowH + 4, shown * rowH, dropScroll, ids.size(), shown);
    }

    private void condRow(GuiGraphics g, int x, int y, int w, String text, Boolean on, int mx, int my, Runnable click) {
        boolean hover = in(mx, my, x + 2, y, w - 4, 12);
        Kit.row(g, x + 2, y, w - 4, 12, 0, hover);
        text(g, fit(text, w - 34), x + 6, y + 2, Theme.INK);
        if (on != null) {
            Kit.toggle(g, x + w - 26, y + 1, on);
        }
        overlay.add(new Act(x + 2, y, w - 4, 12, click, null, null));
    }

    // ---- input ------------------------------------------------------------------------------

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
        lastX = mx;
        if (overlayBox != null) {
            Runnable r = target(overlay, mx, my, button);
            if (r != null) {
                r.run();
                return true;
            }
            if (!in(mx, my, overlayBox[0], overlayBox[1], overlayBox[2], overlayBox[3])) {
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
            if (renaming != null && (typed.isEmpty() || typed.equals(renaming))) {
                stopRenaming();                         // clicked away without a new name
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
                setPage = Math.max(0, Math.min(pageCount - 1, setPage - step));
            } else {
                suggestPage = Math.max(0, Math.min(pageCount - 1, suggestPage - step));
            }
            return true;
        }
        if (open != Drop.NONE && overlayBox != null && in(mx, my, overlayBox[0], overlayBox[1], overlayBox[2], overlayBox[3])) {
            dropScroll = Math.max(0, dropScroll - step);
            return true;
        }
        if (cardRect != null && in(mx, my, cardRect[0], cardRect[1], cardRect[2], cardRect[3])) {
            int next = Math.max(0, Math.min(idMax, idScroll - step));
            if (next != idScroll) {
                idScroll = next;                    // the card's own list scrolls first
                return true;
            }
        }
        if (clip != null && in(mx, my, clip[0], clip[1], clip[2], clip[3])) {
            scroll = Math.max(0, Math.min(scrollMax, scroll - 12 * step));
            open = Drop.NONE;
        }
        return true;
    }

    /** Enter in the name box saves; Escape closes an open list first. */
    boolean keyPressed(int key) {
        if ((key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) && saveName.isFocused()) {
            saveCurrent();
            return true;
        }
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && renaming != null) {
            stopRenaming();                             // Esc leaves renaming, not the menu
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

    // ---- helpers ----------------------------------------------------------------------------

    /** A hit area inside the scrolled page: only where it can be seen. */
    private void clipped(int x, int y, int w, int h, Runnable left, Runnable right, List<Component> tip) {
        if (clip == null) {
            acts.add(new Act(x, y, w, h, left, right, tip));
            return;
        }
        int x1 = Math.max(x, clip[0]);
        int y1 = Math.max(y, clip[1]);
        int x2 = Math.min(x + w, clip[0] + clip[2]);
        int y2 = Math.min(y + h, clip[1] + clip[3]);
        if (x2 > x1 && y2 > y1) {
            acts.add(new Act(x1, y1, x2 - x1, y2 - y1, left, right, tip));
        }
    }

    private boolean visible(int x, int y, int w, int h) {
        return clip == null || (y + h > clip[1] && y < clip[1] + clip[3]);
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
            if (in(mx, my, overlayBox[0], overlayBox[1], overlayBox[2], overlayBox[3]) && tip == null) {
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

    /** An ingredient's card as tooltip lines. */
    private List<Component> card(Ingredient in) {
        List<Component> out = new ArrayList<>();
        out.add(Component.literal(in.name() + " ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal("[" + "✫".repeat(Math.max(0, in.tier())) + "]").withStyle(ChatFormatting.GOLD)));
        for (Map.Entry<String, Ingredient.Stat> e : in.ids().entrySet()) {
            Ingredient.Stat s = e.getValue();
            String u = s.unit() == null ? "" : s.unit();
            boolean good = s.max() > 0;
            out.add(Component.literal(signed(s.min()) + u + " … " + signed(s.max()) + u + " " + statName(e.getKey()))
                    .withStyle(good ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
        String[] names = {"left", "right", "above", "under", "touching", "not_touching"};
        for (int p = 0; p < 6; p++) {
            int v = in.positions()[p];
            if (v != 0) {
                out.add(L.c("wynnlabs.craft.pos." + names[p], (v > 0 ? "+" : "−") + Math.abs(v))
                        .withStyle(v > 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
            }
        }
        List<String> extra = new ArrayList<>();
        if (in.durability() != 0) {
            extra.add(L.t("wynnlabs.craft.card_durability", signed(in.durability())));
        }
        if (in.duration() != 0) {
            extra.add(L.t("wynnlabs.craft.card_duration", signed(in.duration())));
        }
        if (in.charges() != 0) {
            extra.add(L.t("wynnlabs.craft.card_charges", signed(in.charges())));
        }
        for (int s = 0; s < 5; s++) {
            if (in.requirements()[s] != 0) {
                extra.add(skill(s) + " " + signed(in.requirements()[s]));
            }
        }
        if (!extra.isEmpty()) {
            out.add(Component.literal(String.join(" · ", extra)).withStyle(ChatFormatting.GRAY));
        }
        List<String> profs = new ArrayList<>();
        for (String p : in.professions()) {
            profs.add(Professions.name(p.toUpperCase(Locale.ROOT)));
        }
        out.add(Component.literal("Lv " + in.level() + " · " + String.join(", ", profs)).withStyle(ChatFormatting.GRAY));
        CraftSearch.Price p = prices.get(in.name());
        out.add(L.c("wynnlabs.craft.card_own", owned(in.name()), p == null ? "?" : "~" + Prices.fmt(p.emeralds()))
                .withStyle(ChatFormatting.GOLD));
        return out;
    }

    /** Each item type's icon in the type list and field. */
    private static String typeIcon(String t) {
        return switch (t) {
            case "helmet" -> "helmet";
            case "chestplate" -> "shirt";
            case "leggings" -> "hanger";
            case "boots" -> "shoe";
            case "ring" -> "circle-dot";
            case "bracelet" -> "circles";
            case "necklace" -> "diamond";
            case "spear" -> "trident";
            case "dagger" -> "sword";
            case "bow" -> "bow";
            case "wand" -> "wand";
            case "relik" -> "crystal-ball";
            case "potion" -> "flask-2";
            case "scroll" -> "script";
            default -> "meat";
        };
    }

    private String typeName(String t) {
        return L.t("wynnlabs.craft.type." + t);
    }

    private static String skill(int s) {
        return L.t("wynnlabs.craft.skill." + CraftChecks.SKILLS.get(s));
    }

    /** An identification's shown name, from Wynntils' data ("Spell Damage"). */
    private String statName(String id) {
        for (Ingredient i : ingredients) {
            Ingredient.Stat s = i.ids().get(id);
            if (s != null && s.shown() != null && !s.shown().isEmpty()) {
                return s.shown();
            }
        }
        return id;
    }

    /** A short name for a chip: the first word, or the first two when the first is a raw/element prefix. */
    private String shortStat(String id) {
        String n = statName(id);
        return n.length() <= 12 ? n : fit(n, 60);
    }

    private String unit(String id) {
        for (Ingredient i : ingredients) {
            Ingredient.Stat s = i.ids().get(id);
            if (s != null) {
                return s.unit() == null ? "" : s.unit();
            }
        }
        return "";
    }

    private static String weightText(double w) {
        return w == 0.5 ? "½" : w == Math.rint(w) ? String.valueOf((int) w) : String.valueOf(w);
    }

    /** A range for reading: "47–51%", "−20 … −16%", or one value when both ends agree. */
    private static String range(int a, int b, String unit) {
        if (a == b) {
            return signed(a) + unit;
        }
        if (a >= 0) {
            return a + "–" + b + unit;
        }
        return signed(a) + " … " + signed(b) + unit;
    }

    private static String range(Craft.Range r, String unit) {
        return range(r.min(), r.max(), unit);
    }

    /**
     * What buying these copies costs at today's prices (they may have come in since the layout
     * was found); the unknown ones are asked for and shown as "+?".
     */
    private String liveCost(List<String> bought) {
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
        return costText(cost, priced, unknown);
    }

    /** "1 EB", "1 EB +?" (some prices unknown) or "?" (none known). */
    /** "Maliyet 1.2k", or "Maliyet: bilinmiyor" when nothing in it has a price: a bare "?" read as a placeholder. */
    private static String costLine(String costText) {
        return costText.equals("?") ? L.t("wynnlabs.craft.cost_unknown") : L.t("wynnlabs.craft.cost", costText);
    }

    private static String costText(long cost, boolean anyPriced, boolean anyUnpriced) {
        if (!anyPriced) {
            return "?";
        }
        return Prices.fmt(cost) + (anyUnpriced ? " +?" : "");
    }

    private static String signed(int v) {
        return v > 0 ? "+" + v : String.valueOf(v).replace('-', '−');
    }

    private static String stars(int tier) {
        return "★".repeat(Math.max(1, tier));
    }

    private static boolean tr() {
        return L.code().startsWith("tr");
    }

    private static String dec(double v, int places) {
        String s = String.format(Locale.ROOT, "%." + places + "f", v);
        return tr() ? s.replace('.', ',') : s;
    }

    private static String thousands(int v) {
        return String.format(tr() ? Locale.forLanguageTag("tr") : Locale.ENGLISH, "%,d", v);
    }

    private static String pct(int v) {
        return L.t("wynnlabs.screen.pct", v);
    }

    private static String pctText(double p) {
        return L.t("wynnlabs.screen.pct", dec(p * 100, p < 0.1 ? 1 : 0));
    }

    private String fit(String s, int w) {
        if (w <= 0) {
            return "";
        }
        if (width(s) <= w) {
            return s;
        }
        int lo = 0;
        int hi = s.length();
        while (lo < hi) {                   // the longest start that fits with the ellipsis
            int mid = (lo + hi + 1) / 2;
            if (width(s.substring(0, mid) + "…") <= w) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return s.substring(0, lo) + "…";
    }

    // ---- text: the mock-up's sizes (body 7.8 GUI px, fine print 7) when the web font is on ----

    private static final float BODY = 0.87f;
    private static final float FINE = 0.75f;

    private static net.minecraft.network.chat.MutableComponent sized(Component c, float f) {
        return Settings.on("webFont") ? c.copy().withStyle(st -> UiFont.sized(st, f)) : c.copy();
    }

    /** A section title in the tab's size: bold ink, a brass dot and a rule to the right edge. */
    private void section(GuiGraphics g, String title, int x, int y, int w) {
        Component t = Component.literal(title).withStyle(UiFont::bold);
        text(g, t, x, y, Theme.INK_2);
        int dx = x + width(t) + 4;
        g.fill(dx, y + 3, dx + 3, y + 6, Theme.BRASS);
        if (dx + 7 < x + w) {
            g.fill(dx + 7, y + 4, x + w, y + 5, Theme.LINE);
        }
    }

    private void text(GuiGraphics g, String s, int x, int y, int colour) {
        Kit.text(g, font, sized(Component.literal(s), BODY), x, y, colour);
    }

    private void text(GuiGraphics g, Component c, int x, int y, int colour) {
        Kit.text(g, font, sized(c, BODY), x, y, colour);
    }

    /** Fine print: hints, units, counts. */
    private void fine(GuiGraphics g, String s, int x, int y, int colour) {
        Kit.text(g, font, sized(Component.literal(s), FINE), x, y, colour);
    }

    private String fitFine(String s, int w) {
        if (w <= 0) {
            return "";
        }
        if (fineWidth(s) <= w) {
            return s;
        }
        int lo = 0;
        int hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (fineWidth(s.substring(0, mid) + "…") <= w) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return s.substring(0, lo) + "…";
    }

    private int fineWidth(String s) {
        return font.width(sized(Component.literal(s), FINE));
    }

    private int width(String s) {
        return font.width(sized(Component.literal(s), BODY));
    }

    private int width(Component c) {
        return font.width(sized(c, BODY));
    }

    /** Text on wood (buttons, segments): cream with the game's shadow, ink on gold. */
    private void wood(GuiGraphics g, String s, int x, int y, int colour, boolean shadow) {
        g.drawString(font, sized(Component.literal(s), BODY), x, y, colour, shadow);
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
