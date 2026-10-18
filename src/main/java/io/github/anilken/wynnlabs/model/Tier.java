package io.github.anilken.wynnlabs.model;

import net.minecraft.ChatFormatting;

/**
 * Gear rarity, in the game data's order (comparisons and loops over the tiers rely on it), with
 * the name colour and what identifying costs. SET is the old tier for set pieces.
 */
public enum Tier {
    NORMAL(ChatFormatting.WHITE, 0, 0.0f),
    UNIQUE(ChatFormatting.YELLOW, 3, 0.5f),
    RARE(ChatFormatting.LIGHT_PURPLE, 8, 1.2f),
    SET(ChatFormatting.GRAY, 8, 1.2f),
    LEGENDARY(ChatFormatting.AQUA, 12, 4.5f),
    FABLED(ChatFormatting.RED, 16, 8.0f),
    MYTHIC(ChatFormatting.DARK_PURPLE, 90, 18.0f),
    CRAFTED(ChatFormatting.DARK_AQUA, 0, 0.0f);

    private final ChatFormatting colour;
    private final int idBase;
    private final float idPerLevel;

    Tier(ChatFormatting colour, int idBase, float idPerLevel) {
        this.colour = colour;
        this.idBase = idBase;
        this.idPerLevel = idPerLevel;
    }

    /** The colour the game writes this tier's names in. */
    public ChatFormatting colour() {
        return colour;
    }

    /**
     * Emeralds to identify a piece of this tier and level: a base plus a per-level part rounded
     * up (worked out in float, as the game data gives it, so the result matches to the emerald).
     */
    public int identificationCost(int level) {
        return idBase + (int) Math.ceil(level * idPerLevel);
    }

    /** The tier with this name; NORMAL for a name this version does not have. */
    public static Tier of(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return NORMAL;
        }
    }
}
