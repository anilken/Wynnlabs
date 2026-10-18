package io.github.anilken.wynnlabs.crafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The crafting calculator: a recipe, its two material tiers, an attack speed and the six grid
 * slots in, the crafted item's numbers out, as the game works them out. Plain integer maths
 * with no Minecraft types, so every rounding is exact and the tests run anywhere.
 *
 * <p>The grid is two columns of three rows: slot {@code n} is at row {@code n / 2}, column
 * {@code n % 2}.
 */
public final class Craft {

    public static final int SLOTS = 6;

    /** Material tier weights in hundredths: one star 1.0, two 1.25, three 1.4. */
    private static final int[] TIER_WEIGHT = {0, 100, 125, 140};

    /** A crafted weapon's attack speed and its damage factor as a fraction. */
    public enum Speed {
        SLOW(41, 30), NORMAL(1, 1), FAST(41, 50);

        final int num;
        final int den;

        Speed(int num, int den) {
            this.num = num;
            this.den = den;
        }
    }

    /** A closed range of whole numbers. */
    public record Range(int min, int max) {
        public static final Range ZERO = new Range(0, 0);

        Range plus(Range o) {
            return new Range(min + o.min, max + o.max);
        }

        Range plus(int n) {
            return new Range(min + n, max + n);
        }

        public double mean() {
            return (min + max) / 2.0;
        }
    }

    /**
     * What a craft gives. Ranges that do not apply to the item type are null: health for
     * weapons, damage for armour and accessories, durability for consumables, duration for
     * gear. {@code damage} is the base damage after tiers and attack speed; its low and high
     * ends each roll within {@code damageLow} and {@code damageHigh}. {@code requirements} are
     * the raw sums in strength, dexterity, intelligence, defence, agility order (0 or less
     * means none). {@code ids} keep the order the ingredients bring them in.
     */
    public record Result(int[] effectiveness, Map<String, Range> ids, int[] requirements, Range health,
                         Range damage, Range damageLow, Range damageHigh, Range durability, Range duration,
                         int charges, int powderSlots, int level, int materialWeight) {}

    private Craft() {}

    /** The material weighting in hundredths: {@code (w1·a1 + w2·a2) / (a1 + a2)} kept as numerator/denominator. */
    static int[] weight(CraftData.Recipe recipe, int tier1, int tier2) {
        int a1 = recipe.materials().get(0).amount();
        int a2 = recipe.materials().get(1).amount();
        return new int[] {TIER_WEIGHT[clampTier(tier1)] * a1 + TIER_WEIGHT[clampTier(tier2)] * a2, 100 * (a1 + a2)};
    }

    /** The weighting as a plain number in hundredths, rounded, for showing ("×1.30" is 130). */
    public static int weightPercent(CraftData.Recipe recipe, int tier1, int tier2) {
        int[] w = weight(recipe, tier1, tier2);
        return roundDiv(w[0] * 100L, w[1]);
    }

    /**
     * Each slot's effectiveness in percent: 100, plus every other ingredient's position effects
     * on it. Empty slots get effects too (the picker previews them) but give nothing.
     */
    public static int[] effectiveness(List<Ingredient> slots) {
        int[] eff = new int[SLOTS];
        java.util.Arrays.fill(eff, 100);
        for (int from = 0; from < SLOTS; from++) {
            Ingredient ing = at(slots, from);
            if (ing.empty() || !ing.meta()) {
                continue;
            }
            int[] p = ing.positions();
            int row = from / 2;
            int col = from % 2;
            for (int to = 0; to < SLOTS; to++) {
                if (to == from) {
                    continue;
                }
                int r = to / 2;
                int c = to % 2;
                boolean touching = (r == row && Math.abs(c - col) == 1) || (c == col && Math.abs(r - row) == 1);
                int add = 0;
                if (c == col && r < row) {
                    add += p[Ingredient.ABOVE];
                }
                if (c == col && r > row) {
                    add += p[Ingredient.UNDER];
                }
                if (r == row && c < col) {
                    add += p[Ingredient.LEFT];
                }
                if (r == row && c > col) {
                    add += p[Ingredient.RIGHT];
                }
                add += touching ? p[Ingredient.TOUCHING] : p[Ingredient.NOT_TOUCHING];
                eff[to] += add;
            }
        }
        return eff;
    }

    /** One ingredient's identification range at an effectiveness: each end floored toward −∞, swapped when inverted. */
    public static Range scaled(Ingredient.Stat stat, int eff) {
        int a = Math.floorDiv(stat.min() * eff, 100);
        int b = Math.floorDiv(stat.max() * eff, 100);
        return new Range(Math.min(a, b), Math.max(a, b));
    }

    /** One requirement at an effectiveness, rounded half up. */
    static int scaledRequirement(int req, int eff) {
        return Math.floorDiv(2 * req * eff + 100, 200);
    }

    public static Result craft(CraftData.Recipe recipe, int tier1, int tier2, Speed speed, List<Ingredient> slots) {
        int[] eff = effectiveness(slots);
        int[] w = weight(recipe, tier1, tier2);
        boolean consumable = recipe.consumable();

        Map<String, Range> ids = new LinkedHashMap<>();
        int[] reqs = new int[5];
        int durabilityMods = 0;
        int chargeMods = 0;
        int durationMods = 0;
        int placed = 0;
        for (int i = 0; i < SLOTS; i++) {
            Ingredient ing = at(slots, i);
            if (ing.empty()) {
                continue;
            }
            placed++;
            for (Map.Entry<String, Ingredient.Stat> e : ing.ids().entrySet()) {
                ids.merge(e.getKey(), scaled(e.getValue(), eff[i]), Range::plus);
            }
            if (!consumable) {
                for (int s = 0; s < 5; s++) {
                    reqs[s] += scaledRequirement(ing.requirements()[s], eff[i]);
                }
            }
            durabilityMods += ing.durability();
            chargeMods += ing.charges();
            durationMods += ing.duration();
        }
        // An identification that rounds away to nothing on every roll is not on the item.
        ids.values().removeIf(r -> r.min() == 0 && r.max() == 0);

        int band = band(recipe);
        Range health = null;
        Range damage = null;
        Range damageLow = null;
        Range damageHigh = null;
        Range durability = null;
        Range duration = null;
        int charges = 0;
        int[] hd = recipe.healthOrDamage();
        if (Types.weapon(recipe.type()) && hd != null) {
            int lo = floorDiv((long) floorDiv((long) hd[0] * w[0], w[1]) * speed.num, speed.den);
            int hi = floorDiv((long) floorDiv((long) hd[1] * w[0], w[1]) * speed.num, speed.den);
            damage = new Range(lo, hi);
            damageLow = new Range(floorDiv(lo * 9L, 10), floorDiv(lo * 11L, 10));
            damageHigh = new Range(floorDiv(hi * 9L, 10), floorDiv(hi * 11L, 10));
        } else if (hd != null && !(consumable && placed > 0)) {
            health = new Range(floorDiv((long) hd[0] * w[0], w[1]), floorDiv((long) hd[1] * w[0], w[1]));
        }
        if (consumable) {
            // A consumable with no ingredient heals, lasts the recipe's short base duration and has
            // three charges; any ingredient makes it an effect one: the long duration, no heal,
            // charges by level band plus the ingredients' changes.
            int[] base = placed == 0 ? recipe.basicDuration() : recipe.duration();
            if (base != null) {
                duration = new Range(roundDiv((long) base[0] * w[0], w[1]), roundDiv((long) base[1] * w[0], w[1]))
                        .plus(durationMods);
            }
            charges = placed == 0 ? 3 : Math.max(1, band + chargeMods);
        } else if (recipe.durability() != null) {
            int[] d = recipe.durability();
            durability = new Range(roundDiv((long) d[0] * w[0], w[1]), roundDiv((long) d[1] * w[0], w[1]))
                    .plus(durabilityMods);
        }
        int powder = consumable || Types.accessory(recipe.type()) ? 0 : band;
        return new Result(eff, Collections.unmodifiableMap(ids), reqs, health, damage, damageLow, damageHigh,
                durability, duration, charges, powder, recipe.maxLevel(), roundDiv(w[0] * 100L, w[1]));
    }

    /**
     * The level band for charges and powder slots: brackets starting below 30 give 1, below 70
     * give 2, the rest 3. The game's brackets start a new decade at 30 and 70, so the start of
     * the bracket decides.
     */
    static int band(CraftData.Recipe recipe) {
        return recipe.minLevel() < 30 ? 1 : recipe.minLevel() < 70 ? 2 : 3;
    }

    /** The six slots, padded with empty ones, for callers that place fewer. */
    public static List<Ingredient> slots(Ingredient... placed) {
        List<Ingredient> out = new ArrayList<>(SLOTS);
        for (int i = 0; i < SLOTS; i++) {
            out.add(i < placed.length && placed[i] != null ? placed[i] : Ingredient.NONE);
        }
        return out;
    }

    private static Ingredient at(List<Ingredient> slots, int i) {
        Ingredient ing = i < slots.size() ? slots.get(i) : null;
        return ing == null ? Ingredient.NONE : ing;
    }

    private static int clampTier(int t) {
        return Math.max(1, Math.min(3, t));
    }

    private static int floorDiv(long a, long b) {
        return (int) Math.floorDiv(a, b);
    }

    /** a / b rounded half up. */
    private static int roundDiv(long a, long b) {
        return (int) Math.floorDiv(2 * a + b, 2 * b);
    }
}
