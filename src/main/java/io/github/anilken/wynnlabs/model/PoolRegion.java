package io.github.anilken.wynnlabs.model;

import java.util.List;

/**
 * One region of a weekly pool from Wynnventory: a raid or a lootrun camp, with its rewards.
 *
 * @param name   the region's English name ("The Canyon Colossus", "Silent Expanse")
 * @param type   Wynnventory's region type; "" when none
 * @param seenAt the region's {@code timestamp}, epoch milliseconds; -1 when it cannot be read
 * @param items  its rewards, in Wynnventory's order (copied)
 */
public record PoolRegion(String name, String type, long seenAt, List<PoolItem> items) {

    public PoolRegion {
        name = name == null ? "" : name;
        type = type == null ? "" : type;
        seenAt = seenAt < 0 ? -1 : seenAt;
        items = items == null ? List.of() : List.copyOf(items);
    }
}
