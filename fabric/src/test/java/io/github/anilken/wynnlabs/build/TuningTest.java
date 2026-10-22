package io.github.anilken.wynnlabs.build;

import io.github.anilken.wynnlabs.build.roadmap.Roadmap;
import io.github.anilken.wynnlabs.build.roadmap.RoadmapLocksTest;
import io.github.anilken.wynnlabs.game.Expected;
import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;
import io.github.anilken.wynnlabs.game.GameState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tuning strip on the recorded Shaman: Balanced with nothing on and no budget is the road map
 * as before, Damage and Tanky lean the sets their way, and a budget keeps a piece that costs more
 * out of every milestone.
 */
class TuningTest {

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    private static Roadmap.Computed run(Tuning.Choice t) {
        return Roadmap.compute(new Roadmap.Inputs(List.of(), t, false, 0), null);
    }

    @Test
    void balancedWithNoBudgetIsTodaysMap() throws Exception {
        java.nio.file.Path dir = RoadmapLocksTest.shaman();
        FixtureState.load(dir).install("en");
        List<String> diff = Expected.check(dir, "roadmap.en.json", Roadmap.json(), Expected::json);
        assertTrue(diff.isEmpty(), String.join("\n", diff));
        assertEquals(RoadmapLocksTest.sets(Roadmap.build()), RoadmapLocksTest.sets(run(Tuning.Choice.PLAIN).steps()));
        assertTrue(Tuning.Choice.PLAIN.plain() && Tuning.Choice.PLAIN.key().isEmpty());
        // outside a tuned run every weight is as the rules say
        assertEquals(1.0, Tuning.weight("survivalWeight"));
        assertEquals(1.0, Tuning.with(Tuning.Choice.PLAIN, () -> Tuning.weight("damageWeight")));
    }

    @Test
    void damageAndTankyLeanTheSets() throws Exception {
        FixtureState.load(RoadmapLocksTest.shaman()).install("en");
        List<Roadmap.Step> dmg = run(Tuning.Choice.PLAIN.withFocus(Tuning.DAMAGE)).steps();
        List<Roadmap.Step> tank = run(Tuning.Choice.PLAIN.withFocus(Tuning.TANKY)).steps();
        assertNotEquals(RoadmapLocksTest.sets(dmg), RoadmapLocksTest.sets(tank), "the two foci pick other sets");
        double dmgEhp = 0;
        double tankEhp = 0;
        double dmgDps = 0;
        double tankDps = 0;
        for (int i = 0; i < Math.min(dmg.size(), tank.size()); i++) {
            Damage.Result a = dmg.get(i).set().result();
            Damage.Result b = tank.get(i).set().result();
            dmgEhp += a.ehp();
            tankEhp += b.ehp();
            dmgDps += a.factors()[0];
            tankDps += b.factors()[0];
        }
        Fixtures.note(String.format(java.util.Locale.ROOT, "damage focus: dps %.0f ehp %.0f; tanky: dps %.0f ehp %.0f",
                dmgDps, dmgEhp, tankDps, tankEhp));
        assertTrue(tankEhp > dmgEhp, "Tanky wears more effective health");
        assertTrue(dmgDps > tankDps, "Damage deals more");
        // Mana and Speed weigh their stats more inside a run only
        Tuning.Choice ms = Tuning.Choice.PLAIN.withMana(true).withSpeed(true);
        assertTrue(Tuning.with(ms, () -> Tuning.weight("manaWeight")) > 1.5);
        assertTrue(Tuning.with(ms, () -> Tuning.weight("speedWeight")) > 1.5);
    }

    @Test
    void aBudgetLeavesOutWhatCostsMore() throws Exception {
        FixtureState.load(RoadmapLocksTest.shaman()).install("en");
        GameState state = GameState.current();
        // the dearest priced pick of the plain map
        String dear = null;
        double price = 0;
        for (Roadmap.Step s : run(Tuning.Choice.PLAIN).steps()) {
            for (Roadmap.Gear g : s.gear()) {
                double c = Roadmap.cost(state, g.info());
                if (c > price) {
                    price = c;
                    dear = g.info().name();
                }
            }
        }
        assertTrue(dear != null && price > 2, "a priced pick to leave out");
        long budget = (long) Math.floor(price / 2);
        Roadmap.Computed c = run(new Tuning.Choice(Tuning.BALANCED, false, false, budget));
        for (Roadmap.Step s : c.steps()) {
            for (Roadmap.Gear g : s.gear()) {
                assertNotEquals(dear, g.info().name(), "Lv " + s.level() + ": " + dear + " costs " + price + ", over " + budget);
                double cost = Roadmap.cost(state, g.info());
                assertTrue(cost >= 0 && cost <= budget, g.info().name() + " costs " + cost);
            }
        }
        assertFalse(c.budgetCut().isEmpty(), "the map says where the budget left something out");
    }
}
