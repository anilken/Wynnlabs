package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.Profile;
import io.github.anilken.wynnlabs.endgame.AspectBook;
import io.github.anilken.wynnlabs.endgame.ItemIcons;
import io.github.anilken.wynnlabs.endgame.LootrunLog;
import io.github.anilken.wynnlabs.endgame.RaidLog;
import io.github.anilken.wynnlabs.endgame.RaidPool;
import io.github.anilken.wynnlabs.endgame.WynnventoryPools;
import io.github.anilken.wynnlabs.model.WeeklyPool;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The J menu's Endgame tab (openspec endgame-tab, design decision 2): the dry card and the
 * aspect card on top, then raids, the recent-runs feed and the aspect list; side by side at the
 * standard size, three columns when large, one section at a time behind the cards when compact.
 * It only reads {@link RaidLog}, {@link RaidPool}, {@link LootrunLog} and {@link AspectBook};
 * its view state (compact section, aspect filter, picked aspect, list scroll) lives here while
 * the game runs and is never saved. Nothing in it acts in the game. {@code RoadmapScreen} only
 * routes to it, as for {@link CraftingTab}.
 *
 * <p>The tab holds the view state and the frame (font, clock, data, click and hover areas), lays
 * the page out and routes input; its parts are drawn by the panels: {@link EndgameTitleBar},
 * {@link EndgameCards}, {@link EndgameRaids}, {@link EndgameRecentRuns}, {@link EndgameAspectList}
 * and {@link EndgamePoolsView}. What it reads is {@link EndgameData}; the preview's fake data is
 * {@link EndgamePreviewData}.
 *
 * <p>API contract of group 1 (design.md, "API contract"): the signatures below stay as they
 * are; group 5 fills the bodies, wires the tab into RoadmapScreen and LayoutMatrix, and may add
 * private helpers.
 */
public final class EndgameTab {

    public static final EndgameTab INSTANCE = new EndgameTab();

    /** The compact layout's sections, in the order of its three cards. */
    static final int SEC_RAIDS = 0;
    static final int SEC_LOOTRUNS = 1;
    static final int SEC_ASPECTS = 2;

    /** The aspect list's filters. */
    static final int F_ALL = 0;
    static final int F_MISSING = 1;
    static final int F_OWNED = 2;
    static final int F_POOL = 3;

    /** The view switch on the title line. */
    static final int VIEW_SUMMARY = 0;
    public static final int VIEW_POOLS = 1;
    /** The sub-tabs of Havuzlar. */
    static final int TAB_RAIDS = 0;
    static final int TAB_CAMPS = 1;

    // view state: kept while the game runs, never saved (the preview tools read some of it by name)
    int section = SEC_RAIDS;
    int filter = F_ALL;
    String pinned;
    private String character = "";
    int listScroll;
    int listScrollMax;
    int[] listRect;
    /** Where the mythic camps line was drawn last frame, for the preview's tooltip shot; null when not drawn. */
    int[] mythicRect;

    // view state of the pools: kept while the game runs, never saved
    int view = VIEW_SUMMARY;
    int poolTab = TAB_RAIDS;
    String litRaid;
    String litCamp;
    boolean classOnly;
    final int[][] poolScroll = new int[2][5];
    /** Where each item row was drawn this frame, for the preview's tooltip shot. */
    final Map<String, int[]> rowRects = new LinkedHashMap<>();

    // the frame the panels draw into
    final List<EndgamePanel.Act> acts = new ArrayList<>();
    final List<EndgamePanel.Tip> tips = new ArrayList<>();
    Font font;
    long now;
    EndgameData d;
    private long dataAt;

    // the panels
    private final EndgameTitleBar titleBar = new EndgameTitleBar(this);
    private final EndgameCards cards = new EndgameCards(this);
    private final EndgameRaids raids = new EndgameRaids(this);
    private final EndgameRecentRuns runs = new EndgameRecentRuns(this);
    final EndgameAspectList aspectList = new EndgameAspectList(this);
    private final EndgamePoolsView pools = new EndgamePoolsView(this);

    private EndgameTab() {}

    // =====================================================================================
    // drawing

    /**
     * Draws the tab's page and its hover tooltips.
     *
     * @param x       the page's left edge, GUI pixels
     * @param y       the page's top edge, under the title line
     * @param w       the page's width (516 at the standard size, 358 compact)
     * @param h       the page's height (299 standard, 179 compact)
     * @param titleY  the title line's y, for the session and streak pills on its right
     * @param frame   the panel {x, y, w, h}
     * @param screenW the screen's width, GUI pixels
     * @param mx      mouse x
     * @param my      mouse y
     * @param pt      partial tick
     */
    void draw(GuiGraphics g, int x, int y, int w, int h, int titleY, int[] frame, int screenW, int mx, int my, float pt) {
        font = UiFont.get();                            // the menu's font, as on every other tab (setting: webFont)
        now = System.currentTimeMillis();
        acts.clear();
        tips.clear();
        listRect = null;
        mythicRect = null;
        pools.newFrame();
        rowRects.clear();
        String who = Profile.id() == null ? "" : Profile.id();
        if (!who.equals(character)) {
            character = who;                            // another character: its own picked aspect
            pinned = null;
            listScroll = 0;
        }
        if (d == null || now - dataAt > 250) {
            d = EndgameData.read(now, classOnly);
            dataAt = now;
        }
        if (pinned != null && AspectBook.byName(pinned) == null) {
            pinned = null;
        }
        // the panel's rail tells the size: an icon rail when compact, a wide panel when large
        boolean compact = x - frame[0] - 12 < MenuLayout.RAIL;
        boolean large = !compact && (frame[2] > 640 || frame[3] > 344);
        titleBar.pills(g, x, titleY, w, compact, mx, my);
        if (view == VIEW_POOLS) {
            pools.drawPools(g, x, y, w, h, compact, large, mx, my);
        } else if (compact) {
            drawCompact(g, x, y, w, h, mx, my);
        } else if (large) {
            drawLarge(g, x, y, w, h, mx, my);
        } else {
            drawStandard(g, x, y, w, h, mx, my);
        }
        tooltip(g, mx, my, screenW);
    }

    /** Standard: two columns, each topped by its card (dry over raids and runs, aspect over the list). */
    private void drawStandard(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        int gap = 10;
        int lw = (w - gap) / 2;
        int rx = x + lw + gap;
        int rw = w - lw - gap;
        int cardH = 54;
        cards.dryCard(g, x, y, lw, cardH, false, mx, my);
        cards.aspectCard(g, rx, y, rw, cardH, false, mx, my);
        int top = y + cardH + 8;
        int ly = raids.raids(g, x, top, lw, 12, false, false, mx, my);
        runs.feed(g, x, ly + 4, lw, y + h - ly - 4, false, mx, my);
        aspectList.aspects(g, rx, top, rw, y + h - top, false, false, mx, my);
    }

    /** Large: the same band of cards over three columns (raids, recent runs, aspects) with more detail. */
    private void drawLarge(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        int gap = 10;
        int c1 = (w - 2 * gap) * 36 / 100;
        int c2 = (w - 2 * gap) * 32 / 100;
        int c3 = w - 2 * gap - c1 - c2;
        int x2 = x + c1 + gap;
        int x3 = x2 + c2 + gap;
        int cardH = 66;
        cards.dryCard(g, x, y, c1 + gap + c2, cardH, true, mx, my);
        cards.aspectCard(g, x3, y, c3, cardH, true, mx, my);
        int top = y + cardH + 8;
        int ry = raids.raids(g, x, top, c1, 13, true, false, mx, my);
        cards.dryLines(g, x, ry + 4, c1, false);
        runs.feed(g, x2, top, c2, y + h - top, true, mx, my);
        aspectList.aspects(g, x3, top, c3, y + h - top, true, false, mx, my);
    }

    /** Compact: the three small cards are the section switch; the lit card's section uses the full width. */
    private void drawCompact(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        int gap = 6;
        int cw = (w - 2 * gap) / 3;
        int ch = 30;
        for (int i = 0; i < 3; i++) {
            int cx = x + i * (cw + gap);
            int ew = i == 2 ? w - 2 * (cw + gap) : cw;
            cards.smallCard(g, i, cx, y, ew, ch, mx, my);
        }
        int sy = y + ch + 8;
        int sh = h - ch - 8;
        switch (section) {
            case SEC_LOOTRUNS -> {
                int ly = cards.lootDryLine(g, x, sy, w);
                if (!d.p().camps().isEmpty()) {
                    ly += 12 * runs.mythicLine(g, x, ly, w, 1, true);
                }
                runs.compactLootruns(g, x, ly + 2, w, y + h - ly - 2, mx, my);
            }
            case SEC_ASPECTS -> aspectList.aspects(g, x, sy, w, sh, false, true, mx, my);
            default -> {
                int ly = cards.dryLines(g, x, sy, w, true);
                raids.raids(g, x, ly + 2, w, 12, false, true, mx, my);
            }
        }
    }

    /** Opens Havuzlar on this sub-tab at this region (the links from Özet). */
    void openPools(int tab, String region) {
        view = VIEW_POOLS;
        poolTab = tab;
        pick(tab == TAB_RAIDS, region);
    }

    /** Lights this region and starts its lists at the top. */
    void pick(boolean raid, String region) {
        if (raid) {
            litRaid = region;
        } else {
            litCamp = region;
        }
        int[] sc = poolScroll[raid ? TAB_RAIDS : TAB_CAMPS];
        for (int i = 0; i < EndgamePoolsView.S_REGIONS; i++) {
            sc[i] = 0;
        }
    }

    /** Back from a picked aspect to the recommendation. */
    void unpin() {
        pinned = null;
    }

    private void tooltip(GuiGraphics g, int mx, int my, int screenW) {
        List<Component> tip = null;
        for (int i = tips.size() - 1; i >= 0; i--) {
            if (tips.get(i).in(mx, my)) {
                tip = tips.get(i).lines();
                break;
            }
        }
        if (tip == null || tip.isEmpty()) {
            return;
        }
        Glossary.draw(g, font, tip, mx, my, screenW);
    }

    // =====================================================================================
    // input

    /** A click on the page: true when the tab used it (section card, filter chip, pool line, aspect row, "↺"). */
    boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) {
            return false;
        }
        for (int i = acts.size() - 1; i >= 0; i--) {
            EndgamePanel.Act a = acts.get(i);
            if (a.in(mx, my)) {
                a.run().run();
                return true;
            }
        }
        return false;
    }

    /** The wheel over the page: scrolls the list under the mouse; true when it did. */
    boolean mouseScrolled(double mx, double my, double v) {
        if (view == VIEW_POOLS) {
            return pools.mouseScrolled(mx, my, v);
        }
        int[] r = listRect;
        if (r == null || mx < r[0] || mx >= r[0] + r[2] || my < r[1] || my >= r[1] + r[3] || listScrollMax <= 0) {
            return false;
        }
        listScroll = Math.max(0, Math.min(listScrollMax, listScroll - (int) Math.signum(v)));
        return true;
    }

    /** For the preview's layout check: the clickable areas drawn last frame. */
    public List<LayoutCheck.Area> clickAreas() {
        List<LayoutCheck.Area> out = new ArrayList<>();
        for (EndgamePanel.Act a : acts) {
            out.add(new LayoutCheck.Area(a.x(), a.y(), a.w(), a.h(), a.what()));
        }
        return out;
    }

    /** The menu was closed (view state is kept for the next opening). */
    void closed() {
        d = null;                                       // read afresh on the next opening
        ItemIcons.clear();                              // the pack may change before the next opening
    }

    /** For the preview: show this compact section. */
    void previewSection(int s) {
        section = Math.max(0, Math.min(2, s));
    }

    // ---- preview ----

    /**
     * For the no-game preview (LayoutMatrix): the fake data of design decision 9 for the
     * character in play, through the preview hooks of the logs, the pool and the aspect book.
     */
    static void preview() {
        EndgamePreviewData.load();
        INSTANCE.pinned = null;
        INSTANCE.filter = F_ALL;
        INSTANCE.listScroll = 0;
        INSTANCE.resetPools();
        INSTANCE.d = null;
    }

    /** For the preview's empty-state shot: no runs, no pool, aspects never read (fake character char0002). */
    static void previewEmpty() {
        EndgamePreviewData.loadEmpty();
        INSTANCE.pinned = null;
        INSTANCE.filter = F_ALL;
        INSTANCE.listScroll = 0;
        INSTANCE.d = null;
    }

    /** For the preview's fallback shot: the data of {@link #preview()} without Wynnventory's pools (only the in-game pool). */
    static void previewFallback() {
        preview();
        WynnventoryPools.preview(WeeklyPool.NONE, WeeklyPool.NONE);
        INSTANCE.d = null;
    }

    /** For the preview: the aspect list's pool filter on (or back to all). */
    public void previewPoolFilter(boolean on) {
        filter = on ? F_POOL : F_ALL;
        listScroll = 0;
    }

    /** For the preview: this aspect picked into the card, as a click on its row does (null: the recommendation). */
    void previewPick(String aspect) {
        pinned = aspect;
    }

    /** For the preview: where the mythic camps line was drawn last frame {x, y, w, h}; null when not drawn. */
    int[] previewMythicLine() {
        return mythicRect;
    }

    /** For the preview: Havuzlar on this sub-tab at this region (null: as remembered), or Özet when {@code tab} is negative. */
    public void previewPools(int tab, String region) {
        if (tab < 0) {
            view = VIEW_SUMMARY;
            return;
        }
        view = VIEW_POOLS;
        poolTab = tab;
        if (region != null) {
            pick(tab == TAB_RAIDS, region);
        }
    }

    /** For the preview: where an item row was drawn last frame {x, y, w, h} (a shiny one: its name and "*"); null when not drawn. */
    int[] previewRow(String name) {
        return rowRects.get(name);
    }

    /** Back to the first-time view state of the pools (Özet, Raidler, no region lit, the class chip off). */
    private void resetPools() {
        view = VIEW_SUMMARY;
        poolTab = TAB_RAIDS;
        litRaid = null;
        litCamp = null;
        classOnly = false;
        for (int[] sc : poolScroll) {
            java.util.Arrays.fill(sc, 0);
        }
    }
}
