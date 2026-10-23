package io.github.anilken.wynnlabs.search;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Test data for the search tab: the trimmed copies of Wynntils' cache files under
 * {@code search/cache} (Papyrus, Abdomen, Accursed Effigy, ...), and a synthetic cache of the
 * real size (about 7,000 items) for the latency tests. No player data.
 */
final class SearchFixtures {

    private SearchFixtures() {}

    /** The trimmed cache folder. */
    static Path cacheDir() {
        try {
            return Path.of(SearchFixtures.class.getResource("/search/cache/dataStaticGear").toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Chests for the fixture: two near Burning Bog (level 79), one near Abandoned Steelworks (110), one far from everything. */
    static List<Area> chests() {
        return List.of(
                new Area(-400, 40, -5200, 0, -1, 2, null),
                new Area(-420, 40, -5180, 0, -1, 4, null),
                new Area(-1830, 30, -800, 0, -1, 3, null),
                new Area(-16000, 30, 21000, 0, -1, 1, null));
    }

    private static ItemIndex index;

    /** The fixture index, read once. */
    static synchronized ItemIndex index() {
        if (index == null) {
            index = ItemIndex.read(cacheDir(), chests());
        }
        return index;
    }

    private static final String[] SYL = {"ar", "bel", "cor", "dra", "el", "fen", "gor", "hal", "is", "jor", "ka", "lum",
        "mor", "nex", "or", "pa", "qua", "ris", "sol", "tor", "um", "vel", "wyn", "xan", "yr", "zel"};
    private static final String[] STATS = {"manaSteal", "lifeSteal", "rawSpellDamage", "walkSpeed", "manaRegen",
        "healthRegenRaw", "rawStrength", "thunderDamage", "earthDefence", "lootBonus", "xpBonus", "raw1stSpellCost"};

    private static String word(Random r) {
        int n = 2 + r.nextInt(2);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(SYL[r.nextInt(SYL.length)]);
        }
        return Character.toUpperCase(b.charAt(0)) + b.substring(1);
    }

    private static String name(Random r) {
        return r.nextInt(3) == 0 ? word(r) + " " + word(r) : word(r);
    }

    private static String ids(Random r) {
        StringBuilder b = new StringBuilder();
        int n = 2 + r.nextInt(4);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                b.append(',');
            }
            int raw = 1 + r.nextInt(40);
            b.append('"').append(STATS[r.nextInt(STATS.length)]).append("\":{\"min\":").append(raw / 3)
                    .append(",\"max\":").append(raw * 13 / 10).append(",\"raw\":").append(raw).append('}');
        }
        return b.toString();
    }

    /**
     * Writes a synthetic cache of the real size into {@code dir}: 5,500 gear, 972 ingredients
     * (each dropped by a named mob), 112 materials, 156 tomes and 5 charms, plus the fixture's map
     * files. Deterministic (fixed seed). Returns the total item count.
     */
    static int writeSynthetic(Path dir) throws IOException {
        Random r = new Random(42);
        int total = 0;
        StringBuilder gear = new StringBuilder("{");
        String[] subs = {"helmet", "chestplate", "leggings", "boots", "ring", "bracelet", "necklace", "bow", "wand", "spear"};
        String[] tiers = {"normal", "unique", "rare", "legendary", "fabled", "mythic"};
        String[] drops = {"normal", "normal", "never", "lootchest"};
        List<String> used = new ArrayList<>();
        for (int i = 0; i < 5500; i++) {
            String n = name(r) + " " + i;
            used.add(n);
            if (i > 0) {
                gear.append(',');
            }
            int lv = 1 + r.nextInt(110);
            String meta = i % 7 == 0 ? ",\"dropMeta\":{\"coordinates\":[" + (r.nextInt(4000) - 2000) + ",60," + (-r.nextInt(6000))
                    + "],\"name\":\"" + word(r) + "\",\"type\":\"merchant\"}" : "";
            gear.append('"').append(n).append("\":{\"type\":\"armour\",\"subType\":\"").append(subs[r.nextInt(subs.length)])
                    .append("\",\"tier\":\"").append(tiers[r.nextInt(tiers.length)]).append("\",\"requirements\":{\"level\":")
                    .append(lv).append("},\"dropRestriction\":\"").append(drops[r.nextInt(drops.length)])
                    .append("\",\"restriction\":\"none\",\"identifications\":{").append(ids(r)).append('}').append(meta)
                    .append(",\"icon\":{\"format\":\"attribute\",\"value\":{\"id\":\"minecraft:potion\",\"customModelData\":{\"rangeDispatch\":[")
                    .append(i).append("]}}}}");
            total++;
        }
        Files.writeString(dir.resolve("dataStaticGear"), gear.append('}'), StandardCharsets.UTF_8);
        StringBuilder ing = new StringBuilder("{");
        for (int i = 0; i < 972; i++) {
            if (i > 0) {
                ing.append(',');
            }
            ing.append('"').append(name(r)).append(" Essence ").append(i).append("\":{\"tier\":\"TIER_").append(r.nextInt(4))
                    .append("\",\"requirements\":{\"level\":").append(1 + r.nextInt(110)).append(",\"skills\":[\"cooking\"]},")
                    .append("\"identifications\":{").append(ids(r)).append("},\"droppedBy\":[{\"name\":\"")
                    .append(word(r)).append(' ').append(word(r)).append("\",\"coords\":[[").append(r.nextInt(2000)).append(",50,")
                    .append(-r.nextInt(5000)).append(",40]]}]}");
            total++;
        }
        Files.writeString(dir.resolve("dataStaticIngredients"), ing.append('}'), StandardCharsets.UTF_8);
        total += simple(dir, "dataStaticMaterials", 112, "material", "mining", r);
        total += simple(dir, "dataStaticTomes", 156, "tome", "weapon_tome", r);
        total += simple(dir, "dataStaticCharms", 5, "charm", null, r);
        for (String f : new String[] {"dataStaticCaveInfo", "dataStaticCombatMapFeatures", "dataStaticPlaceMapFeatures",
            "dataStaticGatheringNodes", "dataStaticMaps"}) {
            Files.copy(cacheDir().resolve(f), dir.resolve(f));
        }
        return total;
    }

    private static int simple(Path dir, String file, int count, String type, String sub, Random r) throws IOException {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append('"').append(name(r)).append(' ').append(type.toUpperCase(Locale.ROOT).charAt(0)).append(i)
                    .append("\":{\"type\":\"").append(type).append('"')
                    .append(sub == null ? "" : ",\"subType\":\"" + sub + "\"")
                    .append(",\"requirements\":{\"level\":").append(1 + r.nextInt(110)).append("},\"tier\":\"rare\"}");
        }
        Files.writeString(dir.resolve(file), b.append('}'), StandardCharsets.UTF_8);
        return count;
    }
}
