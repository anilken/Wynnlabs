package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.PoolAspect;
import io.github.anilken.wynnlabs.model.RaidRun;
import io.github.anilken.wynnlabs.model.RaidSummary;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/** The Endgame tab's raid table and the weekly pool line under it. */
final class EndgameRaids extends EndgamePanel {

    /** The widest line of the pool tooltip's aspect lists, in pixels. */
    private static final int TIP_W = 240;

    EndgameRaids(EndgameTab tab) {
        super(tab);
    }

    /** "Aspect of the Monolith" → "Monolith": the pool tooltip lists many, the full name is on the row. */
    private static String shortAspect(String name) {
        for (String p : new String[] {"Aspect of the ", "Aspect of "}) {
            if (name.startsWith(p)) {
                return name.substring(p.length());
            }
        }
        return name;
    }

    /** The raid table (done, failed, best; average and last when large) and the pool line; returns the y under it. */
    int raids(GuiGraphics g, int x, int y, int w, int rowH, boolean large, boolean compact, int mx, int my) {
        // numbers right-aligned from the right edge; the name gets what is left
        boolean extra = large && w >= 250;
        int lastR = x + w;
        int avgR = extra ? lastR - 32 : lastR;
        int bestR = extra ? avgR - 36 : lastR;
        int failR = bestR - 38;
        int doneR = failR - 20;
        Kit.section(g, tab.font, L.t("wynnlabs.endgame.raids"), x, y, doneR - 14 - x);
        Art.icon(g, "check", doneR - 8, y, 8, Theme.GOOD);
        Art.icon(g, "x", failR - 8, y, 8, Theme.BAD);
        right(g, L.t("wynnlabs.endgame.col_best"), bestR, y, Theme.INK_3);
        if (extra) {
            right(g, L.t("wynnlabs.endgame.col_avg"), avgR, y, Theme.INK_3);
            right(g, L.t("wynnlabs.endgame.col_last"), lastR, y, Theme.INK_3);
        }
        int ry = y + 11;
        if (!tab.d.raidsUp()) {
            ink(g, fit(L.t("wynnlabs.endgame.raids_off"), w), x, ry + 1, Theme.WARN);
            ry += 12;
        } else if (!tab.d.tracking()) {
            ink(g, fit(L.t("wynnlabs.endgame.raids_untracked"), w), x, ry + 1, Theme.WARN);
            ry += 12;
        }
        boolean any = false;
        for (int i = 0; i < tab.d.summaries().size(); i++) {
            RaidSummary s = tab.d.summaries().get(i);
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
            tab.tips.add(new Tip(x, ry, w, rowH, raidTip(s)));
            ry += rowH;
            if (ry + rowH > y + 11 + 8 * rowH && i < tab.d.summaries().size() - 1 && compact) {
                break;                                  // a long list of new raids: the first rows only
            }
        }
        if (!any && tab.d.raidsUp()) {
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
        EndgameData.Pools p = tab.d.p();
        if (p.source() == RaidPool.Source.NONE || !p.wynnventory() && tab.d.poolSeen() < 0) {
            ink(g, fit(L.t("wynnlabs.endgame.pool_never"), w), x, y + 2, Theme.INK_3);
            List<Component> tip = new ArrayList<>(List.of(title(L.t("wynnlabs.endgame.pool"), ChatFormatting.GOLD),
                    grey(L.t("wynnlabs.endgame.pool_never_tip"))));
            String why = reason(p.status());
            if (why != null) {
                tip.add(dark(why));
            }
            tab.tips.add(new Tip(x, y, w, h, tip));
            return y + h;
        }
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        if (hover) {
            Kit.row(g, x, y, w, h, 0, true);
        }
        boolean stale = tab.d.poolStale();
        int c = stale ? Theme.INK_3 : Theme.ACCENT;
        Art.icon(g, "clock", x + 2, y + 2, 8, c);
        int tx = x + 13;
        int end = x + w - 13;
        String source = L.t(p.wynnventory() ? "wynnlabs.endgame.src_wynnventory" : "wynnlabs.endgame.src_game");
        String head = L.t("wynnlabs.endgame.pool_head") + " · ";
        String age = tab.d.poolAge() == null ? "" : L.cap(tab.d.poolAge());
        if (large) {
            // every pool aspect of the class, with its raids' short names when from Wynnventory
            List<String> names = new ArrayList<>();
            for (PoolAspect a : tab.d.pool()) {
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
                if (cur.isEmpty() || tab.font.width(add) <= end - tx) {
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
            String count = L.t("wynnlabs.endgame.pool_new", tab.d.poolMarked());
            String after = stale ? L.t("wynnlabs.endgame.pool_stale") : "";
            String[] left = {head, source, (age.isEmpty() ? "" : " · " + age) + " · "};
            int lw = 0;
            for (String s : left) {
                lw += tab.font.width(s);
            }
            int cw = tab.font.width(count) + tab.font.width(after);
            int cx = Math.min(tx + lw, end - cw);
            ink(g, count, cx, y + 2, stale ? Theme.INK_3 : Theme.GOOD);
            if (!after.isEmpty()) {
                ink(g, after, cx + tab.font.width(count), y + 2, Theme.INK_3);
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
                int lw = tab.font.width("  ");
                int lines = 0;
                for (int i = 0; i < in.size(); i++) {
                    PoolAspect a = in.get(i);
                    String tg = tagText(a.name());
                    MutableComponent part = Component.literal(shortAspect(a.name())).withStyle(tierChat(a.rarity()));
                    if (tg != null) {
                        part.append(Component.literal(" " + tg).withStyle(ChatFormatting.GREEN));
                    }
                    String sep = i + 1 < in.size() ? ", " : "";
                    int pw = tab.font.width(part.getString() + sep);
                    if (lw + pw > TIP_W && lw > tab.font.width("  ")) {
                        if (++lines == 2) {
                            line.append(Component.literal("+" + (in.size() - i)).withStyle(ChatFormatting.GRAY));
                            break;
                        }
                        tip.add(line);
                        line = Component.literal("  ");
                        lw = tab.font.width("  ");
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
            tip.add(title(L.t("wynnlabs.endgame.tip_pool_head", tab.d.poolAge()), ChatFormatting.GOLD));
            for (PoolAspect a : tab.d.pool()) {
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
        tab.acts.add(new Act(x, y, w, h, () -> {
            tab.filter = EndgameTab.F_POOL;
            tab.listScroll = 0;
            tab.section = EndgameTab.SEC_ASPECTS;
        }, "endgame:pool"));
        tab.tips.add(new Tip(x, y, w, h, tip));
        return y + h;
    }
}
