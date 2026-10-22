package io.github.anilken.wynnlabs.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Text drawn on the J menu's paper keeps its meaning in a darker tone. */
class KitInkTest {

    private static List<Integer> colours(Component c) {
        List<Integer> out = new ArrayList<>();
        Kit.ink(c.getVisualOrderText()).accept((i, style, cp) -> {
            TextColor col = style.getColor();
            out.add(col == null ? null : 0xFF000000 | col.getValue());
            return true;
        });
        return out;
    }

    @Test
    void greenBecomesGood() {
        for (Integer c : colours(Component.literal("ok").withStyle(ChatFormatting.GREEN))) {
            assertEquals(Theme.GOOD, c);
        }
    }

    @Test
    void whiteBecomesInk() {
        for (Integer c : colours(Component.literal("name").withStyle(Style.EMPTY.withColor(0xFFFFFF)))) {
            assertEquals(Theme.INK, c);
        }
    }

    @Test
    void mixedTextKeepsEachPart() {
        Component c = Component.literal("ab").withStyle(ChatFormatting.AQUA)
                .append(Component.literal("cd").withStyle(ChatFormatting.RED));
        assertEquals(List.of(Theme.TIER_LEGENDARY, Theme.TIER_LEGENDARY, Theme.BAD, Theme.BAD), colours(c));
    }

    @Test
    void unstyledTextKeepsTheCallersColour() {
        for (Integer c : colours(Component.literal("plain"))) {
            assertNull(c);
        }
    }
}
