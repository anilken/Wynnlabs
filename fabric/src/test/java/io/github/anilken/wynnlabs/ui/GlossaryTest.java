package io.github.anilken.wynnlabs.ui;

import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.advice.Verdict;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Settings;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Game words explained under menu tooltips, and what each verdict badge means. */
class GlossaryTest {

    @AfterEach
    void english() {
        Settings.use(new JsonObject());
    }

    private static void turkish() {
        JsonObject o = new JsonObject();
        o.addProperty("langOverride", 2);
        Settings.use(o);
    }

    @Test
    void findsTheTermsAWordStarts() {
        assertEquals(List.of("roll"), Glossary.termsIn("Rolled 87%: it sells for more"));
        assertEquals(List.of("identify"), Glossary.termsIn("An unidentified Lv. 60 box"));
        assertEquals(List.of("reveal_fee"), Glossary.termsIn("Its expected ~3 EB does not cover the reveal fee"));
        assertEquals(List.of("dry"), Glossary.termsIn("Raids since the last mythic"));
        assertTrue(Glossary.termsIn("Click: copy name · Wheel: scroll").isEmpty(), "scroll is not roll");
    }

    @Test
    void capitalWordsMatchOnlyAsWritten() {
        assertEquals(List.of("ehp"), Glossary.termsIn("Spell 1200 · EHP 5400 · Speed 10%"));
        assertTrue(Glossary.termsIn("ehp and cheap things").isEmpty());
        turkish();
        assertEquals(List.of("ehp"), Glossary.termsIn("Can · CAN 5400"));
        assertTrue(Glossary.termsIn("Canın azaldı").isEmpty(), "plain Turkish \"can\" is health, not EHP");
    }

    @Test
    void atMostTwoMostSpecificFirst() {
        List<String> t = Glossary.termsIn("Mythic aspect: 40 pulls, 9 raids dry · Pool: tier 3 this week · Archetype: Boltslinger");
        assertEquals(List.of("dry", "pool_tier"), t);
    }

    @Test
    void withAddsTheExplanationsOnce() {
        List<Component> tip = List.of(Component.literal("Archetype: Boltslinger"));
        List<Component> out = Glossary.with(tip);
        assertEquals(3, out.size());
        assertEquals(L.t("wynnlabs.glossary.archetype"), out.get(2).getString());
        assertSame(out, Glossary.with(out), "already explained: unchanged");
        List<Component> plain = List.of(Component.literal("Close"));
        assertSame(plain, Glossary.with(plain));
    }

    @Test
    void explanationsAreShortInBothLanguages() {
        for (int lang = 0; lang < 2; lang++) {
            if (lang == 1) {
                turkish();
            }
            for (String term : Glossary.TERMS) {
                String e = Glossary.explain(term);
                assertFalse(e.startsWith("wynnlabs."), term);
                assertTrue(e.length() <= 110, term + " is " + e.length() + " long");
                assertTrue(e.contains(": "), term + " names the term first");
            }
        }
    }

    @Test
    void eachVerdictSaysWhatItMeans() {
        for (Verdict v : Verdict.values()) {
            String m = v.meaning();
            assertFalse(m.contains("wynnlabs."), v.name());
            assertTrue(m.length() <= 80, v + " is " + m.length() + " long");
        }
        assertTrue(Verdict.IDENTIFY.meaning().startsWith("ID: "), "the slot's short word leads");
        assertFalse(Verdict.SELL.meaning().startsWith("SELL"), "the same word is not said twice");
    }
}
