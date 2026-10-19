package io.github.anilken.wynnlabs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window's search on the recorded Shaman: several builds that keep the chosen items, differ
 * from each other in at least two slots and carry the right labels; a newer request or Cancel
 * drops what an older search finds.
 */
class AroundTest {

    @AfterEach
    void reset() throws Exception {
        Around.cancel();
        Around.settle();
        Around.forget();
        FixtureState.uninstall();
    }

    private static List<Roadmap.Candidate> builds(List<String> around) {
        Roadmap.Computed c = Roadmap.compute(new Roadmap.Inputs(around, Tuning.Choice.PLAIN, true, Around.BUILDS), null);
        assertNotNull(c);
        assertEquals(1, c.steps().size(), "your level only");
        return c.builds();
    }

    @Test
    void severalBuildsThatKeepTheChoiceAndDiffer() throws Exception {
        FixtureState.load(RoadmapLocksTest.shaman()).install("en");
        List<Roadmap.Candidate> b = builds(List.of(RoadmapLocksTest.DEX_RELIK));
        assertEquals(Around.BUILDS, b.size(), "four builds");
        for (Roadmap.Candidate c : b) {
            assertEquals(RoadmapLocksTest.DEX_RELIK, c.set().get("WEAPON").name(), "every build keeps the relik");
            assertNotNull(c.eval(), "its skill points are met");
            assertTrue(c.fixed().contains("WEAPON"));
        }
        for (int i = 0; i < b.size(); i++) {
            if (i > 0) {
                assertTrue(b.get(i).score() <= b.get(i - 1).score(), "best first");
            }
            for (int j = i + 1; j < b.size(); j++) {
                int d = Roadmap.distance(b.get(i).set(), b.get(j).set(), b.get(i).fixed());
                assertTrue(d >= Roadmap.MIN_DISTANCE, "builds " + (i + 1) + " and " + (j + 1) + " differ in " + d + " slots");
            }
        }
    }

    @Test
    void theLabelsAreRight() throws Exception {
        FixtureState.load(RoadmapLocksTest.shaman()).install("en");
        List<Roadmap.Candidate> b = builds(List.of());
        assertFalse(b.isEmpty());
        assertEquals(List.of("best"), b.get(0).labels(), "the first is the best");
        double top = b.get(0).score();
        for (int i = 0; i < b.size(); i++) {
            Roadmap.Candidate c = b.get(i);
            for (String l : c.labels()) {
                switch (l) {
                    case "cheap" -> {
                        assertTrue(c.score() >= top * (1 - Roadmap.CHEAP_WITHIN), "cheap is within 3 % of the best");
                        for (Roadmap.Candidate o : b) {
                            if (o != c && o.score() >= top * (1 - Roadmap.CHEAP_WITHIN)) {
                                assertTrue(c.price() < o.price(), "cheap is the cheapest of them");
                            }
                        }
                    }
                    case "tanky" -> b.stream().filter(o -> o != c)
                            .forEach(o -> assertTrue(c.eval().result().ehp() > o.eval().result().ehp(), "tanky has the most effective health"));
                    case "fast" -> b.stream().filter(o -> o != c)
                            .forEach(o -> assertTrue(c.eval().result().speed() > o.eval().result().speed(), "fast walks the fastest"));
                    case "best" -> assertEquals(0, i);
                    default -> throw new AssertionError("unknown label " + l);
                }
            }
            assertTrue(c.labels().size() <= 1, "one label a build");
        }
        // a made-up list: who gets which label
        Damage.Result slow = new Damage.Result(100, 0, 0, 0, 1000, 0, 0, 0, new double[] {1}, 1000, 100);
        Damage.Result tank = new Damage.Result(99, 0, 0, 0, 5000, 0, 0, 0, new double[] {1}, 5000, 100);
        Damage.Result quick = new Damage.Result(90, 0, 0, 0, 900, 0, 30, 0, new double[] {1}, 900, 100);
        List<Roadmap.Candidate> made = Roadmap.label(List.of(
                cand(100, 5000, slow), cand(99.5, 800, tank), cand(98, 300, quick), cand(80, 10, slow)));
        assertEquals(List.of("best"), made.get(0).labels());
        assertEquals(List.of("tanky"), made.get(1).labels(), "the highest effective health");
        assertEquals(List.of("cheap"), made.get(2).labels(), "the cheapest within 3 %, before its speed");
        assertEquals(List.of(), made.get(3).labels(), "cheapest of all but 20 % behind: no label");
    }

    private static Roadmap.Candidate cand(double score, double price, Damage.Result r) {
        return new Roadmap.Candidate(java.util.Map.of(), new Build.Eval(new int[5], r), score, price, List.of(), java.util.Set.of());
    }

    @Test
    void aNewerRequestOrCancelDropsTheOlderResult() throws Exception {
        FixtureState.load(RoadmapLocksTest.shaman()).install("en");
        Around.forget();
        // a newer request: only its result is kept
        int first = Around.request(List.of(RoadmapLocksTest.DEX_RELIK), Tuning.Choice.PLAIN, false);
        int second = Around.request(List.of("Morph-Emerald"), Tuning.Choice.PLAIN, false);
        Around.settle();
        Around.Result r = Around.result();
        assertNotNull(r, "the newer search finished");
        assertEquals(second, r.gen());
        assertTrue(second > first);
        assertEquals(List.of("Morph-Emerald"), r.around());
        assertFalse(Around.running());
        // Cancel: the result before it stays, nothing new comes
        Around.request(List.of(RoadmapLocksTest.DEX_RELIK), Tuning.Choice.PLAIN, false);
        Around.cancel();
        Around.settle();
        assertEquals(second, Around.result().gen(), "the cancelled search left nothing");
        assertNull(Around.progress(), "nothing running");
        // a job stopped mid-run ends with Cancelled, not a half result
        Roadmap.Job j = new Roadmap.Job();
        j.cancel();
        boolean stopped = false;
        try {
            Roadmap.compute(new Roadmap.Inputs(List.of(), Tuning.Choice.PLAIN, true, Around.BUILDS), j);
        } catch (Roadmap.Cancelled e) {
            stopped = true;
        }
        assertTrue(stopped, "a cancelled job stops");
    }
}
