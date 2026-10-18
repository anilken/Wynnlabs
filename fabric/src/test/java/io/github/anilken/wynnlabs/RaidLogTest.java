package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.RaidDry;
import io.github.anilken.wynnlabs.model.RaidRun;
import io.github.anilken.wynnlabs.model.RaidSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-character raid history: records, totals past the cap, summaries, streak and session. */
class RaidLogTest {

    private static final String TNA = "The Nameless Anomaly";
    private static final String NOG = "Nest of the Grootslangs";
    private static final String TCC = "The Canyon Colossus";

    @TempDir
    Path dir;

    private final AtomicReference<String> id = new AtomicReference<>("char0001");

    @BeforeEach
    void setUp() {
        RaidLog.use(dir, id::get);
    }

    @AfterEach
    void tearDown() {
        RaidLog.reset();
    }

    private static long ms(int min, int sec) {
        return (min * 60L + sec) * 1000L;
    }

    private static RaidRun run(String raid, boolean completed, long endedAt, long durationMs, RaidRun.Room... rooms) {
        return new RaidRun(raid, "", completed, endedAt, durationMs, List.of(rooms));
    }

    private static RaidSummary row(String raid) {
        return RaidLog.summaries().stream().filter(s -> s.raid().equals(raid)).findFirst().orElseThrow();
    }

    /** Writes what is pending and drops the memory, so the next read comes from the file. */
    private static void reload() {
        Saver.flush();
        RaidLog.forget();
        RaidLog.tick();
    }

    @Test
    void aCompletedAndAFailedRunAreRecordedAndSurviveARestart() {
        RaidLog.record(run(TCC, true, 1_000, ms(14, 32), new RaidRun.Room("Room 1", ms(4, 0)),
                new RaidRun.Room("Room 2", ms(10, 32))));
        RaidLog.record(run(NOG, false, 2_000, ms(6, 10), new RaidRun.Room("Slime Gathering", ms(3, 0)),
                new RaidRun.Room("Tunnels", ms(3, 10))));

        assertEquals(2, RaidLog.runs().size());
        assertEquals(NOG, RaidLog.runs().get(0).raid(), "newest first");
        reload();
        assertTrue(Files.exists(RaidLog.file("char0001")));

        List<RaidRun> runs = RaidLog.runs();
        assertEquals(2, runs.size());
        RaidRun failed = runs.get(0);
        assertFalse(failed.completed());
        assertEquals(2, failed.rooms().size(), "the rooms reached are kept");
        RaidRun done = runs.get(1);
        assertTrue(done.completed());
        assertEquals(ms(14, 32), done.durationMs());
        assertEquals("Room 2", done.rooms().get(1).name());

        RaidSummary tcc = row(TCC);
        assertEquals(1, tcc.done());
        assertEquals(0, tcc.failed());
        assertEquals(ms(14, 32), tcc.bestMs());
        assertEquals(List.of(new RaidRun.Room("Room 1", ms(4, 0)), new RaidRun.Room("Room 2", ms(10, 32))), tcc.bestRooms());
        RaidSummary nog = row(NOG);
        assertEquals(0, nog.done());
        assertEquals(1, nog.failed());
        assertEquals(-1, nog.bestMs(), "a failed run gives no time");
        assertEquals(-1, nog.averageMs());
        assertEquals(2_000, nog.lastAt());
    }

    @Test
    void twoCharactersAreKeptApart() {
        RaidLog.record(run(TCC, true, 1_000, ms(14, 0)));
        id.set("char0002");
        RaidLog.tick();
        assertTrue(RaidLog.runs().isEmpty(), "the second character starts empty");
        RaidLog.record(run(NOG, false, 2_000, ms(5, 0)));
        RaidLog.record(run(NOG, true, 3_000, ms(11, 0)));
        assertEquals(2, RaidLog.runs().size());
        reload();

        id.set("char0001");
        RaidLog.tick();
        assertEquals(1, RaidLog.runs().size());
        assertEquals(TCC, RaidLog.runs().get(0).raid());
        assertEquals(0, row(NOG).done() + row(NOG).failed());
        id.set("char0002");
        RaidLog.tick();
        assertEquals(2, RaidLog.runs().size());
        assertEquals(1, row(NOG).done());
        assertTrue(Files.exists(RaidLog.file("char0001")));
        assertTrue(Files.exists(RaidLog.file("char0002")));
    }

    @Test
    void runsBeforeTheCharacterIsKnownAreHeldThenWritten() {
        id.set("");
        RaidLog.record(run(TCC, true, 1_000, ms(14, 0)));
        assertTrue(RaidLog.runs().isEmpty());
        assertFalse(Files.exists(RaidLog.file("char0001")));
        id.set("char0001");
        RaidLog.tick();
        assertEquals(1, RaidLog.runs().size());
        reload();
        assertEquals(1, RaidLog.runs().size(), "the held run reached the character's file");
    }

    @Test
    void theCapKeepsTheTotals() {
        int n = RaidLog.CAP + 5;
        for (int i = 0; i < n; i++) {
            RaidLog.record(run(TCC, i % 5 != 0, 1_000L + i, ms(15, 0) + i));
        }
        assertEquals(RaidLog.CAP, RaidLog.runs().size());
        assertEquals(1_000L + n - 1, RaidLog.runs().get(0).endedAt(), "the oldest fall off");
        RaidSummary tcc = row(TCC);
        assertEquals(n, tcc.done() + tcc.failed());
        assertEquals(101, tcc.failed());
        assertEquals(ms(15, 0) + 1, tcc.bestMs(), "the best time of a run that fell off is kept");
        reload();
        assertEquals(RaidLog.CAP, RaidLog.runs().size());
        assertEquals(n, row(TCC).done() + row(TCC).failed());
        assertEquals(ms(15, 0) + 1, row(TCC).bestMs());
    }

    @Test
    void theBuiltInRaidsComeFirstAndAnUnknownRaidGetsItsRow() {
        List<RaidSummary> empty = RaidLog.summaries();
        assertEquals(RaidLog.RAIDS, empty.stream().map(RaidSummary::raid).toList());
        RaidSummary wtp = empty.get(4);
        assertEquals(0, wtp.done());
        assertEquals(-1, wtp.bestMs());
        assertEquals(-1, wtp.lastAt());
        assertNull(wtp.last());

        RaidLog.record(run("The Frozen Spire", true, 5_000, ms(20, 0)));
        RaidLog.record(run("A Later Raid", false, 6_000, ms(2, 0)));
        List<String> names = RaidLog.summaries().stream().map(RaidSummary::raid).toList();
        assertEquals(7, names.size());
        assertEquals(RaidLog.RAIDS, names.subList(0, 5));
        assertEquals(List.of("A Later Raid", "The Frozen Spire"), names.subList(5, 7), "other raids by name");
        assertEquals(1, row("The Frozen Spire").done());
        reload();
        assertEquals(7, RaidLog.summaries().size());
    }

    @Test
    void bestAndAverageCountCompletedRunsOnly() {
        long last = 4_000;
        RaidLog.record(run(TNA, true, 1_000, ms(18, 10)));
        RaidLog.record(run(TNA, true, 2_000, ms(16, 45)));
        RaidLog.record(run(TNA, false, 2_500, ms(3, 0)));
        RaidLog.record(run(TNA, true, last, ms(17, 20)));
        RaidSummary tna = row(TNA);
        assertEquals(3, tna.done());
        assertEquals(1, tna.failed());
        assertEquals(ms(16, 45), tna.bestMs());
        assertEquals(ms(17, 25), tna.averageMs());
        assertEquals(last, tna.lastAt());
        assertEquals(ms(17, 20), tna.last().durationMs());
    }

    @Test
    void aFailureBreaksTheStreakAndTheSessionCountsBoth() {
        RaidLog.record(run(TCC, true, 1_000, ms(14, 0)));
        RaidLog.record(run(NOG, true, 2_000, ms(11, 0)));
        RaidLog.record(run(TNA, true, 3_000, ms(17, 0)));
        assertEquals(3, RaidLog.streak());
        RaidLog.record(run(TCC, false, 4_000, ms(5, 0)));
        assertEquals(3, RaidLog.sessionDone());
        assertEquals(1, RaidLog.sessionFailed());
        assertEquals(0, RaidLog.streak());
        RaidLog.record(run(TCC, true, 5_000, ms(14, 0)));
        assertEquals(1, RaidLog.streak());

        reload();
        assertEquals(1, RaidLog.streak(), "the streak is saved");
        RaidLog.disconnected();
        assertEquals(0, RaidLog.sessionDone(), "a new session starts on disconnect");
        assertEquals(0, RaidLog.sessionFailed());
        assertEquals(1, RaidLog.streak());
    }

    @Test
    void theStartFillsAMissingDuration() {
        RaidLog.started(TCC, 10_000);
        RaidLog.record(run(TCC, true, 10_000 + ms(14, 32), -1));
        assertEquals(ms(14, 32), RaidLog.runs().get(0).durationMs());
    }

    @Test
    void unknownDryCountersStayUnknown() {
        assertEquals(RaidDry.UNKNOWN, RaidLog.dry(), "no Wynntils: every counter is -1, never 0");
        RaidLog.preview(List.of(run(TCC, true, 2_000, ms(14, 0)), run(TCC, true, 1_000, ms(15, 0))), 5, 3, 0,
                new RaidDry(7, 31, -1, 39));
        assertEquals(-1, RaidLog.dry().tomeRaids());
        assertEquals(31, RaidLog.dry().aspectPulls());
        assertEquals(5, RaidLog.streak());
        assertEquals(3, RaidLog.sessionDone());
        assertEquals(2, row(TCC).done());
        assertEquals(ms(14, 30), row(TCC).averageMs());
        Saver.flush();
        assertFalse(Files.exists(dir.resolve("characters")), "the preview writes nothing");
    }
}
