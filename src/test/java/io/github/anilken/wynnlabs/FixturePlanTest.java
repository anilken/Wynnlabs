package io.github.anilken.wynnlabs;

import org.junit.jupiter.api.Test;

import java.util.List;

/** The build plan, the road map's steps and the shopping list on the recorded fixtures. */
class FixturePlanTest {

    @Test
    void buildPlan() throws Exception {
        FixtureRun.each("build plan", List.of("en"), (fx, dir, lang) ->
                Expected.check(dir, "build.json", Build.json(), Expected::json));
    }

    @Test
    void roadmap() throws Exception {
        FixtureRun.each("road map", List.of("en", "tr"), (fx, dir, lang) ->
                Expected.check(dir, "roadmap." + lang + ".json", Roadmap.json(), Expected::json));
    }

    @Test
    void shoppingList() throws Exception {
        FixtureRun.each("shopping list", List.of("en"), (fx, dir, lang) ->
                Expected.check(dir, "shopping.json", FixtureRun.shopping(), Expected::json));
    }
}
