package io.github.anilken.wynnlabs.model;

import java.util.List;

/**
 * One raid run as it ended, in plain values (raid-tracking spec). Names are the game's English
 * ones as Wynntils gives them, without formatting codes.
 *
 * @param raid         the raid's full name ("The Canyon Colossus")
 * @param abbreviation its short name ("TCC"), or "" when unknown
 * @param completed    true when the raid was completed, false when it failed
 * @param endedAt      when it ended, epoch milliseconds
 * @param durationMs   total time in the raid, milliseconds (-1 when unknown)
 * @param rooms        the rooms reached, in order, each with its time
 */
public record RaidRun(String raid, String abbreviation, boolean completed, long endedAt, long durationMs, List<Room> rooms) {

    public RaidRun {
        abbreviation = abbreviation == null ? "" : abbreviation;
        rooms = rooms == null ? List.of() : List.copyOf(rooms);
    }

    /**
     * A room of a raid run.
     *
     * @param name the room's English name as Wynntils names it
     * @param ms   time spent in the room, milliseconds (-1 when unknown)
     */
    public record Room(String name, long ms) {}
}
