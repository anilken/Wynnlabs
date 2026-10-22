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
 * health floor's old cube penalty every free point went to Defence (63 at Lv 60, 100 from Lv 80);
 * the bounded penalty leaves room for damage.
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
        for (Roadmap.Step s : steps) {
            if (s.set() == null || s.level() < 60) {
                continue;
            }
            int defence = s.set().allocation()[SkillKind.DEFENCE.ordinal()];
            Fixtures.note("Lv " + s.level() + ": Defence " + defence);
            // Lv 60 sits at about 65 % of its floor, where the bounded penalty is steepest: there
            // Defence still wins point by point (81 today), but no milestone is maxed out and from
            // Lv 70 on, where the old cube kept 83-100, damage gets the points
            int most = s.level() < 70 ? 85 : 70;
            if (defence > most) {
                bad.add("Lv " + s.level() + ": " + defence + " points in Defence");
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }
}
