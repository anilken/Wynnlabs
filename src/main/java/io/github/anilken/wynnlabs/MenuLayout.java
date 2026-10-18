package io.github.anilken.wynnlabs;

/**
 * How the J menu fits the space it gets, worked out once a frame from the screen's GUI size:
 * compact under 560×300 (icons-only rail, no page hints), large from 800×450 (a panel up to
 * 920×520, more rows, the same text size), standard in between, which is the 640×344 menu as it
 * always was. The panel keeps 8px from every edge, so its 5px frame stays inside the window.
 */
record MenuLayout(Size size, int x, int y, int w, int h, int railW) {

    enum Size { COMPACT, STANDARD, LARGE }

    static final int RAIL = 100;
    static final int RAIL_COMPACT = 28;

    static MenuLayout of(int screenW, int screenH) {
        Size size = screenW < 560 || screenH < 300 ? Size.COMPACT
                : screenW >= 800 && screenH >= 450 ? Size.LARGE : Size.STANDARD;
        int maxW = size == Size.LARGE ? 920 : 640;
        int maxH = size == Size.LARGE ? 520 : 344;
        int w = Math.max(100, Math.min(screenW - 16, maxW));
        int h = Math.max(100, Math.min(screenH - 16, maxH));
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
