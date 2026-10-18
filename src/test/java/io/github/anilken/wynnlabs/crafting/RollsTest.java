package io.github.anilken.wynnlabs.crafting;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static io.github.anilken.wynnlabs.crafting.Ings.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Roll chances: the published crafter's figures for the wand layout, and a brute-force check. */
class RollsTest {

    private static final double EPS = 1e-9;

    @Test
    void wandLayoutChances() {
        List<Ingredient> wand = wandLayout();
        Rolls.Dist mana = Rolls.of("manaRegen", wand);
        assertEquals("7.50", String.format(java.util.Locale.ROOT, "%.2f", mana.mean()), "757/101, shown to two places");
        assertEquals(50.0 / 101, mana.exactly(8), EPS);

        Rolls.Dist spell = Rolls.of("spellDamage", wand);
        assertEquals(-18.0, spell.mean(), 1e-6);
        assertEquals(13.0 / 101, spell.exactly(-16), EPS);
        assertEquals(13.0 / 101, spell.atLeast(-16), EPS);
        assertEquals(-20, spell.low());
        assertEquals(-16, spell.high());

        Rolls.Dist str = Rolls.of("rawStrength", wand);
        assertEquals("12.51", String.format(java.util.Locale.ROOT, "%.2f", str.mean()));
        assertEquals(51.0 / 101 * 26.0 / 101, str.exactly(14), EPS);

        assertEquals(11.0 / 101, Rolls.of("exploding", wand).exactly(15), EPS);
        assertEquals(2.0 / 101, Rolls.of("healthRegenRaw", wand).atLeast(-150), EPS);
        assertEquals(-1100.0, Rolls.of("rawHealth", wand).mean(), 1e-6);
        assertEquals(1.0, Rolls.of("rawHealth", wand).atLeast(-1200), EPS);
        assertEquals(0.0, Rolls.of("nothing", wand).mean(), EPS);
    }

    @Test
    void matchesBruteForce() {
        Ingredient.Stat a = new Ingredient.Stat(3, 9, "a", "");
        Ingredient.Stat b = new Ingredient.Stat(-5, -2, "b", "");
        Ingredient.Stat c = new Ingredient.Stat(10, 13, "c", "");
        int[] effs = {135, -40, 60};

        Map<Integer, Integer> counts = new TreeMap<>();
        for (int i = 0; i <= 100; i++) {
            for (int j = 0; j <= 100; j++) {
                for (int k = 0; k <= 100; k++) {
                    int v = Rolls.value(a, i, effs[0]) + Rolls.value(b, j, effs[1]) + Rolls.value(c, k, effs[2]);
                    counts.merge(v, 1, Integer::sum);
                }
            }
        }
        Rolls.Dist d = Rolls.convolve(Rolls.convolve(Rolls.slot(a, effs[0]), Rolls.slot(b, effs[1])), Rolls.slot(c, effs[2]));
        double total = Math.pow(101, 3);
        double mean = 0;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            assertEquals(e.getValue() / total, d.exactly(e.getKey()), 1e-12, "value " + e.getKey());
            mean += e.getKey() * e.getValue() / total;
        }
        assertEquals(mean, d.mean(), 1e-9);
        int lowest = counts.keySet().iterator().next();
        assertEquals(lowest, d.low());
        assertEquals(1.0, d.atLeast(lowest), 1e-12);
        int x = lowest + 3;
        double tail = 0;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getKey() >= x) {
                tail += e.getValue() / total;
            }
        }
        assertEquals(tail, d.atLeast(x), 1e-12);
    }
}
