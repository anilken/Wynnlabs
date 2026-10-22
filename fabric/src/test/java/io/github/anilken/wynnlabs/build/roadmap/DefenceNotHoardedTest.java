package io.github.anilken.wynnlabs.build.roadmap;

import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;
import io.github.anilken.wynnlabs.model.SkillKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The synthetic character finds no gear past Lv 50, so only its skill points move. Under the
 * health floor's old cube penalty every free point from Lv 60 on went to Defence and Agility
 * (Lv 120: 100 Defence, 62 Agility) and the damage skills never got another one; the bounded
 * penalty lets the floor pull, but damage keeps getting points.
 */
class DefenceNotHoardedTest {

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    @Test
    void theSyntheticCharacterDoesNotHoardDefence() throws Exception {
        Path dir = Fixtures.root().resolve("synthetic");
        assumeTrue(Files.exists(dir.resolve("state.json")), "no synthetic fixture");
        FixtureState.load(dir).install("en");
        List<Roadmap.Step> steps = Roadmap.build();
        assumeTrue(!steps.isEmpty(), "no road map");
        List<String> bad = new ArrayList<>();
        int[] first = null;
        int[] last = null;
        for (Roadmap.Step s : steps) {
            if (s.set() == null || s.level() < 60) {
                continue;
            }
            int[] a = s.set().allocation();
            Fixtures.note("Lv " + s.level() + ": " + java.util.Arrays.toString(a));
            if (first == null) {
                first = a;
            }
            last = a;
        }
        assumeTrue(first != null && first != last, "no milestones from Lv 60 on");
        // Not a cap on Defence: with Lv 50 gear its Lv 60-120 health stays at 25-45 % of the
        // floor, and its weapon (Conclave Crossfire) is more than half fire, so Defence is both
        // its damage skill and its health; it rightly holds 70-100 of it. What the cube did, and
        // the bounded floor must not, is take every new point from damage: Strength, Dexterity
        // and Intelligence stood still from Lv 60 to 120 (38 points) while 124 went to Defence
        // and Agility. Now they grow by more than 60
        int damage = first[SkillKind.STRENGTH.ordinal()] + first[SkillKind.DEXTERITY.ordinal()]
                + first[SkillKind.INTELLIGENCE.ordinal()];
        int damageLast = last[SkillKind.STRENGTH.ordinal()] + last[SkillKind.DEXTERITY.ordinal()]
                + last[SkillKind.INTELLIGENCE.ordinal()];
        if (damageLast < damage + 40) {
            bad.add("Strength, Dexterity and Intelligence " + damage + " at Lv 60, " + damageLast + " at the end");
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }
}
