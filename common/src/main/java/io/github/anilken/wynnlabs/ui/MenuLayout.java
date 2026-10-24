package io.github.anilken.wynnlabs.ui;

/**
 * How the J menu fits the space it gets, worked out once a frame from the screen's GUI size:
 * compact under 560×300 (icons-only rail, no page hints), large from 800×450 (a panel up to
 * 920×520, more rows, the same text size), standard in between, which is the 640×344 menu as it
 * always was. The panel keeps 8px from every edge, so its 5px frame stays inside the window.
 */
public record MenuLayout(Size size, int x, int y, int w, int h, int railW) {

    enum Size { COMPACT, STANDARD, LARGE }

    static final int RAIL = 100;
    static final int RAIL_COMPACT = 34;

    public static MenuLayout of(int screenW, int screenH) {
        Size size = screenW < 560 || screenH < 300 ? Size.COMPACT
                : screenW >= 800 && screenH >= 450 ? Size.LARGE : Size.STANDARD;
        int maxW = size == Size.LARGE ? 920 : 640;
        int maxH = size == Size.LARGE ? 520 : 344;
        int w = Math.max(100, Math.min(screenW - 16, maxW));
        int h = Math.max(100, Math.min(screenH - 16, maxH));
        // "Menu size": a share of the screen, never below the standard 640x344 the pages are made for
        int fill = io.github.anilken.wynnlabs.core.Settings.number("menuFill", 100);
        if (fill < 100 && size != Size.COMPACT) {
            w = Math.min(w, Math.max(Math.min(640, w), screenW * fill / 100));
            h = Math.min(h, Math.max(Math.min(344, h), screenH * fill / 100));
        }
        return new MenuLayout(size, (screenW - w) / 2, (screenH - h) / 2, w, h, size == Size.COMPACT ? RAIL_COMPACT : RAIL);
    }

    boolean compact() {
        return size == Size.COMPACT;
    }

    boolean large() {
        return size == Size.LARGE;
    }

    /** The page's content: 12px in from the rail and the page's right side, 16 from the top. */
    int contentX() {
        return x + railW + 12;
    }

    int contentW() {
        return w - railW - 24;
    }
}
