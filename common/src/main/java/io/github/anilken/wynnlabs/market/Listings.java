package io.github.anilken.wynnlabs.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.search.BoxMarket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The current Trade Market listings of the items the box contents window looks at, as
 * Wynnventory's /v2/market/listings answered: kept in memory only (never saved), at most
 * {@link #MAX} items, fresh for {@code rules.boxListingsMinutes} (5). Every request goes through
 * {@link Prices}' one worker, key, switch and pace ({@link Prices#queueListings}); page 1 also
 * gives the item's price there. Further pages come one at a time on demand. Nothing about the
 * seller is read: the parse never touches player_name, mod_version or hash_code.
 */
public final class Listings {

    /** How many items are held; the one looked at longest ago goes first. */
    static final int MAX = 200;

    /** Where requests go: {@link Prices}' worker in the game, a recorder in tests. */
    interface Sender {
        /** Whether listings can be asked for at all (Wynnventory, the priceApi switch, a key not refused). */
        boolean live();

        /** Asks for one page; true when it will be fetched (queued now or already waiting). */
        boolean queue(String name, int page);
    }

    /** One answer read: its listings (this item only, no shinies), the total and whether more pages exist. */
    record Page(List<BoxMarket.Listing> listings, Integer total, Boolean hasMore) {}

    /** What is held for one item. Guarded by {@link #ITEMS}. */
    private static final class Entry {
        final List<List<BoxMarket.Listing>> pages = new ArrayList<>();
        Integer total;
        boolean firstHasMore;
        boolean hasMore;
        long fetchedAt;
        boolean failed;
        int pendingPage;
    }

    /**
     * A refresh of one box's items ("Yenile"): how many were asked, how many answered, and when
     * the last answer came (0 while running).
     */
    public static final class Refresh {
        final String box;
        final int total;
        final Set<String> pending = new LinkedHashSet<>();
        private volatile int done;
        private volatile long finishedAt;

        Refresh(String box, int total) {
            this.box = box;
            this.total = total;
        }

        public int total() {
            return total;
        }

        public int done() {
            return done;
        }

        public boolean running() {
            return finishedAt == 0;
        }

        public long finishedAt() {
            return finishedAt;
        }
    }

    private static final Map<String, Entry> ITEMS = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX;
        }
    };

    static Sender sender = new Sender() {
        @Override
        public boolean live() {
            return Prices.apiReady();
        }

        @Override
        public boolean queue(String name, int page) {
            return Prices.queueListings(name, page);
        }
    };

    private static Refresh refresh;

    /** Goes up when held listings change; the window rebuilds its rows then, never per frame. */
    public static volatile int version;

    private Listings() {}

    /** Whether listings can be asked for now. */
    public static boolean live() {
        return sender.live();
    }

    static long freshMs() {
        return (long) (Rules.num("boxListingsMinutes", 5) * 60_000L);
    }

    // ---- reading an answer ----

    /** Page 1 of an answer, or a later page, for {@code name}: the store's copy is replaced or added to. */
    static void accept(String name, int page, JsonObject body) {
        put(name, page, parse(name, body), System.currentTimeMillis());
    }

    /** The listings of {@code name} in an answer: other names and shinies left out, sellers never read. */
    static Page parse(String name, JsonObject body) {
        List<BoxMarket.Listing> out = new ArrayList<>();
        JsonArray data = body.has("data") && body.get("data").isJsonArray() ? body.getAsJsonArray("data") : new JsonArray();
        for (JsonElement el : data) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject l = el.getAsJsonObject();
            String n = str(l, "name");
            if (n == null || !name.equalsIgnoreCase(n) || shiny(l)) {
                continue;                       // exact item, no shinies (they sell for far more)
            }
            Double price = num(l, "listing_price");
            if (price == null) {
                continue;
            }
            Double overall = num(l, "overall_roll");
            List<BoxMarket.StatRoll> rolls = stats(l.get("stat_rolls"));
            // 0 % with no stat above 0 % is no roll at all (fixed stats, or the uploader saw none):
            // shown as unknown, not as the worst roll, and kept out of "best"
            if (overall != null && overall == 0 && rolls.stream().noneMatch(r -> r.percent() != null && r.percent() > 0)) {
                overall = null;
            }
            Double rerolls = num(l, "reroll_count");
            Double amount = num(l, "amount");
            out.add(new BoxMarket.Listing(bool(l, "unidentified"), overall, rolls,
                    rerolls == null ? 0 : rerolls.intValue(), amount == null ? 1 : Math.max(1, amount.intValue()),
                    (int) Math.round(price), Prices.seenAt(l), false));
        }
        // the paging fields, wherever the answer keeps them; none: one page
        JsonObject meta = body;
        for (String k : new String[] {"pagination", "meta", "page_info"}) {
            if (body.has(k) && body.get(k).isJsonObject()) {
                meta = body.getAsJsonObject(k);
                break;
            }
        }
        Double total = first(meta, body, "total", "total_count", "totalCount", "total_items", "count");
        Boolean more = null;
        for (String k : new String[] {"has_more", "has_next", "hasNext", "hasMore"}) {
            JsonObject o = meta.has(k) ? meta : body.has(k) ? body : null;
            if (o != null && o.get(k).isJsonPrimitive()) {
                more = o.get(k).getAsBoolean();
                break;
            }
        }
        if (more == null) {
            Double pageNo = first(meta, body, "page", "current_page");
            Double pages = first(meta, body, "pages", "total_pages", "totalPages");
            if (pageNo != null && pages != null) {
                more = pageNo < pages;
            }
        }
        return new Page(List.copyOf(out), total == null ? null : total.intValue(), more);
    }

    private static boolean shiny(JsonObject l) {
        return bool(l, "shiny") || l.has("shiny_stat") && !l.get("shiny_stat").isJsonNull();
    }

    /** stat_rolls as Wynnventory's uploader writes them (a list of {apiName, displayName, statRoll, rollPercentage}, from its ItemStat record), a list of {name/api_name, value, percentage} or a map of stat → % or → {value, percentage}. */
    private static List<BoxMarket.StatRoll> stats(JsonElement e) {
        List<BoxMarket.StatRoll> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) {
            return out;
        }
        if (e.isJsonArray()) {
            for (JsonElement s : e.getAsJsonArray()) {
                if (s.isJsonObject()) {
                    JsonObject o = s.getAsJsonObject();
                    String key = firstStr(o, "api_name", "apiName", "stat", "name", "key");
                    if (key != null) {
                        out.add(new BoxMarket.StatRoll(key, firstNum(o, "value", "statRoll"), firstNum(o, "rollPercentage", "percentage", "percent", "roll")));
                    }
                }
            }
        } else if (e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> s : e.getAsJsonObject().entrySet()) {
                JsonElement v = s.getValue();
                if (v.isJsonObject()) {
                    JsonObject o = v.getAsJsonObject();
                    out.add(new BoxMarket.StatRoll(s.getKey(), firstNum(o, "value", "statRoll"), firstNum(o, "rollPercentage", "percentage", "percent", "roll")));
                } else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                    out.add(new BoxMarket.StatRoll(s.getKey(), null, v.getAsDouble()));
                }
            }
        }
        return out;
    }

    // ---- the store ----

    /** Keeps an answer read at {@code at}: page 1 replaces what was held (later pages go), a later page is added. */
    static void put(String name, int page, Page p, long at) {
        synchronized (ITEMS) {
            Entry e = ITEMS.get(name);
            if (page <= 1 || e == null) {
                e = new Entry();
                ITEMS.put(name, e);
                e.pages.add(p.listings());
                e.fetchedAt = at;
                e.total = p.total();
                e.firstHasMore = more(p, e);
                e.hasMore = e.firstHasMore;
            } else {
                if (page != e.pages.size() + 1) {
                    return;                     // a page that no longer follows (a refresh came between)
                }
                e.pages.add(p.listings());
                if (p.total() != null) {
                    e.total = p.total();
                }
                e.hasMore = !p.listings().isEmpty() && more(p, e);
            }
            e.failed = false;
            e.pendingPage = 0;
        }
        version++;
    }

    /** More pages: as the answer says; without that, while fewer listings are held than its total. */
    private static boolean more(Page p, Entry e) {
        if (p.hasMore() != null) {
            return p.hasMore();
        }
        if (p.total() == null) {
            return false;
        }
        int held = 0;
        for (List<BoxMarket.Listing> l : e.pages) {
            held += l.size();
        }
        return held < p.total();
    }

    /** A request ended ({@code ok}: its answer was read). A failed page stops "more" for the item. */
    static void finished(String name, int page, boolean ok) {
        synchronized (ITEMS) {
            Entry e = ITEMS.get(name);
            if (!ok) {
                if (page <= 1) {
                    if (e == null) {
                        e = new Entry();
                        ITEMS.put(name, e);
                    }
                    e.failed = true;
                } else if (e != null) {
                    e.hasMore = false;
                }
            }
            if (e != null && e.pendingPage == page) {
                e.pendingPage = 0;
            }
            Refresh r = refresh;
            if (page <= 1 && r != null && r.pending.remove(name)) {
                r.done++;
                if (r.pending.isEmpty()) {
                    r.finishedAt = System.currentTimeMillis();
                }
            }
        }
        version++;
    }

    /**
     * What is held for {@code name}, possibly-sold marks set; null when nothing was ever answered.
     * Once its first page is older than the fresh time the later pages go.
     */
    public static BoxMarket.Held held(String name) {
        synchronized (ITEMS) {
            Entry e = ITEMS.get(name);
            if (e == null) {
                return null;
            }
            if (e.pages.size() > 1 && System.currentTimeMillis() - e.fetchedAt >= freshMs()) {
                dropExtra(e);
            }
            List<BoxMarket.Listing> all = new ArrayList<>();
            e.pages.forEach(all::addAll);
            if (e.pages.isEmpty() && !e.failed) {
                return null;
            }
            return new BoxMarket.Held(BoxMarket.markSold(all), e.total, e.hasMore, e.failed);
        }
    }

    /** When page 1 of {@code name} was read (epoch ms), 0 when never. */
    public static long fetchedAt(String name) {
        synchronized (ITEMS) {
            Entry e = ITEMS.get(name);
            return e == null ? 0 : e.fetchedAt;
        }
    }

    /** Whether a later page of {@code name} is waiting. */
    public static boolean loadingMore(String name) {
        synchronized (ITEMS) {
            Entry e = ITEMS.get(name);
            return e != null && e.pendingPage > 1;
        }
    }

    private static void dropExtra(Entry e) {
        while (e.pages.size() > 1) {
            e.pages.remove(e.pages.size() - 1);
        }
        e.hasMore = e.firstHasMore;
        e.pendingPage = 0;
    }

    // ---- asking ----

    /** Page 1 of each name not read within the fresh time, in this order. Nothing when lookups are off. */
    public static void request(Collection<String> names) {
        if (!sender.live()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String n : names) {
            synchronized (ITEMS) {
                Entry e = ITEMS.get(n);
                if (e != null && !e.failed && !e.pages.isEmpty() && now - e.fetchedAt < freshMs()) {
                    continue;
                }
            }
            sender.queue(n, 1);
        }
    }

    /** The next page of {@code name} while more exist and none is waiting; true when it was asked for. */
    public static boolean requestMore(String name) {
        int page;
        synchronized (ITEMS) {
            Entry e = ITEMS.get(name);
            if (e == null || !e.hasMore || e.pendingPage != 0 || e.pages.isEmpty() || !sender.live()) {
                return false;
            }
            page = e.pages.size() + 1;
            e.pendingPage = page;
        }
        if (!sender.queue(name, page)) {
            synchronized (ITEMS) {
                Entry e = ITEMS.get(name);
                if (e != null && e.pendingPage == page) {
                    e.pendingPage = 0;
                }
            }
            return false;
        }
        return true;
    }

    /**
     * "Yenile" for a box: page 1 of each name again whatever its age, {@code first} at the head,
     * their later pages dropped. A name already waiting is not asked twice. While a refresh of the
     * same box runs, that one is returned and nothing is asked.
     */
    public static Refresh refresh(String box, List<String> names, String first) {
        synchronized (ITEMS) {
            Refresh r = refresh;
            if (r != null && r.running() && r.box.equals(box)) {
                return r;
            }
        }
        List<String> order = new ArrayList<>();
        if (first != null && names.contains(first)) {
            order.add(first);
        }
        for (String n : names) {
            if (!order.contains(n)) {
                order.add(n);
            }
        }
        Refresh r = new Refresh(box, order.size());
        synchronized (ITEMS) {
            refresh = r;
            for (String n : order) {
                Entry e = ITEMS.get(n);
                if (e != null) {
                    dropExtra(e);
                }
                r.pending.add(n);
            }
        }
        boolean live = sender.live();
        for (String n : order) {
            if (!live || !sender.queue(n, 1)) {
                synchronized (ITEMS) {
                    if (r.pending.remove(n)) {
                        r.done++;
                    }
                }
            }
        }
        synchronized (ITEMS) {
            if (r.pending.isEmpty() && r.finishedAt == 0) {
                r.finishedAt = System.currentTimeMillis();
            }
        }
        version++;
        return r;
    }

    /** The last refresh of {@code box}, running or done; null when there was none. */
    public static Refresh refreshOf(String box) {
        Refresh r = refresh;
        return r != null && r.box.equals(box) ? r : null;
    }

    /** Tests: nothing held, no refresh. */
    static void clear() {
        synchronized (ITEMS) {
            ITEMS.clear();
            refresh = null;
        }
    }

    // ---- json helpers ----

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    private static String firstStr(JsonObject o, String... keys) {
        for (String k : keys) {
            String s = str(o, k);
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    private static Double num(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonPrimitive()) {
            return null;
        }
        try {
            return o.get(k).getAsDouble();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double firstNum(JsonObject o, String... keys) {
        for (String k : keys) {
            Double d = num(o, k);
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    private static Double first(JsonObject a, JsonObject b, String... keys) {
        Double d = firstNum(a, keys);
        return d != null || a == b ? d : firstNum(b, keys);
    }

    private static boolean bool(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsBoolean();
    }
}
