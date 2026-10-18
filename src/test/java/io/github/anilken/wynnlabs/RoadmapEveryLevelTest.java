package io.github.anilken.wynnlabs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every milestone of the road map, not just the far ones: its whole build must be wearable
 * (no exclusive pair, every piece at or below the milestone, skill points that fit the points
 * earned by then) and must not score below the previous milestone's build at the same level,
 * judged as the road map judges a set (with the health floor).
 */
class RoadmapEveryLevelTest {

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    private static int earned(int level) {
        return Math.max(0, (Math.min(level, 101) - 1) * 2);
    }

    @Test
    void everyMilestoneBuildIsWearableAndBetter() throws Exception {
        for (String fixture : new String[] {"shaman-44", "synthetic"}) {
            Path dir = Fixtures.root().resolve(fixture);
            if (!Files.exists(dir.resolve("state.json"))) {
                continue;
            }
            FixtureState.load(dir).install("en");
            // the exact search too (off in the other tests), on two per slot
            com.google.gson.JsonObject rm = new com.google.gson.JsonObject();
            rm.addProperty("exactPool", 2);
            com.google.gson.JsonObject change = new com.google.gson.JsonObject();
            change.add("roadmap", rm);
            Rules.update(change);
            List<String> bad = new ArrayList<>();
            Map<String, Damage.Piece> before = null;
            List<Roadmap.Step> steps = Roadmap.build();
            assumeTrue(!steps.isEmpty(), fixture + ": no road map");
            for (Roadmap.Step s : steps) {
                Map<String, Damage.Piece> b = s.build();
                if (b.isEmpty()) {
                    continue;                               // no build plan in this fixture
                }
                int m = s.level();
                String at = fixture + " Lv " + m + ": ";
                // wearable: exclusive groups, levels, skill points
                Map<String, String> groups = new LinkedHashMap<>();
                for (var e : b.entrySet()) {
                    Damage.Piece p = e.getValue();
                    if (p == null) {
                        continue;
                    }
                    String g = Exclusive.group(p.name());
                    if (g != null && groups.put(g, p.name()) != null) {
                        bad.add(at + "two pieces of " + g);
                    }
                    if (p.level() > m) {
                        bad.add(at + p.name() + " is Lv " + p.level());
                    }
                }
                Build.Eval e = Build.allocate(b, m, earned(m));
                if (e == null) {
                    bad.add(at + "the skill points do not fit " + earned(m) + " (needs "
                            + java.util.Arrays.stream(Build.minPoints(b.values())).sum() + ")");
                }
                // better: the milestone's picks only go in when they raise the score at that level
                if (before != null && e != null) {
                    // judged as the road map judges a set: with the health floor (the respec charge
                    // is left out, both sets being compared at the same level from scratch)
                    Build.Eval prev = Build.allocate(before, m, earned(m));
                    if (prev != null && Roadmap.judged(e, null, m) < Roadmap.judged(prev, null, m) * 0.999) {
                        bad.add(String.format("%sscores %.0f (ehp %.0f), below the previous milestone's build at this level (%.0f, ehp %.0f)",
                                at, Roadmap.judged(e, null, m), e.result().ehp(), Roadmap.judged(prev, null, m), prev.result().ehp()));
                    }
                }
                Fixtures.note(at + (e == null ? "no allocation" : String.format("score %.0f, points %d/%d", e.result().objective(),
                        java.util.Arrays.stream(e.allocation()).sum(), earned(m))));
                before = b;
            }
            assertTrue(bad.isEmpty(), String.join("\n", bad));
        }
    }
}
