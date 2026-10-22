package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.HomeTab.Host;
import io.github.anilken.wynnlabs.HomeTab.In;
import io.github.anilken.wynnlabs.HomeTab.PlanLine;
import io.github.anilken.wynnlabs.HomeTab.Road;
import io.github.anilken.wynnlabs.HomeTab.SlotView;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static io.github.anilken.wynnlabs.HomeTab.CHOSEN;
import static io.github.anilken.wynnlabs.HomeTab.age;

/**
 * The gear panel's lines round the figure: the offer to calculate the road map (or its
 * progress), the out-of-date strip, the plan's buttons, and the build bar or the plan's bar.
 */
final class HomeGearBars extends HomePart {

    /**
     * No road map yet, in the stand's place: a small icon, "Suggestions not calculated yet", the
     * gold "Calculate suggestions" button and a faint "takes a few seconds" line; while the first
     * one is built, its bar and percentage with Cancel. Nothing is built until the button is pressed.
     */
    void noMap(GuiGraphics g, Host host, int x, int y, int w, int h, Road road, boolean compact, int mx, int my) {
        int cx = x + w / 2;
        if (road.building()) {
            String title = L.t("wynnlabs.home.loading");
            String sub = L.t("wynnlabs.home.loading_sub", Math.round(road.progress() * 100));
            int need = 11 + 10 + 13 + 18;
            int ty = y + Math.max(0, (h - need) / 2);
            Component t = Component.literal(fit(title, w, true)).withStyle(UiFont::bold);
            Kit.text(g, font, t, cx - font.width(t) / 2, ty, Theme.INK);
            int bw = Math.min(200, w - 16);
            Kit.tealBar(g, cx - bw / 2, ty + 14, bw, 5, road.progress(), true);
            String s = fit(sub, w);
            Kit.text(g, font, s, cx - font.width(s) / 2, ty + 25, Theme.INK_3);
            int by = ty + 38;
            boolean hov = mx >= cx - 7 && mx < cx + 7 && my >= by && my < by + 14;
            Kit.button(g, cx - 7, by, 14, 14, hov ? Kit.HOVER : Kit.REST);
            Art.icon(g, "x", cx - 4, by + 3, 8, Kit.buttonText(hov ? Kit.HOVER : Kit.REST));
            host.area(cx - 7, by, 14, 14, null, List.of(L.c("wynnlabs.pick.cancel").withStyle(ChatFormatting.WHITE),
                    L.c("wynnlabs.home.build_cancel_tip").withStyle(ChatFormatting.GRAY)), "#qcancelbuild:");
            return;
        }
        String title = L.t("wynnlabs.home.none_title");
        String button = L.t("wynnlabs.home.none_button");
        String faint = L.t("wynnlabs.home.none_sub");
        boolean icon = !compact && h >= 80;
        int need = (icon ? 22 : 0) + 12 + 18 + 12;
        int ty = y + Math.max(0, (h - need) / 2);
        if (icon) {
            Art.icon(g, "sparkles", cx - 8, ty, 16, Theme.INK_3);
            ty += 22;
        }
        Component t = Component.literal(fit(title, w, true)).withStyle(UiFont::bold);
        Kit.text(g, font, t, cx - font.width(t) / 2, ty, Theme.INK);
        int bw = Math.min(w, font.width(button) + 23);
        int bx = cx - bw / 2;
        int by = ty + 13;
        boolean hov = mx >= bx && mx < bx + bw && my >= by && my < by + 14;
        Kit.button(g, bx, by, bw, 14, Kit.ON);
        if (hov) {
            Kit.outline(g, bx, by, bw, 14, Theme.GOLD_EDGE);
        }
        Art.icon(g, "player-play", bx + 6, by + 3, 8, Kit.buttonText(Kit.ON));
        g.drawString(font, fit(button, bw - 23), bx + 17, by + 3, Kit.buttonText(Kit.ON), false);
        host.area(bx, by, bw, 14, null, List.of(L.c("wynnlabs.home.none_button").withStyle(ChatFormatting.WHITE),
                L.c("wynnlabs.home.none_tip").withStyle(ChatFormatting.GRAY)), "#qbuild:");
        String f = fit(faint, w);
        Kit.text(g, font, f, cx - font.width(f) / 2, by + 18, Theme.INK_3);
    }

    /**
     * A slim strip at the panel's top when the road map is out of date: "You're Lv 67 now ·
     * suggestions are out of date" (or "Your gear changed"), with its age when it is old, and
     * "⟳ Refresh". The longest wording that fits, never cut.
     */
    void staleStrip(GuiGraphics g, Host host, int x, int y, int w, Roadmap.Stale s, int mx, int my) {
        String what = s.level() ? L.t("wynnlabs.home.stale_level", s.now()) : L.t("wynnlabs.home.stale_gear");
        String age = age(s.ageMs());
        String refresh = L.t("wynnlabs.home.stale_refresh");
        int full = font.width(refresh) + 23;
        int bw = w - 120 >= full ? full : 16;
        int room = w - bw - 4 - 16;
        String[] tries = age.isEmpty() ? new String[] {what, L.t("wynnlabs.home.stale_short")}
                : new String[] {what + " · " + age, what, L.t("wynnlabs.home.stale_short")};
        String text = tries[tries.length - 1];
        for (String c : tries) {
            if (font.width(c) <= room) {
                text = c;
                break;
            }
        }
        LayoutCheck.part(g.pose(), x, y, w, 14, "strip", "the out-of-date strip");
        Kit.seal(g, x, y, w - bw - 3, 14, 0xFFF0D090);
        Art.icon(g, "alert-triangle", x + 4, y + 3, 8, Theme.WARN);
        Kit.text(g, font, fit(text, room), x + 15, y + 3, Theme.WARN);
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(what + (age.isEmpty() ? "" : " · " + age)).withStyle(ChatFormatting.GOLD));
        tip.add(L.c("wynnlabs.home.stale_tip").withStyle(ChatFormatting.GRAY));
        host.area(x, y, w - bw - 3, 14, null, tip, null);
        int bx = x + w - bw;
        boolean hov = mx >= bx && mx < bx + bw && my >= y && my < y + 14;
        int state = hov ? Kit.HOVER : Kit.REST;
        Kit.button(g, bx, y, bw, 14, state);
        if (bw == 16) {
            Art.icon(g, "refresh", bx + 4, y + 3, 8, Kit.buttonText(state));
        } else {
            Art.icon(g, "refresh", bx + 6, y + 3, 8, Kit.buttonText(state));
            g.drawString(font, refresh, bx + 17, y + 3, Kit.buttonText(state), false);
        }
        host.area(bx, y, bw, 14, null, List.of(Component.literal(refresh).withStyle(ChatFormatting.WHITE),
                L.c("wynnlabs.home.refresh_tip").withStyle(ChatFormatting.GRAY)), "#qrefresh:");
    }

    /**
     * The plan's buttons, centred in {@code w} from {@code x}: Save (purple: "Saved ✓" once it is
     * in My builds, a click then updates that copy), Edit (the Choose-items window with its items
     * and strip) and Remove (as the Plan pill's ✕, with Undo). Text when all of it fits, else
     * icons with their names in the tooltips. {@code folded}: the title row's icons, Save and Edit.
     */
    void planButtons(GuiGraphics g, Host host, In in, int x, int y, int w, boolean folded, int mx, int my) {
        boolean saved = Plans.isSaved(Plans.current());
        String[][] b = {
            {"bookmark", L.t(saved ? "wynnlabs.homev.plan_saved" : "wynnlabs.homev.plan_save"), "#qplansave:",
                saved ? "wynnlabs.homev.plan_saved_tip" : "wynnlabs.homev.plan_save_tip", "1"},
            {"edit", L.t("wynnlabs.homev.plan_edit"), "#qplanedit:", "wynnlabs.homev.plan_edit_tip", "0"},
            {"x", L.t("wynnlabs.homev.plan_remove"), "#qplanremove:", "wynnlabs.homev.plan_remove_tip", "0"}};
        int n = folded ? 2 : 3;
        int full = 0;
        for (int i = 0; i < n; i++) {
            full += font.width(b[i][1]) + 23 + (i > 0 ? 3 : 0);
        }
        boolean words = !folded && full <= w;
        int total = words ? full : n * 16 + (n - 1) * 3;
        int bx = x + (w - total) / 2;
        for (int i = 0; i < n; i++) {
            int bw = words ? font.width(b[i][1]) + 23 : 16;
            boolean hov = mx >= bx && mx < bx + bw && my >= y && my < y + 14;
            boolean primary = b[i][4].equals("1");
            int ink;
            if (primary) {
                Kit.primary(g, bx, y, bw, 14, hov);
                ink = Theme.CREAM;
            } else {
                Kit.button(g, bx, y, bw, 14, hov ? Kit.HOVER : Kit.REST);
                ink = Kit.buttonText(hov ? Kit.HOVER : Kit.REST);
            }
            if (words) {
                Art.icon(g, b[i][0], bx + 6, y + 3, 8, ink);
                g.drawString(font, b[i][1], bx + 17, y + 3, ink, false);
            } else {
                Art.icon(g, b[i][0], bx + 4, y + 3, 8, ink);
            }
            LayoutCheck.part(g.pose(), bx, y, bw, 14, "planrow", "the plan's " + b[i][2] + " button");
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(b[i][1]).withStyle(ChatFormatting.WHITE));
            tip.add(L.c(b[i][3]).withStyle(ChatFormatting.GRAY));
            if (b[i][2].equals("#qplanremove:") && !saved) {
                tip.add(L.c("wynnlabs.homev.plan_unsaved").withStyle(ChatFormatting.GOLD));
            }
            host.area(bx, y, bw, 14, null, tip, b[i][2]);
            bx += bw + 3;
        }
    }

    /**
     * Under the stand: the build score now against the score with the selected milestone's
     * whole set (one bar, gold to now and green on to after), and "3 upgrades: +13% · ≈1.4 LE"
     * under it; compact, the line first and the bar after it on the same line. Without a set
     * worked out, the slots' gains summed and no bar.
     */
    void buildBar(GuiGraphics g, In in, Host host, int x, int y, int w, boolean compact, int count, double gains) {
        if (Plans.removedUndoable(System.currentTimeMillis()) != null) {
            // a plan just removed: "Plan removed · Undo" for a few seconds, as after deleting a saved build
            String gone = L.t("wynnlabs.homev.plan_removed") + " · ";
            String undo = L.t("wynnlabs.saved.undo");
            int ly = compact ? y : y + 11;
            int lx = x + (w - font.width(gone) - font.width(undo)) / 2;
            Kit.text(g, font, gone, lx, ly, Theme.INK_3);
            Kit.text(g, font, undo, lx + font.width(gone), ly, Theme.LINK);
            host.area(lx + font.width(gone), ly - 1, font.width(undo), 10, null,
                    List.of(L.c("wynnlabs.homev.plan_undo_tip").withStyle(ChatFormatting.GRAY)), "#qplanundo:");
            return;
        }
        if (count == 0) {
            String all = fit(L.t("wynnlabs.homev.all_fine"), w - 10);
            int ax = x + (w - font.width(all) - 10) / 2;
            int ly = compact ? y : y + 11;
            Art.icon(g, "check", ax, ly, 8, Theme.GOOD);
            Kit.text(g, font, all, ax + 10, ly, Theme.GOOD);
            return;
        }
        double now = in.nowScore();
        double after = in.afterScore();
        boolean scored = !Double.isNaN(now) && !Double.isNaN(after) && now > 0 && after > 0;
        double gain = scored ? (after / now - 1) * 100 : gains;
        List<Component> tip = new ArrayList<>();
        tip.add(scored ? L.c("wynnlabs.homev.bar_tip", Math.round(now), Math.round(after)).withStyle(ChatFormatting.WHITE)
                : L.c("wynnlabs.homev.bar_tip_sum").withStyle(ChatFormatting.GRAY));
        // the price: the Trade Market's for the pieces it lists (a merchant's or a drop has none)
        double price = 0;
        int priced = 0;
        for (SlotView s : in.slots()) {
            if (s.gear() == null || s.gear().price() == null) {
                continue;
            }
            Prices.Price p = Prices.of(s.gear().info().name());
            if (p != null && p.typical() != null) {
                price += p.typical();
                priced++;
            }
        }
        String line = L.n("wynnlabs.homev.after", count, count, "+" + RoadmapScreen.pct(Math.round(gain)));
        if (priced > 0) {
            line += " · ≈" + Prices.fmt(price);
            tip.add(L.cn("wynnlabs.homev.price_tip", priced, Prices.fmt(price), priced).withStyle(ChatFormatting.GOLD));
        }
        int bh;
        int bx;
        int by;
        int bw;
        if (compact) {
            // one line: the text, then the bar to the right edge, centred on the text
            line = fit(line, scored ? w * 2 / 3 : w);
            Kit.text(g, font, line, x, y, Theme.GOOD);
            bh = 4;
            bx = x + font.width(line) + 6;
            by = y + 2;
            bw = x + w - bx;
        } else {
            line = fit(line, w);
            Kit.text(g, font, line, x + (w - font.width(line)) / 2, y + 11, Theme.GOOD);
            bh = 6;
            bx = x;
            by = y;
            bw = w;
        }
        if (scored && bw > 8) {
            double frac = Math.max(0, Math.min(1, now / Math.max(now, after)));
            int fw = (int) Math.round(bw * frac);
            g.fill(bx, by, bx + fw, by + bh, Theme.GOLD_DEEP);
            g.fill(bx + fw, by, bx + bw, by + bh, HomeSlots.UP);
        }
        host.area(x, y, w, compact ? 9 : 20, null, tip, null);
    }

    /**
     * Under the stand with the Plan pill selected: how far you are with the plan, one bar (purple
     * to the share of its pieces you wear) over "Bullseye build · 5/9 worn · ≈27 EB left", or
     * "Build complete"; the better-build hint at the line's right end when a level-up found one
     * (a click opens it, a right-click hides it). Compact: the line, then the hint or the bar.
     */
    void planBar(GuiGraphics g, In in, Host host, int x, int y, int w, boolean compact) {
        PlanLine p = in.plan();
        boolean done = p.total() > 0 && p.worn() >= p.total();
        // the longest forms that fit side by side, never cut: the line with the plan's name, then
        // the count alone; the hint whole, then short (the bar gives way to them when compact)
        String left = p.left() > 0 && !Double.isNaN(p.left()) ? " · " + L.t("wynnlabs.homev.plan_left", Prices.fmt(p.left())) : "";
        // a plan for a level above yours (a chosen item needs it) says so
        String pf = p.plannedFor() > 0 ? " · " + L.t("wynnlabs.homev.plan_for", p.plannedFor()) : "";
        String[] lines = done ? new String[] {L.t("wynnlabs.homev.plan_done") + pf, L.t("wynnlabs.homev.plan_done")}
                : new String[] {L.t("wynnlabs.homev.plan_line", p.name(), p.worn(), p.total()) + pf + left,
                    L.t("wynnlabs.homev.plan_line_short", p.worn(), p.total()) + pf + left,
                    L.t("wynnlabs.homev.plan_line_short", p.worn(), p.total()) + pf,
                    L.t("wynnlabs.homev.plan_line_short", p.worn(), p.total())};
        String[] hints = p.better() <= 0 ? new String[] {null}
                : new String[] {L.t("wynnlabs.homev.plan_better", p.better()), L.t("wynnlabs.homev.plan_better_short", p.better())};
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(p.name()).withStyle(ChatFormatting.LIGHT_PURPLE).withStyle(UiFont::bold));
        tip.add(L.c("wynnlabs.homev.plan_tip", p.worn(), p.total()).withStyle(ChatFormatting.GRAY));
        tip.add(L.c("wynnlabs.homev.plan_fixed_tip").withStyle(ChatFormatting.DARK_GRAY));
        int ty = compact ? y : y + 11;
        int icon = done ? 10 : 0;
        String line = lines[lines.length - 1];
        String h = hints[hints.length - 1];
        search:
        for (String hc : hints) {
            for (String lc : lines) {
                int need = icon + font.width(lc) + (hc == null ? 0 : 8 + font.width(hc)) + (compact && hc == null ? 30 : 0);
                if (need <= w) {
                    line = lc;
                    h = hc;
                    break search;
                }
            }
        }
        int hw = h == null ? 0 : font.width(h);
        int room = w - (h == null ? 0 : hw + 8) - icon;
        line = fit(line, room);
        int lx = compact || h != null ? x : x + (w - font.width(line) - icon) / 2;
        if (done) {
            Art.icon(g, "check", lx, ty, 8, Theme.GOOD);
            lx += 10;
        }
        Kit.text(g, font, line, lx, ty, done ? Theme.GOOD : CHOSEN);
        int lineEnd = lx + font.width(line);
        if (h != null) {
            int hx = x + w - font.width(h);
            Kit.text(g, font, h, hx, ty, Theme.LINK);
            host.area(hx, ty - 1, font.width(h), 10, null, List.of(L.c("wynnlabs.homev.plan_better_tip", p.better()).withStyle(ChatFormatting.AQUA),
                    L.c("wynnlabs.homev.plan_better_click").withStyle(ChatFormatting.DARK_GRAY)), "#qhint:");
        }
        // the bar: the share of the plan you wear, in the chosen items' purple
        double frac = p.total() <= 0 ? 0 : Math.min(1, p.worn() / (double) p.total());
        int bx;
        int by;
        int bw;
        int bh;
        if (compact) {
            bx = lineEnd + 6;
            by = y + 2;
            bw = h != null ? 0 : x + w - bx;
            bh = 4;
        } else {
            bx = x;
            by = y;
            bw = w;
            bh = 6;
        }
        if (bw > 8) {
            // the kit's bar: green when complete, else the chosen items' purple
            if (done) {
                Kit.bar(g, bx, by, bw, bh, frac, Theme.GREEN_HI, Theme.GREEN, Theme.GREEN_LO, false);
            } else {
                Kit.bar(g, bx, by, bw, bh, frac, 0xFFB79FE0, 0xFF8A6CC0, 0xFF5A3A8A, false);
            }
        }
        host.area(x, y, h == null ? w : Math.max(1, x + w - hw - 8 - x), compact ? 9 : 20, null, tip, null);
    }
}
