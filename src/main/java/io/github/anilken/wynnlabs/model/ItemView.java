package io.github.anilken.wynnlabs.model;

import java.util.List;
import java.util.Map;

/**
 * A stack as the mod understands it. {@code kind} says which of the parts below are filled; the
 * rest are null. The first fields hold for any kind:
 * <ul>
 * <li>{@code type}: the reader's own name for what it is ("GearItem", "EmeraldPouchItem" …),
 * which the API shows as the kind of items without parts of their own;</li>
 * <li>{@code plain}: known only as a Wynncraft item, nothing more (a dropped item, mostly);</li>
 * <li>{@code name}, {@code level}, {@code professions}: when the item has such a property
 * (ingredients, materials, powders, keys … but not gear, whose name is in {@code data}).</li>
 * </ul>
 * A content book entry (ACTIVITY) has no part here: it is read only when asked for.
 */
public record ItemView(Kind kind, String type, boolean plain, String name, Integer level, List<String> professions,
        ItemData data, Rolled rolled, Crafted crafted, Box box, Integer qualityTier, Powder powder, Potion potion,
        DungeonKey dungeonKey, SkillPoint skillPoint, Integer crystalCount, String abilityName) {

    public enum Kind {
        GEAR, CRAFTED, BOX, INGREDIENT, MATERIAL, POWDER, POTION, DUNGEON_KEY, SKILL_POINT, SKILL_CRYSTAL,
        ABILITY_NODE, ACTIVITY, OTHER
    }

    public boolean isGear() {
        return kind == Kind.GEAR;
    }

    public boolean isCrafted() {
        return kind == Kind.CRAFTED;
    }

    public boolean isBox() {
        return kind == Kind.BOX;
    }

    /** Gear that has not been identified yet: no rolls. */
    public boolean unidentified() {
        return kind == Kind.GEAR && rolled == null;
    }

    /**
     * An identified gear stack: its rolls, powders, rerolls, the overall roll quality (null when
     * the item shows none), whether it is shiny, and the game's requirement checkmarks.
     */
    public record Rolled(List<Roll> rolls, List<String> powders, int rerolls, Float overall, boolean shiny,
            Marks marks) {

        public boolean hasOverall() {
            return overall != null;
        }

        /** The overall %, 0 when the item shows none. */
        public float overallPercent() {
            return overall == null ? 0.0f : overall;
        }

        public boolean perfect() {
            return overallPercent() >= 100.0f;
        }
    }

    /**
     * The game's own requirement checkmarks under a piece. {@code known} is false when they could
     * not be read; then the rest says nothing.
     */
    public record Marks(boolean known, boolean all, Map<SkillKind, Boolean> skills, boolean quest) {}

    /** A crafted piece: everything it has, since there is no database entry for it. */
    public record Crafted(String name, Slot slot, Tier tier, int level, int dps, int health,
            List<BaseStats.Damage> damages, Requirement requirements, List<Roll> rolls, Klass requiredClass,
            List<String> powders, int powderSlots, Capped durability) {}

    /** An unidentified gear box: the kind and tier of gear in it and its level range. */
    public record Box(Slot slot, Tier tier, int low, int high) {}

    /** A powder: its element ("FIRE") and tier. */
    public record Powder(String element, int tier) {}

    /** A potion: its type, level and uses left (null when it shows none). */
    public record Potion(String type, int level, Capped uses) {}

    /**
     * A dungeon key: the dungeon, broken or corrupted, and the level and entrance of the dungeon
     * it opens (null when unknown).
     */
    public record DungeonKey(String dungeon, boolean corrupted, boolean broken, Integer combatLevel, Integer x,
            Integer z) {}

    /** A skill button in the Character Info menu and the points assigned to it. */
    public record SkillPoint(SkillKind skill, int assigned) {}
}
