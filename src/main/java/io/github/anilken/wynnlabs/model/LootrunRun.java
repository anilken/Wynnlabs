package io.github.anilken.wynnlabs.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One lootrun as it ended, in plain values (lootrun-tracking spec). Camp, beacon colour, mission
 * and item names stay English.
 *
 * @param completed  true when the run was completed, false when it failed
 * @param endedAt    when it ended, epoch milliseconds
 * @param durationS  its time, seconds
 * @param challenges challenges completed
 * @param pulls      reward pulls (0 for a failed run)
 * @param rerolls    reward rerolls (0 for a failed run)
 * @param sacrifices reward sacrifices (0 for a failed run)
 * @param xp         lootrun experience gained (0 for a failed run)
 * @param camp       the camp's name, or "Unknown camp" style fallback chosen by the log; never null
 * @param beacons    beacons picked during the run by colour ("Blue" → 9), in pick order of first
 *                   appearance; colours that could not be read are left out
 * @param missions   the missions taken ("Hoarder", "High Roller"); unknown ones are left out
 * @param mythic     the name of the mythic its reward chest gave, or null
 */
public record LootrunRun(boolean completed, long endedAt, int durationS, int challenges, int pulls, int rerolls,
        int sacrifices, int xp, String camp, Map<String, Integer> beacons, List<String> missions, String mythic) {

    public LootrunRun {
        camp = camp == null ? "" : camp;
        beacons = beacons == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(beacons));
        missions = missions == null ? List.of() : List.copyOf(missions);
    }

    /** The same run with the mythic its reward chest gave. */
    public LootrunRun withMythic(String name) {
        return new LootrunRun(completed, endedAt, durationS, challenges, pulls, rerolls, sacrifices, xp, camp, beacons,
                missions, name);
    }

    /** Wynntils' lootrun state, as the mod needs it; UNKNOWN when it could not be read. */
    public enum State {
        IDLE, CHOOSING_BEACON, IN_TASK, UNKNOWN;

        /** True while a run is going on. */
        public boolean running() {
            return this == CHOOSING_BEACON || this == IN_TASK;
        }
    }
}
