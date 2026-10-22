package io.github.anilken.wynnlabs.build.roadmap;

import com.google.gson.JsonObject;

import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.build.Setup;
import io.github.anilken.wynnlabs.build.Tuning;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A Lv 72 Shaman on the Damage goal who leans to XP is not told to wear two Precious rings. */
class FightingGoalXpRoadmapTest {

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    @Test
    void noTwoPreciousRingsOnTheDamageGoal() throws Exception {
        Path dir = Fixtures.root().resolve("shaman-44");
        assumeTrue(Files.exists(dir.resolve("state.json")), "no shaman-44 fixture");
        // the fixture's rules are from before the goal XP factor: the upgrade on load brings it
        FixtureState.load(dir).atLevel(72).install("en");
        assumeTrue(Setup.goal().equals("DAMAGE"), "the fixture is on the Damage goal");
        JsonObject model = new JsonObject();
        model.addProperty("xpWeight", 1.5);
        JsonObject change = new JsonObject();
        change.add("model", model);
        Rules.update(change);
        Roadmap.Computed c = Roadmap.compute(new Roadmap.Inputs(List.of(), Tuning.Choice.PLAIN, true, 0), null);
        assertNotNull(c);
        assumeTrue(!c.steps().isEmpty(), "no road map");
        Build.Eval e = c.steps().get(0).set();
        assertNotNull(e, "a set at Lv 72");
        Map<String, Damage.Piece> b = c.steps().get(0).build();
        String r1 = b.get("RING1") == null ? "-" : b.get("RING1").name();
        String r2 = b.get("RING2") == null ? "-" : b.get("RING2").name();
        Fixtures.note(String.format(java.util.Locale.ROOT, "Lv 72 xpWeight 1.5: rings %s + %s, xp %.0f, judged %.0f",
                r1, r2, e.result().xp(), Roadmap.judged(e, null, 72)));
        assertFalse(r1.equals("Precious") && r2.equals("Precious"), "two Precious rings: " + r1 + " + " + r2);
    }

    /**
     * The same lean on the whole map: the fixture carries one Precious, so its XP pull shows at
     * Lv 80, where two Ring of Wisdom (XP bonus, little damage) took both ring slots.
     */
    @Test
    void noTwoXpRingsOnTheDamageMap() throws Exception {
        Path dir = Fixtures.root().resolve("shaman-44");
        assumeTrue(Files.exists(dir.resolve("state.json")), "no shaman-44 fixture");
        FixtureState.load(dir).install("en");
        assumeTrue(Setup.goal().equals("DAMAGE"), "the fixture is on the Damage goal");
        JsonObject model = new JsonObject();
        model.addProperty("xpWeight", 1.5);
        JsonObject change = new JsonObject();
        change.add("model", model);
        Rules.update(change);
        List<Roadmap.Step> steps = Roadmap.build();
        assumeTrue(!steps.isEmpty(), "no road map");
        for (Roadmap.Step s : steps) {
            Map<String, Damage.Piece> b = s.build();
            String r1 = b.get("RING1") == null ? "-" : b.get("RING1").name();
            String r2 = b.get("RING2") == null ? "-" : b.get("RING2").name();
            Fixtures.note(String.format(java.util.Locale.ROOT, "Lv %d xpWeight 1.5: rings %s + %s, xp %.0f",
                    s.level(), r1, r2, s.set() == null ? 0 : s.set().result().xp()));
            for (String xpRing : new String[] {"Precious", "Ring of Wisdom"}) {
                assertFalse(r1.equals(xpRing) && r2.equals(xpRing), "Lv " + s.level() + ": two " + xpRing);
            }
        }
    }
}
