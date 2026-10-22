package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.endgame.WynnventoryPools;
import io.github.anilken.wynnlabs.model.CampMythics;
import io.github.anilken.wynnlabs.model.FeedItem;
import io.github.anilken.wynnlabs.model.LootrunRun;
import io.github.anilken.wynnlabs.model.LootrunTotals;
import io.github.anilken.wynnlabs.model.RaidRun;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The Endgame tab's recent runs: the raid and lootrun feed, this week's mythic camps and the lootrun totals. */
final class EndgameRecentRuns extends EndgamePanel {

    EndgameRecentRuns(EndgameTab tab) {
        super(tab);
    }

    /** Raids and lootruns in one feed, newest first, as many as fit, with the lootrun totals under it. */
    void feed(GuiGraphics g, int x, int y, int w, int h, boolean large, int mx, int my) {
        Kit.section(g, tab.font, L.t("wynnlabs.endgame.recent"), x, y, w);
        int ry = y + 11;
        int foot = y + h - 10;
        List<FeedItem> items = FeedItem.merge(tab.d.raids(), tab.d.lootruns(), -1);
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
        int lines = tab.d.p().camps().isEmpty() ? 0 : mythicLine(g, x, 0, w, large ? 2 : 1, false);
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
                int mw = Math.min(tab.font.width(mn), nameW / 2);
                String nf = fit(f.name(), nameW - mw - ROW_ICON - 6);
                ink(g, nf, nameX, ty, Theme.INK);
                int gx = nameX + tab.font.width(nf) + 3;
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
            tab.tips.add(new Tip(x, ry, w, rowH, f.isRaid() ? raidRunTip(f.raid()) : lootrunTip(f.lootrun())));
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
    int mythicLine(GuiGraphics g, int x, int y, int w, int maxLines, boolean draw) {
        List<CampMythics> camps = tab.d.p().camps();
        if (camps.isEmpty()) {
            return 0;
        }
        String label = L.t("wynnlabs.endgame.mythic_week") + " ";
        int end = x + w;
        int cx = x + ROW_ICON + 3 + tab.font.width(label);
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
            int plusW = rest > 0 ? tab.font.width(" +" + rest) : 0;
            if (cx + tab.font.width(part) + plusW > end) {
                if (line + 1 >= maxLines || shown == 0) {
                    break;
                }
                // the next line, under the label
                line++;
                cx = x + ROW_ICON + 3;
                part = c.camp() + " " + c.count();
                if (cx + tab.font.width(part) + plusW > end) {
                    break;
                }
            }
            if (draw) {
                ink(g, part, cx, y + 12 * line, Theme.TIER_MYTHIC);
            }
            cx += tab.font.width(part);
            shown++;
        }
        int rest = camps.size() - shown;
        if (rest > 0 && draw) {
            String plus = (shown > 0 ? " " : "") + "+" + rest;
            ink(g, fit(plus, end - cx), cx, y + 12 * line, Theme.INK_3);
        }
        if (draw) {
            int h = 12 * (line + 1) - 1;
            tab.mythicRect = new int[] {x, y, w, h};
            String first = camps.get(0).camp();
            tab.acts.add(new Act(x, y - 1, w, h, () -> tab.openPools(EndgameTab.TAB_CAMPS, first), "endgame:mythic"));
            tab.tips.add(new Tip(x, y - 1, w, h, mythicTip()));
        }
        return line + 1;
    }

    /** The mythic line's tooltip: every camp with its count, age and mythics; the camps without one; the week and the source. */
    private List<Component> mythicTip() {
        EndgameData.Pools p = tab.d.p();
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
        if (!tab.d.p().loot()) {
            out.add(dark(L.t(tab.d.p().status() == WynnventoryPools.Status.OFF ? "wynnlabs.endgame.mythic_hint_off"
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
        int bx = x + tab.font.width(cf) + 6;
        for (Map.Entry<String, Integer> b : r.beacons().entrySet()) {
            String n = String.valueOf(b.getValue());
            int need = 7 + tab.font.width(n) + 4;
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
        if (!tab.d.lootUp()) {
            ink(g, fit(L.t("wynnlabs.endgame.lootruns_off"), w), x, y, Theme.WARN);
            return;
        }
        LootrunTotals t = tab.d.totals();
        if (t.runs() <= 0) {
            ink(g, fit(L.t("wynnlabs.endgame.lootrun_empty"), w), x, y, Theme.INK_3);
            return;
        }
        ink(g, fit(L.n("wynnlabs.endgame.totals", t.runs(), t.runs(), Math.max(0, t.completionPct()),
                t.pullsPerCompleted() < 0 ? "-" : String.valueOf(Math.round(t.pullsPerCompleted()))), w), x, y, Theme.INK_3);
        tab.tips.add(new Tip(x, y - 1, w, 11, totalsTip(t)));
    }

    /** The compact lootrun section: the last lootruns with time and pulls, then the totals. */
    void compactLootruns(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        int pullsR = x + w;
        int timeR = pullsR - 50;
        int markX = timeR - 46;
        Kit.section(g, tab.font, L.t("wynnlabs.endgame.recent_lootruns"), x, y, markX - 8 - x);
        right(g, L.t("wynnlabs.endgame.col_time"), timeR, y, Theme.INK_3);
        right(g, L.t("wynnlabs.endgame.col_pulls"), pullsR, y, Theme.INK_3);
        int ry = y + 11;
        int foot = y + h - 10;
        if (tab.d.lootruns().isEmpty()) {
            ink(g, fit(L.t("wynnlabs.endgame.lootrun_empty"), w), x, ry + 2, Theme.INK_3);
        }
        int rowH = 12;
        for (int i = 0; i < tab.d.lootruns().size() && ry + rowH <= foot - 2; i++) {
            LootrunRun r = tab.d.lootruns().get(i);
            boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + rowH;
            Kit.row(g, x, ry, w, rowH, i, hover);
            int ty = ry + 2;
            int nameW = markX - 6 - x;
            if (r.mythic() != null) {
                int mw = Math.min(tab.font.width(r.mythic()), nameW / 2);
                String nf = fit(r.camp(), nameW - mw - ROW_ICON - 6);
                ink(g, nf, x + 2, ty, Theme.INK);
                int gx = x + 2 + tab.font.width(nf) + 3;
                mythicGlyph(g, gx, ty - 1, ROW_ICON);
                ink(g, fit(r.mythic(), markX - 4 - gx - ROW_ICON - 2), gx + ROW_ICON + 2, ty, Theme.TIER_MYTHIC);
            } else {
                ink(g, fit(r.camp(), nameW), x + 2, ty, Theme.INK);
            }
            Art.icon(g, r.completed() ? "check" : "x", markX, ty, 8, r.completed() ? Theme.GOOD : Theme.BAD);
            right(g, time(r.durationS() < 0 ? -1 : r.durationS() * 1000L), timeR, ty, Theme.INK);
            right(g, r.completed() ? String.valueOf(r.pulls()) : "-", pullsR, ty, r.completed() ? Theme.INK : Theme.INK_3);
            tab.tips.add(new Tip(x, ry, w, rowH, lootrunTip(r)));
            ry += rowH;
        }
        String t = "";
        LootrunTotals tt = tab.d.totals();
        if (tab.d.lootUp() && tt.runs() > 0) {
            t = L.n("wynnlabs.endgame.totals_short", tt.runs(), tt.runs(), Math.max(0, tt.completionPct()),
                    tt.pullsPerCompleted() < 0 ? "-" : String.valueOf(Math.round(tt.pullsPerCompleted())));
            ink(g, fit(t, w), x, foot, Theme.INK_3);
            tab.tips.add(new Tip(x, foot - 1, w, 11, totalsTip(tt)));
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
}
