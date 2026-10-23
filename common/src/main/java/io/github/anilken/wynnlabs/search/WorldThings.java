package io.github.anilken.wynnlabs.search;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.anilken.wynnlabs.core.Rules;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The world's places for the search, from Wynntils' cached map data: towns and provinces
 * ({@code dataStaticPlaceMapFeatures}, else {@code dataStaticPlaces}), content such as dungeons,
 * raids, caves, boss altars and grind spots ({@code dataStaticCombatMapFeatures} plus what only
 * {@code dataStaticCombatLocations} has), one entry per service type
 * ({@code dataStaticServiceMapFeatures}: "Blacksmith" with every blacksmith, each named after the
 * nearest town) and one per gathering resource (the {@link Places}' nodes). Pure apart from
 * reading the files; a missing file leaves its part empty.
 */
final class WorldThings {

    /** A service is named after the nearest town within this many blocks. */
    static final int TOWN_REACH = 600;

    private WorldThings() {}

    /** Towns, cities and provinces: type "city", "town" or "province". */
    static List<Entry> places(Path cacheDir) {
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JsonElement features = ItemIndex.readJson(cacheDir, "dataStaticPlaceMapFeatures");
        if (features != null && features.isJsonArray()) {
            for (JsonElement el : features.getAsJsonArray()) {
                try {
                    JsonObject f = el.getAsJsonObject();
                    String label = str(f.getAsJsonObject("attributes"), "label");
                    String cat = str(f, "categoryId");
                    String type = cat == null ? "town" : cat.endsWith(":city") ? "city" : cat.endsWith(":province") ? "province" : "town";
                    int lv = f.has("level") ? f.get("level").getAsInt() : 0;
                    add(out, seen, label, Kind.PLACE, type, lv, at(f.getAsJsonObject("location"), label, lv));
                } catch (RuntimeException ignored) {
                    // an entry of another shape
                }
            }
        }
        JsonElement labels = ItemIndex.readJson(cacheDir, "dataStaticPlaces");
        if (labels != null && labels.isJsonObject() && labels.getAsJsonObject().has("labels")) {
            for (JsonElement el : labels.getAsJsonObject().getAsJsonArray("labels")) {
                try {
                    JsonObject l = el.getAsJsonObject();
                    int layer = l.has("layer") ? l.get("layer").getAsInt() : 3;
                    String type = layer == 1 ? "province" : layer == 2 ? "city" : "town";
                    int lv = l.has("level") && l.get("level").getAsString().matches("\\d+") ? l.get("level").getAsInt() : 0;
                    String name = str(l, "name");
                    add(out, seen, name, Kind.PLACE, type, lv,
                            new Area(l.get("x").getAsInt(), 0, l.get("z").getAsInt(), 0, lv, 0, name));
                } catch (RuntimeException ignored) {
                    // an entry of another shape
                }
            }
        }
        return out;
    }

    /**
     * Dungeons, raids, caves, altars, grind spots, lootrun camps, time trials and shrines, each
     * with its level and place, and the items known to come from it: the items whose data names
     * the place, and what the wiki's mobs of that place drop.
     */
    static List<Entry> content(Path cacheDir, List<Entry> items, List<MobWiki.Mob> wiki) {
        Map<String, List<String>> fromPlace = new HashMap<>();
        Set<String> itemNames = new HashSet<>();
        for (Entry e : items) {
            itemNames.add(e.name().toLowerCase(Locale.ROOT));
            for (Entry.Origin o : e.origins()) {
                if (o.name() != null && !o.type().equals("mob")) {
                    List<String> l = fromPlace.computeIfAbsent(o.name().toLowerCase(Locale.ROOT), k -> new ArrayList<>());
                    if (!l.contains(e.name())) {
                        l.add(e.name());
                    }
                }
            }
        }
        for (MobWiki.Mob m : wiki) {
            if (m.region() == null) {
                continue;
            }
            List<String> l = fromPlace.computeIfAbsent(m.region().toLowerCase(Locale.ROOT), k -> new ArrayList<>());
            m.drops().stream().filter(d -> itemNames.contains(d.toLowerCase(Locale.ROOT)) && !l.contains(d)).forEach(l::add);
        }
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JsonElement combat = ItemIndex.readJson(cacheDir, "dataStaticCombatMapFeatures");
        if (combat != null && combat.isJsonArray()) {
            for (JsonElement el : combat.getAsJsonArray()) {
                try {
                    JsonObject f = el.getAsJsonObject();
                    JsonObject attr = f.getAsJsonObject("attributes");
                    String label = str(attr, "label");
                    String cat = str(f, "categoryId");
                    String type = cat == null ? "other" : cat.replace("wynntils:content:", "");
                    if (type.equals("dungeon:corrupted")) {
                        type = "corrupted-dungeon";
                    }
                    int lv = attr.has("level") ? attr.get("level").getAsInt() : 0;
                    if (lv == 0 && type.equals("grind-spot") && label != null) {
                        Integer known = Rules.spotLevel(label);
                        lv = known == null ? 0 : known;
                    }
                    addContent(out, seen, label, type, lv, at(f.getAsJsonObject("location"), label, lv), fromPlace);
                } catch (RuntimeException ignored) {
                    // an entry of another shape
                }
            }
        }
        JsonElement locations = ItemIndex.readJson(cacheDir, "dataStaticCombatLocations");
        if (locations != null && locations.isJsonArray()) {
            for (JsonElement el : locations.getAsJsonArray()) {
                try {
                    JsonObject group = el.getAsJsonObject();
                    String type = groupType(str(group, "type"));
                    for (JsonElement l : group.getAsJsonArray("locations")) {
                        JsonObject o = l.getAsJsonObject();
                        String name = str(o, "name");
                        if (name == null || name.equalsIgnoreCase(type)) {
                            continue;                   // the data's unnamed "Cave"s
                        }
                        addContent(out, seen, name, type, 0, at(o.getAsJsonObject("coordinates"), name, -1), fromPlace);
                    }
                } catch (RuntimeException ignored) {
                    // an entry of another shape
                }
            }
        }
        return out;
    }

    /** "Boss Altars" → "boss-altar", as the map features name their kinds. */
    static String groupType(String group) {
        if (group == null) {
            return "other";
        }
        return switch (group.toLowerCase(Locale.ROOT)) {
            case "raids" -> "raid";
            case "dungeons" -> "dungeon";
            case "rune shrines" -> "shrine";
            case "boss altars" -> "boss-altar";
            case "caves" -> "cave";
            case "grind spots" -> "grind-spot";
            case "lootrun camps" -> "lootrun-camp";
            case "time trials" -> "time-trial";
            default -> "other";
        };
    }

    private static void addContent(List<Entry> out, Set<String> seen, String label, String type, int lv, Area a,
            Map<String, List<String>> fromPlace) {
        if (label == null || !seen.add(label.toLowerCase(Locale.ROOT))) {
            return;
        }
        List<Entry.Origin> links = new ArrayList<>();
        links.add(new Entry.Origin(Entry.AT, label, List.of(a)));
        for (String item : fromPlace.getOrDefault(label.toLowerCase(Locale.ROOT), List.of())) {
            links.add(new Entry.Origin(Entry.ITEM, item, List.of()));
        }
        out.add(Entry.thing(label, Kind.CONTENT, type, lv, links));
    }

    /**
     * One entry per service type, named in plain English ("Blacksmith", "Woodworking station",
     * "Emerald merchant"), with every place of that service, each named after the nearest town.
     */
    static List<Entry> services(Path cacheDir, List<Entry> towns) {
        JsonElement file = ItemIndex.readJson(cacheDir, "dataStaticServiceMapFeatures");
        if (file == null || !file.isJsonArray()) {
            return List.of();
        }
        Map<String, List<Area>> byType = new LinkedHashMap<>();
        for (JsonElement el : file.getAsJsonArray()) {
            try {
                JsonObject f = el.getAsJsonObject();
                String cat = str(f, "categoryId");
                if (cat == null) {
                    continue;
                }
                JsonObject loc = f.getAsJsonObject("location");
                int x = loc.get("x").getAsInt();
                int z = loc.get("z").getAsInt();
                byType.computeIfAbsent(cat, k -> new ArrayList<>())
                        .add(new Area(x, loc.get("y").getAsInt(), z, 0, -1, 0, nearestTown(x, z, towns)));
            } catch (RuntimeException ignored) {
                // an entry of another shape
            }
        }
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, List<Area>> t : byType.entrySet()) {
            String type = t.getKey().replace("wynntils:service:", "");
            out.add(Entry.thing(serviceName(type), Kind.SERVICE, type, 0,
                    List.of(new Entry.Origin(Entry.AT, null, List.copyOf(t.getValue())))));
        }
        return out;
    }

    /** "profession:woodworking" → "Woodworking station", "merchant:emerald" → "Emerald merchant", "fast-travel" → "Fast travel". */
    static String serviceName(String type) {
        if (type.startsWith("profession:")) {
            return cap(type.substring(11)) + " station";
        }
        if (type.startsWith("merchant:")) {
            return cap(type.substring(9).replace('-', ' ')) + " merchant";
        }
        return switch (type) {
            case "trade-market" -> "Trade Market";
            case "seaskipper" -> "Seaskipper";
            default -> cap(type.replace('-', ' ').replace(':', ' '));
        };
    }

    /** The name of the nearest town or city within {@link #TOWN_REACH} blocks, or null. */
    static String nearestTown(int x, int z, List<Entry> towns) {
        double best = TOWN_REACH;
        String name = null;
        for (Entry t : towns) {
            if ("province".equals(t.type())) {
                continue;
            }
            for (Area a : t.places()) {
                double d = Math.hypot(a.x() - x, a.z() - z);
                if (d < best) {
                    best = d;
                    name = t.name();
                }
            }
        }
        return name;
    }

    /**
     * One entry per gathering resource ("Copper"): its lowest node level, the profession of its
     * materials (the type), every node, and the materials made from it.
     */
    static List<Entry> resources(Places places, List<Entry> items) {
        Map<String, List<Entry>> materials = new HashMap<>();
        for (Entry e : items) {
            if (e.kind() == Kind.MATERIAL) {
                materials.computeIfAbsent(Sources.resource(e.name()), k -> new ArrayList<>()).add(e);
            }
        }
        List<Entry> out = new ArrayList<>();
        for (String res : places.resources()) {
            List<Area> nodes = places.nodes(res);
            int lv = nodes.stream().mapToInt(Area::level).filter(l -> l > 0).min().orElse(0);
            String profession = null;
            List<Entry.Origin> links = new ArrayList<>();
            links.add(new Entry.Origin(Entry.AT, null, nodes));
            for (Entry e : materials.getOrDefault(res, List.of())) {
                links.add(new Entry.Origin(Entry.ITEM, e.name(), List.of()));
                profession = profession == null ? e.type() : profession;
            }
            out.add(Entry.thing(cap(res.toLowerCase(Locale.ROOT)), Kind.RESOURCE, profession, lv, links));
        }
        return out;
    }

    private static void add(List<Entry> out, Set<String> seen, String name, Kind kind, String type, int lv, Area a) {
        if (name == null || name.isBlank() || !seen.add(name.toLowerCase(Locale.ROOT))) {
            return;
        }
        out.add(Entry.thing(name, kind, type, lv, List.of(new Entry.Origin(Entry.AT, name, List.of(a)))));
    }

    private static Area at(JsonObject loc, String name, int level) {
        return new Area(loc.get("x").getAsInt(), loc.has("y") ? loc.get("y").getAsInt() : 0, loc.get("z").getAsInt(), 0, level, 0, name);
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
