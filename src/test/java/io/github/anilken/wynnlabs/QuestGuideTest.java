package io.github.anilken.wynnlabs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The quest guide: shipped stages offline or when the page is unchanged, live English when it changed. */
class QuestGuideTest {

    @AfterEach
    void reset() {
        FixtureState.uninstall();
        QuestGuides.reset();
    }

    private static String page() throws Exception {
        return Files.readString(Path.of(QuestGuideTest.class.getResource("/quests/Jungle Fever.txt").toURI()),
                StandardCharsets.UTF_8);
    }

    private static void english() throws Exception {
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("en");
        QuestGuides.reset();
    }

    @Test
    void shippedStagesWithoutTheLivePage() throws Exception {
        english();
        QuestWiki.Info info = QuestWiki.get("Jungle Fever");
        assertNotNull(info, "a shipped guide shows at once, before (or without) the wiki");
        assertEquals(6, info.stages().size());
        assertArrayEquals(new int[] {998, 21, -2030}, info.stages().get(4).points().get(1));
        assertFalse(QuestWiki.newer(info));
    }

    @Test
    void unchangedLivePageKeepsTheShippedText() throws Exception {
        english();
        QuestWiki.putLive("Jungle Fever", QuestWiki.parse(page()));
        QuestWiki.Info info = QuestWiki.get("Jungle Fever");
        assertFalse(QuestWiki.newer(info));
        assertEquals(6, info.stages().size());
    }

    @Test
    void changedLivePageShowsLiveEnglish() throws Exception {
        english();
        QuestWiki.Info live = QuestWiki.parse(page().replace("Return to Worid", "Return to Worid in the jungle"));
        QuestWiki.putLive("Jungle Fever", live);
        QuestWiki.Info info = QuestWiki.get("Jungle Fever");
        assertFalse(QuestWiki.newer(info), "English: the wiki as it is now, no note");
        assertTrue(info.stages().get(3).task().contains("in the jungle"));
    }

    @Test
    void changedLivePageKeepsTheTurkishGuide() throws Exception {
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("tr");
        QuestGuides.reset();
        QuestWiki.putLive("Jungle Fever", QuestWiki.parse(page().replace("Return to Worid", "Return to Worid in the jungle")));
        QuestWiki.Info info = QuestWiki.get("Jungle Fever");
        assertTrue(QuestWiki.newer(info), "the note: some steps may be out of date");
        assertEquals(QuestGuides.find("Jungle Fever").translated().get(3).task(), info.stages().get(3).task());
    }

    @Test
    void anEmptyLivePageKeepsTheShippedGuide() throws Exception {
        english();
        QuestWiki.putLive("Jungle Fever", QuestWiki.parse("{{Infobox/Quest}}\nno stages here"));
        QuestWiki.Info info = QuestWiki.get("Jungle Fever");
        assertEquals(6, info.stages().size());
    }

    @Test
    void trackedStepFromTheMatchedStage() throws Exception {
        english();
        QuestWiki.putLive("Jungle Fever", QuestWiki.parse(page()));
        QuestWiki.Info info = QuestWiki.get("Jungle Fever");
        String game = "Infiltrate Almuj's Secret Emerald Labyrinth at [1019, 92, -1977] underground and find the Banker";
        assertEquals(4, QuestWiki.currentStage(info, game));
        assertEquals(info.stages().get(4).task(), QuestWiki.stepText(info, game));
        assertEquals("Do something nobody wrote down", QuestWiki.stepText(info, "Do something nobody wrote down"),
                "no stage matches: the game's own line");
    }

    @Test
    void titleInThePlayersLanguage() throws Exception {
        english();
        assertEquals("Jungle Fever", QuestGuides.title("Jungle Fever"));
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("tr");
        QuestGuides.reset();
        assertEquals("Orman Humması", QuestGuides.title("Jungle Fever"));
        assertEquals("Some Unknown Quest", QuestGuides.title("Some Unknown Quest"), "no guide: the English name");
    }

    @Test
    void placesTakeTheHintsCoordinatesToo() throws Exception {
        english();
        QuestWiki.Info info = QuestWiki.get("A Headless History");
        java.util.List<QuestWiki.Place> places = QuestWiki.places(info, 0);
        QuestWiki.Place door = places.stream().filter(p -> p.at()[0] == -1079 && p.at()[2] == -5600).findFirst().orElseThrow();
        assertEquals(6, door.stage());
        assertEquals("Merdivenden çık ve büyük kapıya gir.", QuestWiki.placeText("Merdivenden çık ve [-1079, 85, -5600] noktasındaki büyük kapıya gir."));
        assertFalse(door.what().contains("-5600"), "a label without its coordinates: " + door.what());
        for (int i = 0; i < places.size(); i++) {
            for (int j = i + 1; j < places.size(); j++) {
                assertFalse(java.util.Arrays.equals(places.get(i).at(), places.get(j).at()), "each place once");
            }
        }
        assertTrue(places.size() > info.stages().stream().mapToInt(s -> s.points().size()).sum() - 1);
        assertTrue(QuestWiki.places(info, 7).stream().allMatch(p -> p.stage() >= 7), "from the stage you are on");
    }

    @Test
    void placesLearnedWhilePlaying() throws Exception {
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("tr");
        QuestGuides.reset();
        QuestTrail.use(java.util.List.of(
                new QuestTrail.Spot("Cowfusion", "Talk to Ranol at [768, 45, -5425]", 768, 45, -5425, false),
                new QuestTrail.Spot("Cowfusion", "Find a way into the Laboratory", 790, 41, -5590, true),
                new QuestTrail.Spot("Cowfusion", "Search the Laboratory for the missing cows", 812, 30, -5601, true),
                new QuestTrail.Spot("Jungle Fever", "Return to Worid", 0, 70, 900, true)));
        QuestWiki.Info info = QuestWiki.get("Cowfusion");
        java.util.List<QuestWiki.Place> all = QuestTrail.places("Cowfusion", info, 0);
        assertEquals(4, all.size(), "two from the wiki, two learned; the wiki's own once, the other quest's not");
        QuestWiki.Place door = all.get(2);
        assertEquals(2, door.stage());
        assertArrayEquals(new int[] {790, 41, -5590}, door.at());
        assertEquals(info.stages().get(2).task(), door.what(), "the stage's task in Turkish");
        assertEquals(1, QuestTrail.places("Cowfusion", info, 3).size(), "from the stage you are on");
        QuestTrail.reset();
    }

    @Test
    void stageTooltipStaysShort() throws Exception {
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("tr");
        QuestGuides.reset();
        QuestWiki.Info info = QuestWiki.get("The Order of the Grook");
        QuestWiki.Stage st = info.stages().get(12);
        QuestWiki.Tip t = QuestWiki.tip(st, QuestWiki.englishStages(info).get(12), 46, 12);
        assertTrue(t.task().size() + t.hints().size() <= 12, "12 lines at most");
        java.util.List<String> all = new java.util.ArrayList<>(t.task());
        all.addAll(t.hints());
        assertTrue(all.stream().noneMatch(l -> l.contains("Spoiler alert") || l.contains("}}")), "no wiki leftovers: " + all);
        assertTrue(all.stream().noneMatch(l -> l.startsWith("Öğretmen:")), "no dialogue: " + all);
        assertEquals("Öldür. Sonra sunağa tıkla.",
                QuestWiki.shown("Öldür. Sonra sunağa tıkla.|Magic of Nature Puzzle|60|Spoiler alert: The hidden text contains the solution of the}}"));
        assertEquals("Kafesi aç. Öldür.",
                QuestWiki.shown("Kafesi aç. |Spoiler alert: The hidden text contains the solution of the Corruption Puzzle}} Öldür."));
    }

    @Test
    void partsUnderAStageAreItsLines() throws Exception {
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("tr");
        QuestGuides.reset();
        java.util.List<String> hints = QuestWiki.get("A Fighting Species").stages().get(7).hints();
        int head = hints.indexOf("▸ İpuçları");
        assertTrue(head >= 0, "the Tips part, its heading in Turkish: " + hints);
        assertTrue(hints.get(head + 1).contains("Kapı Bulmacası"), "its lines after it");
        assertTrue(hints.stream().noneMatch(h -> h.contains(".png")), "no picture lines");
        assertEquals("Önce düğmeye bas.", QuestWiki.shown("Önce düğmeye bas.|How to open the door}}"), "a spoiler's label goes");
    }

    @Test
    void puzzlePartsOfAStageArePlacesToo() throws Exception {
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("tr");
        QuestGuides.reset();
        java.util.List<QuestWiki.Place> places = QuestWiki.places(QuestWiki.get("Recipe For Disaster"), 0);
        assertTrue(places.size() >= 20, "the ingredient parts under the stages: " + places.size());
        assertTrue(places.stream().anyMatch(p -> p.what().equals("Moonsalt")), "a map template's name stays");
        assertTrue(places.stream().noneMatch(p -> p.what().matches(".*-?\\d{3,5}, -?\\d{1,3}, -?\\d{3,5}.*")),
                "labels without coordinates");
    }

    @Test
    void turkishStagesMatchedOnTheEnglishLine() throws Exception {
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("tr");
        QuestGuides.reset();
        QuestWiki.putLive("Jungle Fever", QuestWiki.parse(page()));
        QuestWiki.Info info = QuestWiki.get("Jungle Fever");
        assertFalse(QuestWiki.newer(info));
        assertEquals(6, info.stages().size());
        assertArrayEquals(new int[] {998, 21, -2030}, info.stages().get(4).points().get(1), "places stay the English ones");
        String game = "Infiltrate Almuj's Secret Emerald Labyrinth at [1019, 92, -1977] underground and find the Banker";
        assertEquals(4, QuestWiki.currentStage(info, game), "the game's English line matches a Turkish guide");
        String step = QuestWiki.stepText(info, game);
        assertEquals(info.stages().get(4).task(), step);
        assertFalse(step.startsWith("Infiltrate"), "the Turkish task, not the game's English line: " + step);
    }
}
