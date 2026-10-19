package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Text that is saved or kept in memory is worded when shown: worded in English, then the menu
 * switched to Turkish, it reads Turkish (and back). Files from older versions, worded already,
 * follow the switch too where their lines can be matched back to a key.
 */
class TextFollowsLanguageTest {

    private static final int EN = 1;
    private static final int TR = 2;

    private static void language(int pick) {
        JsonObject s = new JsonObject();
        s.addProperty("langOverride", pick);
        Settings.use(s);
    }

    @AfterEach
    void reset() {
        Settings.use(new JsonObject());
    }

    // ---- the helper ----

    @Test
    void wordsSurviveAFileAndFollowTheSwitch() {
        language(EN);
        Words w = Words.of("wynnlabs.death.area_level", 48, 44);
        Words back = Words.fromJson(JsonParser.parseString(w.json().toString()));
        assertEquals("Area Lv. 48, you're Lv. 44", back.text());
        language(TR);
        assertEquals("Bölge Lv. 48, sen 44", back.text());
        // a nested argument is worded with it
        Words nested = Words.fromJson(JsonParser.parseString(
                Words.of("wynnlabs.coach.tip_deaths_at", Words.of("wynnlabs.death.area_near", "Nivla Woods")).json().toString()));
        assertTrue(nested.text().contains("Nivla Woods yakını"), nested.text());
    }

    @Test
    void oldWordedTextMatchesBackToItsKeyInEitherLanguage() {
        Words fromTr = Words.unword("Bölge Lv. 48, sen 44", List.of("wynnlabs.death.one_off", "wynnlabs.death.area_level"));
        Words fromEn = Words.unword("Area Lv. 48, you're Lv. 44", List.of("wynnlabs.death.area_level"));
        assertEquals("wynnlabs.death.area_level", fromTr.key());
        assertEquals(List.of("48", "44"), fromTr.args());
        assertEquals(fromTr, fromEn);
        assertNull(Words.unword("something else entirely", List.of("wynnlabs.death.area_level")));
        // "%%" is a plain percent sign, not an argument
        Words pct = Words.unword(" (build +%12)", List.of("wynnlabs.auto.build_gain"));
        assertEquals(List.of("12"), pct.args());
    }

    // ---- the deaths tab ----

    private static DeathCoach.Death death(String area, boolean near, List<Words> advice) {
        return new DeathCoach.Death(1_000_000L, 100, 70, -200, 44, area, near, 48, 2900, 1900, 2.5, 0,
                List.of("Guard Golem"), advice);
    }

    @Test
    void deathAdviceIsSavedAsKeysAndFollowsTheSwitch() {
        language(EN);
        DeathCoach.Death d = death("Nivla Woods", true, new java.util.ArrayList<>());
        d.advice().addAll(DeathCoach.advise(d, List.of(), 3));
        String file = DeathCoach.write(List.of(d));
        assertTrue(file.contains("\"notes\""), "kept as language keys");

        DeathCoach.Death back = DeathCoach.read(file).get(0);
        assertEquals("near Nivla Woods", back.place());
        assertEquals(List.of("Area Lv. 48, you're Lv. 44",
                "Your health ran out in 2.5 s: burst damage, and your effective HP is low (2900)",
                "Put a few points into Defence (now 3)",
                "You had no healing potions left; bring some"), back.adviceText());

        language(TR);
        assertEquals("Nivla Woods yakını", back.place());
        assertEquals("Bölge Lv. 48, sen 44", back.adviceText().get(0));
        assertEquals("Savunmaya birkaç puan ver (şu an 3)", back.adviceText().get(2));
    }

    @Test
    void aDeathsFileWordedInTurkishReadsEnglishInAnEnglishMenu() {
        // what an older version saved while the menu was Turkish: worded lines, no "notes"
        JsonObject o = new JsonObject();
        o.addProperty("time", 1_000_000L);
        o.addProperty("x", 100);
        o.addProperty("y", 70);
        o.addProperty("z", -200);
        o.addProperty("level", 44);
        o.addProperty("area", "Nivla Woods yakını");
        o.addProperty("areaLevel", 48);
        o.addProperty("ehp", 2900);
        o.addProperty("hpMax", 1900);
        o.addProperty("seconds", 2.5);
        o.addProperty("potions", 0);
        o.add("mobs", new JsonArray());
        JsonArray adv = new JsonArray();
        adv.add("Burada 3. ölümün, bu bölgeyi şimdilik bırak");
        adv.add("Can iksirin kalmamıştı, yanına iksir al");
        adv.add("a line no key knows");
        o.add("advice", adv);
        JsonArray file = new JsonArray();
        file.add(o);

        language(EN);
        DeathCoach.Death d = DeathCoach.read(file.toString()).get(0);
        assertEquals("near Nivla Woods", d.place());
        assertEquals(List.of("Death #3 here: leave this area for now",
                "You had no healing potions left; bring some", "a line no key knows"), d.adviceText());
        // saved again, it is keys from now on
        DeathCoach.Death again = DeathCoach.read(DeathCoach.write(List.of(d))).get(0);
        language(TR);
        assertEquals("Burada 3. ölümün, bu bölgeyi şimdilik bırak", again.adviceText().get(0));
    }

    // ---- the last session's tips ----

    @Test
    void sessionTipsFollowTheSwitch() {
        language(EN);
        JsonObject last = new JsonObject();
        JsonArray notes = new JsonArray();
        notes.add(Words.of("wynnlabs.coach.tip_mana").json());
        notes.add(Words.of("wynnlabs.coach.tip_rate", "12.5k").json());
        last.add("notes", notes);
        assertEquals(List.of(L.t("wynnlabs.coach.tip_mana"), "Average 12.5k XP/h"), Coach.sessionTips(last));
        language(TR);
        assertEquals(L.t("wynnlabs.coach.tip_rate", "12.5k"), Coach.sessionTips(last).get(1));
    }

    @Test
    void oldSessionTipsWordedInEnglishReadTurkish() {
        language(EN);
        String worded = L.t("wynnlabs.coach.tip_deaths_at", L.t("wynnlabs.death.area_near", "Nivla Woods"));
        JsonObject last = new JsonObject();
        JsonArray tips = new JsonArray();
        tips.add(worded);
        last.add("tips", tips);
        language(TR);
        assertEquals(L.t("wynnlabs.coach.tip_deaths_at", "Nivla Woods yakını"), Coach.sessionTips(last).get(0));
    }

    // ---- the bank ----

    private static Vault.Stored stored(String note, String lang) {
        return new Vault.Stored("Tempered Iron", 0, "acct:1", 0, 3, false, "material", "SELL", note, lang,
                "MAGE", "", 4, null, null);
    }

    @Test
    void aBankNoteShowsOnlyInTheLanguageItWasWordedIn() {
        language(EN);
        Vault.Stored st = stored("Sell it at the Trade Market", "en_us");
        assertEquals("Sell it at the Trade Market", Vault.noteNow(st));
        language(TR);
        assertNull(Vault.noteNow(st), "worded in English: none until the page is read again in Turkish");
        assertEquals("Trade Market'te sat", Vault.noteNow(stored("Trade Market'te sat", "tr_tr")));
        assertNull(Vault.noteNow(stored("Trade Market'te sat", null)), "an older file did not say which language");
    }

    // ---- the price source's problem line ----

    @Test
    void thePriceProblemIsWordedWhenShown() {
        Words was = Prices.problem;
        try {
            language(EN);
            Prices.problem = Words.of("wynnlabs.price.problem_key", 401);
            String en = Prices.problem.text();
            language(TR);
            assertEquals(L.t("wynnlabs.price.problem_key", 401), Prices.problem.text());
            assertTrue(!en.equals(Prices.problem.text()), "the line changes with the language");
        } finally {
            Prices.problem = was;
        }
    }

    // ---- quest guides from the wiki ----

    @Test
    void anUnnamedQuestPointIsWordedWhenShown() {
        String page = "{{Infobox}}\n== Stage 1 ==\n{{RenderLocation | x = 1000 | y = 70 | z = 2000 }}\n"
                + "{{RenderLocation | x = 3000 | y = 70 | z = 4000 }}\n» Go and look\n";
        language(TR);
        QuestWiki.Info info = QuestWiki.parse(page);
        assertEquals(List.of("point", "point"), info.stages().get(0).names(), "the parse is the same in every language");
        assertEquals("Nokta", QuestWiki.places(info, 0).get(0).what());
        language(EN);
        assertEquals("Point", QuestWiki.places(info, 0).get(0).what());
    }
}
