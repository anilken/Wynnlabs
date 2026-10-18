package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.model.Slot;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Skill point allocation by Wynncraft's rules: equip order, item bonuses, the 100 cap, the earned total. */
class AllocatorTest {

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    private static Damage.Piece piece(String name, Slot type, Map<SkillKind, Integer> reqs, Map<SkillKind, Integer> gives) {
        Map<String, Double> stats = new HashMap<>();
        gives.forEach((k, v) -> stats.put("raw" + k.name().charAt(0) + k.name().substring(1).toLowerCase(java.util.Locale.ROOT), (double) v));
        return new Damage.Piece(ItemStack.EMPTY, name, name, type, 1, stats, reqs, 0, Map.of(), 0);
    }

    private static int[] min(Damage.Piece... pieces) {
        return Build.minPoints(java.util.List.of(pieces));
    }

    @Test
    void anItemWithoutRequirementsAlwaysFits() throws Exception {
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("en");
        Map<String, Damage.Piece> b = new LinkedHashMap<>();
        b.put("RING1", piece("plain", Slot.RING, Map.of(), Map.of()));
        assertNotNull(Build.allocate(b, 49, 96));
    }

    @Test
    void anotherItemsBonusCounts() {
        int[] a = min(piece("needs", Slot.HELMET, Map.of(SkillKind.INTELLIGENCE, 60), Map.of()),
                piece("gives", Slot.RING, Map.of(), Map.of(SkillKind.INTELLIGENCE, 10)));
        assertEquals(50, a[SkillKind.INTELLIGENCE.ordinal()]);
    }

    @Test
    void theCheapestEquipOrderWins() {
        // A: needs 30 Str, gives 20 Dex; B: needs 40 Dex, gives 10 Str.
        // A first: 30 Str + 20 Dex = 50 points; B first: 40 Dex + 20 Str = 60.
        int[] a = min(piece("A", Slot.HELMET, Map.of(SkillKind.STRENGTH, 30), Map.of(SkillKind.DEXTERITY, 20)),
                piece("B", Slot.BOOTS, Map.of(SkillKind.DEXTERITY, 40), Map.of(SkillKind.STRENGTH, 10)));
        assertEquals(50, a[SkillKind.STRENGTH.ordinal()] + a[SkillKind.DEXTERITY.ordinal()],
                "Str " + a[SkillKind.STRENGTH.ordinal()] + " Dex " + a[SkillKind.DEXTERITY.ordinal()]);
    }

    @Test
    void moreThanEarnedIsImpossible() throws Exception {
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("en");
        Map<String, Damage.Piece> b = new LinkedHashMap<>();
        b.put("HELMET", piece("big", Slot.HELMET, Map.of(SkillKind.INTELLIGENCE, 90, SkillKind.AGILITY, 90), Map.of()));
        assertNull(Build.allocate(b, 49, 96));
    }

    @Test
    void noSkillPastAHundredAssigned() throws Exception {
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("en");
        Map<String, Damage.Piece> b = new LinkedHashMap<>();
        b.put("HELMET", piece("over", Slot.HELMET, Map.of(SkillKind.INTELLIGENCE, 110), Map.of()));
        assertNull(Build.allocate(b, 120, 200));
        b.put("RING1", piece("help", Slot.RING, Map.of(), Map.of(SkillKind.INTELLIGENCE, 10)));
        Build.Eval e = Build.allocate(b, 120, 200);
        assertNotNull(e);
        assertEquals(100, e.allocation()[SkillKind.INTELLIGENCE.ordinal()] >= 100 ? 100 : e.allocation()[SkillKind.INTELLIGENCE.ordinal()]);
    }

    @Test
    void hypoxiaFitsTheLevel120Build() throws Exception {
        // live trace: "no allocation" for Hypoxia (Lv 102 ring, no requirements) at Lv 120
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("en");
        String[][] items = {{"HELMET", "Skyfloat"}, {"CHESTPLATE", "Ornamental Plate"}, {"LEGGINGS", "Omniverse"},
            {"BOOTS", "Skidbladnir"}, {"RING1", "Hypoxia"}, {"RING2", "Precious"}, {"BRACELET", "Roots of Unity"},
            {"NECKLACE", "Bookworm"}, {"WEAPON", "Olympic"}};
        Map<String, Damage.Piece> b = new LinkedHashMap<>();
        for (String[] it : items) {
            ItemData info = GameState.current().allGear().filter(i -> i.name().equalsIgnoreCase(it[1])).findFirst().orElseThrow();
            b.put(it[0], Damage.piece(info));
        }
        int[] m = Build.minPoints(b.values());
        Fixtures.note("Lv 120 with Hypoxia needs " + java.util.Arrays.toString(m) + " = " + java.util.Arrays.stream(m).sum());
        assertNotNull(Build.allocate(b, 120, 200), "needs " + java.util.Arrays.toString(m));
    }
}
