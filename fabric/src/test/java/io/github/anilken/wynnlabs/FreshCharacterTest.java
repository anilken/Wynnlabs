package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.Slot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A character that just started (Lv 1, nothing worn or carried) gets a road map: with no weapon
 * the build scored nothing and every candidate was dropped, so a new player was told nothing.
 */
class FreshCharacterTest {

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    @Test
    void aNewCharacterGetsAWeaponAndASetAtEveryMilestone() throws Exception {
        FixtureState.load(Fixtures.root().resolve("shaman-44")).fresh().install("en");
        List<Roadmap.Step> steps = Roadmap.build();
        assertTrue(!steps.isEmpty(), "no road map");
        Slot weapon = Slot.weaponOf(GameState.current().classType());
        assertTrue(steps.get(0).gear().stream().anyMatch(g -> g.slot().equals(weapon.name())),
                "no weapon at Lv " + steps.get(0).level() + ": " + steps.get(0).gear().stream().map(g -> g.info().name()).toList());
        for (Roadmap.Step s : steps) {
            assertTrue(s.set() != null && s.set().result().objective() > 0, "Lv " + s.level() + ": the set scores nothing");
        }
    }
}
