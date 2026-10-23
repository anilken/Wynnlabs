package io.github.anilken.wynnlabs.search;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * One place of the selected item drawn on Wynntils' map: a spawn area (a translucent circle
 * whose radius follows the map's zoom) or a point (merchant, dungeon, chest: a small dot). The
 * label shows on hover. {@code focus} marks the row the player picked.
 */
public record MapMark(int x, int y, int z, int radius, String label, String badge, boolean focus) {

    /** The smallest circle drawn for an area, in screen pixels, so a far zoom still shows it. */
    public static final int MIN_PX = 4;
    /** The largest, so a close zoom never draws a circle bigger than the map. */
    public static final int MAX_PX = 2048;
    /** A point's dot, in screen pixels. */
    public static final int DOT_PX = 4;
    /** At most this many areas a row puts on the map. */
    public static final int PER_ROW = Integer.MAX_VALUE;       // no cap: every place of a row

    public boolean point() {
        return radius <= 0;
    }

    /**
     * An area's radius on the map: {@code blocks} times the map's pixels per block, kept between
     * {@link #MIN_PX} and {@link #MAX_PX}; 0 for a point.
     */
    public static int radiusPx(int blocks, float pxPerBlock) {
        if (blocks <= 0) {
            return 0;
        }
        double px = blocks * (double) Math.max(0f, pxPerBlock);
        return (int) Math.max(MIN_PX, Math.min(MAX_PX, Math.round(px)));
    }

    /** The box the map tests for hover and drawing: the circle's diameter, or the dot's. */
    public static int sizePx(MapMark m, float pxPerBlock) {
        return m.point() ? DOT_PX + 4 : 2 * radiusPx(m.radius(), pxPerBlock);
    }

    /**
     * The marks for an item's ranked rows: the row {@code picked} only, or every row with a place
     * when {@code all}; each row's areas nearest first, at most {@link #PER_ROW}; the picked row's
     * marks have {@code focus}.
     */
    public static List<MapMark> of(List<Source> rows, int picked, boolean all, Function<Source, String> label) {
        List<MapMark> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (!all && i != picked) {
                continue;
            }
            Source s = rows.get(i);
            String text = label.apply(s);
            int n = 0;
            for (Area a : s.areas()) {
                if (n++ >= PER_ROW) {
                    break;
                }
                String name = a.name() == null || a.name().isBlank() ? text : text + " · " + a.name();
                out.add(new MapMark(a.x(), a.y(), a.z(), a.radius(), name, s.badge(), i == picked));
            }
        }
        return out;
    }
}
