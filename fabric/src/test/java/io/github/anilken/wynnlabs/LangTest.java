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
    void englishCountsPickOneOrOther() {
        language(1);
        assertEquals("1 day ago", L.n("wynnlabs.screen.ago_days", 1));
        assertEquals("3 days ago", L.n("wynnlabs.screen.ago_days", 3));
        assertEquals("0 deaths", L.n("wynnlabs.deaths.tile_count", 0));
        assertEquals("Hunt over: Wybel Fluff (1 kill)", L.n("wynnlabs.coach.hunt_ended", 1, "Wybel Fluff", 1));
        assertEquals("Area Lv. 34 · You Lv. 60", L.t("wynnlabs.deaths.chip_zone", 34, 60));
    }

    @Test
    void turkishCountsHaveOneForm() {
        language(2);
        assertEquals("1 gün önce", L.n("wynnlabs.screen.ago_days", 1));
        assertEquals("3 gün önce", L.n("wynnlabs.screen.ago_days", 3));
    }

    @Test
    void unknownKeyStaysAKey() {
        language(1);
        assertEquals("wynnlabs.no.such.key", L.t("wynnlabs.no.such.key"));
    }

    @Test
    void capUpperCasesOnlyTheFirstLetter() {
        assertEquals("Dropped by mobs", L.cap("dropped by mobs", "en_us"));
        assertEquals("• Already started", L.cap("• already started", "en_us"));
        assertEquals("(Slot empty)", L.cap("(slot empty)", "en_us"));
        assertEquals("Lootrun", L.cap("Lootrun", "en_us"));
        assertEquals("3 days ago", L.cap("3 days ago", "en_us"));
        assertEquals("+10 Strength", L.cap("+10 Strength", "en_us"));
        assertEquals("", L.cap("", "en_us"));
    }

    @Test
    void capIsTurkishInTurkish() {
        assertEquals("İlk raid'den sonra", L.cap("ilk raid'den sonra", "tr_tr"));
        assertEquals("Ilık", L.cap("ılık", "tr_tr"));
        assertEquals("Mob'lardan düşer", L.cap("mob'lardan düşer", "tr_tr"));
        assertEquals("Initial", L.cap("initial", "en_us"));
        language(2);
        assertEquals("İçerik", L.cap("içerik"));
    }
}
