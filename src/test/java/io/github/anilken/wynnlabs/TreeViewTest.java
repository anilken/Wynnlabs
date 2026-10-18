package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.AbilityNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The states, points and search the Ability Tree tab shows. */
class TreeViewTest {

    private static final List<AbilityNode> TREE = SyntheticTree.nodes();

    private static List<TreePlanner.Step> plan() {
        return List.of(new TreePlanner.Step("Totem", 1, 1, ""), new TreePlanner.Step("Totemic Smash", 1, 2, ""),
                new TreePlanner.Step("Haul", 1, 4, ""), new TreePlanner.Step("Uproot", 1, 6, "Summoner"),
                new TreePlanner.Step("Totemic Shatter", 2, 8, "Summoner"), new TreePlanner.Step("Puppet Master", 2, 10, "Summoner"));
    }

    @Test
    void everyStateOnce() {
        TreeView.View v = TreeView.build(TREE, SyntheticTree.unlocked(), plan(), 8, "");
        assertEquals(TreeView.State.UNLOCKED, v.byName("Totem").state());
        assertEquals(1, v.byName("Totem").step(), "a taken step keeps its number");
        assertEquals(TreeView.State.NEXT, v.byName("Uproot").state(), "the plan's first node still locked");
        assertEquals(4, v.byName("Uproot").step());
        assertEquals(TreeView.State.PLANNED, v.byName("Totemic Shatter").state());
        assertEquals(5, v.byName("Totemic Shatter").step());
        assertEquals(TreeView.State.BLOCKED, v.byName("Blood Pact").state(), "shut by the planned Totemic Shatter");
        assertEquals(TreeView.State.AVAILABLE, v.byName("Aura").state(), "next to the unlocked Totemic Smash");
        assertEquals(TreeView.State.LOCKED, v.byName("Fetish").state());
        assertEquals("Uproot", v.next().name());
        assertEquals(6, v.nextLevel());
        assertEquals(3, v.spent());
        assertEquals(8, v.total());
        assertEquals(3 + 1 + 2 + 2, v.afterPlan());
        assertTrue(v.byName("Awakened").ultimate());
        assertEquals("UNLOCKABLE", v.byName("Uproot").iconState());
        assertEquals("BLOCKED", v.byName("Blood Pact").iconState());
    }

    @Test
    void archetypePointsNowAndAfterThePlan() {
        TreeView.View v = TreeView.build(TREE, List.of("Totem", "Totemic Smash", "Haul", "Uproot"), plan(), 8, "");
        TreeView.Archetype summoner = v.archetypes().get(0);
        assertEquals("Summoner", summoner.name());
        assertEquals(1, summoner.now());
        assertEquals(3, summoner.after(), "Totemic Shatter and Puppet Master are planned");
        assertEquals(0xFF7F77DD, summoner.colour(), "the node data's colour");
        assertEquals(TreeView.PALETTE[1], TreeView.colour(null, 1), "the palette without one");
    }

    @Test
    void searchDimsWhatDoesNotMatch() {
        TreeView.View v = TreeView.build(TREE, SyntheticTree.unlocked(), plan(), 8, "puppet");
        assertTrue(v.byName("Uproot").matches(), "matched in the description");
        assertTrue(v.byName("Puppet Master").matches());
        assertFalse(v.byName("Haul").matches());
        assertNotNull(v.byName("Awakened"));
    }

    @Test
    void aFreshCharacterCanOnlyTakeTheFirstNode() {
        TreeView.View v = TreeView.build(TREE, List.of(), List.of(), 1, "");
        assertEquals(TreeView.State.AVAILABLE, v.byName("Totem").state());
        assertEquals(TreeView.State.LOCKED, v.byName("Totemic Smash").state());
        assertEquals(0, v.spent());
    }
}
