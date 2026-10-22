package io.github.anilken.wynnlabs.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The screenshot tool's sequence ({@code /wynnlabs shots}, dev builds only), one client tick at a
 * time so the game keeps drawing: the menu language (and size) switched, the mod's own menu opened,
 * then for each picture its tab selected, its steps taken (a filter, a sub-page, a window opened:
 * {@link Stage#act}), an element hovered for its tooltip when it has one, a few frames waited and
 * the frame saved; then everything put back as it was. A step or a hover with nothing to show skips
 * its picture, and the numbers stay in a row. Esc, a menu closed by anything else or an error put
 * everything back at once.
 *
 * <p>Each picture is taken in {@link Stage#passes} passes, one per backdrop behind the menu (black,
 * then white): the two frames together give every pixel its own alpha, so the saved picture is the
 * mod's window alone on transparency ({@link ShotCrop}).
 *
 * <p>Knows nothing of Minecraft: a {@link Stage} does the work ({@link Shots} in the game, a fake
 * in the tests). Nothing here opens a game menu, clicks a slot, or sends anything to the server.
 */
final class ShotRun {

    /**
     * One picture: the tab's number and name, its view's name (null: the tab as it opens, "main"
     * in the file), the steps that make the view, the part to hover for a tooltip (null: none), the
     * parts saved as close-ups beside it, and the ticks to wait before the frame is taken.
     */
    record Shot(int tab, String name, String state, int ticks, List<String> steps, String hover, List<String> closes) {
        Shot(int tab, String name, String state, int ticks) {
            this(tab, name, state, ticks, List.of(), null, List.of());
        }

        /** {@code <lang>-<nn>-<tab>-<state>.png}. */
        String file(String lang, int n) {
            return String.format(Locale.ROOT, "%s-%02d-%s-%s.png", lang, n, name, state == null ? "main" : state);
        }

        /** A close-up of {@code part} beside it: {@code close/<lang>-<nn>-<tab>-<state>-close-<part>.png}. */
        String close(String lang, int n, String part) {
            String f = file(lang, n);
            return "close/" + f.substring(0, f.length() - 4) + "-close-" + part + ".png";
        }
    }

    /** What the sequence does in the game. Every call happens on the client thread, in a tick. */
    interface Stage {
        /** The menu language now (the rail's picker: 0 follows the game, 1 English, 2 Turkish). */
        int lang();

        void lang(int pick);

        /** The menu scale setting now (0: the game's GUI scale, else 2 to 4). */
        int scale();

        void scale(int scale);

        /** The language the menu speaks now, for the file names ("en", "tr"). */
        String code();

        /** The render flags while capturing (no world, HUD or mouse hover; {@code hideName}: the player's name masked), or off again. */
        void flags(boolean on, boolean hideName);

        /** Remembers the screen and the menu's views as they are, then opens the mod's menu. */
        void open();

        /** Whether the menu opened is still the one on screen. */
        boolean showing();

        /** Selects the tab as it opens: every view inside the menu back to its first state, no hover. */
        void select(int tab);

        /** One step towards a view (a filter, a sub-page, a window); false when there is nothing to show. */
        boolean act(String step);

        /** Hovers {@code part} for its tooltip; false when it is not drawn now. Null: the mouse away again. */
        boolean hover(String part);

        /** Frames taken per picture (one per backdrop). */
        int passes();

        /** The backdrop drawn behind the menu for pass {@code pass} (0 black, 1 white). */
        void backdrop(int pass);

        /**
         * Grabs the frame drawn last for pass {@code pass} of {@code file}; after the last pass the
         * picture and its close-ups ({@code closes}: part name → file) are written (later).
         */
        void capture(String file, int pass, java.util.Map<String, String> closes);

        /** Pictures not written yet. */
        int pending();

        /** The screen and the menu's views back as {@link #open} found them. */
        void restore();

        /** The end: {@code count} pictures, {@code stop} null when every one was taken, else why it stopped. */
        void done(int count, Stop stop, String detail);
    }

    enum Stop { ESC, CLOSED, ERROR }

    /** Ticks to wait before a capture: the first one (the pack's textures, icons) longer, then the heavier tabs. */
    static final int FIRST_WAIT = 30;
    static final int SLOW_WAIT = 20;
    static final int WAIT = 8;
    /** Ticks between steps (a frame drawn with the last one, so the next finds what it drew). */
    static final int STEP_WAIT = 3;
    /** Ticks after the hover, so the tooltip is drawn. */
    static final int HOVER_WAIT = 3;
    /** Ticks after a backdrop change before the next pass: a frame drawn with it. */
    static final int BACKDROP_WAIT = 2;
    /** At most this long for the last files to be written (ticks). */
    static final int DRAIN = 400;

    private static final java.util.Set<String> SLOW = java.util.Set.of("craft", "tree", "endgame", "bank", "discoveries");

    /** The quest list's filters after the first, as the screen names them. */
    static final String[] QUEST_FILTERS = {"SHORT", "NEAR", "XP", "ITEM", "MINI", "ABOVE", "DONE"};
    /** The settings' pages after About, in their order (the screen's category numbers 1 …). */
    static final String[] SETTING_PAGES = {"look", "tips", "glow", "coach", "quests", "build", "rules", "data"};
    /** The bank's filter pills after All (the screen's numbers 1 …). */
    static final String[] BANK_FILTERS = {"sell", "quest", "prof", "gear"};
    /** Pool regions captured on each of the Pools page's sub-tabs. */
    static final int POOL_REGIONS = 4;

    /** Every view of one tab, in a stable order; the first is the tab as it opens. */
    static List<Shot> views(int t, String name, int first) {
        List<Shot> out = new ArrayList<>();
        int w = first > 0 ? first : SLOW.contains(name) ? SLOW_WAIT : WAIT;
        switch (name) {
            case "home" -> {
                out.add(shot(t, name, null, w, List.of(), null, List.of("gear", "week")));
                out.add(shot(t, name, "slot-tip", WAIT, List.of(), "slot", List.of()));
                out.add(shot(t, name, "week-tip", WAIT, List.of(), "week", List.of()));
                out.add(shot(t, name, "picker", WAIT, List.of("open:picker"), null, List.of()));
                out.add(shot(t, name, "picker-weapon", WAIT, List.of("open:picker", "click:type:1"), null, List.of()));
                out.add(shot(t, name, "picker-tip", WAIT, List.of("open:picker"), "pickrow", List.of()));
                out.add(shot(t, name, "results", WAIT, List.of("open:results"), null, List.of("results")));
                out.add(shot(t, name, "plan", WAIT, List.of("plan"), null, List.of("gear")));
                out.add(shot(t, name, "plan-tip", WAIT, List.of("plan"), "slot", List.of()));
                out.add(shot(t, name, "plan-source-tip", WAIT, List.of("plan"), "source", List.of()));
                out.add(shot(t, name, "saved", WAIT, List.of("open:saved"), null, List.of()));
            }
            case "craft" -> {
                out.add(shot(t, name, null, w, List.of(), null, List.of()));
                out.add(shot(t, name, "bottom", WAIT, List.of("scroll:craft"), null, List.of()));
                out.add(shot(t, name, "suggestions", WAIT, List.of("craft:suggestions"), null, List.of()));
            }
            case "tree" -> {
                out.add(shot(t, name, null, w, List.of(), null, List.of()));
                out.add(shot(t, name, "end", WAIT, List.of("scroll:tree"), null, List.of()));
            }
            case "endgame" -> {
                out.add(shot(t, name, null, w, List.of(), null, List.of()));
                out.add(shot(t, name, "pool-filter", WAIT, List.of("endgame:poolfilter"), null, List.of()));
                out.add(shot(t, name, "pools", WAIT, List.of("endgame:pools:0"), null, List.of("pools")));
                out.add(shot(t, name, "pools-tip", WAIT, List.of("endgame:pools:0"), "poolrow", List.of()));
                for (int i = 1; i <= POOL_REGIONS; i++) {
                    out.add(shot(t, name, "pools-raid-" + i, WAIT, List.of("endgame:pools:0", "endgame:region:" + i), null, List.of()));
                }
                out.add(shot(t, name, "pools-camps", WAIT, List.of("endgame:pools:1"), null, List.of("pools")));
                for (int i = 1; i <= POOL_REGIONS; i++) {
                    out.add(shot(t, name, "pools-camp-" + i, WAIT, List.of("endgame:pools:1", "endgame:region:" + i), null, List.of()));
                }
            }
            case "quests" -> {
                out.add(shot(t, name, null, w, List.of(), null, List.of()));
                out.add(shot(t, name, "tip", WAIT, List.of(), "quest", List.of()));
                for (int i = 1; i <= 3; i++) {
                    out.add(shot(t, name, "detail-" + (i + 1), WAIT, List.of("quest:tab:" + i), null, List.of()));
                }
                for (String f : QUEST_FILTERS) {
                    out.add(shot(t, name, "filter-" + f.toLowerCase(Locale.ROOT), WAIT, List.of("quest:filter:" + f), null, List.of()));
                }
            }
            case "discoveries" -> {
                out.add(shot(t, name, null, w, List.of(), null, List.of()));
                out.add(shot(t, name, "tip", WAIT, List.of(), "disc", List.of()));
            }
            case "bank" -> {
                out.add(shot(t, name, null, w, List.of(), null, List.of("banklist")));
                out.add(shot(t, name, "tip", WAIT, List.of(), "bankitem", List.of()));
                for (int i = 0; i < BANK_FILTERS.length; i++) {
                    out.add(shot(t, name, "filter-" + BANK_FILTERS[i], WAIT, List.of("bank:filter:" + (i + 1)), null, List.of()));
                }
                out.add(shot(t, name, "search", WAIT, List.of("bank:search"), null, List.of()));
            }
            case "settings" -> {
                out.add(shot(t, name, null, w, List.of(), null, List.of()));
                for (int i = 0; i < SETTING_PAGES.length; i++) {
                    out.add(shot(t, name, SETTING_PAGES[i], WAIT, List.of("settings:cat:" + (i + 1)), null, List.of()));
                }
            }
            default -> out.add(shot(t, name, null, w, List.of(), null, List.of()));
        }
        return out;
    }

    private static Shot shot(int t, String name, String state, int ticks, List<String> steps, String hover, List<String> closes) {
        return new Shot(t, name, state, ticks, steps, hover, closes);
    }

    /**
     * The pictures in rail order: every tab ({@code only} null or "all") or one tab by name, each
     * with its views. {@code rail}: the tabs' numbers in rail order; {@code names}: each tab number's name.
     */
    static List<Shot> plan(int[] rail, String[] names, String only) {
        List<Shot> out = new ArrayList<>();
        for (int t : rail) {
            String name = names[t];
            if (only != null && !only.equals("all") && !only.equals(name)) {
                continue;
            }
            out.addAll(views(t, name, out.isEmpty() ? FIRST_WAIT : 0));
        }
        return out;
    }

    private enum Phase { START, ENTER, WAIT, DRAIN, DONE }

    /** What the wait in {@link Phase#WAIT} leads to. */
    private enum Next { STEP, HOVER, GRAB }

    private final Stage stage;
    private final List<Shot> shots;
    /** The language to switch to (as {@link Stage#lang}), or -1 to keep the one on. */
    private final int lang;
    /** The menu scale to draw at (2 to 4), or -1 to keep the one on. */
    private final int scale;
    private final boolean hideName;

    private Phase phase = Phase.START;
    private Next then;
    private int next;
    private int step;
    private int pass;
    private int left;
    private int count;
    private int skipped;
    private boolean hovering;
    private String code = "en";
    private boolean escaped;

    // what to put back
    private int savedLang;
    private boolean langChanged;
    private int savedScale;
    private boolean scaleChanged;
    private boolean flagsOn;
    private boolean opened;
    private boolean putBack;

    ShotRun(Stage stage, List<Shot> shots, int lang, boolean hideName) {
        this(stage, shots, lang, -1, hideName);
    }

    ShotRun(Stage stage, List<Shot> shots, int lang, int scale, boolean hideName) {
        this.stage = stage;
        this.shots = List.copyOf(shots);
        this.lang = lang;
        this.scale = scale;
        this.hideName = hideName;
    }

    /** Esc while capturing: everything goes back on the next tick. */
    void escape() {
        escaped = true;
    }

    boolean finished() {
        return phase == Phase.DONE;
    }

    /** Pictures taken so far. */
    int count() {
        return count;
    }

    /** Views skipped so far: nothing to show for them. */
    int skipped() {
        return skipped;
    }

    /** One client tick; false once the run is over. */
    boolean tick() {
        if (phase == Phase.DONE) {
            return false;
        }
        try {
            switch (phase) {
                case START -> start();
                case ENTER -> {
                    if (guard()) {
                        enter();
                    }
                }
                case WAIT -> {
                    if (guard() && --left <= 0) {
                        advance();
                    }
                }
                case DRAIN -> {
                    if (stage.pending() <= 0 || --left <= 0) {
                        phase = Phase.DONE;
                        stage.done(count, null, null);
                    }
                }
                default -> { }
            }
        } catch (Throwable t) {
            stop(Stop.ERROR, t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage()));
        }
        return phase != Phase.DONE;
    }

    private void start() {
        savedLang = stage.lang();
        savedScale = stage.scale();
        flagsOn = true;
        stage.flags(true, hideName);
        if (lang >= 0 && lang != savedLang) {
            langChanged = true;
            stage.lang(lang);
        }
        if (scale >= 0 && scale != savedScale) {
            scaleChanged = true;
            stage.scale(scale);
        }
        code = stage.code();
        opened = true;
        stage.open();
        if (shots.isEmpty()) {
            finishCaptures();
        } else {
            phase = Phase.ENTER;
        }
    }

    /** Esc or a menu closed by something else: everything back, and false. */
    private boolean guard() {
        if (escaped) {
            stop(Stop.ESC, null);
            return false;
        }
        if (!stage.showing()) {
            stop(Stop.CLOSED, null);
            return false;
        }
        return true;
    }

    private void enter() {
        if (next >= shots.size()) {
            finishCaptures();
            return;
        }
        Shot s = shots.get(next);
        stage.select(s.tab());
        hovering = false;
        step = 0;
        pass = 0;
        stage.backdrop(0);
        phase = Phase.WAIT;
        if (s.steps().isEmpty()) {
            afterSteps(s);
        } else {
            then = Next.STEP;
            left = STEP_WAIT;
        }
    }

    private void afterSteps(Shot s) {
        then = s.hover() != null ? Next.HOVER : Next.GRAB;
        left = Math.max(1, s.ticks());
    }

    private void advance() {
        Shot s = shots.get(next);
        switch (then) {
            case STEP -> {
                if (!stage.act(s.steps().get(step))) {
                    skip();
                    return;
                }
                if (++step < s.steps().size()) {
                    left = STEP_WAIT;
                } else {
                    afterSteps(s);
                }
            }
            case HOVER -> {
                if (!stage.hover(s.hover())) {
                    skip();
                    return;
                }
                hovering = true;
                then = Next.GRAB;
                left = HOVER_WAIT;
            }
            case GRAB -> grab(s);
            default -> { }
        }
    }

    /** Nothing to show for this view: on to the next, no number taken. */
    private void skip() {
        skipped++;
        unhover();
        next++;
        phase = Phase.ENTER;
    }

    private void grab(Shot s) {
        if (pass == 0) {
            count++;
        }
        java.util.Map<String, String> closes = new java.util.LinkedHashMap<>();
        for (String part : s.closes()) {
            closes.put(part, s.close(code, count, part));
        }
        stage.capture(s.file(code, count), pass, closes);
        if (++pass < Math.max(1, stage.passes())) {
            stage.backdrop(pass);               // the same view on the next backdrop
            left = BACKDROP_WAIT;
            return;
        }
        unhover();
        next++;
        if (next >= shots.size()) {
            finishCaptures();
        } else {
            phase = Phase.ENTER;
        }
    }

    /** The mouse away from what it hovered, so the next view starts without a tooltip. */
    private void unhover() {
        if (hovering) {
            hovering = false;
            stage.hover(null);
        }
    }

    /** Every picture taken: the game back at once, then the last files written. */
    private void finishCaptures() {
        putBack();
        left = DRAIN;
        phase = Phase.DRAIN;
    }

    private void stop(Stop why, String detail) {
        putBack();
        phase = Phase.DONE;
        try {
            stage.done(count, why, detail);
        } catch (Throwable ignored) {
            // the message only
        }
    }

    /** The hover, the screen, the size, the language and the render flags back; each on its own, so one failing leaves the others. */
    private void putBack() {
        if (putBack) {
            return;
        }
        putBack = true;
        try {
            unhover();
        } catch (Throwable t) {
            System.out.println("[WynnLabs] screenshots: could not move the mouse back: " + t);
        }
        if (opened) {
            try {
                stage.restore();
            } catch (Throwable t) {
                System.out.println("[WynnLabs] screenshots: could not restore the screen: " + t);
            }
        }
        if (scaleChanged) {
            try {
                stage.scale(savedScale);
            } catch (Throwable t) {
                System.out.println("[WynnLabs] screenshots: could not restore the menu scale: " + t);
            }
        }
        if (langChanged) {
            try {
                stage.lang(savedLang);
            } catch (Throwable t) {
                System.out.println("[WynnLabs] screenshots: could not restore the language: " + t);
            }
        }
        if (flagsOn) {
            try {
                stage.flags(false, false);
            } catch (Throwable t) {
                System.out.println("[WynnLabs] screenshots: could not restore the render flags: " + t);
            }
        }
    }
}
