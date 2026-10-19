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

    /** A game that writes down what is done to it. A picture is two grabs (over black, over white); "capture" marks the last. */
    private static final class Fake implements ShotRun.Stage {
        final List<String> log = new ArrayList<>();
        int lang = 2;
        int scale = 0;
        int backdrop = -1;
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
        public int scale() {
            return scale;
        }

        @Override
        public void scale(int s) {
            log.add("scale " + s);
            scale = s;
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
        public int passes() {
            return 2;
        }

        @Override
        public void backdrop(int pass) {
            log.add("backdrop " + pass);
            backdrop = pass;
        }

        @Override
        public void capture(String file, int pass) {
            if (failOn != null && file.contains("endgame")) {
                throw failOn;
            }
            if (backdrop != pass) {
                throw new AssertionError("grabbed pass " + pass + " over backdrop " + backdrop);
            }
            log.add("grab " + file + " " + pass);
            if (pass == passes() - 1) {
                log.add("capture " + file);
                pending++;
            }
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
        // each picture: selected, over black, grabbed, over white, grabbed again
        int first = game.log.indexOf("select home-picker");
        assertEquals(List.of("select home-picker", "backdrop 0", "grab en-02-home-picker.png 0", "backdrop 1",
                "grab en-02-home-picker.png 1", "capture en-02-home-picker.png"), game.log.subList(first, first + 6));
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
            assertTrue(only(game.log, "grab").isEmpty(), "grabbed before the frames settled");
        }
        run.tick();
        assertEquals(List.of("grab tr-01-today.png 0"), only(game.log, "grab"));
        // the white backdrop drawn for a frame before the second grab
        run.tick();
        assertEquals(1, only(game.log, "grab").size());
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
        ShotRun run = new ShotRun(game, ShotRun.plan(RAIL, NAMES, "all"), 1, 3, true);
        for (int i = 0; i < 40; i++) {
            run.tick();
        }
        assertTrue(game.hidden);
        assertEquals(1, game.lang);
        assertEquals(3, game.scale);
        run.escape();
        assertFalse(run.tick());
        assertEquals(ShotRun.Stop.ESC, game.stop);
        int restore = game.log.indexOf("restore");
        assertEquals(List.of("restore", "scale 0", "lang 2", "flags false", "done " + run.count() + " ESC"),
                game.log.subList(restore, game.log.size()));
        assertEquals(2, game.lang);
        assertEquals(0, game.scale);
        assertFalse(game.flags);
        assertFalse(game.hidden);
        assertFalse(run.tick());
        assertEquals(1, only(game.log, "restore").size(), "put back once");
    }

    @Test
    void theSizeIsSetBeforeTheMenuOpensAndPutBack() {
        Fake game = new Fake();
        runToEnd(new ShotRun(game, ShotRun.plan(RAIL, NAMES, "today"), -1, 4, false), game);
        assertEquals(List.of("flags true", "scale 4", "open"), game.log.subList(0, 3));
        assertTrue(game.log.indexOf("scale 0") > game.log.indexOf("restore"));
        assertEquals(0, game.scale);
        // the size already on: nothing to change, nothing to put back
        Fake same = new Fake();
        same.scale = 3;
        runToEnd(new ShotRun(same, ShotRun.plan(RAIL, NAMES, "today"), -1, 3, false), same);
        assertTrue(only(same.log, "scale").isEmpty(), same.log.toString());
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

    @Test
    void theSizeWordsAreRead() {
        String[] bad = new String[1];
        Shots.Options o = Shots.parse("tr x4 endgame hidename", bad);
        assertEquals(new Shots.Options(2, "endgame", 4, true), o);
        assertEquals(new Shots.Options(1, "all", -1, false), Shots.parse("", bad));
        assertNull(Shots.parse("x5", bad));
        assertEquals("x5", bad[0]);
    }
}
