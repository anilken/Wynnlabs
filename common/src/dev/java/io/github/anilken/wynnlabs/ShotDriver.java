package io.github.anilken.wynnlabs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dev builds only: the screenshot tool's hands on the mod's own menu. It selects a tab, takes the
 * steps of a view ({@link ShotRun#views}: a filter, a sub-page, the Choose items window …), finds
 * the element a tooltip shot hovers, and puts the menu's shared views (the build window, the
 * Endgame and Crafting pages, the plan pill) back afterwards. It only sets the menu's own view
 * state, never opens a game menu, never starts a road map build or a build search.
 */
final class ShotDriver {

    private final RoadmapScreen screen;

    // the shared views as the run found them
    private final Map<String, Object> window;
    private final Map<String, Object> windowPick;
    private final String windowSearch;
    private final Map<String, Object> endgame;
    private final Map<String, Object> craft;
    private final Map<String, Object> pills;

    ShotDriver(RoadmapScreen screen) {
        this.screen = screen;
        window = Peek.save(BuildWindow.INSTANCE, "open", "view", "editingPlan", "overwriteId", "unseenGen", "awaitGen", "result",
                "card", "fromHint", "chosen", "tuning", "renameId");
        windowPick = Peek.save(Peek.get(BuildWindow.INSTANCE, "pick"), "listScroll", "type", "range");
        var box = (net.minecraft.client.gui.components.EditBox) Peek.get(BuildWindow.INSTANCE, "search");
        windowSearch = box == null ? null : box.getValue();
        endgame = Peek.save(EndgameTab.INSTANCE, "view", "poolTab", "litRaid", "litCamp", "filter", "listScroll", "pinned", "poolScroll");
        craft = Peek.save(CraftingTab.INSTANCE, "view", "scroll");
        pills = Peek.save(planPills(), "planId", "picked");
    }

    // ---- the tabs ----

    /** The tabs' numbers in rail order. */
    static int[] railOrder() {
        return ((int[]) Peek.get(RoadmapScreen.class, "RAIL")).clone();
    }

    static int tabCount() {
        return ((String[]) Peek.get(RoadmapScreen.class, "TABS")).length;
    }

    /** A tab's name in commands and file names: "home", "craft", "endgame" … (its language key's end). */
    static String tabName(int tab) {
        String key = ((String[]) Peek.get(RoadmapScreen.class, "TABS"))[tab];
        return key.substring(key.lastIndexOf("tab_") + 4);
    }

    static String[] tabNames() {
        String[] names = new String[tabCount()];
        for (int t = 0; t < names.length; t++) {
            names[t] = tabName(t);
        }
        return names;
    }

    private static Plans.Pills planPills() {
        return (Plans.Pills) Peek.get(RoadmapScreen.class, "planPills");
    }

    /** {@code t} as a click on its rail entry opens it, with every view inside the menu back at its start. */
    void select(int t) {
        Peek.set(screen, "tab", t);
        Peek.set(screen, "detail", null);
        Peek.set(screen, "goalOpen", false);
        for (String box : new String[] {"search", "treeSearch"}) {
            var b = (net.minecraft.client.gui.components.EditBox) Peek.get(screen, box);
            if (b != null) {
                b.setFocused(false);
                if (box.equals("search")) {
                    b.setValue("");
                }
            }
        }
        screen.setFocused(null);
        Peek.set(screen, "settingsCat", 0);
        Peek.set(screen, "settingsScroll", 0);
        Peek.set(screen, "settingsDetail", false);
        Peek.set(screen, "bankFilter", 0);
        Peek.set(screen, "bankScroll", 0);
        Peek.set(screen, "bankPage", "");
        Peek.set(screen, "questFilter", "RECOMMENDED");
        Peek.set(screen, "questDetailTab", 0);
        Peek.set(screen, "questScroll", 0);
        Peek.set(screen, "treeScroll", 0);
        // the shared views as the run found them (a type filter, a region from the last view gone), then closed
        restore();
        if (BuildWindow.INSTANCE.isOpen()) {
            BuildWindow.INSTANCE.close();
        }
        EndgameTab.INSTANCE.previewPools(-1, null);
        EndgameTab.INSTANCE.previewPoolFilter(false);
        Peek.set(CraftingTab.INSTANCE, "view", 0);
        Peek.set(CraftingTab.INSTANCE, "scroll", 0);
        planPills().pickLevel();
    }

    /** One step of a view ({@link ShotRun#views}); false when there is nothing to show for it. */
    boolean act(String step) {
        String[] p = step.split(":");
        switch (p[0]) {
            case "open" -> {
                return open(p[1]);
            }
            case "click" -> {
                // a button of the build window, by its action and argument ("type:1")
                String what = p[1] + ":" + p[2];
                for (LayoutCheck.Area a : BuildWindow.INSTANCE.clickAreas()) {
                    if (a.what().equals(what)) {
                        return BuildWindow.INSTANCE.mouseClicked(a.x() + a.w() / 2.0, a.y() + a.h() / 2.0, 0);
                    }
                }
                return false;
            }
            case "plan" -> {
                if (Plans.current() == null) {
                    return false;
                }
                planPills().pickPlan();
                return true;
            }
            case "scroll" -> {
                if (p[1].equals("tree")) {
                    Peek.set(screen, "treeScroll", 100_000);
                } else {
                    Peek.set(CraftingTab.INSTANCE, "scroll", 100_000);
                }
                return true;
            }
            case "craft" -> {
                // our suggestions only when they were worked out already: never started here
                int mode = (int) Peek.get(CraftingTab.INSTANCE, "suggestMode");
                List<?> found = (List<?>) Peek.get(CraftingTab.INSTANCE, mode == 0 ? "sets" : "suggestions");
                if (found.isEmpty()) {
                    return false;
                }
                Peek.set(CraftingTab.INSTANCE, "view", 1);
                return true;
            }
            case "endgame" -> {
                return endgame(p);
            }
            case "quest" -> {
                return quest(p);
            }
            case "bank" -> {
                if (Vault.groups().isEmpty()) {
                    return false;
                }
                if (p[1].equals("filter")) {
                    Peek.set(screen, "bankFilter", Integer.parseInt(p[2]));
                    return true;
                }
                String word = searchWord();
                if (word == null) {
                    return false;
                }
                ((net.minecraft.client.gui.components.EditBox) Peek.get(screen, "search")).setValue(word);
                return true;
            }
            case "settings" -> {
                Peek.set(screen, "settingsCat", Integer.parseInt(p[2]));
                return true;
            }
            default -> throw new IllegalArgumentException("unknown step " + step);
        }
    }

    private boolean open(String what) {
        BuildWindow w = BuildWindow.INSTANCE;
        switch (what) {
            case "picker" -> w.openPick();
            case "results" -> {
                Around.Result r = Around.result();
                if (r == null) {
                    return false;               // only a result that was worked out already
                }
                w.openResults(r);
            }
            case "saved" -> {
                if (Plans.saved().isEmpty()) {
                    return false;
                }
                w.openSaved();
            }
            default -> throw new IllegalArgumentException("unknown window " + what);
        }
        var box = (net.minecraft.client.gui.components.EditBox) Peek.get(w, "search");
        if (box != null) {
            box.setFocused(false);              // no blinking cursor in the pictures
        }
        return true;
    }

    private boolean endgame(String[] p) {
        EndgameTab e = EndgameTab.INSTANCE;
        switch (p[1]) {
            case "poolfilter" -> e.previewPoolFilter(true);
            case "pools" -> e.previewPools(Integer.parseInt(p[2]), null);
            case "region" -> {
                // the region list's n-th row, as drawn last frame (none in the compact layout or without data)
                int n = Integer.parseInt(p[2]);
                List<String> regions = new ArrayList<>();
                for (LayoutCheck.Area a : e.clickAreas()) {
                    if (a.what() != null && a.what().startsWith("endgame:region:")) {
                        regions.add(a.what().substring("endgame:region:".length()));
                    }
                }
                if (regions.size() < n) {
                    return false;
                }
                e.previewPools((int) Peek.get(e, "poolTab"), regions.get(n - 1));
            }
            default -> throw new IllegalArgumentException("unknown endgame step " + p[1]);
        }
        return true;
    }

    private boolean quest(String[] p) {
        if (QuestAdvisor.known() == 0) {
            return false;
        }
        if (p[1].equals("tab")) {
            if (Peek.get(screen, "questSelected") == null) {
                return false;
            }
            Peek.set(screen, "questDetailTab", Integer.parseInt(p[2]));
            return true;
        }
        List<QuestAdvisor.Suggestion> list;
        try {
            list = QuestAdvisor.list(p[2]);
        } catch (Throwable t) {
            return false;
        }
        if (list.isEmpty()) {
            return false;
        }
        Peek.set(screen, "questFilter", p[2]);
        Peek.set(screen, "questSelected", null);
        return true;
    }

    /** A word of the first bank item's name that the search finds it by. */
    private static String searchWord() {
        for (Vault.Group gr : Vault.groups()) {
            for (String w : gr.key().split(" ")) {
                String clean = w.replaceAll("[^\\p{L}]", "");
                if (clean.length() >= 4) {
                    return clean.toLowerCase(Locale.ROOT);
                }
            }
        }
        return null;
    }

    // ---- what a tooltip shot hovers ----

    /**
     * The box of {@code part} drawn last frame in GUI pixels {x0, y0, x1, y1}, or null when it was
     * not drawn: the parts the menu reports to the tool, else the boxes the preview already reads.
     */
    float[] target(String part, Map<String, float[]> parts) {
        float[] r = parts.get(part);
        if (r != null) {
            return r;
        }
        int[] logical = switch (part) {
            case "slot" -> HomeTab.INSTANCE.firstSlot;
            case "source" -> HomeTab.INSTANCE.firstPill;
            case "poolrow" -> firstPoolRow();
            default -> null;
        };
        if (logical == null) {
            return null;
        }
        return ShotCrop.toGui(logical[0], logical[1], logical[2], logical[3], MenuScale.factor());
    }

    private static int[] firstPoolRow() {
        if ((int) Peek.get(EndgameTab.INSTANCE, "view") != EndgameTab.VIEW_POOLS) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, int[]> rows = (Map<String, int[]>) Peek.get(EndgameTab.INSTANCE, "rowRects");
        return rows.isEmpty() ? null : rows.values().iterator().next();
    }

    /** The shared views back as the run found them. */
    void restore() {
        Peek.restore(BuildWindow.INSTANCE, window);
        Peek.restore(Peek.get(BuildWindow.INSTANCE, "pick"), windowPick);
        var box = (net.minecraft.client.gui.components.EditBox) Peek.get(BuildWindow.INSTANCE, "search");
        if (box != null && windowSearch != null) {
            box.setValue(windowSearch);
            box.setFocused(false);
        }
        Peek.restore(EndgameTab.INSTANCE, endgame);
        Peek.restore(CraftingTab.INSTANCE, craft);
        Peek.restore(planPills(), pills);
    }
}
