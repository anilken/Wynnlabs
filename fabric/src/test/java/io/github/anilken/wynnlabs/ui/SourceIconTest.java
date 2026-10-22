package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.build.roadmap.RoadmapLocksTest;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.game.FixtureState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Missing pill's source icon follows the same source as the "To get" line. */
class SourceIconTest {

    private static final Set<String> ICONS = Set.of("trophy", "coin", "shopping-cart", "script", "box", "sword", "skull",
            "question-mark");

    @BeforeEach
    void setUp() throws Exception {
        FixtureState.load(RoadmapLocksTest.shaman()).install("en");
    }

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    @Test
    void theIconMatchesTheSourceLine() {
        assertEquals("question-mark", BuildWindow.sourceIcon("No Such Item At All"), "nothing known");
        assertEquals(null, BuildWindow.sourceDetail("No Such Item At All"), "and no detail line");
        for (String name : List.of("Covetous Thunder", "Morph-Emerald", "Tainted Ruby", "Yume", "Venison", "Precious", "Prologue")) {
            String icon = BuildWindow.sourceIcon(name);
            assertTrue(ICONS.contains(icon), name + ": " + icon);
            Object[] src = BuildWindow.source(name);
            if (src[1] != null) {
                assertEquals("coin", icon, name + " has a Trade Market price");
            }
            if (L.t("wynnlabs.results.untradable").equals(src[0])) {
                assertEquals("question-mark", icon, name + " is untradable with no known source");
            }
        }
    }
}
