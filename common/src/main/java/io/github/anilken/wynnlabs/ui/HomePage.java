package io.github.anilken.wynnlabs.ui;

import com.google.gson.JsonObject;

import io.github.anilken.wynnlabs.advice.Advice;
import io.github.anilken.wynnlabs.advice.AutoAdvice;
import io.github.anilken.wynnlabs.advice.Cautions;
import io.github.anilken.wynnlabs.build.Around;
import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.build.Locks;
import io.github.anilken.wynnlabs.build.Plans;
import io.github.anilken.wynnlabs.build.Setup;
import io.github.anilken.wynnlabs.build.TreePlanner;
import io.github.anilken.wynnlabs.build.roadmap.PanelOptions;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.game.ItemJson;
import io.github.anilken.wynnlabs.game.Wyn;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.market.Shopping;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.Slot;
import io.github.anilken.wynnlabs.quests.QuestBook;
import io.github.anilken.wynnlabs.quests.Waypoints;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Home tab: the character HUD, the level pills, a slot's comparison panel and the goal list, around
 * the stand and tiles {@link HomeTab} draws.
 */
public final class HomePage extends MenuTab {

    HomePage(RoadmapScreen menu) {
        super(menu);
    }

    // ---- the tab ----

    @Override
    void beginFrame() {
        levelRows.clear();
        slotHits.clear();
        cardHits.clear();
        backRect = null;
        filterRects = null;
        listRect = null;
    }

    /** The build window covers the home: its slots and level pills take no click. */
    void underWindow() {
        slotHits.clear();
        levelRows.clear();
    }

    @Override
    void draw(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float pt) {
        if (detail != null) {
            drawDetail(g, x, y, w, h, mx, my);
        } else {
            drawHome(g, x, y, w, h, mx, my);
        }
    }

    /** A click while the goal list is open: it closes, and a row picks that goal. True when the click is used up. */
    boolean goalClicked(double ex, double ey) {
        if (!goalOpen) {
            return false;
        }
        goalOpen = false;
        for (int[] r : goalRows) {
            if (in(ex, ey, r)) {
                String goal = Setup.GOALS.get(r[4]);
                if (!goal.equals(Setup.goal())) {
                    Setup.setGoal(goal);
                    menu.flash(L.t("wynnlabs.home.goal_set", Setup.goalName(goal)));
                    menu.rebuildMap();                 // rebuilt in the background, picked up when ready
                }
                return true;
            }
        }
        return goalAnchor != null && in(ex, ey, goalAnchor);    // the button again: closed
    }

    @Override
    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double ex = event.x();
        double ey = event.y();
        if (detail != null) {
            if (backRect != null && in(ex, ey, backRect)) {
                detail = null;
                return true;
            }
            for (int i = 0; filterRects != null && i < filterRects.length; i++) {
                if (in(ex, ey, filterRects[i])) {
                    detailMarket = i == 1;
                    detailScroll = 0;
                    return true;
                }
            }
            for (Object[] c : cardHits) {
                if (listRect != null && in(ex, ey, listRect) && in(ex, ey, (int[]) c[0])) {
                    String n = ((ItemData) c[1]).name();
                    Minecraft.getInstance().keyboardHandler.setClipboard(n);
                    menu.flash(L.t("wynnlabs.screen.copied", n));
                    return true;
                }
            }
            return false;
        }
        for (Object[] sh : slotHits) {
            if (in(ex, ey, (int[]) sh[0])) {
                detail = (Roadmap.Gear) sh[1];
                detailLabel = (String) sh[2];
                detailWorn = (ItemStack) sh[3];
                detailScroll = 0;
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
                int pos = shown.indexOf(menu.selected);
                menu.selected = r[4] == -1 ? shown.get(Math.max(0, Math.min(pos, levelOff) - 1))
                        : r[4] == -2 ? shown.get(Math.min(shown.size() - 1, Math.max(pos, levelOff + levelWindow - 1) + 1))
                        : r[4];
                return true;
            }
        }
        return false;
    }

    /** The comparison panel keeps its clicks; the home itself passes the rest on. */
    @Override
    boolean sharedClicks() {
        return detail == null;
    }

    @Override
    void mouseScrolled(double x, double y, double v) {
        if (detail != null) {
            // the comparison panel's cards scroll; the milestone stays
            detailScroll = Math.max(0, Math.min(detailScrollMax, detailScroll - (int) Math.signum(v) * CARD_H / 2));
            return;
        }
        if (menu.steps.isEmpty()) {
            return;
        }
        planPills.pickLevel();
        // the wheel walks the tabs: şimdi, then the shown milestones
        List<Integer> order = new ArrayList<>();
        order.add(0);
        order.addAll(shownSteps());
        int pos = Math.max(0, order.indexOf(menu.selected));
        menu.selected = order.get(Math.max(0, Math.min(order.size() - 1, pos - (int) Math.signum(v))));
    }

    /** A home {@code #q} action (the build line, the plan, the goal, the road map, powders, archetype, a place to mark). */
    void action(String c, int button) {
        if (c.startsWith("#qadvice:")) {
            Rules.setAdviceHere(true);
            menu.rebuildMap();
        } else if (c.startsWith("#qpick:")) {
            if (button == 1) {
                // right-click: the plan removed, as its pill's ✕ does (Undo on the build line);
                // with no plan, the chosen items cleared
                if (!removePlan()) {
                    Locks.clear();
                    Around.forget();
                    menu.flash(L.t("wynnlabs.homev.pick_cleared"));
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
                menu.flash(L.t(was ? "wynnlabs.homev.plan_updated" : "wynnlabs.homev.plan_saved_flash", kept.name()));
            }
        } else if (c.startsWith("#qplanedit:")) {
            BuildWindow.INSTANCE.editPlan(Plans.current());
        } else if (c.startsWith("#qplanundo:")) {
            Plans.undoRemove(System.currentTimeMillis());
        } else if (c.startsWith("#qsaved:")) {
            BuildWindow.INSTANCE.openSaved();
        } else if (c.startsWith("#qhint:")) {
            if (button == 1) {
                Plans.HINT.dismiss();
            } else {
                BuildWindow.INSTANCE.openResults(Around.checkResult());
            }
        } else if (c.startsWith("#qgoal:")) {
            goalOpen = true;                    // the list opens; a row picks the goal
        } else if (c.startsWith("#qrefresh:") || c.startsWith("#qbuild:")) {
            Roadmap.forceRefresh();
            menu.refreshAsked = true;
        } else if (c.startsWith("#qcancelbuild:")) {
            Roadmap.cancelBuild();
            menu.refreshAsked = false;
        } else if (c.startsWith("#qxp:")) {
            boolean on = !Setup.countsXp();
            Setup.setCountsXp(on);
            menu.flash(L.t(on ? "wynnlabs.home.count_xp_on" : "wynnlabs.home.count_xp_off"));
            menu.rebuildMap();                     // rebuilt in the background, picked up when ready
        } else if (c.startsWith("#qpowder:")) {
            boolean on = Rules.sub("model", "assumePowders", 1) > 0;
            JsonObject pm = new JsonObject();
            pm.addProperty("assumePowders", on ? 0 : 1);
            JsonObject pc = new JsonObject();
            pc.add("model", pm);
            Rules.update(pc);
            menu.flash(L.t(on ? "wynnlabs.home.powder_off" : "wynnlabs.home.powder_on"));
            menu.rebuildMap();                     // rebuilt in the background, picked up when ready
        } else if (c.startsWith("#qarch:")) {
            TreePlanner.cycle();
            String a = TreePlanner.chosen();
            menu.flash(L.t("wynnlabs.home.archetype_set", a.isEmpty() ? L.t("wynnlabs.settings.archetype_off") : a));
            menu.rebuildMap();                     // rebuilt in the background, picked up when ready
        } else if (c.startsWith("#qnear:")) {
            String[] f = c.substring(7).split(" ", 4);
            boolean ok = Waypoints.go(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), f[3]);
            menu.flash(ok ? L.t("wynnlabs.home.marked", f[3]) : L.t("wynnlabs.home.mark_failed"));
        }
    }

    // ---- drawing ----

    private record Box(String label, Slot type, int ring) {}

    /** Box labels are language keys; show with {@code L.t(box.label())}. */
    private static final Box[] LEFT = {new Box("wynnlabs.screen.slot_helmet", Slot.HELMET, 0),
        new Box("wynnlabs.screen.slot_chestplate", Slot.CHESTPLATE, 0),
        new Box("wynnlabs.screen.slot_leggings", Slot.LEGGINGS, 0), new Box("wynnlabs.screen.slot_boots", Slot.BOOTS, 0),
        new Box("wynnlabs.screen.slot_weapon", null, 0)};
    private static final Box[] RIGHT = {new Box("wynnlabs.screen.slot_ring", Slot.RING, 0),
        new Box("wynnlabs.screen.slot_ring", Slot.RING, 1),
        new Box("wynnlabs.screen.slot_bracelet", Slot.BRACELET, 0), new Box("wynnlabs.screen.slot_necklace", Slot.NECKLACE, 0)};
    private static final int CARD_H = 74;
    /** First milestone shown after "now" in the level buttons, and how many fit. */
    private int levelOff = 0;            // the first shown milestone tab, as a position in shownSteps()
    private static final int LEVEL_WINDOW = 5;
    private final List<int[]> levelRows = new ArrayList<>();   // x, y, w, h, step index
    private final List<Object[]> slotHits = new ArrayList<>();  // {int[] rect, Roadmap.Gear, label, worn}
    private final List<Object[]> cardHits = new ArrayList<>();  // {int[] rect, ItemData}
    private int[] backRect;
    /** The slot whose options the comparison panel shows (null: the home itself). */
    Roadmap.Gear detail;
    private String detailLabel;
    private ItemStack detailWorn = ItemStack.EMPTY;
    // the panel's filter (only what the Trade Market sells), kept for the session; its list's
    // scroll in pixels; this frame's filter buttons {all, market} and list area (no initializers:
    // fields a hot swap adds keep no value)
    private boolean detailMarket;
    private int detailScroll;
    private int detailScrollMax;
    private int[][] filterRects;
    private int[] listRect;

    /** The goal list under the goal button, open after a click on it. */
    private boolean goalOpen;
    private int[] goalAnchor;
    private final List<int[]> goalRows = new ArrayList<>();     // x, y, w, h, index in Setup.GOALS

    /** One row per goal: its name and what it weighs, the current one marked, the hovered one lit (when the list is open). */
    void drawGoalList(GuiGraphics g, int mx, int my) {
        if (!goalOpen || goalAnchor == null) {
            return;
        }
        goalRows.clear();
        int rowH = 20;
        int w = 0;
        for (String goal : Setup.GOALS) {
            w = Math.max(w, Math.max(font().width(Setup.goalName(goal)) + 10, font().width(L.t(goalDesc(goal)))));
        }
        w += 16;
        // its right edge on the button's, and never past the panel's left side
        int x = Math.max((menu.width - menu.panelW()) / 2 + menu.railW() + 6, goalAnchor[0] + goalAnchor[2] - w);
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

    /** Upgrades reached by the selected milestone, by slot type name ("RING", "RELIK", ...). */
    private Map<String, Roadmap.Gear> upgrades() {
        Map<String, Roadmap.Gear> out = new LinkedHashMap<>();
        for (int i = 0; i <= menu.selected && i < menu.steps.size(); i++) {
            for (Roadmap.Gear g : menu.steps.get(i).gear()) {
                out.put(g.slot(), g);
            }
        }
        if (!menu.steps.isEmpty()) {
            Roadmap.Step at = menu.steps.get(Math.min(menu.selected, menu.steps.size() - 1));
            Roadmap.keepWorn(out, at);
        }
        // what the best build you own puts on from the bag, where the road map has no pick: the
        // plan follows a piece taken off within seconds, the map only when built again
        Roadmap.bagRows().forEach(out::putIfAbsent);
        return out;
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
        int now = menu.steps.isEmpty() ? 0 : wearAt(menu.steps.get(0));
        for (int i = 1; i < menu.steps.size(); i++) {
            int at = wearAt(menu.steps.get(i));
            // wearable already: the now tab has it; a later set as early: that one is better
            if (at <= now || i + 1 < menu.steps.size() && wearAt(menu.steps.get(i + 1)) <= at) {
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
            if (u == null && type != null) {
                u = up.get("POWDER:" + Build.slotOf(type, box.ring()));     // no new piece there: its powders
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
            int bw = font().width(on) + 12;
            pickChip(g, x + (w - bw) / 2, y + h / 2 + 34, on, false, mx, my, "#qadvice:");
            return;
        }
        if (menu.error != null) {
            ink(g, Component.literal(menu.error).withStyle(ChatFormatting.RED), x, y + 4, 0xFFFFFFFF);
            return;
        }
        // with no road map yet the gear panel offers to calculate one: nothing is built on its own
        drawHomeHeader(g, x, y, w);
        // the visual home (HomeTab): the gear on a stand between two columns of tiles
        int by = y + HUD_H + 13;                            // 4px under the experience bar and its "%100 XP"
        // 4px short of the page's foot: the parchment's corner curls sit there
        HomeTab.INSTANCE.draw(g, homeIn(), homeHost, x, by, w, y + h - by - 4, menu.layout.compact(), menu.layout.large(), mx, my);
    }

    /** This frame's slots for the home's stand: every gear slot with what you wear and the selected level's upgrade. */
    private HomeTab.In homeIn() {
        List<HomeTab.SlotView> slots = new ArrayList<>();
        List<UpRow> rows = upRows();
        for (int i = 0; i < rows.size(); i++) {
            UpRow r = rows.get(i);
            Roadmap.Gear u = r.gear();
            slots.add(new HomeTab.SlotView(i, boxLabel(r.box()), r.type(), r.worn(), u, u == null ? ItemStack.EMPTY : menu.icon(u.info()),
                    u == null ? Double.NaN : rowGain(u), u == null ? null : context(gearTip(u))));
        }
        // the build score now, and with the selected milestone's whole set (NaN when it has none)
        Build.Plan plan = Build.plan();
        double now = plan == null || plan.current() == null ? Double.NaN : plan.current().objective();
        Roadmap.Step s = menu.steps.isEmpty() ? null : menu.steps.get(Math.max(0, Math.min(menu.selected, menu.steps.size() - 1)));
        double after = s == null || s.set() == null ? Double.NaN : s.set().result().objective();
        Plans.Build current = Plans.current();
        int chosen = Locks.names().size();
        int saved = Plans.saved().size();
        boolean searching = Around.running();
        if (planPills.planSelected(current)) {
            return planIn(current, rows, now, after, chosen, saved, searching);
        }
        return new HomeTab.In(slots, now, after, menu.quests.isEmpty() ? null : menu.quests.get(0), places(), null, chosen,
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
        MenuHit hit;
        if (worn != null && !worn.isEmpty()) {
            hit = new MenuHit(x, y, w, h, worn, ex.isEmpty() ? null : ex, null, null, ld);
        } else {
            ItemStack st = info == null ? ItemStack.EMPTY : menu.icon(info);
            if (menu.iconInfo.containsKey(st)) {
                hit = new MenuHit(x, y, w, h, st, ex.isEmpty() ? null : ex, null, null, ld);
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
                hit = new MenuHit(x, y, w, h, null, tip, null);
            }
        }
        if (front) {
            menu.hits.add(0, hit);
        } else {
            menu.hits.add(hit);
        }
    }

    /** A pick's story without its first line (the name): the item's own tooltip has the name. */
    private static List<Component> context(List<Component> tip) {
        return tip.size() <= 1 ? List.of() : List.copyOf(tip.subList(1, tip.size()));
    }

    /** The places to level now, from the road map (none without one). */
    private List<String> places() {
        return menu.steps.isEmpty() ? List.of() : menu.steps.get(0).places();
    }

    /** The road map's state for the gear panel: none yet, being built (and how far), out of date. */
    private HomeTab.Road road() {
        HomeTab.Road preview = HomeTab.previewRoad;
        return preview != null ? preview : new HomeTab.Road(menu.steps.isEmpty(), Roadmap.isBuilding(), Roadmap.progress(), Roadmap.stale());
    }

    /**
     * Removes the plan: the panel shows the suggestions with "Now" selected; the chosen items,
     * the strip and the saved builds stay; the build line offers Undo for a few seconds.
     */
    private boolean removePlan() {
        if (!Plans.remove(System.currentTimeMillis())) {
            return false;
        }
        menu.selected = 0;
        return true;
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
            slots.add(new HomeTab.SlotView(i, boxLabel(r.box()), r.type(), r.worn(), null, menu.icon(info), Double.NaN, tip, mark, info, lead));
        }
        int better = Plans.HINT.showing();
        HomeTab.PlanLine line = new HomeTab.PlanLine(plan.name(), worn, plan.items().size(), left, better,
                plan.level() > GameState.current().level() ? plan.level() : 0);
        return new HomeTab.In(slots, now, after, menu.quests.isEmpty() ? null : menu.quests.get(0), places(), line, chosen,
                saved, searching, road());
    }

    /** The build window's way back into the screen. */
    final BuildWindow.Host windowHost = new BuildWindow.Host() {
        @Override
        public void tip(int x, int y, int w, int h, List<Component> tip) {
            menu.hits.add(new MenuHit(x, y, w, h, null, tip, null));
        }

        @Override
        public void item(int x, int y, int w, int h, ItemData info, ItemStack stack, List<Component> extra) {
            // first in the list: an icon lies over its card, and the first hit under the mouse shows
            itemHit(true, x, y, w, h, info, ItemStack.EMPTY, extra);
        }

        @Override
        public ItemStack icon(ItemData info) {
            return menu.icon(info);
        }

        @Override
        public void flash(String text) {
            menu.flash(text);
        }

        @Override
        public void applied() {
            planPills.pickPlan();
            menu.flash(L.t("wynnlabs.homev.plan_applied"));
        }
    };

    /** The home's way back into the screen: its click and hover areas join this frame's hits. */
    private final HomeTab.Host homeHost = new HomeTab.Host() {
        @Override
        public void area(int x, int y, int w, int h, ItemStack stack, List<Component> tip, String action) {
            menu.hits.add(new MenuHit(x, y, w, h, stack, tip, action));
        }

        @Override
        public void quest(int x, int y, int w, int h, List<Component> tip, QuestBook.Entry q) {
            menu.hits.add(new MenuHit(x, y, w, h, null, tip, null, q));
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
            return HomePage.this.levelPills(g, y, right, room);
        }

        @Override
        public int refresh(GuiGraphics g, int x, int y, int mx, int my, String staleTip) {
            if (Roadmap.isBuilding()) {
                if (menu.steps.isEmpty()) {
                    return 0;                       // the first map: its progress fills the panel instead
                }
                // a new map over the last one: a small bar and Cancel (the last map stays)
                Kit.tealBar(g, x, y + 5, 24, 4, Roadmap.progress(), false);
                menu.hits.add(new MenuHit(x, y, 24, 14, null, List.of(L.c("wynnlabs.home.refreshing").withStyle(ChatFormatting.GRAY)), null));
                boolean ch = mx >= x + 27 && mx < x + 41 && my >= y && my < y + 14;
                Kit.button(g, x + 27, y, 14, 14, ch ? Kit.HOVER : Kit.REST);
                Art.icon(g, "x", x + 30, y + 3, 8, Kit.buttonText(ch ? Kit.HOVER : Kit.REST));
                menu.hits.add(new MenuHit(x + 27, y, 14, 14, null, List.of(L.c("wynnlabs.pick.cancel").withStyle(ChatFormatting.WHITE),
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
            menu.hits.add(new MenuHit(x, y, 14, 14, null, tip, "#qrefresh:"));
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
        g.drawString(font(), name, tx, y + 4, Theme.GOLD, true);
        int nx = tx + font().width(name) + 8;
        String archLabel = arch.isEmpty() ? L.t("wynnlabs.settings.archetype_off") : arch;
        Art.icon(g, "sparkles", nx, y + 7, 8, 0xFFAFA9EC);
        g.drawString(font(), archLabel, nx + 10, y + 7, 0xFFAFA9EC, true);
        menu.hits.add(new MenuHit(nx, y + 5, font().width(archLabel) + 10, 12, null, List.of(
                L.c("wynnlabs.home.archetype_tip", arch.isEmpty() ? L.t("wynnlabs.settings.archetype_off") : arch)
                        .withStyle(ChatFormatting.LIGHT_PURPLE),
                L.c("wynnlabs.home.archetype_click").withStyle(ChatFormatting.DARK_GRAY)), "#qarch:"));
        nx += font().width(archLabel) + 18;
        g.drawString(font(), "Lv " + level, nx, y + 7, Theme.CREAM_DIM, true);
        // the right side: the goal button with the XP switch beside it, the powder button, the
        // nearest bank and Trade Market below
        int chipW = 112;
        int cx = x + w - chipW - 6;
        // the XP switch at the column's right edge, its short label before it; the goal button
        // takes the rest of the row
        boolean xpOn = Setup.countsXp();
        String xpLabel = L.t("wynnlabs.home.count_xp_short");
        int sx = cx + chipW - 19;
        int lx = sx - 3 - font().width(xpLabel);
        int goalW = lx - 4 - cx;
        int gs = goalOpen ? Kit.ON : Kit.REST;
        Kit.button(g, cx, y + 7, goalW, 13, gs);
        woodIconText(g, "target", fit(Setup.goalName(Setup.goal()), goalW - 28), cx + 4, y + 10, gs);
        Art.icon(g, "chevron-down", cx + goalW - 12, y + 10, 8, goalOpen ? Theme.INK_2 : Theme.CREAM_DIM);
        goalAnchor = new int[] {cx, y + 7, goalW, 13};
        menu.hits.add(new MenuHit(cx, y + 8, goalW, 12, null, goalOpen ? List.of() : List.of(
                L.c("wynnlabs.home.goal_tip", Setup.goalName(Setup.goal())).withStyle(ChatFormatting.LIGHT_PURPLE),
                L.c("wynnlabs.home.goal_click").withStyle(ChatFormatting.DARK_GRAY)), "#qgoal:"));
        g.drawString(font(), xpLabel, lx, y + 10, xpOn ? Theme.CREAM : Theme.CREAM_DIM, true);
        Kit.toggle(g, sx, y + 8, xpOn);
        menu.hits.add(new MenuHit(lx, y + 8, sx + 19 - lx, 12, null, List.of(
                L.c("wynnlabs.home.count_xp", L.t(xpOn ? "wynnlabs.rules.on" : "wynnlabs.rules.off")).withStyle(ChatFormatting.LIGHT_PURPLE),
                L.c(xpOn ? "wynnlabs.home.count_xp_on_tip" : "wynnlabs.home.count_xp_off_tip").withStyle(ChatFormatting.GRAY),
                L.c("wynnlabs.home.powder_click").withStyle(ChatFormatting.DARK_GRAY)), "#qxp:"));
        boolean powdered = Rules.sub("model", "assumePowders", 1) > 0;
        String powLabel = powdered ? L.t("wynnlabs.home.powder_on") : L.t("wynnlabs.home.powder_off");
        Kit.button(g, cx, y + 23, chipW, 13, powdered ? Kit.ON : Kit.REST);
        woodIconText(g, "diamond", fit(powLabel, chipW - 18), cx + 4, y + 26, powdered ? Kit.ON : Kit.REST);
        menu.hits.add(new MenuHit(cx, y + 24, chipW, 12, null, List.of(
                L.c(powdered ? "wynnlabs.home.powder_on_tip" : "wynnlabs.home.powder_off_tip").withStyle(ChatFormatting.LIGHT_PURPLE),
                L.c("wynnlabs.home.powder_click").withStyle(ChatFormatting.DARK_GRAY)), "#qpowder:"));
        drawNearest(g, cx, y + 40, chipW);
        // the gauge: the build score against the next milestone's
        Build.Plan plan = Build.plan();
        Damage.Result now = plan == null ? null : plan.current();
        List<Integer> shown = shownSteps();
        Damage.Result target = null;
        int targetLevel = 0;
        if (!shown.isEmpty() && menu.steps.get(shown.get(0)).set() != null) {
            target = menu.steps.get(shown.get(0)).set().result();
            targetLevel = menu.steps.get(shown.get(0)).level();
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
            g.drawString(font(), bold, gcx - font().width(bold) / 2, gcy - 17, Theme.GOLD, true);
            String lbl = L.t("wynnlabs.home.stat_build");
            g.drawString(font(), lbl, gcx - font().width(lbl) / 2, gcy - 7, Theme.CREAM_DIM, false);
            menu.hits.add(new MenuHit(gx, gcy - 28, 58, 30, null, vsTip(L.t("wynnlabs.home.stat_build"), score, frac, targetLevel), null));
        }
        // the bars: effective health, mana (casts in a row), spell damage per cast
        int bx = tx;
        // a large page's bars stop at 300px: a longer track says no more
        int bw = menu.layout.large() ? Math.min(300, gx - 10 - bx) : gx - 10 - bx;
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
                g.drawString(font(), fit((String) b[0], bw - 16), bx + 13, by + 1, 0xFFFFFFFF, true);
                // the milestone's value at the track's end; white with a shadow where the fill reaches it
                if (ref > 0) {
                    String rv = String.valueOf(Math.round(ref));
                    int rx = bx + bw - font().width(rv) - 3;
                    if (rx > bx + 13 + font().width((String) b[0]) + 6) {
                        boolean over = bx + fw >= rx - 2;
                        g.drawString(font(), rv, rx, by + 1, over ? 0xFFFFFFFF : Theme.CREAM_DIM, over);
                    }
                }
                menu.hits.add(new MenuHit(bx, by, bw, 10, null, vsTip((String) b[1], (String) b[0], frac, targetLevel), null));
                by += 11;
            }
        }
        // experience: the teal bar with its brass rim and notches under the band, as before
        int ey = y + HUD_H + 5;
        String pct = RoadmapScreen.pct(xp) + " XP";
        // the % at the bar's end, on its line: the home's page starts right under it
        int xw = w - font().width(pct) - 6;
        Kit.tealBar(g, x, ey, xw, 4, Math.max(0, Math.min(100, xp)) / 100.0, true);
        Kit.notches(g, x, ey, xw, 4);
        // level with the bar, 1px in from the page's edge
        ink(g, pct, x + w - font().width(pct) - 1, ey - 2, FAINT);
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
            g.drawString(font(), fit(label, each - 16), px + 14, y + 2, Theme.NEAR_TEXT, true);
            String what = (k[1].equals("BANKS") ? L.t("wynnlabs.home.bank") : "Trade Market") + " · " + f[3];
            menu.hits.add(new MenuHit(px, y, each, 12, null, List.of(
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
        int pos = shown.indexOf(menu.selected);
        if (menu.selected > 0 && pos < 0 && !shown.isEmpty()) {
            // a hidden milestone: the next shown one has a set as early and better
            int to = shown.get(shown.size() - 1);
            for (int i : shown) {
                if (i >= menu.selected) {
                    to = i;
                    break;
                }
            }
            menu.selected = to;
            pos = shown.indexOf(menu.selected);
        }
        levelWindow = LEVEL_WINDOW;
        // with a plan, its pill first: "Plan · Now · Lv 68 · 80 …"
        Plans.Build plan = Plans.current();
        boolean planOn = planPills.planSelected(plan);
        String planText = L.t("wynnlabs.homev.plan_pill");
        int planW = plan == null ? 0 : font().width(planText) + 12 + PLAN_X;
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
            int tw = i < 0 ? 16 : font().width(t) + 12;
            bx -= tw;
            boolean on = !planOn && i == menu.selected;
            Kit.ledger(g, bx, y - 3, tw, 14, on);
            if (i < 0) {
                Art.icon(g, i == -1 ? "chevron-left" : "chevron-right", bx + 4, y, 8, Kit.buttonText(Kit.REST));
            } else {
                onWood(g, t, bx + 6, y, on ? Kit.ON : Kit.REST);
            }
            levelRows.add(new int[] {bx, y - 3, tw, 14, i});
            if (i > 0) {
                menu.hits.add(new MenuHit(bx, y - 3, tw, 14, null, List.of(
                        L.c("wynnlabs.home.levels_tip", wearAt(menu.steps.get(i)), menu.steps.get(i).level())
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
                g.drawString(font(), planText, bx + 6, y, Theme.CREAM, false);
            } else {
                onWood(g, planText, bx + 6, y, Kit.REST);
            }
            levelRows.add(new int[] {bx, y - 3, planW - PLAN_X, 14, PLAN_PILL});
            menu.hits.add(new MenuHit(bx, y - 3, planW - PLAN_X, 14, null, List.of(Component.literal(plan.name()).withStyle(ChatFormatting.LIGHT_PURPLE),
                    L.c("wynnlabs.homev.plan_pill_tip").withStyle(ChatFormatting.GRAY)), null));
            // its ✕ at the right end: the plan removed (Undo on the build line for a few seconds)
            int xx = bx + planW - PLAN_X - 1;
            boolean xh = menu.frameMx >= xx && menu.frameMx < xx + 12 && menu.frameMy >= y - 2 && menu.frameMy < y + 10;
            Kit.button(g, xx, y - 2, 12, 12, xh ? Kit.HOVER : Kit.REST);
            Art.icon(g, "x", xx + 2, y, 8, Kit.buttonText(xh ? Kit.HOVER : Kit.REST));
            List<Component> xtip = new ArrayList<>();
            xtip.add(L.c("wynnlabs.homev.plan_remove").withStyle(ChatFormatting.WHITE));
            xtip.add(L.c("wynnlabs.homev.plan_remove_tip").withStyle(ChatFormatting.GRAY));
            if (!Plans.isSaved(plan)) {
                xtip.add(L.c("wynnlabs.homev.plan_unsaved").withStyle(ChatFormatting.GOLD));
            }
            menu.hits.add(new MenuHit(xx, y - 2, 12, 12, null, xtip, "#qplanremove:"));
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
        return i < 0 ? "" : i == 0 ? L.t("wynnlabs.home.now_short") : (i == firstShown ? "Lv " : "") + wearAt(menu.steps.get(i));
    }

    private int pillsWidth(List<int[]> order, List<Integer> shown) {
        int firstShown = levelOff < shown.size() ? shown.get(levelOff) : -9;
        int n = 0;
        for (int[] o : order) {
            n += (o[0] < 0 ? 16 : font().width(pillText(o[0], firstShown)) + 12) + 3;
        }
        return n - 3;
    }

    /** The full story of an upgrade, for its hover. */
    /**
     * The gain a row shows: what the selected milestone's build gains over what you wear now in
     * that slot (Roadmap.Step.vsWorn), or the step over the previous milestone's pick when what
     * you wear there is far below it (Roadmap.shownGain).
     */
    private double rowGain(Roadmap.Gear u) {
        return Roadmap.shownGain(menu.steps, menu.selected, u);
    }

    private List<Component> gearTip(Roadmap.Gear u) {
        List<Component> tip = new ArrayList<>();
        ChatFormatting tc = u.info().tier().colour();
        tip.add(Component.literal(u.info().name()).withStyle(tc).withStyle(UiFont::bold));
        // a powder row is a piece you have: its source line names the powders, and there is no other source
        boolean powder = Roadmap.isPowder(u);
        tip.add(powder ? Component.literal(u.source()).withStyle(ChatFormatting.AQUA)
                : L.c("wynnlabs.home.tip_from", u.source()).withStyle(ChatFormatting.DARK_AQUA));
        String also = Roadmap.fromBag(u) || powder ? null : Shopping.alsoFrom(u.info());   // the Trade Market first, the world source under it
        if (also != null && !also.equalsIgnoreCase(u.source())) {
            tip.add(Component.literal("   " + L.t("wynnlabs.homev.source_also", L.cap(also))).withStyle(ChatFormatting.DARK_GRAY));
        }
        Object[] pw = powder ? null : Damage.powderHint(u.info());
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
        boolean step = !Double.isNaN(vsWorn) && Roadmap.showsStep(menu.steps, menu.selected, u);
        tip.add(Double.isNaN(vsWorn)
                ? L.c(Roadmap.isTarget(u) ? "wynnlabs.home.tip_target" : "wynnlabs.home.tip_new").withStyle(ChatFormatting.AQUA)
                : step ? (vsWorn < 0 ? L.c("wynnlabs.home.tip_step_loss", Math.round(-vsWorn * 10) / 10.0).withStyle(ChatFormatting.RED)
                        : L.c("wynnlabs.home.tip_step", Math.round(vsWorn * 10) / 10.0).withStyle(ChatFormatting.GREEN))
                : vsWorn < 0 ? L.c("wynnlabs.home.tip_loss", Math.round(-vsWorn * 10) / 10.0).withStyle(ChatFormatting.RED)
                : L.c("wynnlabs.home.tip_gain", Math.round(vsWorn * 10) / 10.0).withStyle(ChatFormatting.GREEN));
        if (!step && !Double.isNaN(u.gain()) && Math.abs(vsWorn - u.gain()) >= 0.5) {
            // at a later milestone the pick also builds on the picks before it
            tip.add(L.c("wynnlabs.home.tip_step", Math.round(u.gain() * 10) / 10.0).withStyle(ChatFormatting.DARK_GRAY));
        }
        // why this pick: what changes against what you wear, biggest first (else the step's own reasons)
        List<String> why = menu.steps.isEmpty() ? null
                : menu.steps.get(Math.max(0, Math.min(menu.selected, menu.steps.size() - 1))).whyVsWorn().get(u.slot());
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

    private void drawDetail(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        List<Roadmap.Option> all = detail.options() == null ? List.of() : detail.options();
        Roadmap.Option pick = all.isEmpty() ? null : all.get(0);
        List<Roadmap.Option> opts = PanelOptions.shown(all, detailMarket, Wyn.level());
        String back = L.t("wynnlabs.home.back");
        int bw = font().width(back) + 24;
        String[] fl = {L.t("wynnlabs.home.filter_all"), L.t("wynnlabs.home.filter_market")};
        int fx = x + w - bw - 8 - (font().width(fl[0]) + 12) - (font().width(fl[1]) + 12) - 2;
        // the title, without "options, by gain" when the filter needs its room
        Component title = Component.literal(detailLabel).withStyle(ChatFormatting.LIGHT_PURPLE).withStyle(UiFont::bold);
        Component full = title.copy().append(L.c("wynnlabs.home.detail_sub").withStyle(ChatFormatting.DARK_GRAY));
        ink(g, font().width(full) + 8 <= fx - x ? full : title, x, y, 0xFFFFFFFF);
        boolean bh = mx >= x + w - bw && mx < x + w && my >= y - 2 && my < y + 11;
        Kit.button(g, x + w - bw, y - 2, bw, 13, bh ? Kit.HOVER : Kit.REST);
        woodIconText(g, "chevron-left", back, x + w - bw + 7, y, bh ? Kit.HOVER : Kit.REST);
        backRect = new int[] {x + w - bw, y - 2, bw, 13};
        // the filter, left of Back: every option, or only what the Trade Market sells
        filterRects = new int[2][];
        for (int i = 0; i < 2; i++) {
            int fw = font().width(fl[i]) + 12;
            boolean on = detailMarket == (i == 1);
            boolean fh = mx >= fx && mx < fx + fw && my >= y - 2 && my < y + 11;
            int state = on ? Kit.ON : fh ? Kit.HOVER : Kit.REST;
            Kit.button(g, fx, y - 2, fw, 13, state);
            onWood(g, fl[i], fx + 6, y + 1, state);
            filterRects[i] = new int[] {fx, y - 2, fw, 13};
            fx += fw + 2;
        }

        // what you wear now
        int ry = y + 14;
        Kit.card(g, x, ry, w, 20, 0);
        Kit.slot(g, x + 2, ry + 1, 18, 0);
        if (!detailWorn.isEmpty()) {
            g.renderItem(detailWorn, x + 3, ry + 2);
            menu.hits.add(new MenuHit(x + 3, ry + 2, 16, 16, detailWorn, null, null));
        }
        String wn = detailWorn.isEmpty() ? L.cap(L.t("wynnlabs.screen.empty")) : ItemJson.clean(detailWorn.getHoverName().getString());
        String nowLabel = L.t("wynnlabs.home.card_now");
        ink(g, nowLabel, x + 24, ry + 6, FAINT);
        ink(g, Component.literal(wn).withStyle(ChatFormatting.WHITE).withStyle(UiFont::bold),
                x + 24 + font().width(nowLabel) + 6, ry + 6, Theme.INK);
        if (pick != null) {
            Damage.Result f = pick.from();
            String now = L.t("wynnlabs.home.detail_now", Math.round(f.spellDps()), Math.round(f.ehp()), signed(f.speed()));
            ink(g, now, x + w - font().width(now) - 5, ry + 6, MUTED);
        }

        // the cards, best gain first, scrolled by the wheel when they are taller than the panel
        int top = ry + 25;
        int bottom = y + h - 2;
        int listH = opts.size() * CARD_H;
        detailScrollMax = Math.max(0, listH - (bottom - top));
        detailScroll = Math.min(detailScroll, detailScrollMax);
        boolean bar = detailScrollMax > 0;
        int cw = bar ? w - ScrollBar.W - 4 : w;
        listRect = new int[] {x, top, w, bottom - top};
        int hitsFrom = menu.hits.size();
        g.enableScissor(x, top, x + w, bottom);
        int cy = top - detailScroll;
        for (int i = 0; i < opts.size(); i++) {
            if (cy + CARD_H > top && cy < bottom) {
                drawCard(g, opts.get(i), i, opts.get(i) == pick, x, cy, cw, mx, my);
            }
            cy += CARD_H;
        }
        g.disableScissor();
        // an item's tooltip only where its card is in view
        menu.hits.subList(hitsFrom, menu.hits.size()).removeIf(hb -> hb.y() < top || hb.y() + hb.h() > bottom);
        if (bar) {
            int trackH = bottom - top - 8;
            int thumb = Math.max(ScrollBar.MIN_THUMB, trackH * (bottom - top) / listH);
            int ty = top + (trackH - thumb) * detailScroll / detailScrollMax;
            g.fill(x + w - ScrollBar.W + 2, top, x + w - 2, top + trackH, 0x30000000);
            Kit.button(g, x + w - ScrollBar.W, ty, ScrollBar.W, thumb, Kit.REST);
        }
        if (opts.size() <= (detailMarket ? 0 : 1)) {
            ink(g, L.c(detailMarket ? "wynnlabs.home.detail_none_market" : "wynnlabs.home.detail_none")
                    .withStyle(ChatFormatting.DARK_GRAY), x, cy + 2, 0xFFFFFFFF);
        }
    }

    /**
     * One option in a slot's comparison, as a card: a big slot on the left; the name, where it
     * is had and its tags; then only what changes (▲ gains, ▼ losses: old → new and the share),
     * two to a row, and one line naming what stays the same; on the right the build's total as
     * a large seal and a button that copies the name (for the Trade Market search).
     */
    private void drawCard(GuiGraphics g, Roadmap.Option o, int rank, boolean pick, int x, int y, int w, int mx, int my) {
        int h = CARD_H - 8;                     // 8px between cards for the shadow
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        card(g, x, y, w, h, hover, pick ? GOOD : 0);
        ItemStack icon = menu.icon(o.info());
        int sy = y + (h - 40) / 2;
        Kit.slot(g, x + 4, sy, 40, tierRing(o.info()));
        bigItem(g, icon, x + 8, sy + 4);
        menu.hits.add(new MenuHit(x + 8, sy + 4, 32, 32, icon, null, null));
        cardHits.add(new Object[] {new int[] {x, y, w, h}, o.info()});

        // right: BUILD, the total as a seal, the copy button
        int rw = 64;
        int rx = x + w - rw - 6;
        String build = L.t("wynnlabs.home.card_build");
        ink(g, build, rx + rw - font().width(build), y + 8, FAINT);
        String total = (o.gain() >= 0 ? "+" : "−") + RoadmapScreen.pct(Math.round(Math.abs(o.gain())));
        Kit.seal(g, rx + 4, y + 19, rw - 4, 18, o.gain() >= 0 ? (pick ? Theme.SEAL_3 : Theme.SEAL_2) : Theme.BAD);
        g.drawString(font(), total, rx + 4 + (rw - 4 - font().width(total)) / 2, y + 24, Theme.SEAL_TEXT, true);
        String copy = L.t("wynnlabs.home.card_copy");
        int cbw = font().width(copy) + 12;
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
        int cx = tx + font().width(name) + 8;
        for (String[] c : chips) {
            int bg = c[1].equals("r") ? TAG_RED : c[1].equals("a") ? TAG_AMBER
                    : c[1].equals("p") ? TAG_PURPLE : TAG_GREEN;
            int fg = c[1].equals("r") ? Theme.BAD : c[1].equals("a") ? Theme.WARN
                    : c[1].equals("p") ? Theme.TIER_MYTHIC : Theme.GOOD;
            int cw = font().width(c[0]) + 6;
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
            String v = (up ? "+" : "−") + RoadmapScreen.pct(Math.round(Math.abs(p[i])));
            ink(g, v, dx + colW - font().width(v) - 5, dy, up ? Theme.GOOD : Theme.BAD);
            int vx = dx + 76;
            ink(g, fit(sub[i], dx + colW - font().width(v) - 8 - vx), vx, dy, MUTED);
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

    private int chip(GuiGraphics g, int x, int y, String text, int bg, int fg) {
        // a tag: 11px tall, 4px each side; the text keeps the line's y, the tag is centred on it
        String t = font().plainSubstrByWidth(text, 220);
        int w = Kit.Ui.tagW(font(), t);
        g.fill(x, y - 2, x + w, y - 2 + Kit.Ui.TAG_H, bg);
        ink(g, t, x + Kit.Ui.TAG_PAD, y, fg);
        return w;
    }
}
