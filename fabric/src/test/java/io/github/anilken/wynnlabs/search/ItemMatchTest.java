package io.github.anilken.wynnlabs.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Matching typed text: normalising, typos, mob names, stat names and the order of the tiers. */
class ItemMatchTest {

    private static List<Hit> search(String text) {
        return SearchQuery.of(text).run(SearchFixtures.index(), SearchQuery.Facts.NONE).hits();
    }

    private static int rank(List<Hit> hits, String name) {
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).entry().name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void normalising() {
        assertEquals("aberrants amulet", ItemMatch.normalize("Aberrant's  Amulet!"));
        assertEquals("7 yottabyte storage component", ItemMatch.normalize("7-Yottabyte Storage Component"));
        assertEquals("cafe", ItemMatch.normalize("Café"));
        assertEquals("isik", ItemMatch.normalize("Işık"));
        assertEquals("mana Steal", ItemMatch.splitKey("manaSteal"));
        assertEquals("raw 1st Spell Cost", ItemMatch.splitKey("raw1stSpellCost"));
    }

    @Test
    void typoFindsPapyrus() {
        List<Hit> hits = search("papirus");
        int at = rank(hits, "Papyrus");
        assertTrue(at >= 0 && at < 3, "Papyrus at " + at);
        assertEquals(Hit.Match.TYPO, hits.get(at).match());
        // a typo in the start of the word as it is being typed ("papir" is one letter from "Paper" too)
        int typing = rank(search("papir"), "Papyrus");
        assertTrue(typing >= 0 && typing < 3, "Papyrus at " + typing);
    }

    @Test
    void typoNeedsFourLetters() {
        assertEquals(-1, rank(search("pap"), "Abdomen"));
        assertEquals(-1, rank(search("pyp"), "Papyrus"));
    }

    @Test
    void mobNameFindsItsIngredient() {
        List<Hit> hits = search("obelisk");
        int at = rank(hits, "Accursed Effigy");
        assertTrue(at >= 0);
        assertEquals(Hit.Match.MOB, hits.get(at).match());
        assertEquals("Obelisk Of Inversion", hits.get(at).detail());
    }

    @Test
    void statNameFindsItems() {
        List<Hit> hits = search("mana steal");
        assertTrue(rank(hits, "Accursed Effigy") >= 0);
        assertTrue(rank(hits, "About-Face") >= 0);
        Hit h = hits.get(rank(hits, "About-Face"));
        assertEquals(Hit.Match.STAT, h.match());
        assertEquals("manaSteal", h.detail());
    }

    @Test
    void exactAndPrefixOutrankTypos() {
        List<Hit> hits = search("abdomen");
        assertEquals("Abdomen", hits.get(0).entry().name());
        assertEquals(Hit.Match.EXACT, hits.get(0).match());
        for (Hit h : hits) {
            if (h.match() == Hit.Match.TYPO) {
                assertTrue(h.score() < ItemMatch.SUBSTRING);
            }
        }
        // word prefixes in any order
        assertEquals("Aberrant's Amulet", search("amu aber").get(0).entry().name());
    }

    @Test
    void distance() {
        assertEquals(1, ItemMatch.distance("papirus", "papyrus", 7, 1));
        assertEquals(1, ItemMatch.distance("papyrsu", "papyrus", 7, 1));     // a swap counts once
        assertEquals(Integer.MAX_VALUE, ItemMatch.distance("abcdef", "uvwxyz", 6, 2));
    }
}
