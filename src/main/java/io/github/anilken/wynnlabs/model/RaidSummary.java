package io.github.anilken.wynnlabs.model;

import java.util.List;

/**
 * One raid's row in the raid summary (raid-tracking spec, per-raid summary). Times count only
 * completed runs; -1 means none (drawn as "--", never 0).
 *
 * @param raid      the raid's English name
 * @param done      completed runs (running total, not lowered by the history cap)
 * @param failed    failed runs (running total, not lowered by the history cap)
 * @param bestMs    best completed time, milliseconds; -1 when none
 * @param averageMs average completed time over the kept runs, milliseconds; -1 when none
 * @param lastAt    when the last run (completed or failed) ended, epoch milliseconds; -1 when none
 * @param last      that last run, or null
 * @param bestRooms each room's best time over the completed runs kept, in room order
 */
public record RaidSummary(String raid, int done, int failed, long bestMs, long averageMs, long lastAt, RaidRun last,
        List<RaidRun.Room> bestRooms) {

    public RaidSummary {
        bestRooms = bestRooms == null ? List.of() : List.copyOf(bestRooms);
    }

    /** A raid with no runs. */
    public static RaidSummary empty(String raid) {
        return new RaidSummary(raid, 0, 0, -1, -1, -1, null, List.of());
    }
}
