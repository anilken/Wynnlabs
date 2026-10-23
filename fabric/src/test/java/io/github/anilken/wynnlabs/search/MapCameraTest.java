package io.github.anilken.wynnlabs.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map view's sums: world ↔ view pixels both ways, the wheel zooming around the cursor
 * within 0.25..4, a drag moving the world with the mouse, the centre kept inside the map, and
 * fitting a set of places.
 */
class MapCameraTest {

    private static MapCamera camera() {
        MapCamera c = new MapCamera();
        c.size(200, 100);
        c.centre(1236, -1431);
        return c;
    }

    @Test
    void worldAndViewGoBothWays() {
        MapCamera c = camera();
        assertEquals(100, c.viewX(1236), 1e-9, "the centre is the middle of the view");
        assertEquals(50, c.viewY(-1431), 1e-9);
        c.zoom(2);
        assertEquals(120, c.viewX(1246), 1e-9, "10 blocks east at 2 px per block");
        assertEquals(30, c.viewY(-1441), 1e-9, "north is up (−Z)");
        assertEquals(1246, c.worldX(c.viewX(1246)), 1e-9);
        assertEquals(-1441, c.worldZ(c.viewY(-1441)), 1e-9);
        double[] v = c.visible(new double[4]);
        assertEquals(1186, v[0], 1e-9);
        assertEquals(-1456, v[1], 1e-9);
        assertEquals(1286, v[2], 1e-9);
        assertEquals(-1406, v[3], 1e-9);
    }

    @Test
    void wheelZoomKeepsThePointUnderTheCursor() {
        MapCamera c = camera();
        double wx = c.worldX(30);
        double wz = c.worldZ(80);
        c.zoomAround(30, 80, 1.25);
        assertEquals(1.25, c.zoom(), 1e-9);
        assertEquals(30, c.viewX(wx), 1e-9);
        assertEquals(80, c.viewY(wz), 1e-9);
        for (int i = 0; i < 20; i++) {
            c.zoomAround(30, 80, 1.25);
        }
        assertEquals(MapCamera.MAX_ZOOM, c.zoom(), 1e-9, "never closer than 4");
        assertEquals(30, c.viewX(wx), 1e-6, "still under the cursor at the limit");
        for (int i = 0; i < 40; i++) {
            c.zoomAround(150, 10, 0.8);
        }
        assertEquals(MapCamera.MIN_ZOOM, c.zoom(), 1e-9, "never further than 0.25");
    }

    @Test
    void dragMovesTheWorldWithTheMouse() {
        MapCamera c = camera();
        c.zoom(2);
        double before = c.viewX(1300);
        double beforeY = c.viewY(-1400);
        c.pan(15, -8);
        assertEquals(before + 15, c.viewX(1300), 1e-9);
        assertEquals(beforeY - 8, c.viewY(-1400), 1e-9);
    }

    @Test
    void theCentreStaysOnTheMap() {
        MapCamera c = camera();
        c.bounds(new double[] {-2560, -6144, 2048, -1024});
        c.pan(-100000, 0);                       // dragged far to the left: the view goes east
        assertEquals(2048, c.centreX(), 1e-9);
        c.centre(-99999, -99999);
        assertEquals(-2560, c.centreX(), 1e-9);
        assertEquals(-6144, c.centreZ(), 1e-9);
        c.bounds(null);
        c.centre(5000, 5000);
        assertEquals(5000, c.centreX(), 1e-9, "no bounds: anywhere");
    }

    @Test
    void fitShowsEveryPlace() {
        MapCamera c = camera();
        c.fit(1100, -1500, 1300, -1400, 10, 2);
        assertEquals(0.8, c.zoom(), 1e-9, "the narrower side decides: 80 px for 100 blocks");
        assertEquals(1200, c.centreX(), 1e-9);
        assertEquals(-1450, c.centreZ(), 1e-9);
        assertTrue(c.viewX(1100) >= 10 && c.viewX(1300) <= 190);
        c.fit(1236, -1431, 1236, -1431, 10, 2);
        assertEquals(2, c.zoom(), 1e-9, "one point: no closer than the cap");
    }
}
