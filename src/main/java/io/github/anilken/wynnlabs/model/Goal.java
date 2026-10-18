package io.github.anilken.wynnlabs.model;

/** A daily or weekly objective and its score (null when it shows none). */
public record Goal(String goal, Capped score) {}
