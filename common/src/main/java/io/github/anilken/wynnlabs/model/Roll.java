package io.github.anilken.wynnlabs.model;

/**
 * One rolled identification on a stack: the stat ({@link StatRange} terms) and its value.
 * {@code range} is what the piece can roll for it, null when unknown (crafted gear).
 */
public record Roll(String key, String name, String unit, int value, StatRange range) {}
