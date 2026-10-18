package io.github.anilken.wynnlabs.model;

/**
 * A mythic found in a lootrun reward chest.
 *
 * @param name the item's English name ("Warp")
 * @param at   when it was found, epoch milliseconds
 */
public record MythicFind(String name, long at) {}
