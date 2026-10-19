package io.github.anilken.wynnlabs;

import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.model.AbilityNode;
import io.github.anilken.wynnlabs.model.AspectEntry;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.RankedAspect;
import io.github.anilken.wynnlabs.model.Tier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The aspect ranking of design decision 7 on a small synthetic shaman tree and aspect list. */
class AspectRankTest {

    private static final String RIT = "Ritualist";
    private static final String SUM = "Summoner";
    private static final String ACO = "Acolyte";

    private static final String DAMNATION = "Aspect of Damnation";
    private static final String LEGION = "Aspect of the Beckoned Legion";
    private static final String DANCE = "Aspect of the Eternal Dance";
    private static final String MONOLITH = "Aspect of the Monolith";
    private static final String ACCELERATION = "Aspect of Acceleration";
    private static final String EXSANGUINATION = "Aspect of Exsanguination";
    private static final String EMBODIMENT = "Ritualist's Embodiment of the Ancestral Avatar";

    @BeforeEach
    void english() {
        Settings.use(new JsonObject());
        AspectBook.reset();
    }

    @AfterEach
    void reset() {
        AspectBook.reset();
        Settings.use(new JsonObject());
    }

    private static AbilityNode node(int id, String name, String type, String archetype) {
        return new AbilityNode(id, name, type, List.of(), 1, List.of(), null, null, 0, 0, archetype, null,
                new AbilityNode.Place(1, 0, id % 9), List.of());
    }

    private static List<AbilityNode> tree() {
        return List.of(
                node(1, "Totem", "SHAMAN_ABILITY_LOCKED", null),
                node(2, "Malediction", "SKILL_PURPLE_LOCKED", RIT),
                node(3, "Ritual Circle", "SKILL_PURPLE_LOCKED", RIT),
                node(4, "Puppet Master", "SKILL_YELLOW_LOCKED", SUM),
                node(5, "Uproot", "SKILL_YELLOW_LOCKED", SUM),
                node(6, "Blood Sorrow", "SKILL_RED_LOCKED", ACO));
    }

    private static AspectEntry aspect(String name, Tier rarity, int tiers, String... abilities) {
        return new AspectEntry(name, Klass.SHAMAN, rarity, tiers, List.of(), List.of(abilities));
    }

    private static List<AspectEntry> aspects() {
        return List.of(
                aspect(DAMNATION, Tier.LEGENDARY, 4, "Malediction"),
                aspect(LEGION, Tier.FABLED, 3, "Puppet Master"),
                aspect(DANCE, Tier.FABLED, 3, "Ritual Circle"),
                aspect(MONOLITH, Tier.LEGENDARY, 4, "Totem"),
                aspect(ACCELERATION, Tier.LEGENDARY, 4),
                aspect(EXSANGUINATION, Tier.FABLED, 3),
                aspect(EMBODIMENT, Tier.MYTHIC, 3, "Eye of the Storm", "Ritual Circle"));
    }

    private static List<RankedAspect> rank(Map<String, Integer> owned, Set<String> unlocked, Set<String> planned,
            String archetype) {
        return AspectRank.rank(aspects(), owned, Set.of(), tree(), unlocked, planned, archetype);
    }

    private static List<String> names(List<RankedAspect> rows) {
        return rows.stream().map(r -> r.aspect().name()).toList();
    }

    private static RankedAspect row(List<RankedAspect> rows, String name) {
        return rows.stream().filter(r -> r.aspect().name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void archetypeMatchRanksAboveAnotherArchetype() {
        List<RankedAspect> rows = rank(Map.of(), Set.of("Malediction"), Set.of(), RIT);
        RankedAspect damnation = row(rows, DAMNATION);
        RankedAspect legion = row(rows, LEGION);
        assertEquals(1, damnation.rank());
        assertEquals(3, damnation.score());
        assertEquals(0, damnation.ownedTier());
        assertEquals("Boosts Malediction (Ritualist, unlocked)", damnation.reason());
        assertEquals(List.of(new RankedAspect.Match("Malediction", RIT, true, false, 3)), damnation.matches());
        assertTrue(rows.indexOf(damnation) < rows.indexOf(legion));
        assertEquals(0, legion.score());
        assertEquals(0, legion.rank());
    }

    @Test
    void plannedRanksBelowUnlocked() {
        List<RankedAspect> rows = rank(Map.of(), Set.of("Malediction"), Set.of("Ritual Circle"), RIT);
        // Damnation 3 (unlocked); the mythic and Eternal Dance 2 each (planned), the mythic first by rarity;
        // Monolith 1 (class spell)
        assertEquals(List.of(DAMNATION, EMBODIMENT, DANCE, MONOLITH), names(rows).subList(0, 4));
        assertEquals(List.of(1, 2, 3, 4), rows.subList(0, 4).stream().map(RankedAspect::rank).toList());
        assertEquals(2, row(rows, DANCE).score());
        assertEquals("Boosts Ritual Circle (Ritualist, planned)", row(rows, DANCE).reason());
    }

    @Test
    void classSpellAspectScoresOne() {
        RankedAspect monolith = row(rank(Map.of(), Set.of(), Set.of(), RIT), MONOLITH);
        assertEquals(1, monolith.score());
        assertEquals(List.of(new RankedAspect.Match("Totem", null, false, false, 1)), monolith.matches());
        assertEquals("Boosts Totem", monolith.reason());
    }

    @Test
    void anotherArchetypeUnlockedScoresOne() {
        RankedAspect legion = row(rank(Map.of(), Set.of("Puppet Master"), Set.of(), RIT), LEGION);
        assertEquals(1, legion.score());
        assertEquals("Boosts Puppet Master (Summoner, unlocked)", legion.reason());
    }

    @Test
    void embodimentCountsForItsArchetype() {
        // Ritual Circle is a Ritualist node anyway; the name keeps the mythic Ritualist with the tree's archetype missing
        List<AbilityNode> tree = List.of(node(3, "Ritual Circle", "SKILL_WHITE_LOCKED", null), node(9, "Mask", "X", RIT));
        List<RankedAspect> rows = AspectRank.rank(List.of(aspect(EMBODIMENT, Tier.MYTHIC, 3, "Ritual Circle")), Map.of(),
                Set.of(), tree, Set.of("Ritual Circle"), Set.of(), RIT);
        assertEquals(3, rows.get(0).score());
        assertEquals("Boosts Ritual Circle (Ritualist, unlocked)", rows.get(0).reason());
    }

    @Test
    void noMatchGoesLastByRarity() {
        Map<String, Integer> owned = new HashMap<>();
        owned.put(MONOLITH, 4);                 // maxed: after everything unfinished
        List<RankedAspect> rows = rank(owned, Set.of("Malediction"), Set.of("Ritual Circle"), RIT);
        assertEquals(List.of(DAMNATION, EMBODIMENT, DANCE, LEGION, EXSANGUINATION, ACCELERATION, MONOLITH), names(rows));
        RankedAspect acceleration = row(rows, ACCELERATION);
        assertEquals(0, acceleration.rank());
        assertEquals("Not tied to an ability", acceleration.reason());
        assertTrue(acceleration.unfinished());
        RankedAspect monolith = row(rows, MONOLITH);
        assertTrue(monolith.maxed());
        assertEquals(0, monolith.rank());
    }

    @Test
    void missingBeforeUnderMaxOnATie() {
        List<AspectEntry> list = List.of(aspect("Aspect of One", Tier.LEGENDARY, 4, "Malediction"),
                aspect("Aspect of Two", Tier.LEGENDARY, 4, "Malediction"));
        List<RankedAspect> rows = AspectRank.rank(list, Map.of("Aspect of One", 2), Set.of(), tree(),
                Set.of("Malediction"), Set.of(), RIT);
        assertEquals(List.of("Aspect of Two", "Aspect of One"), names(rows));
        assertEquals(2, rows.get(1).ownedTier());
    }

    @Test
    void unknownOwnedTiersOrderByFitWithoutNumbers() {
        List<RankedAspect> rows = rank(null, Set.of("Malediction"), Set.of(), RIT);
        assertEquals(DAMNATION, rows.get(0).aspect().name());
        for (RankedAspect r : rows) {
            assertEquals(0, r.rank(), r.aspect().name());
            assertEquals(-1, r.ownedTier(), r.aspect().name());
            assertFalse(r.unfinished(), r.aspect().name());
            assertFalse(r.maxed(), r.aspect().name());
        }
    }

    @Test
    void equippedNamesMayCarryAPrefix() {
        List<RankedAspect> rows = AspectRank.rank(aspects(), Map.of(), Set.of("§5" + DAMNATION), tree(), Set.of(),
                Set.of(), RIT);
        assertTrue(row(rows, DAMNATION).equipped());
        assertFalse(row(rows, DANCE).equipped());
    }

    @Test
    void noArchetypeChosenFallsBackToTheMostUnlocked() {
        AspectBook.install(Klass.SHAMAN, aspects(), Map.of(), List.of(), tree(),
                Set.of("Puppet Master", "Uproot", "Malediction"), Set.of(), null);
        assertEquals(SUM, AspectBook.archetype());
        assertTrue(AspectBook.archetypeGuessed());
        assertEquals(LEGION, AspectBook.next().aspect().name());

        AspectBook.install(Klass.SHAMAN, aspects(), Map.of(), List.of(), tree(), Set.of("Malediction"), Set.of(), RIT);
        assertEquals(RIT, AspectBook.archetype());
        assertFalse(AspectBook.archetypeGuessed());
    }

    @Test
    void mostUnlockedIsTheFirstArchetypeWhenNoneUnlocked() {
        assertEquals(RIT, AspectRank.mostUnlocked(tree(), Set.of()));
        assertNull(AspectRank.mostUnlocked(null, Set.of()));
        assertNull(AspectRank.mostUnlocked(List.of(node(1, "Totem", "SHAMAN_ABILITY_LOCKED", null)), Set.of()));
    }
}
