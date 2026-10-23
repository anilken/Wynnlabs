package io.github.anilken.wynnlabs.search;

/**
 * Distance and direction from the player to an area. The position is {x, z} (null when the
 * player is not in the world). Distance is horizontal, to the area's edge (centre minus radius,
 * never below 0); direction is one of eight compass points with north = −Z and east = +X, named
 * like the {@code wynnlabs.grind.dir_*} language keys ("north", "northeast", ...).
 */
public final class Compass {

    /** Counter-clockwise from east, as atan2 with +Z down gives them. */
    private static final String[] POINTS = {"east", "southeast", "south", "southwest", "west", "northwest", "north", "northeast"};

    private Compass() {}

    /** Blocks to the area's edge, or NaN when the position is unknown or there is no area. */
    public static double distance(Area a, double[] pos) {
        if (a == null || pos == null) {
            return Double.NaN;
        }
        return Math.max(0, Math.hypot(a.x() - pos[0], a.z() - pos[1]) - a.radius());
    }

    /** "north", "southeast", ...; null when the position is unknown, there is no area, or the player stands on its centre. */
    public static String direction(Area a, double[] pos) {
        if (a == null || pos == null) {
            return null;
        }
        double dx = a.x() - pos[0];
        double dz = a.z() - pos[1];
        if (dx == 0 && dz == 0) {
            return null;
        }
        double angle = Math.toDegrees(Math.atan2(dz, dx));
        int i = (int) Math.round(((angle % 360) + 360) % 360 / 45.0) % 8;
        return POINTS[i];
    }
}
