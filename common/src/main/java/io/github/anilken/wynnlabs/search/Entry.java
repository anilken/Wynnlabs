package io.github.anilken.wynnlabs.search;

import io.github.anilken.wynnlabs.model.Klass;

import java.util.List;
import java.util.Map;

/**
 * One searchable entry: an item read from Wynntils' cached data, or a thing of the world (a mob,
 * a place, a dungeon, a service, ...; see {@link #thing}). Names stay in English as in the data.
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

    /** A thing's links in {@link #origins}: a place it is at (areas, name = the place's label). */
    public static final String AT = "at";
    /** A thing's links: an item it drops, gives or is made into (name = the item). */
    public static final String ITEM = "item";
    /** A thing's links: where it is in words (a region, the wiki's location text). */
    public static final String WHERE = "where";
    /** A thing's links: a quest it belongs to (an NPC's quest, a quest's own name). */
    public static final String QUEST = "quest";
    /** A thing's links: its id in its own tab (a discovery's id), as the name. */
    public static final String ID = "id";
    /** A thing's links: who to talk to first (a quest's start NPC, with its place when known). */
    public static final String NPC = "npc";

    /**
     * A thing of the world (not an item): its kind's {@code type} ("dungeon", "city", "wiki" for a
     * mob only the wiki lists), its level (0 when unknown) and its links as {@link Origin}s of the
     * types {@link #AT}, {@link #ITEM}, {@link #WHERE}, {@link #QUEST} and {@link #NPC}. Never
     * tradable; no icon, stats or class.
     */
    public static Entry thing(String name, Kind kind, String type, int level, List<Origin> links) {
        return new Entry(name, kind, type, level, null, -1, Klass.NONE, Map.of(), null, "world", null,
                new Icon(null, Float.NaN, null, null), List.copyOf(links), List.of(), List.of(), Map.of(), null);
    }

    /** True for items (gear, ingredients, ...); false for the world's things. */
    public boolean item() {
        return kind.item();
    }

    /**
     * What the tab keeps to find this entry again: an item's name, or a thing's kind and name
     * ("mob:Reggie"), since a mob, a place and an item can share a name.
     */
    public String key() {
        return item() ? name : kind.name().toLowerCase(java.util.Locale.ROOT) + ":" + name;
    }

    /** A thing's links of one type, in order. */
    public List<Origin> links(String type) {
        return origins.stream().filter(o -> o.type().equals(type)).toList();
    }

    /** Every area of a thing's {@link #AT} links (and its start NPC's), in order. */
    public List<Area> places() {
        List<Area> out = null;
        List<Area> only = List.of();
        for (Origin o : origins) {
            if ((o.type().equals(AT) || o.type().equals(NPC)) && !o.areas().isEmpty()) {
                if (only.isEmpty()) {
                    only = o.areas();               // the usual single list, handed out as is (drawn every frame)
                } else {
                    if (out == null) {
                        out = new java.util.ArrayList<>(only);
                    }
                    out.addAll(o.areas());
                }
            }
        }
        return out == null ? only : out;
    }

    /** Untradable items, quest items and things never reach the Trade Market. */
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
