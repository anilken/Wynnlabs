package io.github.anilken.wynnlabs.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * A list's scrollbar that works with the mouse, not only the wheel: its thumb is as long as the
 * share of the list in view (at least {@link #MIN_THUMB}) and never leaves the track; pressing it
 * and dragging moves the list with the grab point kept, also when the pointer leaves the bar,
 * until the button is let go; a click on the track above or below the thumb moves one page.
 * Offsets count rows. Drawn with the kit's wood button (lighter on hover, gold while dragged).
 */
final class ScrollBar {

    /** The bar's width. */
    static final int W = 6;
    /** The thumb's shortest length. */
    static final int MIN_THUMB = 10;

    private int x;
    private int y;
    private int h;
    private int visible;
    private int total;
    private int offset;
    private boolean dragging;
    private double grab;

    // ---- the maths (tests use these) ----

    /** The furthest offset: the rows past what is in view. */
    static int max(int visible, int total) {
        return Math.max(0, total - visible);
    }

    /** The thumb's length on a track {@code trackH} long. */
    static int thumbH(int trackH, int visible, int total) {
        if (total <= 0 || visible >= total) {
            return trackH;
        }
        return Math.min(trackH, Math.max(MIN_THUMB, (int) Math.round(trackH * (double) visible / total)));
    }

    /** Where the thumb starts for {@code offset} (clamped). */
    static int thumbY(int trackY, int trackH, int thumbH, int offset, int max) {
        if (max <= 0) {
            return trackY;
        }
        int o = Math.max(0, Math.min(max, offset));
        return trackY + (int) Math.round((trackH - thumbH) * (double) o / max);
    }

    /** The offset whose thumb starts at {@code thumbTop} (clamped). */
    static int offsetAt(double thumbTop, int trackY, int trackH, int thumbH, int max) {
        int room = trackH - thumbH;
        if (room <= 0 || max <= 0) {
            return 0;
        }
        double f = (thumbTop - trackY) / room;
        return Math.max(0, Math.min(max, (int) Math.round(f * max)));
    }

    /** A click on the track at {@code clickY}: one page up when above the thumb, down when below. */
    static int page(int offset, int visible, int max, double clickY, int thumbTop, int thumbH) {
        int step = Math.max(1, visible);
        if (clickY < thumbTop) {
            return Math.max(0, offset - step);
        }
        if (clickY >= thumbTop + thumbH) {
            return Math.min(max, offset + step);
        }
        return offset;
    }

    // ---- this frame's bar ----

    /** Places the bar for this frame: its track at {@code x, y}, {@code h} long, for {@code visible} of {@code total} rows. */
    void place(int x, int y, int h, int visible, int total, int offset) {
        this.x = x;
        this.y = y;
        this.h = h;
        this.visible = visible;
        this.total = total;
        this.offset = Math.max(0, Math.min(max(visible, total), offset));
    }

    /** Whether there is anything to scroll. */
    boolean shown() {
        return max(visible, total) > 0;
    }

    int offset() {
        return offset;
    }

    boolean dragging() {
        return dragging;
    }

    private int thumbH() {
        return thumbH(h, visible, total);
    }

    private int thumbTop() {
        return thumbY(y, h, thumbH(), offset, max(visible, total));
    }

    /** The track's box {x, y, w, h}, for the click areas. */
    int[] track() {
        return new int[] {x, y, W, h};
    }

    /** The thumb's box {x, y, w, h}. */
    int[] thumb() {
        return new int[] {x, thumbTop(), W, thumbH()};
    }

    void draw(GuiGraphics g, int mx, int my) {
        if (!shown()) {
            return;
        }
        g.fill(x + 2, y, x + W - 2, y + h, 0x30000000);
        int ty = thumbTop();
        int th = thumbH();
        boolean hover = mx >= x && mx < x + W && my >= ty && my < ty + th;
        Kit.button(g, x, ty, W, th, dragging ? Kit.ON : hover ? Kit.HOVER : Kit.REST);
    }

    /** A press: on the thumb it starts a drag, on the track it pages. True when the bar took it. */
    boolean mouseClicked(double mx, double my) {
        if (!shown() || mx < x || mx >= x + W || my < y || my >= y + h) {
            return false;
        }
        int ty = thumbTop();
        int th = thumbH();
        if (my >= ty && my < ty + th) {
            dragging = true;
            grab = my - ty;
        } else {
            offset = page(offset, visible, max(visible, total), my, ty, th);
        }
        return true;
    }

    /** A drag with the button held: the thumb follows the pointer, the grab point kept. True while dragging. */
    boolean mouseDragged(double my) {
        if (!dragging) {
            return false;
        }
        offset = offsetAt(my - grab, y, h, thumbH(), max(visible, total));
        return true;
    }

    /** The button let go: the drag ends. */
    void mouseReleased() {
        dragging = false;
    }
}
