package io.github.anilken.wynnlabs.crafting;

import java.util.ArrayList;
import java.util.List;

/**
 * Small answers around a craft: the skill points it still needs, how many crafts reach the
 * next profession level, and the nearest crafting station.
 */
public final class CraftChecks {

    /** Skill names in the requirement order. */
    public static final List<String> SKILLS = List.of("strength", "dexterity", "intelligence", "defence", "agility");

    /** One skill a craft needs more of: index into {@link #SKILLS}, what it needs and what the player has. */
    public record Gap(int skill, int need, int have) {
        public int missing() {
            return need - have;
        }
    }

    private CraftChecks() {}

    /** The skills whose requirement the player's points do not meet; a requirement of 0 or less is none. */
    public static List<Gap> gaps(int[] requirements, int[] points) {
        List<Gap> out = new ArrayList<>();
        for (int s = 0; s < 5; s++) {
            if (requirements[s] > 0 && points[s] < requirements[s]) {
                out.add(new Gap(s, requirements[s], points[s]));
            }
        }
        return out;
    }

    /**
     * Crafts to the next level: the XP still needed divided by the XP per craft, rounded up;
     * 0 when nothing is needed, -1 when it cannot be told (no XP per craft or no level data).
     */
    public static int craftsToLevel(int xpPerCraft, int xpNow, int xpForLevel) {
        if (xpPerCraft <= 0 || xpForLevel <= 0) {
            return -1;
        }
        int left = Math.max(0, xpForLevel - xpNow);
        return (left + xpPerCraft - 1) / xpPerCraft;
    }

    /** The station closest to a position (x, z on the map; height ignored), as its index, or -1 when there is none. */
    public static int nearest(List<int[]> stations, double x, double z) {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < stations.size(); i++) {
            int[] s = stations.get(i);
            double d = (s[0] - x) * (s[0] - x) + (s[2] - z) * (s[2] - z);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** The map distance in blocks between a position and a station. */
    public static int distance(int[] station, double x, double z) {
        return (int) Math.round(Math.hypot(station[0] - x, station[2] - z));
    }
}
