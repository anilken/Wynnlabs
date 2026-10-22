package io.github.anilken.wynnlabs;

import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.model.Slot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The road map on demand: nothing is built on joining or opening the menu, the button builds it,
 * Cancel keeps the last one, a changed level or gear marks it out of date without a rebuild, the
 * tooltip advice works without one; and the last map is saved per character, read back at the
 * next session (out of date when its inputs changed), and a missing or broken file is none.
 */
class RoadmapOnDemandTest {

    @TempDir
    Path tmp;
    private FixtureState fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = FixtureState.load(RoadmapLocksTest.shaman());
        fx.install("en");
        Locks.rootOverride = tmp;
        Roadmap.forget();
        Plans.forget();
    }

    @AfterEach
    void reset() throws Exception {
        Roadmap.cancelBuild();
        Roadmap.awaitIdle();
        Saver.flush();
        Roadmap.forget();
        Locks.rootOverride = null;
        Plans.forget();
        FixtureState.uninstall();
    }

    private static List<String> picks(List<Roadmap.Step> steps) {
        return RoadmapLocksTest.picks(steps);
    }

    @Test
    void nothingBuildsOnJoinOrMenuOpen() throws Exception {
        // what the menu and the tooltips ask on opening: no map, nothing started
        assertTrue(Roadmap.cached().isEmpty(), "no map yet");
        assertFalse(Roadmap.isBuilding(), "opening the menu builds nothing");
        assertFalse(Roadmap.hasMap());
        assertNull(Roadmap.stale(), "nothing to be out of date");
        // the client tick's only background work: the plan's level-up check, and there is no plan
        Around.watch();
        assertFalse(Around.running());
        assertNull(Around.progress());
        Roadmap.awaitIdle();
        assertTrue(Roadmap.cached().isEmpty(), "still nothing after the builder is idle");
        // no warm-up left to call
        for (var m : Roadmap.class.getDeclaredMethods()) {
            assertFalse(m.getName().equals("warm"), "the periodic warm-up is gone");
        }
    }

    @Test
    void theButtonStartsABuildAndCancelKeepsTheLast() throws Exception {
        int v = Roadmap.version();
        Roadmap.forceRefresh();                          // "Calculate suggestions"
        Roadmap.awaitIdle();
        List<Roadmap.Step> first = Roadmap.cached();
        assertFalse(first.isEmpty(), "the button built a map");
        assertTrue(Roadmap.version() > v);
        assertNull(Roadmap.stale(), "just built: current");
        // a new build cancelled at once: the last map stays
        int v2 = Roadmap.version();
        Roadmap.forceRefresh();
        Roadmap.cancelBuild();
        Roadmap.awaitIdle();
        assertSame(first, Roadmap.cached(), "Cancel keeps the previous map");
        assertEquals(v2, Roadmap.version());
    }

    @Test
    void aChangedLevelOrGearMarksItOutOfDate() throws Exception {
        Roadmap.build();
        assertNull(Roadmap.stale());
        // the rules changed (as a setting or a measurement would): out of date, not the level
        JsonObject rules = new JsonObject();
        JsonObject model = new JsonObject();
        model.addProperty("manaWeight", 0.31);
        rules.add("model", model);
        Rules.use(rules);
        Roadmap.recheck();
        Roadmap.Stale s = Roadmap.stale();
        assertNotNull(s, "out of date");
        assertFalse(s.level());
        assertFalse(Roadmap.isBuilding(), "never rebuilt on its own");
        // a level-up
        fx.atLevel(GameState.current().level() + 1);
        Roadmap.recheck();
        s = Roadmap.stale();
        assertTrue(s.level(), "the level changed");
        assertEquals(45, s.now());
        assertEquals(44, s.builtLevel());
        assertFalse(Roadmap.isBuilding());
    }

    @Test
    void theAdvicePathWorksWithoutAMap() {
        assertNull(Roadmap.lastUpgrade(Slot.HELMET), "no map: no road-map line");
        assertNull(Roadmap.lastUpgrade(Slot.RING));
        assertFalse(Roadmap.isBuilding(), "a tooltip starts nothing");
        assertTrue(Roadmap.lastNames().isEmpty());
    }

    @Test
    void theLastMapIsSavedAndReadBack() throws Exception {
        List<Roadmap.Step> built = Roadmap.build();
        Saver.flush();
        Path f = tmp.resolve(GameState.current().characterId()).resolve("roadmap.json");
        assertTrue(Files.exists(f), "saved for the character");
        Roadmap.forget();                               // a new session
        List<Roadmap.Step> read = Roadmap.cached();
        assertEquals(picks(built), picks(read), "the same picks");
        for (int i = 0; i < built.size(); i++) {
            Roadmap.Step a = built.get(i);
            Roadmap.Step b = read.get(i);
            assertEquals(a.level(), b.level());
            assertEquals(a.vsWorn(), b.vsWorn());
            assertEquals(a.places(), b.places());
            assertEquals(a.set() == null, b.set() == null);
            if (a.set() != null) {
                assertEquals(a.set().result().objective(), b.set().result().objective(), 1e-6);
            }
            for (int k = 0; k < a.gear().size(); k++) {
                assertEquals(a.gear().get(k).price(), b.gear().get(k).price(), "prices as they were");
                assertEquals(a.gear().get(k).options().size(), b.gear().get(k).options().size());
            }
        }
        assertNull(Roadmap.stale(), "nothing changed: current");
        assertTrue(Roadmap.lastNames().isEmpty(), "a read-back map fetches no prices");
        // the first piece row (a powder row names no slot kind of its own)
        Roadmap.Gear first = read.get(0).gear().stream().filter(g -> !Roadmap.isPowder(g)).findFirst().orElseThrow();
        assertNotNull(Roadmap.lastUpgrade(Slot.valueOf(first.slot().replace("2", ""))), "the tooltip advice uses it");
        // the next session at another level: shown at once, out of date
        Roadmap.forget();
        fx.atLevel(50);
        assertFalse(Roadmap.cached().isEmpty());
        Roadmap.recheck();
        assertTrue(Roadmap.stale().level(), "read back but out of date");
        assertFalse(Roadmap.isBuilding());
    }

    @Test
    void theEmptyStateOnlyWithNoFileAndABrokenFileIsNone() throws Exception {
        assertFalse(Roadmap.hasMap(), "no file: the button");
        Roadmap.build();
        Saver.flush();
        Roadmap.forget();
        assertTrue(Roadmap.hasMap(), "a file: the map, no button");
        Path f = tmp.resolve(GameState.current().characterId()).resolve("roadmap.json");
        Files.writeString(f, "{\"format\":1,\"steps\":[{", StandardCharsets.UTF_8);
        Roadmap.forget();
        assertFalse(Roadmap.hasMap(), "a broken file reads as none");
        assertTrue(Files.exists(f.resolveSibling("roadmap.json.broken")), "and is kept aside");
        Files.writeString(f, "{\"format\":99,\"steps\":[]}", StandardCharsets.UTF_8);
        Roadmap.forget();
        assertFalse(Roadmap.hasMap(), "another format reads as none");
    }
}
