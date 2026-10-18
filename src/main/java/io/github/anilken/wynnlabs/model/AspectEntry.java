package io.github.anilken.wynnlabs.model;

import java.util.List;

/**
 * One aspect of a class from the game data Wynntils downloaded (aspect-collection spec).
 *
 * @param name       the aspect's English name ("Aspect of Damnation")
 * @param klass      the class it belongs to
 * @param rarity     MYTHIC, FABLED or LEGENDARY
 * @param tiers      how many tiers it has (3 or 4)
 * @param thresholds the copies each tier needs, tier 1 first (e.g. 1, 5, 30, 150); may be shorter
 *                   than {@code tiers} when the data lacks them
 * @param abilities  the ability names its tier texts name (English, as in the tree), empty when
 *                   not read yet; {@code Wyn.classAspects} leaves this empty, the aspect book
 *                   fills it from Wynntils' cached aspect data
 */
public record AspectEntry(String name, Klass klass, Tier rarity, int tiers, List<Integer> thresholds, List<String> abilities) {

    public AspectEntry {
        thresholds = thresholds == null ? List.of() : List.copyOf(thresholds);
        abilities = abilities == null ? List.of() : List.copyOf(abilities);
    }

    /** The same aspect with these ability names. */
    public AspectEntry withAbilities(List<String> names) {
        return new AspectEntry(name, klass, rarity, tiers, thresholds, names);
    }
}
