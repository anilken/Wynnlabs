package io.github.anilken.wynnlabs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scrollbar's maths and its mouse: the thumb's length and place for an offset and back, a
 * page per track click, clamping at both ends, and a drag that keeps the grab point.
 */
class ScrollBarTest {

    @Test
    void thumbLengthIsTheShareInViewWithAMinimum() {
        assertEquals(50, ScrollBar.thumbH(100, 10, 20), "half the list in view: half the track");
        assertEquals(ScrollBar.MIN_THUMB, ScrollBar.thumbH(100, 1, 1000), "never shorter than the minimum");
        assertEquals(100, ScrollBar.thumbH(100, 30, 20), "all in view: the whole track");
        assertEquals(8, ScrollBar.thumbH(8, 1, 1000), "never longer than the track");
    }

    @Test
    void positionAndOffsetGoBothWays() {
        int trackY = 10;
        int trackH = 110;
        int max = ScrollBar.max(10, 60);
        assertEquals(50, max);
        int th = ScrollBar.thumbH(trackH, 10, 60);
        for (int o : new int[] {0, 1, 17, 25, 49, 50}) {
            int ty = ScrollBar.thumbY(trackY, trackH, th, o, max);
            assertEquals(o, ScrollBar.offsetAt(ty, trackY, trackH, th, max), "offset " + o + " round-trips");
        }
        assertEquals(trackY, ScrollBar.thumbY(trackY, trackH, th, -5, max), "clamped at the top");
        assertEquals(trackY + trackH - th, ScrollBar.thumbY(trackY, trackH, th, 999, max), "clamped at the bottom: inside the track");
        assertEquals(0, ScrollBar.offsetAt(-100, trackY, trackH, th, max));
        assertEquals(max, ScrollBar.offsetAt(10_000, trackY, trackH, th, max));
    }

    @Test
    void aTrackClickMovesOnePage() {
        assertEquals(20, ScrollBar.page(10, 10, 50, 100, 30, 20), "below the thumb: a page down");
        assertEquals(0, ScrollBar.page(10, 10, 50, 5, 30, 20), "above: a page up");
        assertEquals(50, ScrollBar.page(45, 10, 50, 100, 30, 20), "clamped at the end");
        assertEquals(0, ScrollBar.page(3, 10, 50, 5, 30, 20), "clamped at the start");
        assertEquals(10, ScrollBar.page(10, 10, 50, 35, 30, 20), "on the thumb: no page");
    }

    @Test
    void aDragKeepsTheGrabPointAndEndsOnRelease() {
        ScrollBar bar = new ScrollBar();
        bar.place(200, 0, 100, 10, 110, 0);              // 100 rows to scroll, a 10px thumb at the top
        assertTrue(bar.shown());
        int[] thumb = bar.thumb();
        assertEquals(0, thumb[1]);
        assertTrue(bar.mouseClicked(202, 5), "a press on the thumb");
        assertTrue(bar.dragging());
        assertTrue(bar.mouseDragged(50), "the thumb follows, the grab 5px into it kept");
        assertEquals(ScrollBar.offsetAt(45, 0, 100, thumb[3], 100), bar.offset());
        assertTrue(bar.mouseDragged(5000), "past the bar: still tracking, clamped");
        assertEquals(100, bar.offset());
        bar.mouseReleased();
        assertFalse(bar.dragging());
        assertFalse(bar.mouseDragged(10), "released: nothing follows");
        assertFalse(bar.mouseClicked(100, 5), "beside the bar: not taken");
        // the track below the thumb pages
        bar.place(200, 0, 100, 10, 110, 0);
        assertTrue(bar.mouseClicked(202, 90));
        assertEquals(10, bar.offset());
    }
}
