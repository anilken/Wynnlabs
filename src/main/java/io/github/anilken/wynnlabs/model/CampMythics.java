package io.github.anilken.wynnlabs.model;

import java.util.List;

/**
 * The mythics a lootrun camp holds this week, from Wynnventory's loot pool (design decision 10).
 *
 * @param camp    the camp's English name
 * @param mythics the mythics' English names, in Wynnventory's order (copied)
 * @param seenAt  the camp's timestamp, epoch milliseconds; -1 when unknown
 */
public record CampMythics(String camp, List<String> mythics, long seenAt) {

    public CampMythics {
        camp = camp == null ? "" : camp;
        mythics = mythics == null ? List.of() : List.copyOf(mythics);
        seenAt = seenAt < 0 ? -1 : seenAt;
    }

    /** How many mythics the camp holds. */
    public int count() {
        return mythics.size();
    }
}
