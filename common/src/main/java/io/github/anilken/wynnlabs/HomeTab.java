package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.Slot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The home tab under the character band, drawn as pictures: the gear on an armour stand in the
 * middle (the armour slots on its left, the accessories on its right, the weapon in its hand),
 * every slot the selected milestone changes framed green with its gain and its body part tinted,
 * the build bar under it; this week's raid pool, the aspects and the professions on the left; the
 * next quest, where to level, the bank and the nearest secret discovery on the right. A tile
 * opens its tab. Compact: the stand on the left and the tiles as rows beside it, as many as fit.
 *
 * <p>{@link RoadmapScreen} keeps the band, the level buttons, the clicks and the tooltips: it
 * hands this frame's slots in ({@link In}) and takes the areas back through {@link Host}. Only
 * reads; nothing here acts in the game.
 *
 * <p>This class lays the page out; {@link HomeGearPanel} (with {@link HomeSlots} and
 * {@link HomeGearBars}) draws the gear panel and {@link HomeTiles} the tiles from {@link HomeData}.
 */
final class HomeTab {

    static final HomeTab INSTANCE = new HomeTab();

    /**
     * One gear slot: its place in the screen's slot order (0-3 armour, 4 the weapon, 5-8 rings,
     * bracelet, necklace), its name, what is worn, the upgrade (null: keep) with its icon, gain
     * and tooltip; for a plan's missing piece, {@code lead}: the lines above the item's tooltip
     * that explain its pill ("Missing · dropped by mobs" and a detail).
     */
    record SlotView(int index, String label, Slot type, ItemStack worn, Roadmap.Gear gear, ItemStack icon, double gain,
            List<Component> tip, Mark mark, ItemData planned, List<Component> lead) {

        SlotView(int index, String label, Slot type, ItemStack worn, Roadmap.Gear gear, ItemStack icon, double gain,
                List<Component> tip) {
            this(index, label, type, worn, gear, icon, gain, tip, Mark.NONE, null, List.of());
        }

        /** A plan's piece to show (chosen or still to get): its icon framed, not what is worn. */
        boolean showsPlanned() {
            return mark == Mark.CHOSEN || mark == Mark.GET;
        }
    }

    /**
     * How a slot of the applied plan shows: NONE (the road map's view), CHOSEN (one of the items
     * you build around, not worn yet: purple), GET (the plan's piece, not worn yet: green), DONE
     * (worn as planned: a tick) and DONE_CHOSEN (a chosen item worn: a purple tick).
     */
    enum Mark { NONE, CHOSEN, GET, DONE, DONE_CHOSEN }

    /**
     * The applied plan's build line: its name, how many of its pieces you wear, what the rest
     * costs (NaN unknown), and the level a better build was found at (-1 none).
     */
    record PlanLine(String name, int worn, int total, double left, int better, int plannedFor) {}

    /**
     * The road map behind the panel: none yet (the panel offers to calculate it), being built and
     * how far, and why it is out of date (null when current).
     */
    record Road(boolean none, boolean building, double progress, Roadmap.Stale stale) {}

    /** The preview: this road map state instead of the real one; never set in the game. */
    static volatile Road previewRoad;

    /**
     * What the screen hands in each frame: the slots, the build score now and with the selected
     * milestone's whole set (NaN when unknown), the best quest (null for none) and the places to
     * level now; the plan's line when the Plan pill is selected (null otherwise); how many items
     * are chosen and builds saved, and whether a search for builds is running.
     */
    record In(List<SlotView> slots, double nowScore, double afterScore, QuestAdvisor.Suggestion quest, List<String> places,
            PlanLine plan, int chosen, int saved, boolean searching, Road road) {

        In(List<SlotView> slots, double nowScore, double afterScore, QuestAdvisor.Suggestion quest, List<String> places) {
            this(slots, nowScore, afterScore, quest, places, null, 0, 0, false, null);
        }
    }

    /** The screen's side: areas for this frame's tooltips and clicks, and its level buttons. */
    interface Host {
        /** A hover and click area: an item's tooltip, or lines, and the screen's action (null: none). */
        void area(int x, int y, int w, int h, ItemStack stack, List<Component> tip, String action);

        /** A quest's area: a click tracks it. */
        void quest(int x, int y, int w, int h, List<Component> tip, QuestBook.Entry q);

        /**
         * An item's hover: its full tooltip (the worn stack's own with your rolls when {@code worn}
         * is not empty, else the item database's) with {@code extra} context lines under it.
         */
        void item(int x, int y, int w, int h, ItemData info, ItemStack worn, List<Component> extra);

        /** {@link #item}, with {@code lead} lines above the item's tooltip (a blank line between). */
        void item(int x, int y, int w, int h, ItemData info, ItemStack worn, List<Component> lead, List<Component> extra);

        /** A slot whose click opens its comparison (when it has options). */
        void compare(int[] rect, SlotView s);

        /** The level buttons ending at {@code right} in {@code room} pixels; their left edge. */
        int levelPills(GuiGraphics g, int y, int right, int room);

        /** The small refresh button (or its bar while the map is rebuilt) at x; the width it took. */
        int refresh(GuiGraphics g, int x, int y, int mx, int my, String staleTip);
    }

    /** The items you chose: their frames, pills and the stand's part in the archetype's purple. */
    static final int CHOSEN = Theme.ACCENT;
    static final int CHOSEN_TEXT = 0xFFF1E9FF;

    private final HomeGearPanel gear = new HomeGearPanel(this);
    private final HomeTiles tiles = new HomeTiles();

    private HomeTab() {}

    // ---- preview (LayoutMatrix): long names, to check they never move the figure ----

    /** Long slot names by slot index, for the preview. */
    static final String[] LONG = {"The King's Robe", "The King's Robe", "Darkiron Scrap", "Darkiron Scrap",
        "Enzan's Lucky Charm", "Enzan's Lucky Charm", "Darkiron Scrap", "Enzan's Lucky Charm", "The King's Robe"};
    /** For the preview: every slot shows a long name and the weapon a "+142%" pill; never set in the game. */
    static boolean previewLong;
    /**
     * The figure's box drawn last frame (x, y, w, h) and the panel's width and stand room, for the
     * preview's check that the names never move it.
     */
    final int[] standBox = new int[6];

    // =====================================================================================
    // drawing

    /**
     * Draws the home's body under the band.
     *
     * @param x       the page's left edge
     * @param y       the top, under the experience bar
     * @param w       the page's width
     * @param h       the height left
     * @param compact the compact menu (an icon rail, a short page)
     * @param large   the large menu
     */
    void draw(GuiGraphics g, In in, Host host, int x, int y, int w, int h, boolean compact, boolean large, int mx, int my) {
        Font font = UiFont.get();
        gear.frame(font, large);
        gear.slots.frame(font, large);
        gear.bars.frame(font, large);
        tiles.frame(font, large);
        firstPill = null;
        long now = System.currentTimeMillis();
        tiles.refresh(now);
        if (compact) {
            int lw = Math.max(118, Math.min(160, w * 36 / 100));
            int pw = w - lw - 6;
            gear.panel(g, in, host, x, y, pw, h, true, false, mx, my);
            tiles.rows(g, in, host, x + pw + 6, y, lw, h, mx, my);
            return;
        }
        int gap = large ? 10 : 8;
        // the sides give the gear panel room for its slots' names
        int side = large ? Math.max(170, Math.min(220, w * 24 / 100)) : Math.max(112, Math.min(140, (w - 2 * gap) * 24 / 100));
        int cw = w - 2 * side - 2 * gap;
        int lh = (h - 2 * gap) / 3;
        tiles.weekTile(g, host, x, y, side, lh, mx, my);
        tiles.aspectTile(g, host, x, y + lh + gap, side, lh, mx, my);
        tiles.professionTile(g, host, x, y + 2 * (lh + gap), side, h - 2 * (lh + gap), mx, my);
        gear.panel(g, in, host, x + side + gap, y, cw, h, false, large, mx, my);
        int rx = x + side + gap + cw + gap;
        int rh = (h - 3 * gap) / 4;
        tiles.questTile(g, in, host, rx, y, side, rh, mx, my);
        tiles.grindTile(g, in, host, rx, y + rh + gap, side, rh, mx, my);
        tiles.bankTile(g, host, rx, y + 2 * (rh + gap), side, rh, mx, my);
        tiles.discoveryTile(g, host, rx, y + 3 * (rh + gap), side, h - 3 * (rh + gap), mx, my);
    }

    /**
     * Three dots, one lit in turn by the clock (a quarter second each), 2px squares 2px apart;
     * all lit and still in the preview's frozen state.
     */
    static void dots(GuiGraphics g, int x, int y, int colour) {
        int lit = Around.previewLoading != null ? -1 : (int) (System.currentTimeMillis() / 250 % 3);
        for (int i = 0; i < 3; i++) {
            int c = lit < 0 || i == lit ? colour : (colour & 0xFFFFFF) | 0x66000000;
            g.fill(x + i * 3, y - 1, x + i * 3 + 2, y + 1, c);
        }
    }

    /** The preview: the first slot's hover box this frame, for a tooltip shot. */
    int[] firstSlot;
    /** The preview: the first missing piece's pill this frame (x, y, w, h, 1 when its square is on its left), for a tooltip shot. */
    int[] firstPill;

    /** "from yesterday", "3 days ago", "5 h ago"; "" under an hour. */
    static String age(long ms) {
        long h = ms / 3_600_000L;
        if (h < 1) {
            return "";
        }
        if (h < 24) {
            return L.t("wynnlabs.home.stale_age_hours", h);
        }
        long d = h / 24;
        return d == 1 ? L.t("wynnlabs.home.stale_age_yesterday") : L.t("wynnlabs.home.stale_age_days", d);
    }
}
