package io.github.anilken.wynnlabs.search;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The search marks on Wynntils' map: how big an area draws at a zoom, and which rows put marks there. */
class MapMarkTest {

    @Test
    void radiusFollowsTheMapsPixelsPerBlock() {
        assertEquals(40, MapMark.radiusPx(40, 1f), "one pixel per block");
        assertEquals(80, MapMark.radiusPx(40, 2f), "zoomed in");
        assertEquals(10, MapMark.radiusPx(40, 0.25f), "zoomed out");
        assertEquals(MapMark.MIN_PX, MapMark.radiusPx(40, 0.05f), "never smaller than a few pixels");
        assertEquals(MapMark.MAX_PX, MapMark.radiusPx(5000, 8f), "never bigger than the map");
        assertEquals(0, MapMark.radiusPx(0, 4f), "a point has no circle");
    }

    @Test
    void sizeIsTheDiameterOrTheDot() {
        MapMark area = new MapMark(0, 60, 0, 30, "Mobs", "sword", true);
        MapMark point = new MapMark(0, 60, 0, 0, "Merchant", "shopping-cart", false);
        assertEquals(120, MapMark.sizePx(area, 2f));
        assertEquals(MapMark.DOT_PX + 4, MapMark.sizePx(point, 2f));
        assertTrue(point.point());
        assertFalse(area.point());
    }

    @Test
    void onlyThePickedRowUnlessAllAreAsked() {
        Source mob = new Source(Source.Type.MOB, "Obelisk Of Inversion",
                List.of(new Area(10, 60, 20, 25, 70, 0, null), new Area(-300, 60, 40, 15, 70, 0, "Burning Bog")),
                1, "sword", 0, null, 0, 0, null);
        Source shop = new Source(Source.Type.MERCHANT, "Detlas", List.of(Area.point(400, 70, -1500)), 1, "shopping-cart", 0, null, 0, 0, null);
        Source market = new Source(Source.Type.MARKET, null, List.of(), 0, "coin", 0, null, 0, 0, null);
        List<Source> rows = List.of(market, mob, shop);

        List<MapMark> picked = MapMark.of(rows, 1, false, s -> s.type().name());
        assertEquals(2, picked.size());
        assertEquals("MOB · Burning Bog", picked.get(1).label());
        assertTrue(picked.stream().allMatch(MapMark::focus));

        List<MapMark> all = MapMark.of(rows, 1, true, s -> s.type().name());
        assertEquals(3, all.size(), "the market row has no place");
        assertFalse(all.get(2).focus());
        assertTrue(all.get(2).point());
    }

    @Test
    void aRowPutsEveryPlace() {
        List<Area> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(Area.point(i * 100, 60, 0));
        }
        Source chests = new Source(Source.Type.LOOT_CHEST, null, many, 7, "box", 0, null, 70, 78, null);
        assertEquals(chests.areas().size(), MapMark.of(List.of(chests), 0, false, s -> "Chests").size());
    }
}
