package io.github.anilken.wynnlabs.search;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.quests.QuestWiki;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every searchable item, read once from Wynntils' downloaded data ({@code wynntils/cache/dataStatic*}:
 * gear, ingredients, materials, tomes, charms, plus the item obtain list and sets), together with
 * the {@link Places} its sources point at, the world's {@link Things} (mobs, places, dungeons,
 * services, quests, ...) and the matcher's prepared names. Immutable; built off the game thread
 * by {@link #startLoading}, read by {@link #get}. A missing gear file gives an empty index whose
 * {@link #missing} says why.
 */
public final class ItemIndex {

    public static final String GEAR = "dataStaticGear";

    private static final ItemIndex LOADING = new ItemIndex(List.of(), List.of(), Map.of(), Places.EMPTY, "loading");
    private static volatile ItemIndex current = LOADING;
    private static volatile boolean started;

    private final List<Entry> entries;
    private final List<Entry> all;
    private final Map<String, Entry> byName;
    private final Map<String, Entry> byKey;
    private final Map<Kind, Integer> counts;
    private final Map<String, List<String>> sets;
    private final Places places;
    private final String missing;
    final ItemMatch.Prepared prepared;

    ItemIndex(List<Entry> entries, List<Entry> things, Map<String, List<String>> sets, Places places, String missing) {
        this.entries = List.copyOf(entries);
        List<Entry> both = new ArrayList<>(this.entries);
        both.addAll(things);
        this.all = List.copyOf(both);
        this.places = places;
        this.missing = missing;
        Map<String, Entry> names = new HashMap<>();
        Map<String, Entry> keys = new HashMap<>();
        Map<Kind, Integer> c = new EnumMap<>(Kind.class);
        for (Entry e : this.all) {
            if (e.item()) {
                names.putIfAbsent(e.name().toLowerCase(Locale.ROOT), e);
            }
            keys.putIfAbsent(e.key().toLowerCase(Locale.ROOT), e);
            c.merge(e.kind(), 1, Integer::sum);
        }
        this.byName = names;
        this.byKey = keys;
        this.counts = Collections.unmodifiableMap(c);
        this.sets = sets;
        this.prepared = ItemMatch.prepare(this.all);
    }

    /** The index now: the loaded one, or an empty one whose {@link #missing} is "loading" or the failure. */
    public static ItemIndex get() {
        return current;
    }

    /**
     * Reads the index on its own thread, once per game session: {@code gameDir/wynntils/cache}
     * and the loot chests (shipped list plus {@code userChests}, which may be missing), with the
     * wiki's mob list as kept on disk; when that list is a week old (and wiki guides are on) it is
     * read again from the wiki afterwards and the index rebuilt with it.
     */
    public static synchronized void startLoading(Path gameDir, Path userChests) {
        if (started) {
            return;
        }
        started = true;
        Thread t = new Thread(() -> {
            Path cache = gameDir.resolve("wynntils/cache");
            try {
                List<Area> chests = LootChests.load(userChests);
                Path wikiDir = QuestWiki.cacheDir();
                current = read(cache, chests, MobWiki.cached(wikiDir));
                if (MobWiki.fresh(wikiDir) || !QuestWiki.on()) {
                    return;
                }
                List<MobWiki.Mob> wiki = MobWiki.load();
                if (!wiki.isEmpty()) {
                    current = read(cache, chests, wiki);
                }
            } catch (Throwable e) {
                if (current.missing() != null) {
                    current = new ItemIndex(List.of(), List.of(), Map.of(), Places.EMPTY, "error: " + e);
                }
            }
        }, "wynnlabs-item-index");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Reads every file now, on the calling thread (the loading thread and tests), without the wiki's mobs. */
    public static ItemIndex read(Path cacheDir, List<Area> chests) {
        return read(cacheDir, chests, List.of());
    }

    /** Reads every file now, with the wiki's mob list (empty: only the mobs the item data names). */
    public static ItemIndex read(Path cacheDir, List<Area> chests, List<MobWiki.Mob> wiki) {
        JsonElement gear = readJson(cacheDir, GEAR);
        if (gear == null || !gear.isJsonObject()) {
            return new ItemIndex(List.of(), List.of(), Map.of(), Places.EMPTY, "no " + GEAR);
        }
        Map<String, String> setOf = new HashMap<>();
        Map<String, List<String>> parts = new HashMap<>();
        JsonElement sets = readJson(cacheDir, "dataStaticSets");
        if (sets != null && sets.isJsonObject()) {
            for (Map.Entry<String, JsonElement> s : sets.getAsJsonObject().entrySet()) {
                JsonObject o = s.getValue().getAsJsonObject();
                if (o.has("parts")) {
                    List<String> names = new ArrayList<>();
                    o.getAsJsonArray("parts").forEach(p -> names.add(p.getAsString()));
                    names.forEach(n -> setOf.putIfAbsent(n, s.getKey()));
                    parts.put(s.getKey(), List.copyOf(names));
                }
            }
        }
        JsonElement obtainJson = readJson(cacheDir, "dataStaticItemObtainV2");
        JsonObject obtain = obtainJson != null && obtainJson.isJsonObject() ? obtainJson.getAsJsonObject() : new JsonObject();
        List<Entry> out = new ArrayList<>();
        addAll(out, gear, Kind.GEAR, obtain, setOf);
        addAll(out, readJson(cacheDir, "dataStaticIngredients"), Kind.INGREDIENT, obtain, setOf);
        addAll(out, readJson(cacheDir, "dataStaticMaterials"), Kind.MATERIAL, obtain, setOf);
        addAll(out, readJson(cacheDir, "dataStaticTomes"), Kind.TOME, obtain, setOf);
        addAll(out, readJson(cacheDir, "dataStaticCharms"), Kind.CHARM, obtain, setOf);
        Places places = Places.read(cacheDir, chests);
        return new ItemIndex(out, Things.read(cacheDir, out, places, wiki), parts, places, null);
    }

    /** A cache file as JSON, or null when it is missing or unreadable. */
    static JsonElement readJson(Path dir, String name) {
        Path f = dir.resolve(name);
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.out.println("[WynnLabs] " + name + " unreadable: " + e);
            return null;
        }
    }

    private static void addAll(List<Entry> out, JsonElement file, Kind kind, JsonObject obtain, Map<String, String> setOf) {
        if (file == null || !file.isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> e : file.getAsJsonObject().entrySet()) {
            try {
                out.add(entry(e.getKey(), e.getValue().getAsJsonObject(), kind, obtain.get(e.getKey()), setOf.get(e.getKey())));
            } catch (RuntimeException ex) {
                // one item of another shape: skip it, keep the rest
            }
        }
    }

    static Entry entry(String name, JsonObject o, Kind kind, JsonElement obtain, String set) {
        JsonObject req = o.has("requirements") && o.get("requirements").isJsonObject() ? o.getAsJsonObject("requirements") : new JsonObject();
        int level = req.has("level") ? req.get("level").getAsInt() : 0;
        Map<String, Integer> skills = new LinkedHashMap<>();
        for (String sk : new String[] {"strength", "dexterity", "intelligence", "defence", "agility"}) {
            if (req.has(sk) && req.get(sk).isJsonPrimitive() && req.get(sk).getAsInt() != 0) {
                skills.put(sk, req.get(sk).getAsInt());
            }
        }
        Klass klass = Klass.NONE;
        String cls = str(req, "classRequirement");
        if (cls != null) {
            try {
                klass = Klass.valueOf(cls.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // a class this mod does not know
            }
        }
        String tier = str(o, "tier");
        int stars = -1;
        if (kind == Kind.INGREDIENT && tier != null && tier.startsWith("TIER_")) {
            stars = Integer.parseInt(tier.substring(5));
            tier = null;
        } else if (tier != null) {
            tier = tier.toLowerCase(Locale.ROOT);
        }
        String type = kind == Kind.INGREDIENT ? "ingredient" : str(o, "subType");
        List<String> professions = new ArrayList<>();
        if (kind == Kind.INGREDIENT && req.has("skills")) {
            req.getAsJsonArray("skills").forEach(s -> professions.add(s.getAsString()));
        } else if (kind == Kind.MATERIAL && type != null) {
            professions.add(type);
        }
        return new Entry(name, kind, type, level, tier, stars, klass, Collections.unmodifiableMap(skills), str(req, "quest"),
                str(o, "restriction"), kind == Kind.GEAR ? str(o, "dropRestriction") : null, icon(o),
                origins(o, obtain), stats(o), List.copyOf(professions), modifiers(o), set);
    }

    private static Entry.Icon icon(JsonObject o) {
        if (!o.has("icon") || !o.get("icon").isJsonObject()) {
            return new Entry.Icon(null, Float.NaN, null, null);
        }
        JsonObject icon = o.getAsJsonObject("icon");
        JsonElement v = icon.get("value");
        if (v != null && v.isJsonPrimitive()) {
            return new Entry.Icon("minecraft:player_head", Float.NaN, null, v.getAsString());
        }
        if (v == null || !v.isJsonObject()) {
            return new Entry.Icon(null, Float.NaN, null, null);
        }
        JsonObject val = v.getAsJsonObject();
        float model = Float.NaN;
        JsonElement cmd = val.get("customModelData");
        if (cmd != null && cmd.isJsonObject() && cmd.getAsJsonObject().has("rangeDispatch")) {
            JsonArray rd = cmd.getAsJsonObject().getAsJsonArray("rangeDispatch");
            if (!rd.isEmpty()) {
                model = rd.get(0).getAsFloat();
            }
        }
        return new Entry.Icon(str(val, "id"), model, str(val, "name"), null);
    }

    /** The data's sources: dropMeta, then an ingredient's droppedBy, then the item obtain list (repeats left out). */
    private static List<Entry.Origin> origins(JsonObject o, JsonElement obtain) {
        List<Entry.Origin> out = new ArrayList<>();
        if (o.has("dropMeta") && o.get("dropMeta").isJsonObject()) {
            JsonObject dm = o.getAsJsonObject("dropMeta");
            List<Area> areas = new ArrayList<>();
            if (dm.has("coordinates") && dm.get("coordinates").isJsonArray()) {
                Area a = area(dm.getAsJsonArray("coordinates"));
                if (a != null) {
                    areas.add(a);
                }
            }
            JsonElement type = dm.get("type");
            List<String> types = new ArrayList<>();
            if (type != null && type.isJsonArray()) {
                type.getAsJsonArray().forEach(t -> types.add(t.getAsString()));
            } else if (type != null && type.isJsonPrimitive()) {
                types.add(type.getAsString());
            }
            for (String t : types) {
                out.add(new Entry.Origin(t, str(dm, "name"), List.copyOf(areas)));
            }
        }
        if (o.has("droppedBy") && o.get("droppedBy").isJsonArray()) {
            for (JsonElement d : o.getAsJsonArray("droppedBy")) {
                JsonObject mob = d.getAsJsonObject();
                List<Area> areas = new ArrayList<>();
                JsonElement c = mob.get("coords");
                if (c != null && c.isJsonArray() && !c.getAsJsonArray().isEmpty()) {
                    JsonArray arr = c.getAsJsonArray();
                    if (arr.get(0).isJsonArray()) {
                        arr.forEach(x -> {
                            Area a = area(x.getAsJsonArray());
                            if (a != null) {
                                areas.add(a);
                            }
                        });
                    } else {
                        Area a = area(arr);
                        if (a != null) {
                            areas.add(a);
                        }
                    }
                }
                out.add(new Entry.Origin("mob", str(mob, "name"), List.copyOf(areas)));
            }
        }
        if (obtain != null && obtain.isJsonArray()) {
            for (JsonElement x : obtain.getAsJsonArray()) {
                JsonObject ob = x.getAsJsonObject();
                String t = str(ob, "type");
                String n = str(ob, "name");
                boolean seen = out.stream().anyMatch(e -> e.type().equals(t) && java.util.Objects.equals(e.name(), n));
                if (t != null && !seen) {
                    out.add(new Entry.Origin(t, n, List.of()));
                }
            }
        }
        return List.copyOf(out);
    }

    /** [x, y, z] or [x, y, z, r]; null for the data's "nowhere" (99999) or a short list. */
    private static Area area(JsonArray c) {
        if (c.size() < 3) {
            return null;
        }
        int x = c.get(0).getAsInt();
        int y = c.get(1).getAsInt();
        int z = c.get(2).getAsInt();
        if (Math.abs(x) > 50_000 || Math.abs(z) > 50_000) {
            return null;
        }
        return new Area(x, y, z, c.size() > 3 ? c.get(3).getAsInt() : 0, -1, 0, null);
    }

    private static List<Entry.Stat> stats(JsonObject o) {
        if (!o.has("identifications") || !o.get("identifications").isJsonObject()) {
            return List.of();
        }
        List<Entry.Stat> out = new ArrayList<>();
        for (Map.Entry<String, JsonElement> s : o.getAsJsonObject("identifications").entrySet()) {
            JsonElement v = s.getValue();
            if (v.isJsonPrimitive()) {
                int n = v.getAsInt();
                out.add(new Entry.Stat(s.getKey(), n, n));
            } else if (v.isJsonObject() && v.getAsJsonObject().has("min") && v.getAsJsonObject().has("max")) {
                out.add(new Entry.Stat(s.getKey(), v.getAsJsonObject().get("min").getAsInt(), v.getAsJsonObject().get("max").getAsInt()));
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, Integer> modifiers(JsonObject o) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String group : new String[] {"ingredientPositionModifiers", "itemOnlyIDs"}) {
            if (o.has(group) && o.get(group).isJsonObject()) {
                for (Map.Entry<String, JsonElement> m : o.getAsJsonObject(group).entrySet()) {
                    if (m.getValue().isJsonPrimitive() && m.getValue().getAsInt() != 0) {
                        out.put(m.getKey(), m.getValue().getAsInt());
                    }
                }
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    // ---- reading the index ----

    /** The items only (gear to charm), in the data's order. */
    public List<Entry> entries() {
        return entries;
    }

    /** Everything searchable: the items, then the world's things. */
    public List<Entry> all() {
        return all;
    }

    /** The item of this name (any case), or null. */
    public Entry byName(String name) {
        return name == null ? null : byName.get(name.toLowerCase(Locale.ROOT));
    }

    /** The entry of this {@link Entry#key} (any case; an item's key is its name), or null. */
    public Entry byKey(String key) {
        return key == null ? null : byKey.get(key.toLowerCase(Locale.ROOT));
    }

    /** How many entries of each kind (kinds with none are absent). */
    public Map<Kind, Integer> counts() {
        return counts;
    }

    /** Every piece of the item's set (itself included), in the set data's order; empty when it has no set. */
    public List<String> setPieces(Entry e) {
        return e.set() == null ? List.of() : sets.getOrDefault(e.set(), List.of());
    }

    public Places places() {
        return places;
    }

    /** Null when loaded; else why the index is empty ("loading", "no dataStaticGear", "error: ..."). */
    public String missing() {
        return missing;
    }
}
