package io.github.anilken.wynnlabs.search;

/**
 * One search result: the entry, its score (higher is better) and why it matched. {@code detail}
 * names what matched when it was not the item's name: the mob for {@link Match#MOB}, the stat key
 * (camelCase, as in the data) for {@link Match#STAT}; null otherwise.
 */
public record Hit(Entry entry, int score, Match match, String detail) {

    /** Why an item matched, best first; ALL when the box is empty and every item is listed. */
    public enum Match { EXACT, PREFIX, WORD, SUBSTRING, TYPO, MOB, STAT, ALL }
}
