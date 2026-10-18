package io.github.anilken.wynnlabs.model;

import java.util.Locale;

/**
 * The five skills, in the game's order (Strength … Agility): arrays indexed by
 * {@link #ordinal()} and EnumMaps rely on it.
 */
public enum SkillKind {
    STRENGTH("EARTH"), DEXTERITY("THUNDER"), INTELLIGENCE("WATER"), DEFENCE("FIRE"), AGILITY("AIR");

    private final String element;

    SkillKind(String element) {
        this.element = element;
    }

    /** "Strength", "Defence" …: how the game writes it. */
    public String displayName() {
        String n = name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** The element this skill boosts ("EARTH" for Strength). */
    public String element() {
        return element;
    }
}
