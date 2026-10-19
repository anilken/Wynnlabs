package io.github.anilken.wynnlabs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The applied plan and the saved builds: save, rename, delete and undo, the file read back, no
 * limit on how many, an overwrite that keeps the name; what you already wear of a plan; the
 * better-build hint's margin and once-a-level; the level row's Plan pill.
 */
class PlansTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() throws Exception {
        FixtureState.load(RoadmapLocksTest.shaman()).install("en");
        Locks.rootOverride = tmp;
        Plans.forget();
    }

    @AfterEach
    void reset() {
        Saver.flush();
        Locks.rootOverride = null;
        Plans.forget();
        FixtureState.uninstall();
    }

    private static Plans.Build build(String name, List<String> locks, String helmet) {
        Map<String, String> items = new LinkedHashMap<>();
        items.put("HELMET", helmet);
        items.put("WEAPON", "Covetous Thunder");
        items.put("RING1", "Morph-Emerald");
        return new Plans.Build("", name, locks, new Tuning.Choice(Tuning.DAMAGE, true, false, 640), items, 1234.5, 44, 0, 0);
    }

    @Test
    void saveRenameDeleteAndUndo() {
        Plans.Build a = Plans.save(build("", List.of("Covetous Thunder"), "Yume"), 1000);
        assertEquals("Covetous Thunder build", a.name(), "named after its chosen item");
        Plans.Build b = Plans.save(build("", List.of(), "Yume"), 1001);
        assertTrue(b.name().startsWith("Build "), "else Build N: " + b.name());
        assertTrue(Plans.rename(a.id(), "  My thunder  ", 1002));
        assertEquals("My thunder", Plans.byId(a.id()).name());
        assertFalse(Plans.rename(a.id(), "   ", 1003), "an empty name keeps the old one");
        assertEquals("My thunder", Plans.byId(a.id()).name());
        Plans.rename(a.id(), "x".repeat(80), 1004);
        assertEquals(Plans.MAX_NAME, Plans.byId(a.id()).name().length(), "names are cut at 40");
        assertTrue(Plans.delete(a.id(), 2000));
        assertEquals(List.of(b.id()), Plans.saved().stream().map(Plans.Build::id).toList());
        assertNotNull(Plans.undoable(2000 + Plans.UNDO_MS - 1));
        assertTrue(Plans.undo(2001));
        assertEquals(List.of(a.id(), b.id()), Plans.saved().stream().map(Plans.Build::id).toList(), "back where it stood");
        Plans.delete(b.id(), 3000);
        assertFalse(Plans.undo(3000 + Plans.UNDO_MS + 1), "too late to undo");
    }

    @Test
    void theFileReadsBackAndABrokenOneIsNone() throws Exception {
        Plans.Build a = Plans.save(build("Thunder", List.of("Covetous Thunder"), "Yume"), 1000);
        Plans.apply(a);
        Saver.flush();
        Path f = tmp.resolve(GameState.current().characterId()).resolve("saved-builds.json");
        assertTrue(Files.exists(f));
        Plans.forget();
        assertEquals(List.of(a), Plans.saved(), "the same build after a restart");
        assertEquals(a, Plans.current(), "and the same plan");
        Files.writeString(f, "{\"builds\": [", java.nio.charset.StandardCharsets.UTF_8);
        Plans.forget();
        assertTrue(Plans.saved().isEmpty(), "a broken file reads as none");
        assertNull(Plans.current());
        assertTrue(Files.exists(f.resolveSibling("saved-builds.json.broken")), "and is kept aside");
    }

    @Test
    void noLimit() {
        for (int i = 0; i < 50; i++) {
            Plans.save(build("B" + i, List.of(), "Yume"), 1000 + i);
        }
        Saver.flush();
        Plans.forget();
        assertEquals(50, Plans.saved().size(), "all fifty kept");
        assertEquals("B49", Plans.saved().get(49).name());
    }

    @Test
    void anOverwriteKeepsTheName() {
        Plans.Build a = Plans.save(build("Keep me", List.of("Covetous Thunder"), "Yume"), 1000);
        Plans.apply(a);
        Plans.Build fresh = build("other", List.of("Morph-Emerald"), "Venison");
        Plans.Build r = Plans.overwrite(a.id(), fresh, 5000);
        assertEquals("Keep me", r.name());
        assertEquals(a.id(), r.id());
        assertEquals(1000, r.created());
        assertEquals(5000, r.updated());
        assertEquals("Venison", r.items().get("HELMET"));
        assertEquals(List.of("Morph-Emerald"), r.locks());
        assertEquals(r, Plans.current(), "the plan follows its saved build");
    }

    @Test
    void progressCountsTheWornPieces() {
        Map<String, String> plan = new LinkedHashMap<>();
        plan.put("HELMET", "Yume");
        plan.put("WEAPON", "Covetous Thunder");
        plan.put("RING1", "Morph-Emerald");
        plan.put("RING2", "Precious");
        plan.put("BOOTS", "Prologue");
        Map<String, String> worn = new LinkedHashMap<>();
        worn.put("HELMET", "Yume");
        worn.put("WEAPON", "Heavensent");
        worn.put("RING1", "Precious");             // the rings in either hand
        worn.put("RING2", "Morph-Emerald");
        assertEquals(3, Plans.wornCount(plan, worn));
        boolean[] at = Plans.wornSlots(plan, worn);
        assertTrue(at[0] && !at[4] && at[5] && at[6] && !at[3]);
        worn.put("BOOTS", "Prologue");
        worn.put("WEAPON", "Covetous Thunder");
        assertEquals(5, Plans.wornCount(plan, worn), "all five planned pieces");
        worn.put("RING2", "Precious");
        assertEquals(4, Plans.wornCount(plan, worn), "two of one ring are not the other");
    }

    @Test
    void theHintNeedsTheMarginAndComesOnceALevel() {
        Plans.Hint h = new Plans.Hint();
        assertFalse(h.shouldCheck(44, 44), "not before a level-up");
        assertTrue(h.shouldCheck(45, 44), "once after it");
        assertFalse(h.shouldCheck(45, 44), "only once at that level");
        assertFalse(h.offer(45, 1000, 1049), "4.9 % is not enough");
        assertEquals(-1, h.showing());
        assertTrue(h.shouldCheck(46, 44));
        assertTrue(h.offer(46, 1000, 1050), "5 % is");
        assertEquals(46, h.showing());
        h.dismiss();
        assertEquals(-1, h.showing(), "hidden until the next level");
        assertTrue(h.shouldCheck(47, 44));
        assertTrue(h.offer(47, 1000, 1200));
        assertEquals(47, h.showing(), "a new level shows it again");
    }

    @Test
    void removingAPlanAndUndo() {
        Plans.Build a = build("Mine", List.of("Covetous Thunder"), "Yume");
        Plans.apply(a);
        Plans.Build kept = Plans.save(build("Kept", List.of(), "Venison"), 900);
        assertTrue(Plans.remove(1000));
        assertNull(Plans.current(), "the panel back to the suggestions");
        assertEquals(List.of(kept), Plans.saved(), "saved builds untouched");
        assertEquals(a, Plans.removedUndoable(1000 + Plans.UNDO_MS - 1));
        assertTrue(Plans.undoRemove(1001));
        assertEquals(a, Plans.current(), "Undo brings it back exactly");
        assertNull(Plans.removedUndoable(1002), "nothing left to undo");
        assertTrue(Plans.remove(2000));
        assertFalse(Plans.undoRemove(2000 + Plans.UNDO_MS + 1), "too late to undo");
        assertFalse(Plans.remove(3000), "no plan: nothing to remove");
    }

    @Test
    void saveAddsThePlanOrUpdatesItsCopy() {
        Plans.Build a = build("Thunder plan", List.of("Covetous Thunder"), "Yume");
        Plans.apply(a);
        assertFalse(Plans.isSaved(Plans.current()));
        Plans.Build kept = Plans.savePlan(1000);
        assertEquals(1, Plans.saved().size(), "added to My builds");
        assertEquals("Thunder plan", kept.name());
        assertTrue(Plans.isSaved(Plans.current()), "the plan is linked to its copy");
        assertEquals(kept.id(), Plans.current().id());
        // changed and saved again: the same copy updated, its name kept
        Plans.Build changed = new Plans.Build(kept.id(), "Thunder plan", List.of("Covetous Thunder"), Tuning.Choice.PLAIN,
                java.util.Map.of("HELMET", "Venison"), 999, 44, kept.created(), 1500);
        Plans.apply(changed);
        Plans.Build again = Plans.savePlan(2000);
        assertEquals(1, Plans.saved().size(), "no second copy");
        assertEquals(kept.id(), again.id());
        assertEquals("Venison", Plans.saved().get(0).items().get("HELMET"));
        assertEquals("Thunder plan", Plans.saved().get(0).name());

    }

    @Test
    void editPreloadsTheWindow() {
        Locks.use(List.of());
        Locks.useTuning(Tuning.Choice.PLAIN);
        try {
            Plans.Build a = build("Thunder plan", List.of("Covetous Thunder", "Morph-Emerald"), "Yume");
            Plans.apply(a);
            BuildWindow.INSTANCE.editPlan(Plans.current());
            assertTrue(BuildWindow.INSTANCE.isOpen());
            assertEquals(BuildWindow.View.PICK, BuildWindow.INSTANCE.view(), "the picker");
            assertEquals(List.of("Covetous Thunder", "Morph-Emerald"), BuildWindow.INSTANCE.chosenNames(), "the plan's chosen items");
            assertEquals(a.tuning(), BuildWindow.INSTANCE.tuning(), "and its strip");
            assertEquals(a.locks(), Locks.names(), "kept as the window's choice");
            BuildWindow.INSTANCE.close();
        } finally {
            Locks.use(null);
            Locks.useTuning(null);
        }
    }

    @Test
    void thePlanPill() {
        Plans.Pills p = new Plans.Pills();
        assertFalse(p.shown(null), "no plan: the pills as before");
        assertFalse(p.planSelected(null));
        Plans.Build a = Plans.save(build("A", List.of(), "Yume"), 1000);
        assertTrue(p.shown(a));
        assertTrue(p.planSelected(a), "selected by default");
        p.pickLevel();
        assertFalse(p.planSelected(a), "another pill picked");
        p.pickPlan();
        assertTrue(p.planSelected(a));
        p.pickLevel();
        Plans.Build b = Plans.save(build("B", List.of(), "Venison"), 1001);
        assertTrue(p.planSelected(b), "a new plan is selected again");
    }
}
