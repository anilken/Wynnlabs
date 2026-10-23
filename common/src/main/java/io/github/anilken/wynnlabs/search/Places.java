package io.github.anilken.wynnlabs.search;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.anilken.wynnlabs.core.Rules;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The world's places the sources point at, from Wynntils' cached map data: caves
 * ({@code dataStaticCaveInfo}), dungeons, altars, raids, lootrun camps and grind spots
 * ({@code dataStaticCombatMapFeatures}), towns ({@code dataStaticPlaceMapFeatures}), gathering
 * nodes ({@code dataStaticGatheringNodes}) and the loot chests of {@link LootChests}. Immutable
 * once read; read off the game thread together with the {@link ItemIndex}.
 */
public final class Places {

    /** A chest takes the level of the nearest leveled place within this many blocks, else it has none. */
    static final int CHEST_LEVEL_REACH = 400;

    public static final Places EMPTY = new Places(List.of(), Map.of(), Map.of(), List.of());

    /** Caves, grind spots with a known level and towns: named, with a level. */
    private final List<Area> leveled;
    /** Every named place by lower-case name. */
    private final Map<String, Area> named;
    /** Gathering nodes by resource ("COPPER"), in the data's order. */
    private final Map<String, List<Area>> nodes;
    /** Loot chests with the level of the nearest place (-1 when none is near). */
    private final List<Area> chests;

    private Places(List<Area> leveled, Map<String, Area> named, Map<String, List<Area>> nodes, List<Area> chests) {
        this.leveled = leveled;
        this.named = named;
        this.nodes = nodes;
        this.chests = chests;
    }

    /** Reads the map files of {@code cacheDir}; a missing file leaves its part empty. */
    public static Places read(Path cacheDir, List<Area> chests) {
        List<Area> leveled = new ArrayList<>();
        Map<String, Area> named = new HashMap<>();
        JsonElement caves = ItemIndex.readJson(cacheDir, "dataStaticCaveInfo");
        if (caves != null && caves.isJsonArray()) {
            for (JsonElement e : caves.getAsJsonArray()) {
                try {
                    JsonObject c = e.getAsJsonObject();
                    Area a = at(c.getAsJsonObject("location"), str(c, "name"), c.has("requirements") ? c.get("requirements").getAsInt() : -1);
                    add(a, leveled, named);
                } catch (RuntimeException ignored) {
                    // an entry of another shape
                }
            }
        }
        JsonElement combat = ItemIndex.readJson(cacheDir, "dataStaticCombatMapFeatures");
        if (combat != null && combat.isJsonArray()) {
            for (JsonElement e : combat.getAsJsonArray()) {
                try {
                    JsonObject f = e.getAsJsonObject();
                    JsonObject attr = f.getAsJsonObject("attributes");
                    String label = str(attr, "label");
                    int lv = attr.has("level") ? attr.get("level").getAsInt() : -1;
                    String cat = str(f, "categoryId");
                    if (lv < 0 && cat != null && cat.endsWith("grind-spot") && label != null) {
                        Integer known = Rules.spotLevel(label);
                        lv = known == null ? -1 : known;
                    }
                    Area a = at(f.getAsJsonObject("location"), label, lv);
                    boolean bandPlace = cat != null && (cat.endsWith(":cave") || cat.endsWith("grind-spot"));
                    if (bandPlace) {
                        add(a, leveled, named);
                    } else if (label != null) {
                        named.putIfAbsent(key(label), a);
                    }
                } catch (RuntimeException ignored) {
                    // an entry of another shape
                }
            }
        }
        JsonElement towns = ItemIndex.readJson(cacheDir, "dataStaticPlaceMapFeatures");
        if (towns != null && towns.isJsonArray()) {
            for (JsonElement e : towns.getAsJsonArray()) {
                try {
                    JsonObject f = e.getAsJsonObject();
                    int lv = f.has("level") ? f.get("level").getAsInt() : -1;
                    add(at(f.getAsJsonObject("location"), str(f.getAsJsonObject("attributes"), "label"), lv), leveled, named);
                } catch (RuntimeException ignored) {
                    // an entry of another shape
                }
            }
        }
        Map<String, List<Area>> nodes = new HashMap<>();
        JsonElement gather = ItemIndex.readJson(cacheDir, "dataStaticGatheringNodes");
        if (gather != null && gather.isJsonArray()) {
            for (JsonElement e : gather.getAsJsonArray()) {
                try {
                    JsonObject n = e.getAsJsonObject();
                    String res = str(n, "resource");
                    if (res != null) {
                        nodes.computeIfAbsent(res.toUpperCase(Locale.ROOT), k -> new ArrayList<>())
                                .add(new Area(n.get("x").getAsInt(), n.get("y").getAsInt(), n.get("z").getAsInt(), 0,
                                        n.has("level") ? n.get("level").getAsInt() : -1, 0, null));
                    }
                } catch (RuntimeException ignored) {
                    // an entry of another shape
                }
            }
        }
        List<Area> leveledOnly = leveled.stream().filter(a -> a.level() >= 0).toList();
        return new Places(leveledOnly, Collections.unmodifiableMap(named), Collections.unmodifiableMap(nodes),
                levelChests(chests, leveledOnly));
    }

    private static void add(Area a, List<Area> leveled, Map<String, Area> named) {
        if (a.name() == null) {
            return;
        }
        if (named.putIfAbsent(key(a.name()), a) == null) {
            leveled.add(a);
        }
    }

    private static Area at(JsonObject loc, String name, int level) {
        return new Area(loc.get("x").getAsInt(), loc.get("y").getAsInt(), loc.get("z").getAsInt(), 0, level, 0, name);
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT).trim();
    }

    /**
     * Each chest with the level of the nearest leveled place within {@link #CHEST_LEVEL_REACH};
     * chests with no place near (housing islands, quest instances) are dropped.
     */
    static List<Area> levelChests(List<Area> chests, List<Area> leveled) {
        List<Area> out = new ArrayList<>();
        for (Area c : chests) {
            double best = CHEST_LEVEL_REACH;
            int lv = -1;
            for (Area p : leveled) {
                double d = Math.hypot(p.x() - c.x(), p.z() - c.z());
                if (d < best) {
                    best = d;
                    lv = p.level();
                }
            }
            if (lv >= 0) {
                out.add(new Area(c.x(), c.y(), c.z(), 0, lv, c.stars(), null));
            }
        }
        return List.copyOf(out);
    }

    /** Caves, grind spots and towns whose level is in {@code lo..hi}, in the data's order. */
    public List<Area> inBand(int lo, int hi) {
        return leveled.stream().filter(a -> a.level() >= lo && a.level() <= hi).toList();
    }

    /** Loot chests whose (estimated) level is in {@code lo..hi}, in the list's order. */
    public List<Area> chestsInBand(int lo, int hi) {
        return chests.stream().filter(a -> a.level() >= lo && a.level() <= hi).toList();
    }

    /** The named place (cave, dungeon, raid, town, ...) of this name, or null. */
    public Area find(String name) {
        return name == null ? null : named.get(key(name));
    }

    /** Gathering nodes of a resource ("COPPER"), in the data's order; empty when none. */
    public List<Area> nodes(String resource) {
        return resource == null ? List.of() : nodes.getOrDefault(resource.toUpperCase(Locale.ROOT), List.of());
    }

    /** Every loot chest that got a level. */
    public List<Area> chests() {
        return chests;
    }
}
