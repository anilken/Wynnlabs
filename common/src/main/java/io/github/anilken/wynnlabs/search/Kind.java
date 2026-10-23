package io.github.anilken.wynnlabs.search;

/**
 * What sort of thing a search entry is: an item (gear to charm) or something of the world (a mob,
 * a place, content such as a dungeon, a service, a gathering resource, a quest, an NPC, a secret
 * discovery). Aspects are left out for now: their data names no source.
 */
public enum Kind {
    GEAR, INGREDIENT, MATERIAL, TOME, CHARM,
    MOB, PLACE, CONTENT, SERVICE, RESOURCE, QUEST, NPC, DISCOVERY;

    /** True for the item kinds (they have icons, sources and prices); false for the world's things. */
    public boolean item() {
        return ordinal() <= CHARM.ordinal();
    }
}
