package io.github.anilken.wynnlabs.endgame;

import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.model.AspectEntry;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.RankedAspect;
import io.github.anilken.wynnlabs.model.Tier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The aspect book: reading the cached aspect data, class filtering and owned tiers unknown until seen. */
class AspectBookTest {

    /** Three shaman aspects (one mythic) and one warrior aspect, in the cache file's shape. */
    private static final String CACHE = """
            {
              "shaman": {
                "Aspect of Damnation": {
                  "name": "Aspect of Damnation", "rarity": "legendary", "requiredClass": "shaman",
                  "tiers": {
                    "1": {"description": [[{"text": "Malediction", "underline": true}, {"text": " deals +5% damage."}]], "threshold": 1},
                    "2": {"description": [[{"text": "Malediction", "underline": true}, {"text": " deals +10% damage."}]], "threshold": 5},
                    "3": {"description": [[{"text": "Malediction", "underline": true}, {"text": " deals +15% damage."}]], "threshold": 30},
                    "4": {"description": [[{"text": "Malediction", "underline": true}, {"text": " deals +20% damage."}]], "threshold": 150}
                  }
                },
                "Aspect of the Monolith": {
                  "name": "Aspect of the Monolith", "rarity": "legendary", "requiredClass": "shaman",
                  "tiers": {
                    "1": {"description": [[{"text": "Your "}, {"text": "Tot", "underline": true}, {"text": "em", "underline": true},
                          {"text": " lasts "}, {"text": "+2s", "underline": false}, {"text": " longer."}]], "threshold": 1},
                    "2": {"description": [[{"text": "Your "}, {"text": "Totem", "underline": true}, {"text": " lasts +4s longer."}]], "threshold": 5}
                  }
                },
                "Ritualist's Embodiment of the Ancestral Avatar": {
                  "name": "Ritualist's Embodiment of the Ancestral Avatar", "rarity": "mythic", "requiredClass": "shaman",
                  "tiers": {
                    "10": {"description": [[{"text": "Eye of the Storm", "underline": true}, {"text": " is stronger."}]], "threshold": 15},
                    "2": {"description": [[{"text": "Eye of the Storm", "underline": true}], [], [{"text": "Ritual Circle", "underline": true}, {"text": " grows."}]], "threshold": 5},
                    "1": {"description": [[{"text": "Eye of the Storm", "underline": true}, {"text": " is stronger."}]], "threshold": 1}
                  }
                }
              },
              "warrior": {
                "Aspect of the Warlord": {
                  "name": "Aspect of the Warlord", "rarity": "fabled", "requiredClass": "warrior",
                  "tiers": {"1": {"description": [[{"text": "Bash", "underline": true}]], "threshold": 1}}
                }
              },
              "notaclass": {"x": {"name": "x"}}
            }
            """;

    private static final String DAMNATION = "Aspect of Damnation";
    private static final String MONOLITH = "Aspect of the Monolith";
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

    private static List<AspectEntry> shaman() {
        return AspectBook.parseCache(CACHE).get(Klass.SHAMAN);
    }

    private static AspectEntry named(List<AspectEntry> list, String name) {
        return list.stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void parsesRarityTiersThresholdsAndAbilities() {
        List<AspectEntry> list = shaman();
        assertEquals(List.of(DAMNATION, MONOLITH, EMBODIMENT), list.stream().map(AspectEntry::name).toList());

        AspectEntry damnation = named(list, DAMNATION);
        assertEquals(Klass.SHAMAN, damnation.klass());
        assertEquals(Tier.LEGENDARY, damnation.rarity());
        assertEquals(4, damnation.tiers());
        assertEquals(List.of(1, 5, 30, 150), damnation.thresholds());
        assertEquals(List.of("Malediction"), damnation.abilities());

        // neighbouring underlined parts join; each name once
        assertEquals(List.of("Totem"), named(list, MONOLITH).abilities());

        AspectEntry mythic = named(list, EMBODIMENT);
        assertEquals(Tier.MYTHIC, mythic.rarity());
        assertEquals(3, mythic.tiers());
        assertEquals(List.of(1, 5, 15), mythic.thresholds(), "tiers in number order, not the file's");
        assertEquals(List.of("Eye of the Storm", "Ritual Circle"), mythic.abilities());
    }

    @Test
    void keepsOnlyTheClassAsked() {
        var cache = AspectBook.parseCache(CACHE);
        assertEquals(Set.of(Klass.SHAMAN, Klass.WARRIOR), cache.keySet());
        List<AspectEntry> shaman = AspectBook.withAbilities(List.of(), Klass.SHAMAN, cache);
        assertEquals(3, shaman.size());
        assertTrue(shaman.stream().allMatch(a -> a.klass() == Klass.SHAMAN));
        assertTrue(shaman.stream().noneMatch(a -> a.name().equals("Aspect of the Warlord")));
        assertTrue(AspectBook.withAbilities(List.of(), Klass.MAGE, cache).isEmpty());
        assertTrue(AspectBook.withAbilities(List.of(), Klass.SHAMAN, null).isEmpty());
    }

    @Test
    void fillsAbilityNamesIntoTheModelsList() {
        // as Wyn.classAspects gives it: no ability names
        List<AspectEntry> model = List.of(new AspectEntry(DAMNATION, Klass.SHAMAN, Tier.LEGENDARY, 4, List.of(1, 5, 30, 150), List.of()),
                new AspectEntry("Aspect of Something New", Klass.SHAMAN, Tier.FABLED, 3, List.of(), List.of()));
        List<AspectEntry> out = AspectBook.withAbilities(model, Klass.SHAMAN, AspectBook.parseCache(CACHE));
        assertEquals(List.of("Malediction"), out.get(0).abilities());
        assertEquals(List.of(), out.get(1).abilities());
        assertEquals(2, out.size(), "the model's list stands, the cache only adds names");
    }

    @Test
    void ownedTiersUnknownUntilSeen() {
        List<AspectEntry> list = shaman();
        assertNull(AspectBook.owned(list, n -> 0, false), "nothing read, menu not seen");
        assertNull(AspectBook.owned(list, n -> -1, true), "every read failed");
        Map<String, Integer> seen = AspectBook.owned(list, n -> 0, true);
        assertNotNull(seen);
        assertEquals(0, seen.get(DAMNATION));
        Map<String, Integer> read = AspectBook.owned(list, n -> n.equals(DAMNATION) ? 2 : 0, false);
        assertNotNull(read, "any owned tier makes them known");
        assertEquals(2, read.get(DAMNATION));
        assertEquals(0, read.get(MONOLITH));
    }

    @Test
    void unknownBookMarksNothingMissing() {
        AspectBook.install(Klass.SHAMAN, shaman(), null, List.of(), null, Set.of(), Set.of(), "Ritualist");
        assertTrue(AspectBook.dataReady());
        assertFalse(AspectBook.known());
        assertEquals(-1, AspectBook.ownedTier(DAMNATION));
        assertEquals(-1, AspectBook.ownedCount());
        assertEquals(3, AspectBook.ranked().size());
        for (RankedAspect r : AspectBook.ranked()) {
            assertEquals(0, r.rank());
            assertFalse(r.unfinished());
        }
        assertEquals(AspectBook.ranked().get(0), AspectBook.next());
    }

    @Test
    void knownBookCountsOwnedAndEquipped() {
        AspectBook.install(Klass.SHAMAN, shaman(), Map.of(DAMNATION, 2, MONOLITH, 0, EMBODIMENT, 3),
                List.of("char0001 " + MONOLITH), null, Set.of(), Set.of(), "Ritualist");
        assertEquals(Klass.SHAMAN, AspectBook.klass());
        assertTrue(AspectBook.known());
        assertEquals(2, AspectBook.ownedTier(DAMNATION));
        assertEquals(0, AspectBook.ownedTier(MONOLITH));
        assertEquals(2, AspectBook.ownedCount());
        assertTrue(AspectBook.equipped(MONOLITH));
        assertFalse(AspectBook.equipped(DAMNATION));
        assertTrue(AspectBook.byName(MONOLITH).equipped());
        assertTrue(AspectBook.byName(EMBODIMENT).maxed());
        assertNull(AspectBook.byName("Aspect of Nothing"));
        assertNotNull(AspectBook.next());
        assertTrue(AspectBook.next().unfinished());
    }

    @Test
    void previewSetsTheBook() {
        AspectBook.preview(Klass.SHAMAN, shaman(), null, List.of(), "Ritualist");
        assertTrue(AspectBook.dataReady());
        assertFalse(AspectBook.known());
        assertEquals("Ritualist", AspectBook.archetype());
        AspectBook.reset();
        assertFalse(AspectBook.dataReady());
        assertEquals(Klass.NONE, AspectBook.klass());
        assertNull(AspectBook.next());
    }
}
