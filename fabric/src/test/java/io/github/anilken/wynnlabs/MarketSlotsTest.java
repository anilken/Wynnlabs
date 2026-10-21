package io.github.anilken.wynnlabs;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How many Trade Market orders a rank allows, and which items get them when there are more. */
class MarketSlotsTest {

    private static MarketSlots.Candidate c(String id, double gain) {
        return new MarketSlots.Candidate(id, gain, 1);
    }

    @Test
    void slotsFollowTheRank() {
        assertEquals(5, MarketSlots.forRank("NONE"));
        assertEquals(5, MarketSlots.forRank("VIP"));
        assertEquals(10, MarketSlots.forRank("VIP_PLUS"));
        assertEquals(15, MarketSlots.forRank("HERO"));
        assertEquals(18, MarketSlots.forRank("HERO_PLUS"));
        assertEquals(21, MarketSlots.forRank("CHAMPION"));
        assertEquals(5, MarketSlots.forRank(null), "not read yet");
        assertEquals(5, MarketSlots.forRank("ADMIN"), "not a supporter rank");
    }

    @Test
    void theItemsThatBringInTheMostKeepTheirOrders() {
        Map<Object, MarketSlots.Seat> seats = MarketSlots.rank(List.of(
                c("cheap", 100), c("unknown", Double.NaN), c("best", 9000), c("middle", 2000)), 2);
        assertEquals(new MarketSlots.Seat(1, true), seats.get("best"));
        assertEquals(new MarketSlots.Seat(2, true), seats.get("middle"));
        assertEquals(new MarketSlots.Seat(3, false), seats.get("cheap"));
        assertEquals(new MarketSlots.Seat(4, false), seats.get("unknown"), "unknown worth ranks last");
    }

    @Test
    void eachCopyNeedsItsOwnOrder() {
        // three identical boxes kept in one bank slot take three orders
        Map<Object, MarketSlots.Seat> seats = MarketSlots.rank(List.of(
                new MarketSlots.Candidate("boxes", 5000, 3), c("ring", 1000)), 3);
        assertEquals(new MarketSlots.Seat(1, true), seats.get("boxes"));
        assertEquals(new MarketSlots.Seat(4, false), seats.get("ring"));
    }

    @Test
    void equalWorthKeepsTheBagFirst() {
        Map<Object, MarketSlots.Seat> seats = MarketSlots.rank(List.of(c("bag", 500), c("bank", 500)), 1);
        assertTrue(seats.get("bag").fits());
        assertFalse(seats.get("bank").fits());
    }

    @Test
    void theNoteSaysWhy() {
        assertEquals("Sell to the Blacksmith, Trade Market full: 15 slots, this is 18th",
                MarketSlots.note(15, 18));
        assertEquals("Sell to the Blacksmith, Trade Market full: 1 slot, this is 2nd",
                MarketSlots.note(1, 2));
        assertEquals("Blacksmith", Advice.sellPlace(new Advice.Entry(Verdict.SELL, MarketSlots.note(15, 18))));
        assertEquals("21st", MarketSlots.ordinal(21));
        assertEquals("12th", MarketSlots.ordinal(12));
        assertEquals("103rd", MarketSlots.ordinal(103));
    }

    @Test
    void theNoteInTurkish() {
        com.google.gson.JsonObject tr = new com.google.gson.JsonObject();
        tr.addProperty("langOverride", 2);
        Settings.use(tr);
        try {
            assertEquals("Blacksmith'e sat, Trade Market dolu: 15 ilan hakkı, bu 18.",
                    MarketSlots.note(15, 18));
            assertEquals("Blacksmith", Advice.sellPlace(new Advice.Entry(Verdict.SELL, MarketSlots.note(15, 18))));
        } finally {
            Settings.use(new com.google.gson.JsonObject());
        }
    }
}
