package io.github.anilken.wynnlabs.crafting;

import java.util.List;
import java.util.Map;

/**
 * One crafting ingredient as the calculator needs it, with no game types: its tier (0-3 stars)
 * and level, the professions that can use it, each identification's roll range, its position
 * effects on other slots, what it adds to durability (already divided by 1000), charges and
 * duration, and its skill point requirements (strength, dexterity, intelligence, defence,
 * agility). The empty slot is {@link #NONE}.
 */
public record Ingredient(String name, int tier, int level, List<String> professions, Map<String, Stat> ids,
                         int[] positions, int durability, int charges, int duration, int[] requirements) {

    /** An identification's range, its shown name and unit ("%", "/5s", "" for raw). */
    public record Stat(int min, int max, String shown, String unit) {}

    /** Position effect slots in {@link #positions}. */
    public static final int LEFT = 0;
    public static final int RIGHT = 1;
    public static final int ABOVE = 2;
    public static final int UNDER = 3;
    public static final int TOUCHING = 4;
    public static final int NOT_TOUCHING = 5;

    /** An empty grid slot: no identifications, no effects. */
    public static final Ingredient NONE = new Ingredient("", 0, 0, List.of(), Map.of(), new int[6], 0, 0, 0, new int[5]);

    public boolean empty() {
        return name.isEmpty();
    }

    /** True when it changes other slots' effectiveness. */
    public boolean meta() {
        for (int p : positions) {
            if (p != 0) {
                return true;
            }
        }
        return false;
    }

    /** True when a profession ("woodworking") may use it. */
    public boolean usableBy(String profession) {
        return professions.contains(profession);
    }
}
