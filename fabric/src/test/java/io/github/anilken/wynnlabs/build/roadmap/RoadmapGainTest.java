package io.github.anilken.wynnlabs.build.roadmap;

import io.github.anilken.wynnlabs.advice.AutoAdvice;
import io.github.anilken.wynnlabs.build.Exclusive;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.game.Fixture;
import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.game.ItemJson;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.model.ItemData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The road map on the recorded Shaman: a milestone's "worn → pick" gain (Step.vsWorn) and its
 * reasons against the step gain each pick carries (Gear.gain); later milestones filter tiers,
 * sources and off-skill points by their own level; today's listings do not move far milestones.
 */
class RoadmapGainTest {

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    private static Path shaman() {
        Path dir = Fixtures.root().resolve("shaman-44");
        assumeTrue(Files.exists(dir.resolve("state.json")), "no shaman-44 fixture");
        return dir;
    }

    @Test
    void wornToPickGainIsCumulative() throws Exception {
        FixtureState.load(shaman()).install("en");
        long t0 = System.nanoTime();
        List<Roadmap.Step> steps = Roadmap.build();
        Fixtures.note("road map built in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        assertFalse(steps.isEmpty(), "steps");
        // the screen's upgrades(): the latest pick per slot up to the milestone
        Map<String, Roadmap.Gear> latest = new LinkedHashMap<>();
        boolean bigger = false;
        for (Roadmap.Step s : steps) {
            for (Roadmap.Gear g : s.gear()) {
                latest.put(g.slot(), g);
            }
            for (var e : latest.entrySet()) {
                Roadmap.Gear g = e.getValue();
                Double cum = s.vsWorn().get(e.getKey());
                Fixtures.note("Lv " + s.level() + " " + e.getKey() + " " + g.info().name() + " (Lv "
                        + g.info().requirements().level() + "): step +" + Math.round(g.gain() * 10) / 10.0
                        + " %, vs worn " + (cum == null ? "-" : "+" + Math.round(cum * 10) / 10.0 + " %")
                        + " " + s.whyVsWorn().getOrDefault(e.getKey(), List.of()));
                if (cum != null && !Double.isNaN(g.gain()) && cum > g.gain() + 5) {
                    bigger = true;
                }
            }
            // a slot can be missing only when its worn piece no longer fits the build (the pick's
            // skill bonus carried another piece's requirement): then the row keeps the step gain
            assertTrue(s.gear().isEmpty() || !s.vsWorn().isEmpty(), "Lv " + s.level() + " has worn → pick gains");
            assertEquals(s.vsWorn().keySet(), s.whyVsWorn().keySet(), "Lv " + s.level() + ": a reason for every gain");
            for (var e : s.whyVsWorn().entrySet()) {
                assertTrue(e.getValue().size() <= Roadmap.WHY_LINES, "Lv " + s.level() + " " + e.getKey() + " ≤ 4 lines");
                if (s.vsWorn().get(e.getKey()) >= 5) {
                    assertFalse(e.getValue().isEmpty(), "Lv " + s.level() + " " + e.getKey() + " says why");
                }
            }
        }
        assertTrue(bigger, "far ahead the worn → pick gain is well above the step gain");
    }

    /** Every gear pick as "level slot name", in order. */
    private static List<String> picks(List<Roadmap.Step> steps, int from) {
        List<String> out = new ArrayList<>();
        for (Roadmap.Step s : steps) {
            if (s.level() >= from) {
                s.gear().forEach(g -> out.add(s.level() + " " + g.slot() + " " + g.info().name()));
            }
        }
        return out;
    }

    @Test
    void sameInputsSamePicks() throws Exception {
        FixtureState.load(shaman()).install("en");
        List<String> first = picks(Roadmap.build(), 0);
        assertEquals(first, picks(Roadmap.build(), 0));
        assertEquals(Roadmap.json().toString(), Roadmap.json().toString());
    }

    @Test
    void listingsOnlyMoveTheNearMilestones(@TempDir Path tmp) throws Exception {
        Path dir = shaman();
        FixtureState fx = FixtureState.load(dir);
        fx.install("en");
        int level = GameState.current().level();
        int ahead = (int) Rules.sub("shopping", "aheadLevels", 5);
        List<String> before = picks(Roadmap.build(), level + ahead + 1);
        long now = GameState.current().now();
        FixtureState.uninstall();
        // every piece above Lv 60 "has no listing right now"
        Fixture.Data d = fx.data;
        Map<String, Prices.Price> prices = new LinkedHashMap<>(d.prices());
        d.gearDb().gear().stream().filter(g -> g.requirements().level() > 60).map(ItemData::name)
                .forEach(n -> prices.put(n, new Prices.Price(100_000, null, null, null, null, now, null, 0)));
        Fixture.write(new Fixture.Data(d.state(), d.items(), d.gearDb(), prices, d.rules(), d.settings()), tmp, null);
        FixtureState.load(tmp).install("en");
        assertEquals(before, picks(Roadmap.build(), level + ahead + 1));
    }

    @Test
    void laterMilestonesFilterByTheirOwnLevel() throws Exception {
        FixtureState.load(shaman()).install("en");
        // the recorded rules leave Mythics out and allow 10 off-skill points
        assertTrue(Roadmap.badTiers(44).contains("MYTHIC"), "Lv 44: no Mythic");
        assertTrue(Roadmap.badTiers(120).isEmpty(), "Lv 120: every tier");
        assertTrue(Roadmap.badSources(44).contains("RAID") && !Roadmap.badSources(120).contains("RAID"), "raids");
        assertEquals(30, Roadmap.maxOff(120));
        assertTrue(Roadmap.maxOff(44) < Roadmap.maxOff(120));
        // Mythics are weighed from the endgame milestones on (picked or not: that is the score's call),
        // and never picked before it
        boolean weighed = false;
        java.util.List<String> scored = new java.util.ArrayList<>();
        Roadmap.trace = (m, slot, line) -> {
            if (m >= 80 && (line.contains(": ranked") || line.contains(": judged"))) {
                scored.add(line);
            }
        };
        List<Roadmap.Step> steps;
        try {
            steps = Roadmap.build();
        } finally {
            Roadmap.trace = null;
        }
        java.util.Set<String> mythics = new java.util.HashSet<>();
        GameState.current().allGear().filter(i -> i.tier().name().equals("MYTHIC")).forEach(i -> mythics.add(i.name()));
        for (String line : scored) {
            weighed |= mythics.contains(line.substring(0, line.indexOf(" Lv")));
        }
        assertTrue(weighed, "a Mythic is weighed once the milestones reach endgame");
        for (Roadmap.Step s : steps) {
            for (Roadmap.Gear g : s.gear()) {
                if (g.info().tier().name().equals("MYTHIC")) {
                    assertTrue(s.level() >= Rules.sub("shopping", "endgameLevel", 80), g.info().name() + " at Lv " + s.level());
                }
            }
        }
    }

    @Test
    void noRowShowsALoss() {
        // a pick shows what it gains against what you wear, alone or with its milestone's other
        // picks (the larger); a pick can beat an earlier one only together with its milestone
        // (Hardcore after Stormdrain), so the number may drop between milestones, never below 0
        try {
            FixtureState.load(shaman()).install("en");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        for (Roadmap.Step s : Roadmap.build()) {
            for (Roadmap.Gear g : s.gear()) {
                Double v = s.vsWorn().get(g.slot());
                if (v != null) {
                    assertTrue(v > 0, "Lv " + s.level() + " " + g.slot() + " " + g.info().name() + " shows " + Math.round(v) + "%");
                }
            }
        }
    }

    @Test
    void noMilestoneWearsTwoPiecesOfAnExclusiveGroup() throws Exception {
        // WynnBuilder refused the Lv 120 build: Ornate Shadow Cowl with Ornate Shadow Cover
        FixtureState.load(shaman()).install("en");
        Map<String, Roadmap.Gear> latest = new LinkedHashMap<>();
        for (Roadmap.Step s : Roadmap.build()) {
            for (Roadmap.Gear g : s.gear()) {
                latest.put(g.slot(), g);
            }
            Map<String, String> seen = new LinkedHashMap<>();
            for (Roadmap.Gear g : latest.values()) {
                String group = Exclusive.group(g.info());
                if (group != null) {
                    String other = seen.put(group, g.info().name());
                    assertTrue(other == null, "Lv " + s.level() + ": " + other + " and " + g.info().name() + " are both " + group);
                }
            }
        }
    }

    @Test
    void theNowTabNeverOffersWhatYouWear() throws Exception {
        // "Yume → Yume": the beam kept the worn helmet over the slot pick and showed it as a pick
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("en");
        List<Roadmap.Step> steps = Roadmap.build();
        for (Roadmap.Gear g : steps.get(0).gear()) {
            if (g.slot().startsWith("RING") || Roadmap.isPowder(g)) {
                continue;                           // a second copy of a worn ring is a real buy; a powder row powders what you wear
            }
            for (net.minecraft.world.item.ItemStack w : AutoAdvice.worn(g.info().slot())) {
                String worn = ItemJson.clean(w.getHoverName().getString()).trim();
                assertTrue(!worn.equalsIgnoreCase(g.info().name()), g.slot() + " offers " + worn + ", which you wear");
            }
        }
    }

    @Test
    void exclusiveGroupsAreKnown() {
        assertEquals("Ornate Shadow", Exclusive.group("Ornate Shadow Cowl"));
        assertEquals("Master Hive", Exclusive.group("Prowess"));
        assertEquals("Fire Hive", Exclusive.group("Mantlewalkers"));
        assertEquals(null, Exclusive.group("Photon"));
    }
}
