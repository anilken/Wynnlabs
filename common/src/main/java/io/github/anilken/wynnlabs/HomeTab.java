package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.PoolItem;
import io.github.anilken.wynnlabs.model.RaidDry;
import io.github.anilken.wynnlabs.model.RankedAspect;
import io.github.anilken.wynnlabs.model.Slot;
import io.github.anilken.wynnlabs.model.Tier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
 */
final class HomeTab {

    static final HomeTab INSTANCE = new HomeTab();

    /**
     * One gear slot: its place in the screen's slot order (0-3 armour, 4 the weapon, 5-8 rings,
     * bracelet, necklace), its name, what is worn, the upgrade (null: keep) with its icon, gain
     * and tooltip.
     */
    record SlotView(int index, String label, Slot type, ItemStack worn, Roadmap.Gear gear, ItemStack icon, double gain,
            List<Component> tip) {}

    /**
     * What the screen hands in each frame: the slots, the build score now and with the selected
     * milestone's whole set (NaN when unknown), the best quest (null for none) and the places to
     * level now.
     */
    record In(List<SlotView> slots, double nowScore, double afterScore, QuestAdvisor.Suggestion quest, List<String> places) {}

    /** The screen's side: areas for this frame's tooltips and clicks, and its level buttons. */
    interface Host {
        /** A hover and click area: an item's tooltip, or lines, and the screen's action (null: none). */
        void area(int x, int y, int w, int h, ItemStack stack, List<Component> tip, String action);

        /** A quest's area: a click tracks it. */
        void quest(int x, int y, int w, int h, List<Component> tip, QuestBook.Entry q);

        /** A slot whose click opens its comparison (when it has options). */
        void compare(int[] rect, SlotView s);

        /** The level buttons ending at {@code right} in {@code room} pixels; their left edge. */
        int levelPills(GuiGraphics g, int y, int right, int room);

        /** The small refresh button (or its bar while the map is rebuilt) at x; the width it took. */
        int refresh(GuiGraphics g, int x, int y, int mx, int my);
    }

    // the stand's colours: a light wooden figure on the card, its edge darker
    private static final int BODY = 0xFFC2A878;
    private static final int BODY_EMPTY = 0xFFDCCBA0;
    private static final int SLOT_WELL = 0xFF9E8763;
    private static final int SLOT_EDGE = Theme.INK_2;
    private static final int UP = Theme.GREEN;
    /** Wynncraft's highest profession level: the professions' bars without Wynntils' XP run to it. */
    private static final int PROF_CAP = 132;

    private Font font;
    /** The large menu this frame: bigger icons, and the tiles' extra lines. */
    private boolean large;
    private Data d;
    private long dataAt;

    private HomeTab() {}

    // ---- preview (LayoutMatrix): long names, to check they never move the figure ----

    /** Long slot names by slot index, for the preview. */
    private static final String[] LONG = {"The King's Robe", "The King's Robe", "Darkiron Scrap", "Darkiron Scrap",
        "Enzan's Lucky Charm", "Enzan's Lucky Charm", "Darkiron Scrap", "Enzan's Lucky Charm", "The King's Robe"};
    /** For the preview: every slot shows a long name and the weapon a "+142%" pill; never set in the game. */
    static boolean previewLong;
    /**
     * The figure's box drawn last frame (x, y, w, h) and the panel's width and stand room, for the
     * preview's check that the names never move it.
     */
    final int[] standBox = new int[6];

    // ---- what the tiles read, twice a second rather than every frame ----

    private record Data(List<PoolItem> pool, Klass klass, boolean raidsUp, int dryPulls, boolean aspectsUp, int owned,
            int total, RankedAspect next, List<String> profs, Map<String, Integer> levels, int[] bank,
            double sellWorth, int priced, Discoveries.Entry near, int nearDist) {

        static Data read() {
            Klass klass = Wyn.classType();
            RaidDry dry = RaidLog.dry();
            // this week's aspects of the class, as the Endgame tab's pool view lists them (its icons), mythic first
            List<PoolItem> pool = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            List<PoolView.Region> regions = PoolView.raidRegions();
            for (String region : regions == null || regions.isEmpty() ? List.of("") : regions.stream().map(PoolView.Region::name).toList()) {
                for (PoolView.Group gr : PoolView.raid(region, true)) {
                    for (PoolView.Row row : gr.rows()) {
                        if (row.ownClass() && row.item().aspect() && seen.add(row.item().name())) {
                            pool.add(row.item());
                        }
                    }
                }
            }
            pool.sort(java.util.Comparator.comparingInt(i -> rarityRank(i.rarity())));
            List<?> classAspects = AspectBook.classAspects();
            // the professions: the crafting tab's one first, then the highest; unknown (-1) and never read (0) left out
            Map<String, Integer> levels = Professions.levels();
            List<String> profs = new ArrayList<>();
            String crafting = CraftingTab.INSTANCE.profession();
            if (crafting != null && levels.getOrDefault(crafting, -1) > 0) {
                profs.add(crafting);
            }
            List<String> byLevel = new ArrayList<>(Professions.ALL);
            byLevel.sort((a, b) -> levels.get(b) - levels.get(a));
            for (String pr : byLevel) {
                if (profs.size() < 3 && levels.get(pr) > 0 && !profs.contains(pr)) {
                    profs.add(pr);
                }
            }
            // the bank: each kind of item once, as the bank tab's filters sort them (sell, quest, profession, gear, other)
            int[] bank = null;
            double worth = 0;
            int priced = 0;
            List<Vault.Group> all = Vault.groups();
            if (!all.isEmpty()) {
                bank = new int[5];
                Set<String> junk = new HashSet<>();
                for (Vault.Group j : Vault.junk()) {
                    junk.add(j.key());
                    Double v = "Trade Market".equals(Vault.place(j)) ? Vault.value(j) : null;
                    if (v != null) {
                        worth += v;
                        priced++;
                    }
                }
                for (Vault.Group gr : all) {
                    Vault.Stored st = gr.first();
                    int k = junk.contains(gr.key()) ? 0 : !Vault.questsFor(gr).isEmpty() ? 1 : !st.prof().isEmpty() ? 2
                            : st.gear() || st.kind().contains("gear") ? 3 : 4;
                    bank[k]++;
                }
            }
            // the nearest secret discovery not done yet and open at your level
            Discoveries.Entry near = null;
            double best = Double.MAX_VALUE;
            LocalPlayer me = Minecraft.getInstance().player;
            if (me != null) {
                Set<String> found = Discoveries.foundInGame();
                String who = GameState.current().characterId();
                int level = Wyn.level();
                for (Discoveries.Entry e : Discoveries.all()) {
                    if (e.minLevel() > level || Discoveries.done(e, found, who)) {
                        continue;
                    }
                    double dd = Discoveries.distance(e, me.getX(), me.getZ());
                    if (dd < best) {
                        best = dd;
                        near = e;
                    }
                }
            }
            return new Data(pool, klass == null ? Klass.NONE : klass, Wyn.up(Wyn.Feature.RAIDS),
                    dry == null ? -1 : dry.aspectPulls(), Wyn.up(Wyn.Feature.ASPECTS), AspectBook.ownedCount(),
                    classAspects == null ? 0 : classAspects.size(), AspectBook.next(), profs, levels, bank, worth, priced, near, near == null ? -1 : (int) Math.round(best));
        }
    }

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
        font = UiFont.get();
        this.large = large;
        long now = System.currentTimeMillis();
        if (d == null || now - dataAt > 500) {
            d = Data.read();
            dataAt = now;
        }
        if (compact) {
            int lw = Math.max(118, Math.min(160, w * 36 / 100));
            int pw = w - lw - 6;
            panel(g, in, host, x, y, pw, h, true, false, mx, my);
            rows(g, in, host, x + pw + 6, y, lw, h, mx, my);
            return;
        }
        int gap = large ? 10 : 8;
        // the sides give the gear panel room for its slots' names
        int side = large ? Math.max(170, Math.min(220, w * 24 / 100)) : Math.max(112, Math.min(140, (w - 2 * gap) * 24 / 100));
        int cw = w - 2 * side - 2 * gap;
        int lh = (h - 2 * gap) / 3;
        weekTile(g, host, x, y, side, lh, mx, my);
        aspectTile(g, host, x, y + lh + gap, side, lh, mx, my);
        professionTile(g, host, x, y + 2 * (lh + gap), side, h - 2 * (lh + gap), mx, my);
        panel(g, in, host, x + side + gap, y, cw, h, false, large, mx, my);
        int rx = x + side + gap + cw + gap;
        int rh = (h - 3 * gap) / 4;
        questTile(g, in, host, rx, y, side, rh, mx, my);
        grindTile(g, in, host, rx, y + rh + gap, side, rh, mx, my);
        bankTile(g, host, rx, y + 2 * (rh + gap), side, rh, mx, my);
        discoveryTile(g, host, rx, y + 3 * (rh + gap), side, h - 3 * (rh + gap), mx, my);
    }

    // ---- the gear panel: the stand, its slots, the build bar ----

    /** The panel's padding inside its card, the same on every side. */
    private static final int PAD = 6;
    /** Between a slot and its gain pill or tick. */
    private static final int MARK_GAP = 3;
    /** The gain pill's and the tick pill's height. */
    private static final int PILL_H = 11;
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

    private void panel(GuiGraphics g, In in, Host host, int x, int y, int w, int h, boolean compact, boolean large,
            int mx, int my) {
        card(g, x, y, w, h, 0, false);
        // the title line: the name, the refresh button, the level buttons on the right, PAD in from both edges
        Component title = Component.literal(L.t("wynnlabs.homev.gear")).withStyle(UiFont::bold);
        int ty = y + PAD + 1;
        Art.icon(g, "hanger", x + PAD, ty, 8, Theme.INK_2);
        Kit.text(g, font, title, x + PAD + 11, ty, Theme.INK);
        int tx = x + PAD + 11 + font.width(title) + 5;
        tx += host.refresh(g, tx, ty - 3, mx, my);
        // the hanger's drawing starts 1px inside its 8px: the buttons end 1px further in to match
        host.levelPills(g, ty, x + w - PAD - 1, x + w - PAD - 1 - tx - 2);

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
        int bottom = y + h - PAD - barH - 4;
        int areaH = bottom - top;
        int s = large ? 36 : compact ? 18 : 22;
        boolean names = !compact;
        // the rows: tall enough for a pill and two lines of name, else one, else none
        int lines = names ? 2 : 0;
        while (lines > 0 && 4 * s + 3 * Math.max(3, block(lines) - s + 3) > areaH) {
            lines--;
        }
        int sg = Math.max(3, Math.max(block(lines) - s + 3, Math.min(large ? 24 : 6, (areaH - 4 * s) / 3)));
        sg = Math.min(sg, Math.max(3, (areaH - 4 * s) / 3));
        int colY = top + Math.max(0, (areaH - 4 * s - 3 * sg) / 2);
        // beside each column a zone for its pills and (not compact) its names, its width from the
        // panel's alone: never measured from the names or the pills, so the figure is the same for
        // every character and the names fit into the zone (one line, two, or two ending in "…")
        int edge = PAD + 1 + s + MARK_GAP + 1;
        int room = w - 2 * edge - 8;
        int zoneMin = names ? Math.max(PILL_ZONE, room * ZONE_SHARE / 100) : PILL_ZONE;
        int zoneCap = names ? large ? ZONE_LARGE_CAP : ZONE_CAP : PILL_ZONE;
        // the figure from the panel alone: as tall as the room between the title and the build bar
        // allows, as wide as the room between the two zones
        int u = Math.max(1, Math.min((areaH - 1) / 32, (room - 2 * zoneMin - 1) / 16));
        int figW = 16 * u + 1;
        // the width the figure leaves goes back to the zones, the same on both sides
        int zone = Math.max(0, Math.min(zoneCap, (room - figW) / 2));
        int midX = x + edge + zone + 4;
        int midW = room - 2 * zone;
        int l = midX + Math.max(0, (midW - figW) / 2);
        int standY = top + Math.max(0, (areaH - 32 * u - 1) / 2);
        // which parts are tinted: an upgrade green, a worn piece wood, nothing worn pale
        int[] state = new int[9];
        for (SlotView v : in.slots()) {
            state[v.index()] = v.gear() != null ? 2 : v.worn().isEmpty() ? 0 : 1;
        }
        stand(g, l + 8 * u, standY, u, state);
        standBox[0] = l;
        standBox[1] = standY;
        standBox[2] = 16 * u + 1;
        standBox[3] = 32 * u + 1;
        standBox[4] = w;
        standBox[5] = areaH;
        for (int i = 0; i < armour.size(); i++) {
            slot(g, host, armour.get(i), x + PAD + 1, colY + i * (s + sg), s, true, zone, lines, large);
        }
        for (int i = 0; i < trinkets.size(); i++) {
            slot(g, host, trinkets.get(i), x + w - PAD - 1 - s, colY + i * (s + sg), s, false, zone, lines, large);
        }
        if (weapon != null) {
            // drawn after the figure, so it lies over the hand: the slot centred on the hand's last two units
            int wx = l + 14 * u - s / 2;
            int wy = standY + 19 * u - s / 2;
            weaponSlot(g, host, weapon, wx, wy, s, x + w - edge - zone - 4, bottom, names, large);
        }
        buildBar(g, in, host, x + PAD + 1, y + h - PAD - barH, w - 2 * PAD - 2, compact, count, gains);
    }

    /** A slot's pill and name block: the pill, then {@code lines} lines of name under it. */
    private static int block(int lines) {
        return lines == 0 ? PILL_H : PILL_H + 2 + lines * 10 - 1;
    }

    /** The width of a slot's pill: its gain, or the tick's 15; 0 for an empty slot. */
    private int markW(SlotView v) {
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
        return Double.isNaN(v.gain()) ? L.t("wynnlabs.screen.pill_set")
                : (v.gain() < -0.5 ? "−" : "+") + RoadmapScreen.pct(Math.round(Math.abs(v.gain())));
    }

    /** The name a slot shows: the upgrade's, else what is worn, else "empty". */
    private static String nameOf(SlotView v) {
        if (previewLong) {
            return LONG[v.index()];
        }
        return v.gear() != null ? v.gear().info().name()
                : v.worn().isEmpty() ? L.t("wynnlabs.screen.empty") : ItemJson.clean(v.worn().getHoverName().getString());
    }

    /**
     * A name in at most two lines no wider than {@code w}: whole when it fits, else broken at the
     * space that makes the longer line shortest; the second line ends in "…" when still too long.
     */
    private List<String> wrap2(String s, int w) {
        return wrap2(s, w, false);
    }

    /** {@link #wrap2(String, int)} measured as drawn: in bold when {@code bold}. */
    private List<String> wrap2(String s, int w, boolean bold) {
        if (width(s, bold) <= w) {
            return List.of(s);
        }
        String a = null;
        String b = null;
        int best = Integer.MAX_VALUE;
        for (int i = s.indexOf(' '); i > 0; i = s.indexOf(' ', i + 1)) {
            String l1 = s.substring(0, i);
            String l2 = s.substring(i + 1);
            int m = Math.max(width(l1, bold), width(l2, bold));
            if (width(l1, bold) <= w && m < best) {
                best = m;
                a = l1;
                b = l2;
            }
        }
        return a == null ? List.of(fit(s, w, bold)) : List.of(a, fit(b, w, bold));
    }

    /**
     * The armour stand from the top centre, in units of {@code u} whole pixels and mirrored about
     * the centre column: the head 8×8 (helmet), the body 8×12 and both arms 4×12 (chestplate),
     * the legs 4×8 (leggings) and feet 4×4 (boots) under it; 16u+1 wide and 32u+1 tall. Every part has a 1px outline in its own colour darkened, and parts that touch
     * share that line. Accessories stay off the figure: their slots show them.
     * {@code state} by slot index: 2 an upgrade (green), 1 worn (wood), 0 nothing worn (pale).
     */
    private void stand(GuiGraphics g, int cx, int top, int u, int[] state) {
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
        int fill = state == 2 ? UP : state == 0 ? BODY_EMPTY : BODY;
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
    private static void slotBox(GuiGraphics g, int x, int y, int s, boolean up) {
        if (up) {
            g.fill(x - 1, y - 1, x + s + 1, y + s + 1, UP);
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
    private void slot(GuiGraphics g, Host host, SlotView v, int sx, int sy, int s, boolean left, int zone, int lines, boolean large) {
        boolean up = v.gear() != null;
        ItemStack show = up ? v.icon() : plain(v.worn(), v.type());
        box(g, v, show, sx, sy, s, large);
        // the frame of an upgrade reaches 1px out: its pill starts the same gap from the frame as a tick's from the square
        int bx = left ? sx + s + MARK_GAP + 1 : sx - MARK_GAP - 1 - zone;
        List<String> name = lines == 0 ? List.of() : wrap2(nameOf(v), zone);
        name = name.size() > lines ? List.of(fit(String.join(" ", name), zone)) : name;
        int bh = block(name.size());
        int py = sy + (s - bh) / 2;
        mark(g, v, show, left ? bx : -1, left ? -1 : bx + zone, py);
        for (int i = 0; i < name.size(); i++) {
            Component c = Component.literal(name.get(i)).withStyle(up ? v.gear().info().tier().colour() : ChatFormatting.GRAY);
            int nx = left ? bx : bx + zone - font.width(c);
            Kit.text(g, font, c, nx, py + PILL_H + 2 + i * 10, Theme.paperRgb(Theme.INK));
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
    private void weaponSlot(GuiGraphics g, Host host, SlotView v, int sx, int sy, int s, int right, int bottom, boolean names,
            boolean large) {
        boolean up = v.gear() != null;
        ItemStack show = up ? v.icon() : plain(v.worn(), v.type());
        box(g, v, show, sx, sy, s, large);
        int pw = markW(v);
        int px = sx + s + MARK_GAP + 1;
        int py = sy + (s - PILL_H) / 2;
        boolean beside = px + pw <= right;
        if (!beside) {
            px = Math.min(sx + (s - pw) / 2, right - pw);
            py = Math.min(sy + s + MARK_GAP + 1, bottom - PILL_H);
        }
        mark(g, v, show, px, -1, py);
        int ny = py + PILL_H + 2;
        int lines = names && beside ? Math.min(2, (bottom - ny + 1) / 10) : 0;
        List<String> name = lines > 0 && right - px >= 20 ? wrap2(nameOf(v), right - px) : List.of();
        if (name.size() > lines || String.join(" ", name).contains("…")) {
            name = List.of();
        }
        int nw = pw;
        for (int i = 0; i < name.size(); i++) {
            Component t = Component.literal(name.get(i)).withStyle(up ? v.gear().info().tier().colour() : ChatFormatting.GRAY);
            Kit.text(g, font, t, px, ny + i * 10, Theme.paperRgb(Theme.INK));
            nw = Math.max(nw, font.width(t));
        }
        int left = Math.min(sx, px);
        int top = Math.min(sy, py);
        int rgt = Math.max(sx + s, px + nw);
        int bot = Math.max(sy + s, name.isEmpty() ? py + PILL_H : ny + name.size() * 10 - 1);
        areas(host, v, new int[] {left, top, rgt - left, bot - top});
    }

    /** A slot's square and its item (or the game's empty-slot picture). */
    private void box(GuiGraphics g, SlotView v, ItemStack show, int sx, int sy, int s, boolean large) {
        slotBox(g, sx, sy, s, v.gear() != null);
        int is = large ? 32 : 16;
        int o = (s - is) / 2;
        if (!show.isEmpty()) {
            item(g, show, sx + o, sy + o, large);
        } else if (v.type() != null) {
            WynnTex.draw(g, "wynn/gui/slot/" + v.type().name().toLowerCase(Locale.ROOT), sx + o, sy + o, is, is);
        }
    }

    /** A slot's pill: its gain on green, or a tick on paper when it is fine; nothing for an empty slot. */
    private void mark(GuiGraphics g, SlotView v, ItemStack show, int left, int right, int y) {
        if (gained(v)) {
            pill(g, gainText(v), null, left, right, y, Theme.SEAL_3, Theme.SEAL_TEXT);
        } else if (!show.isEmpty()) {
            pill(g, null, "check", left, right, y, Theme.PAPER_DEEP, Theme.GOOD);
        }
    }

    /** A slot's hover and click: the upgrade's story and its comparison, else the worn item's tooltip. */
    private static void areas(Host host, SlotView v, int[] rect) {
        if (v.gear() != null) {
            host.compare(rect, v);
            host.area(rect[0], rect[1], rect[2], rect[3], null, v.tip(), null);
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
        int pw = text != null ? font.width(text) + 8 : 15;
        int px = left >= 0 ? left : right - pw;
        Kit.seal(g, px, y, pw, PILL_H, fill);
        if (text != null) {
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

    /**
     * Under the stand: the build score now against the score with the selected milestone's
     * whole set (one bar, gold to now and green on to after), and "3 upgrades: +13% · ≈1.4 LE"
     * under it; compact, the line first and the bar after it on the same line. Without a set
     * worked out, the slots' gains summed and no bar.
     */
    private void buildBar(GuiGraphics g, In in, Host host, int x, int y, int w, boolean compact, int count, double gains) {
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
            g.fill(bx + fw, by, bx + bw, by + bh, UP);
        }
        host.area(x, y, w, compact ? 9 : 20, null, tip, null);
    }

    /** A card with its shadow under the whole width (Kit.card's stops short of the corners). */
    private static void card(GuiGraphics g, int x, int y, int w, int h, int accent, boolean lit) {
        Kit.card(g, x, y, w, h, accent, lit);
        g.fill(x + 1, y + h, x + 2, y + h + 1, 0x88624E3C);
        g.fill(x + w - 2, y + h, x + w - 1, y + h + 1, 0x88624E3C);
        g.fill(x + 1, y + h + 1, x + 3, y + h + 2, 0x44624E3C);
        g.fill(x + w - 3, y + h + 1, x + w - 1, y + h + 2, 0x44624E3C);
    }

    // ---- the tiles ----

    /** A tile's card and its title line (an icon, the name); lit under the mouse. */
    private void tile(GuiGraphics g, int x, int y, int w, int h, String icon, String title, int accent, int mx, int my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        card(g, x, y, w, h, accent, hover);
        Art.icon(g, icon, x + 5, y + 6, 8, Theme.INK_2);
        ink(g, fit(title, w - 22), x + 16, y + 6, Theme.INK_2);
    }

    /** "Click: open the X tab", the last line of a tile's tooltip. */
    private static Component opens(String tabKey) {
        return L.c("wynnlabs.homev.click_open", L.t(tabKey)).withStyle(ChatFormatting.DARK_GRAY);
    }

    /**
     * This week's raid pool: the class's aspects, mythic first, each on a square of its rarity's
     * colour with a 2px frame of it (the icon is the class's aspect, the same for every rarity),
     * and the mythic dry count.
     */
    private void weekTile(GuiGraphics g, Host host, int x, int y, int w, int h, int mx, int my) {
        tile(g, x, y, w, h, "trophy", L.t("wynnlabs.homev.week"), Theme.TIER_MYTHIC, mx, my);
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(L.t("wynnlabs.homev.week_tip")).withStyle(ChatFormatting.LIGHT_PURPLE));
        boolean dry = d.raidsUp() && d.dryPulls() >= 0;
        int iy = y + 18;
        if (d.pool().isEmpty()) {
            ink(g, fit(L.t("wynnlabs.homev.week_none"), w - 12), x + 6, iy + 2, Theme.INK_3);
            tip.add(Component.literal(L.t("wynnlabs.endgame.pool_never_tip")).withStyle(ChatFormatting.GRAY));
        } else {
            int is = large ? 24 : 16;
            int s = is + 4;
            int per = Math.max(1, (w - 12 + 2) / (s + 2));
            int rows = Math.max(1, (y + h - (dry ? 16 : 6) - iy + 2) / (s + 2));
            int max = per * rows;
            int n = d.pool().size() > max ? max - 1 : d.pool().size();
            for (int i = 0; i < n; i++) {
                PoolItem a = d.pool().get(i);
                int sx = x + 6 + (i % per) * (s + 2);
                int sy = iy + (i / per) * (s + 2);
                int c = tierColour(a.rarity());
                g.fill(sx, sy, sx + s, sy + s, c);
                g.fill(sx + 2, sy + 2, sx + s - 2, sy + s - 2, wash(c));
                ItemIcons.draw(g, a, a.aspectClass() == Klass.NONE ? d.klass() : a.aspectClass(), sx + 2, sy + 2, is, false);
            }
            if (n < d.pool().size()) {
                String more = "+" + (d.pool().size() - n);
                int sx = x + 6 + (n % per) * (s + 2);
                int sy = iy + (n / per) * (s + 2);
                ink(g, more, sx + (s - font.width(more)) / 2, sy + (s - 8) / 2, Theme.INK_2);
            }
            // the tooltip by rarity, as the pool view groups them
            Tier at = null;
            for (PoolItem a : d.pool()) {
                if (a.rarity() != at) {
                    at = a.rarity();
                    tip.add(Component.literal(rarityName(at)).withStyle(tierChat(at)).withStyle(UiFont::bold));
                }
                tip.add(Component.literal("  ◆ " + a.name()).withStyle(tierChat(a.rarity())));
            }
        }
        if (dry) {
            ink(g, fit(L.n("wynnlabs.homev.week_dry", d.dryPulls()), w - 12), x + 6, y + h - 13, Theme.INK_3);
        }
        tip.add(opens("wynnlabs.screen.tab_endgame"));
        host.area(x, y, w, h, null, tip, "#qopen:" + RoadmapScreen.TAB_ENDGAME);
    }

    /** A rarity's colour mixed 30% into the card: the square under an aspect's icon. */
    private static int wash(int c) {
        int r = ((c >> 16 & 0xFF) * 3 + (Theme.CARD >> 16 & 0xFF) * 7) / 10;
        int gr = ((c >> 8 & 0xFF) * 3 + (Theme.CARD >> 8 & 0xFF) * 7) / 10;
        int b = ((c & 0xFF) * 3 + (Theme.CARD & 0xFF) * 7) / 10;
        return 0xFF000000 | r << 16 | gr << 8 | b;
    }

    /** Mythic first, then fabled, legendary and the rest. */
    private static int rarityRank(Tier t) {
        return t == Tier.MYTHIC ? 0 : t == Tier.FABLED ? 1 : t == Tier.LEGENDARY ? 2 : 3;
    }

    /** "Mythic": a rarity's name as the game writes it. */
    private static String rarityName(Tier t) {
        if (t == null) {
            return "";
        }
        String n = t.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** The class's aspects: owned of all on a half ring, and the next one for the build. */
    private void aspectTile(GuiGraphics g, Host host, int x, int y, int w, int h, int mx, int my) {
        tile(g, x, y, w, h, "sparkles", L.t("wynnlabs.homev.aspects"), Theme.GOLD_DEEP, mx, my);
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(L.t("wynnlabs.homev.aspects")).withStyle(ChatFormatting.GOLD));
        if (!d.aspectsUp() || d.total() == 0) {
            ink(g, fit(L.t(d.aspectsUp() ? "wynnlabs.endgame.aspects_no_data" : "wynnlabs.endgame.aspects_off"), w - 12),
                    x + 6, y + 20, Theme.INK_3);
        } else {
            int owned = d.owned();
            String count = (owned < 0 ? "?" : String.valueOf(owned)) + "/" + d.total();
            int tx;
            if (h >= 52) {
                int gcx = x + 6 + 29;
                int gcy = y + 18 + 29;
                Kit.gauge(g, gcx, gcy, 29, 5, owned < 0 ? 0 : owned / (double) d.total(), Theme.PAPER_DEEP, Theme.GOLD_DEEP, Theme.GOLD_EDGE);
                Component c = Component.literal(count).withStyle(UiFont::bold);
                Kit.text(g, font, c, gcx - font.width(c) / 2, gcy - 11, Theme.INK);
                tx = x + 70;
            } else {
                Kit.text(g, font, Component.literal(count).withStyle(UiFont::bold), x + 6, y + 19, Theme.INK);
                tx = x + 10 + font.width(count) + 6;
            }
            int tw = x + w - 6 - tx;
            if (owned < 0) {
                ink(g, fit(L.t("wynnlabs.endgame.aspects_unread"), tw), tx, y + 20, Theme.INK_3);
                tip.add(Component.literal(L.t("wynnlabs.endgame.aspects_unread_tip")).withStyle(ChatFormatting.GRAY));
            } else {
                tip.add(L.c("wynnlabs.homev.aspects_tip", owned, d.total()).withStyle(ChatFormatting.GRAY));
                RankedAspect a = d.next();
                if (a == null) {
                    ink(g, fit(L.t("wynnlabs.homev.aspects_done"), tw), tx, y + 20, Theme.GOOD);
                } else {
                    String shortName = shortAspect(a.aspect().name());
                    List<String> name = wrap2(shortName, tw, true);
                    int by;
                    if (!String.join(" ", name).contains("…")) {
                        // beside the ring: "Next" over the name in up to two lines
                        ink(g, fit(L.t("wynnlabs.homev.aspects_next"), tw), tx, y + 20, Theme.INK_3);
                        for (int i = 0; i < name.size(); i++) {
                            Kit.text(g, font, Component.literal(name.get(i)).withStyle(UiFont::bold), tx, y + 31 + i * 10,
                                    tierColour(a.aspect().rarity()));
                        }
                        by = y + 31 + Math.max(2, name.size()) * 10 + 4;
                    } else {
                        // too narrow beside the ring: "Next: name" on a line under it
                        Component next = Component.literal(L.t("wynnlabs.homev.aspects_next") + ": ").withColor(Theme.INK_3 & 0xFFFFFF)
                                .append(Component.literal(shortName).withStyle(UiFont::bold).withColor(tierColour(a.aspect().rarity()) & 0xFFFFFF));
                        if (font.width(next) > w - 12) {
                            // still too wide: the name alone, in its colour
                            next = Component.literal(fit(shortName, w - 12, true)).withStyle(UiFont::bold)
                                    .withColor(tierColour(a.aspect().rarity()) & 0xFFFFFF);
                        }
                        Kit.text(g, font, next, x + 6, y + 51, Theme.INK);
                        by = y + 62;
                    }
                    tip.add(Component.literal(L.t("wynnlabs.homev.aspects_next") + ": " + a.aspect().name())
                            .withStyle(tierChat(a.aspect().rarity())));
                    if (!a.reason().isEmpty()) {
                        tip.add(Component.literal(a.reason()).withStyle(ChatFormatting.GRAY));
                    }
                    // what it boosts, one line under the ring: the abilities' names only (the tooltip has the rest)
                    List<String> abilities = new ArrayList<>();
                    a.matches().forEach(m -> abilities.add(m.ability()));
                    String boosts = abilities.isEmpty() ? a.reason() : L.t("wynnlabs.homev.aspects_boosts", String.join(", ", abilities));
                    if (!boosts.isEmpty() && by + 9 <= y + h - 5) {
                        ink(g, fit(boosts, w - 12), x + 6, by, Theme.INK_3);
                    }
                }
            }
        }
        tip.add(opens("wynnlabs.screen.tab_endgame"));
        host.area(x, y, w, h, null, tip, "#qopen:" + RoadmapScreen.TAB_ENDGAME);
    }

    /**
     * The professions: up to three rows (the crafting tab's profession first, then the highest),
     * each its name, a bar and its level. The bar is the XP into the level where Wynntils gives it,
     * else the level against the cap ({@link #PROF_CAP}).
     */
    private void professionTile(GuiGraphics g, Host host, int x, int y, int w, int h, int mx, int my) {
        tile(g, x, y, w, h, "hammer", L.t("wynnlabs.homev.profs"), Theme.AMBER, mx, my);
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(L.t("wynnlabs.homev.profs")).withStyle(ChatFormatting.GOLD));
        if (d.profs().isEmpty()) {
            ink(g, fit(L.t("wynnlabs.homev.profs_none"), w - 12), x + 6, y + 20, Theme.INK_3);
            tip.add(Component.literal(L.t("wynnlabs.homev.profs_none_tip")).withStyle(ChatFormatting.GRAY));
        } else {
            int pitch = Math.max(11, Math.min(18, (h - 24) / 3));
            int ry = y + 19;
            int lw = 0;                                         // the widest level: every bar ends at one edge
            int names = 0;                                      // the widest name (and its icon): the bars start after it
            for (String pr : d.profs()) {
                lw = Math.max(lw, font.width(String.valueOf(d.levels().get(pr))));
                names = Math.max(names, font.width(Professions.name(pr)) + (WynnTex.has("font/profession") ? 11 : 0));
            }
            int nw = Math.min(names + 4, w - 12 - lw - 4 - 16);    // a bar keeps 16px at least
            for (String pr : d.profs()) {
                if (ry + 9 > y + h - 5) {
                    break;
                }
                int lv = d.levels().get(pr);
                int[] xp = Crafting.professionXp(pr);
                boolean exact = xp != null && xp.length == 2 && xp[1] > 0;
                double frac = exact ? xp[0] / (double) xp[1] : lv / (double) PROF_CAP;
                int nx = WynnTex.profession(g, pr, x + 6, ry - 1, 9) ? x + 17 : x + 6;
                ink(g, fit(Professions.name(pr), x + 6 + nw - nx - 3), nx, ry, Theme.INK_2);
                String n = String.valueOf(lv);
                int bx = x + 6 + nw;
                int bw = x + w - 6 - lw - 4 - bx;
                int colour = Professions.gathering(pr) ? Theme.TEAL_LO : Theme.AMBER_LO;
                Art.pill(g, bx, ry + 3, bw, 3, Theme.WELL, 0);
                Art.pill(g, bx, ry + 3, Math.max(3, (int) Math.round(bw * Math.max(0, Math.min(1, frac)))), 3, colour, 0);
                ink(g, n, x + w - 6 - font.width(n), ry, Theme.INK);
                tip.add(Component.literal(Professions.name(pr) + " · Lv " + lv
                        + (exact ? " · " + L.t("wynnlabs.homev.profs_xp", RoadmapScreen.pct(Math.round(frac * 100))) : ""))
                        .withStyle(ChatFormatting.GRAY));
                ry += pitch;
            }
            if (d.profs().stream().noneMatch(pr -> {
                int[] xp = Crafting.professionXp(pr);
                return xp != null && xp.length == 2 && xp[1] > 0;
            })) {
                tip.add(L.c("wynnlabs.homev.profs_cap", PROF_CAP).withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        tip.add(opens("wynnlabs.screen.tab_craft"));
        host.area(x, y, w, h, null, tip, "#qopen:" + RoadmapScreen.TAB_CRAFT);
    }

    /** The best quest: a small compass pointing to it from where you look, its name, distance and level; a click tracks it. */
    private void questTile(GuiGraphics g, In in, Host host, int x, int y, int w, int h, int mx, int my) {
        tile(g, x, y, w, h, "map-2", L.t("wynnlabs.homev.quest"), 0xFF378ADD, mx, my);
        QuestAdvisor.Suggestion q = in.quest();
        if (q == null) {
            ink(g, fit(L.t(QuestAdvisor.known() == 0 ? "wynnlabs.home.open_book" : "wynnlabs.home.no_quest"), w - 12), x + 6, y + 20,
                    Theme.INK_3);
            host.area(x, y, w, h, null, List.of(opens("wynnlabs.screen.tab_quests")), "#qopen:2");
            return;
        }
        int px = large && h >= 52 ? 3 : 2;                  // the arrow's pixel: 7 of them across
        int ds = 7 * px + 8;
        int tx = x + 6;
        if (dial(g, q.quest(), x + 6, y + 18, ds)) {
            tx = x + 6 + ds + 5;
        }
        int tw = x + w - 6 - tx;
        ink(g, Component.literal(fit(QuestGuides.title(q.quest().name()), tw)).withStyle(ChatFormatting.AQUA), tx, y + 19, Theme.LINK);
        String sub = (q.distance() >= 0 ? RoadmapScreen.dist(q.distance()) + " · " : "") + "Lv. " + q.quest().level();
        ink(g, fit(sub, tw), tx, y + 30, Theme.INK_3);
        // why this one, where the tile has room under the dial
        int ry = y + 22 + ds;
        for (String r : q.reasons()) {
            ry = lines(g, "• " + r, x + 6, ry, w - 12, y + h - 5, Theme.INK_3);
        }
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(QuestGuides.title(q.quest().name())).withStyle(ChatFormatting.AQUA).withStyle(UiFont::bold));
        for (String r : q.reasons()) {
            tip.add(Component.literal("• " + r).withStyle(ChatFormatting.GRAY));
        }
        tip.add(L.c("wynnlabs.home.click_track").withStyle(ChatFormatting.GREEN));
        host.quest(x, y, w, h, tip, q.quest());
    }

    /**
     * A round dial with an arrow from where you look to the quest's start (up: straight ahead);
     * false and nothing drawn when its place or yours is unknown.
     */
    private boolean dial(GuiGraphics g, QuestBook.Entry e, int x, int y, int size) {
        LocalPlayer me = Minecraft.getInstance().player;
        if (me == null || e.x() == null || e.z() == null) {
            return false;
        }
        Art.box(g, x, y, size, size, 4, Theme.WELL, Theme.LINE);
        // the bearing in the game's yaw (0 south, 90 west) less where you face, clockwise on screen,
        // to the nearest of eight directions
        double bearing = Math.toDegrees(Math.atan2(-(e.x() - me.getX()), e.z() - me.getZ()));
        int dir = Math.floorMod((int) Math.round((bearing - me.getYRot()) / 45.0), 8);
        String[] art = dir % 2 == 0 ? ARROW_N : ARROW_NE;
        int px = (size - 8) / 7;
        int ox = x + (size - 7 * px) / 2;
        int oy = y + (size - 7 * px) / 2;
        for (int r = 0; r < 7; r++) {
            for (int c = 0; c < 7; c++) {
                if (art[r].charAt(c) != '#') {
                    continue;
                }
                // a quarter turn clockwise per two steps: (row, col) → (col, 6 - row)
                int rr = r;
                int cc = c;
                for (int t = 0; t < dir / 2; t++) {
                    int n = cc;
                    cc = 6 - rr;
                    rr = n;
                }
                g.fill(ox + cc * px, oy + rr * px, ox + (cc + 1) * px, oy + (rr + 1) * px, Theme.LINK);
            }
        }
        return true;
    }

    /** The dial's arrows as 7×7 pixel art, straight up and up-right; the other six are these turned. */
    private static final String[] ARROW_N = {
        "...#...",
        "..###..",
        ".#.#.#.",
        "#..#..#",
        "...#...",
        "...#...",
        "...#..."};
    private static final String[] ARROW_NE = {
        "..#####",
        ".....##",
        "....#.#",
        "...#..#",
        "..#....",
        ".#.....",
        "#......"};

    /** Where to level: the safest place for now with its safety dot, and the experience to the next level. */
    private void grindTile(GuiGraphics g, In in, Host host, int x, int y, int w, int h, int mx, int my) {
        String raw = null;
        for (String p : in.places()) {
            if (raw == null || RoadmapScreen.placeRisk(p) < RoadmapScreen.placeRisk(raw)) {
                raw = p;
            }
        }
        int risk = raw == null ? 1 : RoadmapScreen.placeRisk(raw);
        tile(g, x, y, w, h, "sword", L.t("wynnlabs.home.grind"), RoadmapScreen.RISK[risk], mx, my);
        int xp;
        try {
            xp = Wyn.xp().percentInt();
        } catch (Throwable t) {
            xp = -1;
        }
        if (raw == null) {
            ink(g, fit(L.t("wynnlabs.home.no_place"), w - 12), x + 6, y + 20, Theme.INK_3);
        } else {
            String place = RoadmapScreen.shortPlace(raw);
            String name = place.contains(" (") ? place.substring(0, place.indexOf(" (")) : place;
            String lv = place.contains(" (") ? place.substring(place.indexOf(" (") + 2, place.length() - 1) : "";
            int rc = Theme.paperRgb(RoadmapScreen.RISK[risk]);
            Art.box(g, x + 6, y + 20, 6, 6, 3, rc, 0);
            ink(g, fit(name, w - 22), x + 16, y + 19, Theme.INK);
            String safe = L.t(risk == 0 ? "wynnlabs.home.risk_safe" : risk == 1 ? "wynnlabs.home.risk_careful" : "wynnlabs.home.risk_risky");
            ink(g, fit(safe + (lv.isEmpty() ? "" : " · " + lv), w - 22), x + 16, y + 30, Theme.INK_3);
            String cmd = RoadmapScreen.compassFor(raw);
            host.area(x, y, w, h, null, List.of(Component.literal(place).withStyle(ChatFormatting.WHITE),
                    Component.literal(L.t(cmd != null ? "wynnlabs.screen.click_compass" : "wynnlabs.screen.click_copy_name"))
                            .withStyle(ChatFormatting.DARK_GRAY)), cmd != null ? cmd : name);
        }
        // the experience to the next level, as the band's bar
        if (xp >= 0 && h >= 50) {
            String p = RoadmapScreen.pct(xp);
            int bw = w - 12 - font.width(p) - 4;
            Kit.tealBar(g, x + 6, y + h - 11, bw, 4, Math.max(0, Math.min(100, xp)) / 100.0, true);
            ink(g, p, x + w - 6 - font.width(p), y + h - 13, Theme.INK_3);
        }
    }

    /** The bank: a strip of what is in it by kind (sell, quest, profession, gear, other), and what selling would bring. */
    private void bankTile(GuiGraphics g, Host host, int x, int y, int w, int h, int mx, int my) {
        tile(g, x, y, w, h, "building-bank", L.t("wynnlabs.homev.bank"), 0xFF5DCAA5, mx, my);
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(L.t("wynnlabs.homev.bank")).withStyle(ChatFormatting.DARK_AQUA));
        int[] b = d.bank();
        if (b == null) {
            ink(g, fit(L.t("wynnlabs.homev.bank_none"), w - 12), x + 6, y + 20, Theme.INK_3);
        } else {
            int[] colours = {Theme.MARK_SELL, Theme.MARK_QUEST, Theme.MARK_IDENTIFY, Theme.MARK_USE, 0xFFBCAE8E};
            String[] names = {L.t("wynnlabs.bank.filter_sell"), L.t("wynnlabs.bank.filter_quest"), L.t("wynnlabs.bank.filter_prof"),
                L.t("wynnlabs.bank.filter_gear"), L.t("wynnlabs.homev.bank_other")};
            int total = 0;
            for (int n : b) {
                total += n;
            }
            int sx = x + 6;
            int sw = w - 12;
            int at = sx;
            int run = 0;
            for (int i = 0; i < b.length; i++) {
                run += b[i];
                int end = total == 0 ? sx : sx + sw * run / total;
                g.fill(at, y + 19, end, y + 25, colours[i]);
                at = end;
                tip.add(Component.literal(names[i] + ": " + b[i]).withColor(colours[i] & 0xFFFFFF));
            }
            Kit.outline(g, sx - 1, y + 18, sw + 2, 8, Theme.LINE);
            String sell = L.t("wynnlabs.homev.bank_sell", b[0]) + (d.sellWorth() > 0 ? " · ≈" + Prices.fmt(d.sellWorth()) : "");
            ink(g, fit(sell, w - 12), x + 6, y + 30, b[0] > 0 ? Theme.BAD : Theme.INK_3);
            if (d.sellWorth() > 0) {
                tip.add(L.cn("wynnlabs.homev.bank_worth_tip", d.priced(), Prices.fmt(d.sellWorth()), d.priced()).withStyle(ChatFormatting.GOLD));
            }
            // a key under it when there is room: a colour square and its count, quest to gear
            if (h >= 52) {
                int kx = x + 6;
                for (int i = 1; i < 4; i++) {
                    String n = String.valueOf(b[i]);
                    if (kx + 9 + font.width(n) > x + w - 6) {
                        break;
                    }
                    g.fill(kx, y + 43, kx + 6, y + 49, colours[i]);
                    ink(g, n, kx + 8, y + 42, Theme.INK_3);
                    kx += 8 + font.width(n) + 8;
                }
            }
        }
        tip.add(opens("wynnlabs.screen.tab_bank"));
        host.area(x, y, w, h, null, tip, "#qopen:" + RoadmapScreen.TAB_BANK);
    }

    /** The nearest secret discovery not done yet at your level: its name, distance, level and region; a click opens it. */
    private void discoveryTile(GuiGraphics g, Host host, int x, int y, int w, int h, int mx, int my) {
        tile(g, x, y, w, h, "compass", L.t("wynnlabs.homev.discovery"), 0xFFC9A7F0, mx, my);
        Discoveries.Entry e = d.near();
        if (e == null) {
            ink(g, fit(L.t("wynnlabs.homev.discovery_none"), w - 12), x + 6, y + 20, Theme.INK_3);
            host.area(x, y, w, h, null, List.of(opens("wynnlabs.screen.tab_discoveries")), "#qopen:" + RoadmapScreen.TAB_DISCOVERIES);
            return;
        }
        ink(g, Component.literal(fit(e.name(), w - 12, true)).withStyle(UiFont::bold), x + 6, y + 19, Theme.ACCENT);
        ink(g, fit(RoadmapScreen.dist(d.nearDist()) + " · Lv. " + e.minLevel(), w - 12), x + 6, y + 30, Theme.INK_3);
        if (h >= 52 && e.region() != null) {
            ink(g, fit(e.region(), w - 12), x + 6, y + 41, Theme.INK_3);
        }
        host.area(x, y, w, h, null, List.of(Component.literal(e.name()).withStyle(ChatFormatting.LIGHT_PURPLE),
                Component.literal(e.region() == null ? "" : e.region()).withStyle(ChatFormatting.GRAY),
                opens("wynnlabs.screen.tab_discoveries")), "#qdisc:" + e.id());
    }

    // ---- compact: the tiles as rows ----

    /**
     * The compact page's tiles as rows beside the stand, in order of use (quest, where to level,
     * this week, aspects, bank, professions, discovery); the ones that do not fit are left out. A row
     * is a label over its answer when tall enough, else one line.
     */
    private void rows(GuiGraphics g, In in, Host host, int x, int y, int w, int h, int mx, int my) {
        List<String[]> rows = new ArrayList<>();       // {icon, label, value, action}
        List<List<Component>> tips = new ArrayList<>();
        QuestAdvisor.Suggestion q = in.quest();
        rows.add(new String[] {"map-2", L.t("wynnlabs.homev.quest"), q == null ? L.t("wynnlabs.home.no_quest")
            : QuestGuides.title(q.quest().name()) + (q.distance() >= 0 ? " · " + RoadmapScreen.dist(q.distance()) : ""), q == null ? "#qopen:2" : null});
        tips.add(q == null ? List.of() : List.of(Component.literal(QuestGuides.title(q.quest().name())).withStyle(ChatFormatting.AQUA),
                L.c("wynnlabs.home.click_track").withStyle(ChatFormatting.GREEN)));
        String raw = null;
        for (String p : in.places()) {
            if (raw == null || RoadmapScreen.placeRisk(p) < RoadmapScreen.placeRisk(raw)) {
                raw = p;
            }
        }
        String place = raw == null ? null : RoadmapScreen.shortPlace(raw);
        String cmd = raw == null ? null : RoadmapScreen.compassFor(raw);
        rows.add(new String[] {"sword", L.t("wynnlabs.home.grind"), place == null ? L.t("wynnlabs.home.no_place") : place,
            place == null ? null : cmd != null ? cmd : place});
        tips.add(place == null ? List.of() : List.of(Component.literal(place).withStyle(ChatFormatting.WHITE),
                Component.literal(L.t(cmd != null ? "wynnlabs.screen.click_compass" : "wynnlabs.screen.click_copy_name"))
                        .withStyle(ChatFormatting.DARK_GRAY)));
        rows.add(new String[] {"trophy", L.t("wynnlabs.homev.week"), d.pool().isEmpty() ? L.t("wynnlabs.homev.week_none")
            : L.n("wynnlabs.homev.week_count", d.pool().size()) + (d.raidsUp() && d.dryPulls() >= 0 ? " · " + L.n("wynnlabs.homev.week_dry", d.dryPulls()) : ""),
            "#qopen:" + RoadmapScreen.TAB_ENDGAME});
        tips.add(List.of(opens("wynnlabs.screen.tab_endgame")));
        RankedAspect a = d.next();
        rows.add(new String[] {"sparkles", L.t("wynnlabs.homev.aspects"), !d.aspectsUp() || d.total() == 0 ? L.t("wynnlabs.endgame.aspects_no_data")
            : (d.owned() < 0 ? "?" : String.valueOf(d.owned())) + "/" + d.total() + (a != null && d.owned() >= 0 ? " · " + shortAspect(a.aspect().name()) : ""),
            "#qopen:" + RoadmapScreen.TAB_ENDGAME});
        tips.add(List.of(opens("wynnlabs.screen.tab_endgame")));
        int[] b = d.bank();
        rows.add(new String[] {"building-bank", L.t("wynnlabs.homev.bank"), b == null ? L.t("wynnlabs.homev.bank_none")
            : L.t("wynnlabs.homev.bank_sell", b[0]) + (d.sellWorth() > 0 ? " · ≈" + Prices.fmt(d.sellWorth()) : ""), "#qopen:" + RoadmapScreen.TAB_BANK});
        tips.add(List.of(opens("wynnlabs.screen.tab_bank")));
        String top = d.profs().isEmpty() ? null : d.profs().get(0);
        rows.add(new String[] {"hammer", L.t("wynnlabs.homev.profs"), top == null ? L.t("wynnlabs.homev.profs_none")
            : Professions.name(top) + " " + d.levels().get(top), "#qopen:" + RoadmapScreen.TAB_CRAFT});
        tips.add(List.of(opens("wynnlabs.screen.tab_craft")));
        Discoveries.Entry e = d.near();
        rows.add(new String[] {"compass", L.t("wynnlabs.homev.discovery"), e == null ? L.t("wynnlabs.homev.discovery_none")
            : e.name() + " · " + RoadmapScreen.dist(d.nearDist()), e == null ? "#qopen:" + RoadmapScreen.TAB_DISCOVERIES : "#qdisc:" + e.id()});
        tips.add(List.of(opens("wynnlabs.screen.tab_discoveries")));

        int gap = 3;
        int n = Math.min(rows.size(), Math.max(1, (h + gap) / (14 + gap)));
        int rh = Math.min(26, (h - (n - 1) * gap) / n);
        boolean two = rh >= 22;
        for (int i = 0; i < n; i++) {
            String[] r = rows.get(i);
            int ry = y + i * (rh + gap);
            boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + rh;
            card(g, x, ry, w, rh, 0, hover);
            Art.icon(g, r[0], x + 4, ry + (rh - 8) / 2, 8, Theme.INK_2);
            int tx = x + 15;
            int tw = x + w - 4 - tx;
            if (two) {
                ink(g, fit(r[1], tw), tx, ry + 3, Theme.INK_3);
                ink(g, fit(r[2], tw), tx, ry + 13, Theme.INK);
            } else {
                ink(g, fit(r[2], tw), tx, ry + (rh - 8) / 2, Theme.INK);
            }
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(r[1] + ": " + r[2]).withStyle(ChatFormatting.WHITE));
            tip.addAll(tips.get(i));
            if (i == 0 && q != null) {
                host.quest(x, ry, w, rh, tip, q.quest());
            } else {
                host.area(x, ry, w, rh, null, tip, r[3]);
            }
        }
    }

    // ---- small helpers ----

    /** Text wrapped to {@code w} from line {@code y}, the lines that end above {@code bottom}; the next line's y. */
    private int lines(GuiGraphics g, String text, int x, int y, int w, int bottom, int colour) {
        for (net.minecraft.util.FormattedCharSequence l : font.split(Component.literal(text), w)) {
            if (y + 9 > bottom) {
                break;
            }
            g.drawString(font, Kit.ink(l), x, y, colour, false);
            y += 10;
        }
        return y;
    }

    private void ink(GuiGraphics g, String s, int x, int y, int colour) {
        Kit.text(g, font, s, x, y, colour);
    }

    private void ink(GuiGraphics g, Component c, int x, int y, int colour) {
        Kit.text(g, font, c, x, y, Theme.paperRgb(colour));
    }

    private String fit(String s, int w) {
        return fit(s, w, false);
    }

    /**
     * {@code s} cut with "…" to {@code w}, measured as drawn: bold text is wider (a pixel per
     * letter in the game's own font), so a name drawn bold is fitted in bold.
     */
    private String fit(String s, int w, boolean bold) {
        if (s == null || w <= 0) {
            return "";
        }
        if (width(s, bold) <= w) {
            return s;
        }
        String cut = font.plainSubstrByWidth(s, Math.max(0, w - width("…", bold)));
        while (!cut.isEmpty() && width(cut + "…", bold) > w) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    private int width(String s, boolean bold) {
        return bold ? font.width(Component.literal(s).withStyle(UiFont::bold)) : font.width(s);
    }

    /** "Aspect of the Monolith" → "Monolith", for a narrow tile. */
    private static String shortAspect(String name) {
        for (String p : new String[] {"Aspect of the ", "Aspect of "}) {
            if (name.startsWith(p)) {
                return name.substring(p.length());
            }
        }
        return name;
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
            case RARE -> ChatFormatting.LIGHT_PURPLE;
            case UNIQUE -> ChatFormatting.YELLOW;
            case SET -> ChatFormatting.GREEN;
            default -> ChatFormatting.WHITE;
        };
    }
}
