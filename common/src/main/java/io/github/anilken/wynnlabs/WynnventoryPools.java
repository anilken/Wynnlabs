package io.github.anilken.wynnlabs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.anilken.wynnlabs.model.PoolItem;
import io.github.anilken.wynnlabs.model.PoolRegion;
import io.github.anilken.wynnlabs.model.Tier;
import io.github.anilken.wynnlabs.model.WeeklyPool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This week's raid and lootrun pools from Wynnventory (openspec raid-lootrun-tracking, design
 * decision 10): asked at most once an hour per endpoint under the live-prices gate
 * ({@link Prices#apiReady()}), cached in the account-wide {@code wynnventory-pools.json}, and
 * read by {@link RaidPool}, {@link LootrunLog} and the Endgame tab from a volatile snapshot.
 *
 * <p>The cache read, the requests, the parsing and the cache write all run off the game thread
 * ("wynnlabs-pools" and {@link Saver}); the client thread only reads volatile fields. The key
 * goes only into the {@code X-API-Key} header: log lines name the endpoint, the status or the
 * exception's class and the week, never the key, the headers or the body.
 *
 * <p>API contract of group 7 (design.md, "API contract (group 7)"): the signatures below stay
 * as they are; private helpers and test hooks are added here.
 */
final class WynnventoryPools {

    /** This week's raid pool. */
    static final String RAID_URL = "https://www.wynnventory.com/api/raidpool/current";
    /** This week's lootrun pool. */
    static final String LOOT_URL = "https://www.wynnventory.com/api/lootpool/current";
    /** Each endpoint is asked at most this often, milliseconds (one hour). */
    static final long REFRESH_MS = 60L * 60 * 1000;

    /** Why the pools are, or are not, in use (for the tab's quiet source text). */
    enum Status {
        /** The live-prices setting is off. */
        OFF,
        /** The Wynnventory mod is not installed. */
        NO_MOD,
        /** No key is saved. */
        NO_KEY,
        /** Wynnventory refused the key (401/403). */
        KEY_REFUSED,
        /** The gate is open, no answer yet. */
        LOADING,
        /** The data is in use. */
        OK,
        /** The last request failed; cached data, if any, is still in use. */
        FAILED
    }

    /** The HTTP seam: the real one uses {@code java.net.http} with the timeouts of {@link Prices}. */
    interface Fetcher {
        Response get(String url, String key) throws java.io.IOException;
    }

    /** An HTTP answer: its status code and body. */
    record Response(int status, String body) {}

    /** The cache file's format number (2: items keep their icon, shiny flag, shiny stat and tier). */
    static final int FORMAT = 2;
    private static final String FILE = "wynnventory-pools.json";
    /** Wynntils' downloaded shiny trackers, read as plain JSON (key → English label). */
    private static final String SHINY_STATS = "wynntils/cache/dataStaticShinyStats";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** The weekly reset, assumed Friday 18:00 UTC (open question 11). */
    private static final DayOfWeek RESET_DAY = DayOfWeek.FRIDAY;
    private static final int RESET_HOUR = 18;

    /** One endpoint: its data and when it was last asked (epoch ms, -1 never). */
    private static final class Endpoint {
        final String url;
        final String label;
        final String unit;
        volatile WeeklyPool data = WeeklyPool.NONE;
        volatile long attemptAt = -1;
        volatile boolean busy;

        Endpoint(String url, String label, String unit) {
            this.url = url;
            this.label = label;
            this.unit = unit;
        }

        void clear() {
            data = WeeklyPool.NONE;
            attemptAt = -1;
            busy = false;
        }
    }

    private static final Endpoint RAID = new Endpoint(RAID_URL, "raid pool", "raids");
    private static final Endpoint LOOT = new Endpoint(LOOT_URL, "lootrun pool", "camps");

    /** Previewed pools (the no-game preview), shown whatever the gate says. */
    private static volatile WeeklyPool previewRaids = WeeklyPool.NONE;
    private static volatile WeeklyPool previewLoot = WeeklyPool.NONE;
    private static volatile boolean previewing;

    /** The gate as last checked by {@link #tick}: null open, else why it is closed. */
    private static volatile Status closed = Status.OFF;
    /** The last answer while the gate is open: LOADING, OK or FAILED. */
    private static volatile Status result = Status.LOADING;
    private static volatile boolean cacheStarted;
    private static volatile boolean cacheRead;
    /** The key's hash as last seen (0: none yet), only to notice a different key; the key itself is never kept here. */
    private static volatile int keyHash;
    /** Shiny tracker keys to their English labels ("raidsWon" → "Raids Won"); empty until read. */
    private static volatile Map<String, String> shinyStats = Map.of();
    /** Whether the shiny field names were logged (once per game run). */
    private static volatile boolean shinyLogged;

    private static Path rootOverride;
    private static Fetcher fetcher;
    /** Tests: the gate's answer instead of {@link Prices}' (null: open), and the key to send. */
    private static Status testClosed;
    private static String testKey;
    private static boolean testGate;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wynnlabs-pools");
        t.setDaemon(true);
        return t;
    });
    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10)).build();

    private WynnventoryPools() {}

    /**
     * Called by {@link RaidPool#onTick}: on the first call, starts the background cache read;
     * when the gate is open and an endpoint is due, submits its GET to the background thread.
     * Never blocks.
     */
    static void tick(long now) {
        if (previewing) {
            return;
        }
        Status why = gate();
        closed = why;
        if (!cacheStarted) {
            cacheStarted = true;
            run(WynnventoryPools::readCache);
        }
        if (why != null || !cacheRead) {
            return;                             // the cache first: it holds the last attempts
        }
        String key = key();
        if (key == null) {
            closed = Status.NO_KEY;
            return;
        }
        int h = key.hashCode();
        if (keyHash != 0 && h != keyHash) {
            RAID.attemptAt = -1;                // a different key: ask once right away
            LOOT.attemptAt = -1;
        }
        keyHash = h;
        for (Endpoint e : new Endpoint[] {RAID, LOOT}) {
            if (closed != null) {
                break;                          // refused by the first answer (inline under a test fetcher)
            }
            // due an hour after the last attempt; an attempt over an hour in the future is a clock that went back
            long last = e.attemptAt;
            if (!e.busy && (last < 0 || now - last >= REFRESH_MS || last - now > REFRESH_MS)) {
                e.busy = true;
                e.attemptAt = now;
                run(() -> fetch(e, key, now));
            }
        }
    }

    /** The latest raid pool; {@link WeeklyPool#NONE} when there is none or the gate is closed. */
    static WeeklyPool raids() {
        if (previewing) {
            return previewRaids;
        }
        return closed == null ? RAID.data : WeeklyPool.NONE;
    }

    /** The latest lootrun pool; {@link WeeklyPool#NONE} when there is none or the gate is closed. */
    static WeeklyPool loot() {
        if (previewing) {
            return previewLoot;
        }
        return closed == null ? LOOT.data : WeeklyPool.NONE;
    }

    /** Why the pools are, or are not, in use. */
    static Status status() {
        if (previewing) {
            return Status.OK;
        }
        Status c = closed;
        return c != null ? c : result;
    }

    /** Pure: whether {@code p} holds data of the week of {@link #resetWeek(long)} at {@code now} or later. */
    static boolean current(WeeklyPool p, long now) {
        if (p == null || !p.present()) {
            return false;
        }
        int[] w = resetWeek(now);
        return p.year() > w[0] || p.year() == w[0] && p.week() >= w[1];
    }

    /** Pure: {@code {isoYear, isoWeek}} of the last weekly reset at or before {@code now} (Friday 18:00 UTC). */
    static int[] resetWeek(long now) {
        ZonedDateTime at = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC);
        ZonedDateTime reset = at.with(TemporalAdjusters.previousOrSame(RESET_DAY))
                .withHour(RESET_HOUR).withMinute(0).withSecond(0).withNano(0);
        if (reset.isAfter(at)) {
            reset = reset.minusWeeks(1);
        }
        return new int[] {reset.get(IsoFields.WEEK_BASED_YEAR), reset.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)};
    }

    /** Pure: an endpoint's body, fetched at {@code fetchedAt}; {@link WeeklyPool#NONE} on bad input. */
    static WeeklyPool parse(String json, long fetchedAt) {
        return parse(json, fetchedAt, new TreeSet<>());
    }

    /**
     * {@link #parse(String, long)}, also collecting into {@code shinyFields} the names of the
     * shiny fields seen ("shiny", "shiny_stat.displayName"): names only, never values.
     */
    static WeeklyPool parse(String json, long fetchedAt, Set<String> shinyFields) {
        try {
            JsonElement root = JsonParser.parseString(json == null ? "" : json);
            if (!root.isJsonObject()) {
                return WeeklyPool.NONE;
            }
            JsonObject o = root.getAsJsonObject();
            List<PoolRegion> regions = new ArrayList<>();
            for (JsonObject r : objects(o, "regions")) {
                String name = str(r, "region");
                if (name.isEmpty()) {
                    continue;
                }
                List<PoolItem> items = new ArrayList<>();
                for (JsonObject i : objects(r, "items")) {
                    PoolItem item = item(i, shinyFields);
                    if (item != null) {
                        items.add(item);
                    }
                }
                regions.add(new PoolRegion(name, str(r, "type"), rfc1123(str(r, "timestamp")), items));
            }
            WeeklyPool p = new WeeklyPool(integer(o, "year", -1), integer(o, "week", -1), fetchedAt, regions);
            return p.present() ? p : WeeklyPool.NONE;
        } catch (RuntimeException e) {
            return WeeklyPool.NONE;
        }
    }

    /** For tests: the cache under {@code root} and this fetcher (run inline, no thread). */
    static synchronized void use(Path root, Fetcher f) {
        reset();
        rootOverride = root;
        fetcher = f;
    }

    /** For tests: forget everything in memory and go back to the config folder and the real fetcher. */
    static synchronized void reset() {
        RAID.clear();
        LOOT.clear();
        previewRaids = WeeklyPool.NONE;
        previewLoot = WeeklyPool.NONE;
        previewing = false;
        closed = Status.OFF;
        result = Status.LOADING;
        cacheStarted = false;
        cacheRead = false;
        keyHash = 0;
        shinyStats = Map.of();
        shinyLogged = false;
        rootOverride = null;
        fetcher = null;
        testGate = false;
        testClosed = null;
        testKey = null;
    }

    /** For tests: the gate's answer ({@code closedBy} null = open) and the key it gives, instead of {@link Prices}'. */
    static synchronized void testGate(Status closedBy, String key) {
        testGate = true;
        testClosed = closedBy;
        testKey = key;
    }

    /** For tests: this data as if it had been fetched, with the gate open or closed. */
    static synchronized void testData(WeeklyPool raids, WeeklyPool loot, boolean gateOpen) {
        RAID.data = raids == null ? WeeklyPool.NONE : raids;
        LOOT.data = loot == null ? WeeklyPool.NONE : loot;
        closed = gateOpen ? null : Status.OFF;
        result = Status.OK;
    }

    /** For the no-game preview: these pools, status OK, the gate ignored and no requests. */
    static synchronized void preview(WeeklyPool raids, WeeklyPool loot) {
        previewing = true;
        previewRaids = raids == null ? WeeklyPool.NONE : raids;
        previewLoot = loot == null ? WeeklyPool.NONE : loot;
    }

    /** For tests: these shiny tracker labels (key → English label) instead of Wynntils' file. */
    static synchronized void shinyStats(Map<String, String> labels) {
        shinyStats = labels == null ? Map.of() : Map.copyOf(labels);
    }

    /** Pure: Wynntils' {@code dataStaticShinyStats} ({@code [{key, displayName}, ...]}) as key → label; empty on bad input. */
    static Map<String, String> parseShinyStats(String json) {
        Map<String, String> out = new HashMap<>();
        try {
            JsonElement root = JsonParser.parseString(json == null ? "" : json);
            JsonArray list = root.isJsonArray() ? root.getAsJsonArray()
                    : root.isJsonObject() && root.getAsJsonObject().has("shinyStats")
                    && root.getAsJsonObject().get("shinyStats").isJsonArray()
                    ? root.getAsJsonObject().getAsJsonArray("shinyStats") : new JsonArray();
            for (JsonElement e : list) {
                if (e.isJsonObject()) {
                    String key = str(e.getAsJsonObject(), "key");
                    String label = str(e.getAsJsonObject(), "displayName");
                    if (!key.isEmpty() && !label.isEmpty()) {
                        out.put(key, label);
                    }
                }
            }
        } catch (RuntimeException e) {
            return Map.of();
        }
        return Map.copyOf(out);
    }

    /** The cache file (account-wide: the pools are the same for every player). */
    static Path file() {
        return (rootOverride != null ? rootOverride : Dirs.config()).resolve(FILE);
    }

    // ---- inside ----

    /** Null when requests may go out, else why not (the order the settings tab would fix them in). */
    private static Status gate() {
        if (testGate) {
            return Prices.keyInvalid ? Status.KEY_REFUSED : testClosed;
        }
        // the pools are plain web requests: unlike the prices they do not need the Wynnventory mod
        if (!Settings.on("priceApi")) {
            return Status.OFF;
        }
        if (Prices.key() == null) {
            return Status.NO_KEY;
        }
        return Prices.keyInvalid ? Status.KEY_REFUSED : null;
    }

    private static String key() {
        return testGate ? testKey : Prices.key();
    }

    /** On the worker thread, or inline under a test fetcher. */
    private static void run(Runnable r) {
        if (fetcher != null) {
            r.run();
        } else {
            WORKER.submit(r);
        }
    }

    /** One GET; the key goes only into the header and never into a log line. */
    private static void fetch(Endpoint e, String key, long now) {
        try {
            if (closed == Status.KEY_REFUSED) {
                return;                         // the other endpoint just refused this key
            }
            Fetcher f = fetcher;
            Response res = f != null ? f.get(e.url, key) : httpGet(e.url, key);
            int code = res == null ? -1 : res.status();
            if (code == 401 || code == 403) {
                Prices.refuseKey(code);
                closed = Status.KEY_REFUSED;
                log(e, "refused (" + code + ")");
            } else if (code != 200) {
                result = Status.FAILED;
                log(e, "HTTP " + code + (e.data.present() ? ", cached week " + e.data.week() + " kept" : ""));
            } else {
                Set<String> shinyFields = new TreeSet<>();
                WeeklyPool p = parse(res.body(), now, shinyFields);
                logShinyFields(shinyFields);
                if (!p.present()) {
                    result = Status.FAILED;
                    log(e, "unreadable answer");
                } else {
                    e.data = p;
                    result = Status.OK;
                    log(e, "week " + p.week() + ", " + p.regions().size() + " " + e.unit);
                }
            }
        } catch (Exception ex) {
            result = Status.FAILED;
            log(e, ex.getClass().getSimpleName());
        } finally {
            e.busy = false;
            save();
        }
    }

    private static void log(Endpoint e, String what) {
        System.out.println("[WynnLabs] Wynnventory " + e.label + ": " + what);
    }

    /** Once per game run, the first time an answer has any: the shiny field names seen (open question 15), never values. */
    private static void logShinyFields(Set<String> names) {
        if (shinyLogged || names.isEmpty()) {
            return;
        }
        shinyLogged = true;
        System.out.println("[WynnLabs] Wynnventory shiny fields: " + String.join(", ", names));
    }

    /** On the worker thread: Wynntils' shiny tracker labels, when it has downloaded them (the game only). */
    private static void readShinyStats() {
        if (rootOverride != null || !shinyStats.isEmpty()) {
            return;                             // tests set their own labels
        }
        try {
            Path f = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve(SHINY_STATS);
            if (Files.isRegularFile(f)) {
                shinyStats = parseShinyStats(Files.readString(f, StandardCharsets.UTF_8));
            }
        } catch (Throwable e) {
            System.out.println("[WynnLabs] shiny stat names unavailable: " + e.getClass().getSimpleName());
        }
    }

    private static Response httpGet(String url, String key) throws java.io.IOException {
        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .header("X-API-Key", key)
                .timeout(java.time.Duration.ofSeconds(20))
                .GET().build();
        try {
            var res = HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(res.statusCode(), res.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("interrupted");
        }
    }

    /** Once, on the worker thread: the last pools and attempts, so a restart within the hour asks nothing. */
    private static void readCache() {
        readShinyStats();
        try {
            Path f = file();
            Saver.settle(f);
            if (Files.exists(f)) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                JsonElement root = JsonParser.parseString(text);
                if (root.isJsonObject()) {
                    JsonObject o = root.getAsJsonObject();
                    // an older format has no icons: its data is shown, but each endpoint is asked once
                    boolean due = integer(o, "format", 1) < FORMAT;
                    readEndpoint(o, "raid", RAID, due);
                    readEndpoint(o, "loot", LOOT, due);
                    Saver.known(f, text);
                }
            }
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not read " + FILE + ": " + e.getClass().getSimpleName());
        } finally {
            if (RAID.data.present() || LOOT.data.present()) {
                result = Status.OK;
            }
            cacheRead = true;
        }
    }

    private static void readEndpoint(JsonObject o, String name, Endpoint e, boolean due) {
        if (!o.has(name) || !o.get(name).isJsonObject()) {
            return;
        }
        JsonObject j = o.getAsJsonObject(name);
        long fetchedAt = longOf(j, "fetchedAt", -1);
        if (!due) {
            long attempt = Math.max(fetchedAt, longOf(j, "attemptAt", -1));
            e.attemptAt = Math.max(e.attemptAt, attempt);
        }
        List<PoolRegion> regions = new ArrayList<>();
        for (JsonObject r : objects(j, "regions")) {
            List<PoolItem> items = new ArrayList<>();
            for (JsonObject i : objects(r, "items")) {
                String n = str(i, "name");
                if (!n.isEmpty()) {
                    items.add(new PoolItem(n, str(i, "itemType"), Tier.of(str(i, "rarity")), str(i, "subtype"),
                            integer(i, "amount", 1), str(i, "iconFormat"), str(i, "iconValue"), bool(i, "shiny"),
                            str(i, "shinyStat"), Math.max(0, integer(i, "tier", 0))));
                }
            }
            String region = str(r, "region");
            if (!region.isEmpty()) {
                regions.add(new PoolRegion(region, str(r, "type"), longOf(r, "seenAt", -1), items));
            }
        }
        WeeklyPool p = new WeeklyPool(integer(j, "year", -1), integer(j, "week", -1), fetchedAt, regions);
        if (p.present() && !e.data.present()) {
            e.data = p;
        }
    }

    /** The cache, written by {@link Saver} off the game thread (after every attempt, so the hour holds across restarts). */
    private static void save() {
        Saver.mark(file(), () -> {
            JsonObject o = new JsonObject();
            o.addProperty("format", FORMAT);
            o.add("raid", toJson(RAID));
            o.add("loot", toJson(LOOT));
            return GSON.toJson(o);
        });
    }

    private static JsonObject toJson(Endpoint e) {
        WeeklyPool p = e.data;
        JsonObject j = new JsonObject();
        j.addProperty("year", p.year());
        j.addProperty("week", p.week());
        j.addProperty("fetchedAt", p.fetchedAt());
        j.addProperty("attemptAt", e.attemptAt);
        JsonArray regions = new JsonArray();
        for (PoolRegion r : p.regions()) {
            JsonObject rj = new JsonObject();
            rj.addProperty("region", r.name());
            rj.addProperty("type", r.type());
            rj.addProperty("seenAt", r.seenAt());
            JsonArray items = new JsonArray();
            for (PoolItem i : r.items()) {
                JsonObject ij = new JsonObject();
                ij.addProperty("name", i.name());
                ij.addProperty("itemType", i.itemType());
                ij.addProperty("rarity", i.rarity().name());
                ij.addProperty("subtype", i.subtype());
                ij.addProperty("amount", i.amount());
                if (i.hasIcon()) {
                    ij.addProperty("iconFormat", i.iconFormat());
                    ij.addProperty("iconValue", i.iconValue());
                }
                if (i.shiny()) {
                    ij.addProperty("shiny", true);
                }
                if (!i.shinyStat().isEmpty()) {
                    ij.addProperty("shinyStat", i.shinyStat());
                }
                if (i.tier() > 0) {
                    ij.addProperty("tier", i.tier());
                }
                items.add(ij);
            }
            rj.add("items", items);
            regions.add(rj);
        }
        j.add("regions", regions);
        return j;
    }

    private static PoolItem item(JsonObject i, Set<String> shinyFields) {
        String name = str(i, "name");
        if (name.isEmpty()) {
            return null;
        }
        String format = "";
        String value = "";
        if (i.has("icon") && i.get("icon").isJsonObject()) {
            JsonObject icon = i.getAsJsonObject("icon");
            format = str(icon, "format");
            JsonElement v = icon.get("value");
            if (v != null && v.isJsonObject()) {
                value = str(v.getAsJsonObject(), "name");      // the model key of a reference value object
            } else {
                value = str(icon, "value");
            }
        }
        for (String k : i.keySet()) {
            if (k.toLowerCase(Locale.ROOT).startsWith("shiny")) {
                shinyFields.add(k);
                if (i.get(k).isJsonObject()) {
                    for (String sub : i.getAsJsonObject(k).keySet()) {
                        shinyFields.add(k + "." + sub);
                    }
                }
            }
        }
        JsonElement stat = i.has("shinyStat") ? i.get("shinyStat") : i.get("shiny_stat");
        return new PoolItem(name, str(i, "itemType"), Tier.of(str(i, "rarity").toUpperCase(Locale.ROOT)),
                str(i, "subtype"), integer(i, "amount", 1), format, value, bool(i, "shiny"), shinyLabel(stat, 0),
                Math.max(0, integer(i, "tier", 0)));
    }

    /**
     * The shiny tracker's English label: a string is a key looked up in Wynntils' list (kept as
     * written when unknown); an object gives its {@code displayName}, its {@code key} (looked up),
     * or the same under {@code statType}. Any other shape: "".
     */
    private static String shinyLabel(JsonElement stat, int depth) {
        if (stat == null || stat.isJsonNull() || depth > 2) {
            return "";
        }
        try {
            if (stat.isJsonPrimitive()) {
                if (!stat.getAsJsonPrimitive().isString()) {
                    return "";
                }
                String key = stat.getAsString().trim();
                return shinyStats.getOrDefault(key, key);
            }
            if (stat.isJsonObject()) {
                JsonObject o = stat.getAsJsonObject();
                String label = str(o, "displayName");
                if (!label.isEmpty()) {
                    return label;
                }
                String key = str(o, "key");
                if (!key.isEmpty()) {
                    return shinyStats.getOrDefault(key, key);
                }
                return shinyLabel(o.get("statType"), depth + 1);
            }
        } catch (RuntimeException e) {
            return "";
        }
        return "";
    }

    private static boolean bool(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isBoolean()
                    && o.get(k).getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** RFC 1123 ("Sat, 17 Oct 2026 22:39:33 GMT") to epoch ms; -1 when it cannot be read. */
    private static long rfc1123(String s) {
        if (s.isEmpty()) {
            return -1;
        }
        try {
            return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static List<JsonObject> objects(JsonObject o, String k) {
        List<JsonObject> out = new ArrayList<>();
        if (o.has(k) && o.get(k).isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray(k)) {
                if (e.isJsonObject()) {
                    out.add(e.getAsJsonObject());
                }
            }
        }
        return out;
    }

    private static String str(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString().trim() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int integer(JsonObject o, String k, int dflt) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : dflt;
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    private static long longOf(JsonObject o, String k, long dflt) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : dflt;
        } catch (RuntimeException e) {
            return dflt;
        }
    }
}
