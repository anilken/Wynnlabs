package io.github.anilken.wynnlabs.search;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Item pages as wynncraft.wiki.gg writes them (trimmed copies in test resources/items). */
class ItemWikiTest {

    private static ItemWiki.Info page(String item) throws Exception {
        String text = Files.readString(Path.of(ItemWikiTest.class.getResource("/items/" + item + ".txt").toURI()),
                StandardCharsets.UTF_8);
        return ItemWiki.parse(item, text);
    }

    @Test
    void dungeonMerchantCostFromTheIdTemplate() throws Exception {
        ItemWiki.Info i = page("Heat Sink");
        assertEquals("Dungeon Merchant, (Fallen Factory)", i.obtain());
        assertEquals(List.of(new ItemWiki.Cost("Fallen Factory Dungeon Merchant", "4 Antikythera Supercomputer's Chips")), i.costs());
        assertTrue(i.preIdentified());
        assertEquals(0, i.identifyCost());
        assertEquals(0, i.mobMin());
        assertEquals("", i.event());
    }

    @Test
    void eventItemWithAYearRoundMerchant() throws Exception {
        ItemWiki.Info i = page("Epilogue");
        assertEquals(List.of(
                new ItemWiki.Cost("Talespun Merchant on the Isles of Fiction", "7 Liquid Emeralds and 32 Emerald Blocks"),
                new ItemWiki.Cost("Equipment Merchant", "12 Talespun Memories")), i.costs(),
                "the festival merchant's \"in exchange for Talespun Memories\" has no number and is left out");
        assertEquals("Festival of the Heroes", i.event());
        assertTrue(i.preIdentified());
    }

    @Test
    void normalDropGivesMobBandAndIdentifyCost() throws Exception {
        ItemWiki.Info i = page("Papyrus");
        assertEquals("Loot Chest, Mob Drop", i.obtain());
        assertEquals(73, i.mobMin());
        assertEquals(81, i.mobMax());
        assertEquals(101, i.identifyCost(), "the page shows \"It costs 101 Emeralds to identify\" (rare, level 77)");
        assertFalse(i.preIdentified());
        assertTrue(i.costs().isEmpty());
    }

    @Test
    void ingredientObtainingSectionAndWorldEvent() throws Exception {
        ItemWiki.Info i = page("Lucky Rabbit's Foot");
        assertEquals("Lucky Rabbit's Foot is dropped by various Rare Mobs in the Rymek Mesa. It can also be found in Loot Chests.",
                i.obtain());
        assertEquals(List.of("Encroaching Conflagration"), i.worldEvents());
        assertTrue(i.costs().isEmpty());
        assertEquals(0, i.identifyCost());
        assertEquals("", i.event());
    }

    @Test
    void identifyCostFollowsTheTemplateFormulas() {
        assertEquals("It costs 101 Emeralds to identify X.", ItemWiki.idCost("X", "{{IDcost|r|77}}"));
        assertEquals("It costs 17 Emeralds to identify X.", ItemWiki.idCost("X", "{{IDcost|legendary|1}}"));
        assertEquals("It costs 38 Emeralds to identify X.", ItemWiki.idCost("X", "{{IDcost|u|70}}"));
        assertEquals("It costs 250 Emeralds to identify X.", ItemWiki.idCost("X", "{{IDcost|c|250}}"));
    }

    @Test
    void aPageWithNothingUsefulIsEmpty() {
        assertTrue(ItemWiki.parse("Nothing", "'''Nothing''' is a page.\n[[Category:Items]]").empty());
    }

    @Test
    void offSwitchMakesNoCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ItemWiki.Info i = ItemWiki.load("Off Switch Item", false, t -> {
            calls.incrementAndGet();
            return "";
        }).get(1, TimeUnit.SECONDS);
        assertSame(ItemWiki.Info.EMPTY, i);
        assertEquals(0, calls.get());
    }

    @Test
    void failureIsEmptyAndRememberedForTheSession() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        java.util.function.Function<String, String> offline = t -> {
            calls.incrementAndGet();
            throw new IllegalStateException("no network");
        };
        assertSame(ItemWiki.Info.EMPTY, ItemWiki.load("Offline Item", true, offline).get(5, TimeUnit.SECONDS));
        assertSame(ItemWiki.Info.EMPTY, ItemWiki.load("Offline Item", true, offline).get(5, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
    }
}
