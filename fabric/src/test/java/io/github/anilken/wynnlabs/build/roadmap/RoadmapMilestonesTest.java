package io.github.anilken.wynnlabs.build.roadmap;

import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.build.Exclusive;
import io.github.anilken.wynnlabs.build.Tuning;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;
import io.github.anilken.wynnlabs.game.GameState;

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

    /** One milestone of the chained map beside the same milestone searched on its own. */
    private record Pair(int level, Map<String, Damage.Piece> chained, Build.Eval chainedEval,
            Map<String, Damage.Piece> fresh, Build.Eval freshEval, int[] prev, List<String> unseen) {}

    /** The chained map's milestones, each with the map searched for that level alone (nowOnly); worked out once. */
    private static List<Pair> pairs;

    private static List<Pair> pairs() throws Exception {
        if (pairs != null) {
            return pairs;
        }
        FixtureState.load(shaman()).install("en");
        List<Roadmap.Step> steps = Roadmap.build();
        int[] prev = Roadmap.assignedPoints(GameState.current());
        FixtureState.uninstall();
        assumeTrue(!steps.isEmpty(), "no road map");
        List<Pair> out = new ArrayList<>();
        for (Roadmap.Step s : steps) {
            if (s.set() == null) {
                continue;
            }
            int m = s.level();
            // the same character at the milestone's level, its map for that level alone: no
            // milestone before it to follow
            FixtureState.load(shaman()).atLevel(m).install("en");
            Roadmap.Computed c = Roadmap.compute(new Roadmap.Inputs(List.of(), Tuning.Choice.PLAIN, true, 0), null);
            // the chain's pieces a search at this level alone never looks at: below the window
            // (rules.shopping.milestoneWindow) and not yours; the chain carried them from before
            java.util.Set<String> owned = Roadmap.ownedCounts(GameState.current()).keySet();
            int floor = m - (int) Rules.sub("shopping", "milestoneWindow", 20);
            List<String> unseen = new ArrayList<>();
            s.build().values().forEach(p -> {
                if (p != null && p.level() < floor && !owned.contains(p.name())) {
                    unseen.add(p.name() + " (Lv " + p.level() + ")");
                }
            });
            FixtureState.uninstall();
            Roadmap.Step f = c == null || c.steps().isEmpty() ? null : c.steps().get(0);
            if (f != null && f.set() != null) {
                out.add(new Pair(m, s.build(), s.set(), f.build(), f.set(), prev, unseen));
            }
            prev = Roadmap.carry(s.set(), s.build(), m);
        }
        pairs = out;
        return out;
    }

    @Test
    void aChainedMilestoneKeepsUpWithOneSearchedFresh() throws Exception {
        // both sets judged as the chain judges its own: against the last milestone's points and
        // pieces (the respec charge, the keep bonus). A fresh set that drops Defence the chain
        // built up pays for the reset, so the raw scores would compare a set the chain may not take
        List<String> bad = new ArrayList<>();
        for (Pair p : pairs()) {
            double chained = Roadmap.judged(p.chained(), p.chainedEval(), p.prev(), p.level());
            double alone = Roadmap.judged(p.fresh(), p.freshEval(), p.prev(), p.level());
            Fixtures.note(String.format("Lv %d: chained %.0f, fresh %.0f (%+.1f %%), judged against the chain's points",
                    p.level(), chained, alone, (chained / alone - 1) * 100));
            if (chained < alone * 0.97) {
                bad.add(String.format("Lv %d: chained %.0f is more than 3 %% behind fresh %.0f", p.level(), chained, alone));
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    @Test
    void aMilestoneSearchedAloneKeepsUpWithTheChain() throws Exception {
        // the build window's "now" searches your level alone: it has no milestones before it to
        // build on, yet must not land far below the map's set for the same level (raw scores:
        // each answers to its own points). Where the chain wears pieces from below the window
        // that it carried along (Lv 120: the Diamond Fusion rings at Lv 95), the search alone
        // cannot have them by rule: noted, not compared
        List<String> bad = new ArrayList<>();
        int compared = 0;
        for (Pair p : pairs()) {
            double chained = p.chainedEval().result().objective();
            double alone = p.freshEval().result().objective();
            Fixtures.note(String.format("Lv %d: alone %.0f, chained %.0f (%+.1f %%)%s", p.level(), alone, chained,
                    (alone / chained - 1) * 100, p.unseen().isEmpty() ? "" : ", chained wears " + p.unseen()));
            if (!p.unseen().isEmpty()) {
                continue;
            }
            compared++;
            if (alone < chained * 0.90) {
                bad.add(String.format("Lv %d: alone %.0f is more than 10 %% behind chained %.0f", p.level(), alone, chained));
            }
        }
        // with XP left out on the Damage goal the chain keeps the Lv 33 Desert Winds rings up to
        // Lv 80 (no XP ring replaces them), so fewer milestones are comparable than before
        assertTrue(compared >= 4, "only " + compared + " milestones compared");
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
            java.util.Set<String> claimed = new java.util.HashSet<>();
            for (Roadmap.Gear g : s.gear()) {
                if (Double.isNaN(g.gain()) || Roadmap.isPowder(g)) {
                    continue;                           // a powder row's gain is over the build as worn
                }
                // the build slots that hold the pick and held something else when the milestone
                // began, the row's own first (RING: the first hand). The same ring in both hands
                // (Diamond Fusion at Lv 100) is two rows, one per hand, in the order the search
                // filled them: either hand may be the "RING" row, but not both rows the same hand
                List<String> slots = new ArrayList<>();
                String own = g.slot().equals("RING") ? "RING1" : g.slot();
                for (var e : set.entrySet()) {
                    Damage.Piece p = e.getValue();
                    Damage.Piece was = start.get(e.getKey());
                    if (p != null && p.name().equalsIgnoreCase(g.info().name()) && (was == null || !was.key().equals(p.key()))
                            && !claimed.contains(e.getKey())) {
                        slots.add(e.getKey().equals(own) ? 0 : slots.size(), e.getKey());
                    }
                }
                double first = Double.NaN;
                String hit = null;
                for (String slot : slots) {
                    Map<String, Damage.Piece> back = new LinkedHashMap<>(set);
                    back.remove(slot);
                    if (start.get(slot) != null) {
                        back.put(slot, start.get(slot));
                    }
                    Build.Eval be = Exclusive.clash(back, slot, start.get(slot) == null ? null : Exclusive.group(start.get(slot))) == null
                            ? Build.allocate(back, m, earned(m)) : null;
                    if (be == null) {
                        continue;                       // the slot cannot go back: the row keeps its own number
                    }
                    double expect = (fullJ / Roadmap.judged(be, prev, m) - 1) * 100;
                    if (Double.isNaN(first)) {
                        first = expect;
                    }
                    if (Math.abs(expect - g.gain()) <= 0.05) {
                        hit = slot;
                        first = expect;
                        break;
                    }
                }
                if (Double.isNaN(first)) {
                    continue;
                }
                Fixtures.note(String.format("Lv %d %s %s: step %.1f, in the set %.1f (%s)", m, g.slot(), g.info().name(), g.gain(),
                        first, hit));
                assertEquals(first, g.gain(), 0.05, "Lv " + m + " " + g.slot() + " " + g.info().name());
                if (slots.size() > 1 && hit != null && hit.startsWith("RING")) {
                    claimed.add(hit);
                }
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
