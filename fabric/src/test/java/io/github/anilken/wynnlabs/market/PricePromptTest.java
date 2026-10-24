package io.github.anilken.wynnlabs.market;

import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.core.Settings;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The live prices question: asked once, only while the player never chose. */
class PricePromptTest {

    @AfterEach
    void reset() {
        Settings.use(new JsonObject());
    }

    @Test
    void askedOnlyWhenNeverChosen() {
        Settings.use(new JsonObject());
        assertTrue(PricePrompt.due(true), "a fresh settings file: ask");
        assertFalse(PricePrompt.due(false), "no Wynnventory mod: the switch does nothing, no question");
        assertFalse(Settings.saved("priceApi"));
        assertFalse(Settings.on("priceApi"), "still off until answered");

        for (boolean set : new boolean[] {true, false}) {
            JsonObject o = new JsonObject();
            o.addProperty("priceApi", set);
            Settings.use(o);
            assertTrue(Settings.saved("priceApi"));
            assertFalse(PricePrompt.due(true), "set by hand (" + set + "): never asked");
        }

        JsonObject shown = new JsonObject();
        shown.addProperty(PricePrompt.ASKED, 1);
        Settings.use(shown);
        assertFalse(PricePrompt.due(true), "shown once and ignored: not again");
    }

    @Test
    void theLineHasAYesAndANo() {
        List<ClickEvent> clicks = new ArrayList<>();
        PricePrompt.line().visit((style, text) -> {
            if (style.getClickEvent() != null && !text.isEmpty()) {
                assertTrue(style.isBold() && style.isUnderlined(), "answers are bold and underlined");
                clicks.add(style.getClickEvent());
            }
            return Optional.empty();
        }, Style.EMPTY);
        assertEquals(List.of(new ClickEvent.RunCommand("/wynnlabs-prices yes"), new ClickEvent.RunCommand("/wynnlabs-prices no")),
                clicks);
    }
}
