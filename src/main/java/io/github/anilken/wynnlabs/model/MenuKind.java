package io.github.anilken.wynnlabs.model;

import java.util.List;

/**
 * The menu open now, as far as the mod cares. {@code type} is the reader's name for it
 * ("CharacterBankContainer" …). For a bank page ({@code storage}): its storage slots and the
 * slots of the page arrows.
 */
public record MenuKind(String type, boolean characterBank, boolean accountBank, boolean storage,
        boolean blacksmithSell, boolean marketSell, List<Integer> slots, int previousSlot, int nextSlot) {}
