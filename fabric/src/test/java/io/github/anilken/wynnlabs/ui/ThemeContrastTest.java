package io.github.anilken.wynnlabs.ui;

import net.minecraft.ChatFormatting;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The J menu's ink must read on its paper: 4.5:1 for body text, 3:1 for secondary text. */
class ThemeContrastTest {

    private static final int[] BACKGROUNDS = {Theme.PAPER, Theme.CARD, Theme.CARD_LOW, Theme.PLAQUE};

    private static void reads(String name, int colour, double need) {
        for (int bg : BACKGROUNDS) {
            double c = Theme.contrast(colour, bg);
            assertTrue(c >= need, String.format("%s #%06X on #%06X is %.2f:1, needs %.1f:1",
                    name, colour & 0xFFFFFF, bg & 0xFFFFFF, c, need));
        }
    }

    @Test
    void bodyTokensRead() {
        reads("INK", Theme.INK, 4.5);
        reads("INK_2", Theme.INK_2, 4.5);
        reads("GOOD", Theme.GOOD, 4.5);
        reads("BAD", Theme.BAD, 4.5);
        reads("WARN", Theme.WARN, 4.5);
        reads("LINK", Theme.LINK, 4.5);
        reads("PRICE", Theme.PRICE, 4.5);
        reads("ACCENT", Theme.ACCENT, 4.5);
        reads("TIER_LEGENDARY", Theme.TIER_LEGENDARY, 4.5);
        reads("TIER_RARE", Theme.TIER_RARE, 4.5);
        reads("TIER_UNIQUE", Theme.TIER_UNIQUE, 4.5);
        reads("TIER_MYTHIC", Theme.TIER_MYTHIC, 4.5);
        reads("TIER_FABLED", Theme.TIER_FABLED, 4.5);
        reads("TIER_SET", Theme.TIER_SET, 4.5);
    }

    @Test
    void secondaryTokensRead() {
        reads("INK_3", Theme.INK_3, 3.0);
    }

    @Test
    void everyChatColourReadsOnPaper() {
        for (ChatFormatting f : ChatFormatting.values()) {
            if (f.isColor()) {
                reads(f.getName(), Theme.paper(f), 3.0);
            }
        }
    }

    @Test
    void tiersFollowWynncraft() {
        // Wynntils' tier colours: Legendary aqua, Rare light purple, Unique yellow, Mythic dark purple
        assertEquals(Theme.TIER_LEGENDARY, Theme.paper(ChatFormatting.AQUA));
        assertEquals(Theme.TIER_RARE, Theme.paper(ChatFormatting.LIGHT_PURPLE));
        assertEquals(Theme.TIER_UNIQUE, Theme.paper(ChatFormatting.YELLOW));
        assertEquals(Theme.TIER_MYTHIC, Theme.paper(ChatFormatting.DARK_PURPLE));
    }

    @Test
    void anyLightColourIsDarkenedUntilItReads() {
        for (int rgb : new int[] {0xFFFFFF, 0xFAC775, 0x9FE1CB, 0x7FD4E8, 0xE8E6DF, 0xF09595}) {
            reads(String.format("#%06X", rgb), Theme.paperRgb(rgb), 4.5);
        }
        // a colour that already reads stays as it is
        assertEquals(0xFF2E2E2E, Theme.paperRgb(0x2E2E2E));
    }
}
