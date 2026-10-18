package io.github.anilken.wynnlabs.model;

import java.util.Optional;

/** Where a piece comes from: a source type ("MERCHANT", "DUNGEON", "LOOT_CHEST" …) and its name if known. */
public record Obtain(String type, Optional<String> name) {}
