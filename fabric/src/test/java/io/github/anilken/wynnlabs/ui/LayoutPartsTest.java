package io.github.anilken.wynnlabs.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The preview's check of the home panel's parts: a slot's pill drawn over the build bar (the
 * compact stale overlap) is reported; a slot's own parts (its square, pill and name, a weapon
 * pill over its own corner) are not; touching edges are not.
 */
class LayoutPartsTest {

    @Test
    void aPillOverTheBuildBarIsReported() {
        List<LayoutCheck.Part> parts = List.of(
                new LayoutCheck.Part(60, 195, 82, 217, "slot3", "the Boots slot"),
                new LayoutCheck.Part(80, 199, 108, 210, "slot3", "a pill (+%44)"),
                new LayoutCheck.Part(54, 200, 270, 209, "bar", "the build bar"));
        List<String> found = LayoutCheck.partOverlaps(parts);
        assertEquals(2, found.size(), String.join("\n", found));
        assertTrue(found.get(0).contains("the build bar"));
    }

    @Test
    void aSlotsOwnPartsAndTouchingEdgesAreFine() {
        List<LayoutCheck.Part> parts = List.of(
                new LayoutCheck.Part(100, 100, 124, 124, "slot4", "the weapon slot"),
                new LayoutCheck.Part(110, 120, 140, 131, "slot4", "a pill (chosen)"),
                new LayoutCheck.Part(124, 100, 150, 111, "slot5", "a pill (get)"),
                new LayoutCheck.Part(40, 200, 270, 220, "bar", "the build bar"),
                new LayoutCheck.Part(40, 180, 64, 200, "slot3", "the Boots slot"));
        assertTrue(LayoutCheck.partOverlaps(parts).isEmpty());
    }
}
