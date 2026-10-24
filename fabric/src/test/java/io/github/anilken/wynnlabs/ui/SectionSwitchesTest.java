package io.github.anilken.wynnlabs.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which settings sections get the "All off / All on" button: two switches or more, never the look or the data. */
class SectionSwitchesTest {

    @Test
    void sectionsWithSeveralSwitchesGetTheButton() {
        List<String> warnings = List.of("dangerWarn", "survivalWarn", "coachToasts");
        assertEquals(warnings, SectionSwitches.keys("coach", warnings));
        assertEquals(List.of("glowQuest", "miniQuests"), SectionSwitches.keys("quests", List.of("glowQuest", "miniQuests")));
    }

    @Test
    void aLoneSwitchOrADeliberateCategoryDoesNot() {
        assertTrue(SectionSwitches.keys("glow", List.of("glowTree")).isEmpty(), "one switch: its own pill is enough");
        assertTrue(SectionSwitches.keys("data", List.of("priceApi", "questGuides", "updateCheck", "autoUpdate")).isEmpty(),
                "online data is a choice of its own");
        assertTrue(SectionSwitches.keys("look", List.of("webFont", "other")).isEmpty());
        assertTrue(SectionSwitches.keys("rules", List.of("a", "b")).isEmpty());
    }
}
