package io.github.anilken.wynnlabs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The screenshot tool's sequence on a fake game: the order, and everything put back at the end, on Esc and on an error. */
class ShotRunTest {

    /** The rail of the menu in miniature: tab numbers in rail order, and each number's name. */
    private static final int[] RAIL = {0, 3, 2, 1};
    private static final String[] NAMES = {"home", "today", "endgame", "settings"};

    /** A game that writes down what is done to it. */
    private static final class Fake implements ShotRun.Stage {
        final List<String> log = new ArrayList<>();
        int lang = 2;
        boolean flags;
        boolean hidden;
        boolean open;
        boolean plan;
        int pending;
        Integer doneCount;
        ShotRun.Stop stop;
        boolean done;
        RuntimeException failOn;

        @Override
        public int lang() {
            return lang;
        }

        @Override
        public void lang(int pick) {
            log.add("lang " + pick);
            lang = pick;
        }

        @Override
        public String code() {
            return lang == 2 ? "tr" : "en";
        }

        @Override
        public void flags(boolean on, boolean hideName) {
            log.add("flags " + on + (hideName ? " hidename" : ""));
            flags = on;
            hidden = hideName;
        }

        @Override
        public void open() {
            log.add("open");
            open = true;
        }

        @Override
        public boolean showing() {
            return open;
        }

        @Override
        public boolean has(String state) {
            return !"plan".equals(state) || plan;
        }

        @Override
        public void select(int tab, String state) {
            log.add("select " + NAMES[tab] + (state == null ? "" : "-" + state));
        }

        @Override
        public void capture(String file) {
            if (failOn != null && file.contains("endgame")) {
                throw failOn;
            }
            log.add("capture " + file);
            pending++;
        }

        @Override
        public int pending() {
            return pending;
        }

        @Override
        public void restore() {
            log.add("restore");
            open = false;
        }

        @Override
        public void done(int count, ShotRun.Stop why, String detail) {
            log.add("done " + count + (why == null ? "" : " " + why));
            done = true;
            doneCount = count;
            stop = why;
        }
    }

    private static int runToEnd(ShotRun run, Fake game) {
        int ticks = 0;
        while (run.tick()) {
            if (++ticks > 10_000) {
                throw new AssertionError("the run never ended");
            }
            if (ticks % 7 == 0) {
                game.pending = 0;                   // the writes finish in the background
            }
        }
        return ticks;
    }

    private static List<String> only(List<String> log, String prefix) {
        return log.stream().filter(l -> l.startsWith(prefix)).toList();
    }

    @Test
    void planFollowsTheRailWithEachTabsViews() {
        List<String> names = ShotRun.plan(RAIL, NAMES, "all").stream()
                .map(s -> s.name() + (s.state() == null ? "" : "-" + s.state())).toList();
        assertEquals(List.of("home", "home-picker", "home-plan", "settings", "settings-about", "endgame", "endgame-pools", "today"),
                names);
        assertEquals(List.of("endgame", "endgame-pools"), ShotRun.plan(RAIL, NAMES, "endgame").stream()
                .map(s -> s.name() + (s.state() == null ? "" : "-" + s.state())).toList());
        assertEquals("en-03-home-plan.png", new ShotRun.Shot(0, "home", "plan", 1).file("en", 3));
        assertEquals("tr-12-today.png", new ShotRun.Shot(1, "today", null, 1).file("tr", 12));
    }

    @Test
    void capturesInOrderThenPutsEverythingBack() {
        Fake game = new Fake();
        game.plan = true;
        ShotRun run = new ShotRun(game, ShotRun.plan(RAIL, NAMES, "all"), 1, false);
        runToEnd(run, game);
        // the language first, then the menu; each tab selected before its capture, in rail order
        assertEquals(List.of("flags true", "lang 1", "open"), game.log.subList(0, 3));
        assertEquals(List.of("capture en-01-home.png", "capture en-02-home-picker.png", "capture en-03-home-plan.png",
                "capture en-04-settings.png", "capture en-05-settings-about.png", "capture en-06-endgame.png",
                "capture en-07-endgame-pools.png", "capture en-08-today.png"), only(game.log, "capture"));
        for (int i = 0; i < game.log.size(); i++) {
            if (game.log.get(i).startsWith("capture")) {
                assertTrue(game.log.get(i - 1).startsWith("select"), "a capture right after its select: " + game.log);
            }
        }
        // then the screen, the language and the flags back, and one message with the count
        int restore = game.log.indexOf("restore");
        assertEquals(List.of("restore", "lang 2", "flags false", "done 8"), game.log.subList(restore, game.log.size()));
        assertEquals(2, game.lang);
        assertFalse(game.flags);
        assertNull(game.stop);
        assertTrue(run.finished());
        assertFalse(run.tick());
    }

    @Test
    void waitsSomeTicksBeforeEachCapture() {
        Fake game = new Fake();
        ShotRun run = new ShotRun(game, List.of(new ShotRun.Shot(1, "today", null, 5)), -1, false);
        run.tick();                                 // start
        run.tick();                                 // select
        for (int i = 0; i < 4; i++) {
            run.tick();
            assertTrue(only(game.log, "capture").isEmpty(), "captured before the frames settled");
        }
        run.tick();
        assertEquals(List.of("capture tr-01-today.png"), only(game.log, "capture"));
    }

    @Test
    void aViewThatIsNotThereIsSkippedAndTheNumbersStayInARow() {
        Fake game = new Fake();
        game.plan = false;
        runToEnd(new ShotRun(game, ShotRun.plan(RAIL, NAMES, "home"), 1, false), game);
        assertEquals(List.of("capture en-01-home.png", "capture en-02-home-picker.png"), only(game.log, "capture"));
        assertEquals(2, game.doneCount);
    }

    @Test
    void escStopsAndPutsEverythingBack() {
        Fake game = new Fake();
        ShotRun run = new ShotRun(game, ShotRun.plan(RAIL, NAMES, "all"), 1, true);
        for (int i = 0; i < 40; i++) {
            run.tick();
        }
        assertTrue(game.hidden);
        assertEquals(1, game.lang);
        run.escape();
        assertFalse(run.tick());
        assertEquals(ShotRun.Stop.ESC, game.stop);
        int restore = game.log.indexOf("restore");
        assertEquals(List.of("restore", "lang 2", "flags false", "done " + run.count() + " ESC"),
                game.log.subList(restore, game.log.size()));
        assertEquals(2, game.lang);
        assertFalse(game.flags);
        assertFalse(game.hidden);
        assertFalse(run.tick());
        assertEquals(1, only(game.log, "restore").size(), "put back once");
    }

    @Test
    void aMenuClosedByAnythingElseStopsTheRun() {
        Fake game = new Fake();
        ShotRun run = new ShotRun(game, ShotRun.plan(RAIL, NAMES, "all"), 1, false);
        run.tick();
        run.tick();
        game.open = false;                          // the player opened the chat, a server menu replaced it
        assertFalse(run.tick());
        assertEquals(ShotRun.Stop.CLOSED, game.stop);
        assertEquals(2, game.lang);
        assertTrue(game.log.contains("restore"));
        assertFalse(game.flags);
    }

    @Test
    void anErrorPutsEverythingBack() {
        Fake game = new Fake();
        game.failOn = new IllegalStateException("no frame");
        ShotRun run = new ShotRun(game, ShotRun.plan(RAIL, NAMES, "all"), 1, false);
        runToEnd(run, game);
        assertEquals(ShotRun.Stop.ERROR, game.stop);
        assertEquals(2, game.lang);
        assertFalse(game.flags);
        assertTrue(game.log.contains("restore"));
        assertFalse(only(game.log, "capture").stream().anyMatch(c -> c.contains("endgame")));
    }

    @Test
    void theCurrentLanguageIsLeftAlone() {
        Fake game = new Fake();
        runToEnd(new ShotRun(game, ShotRun.plan(RAIL, NAMES, "today"), -1, false), game);
        assertTrue(only(game.log, "lang").isEmpty(), game.log.toString());
        assertEquals(List.of("capture tr-01-today.png"), only(game.log, "capture"));
    }

    @Test
    void theLastFilesAreAwaitedBeforeTheMessage() {
        Fake game = new Fake();
        ShotRun run = new ShotRun(game, ShotRun.plan(RAIL, NAMES, "today"), 1, false);
        int ticks = 0;
        while (!game.log.contains("restore")) {
            run.tick();
            ticks++;
            assertTrue(ticks < 1000);
        }
        // the game is back at once; the message waits for the write
        assertFalse(game.done);
        run.tick();
        assertFalse(game.done);
        game.pending = 0;
        assertFalse(run.tick());
        assertTrue(game.done);
    }

    @Test
    void theNameIsFoundWhateverItsCase() {
        assertEquals(6, Shots.maskedAt("Hello anilKen!", "anilken", 0));
        assertEquals(-1, Shots.maskedAt("Hello there", "anilken", 0));
        assertEquals(-1, Shots.maskedAt("anything", "", 0));
    }
}
