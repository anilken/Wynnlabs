package io.github.anilken.wynnlabs.model;

/** A value out of a maximum (XP in the level, health, mana, an objective's score). */
public record Capped(int current, int max) {

    public static final Capped EMPTY = new Capped(0, 0);

    /** Percent of the maximum, rounded to a whole number; an empty value counts as full. */
    public int percentInt() {
        double pct = max == 0 ? 100.0 : (double) current / max * 100.0;
        return Math.round((float) pct);
    }
}
