package io.github.anilken.wynnlabs.market;

import io.github.anilken.wynnlabs.advice.Cautions;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.game.Fixture;
import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.game.SyntheticData;
import io.github.anilken.wynnlabs.game.TestGame;
import io.github.anilken.wynnlabs.model.BaseStats;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.Obtain;
import io.github.anilken.wynnlabs.model.Requirement;
import io.github.anilken.wynnlabs.model.Slot;
import io.github.anilken.wynnlabs.model.Tier;
import io.github.anilken.wynnlabs.quests.QuestBook;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How sure a price is, what a piece really costs, where it is had and what a quest gates. */
class AcquisitionTest {

    private static final long DAY = 86_400_000L;

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    private static ItemData boots(String name, String restriction, Optional<String> quest) {
        return new ItemData(name, Slot.BOOTS, Tier.LEGENDARY, 2,
                new Requirement(60, Optional.empty(), List.of(), quest),
                new BaseStats(900, 0, Optional.empty(), Optional.empty(), List.of(), List.of()), List.of(),
                Optional.empty(), restriction);
    }

    /** The synthetic state with these pieces, their sources, prices and the quest book. */
    private static void install(Path dir, Map<ItemData, List<Obtain>> gear, Map<String, Prices.Price> prices,
            List<QuestBook.Entry> quests) throws Exception {
        TestGame.boot();
        Fixture.Data d = SyntheticData.data("a1b2c3d4", "e5f60718", "Somebody", "Fluffy");
        Fixture.State s = d.state();
        Fixture.State state = new Fixture.State(s.format(), s.recordedAt(), s.inGame(), s.klass(), s.level(), s.characterId(),
                s.characters(), s.characterIds(), s.today(), s.skills(), s.skillsKnown(), s.unspent(), s.professions(),
                quests, s.abilityPlan(), s.position(), s.spots(), s.xpRates(), s.pricesInstalled(), s.selectedSlot());
        List<ItemData> items = new ArrayList<>(d.gearDb().gear());
        Map<String, List<Obtain>> obtain = new LinkedHashMap<>(d.gearDb().obtain());
        gear.forEach((info, from) -> {
            items.add(info);
            obtain.put(info.name(), from);
        });
        Fixture.GearDb db = new Fixture.GearDb(d.gearDb().format(), items, obtain, d.gearDb().boxes());
        Map<String, Prices.Price> all = new LinkedHashMap<>(d.prices());
        all.putAll(prices);
        Fixture.write(new Fixture.Data(state, d.items(), db, all, d.rules(), d.settings()), dir, null);
        FixtureState.load(dir).install("en");
    }

    private static Prices.Price price(int lowest, double middle, Long listed, Integer count) {
        return new Prices.Price(lowest, middle, middle, null, null, 1_759_999_000_000L, listed, count);
    }

    @Test
    void aPriceSaysHowSureItIsAndWhatBuyingCosts(@TempDir Path tmp) throws Exception {
        install(tmp, Map.of(), Map.of(), List.of());
        long now = GameState.current().now();
        // Aloof: one cheap listing seen once, nothing listed now: the middle of the market
        Prices.Price history = price(4096, 40960, null, 0);
        assertEquals(Prices.Confidence.HISTORY, history.confidence());
        assertTrue(history.uncertain());
        assertEquals(40960.0, history.buyPrice());
        Prices.Price stale = price(1000, 3000, now - 3 * DAY, 5);
        assertEquals(Prices.Confidence.STALE, stale.confidence());
        assertEquals(3000.0, stale.buyPrice());
        Prices.Price thin = price(1000, 3000, now - 3_600_000L, 2);
        assertEquals(Prices.Confidence.THIN, thin.confidence());
        assertFalse(thin.uncertain());
        assertEquals(1000.0, thin.buyPrice());
        assertEquals(Prices.Confidence.CACHE, price(1000, 3000, null, null).confidence());
        Prices.Price ok = price(1000, 3000, now - 3_600_000L, 9);
        assertEquals(Prices.Confidence.OK, ok.confidence());
        assertEquals(1000.0, ok.buyPrice());
        Prices.Price none = new Prices.Price(null, null, null, null, null, now, null, null);
        assertEquals(Prices.Confidence.NONE, none.confidence());
        assertNull(none.buyPrice());

        // how much a gain counts for while you would buy it soon
        assertEquals(0.6, Roadmap.availability(history));
        assertEquals(0.85, Roadmap.availability(stale));
        assertEquals(0.85, Roadmap.availability(thin));
        assertEquals(0.9, Roadmap.availability(price(1000, 3000, null, null)));
        assertEquals(1.0, Roadmap.availability(ok));
        assertEquals(1.0, Roadmap.availability(null));
    }

    @Test
    void anOldPriceCostsTheMiddleAndIsMarkedPast(@TempDir Path tmp) throws Exception {
        ItemData aloof = boots("Aloof", "NONE", Optional.empty());
        install(tmp, Map.of(aloof, List.of(new Obtain("LOOT_CHEST", Optional.empty()))),
                Map.of("Aloof", price(4096, 40960, null, 0)), List.of());
        assertEquals(40960.0, Roadmap.cost(GameState.current(), aloof));
        assertTrue(Roadmap.uncertainPrice(GameState.current(), aloof));
        assertEquals(Prices.past("~" + Prices.fmt(40960)), Prices.buyText("Aloof"));
    }

    @Test
    void theTradeMarketComesFirstWhenListed(@TempDir Path tmp) throws Exception {
        ItemData cloak = boots("Cloak of Luminosity", "NONE", Optional.empty());
        ItemData gone = boots("Gone Boots", "NONE", Optional.empty());
        ItemData admiral = boots("Admiral", "UNTRADABLE", Optional.empty());
        List<Obtain> quest = List.of(new Obtain("QUEST", Optional.of("Lights Out")));
        long now = 1_760_000_000_000L;
        install(tmp, Map.of(cloak, quest, gone, quest,
                        admiral, List.of(new Obtain("DUNGEON_MERCHANT", Optional.of("Ice Barrows")))),
                Map.of("Cloak of Luminosity", price(2000, 2500, now - 3_600_000L, 18),
                        "Gone Boots", price(2000, 2500, null, 0)), List.of());
        assertEquals("TRADE_MARKET", Shopping.sourceOf(cloak));
        assertEquals(L.t("wynnlabs.shop.where_market"), Shopping.where(Shopping.sourceOf(cloak)));
        assertEquals(Shopping.where("QUEST: Lights Out"), Shopping.alsoFrom(cloak));
        assertEquals("quest", Shopping.kind(cloak));            // the world source still decides
        // nothing listed now: the quest is the way
        assertEquals("QUEST: Lights Out", Shopping.sourceOf(gone));
        assertNull(Shopping.alsoFrom(gone));
        // an untradable dungeon merchant piece takes a run; a tradable one is bought
        assertTrue(Shopping.effort(admiral));
        assertFalse(Shopping.effort(cloak));
    }

    @Test
    void eventHidesSeasonalEventsNotWorldEvents(@TempDir Path tmp) throws Exception {
        ItemData world = boots("World Event Boots", "NONE", Optional.empty());
        ItemData seasonal = boots("Seasonal Boots", "NONE", Optional.empty());
        ItemData raid = boots("Raid Boots", "NONE", Optional.empty());
        install(tmp, Map.of(world, List.of(new Obtain("WORLD_EVENT", Optional.of("Ancient Nemract"))),
                        seasonal, List.of(new Obtain("EVENT", Optional.of("Wynnterfest"))),
                        raid, List.of(new Obtain("RAID", Optional.of("Nest of the Grootslangs")))),
                Map.of("Seasonal Boots", price(2000, 2500, 1_760_000_000_000L - 3_600_000L, 6),
                        "Raid Boots", price(2000, 2500, 1_760_000_000_000L - 3_600_000L, 6)), List.of());
        List<String> bad = List.of("UNAVAILABLE", "RAID", "EVENT");
        assertFalse(Shopping.excluded(world, bad));             // repeatable any time
        assertTrue(Shopping.excluded(seasonal, bad));           // seasonal, listed or not
        assertTrue(Shopping.excluded(raid, bad));               // raid rewards stay hidden anywhere
        assertFalse(Shopping.excluded(seasonal, List.of("RAID")));
    }

    @Test
    void aTownMerchantStaysFirst(@TempDir Path tmp) throws Exception {
        ItemData ornament = boots("Merchant Boots", "NONE", Optional.empty());
        install(tmp, Map.of(ornament, List.of(new Obtain("MERCHANT", Optional.of("Ragni")))),
                Map.of("Merchant Boots", price(2000, 2500, 1_760_000_000_000L - 3_600_000L, 6)), List.of());
        assertEquals("MERCHANT: Ragni", Shopping.sourceOf(ornament));
        assertEquals(L.t("wynnlabs.shop.where_market"), Shopping.alsoFrom(ornament));
    }

    @Test
    void aQuestNotDoneGatesThePiece(@TempDir Path tmp) throws Exception {
        ItemData sodeta = boots("Sodeta Boots", "NONE", Optional.of("Lost Soles"));
        ItemData royal = boots("Royal Boots", "NONE", Optional.of("WynnExcavation Site D"));
        ItemData free = boots("Free Boots", "NONE", Optional.of("Unknown Quest"));
        List<Obtain> chest = List.of(new Obtain("LOOT_CHEST", Optional.empty()));
        install(tmp, Map.of(sodeta, chest, royal, chest, free, chest), Map.of(), List.of(
                new QuestBook.Entry("Lost Soles", "CAN_START", 63, "LONG", 0, true, null, null, null, null, null,
                        1_759_000_000_000L, "QUEST", null),
                new QuestBook.Entry("WynnExcavation Site D", "COMPLETED", 52, "SHORT", 0, true, null, null, null, null,
                        null, 1_759_000_000_000L, "QUEST", null)));
        QuestBook.Entry pending = Shopping.questPending(GameState.current(), sodeta);
        assertEquals("Lost Soles", pending.name());
        assertEquals(63, pending.level());
        assertTrue(Cautions.of(sodeta).contains(L.t("wynnlabs.caution.quest_first", "Lost Soles")));
        assertNull(Shopping.questPending(GameState.current(), royal));     // done
        assertNull(Shopping.questPending(GameState.current(), free));      // the book does not know it
    }

    @Test
    void anEmptyQuestBookGatesNothing(@TempDir Path tmp) throws Exception {
        ItemData sodeta = boots("Sodeta Boots", "NONE", Optional.of("Lost Soles"));
        install(tmp, Map.of(sodeta, List.of(new Obtain("LOOT_CHEST", Optional.empty()))), Map.of(), List.of());
        assertNull(Shopping.questPending(GameState.current(), sodeta));
        assertFalse(Cautions.of(sodeta).contains(L.t("wynnlabs.caution.quest_first", "Lost Soles")));
    }
}
