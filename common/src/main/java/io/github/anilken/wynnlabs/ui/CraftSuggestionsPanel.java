package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.crafting.Craft;
import io.github.anilken.wynnlabs.crafting.CraftChecks;
import io.github.anilken.wynnlabs.crafting.CraftData;
import io.github.anilken.wynnlabs.crafting.CraftSearch;
import io.github.anilken.wynnlabs.crafting.Ingredient;
import io.github.anilken.wynnlabs.crafting.RecipeCode;
import io.github.anilken.wynnlabs.crafting.SearchRunner;
import io.github.anilken.wynnlabs.crafting.Suggestions;
import io.github.anilken.wynnlabs.crafting.Types;
import io.github.anilken.wynnlabs.crafting.WhatCanICraft;
import io.github.anilken.wynnlabs.crafting.WynnBuilderCode;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.game.Professions;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.ui.CraftingTab.Act;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.github.anilken.wynnlabs.ui.CraftFormat.costLine;
import static io.github.anilken.wynnlabs.ui.CraftFormat.range;
import static io.github.anilken.wynnlabs.ui.CraftFormat.skill;
import static io.github.anilken.wynnlabs.ui.CraftFormat.typeIcon;
import static io.github.anilken.wynnlabs.ui.CraftFormat.typeName;

/** The crafting tab's suggestions page: the mode and filter buttons and the single-piece suggestions. */
final class CraftSuggestionsPanel extends CraftPanel {

    private final SearchRunner suggestRunner = new SearchRunner();
    private CraftSearch.Budget suggesting;
    private String suggestedFor = "";
    int suggestPage;
    int suggestFilter;

    CraftSuggestionsPanel(CraftingTab tab) {
        super(tab);
    }

    /** The menu was closed: a running search stops. */
    void stop() {
        suggestRunner.cancel();
        suggesting = null;
        suggestedFor = "";
    }

    static final String[] SUGGEST_FILTERS = {"all", "craftable", "stock", "cheap", "weapon", "armour", "accessory",
        "consumable"};

    /** The class's order of goal sets: spell first for casters, melee first for the others. */
    List<String> goalOrder() {
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
    List<String> suggestTypes() {
        List<String> t = new ArrayList<>();
        t.add(CraftingTab.defaultType());
        if (t.get(0).equals("ring")) {
            t.clear();
            t.addAll(Types.WEAPONS);
        }
        t.addAll(Types.ARMOUR);
        t.addAll(Types.ACCESSORIES);
        t.addAll(Types.CONSUMABLES);
        return t;
    }

    static int combatLevel() {
        try {
            return Math.max(1, GameState.current().level());
        } catch (Throwable t) {
            return 1;
        }
    }

    /** A suggestion whose recipe or an ingredient needs a higher profession level than the player has. */
    private boolean locked(Suggestions.Suggestion s) {
        int lv = tab.level(s.recipe().profession());
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

    void startSuggestions(boolean force) {
        String k = tab.suggestKey();
        if (!force && k.equals(suggestedFor) && (suggesting != null || !tab.suggestions.isEmpty())) {
            return;
        }
        suggestedFor = k;
        tab.suggestions.clear();
        suggestPage = 0;
        WhatCanICraft.Stock st = CraftingTab.previewLevels.isEmpty() ? tab.stock
                : new WhatCanICraft.Stock(tab.stock.ingredients(), tab.stock.materials(), CraftingTab.previewLevels);
        List<Ingredient> all = tab.ingredients;
        Map<String, CraftSearch.Price> pr = new HashMap<>(tab.prices);
        List<String> types = suggestTypes();
        List<Suggestions.GoalSet> goals = Suggestions.goalSets(goalOrder());
        int combat = combatLevel();
        suggesting = suggestRunner.run(120_000, b -> {
            Suggestions.run(types, goals, combat, st, all, pr::get, 150, b, tab.suggestions::add);
            b.cancel();                              // done: the page stops showing progress
        });
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

    void drawSuggestions(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        tab.clip = null;
        // sets or single pieces, then the filters
        int fx = x;
        String[] modes = {L.t("wynnlabs.craft.suggest_mode.sets"), L.t("wynnlabs.craft.suggest_mode.singles")};
        for (int i = 0; i < modes.length; i++) {
            int bw = width(modes[i]) + 10;
            boolean on = i == tab.suggestMode;
            int state = on ? Kit.ON : in(mx, my, fx, y, bw, ROW_H) ? Kit.HOVER : Kit.REST;
            Kit.button(g, fx, y, bw, ROW_H, state);
            wood(g, modes[i], fx + 5, y + 2, Kit.buttonText(state), !on);
            int m = i;
            tab.acts.add(new Act(fx, y, bw, ROW_H, () -> {
                tab.suggestMode = m;
                suggestPage = 0;
                tab.setsPanel.setPage = 0;
                if (m == 0) {
                    tab.setsPanel.startSets(false);
                } else {
                    startSuggestions(false);
                }
            }, null, null));
            fx += bw + 2;
        }
        fx += 8;
        for (int i = 0; i < SUGGEST_FILTERS.length; i++) {
            if (tab.suggestMode == 0 && i > 3) {
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
            tab.acts.add(new Act(fx, y, bw, ROW_H, () -> {
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
        tab.acts.add(new Act(ax, y, aw, ROW_H, () -> {
            if (tab.suggestMode == 0) {
                tab.setsPanel.startSets(true);
            } else {
                startSuggestions(true);
            }
        }, null, List.of(L.c("wynnlabs.craft.suggest_refresh_tip"))));
        int top = y + ROW_H + 6;
        if (tab.suggestMode == 0) {
            tab.setsPanel.drawSets(g, x, top, w, y + h - top, mx, my);
            return;
        }

        List<Suggestions.Suggestion> list = new ArrayList<>();
        for (Suggestions.Suggestion s : tab.suggestions) {
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
        int cols = w >= CraftingTab.TWO_COLUMNS_MIN ? 2 : 1;
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
                tab.acts.add(new Act(px, by, bw, ROW_H, () -> suggestPage = target, null, null));
            }
            px -= 2;
        }
        tab.pageCount = pages;
    }

    private void suggestCard(GuiGraphics g, Suggestions.Suggestion s, int x, int y, int w, int h, int mx, int my) {
        CraftData.Recipe r = s.recipe();
        CraftSearch.Layout l = s.layout();
        Kit.card(g, x, y, w, h, s.fromStock() ? Theme.GOOD : 0, in(mx, my, x, y, w, h));
        int ix = x + 6;
        // line 1: icon, name, the two marks
        Art.icon(g, typeIcon(r.type()), ix, y + 4, 8, Theme.paperRgb(Theme.INK_2));
        String name = L.t("wynnlabs.craft.suggest_name", L.t("wynnlabs.craft.goalset." + s.goal().key()), typeName(r.type()));
        List<CraftChecks.Gap> gaps = r.consumable() ? List.of() : CraftChecks.gaps(l.result().requirements(), tab.points);
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
            String t = tab.statName(id) + " ";
            text(g, t, vx, y + 26, Theme.INK_2);
            vx += width(t);
            String val = range(v, tab.unit(id));
            text(g, val, vx, y + 26, v.max() < 0 ? Theme.BAD : Theme.GOOD);
            vx += width(val) + 8;
            shownIds++;
        }
        // line 4: cost, and Open / Copy
        String cost = l.bought().isEmpty() ? L.t("wynnlabs.craft.cost_none")
                : costLine(tab.liveCost(l.bought()));
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
            tip.add(Component.literal(in.empty() ? "—" : in.name() + (tab.owned(in.name()) > 0 ? " ×" + tab.owned(in.name()) : ""))
                    .withStyle(in.empty() ? ChatFormatting.GRAY : ChatFormatting.WHITE));
        }
        tab.acts.add(new Act(oxp, by, ow, 11, () -> openSuggestion(s), null, List.of(L.c("wynnlabs.craft.suggest_open_tip"))));
        tab.acts.add(new Act(cxp, by, cw, 11, () -> copySuggestion(s), null, List.of(L.c("wynnlabs.craft.tip.copy"))));
        tab.acts.add(new Act(x, y, w, h, null, null, tip));
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
        tab.flash.accept(L.t("wynnlabs.craft.code_copied"));
    }

    private void openSuggestion(Suggestions.Suggestion s) {
        tab.type = s.recipe().type();
        tab.minLevel = s.recipe().minLevel();
        tab.tier1 = s.tier1();
        tab.tier2 = s.tier2();
        tab.speed = Craft.Speed.NORMAL;
        for (int i = 0; i < Craft.SLOTS; i++) {
            Ingredient in = s.layout().slots().get(i);
            tab.slots[i] = in.empty() ? null : in.name();
        }
        tab.weights.clear();
        tab.weights.putAll(s.goal().goal().weights());
        tab.view = 0;
        tab.scroll = 0;
        tab.flash.accept(L.t("wynnlabs.craft.applied"));
    }
}
