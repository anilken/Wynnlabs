package io.github.anilken.wynnlabs.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tracked mobs: matching floating names, when a marker shows, when an arrival is told. */
class MobTrackTest {

    @Test
    void namesLoseCodesIconsAndLevel() {
        assertEquals("reggie", MobName.key("§cReggie §7[Lv. 40]"));
        assertEquals("reggie", MobName.key("Reggie [Lv. 40]"));
        assertEquals("reggie", MobName.key("[Lv. 40] Reggie"));
        assertEquals("reggie", MobName.key("Reggie (Lvl 40)"));
        assertEquals("reggie", MobName.key("Reggie Lv. 40-45"));
        assertEquals("reggie", MobName.key(" §l§6REGGIE "));
        assertEquals("reggie", MobName.key("Reggie\n§c❤ 1200"), "only the first line counts");
        assertEquals("king of the ocean", MobName.key("  King   of the Ocean "));
        assertEquals("qira's guard", MobName.key("Qira's Guard"));
        assertEquals("", MobName.key("[Lv. 40]"));
        assertEquals("", MobName.key(null));
    }

    @Test
    void matchesWholeNamesOnly() {
        Set<String> keys = Set.of(MobName.key("Reggie"), MobName.key("Ice Barbarian"));
        assertTrue(MobName.matches("§eReggie §8[Lv. 40]", keys));
        assertTrue(MobName.matches("ICE BARBARIAN [Lv. 52]", keys));
        assertFalse(MobName.matches("Reggie's Pet [Lv. 40]", keys), "a longer name is another mob");
        assertFalse(MobName.matches("Barbarian [Lv. 52]", keys));
        assertFalse(MobName.matches("Reggie", Set.of()));
        assertFalse(MobName.matches("[Lv. 40]", keys));
    }

    @Test
    void markerOnlyInPlainSight() {
        assertTrue(MobSight.shown(true, 40, true, false));
        assertTrue(MobSight.shown(true, 40, false, true), "its middle in sight is enough");
        assertFalse(MobSight.shown(true, 40, false, false), "behind blocks: nothing drawn");
        assertFalse(MobSight.shown(false, 40, true, true), "the setting is off");
        assertFalse(MobSight.shown(true, MobSight.MAX_DRAW + 1, true, true), "too far");
        assertFalse(MobSight.shown(true, -1, true, true));
    }

    @Test
    void arrivalsAreToldOnceAndNotTooOften() {
        long now = 1_000_000;
        assertTrue(MobSight.appeared(now, 0), "never seen");
        assertFalse(MobSight.appeared(now, now - 250), "seen at the last scan");
        assertTrue(MobSight.appeared(now, now - MobSight.GONE_MS - 1), "gone a while, back again");

        assertTrue(MobSight.announce(now, 0, 0));
        assertFalse(MobSight.announce(now, now - 1000, 0), "another notice just showed");
        assertFalse(MobSight.announce(now, now - MobSight.ANY_GAP_MS, now - 5000), "the same mob was just told");
        assertTrue(MobSight.announce(now, now - MobSight.ANY_GAP_MS, now - MobSight.SAME_GAP_MS));
    }

    @Test
    void trackedListTogglesAndSkipsDuplicates(@TempDir Path dir) {
        TrackedMobs t = new TrackedMobs(dir.resolve("tracked-mobs.json"));
        assertTrue(t.toggle("Reggie"));
        assertTrue(t.has("§cREGGIE [Lv. 40]"));
        assertEquals(Set.of("reggie"), t.keys());
        assertFalse(t.toggle("reggie"), "the same name again stops tracking it");
        assertTrue(t.keys().isEmpty());
        assertFalse(t.toggle("[Lv. 40]"), "a name with nothing left is ignored");
        t.toggle("Reggie");
        t.toggle("Ice Barbarian");
        t.clear();
        assertTrue(t.names().isEmpty());
    }
}
