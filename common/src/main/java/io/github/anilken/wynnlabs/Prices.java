package io.github.anilken.wynnlabs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trade Market prices, read from what the Wynnventory mod has already fetched.
 *
 * <p>Only Wynnventory's in-memory cache is read (the prices it looked up when you hovered items
 * on the Trade Market); this mod never asks Wynnventory's servers for anything and never
 * touches its API key. What it reads is kept in {@code config/wynnlabs/prices.json}, so prices
 * pile up as you play. Without Wynnventory installed this does nothing.
 */
final class Prices {

    /** Emeralds; null when unknown. "unid" are the prices of unidentified copies. */
    /**
     * A market price. {@code listed}: when Wynnventory last saw it listed (epoch ms, null if
     * unknown); {@code count}: how many listings that price comes from. {@code seen}: when this
     * mod read it. Neither says the item is on the market right now.
     */
    record Price(Integer lowest, Double median, Double avg80, Integer unidLowest, Double unidAvg80, long seen,
            Long listed, Integer count) {

        /**
         * What the price really rests on, for tooltips. Wynnventory's cache holds no listing count
         * or listing time (its timestamp is when it downloaded the price), only whether current
         * listings exist: none → the price is history ("şu an ilan görünmüyor"). Null otherwise.
         */
        String when() {
            String at = java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(seen), java.time.ZoneId.systemDefault())
                    .format(CLOCK);
            if (count != null && count == 0) {
                return L.t("wynnlabs.price.when_none", at);
            }
            if (listed == null) {
                return doubt();
            }
            long min = Math.max(0, (GameState.current().now() - listed) / 60_000L);
            String ago = min < 60 ? L.t("wynnlabs.price.minutes", min)
                    : min < 48 * 60 ? L.t("wynnlabs.price.hours", min / 60) : L.t("wynnlabs.price.days", min / 1440);
            String w = L.t("wynnlabs.price.last_listing", ago);
            return count == null ? w : L.n("wynnlabs.price.when_listings", count, at, count, w);
        }

        /** Old (2+ days), thin (2 listings or fewer) or no current listing: say so; else null. */
        String doubt() {
            if (listed != null) {
                long days = (GameState.current().now() - listed) / 86_400_000L;
                if (days >= 2 || count != null && count <= 2) {
                    return when();
                }
                return null;
            }
            return count != null && count == 0 ? L.t("wynnlabs.price.no_listing_past") : null;
        }

        /**
         * The price to go by: the lowest listing on the market (what you would have to beat to
         * sell, and what you would pay to buy), else the middle-80 % average, else the median.
         */
        Double typical() {
            // boxed on purpose: a ?: mixing double and Double unboxes a null and throws
            if (lowest != null) {
                return Double.valueOf(lowest);
            }
            return avg80 != null ? avg80 : median;
        }

        /** The same for an unidentified item: its lowest listing, else its average. */
        Double unidTypical() {
            if (unidLowest != null) {
                return Double.valueOf(unidLowest);
            }
            return unidAvg80;
        }

        /** The middle of the market, shown next to the lowest when they are far apart. */
        Double middle() {
            return avg80 != null ? avg80 : median;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Price> PRICES = new ConcurrentHashMap<>();
    private static boolean loaded;
    private static boolean unavailable;
    private static int tick;

    private Prices() {}

    private static Path file() {
        return Dirs.config().resolve("prices.json");
    }

    private static void load() {
        loaded = true;
        try {
            Path f = file();
            Saver.settle(f);
            if (Files.exists(f)) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                Saver.known(f, text);
                JsonObject o = GSON.fromJson(text, JsonObject.class);
                o.entrySet().forEach(e -> PRICES.put(e.getKey(), GSON.fromJson(e.getValue(), Price.class)));
            }
            // once: a past version stored Wynnventory's download time and item amount as listing
            // time and count; drop those (the price itself stays), the API refills them
            Path marker = f.resolveSibling("prices-fix-listed");
            if (!Files.exists(marker)) {
                PRICES.replaceAll((k, v) -> new Price(v.lowest(), v.median(), v.avg80(), v.unidLowest(), v.unidAvg80(),
                        v.seen(), null, null));
                Files.writeString(marker, "listing time/count from the cache were wrong; cleared once");
                save();
            }
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not read prices.json: " + e);
        }
    }

    /** prices.json, written in the background a few seconds after the last change (PRICES is concurrent). */
    private static void save() {
        Saver.mark(file(), () -> {
            JsonObject o = new JsonObject();
            PRICES.forEach((k, v) -> o.add(k, GSON.toJsonTree(v)));
            return GSON.toJson(o);
        });
    }

    private static Boolean installed;

    /**
     * Every price feature needs the Wynnventory mod: without it there are no prices, no listing
     * counts and no requests, even with a key saved (Wynntils alone is enough for the rest).
     */
    static boolean installed() {
        if (installed == null) {
            installed = Platform.get().isModLoaded("wynnventory");
            if (!installed) {
                problem = L.t("wynnlabs.price.problem_missing");
                unavailable = true;
            }
        }
        return installed;
    }

    /** Every second: copy whatever Wynnventory has cached since (prices of what you hovered). */
    static void onTick(Minecraft mc) {
        if (++tick % 20 != 0 || !installed() || unavailable) {
            return;
        }
        if (!loaded) {
            load();
        }
        // every minute: prices of what matters to you that are over an hour old, from the API
        if (tick % 1200 == 0 && Settings.on("priceApi")) {
            try {
                request(worthPricing(mc));
            } catch (Throwable ignored) {
            }
        }
        try {
            if (pricesField == null) {
                // looked up once: the service class, its instance and the cache field never change
                Class<?> svc = Class.forName("com.wynnventory.api.service.TrademarketService");
                serviceInstance = svc.getField("INSTANCE").get(null);
                Field f = svc.getDeclaredField("prices");
                f.setAccessible(true);
                pricesField = f;
            }
            Map<?, ?> cache = (Map<?, ?>) pricesField.get(serviceInstance);
            boolean changed = false;
            for (Object snap : cache.values()) {
                changed |= absorb(snap);
            }
            if (changed) {
                save();
                version++;                      // verdicts that weigh prices rebuild
            }
        } catch (ClassNotFoundException e) {
            problem = L.t("wynnlabs.price.problem_missing");
            keyProblem = false;
            unavailable = true;                 // Wynnventory not installed
        } catch (Throwable t) {
            problem = L.t("wynnlabs.price.problem_unreadable", t.getClass().getSimpleName());
            keyProblem = false;
            unavailable = true;
            System.out.println("[WynnLabs] Wynnventory prices unreadable: " + t);
        }
    }

    /** Names whose price matters to you: bag, bank pages you can open, and the J menu's upgrades. */
    static java.util.Set<String> worthPricing(Minecraft mc) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (mc.player != null) {
            var inv = mc.player.getInventory();
            for (int i = 0; i < 36; i++) {
                var item = WynntilsItems.view(inv.getItem(i));
                if (item.isPresent() && item.get().name() != null
                        && !item.get().isBox()) {
                    out.add(item.get().name());
                }
            }
        }
        for (Vault.Group g : Vault.groups()) {
            String kind = g.first().kind();
            if (g.reachable() && (kind.equals("gear") || kind.equals("ingredient") || kind.equals("material")
                    || kind.equals("powder") || kind.equals("dungeonkey"))) {
                out.add(g.name());
            }
        }
        out.addAll(Roadmap.lastNames());
        // untradable gear never has a price
        out.removeIf(n -> {
            var info = WynntilsItems.gearByName(n);
            return info != null && !Shopping.tradable(info);
        });
        return out;
    }

    /** Hovered: fetch this item's listings now when what we have is 10+ minutes old (API switched on). */
    static void refreshSoon(String shown) {
        if (!installed() || !Settings.on("priceApi") || shown == null || shown.isBlank()) {
            return;
        }
        String name = Names.english(shown);        // the market only knows English names
        if (name == null) {
            return;
        }
        Price p = PRICES.get(name);
        long now = System.currentTimeMillis();
        if (p != null && p.listed() != null && now - p.seen() < 10 * 60_000L || p != null && p.count() != null
                && p.count() == 0 && now - p.seen() < 10 * 60_000L) {
            return;
        }
        Long missed = MISSES.get(name);
        if (missed != null && now - missed < 10 * 60_000L) {
            return;
        }
        String k = key();
        if (k == null || keyInvalid || !QUEUED.add(name)) {
            return;
        }
        FETCHER.submit(() -> {
            try {
                fetch(name, k);
            } catch (Exception e) {
                MISSES.put(name, System.currentTimeMillis());
            } finally {
                QUEUED.remove(name);
            }
        });
    }

    static volatile String problem;              // null while prices come in; shown in the settings tab
    /** True when {@link #problem} is the refused key (cleared when a new key is saved). */
    private static volatile boolean keyProblem;

    private static Field pricesField;
    private static Object serviceInstance;
    /** Methods by name per class, looked up once (Wynnventory's summary classes). */
    private static final ClassValue<Map<String, Method>> METHODS = new ClassValue<>() {
        @Override
        protected Map<String, Method> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    /** For tests: a stored price by its exact key. */
    static Price stored(String key) {
        return PRICES.get(key);
    }

    static boolean absorb(Object snapshot) throws Exception {
        Object live = call(snapshot, "live");
        Object summary = live != null && !(Boolean) call(live, "isEmpty") ? live : call(snapshot, "historic");
        if (summary == null) {
            return false;
        }
        String name = (String) call(summary, "getName");
        if (name == null || name.isBlank()) {
            return false;
        }
        Object tier = call(summary, "getTier");
        boolean shiny = Boolean.TRUE.equals(call(summary, "isShiny"));
        String key = name + (tier != null ? " T" + tier : "") + (shiny ? " shiny" : "");
        boolean fromHistory = summary != live;   // no current listings: the historic price
        Price p = new Price((Integer) call(summary, "getLowestPrice"), (Double) call(summary, "getMedian"),
                (Double) call(summary, "getAverageMid80PercentPrice"),
                (Integer) call(summary, "getUnidentifiedLowestPrice"),
                (Double) call(summary, "getUnidentifiedAverageMid80PercentPrice"), System.currentTimeMillis(),
                null, fromHistory ? 0 : null);
        Price old = PRICES.get(key);
        if (old != null && (old.listed() != null || old.count() != null && old.count() == 0 && !fromHistory)
                && System.currentTimeMillis() - old.seen() < MAX_AGE) {
            // live listings from the API under an hour old win over Wynnventory's cache; letting
            // the cache overwrite them made the tooltip's listing line come and go every second
            return false;
        }
        if (old != null && same(old, p)) {
            return false;
        }
        PRICES.put(key, p);
        return true;
    }

    private static boolean same(Price a, Price b) {
        return java.util.Objects.equals(a.lowest(), b.lowest()) && java.util.Objects.equals(a.median(), b.median())
                && java.util.Objects.equals(a.avg80(), b.avg80()) && java.util.Objects.equals(a.listed(), b.listed())
                && java.util.Objects.equals(a.count(), b.count());
    }

    private static Object call(Object o, String method) throws Exception {
        Map<String, Method> byName = METHODS.get(o.getClass());
        Method m = byName.get(method);
        if (m == null) {
            m = o.getClass().getMethod(method);
            byName.put(method, m);
        }
        return m.invoke(o);
    }

    // ---- direct lookups with the player's own Wynnventory key -----------------------------

    private static final java.util.concurrent.ExecutorService FETCHER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "wynninv-prices");
                t.setDaemon(true);
                return t;
            });
    private static final java.util.Set<String> QUEUED = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> MISSES = new ConcurrentHashMap<>();

    /**
     * "~4 EB (en düşük 3 EB, 5 ilan)" for a tooltip, asking Wynnventory in the background when
     * it is not known yet; null until a price is there. Unidentified copies use their own price.
     */
    static String text(String name, boolean unidentified) {
        Price p = GameState.current().price(name);
        if (p == null) {
            GameState.current().requestPrices(java.util.List.of(name));
            return null;
        }
        Double low = unidentified && p.unidTypical() != null ? p.unidTypical() : p.typical();
        Double mid = unidentified && p.unidAvg80() != null ? p.unidAvg80() : p.middle();
        if (low == null) {
            return null;
        }
        // callers cut at " (" to get the price alone: keep " (" in every language
        if (mid != null && Math.abs(mid - low) > low * 0.25) {
            return L.t("wynnlabs.price.with_median", fmt(low), fmt(mid));
        }
        return "~" + fmt(low);
    }
    private static final long MAX_AGE = 60 * 60 * 1000L;

    /** Set when Wynnventory refused the key (401/403): no live requests until a new key is saved. */
    static volatile boolean keyInvalid;

    /**
     * The gate for live requests (also the weekly pools, design decision 10): the Wynnventory mod
     * is installed, {@code priceApi} is on, a key is saved and it was not refused.
     */
    static boolean apiReady() {
        return installed() && Settings.on("priceApi") && key() != null && !keyInvalid;
    }

    /** A pool endpoint answered 401 or 403: the key is refused, as the listings call does. */
    static void refuseKey(int status) {
        keyInvalid = true;                      // stop asking until the player saves another key
        problem = L.t("wynnlabs.price.problem_key", status);
        keyProblem = true;
    }
    /**
     * Goes up when a cached price changes. Kept apart from {@link Rules#version}: a price change
     * rebuilds price-dependent advice (see {@link Stamp}) but never the build plan.
     */
    static volatile int version;
    /** "14:05" under a price; built once. */
    static final java.time.format.DateTimeFormatter CLOCK = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
    /**
     * The key as last read, so tooltips and price lookups never read the file: read in the
     * background at most once a minute (the player may edit the file by hand), and set at once
     * by {@link #setKey}.
     */
    private static volatile String cachedKey;
    private static volatile long keyReadAt;
    private static final java.util.concurrent.atomic.AtomicBoolean KEY_READING = new java.util.concurrent.atomic.AtomicBoolean();
    /** How many times the key file was actually read (for /perf). */
    static final java.util.concurrent.atomic.AtomicInteger KEY_FILE_READS = new java.util.concurrent.atomic.AtomicInteger();

    private static Path keyFile() {
        return Dirs.config().resolve("wynnventory-key.txt");
    }

    /** Saves the player's key (blank removes it), typed in the settings tab; never logged. */
    static void setKey(String k) {
        try {
            String v = k == null ? "" : k.strip();
            if (v.isEmpty()) {
                Files.deleteIfExists(keyFile());
            } else {
                Files.createDirectories(keyFile().getParent());
                Files.writeString(keyFile(), v, StandardCharsets.UTF_8);
            }
            if (!embeddedRead) {
                embeddedKey = EmbeddedKey.load();   // removing the saved key falls back to it at once
                embeddedRead = true;
            }
            cachedKey = v.isEmpty() ? null : v;
            keyReadAt = System.currentTimeMillis();
            keyInvalid = false;
            MISSES.clear();
            if (keyProblem) {
                keyProblem = false;
                problem = null;
            }
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not save the Wynnventory key: " + e.getClass().getSimpleName());
        }
    }

    /**
     * The saved key as "••••1a2b"; "••••" alone for the key built into the jar (so the settings
     * tab does not ask for one, and shows none of its characters); null when there is neither.
     */
    static String maskedKey() {
        String k = cachedKey;
        if (k == null) {
            return key() == null ? null : "••••";
        }
        return "••••" + (k.length() > 4 ? k.substring(k.length() - 4) : "");
    }

    /**
     * The key built into the jar at build time ({@link EmbeddedKey}, task 7.3.6), read once off
     * the game thread with the key file; null when this jar has none. Never logged or shown.
     */
    private static volatile String embeddedKey;
    private static volatile boolean embeddedRead;

    /** Whether the key in use is the one built into the jar (no key saved in config). */
    static boolean embeddedKeyInUse() {
        return cachedKey == null && embeddedKey != null;
    }

    /**
     * The key to send: the player's own key from config/wynnlabs/wynnventory-key.txt when one is
     * saved, else the key built into the jar; null when there is neither.
     */
    static String key() {
        if (System.currentTimeMillis() - keyReadAt > 60_000 && KEY_READING.compareAndSet(false, true)) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    if (!embeddedRead) {
                        embeddedKey = EmbeddedKey.load();
                        embeddedRead = true;
                    }
                    cachedKey = readKeyFile();
                    keyReadAt = System.currentTimeMillis();
                } finally {
                    KEY_READING.set(false);
                }
            });
        }
        String saved = cachedKey;
        return saved != null ? saved : embeddedKey;
    }

    /** Reads the key file now; null when there is none. Off the game thread. */
    static String readKeyFile() {
        try {
            Path f = keyFile();
            if (Files.exists(f)) {
                KEY_FILE_READS.incrementAndGet();
                String k = Files.readString(f, StandardCharsets.UTF_8).strip();
                return k.isEmpty() ? null : k;
            }
        } catch (Exception ignored) {
            // no key
        }
        return null;
    }

    /**
     * Asks Wynnventory for the prices of these items (the shopping list), one request every two
     * seconds in the background, skipping any seen in the last six hours. Needs the player's key.
     */
    static void request(java.util.Collection<String> names) {
        names = names.stream().map(Names::english).toList();   // the market only knows English names
        // Off unless switched on: prices come from Wynnventory's own tooltip lookups instead,
        // so the mod sends no requests of its own
        if (!installed() || !Settings.on("priceApi")) {
            return;
        }
        String k = key();
        if (k == null || keyInvalid) {
            return;
        }
        if (!loaded) {
            load();
        }
        long now = System.currentTimeMillis();
        for (String name : names) {
            Price p = PRICES.get(name);
            Long missed = MISSES.get(name);
            if ((p != null && now - p.seen() < MAX_AGE) || (missed != null && now - missed < MAX_AGE / 6)
                    || !QUEUED.add(name)) {
                continue;
            }
            FETCHER.submit(() -> {
                try {
                    fetch(name, k);
                    Thread.sleep(1000);
                } catch (Exception e) {
                    System.out.println("[WynnLabs] price of " + name + " failed: " + e);
                } finally {
                    QUEUED.remove(name);
                }
            });
        }
    }

    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10)).build();

    /**
     * The live Trade Market listings of one item (Wynnventory's /v2/market/listings, what its
     * website shows): the lowest current price, how many listings, when the newest was seen.
     * No listings: the old price stays, marked "şu an ilan yok". Saved, so verdicts rebuild.
     */
    private static void fetch(String name, String k) throws Exception {
        String q = java.net.URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        var req = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("https://www.wynnventory.com/api/v2/market/listings?item_name=" + q))
                .header("X-API-Key", k)
                .timeout(java.time.Duration.ofSeconds(20))
                .GET().build();
        var res = HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 401 || res.statusCode() == 403) {
            keyInvalid = true;                  // stop asking until the player saves another key
            problem = L.t("wynnlabs.price.problem_key", res.statusCode());
            keyProblem = true;
            return;
        }
        if (res.statusCode() != 200) {
            MISSES.put(name, System.currentTimeMillis());
            return;
        }
        var list = GSON.fromJson(res.body(), JsonObject.class).getAsJsonArray("data");
        // Listings are what players saw on the market, in snapshots: an old snapshot keeps
        // listings that have sold since (the cheap ones go first). The prices come from those seen
        // within 3 hours of the newest; the count from every listing, since a snapshot holds only
        // what that player looked at.
        long newest = 0;
        for (var el : list) {
            newest = Math.max(newest, seenAt(el.getAsJsonObject()));
        }
        long cutoff = newest > 0 ? newest - 3 * 3_600_000L : 0;
        Integer low = null;
        Integer unidLow = null;
        int count = 0;
        java.util.List<Integer> prices = new java.util.ArrayList<>();
        for (var el : list) {
            JsonObject l = el.getAsJsonObject();
            if (!name.equalsIgnoreCase(l.get("name").getAsString()) || l.has("shiny") && l.get("shiny").getAsBoolean()) {
                continue;                       // exact item, no shinies (they sell for far more)
            }
            count++;
            long at = seenAt(l);
            if (at > 0 && at < cutoff) {
                continue;                       // its price: probably sold since
            }
            int price = l.get("listing_price").getAsInt();
            boolean unid = l.has("unidentified") && l.get("unidentified").getAsBoolean();
            if (unid) {
                unidLow = unidLow == null ? price : Math.min(unidLow, price);
            } else {
                low = low == null ? price : Math.min(low, price);
                prices.add(price);
            }
        }
        long now = System.currentTimeMillis();
        Price old = PRICES.get(name);
        Price p;
        if (count == 0) {
            // nothing listed now: keep the last known price, say there is no listing
            p = old == null ? null : new Price(old.lowest(), old.median(), old.avg80(), old.unidLowest(), old.unidAvg80(),
                    now, null, 0);
            if (p == null) {
                MISSES.put(name, now);
                return;
            }
        } else {
            java.util.Collections.sort(prices);
            Double median = prices.isEmpty() ? null : (double) prices.get(prices.size() / 2);
            p = new Price(low, median, median, unidLow, unidLow == null ? null : (double) unidLow, now,
                    newest > 0 ? newest : null, count);
        }
        PRICES.put(name, p);
        save();
        version++;                              // verdicts that weigh market prices rebuild
    }

    /** When a listing was seen, in epoch millis; 0 when unknown. */
    private static long seenAt(JsonObject l) {
        try {
            return java.time.Instant.parse(l.get("timestamp").getAsString()).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    private static Integer intOf(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? (int) Math.round(o.get(k).getAsDouble()) : null;
    }

    private static Double dblOf(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : null;
    }

    /** The price of an item by name (gear) or "name T2" (tiered materials); null if never seen. */
    /** Whether a name ends in an ingredient tier (" T1"): the old {@code matches(".* T\\d$")}, without a regex. */
    static boolean hasTier(String name) {
        int n = name.length();
        char d = n >= 3 ? name.charAt(n - 1) : 'x';
        return n >= 3 && name.charAt(n - 3) == ' ' && name.charAt(n - 2) == 'T' && d >= '0' && d <= '9';
    }

    static Price of(String name) {
        if (!installed()) {
            return null;
        }
        if (!loaded) {
            load();
        }
        if (name == null) {
            return null;
        }
        Price p = PRICES.get(name);
        String en = name;
        if (p == null) {
            en = Names.english(name);               // a translated name ("Doğal Nitratlar")
            if (!en.equals(name)) {
                p = PRICES.get(en);
            }
        }
        if (p == null && !hasTier(en)) {
            // Wynnventory keeps ingredients with their tier ("Runner's Bandages T1"): use it when
            // the name has exactly one tier, so a material with T1-T3 prices is never guessed
            Price only = null;
            int found = 0;
            for (int t = 0; t <= 3; t++) {
                Price q = PRICES.get(en + " T" + t);
                if (q != null) {
                    only = q;
                    found++;
                }
            }
            if (found == 1) {
                p = only;
            }
        }
        return p;
    }

    /** 1 LE = 64 EB = 4096 E. "2.3 LE", "14 EB", "40 E". */
    static String fmt(double emeralds) {
        if (emeralds >= 4096) {
            return String.format(java.util.Locale.ROOT, "%.1f LE", emeralds / 4096);
        }
        if (emeralds >= 64) {
            return String.format(java.util.Locale.ROOT, "%.0f EB", emeralds / 64);
        }
        return Math.round(emeralds) + " E";
    }

    static JsonObject json() {
        if (!loaded) {
            load();
        }
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        PRICES.forEach((k, v) -> {
            JsonObject j = GSON.toJsonTree(v).getAsJsonObject();
            j.addProperty("name", k);
            if (v.typical() != null) {
                j.addProperty("typicalText", fmt(v.typical()));
            }
            arr.add(j);
        });
        o.addProperty("count", arr.size());
        o.addProperty("source", unavailable ? "Wynnventory not found" : "Wynnventory cache (items you hovered)");
        o.add("items", arr);
        return o;
    }
}
