package io.github.anilken.wynnlabs.quests;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.anilken.wynnlabs.core.Dirs;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.game.GameState;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wynncraft's secret discoveries as a step-by-step guide: the shipped data
 * ({@code assets/wynnlabs/discoveries/en.json} and {@code tr.json}, from the wiki, CC BY-NC-SA),
 * which ones this character found (Wynntils' content book, or a tick by hand kept in
 * {@code characters/<id>/discoveries.json}), the order and filters the player picked (kept in
 * settings), and whether the wiki's steps changed since the data shipped.
 *
 * <p>With wiki guides on, opening a discovery re-reads its page through {@link QuestWiki}'s
 * weekly cache and cuts it into steps the way the build tool did ({@link DiscoveryWiki}). The same
 * hash means the shipped steps still hold (in the player's language); another hash means the
 * wiki changed, and its current English steps are shown with a note.
 */
public final class Discoveries {

    /** A step; {@code at} is {x, y, z} with y = {@link DiscoveryWiki#NO_Y} when the page gives none, or null. */
    public record Step(String text, int[] at) {}

    public record Entry(String id, String name, String wiki, int x, int y, int z, int minLevel, String region,
            List<String> requires, String lore, List<Step> steps, String hash) {}

    /** The steps to show and whether they are the wiki's newer English ones. */
    public record Guide(List<Step> steps, boolean newer) {}

    /** The orders the list can take; the first is the default. */
    public enum Sort { LEVEL_DISTANCE, DISTANCE, REGION, LEVEL, NAME }

    /** Which entries the list shows by done state; the first is the default. */
    public enum Show { NOT_DONE, DONE, ALL }

    public static final String[] SORT_KEYS = {"wynnlabs.discoveries.sort_level_distance", "wynnlabs.discoveries.sort_distance",
        "wynnlabs.discoveries.sort_region", "wynnlabs.discoveries.sort_level", "wynnlabs.discoveries.sort_name"};
    public static final String[] SHOW_KEYS = {"wynnlabs.discoveries.show_not_done", "wynnlabs.discoveries.show_done",
        "wynnlabs.discoveries.show_all"};

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static List<Entry> english;
    private static List<Entry> turkish;
    private static final Map<String, Set<String>> TICKS = new ConcurrentHashMap<>();
    private static final Map<String, List<DiscoveryWiki.Step>> LIVE = new ConcurrentHashMap<>();
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();

    private Discoveries() {}

    // ---- the shipped data ----

    /** Both files, read once from the jar; tr.json falls back to the English when it is missing. */
    static synchronized List<Entry> entries(boolean inTurkish) {
        if (english == null) {
            english = read("en.json");
            List<Entry> tr = read("tr.json");
            turkish = tr.size() == english.size() ? tr : english;
        }
        return inTurkish ? turkish : english;
    }

    /** The entries in the menu's language: Turkish when the mod's text is Turkish. */
    public static List<Entry> all() {
        return entries(turkish());
    }

    static boolean turkish() {
        return "tr".equals(L.t("wynnlabs.discoveries.lang"));
    }

    /** One entry in the menu's language, by id; null when there is none. */
    public static Entry get(String id) {
        for (Entry e : all()) {
            if (e.id().equals(id)) {
                return e;
            }
        }
        return null;
    }

    /** The English entry with this id (requirements are judged by their English text). */
    public static Entry englishOf(String id) {
        for (Entry e : entries(false)) {
            if (e.id().equals(id)) {
                return e;
            }
        }
        return null;
    }

    private static List<Entry> read(String file) {
        List<Entry> out = new ArrayList<>();
        try (var in = Discoveries.class.getResourceAsStream("/assets/wynnlabs/discoveries/" + file)) {
            if (in == null) {
                return out;
            }
            for (JsonElement el : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray()) {
                JsonObject o = el.getAsJsonObject();
                List<String> requires = new ArrayList<>();
                o.getAsJsonArray("requires").forEach(r -> requires.add(r.getAsString()));
                List<Step> steps = new ArrayList<>();
                for (JsonElement s : o.getAsJsonArray("steps")) {
                    JsonObject so = s.getAsJsonObject();
                    steps.add(new Step(so.get("text").getAsString(), at(so.get("at"))));
                }
                out.add(new Entry(o.get("id").getAsString(), o.get("name").getAsString(), o.get("wiki").getAsString(),
                        num(o, "x"), num(o, "y"), num(o, "z"), num(o, "minLevel"), o.get("region").getAsString(),
                        requires, o.get("lore").getAsString(), steps, o.get("hash").getAsString()));
            }
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not read discoveries/" + file + ": " + e);
        }
        return out;
    }

    private static int num(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v == null || v.isJsonNull() ? 0 : v.getAsInt();
    }

    private static int[] at(JsonElement v) {
        if (v == null || v.isJsonNull()) {
            return null;
        }
        JsonArray a = v.getAsJsonArray();
        return new int[] {a.get(0).getAsInt(), a.get(1).isJsonNull() ? DiscoveryWiki.NO_Y : a.get(1).getAsInt(), a.get(2).getAsInt()};
    }

    // ---- done ----

    /** Lower case, letters and digits only: "Bak'al's Destruction 1" and "Bak’al’s destruction 1" match. */
    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** The names Wynntils lists as found for this character, as {@link #key}s; empty until it read the content book. */
    public static Set<String> foundInGame() {
        Set<String> out = new java.util.HashSet<>();
        for (String n : GameState.current().completedDiscoveries()) {
            out.add(key(n));
        }
        return out;
    }

    /** Done for this character: Wynntils found it, or the player ticked it. */
    public static boolean done(Entry e, Set<String> found, String character) {
        return found.contains(key(e.name())) || found.contains(key(e.id())) || ticked(character, e.id());
    }

    static boolean done(Entry e) {
        return done(e, foundInGame(), GameState.current().characterId());
    }

    /** Whether Wynntils found it (a hand tick cannot take that back). */
    public static boolean foundByWynntils(Entry e, Set<String> found) {
        return found.contains(key(e.name())) || found.contains(key(e.id()));
    }

    private static Path tickFile(String character) {
        Path root = Dirs.config();
        return (character == null || character.isEmpty() ? root : root.resolve("characters").resolve(character))
                .resolve("discoveries.json");
    }

    private static Set<String> ticks(String character) {
        String c = character == null ? "" : character;
        return TICKS.computeIfAbsent(c, k -> {
            Set<String> s = ConcurrentHashMap.newKeySet();
            try {
                Path f = tickFile(k);
                if (Files.exists(f)) {
                    JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                    o.getAsJsonArray("done").forEach(v -> s.add(v.getAsString()));
                }
            } catch (Exception e) {
                System.out.println("[WynnLabs] could not read discoveries.json: " + e);
            }
            return s;
        });
    }

    public static boolean ticked(String character, String id) {
        return ticks(character).contains(id);
    }

    /** Ticks or unticks a discovery by hand for this character, and saves it. */
    public static void tick(String character, String id, boolean on) {
        Set<String> s = ticks(character);
        if (on ? !s.add(id) : !s.remove(id)) {
            return;
        }
        try {
            JsonObject o = new JsonObject();
            JsonArray a = new JsonArray();
            s.stream().sorted().forEach(a::add);
            o.add("done", a);
            Path f = tickFile(character);
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not save discoveries.json: " + e);
        }
    }

    // ---- requirements ----

    private static final Pattern PROFESSION = Pattern.compile("^([A-Za-z]+) Lv\\. ?(\\d+)\\+?$");

    /**
     * Whether an English requirement is met, when that can be told: a quest (finished, or started
     * for "Started …" and "Partial completion of …"), a profession level, or "All other … found"
     * (every other discovery of the region done). Null when it cannot be told (an item, daytime).
     */
    public static Boolean met(Entry en, String requirement, List<QuestBook.Entry> quests, Set<String> found, String character) {
        String low = requirement.toLowerCase(Locale.ROOT);
        if (low.startsWith("all other ")) {
            for (Entry o : entries(false)) {
                if (!o.id().equals(en.id()) && o.region().equals(en.region()) && !done(o, found, character)) {
                    return false;
                }
            }
            return true;
        }
        Matcher p = PROFESSION.matcher(requirement.trim());
        if (p.matches()) {
            try {
                return GameState.current().professionLevel(p.group(1).toUpperCase(Locale.ROOT)) >= Integer.parseInt(p.group(2));
            } catch (Exception e) {
                return null;
            }
        }
        QuestBook.Entry best = null;
        for (QuestBook.Entry q : quests) {
            if (q.name() != null && low.contains(q.name().toLowerCase(Locale.ROOT))
                    && (best == null || q.name().length() > best.name().length())) {
                best = q;
            }
        }
        if (best == null) {
            return null;
        }
        boolean partly = low.startsWith("started") || low.startsWith("partial");
        return "COMPLETED".equals(best.status()) || partly && "STARTED".equals(best.status());
    }

    // ---- order and filters ----

    public static Sort sort() {
        Sort[] all = Sort.values();
        return all[Math.floorMod(Settings.number("discoverySort", 0), all.length)];
    }

    public static Show show() {
        Show[] all = Show.values();
        return all[Math.floorMod(Settings.number("discoveryShow", 0), all.length)];
    }

    public static boolean withinLevel() {
        return Settings.number("discoveryWithinLevel", 0) == 1;
    }

    public static double distance(Entry e, double px, double pz) {
        return Math.hypot(e.x() - px, e.z() - pz);
    }

    /**
     * The entries in an order: level then distance, distance, region (regions by their nearest
     * discovery, nearest first inside one), level, or name.
     */
    public static List<Entry> sorted(List<Entry> entries, Sort sort, double px, double pz) {
        Comparator<Entry> byDistance = Comparator.comparingDouble(e -> distance(e, px, pz));
        Comparator<Entry> byName = Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT));
        Comparator<Entry> order = switch (sort) {
            case LEVEL_DISTANCE -> Comparator.comparingInt(Entry::minLevel).thenComparing(byDistance);
            case DISTANCE -> byDistance;
            case LEVEL -> Comparator.comparingInt(Entry::minLevel).thenComparing(byName);
            case NAME -> byName;
            case REGION -> {
                Map<String, Double> nearest = new HashMap<>();
                for (Entry e : entries) {
                    nearest.merge(e.region(), distance(e, px, pz), Math::min);
                }
                yield Comparator.<Entry>comparingDouble(e -> nearest.get(e.region())).thenComparing(Entry::region)
                        .thenComparing(byDistance);
            }
        };
        List<Entry> out = new ArrayList<>(entries);
        out.sort(order.thenComparing(byName));
        return out;
    }

    /** Whether the filters let an entry through. */
    public static boolean shown(Entry e, boolean done, Show show, boolean withinLevel, int level) {
        boolean byDone = switch (show) {
            case NOT_DONE -> !done;
            case DONE -> done;
            case ALL -> true;
        };
        return byDone && (!withinLevel || e.minLevel() <= level);
    }

    /** The list as the player set it up, in the menu's language. */
    static List<Entry> view() {
        GameState gs = GameState.current();
        double[] pos = gs.position();
        Set<String> found = foundInGame();
        String character = gs.characterId();
        Show show = show();
        boolean within = withinLevel();
        int level = gs.level();
        List<Entry> out = new ArrayList<>();
        for (Entry e : sorted(all(), sort(), pos[0], pos[1])) {
            if (shown(e, done(e, found, character), show, within, level)) {
                out.add(e);
            }
        }
        return out;
    }

    // ---- live steps ----

    /**
     * The steps to show for an entry: the shipped ones while the live page cuts into the same
     * steps (or is not known), else the live English steps, marked newer.
     */
    static Guide guide(Entry shown, List<DiscoveryWiki.Step> live) {
        if (live == null || live.isEmpty() || DiscoveryWiki.hash(live).equals(shown.hash())) {
            return new Guide(shown.steps(), false);
        }
        List<Step> steps = new ArrayList<>();
        for (DiscoveryWiki.Step s : live) {
            steps.add(new Step(s.text(), s.at()));
        }
        return new Guide(steps, true);
    }

    /** The guide for an entry, reading its page in the background when wiki guides are on. */
    public static Guide guide(Entry shown) {
        if (!QuestWiki.on()) {
            return new Guide(shown.steps(), false);
        }
        refresh(shown.id());
        return guide(shown, LIVE.get(shown.id()));
    }

    /** Starts reading the page (weekly disk cache, then the wiki) unless it is known, loading or failed lately. */
    private static void refresh(String id) {
        if (LIVE.containsKey(id)) {
            return;
        }
        Long failed = FAILED.get(id);
        if (failed != null && System.currentTimeMillis() - failed < 5 * 60_000L) {
            return;
        }
        if (LOADING.add(id)) {
            CompletableFuture.runAsync(() -> {
                try {
                    String text = QuestWiki.page(id);
                    if (text == null) {
                        FAILED.put(id, System.currentTimeMillis());
                    } else {
                        LIVE.put(id, DiscoveryWiki.steps(text));
                    }
                } catch (Exception e) {
                    FAILED.put(id, System.currentTimeMillis());
                } finally {
                    LOADING.remove(id);
                }
            });
        }
    }

    // ---- pins ----

    /** Points and labels ("<name>: step n") for the located steps from {@code from} on; the entry's spot when none. */
    public static void pins(Entry e, List<Step> steps, int from, List<int[]> points, List<String> labels) {
        for (int i = Math.max(0, from); i < steps.size(); i++) {
            int[] at = steps.get(i).at();
            if (at != null) {
                points.add(new int[] {at[0], at[1] == DiscoveryWiki.NO_Y ? e.y() : at[1], at[2]});
                labels.add(L.t("wynnlabs.discoveries.pin", e.name(), i + 1));
            }
        }
        if (points.isEmpty()) {
            points.add(new int[] {e.x(), e.y(), e.z()});
            labels.add(e.name());
        }
    }

    // ---- API ----

    /** GET /discoveries: every entry in the chosen order, with done, level, distance and requirements. */
    public static JsonObject json() {
        GameState gs = GameState.current();
        double[] pos = gs.position();
        Set<String> found = foundInGame();
        String character = gs.characterId();
        List<QuestBook.Entry> quests = gs.quests();
        Show show = show();
        boolean within = withinLevel();
        int level = gs.level();
        JsonObject o = new JsonObject();
        o.addProperty("language", turkish() ? "tr" : "en");
        o.addProperty("sort", sort().name());
        o.addProperty("show", show.name());
        o.addProperty("withinLevel", within);
        o.addProperty("contentBookRead", !found.isEmpty() || DiscoveryBook.seen());
        o.addProperty("licence", "CC BY-NC-SA 3.0, from wynncraft.wiki.gg (see each entry's wiki link)");
        JsonArray list = new JsonArray();
        int doneCount = 0;
        for (Entry e : sorted(all(), sort(), pos[0], pos[1])) {
            Entry en = englishOf(e.id());
            boolean done = done(e, found, character);
            doneCount += done ? 1 : 0;
            JsonObject d = new JsonObject();
            d.addProperty("id", e.id());
            d.addProperty("name", e.name());
            d.addProperty("region", e.region());
            d.addProperty("minLevel", e.minLevel());
            d.addProperty("x", e.x());
            d.addProperty("y", e.y());
            d.addProperty("z", e.z());
            d.addProperty("distance", Math.round(distance(e, pos[0], pos[1])));
            d.addProperty("done", done);
            d.addProperty("doneBy", foundByWynntils(e, found) ? "wynntils" : done ? "hand" : null);
            d.addProperty("shown", shown(e, done, show, within, level));
            JsonArray req = new JsonArray();
            for (int i = 0; i < e.requires().size(); i++) {
                JsonObject r = new JsonObject();
                r.addProperty("text", e.requires().get(i));
                Boolean met = en == null || i >= en.requires().size() ? null
                        : met(en, en.requires().get(i), quests, found, character);
                r.addProperty("met", met);
                req.add(r);
            }
            d.add("requires", req);
            d.addProperty("steps", e.steps().size());
            d.addProperty("wiki", e.wiki());
            list.add(d);
        }
        o.addProperty("count", list.size());
        o.addProperty("done", doneCount);
        o.add("discoveries", list);
        return o;
    }
}
