package io.github.anilken.wynnlabs.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map's tile table on a trimmed copy of Wynntils' {@code dataStaticMaps}: world points land on
 * the right tile and pixel (one pixel per block), a view picks the tiles it overlaps with the main
 * grid ahead of quest tiles, and the table on disk keeps only tiles whose picture is there.
 */
class MapTilesTest {

    /** Five real entries: three main tiles, a quest tile over the main grid, one clear of it. */
    static final String MAPS = """
            [
              {"name": "Main 1-1", "x1": -2560, "z1": -6144, "x2": -1025, "z2": -5121, "md5": "7a95ae2adc362a2de2ab87c9e0b42fe3"},
              {"name": "Main 1-2", "x1": -1024, "z1": -6144, "x2": 1023, "z2": -5121, "md5": "eeb9d40d384a9b9c484c68812e64dae7"},
              {"name": "Main 5-3", "x1": 1024, "z1": -2048, "x2": 2047, "z2": -1025, "md5": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"},
              {"name": "Realm of Light", "x1": -1536, "z1": -6656, "x2": -513, "z2": -5633, "md5": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa2"},
              {"name": "Kingdom of Sand 2", "x1": 2048, "z1": 512, "x2": 3071, "z2": 1023, "md5": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa3"},
              {"name": "Broken", "x1": 0, "z1": 0, "x2": 10, "z2": 10, "md5": "../../secret"},
              {"name": "No corners", "md5": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa4"}
            ]
            """;

    @Test
    void tileCornersLandOnTheirPixels() {
        MapTiles t = MapTiles.parse(MAPS);
        MapTiles.Tile main11 = t.at(-2560, -6144);
        assertEquals("Main 1-1", main11.name());
        assertEquals(1536, main11.width(), "1 px per block: 1536 blocks wide like its picture");
        assertEquals(1024, main11.height());
        assertPixel(0, 0, main11.pixel(-2560, -6144));
        assertEquals("Main 1-1", t.at(-1025, -5121).name());
        assertPixel(1535, 1023, main11.pixel(-1025, -5121));
        assertEquals("Main 1-2", t.at(-1024, -5121).name(), "the next block over is the next tile");
    }

    @Test
    void aPointInANeighbouringTileMatchesItsCorners() {
        MapTiles t = MapTiles.parse(MAPS);
        MapTiles.Tile tile = t.at(1236, -1431);
        assertEquals("Main 5-3", tile.name());
        // the pixel the corners give: the share of the way across the tile times its picture size
        double ex = (1236.0 - tile.x1()) / (tile.x2() + 1 - tile.x1()) * tile.width();
        double ez = (-1431.0 - tile.z1()) / (tile.z2() + 1 - tile.z1()) * tile.height();
        double[] p = tile.pixel(1236, -1431);
        assertTrue(Math.abs(p[0] - ex) <= 2 && Math.abs(p[1] - ez) <= 2, "within 2 blocks");
        assertPixel(212, 617, p);
    }

    @Test
    void theViewPutsAWorldPointWhereItsTilePixelIs() {
        // world → view at the default zoom (1 px per block), compared with where the tile's
        // corner is drawn plus the point's pixel on the tile: the same within 2 blocks
        MapTiles t = MapTiles.parse(MAPS);
        MapCamera cam = new MapCamera();
        cam.size(300, 180);
        cam.centre(1200, -1400);
        double[][] points = {{-2560, -6144}, {-1025, -5121}, {1236, -1431}, {0, -5500}};
        for (double[] w : points) {
            MapTiles.Tile tile = t.at(w[0], w[1]);
            cam.centre(w[0] + 37, w[1] - 21);
            double[] p = tile.pixel(w[0], w[1]);
            double tileX = cam.viewX(tile.x1());
            double tileY = cam.viewY(tile.z1());
            assertTrue(Math.abs(cam.viewX(w[0]) - (tileX + p[0])) < 2, "x of " + w[0]);
            assertTrue(Math.abs(cam.viewY(w[1]) - (tileY + p[1])) < 2, "z of " + w[1]);
        }
    }

    @Test
    void questTilesOverTheMainGridAreLeftOut() {
        MapTiles t = MapTiles.parse(MAPS);
        assertEquals(5, t.all().size(), "the bad entries are skipped");
        List<String> world = t.world().stream().map(MapTiles.Tile::name).toList();
        assertEquals(List.of("Main 1-1", "Main 1-2", "Main 5-3", "Kingdom of Sand 2"), world);
        assertEquals("Main 1-1", t.at(-1000 - 536, -6000).name(), "under Realm of Light the main map shows");
        assertEquals("Kingdom of Sand 2", t.at(2500, 700).name(), "a quest tile clear of the grid is kept");
        assertNull(t.at(9000, 9000));
    }

    @Test
    void aViewPicksTheTilesItOverlaps() {
        MapTiles t = MapTiles.parse(MAPS);
        List<MapTiles.Tile> out = new ArrayList<>();
        assertEquals(List.of("Main 5-3"), names(t.covering(1136, -1531, 1336, -1331, out)));
        assertEquals(List.of("Main 1-1", "Main 1-2"), names(t.covering(-1100, -5800, -900, -5700, out)),
                "across a seam: both, and not Realm of Light");
        assertEquals(List.of(), names(t.covering(5000, 5000, 5100, 5100, out)));
        assertEquals(List.of("Main 1-1"), names(t.covering(-1100, -5800, -1024, -5700, out)),
                "the end is exclusive: x = -1024 is not reached");
    }

    @Test
    void boundsAreTheMainGrid() {
        double[] b = MapTiles.parse(MAPS).bounds();
        assertEquals(-2560, b[0]);
        assertEquals(-6144, b[1]);
        assertEquals(2048, b[2]);
        assertEquals(-1024, b[3]);
        assertNull(MapTiles.none().bounds());
    }

    @Test
    void onDiskOnlyTilesWithAPictureCount(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("dataStaticMaps"), MAPS);
        Files.createDirectories(dir.resolve("maps"));
        Files.write(dir.resolve("maps/eeb9d40d384a9b9c484c68812e64dae7.png"), new byte[] {1});
        MapTiles t = MapTiles.load(dir);
        assertEquals(List.of("Main 1-2"), names(t.world()));
        assertEquals(dir.resolve("maps").resolve("eeb9d40d384a9b9c484c68812e64dae7.png"), t.image(t.world().get(0)));
        assertTrue(MapTiles.load(dir.resolve("missing")).isEmpty(), "no cache: no map");
        Files.writeString(dir.resolve("dataStaticMaps"), "{not json");
        assertTrue(MapTiles.load(dir).isEmpty(), "an unreadable list: no map");
    }

    private static List<String> names(List<MapTiles.Tile> tiles) {
        return tiles.stream().map(MapTiles.Tile::name).toList();
    }

    private static void assertPixel(double x, double z, double[] p) {
        assertEquals(x, p[0], 1e-9);
        assertEquals(z, p[1], 1e-9);
    }
}
