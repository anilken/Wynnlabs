package io.github.anilken.wynnlabs.model;

/**
 * What an identification can roll on a piece: {@code key} is the stat's API name
 * ("spellDamage", "rawHealth" …, the same in the Wynncraft API and the rules' weights),
 * {@code name} how the game shows it, {@code unit} RAW, PERCENT, PER_3_S … and
 * {@code inverted} true for stats where less is better (spell cost).
 */
public record StatRange(String key, String name, String unit, boolean inverted, int min, int max) {

    /** One value only: nothing to roll. */
    public boolean fixed() {
        return min == max;
    }
}
