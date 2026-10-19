package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.AspectEntry;
import io.github.anilken.wynnlabs.model.CampMythics;
import io.github.anilken.wynnlabs.model.FeedItem;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.LootrunRun;
import io.github.anilken.wynnlabs.model.LootrunTotals;
import io.github.anilken.wynnlabs.model.MythicFind;
import io.github.anilken.wynnlabs.model.PoolAspect;
import io.github.anilken.wynnlabs.model.RaidDry;
import io.github.anilken.wynnlabs.model.RaidRun;
import io.github.anilken.wynnlabs.model.RaidSummary;
import io.github.anilken.wynnlabs.model.RankedAspect;
import io.github.anilken.wynnlabs.model.PoolItem;
import io.github.anilken.wynnlabs.model.PoolRegion;
import io.github.anilken.wynnlabs.model.Tier;
import io.github.anilken.wynnlabs.model.WeeklyPool;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * <p>API contract of group 1 (design.md, "API contract"): the signatures below stay as they
 * are; group 5 fills the bodies, wires the tab into RoadmapScreen and LayoutMatrix, and may add
 * private helpers.
 */
final class EndgameTab {

    static final EndgameTab INSTANCE = new EndgameTab();

    /** The compact layout's sections, in the order of its three cards. */
    static final int SEC_RAIDS = 0;
    static final int SEC_LOOTRUNS = 1;
    static final int SEC_ASPECTS = 2;

    private static final int F_ALL = 0;
    private static final int F_MISSING = 1;
    private static final int F_OWNED = 2;
    private static final int F_POOL = 3;
    /** The widest line of the pool tooltip's aspect lists, in pixels. */
    private static final int TIP_W = 240;

    /** "Aspect of the Monolith" → "Monolith": the pool tooltip lists many, the full name is on the row. */
    private static String shortAspect(String name) {
        for (String p : new String[] {"Aspect of the ", "Aspect of "}) {
            if (name.startsWith(p)) {
                return name.substring(p.length());
            }
        }
        return name;
    }

    // pack glyphs (drawn when the Wynncraft pack has them, a plain mark in the same place otherwise)
    private static final String TEX_RAID = "wynn/gui/content_book/raid_active";
    private static final String TEX_CAMP = "wynn/gui/content_book/loot_camp_active";
    private static final String TEX_LOOT = "wynn/gui/loot/";
    private static final String TEX_ASPECTS_BUTTON = "wynn/gui/ability_tree/button/aspects";

    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    private static final long DAY = 24 * HOUR;

    // view state: kept while the game runs, never saved
    private int section = SEC_RAIDS;
    private int filter = F_ALL;
    private String pinned;
    private String character = "";
    private int listScroll;
    private int listScrollMax;
    private int[] listRect;
    /** Where the mythic camps line was drawn last frame, for the preview's tooltip shot; null when not drawn. */
    private int[] mythicRect;

    /** A click area drawn this frame, and what a click there does. */
    private record Act(int x, int y, int w, int h, Runnable run, String what) {
        boolean in(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** A hover area drawn this frame, and its tooltip lines. */
    private record Tip(int x, int y, int w, int h, List<Component> lines) {
        boolean in(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<Act> acts = new ArrayList<>();
    private final List<Tip> tips = new ArrayList<>();
    private Font font;
    private long now;
    private Data d;
    private long dataAt;

    private EndgameTab() {}

    // ---- what the tab reads, once every quarter second rather than every frame ----

    private record Data(boolean raidsUp, boolean lootUp, boolean aspectsUp, boolean tracking, List<RaidSummary> summaries,
            List<RaidRun> raids, int sessionDone, int sessionFailed, int streak, RaidDry dry, List<LootrunRun> lootruns,
            LootrunTotals totals, int dryRuns, int dryPulls, List<MythicFind> mythics, int accountDry, List<PoolAspect> pool,
            long poolSeen, boolean poolStale, String poolAge, int poolMarked, Klass klass, boolean dataReady, boolean known,
            List<RankedAspect> ranked, RankedAspect next, int ownedCount, String archetype, boolean guessed, Pools p, Views v) {

        static Data read(long now, boolean classOnly) {
            List<RaidSummary> sums = new ArrayList<>(safe(RaidLog.summaries()));
            if (sums.isEmpty()) {
                RaidLog.RAIDS.forEach(r -> sums.add(RaidSummary.empty(r)));
            }
            RaidDry dry = RaidLog.dry();
            return new Data(Wyn.up(Wyn.Feature.RAIDS), Wyn.up(Wyn.Feature.LOOTRUNS), Wyn.up(Wyn.Feature.ASPECTS),
                    RaidLog.trackingOn(), sums, safe(RaidLog.runs()), RaidLog.sessionDone(), RaidLog.sessionFailed(),
                    RaidLog.streak(), dry == null ? RaidDry.UNKNOWN : dry, safe(LootrunLog.runs()),
                    LootrunLog.totals() == null ? LootrunTotals.NONE : LootrunLog.totals(), LootrunLog.dryRuns(),
                    LootrunLog.dryPulls(), safe(LootrunLog.mythics()), LootrunLog.accountDryPulls(), safe(RaidPool.aspects()),
                    RaidPool.seenAt(), RaidPool.stale(now), RaidPool.ageText(now), RaidPool.markedCount(),
                    AspectBook.klass() == null ? Klass.NONE : AspectBook.klass(), AspectBook.dataReady(), AspectBook.known(),
                    safe(AspectBook.ranked()), AspectBook.next(), AspectBook.ownedCount(), AspectBook.archetype(),
                    AspectBook.archetypeGuessed(), Pools.read(now), Views.read(now, classOnly));
        }

        private static <T> List<T> safe(List<T> l) {
            return l == null ? List.of() : l;
        }
    }

    /**
     * This week's pools (design decision 10): where the raid pool shown comes from, its raids with
     * the class's aspects and their times, and the lootrun camps holding mythics.
     */
    private record Pools(RaidPool.Source source, int week, WynnventoryPools.Status status, List<String> raids,
            Map<String, List<PoolAspect>> byRaid, Map<String, Long> raidSeen, List<CampMythics> camps, List<String> campsNone,
            boolean lootStale, int lootWeek) {

        static Pools read(long now) {
            RaidPool.Source src = RaidPool.source();
            List<String> raids = Data.safe(RaidPool.poolRaids());
            Map<String, List<PoolAspect>> by = new LinkedHashMap<>();
            Map<String, Long> seen = new LinkedHashMap<>();
            for (String r : raids) {
                by.put(r, Data.safe(RaidPool.aspectsIn(r)));
                seen.put(r, RaidPool.raidSeenAt(r));
            }
            WynnventoryPools.Status st = WynnventoryPools.status();
            return new Pools(src == null ? RaidPool.Source.NONE : src, RaidPool.week(), st == null ? WynnventoryPools.Status.OFF : st,
                    raids, by, seen, Data.safe(LootrunLog.mythicCamps()), Data.safe(LootrunLog.campsWithoutMythic()),
                    LootrunLog.poolStale(now), LootrunLog.poolWeek());
        }

        /** The raid pool shown is Wynnventory's. */
        boolean wynnventory() {
            return source == RaidPool.Source.WYNNVENTORY;
        }

        /** Wynnventory's lootrun pool has data (the mythic line is drawn only then). */
        boolean loot() {
            return !camps.isEmpty() || !campsNone.isEmpty();
        }

        long seenOf(String raid) {
            Long s = raidSeen.get(raid);
            return s == null ? -1 : s;
        }
    }

    /**
     * The Havuzlar view's data (design decision 11): the raid and camp region rows, the groups of
     * every region (raids with the class chip applied), and whether each pool is from an earlier week.
     */
    private record Views(List<PoolView.Region> raids, List<PoolView.Region> camps, Map<String, List<PoolView.Group>> raidGroups,
            Map<String, List<PoolView.Group>> campGroups, boolean raidStale, boolean campStale) {

        static Views read(long now, boolean classOnly) {
            List<PoolView.Region> raids = Data.safe(PoolView.raidRegions());
            List<PoolView.Region> camps = Data.safe(PoolView.campRegions());
            Map<String, List<PoolView.Group>> rg = new LinkedHashMap<>();
            for (PoolView.Region r : raids) {
                rg.put(r.name(), Data.safe(PoolView.raid(r.name(), classOnly)));
            }
            Map<String, List<PoolView.Group>> cg = new LinkedHashMap<>();
            for (PoolView.Region r : camps) {
                cg.put(r.name(), Data.safe(PoolView.camp(r.name())));
            }
            return new Views(raids, camps, rg, cg, !raids.isEmpty() && RaidPool.stale(now),
                    !camps.isEmpty() && LootrunLog.poolStale(now));
        }

        List<PoolView.Region> regions(boolean raid) {
            return raid ? raids : camps;
        }

        List<PoolView.Group> groups(boolean raid, String region) {
            return (raid ? raidGroups : campGroups).getOrDefault(region, List.of());
        }

        boolean stale(boolean raid) {
            return raid ? raidStale : campStale;
        }
    }

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
        wheels.clear();
        rowRects.clear();
        String who = Profile.id() == null ? "" : Profile.id();
        if (!who.equals(character)) {
            character = who;                            // another character: its own picked aspect
            pinned = null;
            listScroll = 0;
        }
        if (d == null || now - dataAt > 250) {
            d = Data.read(now, classOnly);
            dataAt = now;
        }
        if (pinned != null && AspectBook.byName(pinned) == null) {
            pinned = null;
        }
        // the panel's rail tells the size: an icon rail when compact, a wide panel when large
        boolean compact = x - frame[0] - 12 < MenuLayout.RAIL;
        boolean large = !compact && (frame[2] > 640 || frame[3] > 344);
        pills(g, x, titleY, w, compact, mx, my);
        if (view == VIEW_POOLS) {
            drawPools(g, x, y, w, h, compact, large, mx, my);
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
        dryCard(g, x, y, lw, cardH, false, mx, my);
        aspectCard(g, rx, y, rw, cardH, false, mx, my);
        int top = y + cardH + 8;
        int ly = raids(g, x, top, lw, 12, false, false, mx, my);
        feed(g, x, ly + 4, lw, y + h - ly - 4, false, mx, my);
        aspects(g, rx, top, rw, y + h - top, false, false, mx, my);
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
        dryCard(g, x, y, c1 + gap + c2, cardH, true, mx, my);
        aspectCard(g, x3, y, c3, cardH, true, mx, my);
        int top = y + cardH + 8;
        int ry = raids(g, x, top, c1, 13, true, false, mx, my);
        dryLines(g, x, ry + 4, c1, false);
        feed(g, x2, top, c2, y + h - top, true, mx, my);
        aspects(g, x3, top, c3, y + h - top, true, false, mx, my);
    }

    /** Compact: the three small cards are the section switch; the lit card's section uses the full width. */
    private void drawCompact(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        int gap = 6;
        int cw = (w - 2 * gap) / 3;
        int ch = 30;
        for (int i = 0; i < 3; i++) {
            int cx = x + i * (cw + gap);
            int ew = i == 2 ? w - 2 * (cw + gap) : cw;
            smallCard(g, i, cx, y, ew, ch, mx, my);
        }
        int sy = y + ch + 8;
        int sh = h - ch - 8;
        switch (section) {
            case SEC_LOOTRUNS -> {
                int ly = lootDryLine(g, x, sy, w);
                if (!d.p().camps().isEmpty()) {
                    ly += 12 * mythicLine(g, x, ly, w, 1, true);
                }
                compactLootruns(g, x, ly + 2, w, y + h - ly - 2, mx, my);
            }
            case SEC_ASPECTS -> aspects(g, x, sy, w, sh, false, true, mx, my);
            default -> {
                int ly = dryLines(g, x, sy, w, true);
                raids(g, x, ly + 2, w, 12, false, true, mx, my);
            }
        }
    }

    // =====================================================================================
    // Havuzlar: every raid's and camp's weekly pool with the items' own icons (decision 11)

    /** The view switch on the title line. */
    static final int VIEW_SUMMARY = 0;
    static final int VIEW_POOLS = 1;
    /** The sub-tabs. */
    static final int TAB_RAIDS = 0;
    static final int TAB_CAMPS = 1;
    /** Scroll slots per sub-tab: the three columns (0-2), the compact list, the region list. */
    private static final int S_LIST = 3;
    private static final int S_REGIONS = 4;
    /** Lines of a pool list: a group header, the "other classes" rule, an item row. */
    private static final int L_HEAD = 0;
    private static final int L_RULE = 1;
    private static final int L_ROW = 2;
    private static final int REGION_ROW = 24;
    /** A card label's glyph (a 12 px line pitch holds it), drawn 2 px above its text line. */
    private static final int ICON = 12;
    /** A list row's glyph (rows of 12), drawn 1 px above its text line: a little over the letters' height. */
    private static final int ROW_ICON = 10;
    /** A pool from an earlier week: its header and source drawn greyed. */
    private static final int GREYED = Theme.LINE;

    // view state of the pools: kept while the game runs, never saved
    private int view = VIEW_SUMMARY;
    private int poolTab = TAB_RAIDS;
    private String litRaid;
    private String litCamp;
    private boolean classOnly;
    private final int[][] poolScroll = new int[2][5];
    /** The lists drawn this frame that the wheel scrolls. */
    private final List<Wheel> wheels = new ArrayList<>();
    /** Where each item row was drawn this frame, for the preview's tooltip shot. */
    private final Map<String, int[]> rowRects = new LinkedHashMap<>();

    /** A list the wheel scrolls: its box, its scroll slot and how far it can scroll. */
    private record Wheel(int x, int y, int w, int h, int slot, int max) {
        boolean in(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** One line of a pool list. */
    private record PLine(int type, PoolView.Group.Kind kind, int count, PoolView.Row row, boolean dim) {}

    /** Opens Havuzlar on this sub-tab at this region (the links from Özet). */
    private void openPools(int tab, String region) {
        view = VIEW_POOLS;
        poolTab = tab;
        pick(tab == TAB_RAIDS, region);
    }

    /** Lights this region and starts its lists at the top. */
    private void pick(boolean raid, String region) {
        if (raid) {
            litRaid = region;
        } else {
            litCamp = region;
        }
        int[] sc = poolScroll[raid ? TAB_RAIDS : TAB_CAMPS];
        for (int i = 0; i < S_REGIONS; i++) {
            sc[i] = 0;
        }
    }

    /** The lit region; the first time a raid with the most marked aspects (ties in raid order) and the first camp. */
    private PoolView.Region lit(List<PoolView.Region> regs, boolean raid) {
        String want = raid ? litRaid : litCamp;
        for (PoolView.Region r : regs) {
            if (r.name().equals(want)) {
                return r;
            }
        }
        PoolView.Region best = regs.get(0);
        if (raid) {
            for (PoolView.Region r : regs) {
                if (r.marked() > best.marked()) {
                    best = r;
                }
            }
        }
        if (raid) {
            litRaid = best.name();
        } else {
            litCamp = best.name();
        }
        return best;
    }

    /** Havuzlar: the sub-tabs, then the region list and the pane (standard, large) or the ‹ › line and one list (compact). */
    private void drawPools(GuiGraphics g, int x, int y, int w, int h, boolean compact, boolean large, int mx, int my) {
        boolean raid = poolTab == TAB_RAIDS;
        List<PoolView.Region> regs = d.v().regions(raid);
        subTabs(g, x, y, w, compact, mx, my);
        int top = y + 18;
        int bottom = y + h;
        if (regs.isEmpty()) {
            boolean loading = d.p().status() == WynnventoryPools.Status.LOADING;
            String s = L.t(loading ? "wynnlabs.endgame.pools_loading"
                    : raid ? "wynnlabs.endgame.pools_none_raids" : "wynnlabs.endgame.pools_none_loot");
            int ly = top + 2;
            for (String line : wrap(s, w)) {
                ink(g, fit(line, w), x, ly, Theme.INK_3);
                ly += 12;
            }
            List<Component> tip = new ArrayList<>(List.of(title(L.t(raid ? "wynnlabs.endgame.raids" : "wynnlabs.endgame.tab_loot"),
                    ChatFormatting.GOLD), grey(s)));
            String why = reason(d.p().status());
            if (why != null) {
                tip.add(dark(why));
            }
            tips.add(new Tip(x, top, w, ly - top, tip));
            return;
        }
        PoolView.Region lit = lit(regs, raid);
        List<PoolView.Group> groups = d.v().groups(raid, lit.name());
        if (compact) {
            stepper(g, regs, lit, raid, x, top, w, mx, my);
            column(g, lines(groups, raid), x, top + 17, w, bottom - top - 17, S_LIST, true, raid, lit, mx, my);
            return;
        }
        int lw = large ? 190 : 150;
        regionList(g, regs, lit, raid, x, top, lw, bottom - top, mx, my);
        int px = x + lw + 8;
        int pw = x + w - px;
        paneHeader(g, lit, raid, px, top, pw, mx, my);
        int n = large ? 3 : 2;
        int gap = 14;
        int cw = (pw - gap * (n - 1)) / n;
        List<List<PoolView.Group>> cols = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            cols.add(new ArrayList<>());
        }
        for (PoolView.Group gr : groups) {
            cols.get(columnOf(gr.kind(), raid, large)).add(gr);
        }
        int cy = top + 17;
        int ch = bottom - 12 - cy;
        for (int i = 0; i < n; i++) {
            int cx = px + i * (cw + gap);
            column(g, lines(cols.get(i), raid), cx, cy, i == n - 1 ? px + pw - cx : cw, ch, i, false, raid, lit, mx, my);
        }
        ink(g, fit(L.t("wynnlabs.endgame.hint_pools"), pw), px, bottom - 9, Theme.INK_3);
    }

    /**
     * The column a group goes in. Standard: a raid's aspects | the rest; a camp's Shiny, Mythic,
     * Fabled | the rest. Large: Aspectler | Tome'lar | Ekipman, Diğer; Shiny, Mythic | Fabled,
     * Legendary | Rare, Unique, Diğer.
     */
    private static int columnOf(PoolView.Group.Kind k, boolean raid, boolean large) {
        if (raid) {
            return switch (k) {
                case SHINY, ASPECTS -> 0;
                case TOMES -> 1;
                default -> large ? 2 : 1;
            };
        }
        return switch (k) {
            case SHINY, MYTHIC -> 0;
            case FABLED -> large ? 1 : 0;
            case LEGENDARY -> 1;
            default -> large ? 2 : 1;
        };
    }

    /** The sub-tabs "Raidler 5" and "Lootrunlar 7", and the source on the right. */
    private void subTabs(GuiGraphics g, int x, int y, int w, boolean compact, int mx, int my) {
        int nr = d.v().raids().size();
        int nc = d.v().camps().size();
        String[] names = {nr > 0 ? L.t("wynnlabs.endgame.tab_raids_n", nr) : L.t("wynnlabs.endgame.raids"),
            nc > 0 ? L.t("wynnlabs.endgame.tab_loot_n", nc) : L.t("wynnlabs.endgame.tab_loot")};
        int cx = x;
        for (int i = 0; i < 2; i++) {
            int bw = Kit.Ui.btnW(font, names[i]);
            boolean on = poolTab == i;
            boolean hover = mx >= cx && mx < cx + bw && my >= y && my < y + 14;
            int state = on ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
            Kit.button(g, cx, y, bw, 14, state);
            g.drawString(font, names[i], cx + Kit.Ui.BTN_PAD, y + 3, Kit.buttonText(state), state != Kit.ON);
            int t = i;
            acts.add(new Act(cx, y, bw, 14, () -> poolTab = t, "endgame:pooltab" + i));
            cx += bw + 3;
        }
        boolean raid = poolTab == TAB_RAIDS;
        boolean stale = d.v().stale(raid);
        String src = sourceText(raid, false);
        int room = x + w - cx - 6;
        if (font.width(src) > room) {
            src = sourceText(raid, true);
        }
        src = fit(src, room);
        if (src.isEmpty()) {
            return;
        }
        int sx = x + w - font.width(src);
        ink(g, src, sx, y + 3, stale ? GREYED : Theme.INK_3);
        List<Component> tip = new ArrayList<>();
        tip.add(dark(sourceTip(raid)));
        if (stale) {
            tip.add(warn(L.t("wynnlabs.endgame.tip_week_stale")));
        }
        String why = reason(d.p().status());
        if (why != null) {
            tip.add(dark(why));
        }
        tips.add(new Tip(sx, y, x + w - sx, 14, tip));
    }

    /** "Wynnventory · 42. hafta" (short: "42. hf"), "oyunda", or why there is no data; "" when there is nothing to say. */
    private String sourceText(boolean raid, boolean shortForm) {
        int week = raid ? d.p().week() : d.p().lootWeek();
        boolean api = raid ? d.p().wynnventory() : !d.v().camps().isEmpty();
        if (api) {
            String wk = week < 0 ? "" : L.t(shortForm ? "wynnlabs.endgame.week_short" : "wynnlabs.endgame.week_n", week);
            return shortForm ? wk : L.t("wynnlabs.endgame.src_wynnventory") + (wk.isEmpty() ? "" : " · " + wk);
        }
        if (raid && d.p().source() == RaidPool.Source.GAME) {
            return L.t("wynnlabs.endgame.src_game");
        }
        String why = reason(d.p().status());
        return why == null ? "" : why;
    }

    /** The tooltips' source line. */
    private String sourceTip(boolean raid) {
        if (raid && !d.p().wynnventory()) {
            return L.t("wynnlabs.endgame.tip_src_game");
        }
        return L.t("wynnlabs.endgame.tip_src_week", raid ? d.p().week() : d.p().lootWeek());
    }

    /** A region's name: the raid or camp, or "Pool you saw in game" for the in-game pool. */
    private static String regionName(PoolView.Region r) {
        return r.name().isEmpty() ? L.t("wynnlabs.endgame.ingame_region") : r.name();
    }

    /** "3 new" for a raid, "4 mythic" for a camp; "" when none. */
    private static String regionCount(PoolView.Region r) {
        if (r.raid()) {
            return r.marked() > 0 ? L.t("wynnlabs.endgame.pool_new", r.marked()) : "";
        }
        return r.mythics() > 0 ? L.n("wynnlabs.endgame.mythic_n", r.mythics()) : "";
    }

    /** The region list: two lines per raid or camp (glyph and name; the count, the shiny mark and the age). */
    private void regionList(GuiGraphics g, List<PoolView.Region> regs, PoolView.Region lit, boolean raid, int x, int y, int w, int h,
            int mx, int my) {
        int ch = Math.min(h, regs.size() * REGION_ROW + 4);
        Kit.card(g, x, y, w, ch, 0);
        int fits = Math.max(1, (ch - 4) / REGION_ROW);
        int max = Math.max(0, regs.size() - fits);
        int[] sc = poolScroll[poolTab];
        sc[S_REGIONS] = Math.max(0, Math.min(max, sc[S_REGIONS]));
        wheels.add(new Wheel(x, y, w, ch, S_REGIONS, max));
        int rw = w - 4 - (max > 0 ? 4 : 0);
        int ry = y + 2;
        int shown = 0;
        for (int i = sc[S_REGIONS]; i < regs.size() && ry + REGION_ROW <= y + ch - 2; i++) {
            PoolView.Region r = regs.get(i);
            boolean on = r.name().equals(lit.name());
            boolean hover = mx >= x + 2 && mx < x + 2 + rw && my >= ry && my < ry + REGION_ROW;
            if (on) {
                g.fill(x + 2, ry, x + 2 + rw, ry + REGION_ROW, Theme.SELECTED);
                Kit.outline(g, x + 2, ry, rw, REGION_ROW, Theme.BRASS);
            } else if (hover) {
                Kit.row(g, x + 2, ry, rw, REGION_ROW, 0, true);
            }
            // the glyph beside both lines, as tall as they are
            int gs = 16;
            glyph(g, raid ? TEX_RAID : TEX_CAMP, raid ? "sword" : "map-pin", x + 5, ry + (REGION_ROW - gs) / 2, gs, Theme.INK_2);
            int tx = x + 5 + gs + 4;
            ink(g, fit(regionName(r), x + rw - 1 - tx), tx, ry + 3, Theme.INK);
            regionLine(g, r, tx, ry + 13, x + rw - 1 - tx);
            if (!on) {
                acts.add(new Act(x + 2, ry, rw, REGION_ROW, () -> pick(raid, r.name()), "endgame:region:" + r.name()));
            }
            tips.add(new Tip(x + 2, ry, rw, REGION_ROW, regionTip(r, !on)));
            ry += REGION_ROW;
            shown++;
        }
        if (max > 0) {
            thumb(g, x + w - 5, y + 2, ch - 4, shown, regs.size(), sc[S_REGIONS], max);
        }
    }

    /** A region row's second line: "3 new · 3h" / "4 mythic ✦ · 2h", the age last and cut first. */
    private void regionLine(GuiGraphics g, PoolView.Region r, int x, int y, int w) {
        String count = regionCount(r);
        int cx = x;
        if (!count.isEmpty()) {
            String cf = fit(count, w);
            ink(g, cf, cx, y, Theme.INK_3);
            cx += font.width(cf);
        }
        if (r.shinies() > 0 && cx + 10 <= x + w) {
            Art.icon(g, "star-filled", cx + 2, y, 8, Theme.TIER_UNIQUE);
            cx += 10;
        }
        if (r.seenAt() >= 0) {
            String age = (cx > x ? " · " : "") + ago(r.seenAt());
            ink(g, fit(age, x + w - cx), cx, y, Theme.INK_3);
        }
    }

    /** A region row's tooltip: its full name, its age, every group with its count, the source. */
    private List<Component> regionTip(PoolView.Region r, boolean click) {
        List<Component> t = new ArrayList<>();
        t.add(title(regionName(r), ChatFormatting.GOLD));
        boolean stale = d.v().stale(r.raid());
        if (r.seenAt() >= 0) {
            t.add(grey(agoLong(r.seenAt()) + (stale ? L.t("wynnlabs.endgame.pool_stale") : "")));
        }
        String count = regionCount(r);
        if (!count.isEmpty() || r.shinies() > 0) {
            String shiny = r.shinies() > 0 ? L.t("wynnlabs.endgame.shiny_n", r.shinies()) : "";
            t.add(r.raid() ? good(count) : Component.literal(count.isEmpty() ? shiny : shiny.isEmpty() ? count : count + " · " + shiny)
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        for (PoolView.Group gr : d.v().groups(r.raid(), r.name())) {
            t.add(grey("  " + groupName(gr.kind()) + " " + gr.rows().size()));
        }
        if (stale) {
            t.add(warn(L.t("wynnlabs.endgame.tip_week_stale")));
        }
        t.add(dark(sourceTip(r.raid())));
        if (click) {
            t.add(dark(L.t("wynnlabs.endgame.card_click")));
        }
        return t;
    }

    /** The pane's header: the region's name in capitals, its age and counts (drawn whole), and the class chip for a raid. */
    private void paneHeader(GuiGraphics g, PoolView.Region r, boolean raid, int x, int y, int w, int mx, int my) {
        int right = x + w;
        if (raid && d.klass() != Klass.NONE) {
            right = classChip(g, right, y - 1, mx, my) - 6;
        }
        boolean stale = d.v().stale(raid);
        String rest = headRest(r, stale);
        String name = r.name().isEmpty() ? regionName(r) : r.name().toUpperCase(Locale.ROOT);
        int restW = Math.min(font.width(rest), (right - x) / 2);
        Component nc = boldFit(name, right - x - restW);
        Kit.text(g, font, nc, x, y + 2, stale ? GREYED : Theme.INK);
        int nx = x + font.width(nc);
        ink(g, fit(rest, right - nx), nx, y + 2, stale ? GREYED : Theme.INK_2);
        tips.add(new Tip(x, y, right - x, 13, regionTip(r, false)));
    }

    /** " · 3 h ago · 3 new" / " · 2 h ago · 4 mythic · 1 shiny", with "· may be old" after the age. */
    private String headRest(PoolView.Region r, boolean stale) {
        StringBuilder b = new StringBuilder();
        if (r.seenAt() >= 0) {
            b.append(" · ").append(agoLong(r.seenAt()));
        }
        if (stale) {
            b.append(L.t("wynnlabs.endgame.pool_stale"));
        }
        String count = regionCount(r);
        if (!count.isEmpty()) {
            b.append(" · ").append(count);
        }
        if (r.shinies() > 0) {
            b.append(" · ").append(L.t("wynnlabs.endgame.shiny_n", r.shinies()));
        }
        return b.toString();
    }

    /** The "Shaman only" chip ending at {@code right}; returns its left edge. */
    private int classChip(GuiGraphics g, int right, int y, int mx, int my) {
        String s = L.t("wynnlabs.endgame.class_only", nice(d.klass()));
        int bw = Kit.Ui.btnW(font, s);
        int bx = right - bw;
        boolean hover = mx >= bx && mx < right && my >= y && my < y + 14;
        int state = classOnly ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
        Kit.button(g, bx, y, bw, 14, state);
        g.drawString(font, s, bx + Kit.Ui.BTN_PAD, y + 3, Kit.buttonText(state), state != Kit.ON);
        acts.add(new Act(bx, y, bw, 14, () -> {
            classOnly = !classOnly;
            int[] sc = poolScroll[TAB_RAIDS];
            for (int i = 0; i < S_REGIONS; i++) {
                sc[i] = 0;
            }
            d = null;                                   // the groups are read again with the chip
        }, "endgame:classonly"));
        tips.add(new Tip(bx, y, bw, 14, List.of(grey(L.t(classOnly ? "wynnlabs.endgame.class_only_off" : "wynnlabs.endgame.class_only_tip",
                nice(d.klass()))))));
        return bx;
    }

    /** Compact: "‹ [R] The Canyon Colossus · 3 new · 3h ›" (the arrows step, the line's tooltip lists them all) and the class chip. */
    private void stepper(GuiGraphics g, List<PoolView.Region> regs, PoolView.Region lit, boolean raid, int x, int y, int w,
            int mx, int my) {
        int right = x + w;
        if (raid && d.klass() != Klass.NONE) {
            right = classChip(g, right, y, mx, my) - 6;
        }
        int idx = Math.max(0, regs.indexOf(lit));
        arrow(g, x, y, "chevron-left", regs.get((idx + regs.size() - 1) % regs.size()), raid, "endgame:step-", mx, my);
        arrow(g, right - 14, y, "chevron-right", regs.get((idx + 1) % regs.size()), raid, "endgame:step+", mx, my);
        int tx = x + 18;
        int end = right - 18;
        glyph(g, raid ? TEX_RAID : TEX_CAMP, raid ? "sword" : "map-pin", tx, y + 1, ICON, Theme.INK_2);
        int nx = tx + ICON + 3;
        String count = regionCount(lit);
        String rest = (count.isEmpty() ? "" : " · " + count)
                + (lit.shinies() > 0 ? " · " + L.t("wynnlabs.endgame.shiny_n", lit.shinies()) : "")
                + (lit.seenAt() >= 0 ? " · " + ago(lit.seenAt()) : "");
        int restW = Math.min(font.width(rest), (end - nx) / 2);
        boolean stale = d.v().stale(raid);
        String nf = fit(regionName(lit), end - nx - restW);
        ink(g, nf, nx, y + 3, stale ? GREYED : Theme.INK);
        int cx = nx + font.width(nf);
        ink(g, fit(rest, end - cx), cx, y + 3, stale ? GREYED : Theme.INK_3);
        List<Component> tip = new ArrayList<>();
        tip.add(title(L.t(raid ? "wynnlabs.endgame.raids" : "wynnlabs.endgame.tab_loot"), ChatFormatting.GOLD));
        for (PoolView.Region r : regs) {
            String c = regionCount(r);
            String line = (r == lit ? "▶ " : "  ") + regionName(r) + (c.isEmpty() ? "" : " · " + c)
                    + (r.shinies() > 0 ? " · " + L.t("wynnlabs.endgame.shiny_n", r.shinies()) : "")
                    + (r.seenAt() >= 0 ? " · " + ago(r.seenAt()) : "");
            tip.add(r == lit ? Component.literal(line).withStyle(ChatFormatting.WHITE) : grey(line));
        }
        if (stale) {
            tip.add(warn(L.t("wynnlabs.endgame.tip_week_stale")));
        }
        tip.add(dark(sourceTip(raid)));
        tip.add(dark(L.t("wynnlabs.endgame.step_tip")));
        tips.add(new Tip(tx, y, end - tx, 14, tip));
    }

    private void arrow(GuiGraphics g, int x, int y, String icon, PoolView.Region to, boolean raid, String what, int mx, int my) {
        boolean hover = mx >= x && mx < x + 14 && my >= y && my < y + 14;
        Kit.button(g, x, y, 14, 14, hover ? Kit.HOVER : Kit.REST);
        Art.icon(g, icon, x + 3, y + 3, 8, Theme.CREAM);
        acts.add(new Act(x, y, 14, 14, () -> pick(raid, to.name()), what));
        tips.add(new Tip(x, y, 14, 14, List.of(grey(regionName(to)))));
    }

    /** The lines of a pool list: each group's header and rows, the "other classes" rule before the first other class's aspect. */
    private List<PLine> lines(List<PoolView.Group> groups, boolean raid) {
        List<PLine> out = new ArrayList<>();
        boolean klass = d.klass() != Klass.NONE;
        for (PoolView.Group gr : groups) {
            out.add(new PLine(L_HEAD, gr.kind(), gr.rows().size(), null, false));
            boolean ruled = false;
            for (PoolView.Row r : gr.rows()) {
                boolean other = raid && klass && r.item().aspect() && !r.ownClass();
                if (other && !ruled) {
                    out.add(new PLine(L_RULE, gr.kind(), 0, null, false));
                    ruled = true;
                }
                out.add(new PLine(L_ROW, gr.kind(), 0, r, other));
            }
        }
        return out;
    }

    private static int lineH(PLine l, boolean compact) {
        return l.type() == L_ROW ? (compact ? 14 : 16) : 12;
    }

    /** One scrolling list of a pool (a column, or the compact list) with its own thumb. */
    private void column(GuiGraphics g, List<PLine> lines, int x, int y, int w, int h, int slot, boolean compact, boolean raid,
            PoolView.Region region, int mx, int my) {
        int bottom = y + h;
        int fitH = 0;
        int from = lines.size();
        while (from > 0 && fitH + lineH(lines.get(from - 1), compact) <= h) {
            fitH += lineH(lines.get(from - 1), compact);
            from--;
        }
        int max = from;
        int[] sc = poolScroll[poolTab];
        sc[slot] = Math.max(0, Math.min(max, sc[slot]));
        wheels.add(new Wheel(x, y, w, h, slot, max));
        int rw = max > 0 ? w - 6 : w;
        int ly = y;
        int shown = 0;
        for (int i = sc[slot]; i < lines.size(); i++) {
            PLine l = lines.get(i);
            int lh = lineH(l, compact);
            if (ly + lh > bottom) {
                break;
            }
            switch (l.type()) {
                case L_HEAD -> groupHeader(g, l, x, ly, rw);
                case L_RULE -> otherRule(g, x, ly, rw);
                default -> itemRow(g, l, x, ly, rw, lh, compact, raid, region, shown, mx, my);
            }
            ly += lh;
            shown++;
        }
        if (max > 0) {
            thumb(g, x + w - 3, y, h, shown, lines.size(), sc[slot], max);
        }
    }

    /** A thumb on a list's right edge. */
    private void thumb(GuiGraphics g, int x, int y, int h, int shown, int total, int pos, int max) {
        int knobH = Math.max(12, h * shown / Math.max(1, total));
        int knobY = y + (h - knobH) * pos / Math.max(1, max);
        g.fill(x, y, x + 2, y + h, 0x30624E3C);
        g.fill(x, knobY, x + 2, knobY + knobH, Theme.WOOD);
    }

    private static String groupName(PoolView.Group.Kind k) {
        return switch (k) {
            case SHINY -> L.t("wynnlabs.endgame.grp_shiny");
            case ASPECTS -> L.t("wynnlabs.endgame.aspects");
            case TOMES -> L.t("wynnlabs.endgame.grp_tomes");
            case GEAR -> L.t("wynnlabs.endgame.grp_gear");
            case MYTHIC -> rarityName(Tier.MYTHIC);
            case FABLED -> rarityName(Tier.FABLED);
            case LEGENDARY -> rarityName(Tier.LEGENDARY);
            case RARE -> rarityName(Tier.RARE);
            case UNIQUE -> rarityName(Tier.UNIQUE);
            default -> L.t("wynnlabs.endgame.grp_other");
        };
    }

    /** A group's header: its name and row count over a rule ("Shiny" with the shiny mark). */
    private void groupHeader(GuiGraphics g, PLine l, int x, int y, int w) {
        int tx = x;
        if (l.kind() == PoolView.Group.Kind.SHINY) {
            Art.icon(g, "sparkles", x, y + 2, 8, Theme.TIER_UNIQUE);
            tx += 10;
        }
        Kit.section(g, font, fit(groupName(l.kind()) + " " + l.count(), x + w - tx - 12), tx, y + 2, x + w - tx);
    }

    /** The dotted rule "other classes" between the class's own aspects and the others. */
    private void otherRule(GuiGraphics g, int x, int y, int w) {
        String s = fit(L.t("wynnlabs.endgame.other_classes"), w - 16);
        int sw = font.width(s);
        int tx = x + (w - sw) / 2;
        for (int dx = x + 2; dx + 2 <= tx - 3; dx += 4) {
            g.fill(dx, y + 6, dx + 2, y + 7, Theme.LINE);
        }
        for (int dx = tx + sw + 3; dx + 2 <= x + w; dx += 4) {
            g.fill(dx, y + 6, dx + 2, y + 7, Theme.LINE);
        }
        ink(g, s, tx, y + 2, Theme.INK_3);
    }

    /**
     * One item row: its icon, its name in its rarity colour, and on the right (drawn first, never
     * cut) the shiny tracker badge, the mark or ✔ of an own aspect, and "×9" for a folded amount.
     */
    private void itemRow(GuiGraphics g, PLine l, int x, int y, int w, int h, boolean compact, boolean raid, PoolView.Region region,
            int index, int mx, int my) {
        PoolView.Row r = l.row();
        PoolItem item = r.item();
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        Kit.row(g, x, y, w, h, index, hover);
        int size = compact ? 12 : 16;
        ItemIcons.draw(g, item, item.aspectClass(), x + 2, y + (h - size) / 2, size, l.dim());
        int ty = y + (h - 8) / 2;
        int rx = x + w - 1;
        if (item.shiny() && !item.shinyStat().isEmpty()) {
            int bw = Kit.Ui.tagW(font, item.shinyStat());
            Art.pill(g, rx - bw, ty - 1, bw, 10, Theme.WELL, 0);
            Kit.text(g, font, item.shinyStat(), rx - bw + Kit.Ui.TAG_PAD, ty, Theme.INK_3);
            rx -= bw + 3;
        }
        if (r.ownClass()) {
            if (r.maxed()) {
                Art.icon(g, "check", rx - 8, ty, 8, Theme.GOOD);
                rx -= 11;
            } else {
                String tg = markText(r);
                if (tg != null) {
                    int tw = Kit.Ui.tagW(font, tg);
                    tag(g, tg, rx - tw, ty - 1);
                    rx -= tw + 3;
                }
            }
        }
        if (r.count() > 1) {
            String n = "×" + r.count();
            right(g, n, rx, ty, Theme.INK_3);
            rx -= font.width(n) + 3;
        }
        int nx = x + 2 + size + 3;
        int colour = tierColour(item.rarity());
        if (l.dim()) {
            colour = colour & 0x00FFFFFF | 0x80000000;
        }
        ink(g, fit(itemName(item), rx - nx), nx, ty, colour);
        rowRects.putIfAbsent(item.name() + (item.shiny() ? "*" : ""), new int[] {x, y, w, h});
        tips.add(new Tip(x, y, w, h, itemTip(r, raid, region)));
    }

    /** "YENİ" or "+T3" for an own aspect's mark; null for none. */
    private static String markText(PoolView.Row r) {
        if (r.mark() == RaidPool.Mark.NEW) {
            return L.t("wynnlabs.endgame.tag_new");
        }
        if (r.mark() == RaidPool.Mark.TIER_UP && r.tagTier() > 0) {
            return L.t("wynnlabs.endgame.tag_tier_up", r.tagTier());
        }
        return null;
    }

    /** The item's name, with its tier as a numeral for a powder or an amplifier ("Fire Powder IV"). */
    private static String itemName(PoolItem i) {
        if (i.tier() <= 0 || i.aspect()) {
            return i.name();
        }
        String[] roman = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        String n = i.tier() <= roman.length ? roman[i.tier() - 1] : String.valueOf(i.tier());
        return i.name().endsWith(" " + n) ? i.name() : i.name() + " " + n;
    }

    /** "Ekipman", "Shaman aspect'i", "Tome", "Rün" …; "" for a type the mod does not know. */
    private String kindName(PoolItem i) {
        if (i.aspect()) {
            return L.t("wynnlabs.endgame.kind_aspect", nice(i.aspectClass())).trim();
        }
        return switch (i.itemType()) {
            case "GearItem" -> L.t("wynnlabs.endgame.grp_gear");
            case "TomeItem" -> L.t("wynnlabs.endgame.kind_tome");
            case "RuneItem" -> L.t("wynnlabs.endgame.kind_rune");
            case "DungeonKeyItem" -> L.t("wynnlabs.endgame.kind_key");
            case "EmeraldItem" -> L.t("wynnlabs.endgame.kind_emerald");
            case "PowderItem" -> L.t("wynnlabs.endgame.kind_powder");
            case "AmplifierItem" -> L.t("wynnlabs.endgame.kind_amplifier");
            case "InsulatorItem" -> L.t("wynnlabs.endgame.kind_insulator");
            case "SimulatorItem" -> L.t("wynnlabs.endgame.kind_simulator");
            case "WardItem" -> L.t("wynnlabs.endgame.kind_ward");
            case "CharmItem" -> L.t("wynnlabs.endgame.kind_charm");
            default -> "";
        };
    }

    /**
     * An item row's tooltip: the name in its rarity colour; rarity · kind; the folded amount; the
     * shiny tracker; for an own aspect the owned tier, its mark and the other raids holding it;
     * then the region with its age and the source.
     */
    private List<Component> itemTip(PoolView.Row r, boolean raid, PoolView.Region region) {
        PoolItem i = r.item();
        List<Component> t = new ArrayList<>();
        t.add(Component.literal(itemName(i)).withStyle(tierChat(i.rarity())));
        String rarity = i.rarity() == Tier.NORMAL ? L.t("wynnlabs.endgame.grp_other") : rarityName(i.rarity());
        String kind = kindName(i);
        t.add(grey(rarity + (kind.isEmpty() ? "" : " · " + kind) + (i.shiny() ? " · " + L.t("wynnlabs.endgame.grp_shiny") : "")));
        if (r.count() > 1) {
            t.add(grey(L.t("wynnlabs.endgame.tip_amount", r.count())));
        }
        if (i.shiny()) {
            t.add(Component.literal(i.shinyStat().isEmpty() ? L.t("wynnlabs.endgame.grp_shiny")
                    : L.t("wynnlabs.endgame.tip_shiny", i.shinyStat())).withStyle(ChatFormatting.YELLOW));
        }
        if (r.ownClass()) {
            String owned = r.ownedTier() < 0 ? L.t("wynnlabs.endgame.tip_owned_unknown")
                    : r.ownedTier() == 0 ? L.t("wynnlabs.endgame.tip_owned_none")
                    : L.t("wynnlabs.endgame.tip_owned", r.ownedTier(), r.maxTier() > 0 ? String.valueOf(r.maxTier()) : "?");
            MutableComponent line = grey(owned).copy();
            String mk = markText(r);
            if (mk != null) {
                line.append(Component.literal(" · " + mk).withStyle(ChatFormatting.GREEN));
            } else if (r.maxed()) {
                line.append(Component.literal(" · " + L.t("wynnlabs.endgame.tier_max")).withStyle(ChatFormatting.GREEN));
            }
            t.add(line);
            List<String> also = new ArrayList<>(Data.safe(RaidPool.raidsHolding(i.name())));
            also.remove(region.name());
            if (!also.isEmpty()) {
                t.add(grey(L.t("wynnlabs.endgame.tip_also", String.join(", ", also))));
            }
        }
        t.add(dark(regionName(region) + (region.seenAt() < 0 ? "" : " · " + agoLong(region.seenAt()))));
        if (d.v().stale(raid)) {
            t.add(warn(L.t("wynnlabs.endgame.tip_week_stale")));
        }
        t.add(dark(sourceTip(raid)));
        return t;
    }

    /** Words of {@code s} in lines no wider than {@code w}. */
    private List<String> wrap(String s, int w) {
        List<String> out = new ArrayList<>();
        String cur = "";
        for (String word : s.split(" ")) {
            String next = cur.isEmpty() ? word : cur + " " + word;
            if (cur.isEmpty() || font.width(next) <= w) {
                cur = next;
            } else {
                out.add(cur);
                cur = word;
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur);
        }
        return out;
    }

    // ---- title line ----

    /**
     * The title line's right: the session and streak pills, as on Today, and before them the
     * Summary / Pools switch (decision 11). The streak pill gives way when the switch would reach
     * the title.
     */
    private void pills(GuiGraphics g, int x, int titleY, int w, boolean compact, int mx, int my) {
        int py = titleY - 2;
        String[] chips = {L.t("wynnlabs.endgame.view_summary"), L.t("wynnlabs.endgame.view_pools")};
        int chipsW = Kit.Ui.btnW(font, chips[0]) + 2 + Kit.Ui.btnW(font, chips[1]);
        int titleEnd = x + font.width(L.c("wynnlabs.screen.tab_endgame").withStyle(UiFont::bold)) + 8;
        String st = L.t("wynnlabs.endgame.pill_streak", d.streak());
        int tw = font.width(st) + 22;
        String session = L.t(compact ? "wynnlabs.endgame.pill_session_short" : "wynnlabs.endgame.pill_session",
                d.sessionDone(), d.sessionFailed());
        int sw = font.width(session) + 22;
        boolean streak = d.raidsUp() && d.streak() > 0;
        // from the right: the streak, then the session before it, then the switch
        int px = x + w - 3 + 6;
        if (streak && px - 6 - tw - 6 - sw - 6 - chipsW < titleEnd) {
            streak = false;
        }
        if (streak) {
            px -= 6 + tw;
            pill(g, px, py, "flame", st, Theme.WARN);
            tips.add(new Tip(px, py, tw, 13, List.of(title(L.n("wynnlabs.endgame.tip_streak_head", d.streak()), ChatFormatting.GOLD),
                    grey(L.t("wynnlabs.endgame.tip_streak")))));
        }
        if (d.raidsUp()) {
            px -= 6 + sw;
            pill(g, px, py, "clock", session, Theme.GOOD);
            tips.add(new Tip(px, py, sw, 13, List.of(title(L.t("wynnlabs.endgame.tip_session_head"), ChatFormatting.GREEN),
                    grey(L.t("wynnlabs.endgame.tip_session", d.sessionDone(), d.sessionFailed())),
                    grey(L.t("wynnlabs.endgame.tip_session_since")))));
            px -= 4;
        } else {
            px -= 9;
        }
        int cx = px - chipsW;
        for (int i = 0; i < 2; i++) {
            int bw = Kit.Ui.btnW(font, chips[i]);
            boolean on = view == i;
            boolean hover = mx >= cx && mx < cx + bw && my >= py && my < py + 13;
            int state = on ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
            Kit.button(g, cx, py, bw, 13, state);
            g.drawString(font, chips[i], cx + Kit.Ui.BTN_PAD, py + 3, Kit.buttonText(state), state != Kit.ON);
            int v = i;
            acts.add(new Act(cx, py, bw, 13, () -> view = v, "endgame:view" + i));
            cx += bw + 2;
        }
    }

    private void pill(GuiGraphics g, int x, int y, String icon, String text, int colour) {
        int w = font.width(text) + 22;
        Kit.pillLine(g, x, y, w, 13, colour);
        Art.icon(g, icon, x + 6, y + 3, 8, colour);
        Kit.text(g, font, text, x + 16, y + 3, colour);
    }

    // ---- the dry card ----

    /** Since the last mythic: the raid half (Wynntils' account counters) and the lootrun half (this character). */
    private void dryCard(GuiGraphics g, int x, int y, int w, int h, boolean large, int mx, int my) {
        Kit.card(g, x, y, w, h, Theme.TIER_MYTHIC);
        // each label's glyph is ICON tall, 2 px above its line; the label starts 3 px after it
        int gap = ICON + 3;
        mythicGlyph(g, x + 6, y + 4, ICON);
        ink(g, fit(L.t("wynnlabs.endgame.dry_head"), w - 12 - gap), x + 6 + gap, y + 6, Theme.INK_2);
        int mid = x + w / 2;
        g.fill(mid, y + 18, mid + 1, y + h - 5, Theme.LINE);
        // the raid half
        int hx = x + 6;
        int hw = mid - 5 - hx;
        glyph(g, TEX_RAID, "sword", hx, y + 16, ICON, Theme.INK_2);
        ink(g, fit(L.t("wynnlabs.endgame.raid"), hw - gap), hx + gap, y + 18, Theme.INK_2);
        String[] rv = raidDryValue();
        value(g, rv[0], rv[1], hx, y + 30, hw, d.raidsUp() ? Theme.TIER_MYTHIC : Theme.WARN);
        ink(g, fit(rv[2], hw), hx, y + 42, Theme.INK_3);
        if (large) {
            ink(g, fit(L.t("wynnlabs.endgame.dry_account"), hw), hx, y + 54, Theme.INK_3);
        }
        tips.add(new Tip(x, y, mid - x, h, raidDryTip()));
        // the lootrun half
        int lx = mid + 6;
        int lw = x + w - 6 - lx;
        glyph(g, TEX_CAMP, "map-pin", lx, y + 16, ICON, Theme.INK_2);
        ink(g, fit(L.t("wynnlabs.endgame.lootrun"), lw - gap), lx + gap, y + 18, Theme.INK_2);
        String[] lv = lootDryValue();
        value(g, lv[0], lv[1], lx, y + 30, lw, d.lootUp() ? Theme.TIER_MYTHIC : Theme.WARN);
        if (d.lootUp() && !d.mythics().isEmpty()) {
            mythicGlyph(g, lx, y + 40, ICON);
            ink(g, fit(lv[2], lw - gap), lx + gap, y + 42, Theme.INK_3);
        } else {
            ink(g, fit(lv[2], lw), lx, y + 42, Theme.INK_3);
        }
        if (large && d.accountDry() >= 0) {
            ink(g, fit(L.n("wynnlabs.endgame.dry_account_pulls", d.accountDry()), lw), lx, y + 54, Theme.INK_3);
        }
        tips.add(new Tip(mid, y, x + w - mid, h, lootDryTip()));
    }

    /** The raid half's {value, value with numbers only, line under it}. */
    private String[] raidDryValue() {
        RaidDry r = d.dry();
        if (!d.raidsUp()) {
            return new String[] {L.t("wynnlabs.endgame.off"), L.t("wynnlabs.endgame.off"), L.t("wynnlabs.endgame.off_wynntils")};
        }
        String tome = r.tomePulls() >= 0 ? L.n("wynnlabs.endgame.dry_tome", r.tomePulls()) : L.t("wynnlabs.endgame.dry_tome_unknown");
        if (r.aspectPulls() < 0 && r.aspectRaids() < 0) {
            return new String[] {L.t("wynnlabs.endgame.unknown"), L.t("wynnlabs.endgame.unknown"),
                r.tomePulls() >= 0 ? tome : L.t("wynnlabs.endgame.after_first_raid")};
        }
        return new String[] {L.t("wynnlabs.endgame.dry_raid_value", num(r.aspectPulls()), num(r.aspectRaids())),
            num(r.aspectPulls()) + " · " + num(r.aspectRaids()), tome};
    }

    /** The lootrun half's {value, value with numbers only, line under it}. */
    private String[] lootDryValue() {
        if (!d.lootUp()) {
            return new String[] {L.t("wynnlabs.endgame.off"), L.t("wynnlabs.endgame.off"), L.t("wynnlabs.endgame.off_wynntils")};
        }
        if (d.lootruns().isEmpty()) {
            return new String[] {L.t("wynnlabs.endgame.no_runs"), L.t("wynnlabs.endgame.no_runs"),
                L.t("wynnlabs.endgame.after_first_lootrun")};
        }
        String sub = d.mythics().isEmpty() ? L.t("wynnlabs.endgame.no_mythic")
                : L.n("wynnlabs.endgame.mythic_count", d.mythics().size(), d.mythics().size(), d.mythics().get(0).name());
        return new String[] {L.t("wynnlabs.endgame.dry_loot_value", d.dryPulls(), d.dryRuns()),
            d.dryPulls() + " · " + d.dryRuns(), sub};
    }

    /** A bold value; when it is too long, its numbers alone (numbers are never cut). */
    private void value(GuiGraphics g, String full, String numbers, int x, int y, int w, int colour) {
        Component c = bold(full);
        if (font.width(c) > w) {
            c = bold(numbers);
        }
        Kit.text(g, font, c, x, y, colour);
    }

    private List<Component> raidDryTip() {
        List<Component> t = new ArrayList<>();
        t.add(title(L.t("wynnlabs.endgame.tip_raid_dry_head"), ChatFormatting.LIGHT_PURPLE));
        if (!d.raidsUp()) {
            t.add(warn(L.t("wynnlabs.endgame.raids_off")));
            return t;
        }
        RaidDry r = d.dry();
        t.add(grey(L.t("wynnlabs.endgame.tip_dry_aspect", num(r.aspectPulls()), num(r.aspectRaids()))));
        t.add(grey(L.t("wynnlabs.endgame.tip_dry_tome", num(r.tomePulls()), num(r.tomeRaids()))));
        t.add(dark(L.t("wynnlabs.endgame.tip_account")));
        return t;
    }

    private List<Component> lootDryTip() {
        List<Component> t = new ArrayList<>();
        t.add(title(L.t("wynnlabs.endgame.tip_loot_dry_head"), ChatFormatting.LIGHT_PURPLE));
        if (!d.lootUp()) {
            t.add(warn(L.t("wynnlabs.endgame.lootruns_off")));
            return t;
        }
        t.add(grey(L.t("wynnlabs.endgame.tip_loot_dry", d.dryRuns(), d.dryPulls())));
        if (d.accountDry() >= 0) {
            t.add(dark(L.n("wynnlabs.endgame.tip_loot_account", d.accountDry())));
        }
        if (d.mythics().isEmpty()) {
            t.add(grey(L.t("wynnlabs.endgame.no_mythic")));
        } else {
            t.add(grey(L.t("wynnlabs.endgame.tip_mythics", d.mythics().size())));
            for (MythicFind m : d.mythics()) {
                t.add(Component.literal("  ◆ ").withStyle(ChatFormatting.DARK_PURPLE)
                        .append(Component.literal(m.name()).withStyle(ChatFormatting.LIGHT_PURPLE))
                        .append(Component.literal(" · " + agoLong(m.at())).withStyle(ChatFormatting.GRAY)));
            }
        }
        return t;
    }

    // ---- the aspect card ----

    /** The next aspect for the build, or the one picked from the list, with a control back to the recommendation. */
    private void aspectCard(GuiGraphics g, int x, int y, int w, int h, boolean large, int mx, int my) {
        RankedAspect a = pinned != null ? AspectBook.byName(pinned) : d.next();
        int accent = a != null && (d.known() || pinned != null) ? tierColour(a.aspect().rarity()) : Theme.GOLD_DEEP;
        Kit.card(g, x, y, w, h, accent);
        int sx = x + 6;
        int sy = y + (h - 28) / 2 + 1;
        int tx = x + 40;
        int tw = x + w - 6 - tx;
        if (!d.aspectsUp() || !d.dataReady() || a == null || !d.known() && pinned == null) {
            // nothing to recommend yet: say what to do in game, with the game's Aspects button when unread
            Kit.slot(g, sx, sy, 28, 0);
            String[] lines;
            if (!d.aspectsUp()) {
                Art.icon(g, "alert-triangle", sx + 6, sy + 6, 16, Theme.WARN);
                lines = new String[] {L.t("wynnlabs.endgame.aspects_off"), L.t("wynnlabs.endgame.off_wynntils")};
            } else if (!d.dataReady()) {
                Art.icon(g, "clock", sx + 6, sy + 6, 16, Theme.INK_3);
                lines = new String[] {L.t("wynnlabs.endgame.aspects_no_data"), L.t("wynnlabs.endgame.aspects_no_data_2")};
            } else if (!d.known()) {
                if (!WynnTex.drawTrimmed(g, TEX_ASPECTS_BUTTON, sx + 2, sy + 2, 24)) {
                    Art.icon(g, "hierarchy", sx + 6, sy + 6, 16, Theme.INK_2);
                }
                lines = new String[] {L.t("wynnlabs.endgame.aspects_unread"), L.t("wynnlabs.endgame.aspects_unread_2"),
                    L.t("wynnlabs.endgame.aspects_unread_3")};
            } else {
                Art.icon(g, "check", sx + 6, sy + 6, 16, Theme.GOOD);
                lines = new String[] {L.t("wynnlabs.endgame.aspects_all_done")};
            }
            int ly = y + 6;
            for (int i = 0; i < lines.length; i++) {
                ink(g, fit(lines[i], tw), tx, ly, i == 0 ? Theme.INK : Theme.INK_3);
                ly += 12;
            }
            return;
        }
        int ring = tierColour(a.aspect().rarity());
        Kit.slot(g, sx, sy, 28, ring);
        // the aspect's own icon filling the slot, as the list and the Pools view draw it (the
        // pack's 48 px picture holds a 16 px crystal: drawn whole into the slot it was a dot)
        aspectGlyph(g, a.aspect(), sx + 2, sy + 2, 24);
        // line 1: the label, the tier pips and, after a pick, the way back to the recommendation
        int right = x + w - 6;
        if (pinned != null) {
            int bx = x + w - 18;
            int by = y + 4;
            boolean hover = mx >= bx && mx < bx + 12 && my >= by && my < by + 12;
            Kit.button(g, bx, by, 12, 12, hover ? Kit.HOVER : Kit.REST);
            Art.icon(g, "refresh", bx + 2, by + 2, 8, Theme.CREAM);
            acts.add(new Act(bx - 2, y + 2, 16, 15, this::unpin, "endgame:back"));
            tips.add(new Tip(bx - 2, y + 2, 16, 15, List.of(grey(L.t("wynnlabs.endgame.back_tip")))));
            right = bx - 4;
        }
        int pw = pipsW(a.aspect().tiers());
        pips(g, right - pw, y + 7, a.aspect().tiers(), a.ownedTier(), ring);
        String label = L.t(pinned != null ? "wynnlabs.endgame.card_selected" : "wynnlabs.endgame.card_next");
        ink(g, fit(label, right - pw - 6 - tx), tx, y + 6, Theme.INK_2);
        // the name in bold, measured in bold; on the large card a long one takes two lines (the
        // fifth line gives way), else it is cut with "…" inside the card
        List<MutableComponent> title = boldLines(a.aspect().name(), tw, large ? 2 : 1);
        int ny = y + 17;
        for (MutableComponent line : title) {
            Kit.text(g, font, line, tx, ny, ring);
            ny += 10;
        }
        int shift = Math.max(0, title.size() - 1) * 10;
        String reason = a.reason().isEmpty() ? L.t("wynnlabs.endgame.reason_none") : a.reason();
        ink(g, fit(reason, tw), tx, y + 29 + shift, Theme.INK);
        // the source, and its pool mark when the pool shown holds it: from Wynnventory, the raids
        // holding it this week (full names when they fit, else short ones)
        String name = a.aspect().name();
        String tag = tagText(name);
        int tagW = tag == null ? 0 : Kit.Ui.tagW(font, tag) + 4;
        String src;
        String fullRaids = null;
        if (d.p().wynnventory()) {
            List<String> held = RaidPool.raidsHolding(name);
            if (held == null || held.isEmpty()) {
                src = L.t("wynnlabs.endgame.source_not_in_pool");
            } else {
                src = L.t("wynnlabs.endgame.source_week", String.join(", ", held));
                if (font.width(src) > tw - tagW) {
                    List<String> shorts = new ArrayList<>();
                    held.forEach(r -> shorts.add(RaidPool.shortName(r)));
                    src = L.t("wynnlabs.endgame.source_week", String.join(", ", shorts));
                    fullRaids = String.join(", ", held);
                }
            }
        } else {
            src = L.t("wynnlabs.endgame.source_raid") + (RaidPool.holds(name) ? L.t("wynnlabs.endgame.source_pool") : "");
        }
        String srcFit = fit(src, tw - tagW);
        ink(g, srcFit, tx, y + 41 + shift, Theme.INK_3);
        if (tag != null) {
            tag(g, tag, tx + font.width(srcFit) + 4, y + 40 + shift);
        }
        if (large && shift == 0) {
            // the fifth line: the raids' full names when line four had to shorten them
            ink(g, fit(fullRaids != null ? fullRaids : nextTierText(a), tw), tx, y + 53, Theme.INK_3);
        }
        tips.add(new Tip(x, y, (pinned != null ? x + w - 20 : x + w) - x, h, aspectTip(a)));
    }

    private void unpin() {
        pinned = null;
    }

    /** "Next tier: 5 copies" from the thresholds, or that it is at its top tier. */
    private String nextTierText(RankedAspect a) {
        AspectEntry e = a.aspect();
        int owned = Math.max(0, a.ownedTier());
        if (a.ownedTier() >= e.tiers()) {
            return L.t("wynnlabs.endgame.tier_max");
        }
        if (owned < e.thresholds().size()) {
            return L.n("wynnlabs.endgame.tier_next", e.thresholds().get(owned), owned + 1, e.thresholds().get(owned));
        }
        return "";
    }

    // ---- compact cards ----

    /** One of the compact layout's three cards: its answer in two lines; a click opens its section. */
    private void smallCard(GuiGraphics g, int i, int x, int y, int w, int h, int mx, int my) {
        boolean lit = section == i;
        RankedAspect a = i == SEC_ASPECTS ? (pinned != null ? AspectBook.byName(pinned) : d.next()) : null;
        int accent = i == SEC_ASPECTS ? (a != null && d.known() ? tierColour(a.aspect().rarity()) : Theme.GOLD_DEEP) : Theme.TIER_MYTHIC;
        Kit.card(g, x, y, w, h, accent, lit);
        if (lit) {
            // a gold tip under the lit card, like the rail's
            int cx = x + w / 2;
            for (int k = 0; k < 4; k++) {
                g.fill(cx - 3 + k, y + h + k, cx + 4 - k, y + h + k + 1, Theme.GOLD_DEEP);
            }
        }
        int tx = x + 6;
        int tw = w - 12;
        int areaW = w;
        List<Component> tip;
        switch (i) {
            case SEC_RAIDS -> {
                glyph(g, TEX_RAID, "sword", tx, y + 3, ICON, Theme.INK_2);
                ink(g, fit(L.t("wynnlabs.endgame.raid"), tw - ICON - 4), tx + ICON + 4, y + 5, Theme.INK_2);
                String v = !d.raidsUp() ? L.t("wynnlabs.endgame.off")
                        : d.dry().aspectPulls() >= 0 ? L.n("wynnlabs.endgame.pulls_n", d.dry().aspectPulls()) : L.t("wynnlabs.endgame.unknown");
                value(g, v, d.dry().aspectPulls() >= 0 ? String.valueOf(d.dry().aspectPulls()) : v, tx, y + 17, tw,
                        d.raidsUp() ? Theme.TIER_MYTHIC : Theme.WARN);
                tip = new ArrayList<>(raidDryTip());
            }
            case SEC_LOOTRUNS -> {
                glyph(g, TEX_CAMP, "map-pin", tx, y + 3, ICON, Theme.INK_2);
                ink(g, fit(L.t("wynnlabs.endgame.lootrun"), tw - ICON - 4), tx + ICON + 4, y + 5, Theme.INK_2);
                String v = !d.lootUp() ? L.t("wynnlabs.endgame.off")
                        : d.lootruns().isEmpty() ? L.t("wynnlabs.endgame.no_runs") : L.n("wynnlabs.endgame.pulls_n", d.dryPulls());
                value(g, v, d.lootruns().isEmpty() ? v : String.valueOf(d.dryPulls()), tx, y + 17, tw,
                        d.lootUp() ? Theme.TIER_MYTHIC : Theme.WARN);
                tip = new ArrayList<>(lootDryTip());
            }
            default -> {
                if (pinned != null && a != null) {
                    // the way back to the recommendation, beside the card's own click area
                    int bx = x + w - 15;
                    boolean hover = mx >= bx && mx < bx + 12 && my >= y + 3 && my < y + 15;
                    Kit.button(g, bx, y + 3, 12, 12, hover ? Kit.HOVER : Kit.REST);
                    Art.icon(g, "refresh", bx + 2, y + 5, 8, Theme.CREAM);
                    acts.add(new Act(x + w - 16, y, 16, h, this::unpin, "endgame:back"));
                    tips.add(new Tip(x + w - 16, y, 16, h, List.of(grey(L.t("wynnlabs.endgame.back_tip")))));
                    areaW = w - 16;
                    tw = w - 12 - 16;
                }
                if (a != null && d.known() || a != null && pinned != null) {
                    int ring = tierColour(a.aspect().rarity());
                    aspectGlyph(g, a.aspect(), tx, y + 3, ICON);
                    ink(g, fit(L.t(pinned != null ? "wynnlabs.endgame.card_selected" : "wynnlabs.endgame.card_next"), tw - ICON - 4),
                            tx + ICON + 4, y + 5, Theme.INK_2);
                    Kit.text(g, font, boldFit(a.aspect().name(), tw), tx, y + 17, ring);
                    tip = new ArrayList<>(aspectTip(a));
                } else {
                    if (!WynnTex.drawTrimmed(g, TEX_ASPECTS_BUTTON, tx, y + 3, ICON)) {
                        Art.icon(g, "hierarchy", tx, y + 3, ICON, Theme.INK_2);
                    }
                    ink(g, fit(L.t("wynnlabs.endgame.aspects"), tw - ICON - 4), tx + ICON + 4, y + 5, Theme.INK_2);
                    String v = !d.aspectsUp() ? L.t("wynnlabs.endgame.off") : !d.dataReady() ? L.t("wynnlabs.endgame.unknown")
                            : L.t("wynnlabs.endgame.open_aspects");
                    ink(g, fit(v, tw), tx, y + 17, d.aspectsUp() ? Theme.INK_3 : Theme.WARN);
                    tip = new ArrayList<>(List.of(title(L.t("wynnlabs.endgame.aspects"), ChatFormatting.AQUA),
                            grey(!d.aspectsUp() ? L.t("wynnlabs.endgame.aspects_off") : !d.dataReady()
                                    ? L.t("wynnlabs.endgame.aspects_no_data") : L.t("wynnlabs.endgame.aspects_unread_tip"))));
                }
            }
        }
        if (!lit) {
            tip.add(dark(L.t("wynnlabs.endgame.card_click")));
        }
        int sec = i;
        acts.add(new Act(x, y, areaW, h, () -> section = sec, "endgame:section" + i));
        tips.add(new Tip(x, y, areaW, h, tip));
    }

    /** The compact raid section's dry lines in full words (the card only has room for the number); returns the y under them. */
    private int dryLines(GuiGraphics g, int x, int y, int w, boolean compact) {
        if (!d.raidsUp()) {
            return y;
        }
        RaidDry r = d.dry();
        String a = r.aspectPulls() < 0 && r.aspectRaids() < 0 ? L.t("wynnlabs.endgame.line_aspect_unknown")
                : L.t("wynnlabs.endgame.line_aspect", num(r.aspectPulls()), num(r.aspectRaids()));
        String t = r.tomePulls() < 0 && r.tomeRaids() < 0 ? L.t("wynnlabs.endgame.line_tome_unknown")
                : L.t("wynnlabs.endgame.line_tome", num(r.tomePulls()), num(r.tomeRaids()));
        ink(g, fit(a, w), x, y, Theme.INK_2);
        ink(g, fit(t, w), x, y + 12, Theme.INK_2);
        tips.add(new Tip(x, y, w, 23, raidDryTip()));
        return y + 24;
    }

    /** The compact lootrun section's dry line; returns the y under it. */
    private int lootDryLine(GuiGraphics g, int x, int y, int w) {
        if (!d.lootUp()) {
            ink(g, fit(L.t("wynnlabs.endgame.lootruns_off"), w), x, y, Theme.WARN);
            return y + 12;
        }
        if (d.lootruns().isEmpty()) {
            return y;
        }
        String line = L.t("wynnlabs.endgame.line_loot", d.dryPulls(), d.dryRuns());
        String m = d.mythics().isEmpty() ? "" : L.n("wynnlabs.endgame.mythic_n", d.mythics().size());
        int mw = m.isEmpty() ? 0 : font.width(m) + ROW_ICON + 6;
        String lf = fit(line, w - mw);
        ink(g, lf, x, y, Theme.INK_2);
        if (!m.isEmpty()) {
            int gx = x + font.width(lf) + 4;
            mythicGlyph(g, gx, y - 1, ROW_ICON);
            ink(g, m, gx + ROW_ICON + 2, y, Theme.TIER_MYTHIC);
        }
        tips.add(new Tip(x, y, w, 11, lootDryTip()));
        return y + 12;
    }

    // ---- raids ----

    /** The raid table (done, failed, best; average and last when large) and the pool line; returns the y under it. */
    private int raids(GuiGraphics g, int x, int y, int w, int rowH, boolean large, boolean compact, int mx, int my) {
        // numbers right-aligned from the right edge; the name gets what is left
        boolean extra = large && w >= 250;
        int lastR = x + w;
        int avgR = extra ? lastR - 32 : lastR;
        int bestR = extra ? avgR - 36 : lastR;
        int failR = bestR - 38;
        int doneR = failR - 20;
        Kit.section(g, font, L.t("wynnlabs.endgame.raids"), x, y, doneR - 14 - x);
        Art.icon(g, "check", doneR - 8, y, 8, Theme.GOOD);
        Art.icon(g, "x", failR - 8, y, 8, Theme.BAD);
        right(g, L.t("wynnlabs.endgame.col_best"), bestR, y, Theme.INK_3);
        if (extra) {
            right(g, L.t("wynnlabs.endgame.col_avg"), avgR, y, Theme.INK_3);
            right(g, L.t("wynnlabs.endgame.col_last"), lastR, y, Theme.INK_3);
        }
        int ry = y + 11;
        if (!d.raidsUp()) {
            ink(g, fit(L.t("wynnlabs.endgame.raids_off"), w), x, ry + 1, Theme.WARN);
            ry += 12;
        } else if (!d.tracking()) {
            ink(g, fit(L.t("wynnlabs.endgame.raids_untracked"), w), x, ry + 1, Theme.WARN);
            ry += 12;
        }
        boolean any = false;
        for (int i = 0; i < d.summaries().size(); i++) {
            RaidSummary s = d.summaries().get(i);
            boolean none = s.done() + s.failed() == 0;
            any |= !none;
            boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + rowH;
            Kit.row(g, x, ry, w, rowH, i, hover);
            int ty = ry + (rowH - 8) / 2;
            int c = none ? Theme.INK_3 : Theme.INK;
            ink(g, fit(s.raid(), doneR - 22 - x - 2), x + 2, ty, c);
            right(g, none ? "-" : String.valueOf(s.done()), doneR, ty, c);
            right(g, none ? "-" : String.valueOf(s.failed()), failR, ty, none ? Theme.INK_3 : s.failed() > 0 ? Theme.BAD : Theme.INK_3);
            right(g, time(s.bestMs()), bestR, ty, s.bestMs() < 0 ? Theme.INK_3 : Theme.INK);
            if (extra) {
                right(g, time(s.averageMs()), avgR, ty, s.averageMs() < 0 ? Theme.INK_3 : Theme.INK_2);
                right(g, s.lastAt() < 0 ? "--" : ago(s.lastAt()), lastR, ty, Theme.INK_3);
            }
            tips.add(new Tip(x, ry, w, rowH, raidTip(s)));
            ry += rowH;
            if (ry + rowH > y + 11 + 8 * rowH && i < d.summaries().size() - 1 && compact) {
                break;                                  // a long list of new raids: the first rows only
            }
        }
        if (!any && d.raidsUp()) {
            ink(g, fit(L.t("wynnlabs.endgame.raids_empty"), w), x, ry + 2, Theme.INK_3);
            ry += 12;
        }
        return poolLine(g, x, ry + 1, w, large, compact, mx, my);
    }

    private List<Component> raidTip(RaidSummary s) {
        List<Component> t = new ArrayList<>();
        t.add(title(s.raid(), ChatFormatting.GOLD));
        if (s.done() + s.failed() == 0) {
            t.add(grey(L.t("wynnlabs.endgame.raids_empty")));
            return t;
        }
        t.add(grey(L.t("wynnlabs.endgame.tip_raid_counts", s.done(), s.failed())));
        t.add(grey(L.t("wynnlabs.endgame.tip_raid_times", time(s.bestMs()), time(s.averageMs()))));
        if (s.last() != null) {
            t.add(grey(L.t(s.last().completed() ? "wynnlabs.endgame.tip_raid_last_done" : "wynnlabs.endgame.tip_raid_last_failed",
                    agoLong(s.lastAt()), time(s.last().durationMs()))));
        }
        if (!s.bestRooms().isEmpty()) {
            t.add(Component.literal(L.t("wynnlabs.endgame.tip_best_rooms")).withStyle(ChatFormatting.WHITE));
            for (RaidRun.Room r : s.bestRooms()) {
                t.add(grey("  " + r.name() + ": " + time(r.ms())));
            }
        }
        return t;
    }

    /**
     * The weekly pool line: its source in faint ink, its age and how many would be new (drawn
     * first, never cut); a click filters the aspect list to it.
     */
    private int poolLine(GuiGraphics g, int x, int y, int w, boolean large, boolean compact, int mx, int my) {
        int h = 12;
        Pools p = d.p();
        if (p.source() == RaidPool.Source.NONE || !p.wynnventory() && d.poolSeen() < 0) {
            ink(g, fit(L.t("wynnlabs.endgame.pool_never"), w), x, y + 2, Theme.INK_3);
            List<Component> tip = new ArrayList<>(List.of(title(L.t("wynnlabs.endgame.pool"), ChatFormatting.GOLD),
                    grey(L.t("wynnlabs.endgame.pool_never_tip"))));
            String why = reason(p.status());
            if (why != null) {
                tip.add(dark(why));
            }
            tips.add(new Tip(x, y, w, h, tip));
            return y + h;
        }
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        if (hover) {
            Kit.row(g, x, y, w, h, 0, true);
        }
        boolean stale = d.poolStale();
        int c = stale ? Theme.INK_3 : Theme.ACCENT;
        Art.icon(g, "clock", x + 2, y + 2, 8, c);
        int tx = x + 13;
        int end = x + w - 13;
        String source = L.t(p.wynnventory() ? "wynnlabs.endgame.src_wynnventory" : "wynnlabs.endgame.src_game");
        String head = L.t("wynnlabs.endgame.pool_head") + " · ";
        String age = d.poolAge() == null ? "" : d.poolAge();
        if (large) {
            // every pool aspect of the class, with its raids' short names when from Wynnventory
            List<String> names = new ArrayList<>();
            for (PoolAspect a : d.pool()) {
                List<String> held = p.wynnventory() ? RaidPool.raidsHolding(a.name()) : List.of();
                if (held == null || held.isEmpty()) {
                    names.add(a.name());
                } else {
                    List<String> shorts = new ArrayList<>();
                    held.forEach(r -> shorts.add(RaidPool.shortName(r)));
                    names.add(a.name() + " (" + String.join(", ", shorts) + ")");
                }
            }
            String rest = (p.wynnventory() && p.week() >= 0 ? " · " + L.t("wynnlabs.endgame.week_n", p.week()) : "")
                    + (age.isEmpty() ? "" : " · " + age) + (stale ? L.t("wynnlabs.endgame.pool_stale") : "") + ":";
            segments(g, tx, y + 2, end - tx, new String[] {head, source, rest}, new int[] {c, Theme.INK_3, c});
            // the aspects under it, wrapped whole over up to four lines
            List<String> wrapped = new ArrayList<>();
            String cur = "";
            for (String n : names) {
                String add = cur.isEmpty() ? n : cur + ", " + n;
                if (cur.isEmpty() || font.width(add) <= end - tx) {
                    cur = add;
                } else {
                    wrapped.add(cur + ",");
                    cur = n;
                }
            }
            if (!cur.isEmpty()) {
                wrapped.add(cur);
            }
            int max = 4;
            for (int i = 0; i < wrapped.size() && i < max; i++) {
                String s = i == max - 1 && wrapped.size() > max ? String.join(" ", wrapped.subList(i, wrapped.size())) : wrapped.get(i);
                ink(g, fit(s, end - tx), tx, y + 2 + 12 * (i + 1), c);
            }
            h += 12 * Math.min(max, wrapped.size());
        } else {
            // the count is placed first and never cut: right after the age when all fits, else
            // right-aligned with the source and the age cut before it
            String count = L.t("wynnlabs.endgame.pool_new", d.poolMarked());
            String after = stale ? L.t("wynnlabs.endgame.pool_stale") : "";
            String[] left = {head, source, (age.isEmpty() ? "" : " · " + age) + " · "};
            int lw = 0;
            for (String s : left) {
                lw += font.width(s);
            }
            int cw = font.width(count) + font.width(after);
            int cx = Math.min(tx + lw, end - cw);
            ink(g, count, cx, y + 2, stale ? Theme.INK_3 : Theme.GOOD);
            if (!after.isEmpty()) {
                ink(g, after, cx + font.width(count), y + 2, Theme.INK_3);
            }
            segments(g, tx, y + 2, cx - tx, left, new int[] {c, Theme.INK_3, c});
        }
        Art.icon(g, "chevron-right", x + w - 10, y + 2, 8, c);
        List<Component> tip = new ArrayList<>();
        if (p.wynnventory()) {
            // every raid with its age and the class's aspects in it, then the week and the source
            tip.add(title(L.t("wynnlabs.endgame.tip_pool_head_week", p.week()), ChatFormatting.GOLD));
            // kept short so it fits on screen: a raid per line, its aspects side by side under it
            for (String raid : p.raids()) {
                long seen = p.seenOf(raid);
                List<PoolAspect> in = p.byRaid().getOrDefault(raid, List.of());
                tip.add(Component.literal(raid).withStyle(ChatFormatting.WHITE)
                        .append(Component.literal(seen < 0 ? "" : " · " + agoLong(seen)).withStyle(ChatFormatting.GRAY))
                        .append(in.isEmpty() ? dark(" · " + L.t("wynnlabs.endgame.tip_pool_raid_none")) : Component.empty()));
                MutableComponent line = Component.literal("  ");
                int lw = font.width("  ");
                int lines = 0;
                for (int i = 0; i < in.size(); i++) {
                    PoolAspect a = in.get(i);
                    String tg = tagText(a.name());
                    MutableComponent part = Component.literal(shortAspect(a.name())).withStyle(tierChat(a.rarity()));
                    if (tg != null) {
                        part.append(Component.literal(" " + tg).withStyle(ChatFormatting.GREEN));
                    }
                    String sep = i + 1 < in.size() ? ", " : "";
                    int pw = font.width(part.getString() + sep);
                    if (lw + pw > TIP_W && lw > font.width("  ")) {
                        if (++lines == 2) {
                            line.append(Component.literal("+" + (in.size() - i)).withStyle(ChatFormatting.GRAY));
                            break;
                        }
                        tip.add(line);
                        line = Component.literal("  ");
                        lw = font.width("  ");
                    }
                    line.append(part).append(Component.literal(sep).withStyle(ChatFormatting.DARK_GRAY));
                    lw += pw;
                }
                if (!in.isEmpty()) {
                    tip.add(line);
                }
            }
            if (stale) {
                tip.add(warn(L.t("wynnlabs.endgame.tip_week_stale")));
            }
            tip.add(dark(L.t("wynnlabs.endgame.tip_src_players")));
        } else {
            tip.add(title(L.t("wynnlabs.endgame.tip_pool_head", d.poolAge()), ChatFormatting.GOLD));
            for (PoolAspect a : d.pool()) {
                MutableComponent line = Component.literal("  " + a.name()).withStyle(tierChat(a.rarity()))
                        .append(Component.literal(" · " + L.t("wynnlabs.endgame.tier_n", a.tier())).withStyle(ChatFormatting.GRAY));
                String tg = tagText(a.name());
                if (tg != null) {
                    line.append(Component.literal(" " + tg).withStyle(ChatFormatting.GREEN));
                }
                tip.add(line);
            }
            if (stale) {
                tip.add(warn(L.t("wynnlabs.endgame.pool_stale_tip")));
            }
            tip.add(dark(L.t("wynnlabs.endgame.tip_src_game")));
            String why = reason(p.status());
            if (why != null) {
                tip.add(dark(why));
            }
        }
        tip.add(dark(L.t("wynnlabs.endgame.pool_click")));
        acts.add(new Act(x, y, w, h, () -> {
            filter = F_POOL;
            listScroll = 0;
            section = SEC_ASPECTS;
        }, "endgame:pool"));
        tips.add(new Tip(x, y, w, h, tip));
        return y + h;
    }

    // ---- recent runs ----

    /** Raids and lootruns in one feed, newest first, as many as fit, with the lootrun totals under it. */
    private void feed(GuiGraphics g, int x, int y, int w, int h, boolean large, int mx, int my) {
        Kit.section(g, font, L.t("wynnlabs.endgame.recent"), x, y, w);
        int ry = y + 11;
        int foot = y + h - 10;
        List<FeedItem> items = FeedItem.merge(d.raids(), d.lootruns(), -1);
        if (items.isEmpty()) {
            ink(g, fit(L.t("wynnlabs.endgame.recent_empty"), w), x, ry + 2, Theme.INK_3);
        }
        int rowH = large ? 22 : 12;
        int ageR = x + w;
        int pullsR = ageR - 24;
        int timeR = pullsR - (large ? 44 : 46);
        int markX = timeR - 38;
        int nameX = x + 1 + ROW_ICON + 3;
        int max = large ? 10 : 8;
        // this week's mythic camps take the row(s) above the totals
        int lines = d.p().camps().isEmpty() ? 0 : mythicLine(g, x, 0, w, large ? 2 : 1, false);
        int mythicY = foot - 12 * lines;
        for (int i = 0; i < items.size() && i < max && ry + rowH <= mythicY - 2; i++) {
            FeedItem f = items.get(i);
            boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + rowH;
            Kit.row(g, x, ry, w, rowH, i, hover);
            int ty = ry + 2;
            glyph(g, f.isRaid() ? TEX_RAID : TEX_CAMP, f.isRaid() ? "sword" : "map-pin", x + 1, ty - 1, ROW_ICON, Theme.INK_3);
            int nameW = markX - 4 - nameX;
            if (f.mythic() != null) {
                String mn = f.mythic();
                int mw = Math.min(font.width(mn), nameW / 2);
                String nf = fit(f.name(), nameW - mw - ROW_ICON - 6);
                ink(g, nf, nameX, ty, Theme.INK);
                int gx = nameX + font.width(nf) + 3;
                mythicGlyph(g, gx, ty - 1, ROW_ICON);
                ink(g, fit(mn, markX - 4 - gx - ROW_ICON - 2), gx + ROW_ICON + 2, ty, Theme.TIER_MYTHIC);
            } else {
                ink(g, fit(f.name(), nameW), nameX, ty, Theme.INK);
            }
            Art.icon(g, f.completed() ? "check" : "x", markX, ty, 8, f.completed() ? Theme.GOOD : Theme.BAD);
            right(g, time(f.durationMs()), timeR, ty, Theme.INK);
            if (!f.isRaid()) {
                right(g, f.pulls() < 0 ? "-" : L.n("wynnlabs.endgame.pulls_n", f.pulls()), pullsR, ty,
                        f.pulls() < 0 ? Theme.INK_3 : Theme.INK);
            }
            right(g, ago(f.endedAt()), ageR, ty, Theme.INK_3);
            if (large) {
                feedDetail(g, f, nameX, ry + 12, x + w - nameX);
            }
            tips.add(new Tip(x, ry, w, rowH, f.isRaid() ? raidRunTip(f.raid()) : lootrunTip(f.lootrun())));
            ry += rowH;
        }
        if (lines > 0) {
            mythicLine(g, x, mythicY, w, lines, true);
        }
        totalsLine(g, x, foot, w);
    }

    /**
     * This week's mythic camps from Wynnventory's loot pool: "◆ Mythics this week: Silent Expanse 3,
     * Corkus 2 +4". Camps are added whole while they fit (up to {@code maxLines} lines), then "+N"
     * for the rest; counts are never cut. Returns the lines it takes (when {@code draw} is false it
     * only measures).
     */
    private int mythicLine(GuiGraphics g, int x, int y, int w, int maxLines, boolean draw) {
        List<CampMythics> camps = d.p().camps();
        if (camps.isEmpty()) {
            return 0;
        }
        String label = L.t("wynnlabs.endgame.mythic_week") + " ";
        int end = x + w;
        int cx = x + ROW_ICON + 3 + font.width(label);
        int line = 0;
        int shown = 0;
        if (draw) {
            mythicGlyph(g, x, y - 1, ROW_ICON);
            ink(g, label, x + ROW_ICON + 3, y, Theme.INK_2);
        }
        for (int i = 0; i < camps.size(); i++) {
            CampMythics c = camps.get(i);
            String part = (shown > 0 ? ", " : "") + c.camp() + " " + c.count();
            int rest = camps.size() - i - 1;
            int plusW = rest > 0 ? font.width(" +" + rest) : 0;
            if (cx + font.width(part) + plusW > end) {
                if (line + 1 >= maxLines || shown == 0) {
                    break;
                }
                // the next line, under the label
                line++;
                cx = x + ROW_ICON + 3;
                part = c.camp() + " " + c.count();
                if (cx + font.width(part) + plusW > end) {
                    break;
                }
            }
            if (draw) {
                ink(g, part, cx, y + 12 * line, Theme.TIER_MYTHIC);
            }
            cx += font.width(part);
            shown++;
        }
        int rest = camps.size() - shown;
        if (rest > 0 && draw) {
            String plus = (shown > 0 ? " " : "") + "+" + rest;
            ink(g, fit(plus, end - cx), cx, y + 12 * line, Theme.INK_3);
        }
        if (draw) {
            int h = 12 * (line + 1) - 1;
            mythicRect = new int[] {x, y, w, h};
            String first = camps.get(0).camp();
            acts.add(new Act(x, y - 1, w, h, () -> openPools(TAB_CAMPS, first), "endgame:mythic"));
            tips.add(new Tip(x, y - 1, w, h, mythicTip()));
        }
        return line + 1;
    }

    /** The mythic line's tooltip: every camp with its count, age and mythics; the camps without one; the week and the source. */
    private List<Component> mythicTip() {
        Pools p = d.p();
        List<Component> t = new ArrayList<>();
        t.add(title(L.t("wynnlabs.endgame.tip_mythic_head", p.lootWeek()), ChatFormatting.LIGHT_PURPLE));
        for (CampMythics c : p.camps()) {
            t.add(Component.literal(c.camp() + " (" + c.count() + ")" + (c.seenAt() < 0 ? "" : " · " + agoLong(c.seenAt())) + ": ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(String.join(", ", c.mythics())).withStyle(ChatFormatting.LIGHT_PURPLE)));
        }
        if (!p.campsNone().isEmpty()) {
            t.add(dark(L.t("wynnlabs.endgame.tip_mythic_none", String.join(", ", p.campsNone()))));
        }
        if (p.lootStale()) {
            t.add(warn(L.t("wynnlabs.endgame.tip_week_stale")));
        }
        t.add(dark(L.t("wynnlabs.endgame.tip_src_players")));
        t.add(dark(L.t("wynnlabs.endgame.link_pools")));
        return t;
    }

    /** The lootrun totals' tooltip, with the one-line hint on this week's mythics when there is no Wynnventory data. */
    private List<Component> totalsTip(LootrunTotals t) {
        List<Component> out = new ArrayList<>();
        out.add(title(L.t("wynnlabs.endgame.lootrun"), ChatFormatting.GOLD));
        out.add(grey(L.n("wynnlabs.endgame.tip_totals", t.runs(), t.runs(), t.completed(), t.pulls())));
        if (!d.p().loot()) {
            out.add(dark(L.t(d.p().status() == WynnventoryPools.Status.OFF ? "wynnlabs.endgame.mythic_hint_off"
                    : "wynnlabs.endgame.mythic_hint_wait")));
        }
        return out;
    }

    /** A large feed row's second line: a lootrun's challenges and beacon colours, a raid's rooms. */
    private void feedDetail(GuiGraphics g, FeedItem f, int x, int y, int w) {
        if (f.isRaid()) {
            ink(g, fit(L.n("wynnlabs.endgame.rooms_n", f.raid().rooms().size()), w), x, y, Theme.INK_3);
            return;
        }
        LootrunRun r = f.lootrun();
        String ch = L.n("wynnlabs.endgame.challenges_n", r.challenges());
        String cf = fit(ch, w / 2);
        ink(g, cf, x, y, Theme.INK_3);
        int bx = x + font.width(cf) + 6;
        for (Map.Entry<String, Integer> b : r.beacons().entrySet()) {
            String n = String.valueOf(b.getValue());
            int need = 7 + font.width(n) + 4;
            if (bx + need > x + w) {
                break;
            }
            g.fill(bx, y + 1, bx + 6, y + 7, beaconColour(b.getKey()));
            Kit.outline(g, bx, y + 1, 6, 6, 0x66000000);
            ink(g, n, bx + 8, y, Theme.INK_3);
            bx += need;
        }
    }

    /** The lootrun totals under the feed. */
    private void totalsLine(GuiGraphics g, int x, int y, int w) {
        if (!d.lootUp()) {
            ink(g, fit(L.t("wynnlabs.endgame.lootruns_off"), w), x, y, Theme.WARN);
            return;
        }
        LootrunTotals t = d.totals();
        if (t.runs() <= 0) {
            ink(g, fit(L.t("wynnlabs.endgame.lootrun_empty"), w), x, y, Theme.INK_3);
            return;
        }
        ink(g, fit(L.n("wynnlabs.endgame.totals", t.runs(), t.runs(), Math.max(0, t.completionPct()),
                t.pullsPerCompleted() < 0 ? "-" : String.valueOf(Math.round(t.pullsPerCompleted()))), w), x, y, Theme.INK_3);
        tips.add(new Tip(x, y - 1, w, 11, totalsTip(t)));
    }

    /** The compact lootrun section: the last lootruns with time and pulls, then the totals. */
    private void compactLootruns(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        int pullsR = x + w;
        int timeR = pullsR - 50;
        int markX = timeR - 46;
        Kit.section(g, font, L.t("wynnlabs.endgame.recent_lootruns"), x, y, markX - 8 - x);
        right(g, L.t("wynnlabs.endgame.col_time"), timeR, y, Theme.INK_3);
        right(g, L.t("wynnlabs.endgame.col_pulls"), pullsR, y, Theme.INK_3);
        int ry = y + 11;
        int foot = y + h - 10;
        if (d.lootruns().isEmpty()) {
            ink(g, fit(L.t("wynnlabs.endgame.lootrun_empty"), w), x, ry + 2, Theme.INK_3);
        }
        int rowH = 12;
        for (int i = 0; i < d.lootruns().size() && ry + rowH <= foot - 2; i++) {
            LootrunRun r = d.lootruns().get(i);
            boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + rowH;
            Kit.row(g, x, ry, w, rowH, i, hover);
            int ty = ry + 2;
            int nameW = markX - 6 - x;
            if (r.mythic() != null) {
                int mw = Math.min(font.width(r.mythic()), nameW / 2);
                String nf = fit(r.camp(), nameW - mw - ROW_ICON - 6);
                ink(g, nf, x + 2, ty, Theme.INK);
                int gx = x + 2 + font.width(nf) + 3;
                mythicGlyph(g, gx, ty - 1, ROW_ICON);
                ink(g, fit(r.mythic(), markX - 4 - gx - ROW_ICON - 2), gx + ROW_ICON + 2, ty, Theme.TIER_MYTHIC);
            } else {
                ink(g, fit(r.camp(), nameW), x + 2, ty, Theme.INK);
            }
            Art.icon(g, r.completed() ? "check" : "x", markX, ty, 8, r.completed() ? Theme.GOOD : Theme.BAD);
            right(g, time(r.durationS() < 0 ? -1 : r.durationS() * 1000L), timeR, ty, Theme.INK);
            right(g, r.completed() ? String.valueOf(r.pulls()) : "-", pullsR, ty, r.completed() ? Theme.INK : Theme.INK_3);
            tips.add(new Tip(x, ry, w, rowH, lootrunTip(r)));
            ry += rowH;
        }
        String t = "";
        LootrunTotals tt = d.totals();
        if (d.lootUp() && tt.runs() > 0) {
            t = L.n("wynnlabs.endgame.totals_short", tt.runs(), tt.runs(), Math.max(0, tt.completionPct()),
                    tt.pullsPerCompleted() < 0 ? "-" : String.valueOf(Math.round(tt.pullsPerCompleted())));
            ink(g, fit(t, w), x, foot, Theme.INK_3);
            tips.add(new Tip(x, foot - 1, w, 11, totalsTip(tt)));
        }
    }

    private List<Component> raidRunTip(RaidRun r) {
        List<Component> t = new ArrayList<>();
        t.add(title(r.raid(), ChatFormatting.GOLD));
        t.add(r.completed() ? good(L.t("wynnlabs.endgame.tip_done_in", time(r.durationMs())))
                : warn(L.t("wynnlabs.endgame.tip_failed_after", time(r.durationMs()))));
        for (RaidRun.Room room : r.rooms()) {
            t.add(grey("  " + room.name() + ": " + time(room.ms())));
        }
        t.add(dark(agoLong(r.endedAt())));
        return t;
    }

    private List<Component> lootrunTip(LootrunRun r) {
        List<Component> t = new ArrayList<>();
        t.add(title(r.camp(), ChatFormatting.GOLD));
        long ms = r.durationS() < 0 ? -1 : r.durationS() * 1000L;
        t.add(r.completed() ? good(L.t("wynnlabs.endgame.tip_done_in", time(ms))) : warn(L.t("wynnlabs.endgame.tip_failed_after", time(ms))));
        t.add(grey(L.t("wynnlabs.endgame.tip_loot_counts", r.challenges(), r.pulls(), r.rerolls(), r.sacrifices())));
        if (r.xp() > 0) {
            t.add(grey(L.t("wynnlabs.endgame.tip_loot_xp", r.xp())));
        }
        if (!r.beacons().isEmpty()) {
            List<String> parts = new ArrayList<>();
            r.beacons().forEach((k, v) -> parts.add(k + " " + v));
            t.add(grey(L.t("wynnlabs.endgame.tip_beacons", String.join(", ", parts))));
        }
        if (!r.missions().isEmpty()) {
            t.add(grey(L.t("wynnlabs.endgame.tip_missions", String.join(", ", r.missions()))));
        }
        if (r.mythic() != null) {
            t.add(Component.literal(L.t("wynnlabs.endgame.tip_mythic") + " ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(r.mythic()).withStyle(ChatFormatting.LIGHT_PURPLE)));
        }
        t.add(dark(agoLong(r.endedAt())));
        return t;
    }

    // ---- aspects ----

    /** The aspect list: header, filter chips, ranked rows with their own scroll, and the hint. */
    private void aspects(GuiGraphics g, int x, int y, int w, int h, boolean large, boolean compact, int mx, int my) {
        int bottom = y + h;
        int ry = y;
        List<RankedAspect> all = d.ranked();
        int total = all.size();
        // the header: title, then class · archetype · owned/total on the right
        String count = (d.ownedCount() < 0 ? "?" : String.valueOf(d.ownedCount())) + "/" + total;
        String arch = d.archetype() == null ? "" : d.archetype() + (d.guessed() ? "?" : "");
        String who = (compact ? "" : nice(d.klass()) + (arch.isEmpty() ? "" : " · ")) + arch;
        String rt = (who.isEmpty() ? "" : who + " · ") + count;
        if (!compact) {
            // the room right of the title: the class, then the archetype give way before the
            // count is ever cut (the class shows in the icon and the tooltip)
            int room = w - (font.width(L.t("wynnlabs.endgame.aspects")) + 3 + 5 + 12) - 16;
            if (font.width(rt) > room && !arch.isEmpty()) {
                rt = arch + " · " + count;
            }
            if (font.width(rt) > room) {
                rt = count;
            }
            String rf = fit(rt, room);
            int rtx = x + w - font.width(rf);
            boolean icon = d.klass() != Klass.NONE && WynnTex.classIcon(g, d.klass().name(), rtx - 12, y - 1, 10);
            ink(g, rf, rtx, y, Theme.INK_3);
            Kit.section(g, font, L.t("wynnlabs.endgame.aspects"), x, y, rtx - (icon ? 16 : 6) - x);
            tips.add(new Tip(rtx, y - 1, x + w - rtx, 10, headerTip()));
            ry += 12;
        }
        if (!d.aspectsUp()) {
            ink(g, fit(L.t("wynnlabs.endgame.aspects_off"), w), x, ry + 2, Theme.WARN);
            ry += 13;
        }
        if (!d.dataReady() || total == 0) {
            ink(g, fit(L.t("wynnlabs.endgame.aspects_no_data"), w), x, ry + 2, Theme.INK_3);
            return;
        }
        // the filter chips (or, before the Aspects menu was read, the order they are in)
        int missing = 0;
        int owned = 0;
        int inPool = 0;
        for (RankedAspect a : all) {
            missing += a.ownedTier() == 0 ? 1 : 0;
            owned += a.ownedTier() > 0 ? 1 : 0;
            inPool += RaidPool.holds(a.aspect().name()) ? 1 : 0;
        }
        if (!d.known()) {
            filter = filter == F_POOL ? F_POOL : F_ALL;
        }
        String compactRight = compact ? rt : "";
        int crw = compact ? font.width(compactRight) + 6 : 0;
        if (d.known()) {
            String[] names = {L.t("wynnlabs.endgame.f_all", total), L.t("wynnlabs.endgame.f_missing", missing),
                L.t("wynnlabs.endgame.f_owned", owned), L.t("wynnlabs.endgame.f_pool", inPool)};
            int cx = x;
            for (int i = 0; i < names.length; i++) {
                int bw = Kit.Ui.btnW(font, names[i]);
                if (cx + bw > x + w - crw) {
                    break;
                }
                boolean on = filter == i;
                boolean hover = mx >= cx && mx < cx + bw && my >= ry && my < ry + 14;
                int state = on ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
                Kit.button(g, cx, ry, bw, 14, state);
                g.drawString(font, names[i], cx + Kit.Ui.BTN_PAD, ry + 3, Kit.buttonText(state), state != Kit.ON);
                int f = i;
                acts.add(new Act(cx, ry, bw, 14, () -> {
                    filter = f;
                    listScroll = 0;
                }, "endgame:filter" + i));
                cx += bw + 3;
            }
        } else {
            ink(g, fit(L.t("wynnlabs.endgame.order_fit"), w - crw), x, ry + 3, Theme.INK_3);
        }
        if (compact) {
            ink(g, compactRight, x + w - font.width(compactRight), ry + 3, Theme.INK_3);
            tips.add(new Tip(x + w - font.width(compactRight), ry, font.width(compactRight), 14, headerTip()));
        }
        ry += 17;
        if (d.guessed() && d.archetype() != null) {
            // no archetype chosen: the list says which one it ranks for
            ink(g, fit(L.t("wynnlabs.endgame.arch_guessed", d.archetype()), w), x, ry, Theme.INK_3);
            tips.add(new Tip(x, ry - 1, w, 11, headerTip()));
            ry += 12;
        }
        // the rows (the pool filter with Wynnventory's pool: grouped under each raid holding them)
        List<Line> rows = filter == F_POOL && d.p().wynnventory() ? poolGroups(all) : new ArrayList<>();
        if (!(filter == F_POOL && d.p().wynnventory())) {
            for (RankedAspect a : all) {
                boolean keep = switch (filter) {
                    case F_MISSING -> a.ownedTier() == 0;
                    case F_OWNED -> a.ownedTier() > 0;
                    case F_POOL -> RaidPool.holds(a.aspect().name());
                    default -> true;
                };
                if (keep) {
                    rows.add(new Line(null, a, rows.size(), 0));
                }
            }
        }
        int hintH = compact ? 0 : 12;
        int listTop = ry;
        int listBottom = bottom - hintH;
        int listW = w - 6;
        listRect = new int[] {x, listTop, w, listBottom - listTop};
        if (rows.isEmpty()) {
            ink(g, fit(L.t(filter == F_POOL && d.poolSeen() < 0 && !d.p().wynnventory() ? "wynnlabs.endgame.pool_never"
                    : "wynnlabs.endgame.list_empty"), w), x, ry + 2, Theme.INK_3);
        }
        // how far the list can scroll: the first row from which the rest fits (headers scroll with it)
        int fitH = 0;
        int from = rows.size();
        while (from > 0 && fitH + rowH(rows.get(from - 1), large) <= listBottom - listTop) {
            fitH += rowH(rows.get(from - 1), large);
            from--;
        }
        listScrollMax = from;
        listScroll = Math.max(0, Math.min(listScroll, listScrollMax));
        int shown = 0;
        for (int i = listScroll; i < rows.size(); i++) {
            Line l = rows.get(i);
            int rh = rowH(l, large);
            if (ry + rh > listBottom) {
                break;
            }
            if (l.aspect() == null) {
                raidHeader(g, l, x, ry, listW, rh);
            } else {
                aspectRow(g, l.aspect(), l.index(), x, ry, listW, rh, large, mx, my);
            }
            ry += rh;
            shown++;
        }
        if (listScrollMax > 0) {
            int trackH = listBottom - listTop;
            int knobH = Math.max(12, trackH * shown / Math.max(1, rows.size()));
            int knobY = listTop + (trackH - knobH) * listScroll / listScrollMax;
            g.fill(x + w - 3, listTop, x + w - 1, listBottom, 0x30624E3C);
            g.fill(x + w - 3, knobY, x + w - 1, knobY + knobH, Theme.WOOD);
        }
        if (!compact) {
            ink(g, fit(L.t("wynnlabs.screen.hint_endgame"), w), x, bottom - 9, Theme.INK_3);
        }
    }

    private String nice(Klass k) {
        if (k == null || k == Klass.NONE) {
            return "";
        }
        String n = k.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private List<Component> headerTip() {
        List<Component> t = new ArrayList<>();
        t.add(title(L.t("wynnlabs.endgame.aspects"), ChatFormatting.AQUA));
        if (d.archetype() != null) {
            t.add(grey(L.t(d.guessed() ? "wynnlabs.endgame.tip_arch_guessed" : "wynnlabs.endgame.tip_arch", d.archetype())));
        } else {
            t.add(grey(L.t("wynnlabs.endgame.tip_arch_none")));
        }
        if (d.ownedCount() >= 0) {
            t.add(grey(L.t("wynnlabs.endgame.tip_owned_seen", d.ownedCount(), d.ranked().size())));
        } else {
            t.add(grey(L.t("wynnlabs.endgame.aspects_unread_tip")));
        }
        t.add(dark(L.t("wynnlabs.endgame.tip_account_aspects")));
        return t;
    }

    private int rowH(RankedAspect a, boolean large) {
        return large && a.rank() > 0 ? 22 : 14;
    }

    private int rowH(Line l, boolean large) {
        return l.aspect() == null ? 12 : rowH(l.aspect(), large);
    }

    /**
     * One line of the aspect list: an aspect row, or (aspect null) a raid's header in the grouped
     * pool filter with its count of new or tier-up aspects.
     */
    private record Line(String raid, RankedAspect aspect, int index, int marked) {}

    /**
     * The pool filter with Wynnventory's pool: the class's aspects grouped under each raid holding
     * them, in rank order. Groups go by their best-ranked marked aspect; groups with nothing marked
     * come last. An aspect held by two raids is listed under both.
     */
    private List<Line> poolGroups(List<RankedAspect> all) {
        Pools p = d.p();
        record Group(String raid, List<RankedAspect> members, int best, int marked) {}
        List<Group> groups = new ArrayList<>();
        for (String raid : p.raids()) {
            List<String> names = new ArrayList<>();
            p.byRaid().getOrDefault(raid, List.of()).forEach(a -> names.add(a.name()));
            List<RankedAspect> members = new ArrayList<>();
            int best = Integer.MAX_VALUE;
            int marked = 0;
            for (int i = 0; i < all.size(); i++) {
                RankedAspect a = all.get(i);
                if (!names.contains(a.aspect().name())) {
                    continue;
                }
                members.add(a);
                if (RaidPool.markOf(a.aspect().name()) != RaidPool.Mark.NONE) {
                    marked++;
                    best = Math.min(best, i);
                }
            }
            if (!members.isEmpty()) {
                groups.add(new Group(raid, members, best, marked));
            }
        }
        groups.sort(java.util.Comparator.comparingInt(Group::best));     // stable: raid order on ties
        List<Line> out = new ArrayList<>();
        for (Group gr : groups) {
            out.add(new Line(gr.raid(), null, 0, gr.marked()));
            int k = 0;
            for (RankedAspect a : gr.members()) {
                out.add(new Line(gr.raid(), a, k++, 0));
            }
        }
        return out;
    }

    /** A raid's header in the grouped pool list: its glyph, full name, "3 new · 3h" on the right. */
    private void raidHeader(GuiGraphics g, Line l, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, Theme.WELL);
        int ty = y + 2;
        glyph(g, TEX_RAID, "sword", x + 3, y + 1, ROW_ICON, Theme.INK_2);
        long seen = d.p().seenOf(l.raid());
        String age = seen < 0 ? "" : ago(seen);
        String count = l.marked() > 0 ? L.t("wynnlabs.endgame.pool_new", l.marked()) : "";
        String sep = !count.isEmpty() && !age.isEmpty() ? " · " : "";
        int rw = font.width(count) + font.width(sep) + font.width(age);
        int rx = x + w - 3 - rw;
        if (!count.isEmpty()) {
            ink(g, count, rx, ty, Theme.GOOD);
        }
        if (!age.isEmpty()) {
            ink(g, sep + age, rx + font.width(count), ty, Theme.INK_3);
        }
        int nx = x + 3 + ROW_ICON + 3;
        ink(g, fit(l.raid(), rx - 6 - nx), nx, ty, Theme.INK_2);
        List<Component> tip = new ArrayList<>();
        tip.add(title(l.raid(), ChatFormatting.GOLD));
        tip.add(grey(L.t("wynnlabs.endgame.tip_group_marked", l.marked(), d.p().byRaid().getOrDefault(l.raid(), List.of()).size())));
        if (seen >= 0) {
            tip.add(grey(L.t("wynnlabs.endgame.tip_group_seen", agoLong(seen))));
        }
        tip.add(dark(L.t("wynnlabs.endgame.tip_src_week", d.p().week())));
        tip.add(dark(L.t("wynnlabs.endgame.link_pools")));
        String raid = l.raid();
        acts.add(new Act(x, y, w, h, () -> openPools(TAB_RAIDS, raid), "endgame:raidlink:" + raid));
        tips.add(new Tip(x, y, w, h, tip));
    }

    /** One aspect row: rank, rarity glyph, name, pool tag, equipped dot and tier pips; a click shows it in the card. */
    private void aspectRow(GuiGraphics g, RankedAspect a, int index, int x, int y, int w, int h, boolean large, int mx, int my) {
        String name = a.aspect().name();
        boolean lit = name.equals(pinned);
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        Kit.row(g, x, y, w, h, index, lit || hover);
        if (lit) {
            g.fill(x + 2, y, x + w, y + h, 0x60F6E3A8);
        }
        int ty = y + 3;
        int colour = tierColour(a.aspect().rarity());
        if (a.rank() > 0) {
            String n = String.valueOf(a.rank());
            int bw = Math.max(10, font.width(n) + 3);
            g.fill(x + 3, y + 2, x + 3 + bw, y + 12, Theme.BRASS);
            Kit.text(g, font, n, x + 3 + (bw - font.width(n) + 1) / 2, ty, Theme.INK);
        }
        aspectGlyph(g, a.aspect(), x + 16, y + 1, 12);
        // from the right: pips, the equipped dot, the pool tag; the name gets what is left
        int pw = a.ownedTier() < 0 ? font.width("?") : pipsW(a.aspect().tiers());
        int px = x + w - 2 - pw;
        if (a.ownedTier() < 0) {
            Kit.text(g, font, "?", px, ty, Theme.INK_3);
        } else {
            pips(g, px, y + 4, a.aspect().tiers(), a.ownedTier(), colour);
        }
        int rx = px - 4;
        if (a.equipped()) {
            g.fill(rx - 4, y + 5, rx, y + 9, Theme.GOLD_DEEP);
            Kit.outline(g, rx - 5, y + 4, 6, 6, Theme.GOLD_EDGE);
            rx -= 9;
        }
        String tg = tagText(name);
        if (tg != null) {
            int tw = Kit.Ui.tagW(font, tg);
            tag(g, tg, rx - tw, y + 2);
            rx -= tw + 4;
        }
        int nx = x + 31;                                // 3 px clear of the 12 px icon
        Kit.text(g, font, lit ? boldFit(name, rx - nx) : Component.literal(fit(name, rx - nx)), nx, ty, colour);
        if (large && h > 14) {
            ink(g, fit(a.reason(), x + w - 2 - nx), nx, y + 13, Theme.INK_3);
        }
        acts.add(new Act(x, y, w, h, () -> pinned = name, name));
        tips.add(new Tip(x, y, w, h, aspectTip(a)));
    }

    /** An aspect's tooltip: rarity and owned tier, every tier's copies, the abilities behind its rank, the pool. */
    private List<Component> aspectTip(RankedAspect a) {
        AspectEntry e = a.aspect();
        List<Component> t = new ArrayList<>();
        t.add(Component.literal(e.name()).withStyle(tierChat(e.rarity())).withStyle(ChatFormatting.BOLD));
        String rarity = rarityName(e.rarity());
        t.add(grey(a.ownedTier() < 0 ? L.t("wynnlabs.endgame.tip_rarity_unknown", rarity, e.tiers())
                : a.ownedTier() == 0 ? L.t("wynnlabs.endgame.tip_rarity_missing", rarity, e.tiers())
                : L.t("wynnlabs.endgame.tip_rarity_owned", rarity, a.ownedTier(), e.tiers())));
        if (!e.thresholds().isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < e.thresholds().size(); i++) {
                parts.add(L.t("wynnlabs.endgame.tier_n", i + 1) + ": " + e.thresholds().get(i));
            }
            t.add(grey(L.t("wynnlabs.endgame.tip_copies", String.join(" · ", parts))));
        }
        if (!a.matches().isEmpty()) {
            t.add(Component.literal(L.t("wynnlabs.endgame.tip_boosts")).withStyle(ChatFormatting.WHITE));
            for (RankedAspect.Match m : a.matches()) {
                String state = L.t(m.unlocked() ? "wynnlabs.endgame.m_unlocked" : m.planned() ? "wynnlabs.endgame.m_planned"
                        : "wynnlabs.endgame.m_none");
                String what = m.archetype() == null ? state : m.archetype() + ", " + state;
                t.add(grey("  " + m.ability() + " (" + what + ")"));
            }
        } else if (!e.abilities().isEmpty()) {
            t.add(grey(L.t("wynnlabs.endgame.tip_names", String.join(", ", e.abilities()))));
        } else {
            t.add(grey(L.t("wynnlabs.endgame.reason_none")));
        }
        if (a.equipped()) {
            t.add(Component.literal(L.t("wynnlabs.endgame.tip_equipped")).withStyle(ChatFormatting.GOLD));
        }
        if (d.p().wynnventory()) {
            // the raids holding it this week, each with its age, then the source
            List<String> held = RaidPool.raidsHolding(e.name());
            if (held == null || held.isEmpty()) {
                t.add(grey(L.t("wynnlabs.endgame.tip_not_in_pool")));
            } else {
                t.add(good(L.t("wynnlabs.endgame.tip_this_week")));
                for (String raid : held) {
                    long seen = d.p().seenOf(raid);
                    t.add(grey("  " + raid + (seen < 0 ? "" : " · " + agoLong(seen))));
                }
            }
            t.add(dark(L.t("wynnlabs.endgame.tip_src_week", d.p().week())));
            return t;
        }
        for (PoolAspect p : d.pool()) {
            if (p.name().equals(e.name())) {
                t.add(Component.literal(L.t("wynnlabs.endgame.tip_in_pool", p.tier(), d.poolAge())).withStyle(ChatFormatting.GREEN));
            }
        }
        t.add(dark(L.t("wynnlabs.endgame.tip_source")));
        return t;
    }

    // =====================================================================================
    // small pieces

    private int pipsW(int tiers) {
        return Math.max(0, tiers * 7 - 2);
    }

    /** One pip per tier: filled in the tier colour up to the owned tier, empty ones in the well colour. */
    private void pips(GuiGraphics g, int x, int y, int tiers, int owned, int colour) {
        for (int i = 0; i < tiers; i++) {
            int px = x + i * 7;
            if (i < owned) {
                g.fill(px, y, px + 5, y + 5, colour);
            } else {
                g.fill(px, y, px + 5, y + 5, Theme.WELL);
                Kit.outline(g, px, y, 5, 5, Theme.LINE);
            }
        }
    }

    /** "NEW" or "+T2" (the tier the tag names, {@link RaidPool#tagTier}) for an aspect's mark in the pool shown; null for none. */
    private String tagText(String aspect) {
        RaidPool.Mark m = RaidPool.markOf(aspect);
        if (m == RaidPool.Mark.NEW) {
            return L.t("wynnlabs.endgame.tag_new");
        }
        if (m == RaidPool.Mark.TIER_UP) {
            int tier = RaidPool.tagTier(aspect);
            return tier > 0 ? L.t("wynnlabs.endgame.tag_tier_up", tier) : null;
        }
        return null;
    }

    /** A small green tag with light text. */
    private void tag(GuiGraphics g, String text, int x, int y) {
        int w = Kit.Ui.tagW(font, text);
        Art.pill(g, x, y, w, 10, Theme.GOOD, 0);
        Kit.text(g, font, text, x + Kit.Ui.TAG_PAD, y + 1, Theme.CARD);
    }

    /** A pack glyph without its empty margin (the content book's 32 px raid glyph is 19 px of it), else an icon. */
    private void glyph(GuiGraphics g, String tex, String fallback, int x, int y, int size, int colour) {
        if (!WynnTex.drawTrimmed(g, tex, x, y, size)) {
            Art.icon(g, fallback, x, y, size, colour);
        }
    }

    private void mythicGlyph(GuiGraphics g, int x, int y, int size) {
        glyph(g, TEX_LOOT + "mythic", "diamond", x, y, size, Theme.TIER_MYTHIC);
    }

    /**
     * An aspect's own picture, drawn the way the Pools view draws it (the game's aspect item for
     * its class, then the pack texture, then a glyph), so the list and the pools look alike.
     */
    private void aspectGlyph(GuiGraphics g, AspectEntry aspect, int x, int y, int size) {
        Klass k = aspect.klass() == null ? Klass.NONE : aspect.klass();
        if (k == Klass.NONE) {
            rarityGlyph(g, aspect.rarity(), x, y, size);
            return;
        }
        String subtype = k.name().charAt(0) + k.name().substring(1).toLowerCase(Locale.ROOT) + "Aspect";
        ItemIcons.draw(g, new PoolItem(aspect.name(), "AspectItem", aspect.rarity(), subtype, 1), k, x, y, size, false);
    }

    private void rarityGlyph(GuiGraphics g, Tier rarity, int x, int y, int size) {
        String r = rarity == null ? "legendary" : rarity.name().toLowerCase(Locale.ROOT);
        glyph(g, TEX_LOOT + r, "diamond", x, y, size, tierColour(rarity));
    }

    private static int tierColour(Tier t) {
        if (t == null) {
            return Theme.INK;
        }
        return switch (t) {
            case MYTHIC -> Theme.TIER_MYTHIC;
            case FABLED -> Theme.TIER_FABLED;
            case LEGENDARY -> Theme.TIER_LEGENDARY;
            case RARE -> Theme.TIER_RARE;
            case UNIQUE -> Theme.TIER_UNIQUE;
            case SET -> Theme.TIER_SET;
            default -> Theme.INK;
        };
    }

    private static ChatFormatting tierChat(Tier t) {
        if (t == null) {
            return ChatFormatting.WHITE;
        }
        return switch (t) {
            case MYTHIC -> ChatFormatting.DARK_PURPLE;
            case FABLED -> ChatFormatting.RED;
            case LEGENDARY -> ChatFormatting.AQUA;
            default -> t.colour();
        };
    }

    private static String rarityName(Tier t) {
        if (t == null) {
            return "";
        }
        String n = t.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** A beacon colour's mark (English names as Wynntils gives them). */
    private static int beaconColour(String name) {
        return switch (name == null ? "" : name.toLowerCase(Locale.ROOT)) {
            case "blue" -> 0xFF3A6FD0;
            case "purple" -> 0xFF8A4AC8;
            case "yellow" -> 0xFFE0C030;
            case "rainbow" -> 0xFFE070B0;
            case "red" -> 0xFFC83A30;
            case "green" -> 0xFF4AA040;
            case "orange" -> 0xFFE08A30;
            case "aqua" -> 0xFF40C0C8;
            case "gray", "grey" -> 0xFF9A9A9A;
            case "dark gray", "dark grey" -> 0xFF555555;
            case "white" -> 0xFFF0F0F0;
            case "crimson" -> 0xFF8A1A2A;
            default -> Theme.INK_3;
        };
    }

    private void ink(GuiGraphics g, String s, int x, int y, int colour) {
        Kit.text(g, font, s, x, y, colour);
    }

    /** Pieces of one line, each in its own colour, cut with "…" where the line runs out of {@code w}. */
    private void segments(GuiGraphics g, int x, int y, int w, String[] parts, int[] colours) {
        int cx = x;
        int end = x + w;
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i];
            if (s == null || s.isEmpty()) {
                continue;
            }
            int sw = font.width(s);
            if (cx + sw <= end) {
                ink(g, s, cx, y, colours[i]);
                cx += sw;
                continue;
            }
            // the line runs out here: this piece is the last one, cut
            String f = fit(s, end - cx);
            if (!f.isEmpty()) {
                ink(g, f, cx, y, colours[i]);
            }
            return;
        }
    }

    /** Why the in-game pool is shown instead of Wynnventory's, or null when the gate is not the reason. */
    private static String reason(WynnventoryPools.Status s) {
        if (s == null) {
            return null;
        }
        return switch (s) {
            case OFF -> L.t("wynnlabs.endgame.reason_off");
            case NO_MOD -> L.t("wynnlabs.endgame.reason_no_mod");
            case NO_KEY -> L.t("wynnlabs.endgame.reason_no_key");
            case KEY_REFUSED -> L.t("wynnlabs.endgame.reason_refused");
            case FAILED -> L.t("wynnlabs.endgame.reason_failed");
            default -> null;
        };
    }

    /** Text right-aligned to {@code right}. */
    private void right(GuiGraphics g, String s, int right, int y, int colour) {
        Kit.text(g, font, s, right - font.width(s), y, colour);
    }

    private static MutableComponent bold(String s) {
        return Component.literal(s).withStyle(UiFont::bold);
    }

    private static Component title(String s, ChatFormatting f) {
        return Component.literal(s).withStyle(f);
    }

    private static Component grey(String s) {
        return Component.literal(s).withStyle(ChatFormatting.GRAY);
    }

    private static Component dark(String s) {
        return Component.literal(s).withStyle(ChatFormatting.DARK_GRAY);
    }

    private static Component warn(String s) {
        return Component.literal(s).withStyle(ChatFormatting.GOLD);
    }

    private static Component good(String s) {
        return Component.literal(s).withStyle(ChatFormatting.GREEN);
    }

    private String fit(String s, int w) {
        if (s == null || w <= 0) {
            return "";
        }
        if (font.width(s) <= w) {
            return s;
        }
        return font.plainSubstrByWidth(s, Math.max(0, w - font.width("…"))) + "…";
    }

    /**
     * {@code s} in bold, cut with "…" to {@code w} measured in bold: the game's bold is a pixel
     * wider per letter (the web font's bold face differs too), so a cut measured plain runs over.
     */
    private MutableComponent boldFit(String s, int w) {
        if (s == null || w <= 0) {
            return Component.empty();
        }
        MutableComponent whole = bold(s);
        if (font.width(whole) <= w) {
            return whole;
        }
        String cut = font.substrByWidth(whole, Math.max(0, w - font.width(bold("…")))).getString();
        return bold(cut.stripTrailing() + "…");
    }

    /**
     * {@code s} in bold over at most {@code maxLines} lines of {@code w}, broken between words and
     * measured in bold; the last line is cut with "…" when the rest does not fit.
     */
    private List<MutableComponent> boldLines(String s, int w, int maxLines) {
        List<MutableComponent> out = new ArrayList<>();
        if (s == null || s.isEmpty() || w <= 0) {
            return out;
        }
        String rest = s;
        while (out.size() < maxLines - 1 && font.width(bold(rest)) > w) {
            String[] words = rest.split(" ");
            String line = "";
            int used = 0;
            for (String word : words) {
                String next = line.isEmpty() ? word : line + " " + word;
                if (!line.isEmpty() && font.width(bold(next)) > w) {
                    break;
                }
                line = next;
                used++;
            }
            if (used >= words.length || font.width(bold(line)) > w) {
                break;                                  // one word wider than the line: cut it instead
            }
            out.add(bold(line));
            rest = String.join(" ", java.util.Arrays.copyOfRange(words, used, words.length));
        }
        out.add(boldFit(rest, w));
        return out;
    }

    private static String num(int v) {
        return v < 0 ? "?" : String.valueOf(v);
    }

    /** A time as m:ss (h:mm:ss from an hour), "--" when unknown. */
    static String time(long ms) {
        if (ms < 0) {
            return "--";
        }
        long s = ms / 1000;
        if (s >= 3600) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** How long ago, short ("2h", "1d") for a column. */
    private String ago(long at) {
        long d = Math.max(0, now - at);
        if (d < HOUR) {
            return L.t("wynnlabs.endgame.ago_m", Math.max(1, d / MIN));
        }
        if (d < DAY) {
            return L.t("wynnlabs.endgame.ago_h", d / HOUR);
        }
        return L.t("wynnlabs.endgame.ago_d", d / DAY);
    }

    /** How long ago, in words ("2 h ago"). */
    private String agoLong(long at) {
        long d = Math.max(0, now - at);
        if (d < HOUR) {
            return L.t("wynnlabs.screen.ago_min", Math.max(1, d / MIN));
        }
        if (d < DAY) {
            return L.t("wynnlabs.screen.ago_hours", d / HOUR);
        }
        return L.n("wynnlabs.screen.ago_days", d / DAY);
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
        int maxW = Math.max(160, Math.min(260, screenW / 2));
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component c : tip) {
            List<FormattedCharSequence> part = font.split(c, maxW);
            lines.addAll(part.isEmpty() ? List.of(FormattedCharSequence.EMPTY) : part);
        }
        g.setTooltipForNextFrame(font, lines, mx, my);
    }

    // =====================================================================================
    // input

    /** A click on the page: true when the tab used it (section card, filter chip, pool line, aspect row, "↺"). */
    boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) {
            return false;
        }
        for (int i = acts.size() - 1; i >= 0; i--) {
            Act a = acts.get(i);
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
            // each column, the compact list and the region list scroll on their own
            for (Wheel wl : wheels) {
                if (wl.in(mx, my)) {
                    if (wl.max() <= 0) {
                        return false;
                    }
                    int[] sc = poolScroll[poolTab];
                    sc[wl.slot()] = Math.max(0, Math.min(wl.max(), sc[wl.slot()] - (int) Math.signum(v)));
                    return true;
                }
            }
            return false;
        }
        int[] r = listRect;
        if (r == null || mx < r[0] || mx >= r[0] + r[2] || my < r[1] || my >= r[1] + r[3] || listScrollMax <= 0) {
            return false;
        }
        listScroll = Math.max(0, Math.min(listScrollMax, listScroll - (int) Math.signum(v)));
        return true;
    }

    /** For the preview's layout check: the clickable areas drawn last frame. */
    List<LayoutCheck.Area> clickAreas() {
        List<LayoutCheck.Area> out = new ArrayList<>();
        for (Act a : acts) {
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
        long now = System.currentTimeMillis();
        // raids: counts and best times as in the mockup; the newest two show in the feed
        List<RaidRun> raids = new ArrayList<>();
        raids.add(raid("The Canyon Colossus", "TCC", true, now - 5 * HOUR, 872));
        raids.add(raid("Nest of the Grootslangs", "NOG", false, now - 50 * HOUR, 550));
        long at = now - 64 * HOUR;
        int[][] done = {
            {662, 690, 701, 715, 728, 744, 760, 771, 785, 802, 820, 841, 860, 905},   // NOG, best 11:02
            {1188, 1230, 1262, 1301, 1344, 1390},                                       // NOL, best 19:48
            {902, 915, 930, 951, 968, 990, 1010, 1044},                                 // TCC (and the one above), best 14:32
            {1090, 1005, 1040}};                                                        // TNA: 18:10, 16:45, 17:20
        String[][] names = {{"Nest of the Grootslangs", "NOG"}, {"Orphion's Nexus of Light", "NOL"},
            {"The Canyon Colossus", "TCC"}, {"The Nameless Anomaly", "TNA"}};
        int[] fails = {0, 2, 0, 1};
        for (int r = 0; r < done.length; r++) {
            for (int s : done[r]) {
                raids.add(raid(names[r][0], names[r][1], true, at, s));
                at -= 9 * HOUR;
            }
            for (int f = 0; f < fails[r]; f++) {
                raids.add(raid(names[r][0], names[r][1], false, at, 600 + 70 * f));
                at -= 9 * HOUR;
            }
        }
        RaidLog.preview(raids, 5, 3, 0, new RaidDry(7, 31, 2, 39));
        // lootruns: ten over four camps, two failed, two mythics (Warp and Grandmother)
        Map<String, Integer> beacons = new LinkedHashMap<>();
        beacons.put("Blue", 9);
        beacons.put("Purple", 4);
        beacons.put("Yellow", 3);
        beacons.put("Rainbow", 1);
        List<String> missions = List.of("Hoarder", "High Roller");
        List<LootrunRun> runs = List.of(
                loot(true, now - 2 * HOUR, 1880, 32, 14, "Silent Expanse", beacons, missions, null),
                loot(true, now - 26 * HOUR, 1625, 29, 13, "Corkus", beacons, List.of("Cleansing Greed"), "Warp"),
                loot(false, now - 49 * HOUR, 760, 11, 0, "Sky Islands", Map.of("Blue", 3, "Dark Gray", 2), List.of(), null),
                loot(true, now - 60 * HOUR, 1791, 31, 15, "Molten Heights", beacons, missions, null),
                loot(true, now - 74 * HOUR, 1802, 30, 12, "Silent Expanse", beacons, List.of(), null),
                loot(true, now - 98 * HOUR, 1690, 30, 14, "Corkus", beacons, missions, null),
                loot(false, now - 120 * HOUR, 605, 8, 0, "Sky Islands", Map.of("Purple", 2), List.of(), null),
                loot(true, now - 146 * HOUR, 2020, 34, 16, "Molten Heights", beacons, missions, "Grandmother"),
                loot(true, now - 170 * HOUR, 1740, 30, 13, "Silent Expanse", beacons, List.of(), null),
                loot(true, now - 194 * HOUR, 1905, 33, 15, "Corkus", beacons, missions, null));
        LootrunLog.preview(runs, List.of(new MythicFind("Warp", now - 26 * HOUR + MIN), new MythicFind("Grandmother", now - 146 * HOUR + MIN)),
                3, 40);
        // the weekly pool, seen two days ago: Damnation new, Amphibian a tier up
        RaidPool.preview(List.of(
                new PoolAspect("Aspect of Damnation", Klass.SHAMAN, Tier.LEGENDARY, 1),
                new PoolAspect("Aspect of the Amphibian", Klass.SHAMAN, Tier.FABLED, 2),
                new PoolAspect("Aspect of the Eternal Dance", Klass.SHAMAN, Tier.FABLED, 1),
                new PoolAspect("Aspect of the Monolith", Klass.SHAMAN, Tier.LEGENDARY, 1),
                new PoolAspect("Aspect of the Channeler", Klass.SHAMAN, Tier.FABLED, 1)), now - 2 * DAY - HOUR);
        // this week's pools from Wynnventory, as in the group 8 mockup: five raids, seven camps, with
        // the fixtures' icon values (ModelData stays empty, so the preview draws textures and glyphs)
        int[] week = WynnventoryPools.resetWeek(now);
        WynnventoryPools.preview(new WeeklyPool(week[0], week[1], now - 20 * MIN, previewRaids(now)),
                new WeeklyPool(week[0], week[1], now - 20 * MIN, previewCamps(now)));
        // shaman aspects: 9 of 25 owned, two equipped, ranked for Ritualist
        Map<String, Integer> owned = new LinkedHashMap<>();
        owned.put("Aspect of the Amphibian", 1);
        owned.put("Aspect of the Channeler", 1);
        owned.put("Aspect of the Monolith", 2);
        owned.put("Aspect of Lashing Fire", 1);
        owned.put("Aspect of the Haul", 2);
        owned.put("Aspect of Cleansing Waters", 1);
        owned.put("Aspect of Bloodlust", 1);
        owned.put("Aspect of the Vortex", 4);
        owned.put("Aspect of the Eternal Dance", 3);
        AspectBook.preview(Klass.SHAMAN, shamanAspects(), owned, List.of("Aspect of the Monolith", "Aspect of the Eternal Dance"),
                "Ritualist");
        INSTANCE.pinned = null;
        INSTANCE.filter = F_ALL;
        INSTANCE.listScroll = 0;
        INSTANCE.resetPools();
        INSTANCE.d = null;
    }

    /** For the preview's empty-state shot: no runs, no pool, aspects never read (fake character char0002). */
    static void previewEmpty() {
        RaidLog.preview(List.of(), 0, 0, 0, RaidDry.UNKNOWN);
        LootrunLog.preview(List.of(), List.of(), 0, 0);
        RaidPool.preview(List.of(), -1);
        WynnventoryPools.preview(WeeklyPool.NONE, WeeklyPool.NONE);
        AspectBook.preview(Klass.SHAMAN, shamanAspects(), null, List.of(), "Ritualist");
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
    void previewPoolFilter(boolean on) {
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
    void previewPools(int tab, String region) {
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

    /**
     * The mockup's raid pool, with the fixtures' icon values: The Canyon Colossus as in decision
     * 11's drawing (19 aspects, the Shaman's six first; 15 tomes; gear; emerald blocks that fold to
     * ×10, a charm, a powder and an amplifier with their tier), the other four smaller. The Shaman
     * aspects keep group 7's spread (Damnation in two raids).
     */
    private static List<PoolRegion> previewRaids(long now) {
        return List.of(
                region("The Canyon Colossus", now - 3 * HOUR,
                        aspect("Aspect of Exsanguination", Tier.FABLED, "Shaman"),
                        aspect("Aspect of the Monolith", Tier.LEGENDARY, "Shaman"),
                        aspect("Aspect of Damnation", Tier.LEGENDARY, "Shaman"),
                        aspect("Aspect of Gushing Blood", Tier.LEGENDARY, "Shaman"),
                        aspect("Aspect of the Channeler", Tier.FABLED, "Shaman"),
                        aspect("Aspect of the Vortex", Tier.LEGENDARY, "Shaman"),
                        aspect("Mage's Embodiment of Morbid Curiosity", Tier.MYTHIC, "Mage"),
                        aspect("Trapper's Embodiment of Persistence Predation", Tier.MYTHIC, "Archer"),
                        aspect("Boltslinger's Embodiment of Rended Skies", Tier.MYTHIC, "Archer"),
                        aspect("Aspect of Undercrank", Tier.FABLED, "Archer"),
                        aspect("Aspect of Manaflux", Tier.FABLED, "Mage"),
                        aspect("Aspect of the Agile Blade", Tier.FABLED, "Assassin"),
                        aspect("Aspect of Arcane Speed", Tier.FABLED, "Mage"),
                        aspect("Aspect of Shadows", Tier.FABLED, "Assassin"),
                        aspect("Aspect of the Bulwark", Tier.LEGENDARY, "Warrior"),
                        aspect("Aspect of the Hunter", Tier.LEGENDARY, "Archer"),
                        aspect("Aspect of Rallying Fury", Tier.LEGENDARY, "Warrior"),
                        aspect("Aspect of Blinding Light", Tier.LEGENDARY, "Mage"),
                        aspect("Aspect of the Ironclad", Tier.LEGENDARY, "Warrior"),
                        tome("Faerie's Tome of Mysticism II", Tier.MYTHIC, "mysticism"),
                        tome("Vampiric Tome of Defensive Mastery II", Tier.MYTHIC, "armour"),
                        tome("Everlasting Tome of Defensive Mastery II", Tier.MYTHIC, "armour"),
                        tome("Pegasus' Tome of Mysticism II", Tier.MYTHIC, "mysticism"),
                        tome("Golem's Tome of Mysticism II", Tier.MYTHIC, "mysticism"),
                        tome("Dragon's Tome of Mysticism II", Tier.MYTHIC, "mysticism"),
                        tome("Blooming Tome of Defensive Mastery II", Tier.FABLED, "armour"),
                        tome("Fleetfooted Tome of the Marathon II", Tier.FABLED, "marathon"),
                        tome("Pulsing Tome of Defensive Mastery II", Tier.FABLED, "armour"),
                        tome("Oceanic Tome of Defensive Mastery II", Tier.FABLED, "armour"),
                        tome("Courageous Tome of Defensive Mastery II", Tier.FABLED, "armour"),
                        tome("Surefooted Tome of the Marathon II", Tier.FABLED, "marathon"),
                        tome("Tome of Combat Mastery", Tier.FABLED, "weapon"),
                        tome("Bloodthirsty Tome of Combat Mastery II", Tier.LEGENDARY, "weapon"),
                        tome("Tome of Expertise", Tier.LEGENDARY, "expertise"),
                        skin("Warchief", Tier.FABLED),
                        gear("Stardew", Tier.LEGENDARY, "boots.diamond"),
                        item("Emerald Block", "EmeraldItem", Tier.NORMAL, "emerald.emeraldBlock", 4, 0),
                        item("Charm of the Void", "CharmItem", Tier.FABLED, "charm.void", 1, 0),
                        item("Emerald Block", "EmeraldItem", Tier.NORMAL, "emerald.emeraldBlock", 6, 0),
                        item("Liquid Emerald", "EmeraldItem", Tier.NORMAL, "emerald.liquidEmerald", 2, 0),
                        item("Fire Powder", "PowderItem", Tier.NORMAL, "powder.FireSmall", 1, 4),
                        item("Corkian Amplifier", "AmplifierItem", Tier.NORMAL, "augment.corkianAmplifier", 1, 2)),
                region("The Nameless Anomaly", now - 26 * HOUR,
                        aspect("Aspect of Damnation", Tier.LEGENDARY, "Shaman"),
                        aspect("Aspect of the Monolith", Tier.LEGENDARY, "Shaman"),
                        aspect("Aspect of Arcane Speed", Tier.FABLED, "Mage"),
                        tome("Tome of Combat Mastery", Tier.FABLED, "weapon"),
                        item("Emerald Block", "EmeraldItem", Tier.NORMAL, "emerald.emeraldBlock", 64, 0)),
                region("Nest of the Grootslangs", now - 15 * HOUR,
                        aspect("Ritualist's Embodiment of the Ancestral Avatar", Tier.MYTHIC, "Shaman"),
                        aspect("Aspect of Bile Waters", Tier.LEGENDARY, "Shaman"),
                        aspect("Aspect of the Hunter", Tier.LEGENDARY, "Archer"),
                        tome("Tome of Expertise", Tier.LEGENDARY, "expertise"),
                        item("Liquid Emerald", "EmeraldItem", Tier.NORMAL, "emerald.liquidEmerald", 1, 0)),
                region("Orphion's Nexus of Light", now - 20 * HOUR,
                        aspect("Aspect of Motivation", Tier.LEGENDARY, "Shaman"),
                        aspect("Aspect of the Vortex", Tier.LEGENDARY, "Shaman"),
                        aspect("Aspect of Shadows", Tier.FABLED, "Assassin"),
                        tome("Tome of Rage", Tier.LEGENDARY, "weapon")),
                region("The Wartorn Palace", now - 49 * HOUR,
                        aspect("Aspect of Exsanguination", Tier.FABLED, "Shaman"),
                        tome("Tome of Rage", Tier.LEGENDARY, "weapon"),
                        item("Charm of Stone", "CharmItem", Tier.LEGENDARY, "charm.stone", 1, 0)));
    }

    /**
     * The mockup's lootrun pool: seven camps. Canyon of the Lost as in decision 11's drawing: a
     * shiny Halcyon ("Raids Won") next to its plain twin, four distinct mythics and the Corkian
     * Simulator, then Fabled, Legendary, Rare, Unique and the other rewards (rune, key, emeralds,
     * ward, powder).
     */
    private static List<PoolRegion> previewCamps(long now) {
        return List.of(
                region("Fruma Foray (East)", now - 50 * HOUR, gear("Nirvana", Tier.MYTHIC, "dagger.fire3"),
                        gear("Alkatraz", Tier.MYTHIC, "relik.earth3"), gear("Apocalypse", Tier.MYTHIC, "spear.fire3"),
                        gear("Aftershock", Tier.MYTHIC, "relik.earth3"), gear("Toxoplasmosis", Tier.MYTHIC, "bow.earth3"),
                        gear("Lament", Tier.MYTHIC, "wand.water3"), gear("Pyrotechnic", Tier.LEGENDARY, "helmet.diamond")),
                region("Fruma Foray (West)", now - 49 * HOUR, gear("Fatal", Tier.MYTHIC, "wand.thunder3"),
                        gear("Archangel", Tier.MYTHIC, "spear.air3"), gear("Hero", Tier.MYTHIC, "bow.air3"),
                        gear("Weathered", Tier.MYTHIC, "dagger.air3"), gear("Slayer", Tier.MYTHIC, "boots.diamond"),
                        gear("Moontower", Tier.MYTHIC, "chestplate.diamond"), shiny("Moontower", Tier.MYTHIC, "chestplate.diamond", "Mobs Killed")),
                region("Silent Expanse", now - 2 * HOUR, gear("Warp", Tier.MYTHIC, "wand.air3"), gear("Divzer", Tier.MYTHIC, "bow.thunder3"),
                        gear("Stratiformis", Tier.MYTHIC, "spear.air3"), gear("Collapse", Tier.MYTHIC, "spear.earth3"),
                        gear("Absolution", Tier.MYTHIC, "relik.fire3"), shiny("Divzer", Tier.MYTHIC, "bow.thunder3", "Lootruns Completed"),
                        gear("Cerid's Dynamo", Tier.LEGENDARY, "ring.basic")),
                region("Corkus", now - 9 * HOUR, gear("Grandmother", Tier.MYTHIC, "leggings.diamond"),
                        gear("Boreal", Tier.MYTHIC, "chestplate.diamond"), gear("Revenant", Tier.MYTHIC, "boots.diamond"),
                        gear("Fantasia", Tier.MYTHIC, "relik.thunder3"), item("Uth Rune", "RuneItem", Tier.NORMAL, "rune.uth", 1, 0)),
                region("Sky Islands", now - 5 * HOUR, gear("Galleon", Tier.MYTHIC, "helmet.diamond"),
                        gear("Freedom", Tier.MYTHIC, "bow.air3"), gear("Gaia", Tier.MYTHIC, "wand.earth3"),
                        item("Sky Islands Key", "DungeonKeyItem", Tier.NORMAL, "dungeon.key", 1, 0)),
                region("Molten Heights", now - 25 * HOUR, gear("Immolation", Tier.MYTHIC, "relik.fire3"),
                        gear("Hadal", Tier.MYTHIC, "bow.water3"), shiny("Hadal", Tier.MYTHIC, "bow.water3", "Dungeons Completed")),
                region("Canyon of the Lost", now - 2 * HOUR,
                        shiny("Halcyon", Tier.MYTHIC, "wand.multi3", "Raids Won"),
                        gear("Inferno", Tier.MYTHIC, "dagger.fire3"), gear("Quetzalcoatl", Tier.MYTHIC, "wand.air3"),
                        gear("Halcyon", Tier.MYTHIC, "wand.multi3"), gear("Idol", Tier.MYTHIC, "spear.water3"),
                        item("Corkian Simulator", "SimulatorItem", Tier.MYTHIC, "augment.corkianSimulator", 1, 0),
                        gear("Equites", Tier.FABLED, "boots.diamond"), gear("Alkali", Tier.FABLED, "necklace.basic"),
                        gear("Welder Cannon", Tier.FABLED, "bow.fire3"), gear("Vulcan", Tier.FABLED, "helmet.diamond"),
                        gear("Cascade", Tier.FABLED, "spear.water3"), gear("Chimera", Tier.FABLED, "relik.multi3"),
                        tome("Tome of Lootrun Mastery", Tier.FABLED, "lootrun"),
                        gear("Flameshot Hilt", Tier.LEGENDARY, "dagger.fire3"), gear("Steamjet Walkers", Tier.LEGENDARY, "boots.diamond"),
                        gear("Loaded Question", Tier.LEGENDARY, "bracelet.basic"), skin("Outrage", Tier.LEGENDARY),
                        gear("Leo", Tier.LEGENDARY, "chestplate.diamond"),
                        gear("Charybdis", Tier.RARE, "chestplate.diamond"), gear("Ambiguity", Tier.RARE, "necklace.basic"),
                        gear("Ligfamblawende", Tier.RARE, "wand.multi3"), gear("Cloud Nine", Tier.RARE, "spear.air3"),
                        gear("Oktavist", Tier.RARE, "bow.thunder3"), gear("Mystical Lance", Tier.RARE, "spear.thunder3"),
                        gear("Breakbeat", Tier.RARE, "relik.air3"),
                        gear("Leaf Cap", Tier.UNIQUE, "helmet.diamond"), gear("Gale Rider", Tier.UNIQUE, "boots.diamond"),
                        gear("Dune Storm", Tier.UNIQUE, "bow.earth3"),
                        item("Uth Rune", "RuneItem", Tier.NORMAL, "rune.uth", 1, 0),
                        item("Broken Lost Sanctuary Key", "DungeonKeyItem", Tier.NORMAL, "dungeon.keyBroken", 1, 0),
                        item("Emerald Block", "EmeraldItem", Tier.NORMAL, "emerald.emeraldBlock", 3, 0),
                        item("Blue Ward", "WardItem", Tier.NORMAL, "ward.blue", 1, 0),
                        item("Water Powder", "PowderItem", Tier.NORMAL, "powder.WaterSmall", 1, 3)));
    }

    private static PoolRegion region(String name, long seenAt, PoolItem... items) {
        return new PoolRegion(name, "", seenAt, List.of(items));
    }

    private static PoolItem aspect(String name, Tier rarity, String klass) {
        return new PoolItem(name, "AspectItem", rarity, klass + "Aspect", 1, "aspect_attribute", "abilityTree.aspect" + klass, false, "", 0);
    }

    private static PoolItem tome(String name, Tier rarity, String type) {
        return item(name, "TomeItem", rarity, "tome." + type, 1, 0);
    }

    private static PoolItem gear(String name, Tier rarity, String icon) {
        return item(name, "GearItem", rarity, icon, 1, 0);
    }

    private static PoolItem shiny(String name, Tier rarity, String icon, String stat) {
        return new PoolItem(name, "GearItem", rarity, "", 1, "attribute", icon, true, stat, 0);
    }

    /** Gear whose icon is a player head ({@code skin}), as one fixture entry has. */
    private static PoolItem skin(String name, Tier rarity) {
        return new PoolItem(name, "GearItem", rarity, "", 1, "skin", "preview-skin", false, "", 0);
    }

    private static PoolItem item(String name, String type, Tier rarity, String icon, int amount, int tier) {
        return new PoolItem(name, type, rarity, "", amount, "attribute", icon, false, "", tier);
    }

    private static RaidRun raid(String name, String abbr, boolean completed, long endedAt, int seconds) {
        long ms = seconds * 1000L;
        List<RaidRun.Room> rooms = completed
                ? List.of(new RaidRun.Room("Room 1", ms * 24 / 100), new RaidRun.Room("Room 2", ms * 22 / 100),
                        new RaidRun.Room("Room 3", ms * 26 / 100), new RaidRun.Room("Boss", ms - ms * 72 / 100))
                : List.of(new RaidRun.Room("Room 1", ms * 45 / 100), new RaidRun.Room("Room 2", ms - ms * 45 / 100));
        return new RaidRun(name, abbr, completed, endedAt, ms, rooms);
    }

    private static LootrunRun loot(boolean completed, long endedAt, int seconds, int challenges, int pulls, String camp,
            Map<String, Integer> beacons, List<String> missions, String mythic) {
        return new LootrunRun(completed, endedAt, seconds, challenges, pulls, completed ? 2 : 0, completed ? 1 : 0,
                completed ? 41_000 + 900 * challenges : 0, camp, beacons, missions, mythic);
    }

    /** Twenty-five shaman aspects with real names; tiers and thresholds by rarity, ability names for the ranking. */
    private static List<AspectEntry> shamanAspects() {
        Object[][] rows = {
            {"Aspect of Damnation", Tier.LEGENDARY, List.of("Malediction")},
            {"Ritualist's Embodiment of the Ancestral Avatar", Tier.MYTHIC, List.of("Mask of the Fanatic", "Masquerade")},
            {"Aspect of the Amphibian", Tier.FABLED, List.of("Frog Dance")},
            {"Aspect of Gushing Blood", Tier.LEGENDARY, List.of("Blood Pool")},
            {"Aspect of the Channeler", Tier.FABLED, List.of("Aura")},
            {"Aspect of the Monolith", Tier.LEGENDARY, List.of("Totem")},
            {"Aspect of Bile Waters", Tier.LEGENDARY, List.of()},
            {"Aspect of Motivation", Tier.LEGENDARY, List.of()},
            {"Summoner's Embodiment of the Omnipotent Overseer", Tier.MYTHIC, List.of()},
            {"Acolyte's Embodiment of Unwavering Adherence", Tier.MYTHIC, List.of()},
            {"Aspect of the Beckoned Legion", Tier.FABLED, List.of()},
            {"Aspect of Exsanguination", Tier.FABLED, List.of()},
            {"Aspect of the Puppeteer", Tier.LEGENDARY, List.of()},
            {"Aspect of Stances", Tier.LEGENDARY, List.of()},
            {"Aspect of Sanguine Rage", Tier.LEGENDARY, List.of()},
            {"Aspect of the Lunatic", Tier.LEGENDARY, List.of()},
            {"Aspect of Rejuvenation", Tier.LEGENDARY, List.of()},
            {"Aspect of the Undying Totem", Tier.FABLED, List.of()},
            {"Aspect of Infinite Crows", Tier.LEGENDARY, List.of()},
            {"Aspect of Lashing Fire", Tier.LEGENDARY, List.of()},
            {"Aspect of the Haul", Tier.LEGENDARY, List.of()},
            {"Aspect of Cleansing Waters", Tier.LEGENDARY, List.of()},
            {"Aspect of Bloodlust", Tier.LEGENDARY, List.of()},
            {"Aspect of the Vortex", Tier.LEGENDARY, List.of()},
            {"Aspect of the Eternal Dance", Tier.FABLED, List.of("Frog Dance")},
        };
        List<AspectEntry> out = new ArrayList<>();
        for (Object[] r : rows) {
            Tier t = (Tier) r[1];
            List<Integer> th = t == Tier.MYTHIC ? List.of(1, 3, 10) : t == Tier.FABLED ? List.of(1, 15, 75) : List.of(1, 5, 30, 150);
            @SuppressWarnings("unchecked")
            List<String> abilities = (List<String>) r[2];
            out.add(new AspectEntry((String) r[0], Klass.SHAMAN, t, th.size(), th, abilities));
        }
        return out;
    }
}
