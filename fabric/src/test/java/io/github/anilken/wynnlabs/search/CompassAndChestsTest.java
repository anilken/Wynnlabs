package io.github.anilken.wynnlabs.search;

import io.github.anilken.wynnlabs.core.Saver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Distance and direction, the loot chest lists and the recent searches file. */
class CompassAndChestsTest {

    @Test
    void eightDirectionsNorthIsMinusZ() {
        double[] me = {0, 0};
        assertEquals("north", Compass.direction(Area.point(0, 0, -100), me));
        assertEquals("northeast", Compass.direction(Area.point(100, 0, -100), me));
        assertEquals("east", Compass.direction(Area.point(100, 0, 0), me));
        assertEquals("southeast", Compass.direction(Area.point(100, 0, 100), me));
        assertEquals("south", Compass.direction(Area.point(0, 0, 100), me));
        assertEquals("southwest", Compass.direction(Area.point(-100, 0, 100), me));
        assertEquals("west", Compass.direction(Area.point(-100, 0, 0), me));
        assertEquals("northwest", Compass.direction(Area.point(-100, 0, -100), me));
    }

    @Test
    void distanceToTheEdgeNeverBelowZero() {
        double[] me = {0, 0};
        assertEquals(100, Compass.distance(Area.point(0, 0, 100), me), 1e-9);
        assertEquals(70, Compass.distance(new Area(0, 0, 100, 30, -1, 0, null), me), 1e-9);
        assertEquals(0, Compass.distance(new Area(0, 0, 10, 50, -1, 0, null), me), 1e-9);
        assertTrue(Double.isNaN(Compass.distance(Area.point(0, 0, 100), null)));
        assertNull(Compass.direction(Area.point(0, 0, 100), null));
    }

    @Test
    void chestListsMergeByPosition() {
        List<Area> shipped = LootChests.parse("[{\"x\":1,\"y\":2,\"z\":3,\"stars\":0},{\"x\":5,\"y\":6,\"z\":7,\"stars\":2},{\"bad\":1}]");
        List<Area> mine = LootChests.parse("[{\"x\":1,\"y\":2,\"z\":3,\"stars\":3},{\"x\":5,\"y\":6,\"z\":7,\"stars\":1},{\"x\":9,\"y\":9,\"z\":9,\"stars\":9}]");
        List<Area> all = LootChests.merge(shipped, mine);
        assertEquals(3, all.size());
        assertEquals(3, all.get(0).stars());          // the player's tier fills a tierless shipped chest
        assertEquals(2, all.get(1).stars());          // a shipped tier stays
        assertEquals(4, all.get(2).stars());          // clamped to 1-4
    }

    @Test
    void shippedChestsLoadAndGetLevels() {
        List<Area> chests = LootChests.load(null);
        assertTrue(chests.size() > 1000, "shipped chests: " + chests.size());
        assertTrue(chests.stream().allMatch(c -> c.stars() >= 0 && c.stars() <= 4 && c.level() == -1));
        // a chest far from every place gets no level and is left out
        List<Area> leveled = Places.levelChests(SearchFixtures.chests(), List.of(new Area(-411, 0, -5205, 0, 79, 0, "Burning Bog")));
        assertEquals(2, leveled.size());
    }

    @Test
    void playerChestsAreAdded(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("chests.json");
        Files.writeString(f, "[{\"x\":123456,\"y\":1,\"z\":-7,\"stars\":2}]");
        int shipped = LootChests.load(null).size();
        assertEquals(shipped + 1, LootChests.load(f).size());
    }

    @Test
    void recentSearchesKeepTenNewestFirst(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("search-recent.json");
        RecentSearches r = new RecentSearches(f);
        for (int i = 0; i < 12; i++) {
            r.add("Item " + i);
        }
        r.add("Item 5");
        assertEquals(RecentSearches.MAX, r.names().size());
        assertEquals("Item 5", r.names().get(0));
        assertEquals("Item 11", r.names().get(1));
        Saver.flush();
        assertEquals(r.names(), new RecentSearches(f).names());
    }
}
