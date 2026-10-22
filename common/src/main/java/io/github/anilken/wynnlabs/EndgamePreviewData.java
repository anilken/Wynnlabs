package io.github.anilken.wynnlabs;

import static io.github.anilken.wynnlabs.EndgamePanel.DAY;
import static io.github.anilken.wynnlabs.EndgamePanel.HOUR;
import static io.github.anilken.wynnlabs.EndgamePanel.MIN;

import io.github.anilken.wynnlabs.model.AspectEntry;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.LootrunRun;
import io.github.anilken.wynnlabs.model.MythicFind;
import io.github.anilken.wynnlabs.model.PoolAspect;
import io.github.anilken.wynnlabs.model.RaidDry;
import io.github.anilken.wynnlabs.model.RaidRun;
import io.github.anilken.wynnlabs.model.PoolItem;
import io.github.anilken.wynnlabs.model.PoolRegion;
import io.github.anilken.wynnlabs.model.Tier;
import io.github.anilken.wynnlabs.model.WeeklyPool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The no-game preview's fake Endgame data (openspec endgame-tab, design decision 9): raids, lootruns,
 * the weekly pools and the shaman aspects, fed through the preview hooks of the logs, the pool and
 * the aspect book.
 */
final class EndgamePreviewData {

    private EndgamePreviewData() {}

    /** The fake data of design decision 9 for the character in play ({@link EndgameTab#preview()}). */
    static void load() {
        long now = System.currentTimeMillis();
        // raids: counts and best times as in the mockup; the newest two show in the feed
        List<RaidRun> raids = new ArrayList<>();
        raids.add(raid("The Canyon Colossus", "TCC", true, now - 5 * HOUR, 872));
        raids.add(raid("Nest of the Grootslangs", "NOG", false, now - 50 * HOUR, 550));
        long at = now - 64 * HOUR;
        int[][] done = {
            {662, 690, 701, 715, 728, 744, 760, 771, 785, 802, 820, 841, 860, 905},   // NOG, best 11:02
            {1188, 1230, 1262, 1301, 1344, 1390},                                       // NOL, best 19:48
            {902, 915, 930, 951, 968, 990, 1010, 1044},                                 // TCC (and the one above), best 14:32
            {1090, 1005, 1040}};                                                        // TNA: 18:10, 16:45, 17:20
        String[][] names = {{"Nest of the Grootslangs", "NOG"}, {"Orphion's Nexus of Light", "NOL"},
            {"The Canyon Colossus", "TCC"}, {"The Nameless Anomaly", "TNA"}};
        int[] fails = {0, 2, 0, 1};
        for (int r = 0; r < done.length; r++) {
            for (int s : done[r]) {
                raids.add(raid(names[r][0], names[r][1], true, at, s));
                at -= 9 * HOUR;
            }
            for (int f = 0; f < fails[r]; f++) {
                raids.add(raid(names[r][0], names[r][1], false, at, 600 + 70 * f));
                at -= 9 * HOUR;
            }
        }
        RaidLog.preview(raids, 5, 3, 0, new RaidDry(7, 31, 2, 39));
        // lootruns: ten over four camps, two failed, two mythics (Warp and Grandmother)
        Map<String, Integer> beacons = new LinkedHashMap<>();
        beacons.put("Blue", 9);
        beacons.put("Purple", 4);
        beacons.put("Yellow", 3);
        beacons.put("Rainbow", 1);
        List<String> missions = List.of("Hoarder", "High Roller");
        List<LootrunRun> runs = List.of(
                loot(true, now - 2 * HOUR, 1880, 32, 14, "Silent Expanse", beacons, missions, null),
                loot(true, now - 26 * HOUR, 1625, 29, 13, "Corkus", beacons, List.of("Cleansing Greed"), "Warp"),
                loot(false, now - 49 * HOUR, 760, 11, 0, "Sky Islands", Map.of("Blue", 3, "Dark Gray", 2), List.of(), null),
                loot(true, now - 60 * HOUR, 1791, 31, 15, "Molten Heights", beacons, missions, null),
                loot(true, now - 74 * HOUR, 1802, 30, 12, "Silent Expanse", beacons, List.of(), null),
                loot(true, now - 98 * HOUR, 1690, 30, 14, "Corkus", beacons, missions, null),
                loot(false, now - 120 * HOUR, 605, 8, 0, "Sky Islands", Map.of("Purple", 2), List.of(), null),
                loot(true, now - 146 * HOUR, 2020, 34, 16, "Molten Heights", beacons, missions, "Grandmother"),
                loot(true, now - 170 * HOUR, 1740, 30, 13, "Silent Expanse", beacons, List.of(), null),
                loot(true, now - 194 * HOUR, 1905, 33, 15, "Corkus", beacons, missions, null));
        LootrunLog.preview(runs, List.of(new MythicFind("Warp", now - 26 * HOUR + MIN), new MythicFind("Grandmother", now - 146 * HOUR + MIN)),
                3, 40);
        // the weekly pool, seen two days ago: Damnation new, Amphibian a tier up
        RaidPool.preview(List.of(
                new PoolAspect("Aspect of Damnation", Klass.SHAMAN, Tier.LEGENDARY, 1),
                new PoolAspect("Aspect of the Amphibian", Klass.SHAMAN, Tier.FABLED, 2),
                new PoolAspect("Aspect of the Eternal Dance", Klass.SHAMAN, Tier.FABLED, 1),
                new PoolAspect("Aspect of the Monolith", Klass.SHAMAN, Tier.LEGENDARY, 1),
                new PoolAspect("Aspect of the Channeler", Klass.SHAMAN, Tier.FABLED, 1)), now - 2 * DAY - HOUR);
        // this week's pools from Wynnventory, as in the group 8 mockup: five raids, seven camps, with
        // the fixtures' icon values (ModelData stays empty, so the preview draws textures and glyphs)
        int[] week = WynnventoryPools.resetWeek(now);
        WynnventoryPools.preview(new WeeklyPool(week[0], week[1], now - 20 * MIN, previewRaids(now)),
                new WeeklyPool(week[0], week[1], now - 20 * MIN, previewCamps(now)));
        // shaman aspects: 9 of 25 owned, two equipped, ranked for Ritualist
        Map<String, Integer> owned = new LinkedHashMap<>();
        owned.put("Aspect of the Amphibian", 1);
        owned.put("Aspect of the Channeler", 1);
        owned.put("Aspect of the Monolith", 2);
        owned.put("Aspect of Lashing Fire", 1);
        owned.put("Aspect of the Haul", 2);
        owned.put("Aspect of Cleansing Waters", 1);
        owned.put("Aspect of Bloodlust", 1);
        owned.put("Aspect of the Vortex", 4);
        owned.put("Aspect of the Eternal Dance", 3);
        AspectBook.preview(Klass.SHAMAN, shamanAspects(), owned, List.of("Aspect of the Monolith", "Aspect of the Eternal Dance"),
                "Ritualist");
    }

    /** No runs, no pool, aspects never read ({@link EndgameTab#previewEmpty()}). */
    static void loadEmpty() {
        RaidLog.preview(List.of(), 0, 0, 0, RaidDry.UNKNOWN);
        LootrunLog.preview(List.of(), List.of(), 0, 0);
        RaidPool.preview(List.of(), -1);
        WynnventoryPools.preview(WeeklyPool.NONE, WeeklyPool.NONE);
        AspectBook.preview(Klass.SHAMAN, shamanAspects(), null, List.of(), "Ritualist");
    }

    /**
     * The mockup's raid pool, with the fixtures' icon values: The Canyon Colossus as in decision
     * 11's drawing (19 aspects, the Shaman's six first; 15 tomes; gear; emerald blocks that fold to
     * ×10, a charm, a powder and an amplifier with their tier), the other four smaller. The Shaman
     * aspects keep group 7's spread (Damnation in two raids).
     */
    private static List<PoolRegion> previewRaids(long now) {
        return List.of(
                region("The Canyon Colossus", now - 3 * HOUR,
                        aspect("Aspect of Exsanguination", Tier.FABLED, "Shaman"),
                        aspect("Aspect of the Monolith", Tier.LEGENDARY, "Shaman"),
                        aspect("Aspect of Damnation", Tier.LEGENDARY, "Shaman"),
                        aspect("Aspect of Gushing Blood", Tier.LEGENDARY, "Shaman"),
                        aspect("Aspect of the Channeler", Tier.FABLED, "Shaman"),
                        aspect("Aspect of the Vortex", Tier.LEGENDARY, "Shaman"),
                        aspect("Mage's Embodiment of Morbid Curiosity", Tier.MYTHIC, "Mage"),
                        aspect("Trapper's Embodiment of Persistence Predation", Tier.MYTHIC, "Archer"),
                        aspect("Boltslinger's Embodiment of Rended Skies", Tier.MYTHIC, "Archer"),
                        aspect("Aspect of Undercrank", Tier.FABLED, "Archer"),
                        aspect("Aspect of Manaflux", Tier.FABLED, "Mage"),
                        aspect("Aspect of the Agile Blade", Tier.FABLED, "Assassin"),
                        aspect("Aspect of Arcane Speed", Tier.FABLED, "Mage"),
                        aspect("Aspect of Shadows", Tier.FABLED, "Assassin"),
                        aspect("Aspect of the Bulwark", Tier.LEGENDARY, "Warrior"),
                        aspect("Aspect of the Hunter", Tier.LEGENDARY, "Archer"),
                        aspect("Aspect of Rallying Fury", Tier.LEGENDARY, "Warrior"),
                        aspect("Aspect of Blinding Light", Tier.LEGENDARY, "Mage"),
                        aspect("Aspect of the Ironclad", Tier.LEGENDARY, "Warrior"),
                        tome("Faerie's Tome of Mysticism II", Tier.MYTHIC, "mysticism"),
                        tome("Vampiric Tome of Defensive Mastery II", Tier.MYTHIC, "armour"),
                        tome("Everlasting Tome of Defensive Mastery II", Tier.MYTHIC, "armour"),
                        tome("Pegasus' Tome of Mysticism II", Tier.MYTHIC, "mysticism"),
                        tome("Golem's Tome of Mysticism II", Tier.MYTHIC, "mysticism"),
                        tome("Dragon's Tome of Mysticism II", Tier.MYTHIC, "mysticism"),
                        tome("Blooming Tome of Defensive Mastery II", Tier.FABLED, "armour"),
                        tome("Fleetfooted Tome of the Marathon II", Tier.FABLED, "marathon"),
                        tome("Pulsing Tome of Defensive Mastery II", Tier.FABLED, "armour"),
                        tome("Oceanic Tome of Defensive Mastery II", Tier.FABLED, "armour"),
                        tome("Courageous Tome of Defensive Mastery II", Tier.FABLED, "armour"),
                        tome("Surefooted Tome of the Marathon II", Tier.FABLED, "marathon"),
                        tome("Tome of Combat Mastery", Tier.FABLED, "weapon"),
                        tome("Bloodthirsty Tome of Combat Mastery II", Tier.LEGENDARY, "weapon"),
                        tome("Tome of Expertise", Tier.LEGENDARY, "expertise"),
                        skin("Warchief", Tier.FABLED),
                        gear("Stardew", Tier.LEGENDARY, "boots.diamond"),
                        item("Emerald Block", "EmeraldItem", Tier.NORMAL, "emerald.emeraldBlock", 4, 0),
                        item("Charm of the Void", "CharmItem", Tier.FABLED, "charm.void", 1, 0),
                        item("Emerald Block", "EmeraldItem", Tier.NORMAL, "emerald.emeraldBlock", 6, 0),
                        item("Liquid Emerald", "EmeraldItem", Tier.NORMAL, "emerald.liquidEmerald", 2, 0),
                        item("Fire Powder", "PowderItem", Tier.NORMAL, "powder.FireSmall", 1, 4),
                        item("Corkian Amplifier", "AmplifierItem", Tier.NORMAL, "augment.corkianAmplifier", 1, 2)),
                region("The Nameless Anomaly", now - 26 * HOUR,
                        aspect("Aspect of Damnation", Tier.LEGENDARY, "Shaman"),
                        aspect("Aspect of the Monolith", Tier.LEGENDARY, "Shaman"),
                        aspect("Aspect of Arcane Speed", Tier.FABLED, "Mage"),
                        tome("Tome of Combat Mastery", Tier.FABLED, "weapon"),
                        item("Emerald Block", "EmeraldItem", Tier.NORMAL, "emerald.emeraldBlock", 64, 0)),
                region("Nest of the Grootslangs", now - 15 * HOUR,
                        aspect("Ritualist's Embodiment of the Ancestral Avatar", Tier.MYTHIC, "Shaman"),
                        aspect("Aspect of Bile Waters", Tier.LEGENDARY, "Shaman"),
                        aspect("Aspect of the Hunter", Tier.LEGENDARY, "Archer"),
                        tome("Tome of Expertise", Tier.LEGENDARY, "expertise"),
                        item("Liquid Emerald", "EmeraldItem", Tier.NORMAL, "emerald.liquidEmerald", 1, 0)),
                region("Orphion's Nexus of Light", now - 20 * HOUR,
                        aspect("Aspect of Motivation", Tier.LEGENDARY, "Shaman"),
                        aspect("Aspect of the Vortex", Tier.LEGENDARY, "Shaman"),
                        aspect("Aspect of Shadows", Tier.FABLED, "Assassin"),
                        tome("Tome of Rage", Tier.LEGENDARY, "weapon")),
                region("The Wartorn Palace", now - 49 * HOUR,
                        aspect("Aspect of Exsanguination", Tier.FABLED, "Shaman"),
                        tome("Tome of Rage", Tier.LEGENDARY, "weapon"),
                        item("Charm of Stone", "CharmItem", Tier.LEGENDARY, "charm.stone", 1, 0)));
    }

    /**
     * The mockup's lootrun pool: seven camps. Canyon of the Lost as in decision 11's drawing: a
     * shiny Halcyon ("Raids Won") next to its plain twin, four distinct mythics and the Corkian
     * Simulator, then Fabled, Legendary, Rare, Unique and the other rewards (rune, key, emeralds,
     * ward, powder).
     */
    private static List<PoolRegion> previewCamps(long now) {
        return List.of(
                region("Fruma Foray (East)", now - 50 * HOUR, gear("Nirvana", Tier.MYTHIC, "dagger.fire3"),
                        gear("Alkatraz", Tier.MYTHIC, "relik.earth3"), gear("Apocalypse", Tier.MYTHIC, "spear.fire3"),
                        gear("Aftershock", Tier.MYTHIC, "relik.earth3"), gear("Toxoplasmosis", Tier.MYTHIC, "bow.earth3"),
                        gear("Lament", Tier.MYTHIC, "wand.water3"), gear("Pyrotechnic", Tier.LEGENDARY, "helmet.diamond")),
                region("Fruma Foray (West)", now - 49 * HOUR, gear("Fatal", Tier.MYTHIC, "wand.thunder3"),
                        gear("Archangel", Tier.MYTHIC, "spear.air3"), gear("Hero", Tier.MYTHIC, "bow.air3"),
                        gear("Weathered", Tier.MYTHIC, "dagger.air3"), gear("Slayer", Tier.MYTHIC, "boots.diamond"),
                        gear("Moontower", Tier.MYTHIC, "chestplate.diamond"), shiny("Moontower", Tier.MYTHIC, "chestplate.diamond", "Mobs Killed")),
                region("Silent Expanse", now - 2 * HOUR, gear("Warp", Tier.MYTHIC, "wand.air3"), gear("Divzer", Tier.MYTHIC, "bow.thunder3"),
                        gear("Stratiformis", Tier.MYTHIC, "spear.air3"), gear("Collapse", Tier.MYTHIC, "spear.earth3"),
                        gear("Absolution", Tier.MYTHIC, "relik.fire3"), shiny("Divzer", Tier.MYTHIC, "bow.thunder3", "Lootruns Completed"),
                        gear("Cerid's Dynamo", Tier.LEGENDARY, "ring.basic")),
                region("Corkus", now - 9 * HOUR, gear("Grandmother", Tier.MYTHIC, "leggings.diamond"),
                        gear("Boreal", Tier.MYTHIC, "chestplate.diamond"), gear("Revenant", Tier.MYTHIC, "boots.diamond"),
                        gear("Fantasia", Tier.MYTHIC, "relik.thunder3"), item("Uth Rune", "RuneItem", Tier.NORMAL, "rune.uth", 1, 0)),
                region("Sky Islands", now - 5 * HOUR, gear("Galleon", Tier.MYTHIC, "helmet.diamond"),
                        gear("Freedom", Tier.MYTHIC, "bow.air3"), gear("Gaia", Tier.MYTHIC, "wand.earth3"),
                        item("Sky Islands Key", "DungeonKeyItem", Tier.NORMAL, "dungeon.key", 1, 0)),
                region("Molten Heights", now - 25 * HOUR, gear("Immolation", Tier.MYTHIC, "relik.fire3"),
                        gear("Hadal", Tier.MYTHIC, "bow.water3"), shiny("Hadal", Tier.MYTHIC, "bow.water3", "Dungeons Completed")),
                region("Canyon of the Lost", now - 2 * HOUR,
                        shiny("Halcyon", Tier.MYTHIC, "wand.multi3", "Raids Won"),
                        gear("Inferno", Tier.MYTHIC, "dagger.fire3"), gear("Quetzalcoatl", Tier.MYTHIC, "wand.air3"),
                        gear("Halcyon", Tier.MYTHIC, "wand.multi3"), gear("Idol", Tier.MYTHIC, "spear.water3"),
                        item("Corkian Simulator", "SimulatorItem", Tier.MYTHIC, "augment.corkianSimulator", 1, 0),
                        gear("Equites", Tier.FABLED, "boots.diamond"), gear("Alkali", Tier.FABLED, "necklace.basic"),
                        gear("Welder Cannon", Tier.FABLED, "bow.fire3"), gear("Vulcan", Tier.FABLED, "helmet.diamond"),
                        gear("Cascade", Tier.FABLED, "spear.water3"), gear("Chimera", Tier.FABLED, "relik.multi3"),
                        tome("Tome of Lootrun Mastery", Tier.FABLED, "lootrun"),
                        gear("Flameshot Hilt", Tier.LEGENDARY, "dagger.fire3"), gear("Steamjet Walkers", Tier.LEGENDARY, "boots.diamond"),
                        gear("Loaded Question", Tier.LEGENDARY, "bracelet.basic"), skin("Outrage", Tier.LEGENDARY),
                        gear("Leo", Tier.LEGENDARY, "chestplate.diamond"),
                        gear("Charybdis", Tier.RARE, "chestplate.diamond"), gear("Ambiguity", Tier.RARE, "necklace.basic"),
                        gear("Ligfamblawende", Tier.RARE, "wand.multi3"), gear("Cloud Nine", Tier.RARE, "spear.air3"),
                        gear("Oktavist", Tier.RARE, "bow.thunder3"), gear("Mystical Lance", Tier.RARE, "spear.thunder3"),
                        gear("Breakbeat", Tier.RARE, "relik.air3"),
                        gear("Leaf Cap", Tier.UNIQUE, "helmet.diamond"), gear("Gale Rider", Tier.UNIQUE, "boots.diamond"),
                        gear("Dune Storm", Tier.UNIQUE, "bow.earth3"),
                        item("Uth Rune", "RuneItem", Tier.NORMAL, "rune.uth", 1, 0),
                        item("Broken Lost Sanctuary Key", "DungeonKeyItem", Tier.NORMAL, "dungeon.keyBroken", 1, 0),
                        item("Emerald Block", "EmeraldItem", Tier.NORMAL, "emerald.emeraldBlock", 3, 0),
                        item("Blue Ward", "WardItem", Tier.NORMAL, "ward.blue", 1, 0),
                        item("Water Powder", "PowderItem", Tier.NORMAL, "powder.WaterSmall", 1, 3)));
    }

    private static PoolRegion region(String name, long seenAt, PoolItem... items) {
        return new PoolRegion(name, "", seenAt, List.of(items));
    }

    private static PoolItem aspect(String name, Tier rarity, String klass) {
        return new PoolItem(name, "AspectItem", rarity, klass + "Aspect", 1, "aspect_attribute", "abilityTree.aspect" + klass, false, "", 0);
    }

    private static PoolItem tome(String name, Tier rarity, String type) {
        return item(name, "TomeItem", rarity, "tome." + type, 1, 0);
    }

    private static PoolItem gear(String name, Tier rarity, String icon) {
        return item(name, "GearItem", rarity, icon, 1, 0);
    }

    private static PoolItem shiny(String name, Tier rarity, String icon, String stat) {
        return new PoolItem(name, "GearItem", rarity, "", 1, "attribute", icon, true, stat, 0);
    }

    /** Gear whose icon is a player head ({@code skin}), as one fixture entry has. */
    private static PoolItem skin(String name, Tier rarity) {
        return new PoolItem(name, "GearItem", rarity, "", 1, "skin", "preview-skin", false, "", 0);
    }

    private static PoolItem item(String name, String type, Tier rarity, String icon, int amount, int tier) {
        return new PoolItem(name, type, rarity, "", amount, "attribute", icon, false, "", tier);
    }

    private static RaidRun raid(String name, String abbr, boolean completed, long endedAt, int seconds) {
        long ms = seconds * 1000L;
        List<RaidRun.Room> rooms = completed
                ? List.of(new RaidRun.Room("Room 1", ms * 24 / 100), new RaidRun.Room("Room 2", ms * 22 / 100),
                        new RaidRun.Room("Room 3", ms * 26 / 100), new RaidRun.Room("Boss", ms - ms * 72 / 100))
                : List.of(new RaidRun.Room("Room 1", ms * 45 / 100), new RaidRun.Room("Room 2", ms - ms * 45 / 100));
        return new RaidRun(name, abbr, completed, endedAt, ms, rooms);
    }

    private static LootrunRun loot(boolean completed, long endedAt, int seconds, int challenges, int pulls, String camp,
            Map<String, Integer> beacons, List<String> missions, String mythic) {
        return new LootrunRun(completed, endedAt, seconds, challenges, pulls, completed ? 2 : 0, completed ? 1 : 0,
                completed ? 41_000 + 900 * challenges : 0, camp, beacons, missions, mythic);
    }

    /** Twenty-five shaman aspects with real names; tiers and thresholds by rarity, ability names for the ranking. */
    private static List<AspectEntry> shamanAspects() {
        Object[][] rows = {
            {"Aspect of Damnation", Tier.LEGENDARY, List.of("Malediction")},
            {"Ritualist's Embodiment of the Ancestral Avatar", Tier.MYTHIC, List.of("Mask of the Fanatic", "Masquerade")},
            {"Aspect of the Amphibian", Tier.FABLED, List.of("Frog Dance")},
            {"Aspect of Gushing Blood", Tier.LEGENDARY, List.of("Blood Pool")},
            {"Aspect of the Channeler", Tier.FABLED, List.of("Aura")},
            {"Aspect of the Monolith", Tier.LEGENDARY, List.of("Totem")},
            {"Aspect of Bile Waters", Tier.LEGENDARY, List.of()},
            {"Aspect of Motivation", Tier.LEGENDARY, List.of()},
            {"Summoner's Embodiment of the Omnipotent Overseer", Tier.MYTHIC, List.of()},
            {"Acolyte's Embodiment of Unwavering Adherence", Tier.MYTHIC, List.of()},
            {"Aspect of the Beckoned Legion", Tier.FABLED, List.of()},
            {"Aspect of Exsanguination", Tier.FABLED, List.of()},
            {"Aspect of the Puppeteer", Tier.LEGENDARY, List.of()},
            {"Aspect of Stances", Tier.LEGENDARY, List.of()},
            {"Aspect of Sanguine Rage", Tier.LEGENDARY, List.of()},
            {"Aspect of the Lunatic", Tier.LEGENDARY, List.of()},
            {"Aspect of Rejuvenation", Tier.LEGENDARY, List.of()},
            {"Aspect of the Undying Totem", Tier.FABLED, List.of()},
            {"Aspect of Infinite Crows", Tier.LEGENDARY, List.of()},
            {"Aspect of Lashing Fire", Tier.LEGENDARY, List.of()},
            {"Aspect of the Haul", Tier.LEGENDARY, List.of()},
            {"Aspect of Cleansing Waters", Tier.LEGENDARY, List.of()},
            {"Aspect of Bloodlust", Tier.LEGENDARY, List.of()},
            {"Aspect of the Vortex", Tier.LEGENDARY, List.of()},
            {"Aspect of the Eternal Dance", Tier.FABLED, List.of("Frog Dance")},
        };
        List<AspectEntry> out = new ArrayList<>();
        for (Object[] r : rows) {
            Tier t = (Tier) r[1];
            List<Integer> th = t == Tier.MYTHIC ? List.of(1, 3, 10) : t == Tier.FABLED ? List.of(1, 15, 75) : List.of(1, 5, 30, 150);
            @SuppressWarnings("unchecked")
            List<String> abilities = (List<String>) r[2];
            out.add(new AspectEntry((String) r[0], Klass.SHAMAN, t, th.size(), th, abilities));
        }
        return out;
    }
}
