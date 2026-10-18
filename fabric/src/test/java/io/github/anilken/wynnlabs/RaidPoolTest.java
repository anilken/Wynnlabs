package io.github.anilken.wynnlabs;

import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.model.AspectEntry;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.PoolAspect;
import io.github.anilken.wynnlabs.model.Tier;
import io.github.anilken.wynnlabs.model.WeeklyPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The raid reward pool as seen: marks against owned tiers, the age text, staleness, the file. */
class RaidPoolTest {

    private static final long HOUR = 60L * 60 * 1000;
    private static final long DAY = 24 * HOUR;

    private static final PoolAspect DAMNATION = new PoolAspect("Aspect of Damnation", Klass.SHAMAN, Tier.LEGENDARY, 1);
    private static final PoolAspect AMPHIBIAN = new PoolAspect("Aspect of the Amphibian", Klass.SHAMAN, Tier.FABLED, 2);
    private static final PoolAspect MONOLITH = new PoolAspect("Aspect of the Monolith", Klass.SHAMAN, Tier.LEGENDARY, 2);

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        RaidPool.use(dir);
        WynnventoryPools.reset();
        AspectBook.reset();
        english(1);
    }

    @AfterEach
    void tearDown() {
        RaidPool.reset();
        WynnventoryPools.reset();
        AspectBook.reset();
        Settings.use(new JsonObject());
    }

    private static void english(int pick) {
        JsonObject s = new JsonObject();
        s.addProperty("langOverride", pick);
        Settings.use(s);
    }

    @Test
    void marksAgainstTheOwnedTier() {
        assertEquals(RaidPool.Mark.NEW, RaidPool.mark(AMPHIBIAN, 0));
        assertEquals(RaidPool.Mark.TIER_UP, RaidPool.mark(AMPHIBIAN, 1));
        assertEquals(RaidPool.Mark.NONE, RaidPool.mark(AMPHIBIAN, 2));
        assertEquals(RaidPool.Mark.NONE, RaidPool.mark(AMPHIBIAN, 3));
        assertEquals(RaidPool.Mark.NONE, RaidPool.mark(AMPHIBIAN, -1), "owned tiers unknown: no mark");
        assertEquals(RaidPool.Mark.NONE, RaidPool.mark(DAMNATION, 1));
    }

    @Test
    void marksOfThePoolAndTheirCount() {
        long now = 10 * DAY;
        RaidPool.preview(List.of(DAMNATION, AMPHIBIAN, MONOLITH), now - 2 * DAY);
        Map<String, Integer> owned = Map.of("Aspect of the Amphibian", 1, "Aspect of the Monolith", 2);
        assertEquals(RaidPool.Mark.NEW, RaidPool.markOf("Aspect of Damnation", n -> owned.getOrDefault(n, 0)));
        assertEquals(RaidPool.Mark.TIER_UP, RaidPool.markOf("aspect of the amphibian", n -> owned.getOrDefault(n, 0)));
        assertEquals(RaidPool.Mark.NONE, RaidPool.markOf("Aspect of the Monolith", n -> owned.getOrDefault(n, 0)));
        assertEquals(RaidPool.Mark.NONE, RaidPool.markOf("Aspect of Bile Waters", n -> 0), "not in the pool");
        assertEquals(2, RaidPool.markedCount(n -> owned.getOrDefault(n, 0)));
        assertEquals(0, RaidPool.markedCount(n -> -1));
        assertTrue(RaidPool.holds("Aspect of Damnation"));
        assertFalse(RaidPool.holds("Aspect of Bile Waters"));
        assertEquals("2 days ago", RaidPool.ageText(now));
        assertFalse(RaidPool.stale(now));
    }

    @Test
    void theAgeTextAndStaleness() {
        long seen = 100 * DAY;
        RaidPool.preview(List.of(DAMNATION), seen);
        assertEquals("just now", RaidPool.ageText(seen + 30_000));
        assertEquals("5 min ago", RaidPool.ageText(seen + 5 * 60_000));
        assertEquals("3 h ago", RaidPool.ageText(seen + 3 * HOUR + 59 * 60_000));
        assertEquals("1 day ago", RaidPool.ageText(seen + DAY + HOUR));
        assertEquals("6 days ago", RaidPool.ageText(seen + 6 * DAY));
        assertFalse(RaidPool.stale(seen + RaidPool.STALE_MS));
        assertTrue(RaidPool.stale(seen + RaidPool.STALE_MS + 1));
        english(2);
        assertEquals("2 gün önce", RaidPool.ageText(seen + 2 * DAY));
        assertEquals("az önce", RaidPool.ageText(seen));
    }

    @Test
    void neverSeen() {
        assertFalse(RaidPool.seen());
        assertEquals(-1, RaidPool.seenAt());
        assertTrue(RaidPool.aspects().isEmpty());
        assertEquals("", RaidPool.ageText(DAY));
        assertFalse(RaidPool.stale(1000 * DAY));
        assertEquals(RaidPool.Mark.NONE, RaidPool.markOf("Aspect of Damnation", n -> 0));
        RaidPool.preview(List.of(DAMNATION), -1);
        assertFalse(RaidPool.seen(), "the preview's never-seen pool");
        assertTrue(RaidPool.aspects().isEmpty());
    }

    @Test
    void aPoolSeenIsSavedAndReadBack() throws Exception {
        RaidPool.seen(List.of(DAMNATION, AMPHIBIAN), 5 * DAY);
        assertEquals(5 * DAY, RaidPool.seenAt());
        Saver.flush();
        assertTrue(Files.exists(dir.resolve("raid-pool.json")));
        RaidPool.forget();
        assertFalse(RaidPool.seen());
        RaidPool.load();
        assertEquals(List.of(DAMNATION, AMPHIBIAN), RaidPool.aspects(), "menu order, class, rarity and tier kept");
        assertEquals(5 * DAY, RaidPool.seenAt());

        // the same opening: the list grows as the slots load, the time moves with it
        RaidPool.seen(List.of(DAMNATION, AMPHIBIAN, MONOLITH), 5 * DAY + 500);
        assertEquals(3, RaidPool.aspects().size());
        assertEquals(5 * DAY + 500, RaidPool.seenAt());
        // still open, nothing new: not saved again
        RaidPool.seen(List.of(DAMNATION, AMPHIBIAN, MONOLITH), 5 * DAY + 1000);
        assertEquals(5 * DAY + 500, RaidPool.seenAt());
    }

    // ---- group 7: the pool from Wynnventory ----

    private static final long OCT18 = WynnventoryPoolsTest.OCT18;
    private static final String TCC = "The Canyon Colossus";
    private static final String TNA = "The Nameless Anomaly";
    /** The Shaman aspects of the raid fixture, in raid order then Wynnventory's order, with their tier counts. */
    private static final List<String> SHAMAN_POOL = List.of("Aspect of the Amphibian", "Aspect of Bile Waters",
            "Aspect of the Eternal Dance", "Aspect of the Summoner", "Aspect of Damnation", "Aspect of Gushing Blood",
            "Aspect of the Channeler", "Aspect of the Monolith", "Aspect of Seeping Rot");

    private static WeeklyPool raidFixture() {
        return WynnventoryPools.parse(WynnventoryPoolsTest.fixture("raidpool-current.json"), OCT18);
    }

    /** A Shaman whose owned tiers are these (null: never read); every pool aspect has 3 tiers, Damnation 4. */
    private static void shaman(Map<String, Integer> owned) {
        List<AspectEntry> aspects = new ArrayList<>();
        Map<String, Integer> all = owned == null ? null : new HashMap<>();
        for (String n : SHAMAN_POOL) {
            aspects.add(new AspectEntry(n, Klass.SHAMAN, Tier.LEGENDARY, n.equals("Aspect of Damnation") ? 4 : 3,
                    List.of(1, 5, 30), List.of()));
            if (all != null) {
                all.put(n, owned.getOrDefault(n, 0));
            }
        }
        AspectBook.install(Klass.SHAMAN, aspects, all, List.of(), null, Set.of(), Set.of(), "Ritualist");
        RaidPool.clock(() -> OCT18);
    }

    @Test
    void aCurrentWynnventoryPoolWinsOverTheGamesOne() {
        shaman(Map.of());
        RaidPool.preview(List.of(MONOLITH), OCT18 - HOUR);
        WynnventoryPools.testData(raidFixture(), WeeklyPool.NONE, true);
        assertEquals(RaidPool.Source.WYNNVENTORY, RaidPool.source());
        assertEquals(42, RaidPool.week());
        assertEquals(SHAMAN_POOL, RaidPool.aspects().stream().map(PoolAspect::name).toList(),
                "across raids, no repeats, raid order then Wynnventory's");
        assertTrue(RaidPool.aspects().stream().allMatch(a -> a.tier() == 0 && a.klass() == Klass.SHAMAN));
        assertEquals(RaidLog.RAIDS, RaidPool.poolRaids(), "the built-in five in summary order");
        assertEquals(Instant.parse("2026-10-18T08:12:05Z").toEpochMilli(), RaidPool.seenAt(), "the newest raid");
        assertEquals("3 h ago", RaidPool.ageText(OCT18));
        assertFalse(RaidPool.stale(OCT18));
        assertTrue(RaidPool.stale(OCT18 + 7 * DAY), "next week: may be out of date");
    }

    @Test
    void withTheGateClosedTheGamesPoolIsUsed() {
        shaman(Map.of());
        RaidPool.preview(List.of(MONOLITH, AMPHIBIAN), OCT18 - 2 * DAY);
        WynnventoryPools.testData(raidFixture(), WeeklyPool.NONE, false);
        assertEquals(RaidPool.Source.GAME, RaidPool.source());
        assertEquals(List.of(MONOLITH, AMPHIBIAN), RaidPool.aspects());
        assertEquals(-1, RaidPool.week());
        assertTrue(RaidPool.poolRaids().isEmpty());
        assertTrue(RaidPool.raidsHolding("Aspect of the Monolith").isEmpty());
        assertTrue(RaidPool.aspectsIn(TCC).isEmpty());
        assertEquals(-1, RaidPool.raidSeenAt(TCC));
        assertEquals("2 days ago", RaidPool.ageText(OCT18));
        assertFalse(RaidPool.holds("Aspect of Damnation"));
    }

    @Test
    void betweenAStaleWynnventoryPoolAndTheGamesTheNewerWins() {
        shaman(Map.of());
        WeeklyPool fresh = raidFixture();
        WeeklyPool old = new WeeklyPool(2026, 41, fresh.fetchedAt(), fresh.regions());
        WynnventoryPools.testData(old, WeeklyPool.NONE, true);
        RaidPool.preview(List.of(MONOLITH), OCT18 - HOUR);           // after the newest raid timestamp
        assertEquals(RaidPool.Source.GAME, RaidPool.source());
        RaidPool.preview(List.of(MONOLITH), OCT18 - 2 * DAY);        // before it
        assertEquals(RaidPool.Source.WYNNVENTORY, RaidPool.source());
        assertTrue(RaidPool.stale(OCT18), "an earlier week may be out of date");
        assertEquals(41, RaidPool.week());

        assertEquals(RaidPool.Source.WYNNVENTORY, RaidPool.choose(fresh, true, OCT18));
        assertEquals(RaidPool.Source.GAME, RaidPool.choose(old, false, OCT18));
        assertEquals(RaidPool.Source.WYNNVENTORY, RaidPool.choose(old, false, -1));
        assertEquals(RaidPool.Source.GAME, RaidPool.choose(WeeklyPool.NONE, false, OCT18));
        assertEquals(RaidPool.Source.NONE, RaidPool.choose(WeeklyPool.NONE, false, -1));
        assertEquals(RaidPool.Source.NONE, RaidPool.choose(null, true, -1));
    }

    @Test
    void damnationIsHeldByTheCanyonColossusAndTheNamelessAnomaly() {
        shaman(Map.of());
        WynnventoryPools.testData(raidFixture(), WeeklyPool.NONE, true);
        assertEquals(List.of(TCC, TNA), RaidPool.raidsHolding("Aspect of Damnation"));
        assertEquals(List.of(TCC, TNA), RaidPool.raidsHolding("aspect of damnation"));
        assertEquals(List.of("Aspect of Damnation", "Aspect of Gushing Blood", "Aspect of the Channeler"),
                RaidPool.aspectsIn(TCC).stream().map(PoolAspect::name).toList());
        assertEquals(Instant.parse("2026-10-17T22:39:33Z").toEpochMilli(), RaidPool.raidSeenAt(TCC));
        assertEquals(-1, RaidPool.raidSeenAt("Some Other Raid"));
        assertTrue(RaidPool.raidsHolding("Aspect of the Bulwark").isEmpty());
        assertEquals("TCC", RaidPool.shortName(TCC));
        assertEquals("NOL", RaidPool.shortName("Orphion's Nexus of Light"));
        assertEquals("WTP", RaidPool.shortName("The Wartorn Palace"));
        assertEquals("TFR", RaidPool.shortName("The Frozen Rift"), "another raid: initials");
        assertEquals("", RaidPool.shortName(null));
    }

    @Test
    void otherClassesAspectsAreLeftOut() {
        shaman(Map.of());
        WynnventoryPools.testData(raidFixture(), WeeklyPool.NONE, true);
        assertEquals(9, RaidPool.aspects().size());
        assertFalse(RaidPool.holds("Aspect of the Bulwark"), "a Warrior aspect");
        assertTrue(RaidPool.holds("Aspect of Damnation"));
        AspectBook.install(Klass.WARRIOR, List.of(), Map.of(), List.of(), null, Set.of(), Set.of(), null);
        assertTrue(RaidPool.aspects().stream().allMatch(a -> a.klass() == Klass.WARRIOR));
        assertTrue(RaidPool.holds("Aspect of the Bulwark"), "the views follow the class in play");
        assertFalse(RaidPool.holds("Aspect of Damnation"));
    }

    @Test
    void marksOfAWynnventoryPool() {
        PoolAspect api = new PoolAspect("Aspect of the Amphibian", Klass.SHAMAN, Tier.FABLED, 0);
        assertEquals(RaidPool.Mark.NEW, RaidPool.mark(api, 0, 3));
        assertEquals(RaidPool.Mark.TIER_UP, RaidPool.mark(api, 1, 3));
        assertEquals(RaidPool.Mark.TIER_UP, RaidPool.mark(api, 2, 3));
        assertEquals(RaidPool.Mark.NONE, RaidPool.mark(api, 3, 3));
        assertEquals(RaidPool.Mark.NONE, RaidPool.mark(api, -1, 3));
        assertEquals(RaidPool.Mark.NONE, RaidPool.mark(api, 1, -1), "max tier unknown");
        // an aspect seen in game keeps the rule of 2.3
        assertEquals(RaidPool.Mark.TIER_UP, RaidPool.mark(AMPHIBIAN, 1, 3));
        assertEquals(RaidPool.Mark.NONE, RaidPool.mark(AMPHIBIAN, 2, 3));

        shaman(Map.of("Aspect of the Amphibian", 1, "Aspect of the Monolith", 3, "Aspect of Bile Waters", 3,
                "Aspect of the Eternal Dance", 3, "Aspect of the Summoner", 3, "Aspect of Gushing Blood", 3,
                "Aspect of the Channeler", 2, "Aspect of Seeping Rot", 3));
        WynnventoryPools.testData(raidFixture(), WeeklyPool.NONE, true);
        assertEquals(RaidPool.Mark.NEW, RaidPool.markOf("Aspect of Damnation"));
        assertEquals(RaidPool.Mark.TIER_UP, RaidPool.markOf("Aspect of the Amphibian"));
        assertEquals(2, RaidPool.tagTier("Aspect of the Amphibian"), "1 of 3: the tag names tier 2");
        assertEquals(3, RaidPool.tagTier("Aspect of the Channeler"));
        assertEquals(RaidPool.Mark.NONE, RaidPool.markOf("Aspect of the Monolith"), "3 of 3");
        assertEquals(0, RaidPool.tagTier("Aspect of the Monolith"));
        assertEquals(0, RaidPool.tagTier("Aspect of Damnation"), "NEW names no tier");
        assertEquals(RaidPool.Mark.NONE, RaidPool.markOf("Aspect of the Bulwark"), "not in the class's pool");
        assertEquals(3, RaidPool.markedCount(), "Damnation, Amphibian, Channeler");

        shaman(null);                                                // owned tiers never read
        assertEquals(RaidPool.Mark.NONE, RaidPool.markOf("Aspect of Damnation"));
        assertEquals(0, RaidPool.markedCount());
    }

    @Test
    void theGamesTagTierIsTheTierShown() {
        RaidPool.preview(List.of(AMPHIBIAN), 10 * DAY);
        assertEquals(2, RaidPool.tagTier("Aspect of the Amphibian", n -> 1));
        assertEquals(0, RaidPool.tagTier("Aspect of the Amphibian", n -> 2));
        assertEquals(RaidPool.Source.GAME, RaidPool.source());
    }
}
