package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;
import io.github.anilken.wynnlabs.model.BaseStats;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Requirement;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.model.Slot;
import io.github.anilken.wynnlabs.model.Tier;
import io.github.anilken.wynnlabs.search.BoxMarket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the box contents key acts: only in a container screen, not while typing, over a gear box
 * or a tradable item; never with no screen, in chat, in the mod's own screens, over an empty slot
 * or an untradable item. And the fixture box lists exactly its recorded possibilities.
 */
class BoxKeyTest {

    private static final Predicate<ItemView> ANY = v -> true;
    private static final Predicate<ItemView> NONE = v -> false;

    @AfterEach
    void tearDown() {
        FixtureState.uninstall();
    }

    private static ItemView view(ItemView.Kind kind, String name, ItemData data, ItemView.Rolled rolled, ItemView.Box box) {
        return new ItemView(kind, kind.name(), false, name, null, null, data, rolled, null, box, null, null, null, null, null,
                null, null);
    }

    private static ItemView box() {
        return view(ItemView.Kind.BOX, null, null, null, new ItemView.Box(Slot.RELIK, Tier.LEGENDARY, 70, 80));
    }

    private static ItemData atoll() {
        return new ItemData("Atoll", Slot.LEGGINGS, Tier.UNIQUE, 2, new Requirement(68, Optional.empty(), List.of(), Optional.empty()),
                new BaseStats(0, 900, Optional.empty(), Optional.empty(), List.of(), List.of()), List.of(), Optional.empty(), "NONE");
    }

    private static ItemView rolled(float overall) {
        return view(ItemView.Kind.GEAR, null, atoll(), new ItemView.Rolled(List.of(), List.of(), 0, overall, false,
                new ItemView.Marks(true, true, new EnumMap<>(SkillKind.class), false)), null);
    }

    @Test
    void aBoxInAContainerOpensTheWindow() {
        BoxKey.Pick p = BoxKey.opensFor(BoxKey.Where.CONTAINER, false, Optional.of(box()), NONE);
        assertNotNull(p);
        assertEquals(new ItemView.Box(Slot.RELIK, Tier.LEGENDARY, 70, 80), p.box());
        assertNull(p.item());
    }

    @Test
    void elsewhereTheKeyIsLeftAlone() {
        for (BoxKey.Where w : new BoxKey.Where[] {BoxKey.Where.NONE, BoxKey.Where.CHAT, BoxKey.Where.OWN, BoxKey.Where.OTHER}) {
            assertNull(BoxKey.opensFor(w, false, Optional.of(box()), ANY), w.name());
        }
        assertNull(BoxKey.opensFor(BoxKey.Where.CONTAINER, true, Optional.of(box()), ANY));        // a focused text box
        assertNull(BoxKey.opensFor(BoxKey.Where.CONTAINER, false, Optional.empty(), ANY));         // an empty slot
        ItemView skill = view(ItemView.Kind.SKILL_POINT, null, null, null, null);
        assertNull(BoxKey.opensFor(BoxKey.Where.CONTAINER, false, Optional.of(skill), ANY));       // a menu button
    }

    @Test
    void aTradableItemOpensItsOwnListings() {
        ItemView ingredient = view(ItemView.Kind.INGREDIENT, "Glowing Tree Sap", null, null, null);
        BoxKey.Pick p = BoxKey.opensFor(BoxKey.Where.CONTAINER, false, Optional.of(ingredient), ANY);
        assertNotNull(p);
        assertNull(p.box());
        assertEquals("Glowing Tree Sap", p.item().item().name());
        assertFalse(p.item().unidentified());
        BoxKey.Pick gear = BoxKey.opensFor(BoxKey.Where.CONTAINER, false, Optional.of(rolled(72.4f)), ANY);
        assertEquals("Atoll", gear.item().item().name());
        assertEquals(72.4f, gear.item().ownRoll(), 1e-6);
    }

    @Test
    void anUnidentifiedPieceStartsOnUnidentifiedListings() {
        ItemView unid = view(ItemView.Kind.GEAR, null, atoll(), null, null);
        BoxKey.Pick p = BoxKey.opensFor(BoxKey.Where.CONTAINER, false, Optional.of(unid), ANY);
        assertTrue(p.item().unidentified());
        assertNull(p.item().ownRoll());
    }

    @Test
    void anUntradableItemOpensNothing() {
        assertNull(BoxKey.opensFor(BoxKey.Where.CONTAINER, false, Optional.of(rolled(50f)), NONE));
        ItemView unnamed = view(ItemView.Kind.MATERIAL, null, null, null, null);
        assertNull(BoxKey.opensFor(BoxKey.Where.CONTAINER, false, Optional.of(unnamed), ANY));
    }

    @Test
    void theFixtureBoxListsExactlyItsPossibilities() throws Exception {
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("en");
        List<BoxMarket.Item> items = BoxScreen.items(new ItemView.Box(Slot.LEGGINGS, Tier.UNIQUE, 66, 70));
        assertEquals(List.of("Anger Point", "Atoll", "Canopy", "Flame Beaters", "Ironclad", "Leg of the Scared", "Prayer",
                "Reflection"), items.stream().map(BoxMarket.Item::name).toList());
        assertTrue(items.stream().allMatch(i -> i.identifyCost() == Tier.UNIQUE.identificationCost(i.level())));
    }
}
