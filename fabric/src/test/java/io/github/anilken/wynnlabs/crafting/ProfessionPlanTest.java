package io.github.anilken.wynnlabs.crafting;

import io.github.anilken.wynnlabs.search.Area;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfessionPlanTest {

    private static CraftData.Recipe recipe(String type, int min, int xp, String m1, String m2, int amount) {
        return new CraftData.Recipe(type + min, type, "jeweling", min, min + 3,
                List.of(new CraftData.Material(m1, m1, amount), new CraftData.Material(m2, m2, amount)),
                null, null, null, null, xp);
    }

    /** Two jeweling types over two brackets, the necklace giving about twice the ring's XP. */
    private static final List<CraftData.Recipe> RECIPES = List.of(
            recipe("ring", 10, 35, "Granite Gem", "Trout Oil", 2),
            recipe("ring", 20, 70, "Gold Gem", "Salmon Oil", 2),
            recipe("necklace", 10, 65, "Granite Gem", "Trout Oil", 3),
            recipe("necklace", 20, 132, "Gold Gem", "Salmon Oil", 3));

    @Test
    void targetStaysAboveTheLevelAndUnderTheCap() {
        assertEquals(46, ProfessionPlan.clampTarget(45, 30), "never at or below the level");
        assertEquals(132, ProfessionPlan.clampTarget(45, 500));
        assertEquals(132, ProfessionPlan.clampTarget(132, 140), "at the cap the target is the cap");
        assertEquals(60, ProfessionPlan.clampTarget(45, 60));
    }

    @Test
    void scaleAveragesEachTypesRecipeOfTheLevel() {
        assertEquals(50, ProfessionPlan.scale(RECIPES, 18), 1e-9, "(35 + 65) / 2");
        assertEquals(101, ProfessionPlan.scale(RECIPES, 20), 1e-9, "(70 + 132) / 2");
        assertEquals(0, ProfessionPlan.scale(RECIPES, 5), 1e-9, "no recipe that low");
    }

    @Test
    void laterLevelsGrowLikeTheRecipes() {
        // the rest of this level, then this level's XP scaled by the recipes' growth
        assertArrayEquals(new long[] {800, 1000, 2020}, ProfessionPlan.xpPerLevel(RECIPES, 18, 21, 200, 1000));
        assertEquals(3820, ProfessionPlan.xpToTarget(RECIPES, 18, 21, 200, 1000));
        assertEquals(-1, ProfessionPlan.xpToTarget(RECIPES, 18, 21, 0, 0), "the level's XP not read: unknown");
        assertEquals(-1, ProfessionPlan.xpToTarget(RECIPES, 18, 18, 0, 1000), "no level to climb");
    }

    @Test
    void actionsRoundUp() {
        assertEquals(52, ProfessionPlan.actions(1800, 35));
        assertEquals(10, ProfessionPlan.actions(100, 10));
        assertEquals(-1, ProfessionPlan.actions(100, 0), "no XP per action: unknown");
        assertEquals(-1, ProfessionPlan.actions(-1, 5));
    }

    @Test
    void craftingCountsEachBracketAndItsMaterials() {
        Map<String, Long> prices = Map.of("Granite Gem", 10L, "Trout Oil", 5L, "Gold Gem", 20L);
        List<ProfessionPlan.CraftOption> o = ProfessionPlan.crafting(RECIPES, "JEWELING", 18, 21, 200, 1000, prices::get);
        assertEquals(List.of("necklace", "ring"), o.stream().map(ProfessionPlan.CraftOption::type).toList(), "fewest crafts first");
        ProfessionPlan.CraftOption neck = o.get(0);
        ProfessionPlan.CraftOption ring = o.get(1);
        // necklace: 1800 XP on the 10s recipe (28 crafts), 2020 on the 20s one (16)
        assertEquals(44, neck.crafts());
        assertEquals(Map.of("Granite Gem", 84L, "Trout Oil", 84L, "Gold Gem", 48L, "Salmon Oil", 48L), neck.materials());
        // ring: 1800 / 35 = 52, 2020 / 70 = 29
        assertEquals(81, ring.crafts());
        assertEquals(10, ring.recipe().minLevel(), "the recipe of the level now");
        assertNull(neck.cost(), "Salmon Oil has no price");
        assertEquals(List.of("Salmon Oil"), neck.unpriced());
        assertNull(ProfessionPlan.cheapest(o));

        Map<String, Long> all = Map.of("Granite Gem", 10L, "Trout Oil", 5L, "Gold Gem", 20L, "Salmon Oil", 1L);
        o = ProfessionPlan.crafting(RECIPES, "jeweling", 18, 21, 200, 1000, all::get);
        assertEquals(2268L, o.get(0).cost(), "84×10 + 84×5 + 48×20 + 48×1");
        assertEquals(2778L, o.get(1).cost());
        assertEquals("necklace", ProfessionPlan.cheapest(o).type());
    }

    @Test
    void withoutTheLevelsXpOnlyOneCraftIsShown() {
        List<ProfessionPlan.CraftOption> o = ProfessionPlan.crafting(RECIPES, "jeweling", 22, 30, 0, 0, n -> null);
        assertEquals("necklace", o.get(0).type(), "most XP per craft first");
        assertEquals(-1, o.get(0).crafts());
        assertEquals(Map.of("Gold Gem", 3L, "Salmon Oil", 3L), o.get(0).materials(), "one craft's materials");
        assertTrue(ProfessionPlan.crafting(RECIPES, "cooking", 22, 30, 0, 0, n -> null).isEmpty(), "no recipe of that profession");
    }

    @Test
    void spotsAreTheLevelsResourcesAtTheirNearestNode() {
        List<ProfessionPlan.Resource> res = List.of(
                new ProfessionPlan.Resource("Copper", "mining", 1,
                        List.of(Area.point(1000, 60, 1000), Area.point(30, 60, 40)), List.of("Copper Ingot", "Copper Gem")),
                new ProfessionPlan.Resource("Granite", "mining", 10, List.of(Area.point(-300, 60, 400)), List.of("Granite Gem")),
                new ProfessionPlan.Resource("Gold", "mining", 20, List.of(Area.point(0, 60, 10)), List.of("Gold Ingot")),
                new ProfessionPlan.Resource("Oak", "woodcutting", 1, List.of(Area.point(0, 60, 0)), List.of("Oak Plank")));
        List<ProfessionPlan.Spot> s = ProfessionPlan.spots(res, "MINING", 15, new double[] {0, 0}, 5);
        assertEquals(List.of("Granite", "Copper"), s.stream().map(x -> x.resource().name()).toList(),
                "the level's resources, highest first; Gold (20) and Oak (woodcutting) left out");
        assertEquals(30, s.get(1).node().x(), "Copper's nearest node");
        assertEquals(50, s.get(1).distance(), 1e-9);
        assertEquals(1, ProfessionPlan.spots(res, "mining", 15, new double[] {0, 0}, 1).size(), "the limit");
        List<ProfessionPlan.Spot> nowhere = ProfessionPlan.spots(res, "mining", 1, null, 5);
        assertEquals(1000, nowhere.get(0).node().x(), "no position: the data's first node");
        assertTrue(Double.isNaN(nowhere.get(0).distance()));
    }

    @Test
    void lastGainIsKeptPerProfession() {
        ProfessionXp.clear();
        assertEquals(0, ProfessionXp.last("mining"));
        ProfessionXp.gained("MINING", 52.5);
        ProfessionXp.gained("MINING", 0);
        assertEquals(52.5, ProfessionXp.last("mining"), "a zero gain is ignored");
        ProfessionXp.clear();
    }
}
