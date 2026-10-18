package io.github.anilken.wynnlabs.model;

import java.util.List;

/**
 * A weekly pool from Wynnventory (raid or lootrun), immutable (design decision 10).
 *
 * @param year      the pool's ISO year; -1 when unknown
 * @param week      the pool's ISO week; -1 when unknown
 * @param fetchedAt when it was fetched, epoch milliseconds; -1 when unknown
 * @param regions   its raids or camps, in Wynnventory's order (copied)
 */
public record WeeklyPool(int year, int week, long fetchedAt, List<PoolRegion> regions) {

    /** No data. */
    public static final WeeklyPool NONE = new WeeklyPool(-1, -1, -1, List.of());

    public WeeklyPool {
        regions = regions == null ? List.of() : List.copyOf(regions);
    }

    /** Whether this holds any data (a known week and at least one region). */
    public boolean present() {
        return year >= 0 && week >= 0 && !regions.isEmpty();
    }

    /** The newest region timestamp, epoch milliseconds; -1 when none is known. */
    public long newestSeenAt() {
        long newest = -1;
        for (PoolRegion r : regions) {
            newest = Math.max(newest, r.seenAt());
        }
        return newest;
    }
}
