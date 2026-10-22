package io.github.anilken.wynnlabs.game;

import io.github.anilken.wynnlabs.advice.AutoAdvice;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.model.BaseStats;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.Requirement;
import io.github.anilken.wynnlabs.model.Slot;
import io.github.anilken.wynnlabs.model.Tier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Time comes from the state's clock: the same "ago" texts and price ages every run. */
class ClockTest {

    private static final long DAY = 86_400_000L;

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    /** The synthetic state with a Mage wand in bag slot 5 whose price was seen {@code daysOld} ago. */
    private static FixtureState state(Path dir, double daysOld) throws Exception {
        TestGame.boot();
        Fixture.Data d = SyntheticData.data("a1b2c3d4", "e5f60718", "Somebody", "Fluffy");
        long now = d.state().recordedAt();
        ItemData wand = new ItemData("Old Wand", Slot.WAND, Tier.UNIQUE, 1,
                new Requirement(10, Optional.of(Klass.MAGE), List.of(), Optional.empty()),
                new BaseStats(20, 0, Optional.of("NORMAL"), Optional.empty(), List.of(), List.of()), List.of(),
                Optional.empty(), "NONE");
        ItemView view = new ItemView(ItemView.Kind.GEAR, "GearItem", false, null, null, null, wand,
                new ItemView.Rolled(List.of(), List.of(), 0, null, false,
                        new ItemView.Marks(true, true, Map.of(), false)),
                null, null, null, null, null, null, null, null, null);
        List<Fixture.Held> stacks = new ArrayList<>(d.items().stacks());
        stacks.add(new Fixture.Held("Old Wand", 1, view));
        List<Integer> inventory = new ArrayList<>(d.items().inventory());
        inventory.set(5, stacks.size() - 1);
        Fixture.Items items = new Fixture.Items(stacks, inventory, d.items().armour(), d.items().accessories(),
                d.items().mainHand(), d.items().bank());
        Map<String, Prices.Price> prices = new LinkedHashMap<>(d.prices());
        prices.put("Old Wand", new Prices.Price(500, null, null, null, null, now - (long) (daysOld * DAY), null, null));
        // listed three hours before the recording, count unknown: "last listing 3 h ago"
        prices.put("Keen Measure", new Prices.Price(320, null, null, null, null, now - DAY, now - 3 * 3_600_000L, null));
        Fixture.write(new Fixture.Data(d.state(), items, d.gearDb(), prices, d.rules(), d.settings()), dir, null);
        FixtureState fx = FixtureState.load(dir);
        fx.install("en");
        return fx;
    }

    @Test
    void agoTextsUseTheStateClock(@TempDir Path tmp) throws Exception {
        state(tmp, 1);
        Prices.Price p = GameState.current().price("Keen Measure");
        String first = p.when();
        assertEquals(L.t("wynnlabs.price.last_listing", L.t("wynnlabs.price.hours", 3)), first);
        assertEquals(first, p.when());
    }

    @Test
    void weekOldPricesNoLongerCount(@TempDir Path tmp) throws Exception {
        FixtureState fresh = state(tmp.resolve("fresh"), 1);
        String known = AutoAdvice.decide(fresh.bag().get(5)).note();
        assertEquals(known, AutoAdvice.decide(fresh.bag().get(5)).note());
        FixtureState.uninstall();

        FixtureState old = state(tmp.resolve("old"), 8);
        String stale = AutoAdvice.decide(old.bag().get(5)).note();
        assertEquals(stale, AutoAdvice.decide(old.bag().get(5)).note());
        // 500 E is below the Trade Market minimum: the Blacksmith with the price, then without it
        assertEquals(L.t("wynnlabs.auto.sell_blacksmith_market_price",
                L.t("wynnlabs.auto.wrong_class", "Mage", "Shaman"), Prices.fmt(500)), known);
        assertNotEquals(known, stale);
        assertEquals(L.t("wynnlabs.auto.wrong_class", "Mage", "Shaman") + ". "
                + L.t("wynnlabs.auto.sell_blacksmith_no_price"), stale);
    }
}
