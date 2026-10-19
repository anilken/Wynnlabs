package io.github.anilken.wynnlabs;

/**
 * The screenshot tool's picture maths, without Minecraft: the mod's window box from menu pixels to
 * GUI pixels to framebuffer pixels, and the window alone on transparency from two frames of it,
 * one over a black backdrop and one over a white one.
 *
 * <p>Over black a pixel reads {@code c·a}, over white {@code c·a + 255·(1 − a)}: their difference
 * gives its alpha {@code a}, and the black one its colour {@code c}. The frame's rounded corners,
 * nails, shadow and a modal's dimmed backdrop keep their own alpha; whatever is outside the
 * window comes out fully transparent.
 */
final class ShotCrop {

    private ShotCrop() {}

    /** Transparent pixels kept around the window's box. */
    static final int MARGIN = 3;

    /**
     * A box drawn at the menu's logical pixels, in GUI pixels: MenuScale draws under a pose scaled
     * by 1 ÷ {@code factor} (game scale ÷ menu scale). {x0, y0, x1, y1}.
     */
    static float[] toGui(int x, int y, int w, int h, double factor) {
        float f = (float) (1 / factor);
        return new float[] {x * f, y * f, (x + w) * f, (y + h) * f};
    }

    /** Two boxes {x0, y0, x1, y1} as one; either may be null. */
    static float[] union(float[] a, float[] b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return new float[] {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])};
    }

    /**
     * A GUI box {x0, y0, x1, y1} in framebuffer pixels {x, y, w, h} at the game's GUI scale,
     * rounded outward so no pixel of it is lost (a hair of float error is not a pixel).
     */
    static int[] pixels(float[] gui, double guiScale) {
        int x0 = (int) Math.floor(gui[0] * guiScale + 1e-3);
        int y0 = (int) Math.floor(gui[1] * guiScale + 1e-3);
        int x1 = (int) Math.ceil(gui[2] * guiScale - 1e-3);
        int y1 = (int) Math.ceil(gui[3] * guiScale - 1e-3);
        return new int[] {x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0)};
    }

    /** One pixel from its frame over black and its frame over white (ARGB, their alpha ignored): its own colour and alpha. */
    static int unmix(int black, int white) {
        int br = black >> 16 & 0xFF;
        int bg = black >> 8 & 0xFF;
        int bb = black & 0xFF;
        int wr = white >> 16 & 0xFF;
        int wg = white >> 8 & 0xFF;
        int wb = white & 0xFF;
        int a = Math.round((765 - ((wr - br) + (wg - bg) + (wb - bb))) / 3f);
        a = Math.max(0, Math.min(255, a));
        if (a == 0) {
            return 0;
        }
        int r = Math.min(255, Math.round(br * 255f / a));
        int g = Math.min(255, Math.round(bg * 255f / a));
        int b = Math.min(255, Math.round(bb * 255f / a));
        return a << 24 | r << 16 | g << 8 | b;
    }

    /** A frame's pixel at (x, y), ARGB. */
    @FunctionalInterface
    interface Px {
        int at(int x, int y);
    }

    /**
     * The picture: the box {@code rect} {x, y, w, h} of two {@code imgW} × {@code imgH} frames with
     * {@code margin} pixels around it, each pixel {@link #unmix unmixed}; anything off the frames
     * is transparent. Row by row, ARGB, (w + 2·margin) × (h + 2·margin).
     */
    static int[] compose(Px black, Px white, int imgW, int imgH, int[] rect, int margin) {
        int ow = rect[2] + 2 * margin;
        int oh = rect[3] + 2 * margin;
        int[] out = new int[ow * oh];
        for (int oy = 0; oy < oh; oy++) {
            int y = rect[1] - margin + oy;
            if (y < 0 || y >= imgH) {
                continue;
            }
            for (int ox = 0; ox < ow; ox++) {
                int x = rect[0] - margin + ox;
                if (x >= 0 && x < imgW) {
                    out[oy * ow + ox] = unmix(black.at(x, y), white.at(x, y));
                }
            }
        }
        return out;
    }
}
