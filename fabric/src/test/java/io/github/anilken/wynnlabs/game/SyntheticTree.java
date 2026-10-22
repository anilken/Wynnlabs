package io.github.anilken.wynnlabs.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.model.AbilityNode;

import java.util.List;

/**
 * A small made-up Shaman tree for tests and the J menu preview: two pages, three archetypes,
 * an ultimate each, one pair that blocks each other. The shape is the game's (9 x 6 grid,
 * connections down and across), the names and texts are invented.
 */
public final class SyntheticTree {

    private SyntheticTree() {}

    private static List<JsonArray> text(String... lines) {
        List<JsonArray> out = new java.util.ArrayList<>();
        for (String l : lines) {
            JsonObject part = new JsonObject();
            part.addProperty("text", l);
            JsonArray a = new JsonArray();
            a.add(part);
            out.add(a);
        }
        return out;
    }

    private static AbilityNode node(int id, String name, String type, int cost, String archetype, String colour,
            int page, int row, int col, List<Integer> connections, List<String> blocks, String requiredAbility,
            String requiredArchetype, int requiredPoints, int level, String... lines) {
        return new AbilityNode(id, name, type, text(lines), cost, blocks, requiredAbility, requiredArchetype,
                requiredPoints, level, archetype, colour, new AbilityNode.Place(page, row, col), connections);
    }

    private static final String A = "SHAMAN_ABILITY_LOCKED";
    private static final String SUM = "Summoner";
    private static final String RIT = "Ritualist";
    private static final String ACO = "Acolyte";

    /** The tree: 16 nodes, ids 1-16. */
    public static List<AbilityNode> nodes() {
        return List.of(
                node(1, "Totem", A, 1, null, null, 1, 0, 4, List.of(2), List.of(), null, null, 0, 1,
                        "Summon a totem that damages nearby enemies", "Right-Left-Right"),
                node(2, "Totemic Smash", A, 1, null, null, 1, 1, 4, List.of(3, 4), List.of(), null, null, 0, 2,
                        "Your totem lands with a smash"),
                node(3, "Haul", A, 1, null, null, 1, 2, 2, List.of(5), List.of(), null, null, 0, 4,
                        "Pull yourself to your totem", "Right-Right-Right"),
                node(4, "Aura", A, 1, null, null, 1, 2, 6, List.of(6), List.of(), null, null, 0, 4,
                        "Your totem releases a wave of damage", "Right-Right-Left"),
                node(5, "Uproot", A, 1, SUM, "#7F77DD", 1, 3, 2, List.of(7), List.of(), null, null, 0, 6,
                        "Uproot summons a puppet to fight for you"),
                node(6, "Blood Pact", A, 2, ACO, "#D85A30", 1, 3, 6, List.of(8), List.of("Totemic Shatter"), null, null, 0, 6,
                        "Spells cost health instead of mana"),
                node(7, "Totemic Shatter", A, 2, SUM, "#7F77DD", 1, 4, 2, List.of(9, 10), List.of("Blood Pact"), "Totem", null, 0, 8,
                        "Your totem explodes, 160% damage"),
                node(8, "Fluid Healing", A, 1, RIT, "#1D9E75", 1, 4, 6, List.of(11), List.of(), null, null, 0, 8,
                        "Heal allies in your totem's reach"),
                node(9, "Puppet Master", A, 2, SUM, "#7F77DD", 1, 5, 1, List.of(12), List.of(), null, SUM, 2, 10,
                        "Two puppets at once"),
                node(10, "Chant of the Fanatic", A, 2, RIT, "#1D9E75", 1, 5, 4, List.of(13), List.of(), null, null, 0, 10,
                        "Chant to raise everyone's damage"),
                node(11, "Sacrificial Shrine", A, 2, ACO, "#D85A30", 1, 5, 7, List.of(14), List.of(), null, ACO, 1, 10,
                        "Give health to raise spell damage"),
                node(12, "Regeneration Totem", A, 2, SUM, "#7F77DD", 2, 0, 1, List.of(15), List.of(), null, SUM, 3, 14,
                        "Your puppets heal you"),
                node(13, "Mask of the Coward", A, 2, RIT, "#1D9E75", 2, 0, 4, List.of(15, 16), List.of(), null, RIT, 2, 14,
                        "A mask that slows enemies"),
                node(14, "Exploding Pulse", A, 2, ACO, "#D85A30", 2, 0, 7, List.of(16), List.of(), null, ACO, 2, 14,
                        "Your heals also explode"),
                node(15, "Awakened", "SUMMONER_ULTIMATE_LOCKED", 3, SUM, "#7F77DD", 2, 2, 1, List.of(), List.of(), "Puppet Master", SUM, 5, 20,
                        "Ultimate: every puppet becomes a storm"),
                node(16, "Fetish", "ACOLYTE_ULTIMATE_LOCKED", 3, ACO, "#D85A30", 2, 2, 7, List.of(), List.of(), null, ACO, 4, 20,
                        "Ultimate: a fetish that drains everything"));
    }

    /** What the recorded Shaman has taken: the first three. */
    public static List<String> unlocked() {
        return List.of("Totem", "Totemic Smash", "Haul");
    }
}
