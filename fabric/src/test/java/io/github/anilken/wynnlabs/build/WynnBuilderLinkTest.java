package io.github.anilken.wynnlabs.build;

import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;
import io.github.anilken.wynnlabs.game.GameState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * WynnBuilder build links and what they hold: older links ("10_", "11_") from WynnBuilder's own
 * encoding test list, current links ("C…") written here from the published format (the newest
 * data version and the first one), and the address WynnBuilder itself wrote after opening one.
 */
class WynnBuilderLinkTest {

    private static final String DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz+-";

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    @Test
    void olderLinks() {
        WynnBuilderLink.Build b = WynnBuilderLink.decode("https://wynnbuilder.github.io/builder?v=18#11_09V10L0jn0Qt0K00OW0KI0V10Qi1I140J002C1g0000103tlU0z0z0+0+0+0+0-1T2Y2Y2Z2Z2a2a401401401401401z+xlZxe50");
        assertNotNull(b);
        assertEquals(List.of("Dune Storm", "Etiolation", "Sagittarius", "Revenant", "Intensity", "Diamond Steam Ring", "Vortex Bracer",
                "Necklace of a Thousand Storms", "Hero"), names(b));
        assertEquals("A6A6A6A6", b.pieces().get(8).powders());
        assertEquals(List.of(82, 68, 19, 0, 140), b.skills());
        assertEquals(106, b.level());
        assertEquals("2.1.2.0", b.version(), "?v=18");
        assertTrue(b.tomes().isEmpty(), "every tome slot empty");

        WynnBuilderLink.Build c = WynnBuilderLink.decode("https://wynnbuilder.github.io/builder?v=18#10_09V0DPCR-17B9F9fAM8r9t9i510Qz0Jf0x2CR-19c4N4N4N4N4N9c910uH0QJ2M1G-s-s1C1g010003610003610003600z0z0+0+0+0+0-1T2Y2Y2Z2Z2a2azskR5OxIc0");
        assertNotNull(c);
        assertEquals(WynnBuilderLink.Kind.CRAFTED, c.pieces().get(2).kind(), "crafted leggings");
        assertEquals(WynnBuilderLink.Kind.CRAFTED, c.pieces().get(6).kind(), "crafted bracelet");
        assertEquals("Alkatraz", c.pieces().get(8).name());
        assertEquals("E6E6", c.pieces().get(1).powders());
        assertEquals(List.of(150, 80, -10, -10, 76), c.skills(), "negative points read signed");
    }

    @Test
    void currentLinks() {
        // the newest data version: items, powders (a repeat, a tier repeat to the next element, a new id), tomes, points, a level
        int latest = WynnBuilderLink.table().versions().size() - 1;
        Writer w = new Writer(latest);
        w.item("Dune Storm", "");
        w.item("Etiolation", "");
        w.none(true);
        w.item("Revenant", "");
        w.item("Intensity", "");
        w.none(false);
        w.item("Vortex Bracer", "");
        w.item("Necklace of a Thousand Storms", "");
        w.item("Hero", "A6A6E6T4");
        w.tomes("Abyssal Tome of Combat Mastery III");
        w.skills(82, null, -5, null, 140);
        w.level(98);
        WynnBuilderLink.Build b = WynnBuilderLink.decode("https://wynnbuilder.github.io/builder/#" + w.text());
        assertNotNull(b);
        assertEquals(WynnBuilderLink.table().versions().get(latest).name(), b.version());
        assertFalse(b.newer());
        assertEquals(List.of("Dune Storm", "Etiolation", "Revenant", "Intensity", "Vortex Bracer", "Necklace of a Thousand Storms", "Hero"),
                names(b), "the empty leggings and ring are no pieces");
        assertEquals(8, b.pieces().get(6).slot());
        assertEquals("A6A6E6T4", b.pieces().get(6).powders());
        assertEquals(List.of("Abyssal Tome of Combat Mastery III"), b.tomes());
        assertEquals(Arrays.asList(82, null, -5, null, 140), b.skills());
        assertEquals(98, b.level());

        // the first data version (12-bit ids, six powder tiers), a crafted weapon, points left to WynnBuilder, the top level
        Writer old = new Writer(0);
        old.item("Dune Storm", "F6");
        for (int i = 1; i < 8; i++) {
            old.none(i < 4);
        }
        old.crafted(true);
        old.noTomes();
        old.autoSkills();
        old.level(-1);
        WynnBuilderLink.Build o = WynnBuilderLink.decode(old.text());
        assertNotNull(o, "a bare hash");
        assertEquals("2.0.1.1", o.version());
        assertEquals(2, o.pieces().size());
        assertEquals("F6", o.pieces().get(0).powders());
        assertEquals(WynnBuilderLink.Kind.CRAFTED, o.pieces().get(1).kind());
        assertNull(o.skills());
        assertEquals(106, o.level(), "that version's top level");
    }

    @Test
    void linkTheSiteWrote() {
        // WynnBuilder's own address after it opened the link of currentLinks (October 2026, data 2.2.5.0)
        WynnBuilderLink.Build b = WynnBuilderLink.decode("https://wynnbuilder.github.io/builder/#CZ0W9W5G000uQG0500m454+GhcXYfy-200k-Jn0");
        assertNotNull(b);
        assertEquals("2.2.5.0", b.version());
        assertEquals(List.of("Dune Storm", "Etiolation", "Revenant", "Intensity", "Vortex Bracer", "Necklace of a Thousand Storms", "Hero"), names(b));
        assertEquals("A6A6E6T4", b.pieces().get(6).powders());
        assertEquals(List.of("Abyssal Tome of Combat Mastery III"), b.tomes());
        assertEquals(98, b.level());
        assertEquals(Arrays.asList(null, null, -5, null, null), b.skills(), "the site keeps only the points that differ from its own");
    }

    @Test
    void notLinks() {
        assertNull(WynnBuilderLink.decode(null));
        assertNull(WynnBuilderLink.decode(""));
        assertNull(WynnBuilderLink.decode("https://wynnbuilder.github.io/builder/"));
        assertNull(WynnBuilderLink.decode("hello there"));
        assertNull(WynnBuilderLink.decode("https://wynnbuilder.github.io/builder/#C0"), "cut short");
        assertNull(WynnBuilderLink.decode("CR-40w3w3w3w3w3wF3040"), "a crafter code");
    }

    @Test
    void importKeepsWhatTheClassCanUse() throws Exception {
        Path dir = Fixtures.root().resolve("shaman-44");
        assumeTrue(Files.exists(dir.resolve("state.json")), "no shaman-44 fixture");
        FixtureState.load(dir).install("en");
        GameState state = GameState.current();
        Writer w = new Writer(WynnBuilderLink.table().versions().size() - 1);
        w.item("Dune Storm", "");
        for (int i = 1; i < 8; i++) {
            w.none(i < 4);
        }
        w.item("Hero", "");
        w.noTomes();
        w.autoSkills();
        w.level(-1);
        LinkImport.Result r = LinkImport.of(state, WynnBuilderLink.decode(w.text()));
        assertEquals(2, r.rows().size());
        assertEquals(LinkImport.Why.KEPT, r.rows().get(0).why());
        assertEquals(LinkImport.Why.CLASS, r.rows().get(1).why(), "a bow is no shaman weapon");
        assertEquals(List.of("Dune Storm"), r.kept().stream().map(i -> i.name()).toList());
    }

    private static List<String> names(WynnBuilderLink.Build b) {
        return b.pieces().stream().map(p -> p.name() == null ? "?" : p.name()).toList();
    }

    /** The current form, written from the published format: the inverse of the reader, for the tests. */
    private static final class Writer {
        private final StringBuilder bits = new StringBuilder();
        private final WynnBuilderLink.Widths w;
        private int slot;

        Writer(int version) {
            w = WynnBuilderLink.table().versions().get(version);
            put(12, 6);
            put(version, 10);
        }

        void put(int value, int n) {
            for (int i = 0; i < n; i++) {
                bits.append((value >> i & 1) == 1 ? '1' : '0');
            }
        }

        void item(String name, String powders) {
            String[] items = WynnBuilderLink.table().items();
            int id = Arrays.asList(items).indexOf(name);
            assertTrue(id >= 0, name);
            put(0, 2);
            put(id + 1, w.items());
            powders(powders);
        }

        void none(boolean powderable) {
            put(0, 2);
            put(0, w.items());
            if (powderable) {
                put(0, 1);
            }
            slot++;
        }

        void crafted(boolean weapon) {
            put(1, 2);
            int start = bits.length();
            put(0, 1);
            put(2, 7);
            for (int i = 0; i < 6; i++) {
                put(4000, 12);
            }
            put(1, 12);
            put(0, 6);
            if (weapon) {
                put(1, 4);
            }
            put(0, 6 - (bits.length() - start) % 6);
            powders("");
        }

        private void powders(String powders) {
            boolean powderable = slot < 4 || slot == 8;
            slot++;
            if (!powderable) {
                return;
            }
            if (powders.isEmpty()) {
                put(0, 1);
                return;
            }
            put(1, 1);
            List<int[]> ps = new ArrayList<>();
            for (int i = 0; i < powders.length(); i += 2) {
                ps.add(new int[] {"ETWFA".indexOf(powders.charAt(i)), powders.charAt(i + 1) - '1'});
            }
            put(ps.get(0)[0] * w.powderTiers() + ps.get(0)[1], w.powderBits());
            for (int i = 1; i < ps.size(); i++) {
                int[] a = ps.get(i - 1);
                int[] b = ps.get(i);
                if (a[0] == b[0] && a[1] == b[1]) {
                    put(0, 1);
                } else if (a[1] == b[1]) {
                    put(1, 1);
                    put(0, 1);
                    put(Math.floorMod(b[0] - a[0], 5) - 1, w.wrapBits());
                } else {
                    put(1, 1);
                    put(1, 1);
                    put(0, 1);
                    put(b[0] * w.powderTiers() + b[1], w.powderBits());
                }
            }
            put(1, 1);
            put(1, 1);
            put(1, 1);
        }

        void tomes(String first) {
            put(1, 1);
            put(1, 1);
            put(Arrays.asList(WynnBuilderLink.table().tomes()).indexOf(first), w.tomes());
            for (int i = 1; i < w.tomeSlots(); i++) {
                put(0, 1);
            }
        }

        void noTomes() {
            put(0, 1);
        }

        void skills(Integer... sp) {
            put(0, 1);
            for (Integer s : sp) {
                put(s == null ? 0 : 1, 1);
                if (s != null) {
                    put(s & ((1 << w.spBits()) - 1), w.spBits());
                }
            }
        }

        void autoSkills() {
            put(1, 1);
        }

        /** -1: the version's top level. */
        void level(int level) {
            if (level < 0) {
                put(0, 1);
            } else {
                put(1, 1);
                put(level, w.levelBits());
            }
        }

        /** The letters, with no aspects and an empty ability tree after the level, as the site needs to open it. */
        String text() {
            put(0, 6);
            while (bits.length() % 6 != 0) {
                bits.append('0');
            }
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < bits.length(); i += 6) {
                int v = 0;
                for (int j = 0; j < 6; j++) {
                    if (bits.charAt(i + j) == '1') {
                        v |= 1 << j;
                    }
                }
                out.append(DIGITS.charAt(v));
            }
            return out.toString();
        }
    }
}
