package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.Platform;
import io.github.anilken.wynnlabs.search.MapCamera;
import io.github.anilken.wynnlabs.search.MapTiles;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A small framed map of where an item comes from: a crop of the Wynntils world map read from its
 * cache, with each source as a circle (an area) or a dot (a point), the selected one bolder, and
 * the player as a gold dot. The wheel zooms around the cursor, dragging pans; the view keeps to
 * the map. Without the map pictures it draws a parchment grid with the markers and a note.
 *
 * <p>Use: make one per screen, give it {@link #markers}, {@link #player} and {@link #focus} or
 * {@link #fit}, call {@link #draw} each frame and pass it the mouse; {@link #close} when the
 * screen closes frees its textures. Nothing here reads files on the render thread or allocates
 * more than a few small arrays per frame.
 */
final class SearchMapView implements AutoCloseable {

    /**
     * A place on the map: world (x, z), a radius in blocks (0 for a point), its colour (ARGB, alpha
     * ignored), a label shown on hover and for the selected one, and whether it is selected.
     */
    record Marker(double x, double z, double radius, int colour, String label, boolean selected) {}

    /** The frame's width around the map, in GUI px. */
    static final int FRAME = 3;
    /** The smallest circle drawn for an area, in GUI px, so a small area stays visible zoomed out. */
    private static final int MIN_RING = 4;
    private static final int GRID = 256;          // blocks between the fallback grid's lines
    private static final double WHEEL = 1.25;     // zoom per wheel notch
    private static final int RIM = 0x992E2E2E;    // the dark edge round a marker

    private final MapTileCache tiles;
    private final MapCamera camera = new MapCamera();
    private final List<MapTiles.Tile> wanted = new ArrayList<>();
    private final double[] view = new double[4];

    private List<Marker> markers = List.of();
    private boolean hasPlayer;
    private double playerX;
    private double playerZ;
    private Component noMap;
    private boolean boundsSet;
    private int hovered = -1;
    private boolean complete;

    private boolean dragging;
    private double lastX;
    private double lastY;

    /** The inner (map) area of the last frame, for the mouse. */
    private int ix;
    private int iy;
    private int iw;
    private int ih;

    /** A map over Wynntils' cache in this game folder. */
    SearchMapView() {
        this(Platform.get().gameDir().resolve("wynntils").resolve("cache"));
    }

    /** A map over the Wynntils cache folder {@code cacheDir} (null: no map pictures). */
    SearchMapView(Path cacheDir) {
        tiles = new MapTileCache(cacheDir);
    }

    // ---- inputs ----

    /** The places to show (the list is kept as given; pass a new list when it changes). */
    void markers(List<Marker> list) {
        markers = list == null ? List.of() : list;
        hovered = -1;
        boundsSet = false;
    }

    /** The player's position; {@link #noPlayer} when it is not known. */
    void player(double x, double z) {
        hasPlayer = true;
        playerX = x;
        playerZ = z;
    }

    void noPlayer() {
        hasPlayer = false;
    }

    /** The note shown when the map pictures are missing (a lang text from the tab); null for none. */
    void noMapText(Component text) {
        noMap = text;
    }

    /** Looks at (x, z), keeping the zoom (selecting a source). */
    void focus(double x, double z) {
        boundsSet = false;
        camera.centre(x, z);
    }

    /** Sets the zoom (GUI px per block, 0.25..4), keeping the centre. */
    void zoom(double zoom) {
        camera.zoom(zoom);
    }

    /**
     * Frames every marker (and the player when {@code withPlayer}) in a view of {@code w}×{@code h}
     * GUI px (the size {@link #draw} will be given), no closer than 2 px per block.
     */
    void fit(int w, int h, boolean withPlayer) {
        if (markers.isEmpty() && !(withPlayer && hasPlayer)) {
            return;
        }
        double x0 = Double.POSITIVE_INFINITY;
        double z0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY;
        double z1 = Double.NEGATIVE_INFINITY;
        for (Marker m : markers) {
            x0 = Math.min(x0, m.x() - m.radius());
            z0 = Math.min(z0, m.z() - m.radius());
            x1 = Math.max(x1, m.x() + m.radius());
            z1 = Math.max(z1, m.z() + m.radius());
        }
        if (withPlayer && hasPlayer) {
            x0 = Math.min(x0, playerX);
            z0 = Math.min(z0, playerZ);
            x1 = Math.max(x1, playerX);
            z1 = Math.max(z1, playerZ);
        }
        boundsSet = false;
        camera.size(w - 2 * FRAME, h - 2 * FRAME);
        camera.fit(x0, z0, x1, z1, 10, 2);
    }

    /** The camera (centre and zoom), for the tab to keep or show. */
    MapCamera camera() {
        return camera;
    }

    /** Whether the map pictures are known to be missing (the table is read and has no tile). */
    boolean noTiles() {
        MapTiles t = tiles.table();
        return t != null && t.isEmpty();
    }

    /** Whether the last frame drew every map picture it needed (none still loading). */
    boolean ready() {
        return complete;
    }

    /** Textures held now. */
    int texturesHeld() {
        return tiles.held();
    }

    // ---- drawing ----

    /** The framed map in the box (GUI px); {@code mx, my} the mouse, for the hover label. */
    void draw(GuiGraphics g, Font font, int x, int y, int w, int h, int mx, int my) {
        // the frame: a dark edge, the wood, and a thin inner shadow on the map's rim
        g.fill(x, y, x + w, y + h, Theme.WOOD_EDGE);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, Theme.WOOD);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, Theme.WOOD_HI);
        ix = x + FRAME;
        iy = y + FRAME;
        iw = Math.max(1, w - 2 * FRAME);
        ih = Math.max(1, h - 2 * FRAME);
        camera.size(iw, ih);
        MapTiles table = tiles.table();
        if (!boundsSet && table != null) {
            camera.bounds(bounds(table));
            boundsSet = true;
        }
        camera.visible(view);

        g.enableScissor(ix, iy, ix + iw, iy + ih);
        g.fill(ix, iy, ix + iw, iy + ih, Theme.PAPER_DEEP);
        grid(g);
        complete = table != null;
        if (table != null) {
            table.covering(view[0], view[1], view[2], view[3], wanted);
            for (MapTiles.Tile t : wanted) {
                Identifier id = tiles.texture(t, wanted);
                if (id != null) {
                    tile(g, id, t);
                } else if (!tiles.failed(t)) {
                    complete = false;
                }
            }
        }
        hovered = markerAt(mx, my);
        for (int pass = 0; pass < 2; pass++) {               // the selected ones on top
            for (Marker m : markers) {
                if (m.selected() == (pass == 1)) {
                    marker(g, m);
                }
            }
        }
        if (hasPlayer) {
            int px = ix + (int) Math.round(camera.viewX(playerX));
            int py = iy + (int) Math.round(camera.viewY(playerZ));
            disc(g, px, py, 5, Theme.WOOD_EDGE);
            disc(g, px, py, 4, Theme.GOLD);
            disc(g, px, py, 1, Theme.WOOD_EDGE);
        }
        g.disableScissor();
        // the inner shadow over the map's top and left edge
        g.fill(ix, iy, ix + iw, iy + 1, 0x55000000);
        g.fill(ix, iy + 1, ix + 1, iy + ih, 0x55000000);

        Marker label = hovered >= 0 ? markers.get(hovered) : selected();
        if (label != null && label.label() != null && !label.label().isEmpty()) {
            label(g, font, label);
        }
        if (noMap != null && table != null && table.isEmpty()) {
            note(g, font);
        }
    }

    private double[] bounds(MapTiles table) {
        double[] b = table.bounds();
        if (b == null) {
            b = new double[] {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                    Double.NEGATIVE_INFINITY};
        }
        // the markers and the player may lie off the main map (a quest island): keep them reachable
        for (Marker m : markers) {
            b = grow(b, m.x(), m.z());
        }
        if (hasPlayer) {
            b = grow(b, playerX, playerZ);
        }
        b = grow(b, camera.centreX(), camera.centreZ());
        return b;
    }

    private static double[] grow(double[] b, double x, double z) {
        b[0] = Math.min(b[0], x);
        b[1] = Math.min(b[1], z);
        b[2] = Math.max(b[2], x);
        b[3] = Math.max(b[3], z);
        return b;
    }

    /** Faint lines every {@link #GRID} blocks, fixed to the world so they move with the map. */
    private void grid(GuiGraphics g) {
        int colour = 0x309A855C;
        for (double gx = Math.ceil(view[0] / GRID) * GRID; gx < view[2]; gx += GRID) {
            int sx = ix + (int) Math.round(camera.viewX(gx));
            g.fill(sx, iy, sx + 1, iy + ih, colour);
        }
        for (double gz = Math.ceil(view[1] / GRID) * GRID; gz < view[3]; gz += GRID) {
            int sy = iy + (int) Math.round(camera.viewY(gz));
            g.fill(ix, sy, ix + iw, sy + 1, colour);
        }
    }

    /** A tile's picture under a pose that maps its pixels onto world blocks: exact at any zoom. */
    private void tile(GuiGraphics g, Identifier id, MapTiles.Tile t) {
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) (ix + camera.viewX(t.x1())), (float) (iy + camera.viewY(t.z1())));
        pose.scale((float) camera.zoom(), (float) camera.zoom());
        g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0, 0, t.width(), t.height(), t.width(), t.height());
        pose.popMatrix();
    }

    private void marker(GuiGraphics g, Marker m) {
        int cx = ix + (int) Math.round(camera.viewX(m.x()));
        int cy = iy + (int) Math.round(camera.viewY(m.z()));
        int rgb = m.colour() & 0xFFFFFF;
        if (m.radius() <= 0) {
            int r = m.selected() ? 3 : 2;
            disc(g, cx, cy, r + 2, m.selected() ? Theme.WOOD_EDGE : RIM);
            disc(g, cx, cy, r + 1, Theme.CREAM);
            disc(g, cx, cy, r, 0xFF000000 | rgb);
            return;
        }
        int r = Math.max(MIN_RING, (int) Math.round(m.radius() * camera.zoom()));
        int fill = (m.selected() ? 0x66000000 : 0x38000000) | rgb;
        int stroke = 0xFF000000 | rgb;
        ring(g, cx, cy, r, m.selected() ? 2 : 1, fill, stroke);
        // a dark rim outside the ring, so it reads on any ground; the selected one's is the frame's wood
        ring(g, cx, cy, r + 1, 1, 0, m.selected() ? Theme.WOOD_EDGE : RIM);
    }

    /**
     * A circle by rows: the inside filled with {@code fill} (0 for none) and a ring {@code t} px
     * thick in {@code stroke}. Only the rows inside the map are drawn, so a big circle zoomed in
     * costs no more than the map's height.
     */
    private void ring(GuiGraphics g, int cx, int cy, int r, int t, int fill, int stroke) {
        int top = Math.max(-r, iy - cy);
        int bottom = Math.min(r, iy + ih - 1 - cy);
        if (cx + r < ix || cx - r >= ix + iw) {
            return;
        }
        int ri = r - t;
        for (int dy = top; dy <= bottom; dy++) {
            int half = span(r, dy);
            int y0 = cy + dy;
            if (ri <= 0 || Math.abs(dy) > ri) {
                g.fill(cx - half, y0, cx + half + 1, y0 + 1, stroke);
                continue;
            }
            int in = span(ri, dy);
            g.fill(cx - half, y0, cx - in, y0 + 1, stroke);
            g.fill(cx + in + 1, y0, cx + half + 1, y0 + 1, stroke);
            if (fill != 0) {
                g.fill(cx - in, y0, cx + in + 1, y0 + 1, fill);
            }
        }
    }

    private static void disc(GuiGraphics g, int cx, int cy, int r, int colour) {
        for (int dy = -r; dy <= r; dy++) {
            int half = span(r, dy);
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, colour);
        }
    }

    /** Half the width of a circle of radius r at row dy (no lone pixel at the widest row). */
    private static int span(int r, int dy) {
        double rr = r + 0.5;
        return (int) Math.floor(Math.sqrt(Math.max(0, rr * rr - dy * dy)));
    }

    private void label(GuiGraphics g, Font font, Marker m) {
        String s = font.plainSubstrByWidth(m.label(), Math.max(20, iw - 8));
        int tw = font.width(s) + 2 * Kit.Ui.TAG_PAD;
        int th = Kit.Ui.TAG_H;
        int cx = ix + (int) Math.round(camera.viewX(m.x()));
        int cy = iy + (int) Math.round(camera.viewY(m.z()));
        int r = m.radius() <= 0 ? 3 : Math.max(MIN_RING, (int) Math.round(m.radius() * camera.zoom()));
        int lx = Math.max(ix + 2, Math.min(ix + iw - 2 - tw, cx - tw / 2));
        int ly = cy - r - th - 2;
        if (ly < iy + 2) {
            ly = Math.min(iy + ih - th - 2, cy + r + 2);
        }
        ly = Math.max(iy + 2, ly);
        g.fill(lx, ly, lx + tw, ly + th, Theme.CARD);
        Kit.outline(g, lx, ly, tw, th, Theme.LINE);
        Kit.text(g, font, s, lx + Kit.Ui.TAG_PAD, Kit.Ui.textY(ly, th) + 1, Theme.INK);
    }

    private void note(GuiGraphics g, Font font) {
        String s = font.plainSubstrByWidth(noMap.getString(), Math.max(20, iw - 12));
        int tw = font.width(s) + 2 * Kit.Ui.BTN_PAD;
        int th = Kit.Ui.BTN_H;
        int lx = ix + (iw - tw) / 2;
        int ly = iy + ih - th - Kit.Ui.S4;
        g.fill(lx, ly, lx + tw, ly + th, Theme.CARD);
        Kit.outline(g, lx, ly, tw, th, Theme.LINE);
        Kit.text(g, font, s, lx + Kit.Ui.BTN_PAD, Kit.Ui.textY(ly, th), Theme.INK_3);
    }

    private Marker selected() {
        for (Marker m : markers) {
            if (m.selected()) {
                return m;
            }
        }
        return null;
    }

    // ---- mouse (GUI px, the same as draw's) ----

    /** Whether (mx, my) is over the map (as last drawn). */
    boolean isOver(double mx, double my) {
        return mx >= ix && mx < ix + iw && my >= iy && my < iy + ih;
    }

    /** The index of the marker under (mx, my), the nearest centre first; -1 for none. */
    int markerAt(double mx, double my) {
        if (!isOver(mx, my)) {
            return -1;
        }
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < markers.size(); i++) {
            Marker m = markers.get(i);
            double dx = mx - (ix + camera.viewX(m.x()));
            double dy = my - (iy + camera.viewY(m.z()));
            double d = Math.sqrt(dx * dx + dy * dy);
            double r = m.radius() <= 0 ? 4 : Math.max(MIN_RING, m.radius() * camera.zoom()) + 1;
            if (d <= r && d < bestD) {
                best = i;
                bestD = d;
            }
        }
        return best;
    }

    /** A press over the map starts a drag; true when it was over the map. */
    boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !isOver(mx, my)) {
            return false;
        }
        dragging = true;
        lastX = mx;
        lastY = my;
        return true;
    }

    /** The mouse moved with the button down; true while dragging the map. */
    boolean mouseDragged(double mx, double my) {
        if (!dragging) {
            return false;
        }
        camera.pan(mx - lastX, my - lastY);
        lastX = mx;
        lastY = my;
        return true;
    }

    void mouseReleased() {
        dragging = false;
    }

    /** The wheel over the map zooms around the cursor; true when it was over the map. */
    boolean mouseScrolled(double mx, double my, double v) {
        if (v == 0 || !isOver(mx, my)) {
            return false;
        }
        camera.zoomAround(mx - ix, my - iy, Math.pow(WHEEL, v));
        return true;
    }

    /** Frees the textures; call when the screen closes. */
    @Override
    public void close() {
        tiles.close();
    }
}
