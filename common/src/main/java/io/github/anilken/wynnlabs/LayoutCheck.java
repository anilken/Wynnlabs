package io.github.anilken.wynnlabs;

import org.joml.Matrix3x2f;
import org.joml.Vector2f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * For the no-game preview only: the bounds of everything the mod's screens draw in a frame (text
 * and sprites, in GUI pixels after the pose, clipped to the scissor in force), so the preview can
 * fail when something leaves the window or two click areas overlap. Off in the game.
 */
public final class LayoutCheck {

    /** Something drawn: its box in GUI pixels and what it was (text or sprite). */
    record Drawn(float x0, float y0, float x1, float y1, String what, boolean text) {}

    /** A click area: its box and what it does. */
    record Area(int x, int y, int w, int h, String what) {}

    private static volatile boolean on;
    private static final List<Drawn> DRAWN = new ArrayList<>();
    private static final Deque<int[]> SCISSOR = new ArrayDeque<>();
    /** The boxes the screen says it draws in (its frame, a side panel): anything else is out of place. */
    private static final List<int[]> FRAMES = new ArrayList<>();
    /** Where text belongs (the parchment, the rail's planks): text elsewhere sits on the frame. */
    private static final List<int[]> TEXT_AREAS = new ArrayList<>();

    private LayoutCheck() {}

    public static boolean on() {
        return on;
    }

    /** Starts recording a frame (the preview calls this before it draws a screen). */
    static synchronized void start() {
        DRAWN.clear();
        SCISSOR.clear();
        FRAMES.clear();
        TEXT_AREAS.clear();
        PARTS.clear();
        on = true;
    }

    /** Stops recording; what was drawn since {@link #start}. */
    static synchronized List<Drawn> stop() {
        on = false;
        return new ArrayList<>(DRAWN);
    }

    public static synchronized void scissor(Matrix3x2f pose, int x0, int y0, int x1, int y1) {
        if (on) {
            Vector2f a = pose.transformPosition(x0, y0, new Vector2f());
            Vector2f b = pose.transformPosition(x1, y1, new Vector2f());
            SCISSOR.push(new int[] {Math.round(Math.min(a.x, b.x)), Math.round(Math.min(a.y, b.y)),
                Math.round(Math.max(a.x, b.x)), Math.round(Math.max(a.y, b.y))});
        }
    }

    /** A screen's own box (its frame or a side panel), in GUI pixels; called while drawing. */
    public static synchronized void frame(Matrix3x2f pose, int x, int y, int w, int h) {
        if (on) {
            FRAMES.add(box(pose, x, y, w, h));
        }
    }

    /** A box under this pose, in GUI pixels: {x0, y0, x1, y1}. */
    private static int[] box(Matrix3x2f pose, int x, int y, int w, int h) {
        Vector2f a = pose.transformPosition(x, y, new Vector2f());
        Vector2f b = pose.transformPosition(x + w, y + h, new Vector2f());
        return new int[] {Math.round(Math.min(a.x, b.x)), Math.round(Math.min(a.y, b.y)),
            Math.round(Math.max(a.x, b.x)), Math.round(Math.max(a.y, b.y))};
    }

    /** A part of a panel that must not overlap another owner's part (a slot, a pill, a name, the build bar). */
    record Part(int x0, int y0, int x1, int y1, String owner, String what) {}

    private static final List<Part> PARTS = new ArrayList<>();

    /** Registers a part under this pose for {@link #partOverlaps}; parts of one owner may overlap each other. */
    public static synchronized void part(Matrix3x2f pose, int x, int y, int w, int h, String owner, String what) {
        if (on && w > 0 && h > 0) {
            int[] b = box(pose, x, y, w, h);
            PARTS.add(new Part(b[0], b[1], b[2], b[3], owner, what));
        }
    }

    static synchronized List<Part> parts() {
        return new ArrayList<>(PARTS);
    }

    /** Parts of different owners drawn over each other (more than a pixel each way). */
    static List<String> partOverlaps(List<Part> parts) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            Part a = parts.get(i);
            for (int j = i + 1; j < parts.size(); j++) {
                Part b = parts.get(j);
                if (a.owner().equals(b.owner())) {
                    continue;
                }
                int ox = Math.min(a.x1(), b.x1()) - Math.max(a.x0(), b.x0());
                int oy = Math.min(a.y1(), b.y1()) - Math.max(a.y0(), b.y0());
                if (ox > 1 && oy > 1) {
                    out.add(a.what() + " and " + b.what() + " overlap at " + Math.max(a.x0(), b.x0()) + "," + Math.max(a.y0(), b.y0()));
                }
            }
        }
        return out;
    }

    /**
     * A window drawn over everything under this box (a modal over the menu): what was recorded
     * there so far is hidden under it and left out, so the window's text is not read as lying
     * over the menu's.
     */
    public static synchronized void cover(Matrix3x2f pose, int x, int y, int w, int h) {
        if (on) {
            int[] b = box(pose, x, y, w, h);
            DRAWN.removeIf(d -> d.x0() < b[2] && d.x1() > b[0] && d.y0() < b[3] && d.y1() > b[1]);
            PARTS.removeIf(p -> p.x0() < b[2] && p.x1() > b[0] && p.y0() < b[3] && p.y1() > b[1]);
        }
    }

    /** A surface text may be drawn on (parchment, planks), in GUI pixels; called while drawing. */
    public static synchronized void textArea(Matrix3x2f pose, int x, int y, int w, int h) {
        if (on) {
            TEXT_AREAS.add(box(pose, x, y, w, h));
        }
    }

    static synchronized List<int[]> textAreas() {
        return new ArrayList<>(TEXT_AREAS);
    }

    /** Text not on any declared surface (nothing when none was declared): it sits on the wooden frame. */
    static List<String> textOffSurface(List<Drawn> drawn, List<int[]> areas) {
        List<String> out = new ArrayList<>();
        if (areas.isEmpty()) {
            return out;
        }
        for (Drawn d : drawn) {
            if (!d.text() || d.what().isBlank()) {
                continue;
            }
            boolean in = false;
            for (int[] a : areas) {
                in |= d.x0() >= a[0] - 1 && d.y0() >= a[1] - 1 && d.x1() <= a[2] + 1 && d.y1() <= a[3] + 1;
            }
            if (!in) {
                out.add("\"" + d.what() + "\" at " + Math.round(d.x0()) + "," + Math.round(d.y0()) + "–"
                        + Math.round(d.x1()) + "," + Math.round(d.y1()) + " runs off the page");
            }
        }
        return out;
    }

    static synchronized List<int[]> frames() {
        return new ArrayList<>(FRAMES);
    }

    public static synchronized void unscissor() {
        if (on && !SCISSOR.isEmpty()) {
            SCISSOR.pop();
        }
    }

    /** A box drawn under this pose; dropped when the scissor in force hides it all. */
    public static synchronized void record(Matrix3x2f pose, float x, float y, float w, float h, String what, boolean text) {
        if (!on || w <= 0 || h <= 0 || what.startsWith("hud/")) {
            return;                                     // the game's HUD under the screen is not ours
        }
        Vector2f a = pose.transformPosition(x, y, new Vector2f());
        Vector2f b = pose.transformPosition(x + w, y + h, new Vector2f());
        float x0 = Math.min(a.x, b.x);
        float y0 = Math.min(a.y, b.y);
        float x1 = Math.max(a.x, b.x);
        float y1 = Math.max(a.y, b.y);
        int[] s = SCISSOR.peek();
        if (s != null) {
            x0 = Math.max(x0, s[0]);
            y0 = Math.max(y0, s[1]);
            x1 = Math.min(x1, s[2]);
            y1 = Math.min(y1, s[3]);
            if (x1 <= x0 || y1 <= y0) {
                return;
            }
        }
        DRAWN.add(new Drawn(x0, y0, x1, y1, what, text));
    }

    /** What was drawn outside a {@code w}×{@code h} window (half a pixel of slack for rounding). */
    static List<String> outside(List<Drawn> drawn, int w, int h) {
        List<String> out = new ArrayList<>();
        for (Drawn d : drawn) {
            if (d.x0() < -0.5f || d.y0() < -0.5f || d.x1() > w + 0.5f || d.y1() > h + 0.5f) {
                out.add("\"" + d.what() + "\" at " + Math.round(d.x0()) + "," + Math.round(d.y0()) + "–"
                        + Math.round(d.x1()) + "," + Math.round(d.y1()) + " leaves " + w + "×" + h);
            }
        }
        return out;
    }

    /** What was drawn outside every frame the screen declared (nothing when it declared none). */
    static List<String> outsideFrames(List<Drawn> drawn, List<int[]> frames) {
        List<String> out = new ArrayList<>();
        if (frames.isEmpty()) {
            return out;
        }
        for (Drawn d : drawn) {
            boolean in = false;
            for (int[] f : frames) {
                in |= d.x0() >= f[0] - 0.5f && d.y0() >= f[1] - 0.5f && d.x1() <= f[2] + 0.5f && d.y1() <= f[3] + 0.5f;
            }
            if (!in) {
                out.add("\"" + d.what() + "\" at " + Math.round(d.x0()) + "," + Math.round(d.y0()) + "–"
                        + Math.round(d.x1()) + "," + Math.round(d.y1()) + " leaves its frame");
            }
        }
        return out;
    }

    /** Pairs of texts drawn over each other (more than a pixel each way). */
    static List<String> textOverlaps(List<Drawn> drawn) {
        List<Drawn> texts = new ArrayList<>();
        for (Drawn d : drawn) {
            if (d.text() && !d.what().isBlank()) {
                boolean same = false;
                for (Drawn t : texts) {
                    same |= t.what().equals(d.what()) && Math.abs(t.x0() - d.x0()) < 1 && Math.abs(t.y0() - d.y0()) < 1;
                }
                if (!same) {
                    texts.add(d);
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            Drawn a = texts.get(i);
            for (int j = i + 1; j < texts.size(); j++) {
                Drawn b = texts.get(j);
                float ox = Math.min(a.x1(), b.x1()) - Math.max(a.x0(), b.x0());
                float oy = Math.min(a.y1(), b.y1()) - Math.max(a.y0(), b.y0());
                if (ox > 1 && oy > 1) {
                    out.add("\"" + a.what() + "\" and \"" + b.what() + "\" drawn over each other at " + Math.round(Math.max(a.x0(), b.x0()))
                            + "," + Math.round(Math.max(a.y0(), b.y0())));
                }
            }
        }
        return out;
    }

    /** Pairs of click areas that overlap (one click would reach both). */
    static List<String> overlaps(List<Area> areas) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < areas.size(); i++) {
            Area a = areas.get(i);
            for (int j = i + 1; j < areas.size(); j++) {
                Area b = areas.get(j);
                if (a.x() < b.x() + b.w() && b.x() < a.x() + a.w() && a.y() < b.y() + b.h() && b.y() < a.y() + a.h()) {
                    out.add("\"" + a.what() + "\" and \"" + b.what() + "\" overlap at " + Math.max(a.x(), b.x()) + "," + Math.max(a.y(), b.y()));
                }
            }
        }
        return out;
    }
}
