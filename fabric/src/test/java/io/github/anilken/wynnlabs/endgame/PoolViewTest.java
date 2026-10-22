package io.github.anilken.wynnlabs.endgame;

import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.endgame.PoolView.Group;
import io.github.anilken.wynnlabs.endgame.PoolView.Group.Kind;
import io.github.anilken.wynnlabs.endgame.PoolView.Region;
import io.github.anilken.wynnlabs.endgame.PoolView.Row;
import io.github.anilken.wynnlabs.model.AspectEntry;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.PoolAspect;
import io.github.anilken.wynnlabs.model.PoolItem;
import io.github.anilken.wynnlabs.model.PoolRegion;
import io.github.anilken.wynnlabs.model.Tier;
import io.github.anilken.wynnlabs.model.WeeklyPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pools as the Havuzlar view lists them: folding, groups, own class first, marks, region rows. */
class PoolViewTest {

    private static final long OCT18 = WynnventoryPoolsTest.OCT18;
    private static final String TCC = "The Canyon Colossus";
    private static final String CANYON = "Canyon of the Lost";
    /** The Shaman aspects of the raid fixture (as in RaidPoolTest), each with 3 tiers. */
    private static final List<String> SHAMAN_POOL = List.of("Aspect of the Amphibian", "Aspect of Bile Waters",
            "Aspect of the Eternal Dance", "Aspect of the Summoner", "Aspect of Damnation", "Aspect of Gushing Blood",
            "Aspect of the Channeler", "Aspect of the Monolith", "Aspect of Seeping Rot");

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        RaidPool.use(dir);
        WynnventoryPools.reset();
        AspectBook.reset();
        JsonObject s = new JsonObject();
        s.addProperty("langOverride", 1);
        Settings.use(s);
        RaidPool.clock(() -> OCT18);
    }

    @AfterEach
    void tearDown() {
        RaidPool.reset();
        RaidPool.clock(null);
        WynnventoryPools.reset();
        AspectBook.reset();
        Settings.use(new JsonObject());
    }

    private static WeeklyPool raidFixture() {
        return WynnventoryPools.parse(WynnventoryPoolsTest.fixture("raidpool-current.json"), OCT18);
    }

    private static WeeklyPool lootFixture() {
        return WynnventoryPools.parse(WynnventoryPoolsTest.fixture("lootpool-current.json"), OCT18);
    }

    /** A Shaman: Damnation not owned, Gushing Blood at 1 of 3, the Channeler at 3 of 3, the rest at 3. */
    private static void shaman() {
        List<AspectEntry> aspects = new ArrayList<>();
        Map<String, Integer> owned = new HashMap<>();
        for (String n : SHAMAN_POOL) {
            aspects.add(new AspectEntry(n, Klass.SHAMAN, Tier.LEGENDARY, 3, List.of(1, 5, 30), List.of()));
            owned.put(n, 3);
        }
        owned.put("Aspect of Damnation", 0);
        owned.put("Aspect of Gushing Blood", 1);
        AspectBook.install(Klass.SHAMAN, aspects, owned, List.of(), null, Set.of(), Set.of(), "Ritualist");
    }

    private static List<String> names(Group g) {
        return g.rows().stream().map(r -> r.item().name()).toList();
    }

    private static Group of(List<Group> groups, Kind kind) {
        return groups.stream().filter(g -> g.kind() == kind).findFirst().orElseThrow();
    }

    @Test
    void foldSumsRepeatsAndKeepsShiniesApart() {
        PoolItem block = new PoolItem("Emerald Block", "EmeraldItem", Tier.NORMAL, "", 1);
        PoolItem halcyon = new PoolItem("Halcyon", "GearItem", Tier.MYTHIC, "Weapon", 1);
        PoolItem shiny = new PoolItem("Halcyon", "GearItem", Tier.MYTHIC, "Weapon", 1, "attribute", "wand.multi3", true,
                "Raids Won", 0);
        List<PoolItem> in = List.of(block, halcyon, block, shiny, new PoolItem("Emerald Block", "EmeraldItem", Tier.NORMAL, "", 2));
        List<PoolItem> out = PoolView.fold(in);
        assertEquals(List.of("Emerald Block", "Halcyon", "Halcyon"), out.stream().map(PoolItem::name).toList(), "first order kept");
        assertEquals(4, out.get(0).amount());
        assertFalse(out.get(1).shiny());
        assertTrue(out.get(2).shiny(), "a shiny is never folded into its plain twin");
        assertEquals(5, in.size(), "the input is not changed");
        assertTrue(PoolView.fold(null).isEmpty());
        assertTrue(PoolView.fold(List.of()).isEmpty());
    }

    @Test
    void theCanyonColossusForAShaman() {
        shaman();
        WynnventoryPools.testData(raidFixture(), WeeklyPool.NONE, true);
        List<Group> groups = PoolView.raid(TCC, false);
        assertEquals(List.of(Kind.ASPECTS, Kind.TOMES, Kind.GEAR, Kind.MISC), groups.stream().map(Group::kind).toList(),
                "no shiny in this raid: no Shiny group");

        Group aspects = of(groups, Kind.ASPECTS);
        assertEquals(19, aspects.rows().size());
        assertEquals(List.of("Aspect of the Channeler", "Aspect of Damnation", "Aspect of Gushing Blood"),
                names(aspects).subList(0, 3), "own class first, by rarity then Wynnventory's order");
        assertTrue(aspects.rows().subList(0, 3).stream().allMatch(Row::ownClass));
        List<Row> others = aspects.rows().subList(3, 19);
        assertTrue(others.stream().noneMatch(Row::ownClass), "then the other classes");
        assertEquals(List.of("Aspect of Rending Steel", "Aspect of the Snowstorm"),
                others.subList(0, 2).stream().map(r -> r.item().name()).toList(), "mythics first");
        assertTrue(others.stream().allMatch(r -> r.mark() == RaidPool.Mark.NONE && r.ownedTier() == -1));

        Row channeler = aspects.rows().get(0);
        assertEquals(RaidPool.Mark.NONE, channeler.mark());
        assertTrue(channeler.maxed(), "3 of 3");
        Row damnation = aspects.rows().get(1);
        assertEquals(RaidPool.Mark.NEW, damnation.mark());
        assertEquals(0, damnation.tagTier());
        assertEquals(0, damnation.ownedTier());
        Row gushing = aspects.rows().get(2);
        assertEquals(RaidPool.Mark.TIER_UP, gushing.mark());
        assertEquals(2, gushing.tagTier());
        assertEquals(1, gushing.ownedTier());
        assertEquals(3, gushing.maxTier());
        assertFalse(gushing.maxed());

        assertEquals(List.of("Tome of Raid Mastery", "Pegasus' Tome of Mysticism"), names(of(groups, Kind.TOMES)));
        assertEquals(List.of("Stormbringer's Coil"), names(of(groups, Kind.GEAR)));
        Group misc = of(groups, Kind.MISC);
        assertEquals(List.of("Charm of the Colossus", "Ward of Endurance", "Corkian Amplifier III", "Fire Powder III",
                "Liquid Emerald", "Emerald Block"), names(misc), "by rarity, then Wynnventory's order");
        Row blocks = misc.rows().get(5);
        assertEquals(3, blocks.count(), "the repeated Emerald Blocks fold to one row");
        assertEquals(3, blocks.item().amount());

        List<Group> only = PoolView.raid(TCC, true);
        assertEquals(List.of("Aspect of the Channeler", "Aspect of Damnation", "Aspect of Gushing Blood"),
                names(of(only, Kind.ASPECTS)), "class only: the others are dropped");
        assertEquals(4, only.size(), "the other groups stay");
    }

    @Test
    void canyonOfTheLostByRarity() {
        WynnventoryPools.testData(WeeklyPool.NONE, lootFixture(), true);
        List<Group> groups = PoolView.camp(CANYON);
        assertEquals(List.of(Kind.SHINY, Kind.MYTHIC, Kind.FABLED, Kind.LEGENDARY, Kind.RARE, Kind.OTHER),
                groups.stream().map(Group::kind).toList(), "no unique item: no Unique group");
        Group shiny = of(groups, Kind.SHINY);
        assertEquals(List.of("Halcyon"), names(shiny));
        assertEquals("Raids Won", shiny.rows().get(0).item().shinyStat());
        assertEquals(List.of("Inferno", "Quetzalcoatl", "Halcyon", "Idol", "Corkian Simulator"), names(of(groups, Kind.MYTHIC)),
                "Halcyon once");
        assertEquals(List.of("Equites", "Alkali", "Tome of Lootrun Mastery"), names(of(groups, Kind.FABLED)));
        assertEquals(List.of("Az Rune", "Corrupted Ice Barrows Key", "Liquid Emerald"), names(of(groups, Kind.OTHER)),
                "runes, keys and emeralds have no rarity");
        assertTrue(groups.stream().flatMap(g -> g.rows().stream()).noneMatch(Row::ownClass));
    }

    @Test
    void regionRowsCountsAndOrder() {
        shaman();
        WynnventoryPools.testData(raidFixture(), lootFixture(), true);
        List<Region> raids = PoolView.raidRegions();
        assertEquals(RaidLog.RAIDS, raids.stream().map(Region::name).toList(), "RaidPool.poolRaids() order");
        Region tcc = raids.stream().filter(r -> r.name().equals(TCC)).findFirst().orElseThrow();
        assertTrue(tcc.raid());
        assertEquals(2, tcc.marked(), "Damnation NEW, Gushing Blood TIER_UP");
        assertEquals(28, tcc.items(), "30 entries, the three Emerald Blocks as one row");
        assertEquals(1, tcc.mythics(), "Stormbringer's Coil");
        assertEquals(0, tcc.shinies());
        assertEquals(Instant.parse("2026-10-17T22:39:33Z").toEpochMilli(), tcc.seenAt());

        List<Region> camps = PoolView.campRegions();
        assertEquals(List.of("Canyon of the Lost", "Silent Expanse", "Corkus", "Molten Heights", "Fruma Foray (East)",
                "Sky Islands", "Fruma Foray (West)"), camps.stream().map(Region::name).toList(), "by mythics, then name");
        Region canyon = camps.get(0);
        assertFalse(canyon.raid());
        assertEquals(4, canyon.mythics(), "distinct, not shiny");
        assertEquals(1, canyon.shinies());
        assertEquals(15, canyon.items());
        assertEquals(0, canyon.marked());
        assertEquals(Instant.parse("2026-10-17T09:20:00Z").toEpochMilli(), canyon.seenAt());
        assertEquals(3, camps.get(1).mythics(), "Silent Expanse: the shiny Divzer is not a fourth");
        assertEquals(1, camps.get(1).shinies());
        assertEquals(0, camps.get(6).mythics());
    }

    @Test
    void thePoolSeenInGameIsOneRegionWithoutAName() {
        shaman();
        RaidPool.preview(List.of(new PoolAspect("Aspect of Damnation", Klass.SHAMAN, Tier.LEGENDARY, 1),
                new PoolAspect("Aspect of Gushing Blood", Klass.SHAMAN, Tier.LEGENDARY, 2),
                new PoolAspect("Aspect of the Channeler", Klass.SHAMAN, Tier.FABLED, 3)), OCT18 - 3_600_000L);
        assertEquals(RaidPool.Source.GAME, RaidPool.source());
        List<Region> raids = PoolView.raidRegions();
        assertEquals(1, raids.size());
        assertEquals("", raids.get(0).name());
        assertEquals(3, raids.get(0).items());
        assertEquals(OCT18 - 3_600_000L, raids.get(0).seenAt());
        assertEquals(2, raids.get(0).marked());
        List<Group> groups = PoolView.raid("", false);
        assertEquals(List.of(Kind.ASPECTS), groups.stream().map(Group::kind).toList());
        List<Row> rows = of(groups, Kind.ASPECTS).rows();
        assertEquals(List.of("Aspect of the Channeler", "Aspect of Damnation", "Aspect of Gushing Blood"),
                rows.stream().map(r -> r.item().name()).toList());
        assertEquals(RaidPool.Mark.NEW, rows.get(1).mark());
        assertEquals(RaidPool.Mark.TIER_UP, rows.get(2).mark());
        assertEquals(2, rows.get(2).tagTier(), "the tier the menu showed");
        assertTrue(PoolView.campRegions().isEmpty());
        assertTrue(PoolView.raid(TCC, false).isEmpty(), "no Wynnventory raid of that name");
    }

    @Test
    void theGroupCoreWithoutAClass() {
        PoolRegion r = raidFixture().regions().get(0);
        List<Group> groups = PoolView.group(r, true, Klass.NONE, n -> -1, n -> 0, true);
        Group aspects = of(groups, Kind.ASPECTS);
        assertEquals(19, aspects.rows().size(), "no class in play: every aspect, class only or not");
        assertTrue(aspects.rows().stream().noneMatch(Row::ownClass));
        assertEquals(Tier.MYTHIC, aspects.rows().get(0).item().rarity());
        assertTrue(PoolView.group(null, true, Klass.SHAMAN, n -> 0, n -> 0, false).isEmpty());
        PoolRegion shinyRaid = new PoolRegion(TCC, "RAID", 1, List.of(
                new PoolItem("Halcyon", "GearItem", Tier.MYTHIC, "Weapon", 1, "", "", true, "", 0)));
        assertEquals(List.of(Kind.SHINY), PoolView.group(shinyRaid, true, Klass.SHAMAN, n -> 0, n -> 0, false)
                .stream().map(Group::kind).toList(), "a raid with a shiny gets the Shiny group");
    }

    @Test
    void noDataGivesEmptyLists() {
        shaman();
        assertTrue(PoolView.raidRegions().isEmpty());
        assertTrue(PoolView.campRegions().isEmpty());
        assertTrue(PoolView.raid(TCC, false).isEmpty());
        assertTrue(PoolView.raid("", false).isEmpty());
        assertTrue(PoolView.camp(CANYON).isEmpty());
        assertTrue(PoolView.camp(null).isEmpty());
        WynnventoryPools.testData(raidFixture(), lootFixture(), false);   // the gate closed
        assertTrue(PoolView.raidRegions().isEmpty());
        assertTrue(PoolView.campRegions().isEmpty());
    }
}
