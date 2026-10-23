package io.github.anilken.wynnlabs.search;

/**
 * The tracker's decisions, kept apart from the game so they can be tested: when a tracked mob's
 * marker is drawn (only while the player could see it anyway) and when its arrival is told.
 */
public final class MobSight {

    /** Farther than this the marker is not drawn, even in plain sight. */
    public static final double MAX_DRAW = 160;
    /** A mob unseen this long and found again counts as a new appearance. */
    public static final long GONE_MS = 30_000;
    /** At most one notice this often, whatever the mob. */
    public static final long ANY_GAP_MS = 8_000;
    /** The same mob's name is told again only after this long. */
    public static final long SAME_GAP_MS = 60_000;

    private MobSight() {}

    /**
     * Whether the marker is drawn: the setting is on, the mob is near enough, and at least one ray
     * from the camera (to its eyes or to its middle) reached it without a block in the way. A
     * mob behind a wall gets nothing, so the marker never shows more than the player's own eyes.
     */
    public static boolean shown(boolean on, double distance, boolean eyeClear, boolean middleClear) {
        return on && distance >= 0 && distance <= MAX_DRAW && (eyeClear || middleClear);
    }

    /** Whether a mob last seen at {@code lastSeen} (0: never) and seen again {@code now} has just appeared. */
    public static boolean appeared(long now, long lastSeen) {
        return lastSeen <= 0 || now - lastSeen > GONE_MS;
    }

    /**
     * Whether an appearance is told now: not within {@link #ANY_GAP_MS} of the last notice, nor
     * within {@link #SAME_GAP_MS} of the last notice for the same name (0: never told).
     */
    public static boolean announce(long now, long lastAny, long lastSame) {
        return (lastAny <= 0 || now - lastAny >= ANY_GAP_MS) && (lastSame <= 0 || now - lastSame >= SAME_GAP_MS);
    }
}
