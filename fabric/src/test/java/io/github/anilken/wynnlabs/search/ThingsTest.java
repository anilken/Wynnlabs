package io.github.anilken.wynnlabs.search;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.github.anilken.wynnlabs.model.Klass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The world's things in the search: each kind read from trimmed cache copies, the shipped guides
 * and a trimmed wiki list; ranking (data mobs before wiki-only mobs) and the cross-links.
 */
class ThingsTest {

    private static final SearchQuery.Facts FACTS = new SearchQuery.Facts(Klass.MAGE, n -> false, n -> null);

    static String wiki(String name) {
        try {
            return Files.readString(Path.of(ThingsTest.class.getResource("/search/wiki/" + name + ".txt").toURI()),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The wiki's answer to one api.php query, from the trimmed pages. */
    static String api(String query) {
        JsonObject pages = new JsonObject();
        if (query.contains("generator=allpages")) {
            pages.add("1", page("Lists of mobs/Mesa", wiki("Lists of mobs-Mesa")));
            pages.add("2", page("Lists of mobs/Infested Pit", wiki("Lists of mobs-Infested Pit")));
        } else if (query.contains("titles=Reggie")) {
            pages.add("3", page("Reggie", wiki("Reggie")));
        }
        JsonObject q = new JsonObject();
        q.add("pages", pages);
        JsonObject r = new JsonObject();
        r.add("query", q);
        return r.toString();
    }

    private static JsonObject page(String title, String text) {
        JsonObject main = new JsonObject();
        main.addProperty("*", text);
        JsonObject slots = new JsonObject();
        slots.add("main", main);
        JsonObject rev = new JsonObject();
        rev.add("slots", slots);
        JsonArray revs = new JsonArray();
        revs.add(rev);
        JsonObject p = new JsonObject();
        p.addProperty("title", title);
        p.add("revisions", revs);
        return p;
    }

    private static ItemIndex withWiki;

    static synchronized ItemIndex withWiki() {
        if (withWiki == null) {
            withWiki = ItemIndex.read(SearchFixtures.cacheDir(), SearchFixtures.chests(), MobWiki.fetch(ThingsTest::api));
        }
        return withWiki;
    }

    // ---- mobs ----

    @Test
    void ingredientMobsWithSpawnAreasAndDrops() {
        Entry cam = SearchFixtures.index().byKey("mob:T-5lusive Surveillance Camera");
        assertNotNull(cam);
        assertEquals(Kind.MOB, cam.kind());
        assertEquals(MobThings.DROPS, cam.type());
        assertEquals(new Area(-1568, 150, -2582, 250, -1, 0, null), cam.places().get(0));
        assertEquals(List.of("7-Yottabyte Storage Component"), cam.links(Entry.ITEM).stream().map(Entry.Origin::name).toList());
        assertEquals(0, cam.level(), "the data gives no level");
    }

    @Test
    void wikiListRowsAndTranscludedPages() {
        List<MobWiki.Mob> rows = MobWiki.parse("Mesa", wiki("Lists of mobs-Mesa"));
        assertEquals(List.of("Dusty Skeleton", "T-5lusive Sentry", "T-5lusive Surveillance Camera"),
                rows.stream().map(MobWiki.Mob::name).toList(), "the four-cell ambience row is left out");
        MobWiki.Mob dusty = rows.get(0);
        assertEquals(38, dusty.level());
        assertEquals(List.of("Bone Shards"), dusty.drops(), "emeralds are not an item");
        assertEquals("Mesa, Mossy Grotto", dusty.where());
        assertEquals(41, rows.get(1).level());
        assertEquals("", rows.get(1).where());
        assertEquals(List.of("Reggie"), MobWiki.transclusions(wiki("Lists of mobs-Mesa")));
        List<MobWiki.Mob> reggie = MobWiki.parse("Mesa", MobWiki.onlyInclude(wiki("Reggie")));
        assertEquals(1, reggie.size());
        assertEquals(new MobWiki.Mob("Reggie", 40, "Mesa", "Mesa", List.of("Gunpowder", "Lucky Rabbit's Foot")), reggie.get(0));
    }

    @Test
    void wikiFetchFollowsTheListsThenTheMobPagesAndKeepsAFile(@TempDir Path dir) throws Exception {
        List<String> asked = new ArrayList<>();
        List<MobWiki.Mob> mobs = MobWiki.fetch(q -> {
            asked.add(q);
            return api(q);
        });
        assertEquals(2, asked.size(), "one query for the lists, one for the mob pages they pull in");
        assertTrue(mobs.stream().anyMatch(m -> m.name().equals("Reggie") && m.level() == 40));
        assertTrue(mobs.stream().anyMatch(m -> m.name().equals("Pit Crawler") && m.region().equals("Infested Pit")));
        assertTrue(MobWiki.cached(dir).isEmpty());
        assertFalse(MobWiki.fresh(dir));
        Files.writeString(dir.resolve(MobWiki.FILE), new com.google.gson.Gson().toJson(mobs), StandardCharsets.UTF_8);
        assertEquals(mobs, MobWiki.cached(dir));
        assertTrue(MobWiki.fresh(dir));
    }

    @Test
    void wikiRowsJoinTheDataMobAndAddWikiOnlyMobs() {
        ItemIndex idx = withWiki();
        Entry cam = idx.byKey("mob:T-5lusive Surveillance Camera");
        assertEquals(MobThings.DROPS, cam.type(), "still the data's mob");
        assertEquals(44, cam.level(), "the wiki adds the level");
        assertEquals("Mesa", cam.links(Entry.WHERE).get(0).name());
        Entry reggie = idx.byKey("mob:Reggie");
        assertEquals(MobThings.WIKI, reggie.type());
        assertEquals(40, reggie.level());
        assertTrue(reggie.places().isEmpty(), "the wiki gives no areas");
        assertEquals(List.of("Gunpowder", "Lucky Rabbit's Foot"), reggie.links(Entry.ITEM).stream().map(Entry.Origin::name).toList());
    }

    @Test
    void dataMobsRankAboveWikiOnlyMobs() {
        SearchQuery.Result r = new SearchQuery("t-5lusive", SearchQuery.Sort.EASIEST, java.util.Set.of(Kind.MOB), 0,
                Integer.MAX_VALUE, false, false, false).run(withWiki(), FACTS);
        assertEquals(List.of("T-5lusive Surveillance Camera", "T-5lusive Sentry"), r.hits().stream().map(h -> h.entry().name()).toList());
        assertTrue(Things.ease(withWiki().byKey("mob:T-5lusive Surveillance Camera")) < Things.ease(withWiki().byKey("mob:Reggie")));
    }

    // ---- places, content, services, resources ----

    @Test
    void placesHaveLevelAndPlace() {
        Entry cinfras = SearchFixtures.index().byKey("place:Cinfras");
        assertEquals("city", cinfras.type());
        assertEquals(new Area(-455, 0, -4928, 0, cinfras.level(), 0, "Cinfras"), cinfras.places().get(0));
        assertEquals("town", SearchFixtures.index().byKey("place:Gert Camp").type());
    }

    @Test
    void contentWithItsTypeLevelAndItems() {
        ItemIndex idx = withWiki();
        Entry pit = idx.byKey("content:Infested Pit");
        assertEquals("dungeon", pit.type());
        assertTrue(pit.level() > 0);
        assertEquals(List.of("Abdomen", "Accursed Effigy"), pit.links(Entry.ITEM).stream().map(Entry.Origin::name).toList(),
                "the dungeon merchant's item, then what the wiki's mobs there drop");
        assertEquals("corrupted-dungeon", idx.byKey("content:Corrupted Infested Pit").type());
        assertEquals(List.of("Aerolia Boots"), idx.byKey("content:Mossy Grotto").links(Entry.ITEM).stream().map(Entry.Origin::name).toList());
        assertEquals("dungeon", idx.byKey("content:The Forgery").type(), "only the combat list has it");
        assertNull(idx.byKey("content:Cave"), "the list's unnamed caves are left out");
        assertEquals(Source.Type.RAID, Things.type(idx.byKey("content:Nest of the Grootslangs")));
        assertEquals(Source.Type.MOB_BAND, Things.type(idx.byKey("content:Witness Church")));
    }

    @Test
    void oneEntryPerServiceTypeNearestFirst() {
        ItemIndex idx = SearchFixtures.index();
        Entry smith = idx.byKey("service:Blacksmith");
        assertEquals(4, smith.places().size());
        assertTrue(smith.places().stream().allMatch(a -> a.name().equals("Cinfras") || a.name().equals("Detlas")));
        assertNotNull(idx.byKey("service:Woodworking station"));
        assertNotNull(idx.byKey("service:Emerald merchant"));
        assertEquals("Fast travel", WorldThings.serviceName("fast-travel"));
        assertEquals("Dungeon scroll merchant", WorldThings.serviceName("merchant:dungeon-scroll"));
        List<Source> rows = Things.rows(smith, new double[] {480, -1590});
        assertEquals(4, rows.size(), "one row per place");
        assertEquals("Detlas", rows.get(0).name(), "the nearest first");
        assertEquals("shopping-cart", rows.get(0).badge());
    }

    @Test
    void gatheringResourceWithNodesAndMaterials() {
        Entry copper = SearchFixtures.index().byKey("resource:Copper");
        assertEquals(12, copper.places().size());
        assertEquals(List.of("Copper Gem", "Copper Ingot"), copper.links(Entry.ITEM).stream().map(Entry.Origin::name).sorted().toList());
        assertEquals(Source.Type.GATHERING, Things.type(copper));
        assertEquals(1, Things.rows(copper, null).size());
    }

    // ---- quests, NPCs, discoveries ----

    @Test
    void questsWithTheirStartNpc() {
        Entry farmer = SearchFixtures.index().byKey("quest:A Confused Farmer");
        assertNotNull(farmer);
        assertEquals("Bylvis", farmer.links(Entry.NPC).get(0).name());
        assertEquals("Aledar", StoryThings.npc("Talk to Aledar in Lutho at [977, 75, -732]."));
        assertEquals("Almuj Bank Guard", StoryThings.npc("Talk to the Almuj Bank Guard at [976, 83, -1963]"));
        assertNull(StoryThings.npc("Return to Aldorei Town by either using the Juggler"));
        assertNull(StoryThings.npc("Investigate Drale's stable."));
    }

    @Test
    void npcsWithPlaceAndQuest() {
        Entry aledar = SearchFixtures.index().byKey("npc:Aledar");
        assertNotNull(aledar);
        assertEquals(new Area(977, 75, -732, 0, -1, 0, null), aledar.places().get(0));
        assertFalse(aledar.links(Entry.QUEST).isEmpty());
        assertNull(SearchFixtures.index().byKey("npc:Cinfras"), "a town a stage names is no NPC");
    }

    @Test
    void discoveriesWithLevelPlaceAndId() {
        Entry frog = SearchFixtures.index().byKey("discovery:A Croaking Cryptid");
        assertEquals(104, frog.level());
        assertEquals(new Area(-2206, 11, -1435, 0, -1, 0, null), frog.places().get(0));
        assertEquals("Auburn Forest", frog.links(Entry.WHERE).get(0).name());
        assertEquals("A Croaking Cryptid", frog.links(Entry.ID).get(0).name());
    }

    // ---- searching and links ----

    @Test
    void searchFindsEachKindByName() {
        ItemIndex idx = withWiki();
        Map<String, String> want = Map.of("reggie", "mob:Reggie", "cinfras", "place:Cinfras", "blacksmith", "service:Blacksmith",
                "infested pit", "content:Infested Pit", "copper", "resource:Copper", "a confused farmer", "quest:A Confused Farmer",
                "aledar", "npc:Aledar", "croaking cryptid", "discovery:A Croaking Cryptid");
        want.forEach((q, key) -> {
            SearchQuery.Result r = SearchQuery.of(q).run(idx, FACTS);
            assertEquals(key, r.hits().get(0).entry().key(), q);
        });
        // a typo still finds a thing
        assertEquals("place:Cinfras", SearchQuery.of("cinfars").run(idx, FACTS).hits().get(0).entry().key());
        // things are never owned nor on the market, and the kind chips count them
        SearchQuery.Result r = SearchQuery.of("t-5lusive").run(idx, FACTS);
        assertEquals(2, r.counts().get(Kind.MOB));
        assertTrue(r.counts().get(Kind.INGREDIENT) >= 1, "the camera's ingredient is found through its mob");
    }

    @Test
    void itemRowsLinkToTheirThingsAndDropsLinkBack() {
        ItemIndex idx = withWiki();
        assertEquals("place:Cinfras", Things.of(idx, Source.Type.MERCHANT, "Cinfras").key());
        assertEquals("content:Infested Pit", Things.of(idx, Source.Type.DUNGEON_MERCHANT, "Infested Pit").key());
        assertEquals("content:Mossy Grotto", Things.of(idx, Source.Type.CAVE, "Mossy Grotto").key());
        assertEquals("mob:T-5lusive Surveillance Camera", Things.of(idx, Source.Type.MOB, "T-5lusive Surveillance Camera").key());
        assertEquals("resource:Copper", Things.of(idx, Source.Type.GATHERING, "Copper").key());
        assertNull(Things.of(idx, Source.Type.MERCHANT, "The Bear Zoo"));
        assertNull(Things.of(idx, Source.Type.MOB_BAND, null));
        // the item card's rows name these things
        Entry abdomen = idx.byName("Abdomen");
        Source row = Sources.of(abdomen, idx.places(), null, null, null).stream()
                .filter(s -> s.type() == Source.Type.DUNGEON_MERCHANT).findFirst().orElseThrow();
        assertEquals("content:Infested Pit", Things.of(idx, row.type(), row.name()).key());
        // a mob's drops open the items' cards
        for (Entry.Origin d : idx.byKey("mob:T-5lusive Surveillance Camera").links(Entry.ITEM)) {
            assertNotNull(idx.byKey(d.name()), d.name());
        }
        // a thing and an item of the same name stay apart
        assertEquals(Kind.INGREDIENT, idx.byKey("Accursed Effigy").kind());
    }
}
