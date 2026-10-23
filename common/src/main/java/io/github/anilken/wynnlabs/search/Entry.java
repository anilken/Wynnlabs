package io.github.anilken.wynnlabs.search;

import io.github.anilken.wynnlabs.model.Klass;

import java.util.List;
import java.util.Map;

/**
 * One searchable item, read from Wynntils' cached data. Names stay in English as in the data.
 *
 * @param type        the data's sub type: "chestplate", "bow", "weapon_tome", a material's profession; "ingredient" for ingredients
 * @param level       the level requirement (0 when none)
 * @param tier        rarity in lower case for gear, tomes and charms ("rare", "mythic"); null for ingredients and materials
 * @param stars       an ingredient's tier (0–3 stars); -1 for the rest
 * @param klass       the class that can use it, {@link Klass#NONE} for every class
 * @param skills      skill point requirements ("strength" → 40); empty when none
 * @param quest       a quest that must be done first, or null
 * @param restriction "none", "untradable" or "quest_item"; null when the data has none (tradable)
 * @param drop        gear's {@code dropRestriction}: "normal", "never", "lootchest", "dungeon"; null for other kinds
 * @param origins     where the data says it comes from (dropMeta, droppedBy, item obtain list), in the data's order
 * @param stats       identifications with their ranges, in the data's order
 * @param professions crafting professions of an ingredient, or the gathering profession of a material
 * @param modifiers   an ingredient's position modifiers and item-only changes ("above" → 10,
 *                    "durabilityModifier" → -87000); zero values left out; empty for other kinds
 * @param set         the set the item belongs to, or null
 */
public record Entry(String name, Kind kind, String type, int level, String tier, int stars, Klass klass,
        Map<String, Integer> skills, String quest, String restriction, String drop, Icon icon,
        List<Origin> origins, List<Stat> stats, List<String> professions, Map<String, Integer> modifiers,
        String set) {

    /** Untradable items and quest items never reach the Trade Market. */
    public boolean tradable() {
        return restriction == null || restriction.equals("none");
    }

    /** True for gear that drops from ordinary mobs of its level (and from loot chests). */
    public boolean normalDrop() {
        return "normal".equals(drop);
    }

    /**
     * How the item looks: the base item {@code id} ("minecraft:potion") with {@code model} as its
     * custom model data (the Wynncraft pack's range dispatch value, NaN when none) and the pack's
     * model {@code name} ("chestplate.iron"); a head's texture hash in {@code skin} (id is then
     * "minecraft:player_head"). Any field can be null.
     */
    public record Icon(String id, float model, String name, String skin) {}

    /** An identification: {@code key} as in the data (camelCase, "manaSteal"); a fixed value has min == max. */
    public record Stat(String key, int min, int max) {}

    /**
     * One source the data names. {@code type} is the data's word ("merchant", "dungeonMerchant",
     * "mob" for an ingredient's droppedBy, "worldEvent", ...); {@code name} the mob, place, quest or
     * event (may be null); {@code areas} its coordinates when the data has them, else empty.
     */
    public record Origin(String type, String name, List<Area> areas) {}
}
