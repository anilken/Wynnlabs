package io.github.anilken.wynnlabs;

import com.google.gson.JsonObject;

import io.github.anilken.wynnlabs.model.AbilityNode;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Slot;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The J menu. A header with the character and the build's strength, then three tabs:
 * <ul>
 * <li>Road map: the next three things to do (best upgrade, best quest, where to level), your
 * gear slots with what you wear and the upgrade waiting by the chosen level, and a level list
 * on the right with that milestone's abilities, skill points and places.</li>
 * <li>Quests: quests from the content book ranked by {@link QuestAdvisor}; click copies the
 * Wynntils compass command to the quest's next spot.</li>
 * <li>Damage: the model's damage against the damage meter, the live DPS, where the DPS comes
 * from and what each stat would add.</li>
 * <li>Secret discoveries: every one in the order picked, done or not, with lore, requirements
 * and the steps to reach it; pins for each place.</li>
 * <li>Settings: the switches.</li>
 * </ul>
 * Shows only; never buys, starts or clicks anything in the game.
 */
final class RoadmapScreen extends Screen {

    private record Box(String label, Slot type, int ring) {}

    private record Hit(int x, int y, int w, int h, ItemStack stack, List<Component> tip, String copy,
            QuestBook.Entry quest, List<Component> lead) {

        Hit(int x, int y, int w, int h, ItemStack stack, List<Component> tip, String copy, QuestBook.Entry quest) {
            this(x, y, w, h, stack, tip, copy, quest, null);
        }

        Hit(int x, int y, int w, int h, ItemStack stack, List<Component> tip, String copy) {
            this(x, y, w, h, stack, tip, copy, null, null);
        }
    }

    /** Box labels are language keys; show with {@code L.t(box.label())}. */
    private static final Box[] LEFT = {new Box("wynnlabs.screen.slot_helmet", Slot.HELMET, 0),
        new Box("wynnlabs.screen.slot_chestplate", Slot.CHESTPLATE, 0),
        new Box("wynnlabs.screen.slot_leggings", Slot.LEGGINGS, 0), new Box("wynnlabs.screen.slot_boots", Slot.BOOTS, 0),
        new Box("wynnlabs.screen.slot_weapon", null, 0)};
    private static final Box[] RIGHT = {new Box("wynnlabs.screen.slot_ring", Slot.RING, 0),
        new Box("wynnlabs.screen.slot_ring", Slot.RING, 1),
        new Box("wynnlabs.screen.slot_bracelet", Slot.BRACELET, 0), new Box("wynnlabs.screen.slot_necklace", Slot.NECKLACE, 0)};

    private static final int BOX_H = 26;
    private static final int GAP = 4;
    private static final int SIDE_W = 128;
    private static final int STEPS_H = 46;
    private static final int CARD_H = 74;
    private static final int[] FACTOR_COLOURS = {0xFF1D9E75, 0xFF378ADD, 0xFF7F77DD, 0xFFEF9F27, 0xFF97C459, 0xFFE24B4A};

    // palette: the J menu theme (Theme), under the names the screen has always used
    private static final int CARD = Theme.CARD;
    private static final int CARD_HOVER = Theme.CARD_HOVER;
    private static final int LINE = Theme.LINE;
    private static final int ACCENT = Theme.GOLD_DEEP;
    private static final int ACCENT_DARK = Theme.WOOD;
    private static final int GOOD = Theme.GOOD;
    private static final int MUTED = Theme.INK_2;
    private static final int FAINT = Theme.INK_3;
    private static final int GOLD = Theme.PRICE;
    // tags on paper: a light wash of the colour with dark text in it
    private static final int TAG_RED = 0xFFF2C9BE;
    private static final int TAG_AMBER = 0xFFF0D090;
    private static final int TAG_PURPLE = 0xFFDCC8EC;
    private static final int TAG_GREEN = 0xFFCFE0B0;

    /** Tab names as language keys; show with {@code L.t(TABS[i])}. */
    private static final String[] TABS = {"wynnlabs.screen.tab_home", "wynnlabs.screen.tab_today", "wynnlabs.screen.tab_quests",
        "wynnlabs.screen.tab_deaths", "wynnlabs.screen.tab_bank", "wynnlabs.screen.tab_settings", "wynnlabs.screen.tab_ai",
        "wynnlabs.screen.tab_damage", "wynnlabs.screen.tab_discoveries", "wynnlabs.screen.tab_tree", "wynnlabs.screen.tab_craft",
        "wynnlabs.screen.tab_endgame"};
    static final int TAB_BANK = 4;
    private static final int TAB_SETTINGS = 5;
    private static final int TAB_AI = 6;
    private static final int TAB_DAMAGE = 7;
    static final int TAB_DISCOVERIES = 8;
    private static final int TAB_TREE = 9;
    static final int TAB_CRAFT = 10;
    static final int TAB_ENDGAME = 11;
    private static final String[] BANK_FILTERS = {"wynnlabs.bank.filter_all", "wynnlabs.bank.filter_sell", "wynnlabs.bank.filter_quest",
        "wynnlabs.bank.filter_prof", "wynnlabs.bank.filter_gear"};

    private List<Roadmap.Step> steps = List.of();
    private List<QuestAdvisor.Suggestion> quests = List.of();
    private long questsAt;
    private int selected;
    /** First milestone shown after "now" in the level buttons, and how many fit. */
    private int levelOff = 0;            // the first shown milestone tab, as a position in shownSteps()
    private static final int LEVEL_WINDOW = 5;
    private int tab;
    private final Map<String, ItemStack> icons = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();

    /** The preview: whether a hover area with a tooltip lies under (x, y) this frame (logical pixels). */
    boolean tooltipAt(int x, int y) {
        for (Hit h : hits) {
            boolean tip = h.stack() != null || h.tip() != null && !h.tip().isEmpty();
            if (tip && x >= h.x() && x < h.x() + h.w() && y >= h.y() && y < h.y() + h.h()) {
                return true;
            }
        }
        return false;
    }
    private final List<int[]> levelRows = new ArrayList<>();   // x, y, w, h, step index
    private final List<int[]> tabs = new ArrayList<>();        // x, y, w, h, tab index
    private final List<Object[]> toggles = new ArrayList<>();  // {int[] rect, String key}
    private final List<Object[]> slotHits = new ArrayList<>();  // {int[] rect, Roadmap.Gear, label, worn}
    private final List<Object[]> cardHits = new ArrayList<>();  // {int[] rect, ItemData}
    private int[] backRect;
    private Roadmap.Gear detail;
    private String detailLabel;
    private ItemStack detailWorn = ItemStack.EMPTY;
    private String flash = "";
    private long flashUntil;
    private String error;

    RoadmapScreen() {
        super(L.c("wynnlabs.screen.title"));
        // a quest being tracked: the menu opens on the quests tab with that quest chosen
        try {
            if (Wyn.tracking() && Wyn.trackedName() != null) {
                tab = 2;
                questSelected = Wyn.trackedName();
            }
        } catch (Throwable ignored) {
            // no Wynntils: the home tab
        }
    }

    @Override
    protected void init() {
        UiFont.apply(this);                     // Inter instead of the pixel font (setting: webFont)
        WynnTex.clear();                        // the pack may have changed since last time
        String typed = search == null ? "" : search.getValue();
        search = new net.minecraft.client.gui.components.EditBox(font, 0, 0, 150, 12, L.c("wynnlabs.bank.search"));
        search.setMaxLength(40);
        search.setValue(typed);
        search.setResponder(v -> bankScroll = 0);
        paperField(search);
        addWidget(search);
        String treeTyped = treeSearch == null ? "" : treeSearch.getValue();
        treeSearch = new net.minecraft.client.gui.components.EditBox(font, 0, 0, 100, 12, L.c("wynnlabs.tree.search_hint"));
        treeSearch.setMaxLength(40);
        treeSearch.setValue(treeTyped);
        treeSearch.setResponder(v -> treeJump = true);
        paperField(treeSearch);
        addWidget(treeSearch);
        // the Wynnventory key, pasted here instead of a file; shown as dots while typed
        keyBox = new net.minecraft.client.gui.components.EditBox(font, 0, 0, 150, 12, Component.literal("Wynnventory API key"));
        keyBox.setMaxLength(128);
        keyBox.addFormatter((text, pos) -> net.minecraft.util.FormattedCharSequence.forward("•".repeat(text.length()),
                net.minecraft.network.chat.Style.EMPTY));
        paperField(keyBox);
        addWidget(keyBox);
        ruleBox = new net.minecraft.client.gui.components.EditBox(font, 0, 0, 60, 12, L.c("wynnlabs.rules.type_hint"));
        ruleBox.setMaxLength(12);
        paperField(ruleBox);
        addWidget(ruleBox);
        ruleBoxFor = -1;
        for (net.minecraft.client.gui.components.EditBox b : CraftingTab.INSTANCE.init(this, font, text -> {
            flash = text;
            flashUntil = System.currentTimeMillis() + 2500;
        })) {
            addWidget(b);
        }
        for (net.minecraft.client.gui.components.EditBox b : BuildWindow.INSTANCE.init(font)) {
            addWidget(b);
        }
        BuildWindow.INSTANCE.host(windowHost);
        try {
            steps = Roadmap.cached();
            stepsVersion = Roadmap.version();
        } catch (Throwable t) {
            error = L.t("wynnlabs.screen.error_build", t.getClass().getSimpleName());
            System.out.println("[WynnLabs] roadmap failed:");
            t.printStackTrace(System.out);
        }
        refreshQuests();
    }

    private int stepsVersion;
    /** A button here asked for a fresh map: its "updating" bar shows until the map arrives. */
    private boolean refreshAsked;

    private void refreshQuests() {
        try {
            quests = QuestAdvisor.suggest(7);
        } catch (Throwable t) {
            quests = List.of();
        }
        questsAt = System.currentTimeMillis();
    }

    private ItemStack icon(ItemData info) {
        return icons.computeIfAbsent(info.name(), n -> {
            if (GameState.current().running()) {
                try {
                    ItemStack st = WynntilsItems.icon(info);
                    iconInfo.put(st, info);
                    return st;
                } catch (LinkageError e) {
                    // Wynntils changed: a plain item below
                }
            }
            return plainIcon(info.slot());     // the recorded game of the preview has no Wynntils
        });
    }

    /** The goal list under the goal button, open after a click on it. */
    private boolean goalOpen;
    private int[] goalAnchor;
    private final List<int[]> goalRows = new ArrayList<>();     // x, y, w, h, index in Setup.GOALS

    /** One row per goal: its name and what it weighs, the current one marked, the hovered one lit. */
    private void drawGoalList(GuiGraphics g, int mx, int my) {
        goalRows.clear();
        int rowH = 20;
        int w = 0;
        for (String goal : Setup.GOALS) {
            w = Math.max(w, Math.max(font.width(Setup.goalName(goal)) + 10, font.width(L.t(goalDesc(goal)))));
        }
        w += 16;
        // its right edge on the button's, and never past the panel's left side
        int x = Math.max((width - panelW()) / 2 + railW() + 6, goalAnchor[0] + goalAnchor[2] - w);
        int y = goalAnchor[1] + goalAnchor[3] + 2;
        int h = Setup.GOALS.size() * rowH + 6;
        g.nextStratum();                            // over the menu's own text and items
        Kit.card(g, x, y, w, h, 0);
        String now = Setup.goal();
        for (int i = 0; i < Setup.GOALS.size(); i++) {
            String goal = Setup.GOALS.get(i);
            int ry = y + 3 + i * rowH;
            boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + rowH;
            boolean on = goal.equals(now);
            Kit.row(g, x + 2, ry, w - 4, rowH - 1, i, hover || on);
            Component name = Component.literal(Setup.goalName(goal)).withStyle(UiFont::bold);
            if (on) {
                Art.icon(g, "check", x + 7, ry + 2, 8, Theme.paperRgb(Theme.INK));
            }
            ink(g, name, x + 7 + (on ? 10 : 0), ry + 2, on ? Theme.INK : Theme.INK_2);
            ink(g, L.t(goalDesc(goal)), x + 7, ry + 11, MUTED);
            goalRows.add(new int[] {x + 2, ry, w - 4, rowH, i});
        }
    }

    private static String goalDesc(String goal) {
        return switch (goal) {
            case "BALANCED" -> "wynnlabs.setup.goal_balanced_desc";
            case "SAFE" -> "wynnlabs.setup.goal_safe_desc";
            case "TANK" -> "wynnlabs.setup.goal_tank_desc";
            case "HEALER" -> "wynnlabs.setup.goal_healer_desc";
            case "BOSS" -> "wynnlabs.setup.goal_boss_desc";
            case "MOBBING" -> "wynnlabs.setup.goal_mobbing_desc";
            case "XP" -> "wynnlabs.setup.goal_xp_desc";
            case "LOOT" -> "wynnlabs.setup.goal_loot_desc";
            default -> "wynnlabs.setup.goal_damage_desc";
        };
    }

    /** The piece behind each icon stack (by identity), for its tooltip's requirement marks. */
    private final Map<ItemStack, ItemData> iconInfo = new java.util.IdentityHashMap<>();

    /**
     * Wynntils' tooltip of a piece you do not own marks every requirement unmet: its guide stack
     * has no instance, so nothing says what you meet (an owned one gets that from the server).
     * The marks are set again here from your level, class and skill points (assigned plus
     * gear); a quest stays as it was. Null when the stack is not one of these.
     */
    private List<Component> requirementTooltip(ItemStack stack) {
        ItemData info = iconInfo.get(stack);
        if (info == null) {
            return null;
        }
        try {
            List<Boolean> flags = new ArrayList<>();
            for (io.github.anilken.wynnlabs.model.SkillKind k : io.github.anilken.wynnlabs.model.SkillKind.values()) {
                int need = info.requirements().skills().stream().filter(r -> r.skill() == k).mapToInt(r -> r.points()).sum();
                if (need > 0) {
                    flags.add(Build.totalPoints(k) >= need);
                }
            }
            if (info.requirements().quest().isPresent()) {
                flags.add(null);                    // not known here: left as Wynntils drew it
            }
            if (info.requirements().klass().isPresent()) {
                flags.add(info.requirements().klass().get() == GameState.current().classType());
            }
            if (info.requirements().level() > 0) {
                flags.add(GameState.current().level() >= info.requirements().level());
            }
            ReqMarks st = new ReqMarks(flags);
            List<Component> out = new ArrayList<>();
            for (Component line : Screen.getTooltipFromItem(Minecraft.getInstance(), stack)) {
                out.add(st.fix(line));
            }
            return out;
        } catch (Throwable t) {
            return null;                            // Wynntils' tooltip as it is
        }
    }

    /** Walks tooltip lines in order and sets each requirement mark (✔ / ✘ sprite, value colour). */
    private static final class ReqMarks {
        private final List<Boolean> flags;
        private int next;
        private Integer colour;                     // the skill value after a mark takes this colour

        ReqMarks(List<Boolean> flags) {
            this.flags = flags;
        }

        net.minecraft.network.chat.MutableComponent fix(Component c) {
            net.minecraft.network.chat.MutableComponent out;
            net.minecraft.network.chat.Style style = c.getStyle();
            if (c.getContents() instanceof net.minecraft.network.chat.contents.PlainTextContents.LiteralContents lit) {
                String text = lit.text();
                boolean marked = false;
                if (String.valueOf(style.getFont()).contains("requirement/sprite")) {
                    // every \u2714 / \u2718 sprite in the literal, in order: a centred line can put spacing
                    // before the mark, so it need not be the first character
                    StringBuilder sb = new StringBuilder(text);
                    for (int i = 0; i < sb.length(); i++) {
                        char ch = sb.charAt(i);
                        if ((ch == '\uE006' || ch == '\uE007') && next < flags.size()) {
                            Boolean met = flags.get(next++);
                            marked = true;
                            if (met != null) {
                                sb.setCharAt(i, met ? '\uE006' : '\uE007');
                                colour = met ? 0xACFAC6 : 0xFAACAC;
                            } else {
                                colour = null;
                            }
                        }
                    }
                    text = sb.toString();
                }
                if (!marked && colour != null && style.getColor() != null
                        && (style.getColor().getValue() == 0xFAACAC || style.getColor().getValue() == 0xACFAC6)) {
                    style = style.withColor(colour);
                    colour = null;
                }
                out = Component.literal(text).setStyle(style);
            } else {
                out = c.plainCopy().setStyle(style);
            }
            for (Component sib : c.getSiblings()) {
                out.append(fix(sib));
            }
            return out;
        }
    }

    /** A vanilla item for a slot type, when Wynntils' own stack is not there. */
    private static ItemStack plainIcon(Slot slot) {
        if (slot == null) {
            return new ItemStack(net.minecraft.world.item.Items.PAPER);
        }
        return new ItemStack(switch (slot) {
            case HELMET -> net.minecraft.world.item.Items.DIAMOND_HELMET;
            case CHESTPLATE -> net.minecraft.world.item.Items.DIAMOND_CHESTPLATE;
            case LEGGINGS -> net.minecraft.world.item.Items.DIAMOND_LEGGINGS;
            case BOOTS -> net.minecraft.world.item.Items.DIAMOND_BOOTS;
            case RING -> net.minecraft.world.item.Items.GOLD_NUGGET;
            case BRACELET -> net.minecraft.world.item.Items.IRON_NUGGET;
            case NECKLACE -> net.minecraft.world.item.Items.STRING;
            default -> net.minecraft.world.item.Items.STICK;
        });
    }

    /** Upgrades reached by the selected milestone, by slot type name ("RING", "RELIK", ...). */
    private Map<String, Roadmap.Gear> upgrades() {
        Map<String, Roadmap.Gear> out = new LinkedHashMap<>();
        for (int i = 0; i <= selected && i < steps.size(); i++) {
            for (Roadmap.Gear g : steps.get(i).gear()) {
                out.put(g.slot(), g);
            }
        }
        if (!steps.isEmpty()) {
            Roadmap.Step at = steps.get(Math.min(selected, steps.size() - 1));
            Roadmap.keepWorn(out, at);
        }
        // what the best build you own puts on from the bag, where the road map has no pick: the
        // plan follows a piece taken off within seconds, the map only when built again
        Roadmap.bagRows().forEach(out::putIfAbsent);
        return out;
    }

    // ---- layout ----

    /** This frame's size class, panel and rail (MenuLayout): set at the start of each render. */
    private MenuLayout layout = MenuLayout.of(640, 360);
    /** The rail's order (the tab numbers stay what the rest of the screen uses). */
    private static final int[] RAIL = {0, TAB_CRAFT, TAB_TREE, 1, TAB_ENDGAME, 2, TAB_DISCOVERIES, TAB_BANK, TAB_DAMAGE, 3, TAB_AI,
        TAB_SETTINGS};
    /**
     * Each rail entry's hint as a language key ("" for none). The Endgame tab's title line holds
     * its pills, so its hint is drawn under its aspect list instead.
     */
    private static final String[] RAIL_HINT = {
        "", "", "wynnlabs.screen.hint_tree", "wynnlabs.screen.hint_today", "wynnlabs.screen.hint_endgame",
        "wynnlabs.screen.hint_quests", "wynnlabs.screen.hint_discoveries", "wynnlabs.screen.hint_bank", "wynnlabs.screen.hint_damage", "wynnlabs.screen.hint_deaths", "wynnlabs.screen.hint_ai",
        "wynnlabs.screen.hint_settings"};
    private final Map<Integer, ItemStack> railIcons = new HashMap<>();

    /** For the preview's layout check: the click areas drawn this frame (rows and buttons with an action). */
    List<LayoutCheck.Area> clickAreas() {
        List<LayoutCheck.Area> out = new ArrayList<>();
        for (Hit h : hits) {
            if (h.copy() != null || h.quest() != null) {
                out.add(new LayoutCheck.Area(h.x(), h.y(), h.w(), h.h(), h.copy() != null ? h.copy() : h.quest().name()));
            }
        }
        if (tab == TAB_CRAFT) {
            out.addAll(CraftingTab.INSTANCE.clickAreas());
        }
        if (tab == TAB_ENDGAME) {
            out.addAll(EndgameTab.INSTANCE.clickAreas());
        }
        if (tab == 0 && BuildWindow.INSTANCE.isOpen()) {
            return BuildWindow.INSTANCE.clickAreas();       // the window covers the rest
        }
        return out;
    }

    private int panelW() {
        return MenuLayout.of(width, height).w();
    }

    private int panelH() {
        return MenuLayout.of(width, height).h();
    }

    private int railW() {
        return layout.railW();
    }

    private ItemStack railIcon(int tab) {
        return railIcons.computeIfAbsent(tab, t -> new ItemStack(switch (t) {
            case 0 -> net.minecraft.world.item.Items.COMPASS;
            case 1 -> net.minecraft.world.item.Items.CLOCK;
            case 2 -> net.minecraft.world.item.Items.WRITABLE_BOOK;
            case 3 -> net.minecraft.world.item.Items.SKELETON_SKULL;
            case TAB_BANK -> net.minecraft.world.item.Items.CHEST;
            default -> net.minecraft.world.item.Items.COMPARATOR;
        }));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        if (DevHooks.ON && DevHooks.driving()) {
            // the dev screenshot tool: its own mouse (off the window, or over the element whose
            // tooltip it takes), and the frame's boxes noted afresh
            DevHooks.beginFrame();
            mx = DevHooks.mouseX();
            my = DevHooks.mouseY();
        }
        // at the menu scale (MenuScale): a logical size, a scaled pose, logical mouse positions
        MenuScale.render(this, g, mx, my, (x, y) -> draw(g, x, y, pt));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        if (DevHooks.ON && DevHooks.driving()) {
            // the dev screenshot tool: a flat backdrop instead of the blurred world (black, then white)
            var w = Minecraft.getInstance().getWindow();
            g.fill(0, 0, w.getGuiScaledWidth() + 1, w.getGuiScaledHeight() + 1, DevHooks.backdrop());
            return;
        }
        super.renderBackground(g, mx, my, pt);
    }

    /** The mouse this frame (logical pixels), for the drawing helpers that are not handed it. */
    private int frameMx;
    private int frameMy;

    private void draw(GuiGraphics g, int mx, int my, float pt) {
        frameMx = mx;
        frameMy = my;
        super.render(g, mx, my, pt);
        hits.clear();
        levelRows.clear();
        slotHits.clear();
        cardHits.clear();
        backRect = null;
        tabs.clear();
        toggles.clear();
        bankFilterHits.clear();
        if (System.currentTimeMillis() - questsAt > 3000) {
            refreshQuests();
        }
        // a fresher road map was built in the background while the menu is open
        if (stepsVersion != Roadmap.version() && error == null) {
            try {
                List<Roadmap.Step> fresh = Roadmap.cached();
                if (!fresh.isEmpty()) {
                    steps = fresh;
                    selected = Math.min(selected, steps.size() - 1);
                }
            } catch (Throwable ignored) {
            }
            stepsVersion = Roadmap.version();
            refreshAsked = refreshAsked && Roadmap.isBuilding();
        }
        layout = MenuLayout.of(width, height);
        int w = layout.w();
        int h = layout.h();
        int x0 = layout.x();
        int y0 = layout.y();
        // the window: a wooden frame around the panel (outside it, so nothing inside moves),
        // planks under the rail and parchment under the page
        Kit.window(g, x0 - 5, y0 - 5, w + 10, h + 10);
        Kit.planks(g, x0, y0, railW(), h);
        // the page: content keeps 10px from the parchment's sides (14 at the top, 8 at the bottom)
        Kit.paper(g, x0 + railW() + 2, y0 + 2, w - railW() - 4, h - 4);

        drawRail(g, x0, y0, h, mx, my);
        int cx = x0 + railW() + 12;
        int cw = w - railW() - 24;
        int cy = y0 + 16;
        int ch = h - 26;
        if (tab != 0) {
            // a page title and what you can do on it, instead of a crowded tab bar
            int idx = 0;
            for (int i = 0; i < RAIL.length; i++) {
                if (RAIL[i] == tab) {
                    idx = i;
                }
            }
            Kit.text(g, font, L.c(TABS[tab]).withStyle(UiFont::bold), cx, cy, Theme.INK);
            int titleRoom = cw - font.width(L.c(TABS[tab]).withStyle(UiFont::bold)) - 12;
            // the quests tab: when the list was read and its "Yenile" chip on the right
            int right = tab == 2 ? questTitleRight(g, cx + cw, cy, titleRoom, mx, my) : 0;
            int gap = right > 0 ? right + 10 : 0;
            // the page hint where it fits beside the title (none in the compact layout)
            String hint = RAIL_HINT[idx].isEmpty() || tab == 1 || tab == TAB_ENDGAME || layout.compact() ? "" : L.t(RAIL_HINT[idx]);
            hint = right > 0 && titleRoom - gap < 60 ? "" : fit(hint, titleRoom - gap);
            Kit.text(g, font, hint, cx + cw - gap - font.width(hint), cy, Theme.INK_3);
            g.fill(cx, cy + 12, cx + cw, cy + 13, Theme.LINE);
            cy += 19;
            ch -= 19;
        }
        switch (tab) {
            case 1 -> drawToday(g, cx, cy, cw, ch, mx, my);
            case 2 -> drawQuests(g, cx, cy, cw, ch, mx, my);
            case 3 -> drawDeaths(g, cx, cy, cw, ch, mx, my);
            case TAB_BANK -> drawBank(g, cx, cy, cw, ch, mx, my, pt);
            case TAB_SETTINGS -> drawSettings(g, cx, cy, cw, ch, mx, my);
            case TAB_AI -> drawAiTab(g, cx, cy, cw, ch, mx, my);
            case TAB_DAMAGE -> drawDamage(g, cx, cy, cw, ch, mx, my);
            case TAB_DISCOVERIES -> drawDiscoveries(g, cx, cy, cw, ch, mx, my);
            case TAB_TREE -> drawTree(g, cx, cy, cw, ch, mx, my, pt);
            case TAB_CRAFT -> CraftingTab.INSTANCE.draw(g, cx, cy, cw, ch, cy - 19, new int[] {x0, y0, w, h}, width, mx, my, pt);
            case TAB_ENDGAME -> EndgameTab.INSTANCE.draw(g, cx, cy, cw, ch, cy - 19, new int[] {x0, y0, w, h}, width, mx, my, pt);
            default -> {
                if (detail != null) {
                    drawDetail(g, cx, cy, cw, mx, my);
                } else {
                    drawHome(g, cx, cy, cw, ch, mx, my);
                }
            }
        }
        if (goalOpen && goalAnchor != null && tab == 0) {
            drawGoalList(g, mx, my);
        }
        // the build window over the home: what is under it neither shows a tooltip nor takes a click
        if (tab == 0 && BuildWindow.INSTANCE.isOpen()) {
            hits.clear();
            slotHits.clear();
            levelRows.clear();
            BuildWindow.INSTANCE.draw(g, new int[] {x0, y0, w, h}, layout.large(), layout.compact(), windowHost, mx, my, pt);
            net.minecraft.client.gui.components.EditBox f = BuildWindow.INSTANCE.focused();
            if (f != null && getFocused() != f) {
                setFocused(f);
            }
        } else if (BuildWindow.INSTANCE.isOpen()) {
            BuildWindow.INSTANCE.close();
        }
        BuildWindow.INSTANCE.poll(windowHost);
        // close (drawn last, so the crafting tab's saved panel beside it never covers it): a red seal just outside the window's top-right corner (Esc does the same)
        int[] cr = closeRect;
        boolean closeHover = cr != null && mx >= cr[0] && mx < cr[0] + cr[2] && my >= cr[1] && my < cr[1] + cr[3];
        closeRect = Kit.closeOutside(g, width, x0, y0, w, closeHover);
        int bx = closeRect[0];
        int by = closeRect[1];
        if (closeHover) {
            hits.add(new Hit(bx, by, closeRect[2], closeRect[3], null, List.of(L.c("wynnlabs.screen.close")), null));
        }

        if (System.currentTimeMillis() < flashUntil) {
            Component f = Component.literal("✔ " + flash).withStyle(ChatFormatting.GREEN);
            int fw = font.width(f) + 12;
            int fx = cx + (cw - fw) / 2;
            int fy = y0 + h - 18;
            Kit.card(g, fx, fy, fw, 14, 0);
            Kit.text(g, font, f, fx + 6, fy + 3, Theme.GOOD);
        }
        for (Hit hit : hits) {
            if (mx >= hit.x() && mx < hit.x() + hit.w() && my >= hit.y() && my < hit.y() + hit.h()) {
                List<Component> marked = hit.stack() == null ? null : requirementTooltip(hit.stack());
                boolean lead = hit.lead() != null && !hit.lead().isEmpty();
                if (hit.stack() != null && (lead || hit.tip() != null && !hit.tip().isEmpty())) {
                    // an item's own tooltip with lines under it (the build window: where it comes from),
                    // and over it the lines that explain a pill ("Missing · dropped by mobs")
                    List<Component> lines = new ArrayList<>();
                    if (lead) {
                        lines.addAll(hit.lead());
                        lines.add(Component.empty());
                    }
                    lines.addAll(marked != null ? marked : Screen.getTooltipFromItem(Minecraft.getInstance(), hit.stack()));
                    if (hit.tip() != null && !hit.tip().isEmpty()) {
                        lines.add(Component.empty());
                        lines.addAll(hit.tip());
                    }
                    g.setTooltipForNextFrame(font, lines, hit.stack().getTooltipImage(), mx, my,
                            hit.stack().get(net.minecraft.core.component.DataComponents.TOOLTIP_STYLE));
                } else if (marked != null) {
                    g.setTooltipForNextFrame(font, marked, hit.stack().getTooltipImage(), mx, my,
                            hit.stack().get(net.minecraft.core.component.DataComponents.TOOLTIP_STYLE));
                } else if (hit.stack() != null) {
                    g.setTooltipForNextFrame(font, hit.stack(), mx, my);
                } else if (hit.tip() != null && !hit.tip().isEmpty()) {
                    // long lines wrap instead of running across the screen
                    int maxW = Math.max(160, Math.min(260, width / 2));
                    List<FormattedCharSequence> lines = new ArrayList<>();
                    for (Component c : hit.tip()) {
                        List<FormattedCharSequence> part = font.split(c, maxW);
                        lines.addAll(part.isEmpty() ? List.of(FormattedCharSequence.EMPTY) : part);
                    }
                    g.setTooltipForNextFrame(font, lines, mx, my);
                }
                break;
            }
        }
    }

    // ---- drawing kit ----

    /**
     * A parchment card with its accent as a strip along the top; on hover a gold outline. Cards
     * 13px or lower are buttons drawn as cards: those become wooden buttons.
     */
    private void card(GuiGraphics g, int x, int y, int w, int h, boolean hover, int accent) {
        if (h <= 13) {
            Kit.button(g, x, y, w, h, hover ? Kit.HOVER : Kit.REST);
            return;
        }
        Kit.card(g, x, y, w, h, accent, hover);
    }

    /** An 8 px icon and its text on wood, in the button state's colour. */
    private void woodIconText(GuiGraphics g, String icon, String text, int x, int y, int state) {
        Art.icon(g, icon, x, y, 8, Kit.buttonText(state));
        onWood(g, text, x + 10, y, state);
    }

    /** An 8 px icon and its text in ink on paper. */
    private void inkIconText(GuiGraphics g, String icon, String text, int x, int y, int colour) {
        Art.icon(g, icon, x, y, 8, Theme.paperRgb(colour));
        ink(g, text, x + 10, y, colour);
    }

    /** A small section title: a brass diamond, the title, a rule running to the right edge. */
    private void section(GuiGraphics g, String title, int x, int y, int w) {
        Kit.section(g, font, title, x, y, w);
    }

    // ---- text on paper: dark ink, no shadow, chat colours in their paper tone ----

    private void ink(GuiGraphics g, String s, int x, int y, int colour) {
        Kit.text(g, font, s, x, y, Theme.paperRgb(colour));
    }

    private void ink(GuiGraphics g, String s, int x, int y, int colour, boolean shadow) {
        ink(g, s, x, y, colour);
    }

    private void ink(GuiGraphics g, Component c, int x, int y, int colour) {
        Kit.text(g, font, c, x, y, Theme.paperRgb(colour));
    }

    private void ink(GuiGraphics g, Component c, int x, int y, int colour, boolean shadow) {
        ink(g, c, x, y, colour);
    }

    private void ink(GuiGraphics g, FormattedCharSequence s, int x, int y, int colour) {
        g.drawString(font, Kit.ink(s), x, y, Theme.paperRgb(colour), false);
    }

    private void ink(GuiGraphics g, FormattedCharSequence s, int x, int y, int colour, boolean shadow) {
        ink(g, s, x, y, colour);
    }

    /** Text on a wooden button or chip: cream with the game's shadow, ink on gold. */
    private void onWood(GuiGraphics g, String s, int x, int y, int state) {
        g.drawString(font, s, x, y, Kit.buttonText(state), state != Kit.ON);
    }

    private void scaledText(GuiGraphics g, Component c, int x, int y, float s, int colour) {
        if (s != 1f && Settings.on("webFont")) {
            // a font of that size instead of scaling the glyphs, which would make them blocky
            ink(g, c.copy().withStyle(st -> UiFont.sized(st, s)), x, y, colour);
            return;
        }
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(s, s);
        ink(g, c, 0, 0, colour);
        g.pose().popMatrix();
    }

    private void bigItem(GuiGraphics g, ItemStack stack, int x, int y) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(2f, 2f);
        g.renderItem(stack, 0, 0);
        g.pose().popMatrix();
    }

    private String fit(String s, int w) {
        if (font.width(s) <= w) {
            return s;
        }
        return font.plainSubstrByWidth(s, Math.max(0, w - font.width("…"))) + "…";
    }

    /**
     * A tab's badge: a coloured rounded square with a symbol. Drawn, not an item: Wynncraft's
     * resource pack repaints vanilla items (the compass becomes a bowl, the skull a grey block).
     */
    /** A tab's icon on a rounded tile of its colour (home purple, today amber, quests blue …). */
    private void badge(GuiGraphics g, int tab, int x, int y) {
        badge(g, tab, x, y, 16);
    }

    private void badge(GuiGraphics g, int tab, int x, int y, int size) {
        String icon;
        int bg;
        int fg;
        switch (tab) {
            case 0 -> { icon = "home"; bg = 0xFF26233A; fg = 0xFFAFA9EC; }
            case 1 -> { icon = "sun"; bg = 0xFF3A2A12; fg = 0xFFFAC775; }
            case 2 -> { icon = "map-2"; bg = 0xFF12273A; fg = 0xFF85B7EB; }
            case 3 -> { icon = "skull"; bg = 0xFF3A1616; fg = 0xFFF09595; }
            case TAB_BANK -> { icon = "building-bank"; bg = 0xFF0F2A22; fg = 0xFF5DCAA5; }
            case TAB_AI -> { icon = "sparkles"; bg = 0xFF12303A; fg = 0xFF7FD4E8; }
            case TAB_DAMAGE -> { icon = "sword"; bg = 0xFF3A2414; fg = 0xFFF0B27A; }
            case TAB_DISCOVERIES -> { icon = "compass"; bg = 0xFF2A1F3A; fg = 0xFFC9A7F0; }
            case TAB_TREE -> { icon = "hierarchy"; bg = 0xFF1E3220; fg = 0xFF9FDC8A; }
            case TAB_CRAFT -> { icon = "hammer"; bg = 0xFF3A2E14; fg = 0xFFE8C07A; }
            case TAB_ENDGAME -> { icon = "trophy"; bg = 0xFF40280C; fg = 0xFFFFD36B; }
            default -> { icon = "settings"; bg = 0xFF24242E; fg = 0xFFB4B2A9; }
        }
        Art.iconTile(g, icon, x, y, size, bg, fg);
    }

    /** A centred card for a page with nothing yet: what to do, step by step. */
    private void emptyState(GuiGraphics g, int x, int y, int w, int h, ItemStack icon, String title, String... stepsText) {
        emptyState(g, x, y, w, h, icon, title, null, stepsText);
    }

    /** A button under an empty card's title, and a faint centred note under it ({@code note} may be null). */
    private record EmptyButton(String label, int state, List<Component> tip, String action, String note) {}

    /**
     * A centred card for a page with nothing yet: the icon, the title, an optional button with a
     * note under it, then numbered steps. Its height follows what is in it, with the card's
     * padding below the last line at every layout size.
     */
    private void emptyState(GuiGraphics g, int x, int y, int w, int h, ItemStack icon, String title, EmptyButton button,
            String... stepsText) {
        int pad = 10;
        int lh = font.lineHeight;
        int step = lh + 4;
        int cw = Math.min(w - 40, 300);
        List<FormattedCharSequence> note = button == null || button.note() == null ? List.of()
                : font.split(Component.literal(button.note()), cw - 2 * pad - 8);
        int titleY = 44;                            // under the 32 px icon, 8 from the top
        int chh = titleY + lh;
        int buttonY = chh + 6;
        if (button != null) {
            chh = buttonY + Kit.Ui.BTN_H;
        }
        int noteY = chh + 5;
        if (!note.isEmpty()) {
            chh = noteY + note.size() * (lh + 2) - 2;
        }
        int stepsY = chh + 5;
        if (stepsText.length > 0) {
            chh = stepsY + stepsText.length * step - 4;
        }
        chh += pad;
        int cx = x + (w - cw) / 2;
        int cy = y + Math.max(Math.min(10, Math.max(0, h - chh)), (h - chh) / 2 - 10);
        card(g, cx, cy, cw, chh, false, Theme.GOLD_DEEP);
        bigItem(g, icon, cx + (cw - 32) / 2, cy + 8);
        Component t = Component.literal(fit(title, cw - 2 * pad)).withStyle(UiFont::bold);
        ink(g, t, cx + (cw - font.width(t)) / 2, cy + titleY, Theme.INK);
        if (button != null) {
            String label = fit(button.label(), cw - 2 * pad - 2 * Kit.Ui.BTN_PAD);
            int bw = Kit.Ui.btnW(font, label);
            int bx = cx + (cw - bw) / 2;
            int by = cy + buttonY;
            int state = button.state();          // gold when it can run, plain wood while it runs or cannot
            Kit.button(g, bx, by, bw, Kit.Ui.BTN_H, state);
            onWood(g, label, bx + Kit.Ui.BTN_PAD, by + 3, state);
            hits.add(new Hit(bx, by, bw, Kit.Ui.BTN_H, null, button.tip(), button.action()));
        }
        for (int i = 0; i < note.size(); i++) {
            ink(g, note.get(i), cx + (cw - font.width(note.get(i))) / 2, cy + noteY + i * (lh + 2), FAINT);
        }
        int sy = cy + stepsY;
        for (int i = 0; i < stepsText.length; i++) {
            Component c = Component.literal((i + 1) + "  ").withStyle(ChatFormatting.DARK_PURPLE)
                    .append(Component.literal(fit(stepsText[i], cw - 30)).withStyle(ChatFormatting.GRAY));
            ink(g, c, cx + 14, sy, Theme.INK);
            sy += step;
        }
    }

    // ---- ability tree ----

    private net.minecraft.client.gui.components.EditBox treeSearch;
    private int treeScroll;
    /** Room above the tree's first row, so the root and the "s. 1" label are not cut at the top. */
    private static final int TREE_TOP = 6;
    private int treeScrollMax;
    private int treeCell = 30;
    private String treeFocus = "";              // the next pick the view was last opened on
    private boolean treeJump;                   // the search changed: open the first match
    private TreeView.View treeView;
    private String treeKey = "";
    private List<TreePlanner.Step> treePlan = List.of();
    private String treePlanKey = "";
    private TreeLayout.Lines treeLines;
    private String treeLinesKey = "";
    private Map<String, boolean[]> treeLit = Map.of();
    private Map<String, boolean[]> treePlanArms = Map.of();
    private String treeLitKey = "";

    /** Which arms are lit (between unlocked nodes) and which carry the plan, for the view. */
    private void treeArms(List<AbilityNode> tree, TreeView.View v, Map<String, boolean[]> pieces) {
        java.util.Set<Integer> have = new java.util.HashSet<>();
        java.util.Set<Integer> plan = new java.util.HashSet<>();
        for (TreeView.Node n : v.nodes()) {
            if (n.state() == TreeView.State.UNLOCKED) {
                have.add(n.node().id());
            }
            if (n.state() == TreeView.State.UNLOCKED || n.state() == TreeView.State.NEXT || n.state() == TreeView.State.PLANNED) {
                plan.add(n.node().id());
            }
        }
        String key = have.hashCode() + "|" + plan.hashCode() + "|" + System.identityHashCode(pieces) + "|" + System.identityHashCode(tree);
        if (!key.equals(treeLitKey)) {
            treeLit = TreeLayout.lit(tree, pieces, have);
            treePlanArms = TreeLayout.lit(tree, pieces, plan);
            treeLitKey = key;
        }
    }

    /** The tree's lines, from the pieces seen in the game; rebuilt when the tree or the pieces change. */
    private TreeLayout.Lines treeLines(List<AbilityNode> tree) {
        Map<String, boolean[]> pieces = TreeConnectors.get(GameState.current().classType());
        String key = tree.size() + "|" + System.identityHashCode(tree) + "|" + pieces.size() + "|" + System.identityHashCode(pieces);
        if (treeLines == null || !key.equals(treeLinesKey)) {
            treeLines = TreeLayout.lines(tree, pieces);
            treeLinesKey = key;
        }
        return treeLines;
    }

    /** The view, rebuilt when the tree, the unlocked nodes, the archetype or the search change. */
    private TreeView.View treeView(List<AbilityNode> tree) {
        GameState gs = GameState.current();
        List<String> unlocked = gs.unlockedAbilities();
        String chosen = TreePlanner.chosen();
        int level = gs.level();
        String planKey = chosen + "|" + level + "|" + tree.size() + "|" + System.identityHashCode(tree);
        if (!planKey.equals(treePlanKey)) {
            treePlan = chosen.isEmpty() ? List.of() : TreePlanner.wholePlan(chosen);
            treePlanKey = planKey;
        }
        String q = treeSearch == null ? "" : treeSearch.getValue();
        String key = planKey + "|" + q + "|" + unlocked.hashCode();
        if (treeView == null || !key.equals(treeKey)) {
            int total = Math.max(Wyn.abilityPointsAt(level), TreePlanner.basePoints(level));
            treeView = TreeView.build(tree, unlocked, treePlan, total, q);
            treeKey = key;
        }
        return treeView;
    }

    /**
     * The ability tree as the game lays it out: 9 columns, the pages one under the other in one
     * scrolling column, lines between connected nodes, the plan's order on the nodes. Archetype
     * and points on top with the search, the legends on the right, page buttons below.
     */
    private void drawTree(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float pt) {
        List<AbilityNode> tree = GameState.current().abilityTree();
        if (tree == null || tree.isEmpty()) {
            emptyState(g, x, y, w, h, new ItemStack(net.minecraft.world.item.Items.OAK_SAPLING),
                    L.t("wynnlabs.tree.unknown_title"), L.t("wynnlabs.tree.unknown_1"), L.t("wynnlabs.tree.unknown_2"));
            return;
        }
        TreeView.View v = treeView(tree);
        String chosen = TreePlanner.chosen();
        // 1. the top bar: archetype, points, the next pick, search
        int cy = y;
        String archLabel = chosen.isEmpty() ? L.t("wynnlabs.tree.arch_off") : chosen;
        int ax = x + pickChip(g, x, cy, "sparkles", archLabel, !chosen.isEmpty(), mx, my, "#tree:arch") + 8;
        String pts = v.afterPlan() > v.spent() ? L.t("wynnlabs.tree.points_plan", v.spent(), v.total(), v.afterPlan())
                : L.t("wynnlabs.tree.points", v.spent(), v.total());
        ink(g, pts, ax, cy + 3, Theme.INK_2);
        ax += font.width(pts) + 10;
        if (v.next() != null) {
            ink(g, L.t("wynnlabs.tree.next_at", v.nextLevel()), ax, cy + 3, Theme.ACCENT);
        }
        int sw = 104;
        textBox(g, treeSearch, x + w - sw, cy + 1, sw, "wynnlabs.tree.search_hint", mx, my, pt);
        cy += 18;
        // 2. the panel: square cells, 9 across, every page under the one before
        int legendW = 124;
        int cell = Math.max(18, Math.min(34, (w - legendW - 14) / TreeLayout.COLS));
        treeCell = cell;
        int pw = cell * TreeLayout.COLS + 6;
        int ph = h - (cy - y) - 18;
        int px = x;
        int py = cy;
        Kit.slate(g, px, py, pw, ph);
        int first = TreeLayout.firstPage(tree);
        int pages = TreeLayout.pages(tree);
        int contentH = TreeLayout.rows(tree) * cell + 6 + TREE_TOP;
        treeScrollMax = Math.max(0, contentH - (ph - 2));
        // open on the next pick; the search opens its first match
        if (v.next() != null && !v.next().name().equals(treeFocus)) {
            treeFocus = v.next().name();
            // centred on it, rounded to whole rows so no node is cut at the top
            treeScroll = Math.max(0, TreeLayout.row(v.next(), first) * cell + cell / 2 - (ph - 2) / 2) / cell * cell;
        }
        if (treeJump) {
            treeJump = false;
            if (!treeSearch.getValue().isBlank()) {
                for (TreeView.Node n : v.nodes()) {
                    if (n.matches() && n.node().where() != null) {
                        treeScroll = TreeLayout.row(n.node(), first) * cell + cell / 2 - (ph - 2) / 2;
                        break;
                    }
                }
            }
        }
        treeScroll = Math.max(0, Math.min(treeScrollMax, treeScroll));
        int ox = px + 3;
        int oy = py + 3 + TREE_TOP - treeScroll;
        Map<Integer, TreeView.Node> byId = new HashMap<>();
        for (TreeView.Node n : v.nodes()) {
            byId.put(n.node().id(), n);
        }
        g.enableScissor(px + 1, py + 1, px + pw - 1, py + ph - 1);
        // the board's faint grid, then the pages: a line where each one starts, its number on it
        int rows = TreeLayout.rows(tree);
        for (int c = 0; c <= TreeLayout.COLS; c++) {
            g.fill(ox + c * cell, oy, ox + c * cell + 1, oy + rows * cell, 0x10FFFFFF);
        }
        for (int r = 0; r <= rows; r++) {
            g.fill(ox, oy + r * cell, ox + TreeLayout.COLS * cell, oy + r * cell + 1, 0x10FFFFFF);
        }
        for (int p = 0; p < pages; p++) {
            int ly = oy + p * TreeLayout.ROWS * cell;
            if (p > 0) {
                g.fill(px + 1, ly - 1, px + pw - 1, ly + 1, 0x50FFFFFF);
            }
            String label = L.t("wynnlabs.tree.page_short", p + 1);
            int lw2 = font.width(label);
            g.fill(px + pw - 9 - lw2, ly - 5, px + pw - 3, ly + 5, 0xC0262B30);
            g.drawString(font, label, px + pw - 6 - lw2, ly - 4, 0xB0FFFFFF, false);
        }
        // the lines, under the nodes: the game's pieces where a page was seen, routed around
        // the nodes elsewhere; lit between unlocked nodes, the archetype's colour along the
        // plan, grey elsewhere
        TreeLayout.Lines tl = treeLines(tree);
        int thick = cell >= 28 ? 3 : 2;
        // the game's own pieces, each arm lit only on the way between two unlocked nodes, as
        // the game lights them; their lines are left out then, the plan drawn thin over them
        io.github.anilken.wynnlabs.model.Klass cls = GameState.current().classType();
        Map<String, boolean[]> pieces = TreeConnectors.get(cls);
        treeArms(tree, v, pieces);
        boolean pictured = false;
        for (var pc : tl.pieces().entrySet()) {
            String[] k = pc.getKey().split(":");
            int pr = (Integer.parseInt(k[0]) - first) * TreeLayout.ROWS + Integer.parseInt(k[1]);
            int pcx = Integer.parseInt(k[2]);
            boolean[] arms = pieces.get(pc.getKey());
            boolean[] litArms = treeLit.getOrDefault(pc.getKey(), new boolean[4]);
            ItemStack pic = arms == null ? null : Wyn.connectorIcon(arms, litArms);
            if (pic == null) {
                continue;
            }
            pictured = true;
            int py2 = oy + pr * cell;
            if (py2 + cell < py || py2 > py + ph) {
                continue;
            }
            float sc = cell / 16f;
            g.pose().pushMatrix();
            g.pose().translate(ox + pcx * cell, py2);
            g.pose().scale(sc, sc);
            g.renderItem(pic, 0, 0);
            g.pose().popMatrix();
        }
        for (TreeLayout.Line ln : tl.lines()) {
            int colour;
            int t = thick;
            if (ln.piece()) {
                boolean[] la = treeLit.get(ln.cell());
                boolean[] pa = treePlanArms.get(ln.cell());
                boolean lit = la != null && la[ln.arm()];
                boolean plan = pa != null && pa[ln.arm()];
                if (pictured) {
                    if (lit || !plan) {
                        continue;                   // the picture shows it
                    }
                    t = Math.max(1, thick - 1);     // the plan, thin over the dark piece
                }
                colour = lit ? 0xFFE8DCB4 : plan ? planColour(tl.ends().get(ln.group()), byId) : 0xFF4A4E56;
            } else {
                colour = stretchColour(tl.ends().get(ln.group()), byId);
            }
            int x0 = ox + Math.min(ln.c0(), ln.c1()) * cell / 2;
            int x1 = ox + Math.max(ln.c0(), ln.c1()) * cell / 2;
            int y0 = oy + Math.min(ln.r0(), ln.r1()) * cell / 2;
            int y1 = oy + Math.max(ln.r0(), ln.r1()) * cell / 2;
            if (ln.horizontal()) {
                g.fill(x0, y0 - t / 2, x1, y0 - t / 2 + t, colour);
            } else {
                g.fill(x0 - t / 2, y0, x0 - t / 2 + t, y1, colour);
            }
        }
        // the nodes: the game's own icon when the pack gives one, else our shapes; a ring for
        // the state, the plan's number, the blocked mark; what the search misses goes dark
        long now = System.currentTimeMillis();
        TreeView.Node hover = null;
        int[] hoverRect = null;
        int size = cell - 6;
        for (TreeView.Node n : v.nodes()) {
            AbilityNode a = n.node();
            if (a.where() == null) {
                continue;
            }
            int nx = ox + a.where().col() * cell;
            int ny = oy + TreeLayout.row(a, first) * cell;
            if (ny + cell < py || ny > py + ph) {
                continue;
            }
            int ix = nx + 3;
            int iy = ny + 3;
            ItemStack icon = Wyn.nodeIcon(a.type(), n.iconState());
            int fill = switch (n.state()) {
                case UNLOCKED, NEXT -> n.colour();
                case PLANNED -> mix(n.colour(), 0xFF1E1A22, 0.55f);
                case AVAILABLE -> 0xFF2A2530;
                case BLOCKED -> 0xFF4A4452;
                default -> 0xFF2C2C2A;
            };
            String shape = n.ultimate() ? "tree-diamond" : "tree-circle";
            if (icon != null) {
                // the game's picture, a little inside its cell (its glow already reaches out)
                float sc = size / 16f;
                g.pose().pushMatrix();
                g.pose().translate(ix, iy);
                g.pose().scale(sc, sc);
                g.renderItem(icon, 0, 0);
                g.pose().popMatrix();
            } else {
                Art.icon(g, shape, ix, iy, size, fill);
            }
            String ring = shape + "-ring";
            // the game's picture shows unlocked and unlockable itself: only the next pick gets a ring
            switch (icon != null && n.state() != TreeView.State.NEXT ? TreeView.State.LOCKED : n.state()) {
                case UNLOCKED -> Art.icon(g, ring, ix - 1, iy - 1, size + 2, 0xFFEEEDFE);
                case NEXT -> {
                    int alpha = 110 + (int) (145 * (0.5 + 0.5 * Math.sin(now / 1000.0 * Math.PI * 2)));
                    Art.icon(g, ring, ix - 2, iy - 2, size + 4, alpha << 24 | 0xFFE071);
                }
                case AVAILABLE -> Art.icon(g, ring, ix - 1, iy - 1, size + 2, n.colour());
                default -> { }
            }
            if (n.step() > 0) {
                String mark = String.valueOf(n.step());
                g.drawString(font, mark, nx + (cell - font.width(mark)) / 2, ny + (cell - 8) / 2,
                        n.state() == TreeView.State.NEXT ? 0xFFFFE071 : 0xFFFFFFFF, true);
            } else if (n.state() == TreeView.State.BLOCKED) {
                Art.icon(g, "x", nx + (cell - 8) / 2, ny + (cell - 8) / 2, 8, 0xFFFFFFFF);
            }
            if (!n.matches()) {
                g.fill(nx, ny, nx + cell, ny + cell, 0xB01E1A22);
            }
            if (mx >= nx && mx < nx + cell && my >= Math.max(ny, py) && my < Math.min(ny + cell, py + ph)) {
                hover = n;
                hoverRect = new int[] {nx, Math.max(ny, py), cell, Math.min(ny + cell, py + ph) - Math.max(ny, py)};
            }
        }
        g.disableScissor();
        if (hover != null) {
            hits.add(new Hit(hoverRect[0], hoverRect[1], hoverRect[2], hoverRect[3], null, treeTip(hover, v), null));
        }
        // 3. the page buttons: the one in view is lit; a click jumps
        int by = y + h - 14;
        int bx = x;
        // the page at the top of the view; at the end of the scroll, the last page that starts in it
        int pageH = Math.max(1, TreeLayout.ROWS * cell);
        int page = Math.min(pages - 1, Math.max(0, (treeScroll + cell / 2) / pageH));
        if (treeScroll >= treeScrollMax) {
            while (page + 1 < pages && TREE_TOP + (page + 1) * pageH < treeScroll + ph - 2) {
                page++;
            }
        }
        for (int p = 0; p < pages; p++) {
            bx += pickChip(g, bx, by, L.t("wynnlabs.tree.page", p + 1), p == page, mx, my, "#tree:page:" + p) + 3;
        }
        String hint = L.t("wynnlabs.tree.wheel_hint");
        if (bx + 6 + font.width(hint) <= px + pw) {
            ink(g, hint, bx + 6, by + 3, FAINT);
        }
        // 4. the legends: each archetype's points now (and after the plan), then the states
        int lx = px + pw + 8;
        int lw = x + w - lx;
        int ly = py;
        int ah = 18 + v.archetypes().size() * 11 + 2;
        card(g, lx, ly, lw, ah, false, Theme.GOLD_DEEP);
        ink(g, Component.literal(L.t("wynnlabs.tree.legend_arch")).withStyle(UiFont::bold), lx + 6, ly + 5, Theme.INK);
        int ry = ly + 17;
        for (TreeView.Archetype a : v.archetypes()) {
            g.fill(lx + 7, ry + 1, lx + 13, ry + 7, a.colour());
            String t = a.after() > a.now() ? a.now() + " → " + a.after() : String.valueOf(a.now());
            ink(g, fit(a.name(), lw - 24 - font.width(t)), lx + 17, ry, Theme.INK_2);
            ink(g, t, lx + lw - 6 - font.width(t), ry, Theme.INK_2);
            ry += 11;
        }
        ly += ah + 6;
        String[] states = {"wynnlabs.tree.state_unlocked", "wynnlabs.tree.state_next", "wynnlabs.tree.state_planned",
            "wynnlabs.tree.state_available", "wynnlabs.tree.state_blocked", "wynnlabs.tree.state_locked"};
        int[] sample = {0xFF7F77DD, 0xFFFFE071, mix(0xFF7F77DD, 0xFF1E1A22, 0.55f), 0xFF2A2530, 0xFF4A4452, 0xFF2C2C2A};
        // one column; two when that is too tall (a short window), so the states still show
        int stateCols = ly + 18 + states.length * 11 + 2 <= py + ph ? 1 : 2;
        int perCol = (states.length + stateCols - 1) / stateCols;
        int sh = 18 + perCol * 11 + 2;
        int scw = (lw - 6) / stateCols;
        if (ly + sh <= py + ph) {
            card(g, lx, ly, lw, sh, false, Theme.GOLD_DEEP);
            ink(g, Component.literal(L.t("wynnlabs.tree.legend_states")).withStyle(UiFont::bold), lx + 6, ly + 5, Theme.INK);
            for (int i = 0; i < states.length; i++) {
                int sx = lx + (i / perCol) * scw;
                ry = ly + 17 + (i % perCol) * 11;
                Art.icon(g, "tree-circle", sx + 6, ry - 1, 9, sample[i]);
                if (i == 0 || i == 1 || i == 3) {
                    Art.icon(g, "tree-circle-ring", sx + 6, ry - 1, 9, i == 0 ? 0xFFEEEDFE : i == 1 ? 0xFFFFE071 : 0xFF7F77DD);
                }
                ink(g, fit(L.t(states[i]), scw - 20), sx + 18, ry, Theme.INK_2);
            }
        }
    }

    /** The plan's colour on a stretch: the archetype of a planned node it joins. */
    private static int planColour(java.util.Set<Integer> ends, Map<Integer, TreeView.Node> byId) {
        for (int id : ends) {
            TreeView.Node n = byId.get(id);
            if (n != null && (n.state() == TreeView.State.NEXT || n.state() == TreeView.State.PLANNED)) {
                return mix(n.colour(), 0xFF262B30, 0.25f);
            }
        }
        return mix(0xFF7F77DD, 0xFF262B30, 0.25f);
    }

    /**
     * A stretch's colour from the nodes it joins: lit when two of them are unlocked, the
     * archetype's colour when two are unlocked or planned, grey otherwise.
     */
    private static int stretchColour(java.util.Set<Integer> ends, Map<Integer, TreeView.Node> byId) {
        int unlocked = 0;
        int plan = 0;
        int colour = 0;
        for (int id : ends) {
            TreeView.Node n = byId.get(id);
            if (n == null) {
                continue;
            }
            if (n.state() == TreeView.State.UNLOCKED) {
                unlocked++;
                plan++;
            } else if (n.state() == TreeView.State.NEXT || n.state() == TreeView.State.PLANNED) {
                plan++;
                colour = n.colour();
            }
        }
        if (unlocked >= 2) {
            return 0xFFE8DCB4;
        }
        if (plan >= 2) {
            return mix(colour == 0 ? 0xFF7F77DD : colour, 0xFF262B30, 0.35f);
        }
        return 0xFF4A4E56;
    }

    /** {@code a} towards {@code b} by {@code t} (0: a, 1: b), opaque. */
    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int gr = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | r << 16 | gr << 8 | bl;
    }

    /** A node's tooltip: name, description, cost and requirements, what it locks, its state. */
    private List<Component> treeTip(TreeView.Node n, TreeView.View v) {
        AbilityNode a = n.node();
        List<Component> tip = new ArrayList<>();
        net.minecraft.network.chat.MutableComponent name = Component.literal(a.name()).withStyle(ChatFormatting.WHITE).withStyle(UiFont::bold);
        if (n.ultimate()) {
            name.append(Component.literal(" · " + L.t("wynnlabs.tree.ultimate")).withStyle(ChatFormatting.GOLD).withStyle(UiFont::bold));
        }
        tip.add(name);
        if (a.description() != null) {
            for (var line : a.description()) {
                for (String l : Advice.wrap(TreeView.plain(line), 46)) {
                    tip.add(Component.literal(l).withStyle(ChatFormatting.GRAY));
                }
            }
        }
        tip.add(Component.empty());
        String cost = L.n("wynnlabs.tree.tip_cost", a.cost());
        if (a.requiredLevel() > 0) {
            cost += " · " + L.t("wynnlabs.tree.tip_level", a.requiredLevel());
        }
        tip.add(Component.literal(cost).withStyle(ChatFormatting.GREEN));
        if (a.archetype() != null && !a.archetype().isBlank()) {
            tip.add(Component.literal(L.t("wynnlabs.tree.tip_archetype", a.archetype()))
                    .withStyle(st -> st.withColor(n.colour() & 0xFFFFFF)));
        }
        String req = a.requiredAbility();
        if (req != null && !req.isBlank() && !req.equals("None")) {
            tip.add(Component.literal(L.t("wynnlabs.tree.tip_requires", req)).withStyle(ChatFormatting.YELLOW));
        }
        if (a.requiredArchetype() != null && !a.requiredArchetype().isBlank()) {
            tip.add(Component.literal(L.n("wynnlabs.tree.tip_arch_points", a.requiredArchetypePoints(), a.requiredArchetype(), a.requiredArchetypePoints()))
                    .withStyle(ChatFormatting.YELLOW));
        }
        if (a.willBlock() != null && !a.willBlock().isEmpty()) {
            tip.add(Component.literal(L.t("wynnlabs.tree.tip_blocks", String.join(", ", a.willBlock()))).withStyle(ChatFormatting.RED));
        }
        int planned = 0;
        for (TreeView.Node o : v.nodes()) {
            planned = Math.max(planned, o.step());
        }
        String state = switch (n.state()) {
            case UNLOCKED -> L.t("wynnlabs.tree.state_unlocked");
            case NEXT -> L.t("wynnlabs.tree.tip_next") + " · " + L.t("wynnlabs.tree.tip_plan", n.step(), planned);
            case PLANNED -> L.t("wynnlabs.tree.tip_plan", n.step(), planned);
            case AVAILABLE -> L.t("wynnlabs.tree.state_available");
            case BLOCKED -> L.t("wynnlabs.tree.state_blocked");
            default -> L.t("wynnlabs.tree.state_locked");
        };
        tip.add(Component.literal(state).withStyle(n.state() == TreeView.State.NEXT || n.state() == TreeView.State.PLANNED
                ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.DARK_GRAY));
        return tip;
    }

    /** A click on the tab: the archetype chip or a page button. */
    private void treeClick(String action) {
        if (action.equals("#tree:arch")) {
            TreePlanner.cycle();
            String a = TreePlanner.chosen();
            flash = L.t("wynnlabs.home.archetype_set", a.isEmpty() ? L.t("wynnlabs.settings.archetype_off") : a);
            flashUntil = System.currentTimeMillis() + 2500;
            treeFocus = "";                         // the view opens on the new plan's next pick
            steps = rebuild();                      // the build follows the archetype: rebuilt in the background
            refreshAsked = true;
        } else if (action.startsWith("#tree:page:")) {
            treeScroll = Integer.parseInt(action.substring(11)) * TreeLayout.ROWS * treeCell;
        }
    }

    // ---- rail (the menu on the left) ----

    private void drawRail(GuiGraphics g, int x, int y, int h, int mx, int my) {
        // the class and level are on the Home tab's HUD; the rail is the tabs alone
        int ry = y + 10;
        boolean compact = layout.compact();
        // 24 apart, closer on a short window so the entries stay above the language picker (one
        // button and no "Esc" line in the compact rail)
        int foot = compact ? 22 : 42;
        int pitch = Math.max(14, Math.min(24, (h - foot - 10 - 4) / RAIL.length));
        // 2px between entries; the icon shrinks with a short entry so it never spills over
        int eh = pitch - 2;
        int icon = compact ? Math.min(18, eh & ~1) : Math.min(16, (eh - 4) & ~1);
        for (int i = 0; i < RAIL.length; i++) {
            int t = RAIL[i];
            boolean on = t == tab;
            boolean hover = mx >= x && mx < x + railW() && my >= ry && my < ry + eh;
            // the discoveries' page title is too long for the rail: a shorter name there
            String name = t == TAB_DISCOVERIES ? L.t("wynnlabs.screen.rail_discoveries") : L.t(TABS[t]);
            if (compact) {
                // icons only, the tile is the button: gold behind the selected one, a frame on
                // hover, the name in the tooltip
                int ix = x + 2 + (railW() - 4 - icon) / 2;
                int iy = ry + (eh - icon) / 2;
                if (on) {
                    Kit.button(g, x + 4, ry, railW() - 4, eh, Kit.ON);
                    Kit.tip(g, x + railW(), ry, eh);
                } else if (hover) {
                    Kit.button(g, ix - 3, ry, icon + 6, eh, Kit.HOVER);
                }
                badge(g, t, ix, iy, icon);
                hits.add(new Hit(x, ry, railW(), eh, null, List.of(Component.literal(name)), null));
            } else {
                if (on) {
                    // the selected tab is gold and reaches over the page's torn rim with a tip
                    Kit.button(g, x + 4, ry, railW() - 4, eh, Kit.ON);
                    Kit.tip(g, x + railW(), ry, eh);
                } else {
                    Kit.button(g, x + 4, ry, railW() - 8, eh, hover ? Kit.HOVER : Kit.REST);
                }
                badge(g, t, x + 8 + (16 - icon) / 2, ry + (eh - icon) / 2, icon);
                g.drawString(font, fit(name, railW() - 34), x + 30, ry + (eh - 8) / 2, on ? Theme.INK : Theme.CREAM, !on);
            }
            tabs.add(new int[] {x, ry, railW(), eh, t});
            ry += pitch;
        }
        if (compact) {
            drawLanguageButton(g, x + 3, y + h - 18, railW() - 6, mx, my);
        } else {
            drawLanguage(g, x + 8, y + h - 42, railW() - 16, mx, my);
            g.drawString(font, L.t("wynnlabs.screen.esc_close"), x + 8, y + h - 14, Theme.CREAM_DIM);
        }
    }

    /** The compact rail's language: one button showing the choice, a click moves to the next. */
    private void drawLanguageButton(GuiGraphics g, int x, int y, int w, int mx, int my) {
        langRects.clear();
        int pick = Settings.number("langOverride", 0);
        String[] names = {L.t("wynnlabs.settings.lang_auto"), "EN", "TR"};
        String label = fit(names[pick], w - 4);
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 14;
        int state = hover ? Kit.HOVER : Kit.REST;
        Kit.button(g, x, y, w, 14, state);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + 3, Kit.buttonText(state), true);
        langRects.add(new int[] {x, y, w, 14, (pick + 1) % names.length});
        hits.add(new Hit(x, y, w, 14, null, List.of(Component.literal(L.t("wynnlabs.settings.language") + ": " + names[pick])), null));
    }

    /**
     * The mod's language, one segmented control at the bottom of the rail: Auto follows the
     * game, EN and TR pin the mod's text (the game's own language is left alone).
     */
    private void drawLanguage(GuiGraphics g, int x, int y, int w, int mx, int my) {
        langRects.clear();
        g.drawString(font, L.t("wynnlabs.settings.language"), x, y, Theme.CREAM_DIM);
        int pick = Settings.number("langOverride", 0);
        String[] names = {L.t("wynnlabs.settings.lang_auto"), "EN", "TR"};
        int sy = y + 11;
        int sh = 14;
        int segW = w / names.length;
        for (int i = 0; i < names.length; i++) {
            int sx = x + i * segW;
            int ew = i == names.length - 1 ? w - i * segW : segW;
            boolean sel = pick == i;
            boolean hover = mx >= sx && mx < sx + ew && my >= sy && my < sy + sh;
            int state = sel ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
            Kit.button(g, sx, sy, ew, sh, state);
            g.drawString(font, names[i], sx + (ew - font.width(names[i])) / 2, sy + 3, Kit.buttonText(state), !sel);
            langRects.add(new int[] {sx, sy, ew, sh, i});
        }
    }

    // ---- home (Ana) ----

    private record UpRow(Box box, Slot type, ItemStack worn, Roadmap.Gear gear) {}

    /**
     * The level a milestone's whole set is wearable at: its highest piece. A milestone at 120
     * often brings Lv 105 pieces, so its tab reads 105 or so, not 120.
     */
    private static int wearAt(Roadmap.Step s) {
        int hi = 0;
        for (Damage.Piece p : s.build().values()) {
            if (p != null) {
                hi = Math.max(hi, p.level());
            }
        }
        for (Roadmap.Gear g : s.gear()) {
            hi = Math.max(hi, g.info().requirements().level());
        }
        return hi == 0 ? s.level() : hi;
    }

    /**
     * The milestones with a tab: one is left out when its set is wearable already (the now tab
     * has it) or the next one's is wearable as early (its picks were there already, only better
     * chosen), so the tabs climb one level each.
     */
    private List<Integer> shownSteps() {
        List<Integer> out = new ArrayList<>();
        int now = steps.isEmpty() ? 0 : wearAt(steps.get(0));
        for (int i = 1; i < steps.size(); i++) {
            int at = wearAt(steps.get(i));
            // wearable already: the now tab has it; a later set as early: that one is better
            if (at <= now || i + 1 < steps.size() && wearAt(steps.get(i + 1)) <= at) {
                continue;
            }
            out.add(i);
        }
        return out;
    }

    /**
     * Wynncraft's stand-in for an empty slot ("Leggings Slot", "Ring Slot"): nothing is worn
     * there, so the stand shows the slot empty, not a tick on it.
     */
    private static boolean emptySlot(ItemStack s) {
        if (s.isEmpty()) {
            return true;
        }
        var v = GameState.current().view(s);
        return (v.isEmpty() || !v.get().isGear() && !v.get().isCrafted())
                && ItemJson.clean(s.getHoverName().getString()).trim().endsWith(" Slot");
    }

    /** A slot's name on a row; the two rings as "Ring 1" and "Ring 2". */
    private static String boxLabel(Box box) {
        return L.t(box.label()) + (box.type() == Slot.RING ? " " + (box.ring() + 1) : "");
    }

    /** Every slot with what you wear and the upgrade the selected level brings (null: keep). */
    private List<UpRow> upRows() {
        Map<String, Roadmap.Gear> up = upgrades();
        LocalPlayer player = Minecraft.getInstance().player;
        int weakRing = weakerRing(player);
        List<UpRow> out = new ArrayList<>();
        List<Box> boxes = new ArrayList<>(List.of(LEFT));
        boxes.addAll(List.of(RIGHT));
        for (Box box : boxes) {
            Slot type = box.type() == null ? Slot.weaponOf(Wyn.classType()) : box.type();
            ItemStack worn = ItemStack.EMPTY;
            if (player != null && type != null) {
                List<ItemStack> l = AutoAdvice.worn(type);
                if (box.ring() < l.size() && !emptySlot(l.get(box.ring()))) {
                    worn = l.get(box.ring());
                }
            }
            Roadmap.Gear u = type == null ? null : up.get(type.name());
            if (type == Slot.RING) {
                // the ring pick replaces the weaker ring, a pick for the other hand the other one
                Roadmap.Gear second = up.get(Roadmap.RING2);
                if (u == null) {
                    u = box.ring() == weakRing ? second : null;
                } else if (box.ring() != weakRing) {
                    u = second;
                }
            }
            out.add(new UpRow(box, type, worn, u));
        }
        return out;
    }

    private void drawHome(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        if (!Wyn.up(Wyn.Feature.GEAR_DB) || !Wyn.up(Wyn.Feature.CHARACTER)) {
            ink(g, L.c("wynnlabs.wyn.home_off", Wyn.version()).withStyle(ChatFormatting.GOLD), x, y + 4, 0xFFFFFFFF);
            return;
        }
        if (!Rules.adviceHere()) {
            // an alt with the advice off: say so, with the switch right here
            emptyState(g, x, y, w, h, new ItemStack(net.minecraft.world.item.Items.BARRIER),
                    L.t("wynnlabs.home.advice_off_title"), L.t("wynnlabs.home.advice_off_1"));
            String on = L.t("wynnlabs.home.advice_on");
            int bw = font.width(on) + 12;
            pickChip(g, x + (w - bw) / 2, y + h / 2 + 34, on, false, mx, my, "#qadvice:");
            return;
        }
        if (error != null) {
            ink(g, Component.literal(error).withStyle(ChatFormatting.RED), x, y + 4, 0xFFFFFFFF);
            return;
        }
        // with no road map yet the gear panel offers to calculate one: nothing is built on its own
        drawHomeHeader(g, x, y, w);
        // the visual home (HomeTab): the gear on a stand between two columns of tiles
        int by = y + HUD_H + 13;                            // 4px under the experience bar and its "%100 XP"
        // 4px short of the page's foot: the parchment's corner curls sit there
        HomeTab.INSTANCE.draw(g, homeIn(), homeHost, x, by, w, y + h - by - 4, layout.compact(), layout.large(), mx, my);
    }

    /** This frame's slots for the home's stand: every gear slot with what you wear and the selected level's upgrade. */
    private HomeTab.In homeIn() {
        List<HomeTab.SlotView> slots = new ArrayList<>();
        List<UpRow> rows = upRows();
        for (int i = 0; i < rows.size(); i++) {
            UpRow r = rows.get(i);
            Roadmap.Gear u = r.gear();
            slots.add(new HomeTab.SlotView(i, boxLabel(r.box()), r.type(), r.worn(), u, u == null ? ItemStack.EMPTY : icon(u.info()),
                    u == null ? Double.NaN : rowGain(u), u == null ? null : context(gearTip(u))));
        }
        // the build score now, and with the selected milestone's whole set (NaN when it has none)
        Build.Plan plan = Build.plan();
        double now = plan == null || plan.current() == null ? Double.NaN : plan.current().objective();
        Roadmap.Step s = steps.isEmpty() ? null : steps.get(Math.max(0, Math.min(selected, steps.size() - 1)));
        double after = s == null || s.set() == null ? Double.NaN : s.set().result().objective();
        Plans.Build current = Plans.current();
        int chosen = Locks.names().size();
        int saved = Plans.saved().size();
        boolean searching = Around.running();
        if (planPills.planSelected(current)) {
            return planIn(current, rows, now, after, chosen, saved, searching);
        }
        return new HomeTab.In(slots, now, after, quests.isEmpty() ? null : quests.get(0), places(), null, chosen,
                saved, searching, road());
    }

    /**
     * The one way an item's hover is made on the Home tab and in the build window: its full
     * tooltip (tier-coloured name, type and level, requirements, base stats, identification
     * ranges) and then, after a blank line, {@code extra} (plan, source, gain, story). A worn
     * piece shows its own stack's tooltip with your rolls; another piece Wynntils' tooltip of its
     * guide stack, or without one ({@link BuildWindow#itemTooltip}) the item data's. {@code front}:
     * ahead of the other hits (an icon over its card).
     */
    private void itemHit(boolean front, int x, int y, int w, int h, ItemData info, ItemStack worn, List<Component> extra) {
        itemHit(front, x, y, w, h, info, worn, List.of(), extra);
    }

    /** {@link #itemHit}, with {@code lead} lines over the item's tooltip (a blank line between). */
    private void itemHit(boolean front, int x, int y, int w, int h, ItemData info, ItemStack worn, List<Component> lead,
            List<Component> extra) {
        List<Component> ex = extra == null ? List.of() : extra;
        List<Component> ld = lead == null || lead.isEmpty() ? null : lead;
        Hit hit;
        if (worn != null && !worn.isEmpty()) {
            hit = new Hit(x, y, w, h, worn, ex.isEmpty() ? null : ex, null, null, ld);
        } else {
            ItemStack st = info == null ? ItemStack.EMPTY : icon(info);
            if (iconInfo.containsKey(st)) {
                hit = new Hit(x, y, w, h, st, ex.isEmpty() ? null : ex, null, null, ld);
            } else {
                List<Component> tip = new ArrayList<>();
                if (ld != null) {
                    tip.addAll(ld);
                    tip.add(Component.empty());
                }
                tip.addAll(info == null ? List.of() : BuildWindow.itemTooltip(info));
                if (!ex.isEmpty()) {
                    tip.add(Component.empty());
                    tip.addAll(ex);
                }
                hit = new Hit(x, y, w, h, null, tip, null);
            }
        }
        if (front) {
            hits.add(0, hit);
        } else {
            hits.add(hit);
        }
    }

    /** A pick's story without its first line (the name): the item's own tooltip has the name. */
    private static List<Component> context(List<Component> tip) {
        return tip.size() <= 1 ? List.of() : List.copyOf(tip.subList(1, tip.size()));
    }

    /** The places to level now, from the road map (none without one). */
    private List<String> places() {
        return steps.isEmpty() ? List.of() : steps.get(0).places();
    }

    /** The road map's state for the gear panel: none yet, being built (and how far), out of date. */
    private HomeTab.Road road() {
        HomeTab.Road preview = HomeTab.previewRoad;
        return preview != null ? preview : new HomeTab.Road(steps.isEmpty(), Roadmap.isBuilding(), Roadmap.progress(), Roadmap.stale());
    }

    /**
     * Removes the plan: the panel shows the suggestions with "Now" selected; the chosen items,
     * the strip and the saved builds stay; the build line offers Undo for a few seconds.
     */
    private boolean removePlan() {
        if (!Plans.remove(System.currentTimeMillis())) {
            return false;
        }
        selected = 0;
        return true;
    }

    /** An explicit change the road map follows (archetype, goal, powders, language): built again when there is one. */
    private List<Roadmap.Step> rebuild() {
        if (Roadmap.hasMap()) {
            Roadmap.forceRefresh();
        }
        return Roadmap.cached();
    }

    /** The plan's pieces by name, looked up once per plan. */
    private final Map<String, ItemData> planInfo = new HashMap<>();
    private String planInfoFor = "";

    /**
     * The Plan pill's view: each slot the plan's piece, a tick where you wear it already (by
     * name), purple for the items you chose; the line under the stand counts what you wear.
     */
    private HomeTab.In planIn(Plans.Build plan, List<UpRow> rows, double now, double after, int chosen, int saved, boolean searching) {
        if (!planInfoFor.equals(plan.id() + plan.updated())) {
            planInfo.clear();
            java.util.Set<String> want = new java.util.HashSet<>(plan.items().values());
            GameState.current().allGear().filter(i -> want.contains(i.name())).forEach(i -> planInfo.putIfAbsent(i.name(), i));
            planInfoFor = plan.id() + plan.updated();
        }
        Map<String, String> wornNames = new LinkedHashMap<>();
        for (int i = 0; i < rows.size() && i < Plans.SLOTS.length; i++) {
            ItemStack w = rows.get(i).worn();
            if (!w.isEmpty()) {
                wornNames.put(Plans.SLOTS[i], ItemJson.clean(w.getHoverName().getString()).trim());
            }
        }
        // the plan's rings in the hands that wear them, so a ring worn in the other hand shows there
        Map<String, String> items = new LinkedHashMap<>(plan.items());
        String r1 = items.get("RING1");
        String r2 = items.get("RING2");
        if (r1 != null && r2 != null && !r1.equals(wornNames.get("RING1"))
                && (r2.equals(wornNames.get("RING1")) || r1.equals(wornNames.get("RING2")))) {
            items.put("RING1", r2);
            items.put("RING2", r1);
        }
        boolean[] on = Plans.wornSlots(items, wornNames);
        List<HomeTab.SlotView> slots = new ArrayList<>();
        int worn = 0;
        double left = 0;
        for (int i = 0; i < rows.size(); i++) {
            UpRow r = rows.get(i);
            String name = i < Plans.SLOTS.length ? items.get(Plans.SLOTS[i]) : null;
            ItemData info = name == null ? null : planInfo.get(name);
            if (name == null || info == null) {
                slots.add(new HomeTab.SlotView(i, boxLabel(r.box()), r.type(), r.worn(), null, ItemStack.EMPTY, Double.NaN, null));
                continue;
            }
            boolean locked = plan.locked(name);
            boolean has = on[i];
            worn += has ? 1 : 0;
            HomeTab.Mark mark = has ? locked ? HomeTab.Mark.DONE_CHOSEN : HomeTab.Mark.DONE : locked ? HomeTab.Mark.CHOSEN : HomeTab.Mark.GET;
            List<Component> tip = new ArrayList<>();
            List<Component> lead = new ArrayList<>();
            tip.add(L.c(locked ? "wynnlabs.homev.slot_chosen" : "wynnlabs.homev.slot_planned").withStyle(ChatFormatting.LIGHT_PURPLE));
            if (has) {
                tip.add(L.c("wynnlabs.homev.slot_worn").withStyle(ChatFormatting.GREEN));
            } else {
                // the pill's icon explained first: "Missing · dropped by mobs", then where, when known
                lead.add(L.c("wynnlabs.homev.slot_missing", BuildWindow.sourceText(name)).withStyle(ChatFormatting.GOLD));
                String detail = BuildWindow.sourceDetail(name);
                if (detail != null) {
                    lead.add(Component.literal(detail).withStyle(ChatFormatting.GRAY));
                }
                Prices.Price p = Prices.of(name);
                if (p != null && p.typical() != null && Shopping.tradable(info)) {
                    left += p.typical();
                }
            }
            slots.add(new HomeTab.SlotView(i, boxLabel(r.box()), r.type(), r.worn(), null, icon(info), Double.NaN, tip, mark, info, lead));
        }
        int better = Plans.HINT.showing();
        HomeTab.PlanLine line = new HomeTab.PlanLine(plan.name(), worn, plan.items().size(), left, better,
                plan.level() > GameState.current().level() ? plan.level() : 0);
        return new HomeTab.In(slots, now, after, quests.isEmpty() ? null : quests.get(0), places(), line, chosen,
                saved, searching, road());
    }

    /** The build window's way back into the screen. */
    private final BuildWindow.Host windowHost = new BuildWindow.Host() {
        @Override
        public void tip(int x, int y, int w, int h, List<Component> tip) {
            hits.add(new Hit(x, y, w, h, null, tip, null));
        }

        @Override
        public void item(int x, int y, int w, int h, ItemData info, ItemStack stack, List<Component> extra) {
            // first in the list: an icon lies over its card, and the first hit under the mouse shows
            itemHit(true, x, y, w, h, info, ItemStack.EMPTY, extra);
        }

        @Override
        public ItemStack icon(ItemData info) {
            return RoadmapScreen.this.icon(info);
        }

        @Override
        public void flash(String text) {
            flash = text;
            flashUntil = System.currentTimeMillis() + 2500;
        }

        @Override
        public void applied() {
            planPills.pickPlan();
            flash = L.t("wynnlabs.homev.plan_applied");
            flashUntil = System.currentTimeMillis() + 2500;
        }
    };

    /** The home's way back into the screen: its click and hover areas join this frame's hits. */
    private final HomeTab.Host homeHost = new HomeTab.Host() {
        @Override
        public void area(int x, int y, int w, int h, ItemStack stack, List<Component> tip, String action) {
            hits.add(new Hit(x, y, w, h, stack, tip, action));
        }

        @Override
        public void quest(int x, int y, int w, int h, List<Component> tip, QuestBook.Entry q) {
            hits.add(new Hit(x, y, w, h, null, tip, null, q));
        }

        @Override
        public void item(int x, int y, int w, int h, ItemData info, ItemStack worn, List<Component> extra) {
            itemHit(false, x, y, w, h, info, worn, extra);
        }

        @Override
        public void item(int x, int y, int w, int h, ItemData info, ItemStack worn, List<Component> lead, List<Component> extra) {
            itemHit(false, x, y, w, h, info, worn, lead, extra);
        }

        @Override
        public void compare(int[] rect, HomeTab.SlotView s) {
            if (s.gear() != null && s.gear().options() != null && !s.gear().options().isEmpty()) {
                slotHits.add(new Object[] {rect, s.gear(), s.label(), s.worn()});
            }
        }

        @Override
        public int levelPills(GuiGraphics g, int y, int right, int room) {
            return RoadmapScreen.this.levelPills(g, y, right, room);
        }

        @Override
        public int refresh(GuiGraphics g, int x, int y, int mx, int my, String staleTip) {
            if (Roadmap.isBuilding()) {
                if (steps.isEmpty()) {
                    return 0;                       // the first map: its progress fills the panel instead
                }
                // a new map over the last one: a small bar and Cancel (the last map stays)
                Kit.tealBar(g, x, y + 5, 24, 4, Roadmap.progress(), false);
                hits.add(new Hit(x, y, 24, 14, null, List.of(L.c("wynnlabs.home.refreshing").withStyle(ChatFormatting.GRAY)), null));
                boolean ch = mx >= x + 27 && mx < x + 41 && my >= y && my < y + 14;
                Kit.button(g, x + 27, y, 14, 14, ch ? Kit.HOVER : Kit.REST);
                Art.icon(g, "x", x + 30, y + 3, 8, Kit.buttonText(ch ? Kit.HOVER : Kit.REST));
                hits.add(new Hit(x + 27, y, 14, 14, null, List.of(L.c("wynnlabs.pick.cancel").withStyle(ChatFormatting.WHITE),
                        L.c("wynnlabs.home.build_cancel_tip").withStyle(ChatFormatting.GRAY)), "#qcancelbuild:"));
                return 45;
            }
            // the road map built again now: only ever on a click
            boolean hover = mx >= x && mx < x + 14 && my >= y && my < y + 14;
            Kit.button(g, x, y, 14, 14, hover ? Kit.HOVER : Kit.REST);
            Art.icon(g, "refresh", x + 3, y + 3, 8, Kit.buttonText(hover ? Kit.HOVER : Kit.REST));
            List<Component> tip = new ArrayList<>();
            if (staleTip != null) {
                // out of date where no strip fits: an amber badge on the button's corner
                Kit.seal(g, x + 9, y - 2, 7, 7, 0xFFF0B040);
                tip.add(Component.literal(staleTip).withStyle(ChatFormatting.GOLD));
                tip.add(L.c("wynnlabs.home.stale_click").withStyle(ChatFormatting.GRAY));
            } else {
                tip.add(L.c("wynnlabs.home.refresh_tip").withStyle(ChatFormatting.GRAY));
            }
            hits.add(new Hit(x, y, 14, 14, null, tip, "#qrefresh:"));
            return 18;
        }
    };

    private static final int HUD_H = 56;

    /** The class in a brass medal: the pack's class icon, or the best weapon without the pack. */
    private void classMedal(GuiGraphics g, int x, int y, String cls) {
        Kit.medal(g, x, y);
        boolean drew = cls != null && !"?".equals(cls)
                && WynnTex.draw(g, "wynn/gui/character_select/icon/" + cls.toLowerCase(Locale.ROOT), x + 4, y + 4, 16, 16);
        LocalPlayer player = Minecraft.getInstance().player;
        ItemStack weapon = drew || player == null ? ItemStack.EMPTY : AutoAdvice.bestWeapon();
        if (weapon != null && !weapon.isEmpty()) {
            g.renderItem(weapon, x + 4, y + 4);
        }
    }

    /**
     * The character as a HUD on dark slate: the medal, name, archetype and level; effective
     * health, mana and spell damage as filled bars against the next milestone's build, the
     * experience bar under them; the build score on a gauge; the goal and powder buttons and
     * the nearest bank and Trade Market on the right.
     */
    private void drawHomeHeader(GuiGraphics g, int x, int y, int w) {
        String cls;
        int level;
        int xp;
        try {
            cls = nice(Wyn.classType().name());
            level = Wyn.level();
            xp = Wyn.xp().percentInt();
        } catch (Throwable t) {
            cls = "?";
            level = 0;
            xp = 0;
        }
        Kit.slate(g, x, y, w, HUD_H);
        classMedal(g, x + 6, y + 17, cls);
        // the name line: class, archetype (click: the next), level
        String arch = TreePlanner.chosen();
        int tx = x + 36;
        // the name large and bright, with its shadow: ink() would dull the gold for paper
        float ns = Settings.on("webFont") ? 1.5f : 1f;     // the bitmap font scaled gives uneven strokes
        Component name = Component.literal(cls).withStyle(UiFont::bold).withStyle(st -> UiFont.sized(st, ns));
        g.drawString(font, name, tx, y + 4, Theme.GOLD, true);
        int nx = tx + font.width(name) + 8;
        String archLabel = arch.isEmpty() ? L.t("wynnlabs.settings.archetype_off") : arch;
        Art.icon(g, "sparkles", nx, y + 7, 8, 0xFFAFA9EC);
        g.drawString(font, archLabel, nx + 10, y + 7, 0xFFAFA9EC, true);
        hits.add(new Hit(nx, y + 5, font.width(archLabel) + 10, 12, null, List.of(
                L.c("wynnlabs.home.archetype_tip", arch.isEmpty() ? L.t("wynnlabs.settings.archetype_off") : arch)
                        .withStyle(ChatFormatting.LIGHT_PURPLE),
                L.c("wynnlabs.home.archetype_click").withStyle(ChatFormatting.DARK_GRAY)), "#qarch:"));
        nx += font.width(archLabel) + 18;
        g.drawString(font, "Lv " + level, nx, y + 7, Theme.CREAM_DIM, true);
        // the right side: goal and powder buttons, the nearest bank and Trade Market below
        int chipW = 92;
        int cx = x + w - chipW - 6;
        int gs = goalOpen ? Kit.ON : Kit.REST;
        Kit.button(g, cx, y + 7, chipW, 13, gs);
        woodIconText(g, "target", fit(Setup.goalName(Setup.goal()), chipW - 28), cx + 4, y + 10, gs);
        Art.icon(g, "chevron-down", cx + chipW - 12, y + 10, 8, goalOpen ? Theme.INK_2 : Theme.CREAM_DIM);
        goalAnchor = new int[] {cx, y + 7, chipW, 13};
        hits.add(new Hit(cx, y + 8, chipW, 12, null, goalOpen ? List.of() : List.of(
                L.c("wynnlabs.home.goal_tip", Setup.goalName(Setup.goal())).withStyle(ChatFormatting.LIGHT_PURPLE),
                L.c("wynnlabs.home.goal_click").withStyle(ChatFormatting.DARK_GRAY)), "#qgoal:"));
        boolean powdered = Rules.sub("model", "assumePowders", 1) > 0;
        String powLabel = powdered ? L.t("wynnlabs.home.powder_on") : L.t("wynnlabs.home.powder_off");
        Kit.button(g, cx, y + 23, chipW, 13, powdered ? Kit.ON : Kit.REST);
        woodIconText(g, "diamond", fit(powLabel, chipW - 18), cx + 4, y + 26, powdered ? Kit.ON : Kit.REST);
        hits.add(new Hit(cx, y + 24, chipW, 12, null, List.of(
                L.c(powdered ? "wynnlabs.home.powder_on_tip" : "wynnlabs.home.powder_off_tip").withStyle(ChatFormatting.LIGHT_PURPLE),
                L.c("wynnlabs.home.powder_click").withStyle(ChatFormatting.DARK_GRAY)), "#qpowder:"));
        drawNearest(g, cx, y + 40, chipW);
        // the gauge: the build score against the next milestone's
        Build.Plan plan = Build.plan();
        Damage.Result now = plan == null ? null : plan.current();
        List<Integer> shown = shownSteps();
        Damage.Result target = null;
        int targetLevel = 0;
        if (!shown.isEmpty() && steps.get(shown.get(0)).set() != null) {
            target = steps.get(shown.get(0)).set().result();
            targetLevel = steps.get(shown.get(0)).level();
        }
        int gx = cx - 8 - 58;
        if (now != null) {
            double frac = target == null || target.objective() <= 0 ? 1 : now.objective() / target.objective();
            int gcx = gx + 29;
            int gcy = y + 50;
            Kit.gauge(g, gcx, gcy, 29, 5, Math.max(0, Math.min(1, frac)), 0xFF525C66, Theme.GOLD, Theme.GOLD_DEEP);
            // inside the ring: the score where the chord is wide enough for five digits, the label under it
            String score = String.valueOf(Math.round(now.objective()));
            Component bold = Component.literal(score).withStyle(UiFont::bold);
            g.drawString(font, bold, gcx - font.width(bold) / 2, gcy - 17, Theme.GOLD, true);
            String lbl = L.t("wynnlabs.home.stat_build");
            g.drawString(font, lbl, gcx - font.width(lbl) / 2, gcy - 7, Theme.CREAM_DIM, false);
            hits.add(new Hit(gx, gcy - 28, 58, 30, null, vsTip(L.t("wynnlabs.home.stat_build"), score, frac, targetLevel), null));
        }
        // the bars: effective health, mana (casts in a row), spell damage per cast
        int bx = tx;
        // a large page's bars stop at 300px: a longer track says no more
        int bw = layout.large() ? Math.min(300, gx - 10 - bx) : gx - 10 - bx;
        if (now != null && bw > 60) {
            // the game's own maximum health when it is known, the model's otherwise
            double health = Wyn.health().map(c -> (double) c.max()).filter(m -> m > 0).orElse(now.health());
            Object[][] bars = {
                {String.valueOf(Math.round(health)), L.t("wynnlabs.home.stat_health") + " · " + L.t("wynnlabs.home.stat_ehp") + " "
                    + Math.round(now.ehp()), health, target == null ? 0 : target.health(),
                    0xFF4A1B0C, 0xFFE96033, 0xFFC6522C, 0xFF8E3B1F, "heart"},
                {L.n("wynnlabs.home.hud_casts", Math.round(now.casts())), L.t("wynnlabs.home.hud_casts_tip"), now.casts(),
                    target == null ? 0 : target.casts(), 0xFF042C53, 0xFF388EE4, 0xFF3079C2, 0xFF22578B, "droplet"},
                {String.valueOf(Math.round(now.spellDps())), L.t("wynnlabs.home.stat_spell"), now.spellDps(), target == null ? 0 : target.spellDps(),
                    0xFF26215C, 0xFF867EE9, 0xFF726BC6, 0xFF524D8E, "wand"}};
            int by = y + 18;
            for (Object[] b : bars) {
                double val = (Double) b[2];
                double ref = (Double) b[3];
                double frac = ref <= 0 ? 1 : val / ref;
                g.fill(bx, by, bx + bw, by + 10, (Integer) b[4]);
                int fw = (int) Math.round(bw * Math.max(0, Math.min(1, frac)));
                if (fw > 0) {
                    g.fill(bx, by, bx + fw, by + 3, (Integer) b[5]);
                    g.fill(bx, by + 3, bx + fw, by + 8, (Integer) b[6]);
                    g.fill(bx, by + 8, bx + fw, by + 10, (Integer) b[7]);
                }
                // an icon of the bar's height at the left, the value after it
                Art.icon(g, (String) b[8], bx + 2, by + 1, 8, 0xFFFFFFFF);
                g.drawString(font, fit((String) b[0], bw - 16), bx + 13, by + 1, 0xFFFFFFFF, true);
                // the milestone's value at the track's end; white with a shadow where the fill reaches it
                if (ref > 0) {
                    String rv = String.valueOf(Math.round(ref));
                    int rx = bx + bw - font.width(rv) - 3;
                    if (rx > bx + 13 + font.width((String) b[0]) + 6) {
                        boolean over = bx + fw >= rx - 2;
                        g.drawString(font, rv, rx, by + 1, over ? 0xFFFFFFFF : Theme.CREAM_DIM, over);
                    }
                }
                hits.add(new Hit(bx, by, bw, 10, null, vsTip((String) b[1], (String) b[0], frac, targetLevel), null));
                by += 11;
            }
        }
        // experience: the teal bar with its brass rim and notches under the band, as before
        int ey = y + HUD_H + 5;
        String pct = pct(xp) + " XP";
        // the % at the bar's end, on its line: the home's page starts right under it
        int xw = w - font.width(pct) - 6;
        Kit.tealBar(g, x, ey, xw, 4, Math.max(0, Math.min(100, xp)) / 100.0, true);
        Kit.notches(g, x, ey, xw, 4);
        // level with the bar, 1px in from the page's edge
        ink(g, pct, x + w - font.width(pct) - 1, ey - 2, FAINT);
    }

    /** A bar's or the gauge's tooltip: the value, and how it compares with the next milestone's build. */
    private List<Component> vsTip(String label, String value, double frac, int targetLevel) {
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(label + " · " + value).withStyle(ChatFormatting.WHITE));
        if (targetLevel > 0) {
            tip.add(L.c("wynnlabs.home.hud_vs", Math.round(frac * 100), targetLevel).withStyle(ChatFormatting.GRAY));
        } else {
            tip.add(L.c("wynnlabs.home.hud_vs_none").withStyle(ChatFormatting.DARK_GRAY));
        }
        return tip;
    }

    /**
     * The nearest bank (wiki's Bank page) and Trade Market (Wynntils' service list) as two small
     * seals; one click puts a waypoint there (compass, beacon, map pin).
     */
    private void drawNearest(GuiGraphics g, int x, int y, int w) {
        LocalPlayer me = Minecraft.getInstance().player;
        if (me == null) {
            return;
        }
        String[][] kinds = {{"building-bank", "BANKS"}, {"shopping-cart", "TMS"}};
        String bankData = "-866 67 -1590 Ragni|-141 72 -1607 Alekin Village|476 67 -1564 Detlas|855 71 -1623 Ternaves|90 41 -2205 Nemract|-293 44 -1151 Tempo Town|1020 99 -1956 Almuj|1310 33 -1310 Rymek|21 66 -3202 Selchar|793 59 -3342 Nodguj|1142 43 -3114 Craftmas Island|-252 36 -4025 Isle of Fiction|106 75 -828 Nesaak|-217 30 -324 Lusuco|-701 31 -3085 Pirate Town|-888 77 -918 Troms|-664 67 -1036 Temple of the Legends|981 73 -685 Lutho|-2000 78 -4444 Llevigar|-1742 68 -5533 Olux|-1022 46 -5316 Bucie|-1448 44 -4725 Gelibord|-884 122 -4898 The Forgery|-871 123 -4918 The Forgery|-435 46 -4929 Cinfras|-315 64 -4975 Guild Hall|-296 103 -4403 Aldorei Town|167 7 -5300 Thanos|224 29 -5242 Thanos|823 74 -5095 Thesead|925 116 -5480 Eltom|468 109 -4768 Bantisu Air Temple|1018 19 -5134 Rodoroc|1473 45 -5293 Maex|743 164 -4434 Kandon-Beda|1065 108 -4548 Ahmsord|-1593 68 -2912 Corkus City|-1167 65 -2403 Legendary Island|-1906 71 -1649 Fort Torann|-2101 24 -999 Espren|-1721 27 -555 Timasca|-1300 77 -706 Aldwell|-1375 109 -1238 Hyloch|-1554 123 -807 Aelumia Safe Zone|-728 100 -6452 Nexus of Light";
        String tmData = "-2100 26 -1020 Espren|-2024 78 -4479 Llevigar|-1763 67 -5548 Olux|-1718 27 -575 Timasca|-1624 70 -2933 Corkus City|-1538 131 -786 Aelumia Safe Zone|-1326 108 -1249 Hyloch|-1286 80 -671 Aldwell|-874 81 -964 Troms|-456 49 -4949 Cinfras|105 67 -3194 Selchar|502 69 -1570 Detlas|737 165 -4409 Kandon-Beda|816 77 -5079 Thesead|997 76 -713 Lutho|1001 114 -4566 Ahmsord|1058 19 -5121 Rodoroc";
        int px = x;
        int each = (w - 4) / 2;
        for (String[] k : kinds) {
            String bestLine = null;
            double bestD = Double.MAX_VALUE;
            for (String line : (k[1].equals("BANKS") ? bankData : tmData).split("\\|")) {
                String[] f = line.split(" ", 4);
                double d = Math.hypot(Integer.parseInt(f[0]) - me.getX(), Integer.parseInt(f[2]) - me.getZ());
                if (d < bestD) {
                    bestD = d;
                    bestLine = line;
                }
            }
            if (bestLine == null) {
                continue;
            }
            String[] f = bestLine.split(" ", 4);
            String dist = bestD >= 1000 ? Math.round(bestD / 100) / 10.0 + "k" : String.valueOf(Math.round(bestD));
            String label = dist + "m";
            Kit.pillLine(g, px, y, each, 12, 0xFF9EB0A0);
            Art.icon(g, k[0], px + 4, y + 2, 8, Theme.NEAR_TEXT);
            g.drawString(font, fit(label, each - 16), px + 14, y + 2, Theme.NEAR_TEXT, true);
            String what = (k[1].equals("BANKS") ? L.t("wynnlabs.home.bank") : "Trade Market") + " · " + f[3];
            hits.add(new Hit(px, y, each, 12, null, List.of(
                    L.c("wynnlabs.home.nearest", what).withStyle(ChatFormatting.GREEN),
                    L.c("wynnlabs.home.coords_blocks", f[0] + " " + f[1] + " " + f[2], Math.round(bestD)).withStyle(ChatFormatting.GRAY),
                    L.c("wynnlabs.home.click_mark").withStyle(ChatFormatting.DARK_GRAY)),
                    "#qnear:" + f[0] + " " + f[1] + " " + f[2] + " " + what));
            px += each + 4;
        }
    }

    /** An item's bright Minecraft tier colour, for the ring inside its slot (0 when it has none). */
    private static int tierRing(ItemData info) {
        try {
            Integer c = info.tier().colour().getColor();
            return c == null || (c & 0xFFFFFF) == 0xFFFFFF ? 0 : 0xFF000000 | c;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** An edit box for paper: no frame of its own (Kit.field draws one), dark ink without a shadow. */
    private static void paperField(net.minecraft.client.gui.components.EditBox box) {
        box.setBordered(false);
        box.setTextColor(Theme.INK);
        box.setTextShadow(false);
    }

    /**
     * A text box on the parchment: the field, the box's text inside it, and its hint (a
     * language key) in faint ink while it is empty and not being typed in. The game's own hint
     * is not used: it is drawn with a shadow, which smears on paper.
     */
    private void textBox(GuiGraphics g, net.minecraft.client.gui.components.EditBox box, int x, int y, int w,
            String hint, int mx, int my, float pt) {
        Kit.field(g, x, y, w, 12, box.isFocused());
        box.setX(x + 4);
        box.setY(y + 2);
        box.setWidth(w - 8);
        box.render(g, mx, my, pt);
        if (box.getValue().isEmpty() && !box.isFocused()) {
            ink(g, fit(L.t(hint), w - 8), x + 4, y + 2, Theme.INK_3, false);
        }
    }

    /** "/compass at x y z" for a grind place line, or null. */
    static String compassFor(String raw) {
        for (Grind.Spot s : Grind.spots()) {
            // the place line may carry the kind's id or its shown name
            if (raw.contains(s.name() + " " + Grind.kindName(s)) || raw.contains(s.name() + " " + s.kind())) {
                return "/compass at " + s.x() + " " + s.y() + " " + s.z();
            }
        }
        return null;
    }

    /** The first road map of a character is being built: a bar for how far it is. */

    /** How many milestone buttons the level row shows this frame (LEVEL_WINDOW where they fit). */
    private int levelWindow = LEVEL_WINDOW;

    /**
     * The level buttons (şimdi · ‹ · Lv 60 · 70 … · ›) ending at {@code right}, their text on
     * line {@code y}; each names the level its whole set is wearable at, the window follows the
     * selected one and shows as many milestones as {@code room} takes. Returns their left edge.
     */
    private int levelPills(GuiGraphics g, int y, int right, int room) {
        List<Integer> shown = shownSteps();
        int pos = shown.indexOf(selected);
        if (selected > 0 && pos < 0 && !shown.isEmpty()) {
            // a hidden milestone: the next shown one has a set as early and better
            int to = shown.get(shown.size() - 1);
            for (int i : shown) {
                if (i >= selected) {
                    to = i;
                    break;
                }
            }
            selected = to;
            pos = shown.indexOf(selected);
        }
        levelWindow = LEVEL_WINDOW;
        // with a plan, its pill first: "Plan · Now · Lv 68 · 80 …"
        Plans.Build plan = Plans.current();
        boolean planOn = planPills.planSelected(plan);
        String planText = L.t("wynnlabs.homev.plan_pill");
        int planW = plan == null ? 0 : font.width(planText) + 12 + PLAN_X;
        room -= plan == null ? 0 : planW + 3;
        List<int[]> order = pillOrder(shown, pos, levelWindow);
        while (levelWindow > 1 && pillsWidth(order, shown) > room) {
            order = pillOrder(shown, pos, --levelWindow);
        }
        int firstShown = levelOff < shown.size() ? shown.get(levelOff) : -9;
        int bx = right;
        for (int k = order.size() - 1; k >= 0; k--) {
            int i = order.get(k)[0];
            String t = pillText(i, firstShown);
            int tw = i < 0 ? 16 : font.width(t) + 12;
            bx -= tw;
            boolean on = !planOn && i == selected;
            Kit.ledger(g, bx, y - 3, tw, 14, on);
            if (i < 0) {
                Art.icon(g, i == -1 ? "chevron-left" : "chevron-right", bx + 4, y, 8, Kit.buttonText(Kit.REST));
            } else {
                onWood(g, t, bx + 6, y, on ? Kit.ON : Kit.REST);
            }
            levelRows.add(new int[] {bx, y - 3, tw, 14, i});
            if (i > 0) {
                hits.add(new Hit(bx, y - 3, tw, 14, null, List.of(
                        L.c("wynnlabs.home.levels_tip", wearAt(steps.get(i)), steps.get(i).level())
                                .withStyle(ChatFormatting.GRAY)), null));
            }
            bx -= 3;
        }
        if (plan != null) {
            bx -= planW;
            // purple when selected, like the chosen items on the panel
            if (planOn) {
                Kit.ledgerPrimary(g, bx, y - 3, planW, 14);
            } else {
                Kit.ledger(g, bx, y - 3, planW, 14, false);
            }
            if (planOn) {
                g.drawString(font, planText, bx + 6, y, Theme.CREAM, false);
            } else {
                onWood(g, planText, bx + 6, y, Kit.REST);
            }
            levelRows.add(new int[] {bx, y - 3, planW - PLAN_X, 14, PLAN_PILL});
            hits.add(new Hit(bx, y - 3, planW - PLAN_X, 14, null, List.of(Component.literal(plan.name()).withStyle(ChatFormatting.LIGHT_PURPLE),
                    L.c("wynnlabs.homev.plan_pill_tip").withStyle(ChatFormatting.GRAY)), null));
            // its ✕ at the right end: the plan removed (Undo on the build line for a few seconds)
            int xx = bx + planW - PLAN_X - 1;
            boolean xh = frameMx >= xx && frameMx < xx + 12 && frameMy >= y - 2 && frameMy < y + 10;
            Kit.button(g, xx, y - 2, 12, 12, xh ? Kit.HOVER : Kit.REST);
            Art.icon(g, "x", xx + 2, y, 8, Kit.buttonText(xh ? Kit.HOVER : Kit.REST));
            List<Component> xtip = new ArrayList<>();
            xtip.add(L.c("wynnlabs.homev.plan_remove").withStyle(ChatFormatting.WHITE));
            xtip.add(L.c("wynnlabs.homev.plan_remove_tip").withStyle(ChatFormatting.GRAY));
            if (!Plans.isSaved(plan)) {
                xtip.add(L.c("wynnlabs.homev.plan_unsaved").withStyle(ChatFormatting.GOLD));
            }
            hits.add(new Hit(xx, y - 2, 12, 12, null, xtip, "#qplanremove:"));
            bx -= 3;
        }
        return bx + 3;
    }

    /** The Plan pill's place in levelRows (the milestones are 0 and up, ‹ and › -1 and -2). */
    private static final int PLAN_PILL = -3;
    /** The room the Plan pill's ✕ takes at its right end. */
    private static final int PLAN_X = 13;
    /** Whether the Plan pill or a level pill is selected (kept across menu openings). */
    private static final Plans.Pills planPills = new Plans.Pills();

    /** The level row's buttons for a window of {@code window} milestones: {step index, or -1 ‹ / -2 ›}. */
    private List<int[]> pillOrder(List<Integer> shown, int pos, int window) {
        if (pos >= 0 && pos < levelOff) {
            levelOff = pos;
        } else if (pos >= levelOff + window) {
            levelOff = pos - window + 1;
        }
        levelOff = Math.max(0, Math.min(levelOff, Math.max(0, shown.size() - window)));
        List<int[]> order = new ArrayList<>();
        order.add(new int[] {0});
        if (levelOff > 0) {
            order.add(new int[] {-1});
        }
        for (int k = levelOff; k < shown.size() && k < levelOff + window; k++) {
            order.add(new int[] {shown.get(k)});
        }
        if (levelOff + window < shown.size()) {
            order.add(new int[] {-2});
        }
        return order;
    }

    private String pillText(int i, int firstShown) {
        return i < 0 ? "" : i == 0 ? L.t("wynnlabs.home.now_short") : (i == firstShown ? "Lv " : "") + wearAt(steps.get(i));
    }

    private int pillsWidth(List<int[]> order, List<Integer> shown) {
        int firstShown = levelOff < shown.size() ? shown.get(levelOff) : -9;
        int n = 0;
        for (int[] o : order) {
            n += (o[0] < 0 ? 16 : font.width(pillText(o[0], firstShown)) + 12) + 3;
        }
        return n - 3;
    }

    /** The full story of an upgrade, for its hover. */
    /**
     * The gain a row shows: what the selected milestone's build gains over what you wear now in
     * that slot (Roadmap.Step.vsWorn), not just the step over the previous milestone's pick.
     */
    private double rowGain(Roadmap.Gear u) {
        if (u == null || Double.isNaN(u.gain()) || steps.isEmpty() || Roadmap.fromBag(u)) {
            return u == null ? Double.NaN : u.gain();
        }
        Roadmap.Step s = steps.get(Math.max(0, Math.min(selected, steps.size() - 1)));
        return s.vsWorn().getOrDefault(u.slot(), u.gain());
    }

    private List<Component> gearTip(Roadmap.Gear u) {
        List<Component> tip = new ArrayList<>();
        ChatFormatting tc = u.info().tier().colour();
        tip.add(Component.literal(u.info().name()).withStyle(tc).withStyle(UiFont::bold));
        tip.add(L.c("wynnlabs.home.tip_from", u.source()).withStyle(ChatFormatting.DARK_AQUA));
        String also = Roadmap.fromBag(u) ? null : Shopping.alsoFrom(u.info());   // the Trade Market first, the world source under it
        if (also != null && !also.equalsIgnoreCase(u.source())) {
            tip.add(Component.literal("   " + L.t("wynnlabs.homev.source_also", L.cap(also))).withStyle(ChatFormatting.DARK_GRAY));
        }
        Object[] pw = Damage.powderHint(u.info());
        if (pw != null) {
            String[] roman = {"I", "II", "III", "IV", "V", "VI", "VII"};
            String el = switch ((String) pw[0]) {
                case "thunder" -> L.t("wynnlabs.damage.el_thunder");
                case "water" -> L.t("wynnlabs.damage.el_water");
                case "fire" -> L.t("wynnlabs.damage.el_fire");
                case "air" -> L.t("wynnlabs.damage.el_air");
                default -> L.t("wynnlabs.damage.el_earth");
            };
            tip.add(L.c("wynnlabs.home.tip_powder", pw[1], el,
                    roman[Math.max(0, Math.min(6, (int) pw[2] - 1))]).withStyle(ChatFormatting.AQUA));
        }
        if (u.price() != null) {
            tip.add(L.c("wynnlabs.home.tip_past_price", u.price()).withStyle(ChatFormatting.GOLD));
            Prices.Price pp = Prices.of(u.info().name());
            String doubt = pp == null ? null : pp.doubt();
            tip.add(Component.literal(doubt != null ? L.t("wynnlabs.home.tip_doubt", doubt)
                    : L.t("wynnlabs.home.tip_seen")).withStyle(doubt != null ? ChatFormatting.RED : ChatFormatting.DARK_GRAY));
        }
        double vsWorn = rowGain(u);
        tip.add(Double.isNaN(vsWorn)
                ? L.c("wynnlabs.home.tip_target").withStyle(ChatFormatting.AQUA)
                : vsWorn < 0 ? L.c("wynnlabs.home.tip_loss", Math.round(-vsWorn * 10) / 10.0).withStyle(ChatFormatting.RED)
                : L.c("wynnlabs.home.tip_gain", Math.round(vsWorn * 10) / 10.0).withStyle(ChatFormatting.GREEN));
        if (!Double.isNaN(u.gain()) && Math.abs(vsWorn - u.gain()) >= 0.5) {
            // at a later milestone the pick also builds on the picks before it
            tip.add(L.c("wynnlabs.home.tip_step", Math.round(u.gain() * 10) / 10.0).withStyle(ChatFormatting.DARK_GRAY));
        }
        // why this pick: what changes against what you wear, biggest first (else the step's own reasons)
        List<String> why = steps.isEmpty() ? null
                : steps.get(Math.max(0, Math.min(selected, steps.size() - 1))).whyVsWorn().get(u.slot());
        if (why == null || why.isEmpty()) {
            why = u.detail();
        }
        if (why != null && !why.isEmpty()) {
            tip.add(L.c("wynnlabs.home.tip_why").withStyle(ChatFormatting.GOLD).withStyle(UiFont::bold));
            for (String d : why) {
                tip.add(Component.literal("  • " + d).withStyle(ChatFormatting.AQUA));
            }
        }
        // what it asks for: its level and skill points
        List<String> needs = new ArrayList<>();
        needs.add("Lv " + u.info().requirements().level());
        for (var r : u.info().requirements().skills()) {
            if (r.points() > 0) {
                needs.add(r.points() + " " + r.skill().name().substring(0, 3));
            }
        }
        tip.add(L.c("wynnlabs.home.tip_needs", String.join(" · ", needs)).withStyle(ChatFormatting.GRAY));
        tip.add(Component.literal(Shopping.tradable(u.info()) ? L.t("wynnlabs.home.tip_tradable") : L.t("wynnlabs.home.tip_untradable"))
                .withStyle(ChatFormatting.GRAY));
        Integer seenAt = Rules.communityLevel(u.info().name());
        if (seenAt != null) {
            tip.add(L.c("wynnlabs.home.tip_community", seenAt)
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        String caution = Cautions.line(u.info());
        if (caution != null) {
            for (String l : Advice.wrap(caution, 46)) {
                tip.add(Component.literal(l).withStyle(ChatFormatting.RED));
            }
        }
        boolean panel = u.options() != null && !u.options().isEmpty();
        tip.add(Component.literal(panel ? L.t("wynnlabs.home.tip_click_compare") : L.t("wynnlabs.screen.click_copy_name")).withStyle(ChatFormatting.DARK_GRAY));
        return tip;
    }

    private int weakerRing(LocalPlayer player) {
        if (player == null) {
            return 0;
        }
        List<ItemStack> rings = AutoAdvice.worn(Slot.RING);
        if (rings.size() < 2) {
            return 0;
        }
        double a = AutoAdvice.scoreAny(rings.get(0));
        double b = AutoAdvice.scoreAny(rings.get(1));
        a = Double.isNaN(a) ? -1 : a;
        b = Double.isNaN(b) ? -1 : b;
        return b < a ? 1 : 0;
    }

    // ---- comparison panel (a slot's options) ----

    private void drawDetail(GuiGraphics g, int x, int y, int w, int mx, int my) {
        List<Roadmap.Option> opts = detail.options();
        ink(g, Component.literal(detailLabel).withStyle(ChatFormatting.LIGHT_PURPLE).withStyle(UiFont::bold)
                .append(L.c("wynnlabs.home.detail_sub").withStyle(ChatFormatting.DARK_GRAY)), x, y,
                0xFFFFFFFF);
        String back = L.t("wynnlabs.home.back");
        int bw = font.width(back) + 24;
        boolean bh = mx >= x + w - bw && mx < x + w && my >= y - 2 && my < y + 11;
        Kit.button(g, x + w - bw, y - 2, bw, 13, bh ? Kit.HOVER : Kit.REST);
        woodIconText(g, "chevron-left", back, x + w - bw + 7, y, bh ? Kit.HOVER : Kit.REST);
        backRect = new int[] {x + w - bw, y - 2, bw, 13};

        // what you wear now
        int ry = y + 14;
        Kit.card(g, x, ry, w, 20, 0);
        Kit.slot(g, x + 2, ry + 1, 18, 0);
        if (!detailWorn.isEmpty()) {
            g.renderItem(detailWorn, x + 3, ry + 2);
            hits.add(new Hit(x + 3, ry + 2, 16, 16, detailWorn, null, null));
        }
        String wn = detailWorn.isEmpty() ? L.cap(L.t("wynnlabs.screen.empty")) : ItemJson.clean(detailWorn.getHoverName().getString());
        String nowLabel = L.t("wynnlabs.home.card_now");
        ink(g, nowLabel, x + 24, ry + 6, FAINT);
        ink(g, Component.literal(wn).withStyle(ChatFormatting.WHITE).withStyle(UiFont::bold),
                x + 24 + font.width(nowLabel) + 6, ry + 6, Theme.INK);
        if (!opts.isEmpty()) {
            Damage.Result f = opts.get(0).from();
            String now = L.t("wynnlabs.home.detail_now", Math.round(f.spellDps()), Math.round(f.ehp()), signed(f.speed()));
            ink(g, now, x + w - font.width(now) - 5, ry + 6, MUTED);
        }

        int cy = ry + 25;
        for (int i = 0; i < opts.size() && i < 3; i++) {
            drawCard(g, opts.get(i), i, x, cy, w, mx, my);
            cy += CARD_H;
        }
        if (opts.size() < 3) {
            ink(g, L.c("wynnlabs.home.detail_none")
                    .withStyle(ChatFormatting.DARK_GRAY), x, cy + 2, 0xFFFFFFFF);
        }
    }

    /**
     * One option in a slot's comparison, as a card: a big slot on the left; the name, where it
     * is had and its tags; then only what changes (▲ gains, ▼ losses: old → new and the share),
     * two to a row, and one line naming what stays the same; on the right the build's total as
     * a large seal and a button that copies the name (for the Trade Market search).
     */
    private void drawCard(GuiGraphics g, Roadmap.Option o, int rank, int x, int y, int w, int mx, int my) {
        int h = CARD_H - 8;                     // 8px between cards for the shadow
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        card(g, x, y, w, h, hover, rank == 0 ? GOOD : 0);
        ItemStack icon = icon(o.info());
        int sy = y + (h - 40) / 2;
        Kit.slot(g, x + 4, sy, 40, tierRing(o.info()));
        bigItem(g, icon, x + 8, sy + 4);
        hits.add(new Hit(x + 8, sy + 4, 32, 32, icon, null, null));
        cardHits.add(new Object[] {new int[] {x, y, w, h}, o.info()});

        // right: BUILD, the total as a seal, the copy button
        int rw = 64;
        int rx = x + w - rw - 6;
        String build = L.t("wynnlabs.home.card_build");
        ink(g, build, rx + rw - font.width(build), y + 8, FAINT);
        String total = (o.gain() >= 0 ? "+" : "−") + pct(Math.round(Math.abs(o.gain())));
        Kit.seal(g, rx + 4, y + 19, rw - 4, 18, o.gain() >= 0 ? (rank == 0 ? Theme.SEAL_3 : Theme.SEAL_2) : Theme.BAD);
        g.drawString(font, total, rx + 4 + (rw - 4 - font.width(total)) / 2, y + 24, Theme.SEAL_TEXT, true);
        String copy = L.t("wynnlabs.home.card_copy");
        int cbw = font.width(copy) + 12;
        int cstate = hover ? Kit.ON : Kit.REST;
        Kit.button(g, rx + rw - cbw, y + h - 21, cbw, 14, cstate);
        onWood(g, copy, rx + rw - cbw + 6, y + h - 18, cstate);

        // middle: name and level, where it is had, the tags
        int tx = x + 52;
        int tw = rx - tx - 8;
        ChatFormatting tc = o.info().tier().colour();
        String where = o.price() != null ? o.price()
                : Shopping.tradable(o.info()) ? L.cap(o.source()) : L.t("wynnlabs.home.card_untradable", L.cap(o.source()));
        Component name = Component.literal((rank + 1) + ". ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(o.info().name()).withStyle(tc).withStyle(UiFont::bold))
                .append(Component.literal("  Lv. " + o.info().requirements().level()).withStyle(ChatFormatting.GRAY));
        ink(g, name, tx, y + 8, Theme.INK);
        List<String[]> chips = new ArrayList<>();
        int lvNow = Wyn.level();
        if (o.info().requirements().level() > lvNow) {
            chips.add(new String[] {L.t("wynnlabs.home.chip_wear_at", o.info().requirements().level()), "a"});
        }
        Integer seen = Rules.communityLevel(o.info().name());
        if (seen != null) {
            chips.add(new String[] {L.t("wynnlabs.home.chip_community", seen), "p"});
        }
        if (o.cautions().isEmpty()) {
            chips.add(new String[] {L.t("wynnlabs.home.chip_no_cons"), "t"});
        }
        for (String c : o.cautions()) {
            chips.add(new String[] {c, "r"});
        }
        chips.add(o.skills().isEmpty() ? new String[] {L.t("wynnlabs.home.chip_no_points"), "t"}
                : new String[] {L.t("wynnlabs.home.chip_points", o.skills()) + (o.respec() ? L.t("wynnlabs.home.chip_respec") : ""), "a"});
        // the tags follow the name on its line, as far as they fit
        int cx = tx + font.width(name) + 8;
        for (String[] c : chips) {
            int bg = c[1].equals("r") ? TAG_RED : c[1].equals("a") ? TAG_AMBER
                    : c[1].equals("p") ? TAG_PURPLE : TAG_GREEN;
            int fg = c[1].equals("r") ? Theme.BAD : c[1].equals("a") ? Theme.WARN
                    : c[1].equals("p") ? Theme.TIER_MYTHIC : Theme.GOOD;
            int cw = font.width(c[0]) + 6;
            if (cx + cw > tx + tw) {
                break;
            }
            cx += chip(g, cx, y + 8, c[0], bg, fg) + 3;
        }
        ink(g, fit(where, tw), tx, y + 20, MUTED);

        // what changes: gains first (largest first), then losses
        double[] p = o.factorPct();
        Damage.Result a = o.from();
        Damage.Result b = o.to();
        String[] sub = {
            Math.round(a.spellDps()) + "→" + Math.round(b.spellDps()),
            Math.round(a.ehp()) + "→" + Math.round(b.ehp()),
            String.format(Locale.ROOT, "%.1f→%.1f", a.casts(), b.casts()),
            signed(a.speed()) + "%→" + signed(b.speed()) + "%",
            Math.round(a.xp()) + "%→" + Math.round(b.xp()) + "%",
            b.factors().length > 5 ? "×" + String.format(Locale.ROOT, "%.2f", b.factors()[5]) : ""};
        List<Integer> changed = new ArrayList<>();
        List<String> same = new ArrayList<>();
        for (int i = 0; i < 6 && i < p.length; i++) {
            if (Math.abs(p[i]) < 0.5) {
                same.add(Damage.factor(i));
            } else {
                changed.add(i);
            }
        }
        changed.sort((i, j) -> Double.compare(p[j], p[i]));
        int colW = (tw - 8) / 2;
        for (int k = 0; k < changed.size() && k < 4; k++) {
            int i = changed.get(k);
            boolean up = p[i] > 0;
            int dx = tx + (k % 2) * (colW + 8);
            int dy = y + 34 + (k / 2) * 14;
            Art.box(g, dx, dy - 3, colW, 13, 3, up ? 0xFFD3DFB0 : 0xFFEDCDB8, 0);
            Art.icon(g, up ? "arrow-up" : "arrow-down", dx + 3, dy, 8, Theme.paperRgb(up ? Theme.GOOD : Theme.BAD));
            String label = fit(Damage.factor(i), 58);
            ink(g, label, dx + 14, dy, Theme.INK);
            String v = (up ? "+" : "−") + pct(Math.round(Math.abs(p[i])));
            ink(g, v, dx + colW - font.width(v) - 5, dy, up ? Theme.GOOD : Theme.BAD);
            int vx = dx + 76;
            ink(g, fit(sub[i], dx + colW - font.width(v) - 8 - vx), vx, dy, MUTED);
        }
        if (changed.size() > 4) {
            same.add(0, L.n("wynnlabs.home.card_more", changed.size() - 4));
        }
        if (!same.isEmpty() && changed.size() <= 2) {
            ink(g, fit(L.t("wynnlabs.home.card_same", String.join(", ", same)), tw), tx, y + 51, FAINT);
        }
    }

    private static String signed(double v) {
        long n = Math.round(v);
        return n > 0 ? "+" + n : String.valueOf(n);
    }

    // ---- today tab (Bugün) ----

    /**
     * Today: the reset countdown and this session's XP in the header, a card per character
     * (level, when played, the daily tasks, the daily reward), a framed to-do list with item icons
     * and one-click actions, and the last session in four tiles.
     */
    private void drawToday(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        // header chips, on the title line
        long m = Today.minutesToReset();
        double sr = XpMeter.sessionRate();
        String session = L.t("wynnlabs.today.session_chip", QuestAdvisor.fmt(XpMeter.sessionXp()))
                + (sr > 0 ? L.t("wynnlabs.today.session_rate", QuestAdvisor.fmt(Math.round(sr))) : "");
        String reset = L.t("wynnlabs.today.reset_chip", m / 60, m % 60);
        // the pills' own widths (pillText's), so the last one ends inside the page like the title's rule
        int sw = font.width(session) + 12;
        int rw = font.width(reset) + 22;
        int hy = y - 21;
        if (System.currentTimeMillis() - daysAt > 2000) {     // files: not every frame
            days = Today.all(Minecraft.getInstance());
            daysAt = System.currentTimeMillis();
        }
        String today = Today.utcDate();
        // the daily reward is once per account per day: the newest reading from any character today
        Boolean reward = null;
        long readAt = 0;
        for (Today.Day d : days) {
            if (today.equals(d.date()) && d.reward() != null && d.seen() >= readAt) {
                reward = d.reward();
                readAt = d.seen();
            }
        }
        int px = x + w - 3 - sw;
        pillText(g, px, hy, session, 0xFF97C459, 0xFF1C2A1E);
        px -= 6 + rw;
        pillText(g, px, hy, "clock", reset, 0xFFB4B2A9, 0xFF1A1A23);
        if (reward != null) {
            String rt = reward ? L.t("wynnlabs.today.reward_ready_chip") : L.t("wynnlabs.today.reward_claimed_chip");
            int tw = font.width(rt) + 22;
            px -= 6 + tw;
            pillText(g, px, hy, reward ? "sparkles" : "check", rt, reward ? 0xFF7EE0BC : 0xFF9C9A92, reward ? 0xFF173A2C : 0xFF1A1A23);
        }
        int ry = y;

        // one card per character, the one you play first
        int perRow = Math.max(1, Math.min(4, days.size()));
        int gap = 6;
        int cw = (w - (perRow - 1) * gap) / perRow;
        // compact: a shorter card (name and daily, no "played" line) so the rest of the page fits
        boolean small = layout.compact();
        int ch = small ? 30 : 44;
        int iconS = small ? 20 : 28;
        for (int i = 0; i < days.size(); i++) {
            Today.Day d = days.get(i);
            boolean me = i == 0;
            boolean stale = !today.equals(d.date());
            int cx = x + (i % perRow) * (cw + gap);
            int cy = ry + (i / perRow) * (ch + gap);
            Kit.card(g, cx, cy, cw, ch, 0, me);
            // the class's portrait from the game's character select, the text beside it (none without the pack)
            int tx = cx + 6;
            if (d.cls() != null && WynnTex.classIcon(g, d.cls(), cx + 5, cy + 5, iconS)) {
                tx = cx + 9 + iconS;
            }
            int tw = cx + cw - 6 - tx;
            String lv = "Lv. " + d.level();
            ink(g, Component.literal(fit(nice(d.cls()), tw - 4 - font.width(lv))).withStyle(UiFont::bold),
                    tx, cy + 5, Theme.INK);
            ink(g, lv, cx + cw - 6 - font.width(lv), cy + 5, MUTED);
            if (!small) {
                String when = me ? L.t("wynnlabs.today.card_now") : L.t("wynnlabs.today.card_played", ago(d.seen()));
                ink(g, fit(when, tw), tx, cy + 15, FAINT);
            }
            long done = d.objectives().stream().filter(o -> o.max() > 0 && o.score() >= o.max()).count();
            String dv = stale || d.objectives().isEmpty() ? "—" : done + "/" + d.objectives().size();
            int dy = small ? cy + 15 : cy + 26;
            ink(g, fit(L.t("wynnlabs.today.card_daily"), tw - 18), tx, dy, stale ? FAINT : 0xFFDDDDDD);
            ink(g, dv, cx + cw - 6 - font.width(dv), dy, stale ? FAINT : 0xFFDDDDDD);
            Art.pill(g, tx, dy + 10, cx + cw - 6 - tx, 3, Theme.WELL, 0);
            if (!stale && !d.objectives().isEmpty() && done > 0) {
                Art.pill(g, tx, dy + 10, Math.max(3, (int) ((cx + cw - 6 - tx) * done / d.objectives().size())), 3, Theme.GREEN, 0);
            }
        }
        int cardRows = (days.size() + perRow - 1) / perRow;
        ry += cardRows * (ch + gap) + 4;

        // to do: a framed list, an item icon per line, the action in blue when it is a click
        section(g, L.t("wynnlabs.today.todo"), x, ry, w);
        ry += 11;
        List<Object[]> todo = new ArrayList<>();   // {icon, text, action, command, quest, objective}
        Today.Day mine = days.isEmpty() ? null : days.get(0);
        if (reward == null) {
            todo.add(new Object[] {icon("chest"), L.t("wynnlabs.today.todo_reward_unknown"), null, null, null, null});
        } else if (reward) {
            todo.add(new Object[] {icon("chest"), L.t("wynnlabs.today.todo_reward_ready"), null, null, null, null});
        }
        if (mine != null && today.equals(mine.date())) {
            for (Today.Objective o : mine.objectives()) {
                todo.add(new Object[] {icon("pick"), o.goal(), null, null, null, o});
            }
            if (mine.tickets() > 0) {
                todo.add(new Object[] {icon("paper"), L.n("wynnlabs.today.tickets", mine.tickets()), L.t("wynnlabs.today.go_compass"),
                        "/compass at 437 70 -1613", null, null});
            }
        }
        if (System.currentTimeMillis() - vaultAt > 3000) {
            vaultFinds = Vault.forMe();
            vaultAt = System.currentTimeMillis();
        }
        List<Vault.Group> bankJunk = Vault.junk();
        if (!bankJunk.isEmpty()) {
            long tm = bankJunk.stream().filter(j -> "Trade Market".equals(Vault.place(j))).count();
            todo.add(new Object[] {icon("emerald"), L.n("wynnlabs.today.todo_bank", bankJunk.size())
                    + (tm > 0 ? " (" + tm + " Trade Market)" : ""), L.t("wynnlabs.today.go_bank"), "#qgo:bank", null, null});
        } else if (Vault.count() == 0) {
            todo.add(new Object[] {icon("emerald"), L.t("wynnlabs.today.open_bank"), null, null, null, null});
        }
        if (!vaultFinds.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Vault.Find f : vaultFinds) {
                names.add(f.item().name() + " +" + pct(Math.round(f.gain())));
            }
            todo.add(new Object[] {icon("diamond"), L.t("wynnlabs.today.todo_vault", String.join(", ", names)),
                    L.t("wynnlabs.today.go_bank"), "#qgo:bank", null, null});
        }
        try {
            SellSummary.Summary ss = SellSummary.compute(Minecraft.getInstance());
            if (ss.blacksmith().size() + ss.market().size() > 0) {
                todo.add(new Object[] {icon("gold"), L.t("wynnlabs.today.todo_bag", ss.blacksmith().size(), ss.market().size())
                        + (ss.marketValue() > 0 ? " (~" + Prices.fmt(ss.marketValue()) + ")" : ""), null, null, null, null});
            }
        } catch (Throwable ignored) {
        }
        if (!quests.isEmpty()) {
            QuestAdvisor.Suggestion q = quests.get(0);
            todo.add(new Object[] {icon("compass"), L.t("wynnlabs.today.todo_quest", QuestGuides.title(q.quest().name())),
                    L.t("wynnlabs.today.track"), null, q.quest(), null});
        }
        int mf = Coach.manaFailsLast5Min();
        if (mf > 0) {
            todo.add(new Object[] {icon("lapis"), L.n("wynnlabs.today.mana_fails", mf), null, null, null, null});
        }
        int rowH = 22;
        int listH = Math.max(1, todo.size()) * rowH;
        Kit.card(g, x, ry, w, listH, 0);
        if (todo.isEmpty()) {
            ink(g, L.t("wynnlabs.today.nothing_todo"), x + 8, ry + 5, FAINT);
        }
        for (int i = 0; i < todo.size(); i++) {
            Object[] t = todo.get(i);
            int ty = ry + i * rowH;
            if (i > 0) {
                g.fill(x + 1, ty, x + w - 1, ty + 1, Theme.LINE);
            }
            boolean click = t[3] != null || t[4] != null;
            boolean hover = click && mx >= x && mx < x + w && my >= ty && my < ty + rowH;
            if (hover) {
                Kit.row(g, x + 2, ty + 1, w - 4, rowH - 2, 0, true);
            }
            String[] ic = (String[]) t[0];
            Art.iconTile(g, ic[0], x + 8, ty + 4, 14, (int) Long.parseLong(ic[1], 16), (int) Long.parseLong(ic[2], 16));
            String action = (String) t[2];
            Today.Objective o = (Today.Objective) t[5];
            int reserve = o != null ? 120 : action == null ? 8 : Kit.Ui.btnW(font, action) + 12;
            ink(g, fit((String) t[1], w - 24 - reserve), x + 28, ty + 7, 0xFFE8E6DF);
            if (o != null && o.max() > 0) {
                boolean ok = o.score() >= o.max();
                int bx = x + w - 110;
                Art.pill(g, bx, ty + 10, 70, 3, Theme.WELL, 0);
                if (o.score() > 0) {
                    Art.pill(g, bx, ty + 10, Math.max(3, 70 * Math.min(o.score(), o.max()) / o.max()), 3, ok ? Theme.GREEN : Theme.AMBER, 0);
                }
                String sc = o.score() + "/" + o.max();
                ink(g, sc, x + w - 8 - font.width(sc), ty + 7, ok ? Theme.GOOD : MUTED);
            } else if (action != null && click) {
                // a click: a small wooden button, gold under the mouse
                int aw = Kit.Ui.btnW(font, action);
                int state = hover ? Kit.ON : Kit.REST;
                Kit.button(g, x + w - 6 - aw, ty + 4, aw, Kit.Ui.BTN_H, state);
                onWood(g, action, x + w - 6 - aw + Kit.Ui.BTN_PAD, ty + 7, state);
            } else if (action != null) {
                ink(g, action, x + w - 8 - font.width(action), ty + 7, FAINT);
            }
            if (t[3] != null) {
                hits.add(new Hit(x, ty, w, rowH, null, null, (String) t[3]));
            } else if (t[4] != null) {
                QuestBook.Entry q = (QuestBook.Entry) t[4];
                hits.add(new Hit(x, ty, w, rowH, null, List.of(Component.literal(QuestGuides.title(q.name())).withStyle(ChatFormatting.AQUA),
                        L.c("wynnlabs.today.track_tip").withStyle(ChatFormatting.DARK_GRAY)), null, q));
            }
        }
        ry += listH + 8;

        // the professions: gathering ones with a bar (quests ask for those), crafting as numbers
        // the professions while their card fits under the rest (a short window drops them)
        int profRows = (Professions.ALL.size() + (layout.compact() ? 3 : layout.large() ? 6 : 4) - 1)
                / (layout.compact() ? 3 : layout.large() ? 6 : 4);
        if (ry + 11 + profRows * 14 + 6 <= y + h) {
            ry = drawProfessions(g, x, ry, w, mx, my);
        }

        // the last session, in four tiles with big numbers
        try {
            com.google.gson.JsonObject last = Coach.lastSession();
            com.google.gson.JsonObject meas = Coach.measured();
            if (last != null && ry + 11 + 38 <= y + h) {
                section(g, L.t("wynnlabs.today.last_head", ago(last.get("end").getAsLong())), x, ry, w);
                ry += 11;
                long mins = (last.get("end").getAsLong() - last.get("start").getAsLong()) / 60000;
                double lvs = last.get("levels").getAsDouble();
                int em = last.get("emeralds").getAsInt();
                int de = last.get("deaths").getAsInt();
                int tw = (w - 3 * gap) / 4;
                int th = 38;
                bigTile(g, x, ry, tw, th, L.t("wynnlabs.today.tile_time"), L.t("wynnlabs.today.tile_minutes", mins), null, 0xFFFFFFFF);
                bigTile(g, x + (tw + gap), ry, tw, th, L.t("wynnlabs.today.tile_levels"),
                        (lvs >= 0 ? "+" : "") + String.format(Locale.ROOT, "%.2f", lvs), null, 0xFF97C459);
                bigTile(g, x + 2 * (tw + gap), ry, tw, th, L.t("wynnlabs.today.tile_money"),
                        (em >= 0 ? "+" : "") + Prices.fmt(em) + " · " + de, null, de >= 2 ? 0xFFE24B4A : 0xFFFFFFFF);
                if (meas.has("casts") && meas.get("casts").getAsInt() >= 5 && meas.has("modelPerCast")) {
                    bigTile(g, x + 3 * (tw + gap), ry, tw, th, L.t("wynnlabs.today.tile_damage", meas.get("casts").getAsInt()),
                            "~" + Math.round(meas.get("avgPerCast").getAsDouble()),
                            L.t("wynnlabs.today.tile_damage_sub", Math.round(meas.get("modelPerCast").getAsDouble()),
                                    String.format(Locale.ROOT, "%.2f", Rules.sub("model", "spellCalibration", 1.0))), 0xFFFFFFFF);
                } else {
                    bigTile(g, x + 3 * (tw + gap), ry, tw, th, L.t("wynnlabs.today.tile_damage", 0), "—", null, 0xFF6E6C78);
                }
                ry += th + 4;
                List<String> tipsOut = Coach.sessionTips(last);
                if (!tipsOut.isEmpty() && ry < y + h - 10) {
                    ink(g, fit("→ " + String.join(" · ", tipsOut), w), x, ry + 2, Theme.ACCENT);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** A list icon: {Tabler name, tile ARGB, icon ARGB} as hex strings. */
    private static String[] icon(String what) {
        return switch (what) {
            case "chest" -> new String[] {"gift", "FF173A2C", "FF7EE0BC"};
            case "pick" -> new String[] {"pick", "FF3A2A12", "FFFAC775"};
            case "paper" -> new String[] {"ticket", "FF12303A", "FF55FFFF"};
            case "emerald" -> new String[] {"coin", "FF3A3212", "FFFAC775"};
            case "diamond" -> new String[] {"diamond", "FF0F2A22", "FF5DCAA5"};
            case "gold" -> new String[] {"shopping-cart", "FF3A3212", "FFFAC775"};
            case "compass" -> new String[] {"map-pin", "FF12273A", "FF85B7EB"};
            default -> new String[] {"droplet", "FF12273A", "FF85B7EB"};
        };
    }

    /** A pill with an icon and text (green for a waiting reward); without a background when bg is 0. */
    private void badge(GuiGraphics g, int x, int y, int maxW, String icon, String text, int colour, int bg) {
        String t = fit(text, maxW - 18);
        int w = font.width(t) + 18;
        if (bg != 0) {
            Kit.pillLine(g, x, y, w, 11, Theme.paperRgb(colour));
        }
        Art.icon(g, icon, x + 4, y + 2, 7, Theme.paperRgb(colour));
        ink(g, t, x + 13, y + 2, colour, false);
    }

    /** A number tile: a dim label, a big bold value, and an optional small note under it. */
    private void bigTile(GuiGraphics g, int x, int y, int w, int h, String label, String value, String note, int colour) {
        Kit.plaque(g, x, y, w, h);
        ink(g, fit(label, w - 10), x + 6, y + 5, FAINT);
        scaledText(g, Component.literal(fit(value, (int) ((w - 10) / 1.3f))).withStyle(UiFont::bold), x + 6, y + 16, 1.3f, colour);
        if (note != null) {
            int vw = (int) (font.width(Component.literal(value).withStyle(UiFont::bold)) * 1.3f);
            scaledText(g, Component.literal(fit(note, (int) ((w - vw - 16) / 0.75f))), x + 10 + vw, y + 22, 0.75f, 0xFF6E6C78);
        }
    }

    /** A pill label; returns where the next one starts. */
    private long blockedAt;
    private String blockedLine = "";

    /**
     * The professions card: in columns, the gathering professions first with a bar to 100 under
     * the name, the crafting ones after them as numbers; then, when a quest you could do by level
     * is held back by a profession, what it still needs. Returns the y below it.
     */
    private int drawProfessions(GuiGraphics g, int x, int y, int w, int mx, int my) {
        Map<String, Integer> lv = Professions.levels();
        if (lv.values().stream().allMatch(v -> v < 0)) {
            return y;                                   // not known (no Wynntils): nothing to show
        }
        section(g, L.t("wynnlabs.prof.title"), x, y, w);
        y += 11;
        // four columns; three on a compact page so every name fits, six on a large one so a name
        // stays near its level
        int cols = layout.compact() ? 3 : layout.large() ? 6 : 4;
        int colW = (w - 12) / cols;
        int rowH = 11;
        int rows = (Professions.ALL.size() + cols - 1) / cols;
        // a row with a gathering profession is taller: its bar runs under the names
        int[] rowY = new int[rows + 1];
        for (int r = 0; r < rows; r++) {
            boolean bar = false;
            for (int i = r * cols; i < Math.min(Professions.ALL.size(), (r + 1) * cols); i++) {
                bar |= Professions.gathering(Professions.ALL.get(i)) && lv.get(Professions.ALL.get(i)) >= 0;
            }
            rowY[r + 1] = rowY[r] + rowH + (bar ? 3 : 0);
        }
        // the quest a profession holds back: worked out every few seconds, not every frame
        if (System.currentTimeMillis() - blockedAt > 3000) {
            blockedAt = System.currentTimeMillis();
            blockedLine = "";
            int level = Wyn.level();
            int asked = 0;
            for (QuestBook.Entry e : QuestBook.all()) {
                if (!"UNAVAILABLE".equals(e.status()) || e.level() > level || asked >= 6) {
                    continue;
                }
                asked++;                                // a few wiki pages at a time
                QuestWiki.Info qi = QuestWiki.get(e.name());
                Map<String, int[]> miss = qi == null ? Map.of() : Professions.missing(qi.professions());
                if (!miss.isEmpty()) {
                    List<String> parts = new ArrayList<>();
                    miss.forEach((p, hn) -> parts.add(Professions.name(p) + " +" + (hn[1] - hn[0])));
                    blockedLine = L.t("wynnlabs.prof.blocked", String.join(", ", parts), e.name());
                    break;
                }
            }
        }
        int ch = rowY[rows] + 6 + (blockedLine.isEmpty() ? 0 : rowH + 2);
        Kit.card(g, x, y, w, ch, 0);
        for (int i = 0; i < Professions.ALL.size(); i++) {
            String p = Professions.ALL.get(i);
            int v = lv.get(p);
            int cx = x + 6 + (i % cols) * colW;
            int cy = y + 4 + rowY[i / cols];
            String val = v < 0 ? "?" : String.valueOf(v);
            int vw = font.width(val);
            // the profession's icon from the game's pack before its name (the name alone without the pack)
            int nx = WynnTex.profession(g, p, cx, cy - 1, 9) ? cx + 11 : cx;
            ink(g, fit(Professions.name(p), colW - vw - 10 - (nx - cx)), nx, cy, MUTED);
            if (Professions.gathering(p) && v >= 0) {
                int bw = colW - 12;
                Art.pill(g, cx, cy + 10, bw, 2, Theme.WELL, 0);
                Art.pill(g, cx, cy + 10, Math.max(2, bw * Math.min(v, 100) / 100), 2, Theme.TEAL, 0);
            }
            ink(g, val, cx + colW - vw - 6, cy, Theme.INK);
        }
        if (!blockedLine.isEmpty()) {
            ink(g, fit(blockedLine, w - 12), x + 6, y + 4 + rowY[rows] + 1, Theme.BAD);
        }
        return y + ch + 8;
    }

    private int pillText(GuiGraphics g, int x, int y, String text, int colour, int bg) {
        int w = font.width(text) + 12;
        Kit.pillLine(g, x, y, w, 13, Theme.paperRgb(colour));
        ink(g, text, x + 6, y + 3, colour, false);
        return x + w + 6;
    }

    /** A pill with an 8 px icon before its text. */
    private int pillText(GuiGraphics g, int x, int y, String icon, String text, int colour, int bg) {
        int w = font.width(text) + 22;
        Kit.pillLine(g, x, y, w, 13, Theme.paperRgb(colour));
        inkIconText(g, icon, text, x + 6, y + 3, colour);
        return x + w + 6;
    }

    private List<Today.Day> days = List.of();
    private List<Vault.Find> vaultFinds = List.of();
    private long vaultAt;
    private long daysAt;

    private static String ago(long t) {
        long min = (System.currentTimeMillis() - t) / 60000;
        if (min < 60) {
            return L.t("wynnlabs.screen.ago_min", min);
        }
        if (min < 24 * 60) {
            return L.t("wynnlabs.screen.ago_hours", min / 60);
        }
        return L.n("wynnlabs.screen.ago_days", min / 1440);
    }

    // ---- bank tab (Banka): the bank as a grid of real items ----

    private net.minecraft.client.gui.components.EditBox search;
    private net.minecraft.client.gui.components.EditBox keyBox;
    /** False while the key built into the mod is in use: the settings row then asks for nothing. */
    private boolean keyBoxShown = true;

    /** Saves what is in the key box (live prices switch on with it) and empties the box. */
    private void saveKey() {
        String v = keyBox.getValue().strip();
        if (v.isEmpty()) {
            return;
        }
        Prices.setKey(v);
        keyBox.setValue("");
        keyBox.setFocused(false);
        setFocused(null);
        if (!Settings.on("priceApi")) {
            Settings.toggle("priceApi");
        }
        flash = L.t("wynnlabs.settings.key_saved");
        flashUntil = System.currentTimeMillis() + 2500;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        // the screenshot tool drives the menu: Esc stops it (and puts everything back), other keys wait
        if (DevHooks.ON && DevHooks.driving()) {
            if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                DevHooks.escape();
            }
            return true;
        }
        // the build window first: Esc closes a rename or the window, never the menu
        if (tab == 0 && BuildWindow.INSTANCE.keyPressed(event.key())) {
            return true;
        }
        if (tab == TAB_CRAFT && CraftingTab.INSTANCE.keyPressed(event.key())) {
            return true;
        }
        if (tab == TAB_SETTINGS && ruleBox != null && ruleBox.isFocused()
                && (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) {
            saveRuleBox();
            return true;
        }
        if (tab == TAB_SETTINGS && keyBox != null && keyBox.isFocused()
                && (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) {
            saveKey();
            return true;
        }
        return super.keyPressed(event);
    }
    private int bankFilter;
    private int bankScroll;
    private String bankPage = "";                                       // "" = every item, by kind
    private final List<Object[]> bankFilterHits = new ArrayList<>();   // {int[] rect, Integer filter}
    private final List<Object[]> bankPageHits = new ArrayList<>();     // {int[] rect, String page}
    private static final int SLOT = 24;                 // the bank's slots: big enough to tell items apart
    private static final int PITCH = 26;
    private static final int ICON = 20;                 // the item in a slot, 2px from its edge
    private static final int MARK_SELL = Theme.MARK_SELL;
    private static final int MARK_QUEST = Theme.MARK_QUEST;
    private static final int MARK_IDENTIFY = Theme.MARK_IDENTIFY;
    private static final int MARK_USE = Theme.MARK_USE;

    private void drawBank(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float pt) {
        bankPageHits.clear();
        List<Vault.Group> all = Vault.groups();
        if (all.isEmpty()) {
            emptyState(g, x, y, w, h, railIcon(TAB_BANK), L.t("wynnlabs.bank.empty_title"),
                    L.t("wynnlabs.bank.empty_step1"),
                    L.t("wynnlabs.bank.empty_step2"),
                    L.t("wynnlabs.bank.empty_step3"));
            return;
        }
        List<Vault.Group> junk = Vault.junk();
        java.util.Set<String> junkNames = new java.util.HashSet<>();
        junk.forEach(j -> junkNames.add(j.key()));
        Map<String, Vault.Group> byName = new HashMap<>();
        all.forEach(gr -> byName.put(gr.key(), gr));

        // summary
        long tm = junk.stream().filter(j -> "Trade Market".equals(Vault.place(j))).count();
        double worth = junk.stream().filter(j -> "Trade Market".equals(Vault.place(j)))
                .map(Vault::value).filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).sum();
        Component head = L.cn("wynnlabs.bank.summary", Vault.pages(), Vault.count(), Vault.pages()).withStyle(ChatFormatting.WHITE)
                .append(Component.literal(junk.isEmpty() ? L.t("wynnlabs.bank.nothing_to_sell") : L.n("wynnlabs.bank.kinds_sellable", junk.size()))
                        .withStyle(junk.isEmpty() ? ChatFormatting.GRAY : ChatFormatting.RED))
                .append(Component.literal(junk.isEmpty() ? "" : " (Blacksmith " + (junk.size() - tm) + ", Trade Market " + tm
                        + (worth > 0 ? " ~" + Prices.fmt(worth) : "") + ")").withStyle(ChatFormatting.GRAY));
        int unpriced = 0;
        for (Vault.Group gr : all) {
            String k = gr.first().kind();
            if (gr.reachable() && Vault.value(gr) == null && (k.equals("gear") || k.equals("gearbox") || k.equals("ingredient")
                    || k.equals("material") || k.equals("powder"))) {
                unpriced++;
            }
        }
        if (unpriced > 0) {
            Component u = L.cn("wynnlabs.bank.unpriced", unpriced)
                    .withStyle(ChatFormatting.GOLD);
            int uw = font.width(u);
            if (font.width(head) + uw + 16 <= w) {
                ink(g, u, x + w - uw, y, 0xFFFFFFFF);
            }
        }
        ink(g, head, x, y, 0xFFFFFFFF);

        // search and filters
        int sy = y + 12;
        // the chips' room decides the search box: narrower on a small page, down to 70px
        int chipsW = 0;
        for (String f : BANK_FILTERS) {
            chipsW += font.width(L.t(f)) + 16;
        }
        int searchW = Math.max(70, Math.min(130, w - 8 - chipsW));
        textBox(g, search, x, sy, searchW, "wynnlabs.bank.search_hint", mx, my, pt);
        int fx = x + searchW + 8;
        for (int i = 0; i < BANK_FILTERS.length; i++) {
            boolean on = i == bankFilter;
            String ft = L.t(BANK_FILTERS[i]);
            int fw = font.width(ft) + 12;
            if (fx + fw > x + w) {
                // still too narrow: the rest of the chips on a line of their own
                fx = x;
                sy += 16;
            }
            boolean fh = mx >= fx && mx < fx + fw && my >= sy - 1 && my < sy + 13;
            int state = on ? Kit.ON : fh ? Kit.HOVER : Kit.REST;
            Kit.button(g, fx, sy - 1, fw, 14, state);
            onWood(g, ft, fx + 6, sy + 2, state);
            bankFilterHits.add(new Object[] {new int[] {fx, sy - 1, fw, 14}, i});
            fx += fw + 4;
        }

        // pages: Tümü · Hesap 1 2 3 … · Karakter 1 · Mage 1 2 …
        int py = sy + 17;
        int px = x;
        px += pageChip(g, px, py, L.t("wynnlabs.bank.page_all"), "", mx, my) + 8;
        // one group a bank (each character its own, even when two share a name), a "|" between
        // groups; a group starts a new line rather than leave its label apart from its pages
        List<String> keys = Vault.pageKeys();
        String group = null;
        String groupName = null;
        for (int k = 0; k < keys.size(); k++) {
            String key = keys.get(k);
            String gk = pageGroup(key);
            if (!gk.equals(group)) {
                String name = Vault.bankName(key);
                boolean label = !name.equals(groupName);
                boolean bar = group != null && !label;     // the same name again: a "|" tells them apart
                group = gk;
                groupName = name;
                int labelW = label ? font.width(name) + 4 : bar ? font.width("|") + 6 : 0;
                int gw = labelW;
                for (int j = k; j < keys.size() && pageGroup(keys.get(j)).equals(gk); j++) {
                    gw += font.width(String.valueOf(Vault.pageNumber(keys.get(j)))) + 14;
                }
                int firstW = labelW + font.width(String.valueOf(Vault.pageNumber(key))) + 12;
                // a new line when the whole group fits one, or the label and its first page do not fit this one
                if (px > x && (px + gw > x + w && gw <= w || px + firstW > x + w)) {
                    px = x;
                    py += 16;
                    label = true;                   // a group starting a line says whose it is again
                }
                if (bar && !label) {
                    ink(g, "|", px, py + 3, FAINT);
                    px += font.width("|") + 6;
                }
                if (label) {
                    ink(g, name, px, py + 3, FAINT);
                    px += font.width(name) + 4;
                }
            }
            String num = String.valueOf(Vault.pageNumber(key));
            if (px + font.width(num) + 12 > x + w) {
                px = x;
                py += 16;
            }
            px += pageChip(g, px, py, num, key, mx, my) + 2;
            if (k + 1 < keys.size() && !pageGroup(keys.get(k + 1)).equals(gk)) {
                px += 8;
            }
        }
        if (!bankPage.isEmpty() && !Vault.pageKeys().contains(bankPage)) {
            bankPage = "";
        }

        // the grid
        int gy = py + 19;
        int bottom = y + h - 12;
        if (DevHooks.ON) {
            DevHooks.part("banklist", g.pose(), x, gy, w, bottom - gy);
        }
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        g.enableScissor(x, gy, x + w, bottom);
        int contentH;
        if (bankPage.isEmpty()) {
            contentH = drawAllItems(g, x, gy - bankScroll, w, gy, bottom, all, junkNames, q, mx, my);
        } else {
            contentH = drawOnePage(g, x, gy - bankScroll, w, gy, bottom, bankPage, junkNames, byName, q, mx, my);
        }
        g.disableScissor();
        bankScrollMax = Math.max(0, contentH - (bottom - gy));
        bankScroll = Math.min(bankScroll, bankScrollMax);

        // legend
        int lx = x;
        int ly = y + h - 9;
        lx = legend(g, lx, ly, MARK_SELL, L.t("wynnlabs.bank.legend_sell"));
        lx = legend(g, lx, ly, MARK_QUEST, L.t("wynnlabs.bank.legend_quest"));
        lx = legend(g, lx, ly, MARK_IDENTIFY, L.t("wynnlabs.bank.legend_identify"));
        lx = legend(g, lx, ly, MARK_USE, L.t("wynnlabs.bank.legend_use"));
        String more = bankScrollMax > 0 ? L.t("wynnlabs.screen.scroll_wheel") : "";
        ink(g, more, x + w - font.width(more), ly, FAINT);
    }

    private int bankScrollMax;

    private int legend(GuiGraphics g, int x, int y, int colour, String text) {
        g.fill(x, y + 1, x + 6, y + 7, colour);
        ink(g, text, x + 9, y, MUTED);
        return x + 9 + font.width(text) + 12;
    }

    private int pageChip(GuiGraphics g, int x, int y, String text, String key, int mx, int my) {
        boolean on = bankPage.equals(key);
        int w = font.width(text) + 12;
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 14;
        int state = on ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
        Kit.button(g, x, y, w, 14, state);
        onWood(g, text, x + 6, y + 3, state);
        bankPageHits.add(new Object[] {new int[] {x, y, w, 14}, key});
        return w;
    }

    /** One slot: a sunken square, the item, its count, a coloured frame for the verdict, dimmed if filtered out. */
    private void slot(GuiGraphics g, int x, int y, ItemStack stack, String count, int mark, boolean dim, boolean hover) {
        Kit.slot(g, x, y, SLOT, 0);
        if (DevHooks.ON && stack != null && !dim && tab == TAB_BANK) {
            DevHooks.part("bankitem", g.pose(), x, y, SLOT, SLOT);
        }
        if (stack != null) {
            int in = (SLOT - ICON) / 2;
            g.pose().pushMatrix();
            g.pose().translate(x + in, y + in);
            g.pose().scale(ICON / 16f, ICON / 16f);
            g.renderItem(stack, 0, 0);
            // the bar and such without the game's count, which sits low enough to spill out of the slot
            g.renderItemDecorations(font, stack, 0, 0, "");
            g.pose().popMatrix();
            if (count != null) {
                // the count inside the slot, bottom right, 1px in
                g.drawString(font, count, x + SLOT - 1 - font.width(count), y + SLOT - 9, 0xFFFFFFFF, true);
            }
        }
        if (mark != 0) {
            g.renderOutline(x, y, SLOT, SLOT, mark);
        }
        if (dim) {
            g.fill(x, y, x + SLOT, y + SLOT, 0xB0D8C595);   // faded into the paper
        }
        if (hover) {
            g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0x50FFFFFF);
        }
    }

    /** The bank a page key belongs to: "acct", or "char:" and the character's id. */
    private static String pageGroup(String key) {
        return key.startsWith("char:") && key.indexOf(':', 5) > 0 ? key.substring(0, key.indexOf(':', 5)) : "acct";
    }

    private static String shortCount(int n) {
        // "1k" past 999: a slot has room for three digits
        return n <= 1 ? null : n < 1000 ? String.valueOf(n) : n / 1000 + "k";
    }

    private int markOf(Vault.Group gr, java.util.Set<String> junkNames) {
        if (gr == null) {
            return 0;
        }
        if (junkNames.contains(gr.key())) {
            return MARK_SELL;
        }
        if (!Vault.questsFor(gr).isEmpty()) {
            return MARK_QUEST;
        }
        String v = gr.first().verdict();
        if (v == null) {
            return 0;
        }
        Verdict vv = Verdict.of(v);
        return vv == Verdict.IDENTIFY ? MARK_IDENTIFY : vv == Verdict.WEAR || vv == Verdict.USE ? MARK_USE : 0;
    }

    private boolean matches(Vault.Group gr, java.util.Set<String> junkNames, String q) {
        Vault.Stored st = gr.first();
        boolean keep = switch (bankFilter) {
            case 1 -> junkNames.contains(gr.key());
            case 2 -> !Vault.questsFor(gr).isEmpty();
            case 3 -> !st.prof().isEmpty();
            case 4 -> st.gear() || st.kind().contains("gear");
            default -> true;
        };
        if (keep && !q.isEmpty()) {
            String hay = (gr.name() + " " + Vault.where(gr) + " " + st.kind() + " " + st.prof() + " "
                    + (st.verdict() == null ? "" : Verdict.of(st.verdict()).label())).toLowerCase(Locale.ROOT);
            keep = hay.contains(q);
        }
        return keep;
    }

    /** Every item once (with its total), in sections: to sell, quests, gear, boxes, crafting, the rest. */
    private int drawAllItems(GuiGraphics g, int x, int y, int w, int top, int bottom, List<Vault.Group> all,
            java.util.Set<String> junkNames, String q, int mx, int my) {
        String[] titles = {L.t("wynnlabs.bank.sec_sell"), L.t("wynnlabs.bank.sec_quest"), L.t("wynnlabs.bank.sec_gear"),
            L.t("wynnlabs.bank.sec_boxes"), L.t("wynnlabs.bank.sec_crafting"), L.t("wynnlabs.bank.sec_other")};
        List<List<Vault.Group>> sections = new ArrayList<>();
        for (int i = 0; i < titles.length; i++) {
            sections.add(new ArrayList<>());
        }
        for (Vault.Group gr : all) {
            if (!matches(gr, junkNames, q)) {
                continue;
            }
            Vault.Stored st = gr.first();
            int sec = junkNames.contains(gr.key()) ? 0
                    : !Vault.questsFor(gr).isEmpty() ? 1
                    : st.gear() || st.kind().equals("craftedgear") ? 2
                    : st.kind().equals("gearbox") ? 3
                    : !st.prof().isEmpty() ? 4 : 5;
            sections.get(sec).add(gr);
        }
        int cols = Math.max(1, (w + 2) / PITCH);
        int cy = y;
        boolean any = false;
        for (int i = 0; i < titles.length; i++) {
            List<Vault.Group> list = sections.get(i);
            if (list.isEmpty()) {
                continue;
            }
            any = true;
            int total = list.stream().mapToInt(Vault.Group::total).sum();
            section(g, titles[i] + "  " + (total > list.size() ? L.n("wynnlabs.bank.sec_kinds_items", list.size(), list.size(), total)
                    : String.valueOf(list.size())), x, cy, w);
            cy += 12;
            for (int k = 0; k < list.size(); k++) {
                Vault.Group gr = list.get(k);
                int sx = x + (k % cols) * PITCH;
                int sy = cy + (k / cols) * PITCH;
                boolean visible = sy + SLOT > top && sy < bottom;
                boolean hover = visible && mx >= sx && mx < sx + SLOT && my >= sy && my < sy + SLOT && my >= top && my < bottom;
                slot(g, sx, sy, Vault.stackOf(gr.first()), shortCount(gr.total()), markOf(gr, junkNames), !gr.reachable(), hover);
            if (gr != null && Vault.value(gr) == null) {
                String kd = gr.first().kind();
                if (kd.equals("gear") || kd.equals("gearbox") || kd.equals("ingredient") || kd.equals("material") || kd.equals("powder")) {
                    g.pose().pushMatrix();
                    g.pose().translate(sx + 1, sy + 1);
                    g.pose().scale(0.75f, 0.75f);
                    ink(g, "?", 0, 0, 0xFFFAC775);
                    g.pose().popMatrix();
                }
            }
                if (visible) {
                    addSlotTip(sx, Math.max(sy, top), SLOT, Math.min(sy + SLOT, bottom) - Math.max(sy, top), gr, junkNames, mx, my);
                }
            }
            cy += ((list.size() + cols - 1) / cols) * PITCH + 6;
        }
        if (!any) {
            ink(g, L.c("wynnlabs.bank.no_match").withStyle(ChatFormatting.GRAY), x, cy + 4, 0xFFFFFFFF);
            cy += 16;
        }
        return cy - y;
    }

    /** One bank page laid out as in the game; filtered-out items are dimmed, not hidden. */
    private int drawOnePage(GuiGraphics g, int x, int y, int w, int top, int bottom, String key,
            java.util.Set<String> junkNames, Map<String, Vault.Group> byName, String q, int mx, int my) {
        int[] size = Vault.gridSize();
        int gw = size[0] * PITCH - 2;
        int ox = x + (w - gw) / 2;
        Map<Integer, Vault.Stored> at = new HashMap<>();
        List<Vault.Stored> loose = new ArrayList<>();
        for (Vault.Stored st : Vault.page(key)) {
            if (st.slot() >= 0) {
                at.put(st.slot(), st);
            } else {
                loose.add(st);
            }
        }
        ink(g, Component.literal(Vault.label(key)).withStyle(UiFont::bold), ox, y, 0xFFFFFFFF);
        String n = L.n("wynnlabs.bank.page_stacks", Vault.page(key).size()) + (Vault.page(key).isEmpty() ? L.t("wynnlabs.bank.page_empty") : "");
        ink(g, n, ox + gw - font.width(n), y, MUTED);
        int gy = y + 12;
        int slots = size[0] * size[1];
        for (int i = 0; i < slots; i++) {
            int col = i % size[0];
            int row = i / size[0];
            int menuIndex = row * 9 + col;
            Vault.Stored st = at.get(menuIndex);
            if (st == null && i < loose.size()) {
                st = loose.get(i);              // pages saved before slots were kept: in order
            }
            int sx = ox + col * PITCH;
            int sy = gy + row * PITCH;
            boolean visible = sy + SLOT > top && sy < bottom;
            boolean hover = visible && mx >= sx && mx < sx + SLOT && my >= sy && my < sy + SLOT;
            if (st == null) {
                slot(g, sx, sy, null, null, 0, false, false);
                continue;
            }
            Vault.Group gr = byName.get(st.key());
            boolean on = gr == null || matches(gr, junkNames, q);
            slot(g, sx, sy, Vault.stackOf(st), shortCount(st.count()), on ? markOf(gr, junkNames) : 0, !on, hover);
            if (gr != null && Vault.value(gr) == null) {
                String kd = gr.first().kind();
                if (kd.equals("gear") || kd.equals("gearbox") || kd.equals("ingredient") || kd.equals("material") || kd.equals("powder")) {
                    g.pose().pushMatrix();
                    g.pose().translate(sx + 1, sy + 1);
                    g.pose().scale(0.75f, 0.75f);
                    ink(g, "?", 0, 0, 0xFFFAC775);
                    g.pose().popMatrix();
                }
            }
            if (visible && gr != null) {
                addSlotTip(sx, Math.max(sy, top), SLOT, Math.min(sy + SLOT, bottom) - Math.max(sy, top), gr, junkNames, mx, my);
            }
        }
        return 12 + size[1] * PITCH;
    }

    /**
     * A box: its levels and tier, which of your characters it suits, and the best that can come
     * out for them (by the rules' score) with market prices, so you know whether to identify it.
     */
    private void boxLines(ItemView.Box box, List<Component> tip, boolean outcomesToo) {
        if (box == null) {
            return;
        }
        int low = box.low();
        int high = box.high();
        tip.add(L.c("wynnlabs.bank.box_head", nice(box.slot().name()), nice(box.tier().name()), low, high)
                .withStyle(box.tier().colour(), ChatFormatting.BOLD));
        // your characters this box is for: right class, levels from 15 below to 5 above them
        java.util.Set<String> mine = new java.util.LinkedHashSet<>();
        List<String> forWho = new ArrayList<>();
        try {
            for (var e : Profile.list().getAsJsonArray("characters")) {
                var o = e.getAsJsonObject();
                String cls = o.get("class").getAsString();
                int lv = o.get("level").getAsInt();
                boolean classFits = false;
                for (var info : WynntilsItems.possibleGears(box)) {
                    var c = info.requirements().klass().orElse(info.slot().klass());
                    if (c == null || c.name().equals("NONE") || c.name().equals(cls)) {
                        classFits = true;
                        break;
                    }
                }
                if (classFits && high >= lv - 15 && low <= lv + 5) {
                    mine.add(cls);
                    forWho.add(nice(cls) + " Lv. " + lv);
                }
            }
        } catch (Throwable ignored) {
        }
        tip.add(forWho.isEmpty()
                ? L.c("wynnlabs.bank.box_fits_none").withStyle(ChatFormatting.RED)
                : L.c("wynnlabs.bank.box_fits", String.join(", ", forWho)).withStyle(ChatFormatting.GREEN));
        // every outcome by level, as Wynntils shows it in the bank: identify cost, names, best price
        List<ItemData> outcomes = new ArrayList<>(WynntilsItems.possibleGears(box));
        if (!outcomesToo || outcomes.isEmpty()) {
            tip.add(Component.empty());
            return;
        }
        java.util.TreeMap<Integer, List<ItemData>> byLevel = new java.util.TreeMap<>();
        outcomes.forEach(i -> byLevel.computeIfAbsent(i.requirements().level(), k -> new ArrayList<>()).add(i));
        tip.add(L.c("wynnlabs.bank.box_outcomes", outcomes.size()).withStyle(ChatFormatting.GREEN));
        int lines = 0;
        for (var e : byLevel.entrySet()) {
            if (lines++ == 10) {
                tip.add(L.cn("wynnlabs.bank.box_more_levels", byLevel.size() - 10).withStyle(ChatFormatting.DARK_GRAY));
                break;
            }
            int cost = box.tier().identificationCost(e.getKey());
            Double best = null;
            List<String> names = new ArrayList<>();
            for (ItemData info : e.getValue()) {
                var c = info.requirements().klass().orElse(info.slot().klass());
                boolean forMe = mine.isEmpty() || c == null || c.name().equals("NONE") || mine.contains(c.name());
                names.add((forMe ? "" : "·") + info.name());
                Double v = unidPrice(info.name());
                if (v != null && (best == null || v > best)) {
                    best = v;
                }
            }
            tip.add(Component.literal("  – Lv. " + e.getKey() + " ").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal("[" + cost + " E]").withStyle(ChatFormatting.DARK_GREEN))
                    .append(Component.literal(": " + fit(String.join(", ", names), 200)).withStyle(box.tier().colour()))
                    .append(Component.literal(best == null ? "" : " (" + Prices.fmt(best) + ")").withStyle(ChatFormatting.GOLD)));
        }
        // the three dearest outcomes: what identifying could be worth
        List<String> dear = outcomes.stream()
                .filter(i -> unidPrice(i.name()) != null)
                .sorted((a, b) -> Double.compare(unidPrice(b.name()), unidPrice(a.name())))
                .limit(3).map(i -> i.name() + " ~" + Prices.fmt(unidPrice(i.name()))).toList();
        if (!dear.isEmpty()) {
            tip.add(L.c("wynnlabs.bank.box_market", String.join(", ", dear)).withStyle(ChatFormatting.GOLD));
        }
        tip.add(Component.empty());
    }

    /** Lowest Trade Market listing of an identified item (what you'd sell it for), or null. */
    private static Double unidPrice(String name) {
        Prices.Price p = Prices.of(name);
        return p == null ? null : p.typical();
    }

    /** The item's own tooltip, then where it is kept, the rules' verdict, quests, crafting and price. */
    /**
     * The slot's tooltip is the item's own, drawn by Wynntils like in the bank (possible items,
     * stat ranges, …); the mod's lines for it (where, verdict, quests, crafting, price) are
     * handed to the tooltip through {@link Vault#tipExtra}. Built only for the hovered slot.
     */
    private void addSlotTip(int x, int y, int w, int h, Vault.Group gr, java.util.Set<String> junkNames, int mx, int my) {
        if (h <= 0) {
            return;
        }
        Vault.Stored st = gr.first();
        ItemStack stack = Vault.stackOf(st);
        hits.add(new Hit(x, y, w, h, stack, null, gr.name()));
        if (!(mx >= x && mx < x + w && my >= y && my < y + h)) {
            return;
        }
        List<Component> tip = new ArrayList<>();
        tip.add(Component.empty());
        if (st.verdict() != null) {
            boolean sell = junkNames.contains(gr.key());
            String where = sell ? Vault.place(gr) : null;
            tip.add(Component.literal(TooltipListener.marker()).withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(Component.literal(Verdict.of(st.verdict()).label() + (where == null ? "" : " → " + where))
                            .withStyle(sell ? ChatFormatting.RED : ChatFormatting.GREEN).withStyle(UiFont::bold))
                    .append(Component.literal(st.by() == null ? "" : L.t("wynnlabs.bank.tip_checked_by", AutoAdvice.nice(st.by())))
                            .withStyle(ChatFormatting.DARK_GRAY)));
            String note = Vault.noteNow(st);
            if (note != null) {
                for (String l : Advice.wrap(note, 40)) {
                    tip.add(Component.literal(l).withStyle(ChatFormatting.GRAY));
                }
            }
        }
        // a box Wynntils could not read: its levels and outcomes from what was saved
        boolean known = WynntilsItems.known(stack);
        if (Vault.boxOf(st) != null) {
            boxLines(Vault.boxOf(st), tip, !known);
        }
        tip.add(Component.literal("▣ " + (gr.total() > 1 ? "×" + gr.total() + " · " : "") + Vault.where(gr))
                .withStyle(ChatFormatting.DARK_AQUA));
        if (!gr.reachable()) {
            tip.add(L.c("wynnlabs.bank.tip_other_char").withStyle(ChatFormatting.DARK_GRAY));
        }
        for (String qn : Vault.questsFor(gr)) {
            tip.add(L.c("wynnlabs.bank.tip_quest", qn).withStyle(ChatFormatting.AQUA));
        }
        String prof = Vault.professions(st);
        if (prof != null) {
            tip.add(L.c("wynnlabs.bank.tip_prof", prof).withStyle(ChatFormatting.GREEN));
        }
        Double v = Vault.value(gr);
        if (v != null && gr.total() > 1) {
            tip.add(L.c("wynnlabs.bank.tip_all_worth", Prices.fmt(v)).withStyle(ChatFormatting.GOLD));
        }
        tip.add(L.c("wynnlabs.bank.click_copy").withStyle(ChatFormatting.DARK_GRAY));
        Vault.setTipExtra(stack, tip);
    }

    // ---- deaths tab (Ölüm koçu) ----

    /** A death worth showing: one recorded live (the ones copied from old logs have no details). */
    private static boolean detailed(DeathCoach.Death d) {
        return d.level() > 0 && d.ehp() > 0;
    }

    /** The death's main cause, as a language key; drives its colour too. */
    private static String cause(DeathCoach.Death d) {
        if (d.areaLevel() > d.level() + 2) {
            return "wynnlabs.deaths.cause_over";
        }
        if (d.secondsToZero() > 0 && d.secondsToZero() <= 3) {
            return "wynnlabs.deaths.cause_burst";
        }
        if (d.potions() == 0) {
            return "wynnlabs.deaths.cause_nopots";
        }
        if (d.secondsToZero() > 3) {
            return "wynnlabs.deaths.cause_slow";
        }
        return "wynnlabs.deaths.cause_unknown";
    }

    private static int causeColour(String cause) {
        return switch (cause) {
            case "wynnlabs.deaths.cause_over" -> 0xFFE24B4A;
            case "wynnlabs.deaths.cause_burst" -> 0xFFEF9F27;
            case "wynnlabs.deaths.cause_nopots" -> 0xFFD4537E;
            case "wynnlabs.deaths.cause_slow" -> 0xFF378ADD;
            default -> 0xFF5F5E5A;
        };
    }

    /** Nameplate leftovers off a mob name ("Rai-Elisu  1s" → "Rai-Elisu"). */
    private static String mobName(String m) {
        return m.replaceAll("\\s{2,}.*$", "").replaceAll("\\s+\\d+s$", "").trim();
    }

    /**
     * Deaths: three tiles (this week, the most dangerous place, the main cause), the coach's
     * advice, then each death with its cause stripe, place, when, and the numbers that matter;
     * click one to put its spot on the compass. Old entries without details fold into one line.
     */
    private void drawDeaths(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        List<DeathCoach.Death> all = DeathCoach.all();
        if (all.isEmpty()) {
            int ly = y + 4;
            for (var l : font.split(L.c("wynnlabs.deaths.none").withStyle(ChatFormatting.GRAY), w)) {
                ink(g, l, x, ly, 0xFFFFFFFF);
                ly += Kit.Ui.LINE_DENSE;
            }
            return;
        }
        long now = System.currentTimeMillis();
        List<DeathCoach.Death> real = new ArrayList<>();
        int legacy = 0;
        for (DeathCoach.Death d : all) {
            if (detailed(d)) {
                real.add(d);
            } else {
                legacy++;
            }
        }
        List<DeathCoach.Death> week = new ArrayList<>();
        for (DeathCoach.Death d : real) {
            if (now - d.time() < 7 * 24 * 3600_000L) {
                week.add(d);
            }
        }
        Map<String, Integer> places = new HashMap<>();
        Map<String, Integer> causes = new HashMap<>();
        Map<String, Integer> advice = new HashMap<>();
        for (DeathCoach.Death d : week) {
            places.merge(d.place().isEmpty() ? "?" : L.cap(d.place()), 1, Integer::sum);
            causes.merge(cause(d), 1, Integer::sum);
            for (String a : d.adviceText()) {
                advice.merge(a, 1, Integer::sum);
            }
        }
        String worstPlace = best(places);
        String mainCause = best(causes);
        String topAdvice = best(advice);

        // three tiles
        int gap = 6;
        int tw = (w - 2 * gap) / 3;
        int th = 34;
        tile(g, x, y, tw, th, L.t("wynnlabs.deaths.tile_week"), L.n("wynnlabs.deaths.tile_count", week.size()), 0xFFFFFFFF);
        tile(g, x + tw + gap, y, tw, th, L.t("wynnlabs.deaths.tile_place"),
                worstPlace == null ? "—" : worstPlace + " ×" + places.get(worstPlace), 0xFFE8E6DF);
        tile(g, x + 2 * (tw + gap), y, tw, th, L.t("wynnlabs.deaths.tile_cause"),
                mainCause == null ? "—" : L.t(mainCause), mainCause == null ? 0xFFE8E6DF : causeColour(mainCause));
        int ry = y + th + 6;
        if (topAdvice != null) {
            // a card like the deaths under it (same edges, one border), its stripe in the coach's colour
            Kit.card(g, x, ry, w, 16, 0, false);
            g.fill(x + 1, ry + 1, x + 4, ry + 15, Theme.ACCENT);
            inkIconText(g, "sparkles", fit(L.t("wynnlabs.deaths.advice_head") + topAdvice, w - 25), x + 9, ry + 4, Theme.ACCENT);
            ry += 21;
        }

        // the list, newest first, as many as fit
        int rowH = 34;
        int footer = legacy > 0 ? 14 : 0;
        int fit = Math.max(1, (y + h - ry - footer) / rowH);
        List<DeathCoach.Death> recent = new ArrayList<>(real);
        java.util.Collections.reverse(recent);
        for (int i = 0; i < recent.size() && i < fit; i++) {
            DeathCoach.Death d = recent.get(i);
            String cause = cause(d);
            boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + rowH - 3;
            Kit.card(g, x, ry, w, rowH - 3, 0, hover);
            g.fill(x + 1, ry + 1, x + 4, ry + rowH - 4, causeColour(cause));
            String when = ago(d.time());
            ink(g, when, x + w - 6 - font.width(when), ry + 4, FAINT);
            ink(g, fit(d.place().isEmpty() ? "?" : L.cap(d.place()), w - 24 - font.width(when)), x + 9, ry + 4, 0xFFFFFFFF);
            // the numbers: zone vs you, how fast, potions
            int cx = x + 9;
            int cy = ry + 15;
            if (d.areaLevel() > 0) {
                cx = chip(g, cx, cy, L.t("wynnlabs.deaths.chip_zone", d.areaLevel(), d.level()),
                        d.areaLevel() > d.level() + 2 ? 0xFFE24B4A : 0xFF9C9A92);
            }
            if (d.secondsToZero() > 0) {
                cx = chip(g, cx, cy, L.t("wynnlabs.deaths.chip_seconds", dec(d.secondsToZero(), 1)),
                        d.secondsToZero() <= 3 ? 0xFFEF9F27 : 0xFF9C9A92);
            }
            if (d.potions() >= 0) {
                cx = chip(g, cx, cy, L.n("wynnlabs.deaths.chip_potions", d.potions()), d.potions() == 0 ? 0xFFD4537E : 0xFF9C9A92);
            }
            if (!d.advice().isEmpty() && cx < x + w - 60) {
                ink(g, fit("→ " + d.adviceText().get(0), x + w - cx - 8), cx + 4, cy + 2, 0xFFAFA9EC);
            }
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(L.cap(d.place())).withStyle(ChatFormatting.WHITE));
            tip.add(Component.literal(L.t(cause)).withStyle(ChatFormatting.GRAY));
            if (!d.mobs().isEmpty()) {
                List<String> mobs = new ArrayList<>();
                for (String m : d.mobs()) {
                    mobs.add(mobName(m));
                }
                for (String l : Advice.wrap(L.t("wynnlabs.deaths.tip_mobs", String.join(", ", mobs)), 46)) {
                    tip.add(Component.literal(l).withStyle(ChatFormatting.GRAY));
                }
            }
            for (String a : d.adviceText()) {
                for (String l : Advice.wrap("→ " + a, 46)) {
                    tip.add(Component.literal(l).withStyle(ChatFormatting.LIGHT_PURPLE));
                }
            }
            tip.add(Component.literal(L.t("wynnlabs.deaths.tip_click")).withStyle(ChatFormatting.DARK_GRAY));
            hits.add(new Hit(x, ry, w, rowH - 3, null, tip,
                    "#qnear:" + d.x() + " " + d.y() + " " + d.z() + " " + L.t("wynnlabs.deaths.pin", d.place())));
            ry += rowH;
        }
        int more = recent.size() - Math.min(recent.size(), fit);
        List<String> foot = new ArrayList<>();
        if (more > 0) {
            foot.add(L.n("wynnlabs.deaths.more", more));
        }
        if (legacy > 0) {
            foot.add(L.n("wynnlabs.deaths.legacy", legacy));
        }
        if (!foot.isEmpty()) {
            ink(g, fit(String.join(" · ", foot), w), x, Math.max(ry + 2, y + h - 11), FAINT);
        }
    }

    private static String best(Map<String, Integer> counts) {
        String best = null;
        for (var e : counts.entrySet()) {
            if (best == null || e.getValue() > counts.get(best)) {
                best = e.getKey();
            }
        }
        return best;
    }

    /** A small number tile: a dim label over a bright value. */
    private void tile(GuiGraphics g, int x, int y, int w, int h, String label, String value, int colour) {
        Kit.plaque(g, x, y, w, h);
        ink(g, fit(label, w - 10), x + 6, y + 5, FAINT);
        ink(g, fit(value, w - 10), x + 6, y + 18, colour);
    }

    /** A bordered word in a row of facts; returns where the next one starts. */
    private int chip(GuiGraphics g, int x, int y, String text, int colour) {
        int cw = font.width(text) + 12;
        Kit.pillLine(g, x, y, cw, 12, Theme.paperRgb(colour));
        ink(g, text, x + 6, y + 2, colour);
        return x + cw + 4;
    }

    // ---- quests tab (Görevler) ----

    private String questFilter = "RECOMMENDED";
    private int questScroll;
    private String questSelected;
    private int questDetailTab;
    /** The points tab: its first row, and where the detail panel is (the wheel scrolls it there). */
    private int pointScroll;
    /** The quest and stage the points list was last opened on: a new one starts at the stage you are on. */
    private String pointScrollKey = "";
    private int[] questDetailRect;
    /** The steps tab: its first stage; a new quest or stage starts two steps above the one you are on. */
    private int stepScroll;
    private String stepScrollKey = "";
    private Map<String, List<QuestAdvisor.Suggestion>> questLists = new HashMap<>();
    private long questListsAt;

    /** Every filter's list, refreshed every two seconds (the chips show their counts). */
    private Map<String, List<QuestAdvisor.Suggestion>> questLists() {
        if (System.currentTimeMillis() - questListsAt > 2000) {
            Map<String, List<QuestAdvisor.Suggestion>> m = new HashMap<>();
            for (String f : QuestAdvisor.FILTERS) {
                try {
                    m.put(f, QuestAdvisor.list(f));
                } catch (Throwable t) {
                    m.put(f, List.of());
                }
            }
            questLists = m;
            questListsAt = System.currentTimeMillis();
        }
        return questLists;
    }

    private static QuestBook.Entry questEntry(String name) {
        for (QuestBook.Entry e : QuestBook.all()) {
            if (e.name().equalsIgnoreCase(name)) {
                return e;
            }
        }
        return null;
    }

    /** The elements your build hits with (from the skills it leans on): earth, water, … */
    private static List<String> myElements() {
        List<String> out = new ArrayList<>();
        for (String sk : Rules.list("model", "skillPriority")) {
            switch (sk) {
                case "STRENGTH" -> out.add("earth");
                case "DEXTERITY" -> out.add("thunder");
                case "INTELLIGENCE" -> out.add("water");
                case "DEFENCE" -> out.add("fire");
                case "AGILITY" -> out.add("air");
                default -> {
                }
            }
        }
        return out;
    }

    private static String elementTr(String e) {
        return switch (e) {
            case "earth" -> L.t("wynnlabs.quests.element_earth");
            case "water" -> L.t("wynnlabs.quests.element_water");
            case "fire" -> L.t("wynnlabs.quests.element_fire");
            case "air" -> L.t("wynnlabs.quests.element_air");
            case "thunder" -> L.t("wynnlabs.quests.element_thunder");
            default -> e;
        };
    }

    private long levelXp() {
        try {
            return Math.max(1, Wyn.xp().max());
        } catch (Throwable t) {
            return 1;
        }
    }

    private void line(GuiGraphics g, int x1, int y1, int x2, int y2, int colour) {
        int n = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        for (int i = 0; i <= n; i += 2) {
            int px = x1 + (x2 - x1) * i / Math.max(1, n);
            int py = y1 + (y2 - y1) * i / Math.max(1, n);
            g.fill(px, py, px + 1, py + 1, colour);
        }
    }

    private int button(GuiGraphics g, int x, int y, String text, boolean primary, int mx, int my, String action) {
        return button(g, x, y, text, primary, mx, my, action, 7);
    }

    private int button(GuiGraphics g, int x, int y, String text, boolean primary, int mx, int my, String action, int pad) {
        int w = font.width(text) + 2 * pad;
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 14;
        int state = primary ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
        Kit.button(g, x, y, w, 14, state);
        onWood(g, text, x + pad, y + 3, state);
        hits.add(new Hit(x, y, w, 14, null, null, action));
        return w;
    }

    /** "Read quests with Wynntils" (the empty card) or "Yenile" (the title line's chip); while running, "Reading quests…". */
    private String rescanLabel(boolean chip) {
        if (QuestBook.rescanning()) {
            return L.t("wynnlabs.quests.rescanning");
        }
        return L.t(chip ? "wynnlabs.quests.refresh" : "wynnlabs.quests.rescan");
    }

    /** The rescan's tooltip: what it does and that it runs only on a press, or why it cannot run. */
    private List<Component> rescanTip() {
        if (!Wyn.canRescanQuests()) {
            return List.of(L.c("wynnlabs.quests.rescan_off", Wyn.version()).withStyle(ChatFormatting.GOLD));
        }
        return List.of(L.c(QuestBook.rescanning() ? "wynnlabs.quests.rescanning" : "wynnlabs.quests.rescan")
                        .withStyle(ChatFormatting.GOLD),
                L.c("wynnlabs.quests.rescan_tip").withStyle(ChatFormatting.GRAY),
                L.c("wynnlabs.quests.rescan_only_press").withStyle(ChatFormatting.DARK_GRAY));
    }

    /**
     * The quests tab's title line, on the right: how long ago the list was read and the "Yenile"
     * chip (only once quests are known); returns the width used, 0 when nothing is drawn.
     */
    private int questTitleRight(GuiGraphics g, int right, int y, int room, int mx, int my) {
        if (!Wyn.up(Wyn.Feature.QUESTS) || QuestAdvisor.known() == 0) {
            return 0;
        }
        boolean ready = Wyn.canRescanQuests() && !QuestBook.rescanning();
        String label = rescanLabel(true);
        int bw = font.width(label) + 22;
        int bx = right - bw;
        int by = y - 2;
        boolean hover = mx >= bx && mx < bx + bw && my >= by && my < by + 13;
        int state = hover && ready ? Kit.HOVER : Kit.REST;
        Kit.button(g, bx, by, bw, 13, state);
        woodIconText(g, "refresh", label, bx + 6, by + 3, state);
        hits.add(new Hit(bx, by, bw, 13, null, rescanTip(), "#qrescan:"));
        int used = bw;
        long at = QuestBook.readAt();
        if (at > 0) {
            String read = System.currentTimeMillis() - at < 60_000 ? L.t("wynnlabs.quests.read_now")
                    : L.t("wynnlabs.quests.read_ago", ago(at));
            int rw = font.width(read);
            if (used + 6 + rw <= room) {
                Kit.text(g, font, read, bx - 6 - rw, y, Theme.INK_3);
                used += 6 + rw;
            }
        }
        return used;
    }

    private void drawQuests(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        if (!Wyn.up(Wyn.Feature.QUESTS)) {
            ink(g, L.c("wynnlabs.wyn.quests_off", Wyn.version()).withStyle(ChatFormatting.GOLD), x, y + 4, 0xFFFFFFFF);
            return;
        }
        Map<String, List<QuestAdvisor.Suggestion>> lists = questLists();
        if (QuestAdvisor.known() == 0) {
            // the gold button has Wynntils read the book; the line under it says the manual way
            boolean ready = Wyn.canRescanQuests() && !QuestBook.rescanning();
            emptyState(g, x, y, w, h, railIcon(2), L.t("wynnlabs.quests.empty_title"),
                    new EmptyButton(rescanLabel(false), ready ? Kit.ON : Kit.REST, rescanTip(), "#qrescan:",
                            L.t("wynnlabs.quests.empty_manual")));
            return;
        }
        // 1. the quest you track, and a little map of where things are
        int cardW = w;
        int cy = y;
        String tracked = null;
        String task = "";
        try {
            if (Wyn.tracking()) {
                tracked = Wyn.trackedName();
                task = Wyn.trackedTask();
            }
        } catch (Throwable ignored) {
        }
        QuestWiki.Info tInfo = tracked == null ? null : QuestWiki.get(tracked);
        int tStage = QuestWiki.currentStage(tInfo, task);
        List<int[]> mapPoints = new ArrayList<>();
        List<Integer> mapColours = new ArrayList<>();
        if (tracked != null) {
            card(g, x, cy, cardW, 58, false, Theme.AMBER);
            Kit.outline(g, x - 1, cy - 1, cardW + 2, 60, Theme.AMBER_LO);
            int stages = tInfo == null ? 0 : tInfo.stages().size();
            QuestBook.Entry te = questEntry(tracked);
            int total = te == null ? 30 : QuestAdvisor.minutes(new QuestAdvisor.Suggestion(te, 0, -1, List.of()));
            int left = stages > 0 && tStage >= 0 ? Math.max(1, total * (stages - tStage) / stages) : -1;
            Component head = L.c("wynnlabs.quests.tracking").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(fit(QuestGuides.title(tracked), cardW - 150)).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(stages > 0 && tStage >= 0 ? L.t("wynnlabs.quests.stage", tStage + 1, stages) : "")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(left > 0 ? L.t("wynnlabs.quests.minutes_left", left) : "").withStyle(ChatFormatting.DARK_GRAY));
            Art.icon(g, "player-play", x + 8, cy + 7, 8, Theme.paperRgb(0xFFFFAA00));
            ink(g, head, x + 18, cy + 7, 0xFFFFFFFF);
            g.fill(x + 8, cy + 19, x + cardW - 8, cy + 22, Theme.WELL);
            if (stages > 0 && tStage >= 0) {
                g.fill(x + 8, cy + 19, x + 8 + (cardW - 16) * (tStage + 1) / stages, cy + 22, Theme.AMBER);
            }
            // the matched stage in the player's language, else the game's own line
            ink(g, fit(QuestWiki.stepText(tInfo, task), cardW - 16), x + 8, cy + 27, 0xFFCFCDC6);
            int bx = x + 8;
            bx += button(g, bx, cy + 39, L.t("wynnlabs.quests.btn_guide"), true, mx, my, "#guide:" + tracked + "\n" + task) + 4;
            int[] next = null;
            if (tInfo != null) {
                for (int i = Math.max(0, tStage); i < tInfo.stages().size() && next == null; i++) {
                    next = tInfo.stages().get(i).at();
                }
            }
            if (next != null) {
                bx += button(g, bx, cy + 39, L.t("wynnlabs.quests.btn_next"), false, mx, my,
                        "/compass at " + next[0] + " " + next[1] + " " + next[2]) + 4;
            }
            if (tInfo != null) {
                button(g, bx, cy + 39, L.t("wynnlabs.quests.btn_mark_all"), false, mx, my, "#qpins:" + tracked);
                for (int i = 0; i < tInfo.stages().size(); i++) {
                    for (int[] at : tInfo.stages().get(i).points()) {
                        mapPoints.add(at);
                        mapColours.add(i < tStage ? 0xFF5DCAA5 : i == tStage ? 0xFFEF9F27 : 0xFF85B7EB);
                    }
                }
            } else {
                ink(g, L.t("wynnlabs.quests.wiki_loading_short"), bx, cy + 42, FAINT);
            }
        } else if (layout.compact()) {
            // a short window: one line says it, the list gets the room
            card(g, x, cy, cardW, 18, false, 0);
            ink(g, Component.literal(fit(L.t("wynnlabs.quests.none_tracked") + " · " + L.t("wynnlabs.quests.pick_hint"), cardW - 16))
                    .withStyle(ChatFormatting.GRAY), x + 8, cy + 5, 0xFFFFFFFF);
        } else {
            card(g, x, cy, cardW, 58, false, 0);
            ink(g, L.c("wynnlabs.quests.none_tracked").withStyle(ChatFormatting.GRAY), x + 8, cy + 14, 0xFFFFFFFF);
            ink(g, L.t("wynnlabs.quests.pick_hint"), x + 8, cy + 30, FAINT);
        }
        // 2. this session's plan: the best safe quests near each other for about an hour
        List<QuestAdvisor.Suggestion> pool = new ArrayList<>();
        for (QuestAdvisor.Suggestion s : lists.getOrDefault("RECOMMENDED", List.of())) {
            if (s.quest().x() != null && !"HARD".equals(s.quest().difficulty()) && pool.size() < 8) {
                pool.add(s);
            }
        }
        List<QuestAdvisor.Suggestion> plan = new ArrayList<>();
        double px = Minecraft.getInstance().player == null ? 0 : Minecraft.getInstance().player.getX();
        double pz = Minecraft.getInstance().player == null ? 0 : Minecraft.getInstance().player.getZ();
        double sx = px;
        double sz = pz;
        int minutes = 0;
        double walk = 0;
        long xpSum = 0;
        while (!pool.isEmpty() && minutes < 60 && plan.size() < 4) {
            QuestAdvisor.Suggestion nearest = pool.get(0);
            double best = Double.MAX_VALUE;
            for (QuestAdvisor.Suggestion s : pool) {
                double d = Math.hypot(s.quest().x() - sx, s.quest().z() - sz);
                if (d < best) {
                    best = d;
                    nearest = s;
                }
            }
            pool.remove(nearest);
            plan.add(nearest);
            walk += best;
            minutes += QuestAdvisor.minutes(new QuestAdvisor.Suggestion(nearest.quest(), 0, (int) best, List.of()));
            xpSum += nearest.quest().xp();
            sx = nearest.quest().x();
            sz = nearest.quest().z();
        }
        cy += tracked == null && layout.compact() ? 23 : 63;
        // the plan line (not in the compact layout)
        if (!plan.isEmpty() && !layout.compact()) {
            List<String> names = new ArrayList<>();
            for (QuestAdvisor.Suggestion s : plan) {
                names.add(QuestGuides.title(s.quest().name()));
            }
            double levels = xpSum / (double) levelXp();
            Component pl = L.c("wynnlabs.quests.plan_head", minutes).withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(String.join(" → ", names)).withStyle(ChatFormatting.WHITE));
            Component gain = L.c("wynnlabs.quests.plan_levels", String.format(Locale.ROOT, "%.1f", levels)).withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(" · " + String.format(Locale.ROOT, "%.1f", walk / 1000.0) + " km").withStyle(ChatFormatting.DARK_GRAY));
            int gw = font.width(gain);
            ink(g, Component.literal(fit(pl.getString(), w - gw - 10)).withStyle(ChatFormatting.GRAY), x, cy, 0xFFFFFFFF);
            ink(g, gain, x + w - gw, cy, 0xFFFFFFFF);
            List<Component> tip = new ArrayList<>();
            tip.add(L.c("wynnlabs.quests.plan_tip").withStyle(ChatFormatting.GRAY));
            for (QuestAdvisor.Suggestion s : plan) {
                tip.add(Component.literal("• " + QuestGuides.title(s.quest().name()) + "  ").withStyle(ChatFormatting.WHITE)
                        .append(Component.literal(QuestAdvisor.fmt(s.quest().xp()) + " XP").withStyle(ChatFormatting.GOLD)));
            }
            tip.add(L.c("wynnlabs.quests.plan_click").withStyle(ChatFormatting.DARK_GRAY));
            StringBuilder cmd = new StringBuilder("#qplan:");
            for (QuestAdvisor.Suggestion s : plan) {
                cmd.append(s.quest().name()).append('\n');
            }
            hits.add(new Hit(x, cy - 1, w, 10, null, tip, cmd.toString()));
            cy += 13;
        }
        // 3. filters with their counts; in the compact layout one button that steps to the next
        int fx = x;
        for (int i = 0; i < QuestAdvisor.FILTERS.length && layout.compact(); i++) {
            String f = QuestAdvisor.FILTERS[i];
            if (!f.equals(questFilter)) {
                continue;
            }
            String next = QuestAdvisor.FILTERS[(i + 1) % QuestAdvisor.FILTERS.length];
            String label = L.t(QuestAdvisor.FILTER_NAMES[i]) + " " + lists.getOrDefault(f, List.of()).size() + "  ›";
            int cw = font.width(label) + 12;
            boolean hover = mx >= fx && mx < fx + cw && my >= cy && my < cy + 14;
            Kit.button(g, fx, cy, cw, 14, hover ? Kit.HOVER : Kit.ON);
            onWood(g, label, fx + 6, cy + 3, hover ? Kit.HOVER : Kit.ON);
            hits.add(new Hit(fx, cy, cw, 14, null, List.of(L.c("wynnlabs.quests.filter_next",
                    L.t(QuestAdvisor.FILTER_NAMES[(i + 1) % QuestAdvisor.FILTERS.length]))), "#qfilter:" + next));
        }
        for (int i = 0; i < QuestAdvisor.FILTERS.length && !layout.compact(); i++) {
            String f = QuestAdvisor.FILTERS[i];
            int n = lists.getOrDefault(f, List.of()).size();
            String label = L.t(QuestAdvisor.FILTER_NAMES[i]) + " " + n;
            boolean on = f.equals(questFilter);
            int cw = font.width(label) + 12;
            if (fx + cw > x + w) {
                break;
            }
            boolean hover = mx >= fx && mx < fx + cw && my >= cy && my < cy + 14;
            int state = on ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
            Kit.button(g, fx, cy, cw, 14, state);
            onWood(g, label, fx + 6, cy + 3, n == 0 && !on ? Kit.REST : state);
            hits.add(new Hit(fx, cy, cw, 14, null, null, "#qfilter:" + f));
            fx += cw + 3;
        }
        cy += 17;
        // 4. the list (left) and the selected quest (right)
        List<QuestAdvisor.Suggestion> list = lists.getOrDefault(questFilter, List.of());
        int listW = (w - 8) * 47 / 100;
        int colH = y + h - cy;
        int rowH = 26;                              // two lines and a tag: title +4, sub +15
        int fit = Math.max(1, (colH - 12) / rowH);
        questScroll = Math.max(0, Math.min(questScroll, Math.max(0, list.size() - fit)));
        if (questSelected == null || list.stream().noneMatch(s -> s.quest().name().equals(questSelected))) {
            String tr = tracked;
            questSelected = tr != null && list.stream().anyMatch(s -> s.quest().name().equals(tr))
                    ? tr : list.isEmpty() ? null : list.get(0).quest().name();
        }
        long lvXp = levelXp();
        int level = Wyn.level();
        int want = (int) Rules.num("questLevelMargin", 4);
        // quests that unlock gear: the ITEM filter's list (a reason's words depend on the language)
        java.util.Set<String> unlocking = new java.util.HashSet<>();
        lists.getOrDefault("ITEM", List.of()).forEach(u -> unlocking.add(u.quest().name()));
        for (int i = questScroll; i < list.size() && i < questScroll + fit; i++) {
            QuestAdvisor.Suggestion s = list.get(i);
            QuestBook.Entry q = s.quest();
            int ry = cy + (i - questScroll) * rowH;
            boolean sel = q.name().equals(questSelected);
            boolean hover = mx >= x && mx < x + listW && my >= ry && my < ry + rowH - 1;
            g.fill(x, ry, x + listW, ry + rowH - 1, sel ? Theme.SELECTED : hover ? Theme.CARD_HOVER : Theme.CARD);
            if (sel) {
                g.fill(x, ry, x + 2, ry + rowH - 1, ACCENT);
            }
            boolean started = "STARTED".equals(q.status());
            boolean done = "COMPLETED".equals(q.status());
            int badge = done ? 0xFF0F6E56 : started ? 0xFFBA7517 : "MINI_QUEST".equals(q.type()) ? 0xFF3C3489 : 0xFF185FA5;
            g.fill(x + 5, ry + 5, x + 21, ry + 21, badge);
            Kit.outline(g, x + 5, ry + 5, 16, 16, 0x55000000);
            String num = done ? "✔" : String.valueOf(i + 1);
            g.drawString(font, num, x + 13 - font.width(num) / 2, ry + 9, 0xFFFFFFFF, true);
            // right: XP and a tag
            String xp = q.xp() > 0 ? QuestAdvisor.fmt(q.xp()) : "";
            String pct = q.xp() > 0 ? " " + pct(Math.min(999, q.xp() * 100 / lvXp)) : "";
            int margin = level - q.level();
            String tag;
            int tagBg;
            int tagFg;
            boolean unlocks = unlocking.contains(q.name());
            QuestWiki.Info rowInfo = done ? null : QuestWiki.get(q.name());     // the visible rows only
            Map<String, int[]> profMissing = rowInfo == null ? Map.of() : Professions.missing(rowInfo.professions());
            if (done) {
                tag = L.t("wynnlabs.quests.tag_done");
                tagBg = TAG_GREEN;
                tagFg = Theme.GOOD;
            } else if (!profMissing.isEmpty()) {
                tag = L.t("wynnlabs.quests.tag_profession", profMissing.size());
                tagBg = TAG_RED;
                tagFg = Theme.BAD;
            } else if (unlocks) {
                tag = L.t("wynnlabs.quests.tag_unlocks");
                tagBg = TAG_PURPLE;
                tagFg = Theme.TIER_MYTHIC;
            } else if (margin < 0) {
                tag = L.t("wynnlabs.quests.tag_levels_later", -margin);
                tagBg = Theme.WELL;
                tagFg = MUTED;
            } else if ("HARD".equals(q.difficulty())) {
                tag = L.t("wynnlabs.quests.tag_hard");
                tagBg = TAG_AMBER;
                tagFg = Theme.WARN;
            } else if (margin < want) {
                tag = L.t("wynnlabs.quests.tag_bit_hard");
                tagBg = TAG_AMBER;
                tagFg = Theme.WARN;
            } else {
                tag = L.t("wynnlabs.quests.tag_easy");
                tagBg = TAG_GREEN;
                tagFg = Theme.GOOD;
            }
            int tw = Kit.Ui.tagW(font, tag);
            int rx = x + listW - 4;
            ink(g, Component.literal(xp).withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(pct).withStyle(ChatFormatting.DARK_GRAY)), rx - font.width(xp + pct), ry + 4, 0xFFFFFFFF);
            g.fill(rx - tw, ry + 13, rx, ry + 13 + Kit.Ui.TAG_H, tagBg);
            ink(g, tag, rx - tw + Kit.Ui.TAG_PAD, Kit.Ui.textY(ry + 13, Kit.Ui.TAG_H), tagFg);
            int textW = listW - 30 - Math.max(font.width(xp + pct), tw) - 8;
            ink(g, Component.literal(fit(QuestGuides.title(q.name()), textW)).withStyle(started ? ChatFormatting.GOLD : ChatFormatting.WHITE),
                    x + 26, ry + 4, 0xFFFFFFFF);
            // the distance first: the most useful number, the last one a narrow row would cut
            String sub = (s.distance() >= 0 ? dist(s.distance()) + " · " : "") + "Lv " + q.level() + " · " + QuestAdvisor.lengthName(q)
                    + (done ? "" : L.t("wynnlabs.quests.row_minutes", QuestAdvisor.minutes(s)));
            ink(g, fit(sub, textW), x + 26, ry + 15, MUTED);
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(QuestGuides.title(q.name())).withStyle(ChatFormatting.AQUA).withStyle(UiFont::bold));
            for (String r : s.reasons()) {
                tip.add(Component.literal("• " + L.cap(r)).withStyle(ChatFormatting.GRAY));
            }
            if (q.xp() > 0) {
                tip.add(L.c("wynnlabs.quests.tip_level_share", q.xp() * 100 / lvXp).withStyle(ChatFormatting.DARK_GRAY));
            }
            profMissing.forEach((p, hn) -> tip.add(Component.literal("✖ " + Professions.name(p) + " " + hn[0] + "/" + hn[1])
                    .withStyle(ChatFormatting.RED)));
            tip.add(L.c("wynnlabs.quests.click_detail").withStyle(ChatFormatting.DARK_GRAY));
            hits.add(new Hit(x, ry, listW, rowH - 1, null, tip, "#qsel:" + q.name()));
            if (DevHooks.ON) {
                DevHooks.part("quest", g.pose(), x, ry, listW, rowH - 1);
            }
        }
        if (list.isEmpty()) {
            ink(g, L.c("wynnlabs.quests.none_in_filter").withStyle(ChatFormatting.GRAY), x + 6, cy + 6, 0xFFFFFFFF);
        }
        String foot = list.size() > fit ? (questScroll + 1) + "–" + Math.min(list.size(), questScroll + fit) + " / " + list.size()
                + " · " + L.t("wynnlabs.screen.scroll_wheel") : L.n("wynnlabs.quests.known", QuestAdvisor.known());
        ink(g, fit(foot, listW), x, y + h - 9, FAINT);
        drawQuestDetail(g, x + listW + 8, cy, w - listW - 8, colH, tracked, task, mx, my, lists);
    }

    private void drawQuestDetail(GuiGraphics g, int x, int y, int w, int h, String tracked, String task, int mx, int my,
            Map<String, List<QuestAdvisor.Suggestion>> lists) {
        card(g, x, y, w, h, false, 0);
        if (questSelected == null) {
            ink(g, L.c("wynnlabs.quests.pick_left").withStyle(ChatFormatting.GRAY), x + 8, y + 8, 0xFFFFFFFF);
            return;
        }
        QuestBook.Entry q = questEntry(questSelected);
        QuestWiki.Info info = QuestWiki.get(questSelected);
        int ix = x + 7;
        int iy = y + 5;
        int iw = w - 14;
        ink(g, Component.literal(fit(QuestGuides.title(questSelected), iw)).withStyle(ChatFormatting.AQUA).withStyle(UiFont::bold), ix, iy, 0xFFFFFFFF);
        iy += Kit.Ui.LINE;
        List<String> sub = new ArrayList<>();
        if (q != null) {
            sub.add("Lv " + q.level());
            // the length as its time, as the list says it: "Orta" twice (length, difficulty) read as one
            QuestAdvisor.Suggestion row = new QuestAdvisor.Suggestion(q, 0, -1, List.of());
            for (QuestAdvisor.Suggestion s : lists.getOrDefault(questFilter, List.of())) {
                if (s.quest().name().equals(questSelected)) {
                    row = s;                        // the list's own row: the same minutes, walk included
                }
            }
            sub.add(L.t("wynnlabs.quests.about_minutes", QuestAdvisor.minutes(row)));
        }
        if (info != null && !info.difficulty().isEmpty()) {
            sub.add(info.difficulty().toLowerCase(Locale.ROOT).replace("easy", L.t("wynnlabs.quests.diff_easy"))
                    .replace("medium", L.t("wynnlabs.quests.diff_medium")).replace("hard", L.t("wynnlabs.quests.diff_hard")));
        }
        if (info != null && !info.province().isEmpty()) {
            sub.add(info.province());
        }
        if (q != null) {
            sub.add(q.met() ? L.t("wynnlabs.quests.prereq_ok") : L.t("wynnlabs.quests.prereq_missing"));
        }
        ink(g, fit(String.join(" · ", sub), iw), ix, iy, q != null && !q.met() ? 0xFFF09595 : MUTED);
        iy += Kit.Ui.LINE + 2;
        // the professions the quest needs: met in green, missing in red, with the level now
        if (info != null && !info.professions().isEmpty()) {
            int px = ix;
            for (var e : info.professions().entrySet()) {
                int have = Professions.level(e.getKey());
                boolean ok = have >= e.getValue();
                String t = Professions.name(e.getKey()) + " " + (have < 0 ? "?" : have) + "/" + e.getValue();
                int tw = font.width(t) + 10;
                if (px + tw > ix + iw) {
                    break;
                }
                int c = have < 0 ? MUTED : ok ? Theme.GOOD : Theme.BAD;
                inkIconText(g, ok ? "check" : "x", t, px, iy, c);
                List<Component> ptip = new ArrayList<>();
                ptip.add(Component.literal(Professions.name(e.getKey()) + " " + e.getValue()).withStyle(ChatFormatting.WHITE));
                ptip.add((have < 0 ? L.c("wynnlabs.prof.unknown") : ok ? L.c("wynnlabs.prof.met", have)
                        : L.cn("wynnlabs.prof.short", e.getValue() - have, e.getValue() - have, have)).withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED));
                hits.add(new Hit(px, iy - 1, tw, 10, null, ptip, null));
                px += tw + 10;
            }
            iy += Kit.Ui.LINE + 2;
        }
        // the actions, measured first: the content stops above their rows
        List<Object[]> acts = new ArrayList<>();
        if (q != null && !"COMPLETED".equals(q.status())) {
            acts.add(new Object[] {questSelected.equals(tracked) ? L.t("wynnlabs.quests.btn_tracked") : L.t("wynnlabs.quests.btn_track"),
                true, "#qtrack:" + questSelected});
        }
        if (questDetailTab == 3) {
            // the points tab: all of them, the stage you are on, or none
            acts.add(new Object[] {L.t("wynnlabs.quests.btn_mark_every"), false, "#qpins:" + questSelected});
            acts.add(new Object[] {L.t("wynnlabs.quests.btn_mark_stage"), false, "#qpinstage:" + questSelected});
        } else {
            acts.add(new Object[] {L.t("wynnlabs.quests.btn_mark"), false, "#qpins:" + questSelected});
            acts.add(new Object[] {L.t("wynnlabs.quests.btn_full_guide"), false, "#guide:" + questSelected + "\n"
                    + (questSelected.equals(tracked) ? task : q != null && q.task() != null ? q.task() : "")});
        }
        acts.add(new Object[] {L.t("wynnlabs.quests.btn_clear_pins"), false, "#qclear"});
        if (questDetailTab == 3 && layout.compact() && buttonRowExtra(x + w - 6 - ix, acts) > 0) {
            // the points tab on a compact panel: short names keep the buttons on one row, the points get it
            String[] shortNames = {questSelected.equals(tracked) ? L.t("wynnlabs.quests.btn_tracked_short")
                    : L.t("wynnlabs.quests.btn_track_short"), L.t("wynnlabs.quests.btn_mark_every_short"),
                L.t("wynnlabs.quests.btn_mark_stage_short"), L.t("wynnlabs.quests.btn_clear_pins_short")};
            int off = acts.size() - 3;              // the track button is not there on a finished quest
            for (int i = 0; i < acts.size(); i++) {
                acts.get(i)[0] = shortNames[i + 1 - off];
            }
        }
        int buttonsUp = buttonRowExtra(x + w - 6 - ix, acts);
        // tabs
        List<QuestWiki.Place> places = info == null ? List.of() : QuestTrail.places(questSelected, info, 0);
        String[] tabsNames = {L.t("wynnlabs.quests.tab_steps"), L.t("wynnlabs.quests.tab_enemies"), L.t("wynnlabs.quests.tab_rewards"),
            L.t("wynnlabs.quests.tab_points", places.size())};
        questDetailRect = new int[] {x, y, w, h};
        int tx = ix;
        int natural = 0;
        for (String t : tabsNames) {
            natural += font.width(t) + 12;
        }
        // a narrow panel: the short names, the room left over shared out evenly between the tabs;
        // equal tabs with the names cut when even those do not fit
        int extra = 0;
        int share = Integer.MAX_VALUE;
        if (natural > iw) {
            tabsNames = new String[] {L.t("wynnlabs.quests.tab_steps_short"), L.t("wynnlabs.quests.tab_enemies_short"),
                L.t("wynnlabs.quests.tab_rewards_short"), L.t("wynnlabs.quests.tab_points_short", places.size())};
            int shortW = 0;
            for (String t : tabsNames) {
                shortW += font.width(t) + 4;
            }
            if (shortW <= iw) {
                extra = (iw - shortW) / tabsNames.length;
            } else {
                share = iw / tabsNames.length;
            }
        }
        for (int i = 0; i < tabsNames.length; i++) {
            boolean on = i == questDetailTab;
            boolean even = share != Integer.MAX_VALUE;
            boolean spread = !even && natural > iw;
            String tn = even ? fit(tabsNames[i], share - 6) : tabsNames[i];
            int tw = even ? share - 4 : spread ? font.width(tn) + extra : font.width(tn) + 8;
            int pad = even || spread ? 2 : 4;
            ink(g, tn, tx + pad, iy, on ? 0xFFFFFFFF : MUTED);
            if (on) {
                g.fill(tx + pad - 2, iy + 10, tx + pad + font.width(tn) + 2, iy + 11, ACCENT);
            }
            hits.add(new Hit(tx, iy - 2, tw, 13, null, null, "#qtab:" + i));
            tx += tw + 4;
        }
        iy += 15;
        int bottom = y + h - 24 - buttonsUp;        // above the button rows and their 4px
        if (info == null) {
            ink(g, !QuestWiki.on() ? L.t("wynnlabs.quests.wiki_off") : QuestWiki.failed(questSelected)
                    ? L.t("wynnlabs.quests.wiki_not_found") : L.t("wynnlabs.quests.wiki_loading"), ix, iy, FAINT);
        } else if (questDetailTab == 0) {
            if (QuestWiki.newer(info)) {
                // the wiki changed after the translation was shipped: its English stages, said so
                ink(g, fit(L.t("wynnlabs.quests.newer_than_translation"), iw), ix, iy, Theme.WARN);
                iy += Kit.Ui.LINE_DENSE;
            }
            // stages: done ✔, current ▶ (with its first hints), the rest numbered; coordinates → "yol ›"
            int cur = questSelected.equals(tracked) ? QuestWiki.currentStage(info, task)
                    : q != null && "STARTED".equals(q.status()) ? QuestWiki.currentStage(info, q.task()) : -1;
            String key = questSelected + "|" + cur;
            if (!key.equals(stepScrollKey)) {
                stepScrollKey = key;
                stepScroll = Math.max(0, cur - 2);
            }
            stepScroll = Math.max(0, Math.min(stepScroll, info.stages().size() - 1));
            int stepsTop = iy;
            int last = stepScroll;
            for (int i = stepScroll; i < info.stages().size() && iy + 10 <= bottom; i++) {
                last = i;
                QuestWiki.Stage st = info.stages().get(i);
                int mc = i < cur ? 0xFF5DCAA5 : i == cur ? 0xFFFAC775 : FAINT;
                if (i < cur || i == cur) {
                    Art.icon(g, i < cur ? "check" : "player-play", ix, iy, 8, Theme.paperRgb(mc));
                } else {
                    ink(g, String.valueOf(i + 1), ix, iy, mc);
                }
                int textW = iw - 14 - (st.at() != null ? 38 : 0);
                String title = QuestWiki.stageTitle(st.title());
                String text = st.task().isEmpty() ? title : st.task();
                // the row without the coordinates and the words that point at them: "yol ›" and the tip keep those
                String shown = QuestWiki.placeText(text);
                text = shown.isEmpty() ? text : shown;
                // the large layout wraps a step to two lines instead of cutting it
                List<FormattedCharSequence> stepLines = layout.large() ? font.split(Component.literal(text), textW) : List.of();
                int stepRows = Math.max(1, Math.min(2, stepLines.size()));
                if (stepRows > 1 && iy + 10 + Kit.Ui.LINE_DENSE <= bottom) {
                    int c = i == cur ? 0xFFFFFFFF : i < cur ? MUTED : 0xFFCFCDC6;
                    ink(g, stepLines.get(0), ix + 12, iy, c);
                    ink(g, fit(stepRest(text, stepLines.get(0)), textW), ix + 12, iy + Kit.Ui.LINE_DENSE, c);
                } else {
                    stepRows = 1;
                    ink(g, fit(text, textW), ix + 12, iy, i == cur ? 0xFFFFFFFF : i < cur ? MUTED : 0xFFCFCDC6);
                }
                if (st.at() != null) {
                    String go = L.t("wynnlabs.quests.go");
                    int gx = ix + iw - font.width(go) - 9;
                    boolean hv = mx >= gx - 2 && mx < ix + iw && my >= iy - 1 && my < iy + 9;
                    ink(g, go, gx, iy, hv ? 0xFFCECBF6 : 0xFF7F77DD);
                    Art.icon(g, "chevron-right", ix + iw - 8, iy, 8, Theme.paperRgb(hv ? 0xFFCECBF6 : 0xFF7F77DD));
                    // the first tip line names the pin on the map and compass
                    hits.add(new Hit(gx - 2, iy - 1, font.width(go) + 13, 10, null,
                            List.of(Component.literal(title).withStyle(ChatFormatting.LIGHT_PURPLE),
                                    Component.literal(st.at()[0] + ", " + st.at()[1] + ", " + st.at()[2]).withStyle(ChatFormatting.GRAY)),
                            "/compass at " + st.at()[0] + " " + st.at()[1] + " " + st.at()[2]));
                }
                List<Component> tip = new ArrayList<>();
                // the title once, the task, the hints without dialogue: 12 lines at most, the rest in the full guide
                tip.add(Component.literal(title).withStyle(ChatFormatting.LIGHT_PURPLE));
                List<QuestWiki.Stage> en = QuestWiki.englishStages(info);
                QuestWiki.Tip t = QuestWiki.tip(st, i < en.size() ? en.get(i) : null, 46, 12);
                t.task().forEach(l -> tip.add(Component.literal(l).withStyle(ChatFormatting.WHITE)));
                t.hints().forEach(l -> tip.add(Component.literal(l).withStyle(ChatFormatting.GRAY)));
                if (t.more()) {
                    tip.add(L.c("wynnlabs.quests.tip_more").withStyle(ChatFormatting.DARK_GRAY));
                }
                hits.add(new Hit(ix, iy - 1, iw - 32, Kit.Ui.LINE_DENSE * stepRows, null, tip, null));
                iy += Kit.Ui.LINE_DENSE * stepRows;
                if (i == cur) {
                    // the stage you are on: its hints wrapped, not cut, so a puzzle's last line (its
                    // answer, often) shows too; more rows on a large page
                    int hintRows = layout.large() ? 10 : 4;
                    int used = 0;
                    hints:
                    for (String hint : st.hints()) {
                        for (FormattedCharSequence line : font.split(Component.literal(QuestWiki.shown(hint)), iw - 14)) {
                            if (used >= hintRows || iy + 10 > bottom) {
                                break hints;
                            }
                            ink(g, line, ix + 12, iy, MUTED);
                            iy += Kit.Ui.LINE_DENSE;
                            used++;
                        }
                    }
                }
            }
            int count = info.stages().size();
            if (stepScroll > 0 || last < count - 1) {
                // more steps than fit: where you are in them, a thin bar at the right edge (the wheel scrolls)
                int barH = Math.max(12, (bottom - stepsTop) * (last - stepScroll + 1) / count);
                int barY = stepsTop + (bottom - stepsTop - barH) * stepScroll / Math.max(1, count - 1 - (last - stepScroll));
                g.fill(x + w - 5, stepsTop, x + w - 3, bottom, 0x22000000);
                g.fill(x + w - 5, Math.min(barY, bottom - barH), x + w - 3, Math.min(barY, bottom - barH) + barH, 0x88000000);
            }
        } else if (questDetailTab == 1) {
            // enemies: level, health, what they are weak to; ✔ when your build's element is one of them
            List<String> mine = myElements();
            if (info.mobs().isEmpty()) {
                ink(g, L.t("wynnlabs.quests.no_mobs"), ix, iy, FAINT);
            }
            int lv = Wyn.level();
            for (QuestWiki.Mob m : info.mobs()) {
                if (iy + 20 > bottom) {
                    break;
                }
                boolean boss = m.health() >= 3000;
                ink(g, Component.literal(fit(m.name(), iw - 60)).withStyle(boss ? ChatFormatting.RED : ChatFormatting.WHITE)
                        .append(Component.literal(boss ? " boss" : "").withStyle(ChatFormatting.DARK_RED)), ix, iy, 0xFFFFFFFF);
                String lvText = "Lv " + m.level();
                ink(g, lvText, ix + iw - font.width(lvText), iy, m.level() > lv + 3 ? 0xFFF09595 : MUTED);
                List<String> weak = new ArrayList<>();
                boolean hits2 = false;
                for (String e : m.weak()) {
                    weak.add(elementTr(e));
                    hits2 |= mine.contains(e);
                }
                String info2 = L.t("wynnlabs.quests.mob_health", m.health()) + (weak.isEmpty() ? "" : L.t("wynnlabs.quests.mob_weak", String.join(", ", weak)));
                ink(g, Component.literal(fit(info2, iw - 60)).withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(hits2 ? L.t("wynnlabs.quests.mob_your_build") : "").withStyle(ChatFormatting.GREEN)), ix + 6, iy + 9, 0xFFFFFFFF);
                iy += 21;
            }
        } else if (questDetailTab == 3) {
            int cur = questSelected.equals(tracked) ? QuestWiki.currentStage(info, task)
                    : q != null && "STARTED".equals(q.status()) ? QuestWiki.currentStage(info, q.task()) : -1;
            drawPoints(g, places, cur, ix, iy, iw, bottom, mx, my);
        } else {
            // rewards: XP as part of a level, emeralds, items (gear: what it would do for your build)
            long xp = info.xp() > 0 ? info.xp() : q == null ? 0 : q.xp();
            if (xp > 0) {
                ink(g, Component.literal(QuestAdvisor.fmt(xp) + " XP").withStyle(ChatFormatting.GOLD)
                        .append(L.c("wynnlabs.quests.reward_level_share", xp * 100 / levelXp()).withStyle(ChatFormatting.DARK_GRAY)),
                        ix, iy, 0xFFFFFFFF);
                iy += Kit.Ui.LINE;
            }
            if (info.emeralds() > 0) {
                ink(g, L.c("wynnlabs.quests.reward_emeralds", info.emeralds()).withStyle(ChatFormatting.GREEN), ix, iy, 0xFFFFFFFF);
                iy += Kit.Ui.LINE;
            }
            for (String r : info.rewards()) {
                if (iy + 10 > bottom) {
                    break;
                }
                String gainText = "";
                boolean up = false;
                var gi = WynntilsItems.gearByName(r.replaceAll("^\\d+\\s*", "").trim());
                Build.Plan plan = Build.plan();
                if (gi != null && plan != null && plan.result().objective() > 0) {
                    int lvNow = Wyn.level();
                    if (gi.requirements().level() > lvNow) {
                        gainText = L.t("wynnlabs.quests.reward_at_level", gi.requirements().level());
                    } else {
                        String slot = Build.slotOf(gi.slot(), 0);
                        double v = slot == null ? Double.NaN
                                : Build.trySlot(plan.pieces(), plan.allocation(), slot, Damage.piece(gi), lvNow, plan.earned());
                        if (!Double.isNaN(v)) {
                            double gp = (v / plan.result().objective() - 1) * 100;
                            up = gp > 0.5;
                            gainText = up ? L.t("wynnlabs.quests.reward_gain", Math.round(gp)) : L.t("wynnlabs.quests.reward_no_gain");
                        }
                    }
                }
                ink(g, Component.literal(fit(r, iw - font.width(gainText) - 4)).withStyle(ChatFormatting.LIGHT_PURPLE)
                        .append(Component.literal(gainText).withStyle(up ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)),
                        ix, iy, 0xFFFFFFFF);
                iy += Kit.Ui.LINE;
            }
            if (xp <= 0 && info.emeralds() <= 0 && info.rewards().isEmpty()) {
                ink(g, L.t("wynnlabs.quests.no_rewards"), ix, iy, FAINT);
            }
        }
        buttonRow(g, ix, y + h - 20, x + w - 6 - ix, acts, mx, my);
    }

    /** What follows a wrapped first line of {@code text} (the first line read back as a string). */
    private static String stepRest(String text, FormattedCharSequence first) {
        StringBuilder sb = new StringBuilder();
        first.accept((i, style, cp) -> {
            sb.appendCodePoint(cp);
            return true;
        });
        return text.length() > sb.length() ? text.substring(sb.length()).trim() : "";
    }

    /** Each place's number: its stage and its place in that stage, "5.2". */
    static List<String> pointNumbers(List<QuestWiki.Place> places) {
        List<String> out = new ArrayList<>();
        int stage = -1;
        int k = 0;
        for (QuestWiki.Place p : places) {
            k = p.stage() == stage ? k + 1 : 1;
            stage = p.stage();
            out.add((p.stage() + 1) + "." + k);
        }
        return out;
    }

    /** "120 m", "1.4 km": how far a place is, along the ground. */
    private static String far(double blocks) {
        return blocks < 1000 ? Math.round(blocks) + " m" : String.format(Locale.ROOT, "%.1f km", blocks / 1000);
    }

    /**
     * The points tab: every place of the quest, numbered by stage, with what is there, where, how
     * far and "yol ›"; the stages you passed are struck through. Hover: the whole line; click: the compass.
     */
    private void drawPoints(GuiGraphics g, List<QuestWiki.Place> places, int cur, int ix, int iy, int iw, int bottom, int mx, int my) {
        if (places.isEmpty()) {
            for (String l : Advice.wrap(L.t("wynnlabs.quests.points_none"), Math.max(20, iw / 6))) {
                ink(g, l, ix, iy, FAINT);
                iy += Kit.Ui.LINE_DENSE;
            }
            return;
        }
        var pl = Minecraft.getInstance().player;
        double nearest = Double.MAX_VALUE;
        double[] dist = new double[places.size()];
        for (int i = 0; i < places.size(); i++) {
            int[] at = places.get(i).at();
            dist[i] = pl == null ? -1 : Math.hypot(pl.getX() - at[0], pl.getZ() - at[2]);
            if (pl != null && places.get(i).stage() >= Math.max(0, cur)) {
                nearest = Math.min(nearest, dist[i]);
            }
        }
        if (iy + 10 > bottom) {
            return;                                     // no room at all (a very short window)
        }
        String head = nearest == Double.MAX_VALUE ? L.n("wynnlabs.quests.points_count", places.size())
                : L.n("wynnlabs.quests.points_head", places.size(), places.size(), far(nearest));
        ink(g, fit(head, iw), ix, iy, MUTED);
        iy += Kit.Ui.LINE_DENSE + 2;
        List<String> nums = pointNumbers(places);
        // two lines a place: number, what is there and "yol ›"; under it where and how far
        int rowH = 2 * Kit.Ui.LINE_DENSE + 1;
        int rows = Math.max(1, (bottom - iy) / rowH);
        String key = questSelected + "|" + cur;
        if (!key.equals(pointScrollKey)) {
            // the passed stages' places stay above, a turn of the wheel away
            pointScrollKey = key;
            pointScroll = 0;
            while (cur > 0 && pointScroll < places.size() && places.get(pointScroll).stage() < cur) {
                pointScroll++;
            }
        }
        pointScroll = Math.max(0, Math.min(pointScroll, Math.max(0, places.size() - rows)));
        String go = L.t("wynnlabs.quests.go");
        int goW = font.width(go) + 11;
        int numW = font.width("00.00") + 4;
        int textW = iw - numW - goW;
        for (int i = pointScroll; i < places.size() && iy + rowH - 2 <= bottom; i++) {
            QuestWiki.Place p = places.get(i);
            int[] at = p.at();
            boolean done = cur >= 0 && p.stage() < cur;
            boolean now = p.stage() == cur;
            boolean hv = mx >= ix && mx < ix + iw && my >= iy - 1 && my < iy + rowH - 1;
            if (hv) {
                g.fill(ix - 2, iy - 2, ix + iw + 1, iy + rowH - 2, 0x22000000);
            }
            ink(g, nums.get(i), ix, iy, done ? FAINT : now ? 0xFFFAC775 : MUTED);
            Component what = Component.literal(fit(p.what(), textW - 4));
            ink(g, done ? what.copy().withStyle(ChatFormatting.STRIKETHROUGH) : what, ix + numW, iy,
                    done ? FAINT : now ? 0xFFFFFFFF : 0xFFCFCDC6);
            String where = at[0] + ", " + at[1] + ", " + at[2] + (dist[i] >= 0 ? "  ·  " + far(dist[i]) : "");
            ink(g, fit(where, textW), ix + numW, iy + Kit.Ui.LINE_DENSE - 1, FAINT);
            if (done) {
                Art.icon(g, "check", ix + iw - 9, iy, 8, Theme.paperRgb(0xFF5DCAA5));
            } else {
                ink(g, go, ix + iw - goW + 2, iy, hv ? 0xFFCECBF6 : 0xFF7F77DD);
                Art.icon(g, "chevron-right", ix + iw - 8, iy, 8, Theme.paperRgb(hv ? 0xFFCECBF6 : 0xFF7F77DD));
            }
            // the first tip line names the pin on the map and compass, like the stage list's "yol ›"
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(nums.get(i) + " " + p.what()).withStyle(ChatFormatting.LIGHT_PURPLE));
            for (String l : Advice.wrap(p.what(), 46)) {
                tip.add(Component.literal(l).withStyle(ChatFormatting.WHITE));
            }
            tip.add(Component.literal(QuestWiki.stageTitle("Stage " + (p.stage() + 1)) + " · " + at[0] + ", " + at[1] + ", " + at[2])
                    .withStyle(ChatFormatting.GRAY));
            tip.add(L.c("wynnlabs.quests.point_click").withStyle(ChatFormatting.DARK_GRAY));
            hits.add(new Hit(ix, iy - 1, iw, rowH, null, tip, "/compass at " + at[0] + " " + at[1] + " " + at[2]));
            iy += rowH;
        }
        if (places.size() > rows) {
            String more = (pointScroll + 1) + "–" + Math.min(places.size(), pointScroll + rows) + " / " + places.size();
            ink(g, more, ix + iw - font.width(more), bottom - 4, FAINT);     // clear of a button row right under it
        }
    }

    /**
     * A row of buttons ({text, primary, action}) that stays inside {@code maxW}: 7px each side of
     * the text when there is room, less down to 3px, and a second row above when even that does not fit.
     */
    /** How much higher than one row {@link #buttonRow} reaches: 17 when it needs two rows, else 0. */
    private int buttonRowExtra(int maxW, List<Object[]> acts) {
        int text = 0;
        for (Object[] a : acts) {
            text += font.width((String) a[0]);
        }
        int gaps = 4 * (acts.size() - 1);
        int pad = Math.max(3, Math.min(7, (maxW - gaps - text) / (2 * acts.size())));
        return text + gaps + 2 * pad * acts.size() > maxW && acts.size() > 1 ? 17 : 0;
    }

    private void buttonRow(GuiGraphics g, int x, int y, int maxW, List<Object[]> acts, int mx, int my) {
        int text = 0;
        for (Object[] a : acts) {
            text += font.width((String) a[0]);
        }
        int gaps = 4 * (acts.size() - 1);
        int pad = Math.max(3, Math.min(7, (maxW - gaps - text) / (2 * acts.size())));
        int split = acts.size();
        if (text + gaps + 2 * pad * acts.size() > maxW && acts.size() > 1) {
            split = (acts.size() + 1) / 2;          // two rows: the first half on top
            pad = 7;
        }
        int bx = x;
        int by = split < acts.size() ? y - 17 : y;
        for (int i = 0; i < acts.size(); i++) {
            if (i == split) {
                bx = x;
                by = y;
            }
            Object[] a = acts.get(i);
            bx += button(g, bx, by, (String) a[0], (boolean) a[1], mx, my, (String) a[2], pad) + 4;
        }
    }

    private int chip(GuiGraphics g, int x, int y, String text, int bg, int fg) {
        // a tag: 11px tall, 4px each side; the text keeps the line's y, the tag is centred on it
        String t = font.plainSubstrByWidth(text, 220);
        int w = Kit.Ui.tagW(font, t);
        g.fill(x, y - 2, x + w, y - 2 + Kit.Ui.TAG_H, bg);
        ink(g, t, x + Kit.Ui.TAG_PAD, y, fg);
        return w;
    }

    // ---- secret discoveries tab (Gizli Keşifler) ----

    private String discSelected;
    private int discScroll;
    private int discStepScroll;
    private int[] discDetailRect;

    /** A chip that is on or off, with its action; returns its width. */
    /** A chip with an 8 px icon before its label. */
    private int pickChip(GuiGraphics g, int x, int y, String icon, String label, boolean on, int mx, int my, String action) {
        int cw = font.width(label) + 22;
        boolean hover = mx >= x && mx < x + cw && my >= y && my < y + 14;
        int state = on ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
        Kit.button(g, x, y, cw, 14, state);
        woodIconText(g, icon, label, x + 6, y + 3, state);
        hits.add(new Hit(x, y, cw, 14, null, null, action));
        return cw;
    }

    private int pickChip(GuiGraphics g, int x, int y, String label, boolean on, int mx, int my, String action) {
        int cw = font.width(label) + 12;
        boolean hover = mx >= x && mx < x + cw && my >= y && my < y + 14;
        int state = on ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
        Kit.button(g, x, y, cw, 14, state);
        onWood(g, label, x + 6, y + 3, state);
        hits.add(new Hit(x, y, cw, 14, null, null, action));
        return cw;
    }

    /**
     * Secret discoveries: the order and filters on top (kept in settings), the list on the left
     * with a pin button at each row's end, and the chosen discovery on the right: lore,
     * requirements met or not, and its numbered steps with a pin for each place.
     */
    private void drawDiscoveries(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        GameState gs = GameState.current();
        java.util.Set<String> found = Discoveries.foundInGame();
        String character = gs.characterId();
        double[] pos = gs.position();
        int level = gs.level();
        Discoveries.Sort sort = Discoveries.sort();
        Discoveries.Show show = Discoveries.show();
        boolean within = Discoveries.withinLevel();
        List<Discoveries.Entry> sorted = Discoveries.sorted(Discoveries.all(), sort, pos[0], pos[1]);
        int[] counts = new int[3];
        int doneAll = 0;
        List<Discoveries.Entry> list = new ArrayList<>();
        for (Discoveries.Entry e : sorted) {
            boolean done = Discoveries.done(e, found, character);
            doneAll += done ? 1 : 0;
            if (!within || e.minLevel() <= level) {
                counts[done ? 1 : 0]++;
                counts[2]++;
            }
            if (Discoveries.shown(e, done, show, within, level)) {
                list.add(e);
            }
        }
        // 1. the order
        int cy = y;
        String sortLabel = L.t("wynnlabs.discoveries.sort");
        ink(g, sortLabel, x, cy + 2, FAINT);
        int fx = x + font.width(sortLabel) + 6;
        for (int i = 0; i < Discoveries.SORT_KEYS.length; i++) {
            fx += pickChip(g, fx, cy, L.t(Discoveries.SORT_KEYS[i]), sort.ordinal() == i, mx, my, "#discsort:" + i) + 3;
        }
        // the progress, and why it may still be 0
        boolean unread = found.isEmpty() && !DiscoveryBook.seen();
        String progress = unread ? L.t("wynnlabs.discoveries.book_unread") : L.t("wynnlabs.discoveries.progress", doneAll, sorted.size());
        // in the room the sort chips leave (cut short when narrow; the tip has it whole)
        String shown = fit(progress, x + w - fx - 6);
        int pw = font.width(shown);
        ink(g, shown, x + w - pw, cy + 2, unread ? 0xFFFAC775 : 0xFF9FE1CB);
        List<Component> ptip = new ArrayList<>();
        ptip.add(Component.literal(progress).withStyle(ChatFormatting.WHITE));
        for (String l : Advice.wrap(L.t("wynnlabs.discoveries.book_tip"), 48)) {
            ptip.add(Component.literal(l).withStyle(ChatFormatting.GRAY));
        }
        hits.add(new Hit(x + w - pw, cy, pw, 12, null, ptip, null));
        cy += 15;
        // 2. the filters, with their counts
        String showLabel = L.t("wynnlabs.discoveries.show");
        ink(g, showLabel, x, cy + 2, FAINT);
        fx = x + font.width(showLabel) + 6;
        for (int i = 0; i < Discoveries.SHOW_KEYS.length; i++) {
            fx += pickChip(g, fx, cy, L.t(Discoveries.SHOW_KEYS[i]) + " " + counts[i], show.ordinal() == i, mx, my, "#discshow:" + i) + 3;
        }
        fx += 6;
        pickChip(g, fx, cy, (within ? "✔ " : "") + L.t("wynnlabs.discoveries.within_level", level), within, mx, my, "#disclevel");
        cy += 17;
        // 3. the list (left) and the chosen discovery (right)
        int listW = (w - 8) * 46 / 100;
        int colH = y + h - cy;
        int rowH = 24;
        int fit = Math.max(1, (colH - 12) / rowH);
        discScroll = Math.max(0, Math.min(discScroll, Math.max(0, list.size() - fit)));
        if (discSelected == null || list.stream().noneMatch(e -> e.id().equals(discSelected))) {
            if (!list.isEmpty() && (discSelected == null || Discoveries.get(discSelected) == null)) {
                discSelected = list.get(0).id();
                discStepScroll = 0;
            }
        }
        for (int i = discScroll; i < list.size() && i < discScroll + fit; i++) {
            Discoveries.Entry e = list.get(i);
            int ry = cy + (i - discScroll) * rowH;
            boolean sel = e.id().equals(discSelected);
            boolean done = Discoveries.done(e, found, character);
            boolean hover = mx >= x && mx < x + listW && my >= ry && my < ry + rowH - 1;
            g.fill(x, ry, x + listW, ry + rowH - 1, sel ? Theme.SELECTED : hover ? Theme.CARD_HOVER : Theme.CARD);
            if (sel) {
                g.fill(x, ry, x + 2, ry + rowH - 1, ACCENT);
            }
            g.fill(x + 5, ry + 4, x + 20, ry + 19, done ? 0xFF3F7A34 : 0xFF5A4A7A);
            Kit.outline(g, x + 5, ry + 4, 15, 15, 0x55000000);
            String num = done ? "✔" : String.valueOf(i + 1);
            g.drawString(font, num, x + 13 - font.width(num) / 2, ry + 8, 0xFFFFFFFF, true);
            // the pin button at the row's end: the way to the first place, without opening the guide
            int bx = x + listW - 18;
            boolean pinHover = mx >= bx && mx < bx + 14 && my >= ry + 4 && my < ry + 18;
            Kit.button(g, bx, ry + 4, 14, 14, pinHover ? Kit.ON : Kit.REST);
            Art.icon(g, "map-pin", bx + 3, ry + 7, 8, pinHover ? Theme.INK : Theme.CREAM);
            hits.add(new Hit(bx, ry + 4, 14, 14, null, List.of(L.c("wynnlabs.discoveries.pin_tip", e.name()).withStyle(ChatFormatting.LIGHT_PURPLE)),
                    "#discpin:0:" + e.id()));
            int textW = listW - 26 - 24;
            ink(g, Component.literal(fit(e.name(), textW)).withStyle(done ? ChatFormatting.GRAY : ChatFormatting.WHITE),
                    x + 25, ry + 4, 0xFFFFFFFF);
            // the distance first: a narrow row cuts the region, not the number
            String sub = dist((int) Math.round(Discoveries.distance(e, pos[0], pos[1]))) + " · Lv " + e.minLevel() + " · " + e.region();
            ink(g, fit(sub, textW), x + 25, ry + 14, e.minLevel() > level ? Theme.BAD : MUTED);
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(e.name()).withStyle(ChatFormatting.AQUA).withStyle(UiFont::bold));
            for (String l : Advice.wrap(e.lore(), 48)) {
                tip.add(Component.literal(l).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            }
            tip.add(L.c("wynnlabs.quests.click_detail").withStyle(ChatFormatting.DARK_GRAY));
            hits.add(new Hit(x, ry, listW - 20, rowH - 1, null, tip, "#discsel:" + e.id()));
            if (DevHooks.ON) {
                DevHooks.part("disc", g.pose(), x, ry, listW - 20, rowH - 1);
            }
        }
        if (list.isEmpty()) {
            ink(g, L.c("wynnlabs.discoveries.none_shown").withStyle(ChatFormatting.GRAY), x + 6, cy + 6, 0xFFFFFFFF);
        }
        String foot = list.size() > fit ? (discScroll + 1) + "–" + Math.min(list.size(), discScroll + fit) + " / " + list.size()
                + " · " + L.t("wynnlabs.screen.scroll_wheel") : L.t("wynnlabs.discoveries.licence");
        ink(g, fit(foot, listW), x, y + h - 9, FAINT);
        Discoveries.Entry chosen = discSelected == null ? null : Discoveries.get(discSelected);
        drawDiscoveryDetail(g, x + listW + 8, cy, w - listW - 8, colH, chosen, found, character, pos, mx, my);
    }

    /** Extra space before each guide step after the first. */
    private static final int STEP_GAP = 6;

    private void drawDiscoveryDetail(GuiGraphics g, int x, int y, int w, int h, Discoveries.Entry e, java.util.Set<String> found,
            String character, double[] pos, int mx, int my) {
        card(g, x, y, w, h, false, 0);
        discDetailRect = new int[] {x, y, w, h};
        if (e == null) {
            ink(g, L.c("wynnlabs.discoveries.pick_left").withStyle(ChatFormatting.GRAY), x + 8, y + 8, 0xFFFFFFFF);
            return;
        }
        int ix = x + 7;
        int iy = y + 5;
        int iw = w - 14;
        boolean done = Discoveries.done(e, found, character);
        boolean byWynntils = Discoveries.foundByWynntils(e, found);
        ink(g, Component.literal(fit(e.name(), iw)).withStyle(ChatFormatting.AQUA).withStyle(UiFont::bold), ix, iy, 0xFFFFFFFF);
        iy += Kit.Ui.LINE;
        String sub = "Lv " + e.minLevel() + " · " + e.region() + " · " + dist((int) Math.round(Discoveries.distance(e, pos[0], pos[1])))
                + " · " + L.t(done ? "wynnlabs.discoveries.state_done" : "wynnlabs.discoveries.state_open");
        ink(g, fit(sub, iw), ix, iy, done ? 0xFF9FE1CB : MUTED);
        iy += Kit.Ui.LINE;
        // requirements, each met (green), unmet (red) or unknown (grey)
        Discoveries.Entry en = Discoveries.englishOf(e.id());
        if (!e.requires().isEmpty()) {
            List<QuestBook.Entry> quests = GameState.current().quests();
            net.minecraft.network.chat.MutableComponent line = L.c("wynnlabs.discoveries.needs").withStyle(ChatFormatting.DARK_GRAY);
            List<Component> tip = new ArrayList<>();
            tip.add(L.c("wynnlabs.discoveries.needs").withStyle(ChatFormatting.WHITE));
            for (int i = 0; i < e.requires().size(); i++) {
                Boolean met = en == null || i >= en.requires().size() ? null
                        : Discoveries.met(en, en.requires().get(i), quests, found, character);
                ChatFormatting c = met == null ? ChatFormatting.GRAY : met ? ChatFormatting.GREEN : ChatFormatting.RED;
                String mark = met == null ? "" : met ? "✔ " : "✘ ";
                line.append(Component.literal((i > 0 ? " · " : " ") + mark + e.requires().get(i)).withStyle(c));
                tip.add(Component.literal(mark + e.requires().get(i)).withStyle(c));
            }
            if (font.width(line) <= iw) {
                ink(g, line, ix, iy, 0xFFFFFFFF);
            } else {
                ink(g, Component.literal(fit(line.getString(), iw)).withStyle(ChatFormatting.GRAY), ix, iy, 0xFFFFFFFF);
            }
            hits.add(new Hit(ix, iy - 1, iw, Kit.Ui.LINE, null, tip, null));
            iy += Kit.Ui.LINE;
        }
        // the actions, measured first: the steps stop above their rows
        String tickText = byWynntils ? L.t("wynnlabs.discoveries.btn_found") : done ? L.t("wynnlabs.discoveries.btn_undo")
                : L.t("wynnlabs.discoveries.btn_done");
        List<Object[]> acts = List.of(
                new Object[] {tickText, !done, byWynntils ? "#discfound" : "#disctick:" + e.id()},
                new Object[] {L.t("wynnlabs.discoveries.btn_pin_all"), false, "#discpin:0:" + e.id()},
                new Object[] {L.t("wynnlabs.discoveries.btn_wiki"), false, e.wiki()});
        int bottom = y + h - 24 - buttonRowExtra(x + w - 6 - ix, acts);
        Discoveries.Guide guide = Discoveries.guide(e);
        String go = L.t("wynnlabs.quests.go");
        int textW = iw - 14 - font.width(go) - 13;
        List<Object[]> lines = new ArrayList<>();          // {step index, line, first line?}
        for (int s = 0; s < guide.steps().size(); s++) {
            List<net.minecraft.util.FormattedCharSequence> wrapped = font.split(Component.literal(guide.steps().get(s).text()), textW);
            for (int k = 0; k < wrapped.size(); k++) {
                lines.add(new Object[] {s, wrapped.get(k), k == 0});
            }
        }
        // the lore, in at most two lines, cut with "…" (the whole of it on hover); the steps come
        // first, so it keeps one line when its second would push a step out of sight
        int stepsH = lines.size() * Kit.Ui.LINE_DENSE + Math.max(0, guide.steps().size() - 1) * STEP_GAP;
        int notesH = guide.newer() ? 2 * Kit.Ui.LINE_DENSE + 2 : !QuestWiki.on() ? Kit.Ui.LINE : 0;
        int loreRows = bottom - (iy + 2 * Kit.Ui.LINE_DENSE + 10 + notesH) >= stepsH ? 2 : 1;
        List<String> lore = wrapText(e.lore(), iw, loreRows);
        List<Component> loreTip = new ArrayList<>();
        for (String l : Advice.wrap(e.lore(), 48)) {
            loreTip.add(Component.literal(l).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        }
        for (String l : lore) {
            ink(g, l, ix, iy, 0xFF77756E);
            iy += Kit.Ui.LINE_DENSE;
        }
        hits.add(new Hit(ix, iy - Kit.Ui.LINE_DENSE * lore.size(), iw, Kit.Ui.LINE_DENSE * lore.size(), null, loreTip, null));
        iy += 4;
        g.fill(ix, iy, ix + iw, iy + 1, Theme.LINE);       // a rule between the story and the steps
        iy += 6;
        if (guide.newer()) {
            for (net.minecraft.util.FormattedCharSequence l : font.split(L.c("wynnlabs.discoveries.newer"), iw)) {
                ink(g, l, ix, iy, 0xFFFAC775);
                iy += Kit.Ui.LINE_DENSE;
            }
            iy += 2;
        } else if (!QuestWiki.on()) {
            ink(g, fit(L.t("wynnlabs.discoveries.wiki_off"), iw), ix, iy, FAINT);
            iy += Kit.Ui.LINE;
        }
        // the steps, numbered, wrapped; "way ›" pins that step's place and the ones after it
        int room = Math.max(1, (bottom - iy) / Kit.Ui.LINE_DENSE);
        discStepScroll = Math.max(0, Math.min(discStepScroll, Math.max(0, lines.size() - room)));
        for (int i = discStepScroll; i < lines.size() && iy + 9 <= bottom; i++) {
            int s = (Integer) lines.get(i)[0];
            boolean first = (Boolean) lines.get(i)[2];
            Discoveries.Step st = guide.steps().get(s);
            if (first && i > discStepScroll) {
                iy += STEP_GAP;                                 // air between steps, so each reads as one
                if (iy + 9 > bottom) {
                    break;
                }
            }
            if (first) {
                ink(g, (s + 1) + ".", ix, iy, 0xFFAFA9EC);
                if (st.at() != null) {
                    int gx = ix + iw - font.width(go) - 9;
                    boolean hv = mx >= gx - 2 && mx < ix + iw && my >= iy - 1 && my < iy + 9;
                    ink(g, go, gx, iy, hv ? 0xFFCECBF6 : 0xFF7F77DD);
                    Art.icon(g, "chevron-right", ix + iw - 8, iy, 8, Theme.paperRgb(hv ? 0xFFCECBF6 : 0xFF7F77DD));
                    int py = st.at()[1] == DiscoveryWiki.NO_Y ? e.y() : st.at()[1];
                    hits.add(new Hit(gx - 2, iy - 1, font.width(go) + 13, 10, null, List.of(
                            L.c("wynnlabs.discoveries.pin", e.name(), s + 1).withStyle(ChatFormatting.LIGHT_PURPLE),
                            Component.literal(st.at()[0] + ", " + py + ", " + st.at()[2]).withStyle(ChatFormatting.GRAY)),
                            "#discpin:" + s + ":" + e.id()));
                }
            }
            ink(g, (net.minecraft.util.FormattedCharSequence) lines.get(i)[1], ix + 14, iy, 0xFFCFCDC6);
            iy += Kit.Ui.LINE_DENSE;
        }
        if (lines.size() > room) {
            String more = (discStepScroll + 1) + "–" + Math.min(lines.size(), discStepScroll + room) + " / " + lines.size();
            ink(g, more, x + w - 7 - font.width(more), y + 5, FAINT);
        }
        buttonRow(g, ix, y + h - 20, x + w - 6 - ix, acts, mx, my);
    }

    /** A click on the discoveries tab; true when it was one. */
    private boolean discoveryClick(String c) {
        if (c.startsWith("#discsort:")) {
            Settings.setNumber("discoverySort", Integer.parseInt(c.substring(10)));
            discScroll = 0;
        } else if (c.startsWith("#discshow:")) {
            Settings.setNumber("discoveryShow", Integer.parseInt(c.substring(10)));
            discScroll = 0;
        } else if (c.equals("#disclevel")) {
            Settings.setNumber("discoveryWithinLevel", Discoveries.withinLevel() ? 0 : 1);
            discScroll = 0;
        } else if (c.startsWith("#discsel:")) {
            String id = c.substring(9);
            if (!id.equals(discSelected)) {
                discSelected = id;
                discStepScroll = 0;
            }
        } else if (c.startsWith("#disctick:")) {
            String id = c.substring(10);
            String character = GameState.current().characterId();
            boolean now = !Discoveries.ticked(character, id);
            Discoveries.tick(character, id, now);
            flash = L.t(now ? "wynnlabs.discoveries.ticked" : "wynnlabs.discoveries.unticked");
            flashUntil = System.currentTimeMillis() + 2500;
        } else if (c.equals("#discfound")) {
            flash = L.t("wynnlabs.discoveries.found_by_book");
            flashUntil = System.currentTimeMillis() + 2500;
        } else if (c.startsWith("#discpin:")) {
            String[] p = c.substring(9).split(":", 2);
            Discoveries.Entry e = Discoveries.get(p[1]);
            if (e != null) {
                // the guide's steps (the wiki's newer ones when it changed), from the clicked one on
                List<int[]> pts = new ArrayList<>();
                List<String> labels = new ArrayList<>();
                Discoveries.pins(e, Discoveries.guide(e).steps(), Integer.parseInt(p[0]), pts, labels);
                int n = Waypoints.all(pts, labels);
                flash = n > 1 ? L.n("wynnlabs.quests.pins_added", n) : n == 1 ? L.t("wynnlabs.screen.guiding", labels.get(0))
                        : L.t("wynnlabs.home.mark_failed");
                flashUntil = System.currentTimeMillis() + 2500;
            }
        } else {
            return false;
        }
        return true;
    }

    // ---- settings tab: a field guide ----

    private int settingsCat;
    private int[] closeRect;
    private final List<int[]> langRects = new ArrayList<>();   // {x, y, w, h, choice}

    private static final String[] SETTING_CATS = {"about", "look", "tips", "glow", "coach", "quests", "build", "rules", "data"};
    private static final String[] SETTING_CAT_NAMES = {"wynnlabs.settings.cat_about", "wynnlabs.settings.cat_look",
        "wynnlabs.settings.cat_tips", "wynnlabs.settings.cat_glow", "wynnlabs.settings.cat_coach", "wynnlabs.settings.cat_quests",
        "wynnlabs.settings.cat_build", "wynnlabs.settings.cat_rules", "wynnlabs.settings.cat_data"};
    private static final String[] SETTING_CAT_DESC = {"wynnlabs.settings.catdesc_about", "wynnlabs.settings.catdesc_look",
        "wynnlabs.settings.catdesc_tips", "wynnlabs.settings.catdesc_glow", "wynnlabs.settings.catdesc_coach",
        "wynnlabs.settings.catdesc_quests", "wynnlabs.settings.catdesc_build", "wynnlabs.settings.catdesc_rules",
        "wynnlabs.settings.catdesc_data"};
    private static final String[] SETTING_CAT_ICONS = {"info-circle", "eye", "tag", "sparkles", "user", "map-2", "shield",
        "filter", "world"};
    private static final int SET_ROW = 32;
    /** A row of the settings list; a section title in it. */
    private static final int LIST_ROW = 14;
    private static final int LIST_HEAD = 13;

    static final String REPO = "https://github.com/anilken/wynnlabs";
    /** The About page's links: the source, a new issue (its template chooser), the licence. */
    private static final String[][] ABOUT_LINKS = {
        {"wynnlabs.about.source", REPO},
        {"wynnlabs.about.report", REPO + "/issues/new/choose"},
        {"wynnlabs.about.licence", REPO + "/blob/main/LICENSE"}};

    /** The row picked in each category, an index into its {@link #settingRows}: the first row until one is picked. */
    private final int[] settingsPick = new int[SETTING_CATS.length];
    /** Compact: the picked row's page fills the tab ("‹ Back" returns to the list). */
    private boolean settingsDetail;
    private int settingsScroll;
    private int settingsScrollMax;
    /** The list's box this frame, for the wheel. */
    private int[] settingsListRect;
    private int rowPitch = SET_ROW;

    /** One row of a settings category: kind switch, step, advice, arch, goal, scale, rule or key; "head" is a section title. */
    private record SetRow(String kind, String id, int rule) {

        boolean head() {
            return kind.equals("head");
        }

        RuleEditor.Rule r() {
            return RuleEditor.RULES.get(rule);
        }
    }

    /** A category's rows in the order the list shows them. */
    private static List<SetRow> settingRows(String cat) {
        List<SetRow> rows = new ArrayList<>();
        if (cat.equals("about")) {
            return rows;
        }
        if (cat.equals("rules")) {
            for (RuleEditor.Section s : RuleEditor.SECTIONS) {
                rows.add(new SetRow("head", s.title(), -1));
                for (int i : s.rules()) {
                    rows.add(new SetRow("rule", RuleEditor.RULES.get(i).key(), i));
                }
            }
            return rows;
        }
        if (cat.equals("build")) {
            rows.add(new SetRow("head", "wynnlabs.settings.sec_build_goal", -1));
            rows.add(new SetRow("advice", "advice_here", -1));
            rows.add(new SetRow("arch", "archetype", -1));
            rows.add(new SetRow("goal", "goal", -1));
        }
        java.util.Set<String> done = new java.util.HashSet<>();
        for (String[] sec : SETTING_SECTIONS.getOrDefault(cat, List.of())) {
            rows.add(new SetRow("head", sec[0], -1));
            for (int j = 1; j < sec.length; j++) {
                if (sec[j].equals("menu_scale")) {
                    rows.add(new SetRow("scale", "menu_scale", -1));
                } else if (Settings.LABELS.containsKey(sec[j]) && cat.equals(Settings.category(sec[j]))) {
                    addSwitch(rows, sec[j]);
                    done.add(sec[j]);
                }
            }
        }
        for (String k : Settings.LABELS.keySet()) {
            if (cat.equals(Settings.category(k)) && !done.contains(k)) {
                addSwitch(rows, k);                     // one no section names yet: at the end
            }
        }
        return rows;
    }

    /** A switch, its number right under it, and the key right under live prices. */
    private static void addSwitch(List<SetRow> rows, String k) {
        rows.add(new SetRow("switch", k, -1));
        for (var st : Settings.STEP_PARENT.entrySet()) {
            if (st.getValue().equals(k)) {
                rows.add(new SetRow("step", st.getKey(), -1));
            }
        }
        if (k.equals("priceApi")) {
            rows.add(new SetRow("key", "key", -1));
        }
    }

    /**
     * Each page's settings under section titles, as the Rules page has them: the title's key, then
     * the settings in order ("menu_scale" is the menu size row). A key a build does not have is
     * skipped; a setting no section names comes at the end.
     */
    private static final Map<String, List<String[]>> SETTING_SECTIONS = Map.of(
            "look", List.<String[]>of(
                    new String[] {"wynnlabs.settings.sec_text", "webFont", "menu_scale"}),
            "tips", List.of(
                    new String[] {"wynnlabs.settings.sec_advice", "itemAdvice", "altAdvice", "wornHint"},
                    new String[] {"wynnlabs.settings.sec_tooltip", "powderHint", "tooltipPrice", "boxPrices", "bankHint"}),
            "glow", List.of(
                    new String[] {"wynnlabs.settings.sec_bag", "glowUpgrade", "glowShop", "sellGlow", "verdictBadges",
                            "hotbarBadges"},
                    new String[] {"wynnlabs.settings.sec_ground", "groundGlow", "groundBeam", "groundGlowShop",
                            "groundGlowNormal"},
                    new String[] {"wynnlabs.settings.sec_menus", "glowTree"}),
            "coach", List.of(
                    new String[] {"wynnlabs.settings.sec_chat", "chatUpgrade", "chatSkills", "chatLevelUp"},
                    new String[] {"wynnlabs.settings.sec_warnings", "dangerWarn", "survivalWarn", "coachToasts"},
                    new String[] {"wynnlabs.settings.sec_screen", "dpsHud", "sellSummary"}),
            "quests", List.<String[]>of(
                    new String[] {"wynnlabs.settings.sec_quests", "glowQuest", "miniQuests"}),
            "build", List.<String[]>of(
                    new String[] {"wynnlabs.settings.sec_tree", "menuAdvice", "autoTree", "learning", "manaMeter"}),
            "data", List.of(
                    new String[] {"wynnlabs.settings.sec_online", "priceApi", "questGuides", "updateCheck"},
                    new String[] {"wynnlabs.settings.sec_dev", "debug"}));

    private static boolean isToggle(SetRow r) {
        return r.kind().equals("switch") || r.kind().equals("advice") || r.kind().equals("rule") && r.r().isSwitch();
    }

    private static boolean toggleOn(SetRow r) {
        return switch (r.kind()) {
            case "switch" -> Settings.on(r.id());
            case "advice" -> Rules.adviceHere();
            default -> RuleEditor.value(r.r()) > 0;
        };
    }

    private static String rowTitle(SetRow r) {
        return switch (r.kind()) {
            case "switch" -> L.t(Settings.LABELS.get(r.id()));
            case "step" -> L.t(Settings.STEP_LABELS.get(r.id()));
            case "advice" -> L.t("wynnlabs.settings.advice_here");
            case "arch" -> L.t("wynnlabs.settings.arch_title");
            case "goal" -> L.t("wynnlabs.settings.goal_title");
            case "scale" -> L.t("wynnlabs.settings.menu_scale");
            case "key" -> L.t("wynnlabs.settings.key_title");
            case "rule" -> L.t(r.r().title());
            default -> L.t(r.id());
        };
    }

    private static String rowDesc(SetRow r) {
        return switch (r.kind()) {
            case "switch", "step" -> L.t(Settings.DESC.get(r.id()));
            case "advice" -> L.t("wynnlabs.settings.advice_here_desc");
            case "arch" -> L.t("wynnlabs.settings.arch_desc");
            case "goal" -> L.t("wynnlabs.settings.goal_desc");
            case "scale" -> L.t("wynnlabs.settings.menu_scale_desc");
            case "key" -> L.t("wynnlabs.settings.key_hint");
            case "rule" -> L.t(r.r().desc());
            default -> "";
        };
    }

    /** What the row's value is in a few characters ("6 lv", "30 EB", "Boltslinger"); "" for a switch. */
    private static String shortValue(SetRow r) {
        return switch (r.kind()) {
            case "rule" -> r.r().key().equals("marketSlots") && RuleEditor.value(r.r()) <= 0
                    ? MarketSlots.autoShort() : L.cap(RuleEditor.display(r.r()));
            case "step" -> stepText(r.id(), Settings.stepValue(r.id()));
            case "arch" -> TreePlanner.chosen().isEmpty() ? L.t("wynnlabs.settings.archetype_off") : TreePlanner.chosen();
            case "goal" -> Setup.goalName(Setup.goal());
            case "scale" -> MenuScale.setting() == 0 ? L.t("wynnlabs.settings.scale_game") : String.valueOf(MenuScale.setting());
            case "key" -> L.t(Prices.embeddedKeyInUse() && !Prices.keyInvalid ? "wynnlabs.settings.key_state_builtin"
                    : Prices.maskedKey() == null ? "wynnlabs.settings.key_state_none"
                    : Prices.keyInvalid ? "wynnlabs.settings.key_state_invalid" : "wynnlabs.settings.key_state_saved");
            default -> "";
        };
    }

    /** Whether the row has a default to go back to. */
    private static boolean hasDefault(SetRow r) {
        return switch (r.kind()) {
            case "switch", "step", "advice", "scale", "rule" -> true;
            default -> false;
        };
    }

    /** Whether the row differs from its default: a gold dot in the list, a count on its category's chip. */
    private static boolean rowChanged(SetRow r) {
        return switch (r.kind()) {
            case "switch" -> Settings.changed(r.id());
            case "step" -> Settings.stepValue(r.id()) != Settings.stepDefault(r.id());
            case "advice" -> !Rules.adviceHere();
            case "scale" -> MenuScale.setting() != 0;
            case "rule" -> RuleEditor.changed(r.r());
            default -> false;
        };
    }

    private static int changedIn(String cat) {
        int n = 0;
        for (SetRow r : settingRows(cat)) {
            if (!r.head() && rowChanged(r)) {
                n++;
            }
        }
        return n;
    }

    /** "Default: on", "Default 4 lv · from 1 to 20"; null for a row without a default. */
    private static String rowDefault(SetRow r) {
        String on = L.t("wynnlabs.settings.state_on");
        String off = L.t("wynnlabs.settings.state_off");
        return switch (r.kind()) {
            case "switch" -> L.t("wynnlabs.settings.default", Settings.byDefault(r.id()) ? on : off);
            case "advice" -> L.t("wynnlabs.settings.default", on);
            case "step" -> {
                int[] options = Settings.STEPS.get(r.id());
                yield L.t("wynnlabs.settings.default_range", stepText(r.id(), Settings.stepDefault(r.id())),
                        stepText(r.id(), options[0]), stepText(r.id(), options[options.length - 1]));
            }
            case "scale" -> {
                int pick = MenuScale.setting();
                int used = MenuScale.effective();
                String d = L.t("wynnlabs.settings.default", L.t("wynnlabs.settings.menu_scale_game"));
                yield pick > 0 && used != pick ? d + " · " + L.t("wynnlabs.settings.menu_scale_used", used) : d;
            }
            case "rule" -> {
                RuleEditor.Rule rule = r.r();
                if (rule.isSwitch()) {
                    yield L.t("wynnlabs.settings.default", on);
                }
                double[] range = RuleEditor.range(rule);
                yield L.t("wynnlabs.settings.default_range", RuleEditor.format(rule, rule.def()),
                        RuleEditor.format(rule, range[0]), RuleEditor.format(rule, range[1]));
            }
            default -> null;
        };
    }

    /** The row's example ("wynnlabs.settings.example." + its id), filled with what the value means now. */
    private static String rowExample(SetRow r) {
        String id = r.kind().equals("rule") ? RuleEditor.id(r.r()) : r.id();
        Object[] args = new Object[0];
        if (r.kind().equals("step")) {
            args = new Object[] {Settings.stepValue(id)};
        }
        if (r.kind().equals("rule") && !r.r().isSwitch()) {
            RuleEditor.Rule rule = r.r();
            long v = Math.round(RuleEditor.value(rule));
            int lv = 80;                                    // the examples' character is level 80
            args = switch (id) {
                case "too_low" -> new Object[] {lv - v - 1, lv - v};
                case "too_high" -> new Object[] {lv + v + 1};
                case "old_gear", "quest_margin", "grind_below" -> new Object[] {lv - v};
                case "ahead", "grind_above" -> new Object[] {lv + v};
                case "min_gain" -> new Object[] {RuleEditor.format(rule, v), RuleEditor.format(rule, Math.max(0, v - 1))};
                case "reroll" -> new Object[] {RuleEditor.format(rule, Math.max(0, v - 1)), RuleEditor.format(rule, v)};
                case "market_slots" -> new Object[] {MarketSlots.slots()};
                default -> new Object[] {RuleEditor.format(rule, v)};
            };
        }
        return L.t("wynnlabs.settings.example." + id, args);
    }

    /** The picked row of the shown category, moved off a section title. */
    private int pickedRow(List<SetRow> rows) {
        int p = Math.max(0, Math.min(rows.size() - 1, settingsPick[settingsCat]));
        while (p < rows.size() - 1 && rows.get(p).head()) {
            p++;
        }
        settingsPick[settingsCat] = p;
        return p;
    }

    /**
     * Settings as a field guide: the categories as chips along the top (each with how many of its
     * settings differ from their default), a short list of the category's settings on the left
     * and, on the right, the picked one explained: what it does, an example, its value with its
     * control, its default and range, and a reset. Compact: the list fills the page and a picked
     * row opens over it. The About category shows the version, the update check and the links.
     */
    private void drawSettings(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        scaleRects.clear();
        keyBoxShown = false;                    // set again where the key box is drawn
        ruleBoxRect = null;
        settingsListRect = null;
        if (settingsCat < 0 || settingsCat >= SETTING_CATS.length) {
            settingsCat = 0;
        }
        String cat = SETTING_CATS[settingsCat];
        List<SetRow> rows = settingRows(cat);
        boolean compact = layout.compact();
        if (compact && settingsDetail && !rows.isEmpty()) {
            // the picked row over the whole page; its "‹ Back" line says where you are
            drawSettingDetail(g, rows.get(pickedRow(rows)), pickedRow(rows), x, y, w, h, mx, my, true);
            return;
        }
        int chipsH = drawSettingChips(g, x, y, w, mx, my);
        int top = y + chipsH + 5;
        int bottom = y + h;
        if (cat.equals("about")) {
            drawAboutPage(g, x, top, w, bottom - top, mx, my);
            return;
        }
        if (cat.equals("rules")) {
            bottom -= 18;
            drawRulesFooter(g, x, bottom + 4, w, mx, my);
        }
        int pick = pickedRow(rows);
        if (compact) {
            drawSettingList(g, rows, pick, x, top, w, bottom - top, mx, my);
            return;
        }
        int listW = Math.max(180, Math.min(260, w * 38 / 100));
        drawSettingList(g, rows, pick, x, top, listW, bottom - top, mx, my);
        drawSettingDetail(g, rows.get(pick), pick, x + listW + 8, top, w - listW - 8, bottom - top, mx, my, false);
    }

    /** The category chips, wrapped onto a second line when they do not fit; returns their height. */
    private int drawSettingChips(GuiGraphics g, int x, int y, int w, int mx, int my) {
        boolean icons = !layout.compact();
        int cx = x;
        int cy = y;
        for (int c = 0; c < SETTING_CATS.length; c++) {
            String name = L.t(SETTING_CAT_NAMES[c]);
            int changed = changedIn(SETTING_CATS[c]);
            String count = String.valueOf(changed);
            int cw = 12 + (icons ? 11 : 0) + font.width(name) + (changed > 0 ? 10 + font.width(count) : 0);
            if (cx > x && cx + cw > x + w) {
                cx = x;
                cy += 17;
            }
            cw = Math.min(cw, w);
            boolean sel = c == settingsCat;
            boolean hover = mx >= cx && mx < cx + cw && my >= cy && my < cy + 14;
            int state = sel ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
            Kit.button(g, cx, cy, cw, 14, state);
            int tx = cx + 6;
            if (icons) {
                Art.icon(g, SETTING_CAT_ICONS[c], tx, cy + 3, 8, Kit.buttonText(state));
                tx += 11;
            }
            int room = cx + cw - 6 - tx - (changed > 0 ? 10 + font.width(count) : 0);
            onWood(g, fit(name, room), tx, cy + 3, state);
            if (changed > 0) {
                int dx = cx + cw - 6 - font.width(count) - 8;
                dot(g, dx, cy + 4);
                onWood(g, count, dx + 8, cy + 3, state);
            }
            toggles.add(new Object[] {new int[] {cx, cy, cw, 14}, "#cat:" + c});
            if (hover) {
                List<Component> tip = new ArrayList<>();
                tip.add(Component.literal(name).withStyle(ChatFormatting.WHITE));
                tip.add(L.c(SETTING_CAT_DESC[c]).withStyle(ChatFormatting.GRAY));
                if (changed > 0) {
                    tip.add(L.c("wynnlabs.settings.changed_count", changed).withStyle(ChatFormatting.GOLD));
                }
                hits.add(new Hit(cx, cy, cw, 14, null, tip, null));
            }
            cx += cw + 3;
        }
        return cy + 14 - y;
    }

    /** The gold dot of a setting changed from its default. */
    private static void dot(GuiGraphics g, int x, int y) {
        Art.box(g, x, y, 6, 6, 3, Theme.GOLD_DEEP, Theme.GOLD_EDGE);
    }

    /** Rules: how many differ from their default, and the reset of this character's rules. */
    private void drawRulesFooter(GuiGraphics g, int x, int y, int w, int mx, int my) {
        String reset = L.t("wynnlabs.rules.reset");
        int bw = font.width(reset) + 24;
        int bx = x + w - bw;
        boolean bh = mx >= bx && mx < bx + bw && my >= y && my < y + 14;
        Kit.button(g, bx, y, bw, 14, bh ? Kit.HOVER : Kit.REST);
        Art.icon(g, "refresh", bx + 6, y + 3, 8, Kit.buttonText(bh ? Kit.HOVER : Kit.REST));
        onWood(g, reset, bx + 17, y + 3, bh ? Kit.HOVER : Kit.REST);
        toggles.add(new Object[] {new int[] {bx, y, bw, 14}, "#rulesreset"});
        int n = changedIn("rules");
        String info = n > 0 ? L.t("wynnlabs.settings.changed_in", n, L.t("wynnlabs.settings.cat_rules"))
                : L.t("wynnlabs.rules.per_character");
        int tx = x;
        if (n > 0) {
            dot(g, x, y + 4);
            tx += 9;
        }
        ink(g, fit(info, bx - tx - 8), tx, y + 3, Theme.INK_3);
    }

    /** The category's settings: a label and its value, a switch clicked right in the row; the picked row lit. */
    private void drawSettingList(GuiGraphics g, List<SetRow> rows, int pick, int x, int y, int w, int h, int mx, int my) {
        Kit.card(g, x, y, w, h, 0);
        int top = y + 3;
        int bottomY = y + h - 3;
        int contentH = 0;
        for (SetRow r : rows) {
            contentH += r.head() ? LIST_HEAD : LIST_ROW;
        }
        int avail = bottomY - top;
        settingsScrollMax = Math.max(0, contentH - avail);
        settingsScroll = Math.max(0, Math.min(settingsScrollMax, settingsScroll));
        settingsListRect = new int[] {x, y, w, h};
        int ix = x + 3;
        int iw = w - 6 - (settingsScrollMax > 0 ? 5 : 0);
        // − and + right in the row where the list is wide enough (compact's full page, the large menu)
        boolean steppers = iw >= 236;
        int rowMy = my >= top && my < bottomY ? my : -10000;    // no hover on a row scrolled out
        int t0 = toggles.size();
        g.enableScissor(x + 1, top, x + w - 1, bottomY);
        int ry = top - settingsScroll;
        for (int i = 0; i < rows.size(); i++) {
            SetRow r = rows.get(i);
            int rh = r.head() ? LIST_HEAD : LIST_ROW;
            if (ry + rh > top && ry < bottomY) {
                drawListRow(g, r, i, i == pick, ix, ry, iw, rh, mx, rowMy, steppers);
            }
            ry += rh;
        }
        g.disableScissor();
        // click areas: only the part that shows
        for (int i = toggles.size() - 1; i >= t0; i--) {
            if (toggles.get(i)[0] instanceof int[] r) {
                int y0 = Math.max(r[1], top);
                int y1 = Math.min(r[1] + r[3], bottomY);
                if (y1 <= y0) {
                    toggles.remove(i);
                } else {
                    toggles.set(i, new Object[] {new int[] {r[0], y0, r[2], y1 - y0}, toggles.get(i)[1]});
                }
            }
        }
        if (settingsScrollMax > 0) {
            // where in the list you are: a thin bar at the right edge
            int barH = Math.max(12, avail * avail / contentH);
            int barY = top + (avail - barH) * settingsScroll / settingsScrollMax;
            g.fill(x + w - 6, top, x + w - 4, bottomY, 0x22000000);
            g.fill(x + w - 6, barY, x + w - 4, barY + barH, 0x88000000);
        }
    }

    private void drawListRow(GuiGraphics g, SetRow r, int i, boolean picked, int x, int y, int w, int h, int mx, int my,
            boolean steppers) {
        if (r.head()) {
            section(g, L.t(r.id()), x + 2, y + 3, w - 4);
            return;
        }
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        if (picked) {
            g.fill(x, y, x + w, y + h, Theme.SELECTED);
        } else if (hover) {
            g.fill(x, y, x + w, y + h, Theme.CARD_HOVER);
        }
        boolean changed = rowChanged(r);
        int right = x + w - 3;
        int ctrlX;
        if (isToggle(r)) {
            // a switch stays one click: its pill flips it right here
            int px = right - 19;
            Kit.toggle(g, px, y + (h - 11) / 2, toggleOn(r));
            toggles.add(new Object[] {new int[] {px - 3, y, right - px + 3, h}, "#stog:" + i});
            ctrlX = px - 3;
        } else if (steppers && (r.kind().equals("rule") || r.kind().equals("step"))) {
            String v = shortValue(r);
            int vw = Math.max(42, font.width(v) + 8);
            int bx = right - (vw + 26);
            int sy = y + (h - 12) / 2;
            stepButton(g, bx, sy, "−", mx, my, stepId(r, -1));
            g.fill(bx + 13, sy, bx + 13 + vw, sy + 12, Theme.WELL);
            ink(g, v, bx + 13 + (vw - font.width(v)) / 2, sy + 2, Theme.INK);
            stepButton(g, bx + 14 + vw, sy, "+", mx, my, stepId(r, 1));
            ctrlX = bx - 2;
        } else {
            String v = fit(shortValue(r), w / 2);
            ctrlX = right - font.width(v);
            ink(g, v, ctrlX, y + (h - 8) / 2, changed ? Theme.INK : Theme.INK_3);
            ctrlX -= 2;
        }
        String title = rowTitle(r);
        int lx = x + 4;
        String cut = fit(title, ctrlX - lx - (changed ? 12 : 4));
        ink(g, cut, lx, y + (h - 8) / 2, isToggle(r) && !toggleOn(r) ? Theme.INK_2 : Theme.INK);
        if (changed) {
            dot(g, lx + font.width(cut) + 4, y + (h - 6) / 2);
        }
        toggles.add(new Object[] {new int[] {x, y, ctrlX - x, h}, "#ssel:" + i});
        if (hover && mx < ctrlX && !cut.equals(title)) {
            rowTip(x, y, ctrlX - x, h, title, rowDesc(r));
        }
    }

    /**
     * The picked setting explained: where it sits, its name (a gold dot when changed), what it
     * does, its value with its control, the default and range, an example, and the reset.
     * {@code page}: compact, over the whole tab, with "‹ Back" on top.
     */
    private void drawSettingDetail(GuiGraphics g, SetRow r, int index, int x, int y, int w, int h, int mx, int my, boolean page) {
        Kit.card(g, x, y, w, h, 0);
        int px = x + 8;
        int pw = w - 16;
        int cy = y + 6;
        int bottom = y + h - 6;
        String crumb = L.t(SETTING_CAT_NAMES[settingsCat]);
        if (r.kind().equals("rule")) {
            for (RuleEditor.Section s : RuleEditor.SECTIONS) {
                for (int i : s.rules()) {
                    if (i == r.rule()) {
                        crumb += " › " + L.t(s.title());
                    }
                }
            }
        }
        if (page) {
            String back = L.t("wynnlabs.settings.back");
            int bw = font.width(back) + 22;
            boolean bh = mx >= px && mx < px + bw && my >= cy && my < cy + 14;
            int st = bh ? Kit.HOVER : Kit.REST;
            Kit.button(g, px, cy, bw, 14, st);
            Art.icon(g, "chevron-left", px + 5, cy + 3, 8, Kit.buttonText(st));
            onWood(g, back, px + 16, cy + 3, st);
            toggles.add(new Object[] {new int[] {px, cy, bw, 14}, "#sback"});
            ink(g, fit(crumb, pw - bw - 8), px + bw + 8, cy + 3, Theme.INK_3);
            cy += 20;
        } else {
            ink(g, fit(crumb, pw), px, cy, Theme.INK_3);
            cy += 12;
        }
        // the name, bold, and "changed" with its dot on the right
        boolean changed = rowChanged(r);
        String chText = L.t("wynnlabs.settings.changed");
        int chW = changed ? font.width(chText) + 10 : 0;
        List<FormattedCharSequence> titleLines = font.split(Component.literal(rowTitle(r)).withStyle(UiFont::bold), pw - chW - 4);
        for (int i = 0; i < Math.min(2, titleLines.size()); i++) {
            ink(g, titleLines.get(i), px, cy + i * 10, Theme.INK);
        }
        if (changed) {
            dot(g, px + pw - chW, cy + 1);
            ink(g, chText, px + pw - chW + 9, cy, Theme.WARN);
        }
        cy += 10 * Math.min(2, Math.max(1, titleLines.size())) + 3;

        // what is left once the control, its default and the reset are placed
        boolean reset = hasDefault(r);
        int resetY = bottom - 14;
        int floor = reset ? resetY - 4 : bottom;
        // what it does: up to three lines (two on a short page)
        List<String> desc = wrapText(rowDesc(r), pw, 3);
        int ctrlH = r.kind().equals("key") ? keyDetailHeight(pw) : 14;
        String def = rowDefault(r);
        int needBelow = ctrlH + (def != null ? 11 : 0) + 4;
        while (desc.size() > 1 && cy + desc.size() * 10 + 4 + needBelow > floor) {
            desc = wrapText(rowDesc(r), pw, desc.size() - 1);
        }
        for (String line : desc) {
            ink(g, line, px, cy, Theme.INK_2);
            cy += 10;
        }
        cy += 4;

        // the value and its control, then the default and range beside it (or under it)
        int[] used = drawSettingControl(g, r, index, px, cy, pw, mx, my);
        if (def != null) {
            int dx = px + used[0] + 10;
            if (dx + font.width(def) <= px + pw) {
                ink(g, def, dx, cy + (used[1] - 8) / 2, Theme.INK_3);
                cy += used[1] + 5;
            } else {
                cy += used[1] + 3;
                ink(g, fit(def, pw), px, cy, Theme.INK_3);
                cy += 13;
            }
        } else {
            cy += used[1] + 5;
        }

        // an example of what it does, in as many lines as the room left takes
        String example = rowExample(r);
        int lines = Math.min(4, (floor - cy - 20) / 10);
        if (lines >= 1 && !example.isEmpty()) {
            List<String> ex = new ArrayList<>();
            for (String para : example.split("\n")) {
                ex.addAll(wrapText(para, pw - 12, Math.max(1, lines - ex.size())));
                if (ex.size() >= lines) {
                    break;
                }
            }
            int bh = 15 + ex.size() * 10 + 4;           // the last line clear of the frame, as the title is
            g.fill(px, cy, px + pw, cy + bh, Theme.PAPER);
            Kit.outline(g, px, cy, pw, bh, Theme.LINE);
            inkIconText(g, "eye", L.t("wynnlabs.settings.example_title"), px + 6, cy + 4, Theme.INK_3);
            for (int i = 0; i < ex.size(); i++) {
                ink(g, ex.get(i), px + 6, cy + 16 + i * 10, Theme.INK);
            }
        }

        // back to the default (or a note that it is there)
        if (reset) {
            int rx = px;
            if (changed) {
                String label = L.t("wynnlabs.settings.reset_one");
                int bw = font.width(label) + 24;
                boolean bh = mx >= rx && mx < rx + bw && my >= resetY && my < resetY + 14;
                int st = bh ? Kit.HOVER : Kit.REST;
                Kit.button(g, rx, resetY, bw, 14, st);
                Art.icon(g, "refresh", rx + 6, resetY + 3, 8, Kit.buttonText(st));
                onWood(g, label, rx + 17, resetY + 3, st);
                toggles.add(new Object[] {new int[] {rx, resetY, bw, 14}, "#sreset:" + index});
                rx += bw + 8;
            } else {
                String at = L.t("wynnlabs.settings.at_default");
                ink(g, fit(at, pw), rx, resetY + 3, Theme.INK_3);
                rx += font.width(at) + 8;
            }
            if (r.kind().equals("rule") && rx < px + pw - 40) {
                ink(g, fit(L.t("wynnlabs.rules.per_character"), px + pw - rx), rx, resetY + 3, Theme.INK_3);
            }
        }
    }

    /** The control of the picked row at x, y; returns the width and height it took. */
    private int[] drawSettingControl(GuiGraphics g, SetRow r, int index, int x, int y, int w, int mx, int my) {
        if (isToggle(r)) {
            boolean on = toggleOn(r);
            String state = L.t(on ? "wynnlabs.settings.state_on" : "wynnlabs.settings.state_off");
            int cw = 24 + font.width(state);
            boolean hover = mx >= x - 2 && mx < x + cw + 2 && my >= y && my < y + 14;
            if (hover) {
                g.fill(x - 2, y, x + cw + 2, y + 14, Theme.CARD_HOVER);
            }
            Kit.toggle(g, x, y + 1, on);
            ink(g, state, x + 24, y + 3, on ? Theme.GOOD : Theme.INK_2);
            toggles.add(new Object[] {new int[] {x - 2, y, cw + 4, 14}, "#stog:" + index});
            return new int[] {cw, 14};
        }
        switch (r.kind()) {
            case "rule" -> {
                RuleEditor.Rule rule = r.r();
                String value = L.cap(RuleEditor.display(rule));
                boolean emerald = rule.unit().equals("E");
                int vw = emerald ? 64 : Math.max(44, font.width(value) + 10);
                int sy = y + 1;
                stepButton(g, x, sy, "−", mx, my, "#rule:" + r.rule() + ":-1");
                if (emerald) {
                    // the amount typed in the box, the unit on the chip after it (click: E, EB, LE);
                    // − + still step through the usual amounts
                    double v = RuleEditor.value(rule);
                    if (ruleBoxFor != r.rule()) {
                        ruleUnit = v >= 4096 && v % 4096 == 0 ? 2 : v >= 64 && v % 64 == 0 ? 1 : 0;
                    }
                    if (!ruleBox.isFocused()) {
                        double inUnit = v / UNIT_MULT[ruleUnit];
                        ruleBox.setValue(inUnit == Math.rint(inUnit) ? String.valueOf((long) inUnit)
                                : String.valueOf(Math.round(inUnit * 100) / 100.0));
                    }
                    ruleBoxFor = r.rule();
                    int nw = 40;
                    textBox(g, ruleBox, x + 14, sy, nw, "wynnlabs.rules.type_hint", mx, my, 0f);
                    ruleBoxRect = new int[] {x + 14, sy, nw, 12};
                    String u = UNIT_NAMES[ruleUnit];
                    int ux = x + 15 + nw;
                    int uw = vw + 2 - nw - 1;
                    boolean uh = mx >= ux && mx < ux + uw && my >= sy && my < sy + 12;
                    Kit.button(g, ux, sy, uw, 12, uh ? Kit.HOVER : Kit.REST);
                    onWood(g, u, ux + (uw - font.width(u)) / 2, sy + 2, uh ? Kit.HOVER : Kit.REST);
                    toggles.add(new Object[] {new int[] {ux, sy, uw, 12}, "#ruleunit"});
                } else {
                    g.fill(x + 14, sy, x + 16 + vw, sy + 12, Theme.WELL);
                    ink(g, value, x + 15 + (vw - font.width(value)) / 2, sy + 2, Theme.INK);
                }
                stepButton(g, x + 16 + vw, sy, "+", mx, my, "#rule:" + r.rule() + ":1");
                return new int[] {28 + vw, 14};
            }
            case "step" -> {
                String value = shortValue(r);
                int vw = Math.max(44, font.width(value) + 10);
                int sy = y + 1;
                stepButton(g, x, sy, "−", mx, my, stepId(r, -1));
                g.fill(x + 14, sy, x + 16 + vw, sy + 12, Theme.WELL);
                ink(g, value, x + 15 + (vw - font.width(value)) / 2, sy + 2, Theme.INK);
                stepButton(g, x + 16 + vw, sy, "+", mx, my, stepId(r, 1));
                return new int[] {28 + vw, 14};
            }
            case "arch", "goal" -> {
                String chip = shortValue(r) + " ›";
                int cw = Math.min(w, font.width(chip) + 14);
                boolean hover = mx >= x && mx < x + cw && my >= y && my < y + 14;
                Kit.button(g, x, y, cw, 14, hover ? Kit.HOVER : Kit.REST);
                onWood(g, fit(chip, cw - 14), x + 7, y + 3, hover ? Kit.HOVER : Kit.REST);
                toggles.add(new Object[] {new int[] {x, y, cw, 14}, r.kind().equals("arch") ? "#archetype" : "#goal"});
                return new int[] {cw, 14};
            }
            case "scale" -> {
                return new int[] {scaleButtons(g, x, y, w, mx, my), 14};
            }
            case "key" -> {
                return new int[] {w, drawKeyDetail(g, x, y, w, mx, my)};
            }
            default -> {
                return new int[] {0, 14};
            }
        }
    }

    /** Appearance → Menu scale: Same as the game, 2, 3 or 4 (MenuScale); returns the width taken. */
    private int scaleButtons(GuiGraphics g, int x, int y, int w, int mx, int my) {
        int pick = MenuScale.setting();
        String game = L.t("wynnlabs.settings.menu_scale_game");
        if (font.width(game) + 12 + 3 * 25 > w) {
            game = L.t("wynnlabs.settings.scale_game");
        }
        String[] names = {game, "2", "3", "4"};
        int[] values = {0, 2, 3, 4};
        int bx = x;
        for (int i = 0; i < names.length; i++) {
            int bw = Math.max(22, font.width(names[i]) + 12);
            boolean sel = pick == values[i];
            boolean hover = mx >= bx && mx < bx + bw && my >= y && my < y + 14;
            int state = sel ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
            Kit.button(g, bx, y, bw, 14, state);
            g.drawString(font, names[i], bx + (bw - font.width(names[i])) / 2, y + 3, Kit.buttonText(state), !sel);
            scaleRects.add(new int[] {bx, y, bw, 14, values[i]});
            bx += bw + 3;
        }
        return bx - 3 - x;
    }

    /** The settings row for the picked row's switch or reset, by its list index. */
    private void settingsAction(String id) {
        List<SetRow> rows = settingRows(SETTING_CATS[settingsCat]);
        int i = Integer.parseInt(id.substring(id.indexOf(':') + 1));
        if (i < 0 || i >= rows.size() || rows.get(i).head()) {
            return;
        }
        SetRow r = rows.get(i);
        if (id.startsWith("#ssel:")) {
            settingsPick[settingsCat] = i;
            settingsDetail = layout.compact();
            return;
        }
        if (id.startsWith("#stog:")) {
            if (!layout.compact()) {
                settingsPick[settingsCat] = i;      // the panel follows the switch you flipped
            }
            switch (r.kind()) {
                case "switch" -> {
                    Settings.toggle(r.id());
                    if ("webFont".equals(r.id())) {
                        rebuildWidgets();           // init() again: the screen and its boxes take the new font now
                    }
                }
                case "advice" -> adviceHere(!Rules.adviceHere());
                default -> RuleEditor.step(r.r(), 1);
            }
            return;
        }
        // #sreset: this one setting back to its default
        switch (r.kind()) {
            case "switch" -> {
                Settings.reset(r.id());
                if ("webFont".equals(r.id())) {
                    rebuildWidgets();
                }
            }
            case "step" -> Settings.resetNumber(r.id());
            case "advice" -> adviceHere(true);
            case "scale" -> Settings.resetNumber("menuScale");
            case "rule" -> RuleEditor.reset(r.r());
            default -> {
                return;
            }
        }
        flash = L.t("wynnlabs.settings.reset_done", rowTitle(r));
        flashUntil = System.currentTimeMillis() + 2500;
    }

    /** Advice on this character on or off; the road map follows. */
    private void adviceHere(boolean on) {
        Rules.setAdviceHere(on);
        steps = rebuild();
        refreshAsked = Rules.adviceHere();
    }

    /** The preview's shots: this category, this row picked, and (compact) its page open. */
    void previewSettings(int cat, int row, boolean detail) {
        settingsCat = cat;
        settingsPick[cat] = row;
        settingsDetail = detail;
        settingsScroll = 0;
    }

    // ---- settings: About ----

    /** "v0.3.0" for "0.3.0"; "?" when unknown. */
    private static String vName(String v) {
        if (v == null || v.isBlank()) {
            return "?";
        }
        return Character.isDigit(v.charAt(0)) ? "v" + v : v;
    }

    private static String loaderLabel() {
        try {
            String l = Platform.get().loaderName();
            return l.equals("neoforge") ? "NeoForge" : l.equals("fabric") ? "Fabric" : l;
        } catch (Throwable e) {
            return "?";
        }
    }

    /**
     * About: whether this is the newest Wynn Labs (up to date, a newer one out, or why it could not
     * tell) with its button, the links (source, a bug report, the licence), the mod's version and
     * loader, and which Wynntils features work.
     */
    private void drawAboutPage(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        UpdateCheck.State s = UpdateCheck.state();
        String mine = vName(s.own().isEmpty() ? UpdateCheck.version.get() : s.own());
        String loader = loaderLabel();
        String title;
        String sub;
        String icon;
        int iconColour;
        String button;
        String action;
        switch (s.status()) {
            case UP_TO_DATE -> {
                title = L.t("wynnlabs.about.uptodate_title");
                sub = L.t("wynnlabs.about.uptodate_sub", mine, loader, ago(s.checkedAt()));
                icon = "check";
                iconColour = Theme.GOOD;
                button = L.t("wynnlabs.about.check_again");
                action = "#upd:check";
            }
            case NEWER -> {
                title = L.t("wynnlabs.about.newer_title", s.latest());
                sub = s.published() > 0 ? L.t("wynnlabs.about.newer_sub", mine, ago(s.published()))
                        : L.t("wynnlabs.about.newer_sub_nodate", mine);
                icon = "arrow-up";
                iconColour = Theme.WARN;
                button = L.t("wynnlabs.about.open_github");
                action = "#upd:open";
            }
            case FAILED -> {
                title = L.t("wynnlabs.about.failed_title");
                sub = L.t(switch (s.reason()) {
                    case OFFLINE -> "wynnlabs.about.reason_offline";
                    case OFF -> "wynnlabs.about.reason_off";
                    case DEV -> "wynnlabs.about.reason_dev";
                    default -> "wynnlabs.about.reason_no_answer";
                });
                icon = "alert-triangle";
                iconColour = Theme.BAD;
                button = s.reason() == UpdateCheck.Reason.OFF ? L.t("wynnlabs.about.go_setting")
                        : s.reason() == UpdateCheck.Reason.DEV ? null : L.t("wynnlabs.about.try_again");
                action = s.reason() == UpdateCheck.Reason.OFF ? "#upd:setting" : "#upd:check";
            }
            case CHECKING -> {
                title = L.t("wynnlabs.about.checking_title");
                sub = L.t("wynnlabs.about.idle_sub", mine, loader);
                icon = "refresh";
                iconColour = Theme.INK_3;
                button = null;
                action = null;
            }
            default -> {
                title = L.t("wynnlabs.about.idle_title");
                sub = L.t("wynnlabs.about.idle_sub", mine, loader);
                icon = "clock";
                iconColour = Theme.INK_3;
                button = L.t("wynnlabs.about.check_now");
                action = "#upd:check";
            }
        }
        boolean newer = s.status() == UpdateCheck.Status.NEWER;
        int cardH = 40;
        Kit.card(g, x, y, w, cardH, newer ? Theme.GOLD_DEEP : 0, newer);
        if (newer) {
            Kit.outline(g, x - 1, y - 1, w + 2, cardH + 2, Theme.GOLD_DEEP);
        }
        Art.icon(g, icon, x + 8, y + 9, 12, Theme.paperRgb(iconColour));
        int tx = x + 26;
        int bx = x + w - 8;
        if (button != null) {
            boolean linkOut = "#upd:open".equals(action);
            int bw = font.width(button) + 14 + (linkOut ? 11 : 0);
            bx -= bw;
            int by = y + (cardH - 14) / 2;
            boolean bh = mx >= bx && mx < bx + bw && my >= by && my < by + 14;
            int st = newer ? Kit.ON : bh ? Kit.HOVER : Kit.REST;
            Kit.button(g, bx, by, bw, 14, st);
            if (linkOut) {
                Art.icon(g, "arrow-up-right", bx + 6, by + 3, 8, Kit.buttonText(st));
            }
            g.drawString(font, button, bx + 7 + (linkOut ? 11 : 0), by + 3, Kit.buttonText(st), st != Kit.ON);
            toggles.add(new Object[] {new int[] {bx, by, bw, 14}, action});
            if (linkOut && s.url() != null) {
                hits.add(new Hit(bx, by, bw, 14, null, List.of(L.c("wynnlabs.about.link_tip", s.url())), null));
            }
        }
        int room = bx - tx - 8;
        ink(g, Component.literal(fit(title, room - 6)).withStyle(UiFont::bold), tx, y + 8, Theme.INK);
        List<String> subLines = wrapText(sub, room, 2);
        for (int i = 0; i < subLines.size(); i++) {
            ink(g, subLines.get(i), tx, y + 20 + i * 9, Theme.INK_2);
        }
        int cy = y + cardH + 8;

        // the links, each opened in the browser only after the game's "open this link?" prompt
        section(g, L.t("wynnlabs.about.links"), x, cy, w);
        cy += 12;
        int lx = x;
        for (int i = 0; i < ABOUT_LINKS.length; i++) {
            String label = L.t(ABOUT_LINKS[i][0]);
            int bw = font.width(label) + 25;
            if (lx > x && lx + bw > x + w) {
                lx = x;
                cy += 17;
            }
            boolean bh = mx >= lx && mx < lx + bw && my >= cy && my < cy + 14;
            int st = bh ? Kit.HOVER : Kit.REST;
            Kit.button(g, lx, cy, bw, 14, st);
            Art.icon(g, "arrow-up-right", lx + 6, cy + 3, 8, Kit.buttonText(st));
            onWood(g, label, lx + 17, cy + 3, st);
            toggles.add(new Object[] {new int[] {lx, cy, bw, 14}, "#link:" + i});
            if (bh) {
                hits.add(new Hit(lx, cy, bw, 14, null, List.of(L.c("wynnlabs.about.link_tip", ABOUT_LINKS[i][1])), null));
            }
            lx += bw + 4;
        }
        cy += 19;
        ink(g, fit(L.t("wynnlabs.about.mod_line", mine, loader), w), x, cy, Theme.INK_3);
        cy += 14;
        // which Wynntils features work, when there is room for it
        if (y + h - cy >= 30) {
            drawAbout(g, x, cy, w, mx, my);
        }
    }

    /** Opens a GitHub page in the browser after the game's "open this link?" prompt; anything else is refused. */
    private void openLink(String url) {
        if (url == null || !url.startsWith("https://github.com/")) {
            return;
        }
        net.minecraft.client.gui.screens.ConfirmLinkScreen.confirmLinkNow(this, java.net.URI.create(url), true);
    }

    /** The About page's buttons: check again, open the release, the setting, a link. */
    private void aboutClick(String id) {
        switch (id) {
            case "#upd:check" -> {
                if (!UpdateCheck.checkNow()) {
                    UpdateCheck.State s = UpdateCheck.state();
                    if (s.status() != UpdateCheck.Status.FAILED) {
                        flash = L.t("wynnlabs.about.wait");
                        flashUntil = System.currentTimeMillis() + 2500;
                    }
                }
            }
            case "#upd:open" -> openLink(UpdateCheck.state().url());
            case "#upd:setting" -> {
                // Prices and data, the update check picked
                for (int c = 0; c < SETTING_CATS.length; c++) {
                    if (SETTING_CATS[c].equals("data")) {
                        settingsCat = c;
                        List<SetRow> rows = settingRows("data");
                        for (int i = 0; i < rows.size(); i++) {
                            if (rows.get(i).id().equals("updateCheck")) {
                                settingsPick[c] = i;
                            }
                        }
                        settingsScroll = 0;
                        settingsDetail = layout.compact();
                    }
                }
            }
            default -> {
                int i = Integer.parseInt(id.substring("#link:".length()));
                if (i >= 0 && i < ABOUT_LINKS.length) {
                    openLink(ABOUT_LINKS[i][1]);
                }
            }
        }
    }

    // ---- the rule box (an emerald rule's amount) and the AI tab's switch rows ----

    private net.minecraft.client.gui.components.EditBox ruleBox;
    private int ruleBoxFor = -1;                    // the rule the box shows, an index in RuleEditor.RULES
    private int[] ruleBoxRect;
    private int ruleUnit;                           // 0 E, 1 EB, 2 LE
    private static final String[] UNIT_NAMES = {"E", "EB", "LE"};
    private static final double[] UNIT_MULT = {1, 64, 4096};

    /** Saves what was typed in the rule box, in the chip's unit; a value that is no value is left as it was. */
    private void saveRuleBox() {
        if (ruleBoxFor >= 0 && ruleBoxFor < RuleEditor.RULES.size()) {
            RuleEditor.Rule r = RuleEditor.RULES.get(ruleBoxFor);
            String typed = ruleBox.getValue().trim();
            // a unit typed in the box ("1 le") wins over the chip
            boolean ok = RuleEditor.set(r, typed.matches(".*[a-zA-Z].*") ? typed : typed + " " + UNIT_NAMES[ruleUnit]);
            flash = ok ? L.t("wynnlabs.rules.typed_ok", L.t(r.title()), RuleEditor.display(r)) : L.t("wynnlabs.rules.typed_bad");
            flashUntil = System.currentTimeMillis() + 2500;
        }
        ruleBox.setFocused(false);
        setFocused(null);
    }

    /** A switch with its description (the AI tab's rows); the whole row is clickable. */
    private void drawSettingRow(GuiGraphics g, Object[] row, int x, int y, int w, int mx, int my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + rowPitch - 2;
        if (hover) {
            g.fill(x - 3, y - 1, x + w, y + rowPitch - 3, Theme.CARD_HOVER);
        }
        String k = (String) row[1];
        boolean on = Settings.on(k);
        pill(g, x, y + 9, on);
        String title = L.t(Settings.LABELS.get(k));
        String desc = L.t(Settings.DESC.get(k));
        ink(g, fit(title, w - 26), x + 26, y + 5, on ? 0xFFE8E6DF : MUTED);
        // a taller row (the AI tab's) has room for the description's second line
        descWrap(g, desc, x + 26, y + 17, w - 26, rowPitch > SET_ROW ? 2 : 1);
        toggles.add(new Object[] {new int[] {x, y, w, rowPitch - 2}, k});
        if (hover) {
            rowTip(x, y, w, rowPitch - 2, title, desc);
        }
    }

    private static final float DESC_SCALE = 0.85f;

    /** A setting's one-line description: a little smaller and dimmer than its title, cut to fit. */
    private void desc(GuiGraphics g, String text, int x, int y, int w) {
        String cut = fit(text, (int) (w / DESC_SCALE));
        scaledText(g, Component.literal(cut), x, y, DESC_SCALE, 0xFF6E6C78);
    }

    /** A description wrapped to at most {@code lines} lines, 9px apart; the last one cut with "…". Returns the lines drawn. */
    private int descWrap(GuiGraphics g, String text, int x, int y, int w, int lines) {
        List<String> rows = wrapText(text, (int) (w / DESC_SCALE), lines);
        for (int i = 0; i < rows.size(); i++) {
            scaledText(g, Component.literal(rows.get(i)), x, y + i * 9, DESC_SCALE, 0xFF6E6C78);
        }
        return rows.size();
    }

    /** {@code text} split into at most {@code lines} lines of {@code w}px, the last one cut with "…" when more is left. */
    private List<String> wrapText(String text, int w, int lines) {
        List<String> out = new ArrayList<>();
        String rest = text.trim();
        while (!rest.isEmpty() && out.size() < lines) {
            if (out.size() == lines - 1 || font.width(rest) <= w) {
                out.add(fit(rest, w));
                break;
            }
            String head = font.plainSubstrByWidth(rest, w);
            int cut = head.lastIndexOf(' ');
            if (cut > 0) {
                head = head.substring(0, cut);
            }
            out.add(head);
            rest = rest.substring(head.length()).trim();
        }
        return out;
    }

    /** A setting's full title and description as a tooltip, for the text the row had to cut. */
    private void rowTip(int x, int y, int w, int h, String title, String desc) {
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(title).withStyle(ChatFormatting.WHITE));
        for (String l : Advice.wrap(desc, 44)) {
            tip.add(Component.literal(l).withStyle(ChatFormatting.GRAY));
        }
        hits.add(new Hit(x, y, w, h, null, tip, null));
    }

    /** An on/off switch: a small pill with its knob left (off) or right (on). */
    private void pill(GuiGraphics g, int x, int y, boolean on) {
        Kit.toggle(g, x, y, on);
    }

    private void stepButton(GuiGraphics g, int x, int y, String s, int mx, int my, String id) {
        boolean hover = mx >= x && mx < x + 12 && my >= y && my < y + 12;
        String tex = s.equals("+") ? "wynn/gui/button/plus" : "wynn/gui/button/minus";
        Kit.button(g, x, y, 12, 12, hover ? Kit.HOVER : Kit.REST);
        if (!WynnTex.draw(g, tex, x + 2, y + 2, 8, 8)) {
            onWood(g, s, x + (12 - font.width(s)) / 2 + 1, y + 2, hover ? Kit.HOVER : Kit.REST);
        }
        toggles.add(new Object[] {new int[] {x, y, 12, 12}, id});
    }

    /** The click id of a rule's or a number setting's − (−1) or + (+1). */
    private static String stepId(SetRow r, int dir) {
        return r.kind().equals("step") ? "#step:" + r.id() + ":" + dir : "#rule:" + r.rule() + ":" + dir;
    }

    /** A number setting's value as its row writes it ("85%"). */
    private static String stepText(String key, int v) {
        String fmt = Settings.STEP_FORMAT.get(key);
        return fmt == null ? String.valueOf(v) : L.t(fmt, v);
    }

    /**
     * The AI assistant panel: the API's state (a dot, the last request, today's count), its
     * address with copy and the port with −/+, the skill buttons, and the endpoint list.
     */
    private void drawAiPanel(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        // status card
        String st = AiLink.state;
        int dot = st.equals("listening") ? 0xFF5DCAA5 : st.equals("busy") ? 0xFFE24B4A : 0xFF6E6C78;
        // compact: one line, the state and then its note, to leave the endpoints some room
        boolean slim = layout.compact();
        Kit.card(g, x, y, w, slim ? 18 : 34, 0);
        Art.box(g, x + 8, y + 6, 7, 7, 3, dot, 0);
        String head = st.equals("listening") ? L.t("wynnlabs.ai.listening")
                : st.equals("busy") ? L.t("wynnlabs.ai.busy", AiLink.port) : L.t("wynnlabs.ai.off");
        String headCut = fit(head, w - 30);
        ink(g, Component.literal(headCut).withStyle(UiFont::bold), x + 20, y + 5, 0xFFFFFFFF, false);
        String sub;
        if (st.equals("listening")) {
            sub = AiLink.lastAt() == 0 ? L.t("wynnlabs.ai.no_requests")
                    : L.t("wynnlabs.ai.last_request", ago(AiLink.lastAt()), AiLink.lastPath(), AiLink.today());
        } else if (st.equals("busy")) {
            sub = L.t("wynnlabs.ai.busy_hint");
        } else {
            sub = L.t("wynnlabs.ai.off_hint");
        }
        int ry;
        if (slim) {
            int hw = font.width(Component.literal(headCut).withStyle(UiFont::bold)) + 8;
            desc(g, sub, x + 20 + hw, y + 6, w - 28 - hw);
            ry = y + 23;
        } else {
            desc(g, sub, x + 20, y + 19, w - 28);
            ry = y + 40;
        }
        // address and port
        String url = AiLink.url();
        ink(g, url, x + 2, ry + 3, 0xFF85B7EB, false);
        int bx = x + 8 + font.width(url);
        String copy = L.t("wynnlabs.ai.copy");
        int cw = font.width(copy) + 14;
        boolean ch = mx >= bx && mx < bx + cw && my >= ry && my < ry + 13;
        Kit.button(g, bx, ry, cw, 13, ch ? Kit.HOVER : Kit.REST);
        onWood(g, copy, bx + 7, ry + 3, ch ? Kit.HOVER : Kit.REST);
        toggles.add(new Object[] {new int[] {bx, ry, cw, 13}, "#aicopy"});
        String portLabel = L.t("wynnlabs.ai.port", AiLink.port);
        int pw = font.width(portLabel);
        int px = x + w - (pw + 40);
        stepButton(g, px, ry, "−", mx, my, "#aiport:-1");
        ink(g, portLabel, px + 18, ry + 3, 0xFFFAC775, false);
        stepButton(g, px + 22 + pw, ry, "+", mx, my, "#aiport:1");
        ry += 20;
        // the skill
        String[][] btns = {{L.t("wynnlabs.ai.export"), "#aiexport"}, {L.t("wynnlabs.ai.install"), "#aiinstall"},
            {L.t("wynnlabs.ai.prompt"), "#aiprompt"}};
        int sx = x;
        for (String[] b : btns) {
            int bw = font.width(b[0]) + 14;
            boolean hov = mx >= sx && mx < sx + bw && my >= ry && my < ry + 15;
            Kit.button(g, sx, ry, bw, 15, hov ? Kit.ON : Kit.REST);
            onWood(g, b[0], sx + 7, ry + 4, hov ? Kit.ON : Kit.REST);
            toggles.add(new Object[] {new int[] {sx, ry, bw, 15}, b[1]});
            sx += bw + 6;
        }
        if (aiNote != null) {
            desc(g, aiNote, x, ry + 19, w);
        }
        ry += slim && aiNote == null ? 21 : 32;
        // endpoints, two columns when they do not fit
        ink(g, L.t("wynnlabs.ai.endpoints"), x, ry, FAINT, false);
        ry += Kit.Ui.LINE;
        int rowH = Kit.Ui.LINE_DENSE;
        int fitRows = Math.max(1, (y + h - ry) / rowH);
        int all = AiLink.ENDPOINTS.size();
        // as many columns of at least 240px as the width holds (up to 3), two when one would not fit
        int cols = Math.max(all > fitRows ? 2 : 1, Math.min(3, w / 240));
        int per = (all + cols - 1) / cols;
        // what still does not fit: the last column's last row says how many are left out
        int shown = all;
        if (per > fitRows) {
            per = fitRows;
            shown = per * cols - 1;
        }
        int colW = (w - (cols - 1) * 12) / cols;
        for (int i = 0; i < shown; i++) {
            AiLink.Endpoint e = AiLink.ENDPOINTS.get(i);
            int ex = x + (i / per) * (colW + 12);
            int ey = ry + (i % per) * rowH;
            ink(g, e.path(), ex, ey, 0xFF85B7EB, false);
            int pwid = font.width(e.path()) + 6;
            desc(g, L.t(e.key()), ex + pwid, ey + 1, colW - pwid);
        }
        if (shown < all) {
            int ex = x + (cols - 1) * (colW + 12);
            int ey = ry + (per - 1) * rowH;
            String more = L.t("wynnlabs.ai.more", all - shown);
            ink(g, more, ex, ey, FAINT, false);
            List<Component> tip = new ArrayList<>();
            for (int i = shown; i < all; i++) {
                AiLink.Endpoint e = AiLink.ENDPOINTS.get(i);
                tip.add(Component.literal(e.path() + "  ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal(L.t(e.key())).withStyle(ChatFormatting.GRAY)));
            }
            hits.add(new Hit(ex, ey - 1, font.width(more), rowH, null, tip, null));
        }
    }

    private String aiNote;

    private final List<int[]> scaleRects = new ArrayList<>();   // {x, y, w, h, scale}

    /** About: the Wynntils version and which of its features work with it. */
    private void drawAbout(GuiGraphics g, int x, int y, int w, int mx, int my) {
        var down = Wyn.down();
        Component title = Component.literal(L.t("wynnlabs.wyn.about", Wyn.version())).withStyle(UiFont::bold);
        String line = L.t(down.isEmpty() ? "wynnlabs.wyn.all_ok" : "wynnlabs.wyn.some_off");
        // the card is as wide as its text (no wider than the page); a narrow page wraps the line
        int need = Math.max(font.width(title) + 16, font.width(line) + 30);
        for (var e : down.entrySet()) {
            need = Math.max(need, (int) (font.width(Wyn.name(e.getKey()) + " — " + e.getValue()) * DESC_SCALE) + 24);
        }
        w = Math.min(w, need);
        List<String> lines = wrapText(line, w - 28, 2);
        int extra = 10 * (lines.size() - 1);
        int h = (down.isEmpty() ? 30 : 30 + 10 * down.size()) + extra;
        Kit.card(g, x, y, w, h, 0);
        ink(g, title, x + 8, y + 5, 0xFFFFFFFF, false);
        int lc = down.isEmpty() ? 0xFF5DCAA5 : 0xFFE24B4A;
        inkIconText(g, down.isEmpty() ? "check" : "x", lines.get(0), x + 8, y + 17, lc);
        for (int i = 1; i < lines.size(); i++) {
            ink(g, lines.get(i), x + 18, y + 17 + 10 * i, lc);
        }
        if (!down.isEmpty()) {
            int ly = y + 28 + extra;
            for (var e : down.entrySet()) {
                desc(g, Wyn.name(e.getKey()) + " — " + e.getValue(), x + 14, ly, w - 22);
                ly += 10;
            }
        }
    }

    // ---- damage tab (Hasar) ----

    /** Spells, main attack, summons; then the elements in Damage.ELEMENTS order. */
    private static final int[] SOURCE_COLOURS = {0xFF7F77DD, 0xFFEF9F27, 0xFF1D9E75};
    private static final int[] ELEMENT_COLOURS = {0xFFFAC775, 0xFF5DA548, 0xFFF4E04D, 0xFF55C8E8, 0xFFE8584A, 0xFFA8A090};

    /** {@code v} with {@code digits} decimals and the language's separator ("4.5", "4,5"). */
    private static String dec(double v, int digits) {
        String s = String.format(Locale.ROOT, "%." + digits + "f", v);
        int dot = s.indexOf('.');
        return dot < 0 ? s : L.t("wynnlabs.damage.decimal", s.substring(0, dot), s.substring(dot + 1));
    }

    /** A gain or share in % with one decimal below 10 ("%4,5", "%12"). */
    private static String pctOf(double v) {
        return pct(Math.abs(v) < 0.05 ? "0" : Math.abs(v) < 10 ? dec(v, 1) : String.valueOf(Math.round(v)));
    }

    /**
     * The Damage tab: the model's damage per cast next to what the damage meter measured, the live
     * DPS meter, where the build's DPS comes from (spells, main attack, summons; per element), and
     * what one more step of each stat would add to it, best first.
     */
    private void drawDamage(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        DamageReport.Report report = DamageReport.get();
        if (report == null) {
            ink(g, L.c("wynnlabs.damage.no_plan").withStyle(ChatFormatting.GRAY), x, y + 4, 0xFFFFFFFF);
            return;
        }
        int gap = 8;
        // compact: the measured card leaves its note to the tooltip, to give the what-ifs rows
        boolean slim = layout.compact();
        int topH = slim ? 57 : 68;
        int lw = (w - gap) * 56 / 100;
        drawMeasured(g, x, y, lw, topH, mx, my);
        drawLive(g, x + lw + gap, y, w - lw - gap, topH, mx, my);

        // where the DPS comes from
        Damage.Breakdown b = report.detail().breakdown();
        double total = b.total();
        int sy = y + topH + 8;
        section(g, L.t("wynnlabs.damage.breakdown", QuestAdvisor.fmt(Math.round(total))), x, sy, w);
        sy += 12;
        int colW = (w - 16) / 2;
        String[] sources = {L.t("wynnlabs.damage.src_spell"), L.t("wynnlabs.damage.src_main"), L.t("wynnlabs.damage.src_summon")};
        double[] values = {b.spellTotal(), b.mainTotal(), b.summonTotal()};
        for (int i = 0; i < sources.length; i++) {
            shareRow(g, x, sy + i * 11, colW, sources[i], values[i], total, SOURCE_COLOURS[i]);
        }
        // the build score weighs the main attack less: say by how much
        double weight = Rules.sub("model", "mainAttackWeight", 0.5);
        desc(g, L.t("wynnlabs.damage.main_weight", dec(weight, 2)), x, sy + 36, colW);
        hits.add(new Hit(x, sy + 11, colW, 11, null, List.of(
                L.c("wynnlabs.damage.src_main").withStyle(ChatFormatting.GOLD),
                L.c("wynnlabs.damage.main_weight_tip", dec(weight, 2)).withStyle(ChatFormatting.GRAY)), null));
        int ex = x + colW + 16;
        if (slim) {
            // compact: the elements in two columns of three, a colour mark and the share, no bars
            int subW = (colW - 8) / 2;
            for (int i = 0; i < Damage.ELEMENTS.length; i++) {
                int cx = ex + (i / 3) * (subW + 8);
                int cy = sy + (i % 3) * 11;
                double v = b.element(i);
                String share = pctOf(total > 0 ? v / total * 100 : 0);
                g.fill(cx, cy + 2, cx + 4, cy + 6, ELEMENT_COLOURS[i]);
                ink(g, fit(L.t(DamageReport.ELEMENT_KEYS[i]), subW - 10 - font.width(share)), cx + 7, cy, 0xFFDDDDDD);
                ink(g, share, cx + subW - font.width(share), cy, v > 0 ? 0xFFE8E6DF : FAINT);
                hits.add(new Hit(cx, cy - 1, subW, 11, null, List.of(Component.literal(
                        L.t(DamageReport.ELEMENT_KEYS[i]) + "  " + QuestAdvisor.fmt(Math.round(v)) + "  " + share)), null));
            }
            sy += 4 * 11 + 4;
        } else {
            for (int i = 0; i < Damage.ELEMENTS.length; i++) {
                shareRow(g, ex, sy + i * 11, colW, L.t(DamageReport.ELEMENT_KEYS[i]), b.element(i), total, ELEMENT_COLOURS[i]);
            }
            sy += 6 * 11 + 6;
        }

        // what one more step of each stat would add
        String free = report.freePoints() > 0 ? L.n("wynnlabs.damage.free_points", report.freePoints())
                : L.t("wynnlabs.damage.no_free_points");
        // the note in the room the title leaves (cut short on a narrow page)
        free = fit(free, w - font.width(L.t("wynnlabs.damage.what_if")) - 24);
        section(g, L.t("wynnlabs.damage.what_if"), x, sy, w - font.width(free) - 8);
        ink(g, free, x + w - font.width(free), sy, FAINT);
        sy += 12;
        List<DamageReport.WhatIf> ifs = report.whatIf();
        double maxGain = 0;
        for (DamageReport.WhatIf wi : ifs) {
            maxGain = Math.max(maxGain, wi.gain());
        }
        // compact: denser rows without bars, so more of them fit
        int rowH = slim ? Kit.Ui.LINE_DENSE : Kit.Ui.LINE;
        int per = Math.max(1, Math.min((ifs.size() + 1) / 2, (y + h - sy) / rowH));
        for (int i = 0; i < ifs.size() && i < per * 2; i++) {
            DamageReport.WhatIf wi = ifs.get(i);
            int rx = x + (i / per) * (colW + 16);
            int ry = sy + (i % per) * rowH;
            whatIfRow(g, rx, ry, colW, wi, maxGain, mx, my);
        }
    }

    /** The longest bar in the damage tab's rows: on a large page a longer one reads no better. */
    private static final int BAR_MAX = 150;

    /** The damage rows' value column, the same width on every row so every bar ends at the same x. */
    private int numColW() {
        return font.width("+00.0k");
    }

    private int pctColW() {
        return Math.max(font.width("%100"), font.width("%00,0"));
    }

    /**
     * A bar row's bar and value: a track of {@code frac} from {@code bx}, then the number and the share
     * right-aligned in their fixed columns. {@code room} is the width from {@code bx} to the row's end.
     */
    private void barAndValue(GuiGraphics g, int bx, int y, int room, double frac, int colour, String num, String share, int valColour) {
        int valW = numColW() + 6 + pctColW();
        int bw = Math.max(10, Math.min(BAR_MAX, room - valW - 8));
        Art.pill(g, bx, y + 2, bw, 5, Theme.WELL, 0);
        int fill = (int) Math.round(bw * Math.max(0, Math.min(1, frac)));
        if (fill >= 2) {
            Art.pill(g, bx, y + 2, fill, 5, colour, 0);
        }
        int vx = bx + bw + 8;
        ink(g, num, vx + numColW() - font.width(num), y, valColour);
        ink(g, share, vx + valW - font.width(share), y, valColour);
    }

    /** Computed against measured: damage per cast by the model and by the meter, the ratio, the calibration. */
    private void drawMeasured(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        Kit.card(g, x, y, w, h, 0);
        ink(g, Component.literal(L.t("wynnlabs.damage.vs_title")).withStyle(UiFont::bold), x + 8, y + 7, 0xFFFFFFFF);
        double model = DamageReport.modelPerCast();
        double cal = DamageReport.calibration();
        int casts = DamageReport.measuredCasts();
        double measured = DamageReport.measuredPerCast();
        String na = "—";
        String[][] rows = {
            {L.t("wynnlabs.damage.model_per_cast"), model > 0 ? QuestAdvisor.fmt(Math.round(model)) : na},
            {L.t("wynnlabs.damage.measured_per_cast"), casts > 0
                    ? L.n("wynnlabs.damage.measured_value", casts, QuestAdvisor.fmt(Math.round(measured)), casts) : na},
            {L.t("wynnlabs.damage.ratio"), (casts > 0 && model > 0 ? pct(Math.round(measured / model * 100)) : na)
                    + " · " + L.t("wynnlabs.damage.calibration", dec(cal, 2))}};
        int ry = y + 20;
        // a large card keeps each value near its label
        int kvW = layout.large() ? Math.min(w - 16, 225) : w - 16;
        for (String[] r : rows) {
            ink(g, fit(r[0], kvW - font.width(r[1]) - 6), x + 8, ry, MUTED);
            ink(g, r[1], x + 8 + kvW - font.width(r[1]), ry, 0xFFE8E6DF);
            ry += Kit.Ui.LINE_DENSE;
        }
        String note = casts == 0 ? L.t("wynnlabs.damage.none_measured")
                : casts < DamageReport.ENOUGH_CASTS ? L.n("wynnlabs.damage.few_casts", casts, casts, DamageReport.ENOUGH_CASTS)
                : L.t("wynnlabs.damage.enough_casts");
        if (ry + 9 <= y + h - 4) {
            desc(g, note, x + 8, y + h - 13, w - 16);
        }
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(L.t("wynnlabs.damage.vs_title")).withStyle(ChatFormatting.WHITE));
        for (String l : Advice.wrap(note + " " + L.t("wynnlabs.damage.vs_explain"), 48)) {
            tip.add(Component.literal(l).withStyle(ChatFormatting.GRAY));
        }
        hits.add(new Hit(x, y, w, h, null, tip, null));
    }

    /** The live meter: DPS of the last 10 seconds, the session's best 10 seconds, this fight's damage. */
    private void drawLive(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        DpsMeter.State s = DpsMeter.LIVE.state();
        Kit.card(g, x, y, w, h, s.fighting() ? Theme.AMBER : 0);
        if (s.fighting()) {
            Kit.outline(g, x - 1, y - 1, w + 2, h + 2, Theme.AMBER_LO);
        }
        ink(g, Component.literal(L.t("wynnlabs.damage.live_title")).withStyle(UiFont::bold), x + 8, y + 7, 0xFFFFFFFF);
        // the same meter on the game screen: its switch, right here
        String hud = L.t("wynnlabs.damage.live_hud");
        int px = x + w - 26;
        int lx = px - 4 - font.width(hud);
        boolean hov = mx >= lx && mx < x + w - 4 && my >= y + 3 && my < y + 16;
        ink(g, hud, lx, y + 5, hov ? 0xFFDDDDDD : FAINT);
        pill(g, px, y + 5, Settings.on("dpsHud"));
        toggles.add(new Object[] {new int[] {lx, y + 3, x + w - 4 - lx, 13}, "dpsHud"});
        if (hov) {
            rowTip(lx, y + 3, x + w - 4 - lx, 13, L.t("wynnlabs.setting.dpsHud"), L.t("wynnlabs.setting_desc.dpsHud"));
        }
        if (s.sessionDamage() <= 0) {
            // wrapped over the card's room rather than cut
            descWrap(g, L.t("wynnlabs.damage.live_empty"), x + 8, y + 20, w - 16, Math.max(1, (h - 24) / 9));
            return;
        }
        String dps = QuestAdvisor.fmt(Math.round(s.dps()));
        scaledText(g, Component.literal(dps).withStyle(UiFont::bold), x + 8, y + 20, 1.5f, s.fighting() ? Theme.WARN : MUTED);
        ink(g, "DPS", x + 12 + (int) (font.width(dps) * 1.5f), y + 24, FAINT);
        String best = L.t("wynnlabs.damage.live_best", QuestAdvisor.fmt(Math.round(s.best())));
        ink(g, fit(best, w - 16), x + 8, y + 39, MUTED);
        String fight = L.t(s.fighting() ? "wynnlabs.damage.live_fight" : "wynnlabs.damage.live_last_fight",
                QuestAdvisor.fmt(Math.round(s.fightDamage())), Math.round(s.fightSeconds()));
        ink(g, fit(fight, w - 16), x + 8, y + 39 + Kit.Ui.LINE_DENSE, MUTED);
    }

    /** A label, a bar of its share of {@code total}, the value and the share in %. */
    private void shareRow(GuiGraphics g, int x, int y, int w, String label, double value, double total, int colour) {
        int labelW = 74;
        double share = total > 0 ? value / total : 0;
        ink(g, fit(label, labelW - 4), x, y, 0xFFDDDDDD);
        barAndValue(g, x + labelW, y, w - labelW, share, colour, QuestAdvisor.fmt(Math.round(value)),
                pctOf(share * 100), value > 0 ? 0xFFE8E6DF : FAINT);
    }

    /** One what-if: the change, a green bar of its gain against the biggest, "+123 DPS (%4,5)". */
    private void whatIfRow(GuiGraphics g, int x, int y, int w, DamageReport.WhatIf wi, double maxGain, int mx, int my) {
        long gain = Math.round(wi.gain());
        String num = (gain > 0 ? "+" : "") + QuestAdvisor.fmt(gain);
        String share = pctOf(wi.percent());
        boolean useful = wi.gain() >= 0.5;
        int valColour = useful ? 0xFF9FE1CB : FAINT;
        int valW = numColW() + 6 + pctColW();
        if (layout.compact()) {
            // compact: no bar, the label up to the value column
            ink(g, fit(L.t(wi.key()), w - valW - 6), x, y, useful ? 0xFFDDDDDD : MUTED);
            ink(g, num, x + w - valW + numColW() - font.width(num), y, valColour);
            ink(g, share, x + w - font.width(share), y, valColour);
        } else {
            int labelW = 124;
            ink(g, fit(L.t(wi.key()), labelW - 4), x, y, useful ? 0xFFDDDDDD : MUTED);
            barAndValue(g, x + labelW, y, w - labelW, maxGain > 0 ? Math.max(0, wi.gain()) / maxGain : 0,
                    Theme.GREEN, num, share, valColour);
        }
        if (mx >= x && mx < x + w && my >= y && my < y + 11) {
            hits.add(new Hit(x, y, w, 11, null, List.of(
                    Component.literal(L.t(wi.key())).withStyle(ChatFormatting.WHITE),
                    L.c("wynnlabs.damage.what_if_tip", QuestAdvisor.fmt(Math.round(wi.dps())), dec(wi.casts(), 2))
                            .withStyle(ChatFormatting.GRAY)), null));
        }
    }

    /** The AI assistant tab: the API and write switches, then status, address, skill and endpoints. */
    private void drawAiTab(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        // two switches side by side, their descriptions on two lines rather than cut
        rowPitch = SET_ROW + 9;
        int colW = (w - 18) / 2;
        drawSettingRow(g, new Object[] {"switch", "localApi"}, x, y, colW, mx, my);
        drawSettingRow(g, new Object[] {"switch", "apiWrite"}, x + colW + 18, y, colW, mx, my);
        int top = rowPitch + (layout.compact() ? 0 : 6);
        rowPitch = SET_ROW;
        drawAiPanel(g, x, y + top, w, h - top, mx, my);
    }

    private void aiClick(String id) {
        try {
            switch (id) {
                case "#aicopy" -> {
                    Minecraft.getInstance().keyboardHandler.setClipboard(AiLink.url());
                    aiNote = L.t("wynnlabs.ai.copied_url");
                }
                case "#aiexport" -> aiNote = L.t("wynnlabs.ai.exported", AiLink.export());
                case "#aiinstall" -> aiNote = L.t("wynnlabs.ai.installed", AiLink.installClaude());
                case "#aiprompt" -> {
                    Minecraft.getInstance().keyboardHandler.setClipboard(AiLink.prompt());
                    aiNote = L.t("wynnlabs.ai.copied_prompt");
                }
                default -> {
                    if (id.startsWith("#aiport:")) {
                        int p = Math.max(1024, Math.min(65535, Settings.number("localApiPort", 47591)
                                + Integer.parseInt(id.substring(8))));
                        Settings.setNumber("localApiPort", p);
                        WynnLabs.syncServer();
                        aiNote = null;
                    }
                }
            }
        } catch (Exception e) {
            aiNote = L.t("wynnlabs.ai.failed", e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    /** False while the key built into the mod works: the key row then asks for nothing. */
    private static boolean keyBoxNeeded() {
        return !(Prices.embeddedKeyInUse() && !Prices.keyInvalid);
    }

    /** The height {@link #drawKeyDetail} takes. */
    private static int keyDetailHeight(int w) {
        return keyBoxNeeded() ? 50 : 32;
    }

    /**
     * Prices and data → Wynnventory key: where the prices and bank notes come from, then the key
     * (built in, or the box to paste your own with Save and Remove). Returns the height it took.
     */
    private int drawKeyDetail(GuiGraphics g, int x, int y, int w, int mx, int my) {
        Words problem = Prices.problem;
        String pr = problem == null ? null : problem.text();
        String an = Vault.annotateProblem;
        String prices = pr == null ? L.t("wynnlabs.settings.prices_ok") : "✖ " + pr;
        String bank = an == null ? L.t("wynnlabs.settings.bank_ok") : L.t("wynnlabs.settings.bank_bad", an);
        ink(g, Component.literal(fit(prices, w)).withStyle(pr == null ? ChatFormatting.DARK_GREEN : ChatFormatting.RED), x, y, 0xFFFFFFFF);
        ink(g, Component.literal(fit(bank, w)).withStyle(an == null ? ChatFormatting.DARK_GREEN : ChatFormatting.RED), x, y + 10, 0xFFFFFFFF);
        int ky = y + 22;
        keyBoxShown = keyBoxNeeded();
        if (!keyBoxShown) {
            // the key built into the mod works: nothing to ask for
            ink(g, L.c("wynnlabs.settings.key").withStyle(ChatFormatting.GRAY)
                    .append(L.c("wynnlabs.settings.key_builtin").withStyle(ChatFormatting.GREEN)), x, ky, 0xFFFFFFFF);
            return 32;
        }
        String masked = Prices.maskedKey();
        Component ks = L.c("wynnlabs.settings.key").withStyle(ChatFormatting.GRAY)
                .append(masked == null ? L.c("wynnlabs.settings.key_none").withStyle(ChatFormatting.DARK_GRAY)
                        : Prices.keyInvalid ? L.c("wynnlabs.settings.key_invalid", masked).withStyle(ChatFormatting.RED)
                        : Component.literal(masked).withStyle(ChatFormatting.GREEN));
        ink(g, ks, x, ky, 0xFFFFFFFF);
        int by = ky + 12;
        String save = L.t("wynnlabs.settings.save");
        String[][] btns = masked == null ? new String[][] {{save, "#keysave"}}
                : new String[][] {{save, "#keysave"}, {L.t("wynnlabs.settings.remove"), "#keydel"}};
        int buttons = 0;
        for (String[] b : btns) {
            buttons += font.width(b[0]) + 14 + 4;
        }
        int kw = Math.max(60, Math.min(220, w - buttons - 2));
        textBox(g, keyBox, x, by, kw, "wynnlabs.settings.key_box", mx, my, 0f);
        int sx = x + kw + 6;
        for (String[] b : btns) {
            int bw = font.width(b[0]) + 14;
            boolean bh = mx >= sx && mx < sx + bw && my >= by && my < by + 12;
            card(g, sx, by, bw, 12, bh, 0);
            g.drawString(font, b[0], sx + 7, by + 2, b[1].equals("#keydel") ? 0xFFFFB0A0 : 0xFFB8F0A0, true);
            toggles.add(new Object[] {new int[] {sx, by, bw, 12}, b[1]});
            sx += bw + 4;
        }
        return 50;
    }

    // ---- input ----

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (DevHooks.ON && DevHooks.driving()) {
            return true;
        }
        double f = MenuScale.factor();
        MouseButtonEvent e = MenuScale.event(event);
        if (tab == 0 && BuildWindow.INSTANCE.mouseDragged(e.x(), e.y())) {
            return true;                                // a build window scrollbar's thumb follows the mouse
        }
        if (tab == TAB_CRAFT && CraftingTab.INSTANCE.mouseDragged(e.x(), e.y())) {
            return true;                                // the probability slider follows the mouse
        }
        return super.mouseDragged(e, dx * f, dy * f);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        CraftingTab.INSTANCE.mouseReleased();
        BuildWindow.INSTANCE.mouseReleased();
        return super.mouseReleased(MenuScale.event(event));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (DevHooks.ON && DevHooks.driving()) {
            return true;                                // the screenshot tool drives the menu
        }
        event = MenuScale.event(event);                 // logical pixels, as drawn
        double ex = event.x();
        double ey = event.y();
        if (tab == 0 && BuildWindow.INSTANCE.isOpen()) {
            if (closeRect != null && in(ex, ey, closeRect)) {
                onClose();
                return true;
            }
            net.minecraft.client.gui.components.EditBox f = BuildWindow.INSTANCE.focused();
            if (f != null && f.mouseClicked(event, doubleClick)) {
                return true;
            }
            return BuildWindow.INSTANCE.mouseClicked(ex, ey, event.button());
        }
        if (goalOpen) {
            goalOpen = false;
            for (int[] r : goalRows) {
                if (in(ex, ey, r)) {
                    String goal = Setup.GOALS.get(r[4]);
                    if (!goal.equals(Setup.goal())) {
                        Setup.setGoal(goal);
                        flash = L.t("wynnlabs.home.goal_set", Setup.goalName(goal));
                        flashUntil = System.currentTimeMillis() + 2500;
                        steps = rebuild();              // rebuilt in the background, picked up when ready
                        refreshAsked = true;
                    }
                    return true;
                }
            }
            if (goalAnchor != null && in(ex, ey, goalAnchor)) {
                return true;                        // the button again: closed
            }
        }
        if (closeRect != null && in(ex, ey, closeRect)) {
            onClose();
            return true;
        }
        for (int[] r : scaleRects) {
            if (tab == TAB_SETTINGS && in(ex, ey, r)) {
                Settings.setNumber("menuScale", r[4]);
                return true;                            // the next frame draws at the new scale
            }
        }
        for (int[] r : langRects) {
            if (in(ex, ey, r)) {
                Settings.setNumber("langOverride", r[4]);
                steps = rebuild();                  // its text is in the old language: rebuilt in the background
                refreshAsked = true;
                return true;
            }
        }
        for (int[] t : tabs) {
            if (in(ex, ey, t)) {
                tab = t[4];
                detail = null;
                search.setFocused(tab == TAB_BANK);
                treeSearch.setFocused(tab == TAB_TREE);
                setFocused(tab == TAB_BANK ? search : tab == TAB_TREE ? treeSearch : null);
                return true;
            }
        }
        if (tab == TAB_CRAFT) {
            if (CraftingTab.INSTANCE.mouseClicked(ex, ey, event.button())) {
                return true;
            }
            return super.mouseClicked(event, doubleClick);
        }
        if (tab == TAB_ENDGAME) {
            if (EndgameTab.INSTANCE.mouseClicked(ex, ey, event.button())) {
                return true;
            }
            return super.mouseClicked(event, doubleClick);
        }
        if (tab == TAB_BANK) {
            if (search.mouseClicked(event, doubleClick)) {
                setFocused(search);
                return true;
            }
            for (Object[] f : bankFilterHits) {
                if (in(ex, ey, (int[]) f[0])) {
                    bankFilter = (Integer) f[1];
                    bankScroll = 0;
                    return true;
                }
            }
            for (Object[] f : bankPageHits) {
                if (in(ex, ey, (int[]) f[0])) {
                    bankPage = (String) f[1];
                    bankScroll = 0;
                    return true;
                }
            }
        }
        if (tab == TAB_TREE) {
            if (treeSearch.mouseClicked(event, doubleClick)) {
                setFocused(treeSearch);
                return true;
            }
            for (Hit h : hits) {
                if (h.copy() != null && h.copy().startsWith("#tree") && in(ex, ey, new int[] {h.x(), h.y(), h.w(), h.h()})) {
                    treeClick(h.copy());
                    return true;
                }
            }
            return super.mouseClicked(event, doubleClick);
        }
        if (tab == TAB_SETTINGS || tab == TAB_AI || tab == TAB_DAMAGE) {
            boolean dataCat = tab == TAB_SETTINGS && settingsCat < SETTING_CATS.length && "data".equals(SETTING_CATS[settingsCat]);
            if (dataCat && keyBoxShown && keyBox.mouseClicked(event, doubleClick)) {
                keyBox.setFocused(true);
                setFocused(keyBox);
                return true;
            }
            keyBox.setFocused(false);
            if (ruleBoxFor >= 0 && ruleBoxRect != null && in(ex, ey, ruleBoxRect)) {
                ruleBox.mouseClicked(event, doubleClick);
                ruleBox.setFocused(true);
                setFocused(ruleBox);
                return true;
            }
            if (ruleBox.isFocused()) {
                saveRuleBox();                      // clicking away keeps what was typed
            }
            for (Object[] t : toggles) {
                if (in(ex, ey, (int[]) t[0])) {
                    String id = String.valueOf(t[1]);
                    if (id.startsWith("#ai")) {
                        aiClick(id);
                        return true;
                    }
                    if (id.equals("#ruleunit")) {
                        // the next unit; the amount typed stays, so "100" E becomes 100 EB
                        ruleUnit = (ruleUnit + 1) % UNIT_NAMES.length;
                        ruleBox.setFocused(true);       // saved with the new unit
                        saveRuleBox();
                        return true;
                    }
                    if (id.startsWith("#cat:")) {
                        settingsCat = Integer.parseInt(id.substring(5));
                        settingsScroll = 0;
                        settingsDetail = false;
                        keyBox.setFocused(false);
                        setFocused(null);
                    } else if (id.startsWith("#ssel:") || id.startsWith("#stog:") || id.startsWith("#sreset:")) {
                        settingsAction(id);
                    } else if ("#sback".equals(id)) {
                        settingsDetail = false;
                    } else if (id.startsWith("#upd:") || id.startsWith("#link:")) {
                        aboutClick(id);
                    } else if (id.startsWith("#rule:")) {
                        String[] p = id.split(":");
                        RuleEditor.step(RuleEditor.RULES.get(Integer.parseInt(p[1])), Integer.parseInt(p[2]));
                    } else if (id.startsWith("#step:")) {
                        String[] p = id.split(":");
                        Settings.step(p[1], Integer.parseInt(p[2]));
                    } else if ("#rulesreset".equals(id)) {
                        RuleEditor.reset();
                        flash = L.t("wynnlabs.rules.reset_done");
                        flashUntil = System.currentTimeMillis() + 2500;
                    } else if ("#keysave".equals(t[1])) {
                        saveKey();
                    } else if ("#keydel".equals(t[1])) {
                        Prices.setKey("");
                        flash = L.t("wynnlabs.settings.key_removed");
                        flashUntil = System.currentTimeMillis() + 2500;
                    } else if ("#archetype".equals(t[1])) {
                        TreePlanner.cycle();
                    } else if ("#goal".equals(t[1])) {
                        Setup.cycleGoal();
                    } else {
                        Settings.toggle((String) t[1]);
                        if ("webFont".equals(t[1])) {
                            rebuildWidgets();       // init() again: the screen and its boxes take the new font now
                        }
                    }
                    return true;
                }
            }
            return super.mouseClicked(event, doubleClick);
        }
        if (tab == 0 && detail != null) {
            if (backRect != null && in(ex, ey, backRect)) {
                detail = null;
                return true;
            }
            for (Object[] c : cardHits) {
                if (in(ex, ey, (int[]) c[0])) {
                    String n = ((ItemData) c[1]).name();
                    Minecraft.getInstance().keyboardHandler.setClipboard(n);
                    flash = L.t("wynnlabs.screen.copied", n);
                    flashUntil = System.currentTimeMillis() + 2500;
                    return true;
                }
            }
            return super.mouseClicked(event, doubleClick);
        }
        if (tab == 0) {
            for (Object[] sh : slotHits) {
                if (in(ex, ey, (int[]) sh[0])) {
                    detail = (Roadmap.Gear) sh[1];
                    detailLabel = (String) sh[2];
                    detailWorn = (ItemStack) sh[3];
                    return true;
                }
            }
            for (int[] r : levelRows) {
                if (in(ex, ey, r) && r[4] == PLAN_PILL) {
                    planPills.pickPlan();
                    return true;
                }
                if (in(ex, ey, r)) {
                    planPills.pickLevel();
                    // ‹ and › step the selection; the window follows it
                    List<Integer> shown = shownSteps();
                    int pos = shown.indexOf(selected);
                    selected = r[4] == -1 ? shown.get(Math.max(0, Math.min(pos, levelOff) - 1))
                            : r[4] == -2 ? shown.get(Math.min(shown.size() - 1, Math.max(pos, levelOff + levelWindow - 1) + 1))
                            : r[4];
                    return true;
                }
            }
        }
        for (Hit h : hits) {
            if (h.copy() != null && h.copy().startsWith("#disc") && ex >= h.x() && ex < h.x() + h.w() && ey >= h.y()
                    && ey < h.y() + h.h() && discoveryClick(h.copy())) {
                return true;
            }
            if (h.copy() != null && h.copy().startsWith("#q") && ex >= h.x() && ex < h.x() + h.w() && ey >= h.y()
                    && ey < h.y() + h.h()) {
                String c = h.copy();
                if (c.startsWith("#qfilter:")) {
                    questFilter = c.substring(9);
                    questScroll = 0;
                    questSelected = null;
                } else if (c.startsWith("#qsel:")) {
                    questSelected = c.substring(6);
                    pointScroll = 0;
                } else if (c.startsWith("#qtab:")) {
                    questDetailTab = Integer.parseInt(c.substring(6));
                } else if (c.startsWith("#qtrack:")) {
                    QuestBook.Entry q = questEntry(c.substring(8));
                    if (q != null) {
                        onClose();
                        if (QuestBook.track(q)) {
                            Minecraft.getInstance().gui.setOverlayMessage(
                                    L.c("wynnlabs.quests.now_tracking", QuestGuides.title(q.name())).withStyle(ChatFormatting.GREEN), false);
                        }
                    }
                } else if (c.startsWith("#qrescan:")) {
                    // the player's press, the only thing that starts Wynntils' quest book refresh
                    // (QuestBook.askWynntils → Wyn.rescanQuests); nothing while it runs or when it cannot
                    QuestBook.askWynntils();
                } else if (c.equals("#qclear")) {
                    int n = Waypoints.clearAll();
                    flash = L.n("wynnlabs.quests.pins_cleared", n);
                    flashUntil = System.currentTimeMillis() + 2500;
                } else if (c.startsWith("#qpins:")) {
                    String name = c.substring(7);
                    QuestWiki.Info info = QuestWiki.get(name);
                    List<int[]> pts = new ArrayList<>();
                    List<String> labels = new ArrayList<>();
                    if (info != null) {
                        // a tracked quest starts at the stage you are on: the ones before are done;
                        // every place from there is on the map at once, the hints' doors and levers too
                        int from = name.equals(Wyn.trackedName()) ? Math.max(0, QuestWiki.currentStage(info, Wyn.trackedTask())) : 0;
                        List<QuestWiki.Place> all = QuestTrail.places(name, info, 0);
                        List<String> nums = pointNumbers(all);
                        for (int i = 0; i < all.size(); i++) {
                            if (all.get(i).stage() >= from) {
                                pts.add(all.get(i).at());
                                labels.add(nums.get(i) + " " + all.get(i).what());     // "5.2 Basınç plakası…", as in the points tab
                            }
                        }
                    }
                    QuestBook.Entry q = questEntry(name);
                    if (pts.isEmpty() && q != null && q.x() != null) {
                        pts.add(new int[] {q.x(), q.y(), q.z()});
                        labels.add(L.t("wynnlabs.quests.pin_start", QuestGuides.title(name)));
                    }
                    int n = Waypoints.showAll(pts, labels);
                    flash = n > 0 ? L.n("wynnlabs.quests.pins_added", n) : L.t("wynnlabs.quests.no_coords");
                    flashUntil = System.currentTimeMillis() + 2500;
                } else if (c.startsWith("#qpinstage:")) {
                    // the stage you are on (the first one when the quest is not started)
                    String name = c.substring(11);
                    QuestWiki.Info info = QuestWiki.get(name);
                    QuestBook.Entry q = questEntry(name);
                    int stage = name.equals(Wyn.trackedName()) ? QuestWiki.currentStage(info, Wyn.trackedTask())
                            : q != null && "STARTED".equals(q.status()) ? QuestWiki.currentStage(info, q.task()) : 0;
                    List<QuestWiki.Place> all = QuestTrail.places(name, info, 0);
                    List<String> nums = pointNumbers(all);
                    List<int[]> pts = new ArrayList<>();
                    List<String> labels = new ArrayList<>();
                    for (int i = 0; i < all.size(); i++) {
                        if (all.get(i).stage() == Math.max(0, stage)) {
                            pts.add(all.get(i).at());
                            labels.add(nums.get(i) + " " + all.get(i).what());
                        }
                    }
                    int n = Waypoints.showAll(pts, labels);
                    flash = n > 0 ? L.n("wynnlabs.quests.pins_added", n) : L.t("wynnlabs.quests.no_coords");
                    flashUntil = System.currentTimeMillis() + 2500;
                } else if (c.startsWith("#qadvice:")) {
                    Rules.setAdviceHere(true);
                    steps = rebuild();
                    refreshAsked = true;
                } else if (c.startsWith("#qpick:")) {
                    if (event.button() == 1) {
                        // right-click: the plan removed, as its pill's ✕ does (Undo on the build line);
                        // with no plan, the chosen items cleared
                        if (!removePlan()) {
                            Locks.clear();
                            Around.forget();
                            flash = L.t("wynnlabs.homev.pick_cleared");
                            flashUntil = System.currentTimeMillis() + 2500;
                        }
                    } else {
                        BuildWindow.INSTANCE.openPick();
                    }
                } else if (c.startsWith("#qplanremove:")) {
                    removePlan();
                } else if (c.startsWith("#qplansave:")) {
                    boolean was = Plans.isSaved(Plans.current());
                    Plans.Build kept = Plans.savePlan(System.currentTimeMillis());
                    if (kept != null) {
                        flash = L.t(was ? "wynnlabs.homev.plan_updated" : "wynnlabs.homev.plan_saved_flash", kept.name());
                        flashUntil = System.currentTimeMillis() + 2500;
                    }
                } else if (c.startsWith("#qplanedit:")) {
                    BuildWindow.INSTANCE.editPlan(Plans.current());
                } else if (c.startsWith("#qplanundo:")) {
                    Plans.undoRemove(System.currentTimeMillis());
                } else if (c.startsWith("#qsaved:")) {
                    BuildWindow.INSTANCE.openSaved();
                } else if (c.startsWith("#qhint:")) {
                    if (event.button() == 1) {
                        Plans.HINT.dismiss();
                    } else {
                        BuildWindow.INSTANCE.openResults(Around.checkResult());
                    }
                } else if (c.startsWith("#qgoal:")) {
                    goalOpen = true;                    // the list opens; a row picks the goal
                } else if (c.startsWith("#qrefresh:") || c.startsWith("#qbuild:")) {
                    Roadmap.forceRefresh();
                    refreshAsked = true;
                } else if (c.startsWith("#qcancelbuild:")) {
                    Roadmap.cancelBuild();
                    refreshAsked = false;
                } else if (c.startsWith("#qpowder:")) {
                    boolean on = Rules.sub("model", "assumePowders", 1) > 0;
                    JsonObject pm = new JsonObject();
                    pm.addProperty("assumePowders", on ? 0 : 1);
                    JsonObject pc = new JsonObject();
                    pc.add("model", pm);
                    Rules.update(pc);
                    flash = L.t(on ? "wynnlabs.home.powder_off" : "wynnlabs.home.powder_on");
                    flashUntil = System.currentTimeMillis() + 2500;
                    steps = rebuild();                  // rebuilt in the background, picked up when ready
                    refreshAsked = true;
                } else if (c.startsWith("#qarch:")) {
                    TreePlanner.cycle();
                    String a = TreePlanner.chosen();
                    flash = L.t("wynnlabs.home.archetype_set", a.isEmpty() ? L.t("wynnlabs.settings.archetype_off") : a);
                    flashUntil = System.currentTimeMillis() + 2500;
                    steps = rebuild();                  // rebuilt in the background, picked up when ready
                    refreshAsked = true;
                } else if (c.startsWith("#qgo:")) {
                    tab = TAB_BANK;
                    detail = null;
                } else if (c.startsWith("#qopen:")) {
                    tab = Integer.parseInt(c.substring(7));     // a home tile: its tab
                    detail = null;
                } else if (c.startsWith("#qdisc:")) {
                    tab = TAB_DISCOVERIES;                      // the home's nearest discovery, chosen there
                    discSelected = c.substring(7);
                    detail = null;
                } else if (c.startsWith("#qnear:")) {
                    String[] f = c.substring(7).split(" ", 4);
                    boolean ok = Waypoints.go(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), f[3]);
                    flash = ok ? L.t("wynnlabs.home.marked", f[3]) : L.t("wynnlabs.home.mark_failed");
                    flashUntil = System.currentTimeMillis() + 2500;
                } else if (c.startsWith("#qplan:")) {
                    List<int[]> pts = new ArrayList<>();
                    List<String> labels = new ArrayList<>();
                    int i = 1;
                    for (String name : c.substring(7).split("\n")) {
                        QuestBook.Entry q = questEntry(name);
                        if (q != null && q.x() != null) {
                            pts.add(new int[] {q.x(), q.y(), q.z()});
                            labels.add((i++) + ") " + name);
                        }
                    }
                    int n = Waypoints.all(pts, labels);
                    flash = L.n("wynnlabs.quests.plan_marked", n);
                    flashUntil = System.currentTimeMillis() + 2500;
                }
                return true;
            }
            if (h.quest() != null && event.button() == 0 && ex >= h.x() && ex < h.x() + h.w() && ey >= h.y()
                    && ey < h.y() + h.h()) {
                // Track it like a click in the content book would; close so the book can open
                QuestBook.Entry q = h.quest();
                onClose();
                if (QuestBook.track(q)) {
                    Minecraft.getInstance().gui.setOverlayMessage(
                            L.c("wynnlabs.quests.now_tracking", QuestGuides.title(q.name())).withStyle(ChatFormatting.GREEN), false);
                }
                return true;
            }
            if (h.copy() != null && h.copy().startsWith("#guide:") && ex >= h.x() && ex < h.x() + h.w()
                    && ey >= h.y() && ey < h.y() + h.h()) {
                String[] qs = h.copy().substring(7).split("\n", 2);
                Minecraft.getInstance().setScreen(new GuideScreen(qs[0], qs.length > 1 ? qs[1] : ""));
                return true;
            }
            if (h.copy() != null && h.copy().startsWith("/compass at ") && ex >= h.x() && ex < h.x() + h.w()
                    && ey >= h.y() && ey < h.y() + h.h()) {
                // Go there in one click: a Wynntils waypoint (compass, beacon, map pin), set locally like
                // its own /compass; nothing is sent to the server. The previous pin of ours is replaced.
                boolean placed = false;
                String what = h.tip() != null && !h.tip().isEmpty() ? h.tip().get(0).getString()
                        : h.quest() != null ? h.quest().name() : L.t("wynnlabs.screen.target");
                if (what.length() > 32) {
                    what = what.substring(0, 32);
                }
                try {
                    String[] p = h.copy().substring("/compass at ".length()).trim().split("\\s+");
                    placed = Waypoints.go(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), what);
                } catch (Throwable ignored) {
                }
                if (placed) {
                    flash = L.t("wynnlabs.screen.guiding", what);
                } else {
                    Minecraft.getInstance().keyboardHandler.setClipboard(h.copy());
                    flash = L.t("wynnlabs.screen.copied", h.copy());
                }
                flashUntil = System.currentTimeMillis() + 2500;
                return true;
            }
            if (h.copy() != null && ex >= h.x() && ex < h.x() + h.w() && ey >= h.y() && ey < h.y() + h.h()) {
                Minecraft.getInstance().keyboardHandler.setClipboard(h.copy());
                flash = L.t("wynnlabs.screen.copied", h.copy());
                flashUntil = System.currentTimeMillis() + 2500;
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private static boolean in(double x, double y, int[] r) {
        return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        if (DevHooks.ON && DevHooks.driving()) {
            return true;                                // the screenshot tool drives the menu
        }
        x = MenuScale.at(x);
        y = MenuScale.at(y);
        if (tab == 0 && BuildWindow.INSTANCE.isOpen()) {
            return BuildWindow.INSTANCE.mouseScrolled(x, y, v);
        }
        if (tab == 0 && !steps.isEmpty()) {
            planPills.pickLevel();
            // the wheel walks the tabs: şimdi, then the shown milestones
            List<Integer> order = new ArrayList<>();
            order.add(0);
            order.addAll(shownSteps());
            int pos = Math.max(0, order.indexOf(selected));
            selected = order.get(Math.max(0, Math.min(order.size() - 1, pos - (int) Math.signum(v))));
        }
        if (tab == 2) {
            if (questDetailTab == 3 && questDetailRect != null && in(x, y, questDetailRect)) {
                pointScroll = Math.max(0, pointScroll - 3 * (int) Math.signum(v));
            } else if (questDetailTab == 0 && questDetailRect != null && in(x, y, questDetailRect)) {
                stepScroll = Math.max(0, stepScroll - (int) Math.signum(v));
            } else {
                questScroll = Math.max(0, questScroll - (int) Math.signum(v));
            }
        }
        if (tab == TAB_DISCOVERIES) {
            // over the chosen discovery its steps scroll, elsewhere the list
            if (discDetailRect != null && in(x, y, discDetailRect)) {
                discStepScroll = Math.max(0, discStepScroll - 3 * (int) Math.signum(v));
            } else {
                discScroll = Math.max(0, discScroll - (int) Math.signum(v));
            }
        }
        if (tab == TAB_BANK) {
            bankScroll = Math.max(0, Math.min(bankScrollMax, bankScroll - PITCH * (int) Math.signum(v)));
        }
        if (tab == TAB_TREE) {
            treeScroll = Math.max(0, Math.min(treeScrollMax, treeScroll - treeCell * (int) Math.signum(v)));
        }
        if (tab == TAB_SETTINGS && settingsListRect != null && in(x, y, settingsListRect)) {
            settingsScroll = Math.max(0, Math.min(settingsScrollMax, settingsScroll - 2 * LIST_ROW * (int) Math.signum(v)));
        }
        if (tab == TAB_CRAFT) {
            CraftingTab.INSTANCE.mouseScrolled(x, y, v);
        }
        if (tab == TAB_ENDGAME) {
            EndgameTab.INSTANCE.mouseScrolled(x, y, v);
        }
        return true;
    }

    @Override
    public void removed() {
        CraftingTab.INSTANCE.closed();          // a search running for the tab stops with the menu
        EndgameTab.INSTANCE.closed();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- helpers ----

    private boolean isGear(ItemStack s) {
        var p = WynntilsItems.view(s);
        return p.isPresent() && (p.get().isGear()
                || p.get().isCrafted());
    }

    private static String nice(String upper) {
        if (upper == null || upper.isEmpty()) {
            return "";
        }
        String n = upper.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** A percentage in the player's language: "%8" in Turkish, "8%" in English. */
    static String pct(Object n) {
        return L.t("wynnlabs.screen.pct", n);
    }

    static String dist(int blocks) {
        return blocks >= 1000 ? Math.round(blocks / 100.0) / 10.0 + " km" : blocks + " m";
    }

    /** Safe green, careful amber, risky red (Danger.risk). */
    static final int[] RISK = {0xFF5DCAA5, 0xFFFAC775, 0xFFF09595};

    static int placeRisk(String p) {
        int bar = p.indexOf('|');
        if (bar <= 0) {
            return 1;
        }
        try {
            return Math.max(0, Math.min(2, Integer.parseInt(p.substring(0, bar))));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    static String shortPlace(String p) {
        int bar = p.indexOf('|');
        if (bar > 0 && bar < 3) {
            p = p.substring(bar + 1);
        }
        // the kind inside may be Grind's id ("cave") or its shown name ("mağara"/"cave"): name it, then shorten
        for (String k : new String[] {Grind.CAVE, Grind.DUNGEON, Grind.ALTAR, Grind.GRIND_SPOT}) {
            p = p.replace(" " + k + " (", " " + Grind.kindName(k) + " (");
        }
        return Grind.shortKind(p);
    }
}
