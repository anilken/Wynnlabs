package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.AspectEntry;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.PoolAspect;
import io.github.anilken.wynnlabs.model.RankedAspect;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** The Endgame tab's aspect list: header, filter chips, ranked rows (or grouped by raid for the pool filter). */
final class EndgameAspectList extends EndgamePanel {

    EndgameAspectList(EndgameTab tab) {
        super(tab);
    }

    /** The aspect list: header, filter chips, ranked rows with their own scroll, and the hint. */
    void aspects(GuiGraphics g, int x, int y, int w, int h, boolean large, boolean compact, int mx, int my) {
        int bottom = y + h;
        int ry = y;
        List<RankedAspect> all = tab.d.ranked();
        int total = all.size();
        // the header: title, then class · archetype · owned/total on the right
        String count = (tab.d.ownedCount() < 0 ? "?" : String.valueOf(tab.d.ownedCount())) + "/" + total;
        String arch = tab.d.archetype() == null ? "" : tab.d.archetype() + (tab.d.guessed() ? "?" : "");
        String who = (compact ? "" : nice(tab.d.klass()) + (arch.isEmpty() ? "" : " · ")) + arch;
        String rt = (who.isEmpty() ? "" : who + " · ") + count;
        if (!compact) {
            // the room right of the title: the class, then the archetype give way before the
            // count is ever cut (the class shows in the icon and the tooltip)
            int room = w - (tab.font.width(L.t("wynnlabs.endgame.aspects")) + 3 + 5 + 12) - 16;
            if (tab.font.width(rt) > room && !arch.isEmpty()) {
                rt = arch + " · " + count;
            }
            if (tab.font.width(rt) > room) {
                rt = count;
            }
            String rf = fit(rt, room);
            int rtx = x + w - tab.font.width(rf);
            boolean icon = tab.d.klass() != Klass.NONE && WynnTex.classIcon(g, tab.d.klass().name(), rtx - 12, y - 1, 10);
            ink(g, rf, rtx, y, Theme.INK_3);
            Kit.section(g, tab.font, L.t("wynnlabs.endgame.aspects"), x, y, rtx - (icon ? 16 : 6) - x);
            tab.tips.add(new Tip(rtx, y - 1, x + w - rtx, 10, headerTip()));
            ry += 12;
        }
        if (!tab.d.aspectsUp()) {
            ink(g, fit(L.t("wynnlabs.endgame.aspects_off"), w), x, ry + 2, Theme.WARN);
            ry += 13;
        }
        if (!tab.d.dataReady() || total == 0) {
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
        if (!tab.d.known()) {
            tab.filter = tab.filter == EndgameTab.F_POOL ? EndgameTab.F_POOL : EndgameTab.F_ALL;
        }
        String compactRight = compact ? rt : "";
        int crw = compact ? tab.font.width(compactRight) + 6 : 0;
        if (tab.d.known()) {
            String[] names = {L.t("wynnlabs.endgame.f_all", total), L.t("wynnlabs.endgame.f_missing", missing),
                L.t("wynnlabs.endgame.f_owned", owned), L.t("wynnlabs.endgame.f_pool", inPool)};
            int cx = x;
            for (int i = 0; i < names.length; i++) {
                int bw = Kit.Ui.btnW(tab.font, names[i]);
                if (cx + bw > x + w - crw) {
                    break;
                }
                boolean on = tab.filter == i;
                boolean hover = mx >= cx && mx < cx + bw && my >= ry && my < ry + 14;
                int state = on ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
                Kit.button(g, cx, ry, bw, 14, state);
                g.drawString(tab.font, names[i], cx + Kit.Ui.BTN_PAD, ry + 3, Kit.buttonText(state), state != Kit.ON);
                int f = i;
                tab.acts.add(new Act(cx, ry, bw, 14, () -> {
                    tab.filter = f;
                    tab.listScroll = 0;
                }, "endgame:filter" + i));
                cx += bw + 3;
            }
        } else {
            ink(g, fit(L.t("wynnlabs.endgame.order_fit"), w - crw), x, ry + 3, Theme.INK_3);
        }
        if (compact) {
            ink(g, compactRight, x + w - tab.font.width(compactRight), ry + 3, Theme.INK_3);
            tab.tips.add(new Tip(x + w - tab.font.width(compactRight), ry, tab.font.width(compactRight), 14, headerTip()));
        }
        ry += 17;
        if (tab.d.guessed() && tab.d.archetype() != null) {
            // no archetype chosen: the list says which one it ranks for
            ink(g, fit(L.t("wynnlabs.endgame.arch_guessed", tab.d.archetype()), w), x, ry, Theme.INK_3);
            tab.tips.add(new Tip(x, ry - 1, w, 11, headerTip()));
            ry += 12;
        }
        // the rows (the pool filter with Wynnventory's pool: grouped under each raid holding them)
        List<Line> rows = tab.filter == EndgameTab.F_POOL && tab.d.p().wynnventory() ? poolGroups(all) : new ArrayList<>();
        if (!(tab.filter == EndgameTab.F_POOL && tab.d.p().wynnventory())) {
            for (RankedAspect a : all) {
                boolean keep = switch (tab.filter) {
                    case EndgameTab.F_MISSING -> a.ownedTier() == 0;
                    case EndgameTab.F_OWNED -> a.ownedTier() > 0;
                    case EndgameTab.F_POOL -> RaidPool.holds(a.aspect().name());
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
        tab.listRect = new int[] {x, listTop, w, listBottom - listTop};
        if (rows.isEmpty()) {
            ink(g, fit(L.t(tab.filter == EndgameTab.F_POOL && tab.d.poolSeen() < 0 && !tab.d.p().wynnventory() ? "wynnlabs.endgame.pool_never"
                    : "wynnlabs.endgame.list_empty"), w), x, ry + 2, Theme.INK_3);
        }
        // how far the list can scroll: the first row from which the rest fits (headers scroll with it)
        int fitH = 0;
        int from = rows.size();
        while (from > 0 && fitH + rowH(rows.get(from - 1), large) <= listBottom - listTop) {
            fitH += rowH(rows.get(from - 1), large);
            from--;
        }
        tab.listScrollMax = from;
        tab.listScroll = Math.max(0, Math.min(tab.listScroll, tab.listScrollMax));
        int shown = 0;
        for (int i = tab.listScroll; i < rows.size(); i++) {
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
        if (tab.listScrollMax > 0) {
            int trackH = listBottom - listTop;
            int knobH = Math.max(12, trackH * shown / Math.max(1, rows.size()));
            int knobY = listTop + (trackH - knobH) * tab.listScroll / tab.listScrollMax;
            g.fill(x + w - 3, listTop, x + w - 1, listBottom, 0x30624E3C);
            g.fill(x + w - 3, knobY, x + w - 1, knobY + knobH, Theme.WOOD);
        }
        if (!compact) {
            ink(g, fit(L.t("wynnlabs.screen.hint_endgame"), w), x, bottom - 9, Theme.INK_3);
        }
    }

    private List<Component> headerTip() {
        List<Component> t = new ArrayList<>();
        t.add(title(L.t("wynnlabs.endgame.aspects"), ChatFormatting.AQUA));
        if (tab.d.archetype() != null) {
            t.add(grey(L.t(tab.d.guessed() ? "wynnlabs.endgame.tip_arch_guessed" : "wynnlabs.endgame.tip_arch", tab.d.archetype())));
        } else {
            t.add(grey(L.t("wynnlabs.endgame.tip_arch_none")));
        }
        if (tab.d.ownedCount() >= 0) {
            t.add(grey(L.t("wynnlabs.endgame.tip_owned_seen", tab.d.ownedCount(), tab.d.ranked().size())));
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
        EndgameData.Pools p = tab.d.p();
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
        long seen = tab.d.p().seenOf(l.raid());
        String age = seen < 0 ? "" : ago(seen);
        String count = l.marked() > 0 ? L.t("wynnlabs.endgame.pool_new", l.marked()) : "";
        String sep = !count.isEmpty() && !age.isEmpty() ? " · " : "";
        int rw = tab.font.width(count) + tab.font.width(sep) + tab.font.width(age);
        int rx = x + w - 3 - rw;
        if (!count.isEmpty()) {
            ink(g, count, rx, ty, Theme.GOOD);
        }
        if (!age.isEmpty()) {
            ink(g, sep + age, rx + tab.font.width(count), ty, Theme.INK_3);
        }
        int nx = x + 3 + ROW_ICON + 3;
        ink(g, fit(l.raid(), rx - 6 - nx), nx, ty, Theme.INK_2);
        List<Component> tip = new ArrayList<>();
        tip.add(title(l.raid(), ChatFormatting.GOLD));
        tip.add(grey(L.t("wynnlabs.endgame.tip_group_marked", l.marked(), tab.d.p().byRaid().getOrDefault(l.raid(), List.of()).size())));
        if (seen >= 0) {
            tip.add(grey(L.t("wynnlabs.endgame.tip_group_seen", agoLong(seen))));
        }
        tip.add(dark(L.t("wynnlabs.endgame.tip_src_week", tab.d.p().week())));
        tip.add(dark(L.t("wynnlabs.endgame.link_pools")));
        String raid = l.raid();
        tab.acts.add(new Act(x, y, w, h, () -> tab.openPools(EndgameTab.TAB_RAIDS, raid), "endgame:raidlink:" + raid));
        tab.tips.add(new Tip(x, y, w, h, tip));
    }

    /** One aspect row: rank, rarity glyph, name, pool tag, equipped dot and tier pips; a click shows it in the card. */
    private void aspectRow(GuiGraphics g, RankedAspect a, int index, int x, int y, int w, int h, boolean large, int mx, int my) {
        String name = a.aspect().name();
        boolean lit = name.equals(tab.pinned);
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        Kit.row(g, x, y, w, h, index, lit || hover);
        if (lit) {
            g.fill(x + 2, y, x + w, y + h, 0x60F6E3A8);
        }
        int ty = y + 3;
        int colour = tierColour(a.aspect().rarity());
        if (a.rank() > 0) {
            String n = String.valueOf(a.rank());
            int bw = Math.max(10, tab.font.width(n) + 3);
            g.fill(x + 3, y + 2, x + 3 + bw, y + 12, Theme.BRASS);
            Kit.text(g, tab.font, n, x + 3 + (bw - tab.font.width(n) + 1) / 2, ty, Theme.INK);
        }
        aspectGlyph(g, a.aspect(), x + 16, y + 1, 12);
        // from the right: pips, the equipped dot, the pool tag; the name gets what is left
        int pw = a.ownedTier() < 0 ? tab.font.width("?") : pipsW(a.aspect().tiers());
        int px = x + w - 2 - pw;
        if (a.ownedTier() < 0) {
            Kit.text(g, tab.font, "?", px, ty, Theme.INK_3);
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
            int tw = Kit.Ui.tagW(tab.font, tg);
            tag(g, tg, rx - tw, y + 2);
            rx -= tw + 4;
        }
        int nx = x + 31;                                // 3 px clear of the 12 px icon
        Kit.text(g, tab.font, lit ? boldFit(name, rx - nx) : Component.literal(fit(name, rx - nx)), nx, ty, colour);
        if (large && h > 14) {
            ink(g, fit(a.reason(), x + w - 2 - nx), nx, y + 13, Theme.INK_3);
        }
        tab.acts.add(new Act(x, y, w, h, () -> tab.pinned = name, name));
        tab.tips.add(new Tip(x, y, w, h, aspectTip(a)));
    }

    /** An aspect's tooltip: rarity and owned tier, every tier's copies, the abilities behind its rank, the pool. */
    List<Component> aspectTip(RankedAspect a) {
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
        if (tab.d.p().wynnventory()) {
            // the raids holding it this week, each with its age, then the source
            List<String> held = RaidPool.raidsHolding(e.name());
            if (held == null || held.isEmpty()) {
                t.add(grey(L.t("wynnlabs.endgame.tip_not_in_pool")));
            } else {
                t.add(good(L.t("wynnlabs.endgame.tip_this_week")));
                for (String raid : held) {
                    long seen = tab.d.p().seenOf(raid);
                    t.add(grey("  " + raid + (seen < 0 ? "" : " · " + agoLong(seen))));
                }
            }
            t.add(dark(L.t("wynnlabs.endgame.tip_src_week", tab.d.p().week())));
            return t;
        }
        for (PoolAspect p : tab.d.pool()) {
            if (p.name().equals(e.name())) {
                t.add(Component.literal(L.t("wynnlabs.endgame.tip_in_pool", p.tier(), tab.d.poolAge())).withStyle(ChatFormatting.GREEN));
            }
        }
        t.add(dark(L.t("wynnlabs.endgame.tip_source")));
        return t;
    }
}
