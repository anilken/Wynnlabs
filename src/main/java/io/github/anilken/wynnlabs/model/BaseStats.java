package io.github.anilken.wynnlabs.model;

import java.util.List;
import java.util.Optional;

/**
 * A piece's stats that never roll: average DPS and damage per element for weapons, health and
 * defences for armour. Elements and damage types by their upper-case names ("NEUTRAL", "FIRE"),
 * attack speed like "VERY_SLOW".
 */
public record BaseStats(int averageDps, int health, Optional<String> attackSpeed, Optional<String> majorId,
        List<Damage> damages, List<Defence> defences) {

    /** Damage of one type, low to high. */
    public record Damage(String type, int low, int high) {}

    /** Defence against one element. */
    public record Defence(String element, int value) {}
}
