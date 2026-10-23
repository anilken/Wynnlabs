package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.build.BuildSources;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.endgame.ItemIcons;
import io.github.anilken.wynnlabs.game.ItemJson;
import io.github.anilken.wynnlabs.model.Slot;
import io.github.anilken.wynnlabs.ui.HomeTab.Host;
import io.github.anilken.wynnlabs.ui.HomeTab.Mark;
import io.github.anilken.wynnlabs.ui.HomeTab.SlotView;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

import static io.github.anilken.wynnlabs.ui.HomeTab.CHOSEN;
import static io.github.anilken.wynnlabs.ui.HomeTab.CHOSEN_TEXT;
import static io.github.anilken.wynnlabs.ui.HomeTab.LONG;
import static io.github.anilken.wynnlabs.ui.HomeTab.previewLong;

/**
 * The gear panel's figure: the armour stand with its parts tinted, the slots beside it with
 * their items, pills and names, and each slot's hover and click area.
 */
final class HomeSlots extends HomePart {

    // the stand's colours: a light wooden figure on the card, its edge darker
    private static final int BODY = 0xFFC2A878;
    private static final int BODY_EMPTY = 0xFFDCCBA0;
    private static final int SLOT_WELL = 0xFF9E8763;
    private static final int SLOT_EDGE = Theme.INK_2;
    static final int UP = Theme.GREEN;

    private static final int CHOSEN_BODY = 0xFFA58BD0;

    /** Between a slot and its gain pill or tick. */
    static final int MARK_GAP = 3;
    /** The gain pill's and the tick pill's height. */
    private static final int PILL_H = 11;

    private final HomeTab tab;

    HomeSlots(HomeTab tab) {
        this.tab = tab;
    }

    /** Whose part the next pill is, for the preview's check. */
    private String partOwner;

    /** A slot's pill and name block: the pill, then {@code lines} lines of name under it. */
    static int block(int lines) {
        return lines == 0 ? PILL_H : PILL_H + 2 + lines * 10 - 1;
    }

    /**
     * A plan's missing piece's pill in {@code room} pixels: {text, icon}. "Missing" with the
     * source's icon, the icon alone where that does not fit; a chosen piece keeps "chosen" with
     * the icon, then "chosen" alone, then the icon alone.
     */
    private String[] missingPill(SlotView v, int room) {
        String icon = BuildWindow.sourceIcon(v.planned() == null ? "" : v.planned().name());
        String text = L.t(v.mark() == Mark.CHOSEN ? "wynnlabs.homev.chosen_pill" : "wynnlabs.homev.get_pill");
        if (pillW(text, icon) <= room) {
            return new String[] {text, icon};
        }
        if (v.mark() == Mark.CHOSEN && pillW(text, null) <= room) {
            return new String[] {text, null};
        }
        return new String[] {null, icon};
    }

    /** A pill's width: its text and 8px icon side by side, either alone; the icon alone is 15. */
    private int pillW(String text, String icon) {
        if (text == null) {
            return 15;
        }
        return font.width(text) + 8 + (icon == null ? 0 : 10);
    }

    /** The width of a slot's pill in {@code room} pixels: its gain, or the tick's 15; 0 for an empty slot. */
    private int markW(SlotView v, int room) {
        if (v.mark() == Mark.CHOSEN || v.mark() == Mark.GET) {
            String[] p = missingPill(v, room);
            return pillW(p[0], p[1]);
        }
        if (v.mark() == Mark.DONE || v.mark() == Mark.DONE_CHOSEN) {
            return 15;
        }
        if (gained(v)) {
            return font.width(gainText(v)) + 8;
        }
        return v.worn().isEmpty() ? 0 : 15;
    }

    /** A slot with a gain pill: an upgrade (and, in the long-names preview, the weapon). */
    private static boolean gained(SlotView v) {
        return v.gear() != null || previewLong && v.index() == 4;
    }

    private static String gainText(SlotView v) {
        if (previewLong && v.index() == 4) {
            return "+142%";
        }
        return Roadmap.pill(v.gain(), v.gear());
    }

    /** The name a slot shows: the upgrade's, else what is worn, else "empty". */
    private static String nameOf(SlotView v) {
        if (previewLong) {
            return LONG[v.index()];
        }
        if (v.showsPlanned() && v.planned() != null) {
            return v.planned().name();
        }
        return v.gear() != null ? v.gear().info().name()
                : v.worn().isEmpty() ? L.cap(L.t("wynnlabs.screen.empty")) : ItemJson.clean(v.worn().getHoverName().getString());
    }

    /**
     * The armour stand from the top centre, in units of {@code u} whole pixels and mirrored about
     * the centre column: the head 8×8 (helmet), the body 8×12 and both arms 4×12 (chestplate),
     * the legs 4×8 (leggings) and feet 4×4 (boots) under it; 16u+1 wide and 32u+1 tall. Every part has a 1px outline in its own colour darkened, and parts that touch
     * share that line. Accessories stay off the figure: their slots show them.
     * {@code state} by slot index: 2 an upgrade (green), 1 worn (wood), 0 nothing worn (pale).
     */
    void stand(GuiGraphics g, int cx, int top, int u, int[] state) {
        int l = cx - 8 * u;
        // arms and body first, the head and legs over the shared lines (each draws its own edge on them)
        part(g, l, top + 8 * u, 4 * u, 12 * u, state[1]);                              // arms
        part(g, l + 12 * u, top + 8 * u, 4 * u, 12 * u, state[1]);
        part(g, l + 4 * u, top + 8 * u, 8 * u, 12 * u, state[1]);                      // body
        part(g, l + 4 * u, top, 8 * u, 8 * u, state[0]);                               // head
        part(g, l + 4 * u, top + 20 * u, 4 * u, 8 * u, state[2]);                      // legs
        part(g, l + 8 * u, top + 20 * u, 4 * u, 8 * u, state[2]);
        part(g, l + 4 * u, top + 28 * u, 4 * u, 4 * u, state[3]);                      // feet
        part(g, l + 8 * u, top + 28 * u, 4 * u, 4 * u, state[3]);
    }

    /**
     * One part of the stand covering x..x+w and y..y+h inclusive: its colour inside a 1px outline of
     * that colour darkened. The outline's last column and row are the next part's first, so two
     * parts that touch draw one line between them.
     */
    private void part(GuiGraphics g, int x, int y, int w, int h, int state) {
        int fill = state == 3 ? CHOSEN_BODY : state == 2 ? UP : state == 0 ? BODY_EMPTY : BODY;
        g.fill(x, y, x + w + 1, y + h + 1, darker(fill));
        g.fill(x + 1, y + 1, x + w, y + h, fill);
    }

    /** A colour at 60% brightness, for the stand's outlines. */
    private static int darker(int argb) {
        int r = (argb >> 16 & 0xFF) * 3 / 5;
        int gr = (argb >> 8 & 0xFF) * 3 / 5;
        int b = (argb & 0xFF) * 3 / 5;
        return 0xFF000000 | r << 16 | gr << 8 | b;
    }

    /**
     * A flat slot square, the same on all four sides: a 1px dark edge round a brown well; with an
     * upgrade the edge is green and 2px wide (1 outside the square, 1 on its edge).
     */
    private static void slotBox(GuiGraphics g, int x, int y, int s, int frame) {
        if (frame != 0) {
            g.fill(x - 1, y - 1, x + s + 1, y + s + 1, frame);
            g.fill(x + 1, y + 1, x + s - 1, y + s - 1, SLOT_WELL);
            return;
        }
        g.fill(x, y, x + s, y + s, SLOT_EDGE);
        g.fill(x + 1, y + 1, x + s - 1, y + s - 1, SLOT_WELL);
    }

    /** The item to show for a worn piece: without the Wynncraft pack its paper becomes the slot type's plain item. */
    private static ItemStack plain(ItemStack worn, Slot type) {
        if (worn.isEmpty() || !worn.is(net.minecraft.world.item.Items.PAPER) || ItemIcons.packLoaded()) {
            return worn;
        }
        return new ItemStack(switch (type == null ? Slot.UNKNOWN : type) {
            case HELMET -> net.minecraft.world.item.Items.IRON_HELMET;
            case CHESTPLATE -> net.minecraft.world.item.Items.IRON_CHESTPLATE;
            case LEGGINGS -> net.minecraft.world.item.Items.IRON_LEGGINGS;
            case BOOTS -> net.minecraft.world.item.Items.IRON_BOOTS;
            case RING -> net.minecraft.world.item.Items.GOLD_NUGGET;
            case BRACELET -> net.minecraft.world.item.Items.IRON_NUGGET;
            case NECKLACE -> net.minecraft.world.item.Items.STRING;
            default -> net.minecraft.world.item.Items.STICK;
        });
    }

    /**
     * A slot by the stand: the upgrade's item framed green with its gain in a pill beside it, or
     * what you wear with a tick in a pill of the same size and place; an empty slot shows the
     * game's slot picture. Under the pill, {@code lines} lines of the name in {@code zone} pixels;
     * the pill and name are centred on the slot as one block. {@code left}: the slot is left of
     * the stand (its block on its right).
     */
    void slot(GuiGraphics g, Host host, SlotView v, int sx, int sy, int s, boolean left, int zone, int lines, boolean large) {
        ItemStack show = showOf(v);
        box(g, v, show, sx, sy, s, large, !left);
        // the frame of an upgrade reaches 1px out: its pill starts the same gap from the frame as a tick's from the square
        int bx = left ? sx + s + MARK_GAP + 1 : sx - MARK_GAP - 1 - zone;
        List<String> name = lines == 0 ? List.of() : wrap2(nameOf(v), zone);
        name = name.size() > lines ? List.of(fit(String.join(" ", name), zone)) : name;
        int bh = block(name.size());
        int py = sy + (s - bh) / 2;
        partOwner = "slot" + v.index();
        mark(g, v, show, left ? bx : -1, left ? -1 : bx + zone, py, zone);
        for (int i = 0; i < name.size(); i++) {
            Component c = Component.literal(name.get(i)).withStyle(nameColour(v));
            int nx = left ? bx : bx + zone - font.width(c);
            Kit.text(g, font, c, nx, py + PILL_H + 2 + i * 10, Theme.paperRgb(Theme.INK));
            LayoutCheck.part(g.pose(), nx, py + PILL_H + 2 + i * 10, font.width(c), 9, partOwner, "the " + v.label() + " slot's name");
        }
        int top = Math.min(sy, py);
        int bot = Math.max(sy + s, py + bh);
        int[] rect = left ? new int[] {sx, top, s + MARK_GAP + 1 + zone, bot - top} : new int[] {bx, top, s + MARK_GAP + 1 + zone, bot - top};
        areas(host, v, rect);
    }

    /**
     * The weapon's slot over the figure's hand, like the others: its pill to the right, vertically
     * centred on it, when it ends left of {@code right}, with up to two lines of the whole name
     * under it while they end left of {@code right} and above {@code bottom}. With no room beside,
     * the pill goes under the slot (over its lower corner when the panel ends first) and the name
     * is in the tooltip only. The figure never moves for either.
     */
    void weaponSlot(GuiGraphics g, Host host, SlotView v, int sx, int sy, int s, int right, int bottom, boolean names,
            boolean large) {
        ItemStack show = showOf(v);
        box(g, v, show, sx, sy, s, large, false);
        int px = sx + s + MARK_GAP + 1;
        int room = right - px;
        int pw = markW(v, room);
        int py = sy + (s - PILL_H) / 2;
        boolean beside = px + pw <= right;
        if (!beside) {
            px = Math.min(sx + (s - pw) / 2, right - pw);
            py = Math.min(sy + s + MARK_GAP + 1, bottom - PILL_H);
        }
        partOwner = "slot" + v.index();
        mark(g, v, show, px, -1, py, room);
        int ny = py + PILL_H + 2;
        int lines = names && beside ? Math.min(2, (bottom - ny + 1) / 10) : 0;
        List<String> name = lines > 0 && right - px >= 20 ? wrap2(nameOf(v), right - px) : List.of();
        if (name.size() > lines || String.join(" ", name).contains("…")) {
            name = List.of();
        }
        int nw = pw;
        for (int i = 0; i < name.size(); i++) {
            Component t = Component.literal(name.get(i)).withStyle(nameColour(v));
            Kit.text(g, font, t, px, ny + i * 10, Theme.paperRgb(Theme.INK));
            LayoutCheck.part(g.pose(), px, ny + i * 10, font.width(t), 9, partOwner, "the weapon's name");
            nw = Math.max(nw, font.width(t));
        }
        int left = Math.min(sx, px);
        int top = Math.min(sy, py);
        int rgt = Math.max(sx + s, px + nw);
        int bot = Math.max(sy + s, name.isEmpty() ? py + PILL_H : ny + name.size() * 10 - 1);
        areas(host, v, new int[] {left, top, rgt - left, bot - top});
    }

    /** A slot's square and its item (or the game's empty-slot picture). */
    private void box(GuiGraphics g, SlotView v, ItemStack show, int sx, int sy, int s, boolean large, boolean badgeRight) {
        int frame = v.mark() == Mark.CHOSEN ? CHOSEN : v.gear() != null || v.mark() == Mark.GET ? UP : 0;
        slotBox(g, sx, sy, s, frame);
        LayoutCheck.part(g.pose(), sx - 1, sy - 1, s + 2, s + 2, "slot" + v.index(), "the " + v.label() + " slot");
        if (v.mark() == Mark.CHOSEN || v.mark() == Mark.DONE_CHOSEN) {
            // the lock badge on the corner away from the pill (the item's transparent corner may lie over its edge)
            int bx = badgeRight ? sx + s - 7 : sx - 3;
            Kit.seal(g, bx, sy - 3, 10, 10, CHOSEN);
            Art.icon(g, "lock", bx + 1, sy - 2, 8, CHOSEN_TEXT);
        } else if (!show.isEmpty()) {
            // where the shown piece comes from, on the slot's outer top corner; a suggested piece
            // already in the bag or bank shows the bank (the lookups behind both are cached)
            boolean worn = v.mark() == Mark.DONE || !v.showsPlanned() && v.gear() == null;
            String name = v.showsPlanned() && v.planned() != null ? v.planned().name()
                    : v.gear() != null ? v.gear().info().name()
                    : ItemJson.clean(v.worn().getHoverName().getString()).trim();
            String icon = !worn && BuildSources.haveNames().contains(name)
                    ? "building-bank" : BuildWindow.sourceIcon(name);
            // each kind of place its own colour, a light icon on it, so the kind reads at a glance
            int fill = Theme.sourceFill(icon);
            int bx = badgeRight ? sx + s - 10 : sx - 5;
            Kit.seal(g, bx, sy - 5, 15, 15, fill);
            Art.icon(g, icon, bx + 2, sy - 3, 11, Theme.CREAM);
        }
        int is = large ? 32 : 16;
        int o = (s - is) / 2;
        if (!show.isEmpty()) {
            item(g, show, sx + o, sy + o, large);
        } else if (v.type() != null) {
            WynnTex.draw(g, "wynn/gui/slot/" + v.type().name().toLowerCase(Locale.ROOT), sx + o, sy + o, is, is);
        }
    }

    /** What a slot shows: a plan's piece to get or a chosen one, else the upgrade, else what you wear. */
    private static ItemStack showOf(SlotView v) {
        return v.showsPlanned() || v.gear() != null ? v.icon() : plain(v.worn(), v.type());
    }

    /** A slot's name colour: the shown piece's tier, grey for what you wear. */
    private static ChatFormatting nameColour(SlotView v) {
        if (v.showsPlanned() && v.planned() != null) {
            return v.planned().tier().colour();
        }
        return v.gear() != null ? v.gear().info().tier().colour() : ChatFormatting.GRAY;
    }

    /**
     * A slot's pill: "chosen" on purple for an item you build around, "Missing" on green for a
     * plan's piece to get, both with the icon of where it comes from (the icon alone where
     * {@code room} is short), a tick (purple for a chosen item) when you wear the plan's piece; else its
     * gain on green, or a tick on paper when it is fine; nothing for an empty slot.
     */
    private void mark(GuiGraphics g, SlotView v, ItemStack show, int left, int right, int y, int room) {
        switch (v.mark()) {
            case CHOSEN, GET -> {
                String[] p = missingPill(v, room);
                if (tab.firstPill == null) {
                    int pw = pillW(p[0], p[1]);
                    tab.firstPill = new int[] {left >= 0 ? left : right - pw, y, pw, PILL_H, left >= 0 ? 1 : 0};
                }
                boolean chosen = v.mark() == Mark.CHOSEN;
                pill(g, p[0], p[1], left, right, y, chosen ? CHOSEN : Theme.SEAL_3, chosen ? CHOSEN_TEXT : Theme.SEAL_TEXT);
                return;
            }
            case DONE -> {
                pill(g, null, "check", left, right, y, Theme.PAPER_DEEP, Theme.GOOD);
                return;
            }
            case DONE_CHOSEN -> {
                pill(g, null, "check", left, right, y, CHOSEN, CHOSEN_TEXT);
                return;
            }
            default -> { }
        }
        if (gained(v)) {
            pill(g, gainText(v), null, left, right, y, Theme.SEAL_3, Theme.SEAL_TEXT);
        } else if (!show.isEmpty()) {
            pill(g, null, "check", left, right, y, Theme.PAPER_DEEP, Theme.GOOD);
        }
    }

    /** A slot's hover and click: the upgrade's story and its comparison, else the worn item's tooltip. */
    private void areas(Host host, SlotView v, int[] rect) {
        if (v.index() == 0) {
            tab.firstSlot = rect.clone();
        }
        // every item here shows its full tooltip (Host.item), its context lines under it
        if (v.mark() != Mark.NONE && v.planned() != null) {
            boolean worn = v.mark() == Mark.DONE || v.mark() == Mark.DONE_CHOSEN;
            // the rect holds the square, the pill and the name: the pill's hover is the slot's
            host.item(rect[0], rect[1], rect[2], rect[3], v.planned(), worn ? v.worn() : ItemStack.EMPTY,
                    v.lead() == null ? List.of() : v.lead(), v.tip());
        } else if (v.gear() != null) {
            host.compare(rect, v);
            host.item(rect[0], rect[1], rect[2], rect[3], v.gear().info(), ItemStack.EMPTY, v.tip());
        } else if (!v.worn().isEmpty()) {
            host.area(rect[0], rect[1], rect[2], rect[3], v.worn(), null, null);
        } else {
            host.area(rect[0], rect[1], rect[2], rect[3], null, List.of(Component.literal(v.label() + ": " + L.t("wynnlabs.screen.empty"))
                    .withStyle(ChatFormatting.GRAY)), null);
        }
    }

    /**
     * A pill of {@link #PILL_H} with a text or an 8px icon centred in it, starting at {@code left}
     * or ending at {@code right} (the other is -1).
     */
    private void pill(GuiGraphics g, String text, String icon, int left, int right, int y, int fill, int ink) {
        int pw = pillW(text, icon);
        int px = left >= 0 ? left : right - pw;
        Kit.seal(g, px, y, pw, PILL_H, fill);
        LayoutCheck.part(g.pose(), px, y, pw, PILL_H, partOwner == null ? "pill" : partOwner, "a pill (" + (text == null ? icon : text) + ")");
        if (text != null && icon != null) {
            // the icon, then the text
            Art.icon(g, icon, px + 4, y + (PILL_H - 8) / 2, 8, ink);
            g.drawString(font, text, px + 14, y + 2, ink, true);
        } else if (text != null) {
            g.drawString(font, text, px + (pw - font.width(text) + 1) / 2, y + 2, ink, true);
        } else {
            Art.icon(g, icon, px + (pw - 8) / 2, y + (PILL_H - 8) / 2, 8, ink);
        }
    }

    private void item(GuiGraphics g, ItemStack stack, int x, int y, boolean big) {
        if (!big) {
            g.renderItem(stack, x, y);
            return;
        }
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(2f, 2f);
        g.renderItem(stack, 0, 0);
        g.pose().popMatrix();
    }
}
