package io.github.anilken.wynnlabs.model;

/**
 * A character class. Same constants and order as the game data the mod reads, so an EnumMap or
 * a sort by class stays the same; NONE is "no class" (not logged in, or gear for every class).
 */
public enum Klass {
    MAGE, ARCHER, WARRIOR, ASSASSIN, SHAMAN, NONE
}
