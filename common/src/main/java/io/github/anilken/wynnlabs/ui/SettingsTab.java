package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.advice.MarketSlots;
import io.github.anilken.wynnlabs.build.Setup;
import io.github.anilken.wynnlabs.build.TreePlanner;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.RuleEditor;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.core.UpdateCheck;
import io.github.anilken.wynnlabs.core.Words;
import io.github.anilken.wynnlabs.game.Wyn;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.market.Vault;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The settings tab: a field guide of categories, rows and their controls, the About page and the
 * Wynnventory key.
 */
final class SettingsTab extends MenuTab {

    SettingsTab(RoadmapScreen menu) {
        super(menu);
    }

    // ---- the tab ----

    @Override
    void draw(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float pt) {
        drawSettings(g, x, y, w, h, mx, my);
    }

    @Override
    List<EditBox> init() {
        // the Wynnventory key, pasted here instead of a file; shown as dots while typed
        keyBox = new EditBox(font(), 0, 0, 150, 12, Component.literal("Wynnventory API key"));
        keyBox.setMaxLength(128);
        keyBox.addFormatter((text, pos) -> net.minecraft.util.FormattedCharSequence.forward("•".repeat(text.length()),
                net.minecraft.network.chat.Style.EMPTY));
        paperField(keyBox);
        ruleBox = new EditBox(font(), 0, 0, 60, 12, L.c("wynnlabs.rules.type_hint"));
        ruleBox.setMaxLength(12);
        paperField(ruleBox);
        ruleBoxFor = -1;
        return List.of(keyBox, ruleBox);
    }

    @Override
    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return toggleClicked(event, doubleClick);
    }

    /** The tab's clicks are its own: the rest go to the screen's widgets. */
    @Override
    boolean sharedClicks() {
        return false;
    }

    /** A click on a menu scale button (About); the next frame draws at the new scale. */
    boolean scaleClicked(double ex, double ey) {
        for (int[] r : scaleRects) {
            if (in(ex, ey, r)) {
                Settings.setNumber("menuScale", r[4]);
                return true;
            }
        }
        return false;
    }

    /**
     * A click on the settings, AI or damage tab: the key and rule boxes, then the switches, chips
     * and buttons this frame drew ({@link RoadmapScreen#toggles}). True when one took it.
     */
    boolean toggleClicked(MouseButtonEvent event, boolean doubleClick) {
        double ex = event.x();
        double ey = event.y();
        boolean dataCat = menu.tab() == RoadmapScreen.TAB_SETTINGS && settingsCat < SETTING_CATS.length && "data".equals(SETTING_CATS[settingsCat]);
        if (dataCat && keyBoxShown && keyBox.mouseClicked(event, doubleClick)) {
            keyBox.setFocused(true);
            menu.setFocused(keyBox);
            return true;
        }
        keyBox.setFocused(false);
        if (ruleBoxFor >= 0 && ruleBoxRect != null && in(ex, ey, ruleBoxRect)) {
            ruleBox.mouseClicked(event, doubleClick);
            ruleBox.setFocused(true);
            menu.setFocused(ruleBox);
            return true;
        }
        if (ruleBox.isFocused()) {
            saveRuleBox();                      // clicking away keeps what was typed
        }
        for (Object[] t : menu.toggles) {
            if (in(ex, ey, (int[]) t[0])) {
                String id = String.valueOf(t[1]);
                if (id.startsWith("#ai")) {
                    menu.ai.aiClick(id);
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
                    menu.setFocused(null);
                } else if (id.startsWith("#sall:")) {
                    SectionSwitches.flip(sectionKeys(settingRows(SETTING_CATS[settingsCat]), Integer.parseInt(id.substring(6))));
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
                    menu.flash(L.t("wynnlabs.rules.reset_done"));
                } else if ("#keysave".equals(t[1])) {
                    saveKey();
                } else if ("#keydel".equals(t[1])) {
                    Prices.setKey("");
                    menu.flash(L.t("wynnlabs.settings.key_removed"));
                } else if ("#archetype".equals(t[1])) {
                    TreePlanner.cycle();
                } else if ("#goal".equals(t[1])) {
                    Setup.cycleGoal();
                } else {
                    Settings.toggle((String) t[1]);
                    if ("webFont".equals(t[1])) {
                        menu.reinit();             // init() again: the screen and its boxes take the new font now
                    }
                }
                return true;
            }
        }
        return false;
    }

    @Override
    void mouseScrolled(double x, double y, double v) {
        if (settingsListRect != null && in(x, y, settingsListRect)) {
            settingsScroll = Math.max(0, Math.min(settingsScrollMax, settingsScroll - 2 * LIST_ROW * (int) Math.signum(v)));
        }
    }

    /** Enter in the rule box or the key box saves it. */
    @Override
    boolean keyPressed(int key) {
        boolean enter = key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;
        if (ruleBox != null && ruleBox.isFocused() && enter) {
            saveRuleBox();
            return true;
        }
        if (keyBox != null && keyBox.isFocused() && enter) {
            saveKey();
            return true;
        }
        return false;
    }

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
        menu.setFocused(null);
        if (!Settings.on("priceApi")) {
            Settings.toggle("priceApi");
        }
        menu.flash(L.t("wynnlabs.settings.key_saved"));
    }

    // ---- settings tab: a field guide ----

    private int settingsCat;

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
    static final int SET_ROW = 32;
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
                    new String[] {"wynnlabs.settings.sec_tooltip", "powderHint", "tooltipPrice", "boxPrices", "bankHint",
                            "sourceHint"}),
            "glow", List.of(
                    new String[] {"wynnlabs.settings.sec_bag", "glowUpgrade", "glowShop", "sellGlow", "verdictBadges",
                            "hotbarBadges"},
                    new String[] {"wynnlabs.settings.sec_ground", "groundGlow", "groundBeam", "groundGlowShop",
                            "groundGlowNormal", "groundGlowOther"},
                    new String[] {"wynnlabs.settings.sec_tracked", "mobTrack", "mobTrackSound"},
                    new String[] {"wynnlabs.settings.sec_menus", "glowTree"}),
            "coach", List.of(
                    new String[] {"wynnlabs.settings.sec_chat", "chatUpgrade", "chatSkills", "chatLevelUp"},
                    new String[] {"wynnlabs.settings.sec_warnings", "dangerWarn", "survivalWarn", "coachToasts"},
                    new String[] {"wynnlabs.settings.sec_screen", "dpsHud", "sellSummary"}),
            "quests", List.<String[]>of(
                    new String[] {"wynnlabs.settings.sec_quests", "glowQuest", "miniQuests"}),
            "build", List.<String[]>of(
                    new String[] {"wynnlabs.settings.sec_tree", "menuAdvice", "autoTree", "learning", "manaMeter",
                            "treeBonuses"}),
            "data", List.of(
                    new String[] {"wynnlabs.settings.sec_online", "priceApi", "questGuides", "updateCheck", "autoUpdate"},
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
        boolean compact = menu.layout.compact();
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
        boolean icons = !menu.layout.compact();
        int cx = x;
        int cy = y;
        for (int c = 0; c < SETTING_CATS.length; c++) {
            String name = L.t(SETTING_CAT_NAMES[c]);
            int changed = changedIn(SETTING_CATS[c]);
            String count = String.valueOf(changed);
            int cw = 12 + (icons ? 11 : 0) + font().width(name) + (changed > 0 ? 10 + font().width(count) : 0);
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
            int room = cx + cw - 6 - tx - (changed > 0 ? 10 + font().width(count) : 0);
            onWood(g, fit(name, room), tx, cy + 3, state);
            if (changed > 0) {
                int dx = cx + cw - 6 - font().width(count) - 8;
                dot(g, dx, cy + 4);
                onWood(g, count, dx + 8, cy + 3, state);
            }
            menu.toggles.add(new Object[] {new int[] {cx, cy, cw, 14}, "#cat:" + c});
            if (hover) {
                List<Component> tip = new ArrayList<>();
                tip.add(Component.literal(name).withStyle(ChatFormatting.WHITE));
                tip.add(L.c(SETTING_CAT_DESC[c]).withStyle(ChatFormatting.GRAY));
                if (changed > 0) {
                    tip.add(L.c("wynnlabs.settings.changed_count", changed).withStyle(ChatFormatting.GOLD));
                }
                menu.hits.add(new MenuHit(cx, cy, cw, 14, null, tip, null));
            }
            cx += cw + 3;
        }
        return cy + 14 - y;
    }

    /** Rules: how many differ from their default, and the reset of this character's rules. */
    private void drawRulesFooter(GuiGraphics g, int x, int y, int w, int mx, int my) {
        String reset = L.t("wynnlabs.rules.reset");
        int bw = font().width(reset) + 24;
        int bx = x + w - bw;
        boolean bh = mx >= bx && mx < bx + bw && my >= y && my < y + 14;
        Kit.button(g, bx, y, bw, 14, bh ? Kit.HOVER : Kit.REST);
        Art.icon(g, "refresh", bx + 6, y + 3, 8, Kit.buttonText(bh ? Kit.HOVER : Kit.REST));
        onWood(g, reset, bx + 17, y + 3, bh ? Kit.HOVER : Kit.REST);
        menu.toggles.add(new Object[] {new int[] {bx, y, bw, 14}, "#rulesreset"});
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
        int t0 = menu.toggles.size();
        g.enableScissor(x + 1, top, x + w - 1, bottomY);
        int ry = top - settingsScroll;
        for (int i = 0; i < rows.size(); i++) {
            SetRow r = rows.get(i);
            int rh = r.head() ? LIST_HEAD : LIST_ROW;
            if (ry + rh > top && ry < bottomY && r.head()) {
                drawListHead(g, r, sectionKeys(rows, i), i, ix, ry, iw, mx, rowMy);
            } else if (ry + rh > top && ry < bottomY) {
                drawListRow(g, r, i, i == pick, ix, ry, iw, rh, mx, rowMy, steppers);
            }
            ry += rh;
        }
        g.disableScissor();
        // click areas: only the part that shows
        for (int i = menu.toggles.size() - 1; i >= t0; i--) {
            if (menu.toggles.get(i)[0] instanceof int[] r) {
                int y0 = Math.max(r[1], top);
                int y1 = Math.min(r[1] + r[3], bottomY);
                if (y1 <= y0) {
                    menu.toggles.remove(i);
                } else {
                    menu.toggles.set(i, new Object[] {new int[] {r[0], y0, r[2], y1 - y0}, menu.toggles.get(i)[1]});
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

    /** The switches under the section title at {@code head}, when it gets the "All off / All on" button. */
    private List<String> sectionKeys(List<SetRow> rows, int head) {
        List<String> keys = new ArrayList<>();
        for (int j = head + 1; j < rows.size() && !rows.get(j).head(); j++) {
            if (rows.get(j).kind().equals("switch")) {
                keys.add(rows.get(j).id());
            }
        }
        return SectionSwitches.keys(SETTING_CATS[settingsCat], keys);
    }

    /** A section title, and on its right the button that turns the section's switches all off or all on. */
    private void drawListHead(GuiGraphics g, SetRow r, List<String> keys, int i, int x, int y, int w, int mx, int my) {
        String title = L.t(r.id());
        boolean anyOn = SectionSwitches.anyOn(keys);
        String label = SectionSwitches.label(anyOn);
        int bw = font().width(label) + 10;
        if (keys.isEmpty() || w - bw - 4 < 40) {
            section(g, title, x + 2, y + 3, w - 4);         // no button, or no room for one beside the title
            return;
        }
        int bx = x + w - 2 - bw;
        int by = y + 1;
        boolean hover = mx >= bx && mx < bx + bw && my >= by && my < by + 12;
        int state = hover ? Kit.HOVER : Kit.REST;
        String cut = fit(title, bx - x - 16);
        section(g, cut, x + 2, y + 3, bx - x - 6);
        Kit.button(g, bx, by, bw, 12, state);
        onWood(g, label, bx + 5, by + 2, state);
        menu.toggles.add(new Object[] {new int[] {bx, by, bw, 12}, "#sall:" + i});
        if (hover) {
            rowTip(bx, by, bw, 12, label, SectionSwitches.tip(anyOn, title));
        }
    }

    private void drawListRow(GuiGraphics g, SetRow r, int i, boolean picked, int x, int y, int w, int h, int mx, int my,
            boolean steppers) {
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
            menu.toggles.add(new Object[] {new int[] {px - 3, y, right - px + 3, h}, "#stog:" + i});
            ctrlX = px - 3;
        } else if (steppers && (r.kind().equals("rule") || r.kind().equals("step"))) {
            String v = shortValue(r);
            int vw = Math.max(42, font().width(v) + 8);
            int bx = right - (vw + 26);
            int sy = y + (h - 12) / 2;
            stepButton(g, bx, sy, "−", mx, my, stepId(r, -1));
            g.fill(bx + 13, sy, bx + 13 + vw, sy + 12, Theme.WELL);
            ink(g, v, bx + 13 + (vw - font().width(v)) / 2, sy + 2, Theme.INK);
            stepButton(g, bx + 14 + vw, sy, "+", mx, my, stepId(r, 1));
            ctrlX = bx - 2;
        } else {
            String v = fit(shortValue(r), w / 2);
            ctrlX = right - font().width(v);
            ink(g, v, ctrlX, y + (h - 8) / 2, changed ? Theme.INK : Theme.INK_3);
            ctrlX -= 2;
        }
        String title = rowTitle(r);
        int lx = x + 4;
        String cut = fit(title, ctrlX - lx - (changed ? 12 : 4));
        ink(g, cut, lx, y + (h - 8) / 2, isToggle(r) && !toggleOn(r) ? Theme.INK_2 : Theme.INK);
        if (changed) {
            dot(g, lx + font().width(cut) + 4, y + (h - 6) / 2);
        }
        menu.toggles.add(new Object[] {new int[] {x, y, ctrlX - x, h}, "#ssel:" + i});
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
            int bw = font().width(back) + 22;
            boolean bh = mx >= px && mx < px + bw && my >= cy && my < cy + 14;
            int st = bh ? Kit.HOVER : Kit.REST;
            Kit.button(g, px, cy, bw, 14, st);
            Art.icon(g, "chevron-left", px + 5, cy + 3, 8, Kit.buttonText(st));
            onWood(g, back, px + 16, cy + 3, st);
            menu.toggles.add(new Object[] {new int[] {px, cy, bw, 14}, "#sback"});
            ink(g, fit(crumb, pw - bw - 8), px + bw + 8, cy + 3, Theme.INK_3);
            cy += 20;
        } else {
            ink(g, fit(crumb, pw), px, cy, Theme.INK_3);
            cy += 12;
        }
        // the name, bold, and "changed" with its dot on the right
        boolean changed = rowChanged(r);
        String chText = L.t("wynnlabs.settings.changed");
        int chW = changed ? font().width(chText) + 10 : 0;
        List<FormattedCharSequence> titleLines = font().split(Component.literal(rowTitle(r)).withStyle(UiFont::bold), pw - chW - 4);
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
            if (dx + font().width(def) <= px + pw) {
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
                int bw = font().width(label) + 24;
                boolean bh = mx >= rx && mx < rx + bw && my >= resetY && my < resetY + 14;
                int st = bh ? Kit.HOVER : Kit.REST;
                Kit.button(g, rx, resetY, bw, 14, st);
                Art.icon(g, "refresh", rx + 6, resetY + 3, 8, Kit.buttonText(st));
                onWood(g, label, rx + 17, resetY + 3, st);
                menu.toggles.add(new Object[] {new int[] {rx, resetY, bw, 14}, "#sreset:" + index});
                rx += bw + 8;
            } else {
                String at = L.t("wynnlabs.settings.at_default");
                ink(g, fit(at, pw), rx, resetY + 3, Theme.INK_3);
                rx += font().width(at) + 8;
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
            int cw = 24 + font().width(state);
            boolean hover = mx >= x - 2 && mx < x + cw + 2 && my >= y && my < y + 14;
            if (hover) {
                g.fill(x - 2, y, x + cw + 2, y + 14, Theme.CARD_HOVER);
            }
            Kit.toggle(g, x, y + 1, on);
            ink(g, state, x + 24, y + 3, on ? Theme.GOOD : Theme.INK_2);
            menu.toggles.add(new Object[] {new int[] {x - 2, y, cw + 4, 14}, "#stog:" + index});
            return new int[] {cw, 14};
        }
        switch (r.kind()) {
            case "rule" -> {
                RuleEditor.Rule rule = r.r();
                String value = L.cap(RuleEditor.display(rule));
                boolean emerald = rule.unit().equals("E");
                int vw = emerald ? 64 : Math.max(44, font().width(value) + 10);
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
                    onWood(g, u, ux + (uw - font().width(u)) / 2, sy + 2, uh ? Kit.HOVER : Kit.REST);
                    menu.toggles.add(new Object[] {new int[] {ux, sy, uw, 12}, "#ruleunit"});
                } else {
                    g.fill(x + 14, sy, x + 16 + vw, sy + 12, Theme.WELL);
                    ink(g, value, x + 15 + (vw - font().width(value)) / 2, sy + 2, Theme.INK);
                }
                stepButton(g, x + 16 + vw, sy, "+", mx, my, "#rule:" + r.rule() + ":1");
                return new int[] {28 + vw, 14};
            }
            case "step" -> {
                String value = shortValue(r);
                int vw = Math.max(44, font().width(value) + 10);
                int sy = y + 1;
                stepButton(g, x, sy, "−", mx, my, stepId(r, -1));
                g.fill(x + 14, sy, x + 16 + vw, sy + 12, Theme.WELL);
                ink(g, value, x + 15 + (vw - font().width(value)) / 2, sy + 2, Theme.INK);
                stepButton(g, x + 16 + vw, sy, "+", mx, my, stepId(r, 1));
                return new int[] {28 + vw, 14};
            }
            case "arch", "goal" -> {
                String chip = shortValue(r) + " ›";
                int cw = Math.min(w, font().width(chip) + 14);
                boolean hover = mx >= x && mx < x + cw && my >= y && my < y + 14;
                Kit.button(g, x, y, cw, 14, hover ? Kit.HOVER : Kit.REST);
                onWood(g, fit(chip, cw - 14), x + 7, y + 3, hover ? Kit.HOVER : Kit.REST);
                menu.toggles.add(new Object[] {new int[] {x, y, cw, 14}, r.kind().equals("arch") ? "#archetype" : "#goal"});
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
        if (font().width(game) + 12 + 3 * 25 > w) {
            game = L.t("wynnlabs.settings.scale_game");
        }
        String[] names = {game, "2", "3", "4"};
        int[] values = {0, 2, 3, 4};
        int bx = x;
        for (int i = 0; i < names.length; i++) {
            int bw = Math.max(22, font().width(names[i]) + 12);
            boolean sel = pick == values[i];
            boolean hover = mx >= bx && mx < bx + bw && my >= y && my < y + 14;
            int state = sel ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
            Kit.button(g, bx, y, bw, 14, state);
            g.drawString(font(), names[i], bx + (bw - font().width(names[i])) / 2, y + 3, Kit.buttonText(state), !sel);
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
            settingsDetail = menu.layout.compact();
            return;
        }
        if (id.startsWith("#stog:")) {
            if (!menu.layout.compact()) {
                settingsPick[settingsCat] = i;      // the panel follows the switch you flipped
            }
            switch (r.kind()) {
                case "switch" -> {
                    Settings.toggle(r.id());
                    if ("webFont".equals(r.id())) {
                        menu.reinit();           // init() again: the screen and its boxes take the new font now
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
                    menu.reinit();
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
        menu.flash(L.t("wynnlabs.settings.reset_done", rowTitle(r)));
    }

    /** Advice on this character on or off; the road map follows. */
    private void adviceHere(boolean on) {
        Rules.setAdviceHere(on);
        menu.steps = menu.rebuild();
        menu.refreshAsked = Rules.adviceHere();
    }

    /** The preview's shots: this category, this row picked, and (compact) its page open. */
    void previewSettings(int cat, int row, boolean detail) {
        settingsCat = cat;
        settingsPick[cat] = row;
        settingsDetail = detail;
        settingsScroll = 0;
    }

    // ---- settings: About ----

    /** The update card, made on first use. */
    private AboutUpdateCard updateCard;

    private AboutUpdateCard updateCard() {
        if (updateCard == null) {
            updateCard = new AboutUpdateCard(menu);
        }
        return updateCard;
    }

    /**
     * About: whether this is the newest Wynn Labs and what to do about it ({@link AboutUpdateCard}),
     * the links (source, a bug report, the licence), the mod's version and loader, and which
     * Wynntils features work.
     */
    private void drawAboutPage(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        UpdateCheck.State s = UpdateCheck.state();
        String mine = UpdateScreen.vName(s.own().isEmpty() ? UpdateCheck.version.get() : s.own());
        String loader = UpdateScreen.loaderLabel();
        int cardH = updateCard().draw(g, x, y, w, mx, my);
        int cy = y + cardH + 8;

        // the links, each opened in the browser only after the game's "open this link?" prompt
        section(g, L.t("wynnlabs.about.links"), x, cy, w);
        cy += 12;
        int lx = x;
        for (int i = 0; i < ABOUT_LINKS.length; i++) {
            String label = L.t(ABOUT_LINKS[i][0]);
            int bw = font().width(label) + 25;
            if (lx > x && lx + bw > x + w) {
                lx = x;
                cy += 17;
            }
            boolean bh = mx >= lx && mx < lx + bw && my >= cy && my < cy + 14;
            int st = bh ? Kit.HOVER : Kit.REST;
            Kit.button(g, lx, cy, bw, 14, st);
            Art.icon(g, "arrow-up-right", lx + 6, cy + 3, 8, Kit.buttonText(st));
            onWood(g, label, lx + 17, cy + 3, st);
            menu.toggles.add(new Object[] {new int[] {lx, cy, bw, 14}, "#link:" + i});
            if (bh) {
                menu.hits.add(new MenuHit(lx, cy, bw, 14, null, List.of(L.c("wynnlabs.about.link_tip", ABOUT_LINKS[i][1])), null));
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

    /** The About page's buttons: the update card's, the setting, a link. */
    private void aboutClick(String id) {
        switch (id) {
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
                        settingsDetail = menu.layout.compact();
                    }
                }
            }
            default -> {
                if (updateCard().click(id) || !id.startsWith("#link:")) {
                    return;
                }
                int i = Integer.parseInt(id.substring("#link:".length()));
                if (i >= 0 && i < ABOUT_LINKS.length) {
                    updateCard().openLink(ABOUT_LINKS[i][1]);
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
            menu.flash(ok ? L.t("wynnlabs.rules.typed_ok", L.t(r.title()), RuleEditor.display(r)) : L.t("wynnlabs.rules.typed_bad"));
        }
        ruleBox.setFocused(false);
        menu.setFocused(null);
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

    private final List<int[]> scaleRects = new ArrayList<>();   // {x, y, w, h, scale}

    /** About: the Wynntils version and which of its features work with it. */
    private void drawAbout(GuiGraphics g, int x, int y, int w, int mx, int my) {
        var down = Wyn.down();
        Component title = Component.literal(L.t("wynnlabs.wyn.about", Wyn.version())).withStyle(UiFont::bold);
        String line = L.t(down.isEmpty() ? "wynnlabs.wyn.all_ok" : "wynnlabs.wyn.some_off");
        // the card is as wide as its text (no wider than the page); a narrow page wraps the line
        int need = Math.max(font().width(title) + 16, font().width(line) + 30);
        for (var e : down.entrySet()) {
            need = Math.max(need, (int) (font().width(Wyn.name(e.getKey()) + " — " + e.getValue()) * DESC_SCALE) + 24);
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
            buttons += font().width(b[0]) + 14 + 4;
        }
        int kw = Math.max(60, Math.min(220, w - buttons - 2));
        textBox(g, keyBox, x, by, kw, "wynnlabs.settings.key_box", mx, my, 0f);
        int sx = x + kw + 6;
        for (String[] b : btns) {
            int bw = font().width(b[0]) + 14;
            boolean bh = mx >= sx && mx < sx + bw && my >= by && my < by + 12;
            card(g, sx, by, bw, 12, bh, 0);
            g.drawString(font(), b[0], sx + 7, by + 2, b[1].equals("#keydel") ? 0xFFFFB0A0 : 0xFFB8F0A0, true);
            menu.toggles.add(new Object[] {new int[] {sx, by, bw, 12}, b[1]});
            sx += bw + 4;
        }
        return 50;
    }
}
