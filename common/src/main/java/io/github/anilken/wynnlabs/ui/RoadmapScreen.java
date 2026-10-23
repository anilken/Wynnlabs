package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap;
import io.github.anilken.wynnlabs.core.DevHooks;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.game.Wyn;
import io.github.anilken.wynnlabs.game.WynntilsItems;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.Slot;
import io.github.anilken.wynnlabs.quests.Grind;
import io.github.anilken.wynnlabs.quests.QuestAdvisor;
import io.github.anilken.wynnlabs.quests.QuestBook;
import io.github.anilken.wynnlabs.quests.QuestGuides;
import io.github.anilken.wynnlabs.quests.Waypoints;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The J menu's window: the wooden frame, the rail of tabs on the left, the page title, the flash
 * line and the tooltips, and the routing of clicks, the wheel and keys to the open tab. Each tab
 * draws its own page in its own class ({@link MenuTab}: {@link HomePage}, {@link TodayTab},
 * {@link QuestsTab}, {@link DeathsTab}, {@link BankTab}, {@link SettingsTab}, {@link AiTab},
 * {@link DamageTab}, {@link DiscoveriesTab}, {@link TreeTab}; the Crafting and Endgame tabs are
 * {@link CraftingTab} and {@link EndgameTab}). The screen keeps what they share: the road map's
 * steps and the chosen milestone, the quest suggestions, this frame's hover and click areas and
 * the flash line. Shows only; never buys, starts or clicks anything in the game.
 */
public final class RoadmapScreen extends Screen {
    /** Tab names as language keys; show with {@code L.t(TABS[i])}. */
    private static final String[] TABS = {"wynnlabs.screen.tab_home", "wynnlabs.screen.tab_today", "wynnlabs.screen.tab_quests",
        "wynnlabs.screen.tab_deaths", "wynnlabs.screen.tab_bank", "wynnlabs.screen.tab_settings", "wynnlabs.screen.tab_ai",
        "wynnlabs.screen.tab_damage", "wynnlabs.screen.tab_discoveries", "wynnlabs.screen.tab_tree", "wynnlabs.screen.tab_craft",
        "wynnlabs.screen.tab_endgame", "wynnlabs.screen.tab_search"};
    public static final int TAB_BANK = 4;
    static final int TAB_SETTINGS = 5;
    static final int TAB_AI = 6;
    static final int TAB_DAMAGE = 7;
    static final int TAB_DISCOVERIES = 8;
    static final int TAB_TREE = 9;
    static final int TAB_CRAFT = 10;
    static final int TAB_ENDGAME = 11;
    static final int TAB_SEARCH = 12;

    List<Roadmap.Step> steps = List.of();
    List<QuestAdvisor.Suggestion> quests = List.of();
    private long questsAt;
    /** The chosen milestone, an index in {@link #steps} (0: now). */
    int selected;
    private int tab;
    private final Map<String, ItemStack> icons = new HashMap<>();
    final List<MenuHit> hits = new ArrayList<>();

    /** The preview: whether a hover area with a tooltip lies under (x, y) this frame (logical pixels). */
    boolean tooltipAt(int x, int y) {
        for (MenuHit h : hits) {
            boolean tip = h.stack() != null || h.tip() != null && !h.tip().isEmpty();
            if (tip && x >= h.x() && x < h.x() + h.w() && y >= h.y() && y < h.y() + h.h()) {
                return true;
            }
        }
        return false;
    }
    private final List<int[]> tabs = new ArrayList<>();        // x, y, w, h, tab index
    /** The switches, chips and buttons of the settings, AI and damage tabs: {int[] rect, String key}. */
    final List<Object[]> toggles = new ArrayList<>();
    private String flash = "";
    private long flashUntil;
    String error;

    // the tabs, made with the screen: their views start afresh each time the menu opens
    private final MenuPaint paint = new MenuPaint(this);
    public final HomePage home = new HomePage(this);
    final TodayTab today = new TodayTab(this);
    public final QuestsTab questsTab = new QuestsTab(this);
    final DeathsTab deaths = new DeathsTab(this);
    public final BankTab bank = new BankTab(this);
    public final SettingsTab settings = new SettingsTab(this);
    final AiTab ai = new AiTab(this);
    final DamageTab damage = new DamageTab(this);
    final DiscoveriesTab discoveries = new DiscoveriesTab(this);
    public final TreeTab tree = new TreeTab(this);
    final SearchTab search = new SearchTab(this);
    /** The tabs by number (null for the Crafting and Endgame tabs, which are their own singletons). */
    private final MenuTab[] pages = {home, today, questsTab, deaths, bank, settings, ai, damage, discoveries, tree, null, null,
        search};
    /** The screen the search key was pressed on (a bank, a chest): closing the menu goes back to it. */
    private Screen parent;

    /** The menu on the search tab, on {@code item} when not null; Esc goes back to {@code parent} when not null. */
    public static RoadmapScreen search(String item, Screen parent) {
        RoadmapScreen s = new RoadmapScreen();
        s.tab = TAB_SEARCH;
        s.parent = parent;
        if (item != null) {
            s.search.show(item);
        }
        return s;
    }

    public RoadmapScreen() {
        super(L.c("wynnlabs.screen.title"));
        // a quest being tracked: the menu opens on the quests tab with that quest chosen
        try {
            if (Wyn.tracking() && Wyn.trackedName() != null) {
                tab = 2;
                questsTab.questSelected = Wyn.trackedName();
            }
        } catch (Throwable ignored) {
            // no Wynntils: the home tab
        }
    }

    /** The screen's font, for the tabs (it changes with the webFont setting). */
    net.minecraft.client.gui.Font font() {
        return font;
    }

    /** The open tab's number. */
    int tab() {
        return tab;
    }

    /** A short note at the foot of the window for a few seconds ("Copied", "Saved"). */
    void flash(String text) {
        flash = text;
        flashUntil = System.currentTimeMillis() + 2500;
    }

    /** The screen made again with the current font (the webFont switch). */
    void reinit() {
        rebuildWidgets();
    }

    @Override
    protected void init() {
        UiFont.apply(this);                     // Inter instead of the pixel font (setting: webFont)
        WynnTex.clear();                        // the pack may have changed since last time
        for (MenuTab t : new MenuTab[] {bank, tree, settings, search}) {
            for (net.minecraft.client.gui.components.EditBox b : t.init()) {
                addWidget(b);
            }
        }
        // the search tab's items, read in the background from the first opening on
        io.github.anilken.wynnlabs.search.ItemIndex.startLoading(io.github.anilken.wynnlabs.core.Platform.get().gameDir(),
                io.github.anilken.wynnlabs.core.Dirs.config().resolve("chests.json"));
        if (tab == TAB_SEARCH) {
            search.focus();
        }
        for (net.minecraft.client.gui.components.EditBox b : CraftingTab.INSTANCE.init(this, font, this::flash)) {
            addWidget(b);
        }
        for (net.minecraft.client.gui.components.EditBox b : BuildWindow.INSTANCE.init(font)) {
            addWidget(b);
        }
        BuildWindow.INSTANCE.host(home.windowHost);
        try {
            steps = Roadmap.cached();
            stepsVersion = Roadmap.version();
        } catch (Throwable t) {
            error = L.t("wynnlabs.screen.error_build", t.getClass().getSimpleName());
            System.out.println("[WynnLabs] roadmap failed:");
            t.printStackTrace(System.out);
        }
        refreshQuests();
        if (tab == TAB_SEARCH) {
            search.focus();                     // last, so no box added after it takes the keys
        }
    }

    private int stepsVersion;
    /** A button here asked for a fresh map: its "updating" bar shows until the map arrives. */
    boolean refreshAsked;

    private void refreshQuests() {
        try {
            quests = QuestAdvisor.suggest(7);
        } catch (Throwable t) {
            quests = List.of();
        }
        questsAt = System.currentTimeMillis();
    }

    ItemStack icon(ItemData info) {
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

    /** The piece behind each icon stack (by identity), for its tooltip's requirement marks. */
    final Map<ItemStack, ItemData> iconInfo = new java.util.IdentityHashMap<>();

    /**
     * Wynntils' tooltip of a piece you do not own marks every requirement unmet: its guide stack
     * has no instance, so nothing says what you meet (an owned one gets that from the server).
     * The marks are set again here from your level, class and skill points (assigned plus
     * gear) and your quest book. Null when the stack is not one of these.
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
                // from your quest book (a stack with no instance always showed ✘ here); a quest
                // the book does not list stays as Wynntils drew it
                String want = info.requirements().quest().get().replace('’', '\'').trim()
                        .toLowerCase(java.util.Locale.ROOT);
                Boolean done = null;
                for (io.github.anilken.wynnlabs.quests.QuestBook.Entry q : GameState.current().quests()) {
                    if (q.name().replace('’', '\'').trim().toLowerCase(java.util.Locale.ROOT).equals(want)) {
                        done = "COMPLETED".equals(q.status());
                        break;
                    }
                }
                flags.add(done);
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

    // ---- layout ----

    /** This frame's size class, panel and rail (MenuLayout): set at the start of each render. */
    MenuLayout layout = MenuLayout.of(640, 360);
    /** The rail's order (the tab numbers stay what the rest of the screen uses). */
    private static final int[] RAIL = {0, TAB_SEARCH, TAB_CRAFT, TAB_TREE, 1, TAB_ENDGAME, 2, TAB_DISCOVERIES, TAB_BANK, TAB_DAMAGE, 3,
        TAB_AI, TAB_SETTINGS};
    /**
     * Each rail entry's hint as a language key ("" for none). The Endgame tab's title line holds
     * its pills, so its hint is drawn under its aspect list instead.
     */
    private static final String[] RAIL_HINT = {
        "", "", "", "wynnlabs.screen.hint_tree", "wynnlabs.screen.hint_today", "wynnlabs.screen.hint_endgame",
        "wynnlabs.screen.hint_quests", "wynnlabs.screen.hint_discoveries", "wynnlabs.screen.hint_bank", "wynnlabs.screen.hint_damage", "wynnlabs.screen.hint_deaths", "wynnlabs.screen.hint_ai",
        "wynnlabs.screen.hint_settings"};
    private final Map<Integer, ItemStack> railIcons = new HashMap<>();

    /** For the preview's layout check: the click areas drawn this frame (rows and buttons with an action). */
    List<LayoutCheck.Area> clickAreas() {
        List<LayoutCheck.Area> out = new ArrayList<>();
        for (MenuHit h : hits) {
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

    int panelW() {
        return MenuLayout.of(width, height).w();
    }

    int railW() {
        return layout.railW();
    }

    ItemStack railIcon(int tab) {
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
    int frameMx;
    int frameMy;

    private void draw(GuiGraphics g, int mx, int my, float pt) {
        frameMx = mx;
        frameMy = my;
        super.render(g, mx, my, pt);
        hits.clear();
        tabs.clear();
        toggles.clear();
        for (MenuTab t : pages) {
            if (t != null) {
                t.beginFrame();
            }
        }
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
            int right = pages[tab] != null ? pages[tab].titleRight(g, cx + cw, cy, titleRoom, mx, my) : 0;
            int gap = right > 0 ? right + 10 : 0;
            // the page hint where it fits beside the title (none in the compact layout)
            String hint = RAIL_HINT[idx].isEmpty() || tab == 1 || tab == TAB_ENDGAME || layout.compact() ? "" : L.t(RAIL_HINT[idx]);
            hint = right > 0 && titleRoom - gap < 60 ? "" : paint.fit(hint, titleRoom - gap);
            Kit.text(g, font, hint, cx + cw - gap - font.width(hint), cy, Theme.INK_3);
            g.fill(cx, cy + 12, cx + cw, cy + 13, Theme.LINE);
            cy += 19;
            ch -= 19;
        }
        switch (tab) {
            case TAB_CRAFT -> CraftingTab.INSTANCE.draw(g, cx, cy, cw, ch, cy - 19, new int[] {x0, y0, w, h}, width, mx, my, pt);
            case TAB_ENDGAME -> EndgameTab.INSTANCE.draw(g, cx, cy, cw, ch, cy - 19, new int[] {x0, y0, w, h}, width, mx, my, pt);
            default -> pages[tab].draw(g, cx, cy, cw, ch, mx, my, pt);
        }
        if (tab == 0) {
            home.drawGoalList(g, mx, my);
        }
        // the build window over the home: what is under it neither shows a tooltip nor takes a click
        if (tab == 0 && BuildWindow.INSTANCE.isOpen()) {
            hits.clear();
            home.underWindow();
            BuildWindow.INSTANCE.draw(g, new int[] {x0, y0, w, h}, layout.large(), layout.compact(), home.windowHost, mx, my, pt);
            net.minecraft.client.gui.components.EditBox f = BuildWindow.INSTANCE.focused();
            if (f != null && getFocused() != f) {
                setFocused(f);
            }
        } else if (BuildWindow.INSTANCE.isOpen()) {
            BuildWindow.INSTANCE.close();
        }
        BuildWindow.INSTANCE.poll(home.windowHost);
        // close (drawn last, so the crafting tab's saved panel beside it never covers it): a red seal just outside the window's top-right corner (Esc does the same)
        int[] cr = closeRect;
        boolean closeHover = cr != null && mx >= cr[0] && mx < cr[0] + cr[2] && my >= cr[1] && my < cr[1] + cr[3];
        closeRect = Kit.closeOutside(g, width, x0, y0, w, closeHover);
        int bx = closeRect[0];
        int by = closeRect[1];
        if (closeHover) {
            hits.add(new MenuHit(bx, by, closeRect[2], closeRect[3], null, List.of(L.c("wynnlabs.screen.close")), null));
        }

        if (System.currentTimeMillis() < flashUntil) {
            Component f = Component.literal("✔ " + flash).withStyle(ChatFormatting.GREEN);
            int fw = font.width(f) + 12;
            int fx = cx + (cw - fw) / 2;
            int fy = y0 + h - 18;
            Kit.card(g, fx, fy, fw, 14, 0);
            Kit.text(g, font, f, fx + 6, fy + 3, Theme.GOOD);
        }
        for (MenuHit hit : hits) {
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
                paint.badge(g, t, ix, iy, icon);
                hits.add(new MenuHit(x, ry, railW(), eh, null, List.of(Component.literal(name)), null));
            } else {
                if (on) {
                    // the selected tab is gold and reaches over the page's torn rim with a tip
                    Kit.button(g, x + 4, ry, railW() - 4, eh, Kit.ON);
                    Kit.tip(g, x + railW(), ry, eh);
                } else {
                    Kit.button(g, x + 4, ry, railW() - 8, eh, hover ? Kit.HOVER : Kit.REST);
                }
                paint.badge(g, t, x + 8 + (16 - icon) / 2, ry + (eh - icon) / 2, icon);
                g.drawString(font, paint.fit(name, railW() - 34), x + 30, ry + (eh - 8) / 2, on ? Theme.INK : Theme.CREAM, !on);
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
        String label = paint.fit(names[pick], w - 4);
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 14;
        int state = hover ? Kit.HOVER : Kit.REST;
        Kit.button(g, x, y, w, 14, state);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + 3, Kit.buttonText(state), true);
        langRects.add(new int[] {x, y, w, 14, (pick + 1) % names.length});
        hits.add(new MenuHit(x, y, w, 14, null, List.of(Component.literal(L.t("wynnlabs.settings.language") + ": " + names[pick])), null));
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

    /** An explicit change the road map follows (archetype, goal, powders, language): built again when there is one. */
    List<Roadmap.Step> rebuild() {
        if (Roadmap.hasMap()) {
            Roadmap.forceRefresh();
        }
        return Roadmap.cached();
    }

    /** {@link #rebuild}: the map built again in the background, its "updating" bar shown until it arrives. */
    void rebuildMap() {
        steps = rebuild();
        refreshAsked = true;
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
        // the search key on another tab, while nothing is being typed: the search tab
        if (tab != TAB_SEARCH && SearchKey.matches(event)
                && !(getFocused() instanceof net.minecraft.client.gui.components.EditBox b && b.isFocused())) {
            SearchKey.swallowNextChar();
            openTab(TAB_SEARCH);
            return true;
        }
        if (pages[tab] != null && pages[tab].keyPressed(event.key())) {
            return true;
        }
        return super.keyPressed(event);
    }
    private int[] closeRect;
    private final List<int[]> langRects = new ArrayList<>();   // {x, y, w, h, choice}

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
        if (tab == TAB_SEARCH && search.mouseDragged(e.x(), e.y())) {
            return true;
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
        search.mouseReleased();
        return super.mouseReleased(MenuScale.event(event));
    }

    /**
     * A click: the build window over the home, the goal list, the close seal, the language and the
     * rail first; then the open tab; then, unless the tab keeps its clicks, this frame's shared
     * click areas ({@link #hitClicked}).
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (DevHooks.ON && DevHooks.driving()) {
            return true;                                // the screenshot tool drives the menu
        }
        event = MenuScale.event(event);                 // logical pixels, as drawn
        double ex = event.x();
        double ey = event.y();
        if (tab == 0 && BuildWindow.INSTANCE.isOpen()) {
            if (closeRect != null && MenuPaint.in(ex, ey, closeRect)) {
                onClose();
                return true;
            }
            net.minecraft.client.gui.components.EditBox f = BuildWindow.INSTANCE.focused();
            if (f != null && f.mouseClicked(event, doubleClick)) {
                return true;
            }
            return BuildWindow.INSTANCE.mouseClicked(ex, ey, event.button());
        }
        if (home.goalClicked(ex, ey)) {
            return true;
        }
        if (closeRect != null && MenuPaint.in(ex, ey, closeRect)) {
            onClose();
            return true;
        }
        if (tab == TAB_SETTINGS && settings.scaleClicked(ex, ey)) {
            return true;                                // the next frame draws at the new scale
        }
        for (int[] r : langRects) {
            if (MenuPaint.in(ex, ey, r)) {
                Settings.setNumber("langOverride", r[4]);
                rebuildMap();                       // its text is in the old language: rebuilt in the background
                return true;
            }
        }
        for (int[] t : tabs) {
            if (MenuPaint.in(ex, ey, t)) {
                openTab(t[4]);
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
        MenuTab page = pages[tab];
        if (page.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (page.sharedClicks()) {
            for (MenuHit h : hits) {
                if (hitClicked(h, event)) {
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** Shows tab {@code t}: its text box takes the keys; leaving the search tab takes its marks off the map. */
    void openTab(int t) {
        if (tab == TAB_SEARCH && t != TAB_SEARCH) {
            search.closed();
        }
        tab = t;
        home.detail = null;
        bank.search.setFocused(tab == TAB_BANK);
        tree.treeSearch.setFocused(tab == TAB_TREE);
        search.box.setFocused(false);
        setFocused(tab == TAB_BANK ? bank.search : tab == TAB_TREE ? tree.treeSearch : null);
        if (tab == TAB_SEARCH) {
            search.focus();
        }
    }

    /** Typing, except the search key's own character right after it opened the tab. */
    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        return SearchKey.swallow() || super.charTyped(event);
    }

    /** Esc: back to the screen the search key was pressed on, when there is one. */
    @Override
    public void onClose() {
        if (parent != null && minecraft != null) {
            minecraft.setScreen(parent);
            return;
        }
        super.onClose();
    }

    /**
     * A click on one of this frame's shared click areas, any tab: a discovery's or a quest's
     * action, a quest to track, a guide, a place for the compass, or text to copy.
     */
    private boolean hitClicked(MenuHit h, MouseButtonEvent event) {
        double ex = event.x();
        double ey = event.y();
        boolean over = ex >= h.x() && ex < h.x() + h.w() && ey >= h.y() && ey < h.y() + h.h();
        if (h.copy() != null && h.copy().startsWith("#disc") && over && discoveries.discoveryClick(h.copy())) {
            return true;
        }
        if (h.copy() != null && h.copy().startsWith("#q") && over) {
            String c = h.copy();
            if (c.startsWith("#qgo:")) {
                tab = TAB_BANK;
                home.detail = null;
            } else if (c.startsWith("#qopen:")) {
                tab = Integer.parseInt(c.substring(7));     // a home tile: its tab
                home.detail = null;
            } else if (c.startsWith("#qdisc:")) {
                tab = TAB_DISCOVERIES;                      // the home's nearest discovery, chosen there
                discoveries.discSelected = c.substring(7);
                home.detail = null;
            } else if (!questsTab.action(c)) {
                home.action(c, event.button());
            }
            return true;
        }
        if (h.quest() != null && event.button() == 0 && over) {
            // Track it like a click in the content book would; close so the book can open
            QuestBook.Entry q = h.quest();
            onClose();
            if (QuestBook.track(q)) {
                Minecraft.getInstance().gui.setOverlayMessage(
                        L.c("wynnlabs.quests.now_tracking", QuestGuides.title(q.name())).withStyle(ChatFormatting.GREEN), false);
            }
            return true;
        }
        if (h.copy() != null && h.copy().startsWith("#guide:") && over) {
            String[] qs = h.copy().substring(7).split("\n", 2);
            Minecraft.getInstance().setScreen(new GuideScreen(qs[0], qs.length > 1 ? qs[1] : ""));
            return true;
        }
        if (h.copy() != null && h.copy().startsWith("/compass at ") && over) {
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
                flash(L.t("wynnlabs.screen.guiding", what));
            } else {
                Minecraft.getInstance().keyboardHandler.setClipboard(h.copy());
                flash(L.t("wynnlabs.screen.copied", h.copy()));
            }
            return true;
        }
        if (h.copy() != null && over) {
            Minecraft.getInstance().keyboardHandler.setClipboard(h.copy());
            flash(L.t("wynnlabs.screen.copied", h.copy()));
            return true;
        }
        return false;
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
        if (pages[tab] != null) {
            pages[tab].mouseScrolled(x, y, v);
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
        search.closed();                        // its worker stops; its map marks go unless kept
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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
