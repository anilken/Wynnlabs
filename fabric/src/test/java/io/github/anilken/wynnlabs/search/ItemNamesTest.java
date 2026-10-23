package io.github.anilken.wynnlabs.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The search key over a hovered item: the slot's name as shown, matched to the index's item. */
class ItemNamesTest {

    @Test
    void cleansWhatSlotsShow() {
        assertEquals("Papyrus", ItemNames.clean("§5Papyrus"));
        assertEquals("Papyrus", ItemNames.clean("§7Unidentified §5Papyrus"));
        assertEquals("Lucky Rabbit's Foot", ItemNames.clean("§7Lucky Rabbit’s Foot §6[§e✫✫§8✫§6]"));
        assertEquals("Nirvana", ItemNames.clean("Shiny Nirvana"));
        assertEquals("Decrepit Sewers Key", ItemNames.clean("DecrepitÀÀÀSewers Key"));
        assertEquals("Abdomen", ItemNames.clean(" Abdomen "));
        assertEquals("", ItemNames.clean(null));
    }

    @Test
    void findsTheEntryInAnyCase() {
        ItemIndex idx = SearchFixtures.index();
        assertEquals("Papyrus", ItemNames.find(idx, "§5Unidentified Papyrus").name());
        assertEquals("Accursed Effigy", ItemNames.find(idx, "accursed effigy [✫✫✫]").name());
        assertNull(ItemNames.find(idx, "§fA Stick"));
        assertNull(ItemNames.find(idx, ""));
    }
}
