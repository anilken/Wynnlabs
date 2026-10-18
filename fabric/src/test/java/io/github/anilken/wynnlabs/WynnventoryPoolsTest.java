package io.github.anilken.wynnlabs;

import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.WynnventoryPools.Response;
import io.github.anilken.wynnlabs.WynnventoryPools.Status;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.PoolItem;
import io.github.anilken.wynnlabs.model.PoolRegion;
import io.github.anilken.wynnlabs.model.Tier;
import io.github.anilken.wynnlabs.model.WeeklyPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** This week's pools from Wynnventory: parsing, weeks, the gate, the hourly limit, the cache and the key kept private. */
class WynnventoryPoolsTest {

    /** Sunday 2026-10-18 12:00 UTC: ISO week 42, two days after the Friday reset. */
    static final long OCT18 = Instant.parse("2026-10-18T12:00:00Z").toEpochMilli();
    private static final long MIN = 60_000L;
    private static final String KEY = "test-key";

    @TempDir
    Path dir;

    /** A fake Wynnventory: answers by URL and counts the calls (no network). */
    private static final class Fake implements WynnventoryPools.Fetcher {
        final List<String> calls = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        Function<String, Response> answer = url -> new Response(200,
                fixture(url.equals(WynnventoryPools.RAID_URL) ? "raidpool-current.json" : "lootpool-current.json"));

        @Override
        public Response get(String url, String key) throws IOException {
            calls.add(url);
            keys.add(key);
            Response r = answer.apply(url);
            if (r == null) {
                throw new IOException("timed out");
            }
            return r;
        }

        long count(String url) {
            return calls.stream().filter(url::equals).count();
        }
    }

    static String fixture(String name) {
        try {
            return Files.readString(Fixtures.root().resolve("wynnventory").resolve(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setUp() {
        WynnventoryPools.reset();
        Prices.keyInvalid = false;
        JsonObject s = new JsonObject();
        s.addProperty("langOverride", 1);
        Settings.use(s);
    }

    @AfterEach
    void tearDown() {
        Saver.flush();
        WynnventoryPools.reset();
        Prices.keyInvalid = false;
        Prices.problem = null;
        Settings.use(new JsonObject());
    }

    private Fake open() {
        Fake f = new Fake();
        WynnventoryPools.use(dir, f);
        WynnventoryPools.testGate(null, KEY);
        return f;
    }

    @Test
    void bothFixturesParse() {
        WeeklyPool raid = WynnventoryPools.parse(fixture("raidpool-current.json"), OCT18);
        assertEquals(2026, raid.year());
        assertEquals(42, raid.week());
        assertEquals(OCT18, raid.fetchedAt());
        assertEquals(5, raid.regions().size());
        PoolRegion tcc = raid.regions().get(0);
        assertEquals("The Canyon Colossus", tcc.name());
        assertEquals("RAID", tcc.type());
        assertEquals(Instant.parse("2026-10-17T22:39:33Z").toEpochMilli(), tcc.seenAt(), "RFC 1123 timestamp");
        PoolItem first = tcc.items().get(0);
        assertEquals("Aspect of Damnation", first.name());
        assertTrue(first.aspect());
        assertEquals(Klass.SHAMAN, first.aspectClass());
        assertEquals(Tier.LEGENDARY, first.rarity());
        for (PoolRegion r : raid.regions()) {
            long aspects = r.items().stream().filter(PoolItem::aspect).count();
            assertTrue(aspects >= 18 && aspects <= 20, r.name() + " holds 18-20 aspects");
            assertTrue(r.items().stream().anyMatch(i -> !i.aspect()), "other item types kept");
        }
        assertEquals(Instant.parse("2026-10-18T08:12:05Z").toEpochMilli(), raid.newestSeenAt());

        WeeklyPool loot = WynnventoryPools.parse(fixture("lootpool-current.json"), OCT18);
        assertEquals(42, loot.week());
        assertEquals(7, loot.regions().size());
        assertEquals(15, loot.regions().stream().flatMap(r -> r.items().stream()).filter(PoolItem::mythicGear).count(),
                "the shiny Halcyon and the shiny Divzer included");
    }

    // ---- group 8: icons, shiny and tier ----

    private static PoolItem find(WeeklyPool p, String region, String name, boolean shiny) {
        return p.regions().stream().filter(r -> r.name().equals(region)).flatMap(r -> r.items().stream())
                .filter(i -> i.name().equals(name) && i.shiny() == shiny).findFirst().orElseThrow();
    }

    @Test
    void iconsShinyAndTierFromBothFixtures() {
        WynnventoryPools.shinyStats(WynnventoryPools.parseShinyStats(wynntilsFixture("shiny-stats.json")));
        WeeklyPool raid = WynnventoryPools.parse(fixture("raidpool-current.json"), OCT18);
        PoolItem damnation = find(raid, "The Canyon Colossus", "Aspect of Damnation", false);
        assertEquals("aspect_attribute", damnation.iconFormat());
        assertEquals("abilityTree.aspectShaman", damnation.iconValue());
        assertTrue(damnation.hasIcon());
        assertFalse(damnation.shiny());
        assertEquals(0, damnation.tier());
        PoolItem powder = find(raid, "The Canyon Colossus", "Fire Powder III", false);
        assertEquals("powder.FireSmall", powder.iconValue());
        assertEquals(3, powder.tier());
        assertEquals("tome.mana", find(raid, "The Canyon Colossus", "Pegasus' Tome of Mysticism", false).iconValue());
        assertEquals("skin", find(raid, "Nest of the Grootslangs", "Gaia's Cradle", false).iconFormat());
        assertTrue(raid.regions().stream().flatMap(r -> r.items().stream()).noneMatch(PoolItem::shiny), "no shiny in a raid");

        WeeklyPool loot = WynnventoryPools.parse(fixture("lootpool-current.json"), OCT18);
        PoolItem shinyHalcyon = find(loot, "Canyon of the Lost", "Halcyon", true);
        assertEquals("Raids Won", shinyHalcyon.shinyStat(), "an object's displayName");
        assertEquals("wand.multi3", shinyHalcyon.iconValue());
        PoolItem plainHalcyon = find(loot, "Canyon of the Lost", "Halcyon", false);
        assertEquals("", plainHalcyon.shinyStat());
        assertEquals("Raids Won", find(loot, "Silent Expanse", "Divzer", true).shinyStat(), "a string key, looked up");
        PoolItem uth = find(loot, "Sky Islands", "Uth Rune", false);
        assertFalse(uth.hasIcon(), "an item without an icon");
        assertEquals("", uth.iconFormat());

        WynnventoryPools.shinyStats(null);
        WeeklyPool unlabelled = WynnventoryPools.parse(fixture("lootpool-current.json"), OCT18);
        assertEquals("raidsWon", find(unlabelled, "Silent Expanse", "Divzer", true).shinyStat(), "an unknown key kept as written");
    }

    @Test
    void shinyShapesAndIconValueObjects() {
        WynnventoryPools.shinyStats(java.util.Map.of("mobsKilled", "Mobs Killed"));
        String json = "{\"year\":2026,\"week\":42,\"regions\":[{\"region\":\"X\",\"items\":["
                + "{\"name\":\"A\",\"shiny\":true,\"shiny_stat\":[1,2]},"
                + "{\"name\":\"B\",\"shiny\":true,\"shinyStat\":{\"key\":\"mobsKilled\"}},"
                + "{\"name\":\"C\",\"shiny\":true,\"shinyStat\":{\"statType\":{\"displayName\":\"Wars Won\"}}},"
                + "{\"name\":\"D\",\"shiny\":\"yes\",\"shinyStat\":7,\"tier\":null},"
                + "{\"name\":\"E\",\"icon\":{\"format\":\"attribute\",\"value\":{\"id\":\"minecraft:potion\",\"name\":\"tome.mana\"}},\"tier\":4}"
                + "]}]}";
        java.util.Set<String> fields = new java.util.TreeSet<>();
        List<PoolItem> items = WynnventoryPools.parse(json, 5, fields).regions().get(0).items();
        assertTrue(items.get(0).shiny(), "an unknown shape keeps the shiny flag");
        assertEquals("", items.get(0).shinyStat(), "with an empty label");
        assertEquals("Mobs Killed", items.get(1).shinyStat());
        assertEquals("Wars Won", items.get(2).shinyStat());
        assertFalse(items.get(3).shiny(), "not a boolean");
        assertEquals("", items.get(3).shinyStat());
        assertEquals(0, items.get(3).tier(), "null tier");
        assertEquals("tome.mana", items.get(4).iconValue(), "an object value gives its name");
        assertEquals(4, items.get(4).tier());
        assertEquals(java.util.Set.of("shiny", "shinyStat", "shinyStat.key", "shinyStat.statType", "shiny_stat"), fields);
    }

    @Test
    void aFormat1CacheIsReadAndAskedOnce() throws Exception {
        long fetched = OCT18 - 10 * MIN;
        String v1 = "{\"format\":1,"
                + "\"raid\":{\"year\":2026,\"week\":42,\"fetchedAt\":" + fetched + ",\"attemptAt\":" + fetched + ",\"regions\":["
                + "{\"region\":\"The Canyon Colossus\",\"type\":\"RAID\",\"seenAt\":1,\"items\":["
                + "{\"name\":\"Aspect of Damnation\",\"itemType\":\"AspectItem\",\"rarity\":\"LEGENDARY\",\"subtype\":\"ShamanAspect\",\"amount\":1}]}]},"
                + "\"loot\":{\"year\":2026,\"week\":42,\"fetchedAt\":" + fetched + ",\"attemptAt\":" + fetched + ",\"regions\":["
                + "{\"region\":\"Corkus\",\"type\":\"LOOTRUN_CAMP\",\"seenAt\":1,\"items\":["
                + "{\"name\":\"Boreal\",\"itemType\":\"GearItem\",\"rarity\":\"MYTHIC\",\"subtype\":\"Weapon\",\"amount\":1}]}]}}";
        Files.writeString(dir.resolve("wynnventory-pools.json"), v1, StandardCharsets.UTF_8);
        Fake f = open();
        f.answer = url -> new Response(500, "");
        WynnventoryPools.tick(OCT18);
        assertEquals(1, f.count(WynnventoryPools.RAID_URL), "within the hour, but an old format: asked once");
        assertEquals(1, f.count(WynnventoryPools.LOOT_URL));
        PoolItem cached = WynnventoryPools.raids().regions().get(0).items().get(0);
        assertEquals("Aspect of Damnation", cached.name(), "the old data is still shown");
        assertFalse(cached.hasIcon());
        WynnventoryPools.tick(OCT18 + 20 * MIN);
        assertEquals(2, f.calls.size(), "only once");
        Saver.flush();

        Files.writeString(dir.resolve("wynnventory-pools.json"), v1, StandardCharsets.UTF_8);
        Fake again = open();                    // a successful answer brings the icons
        WynnventoryPools.tick(OCT18);
        assertEquals(2, again.calls.size());
        assertTrue(find(WynnventoryPools.loot(), "Canyon of the Lost", "Halcyon", true).hasIcon());
        WynnventoryPools.tick(OCT18 + 30 * MIN);
        assertEquals(2, again.calls.size());
        Saver.flush();
        String text = Files.readString(dir.resolve("wynnventory-pools.json"), StandardCharsets.UTF_8);
        assertTrue(text.contains("\"format\": 2"), "written as format 2");
    }

    static String wynntilsFixture(String name) {
        try {
            return Files.readString(Fixtures.root().resolve("wynntils").resolve(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void missingFieldsGetDefaultsAndGarbageGivesNone() {
        WeeklyPool p = WynnventoryPools.parse(
                "{\"year\":2026,\"week\":42,\"regions\":[{\"region\":\"X\",\"timestamp\":\"yesterday\",\"items\":[{\"name\":\"A\"},{\"amount\":2}]}]}",
                5);
        PoolRegion r = p.regions().get(0);
        assertEquals(-1, r.seenAt(), "unreadable timestamp");
        assertEquals("", r.type());
        assertEquals(List.of(new PoolItem("A", "", Tier.NORMAL, "", 1)), r.items(), "an item without a name is left out");
        for (String bad : new String[] {null, "", "not json", "[]", "{}", "{\"year\":\"x\"}", "{\"year\":2026,\"week\":42}",
                "{\"year\":2026,\"week\":42,\"regions\":{}}"}) {
            assertSame(WeeklyPool.NONE, WynnventoryPools.parse(bad, 5), String.valueOf(bad));
        }
    }

    @Test
    void weeksAgainstTheReset() {
        assertArrayEquals(new int[] {2026, 42}, WynnventoryPools.resetWeek(OCT18));
        assertFalse(WynnventoryPools.current(week(41), OCT18), "an earlier week is stale");
        assertTrue(WynnventoryPools.current(week(42), OCT18));
        assertTrue(WynnventoryPools.current(week(43), OCT18), "a later week still counts as current");
        assertFalse(WynnventoryPools.current(WeeklyPool.NONE, OCT18));
        assertFalse(WynnventoryPools.current(new WeeklyPool(2025, 52, 0, week(42).regions()), OCT18));
        assertTrue(WynnventoryPools.current(new WeeklyPool(2027, 1, 0, week(42).regions()), OCT18));
    }

    @Test
    void theWeekChangesAtTheFridayReset() {
        long before = Instant.parse("2026-10-23T17:59:59Z").toEpochMilli();
        long at = Instant.parse("2026-10-23T18:00:00Z").toEpochMilli();
        assertArrayEquals(new int[] {2026, 42}, WynnventoryPools.resetWeek(before));
        assertArrayEquals(new int[] {2026, 43}, WynnventoryPools.resetWeek(at));
        assertTrue(WynnventoryPools.current(week(42), before));
        assertFalse(WynnventoryPools.current(week(42), at));
        // the reset of Friday 2026-01-02 belongs to ISO week 1 of 2026
        assertArrayEquals(new int[] {2026, 1},
                WynnventoryPools.resetWeek(Instant.parse("2026-01-03T00:00:00Z").toEpochMilli()));
    }

    @Test
    void withTheGateClosedNothingIsFetched() {
        Fake f = new Fake();
        WynnventoryPools.use(dir, f);
        for (Status why : new Status[] {Status.OFF, Status.NO_MOD, Status.NO_KEY, Status.KEY_REFUSED}) {
            WynnventoryPools.testGate(why, KEY);
            WynnventoryPools.tick(OCT18);
            WynnventoryPools.tick(OCT18 + 2 * WynnventoryPools.REFRESH_MS);
            assertEquals(why, WynnventoryPools.status());
            assertSame(WeeklyPool.NONE, WynnventoryPools.raids());
            assertSame(WeeklyPool.NONE, WynnventoryPools.loot());
        }
        assertTrue(f.calls.isEmpty());
    }

    @Test
    void twoTicksWithinAnHourFetchEachEndpointOnce() {
        Fake f = open();
        assertSame(WeeklyPool.NONE, WynnventoryPools.raids(), "nothing before the first tick");
        WynnventoryPools.tick(OCT18);
        WynnventoryPools.tick(OCT18 + 30 * MIN);
        assertEquals(1, f.count(WynnventoryPools.RAID_URL));
        assertEquals(1, f.count(WynnventoryPools.LOOT_URL));
        assertEquals(List.of(KEY, KEY), f.keys, "the key goes with the request");
        assertEquals(42, WynnventoryPools.raids().week());
        assertEquals(7, WynnventoryPools.loot().regions().size());
        assertEquals(Status.OK, WynnventoryPools.status());
        WynnventoryPools.tick(OCT18 + WynnventoryPools.REFRESH_MS);
        assertEquals(2, f.count(WynnventoryPools.RAID_URL), "an hour later: asked again");
        assertEquals(2, f.count(WynnventoryPools.LOOT_URL));
    }

    @Test
    void aRestartWithinTheHourFetchesNothing() throws Exception {
        Fake f = open();
        WynnventoryPools.tick(OCT18);
        assertEquals(2, f.calls.size());
        Saver.flush();
        assertTrue(Files.exists(dir.resolve("wynnventory-pools.json")));

        Fake again = open();                    // the game restarts: memory gone, the cache file stays
        WynnventoryPools.tick(OCT18 + 20 * MIN);
        assertTrue(again.calls.isEmpty(), "within the hour of the last request");
        assertEquals(42, WynnventoryPools.raids().week(), "this week's pools come from the cache");
        assertEquals(5, WynnventoryPools.raids().regions().size());
        assertEquals(WynnventoryPools.parse(fixture("lootpool-current.json"), OCT18), WynnventoryPools.loot(),
                "icons, shiny flags, shiny stats and tiers come back from the cache");
        assertEquals(WynnventoryPools.parse(fixture("raidpool-current.json"), OCT18), WynnventoryPools.raids());
        assertEquals(Status.OK, WynnventoryPools.status());
        WynnventoryPools.tick(OCT18 + 61 * MIN);
        assertEquals(2, again.calls.size());
    }

    @Test
    void aFailedAttemptAlsoCountsAcrossARestart() {
        Fake f = open();
        f.answer = url -> new Response(500, "");
        WynnventoryPools.tick(OCT18);
        Saver.flush();
        Fake again = open();
        WynnventoryPools.tick(OCT18 + 10 * MIN);
        assertTrue(again.calls.isEmpty());
    }

    @Test
    void aDifferentKeyAsksOnceRightAway() {
        Fake f = open();
        WynnventoryPools.tick(OCT18);
        WynnventoryPools.testGate(null, "other-test-key");
        WynnventoryPools.tick(OCT18 + 5 * MIN);
        assertEquals(4, f.calls.size());
        WynnventoryPools.tick(OCT18 + 10 * MIN);
        assertEquals(4, f.calls.size(), "only once");
    }

    @Test
    void a401RefusesTheKeyAndGivesNone() {
        Fake f = open();
        f.answer = url -> new Response(401, "{\"error\":\"unauthorized\"}");
        WynnventoryPools.tick(OCT18);
        assertTrue(Prices.keyInvalid);
        assertEquals(Status.KEY_REFUSED, WynnventoryPools.status());
        assertSame(WeeklyPool.NONE, WynnventoryPools.raids());
        assertSame(WeeklyPool.NONE, WynnventoryPools.loot());
        int sent = f.calls.size();
        WynnventoryPools.tick(OCT18 + 3 * WynnventoryPools.REFRESH_MS);
        assertEquals(sent, f.calls.size(), "nothing more until another key is saved");
    }

    @Test
    void after500TheCachedCurrentWeekIsKept() {
        Fake f = open();
        WynnventoryPools.tick(OCT18);
        f.answer = url -> new Response(500, "oops");
        WynnventoryPools.tick(OCT18 + WynnventoryPools.REFRESH_MS);
        assertEquals(4, f.calls.size());
        assertEquals(Status.FAILED, WynnventoryPools.status());
        assertEquals(42, WynnventoryPools.raids().week());
        assertTrue(WynnventoryPools.current(WynnventoryPools.raids(), OCT18 + WynnventoryPools.REFRESH_MS));
        f.answer = url -> null;                 // a timeout
        WynnventoryPools.tick(OCT18 + 2 * WynnventoryPools.REFRESH_MS);
        assertEquals(Status.FAILED, WynnventoryPools.status());
        assertEquals(7, WynnventoryPools.loot().regions().size());
        f.answer = url -> new Response(200, "<html>maintenance</html>");
        WynnventoryPools.tick(OCT18 + 3 * WynnventoryPools.REFRESH_MS);
        assertEquals(42, WynnventoryPools.raids().week(), "an unreadable answer keeps the week too");
    }

    @Test
    void theKeyNeverAppearsInTheOutputOrTheCache() throws Exception {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream both = new PrintStream(captured, true, StandardCharsets.UTF_8);
        try {
            System.setOut(both);
            System.setErr(both);
            Fake f = open();
            WynnventoryPools.tick(OCT18);                                       // 200
            f.answer = url -> new Response(503, "");
            WynnventoryPools.tick(OCT18 + WynnventoryPools.REFRESH_MS);         // failed
            f.answer = url -> null;
            WynnventoryPools.tick(OCT18 + 2 * WynnventoryPools.REFRESH_MS);     // exception
            f.answer = url -> new Response(403, "");
            WynnventoryPools.tick(OCT18 + 3 * WynnventoryPools.REFRESH_MS);     // refused
            Saver.flush();
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        String log = captured.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("[WynnLabs] Wynnventory raid pool: week 42, 5 raids"), "the log names the endpoint and the week");
        assertTrue(log.contains("[WynnLabs] Wynnventory lootrun pool: HTTP 503"));
        assertTrue(log.contains("IOException"));
        assertTrue(log.contains("refused (403)"));
        assertFalse(log.contains(KEY), "the key is never logged");
        assertTrue(log.contains("[WynnLabs] Wynnventory shiny fields: shiny, shinyStat, shiny_stat, shiny_stat.displayName, shiny_stat.key"),
                "the shiny field names, once");
        assertEquals(log.indexOf("shiny fields"), log.lastIndexOf("shiny fields"), "logged once");
        assertFalse(log.contains("raidsWon") || log.contains("Raids Won"), "never a shiny value");
        String cache = Files.readString(dir.resolve("wynnventory-pools.json"), StandardCharsets.UTF_8);
        assertFalse(cache.contains(KEY), "the key is never written to the cache");
    }

    @Test
    void thePreviewIgnoresTheGate() {
        WeeklyPool raid = week(42);
        WynnventoryPools.preview(raid, WeeklyPool.NONE);
        assertSame(raid, WynnventoryPools.raids());
        assertEquals(Status.OK, WynnventoryPools.status());
        WynnventoryPools.tick(OCT18);                       // no fetcher, no gate: nothing happens
        assertSame(raid, WynnventoryPools.raids());
    }

    private static WeeklyPool week(int w) {
        return new WeeklyPool(2026, w, OCT18, List.of(new PoolRegion("The Canyon Colossus", "RAID", OCT18 - MIN, List.of())));
    }
}
