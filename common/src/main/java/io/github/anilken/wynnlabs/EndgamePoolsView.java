package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.PoolItem;
import io.github.anilken.wynnlabs.model.Tier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Endgame tab's Havuzlar view (openspec endgame-tab, decision 11): the raid and camp sub-tabs,
 * the region list, and each region's pool in scrolling columns with the items' own icons.
 */
final class EndgamePoolsView extends EndgamePanel {

    /** Scroll slots per sub-tab: the three columns (0-2), the compact list, the region list. */
    private static final int S_LIST = 3;
    static final int S_REGIONS = 4;
    /** Lines of a pool list: a group header, the "other classes" rule, an item row. */
    private static final int L_HEAD = 0;
    private static final int L_RULE = 1;
    private static final int L_ROW = 2;
    private static final int REGION_ROW = 24;

    /** The lists drawn this frame that the wheel scrolls. */
    private final List<Wheel> wheels = new ArrayList<>();

    /** A list the wheel scrolls: its box, its scroll slot and how far it can scroll. */
    private record Wheel(int x, int y, int w, int h, int slot, int max) {
        boolean in(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** One line of a pool list. */
    private record PLine(int type, PoolView.Group.Kind kind, int count, PoolView.Row row, boolean dim) {}

    EndgamePoolsView(EndgameTab tab) {
        super(tab);
    }

    /** A new frame: the lists the wheel scrolls are added again as they are drawn. */
    void newFrame() {
        wheels.clear();
    }

    /** The wheel over Havuzlar: each column, the compact list and the region list scroll on their own; true when it did. */
    boolean mouseScrolled(double mx, double my, double v) {
        for (Wheel wl : wheels) {
            if (wl.in(mx, my)) {
                if (wl.max() <= 0) {
                    return false;
                }
                int[] sc = tab.poolScroll[tab.poolTab];
                sc[wl.slot()] = Math.max(0, Math.min(wl.max(), sc[wl.slot()] - (int) Math.signum(v)));
                return true;
            }
        }
        return false;
    }

    /** The lit region; the first time a raid with the most marked aspects (ties in raid order) and the first camp. */
    private PoolView.Region lit(List<PoolView.Region> regs, boolean raid) {
        String want = raid ? tab.litRaid : tab.litCamp;
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
            tab.litRaid = best.name();
        } else {
            tab.litCamp = best.name();
        }
        return best;
    }

    /** Havuzlar: the sub-tabs, then the region list and the pane (standard, large) or the ‹ › line and one list (compact). */
    void drawPools(GuiGraphics g, int x, int y, int w, int h, boolean compact, boolean large, int mx, int my) {
        if (DevHooks.ON) {
            DevHooks.part("pools", g.pose(), x, y, w, h);
        }
        boolean raid = tab.poolTab == EndgameTab.TAB_RAIDS;
        List<PoolView.Region> regs = tab.d.v().regions(raid);
        subTabs(g, x, y, w, compact, mx, my);
        int top = y + 18;
        int bottom = y + h;
        if (regs.isEmpty()) {
            boolean loading = tab.d.p().status() == WynnventoryPools.Status.LOADING;
            String s = L.t(loading ? "wynnlabs.endgame.pools_loading"
                    : raid ? "wynnlabs.endgame.pools_none_raids" : "wynnlabs.endgame.pools_none_loot");
            int ly = top + 2;
            for (String line : wrap(s, w)) {
                ink(g, fit(line, w), x, ly, Theme.INK_3);
                ly += 12;
            }
            List<Component> tip = new ArrayList<>(List.of(title(L.t(raid ? "wynnlabs.endgame.raids" : "wynnlabs.endgame.tab_loot"),
                    ChatFormatting.GOLD), grey(s)));
            String why = reason(tab.d.p().status());
            if (why != null) {
                tip.add(dark(why));
            }
            tab.tips.add(new Tip(x, top, w, ly - top, tip));
            return;
        }
        PoolView.Region lit = lit(regs, raid);
        List<PoolView.Group> groups = tab.d.v().groups(raid, lit.name());
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
        int nr = tab.d.v().raids().size();
        int nc = tab.d.v().camps().size();
        String[] names = {nr > 0 ? L.t("wynnlabs.endgame.tab_raids_n", nr) : L.t("wynnlabs.endgame.raids"),
            nc > 0 ? L.t("wynnlabs.endgame.tab_loot_n", nc) : L.t("wynnlabs.endgame.tab_loot")};
        int cx = x;
        for (int i = 0; i < 2; i++) {
            int bw = Kit.Ui.btnW(tab.font, names[i]);
            boolean on = tab.poolTab == i;
            boolean hover = mx >= cx && mx < cx + bw && my >= y && my < y + 14;
            int state = on ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
            Kit.button(g, cx, y, bw, 14, state);
            g.drawString(tab.font, names[i], cx + Kit.Ui.BTN_PAD, y + 3, Kit.buttonText(state), state != Kit.ON);
            int t = i;
            tab.acts.add(new Act(cx, y, bw, 14, () -> tab.poolTab = t, "endgame:pooltab" + i));
            cx += bw + 3;
        }
        boolean raid = tab.poolTab == EndgameTab.TAB_RAIDS;
        boolean stale = tab.d.v().stale(raid);
        String src = sourceText(raid, false);
        int room = x + w - cx - 6;
        if (tab.font.width(src) > room) {
            src = sourceText(raid, true);
        }
        src = fit(src, room);
        if (src.isEmpty()) {
            return;
        }
        int sx = x + w - tab.font.width(src);
        ink(g, src, sx, y + 3, stale ? GREYED : Theme.INK_3);
        List<Component> tip = new ArrayList<>();
        tip.add(dark(sourceTip(raid)));
        if (stale) {
            tip.add(warn(L.t("wynnlabs.endgame.tip_week_stale")));
        }
        String why = reason(tab.d.p().status());
        if (why != null) {
            tip.add(dark(why));
        }
        tab.tips.add(new Tip(sx, y, x + w - sx, 14, tip));
    }

    /** "Wynnventory · 42. hafta" (short: "42. hf"), "oyunda", or why there is no data; "" when there is nothing to say. */
    private String sourceText(boolean raid, boolean shortForm) {
        int week = raid ? tab.d.p().week() : tab.d.p().lootWeek();
        boolean api = raid ? tab.d.p().wynnventory() : !tab.d.v().camps().isEmpty();
        if (api) {
            String wk = week < 0 ? "" : L.t(shortForm ? "wynnlabs.endgame.week_short" : "wynnlabs.endgame.week_n", week);
            return shortForm ? wk : L.t("wynnlabs.endgame.src_wynnventory") + (wk.isEmpty() ? "" : " · " + wk);
        }
        if (raid && tab.d.p().source() == RaidPool.Source.GAME) {
            return L.t("wynnlabs.endgame.src_game");
        }
        String why = reason(tab.d.p().status());
        return why == null ? "" : why;
    }

    /** The tooltips' source line. */
    private String sourceTip(boolean raid) {
        if (raid && !tab.d.p().wynnventory()) {
            return L.t("wynnlabs.endgame.tip_src_game");
        }
        return L.t("wynnlabs.endgame.tip_src_week", raid ? tab.d.p().week() : tab.d.p().lootWeek());
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
        int[] sc = tab.poolScroll[tab.poolTab];
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
                tab.acts.add(new Act(x + 2, ry, rw, REGION_ROW, () -> tab.pick(raid, r.name()), "endgame:region:" + r.name()));
            }
            tab.tips.add(new Tip(x + 2, ry, rw, REGION_ROW, regionTip(r, !on)));
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
            cx += tab.font.width(cf);
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
        boolean stale = tab.d.v().stale(r.raid());
        if (r.seenAt() >= 0) {
            t.add(grey(agoLong(r.seenAt()) + (stale ? L.t("wynnlabs.endgame.pool_stale") : "")));
        }
        String count = regionCount(r);
        if (!count.isEmpty() || r.shinies() > 0) {
            String shiny = r.shinies() > 0 ? L.t("wynnlabs.endgame.shiny_n", r.shinies()) : "";
            t.add(r.raid() ? good(count) : Component.literal(count.isEmpty() ? shiny : shiny.isEmpty() ? count : count + " · " + shiny)
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        for (PoolView.Group gr : tab.d.v().groups(r.raid(), r.name())) {
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
        if (raid && tab.d.klass() != Klass.NONE) {
            right = classChip(g, right, y - 1, mx, my) - 6;
        }
        boolean stale = tab.d.v().stale(raid);
        String rest = headRest(r, stale);
        String name = r.name().isEmpty() ? regionName(r) : r.name().toUpperCase(Locale.ROOT);
        int restW = Math.min(tab.font.width(rest), (right - x) / 2);
        Component nc = boldFit(name, right - x - restW);
        Kit.text(g, tab.font, nc, x, y + 2, stale ? GREYED : Theme.INK);
        int nx = x + tab.font.width(nc);
        ink(g, fit(rest, right - nx), nx, y + 2, stale ? GREYED : Theme.INK_2);
        tab.tips.add(new Tip(x, y, right - x, 13, regionTip(r, false)));
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
        String s = L.t("wynnlabs.endgame.class_only", nice(tab.d.klass()));
        int bw = Kit.Ui.btnW(tab.font, s);
        int bx = right - bw;
        boolean hover = mx >= bx && mx < right && my >= y && my < y + 14;
        int state = tab.classOnly ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
        Kit.button(g, bx, y, bw, 14, state);
        g.drawString(tab.font, s, bx + Kit.Ui.BTN_PAD, y + 3, Kit.buttonText(state), state != Kit.ON);
        tab.acts.add(new Act(bx, y, bw, 14, () -> {
            tab.classOnly = !tab.classOnly;
            int[] sc = tab.poolScroll[EndgameTab.TAB_RAIDS];
            for (int i = 0; i < S_REGIONS; i++) {
                sc[i] = 0;
            }
            tab.d = null;                                   // the groups are read again with the chip
        }, "endgame:classonly"));
        tab.tips.add(new Tip(bx, y, bw, 14, List.of(grey(L.t(tab.classOnly ? "wynnlabs.endgame.class_only_off" : "wynnlabs.endgame.class_only_tip",
                nice(tab.d.klass()))))));
        return bx;
    }

    /** Compact: "‹ [R] The Canyon Colossus · 3 new · 3h ›" (the arrows step, the line's tooltip lists them all) and the class chip. */
    private void stepper(GuiGraphics g, List<PoolView.Region> regs, PoolView.Region lit, boolean raid, int x, int y, int w,
            int mx, int my) {
        int right = x + w;
        if (raid && tab.d.klass() != Klass.NONE) {
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
        int restW = Math.min(tab.font.width(rest), (end - nx) / 2);
        boolean stale = tab.d.v().stale(raid);
        String nf = fit(regionName(lit), end - nx - restW);
        ink(g, nf, nx, y + 3, stale ? GREYED : Theme.INK);
        int cx = nx + tab.font.width(nf);
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
        tab.tips.add(new Tip(tx, y, end - tx, 14, tip));
    }

    private void arrow(GuiGraphics g, int x, int y, String icon, PoolView.Region to, boolean raid, String what, int mx, int my) {
        boolean hover = mx >= x && mx < x + 14 && my >= y && my < y + 14;
        Kit.button(g, x, y, 14, 14, hover ? Kit.HOVER : Kit.REST);
        Art.icon(g, icon, x + 3, y + 3, 8, Theme.CREAM);
        tab.acts.add(new Act(x, y, 14, 14, () -> tab.pick(raid, to.name()), what));
        tab.tips.add(new Tip(x, y, 14, 14, List.of(grey(regionName(to)))));
    }

    /** The lines of a pool list: each group's header and rows, the "other classes" rule before the first other class's aspect. */
    private List<PLine> lines(List<PoolView.Group> groups, boolean raid) {
        List<PLine> out = new ArrayList<>();
        boolean klass = tab.d.klass() != Klass.NONE;
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
        int[] sc = tab.poolScroll[tab.poolTab];
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
        Kit.section(g, tab.font, fit(groupName(l.kind()) + " " + l.count(), x + w - tx - 12), tx, y + 2, x + w - tx);
    }

    /** The dotted rule "other classes" between the class's own aspects and the others. */
    private void otherRule(GuiGraphics g, int x, int y, int w) {
        String s = fit(L.t("wynnlabs.endgame.other_classes"), w - 16);
        int sw = tab.font.width(s);
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
            int bw = Kit.Ui.tagW(tab.font, item.shinyStat());
            Art.pill(g, rx - bw, ty - 1, bw, 10, Theme.WELL, 0);
            Kit.text(g, tab.font, item.shinyStat(), rx - bw + Kit.Ui.TAG_PAD, ty, Theme.INK_3);
            rx -= bw + 3;
        }
        if (r.ownClass()) {
            if (r.maxed()) {
                Art.icon(g, "check", rx - 8, ty, 8, Theme.GOOD);
                rx -= 11;
            } else {
                String tg = markText(r);
                if (tg != null) {
                    int tw = Kit.Ui.tagW(tab.font, tg);
                    tag(g, tg, rx - tw, ty - 1);
                    rx -= tw + 3;
                }
            }
        }
        if (r.count() > 1) {
            String n = "×" + r.count();
            right(g, n, rx, ty, Theme.INK_3);
            rx -= tab.font.width(n) + 3;
        }
        int nx = x + 2 + size + 3;
        int colour = tierColour(item.rarity());
        if (l.dim()) {
            colour = colour & 0x00FFFFFF | 0x80000000;
        }
        ink(g, fit(itemName(item), rx - nx), nx, ty, colour);
        tab.rowRects.putIfAbsent(item.name() + (item.shiny() ? "*" : ""), new int[] {x, y, w, h});
        tab.tips.add(new Tip(x, y, w, h, itemTip(r, raid, region)));
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
            List<String> also = new ArrayList<>(EndgameData.safe(RaidPool.raidsHolding(i.name())));
            also.remove(region.name());
            if (!also.isEmpty()) {
                t.add(grey(L.t("wynnlabs.endgame.tip_also", String.join(", ", also))));
            }
        }
        t.add(dark(regionName(region) + (region.seenAt() < 0 ? "" : " · " + agoLong(region.seenAt()))));
        if (tab.d.v().stale(raid)) {
            t.add(warn(L.t("wynnlabs.endgame.tip_week_stale")));
        }
        t.add(dark(sourceTip(raid)));
        return t;
    }
}
