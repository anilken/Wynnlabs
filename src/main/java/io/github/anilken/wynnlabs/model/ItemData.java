package io.github.anilken.wynnlabs.model;

import java.util.List;
import java.util.Optional;

/**
 * A gear piece as the item database describes it, whoever owns it: the same for every copy.
 * {@code stats} are the identifications it can roll, in the data's order; {@code restriction} is
 * NONE, UNTRADABLE, QUEST_ITEM or SOULBOUND.
 */
public record ItemData(String name, Slot slot, Tier tier, int powderSlots, Requirement requirements,
        BaseStats base, List<StatRange> stats, Optional<SetData> set, String restriction) {

    public int level() {
        return requirements.level();
    }
}
