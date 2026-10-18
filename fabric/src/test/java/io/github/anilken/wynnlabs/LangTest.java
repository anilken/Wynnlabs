package io.github.anilken.wynnlabs;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** L without the game: the bundled language files, formatted like I18n does. */
class LangTest {

    private static void language(int pick) {
        JsonObject s = new JsonObject();
        s.addProperty("langOverride", pick);
        Settings.use(s);
    }

    @AfterEach
    void reset() {
        Settings.use(new JsonObject());
    }

    @Test
    void englishWithArguments() {
        language(1);
        assertEquals(" (build +12%)", L.t("wynnlabs.auto.build_gain", 12));
        assertEquals("You can wear it at Lv. 44", L.t("wynnlabs.auto.wear_at_level", 44));
    }

    @Test
    void turkishWithArguments() {
        language(2);
        assertEquals(" (build +%12)", L.t("wynnlabs.auto.build_gain", 12));
        assertEquals("Lv. 44'te giyebilirsin", L.t("wynnlabs.auto.wear_at_level", 44));
    }

    @Test
    void followTheGameIsEnglishWithoutOne() {
        language(0);
        assertEquals("5 min", L.t("wynnlabs.price.minutes", 5));
        assertEquals("5 min", L.c("wynnlabs.price.minutes", 5).getString());
    }

    @Test
    void unknownKeyStaysAKey() {
        language(1);
        assertEquals("wynnlabs.no.such.key", L.t("wynnlabs.no.such.key"));
    }
}
