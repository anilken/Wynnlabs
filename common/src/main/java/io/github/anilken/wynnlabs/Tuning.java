package io.github.anilken.wynnlabs;

import java.util.function.Supplier;

/**
 * The "Choose items" window's tuning strip: what the road map's set should lean towards (damage,
 * balanced or tanky; mana; walk speed) and the most its buys may cost. The leaning is a few
 * multipliers on the damage model's own weights (rules.model.*), in force only while the road
 * map is built ({@link #with}): Balanced with nothing on multiplies by 1, the scoring as before.
 * Saved per character with the chosen items ({@link Locks}).
 */
final class Tuning {

    static final int BALANCED = 0;
    static final int DAMAGE = 1;
    static final int TANKY = 2;

    /** The budget's steps in emeralds: no limit, 10 EB, 1 LE, 5 LE. */
    static final long[] BUDGETS = {0, 10 * 64, 4096, 5 * 4096};

    /**
     * One setting of the strip. {@code budget} in emeralds, 0 for no limit.
     */
    record Choice(int focus, boolean mana, boolean speed, long budget) {

        static final Choice PLAIN = new Choice(BALANCED, false, false, 0);

        /** Balanced, nothing on, no budget: the road map as it always was. */
        boolean plain() {
            return focus == BALANCED && !mana && !speed && budget <= 0;
        }

        /** The weights' part of it: Balanced with nothing on leaves every weight as it is. */
        boolean neutral() {
            return focus == BALANCED && !mana && !speed;
        }

        /** For the road map's cache keys; "" when plain. */
        String key() {
            return plain() ? "" : focus + (mana ? "m" : "") + (speed ? "s" : "") + "/" + budget;
        }

        Choice withFocus(int f) {
            return new Choice(f, mana, speed, budget);
        }

        Choice withMana(boolean on) {
            return new Choice(focus, on, speed, budget);
        }

        Choice withSpeed(boolean on) {
            return new Choice(focus, mana, on, budget);
        }

        /** The next budget step after this one (back to no limit after the last). */
        Choice nextBudget() {
            int at = 0;
            for (int i = 0; i < BUDGETS.length; i++) {
                if (BUDGETS[i] == budget) {
                    at = i;
                }
            }
            return new Choice(focus, mana, speed, BUDGETS[(at + 1) % BUDGETS.length]);
        }
    }

    private static final ThreadLocal<Choice> ACTIVE = new ThreadLocal<>();

    private Tuning() {}

    /** Runs {@code body} with {@code c}'s weights in force on this thread. */
    static <T> T with(Choice c, Supplier<T> body) {
        Choice before = ACTIVE.get();
        ACTIVE.set(c == null || c.neutral() ? null : c);
        try {
            return body.get();
        } finally {
            if (before == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(before);
            }
        }
    }

    /** The setting in force on this thread (for work handed to other threads); null when neutral. */
    static Choice active() {
        return ACTIVE.get();
    }

    /** The multiplier on the model weight {@code key} (rules.model.*); 1 outside {@link #with}. */
    static double weight(String key) {
        Choice c = ACTIVE.get();
        if (c == null) {
            return 1.0;
        }
        return switch (key) {
            // the damage total's exponent and how much effective health counts
            case "damageWeight" -> c.focus == DAMAGE ? 1.15 : c.focus == TANKY ? 0.85 : 1.0;
            // the goal already sets how much health counts: Damage leans a little, not by half again
            case "survivalWeight" -> c.focus == DAMAGE ? 0.75 : c.focus == TANKY ? 2.5 : 1.0;
            case "survivalPower" -> c.focus == DAMAGE ? 0.75 : c.focus == TANKY ? 1.5 : 1.0;
            case "ehpFloorScale" -> c.focus == DAMAGE ? 0.9 : c.focus == TANKY ? 1.25 : 1.0;
            // mana: keeping up the casts, and what mana steal brings back
            case "manaWeight" -> c.mana ? 3.0 : 1.0;
            case "manaStealFactor" -> c.mana ? 2.0 : 1.0;
            // walk speed: its weight and the room it may add
            case "speedWeight" -> c.speed ? 3.0 : 1.0;
            case "goalSpeedRoom" -> c.speed ? 2.0 : 1.0;
            default -> 1.0;
        };
    }
}
