package io.github.anilken.wynnlabs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The milestones of the road map as a chain: each one builds on the one before (the respec
 * charge, the keep bonus) yet must not fall behind the same milestone searched on its own; a
 * row's step is what its pick adds to the finished set; and the pills read sensibly for a
 * character that just started, whose Lv 1 gear once made a Lv 120 relik "+64092 %".
 */
class RoadmapMilestonesTest {

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    private static Path shaman() {
        Path dir = Fixtures.root().resolve("shaman-44");
        assumeTrue(Files.exists(dir.resolve("state.json")), "no shaman-44 fixture");
        return dir;
    }

    private static int earned(int level) {
        return Math.max(0, (Math.min(level, 101) - 1) * 2);
    }

    @Test
    void aChainedMilestoneKeepsUpWithOneSearchedFresh() throws Exception {
        FixtureState.load(shaman()).install("en");
        List<Roadmap.Step> steps = Roadmap.build();
        assumeTrue(!steps.isEmpty(), "no road map");
        FixtureState.uninstall();
        List<String> bad = new ArrayList<>();
        for (Roadmap.Step s : steps) {
            if (s.set() == null) {
                continue;
            }
            int m = s.level();
            // the same character at the milestone's level, its map for that level alone: no
            // milestone before it to follow
            FixtureState.load(shaman()).atLevel(m).install("en");
            Roadmap.Computed c = Roadmap.compute(new Roadmap.Inputs(List.of(), Tuning.Choice.PLAIN, true, 0), null);
            FixtureState.uninstall();
            Build.Eval fresh = c == null || c.steps().isEmpty() ? null : c.steps().get(0).set();
            if (fresh == null) {
                continue;
            }
            double chained = Roadmap.judged(s.set(), null, m);
            double alone = Roadmap.judged(fresh, null, m);
            Fixtures.note(String.format("Lv %d: chained %.0f, fresh %.0f (%+.1f %%)", m, chained, alone, (chained / alone - 1) * 100));
            if (chained < alone * 0.97) {
                bad.add(String.format("Lv %d: chained %.0f is more than 3 %% behind fresh %.0f", m, chained, alone));
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    @Test
    void aStepIsWhatThePickAddsToTheFinishedSet() throws Exception {
        FixtureState.load(shaman()).install("en");
        List<Roadmap.Step> steps = Roadmap.build();
        assumeTrue(!steps.isEmpty() && !steps.get(0).build().isEmpty(), "no road map with a build plan");
        int[] prev = Roadmap.assignedPoints(GameState.current());
        // the first milestone starts from what you have, its empty powder slots filled
        Map<String, Damage.Piece> start = Roadmap.powdered(Build.plan().pieces());
        int checked = 0;
        for (Roadmap.Step s : steps) {
            int m = s.level();
            Map<String, Damage.Piece> set = s.build();
            Build.Eval full = Build.allocate(set, m, earned(m));
            assertTrue(full != null, "Lv " + m + ": the set fits");
            double fullJ = Roadmap.judged(full, prev, m);
            for (Roadmap.Gear g : s.gear()) {
                if (Double.isNaN(g.gain()) || Roadmap.isPowder(g)) {
                    continue;                           // a powder row's gain is over the build as worn
                }
                // the build slot that holds the pick and held something else when the milestone began
                String slot = null;
                for (var e : set.entrySet()) {
                    Damage.Piece p = e.getValue();
                    Damage.Piece was = start.get(e.getKey());
                    if (p != null && p.name().equalsIgnoreCase(g.info().name()) && (was == null || !was.key().equals(p.key()))
                            && (slot == null || e.getKey().equals(g.slot().equals("RING") ? "RING1" : g.slot()))) {
                        slot = e.getKey();
                    }
                }
                if (slot == null) {
                    continue;
                }
                Map<String, Damage.Piece> back = new LinkedHashMap<>(set);
                back.remove(slot);
                if (start.get(slot) != null) {
                    back.put(slot, start.get(slot));
                }
                Build.Eval be = Exclusive.clash(back, slot, start.get(slot) == null ? null : Exclusive.group(start.get(slot))) == null
                        ? Build.allocate(back, m, earned(m)) : null;
                if (be == null) {
                    continue;                           // the slot cannot go back: the row keeps its own number
                }
                double expect = (fullJ / Roadmap.judged(be, prev, m) - 1) * 100;
                Fixtures.note(String.format("Lv %d %s %s: step %.1f, in the set %.1f", m, g.slot(), g.info().name(), g.gain(), expect));
                assertEquals(expect, g.gain(), 0.05, "Lv " + m + " " + g.slot() + " " + g.info().name());
                checked++;
            }
            prev = Roadmap.carry(s.set(), set, m);
            start = new LinkedHashMap<>(set);
        }
        assertTrue(checked > 10, "only " + checked + " rows checked");
    }

    @Test
    void aNewCharactersPillsReadSensibly() throws Exception {
        FixtureState.load(shaman()).fresh().install("en");
        List<Roadmap.Step> steps = Roadmap.build();
        assumeTrue(!steps.isEmpty(), "no road map");
        List<String> bad = new ArrayList<>();
        Map<String, Roadmap.Gear> latest = new LinkedHashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            Roadmap.Step s = steps.get(i);
            for (Roadmap.Gear g : s.gear()) {
                latest.put(g.slot(), g);
            }
            Roadmap.keepWorn(latest, s);
            for (Roadmap.Gear g : latest.values()) {
                double shown = Roadmap.shownGain(steps, i, g);
                String pill = Roadmap.pill(shown, g);
                Fixtures.note("Lv " + s.level() + " " + g.slot() + " " + g.info().name() + ": " + pill);
                // nothing is worn: every row shows its step over the milestone before, never a
                // number against an empty slot
                assertTrue(Roadmap.showsStep(steps, i, g), "Lv " + s.level() + " " + g.slot() + " shows its step");
                if (!pill.equals("New") && !pill.equals("Set") && !pill.matches("[+−]\\d{1,3}%")) {
                    bad.add("Lv " + s.level() + " " + g.slot() + " " + g.info().name() + ": " + pill);
                }
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
        // the first weapon of an empty build has nothing to compare with
        Roadmap.Gear weapon = steps.get(0).gear().stream()
                .filter(g -> g.info().slot().isWeapon()).findFirst().orElse(null);
        assertTrue(weapon != null, "a weapon at the start");
        assertEquals("New", Roadmap.pill(Roadmap.shownGain(steps, 0, weapon), weapon));
    }

    @Test
    void pillsCapLargeNumbersAndNameMissingOnes() throws Exception {
        FixtureState.load(shaman()).install("en");
        assertEquals("+12%", Roadmap.pill(12.3, null));
        assertEquals("−3%", Roadmap.pill(-3.4, null));
        assertEquals(">999%", Roadmap.pill(64092, null));
        assertEquals("New", Roadmap.pill(Double.NaN, null));
    }
}
