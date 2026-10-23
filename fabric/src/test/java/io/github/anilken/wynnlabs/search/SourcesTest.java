package io.github.anilken.wynnlabs.search;

import io.github.anilken.wynnlabs.build.BuildSources;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.market.Shopping;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An item's sources and their order, on the trimmed cache files (the spec's scenarios). */
class SourcesTest {

    private static final Prices.Price PRICE = new Prices.Price(4000, 4500.0, 4200.0, null, null, 0L, null, 12);
    /** Standing next to Burning Bog. */
    private static final double[] BOG = {-400, -5200};

    private static List<Source> of(String name, Sources.Owned owned, Prices.Price price, double[] pos) {
        ItemIndex idx = SearchFixtures.index();
        return Sources.of(idx.byName(name), idx.places(), owned, price, pos);
    }

    private static List<Source.Type> types(List<Source> rows) {
        return rows.stream().map(Source::type).toList();
    }

    @Test
    void ingredientDroppedByAMobWithTwoAreas() {
        List<Source> rows = of("Accursed Effigy", null, null, null);
        Source mob = rows.get(0);
        assertEquals(Source.Type.MOB, mob.type());
        assertEquals("Obelisk Of Inversion", mob.name());
        assertEquals(2, mob.areas().size());
        assertEquals("sword", mob.badge());
    }

    @Test
    void dungeonMerchantGear() {
        List<Source> rows = of("Abdomen", null, PRICE, null);
        // untradable: the price is ignored
        assertEquals(List.of(Source.Type.DUNGEON_MERCHANT), types(rows));
        assertEquals("Infested Pit", rows.get(0).name());
        assertEquals(Area.point(-157, 54, -1819), rows.get(0).main());
        assertEquals("shopping-cart", rows.get(0).badge());
    }

    @Test
    void unknownUntradableItemGetsOneUnknownRow() {
        List<Source> rows = of("Fearmonger", null, PRICE, BOG);
        assertEquals(List.of(Source.Type.UNKNOWN), types(rows));
        assertTrue(rows.get(0).areas().isEmpty());
        assertEquals("question-mark", rows.get(0).badge());
    }

    @Test
    void normalDropBandWithPlacesOfThatLevelNearestFirst() {
        List<Source> rows = of("Papyrus", null, null, BOG);
        Source band = rows.stream().filter(s -> s.type() == Source.Type.MOB_BAND).findFirst().orElseThrow();
        assertEquals(73, band.minLevel());
        assertEquals(81, band.maxLevel());
        assertTrue(band.areas().size() > 5, "every place of the band, no cap");
        assertEquals("Burning Bog", band.main().name());
        double last = -1;
        for (Area a : band.areas()) {
            assertTrue(a.level() >= 73 && a.level() <= 81, a.name() + " " + a.level());
            double d = Compass.distance(a, BOG);
            assertTrue(d >= last);
            last = d;
        }
        Source chest = rows.get(rows.size() - 1);
        assertEquals(Source.Type.LOOT_CHEST, chest.type());
        // the two chests by Burning Bog (79); the one by Abandoned Steelworks (110) is out of the band
        assertEquals(2, chest.areas().size());
        assertTrue(chest.areas().stream().allMatch(a -> a.level() == 79));
    }

    @Test
    void lootChestGearGetsTheChestRowOnly() {
        List<Source> rows = of("Aberrant's Amulet", null, null, null);
        assertEquals(List.of(Source.Type.LOOT_CHEST), types(rows));
        assertEquals(110, rows.get(0).main().level());
        assertEquals(3, rows.get(0).main().stars());
    }

    @Test
    void ownedThenMarketThenFarm() {
        Map<String, Integer> bank = new LinkedHashMap<>();
        bank.put("Page 3", 2);
        List<Source> rows = of("Papyrus", new Sources.Owned(1, bank), PRICE, BOG);
        assertEquals(List.of(Source.Type.BAG, Source.Type.BANK, Source.Type.MARKET, Source.Type.MOB_BAND, Source.Type.LOOT_CHEST),
                types(rows));
        assertEquals("Page 3", rows.get(1).name());
        assertEquals(2, rows.get(1).count());
        assertEquals(PRICE, rows.get(2).price());
        // bank only: the bank row first, the market second
        rows = of("Papyrus", new Sources.Owned(0, bank), PRICE, null);
        assertEquals(Source.Type.BANK, rows.get(0).type());
        assertEquals(Source.Type.MARKET, rows.get(1).type());
    }

    @Test
    void sourcesNamedOnlyByPlaceFindTheirPlace() {
        Source cave = of("Aerolia Boots", null, null, null).get(0);
        assertEquals(Source.Type.CAVE, cave.type());
        assertEquals("Mossy Grotto", cave.name());
        assertEquals(-449, cave.main().x());
        assertEquals("box", cave.badge());
        // a merchant the map data does not know keeps its name and has no place
        Source bear = of("Bear Body", null, null, null).get(0);
        assertEquals(Source.Type.MERCHANT, bear.type());
        assertNull(bear.main());
        assertEquals(List.of(Source.Type.UNAVAILABLE), types(of("Bear Head", null, null, null)));
    }

    @Test
    void typeListsGiveOneRowEachMerchantFirst() {
        List<Source> rows = of("Anthem", null, null, null);
        assertEquals(List.of(Source.Type.MERCHANT, Source.Type.EVENT), types(rows));
        assertEquals("Isles of Fiction", rows.get(0).name());
        assertEquals(-345, rows.get(0).main().x());
    }

    @Test
    void raidIsLastAndMaterialsGetGatheringSpots() {
        List<Source> rows = of("Abrasion", null, null, null);
        assertEquals(List.of(Source.Type.RAID), types(rows));
        assertEquals("trophy", rows.get(0).badge());
        Source g = of("Copper Ingot", null, null, null).get(0);
        assertEquals(Source.Type.GATHERING, g.type());
        assertEquals("Copper", g.name());
        assertTrue(g.areas().size() >= 1);
        for (int i = 0; i < g.areas().size(); i++) {
            for (int j = i + 1; j < g.areas().size(); j++) {
                Area a = g.areas().get(i);
                Area b = g.areas().get(j);
                assertTrue(Math.hypot(a.x() - b.x(), a.z() - b.z()) >= Sources.NODE_SPACING);
            }
        }
    }

    @Test
    void effortOrder() {
        assertTrue(SourceRank.effort(Source.Type.MOB) < SourceRank.effort(Source.Type.DUNGEON));
        assertTrue(SourceRank.effort(Source.Type.MERCHANT) < SourceRank.effort(Source.Type.DUNGEON));
        assertTrue(SourceRank.effort(Source.Type.DUNGEON) < SourceRank.effort(Source.Type.QUEST));
        assertTrue(SourceRank.effort(Source.Type.DUNGEON) < SourceRank.effort(Source.Type.CHALLENGE));
        for (Source.Type t : Source.Type.values()) {
            if (t != Source.Type.RAID && t.ordinal() < Source.Type.MOB_BAND.ordinal()) {
                assertTrue(SourceRank.effort(t) < SourceRank.effort(Source.Type.RAID), t.name());
            }
        }
    }

    @Test
    void badgesMatchTheHomePage() {
        for (Source.Type t : Source.Type.values()) {
            if (t.obtain == null) {
                continue;
            }
            String home = BuildSources.kindIcon(Shopping.kind(t.obtain));
            if (!home.equals("question-mark")) {
                assertEquals(home, SourceRank.badge(t), t.name());
            }
        }
        assertEquals("shopping-cart", SourceRank.badge(Source.Type.MERCHANT));
        assertEquals("skull", SourceRank.badge(Source.Type.DUNGEON));
        assertEquals("script", SourceRank.badge(Source.Type.QUEST));
        assertEquals("coin", SourceRank.badge(Source.Type.MARKET));
        assertEquals("building-bank", SourceRank.badge(Source.Type.BANK));
    }

    @Test
    void easiestForTheResultsTable() {
        ItemIndex idx = SearchFixtures.index();
        assertEquals(Source.Type.MARKET, SourceRank.easiest(idx.byName("Papyrus"), true));
        assertEquals(Source.Type.MOB_BAND, SourceRank.easiest(idx.byName("Papyrus"), false));
        assertEquals(Source.Type.DUNGEON_MERCHANT, SourceRank.easiest(idx.byName("Abdomen"), true));
        assertEquals(Source.Type.MERCHANT, SourceRank.easiest(idx.byName("Anthem"), false));
        assertEquals(Source.Type.UNKNOWN, SourceRank.easiest(idx.byName("Fearmonger"), false));
    }
}
