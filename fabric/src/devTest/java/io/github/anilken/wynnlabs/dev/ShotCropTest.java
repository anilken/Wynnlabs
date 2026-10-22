package io.github.anilken.wynnlabs.dev;

import org.joml.Matrix3x2f;
import org.joml.Vector2f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The screenshot tool's crop: the window's box from menu pixels to framebuffer pixels, and its pixels unmixed from two backdrops. */
class ShotCropTest {

    /** The menu's window box in its logical pixels: the frame (x0 − 5 …) with its shadow, as RoadmapScreen draws it on a 640 × 360 page. */
    private static final int[] WINDOW = {15, 9, 610, 345};

    private static int[] crop(int[] logical, double guiScale, double factor) {
        return ShotCrop.pixels(ShotCrop.toGui(logical[0], logical[1], logical[2], logical[3], factor), guiScale);
    }

    @Test
    void atTheGamesOwnScaleAGuiPixelIsScalePixels() {
        for (int s : new int[] {2, 3, 4}) {
            assertArrayEquals(new int[] {15 * s, 9 * s, 610 * s, 345 * s}, crop(WINDOW, s, 1), "GUI scale " + s);
        }
    }

    @Test
    void aMenuScaleOtherThanTheGamesDrawsAtTheMenuScale() {
        // game 3, menu 4: logical pixels are a quarter of the framebuffer's, GUI pixels a third
        assertArrayEquals(new int[] {60, 36, 2440, 1380}, crop(WINDOW, 3, 3 / 4.0));
        // game 4, menu 3
        assertArrayEquals(new int[] {45, 27, 1830, 1035}, crop(WINDOW, 4, 4 / 3.0));
    }

    @Test
    void aBoxBetweenPixelsIsRoundedOutward() {
        // 7 logical px at game 4 / menu 3: 7 × 3 = 21 framebuffer px, from 1 × 3 = 3
        assertArrayEquals(new int[] {3, 3, 21, 21}, crop(new int[] {1, 1, 7, 7}, 4, 4 / 3.0));
        // a GUI box on fractions: 0.4 … 2.6 at scale 2 is 0.8 … 5.2 → 0 … 6
        assertArrayEquals(new int[] {0, 0, 6, 6}, ShotCrop.pixels(new float[] {0.4f, 0.4f, 2.6f, 2.6f}, 2));
    }

    @Test
    void theMenuPoseGivesTheSameGuiBox() {
        // MenuScale draws under a pose scaled by 1 / factor: Shots.frame reads the box through it
        for (double factor : new double[] {1, 3 / 4.0, 4 / 3.0, 2 / 3.0}) {
            Matrix3x2f pose = new Matrix3x2f().scale((float) (1 / factor), (float) (1 / factor));
            Vector2f a = pose.transformPosition(WINDOW[0], WINDOW[1], new Vector2f());
            Vector2f b = pose.transformPosition(WINDOW[0] + WINDOW[2], WINDOW[1] + WINDOW[3], new Vector2f());
            float[] gui = ShotCrop.toGui(WINDOW[0], WINDOW[1], WINDOW[2], WINDOW[3], factor);
            assertArrayEquals(new float[] {a.x, a.y, b.x, b.y}, gui, 1e-4f, "factor " + factor);
        }
    }

    @Test
    void unionKeepsTheSealOutsideTheCorner() {
        float[] frame = {10, 10, 300, 200};
        float[] seal = {293, 1, 307, 15};
        assertArrayEquals(new float[] {10, 1, 307, 200}, ShotCrop.union(frame, seal));
        assertArrayEquals(seal, ShotCrop.union(null, seal));
    }

    @Test
    void twoBackdropsGiveEachPixelItsOwnAlpha() {
        // the world's place: black over black, white over white
        assertEquals(0, ShotCrop.unmix(0xFF000000, 0xFFFFFFFF));
        // an opaque pixel reads the same on both
        assertEquals(0xFFC08040, ShotCrop.unmix(0xFFC08040, 0xFFC08040));
        // the frame's shadow, black at 40%: 0 over black, 153 over white
        assertEquals(0x66000000, ShotCrop.unmix(0xFF000000, 0xFF999999));
        // a half-covered red: 128,0,0 over black, 255,127,127 over white
        int px = ShotCrop.unmix(0xFF800000, 0xFFFF7F7F);
        assertEquals(128, px >>> 24, 1);
        assertEquals(255, px >> 16 & 0xFF, 1);
        assertEquals(0, px & 0xFFFF);
    }

    @Test
    void composeCropsWithATransparentMargin() {
        // a 10 × 8 frame: a 4 × 3 opaque box at (3, 2) over the backdrop
        int w = 10;
        int h = 8;
        ShotCrop.Px black = (x, y) -> x >= 3 && x < 7 && y >= 2 && y < 5 ? 0xFF123456 : 0xFF000000;
        ShotCrop.Px white = (x, y) -> x >= 3 && x < 7 && y >= 2 && y < 5 ? 0xFF123456 : 0xFFFFFFFF;
        int m = 3;
        int[] out = ShotCrop.compose(black, white, w, h, new int[] {3, 2, 4, 3}, m);
        int ow = 4 + 2 * m;
        assertEquals(ow * (3 + 2 * m), out.length);
        assertEquals(0, out[0]);                                    // a margin corner, off the frame's top-left
        assertEquals(0xFF123456, out[m * ow + m]);                  // the box's first pixel
        assertEquals(0xFF123456, out[(m + 2) * ow + m + 3]);        // and its last
        assertEquals(0, out[(m + 3) * ow + m]);                     // under it: the backdrop, transparent
        // a box at the frame's edge: the margin past it is transparent, not an error
        int[] edge = ShotCrop.compose(black, white, w, h, new int[] {0, 0, 2, 2}, m);
        assertEquals(0, edge[0]);
    }
}
