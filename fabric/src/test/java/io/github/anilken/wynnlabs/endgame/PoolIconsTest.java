package io.github.anilken.wynnlabs.endgame;

import io.github.anilken.wynnlabs.endgame.PoolIcons.Ref;
import io.github.anilken.wynnlabs.game.ModelData;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.PoolItem;
import io.github.anilken.wynnlabs.model.Tier;
import io.github.anilken.wynnlabs.model.WeeklyPool;
import io.github.anilken.wynnlabs.ui.Theme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where a pool item's icon comes from: the model table, the aliases, the base item, the texture and the glyph. */
class PoolIconsTest {

    private static final long OCT18 = WynnventoryPoolsTest.OCT18;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        ModelData.use(ModelData.parse(WynnventoryPoolsTest.wynntilsFixture("model-data.json")));
        WynnventoryPools.reset();
    }

    @AfterEach
    void tearDown() {
        ModelData.reset();
        WynnventoryPools.reset();
    }

    private static WeeklyPool loot() {
        return WynnventoryPools.parse(WynnventoryPoolsTest.fixture("lootpool-current.json"), OCT18);
    }

    private static WeeklyPool raid() {
        return WynnventoryPools.parse(WynnventoryPoolsTest.fixture("raidpool-current.json"), OCT18);
    }

    private static PoolItem find(WeeklyPool p, String region, String name, boolean shiny) {
        return p.regions().stream().filter(r -> r.name().equals(region)).flatMap(r -> r.items().stream())
                .filter(i -> i.name().equals(name) && i.shiny() == shiny).findFirst().orElseThrow();
    }

    @Test
    void theModelTableFixtureParses() {
        assertTrue(ModelData.ready());
        assertEquals(4425f, ModelData.of("wand.multi3"));
        assertEquals(62f, ModelData.of("abilityTree.aspectShaman"));
        assertTrue(Float.isNaN(ModelData.of("not.a.key")));
        assertTrue(Float.isNaN(ModelData.of(null)));
        assertTrue(ModelData.parse("not json").isEmpty());
        assertTrue(ModelData.parse("{\"floats\":{\"a\":\"x\",\"b\":3}}").equals(Map.of("b", 3f)), "numbers only");
        ModelData.reset();
        assertFalse(ModelData.ready());
        assertTrue(Float.isNaN(ModelData.of("wand.multi3")), "not loaded");
    }

    @Test
    void theTableIsReadOffTheGameThreadOnce() throws Exception {
        ModelData.reset();
        Files.writeString(dir.resolve(ModelData.FILE), WynnventoryPoolsTest.wynntilsFixture("model-data.json"),
                StandardCharsets.UTF_8);
        ModelData.load(dir);
        for (int i = 0; i < 200 && !ModelData.ready(); i++) {
            Thread.sleep(10);
        }
        assertTrue(ModelData.ready());
        assertEquals(3975f, ModelData.of("spear.water3"));
        Files.writeString(dir.resolve(ModelData.FILE), "{\"floats\":{}}", StandardCharsets.UTF_8);
        ModelData.load(dir);                     // done: no second read
        Thread.sleep(50);
        assertEquals(3975f, ModelData.of("spear.water3"));
    }

    @Test
    void halcyonIsAWandOnAPotion() {
        Ref r = PoolIcons.resolve(find(loot(), "Canyon of the Lost", "Halcyon", false), Klass.SHAMAN);
        assertEquals("wand.multi3", r.modelKey());
        assertEquals("minecraft:potion", r.itemId());
        assertEquals("wynn/loot/mythic", r.texture(), "gear has no single texture: the rarity glyph");
        assertEquals(Theme.TIER_MYTHIC, r.glyphColour());
        assertEquals('G', r.glyphLetter());
        assertEquals(0, r.tint());
        assertTrue(PoolIcons.stackable(r));
    }

    @Test
    void armourKeysUseLeatherArmour() {
        Ref equites = PoolIcons.resolve(find(loot(), "Canyon of the Lost", "Equites", false), Klass.NONE);
        assertEquals("boots.diamond", equites.modelKey());
        assertEquals("minecraft:leather_boots", equites.itemId());
        assertEquals(Theme.TIER_FABLED, equites.glyphColour());
        Ref coil = PoolIcons.resolve(find(raid(), "The Canyon Colossus", "Stormbringer's Coil", false), Klass.NONE);
        assertEquals("minecraft:leather_helmet", coil.itemId());
    }

    @Test
    void aRuneWithoutAnIconTakesItsKeyFromTheName() {
        PoolItem uth = find(loot(), "Sky Islands", "Uth Rune", false);
        assertFalse(uth.hasIcon());
        Ref r = PoolIcons.resolve(uth, Klass.SHAMAN);
        assertEquals("rune_uth", r.modelKey());
        assertEquals("wynn/rune/uth", r.texture());
        assertEquals('R', r.glyphLetter());
        Ref withIcon = PoolIcons.resolve(find(loot(), "Corkus", "Uth Rune", false), Klass.SHAMAN);
        assertEquals("rune_uth", withIcon.modelKey(), "rune.uth through its alias");
    }

    @Test
    void theCorkianSimulatorThroughItsAlias() {
        Ref r = PoolIcons.resolve(find(loot(), "Canyon of the Lost", "Corkian Simulator", false), Klass.NONE);
        assertEquals("corkian_simulator", r.modelKey());
        assertEquals("minecraft:potion", r.itemId());
        assertEquals("wynn/augment/corkian_simulator", r.texture());
        Ref ins = PoolIcons.resolve(find(loot(), "Corkus", "Corkian Insulator", false), Klass.NONE);
        assertEquals("", ins.modelKey(), "the small table lacks corkian_insulator");
        assertEquals("wynn/augment/corkian_insulator", ins.texture());
        assertFalse(PoolIcons.stackable(ins));
    }

    @Test
    void aShamanAspectIsTheShamanPicture() {
        Ref r = PoolIcons.resolve(find(raid(), "The Canyon Colossus", "Aspect of Damnation", false), Klass.SHAMAN);
        assertEquals("abilityTree.aspectShaman", r.modelKey());
        assertEquals("wynn/gui/ability_tree/aspect/shaman", r.texture());
        assertEquals(0xFF479AC0, r.glyphColour(), "the class colour, not the rarity's");
        assertEquals('A', r.glyphLetter());
        Ref archer = PoolIcons.resolve(find(raid(), "The Canyon Colossus", "Aspect of the Sharpshooter", false), Klass.SHAMAN);
        assertEquals("abilityTree.aspectArcher", archer.modelKey(), "another class's aspect keeps its own class");
        assertEquals(0xFFC5589B, archer.glyphColour());
        Ref noIcon = PoolIcons.resolve(new PoolItem("Aspect of Damnation", "AspectItem", Tier.LEGENDARY, "", 1), Klass.SHAMAN);
        assertEquals("abilityTree.aspectShaman", noIcon.modelKey(), "no subtype, no icon: the class given");
    }

    @Test
    void tomesByType() {
        Ref mana = PoolIcons.resolve(find(raid(), "The Canyon Colossus", "Pegasus' Tome of Mysticism", false), Klass.NONE);
        assertEquals("tome.mana", mana.modelKey());
        assertEquals("wynn/mastery_tome/mana", mana.texture());
        assertEquals('T', mana.glyphLetter());
        Ref lootrun = PoolIcons.resolve(find(loot(), "Corkus", "Tome of Lootrun Mastery", false), Klass.NONE);
        assertEquals("wynn/mastery_tome/lootrun", lootrun.texture());
        Ref byName = PoolIcons.resolve(new PoolItem("Tome of Weapon Mastery", "TomeItem", Tier.FABLED, "", 1), Klass.NONE);
        assertEquals("wynn/mastery_tome/weapon", byName.texture());
    }

    @Test
    void anUnknownGearItemFallsToTheRarityGlyph() {
        PoolItem unknown = new PoolItem("Nowhere Blade", "GearItem", Tier.LEGENDARY, "Weapon", 1, "attribute",
                "sword.nothing9", false, "", 0);
        Ref r = PoolIcons.resolve(unknown, Klass.NONE);
        assertEquals("", r.modelKey());
        assertEquals("wynn/loot/legendary", r.texture());
        assertEquals(Theme.TIER_LEGENDARY, r.glyphColour());
        assertFalse(PoolIcons.stackable(r));
        Ref bare = PoolIcons.resolve(new PoolItem("Odd Thing", "", Tier.NORMAL, "", 1), Klass.NONE);
        assertEquals("wynn/loot/base", bare.texture());
        assertEquals(Theme.INK_3, bare.glyphColour());
        assertEquals('O', bare.glyphLetter());
    }

    @Test
    void anEmptyTableResolvesNoModelKey() {
        ModelData.use(Map.of());
        for (WeeklyPool p : List.of(raid(), loot())) {
            p.regions().forEach(r -> r.items().forEach(i -> {
                Ref ref = PoolIcons.resolve(i, Klass.SHAMAN);
                assertEquals("", ref.modelKey(), i.name());
                assertFalse(ref.texture().isEmpty(), i.name() + " still has a texture");
            }));
        }
        ModelData.reset();
        assertEquals("", PoolIcons.resolve(find(loot(), "Canyon of the Lost", "Halcyon", false), Klass.NONE).modelKey(),
                "not loaded");
    }

    @Test
    void vanillaItemsForEmeraldsAndHeads() {
        Ref block = PoolIcons.resolve(find(raid(), "The Canyon Colossus", "Emerald Block", false), Klass.NONE);
        assertEquals("minecraft:emerald_block", block.itemId());
        assertEquals("", block.modelKey());
        assertTrue(PoolIcons.stackable(block), "a vanilla item needs no float");
        assertEquals('E', block.glyphLetter());
        Ref liquid = PoolIcons.resolve(find(raid(), "The Canyon Colossus", "Liquid Emerald", false), Klass.NONE);
        assertEquals("minecraft:experience_bottle", liquid.itemId());
        Ref head = PoolIcons.resolve(find(raid(), "Nest of the Grootslangs", "Gaia's Cradle", false), Klass.NONE);
        assertEquals("minecraft:player_head", head.itemId());
        assertTrue(PoolIcons.stackable(head));
        assertEquals("wynn/loot/mythic", head.texture());
    }

    @Test
    void powdersWardsKeysAndCharms() {
        Ref small = PoolIcons.resolve(find(raid(), "The Canyon Colossus", "Fire Powder III", false), Klass.NONE);
        assertEquals("wynn/powder/small", small.texture());
        assertEquals(0xFFFF5555, small.tint(), "fire red");
        assertEquals('P', small.glyphLetter());
        Ref large = PoolIcons.resolve(find(raid(), "Nest of the Grootslangs", "Fire Powder VI", false), Klass.NONE);
        assertEquals("wynn/powder/large", large.texture());
        Ref byTier = PoolIcons.resolve(new PoolItem("Water Powder V", "PowderItem", Tier.NORMAL, "WATER", 1, "", "", false, "", 5),
                Klass.NONE);
        assertEquals("wynn/powder/large", byTier.texture());
        assertEquals(0xFF55FFFF, byTier.tint());

        Ref ward = PoolIcons.resolve(find(raid(), "The Canyon Colossus", "Ward of Endurance", false), Klass.NONE);
        assertEquals("ward_blue", ward.modelKey());
        assertEquals("wynn/ward/blue", ward.texture());
        Ref named = PoolIcons.resolve(new PoolItem("Blue Ward", "WardItem", Tier.NORMAL, "", 1), Klass.NONE);
        assertEquals("ward_blue", named.modelKey());

        Ref key = PoolIcons.resolve(find(loot(), "Corkus", "Corrupted Ice Barrows Key", false), Klass.NONE);
        assertEquals("dungeon_key_broken", key.modelKey());
        assertEquals("wynn/dungeon/key_broken", key.texture());
        Ref broken = PoolIcons.resolve(new PoolItem("Broken Galleon's Graveyard Key", "DungeonKeyItem", Tier.NORMAL, "", 1),
                Klass.NONE);
        assertEquals("dungeon_key_broken", broken.modelKey());
        Ref plainKey = PoolIcons.resolve(new PoolItem("Ice Barrows Key", "DungeonKeyItem", Tier.NORMAL, "", 1), Klass.NONE);
        assertEquals("dungeon_key", plainKey.modelKey());
        assertEquals("wynn/dungeon/key", plainKey.texture());

        Ref charm = PoolIcons.resolve(new PoolItem("Charm of the Void", "CharmItem", Tier.FABLED, "", 1), Klass.NONE);
        assertEquals("charm.void", charm.modelKey());
        assertEquals("wynn/loot/fabled", charm.texture());
    }

    @Test
    void aliasesInTryOrder() {
        assertEquals("corkian_simulator", PoolIcons.aliases("augment.corkianSimulator").get(1));
        assertEquals("rune_nii", PoolIcons.aliases("rune.nii").get(1));
        assertEquals("ward_pink", PoolIcons.aliases("ward.pink").get(1));
        assertEquals("dungeon_key_broken", PoolIcons.aliases("dungeon.keyBroken").get(1));
        assertEquals("dungeon_key", PoolIcons.aliases("dungeon.key").get(1));
        assertEquals("wand.multi3", PoolIcons.aliases("wand.multi3").get(0), "the key itself first");
        assertTrue(PoolIcons.aliases("").isEmpty());
        assertTrue(PoolIcons.aliases(null).isEmpty());
    }

    @Test
    void classColours() {
        assertEquals(0xFFC5589B, PoolIcons.classColour(Klass.ARCHER));
        assertEquals(0xFFA143AF, PoolIcons.classColour(Klass.ASSASSIN));
        assertEquals(0xFFCC8B3D, PoolIcons.classColour(Klass.MAGE));
        assertEquals(0xFF479AC0, PoolIcons.classColour(Klass.SHAMAN));
        assertEquals(0xFFCA5D50, PoolIcons.classColour(Klass.WARRIOR));
        assertEquals(Theme.INK_3, PoolIcons.classColour(Klass.NONE));
        assertEquals(Theme.INK_3, PoolIcons.classColour(null));
    }

    @Test
    void nothingStillResolves() {
        Ref r = PoolIcons.resolve(null, Klass.SHAMAN);
        assertEquals("", r.modelKey());
        assertEquals("", r.texture());
        assertEquals('?', r.glyphLetter());
    }
}
