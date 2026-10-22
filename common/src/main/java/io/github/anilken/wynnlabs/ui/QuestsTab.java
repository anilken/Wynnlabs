package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.advice.Advice;
import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.core.DevHooks;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.game.Professions;
import io.github.anilken.wynnlabs.game.Wyn;
import io.github.anilken.wynnlabs.game.WynntilsItems;
import io.github.anilken.wynnlabs.quests.QuestAdvisor;
import io.github.anilken.wynnlabs.quests.QuestBook;
import io.github.anilken.wynnlabs.quests.QuestGuides;
import io.github.anilken.wynnlabs.quests.QuestTrail;
import io.github.anilken.wynnlabs.quests.QuestWiki;
import io.github.anilken.wynnlabs.quests.Waypoints;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The quests tab: the ranked quest list and a quest's steps, points and pins. */
final class QuestsTab extends MenuTab {

    QuestsTab(RoadmapScreen menu) {
        super(menu);
    }

    // ---- the tab ----

    @Override
    void draw(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float pt) {
        drawQuests(g, x, y, w, h, mx, my);
    }

    @Override
    int titleRight(GuiGraphics g, int right, int y, int room, int mx, int my) {
        return questTitleRight(g, right, y, room, mx, my);
    }

    @Override
    void mouseScrolled(double x, double y, double v) {
        if (questDetailTab == 3 && questDetailRect != null && in(x, y, questDetailRect)) {
            pointScroll = Math.max(0, pointScroll - 3 * (int) Math.signum(v));
        } else if (questDetailTab == 0 && questDetailRect != null && in(x, y, questDetailRect)) {
            stepScroll = Math.max(0, stepScroll - (int) Math.signum(v));
        } else {
            questScroll = Math.max(0, questScroll - (int) Math.signum(v));
        }
    }

    /**
     * A quest {@code #q} action, from any tab (the list's filters and rows, the detail's tabs,
     * tracking, the rescan, pins, a marked plan); false when {@code c} is not one of them.
     */
    boolean action(String c) {
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
                menu.onClose();
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
            menu.flash(L.n("wynnlabs.quests.pins_cleared", n));
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
            menu.flash(n > 0 ? L.n("wynnlabs.quests.pins_added", n) : L.t("wynnlabs.quests.no_coords"));
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
            menu.flash(n > 0 ? L.n("wynnlabs.quests.pins_added", n) : L.t("wynnlabs.quests.no_coords"));
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
            menu.flash(L.n("wynnlabs.quests.plan_marked", n));
        } else {
            return false;
        }
        return true;
    }

    // ---- drawing ----

    private String questFilter = "RECOMMENDED";
    private int questScroll;
    /** The quest the detail panel shows (null: none chosen). */
    String questSelected;
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
        int bw = font().width(label) + 22;
        int bx = right - bw;
        int by = y - 2;
        boolean hover = mx >= bx && mx < bx + bw && my >= by && my < by + 13;
        int state = hover && ready ? Kit.HOVER : Kit.REST;
        Kit.button(g, bx, by, bw, 13, state);
        woodIconText(g, "refresh", label, bx + 6, by + 3, state);
        menu.hits.add(new MenuHit(bx, by, bw, 13, null, rescanTip(), "#qrescan:"));
        int used = bw;
        long at = QuestBook.readAt();
        if (at > 0) {
            String read = System.currentTimeMillis() - at < 60_000 ? L.t("wynnlabs.quests.read_now")
                    : L.t("wynnlabs.quests.read_ago", ago(at));
            int rw = font().width(read);
            if (used + 6 + rw <= room) {
                Kit.text(g, font(), read, bx - 6 - rw, y, Theme.INK_3);
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
            emptyState(g, x, y, w, h, menu.railIcon(2), L.t("wynnlabs.quests.empty_title"),
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
        } else if (menu.layout.compact()) {
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
        cy += tracked == null && menu.layout.compact() ? 23 : 63;
        // the plan line (not in the compact layout)
        if (!plan.isEmpty() && !menu.layout.compact()) {
            List<String> names = new ArrayList<>();
            for (QuestAdvisor.Suggestion s : plan) {
                names.add(QuestGuides.title(s.quest().name()));
            }
            double levels = xpSum / (double) levelXp();
            Component pl = L.c("wynnlabs.quests.plan_head", minutes).withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(String.join(" → ", names)).withStyle(ChatFormatting.WHITE));
            Component gain = L.c("wynnlabs.quests.plan_levels", String.format(Locale.ROOT, "%.1f", levels)).withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(" · " + String.format(Locale.ROOT, "%.1f", walk / 1000.0) + " km").withStyle(ChatFormatting.DARK_GRAY));
            int gw = font().width(gain);
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
            menu.hits.add(new MenuHit(x, cy - 1, w, 10, null, tip, cmd.toString()));
            cy += 13;
        }
        // 3. filters with their counts; in the compact layout one button that steps to the next
        int fx = x;
        for (int i = 0; i < QuestAdvisor.FILTERS.length && menu.layout.compact(); i++) {
            String f = QuestAdvisor.FILTERS[i];
            if (!f.equals(questFilter)) {
                continue;
            }
            String next = QuestAdvisor.FILTERS[(i + 1) % QuestAdvisor.FILTERS.length];
            String label = L.t(QuestAdvisor.FILTER_NAMES[i]) + " " + lists.getOrDefault(f, List.of()).size() + "  ›";
            int cw = font().width(label) + 12;
            boolean hover = mx >= fx && mx < fx + cw && my >= cy && my < cy + 14;
            Kit.button(g, fx, cy, cw, 14, hover ? Kit.HOVER : Kit.ON);
            onWood(g, label, fx + 6, cy + 3, hover ? Kit.HOVER : Kit.ON);
            menu.hits.add(new MenuHit(fx, cy, cw, 14, null, List.of(L.c("wynnlabs.quests.filter_next",
                    L.t(QuestAdvisor.FILTER_NAMES[(i + 1) % QuestAdvisor.FILTERS.length]))), "#qfilter:" + next));
        }
        for (int i = 0; i < QuestAdvisor.FILTERS.length && !menu.layout.compact(); i++) {
            String f = QuestAdvisor.FILTERS[i];
            int n = lists.getOrDefault(f, List.of()).size();
            String label = L.t(QuestAdvisor.FILTER_NAMES[i]) + " " + n;
            boolean on = f.equals(questFilter);
            int cw = font().width(label) + 12;
            if (fx + cw > x + w) {
                break;
            }
            boolean hover = mx >= fx && mx < fx + cw && my >= cy && my < cy + 14;
            int state = on ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
            Kit.button(g, fx, cy, cw, 14, state);
            onWood(g, label, fx + 6, cy + 3, n == 0 && !on ? Kit.REST : state);
            menu.hits.add(new MenuHit(fx, cy, cw, 14, null, null, "#qfilter:" + f));
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
            g.drawString(font(), num, x + 13 - font().width(num) / 2, ry + 9, 0xFFFFFFFF, true);
            // right: XP and a tag
            String xp = q.xp() > 0 ? QuestAdvisor.fmt(q.xp()) : "";
            String pct = q.xp() > 0 ? " " + RoadmapScreen.pct(Math.min(999, q.xp() * 100 / lvXp)) : "";
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
            int tw = Kit.Ui.tagW(font(), tag);
            int rx = x + listW - 4;
            ink(g, Component.literal(xp).withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(pct).withStyle(ChatFormatting.DARK_GRAY)), rx - font().width(xp + pct), ry + 4, 0xFFFFFFFF);
            g.fill(rx - tw, ry + 13, rx, ry + 13 + Kit.Ui.TAG_H, tagBg);
            ink(g, tag, rx - tw + Kit.Ui.TAG_PAD, Kit.Ui.textY(ry + 13, Kit.Ui.TAG_H), tagFg);
            int textW = listW - 30 - Math.max(font().width(xp + pct), tw) - 8;
            ink(g, Component.literal(fit(QuestGuides.title(q.name()), textW)).withStyle(started ? ChatFormatting.GOLD : ChatFormatting.WHITE),
                    x + 26, ry + 4, 0xFFFFFFFF);
            // the distance first: the most useful number, the last one a narrow row would cut
            String sub = (s.distance() >= 0 ? RoadmapScreen.dist(s.distance()) + " · " : "") + "Lv " + q.level() + " · " + QuestAdvisor.lengthName(q)
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
            menu.hits.add(new MenuHit(x, ry, listW, rowH - 1, null, tip, "#qsel:" + q.name()));
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
                int tw = font().width(t) + 10;
                if (px + tw > ix + iw) {
                    break;
                }
                int c = have < 0 ? MUTED : ok ? Theme.GOOD : Theme.BAD;
                inkIconText(g, ok ? "check" : "x", t, px, iy, c);
                List<Component> ptip = new ArrayList<>();
                ptip.add(Component.literal(Professions.name(e.getKey()) + " " + e.getValue()).withStyle(ChatFormatting.WHITE));
                ptip.add((have < 0 ? L.c("wynnlabs.prof.unknown") : ok ? L.c("wynnlabs.prof.met", have)
                        : L.cn("wynnlabs.prof.short", e.getValue() - have, e.getValue() - have, have)).withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED));
                menu.hits.add(new MenuHit(px, iy - 1, tw, 10, null, ptip, null));
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
        if (questDetailTab == 3 && menu.layout.compact() && buttonRowExtra(x + w - 6 - ix, acts) > 0) {
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
            natural += font().width(t) + 12;
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
                shortW += font().width(t) + 4;
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
            int tw = even ? share - 4 : spread ? font().width(tn) + extra : font().width(tn) + 8;
            int pad = even || spread ? 2 : 4;
            ink(g, tn, tx + pad, iy, on ? 0xFFFFFFFF : MUTED);
            if (on) {
                g.fill(tx + pad - 2, iy + 10, tx + pad + font().width(tn) + 2, iy + 11, ACCENT);
            }
            menu.hits.add(new MenuHit(tx, iy - 2, tw, 13, null, null, "#qtab:" + i));
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
                List<FormattedCharSequence> stepLines = menu.layout.large() ? font().split(Component.literal(text), textW) : List.of();
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
                    int gx = ix + iw - font().width(go) - 9;
                    boolean hv = mx >= gx - 2 && mx < ix + iw && my >= iy - 1 && my < iy + 9;
                    ink(g, go, gx, iy, hv ? 0xFFCECBF6 : 0xFF7F77DD);
                    Art.icon(g, "chevron-right", ix + iw - 8, iy, 8, Theme.paperRgb(hv ? 0xFFCECBF6 : 0xFF7F77DD));
                    // the first tip line names the pin on the map and compass
                    menu.hits.add(new MenuHit(gx - 2, iy - 1, font().width(go) + 13, 10, null,
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
                menu.hits.add(new MenuHit(ix, iy - 1, iw - 32, Kit.Ui.LINE_DENSE * stepRows, null, tip, null));
                iy += Kit.Ui.LINE_DENSE * stepRows;
                if (i == cur) {
                    // the stage you are on: its hints wrapped, not cut, so a puzzle's last line (its
                    // answer, often) shows too; more rows on a large page
                    int hintRows = menu.layout.large() ? 10 : 4;
                    int used = 0;
                    hints:
                    for (String hint : st.hints()) {
                        for (FormattedCharSequence line : font().split(Component.literal(QuestWiki.shown(hint)), iw - 14)) {
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
                ink(g, lvText, ix + iw - font().width(lvText), iy, m.level() > lv + 3 ? 0xFFF09595 : MUTED);
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
                ink(g, Component.literal(fit(r, iw - font().width(gainText) - 4)).withStyle(ChatFormatting.LIGHT_PURPLE)
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
        int goW = font().width(go) + 11;
        int numW = font().width("00.00") + 4;
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
            menu.hits.add(new MenuHit(ix, iy - 1, iw, rowH, null, tip, "/compass at " + at[0] + " " + at[1] + " " + at[2]));
            iy += rowH;
        }
        if (places.size() > rows) {
            String more = (pointScroll + 1) + "–" + Math.min(places.size(), pointScroll + rows) + " / " + places.size();
            ink(g, more, ix + iw - font().width(more), bottom - 4, FAINT);     // clear of a button row right under it
        }
    }
}
