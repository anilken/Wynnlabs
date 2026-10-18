package io.github.anilken.wynnlabs;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Every item's verdict and note on the recorded fixtures, in English and Turkish. */
class FixtureVerdictTest {

    private static void run(String language) throws Exception {
        FixtureRun.each("verdicts (" + language + ")", List.of(language), (fx, dir, lang) ->
                Expected.check(dir, "verdicts." + lang + ".json", FixtureRun.verdicts(fx), Expected::verdicts));
    }

    @Test
    void english() throws Exception {
        run("en");
    }

    @Test
    void turkish() throws Exception {
        run("tr");
    }
}
