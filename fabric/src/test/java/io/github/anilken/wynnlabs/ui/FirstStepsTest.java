package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.quests.QuestAdvisor;
import io.github.anilken.wynnlabs.quests.QuestBook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The home's first steps (kept once seen) and the "Next" line's choice (the first that applies). */
class FirstStepsTest {

    @Test
    void aStepSeenOnceStaysDone() {
        Set<String> saved = Set.of("book");
        assertSame(saved, FirstSteps.merge(saved, new boolean[] {false, true, false, false}), "nothing new: nothing to write");
        assertSame(saved, FirstSteps.merge(saved, new boolean[] {false, false, false, false}), "a menu forgotten stays ticked");
        assertEquals(Set.of("book", "bank", "character"), FirstSteps.merge(saved, new boolean[] {true, false, false, true}));
    }

    @Test
    void theFileKeepsTheStepsAndIgnoresTheRest(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("characters").resolve("abc").resolve("first-steps.txt");
        assertEquals(Set.of(), FirstSteps.read(f), "no file: none done");
        FirstSteps.write(f, Set.of("tree", "bank"));
        assertEquals(Set.of("tree", "bank"), FirstSteps.read(f));
        Files.writeString(f, "tree\nsomething\n bank \n");
        assertEquals(Set.of("tree", "bank"), FirstSteps.read(f));
    }

    @Test
    void theOpenStepsComeFirst() {
        assertArrayEquals(new int[] {1, 3, 0, 2}, HomeSteps.order(new boolean[] {true, false, true, false}));
        assertArrayEquals(new int[] {0, 1, 2, 3}, HomeSteps.order(new boolean[] {false, false, false, false}));
        assertEquals(2, FirstSteps.count(new boolean[] {true, false, true, false}));
    }

    @Test
    void theNextLineTakesTheFirstThatApplies() {
        assertNull(HomeNext.choose(null, null, null, null), "nothing applies: no line");
        QuestBook.Entry q = new QuestBook.Entry("Jungle Fever", "STARTED", 48, "MEDIUM", 0, true, null, 1019, 84, -1976, null, 0,
                "QUEST", "MEDIUM");
        HomeNext.Pick quest = HomeNext.choose(null, null, new QuestAdvisor.Suggestion(q, 1, 320, List.of()), "Nivla Woods (Lv. 1-5)");
        assertSame(q, quest.quest(), "a quest goes before a place to level");
        assertEquals("/compass at 1019 84 -1976", quest.compass());
        HomeNext.Pick place = HomeNext.choose(null, null, null, "Nivla Woods (Lv. 1-5)");
        assertNull(place.quest());
        assertEquals("sword", place.icon());
    }

    @Test
    void theSafestPlaceIsTheFirstOfTheSafest() {
        assertNull(HomeNext.safest(List.of()));
        assertEquals("a", HomeNext.safest(List.of("a", "b")));
    }
}
