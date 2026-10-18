package io.github.anilken.wynnlabs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The answers of GET /inventory, /equipped, /build and /roadmap on the recorded fixtures: field
 * names and types first (what tools reading the API rely on), then the values. The fixture's
 * clock is fixed, so no value depends on the time.
 */
class FixtureJsonTest {

    @Test
    void apiAnswers() throws Exception {
        FixtureRun.each("API JSON", List.of("en"), (fx, dir, lang) ->
                Expected.check(dir, "api.json", FixtureRun.api(), (was, now) -> {
                    List<String> out = new ArrayList<>();
                    Expected.shape(was, now).forEach(d -> out.add("shape: " + d));
                    if (out.isEmpty()) {
                        out.addAll(Expected.json(was, now));
                    }
                    return out;
                }));
    }
}
