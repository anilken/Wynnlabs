package io.github.anilken.wynnlabs.quests;

import com.google.gson.JsonObject;

import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The secret discovery guide: shipped data, order, done state, requirements and the wiki check. */
class DiscoveriesTest {

    @AfterEach
    void reset() {
        Settings.use(new JsonObject());
    }

    private static Discoveries.Entry entry(String id, int level, String region, int x, int z) {
        return new Discoveries.Entry(id, id, "https://wynncraft.wiki.gg/wiki/" + id, x, 64, z, level, region,
                List.of(), "", List.of(new Discoveries.Step("Go there.", new int[] {x, 64, z})), "");
    }

    private static List<String> names(List<Discoveries.Entry> list) {
        return list.stream().map(Discoveries.Entry::id).toList();
    }

    @Test
    void bothLanguagesShipTheSameEntries() {
        List<Discoveries.Entry> en = Discoveries.entries(false);
        List<Discoveries.Entry> tr = Discoveries.entries(true);
        assertTrue(en.size() >= 100, "entries: " + en.size());
        assertEquals(names(en), names(tr));
        for (int i = 0; i < en.size(); i++) {
            assertEquals(en.get(i).steps().size(), tr.get(i).steps().size(), en.get(i).id());
            assertFalse(en.get(i).steps().isEmpty(), en.get(i).id());
        }
        Discoveries.Entry frog = Discoveries.englishOf("A Croaking Cryptid");
        assertNotNull(frog);
        assertEquals(104, frog.minLevel());
        assertEquals("Auburn Forest", frog.region());
        int[] door = frog.steps().get(0).at();
        assertEquals(List.of(-2206, 11, -1435), List.of(door[0], door[1], door[2]));
        assertTrue(frog.steps().get(0).text().contains("\"...Hi?\""));
        // Turkish text, English names and dialogue options
        Discoveries.Entry tr0 = tr.get(names(tr).indexOf("A Croaking Cryptid"));
        String trDoor = tr0.steps().get(0).text();
        assertTrue(trDoor.contains("\"...Hi?\"") && trDoor.contains("seçeneğini seç") && trDoor.contains("kapı"), trDoor);
        assertTrue(tr0.steps().get(1).text().contains("Frog Bog"));
    }

    @Test
    void levelThenDistance() {
        Discoveries.Entry near = entry("near", 10, "A", 10, 0);
        Discoveries.Entry far = entry("far", 10, "A", 500, 0);
        Discoveries.Entry low = entry("low", 1, "B", 900, 0);
        List<Discoveries.Entry> out = Discoveries.sorted(List.of(far, near, low), Discoveries.Sort.LEVEL_DISTANCE, 0, 0);
        assertEquals(List.of("low", "near", "far"), names(out));
        assertEquals(List.of("near", "far", "low"), names(Discoveries.sorted(List.of(far, near, low), Discoveries.Sort.DISTANCE, 0, 0)));
    }

    @Test
    void regionGroupsWithTheNearestFirst() {
        // region B has the nearest discovery, so it comes first; inside each region nearer first
        Discoveries.Entry a1 = entry("a1", 5, "A", 300, 0);
        Discoveries.Entry a2 = entry("a2", 1, "A", 200, 0);
        Discoveries.Entry b1 = entry("b1", 9, "B", 50, 0);
        Discoveries.Entry b2 = entry("b2", 2, "B", 900, 0);
        List<Discoveries.Entry> out = Discoveries.sorted(List.of(a1, b2, a2, b1), Discoveries.Sort.REGION, 0, 0);
        assertEquals(List.of("b1", "b2", "a2", "a1"), names(out));
    }

    @Test
    void filtersByDoneAndLevel() {
        Discoveries.Entry high = entry("high", 90, "A", 0, 0);
        assertTrue(Discoveries.shown(high, false, Discoveries.Show.NOT_DONE, false, 10));
        assertFalse(Discoveries.shown(high, false, Discoveries.Show.NOT_DONE, true, 10));
        assertFalse(Discoveries.shown(high, true, Discoveries.Show.NOT_DONE, false, 100));
        assertTrue(Discoveries.shown(high, true, Discoveries.Show.DONE, true, 100));
        assertTrue(Discoveries.shown(high, false, Discoveries.Show.ALL, false, 1));
    }

    @Test
    void sortAndFiltersComeFromSettings() {
        JsonObject s = new JsonObject();
        s.addProperty("discoverySort", 2);
        s.addProperty("discoveryShow", 2);
        s.addProperty("discoveryWithinLevel", 1);
        Settings.use(s);
        assertEquals(Discoveries.Sort.REGION, Discoveries.sort());
        assertEquals(Discoveries.Show.ALL, Discoveries.show());
        assertTrue(Discoveries.withinLevel());
        Settings.use(new JsonObject());
        assertEquals(Discoveries.Sort.LEVEL_DISTANCE, Discoveries.sort());
        assertEquals(Discoveries.Show.NOT_DONE, Discoveries.show());
    }

    @Test
    void handTicksArePerCharacter() {
        String id = "A Croaking Cryptid";
        Discoveries.Entry e = Discoveries.englishOf(id);
        Discoveries.tick("test-first", id, true);
        assertTrue(Discoveries.done(e, Set.of(), "test-first"));
        assertFalse(Discoveries.done(e, Set.of(), "test-second"));
        Discoveries.tick("test-first", id, false);
        assertFalse(Discoveries.done(e, Set.of(), "test-first"));
        // Wynntils' list counts without a tick, whatever the apostrophes
        assertTrue(Discoveries.done(Discoveries.englishOf("Bak'al's Destruction 1"), Set.of(Discoveries.key("Bak’al’s Destruction 1")),
                "test-second"));
    }

    @Test
    void questRequirementUnmetUntilFinished() {
        Discoveries.Entry e = Discoveries.englishOf("A Croaking Cryptid");
        QuestBook.Entry started = new QuestBook.Entry("A Journey Home", "STARTED", 104, "LONG", 0, true, null, null, null, null,
                null, 0, "QUEST", "HARD");
        QuestBook.Entry done = new QuestBook.Entry("A Journey Home", "COMPLETED", 104, "LONG", 0, true, null, null, null, null,
                null, 0, "QUEST", "HARD");
        assertEquals(Boolean.FALSE, Discoveries.met(e, "A Journey Home", List.of(started), Set.of(), "test-x"));
        assertEquals(Boolean.TRUE, Discoveries.met(e, "A Journey Home", List.of(done), Set.of(), "test-x"));
        assertEquals(Boolean.TRUE, Discoveries.met(e, "Partial completion of A Journey Home", List.of(started), Set.of(), "test-x"));
        assertNull(Discoveries.met(e, "Golden Avia Feather", List.of(done), Set.of(), "test-x"));
    }

    private static String page() throws Exception {
        try (InputStream in = Objects.requireNonNull(DiscoveriesTest.class.getResourceAsStream("/discoveries/Colossal Ruins.txt"))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void unchangedPageKeepsTheShippedText() throws Exception {
        Discoveries.Entry tr = Discoveries.entries(true).stream().filter(e -> e.id().equals("Colossal Ruins")).findFirst().orElseThrow();
        Discoveries.Guide g = Discoveries.guide(tr, DiscoveryWiki.steps(page()));
        assertFalse(g.newer());
        assertEquals(tr.steps(), g.steps());
        assertTrue(g.steps().get(0).text().contains("büyü"), g.steps().get(0).text());
    }

    @Test
    void changedPageShowsTheLiveEnglishSteps() throws Exception {
        Discoveries.Entry tr = Discoveries.entries(true).stream().filter(e -> e.id().equals("Colossal Ruins")).findFirst().orElseThrow();
        String changed = page().replace("*Go to the ruin on the island in front of you.", "*Walk to the ruin on the island ahead.");
        Discoveries.Guide g = Discoveries.guide(tr, DiscoveryWiki.steps(changed));
        assertTrue(g.newer());
        assertEquals("Walk to the ruin on the island ahead.", g.steps().get(1).text());
        // no page (offline, guides off): the shipped steps
        assertFalse(Discoveries.guide(tr, null).newer());
    }

    @Test
    void routeListsEveryEntryWithDoneLevelAndDistance() throws Exception {
        List<java.nio.file.Path> folders = Fixtures.folders();
        org.junit.jupiter.api.Assumptions.assumeFalse(folders.isEmpty(), Fixtures::none);
        FixtureState state = FixtureState.load(folders.get(0));
        try {
            state.install("tr");
            JsonObject o = Discoveries.json();
            assertEquals("tr", o.get("language").getAsString());
            assertEquals(Discoveries.entries(false).size(), o.get("count").getAsInt());
            var list = o.getAsJsonArray("discoveries");
            assertEquals(Discoveries.entries(false).size(), list.size());
            double last = -1;
            for (var el : list) {
                JsonObject d = el.getAsJsonObject();
                assertTrue(d.has("done") && d.has("minLevel") && d.has("distance") && d.has("requires"), d.toString());
                // the default order: level, then distance
                double key = d.get("minLevel").getAsInt() * 1e6 + d.get("distance").getAsDouble();
                assertTrue(key >= last, d.get("id").getAsString());
                last = key;
            }
        } finally {
            FixtureState.uninstall();
        }
    }

    @Test
    void stepPinsAreNamedAfterTheDiscoveryAndStep() {
        JsonObject s = new JsonObject();
        s.addProperty("langOverride", 1);
        Settings.use(s);
        Discoveries.Entry frog = Discoveries.englishOf("A Croaking Cryptid");
        List<int[]> points = new java.util.ArrayList<>();
        List<String> labels = new java.util.ArrayList<>();
        Discoveries.pins(frog, frog.steps(), 2, points, labels);
        assertEquals(-2096, points.get(0)[0]);
        assertEquals("A Croaking Cryptid: step 3", labels.get(0));
        assertEquals(frog.steps().size() - 2, points.size());
    }
}
