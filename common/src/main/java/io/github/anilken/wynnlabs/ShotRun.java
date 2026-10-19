package io.github.anilken.wynnlabs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The screenshot tool's sequence ({@code /wynnlabs shots}), one client tick at a time so the game
 * keeps drawing: the menu language switched, the mod's own menu opened, each tab (and a few views
 * inside it) selected, a few frames waited, the frame saved; then everything put back as it was.
 * Esc, a menu closed by anything else or an error put everything back at once.
 *
 * <p>Knows nothing of Minecraft: a {@link Stage} does the work ({@link Shots} in the game, a fake
 * in the tests). Nothing here opens a game menu, clicks, or sends anything to the server.
 */
final class ShotRun {

    /** One picture: the tab's number, its name in the file, a view inside it (null: the tab as it opens), the ticks to wait first. */
    record Shot(int tab, String name, String state, int ticks) {
        /** {@code <lang>-<nn>-<tab>[-<state>].png}. */
        String file(String lang, int n) {
            return String.format(Locale.ROOT, "%s-%02d-%s%s.png", lang, n, name, state == null ? "" : "-" + state);
        }
    }

    /** What the sequence does in the game. Every call happens on the client thread, in a tick. */
    interface Stage {
        /** The menu language now (the rail's picker: 0 follows the game, 1 English, 2 Turkish). */
        int lang();

        void lang(int pick);

        /** The language the menu speaks now, for the file names ("en", "tr"). */
        String code();

        /** The render flags while capturing (no mouse hover, no HUD; {@code hideName}: the player's name masked), or off again. */
        void flags(boolean on, boolean hideName);

        /** Remembers the screen and the menu's views as they are, then opens the mod's menu. */
        void open();

        /** Whether the menu opened is still the one on screen. */
        boolean showing();

        /** Whether a view can be captured now (the plan view only when there is a plan). */
        boolean has(String state);

        void select(int tab, String state);

        /** Saves the frame drawn last as {@code file} (in the run's folder); the write finishes later. */
        void capture(String file);

        /** Captures not written yet. */
        int pending();

        /** The screen and the menu's views back as {@link #open} found them. */
        void restore();

        /** The end: {@code count} captures, {@code stop} null when every one was taken, else why it stopped. */
        void done(int count, Stop stop, String detail);
    }

    enum Stop { ESC, CLOSED, ERROR }

    /** The views captured after a tab, by the tab's name. */
    static final Map<String, List<String>> STATES = Map.of(
            "home", List.of("picker", "plan"),
            "settings", List.of("about"),
            "endgame", List.of("pools"));

    /** Ticks to wait before a capture: the first one (the pack's textures, icons) longer, then the heavier tabs. */
    static final int FIRST_WAIT = 30;
    static final int SLOW_WAIT = 20;
    static final int WAIT = 10;
    /** At most this long for the last files to be written (ticks). */
    static final int DRAIN = 200;

    private static final java.util.Set<String> SLOW = java.util.Set.of("craft", "tree", "endgame", "bank", "discoveries");

    /**
     * The pictures in rail order: every tab ({@code only} null or "all") or one tab by name, each
     * followed by its views. {@code rail}: the tabs' numbers in rail order; {@code names}: each
     * tab number's name.
     */
    static List<Shot> plan(int[] rail, String[] names, String only) {
        List<Shot> out = new ArrayList<>();
        for (int t : rail) {
            String name = names[t];
            if (only != null && !only.equals("all") && !only.equals(name)) {
                continue;
            }
            int wait = out.isEmpty() ? FIRST_WAIT : SLOW.contains(name) ? SLOW_WAIT : WAIT;
            out.add(new Shot(t, name, null, wait));
            for (String s : STATES.getOrDefault(name, List.of())) {
                out.add(new Shot(t, name, s, WAIT));
            }
        }
        return out;
    }

    private enum Phase { START, ENTER, WAIT, DRAIN, DONE }

    private final Stage stage;
    private final List<Shot> shots;
    /** The language to switch to (as {@link Stage#lang}), or -1 to keep the one on. */
    private final int lang;
    private final boolean hideName;

    private Phase phase = Phase.START;
    private int next;
    private int left;
    private int count;
    private String code = "en";
    private boolean escaped;

    // what to put back
    private int savedLang;
    private boolean langChanged;
    private boolean flagsOn;
    private boolean opened;
    private boolean putBack;

    ShotRun(Stage stage, List<Shot> shots, int lang, boolean hideName) {
        this.stage = stage;
        this.shots = List.copyOf(shots);
        this.lang = lang;
        this.hideName = hideName;
    }

    /** Esc while capturing: everything goes back on the next tick. */
    void escape() {
        escaped = true;
    }

    boolean finished() {
        return phase == Phase.DONE;
    }

    /** Captures taken so far. */
    int count() {
        return count;
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
                        capture();
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
        flagsOn = true;
        stage.flags(true, hideName);
        if (lang >= 0 && lang != savedLang) {
            langChanged = true;
            stage.lang(lang);
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
        // a view that is not there now (no plan): skipped, the numbers stay in a row
        while (next < shots.size() && shots.get(next).state() != null && !stage.has(shots.get(next).state())) {
            next++;
        }
        if (next >= shots.size()) {
            finishCaptures();
            return;
        }
        Shot s = shots.get(next);
        stage.select(s.tab(), s.state());
        left = Math.max(1, s.ticks());
        phase = Phase.WAIT;
    }

    private void capture() {
        Shot s = shots.get(next++);
        stage.capture(s.file(code, ++count));
        if (next >= shots.size()) {
            finishCaptures();
        } else {
            phase = Phase.ENTER;
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

    /** The screen, the language and the render flags back; each on its own, so one failing leaves the others. */
    private void putBack() {
        if (putBack) {
            return;
        }
        putBack = true;
        if (opened) {
            try {
                stage.restore();
            } catch (Throwable t) {
                System.out.println("[WynnLabs] screenshots: could not restore the screen: " + t);
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
