package io.github.anilken.wynnlabs.dev;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The screenshot tool's sequence on a fake game: the order, the skips, the hovers, and everything put back at the end, on Esc and on an error. */
class ShotRunTest {

    /** The rail of the menu in miniature: tab numbers in rail order, and each number's name. */
    private static final int[] RAIL = {0, 3, 2, 1};
    private static final String[] NAMES = {"home", "today", "endgame", "settings"};

    /** A game that writes down what is done to it. A picture is two grabs (over black, over white); "capture" marks the last. */
    private static final class Fake implements ShotRun.Stage {
        final List<String> log = new ArrayList<>();
        /** Steps and hover parts with nothing to show. */
        final Set<String> missing = new HashSet<>();
        int lang = 2;
        int scale = 0;
        int backdrop = -1;
        String hovered;
        boolean flags;
        boolean hidden;
        boolean open;
        int pending;
        Integer doneCount;
        ShotRun.Stop stop;
        boolean done;
        RuntimeException failOn;
        final List<Map<String, String>> closes = new ArrayList<>();

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
        public void select(int tab) {
            if (hovered != null) {
                throw new AssertionError("a view selected while " + hovered + " is hovered");
            }
            log.add("select " + NAMES[tab]);
        }

        @Override
        public boolean act(String step) {
            log.add("act " + step);
            return !missing.contains(step);
        }

        @Override
        public boolean hover(String part) {
            log.add("hover " + part);
            if (part != null && missing.contains(part)) {
                return false;
            }
            hovered = part;
            return true;
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
        public void capture(String file, int pass, Map<String, String> close) {
            if (failOn != null && file.contains("endgame")) {
                throw failOn;
            }
            if (backdrop != pass) {
                throw new AssertionError("grabbed pass " + pass + " over backdrop " + backdrop);
            }
            log.add("grab " + file + " " + pass);
            if (pass == passes() - 1) {
                log.add("capture " + file);
                closes.add(close);
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
            if (++ticks > 100_000) {
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

    private static List<String> captured(Fake game) {
        return only(game.log, "capture ").stream().map(l -> l.substring("capture ".length())).toList();
    }

    @Test
    void planFollowsTheRailWithEachTabsViews() {
        List<ShotRun.Shot> plan = ShotRun.plan(RAIL, NAMES, "all");
        List<String> names = plan.stream().map(s -> s.name() + "-" + (s.state() == null ? "main" : s.state())).toList();
        // the rail's order, each tab first as it opens, then its views
        assertEquals(List.of("home-main", "home-slot-tip", "home-week-tip", "home-picker", "home-picker-weapon",
                "home-picker-import", "home-picker-tip", "home-results", "home-plan", "home-plan-tip", "home-plan-source-tip",
                "home-saved"), names.subList(0, 12));
        assertEquals("settings-main", names.get(12));
        assertEquals("settings-data", names.get(20));
        assertEquals("endgame-main", names.get(21));
        assertEquals("today-main", names.get(names.size() - 1));
        assertEquals(ShotRun.FIRST_WAIT, plan.get(0).ticks());
        // the same plan every time: the numbers are stable
        assertEquals(plan, ShotRun.plan(RAIL, NAMES, "all"));
        assertEquals(List.of("home", "home"), ShotRun.plan(RAIL, NAMES, "home").stream().limit(2).map(ShotRun.Shot::name).toList());
        ShotRun.Shot main = plan.get(0);
        assertEquals("en-01-home-main.png", main.file("en", 1));
        assertEquals("close/en-01-home-main-close-gear.png", main.close("en", 1, "gear"));
        assertEquals("tr-12-today-main.png", new ShotRun.Shot(1, "today", null, 1).file("tr", 12));
        assertEquals(List.of("gear", "week"), main.closes());
    }

    @Test
    void capturesInOrderThenPutsEverythingBack() {
        Fake game = new Fake();
        ShotRun run = new ShotRun(game, ShotRun.plan(RAIL, NAMES, "settings"), 1, false);
        runToEnd(run, game);
        // the language first, then the menu; each view selected and its steps taken before its capture
        assertEquals(List.of("flags true", "lang 1", "open"), game.log.subList(0, 3));
        assertEquals(List.of("en-01-settings-main.png", "en-02-settings-look.png", "en-03-settings-tips.png",
                "en-04-settings-glow.png", "en-05-settings-coach.png", "en-06-settings-quests.png", "en-07-settings-build.png",
                "en-08-settings-rules.png", "en-09-settings-data.png"), captured(game));
        int look = game.log.indexOf("act settings:cat:1");
        assertEquals(List.of("select settings", "backdrop 0", "act settings:cat:1"), game.log.subList(look - 2, look + 1));
        int first = game.log.indexOf("grab en-02-settings-look.png 0");
        assertEquals(List.of("grab en-02-settings-look.png 0", "backdrop 1", "grab en-02-settings-look.png 1",
                "capture en-02-settings-look.png"), game.log.subList(first, first + 4));
        // then the screen, the language and the flags back, and one message with the count
        int restore = game.log.indexOf("restore");
        assertEquals(List.of("restore", "lang 2", "flags false", "done 9"), game.log.subList(restore, game.log.size()));
        assertEquals(2, game.lang);
        assertFalse(game.flags);
        assertNull(game.stop);
        assertTrue(run.finished());
        assertFalse(run.tick());
    }

    @Test
    void viewsWithNothingToShowAreSkippedAndTheNumbersStayInARow() {
        Fake game = new Fake();
        // no result worked out, no plan, no saved builds, nothing drawn to hover for the week tile
        game.missing.addAll(List.of("open:results", "plan", "open:saved", "week"));
        ShotRun run = new ShotRun(game, ShotRun.plan(RAIL, NAMES, "home"), 1, false);
        runToEnd(run, game);
        assertEquals(List.of("en-01-home-main.png", "en-02-home-slot-tip.png", "en-03-home-picker.png",
                "en-04-home-picker-weapon.png", "en-05-home-picker-import.png", "en-06-home-picker-tip.png"), captured(game));
        assertEquals(6, game.doneCount);
        assertEquals(6, run.skipped());
        // a skipped view is never grabbed
        assertTrue(only(game.log, "grab").stream().noneMatch(g -> g.contains("results") || g.contains("plan") || g.contains("saved")));
        // the close-ups asked for beside the main view
        assertEquals(Map.of("gear", "close/en-01-home-main-close-gear.png", "week", "close/en-01-home-main-close-week.png"),
                game.closes.get(0));
    }

    @Test
    void aTooltipShotHoversThenMovesTheMouseAway() {
        Fake game = new Fake();
        ShotRun run = new ShotRun(game, ShotRun.plan(RAIL, NAMES, "home"), 1, false);
        runToEnd(run, game);
        int hover = game.log.indexOf("hover slot");
        int grab = game.log.indexOf("grab en-02-home-slot-tip.png 0");
        int away = game.log.indexOf("hover null");
        int nextSelect = game.log.subList(hover, game.log.size()).indexOf("select home") + hover;
        assertTrue(hover < grab, "hovered before the grab");
        assertTrue(grab < away && away < nextSelect, "the mouse away before the next view: " + game.log);
        assertNull(game.hovered);
        // a view without a hover never moves the mouse
        assertEquals(List.of("hover slot", "hover null", "hover week", "hover null", "hover pickrow", "hover null",
                "hover slot", "hover null", "hover source", "hover null"), only(game.log, "hover"));
    }

    @Test
    void aHoverWithNothingDrawnIsSkippedWithoutATooltip() {
        Fake game = new Fake();
        game.missing.add("slot");
        runToEnd(new ShotRun(game, ShotRun.plan(RAIL, NAMES, "home"), 1, false), game);
        assertTrue(captured(game).stream().noneMatch(c -> c.contains("slot-tip")));
        assertNull(game.hovered);
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
        assertEquals(List.of("grab tr-01-today-main.png 0"), only(game.log, "grab"));
        // the white backdrop drawn for a frame before the second grab
        run.tick();
        assertEquals(1, only(game.log, "grab").size());
        run.tick();
        assertEquals(List.of("tr-01-today-main.png"), captured(game));
    }

    @Test
    void escStopsMidHoverAndPutsEverythingBack() {
        Fake game = new Fake();
        ShotRun run = new ShotRun(game, ShotRun.plan(RAIL, NAMES, "all"), 1, 3, true);
        while (game.hovered == null) {
            assertTrue(run.tick());
        }
        assertTrue(game.hidden);
        assertEquals(1, game.lang);
        assertEquals(3, game.scale);
        run.escape();
        assertFalse(run.tick());
        assertEquals(ShotRun.Stop.ESC, game.stop);
        int away = game.log.lastIndexOf("hover null");
        assertEquals(List.of("hover null", "restore", "scale 0", "lang 2", "flags false", "done " + run.count() + " ESC"),
                game.log.subList(away, game.log.size()));
        assertNull(game.hovered);
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
        assertNull(game.hovered);
        assertTrue(game.log.contains("restore"));
        assertFalse(captured(game).stream().anyMatch(c -> c.contains("endgame")));
    }

    @Test
    void theCurrentLanguageIsLeftAlone() {
        Fake game = new Fake();
        runToEnd(new ShotRun(game, ShotRun.plan(RAIL, NAMES, "today"), -1, false), game);
        assertTrue(only(game.log, "lang").isEmpty(), game.log.toString());
        assertEquals(List.of("tr-01-today-main.png"), captured(game));
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
    void theIndexListsEveryPicture() {
        String html = Shots.index("Run", List.of("en-01-home-main.png", "en-02-home-slot-tip.png"),
                List.of("close/en-01-home-main-close-gear.png"));
        assertTrue(html.contains("<img src=\"en-02-home-slot-tip.png\""));
        assertTrue(html.contains("<figcaption>close/en-01-home-main-close-gear.png</figcaption>"));
        assertTrue(html.contains("(2 + 1 close-ups)"));
    }
}
