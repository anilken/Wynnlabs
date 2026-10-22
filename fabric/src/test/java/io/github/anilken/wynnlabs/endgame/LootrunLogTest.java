package io.github.anilken.wynnlabs.endgame;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Saver;
import io.github.anilken.wynnlabs.model.CampMythics;
import io.github.anilken.wynnlabs.model.LootrunRun;
import io.github.anilken.wynnlabs.model.LootrunRun.State;
import io.github.anilken.wynnlabs.model.LootrunTotals;
import io.github.anilken.wynnlabs.model.MythicFind;
import io.github.anilken.wynnlabs.model.PoolItem;
import io.github.anilken.wynnlabs.model.PoolRegion;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lootrun history per character (lootrun-tracking spec): runs, beacons, missions, dry streak, mythics, totals. */
class LootrunLogTest {

    private static final long T0 = 1_760_000_000_000L;
    private static final long MIN = 60_000L;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        LootrunLog.reset();
        WynnventoryPools.reset();
        LootrunLog.useRoot(dir);
        LootrunLog.switchTo("char0001");
    }

    @AfterEach
    void tearDown() {
        Saver.flush();
        LootrunLog.reset();
        LootrunLog.useRoot(null);
        WynnventoryPools.reset();
    }

    /** A run as the events bring it: start, beacons, missions each second, then the end event. */
    private static void completedRun(long end, int pulls) {
        LootrunLog.step(State.CHOOSING_BEACON, "Silent Expanse", List.of(), end - 30 * MIN);
        LootrunLog.finished(true, 20, 1800, pulls, 0, 0, 1000, end);
    }

    private static void restart(String id) {
        Saver.flush();
        LootrunLog.reset();
        LootrunLog.switchTo(id);
    }

    @Test
    void aCompletedRunKeepsItsNumbersBeaconsMissionsAndCamp() {
        long start = T0;
        LootrunLog.step(State.CHOOSING_BEACON, "Silent Expanse", List.of(), start);
        for (int i = 0; i < 9; i++) {
            LootrunLog.beacon("Blue");
        }
        for (int i = 0; i < 4; i++) {
            LootrunLog.beacon("Purple");
        }
        LootrunLog.step(State.IN_TASK, "Somewhere Else", List.of("Hoarder"), start + 1000);
        for (int i = 0; i < 3; i++) {
            LootrunLog.beacon("Yellow");
        }
        LootrunLog.beacon("Rainbow");
        LootrunLog.step(State.IN_TASK, null, List.of("Hoarder", "High Roller"), start + 2000);
        // Wynntils clears its details at the end: an empty snapshot keeps what was seen
        LootrunLog.step(State.IN_TASK, null, List.of(), start + 3000);
        LootrunLog.finished(true, 32, 1880, 14, 2, 1, 5400, start + 1880_000);

        List<LootrunRun> runs = LootrunLog.runs();
        assertEquals(1, runs.size());
        LootrunRun r = runs.get(0);
        assertTrue(r.completed());
        assertEquals(32, r.challenges());
        assertEquals(14, r.pulls());
        assertEquals(2, r.rerolls());
        assertEquals(1, r.sacrifices());
        assertEquals(5400, r.xp());
        assertEquals(1880, r.durationS());
        assertEquals(start + 1880_000, r.endedAt());
        assertEquals("Silent Expanse", r.camp(), "the camp is where the run started");
        assertEquals(Map.of("Blue", 9, "Purple", 4, "Yellow", 3, "Rainbow", 1), r.beacons());
        assertEquals(List.of("Blue", "Purple", "Yellow", "Rainbow"), new ArrayList<>(r.beacons().keySet()));
        assertEquals(List.of("Hoarder", "High Roller"), r.missions());
        assertNull(r.mythic());

        restart("char0001");
        assertEquals(runs, LootrunLog.runs(), "the run survives a restart");
        assertTrue(Files.exists(dir.resolve("char0001").resolve("lootruns.json")));
    }

    @Test
    void aFailedRunHasNoRewardNumbers() {
        LootrunLog.step(State.IN_TASK, null, List.of(), T0);
        LootrunLog.finished(false, 11, 700, 0, 0, 0, 0, T0 + 700_000);
        LootrunRun r = LootrunLog.runs().get(0);
        assertFalse(r.completed());
        assertEquals(11, r.challenges());
        assertEquals(700, r.durationS());
        assertEquals(0, r.pulls());
        assertEquals(0, r.rerolls());
        assertEquals(0, r.sacrifices());
        assertEquals(0, r.xp());
        assertEquals(L.t("wynnlabs.lootrun.unknown_camp"), r.camp(), "an unread camp reads as unknown, never blank");
        assertTrue(r.beacons().isEmpty());
        assertTrue(r.missions().isEmpty());
    }

    @Test
    void charactersAreKeptApart() {
        completedRun(T0, 12);
        LootrunLog.switchTo("char0002");
        assertTrue(LootrunLog.runs().isEmpty(), "another character starts empty");
        completedRun(T0 + 60 * MIN, 15);
        completedRun(T0 + 120 * MIN, 13);
        restart("char0001");
        assertEquals(1, LootrunLog.runs().size());
        restart("char0002");
        assertEquals(2, LootrunLog.runs().size());
        assertEquals(T0 + 120 * MIN, LootrunLog.runs().get(0).endedAt(), "newest first");
    }

    @Test
    void aRunBeforeTheCharacterIsKnownIsWrittenWhenItIs() {
        LootrunLog.reset();
        completedRun(T0, 12);
        assertEquals(1, LootrunLog.runs().size());
        LootrunLog.switchTo("char0001");
        assertEquals(1, LootrunLog.runs().size());
        restart("char0001");
        assertEquals(1, LootrunLog.runs().size(), "held in memory, then saved for the character");
    }

    @Test
    void theCapKeepsTheNewest500() {
        for (int i = 0; i < LootrunLog.CAP + 5; i++) {
            LootrunLog.finished(true, 1, 60, 1, 0, 0, 0, T0 + i * MIN);
        }
        assertEquals(LootrunLog.CAP, LootrunLog.runs().size());
        assertEquals(T0 + (LootrunLog.CAP + 4) * MIN, LootrunLog.runs().get(0).endedAt());
        restart("char0001");
        assertEquals(LootrunLog.CAP, LootrunLog.runs().size());
    }

    @Test
    void aRunLeftWithoutAnEndEventIsDropped() {
        LootrunLog.step(State.IN_TASK, "Corkus", List.of(), T0);
        LootrunLog.beacon("Blue");
        LootrunLog.step(State.IDLE, null, List.of(), T0 + MIN);
        LootrunLog.step(State.IDLE, null, List.of(), T0 + 3 * MIN);
        LootrunLog.step(State.CHOOSING_BEACON, "Sky Islands", List.of(), T0 + 4 * MIN);
        LootrunLog.finished(true, 5, 300, 3, 0, 0, 0, T0 + 9 * MIN);
        LootrunRun r = LootrunLog.runs().get(0);
        assertEquals("Sky Islands", r.camp());
        assertTrue(r.beacons().isEmpty(), "the abandoned run's beacon is not carried over");

        LootrunLog.step(State.IN_TASK, "Corkus", List.of(), T0 + 10 * MIN);
        LootrunLog.beacon("Green");
        LootrunLog.disconnected();
        LootrunLog.finished(false, 2, 100, 0, 0, 0, 0, T0 + 12 * MIN);
        assertTrue(LootrunLog.runs().get(0).beacons().isEmpty(), "a disconnect drops the run in progress");
    }

    @Test
    void theDryStreakGrowsAndAMythicResetsIt() {
        completedRun(T0, 12);
        completedRun(T0 + 60 * MIN, 15);
        completedRun(T0 + 120 * MIN, 13);
        assertEquals(3, LootrunLog.dryRuns());
        assertEquals(40, LootrunLog.dryPulls());

        long end = T0 + 180 * MIN;
        completedRun(end, 14);
        LootrunLog.mythic("Warp", end + 2 * MIN);
        assertEquals(0, LootrunLog.dryRuns(), "a mythic starts the streak again");
        assertEquals(0, LootrunLog.dryPulls());
        assertEquals(List.of(new MythicFind("Warp", end + 2 * MIN)), LootrunLog.mythics());
        assertEquals("Warp", LootrunLog.runs().get(0).mythic(), "the run whose chest gave it is marked");
        assertNull(LootrunLog.runs().get(1).mythic());

        completedRun(end + 60 * MIN, 11);
        assertEquals(1, LootrunLog.dryRuns());
        assertEquals(11, LootrunLog.dryPulls());

        restart("char0001");
        assertEquals(1, LootrunLog.dryRuns(), "the streak is kept per character");
        assertEquals(11, LootrunLog.dryPulls());
        assertEquals("Warp", LootrunLog.mythics().get(0).name());
        assertEquals("Warp", LootrunLog.runs().get(1).mythic());
    }

    @Test
    void aMythicOutsideTheWindowCountsButMarksNoRun() {
        completedRun(T0, 12);
        completedRun(T0 + 60 * MIN, 15);
        long late = T0 + 60 * MIN + LootrunLog.MYTHIC_WINDOW_MS + 1;
        LootrunLog.mythic("Grandmother", late);
        assertEquals(1, LootrunLog.mythics().size());
        assertEquals(0, LootrunLog.dryRuns());
        assertEquals(0, LootrunLog.dryPulls());
        for (LootrunRun r : LootrunLog.runs()) {
            assertNull(r.mythic(), "no run is marked for a chest opened that late");
        }

        // a failed run after the completed one does not take the mythic; the completed run does
        completedRun(T0 + 120 * MIN, 13);
        LootrunLog.finished(false, 3, 200, 0, 0, 0, 0, T0 + 125 * MIN);
        LootrunLog.mythic("Warp", T0 + 126 * MIN);
        assertNull(LootrunLog.runs().get(0).mythic());
        assertEquals("Warp", LootrunLog.runs().get(1).mythic());
        assertEquals("Warp", LootrunLog.mythics().get(0).name(), "newest first");
        assertEquals(2, LootrunLog.mythics().size());
    }

    @Test
    void totalsOverTenRuns() {
        assertEquals(LootrunTotals.NONE, LootrunLog.totals());
        int[] pulls = {14, 12, 15, 13, 16, 14, 13, 15};
        for (int i = 0; i < pulls.length; i++) {
            LootrunLog.finished(true, 30, 1800, pulls[i], 0, 0, 0, T0 + i * 60 * MIN);
        }
        LootrunLog.finished(false, 9, 600, 0, 0, 0, 0, T0 + 9 * 60 * MIN);
        LootrunLog.finished(false, 4, 300, 0, 0, 0, 0, T0 + 10 * 60 * MIN);
        LootrunTotals t = LootrunLog.totals();
        assertEquals(10, t.runs());
        assertEquals(8, t.completed());
        assertEquals(80, t.completionPct());
        assertEquals(112, t.pulls());
        assertEquals(14.0, t.pullsPerCompleted(), 1e-9);

        LootrunTotals failedOnly = LootrunLog.totalsOf(List.of(
                new LootrunRun(false, T0, 60, 2, 0, 0, 0, 0, "Corkus", null, null, null)));
        assertEquals(0, failedOnly.completionPct());
        assertEquals(-1, failedOnly.pullsPerCompleted(), "no completed run: unknown, not 0");
    }

    @Test
    void thePreviewShowsItsDataAndSavesNothing() {
        List<LootrunRun> runs = List.of(
                new LootrunRun(true, T0, 1880, 32, 14, 2, 1, 5400, "Silent Expanse", Map.of("Blue", 9), List.of("Hoarder"), "Warp"));
        LootrunLog.preview(runs, List.of(new MythicFind("Warp", T0)), 3, 40);
        assertEquals(runs, LootrunLog.runs());
        assertEquals(3, LootrunLog.dryRuns());
        assertEquals(40, LootrunLog.dryPulls());
        assertEquals(1, LootrunLog.totals().runs());
        LootrunLog.finished(true, 1, 1, 1, 0, 0, 0, T0 + MIN);
        assertEquals(1, LootrunLog.runs().size(), "game events do not change the preview");
        Saver.flush();
        assertFalse(Files.exists(dir.resolve("char0001").resolve("lootruns.json")));
    }

    // ---- group 7: this week's mythics per camp from Wynnventory ----

    private static WeeklyPool lootFixture() {
        return WynnventoryPools.parse(WynnventoryPoolsTest.fixture("lootpool-current.json"), WynnventoryPoolsTest.OCT18);
    }

    @Test
    void mythicCampsFromTheLootPoolByCountThenName() {
        WynnventoryPools.testData(WeeklyPool.NONE, lootFixture(), true);
        List<CampMythics> camps = LootrunLog.mythicCamps();
        assertEquals(List.of("Canyon of the Lost", "Silent Expanse", "Corkus", "Molten Heights", "Fruma Foray (East)",
                "Sky Islands"), camps.stream().map(CampMythics::camp).toList());
        assertEquals(List.of(4, 3, 2, 2, 1, 1), camps.stream().map(CampMythics::count).toList());
        assertEquals(List.of("Inferno", "Quetzalcoatl", "Halcyon", "Idol"), camps.get(0).mythics(),
                "Wynnventory's order; the shiny Halcyon is not a second Halcyon");
        assertEquals(Instant.parse("2026-10-17T09:20:00Z").toEpochMilli(), camps.get(0).seenAt());
        assertEquals(List.of("Warp", "Divzer", "Stratiformis"), camps.get(1).mythics(), "the shiny Divzer is left out");
        assertEquals(List.of("Fruma Foray (West)"), LootrunLog.campsWithoutMythic());
        assertEquals(42, LootrunLog.poolWeek());
        assertEquals(Instant.parse("2026-10-18T07:05:58Z").toEpochMilli(), LootrunLog.poolSeenAt());
        assertFalse(LootrunLog.poolStale(WynnventoryPoolsTest.OCT18));
        assertTrue(LootrunLog.poolStale(WynnventoryPoolsTest.OCT18 + 7 * 24 * 60 * MIN), "next week");
    }

    @Test
    void eachMythicNameCountsOnceAndShiniesNotAtAll() {
        PoolItem shiny = new PoolItem("Halcyon", "GearItem", Tier.MYTHIC, "Weapon", 1, "attribute", "wand.multi3", true,
                "Raids Won", 0);
        WeeklyPool p = new WeeklyPool(2026, 42, 1, List.of(
                new PoolRegion("Canyon of the Lost", "LOOTRUN_CAMP", 5, List.of(shiny,
                        new PoolItem("Halcyon", "GearItem", Tier.MYTHIC, "Weapon", 1),
                        new PoolItem("halcyon", "GearItem", Tier.MYTHIC, "Weapon", 1),
                        new PoolItem("Idol", "GearItem", Tier.MYTHIC, "Weapon", 1))),
                new PoolRegion("Corkus", "LOOTRUN_CAMP", 6, List.of(
                        new PoolItem("Divzer", "GearItem", Tier.MYTHIC, "Weapon", 1, "", "", true, "", 0)))));
        WynnventoryPools.testData(WeeklyPool.NONE, p, true);
        List<CampMythics> camps = LootrunLog.mythicCamps();
        assertEquals(1, camps.size());
        assertEquals(List.of("Halcyon", "Idol"), camps.get(0).mythics());
        assertEquals(List.of("Corkus"), LootrunLog.campsWithoutMythic(), "a shiny alone is not a mythic of the camp");
    }

    @Test
    void gearThatIsNotMythicIsIgnored() {
        WynnventoryPools.testData(WeeklyPool.NONE, lootFixture(), true);
        assertTrue(LootrunLog.mythicCamps().stream().flatMap(c -> c.mythics().stream())
                .noneMatch(n -> n.equals("Sunstar") || n.equals("Cataclysm Remnant") || n.equals("Az Rune")));
        WeeklyPool noMythic = new WeeklyPool(2026, 42, 1, List.of(new PoolRegion("Corkus", "LOOTRUN_CAMP", 5, List.of(
                new PoolItem("Sunstar", "GearItem", Tier.LEGENDARY, "Weapon", 1),
                new PoolItem("Az Rune", "RuneItem", Tier.MYTHIC, "", 1),
                new PoolItem("Tome of Lootrun Mastery", "TomeItem", Tier.MYTHIC, "", 1)))));
        WynnventoryPools.testData(WeeklyPool.NONE, noMythic, true);
        assertTrue(LootrunLog.mythicCamps().isEmpty(), "only mythic gear counts");
        assertEquals(List.of("Corkus"), LootrunLog.campsWithoutMythic());
    }

    @Test
    void theListsAreEmptyWithoutData() {
        assertTrue(LootrunLog.mythicCamps().isEmpty());
        assertTrue(LootrunLog.campsWithoutMythic().isEmpty());
        assertEquals(-1, LootrunLog.poolSeenAt());
        assertEquals(-1, LootrunLog.poolWeek());
        assertTrue(LootrunLog.poolStale(WynnventoryPoolsTest.OCT18));
        WynnventoryPools.testData(WeeklyPool.NONE, lootFixture(), false);   // the gate closed
        assertTrue(LootrunLog.mythicCamps().isEmpty());
        assertEquals(-1, LootrunLog.poolWeek());
    }
}
