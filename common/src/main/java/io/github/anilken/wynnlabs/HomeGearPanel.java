package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.HomeTab.Host;
import io.github.anilken.wynnlabs.HomeTab.In;
import io.github.anilken.wynnlabs.HomeTab.Mark;
import io.github.anilken.wynnlabs.HomeTab.Road;
import io.github.anilken.wynnlabs.HomeTab.SlotView;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static io.github.anilken.wynnlabs.HomeTab.age;
import static io.github.anilken.wynnlabs.HomeTab.dots;

/**
 * The home tab's gear panel: its title row with the refresh, level, My builds and Choose
 * items buttons, and the layout of the figure, its slots and the bar under them.
 */
final class HomeGearPanel extends HomePart {

    /** The panel's padding inside its card, the same on every side. */
    private static final int PAD = 6;
    /**
     * The name zone beside each slot column: at least this share (in percent) of the room between
     * the two columns, at least {@link #PILL_ZONE}, and at most {@link #ZONE_CAP} (the large menu
     * {@link #ZONE_LARGE_CAP}) once the figure has its size.
     */
    private static final int ZONE_SHARE = 28;
    private static final int ZONE_CAP = 70;
    private static final int ZONE_LARGE_CAP = 90;
    /** Compact: the zone beside each column holds only its pills, the widest gain ("+142%") included. */
    private static final int PILL_ZONE = 40;

    /** The plan's button row under the figure: its 14px and the gaps above and below it. */
    private static final int PLAN_ROW = 18;

    /** The out-of-date strip's height with its gap under it. */
    private static final int STRIP_H = 17;

    private final HomeTab tab;
    final HomeSlots slots;
    final HomeGearBars bars;

    HomeGearPanel(HomeTab tab) {
        this.tab = tab;
        this.slots = new HomeSlots(tab);
        this.bars = new HomeGearBars();
    }

    void panel(GuiGraphics g, In in, Host host, int x, int y, int w, int h, boolean compact, boolean large,
            int mx, int my) {
        card(g, x, y, w, h, 0, false);
        if (DevHooks.ON) {
            DevHooks.part("gear", g.pose(), x, y, w, h);
        }
        // the title line: the name, the refresh button, the level buttons on the right, PAD in from both edges
        Component title = Component.literal(L.t("wynnlabs.homev.gear")).withStyle(UiFont::bold);
        int ty = y + PAD + 1;
        Art.icon(g, "hanger", x + PAD, ty, 8, Theme.INK_2);
        // compact: the hanger alone, so the buttons, the level pills and a Plan pill keep their room
        boolean named = !compact;
        if (named) {
            Kit.text(g, font, title, x + PAD + 11, ty, Theme.INK);
        }
        int tx = x + PAD + 11 + (named ? font.width(title) + 5 : 0);
        // an out-of-date road map: a strip over the stand where the stand keeps its room, else
        // (compact, or a short panel) an amber badge on the refresh button, its tooltip saying why
        Road early = in.road();
        boolean staleNow = early != null && early.stale() != null && !early.none() && in.plan() == null;
        int slotNeed = 4 * (large ? 36 : 22) + 9;
        boolean stripRoom = !compact && (y + h - PAD - 20 - 4) - (ty + 15 + STRIP_H) >= slotNeed;
        String staleTip = null;
        if (staleNow && !stripRoom) {
            String age = age(early.stale().ageMs());
            staleTip = (early.stale().level() ? L.t("wynnlabs.home.stale_level", early.stale().now()) : L.t("wynnlabs.home.stale_gear"))
                    + (age.isEmpty() ? "" : " · " + age);
        }
        tx += host.refresh(g, tx, ty - 3, mx, my, staleTip);
        // the hanger's drawing starts 1px inside its 8px: the buttons end 1px further in to match
        int right = x + w - PAD - 1;
        int titleRoom = right - tx - 2;
        // "My builds" and "Choose items" left of the level buttons; only their icons when the row is tight
        // "My builds" and "Choose items" as icons with their counts, left of the level buttons
        int pickW = countW(in.chosen());
        int savedW = countW(in.saved());
        int pills = host.levelPills(g, ty, right, titleRoom - pickW - savedW - 8);
        int bx = pills - 4 - pickW;
        if (bx >= tx) {
            pickButton(g, host, bx, ty - 3, pickW, in, mx, my);
            bx -= 3 + savedW;
            if (bx >= tx) {
                savedButton(g, host, bx, ty - 3, savedW, in, mx, my);
            }
        }
        // the title row's free room, where the plan's buttons fold when the panel has no room for their row
        int foldLeft = tx;
        int foldRight = bx - 3;

        List<SlotView> armour = new ArrayList<>();
        List<SlotView> trinkets = new ArrayList<>();
        SlotView weapon = null;
        int count = 0;
        double gains = 0;
        for (SlotView s : in.slots()) {
            if (s.index() < 4) {
                armour.add(s);
            } else if (s.index() == 4) {
                weapon = s;
            } else {
                trinkets.add(s);
            }
            if (s.gear() != null) {
                count++;
                gains += Double.isNaN(s.gain()) ? 0 : s.gain();
            }
        }
        // the stand's room between the title and the build bar, 4px clear of each
        int barH = compact ? 9 : 20;
        int top = ty + 11 + 4;
        Road road = in.road();
        if (road != null && road.none() && in.plan() == null) {
            // no road map yet: an offer to calculate it, or its progress, in the stand's place
            bars.noMap(g, host, x + PAD + 1, top, w - 2 * PAD - 2, y + h - PAD - top, road, compact, mx, my);
            return;
        }
        if (staleNow && stripRoom) {
            // the strip's height comes off the stand's room before the slots, stand and bar are placed
            bars.staleStrip(g, host, x + PAD + 1, top, w - 2 * PAD - 2, road.stale(), mx, my);
            top += STRIP_H;
        }
        int bottom = y + h - PAD - barH - 4;
        int areaH = bottom - top;
        int s = large ? 36 : compact ? 18 : 22;
        boolean names = !compact;
        // the rows: tall enough for a pill and two lines of name, else one, else none
        int lines = names ? 2 : 0;
        while (lines > 0 && 4 * s + 3 * Math.max(3, HomeSlots.block(lines) - s + 3) > areaH) {
            lines--;
        }
        int sg = Math.max(3, Math.max(HomeSlots.block(lines) - s + 3, Math.min(large ? 24 : 6, (areaH - 4 * s) / 3)));
        sg = Math.min(sg, Math.max(3, (areaH - 4 * s) / 3));
        int colY = top + Math.max(0, (areaH - 4 * s - 3 * sg) / 2);
        // beside each column a zone for its pills and (not compact) its names, its width from the
        // panel's alone: never measured from the names or the pills, so the figure is the same for
        // every character and the names fit into the zone (one line, two, or two ending in "…")
        int edge = PAD + 1 + s + HomeSlots.MARK_GAP + 1;
        int room = w - 2 * edge - 8;
        int zoneMin = names ? Math.max(PILL_ZONE, room * ZONE_SHARE / 100) : PILL_ZONE;
        int zoneCap = names ? large ? ZONE_LARGE_CAP : ZONE_CAP : PILL_ZONE;
        // the figure from the panel alone: as tall as the room between the title and the build bar
        // allows, as wide as the room between the two zones
        int u = Math.max(1, Math.min((areaH - 1) / 32, (room - 2 * zoneMin - 1) / 16));
        // the plan's view: a row of its buttons under the figure, above the bar; the figure gives
        // up that row's height (the slot columns keep theirs), or the buttons fold into the title row
        int figH = areaH;
        boolean planRow = false;
        if (in.plan() != null && !compact) {
            int u2 = Math.max(1, Math.min((areaH - PLAN_ROW - 1) / 32, (room - 2 * zoneMin - 1) / 16));
            int zone2 = Math.max(0, Math.min(zoneCap, (room - 16 * u2 - 1) / 2));
            if ((areaH - PLAN_ROW - 1) / 32 >= 1 && room - 2 * zone2 >= 3 * 16 + 6) {
                planRow = true;
                u = u2;
                figH = areaH - PLAN_ROW;
            }
        }
        int figW = 16 * u + 1;
        // the width the figure leaves goes back to the zones, the same on both sides
        int zone = Math.max(0, Math.min(zoneCap, (room - figW) / 2));
        int midX = x + edge + zone + 4;
        int midW = room - 2 * zone;
        int l = midX + Math.max(0, (midW - figW) / 2);
        int standY = top + Math.max(0, (figH - 32 * u - 1) / 2);
        // which parts are tinted: a chosen item purple, an upgrade (or a plan's piece to get) green,
        // a worn piece wood, nothing worn pale
        int[] state = new int[9];
        for (SlotView v : in.slots()) {
            state[v.index()] = v.mark() == Mark.CHOSEN || v.mark() == Mark.DONE_CHOSEN ? 3
                    : v.gear() != null || v.mark() == Mark.GET ? 2 : v.worn().isEmpty() ? 0 : 1;
        }
        slots.stand(g, l + 8 * u, standY, u, state);
        tab.standBox[0] = l;
        tab.standBox[1] = standY;
        tab.standBox[2] = 16 * u + 1;
        tab.standBox[3] = 32 * u + 1;
        tab.standBox[4] = w;
        tab.standBox[5] = areaH;
        for (int i = 0; i < armour.size(); i++) {
            slots.slot(g, host, armour.get(i), x + PAD + 1, colY + i * (s + sg), s, true, zone, lines, large);
        }
        for (int i = 0; i < trinkets.size(); i++) {
            slots.slot(g, host, trinkets.get(i), x + w - PAD - 1 - s, colY + i * (s + sg), s, false, zone, lines, large);
        }
        if (weapon != null) {
            // drawn after the figure, so it lies over the hand: the slot centred on the hand's last two units
            int wx = l + 14 * u - s / 2;
            int wy = standY + 19 * u - s / 2;
            slots.weaponSlot(g, host, weapon, wx, wy, s, x + w - edge - zone - 4, bottom, names, large);
        }
        if (in.plan() != null) {
            if (planRow) {
                bars.planButtons(g, host, in, midX, bottom - 14, midW, false, mx, my);
            } else if (foldRight - foldLeft >= 2 * 16 + 3) {
                // folded: Save and Edit as icons left of My builds (Remove is the Plan pill's ✕)
                bars.planButtons(g, host, in, foldRight - (2 * 16 + 3), ty - 3, 2 * 16 + 3, true, mx, my);
            }
        }
        // the preview's check: the bar's whole line is one part, which no slot, pill or name may touch
        LayoutCheck.part(g.pose(), x + PAD + 1, y + h - PAD - barH, w - 2 * PAD - 2, barH, "bar", "the build bar");
        if (in.plan() != null) {
            bars.planBar(g, in, host, x + PAD + 1, y + h - PAD - barH, w - 2 * PAD - 2, compact);
        } else {
            bars.buildBar(g, in, host, x + PAD + 1, y + h - PAD - barH, w - 2 * PAD - 2, compact, count, gains);
        }
    }

    /** An icon button's width with its count beside the icon (none for 0). */
    private int countW(int n) {
        return n > 0 ? 16 + font.width(String.valueOf(n)) + 2 : 16;
    }

    /**
     * "Choose items" as a purple button with a plus and the count of chosen items: opens the
     * window that builds around the items you pick; a right-click clears them. Three cycling
     * dots instead of the plus while a search runs. Its name and use are in the tooltip.
     */
    private void pickButton(GuiGraphics g, Host host, int x, int y, int w, In in, int mx, int my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 14;
        Kit.primary(g, x, y, w, 14, hover);
        if (in.searching()) {
            dots(g, x + 4, y + 7, Theme.CREAM);
        } else {
            Art.icon(g, "plus", x + 4, y + 3, 8, Theme.CREAM);
        }
        if (in.chosen() > 0) {
            g.drawString(font, String.valueOf(in.chosen()), x + 13, y + 3, Theme.CREAM, false);
        }
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(in.chosen() > 0 ? L.t("wynnlabs.homev.pick_n", in.chosen()) : L.t("wynnlabs.homev.pick"))
                .withStyle(ChatFormatting.WHITE));
        tip.add(L.c("wynnlabs.homev.pick_tip").withStyle(ChatFormatting.LIGHT_PURPLE));
        for (String n : Locks.names()) {
            tip.add(Component.literal("  " + n).withStyle(ChatFormatting.GRAY));
        }
        if (in.searching()) {
            tip.add(L.c("wynnlabs.homev.pick_running").withStyle(ChatFormatting.GRAY));
        }
        tip.add(L.c("wynnlabs.homev.pick_clear_tip").withStyle(ChatFormatting.DARK_GRAY));
        host.area(x, y, w, 14, null, tip, "#qpick:");
    }

    /** "My builds" as a wood button with a bookmark and the count of saved builds. */
    private void savedButton(GuiGraphics g, Host host, int x, int y, int w, In in, int mx, int my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 14;
        int state = hover ? Kit.HOVER : Kit.REST;
        Kit.button(g, x, y, w, 14, state);
        Art.icon(g, "bookmark", x + 4, y + 3, 8, Kit.buttonText(state));
        if (in.saved() > 0) {
            g.drawString(font, String.valueOf(in.saved()), x + 13, y + 3, Kit.buttonText(state), false);
        }
        host.area(x, y, w, 14, null, List.of(Component.literal(L.t("wynnlabs.homev.saved_n", in.saved())).withStyle(ChatFormatting.WHITE),
                L.c("wynnlabs.homev.saved_tip").withStyle(ChatFormatting.LIGHT_PURPLE)), "#qsaved:");
    }
}
