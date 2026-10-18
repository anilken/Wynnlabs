package io.github.anilken.wynnlabs.crafting;

import java.util.List;

/**
 * The chance of each value an identification can roll on a craft. Each ingredient rolls a step
 * from 0 to 100 across its range, rounded half up, and the step is scaled by its slot's
 * effectiveness (floored toward −∞); the item's value is the sum, so its chances are the
 * convolution of the ingredients' chances. Ranges are small, so this is exact.
 */
public final class Rolls {

    private static final int STEPS = 101;

    /** Chances of the whole numbers from {@code low} up; {@code p[i]} is the chance of {@code low + i}. */
    public record Dist(int low, double[] p) {

        public static final Dist ZERO = new Dist(0, new double[] {1.0});

        public int high() {
            return low + p.length - 1;
        }

        public double mean() {
            double m = 0;
            for (int i = 0; i < p.length; i++) {
                m += (low + i) * p[i];
            }
            return m;
        }

        public double exactly(int value) {
            int i = value - low;
            return i < 0 || i >= p.length ? 0 : p[i];
        }

        public double atLeast(int value) {
            double s = 0;
            for (int i = Math.max(0, value - low); i < p.length; i++) {
                s += p[i];
            }
            return Math.min(1.0, s);
        }
    }

    private Rolls() {}

    /** The value an ingredient gives at roll step {@code k} (0-100) and an effectiveness. */
    static int value(Ingredient.Stat stat, int k, int eff) {
        int rolled = (int) Math.floorDiv(2L * (100L * stat.min() + (long) (stat.max() - stat.min()) * k) + 100, 200);
        return Math.floorDiv(rolled * eff, 100);
    }

    /** One ingredient's chances for an identification at an effectiveness. */
    public static Dist slot(Ingredient.Stat stat, int eff) {
        Craft.Range r = Craft.scaled(stat, eff);
        double[] p = new double[r.max() - r.min() + 1];
        for (int k = 0; k < STEPS; k++) {
            p[value(stat, k, eff) - r.min()] += 1.0 / STEPS;
        }
        return new Dist(r.min(), p);
    }

    public static Dist convolve(Dist a, Dist b) {
        double[] out = new double[a.p.length + b.p.length - 1];
        for (int i = 0; i < a.p.length; i++) {
            if (a.p[i] == 0) {
                continue;
            }
            for (int j = 0; j < b.p.length; j++) {
                out[i + j] += a.p[i] * b.p[j];
            }
        }
        return new Dist(a.low + b.low, out);
    }

    /** The chances of one identification ({@code "spellDamage"}) on a layout. */
    public static Dist of(String id, List<Ingredient> slots) {
        int[] eff = Craft.effectiveness(slots);
        Dist d = Dist.ZERO;
        for (int i = 0; i < Craft.SLOTS && i < slots.size(); i++) {
            Ingredient ing = slots.get(i);
            Ingredient.Stat s = ing == null ? null : ing.ids().get(id);
            if (s != null) {
                d = convolve(d, slot(s, eff[i]));
            }
        }
        return d;
    }
}
