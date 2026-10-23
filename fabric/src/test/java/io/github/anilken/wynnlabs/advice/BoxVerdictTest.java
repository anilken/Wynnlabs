package io.github.anilken.wynnlabs.advice;

import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.Slot;
import io.github.anilken.wynnlabs.model.Tier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The box window's verdict chip reads the tooltip's own decision: the same route and fee as
 * {@link BoxRoutes} gives for the {@link BoxRoutesTest} boxes, "Tanımla" for the Item Identifier
 * and the Trade Market's reveal, "Sat" for the Blacksmith, and the tooltip's line itself.
 */
class BoxVerdictTest {

    private static final ItemView.Box RARE_CHEST = new ItemView.Box(Slot.CHESTPLATE, Tier.RARE, 56, 60);

    @AfterEach
    void tearDown() {
        FixtureState.uninstall();
    }

    private static BoxRoutes.Outcome o(String name, int cost, Double price) {
        return new BoxRoutes.Outcome(name, cost, price, true, false);
    }

    private static List<BoxRoutes.Outcome> rareChest() {
        return List.of(o("Dravarden", Tier.RARE.identificationCost(56), 16.0 * 64),
                o("Hailstone", Tier.RARE.identificationCost(57), 16.0 * 64),
                o("Skien's Fatigues", Tier.RARE.identificationCost(59), 2.8 * 4096),
                o("Masochist", Tier.RARE.identificationCost(57), 46.0 * 64),
                o("Tarnkappe", Tier.RARE.identificationCost(56), 9.0 * 64));
    }

    private static BoxVerdict.Result of(BoxRoutes.Choice c) {
        Advice.Entry e = new Advice.Entry(Verdict.SELL, "tooltip line");
        return BoxVerdict.from(new BoxAdvice.Decision(e, c), RARE_CHEST);
    }

    @Test
    void routesMapToTheChip() {
        assertEquals(BoxVerdict.Label.IDENTIFY, BoxVerdict.label(BoxRoutes.Route.IDENTIFIER));
        assertEquals(BoxVerdict.Label.IDENTIFY, BoxVerdict.label(BoxRoutes.Route.REVEAL_MARKET));
        assertEquals(BoxVerdict.Label.SELL, BoxVerdict.label(BoxRoutes.Route.BLACKSMITH));
        assertEquals(BoxVerdict.Label.SELL, BoxVerdict.label(BoxRoutes.Route.UNTRADABLE));
    }

    @Test
    void aBoxWorthRevealingSaysIdentifyWithTheTooltipsValueAndFee() {
        BoxRoutes.Choice c = BoxRoutes.choose(rareChest(), 5, 640, false);
        BoxVerdict.Result r = of(c);
        assertEquals(BoxRoutes.Route.REVEAL_MARKET, c.route());
        assertEquals(BoxVerdict.Label.IDENTIFY, r.label());
        assertEquals("reveal_market", r.route());
        assertEquals(c.value().expected(), r.value());
        assertEquals(76, r.costLow());
        assertEquals(79, r.costHigh());
        assertEquals("tooltip line", r.note());
    }

    @Test
    void aCheapBoxSaysSell() {
        BoxRoutes.Choice c = BoxRoutes.choose(List.of(o("Boots A", 18, 10.0), o("Boots B", 18, 12.0)), 5, 640, false);
        BoxVerdict.Result r = of(c);
        assertEquals(BoxRoutes.Route.BLACKSMITH, c.route());
        assertEquals(BoxVerdict.Label.SELL, r.label());
        assertEquals(18, r.costLow());
    }

    @Test
    void aBoxYouWouldWearSaysIdentify() {
        List<BoxRoutes.Outcome> wear = List.of(new BoxRoutes.Outcome("Masochist", 77, 46.0 * 64, true, true));
        assertEquals(BoxVerdict.Label.IDENTIFY, of(BoxRoutes.choose(wear, 5, 640, false)).label());
    }

    @Test
    void theWindowAndTheTooltipAgreeOnAFixtureBox() throws Exception {
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("en");
        ItemView.Box box = new ItemView.Box(Slot.LEGGINGS, Tier.UNIQUE, 66, 70);
        BoxAdvice.Decision d = BoxAdvice.decide(box, Klass.SHAMAN, 44);
        BoxVerdict.Result r = BoxVerdict.of(box, Klass.SHAMAN, 44);
        assertEquals(BoxAdvice.box(box, Klass.SHAMAN, 44), d.entry());
        assertEquals(d.entry().note(), r.note());
        if (d.choice() == null) {
            assertNull(r.route());
        } else {
            assertEquals(BoxVerdict.label(d.choice().route()), r.label());
        }
    }
}
