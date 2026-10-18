package io.github.anilken.wynnlabs.model;

/**
 * An aspect shown in the raid reward preview menu (the weekly pool), as the player saw it.
 *
 * @param name   the aspect's English name
 * @param klass  the class it belongs to (NONE when unknown)
 * @param rarity MYTHIC, FABLED or LEGENDARY
 * @param tier   the tier the preview shows (1 when it shows none)
 */
public record PoolAspect(String name, Klass klass, Tier rarity, int tier) {}
