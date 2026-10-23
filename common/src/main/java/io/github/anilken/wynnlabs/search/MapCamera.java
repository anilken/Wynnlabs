package io.github.anilken.wynnlabs.search;

/**
 * Where a map view looks: the world point at its centre and the zoom (view pixels per block),
 * with the sums that turn world (x, z) into view pixels and back, zoom around the cursor and pan.
 * The centre stays inside a bounding rectangle, so the view never drifts off the map. Pure, so
 * the sums are tested without the game; the view's top-left pixel is (0, 0).
 */
public final class MapCamera {

    public static final double MIN_ZOOM = 0.25;
    public static final double MAX_ZOOM = 4;

    private double cx;
    private double cz;
    private double zoom = 1;
    private int w = 1;
    private int h = 1;
    private double bx0 = Double.NEGATIVE_INFINITY;
    private double bz0 = Double.NEGATIVE_INFINITY;
    private double bx1 = Double.POSITIVE_INFINITY;
    private double bz1 = Double.POSITIVE_INFINITY;

    public double centreX() {
        return cx;
    }

    public double centreZ() {
        return cz;
    }

    public double zoom() {
        return zoom;
    }

    public int width() {
        return w;
    }

    public int height() {
        return h;
    }

    /** The view's size in pixels (at least 1×1). */
    public void size(int width, int height) {
        w = Math.max(1, width);
        h = Math.max(1, height);
    }

    /** The rectangle the centre stays in; {@code null} lifts it. */
    public void bounds(double[] b) {
        if (b == null) {
            bx0 = bz0 = Double.NEGATIVE_INFINITY;
            bx1 = bz1 = Double.POSITIVE_INFINITY;
        } else {
            bx0 = Math.min(b[0], b[2]);
            bz0 = Math.min(b[1], b[3]);
            bx1 = Math.max(b[0], b[2]);
            bz1 = Math.max(b[1], b[3]);
        }
        clamp();
    }

    /** Looks at (x, z), keeping the zoom. */
    public void centre(double x, double z) {
        cx = x;
        cz = z;
        clamp();
    }

    /** Sets the zoom (clamped to {@link #MIN_ZOOM}..{@link #MAX_ZOOM}), keeping the centre. */
    public void zoom(double z) {
        zoom = clampZoom(z);
    }

    /**
     * Looks at the world rectangle [x0, x1] × [z0, z1] with {@code pad} view pixels around it: the
     * largest zoom that fits, no closer than {@code maxZoom}.
     */
    public void fit(double x0, double z0, double x1, double z1, int pad, double maxZoom) {
        double dw = Math.max(1, Math.abs(x1 - x0));
        double dh = Math.max(1, Math.abs(z1 - z0));
        double room = Math.max(1, w - 2.0 * pad);
        double roomH = Math.max(1, h - 2.0 * pad);
        zoom = clampZoom(Math.min(maxZoom, Math.min(room / dw, roomH / dh)));
        centre((x0 + x1) / 2, (z0 + z1) / 2);
    }

    /** The view pixel column of world x. */
    public double viewX(double x) {
        return w / 2.0 + (x - cx) * zoom;
    }

    /** The view pixel row of world z. */
    public double viewY(double z) {
        return h / 2.0 + (z - cz) * zoom;
    }

    /** The world x under view column {@code px}. */
    public double worldX(double px) {
        return cx + (px - w / 2.0) / zoom;
    }

    /** The world z under view row {@code py}. */
    public double worldZ(double py) {
        return cz + (py - h / 2.0) / zoom;
    }

    /** The world rectangle the view shows: {x0, z0, x1, z1}. */
    public double[] visible(double[] out) {
        out[0] = worldX(0);
        out[1] = worldZ(0);
        out[2] = worldX(w);
        out[3] = worldZ(h);
        return out;
    }

    /**
     * Zooms by {@code factor} keeping the world point under view pixel (px, py) where it is (the
     * wheel over the map). Near the bounds' edge the clamp may move it a little.
     */
    public void zoomAround(double px, double py, double factor) {
        double wx = worldX(px);
        double wz = worldZ(py);
        zoom = clampZoom(zoom * factor);
        cx = wx - (px - w / 2.0) / zoom;
        cz = wz - (py - h / 2.0) / zoom;
        clamp();
    }

    /** Moves the picture by (dx, dy) view pixels, as a drag does: the world follows the mouse. */
    public void pan(double dx, double dy) {
        cx -= dx / zoom;
        cz -= dy / zoom;
        clamp();
    }

    private static double clampZoom(double z) {
        if (Double.isNaN(z)) {
            return 1;
        }
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
    }

    private void clamp() {
        cx = Math.max(bx0, Math.min(bx1, cx));
        cz = Math.max(bz0, Math.min(bz1, cz));
    }
}
