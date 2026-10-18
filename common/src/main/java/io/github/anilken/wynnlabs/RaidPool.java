package io.github.anilken.wynnlabs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.model.AspectEntry;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.MenuKind;
import io.github.anilken.wynnlabs.model.PoolAspect;
import io.github.anilken.wynnlabs.model.PoolItem;
import io.github.anilken.wynnlabs.model.PoolRegion;
import io.github.anilken.wynnlabs.model.Tier;
import io.github.anilken.wynnlabs.model.WeeklyPool;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;

/**
 * The raid reward pool (openspec raid-tracking, design decisions 8 and 10): this week's pool of
 * every raid from Wynnventory when the player's key allows it ({@link WynnventoryPools}), else
 * the pool as the player last saw it. When the player opens the raid reward preview menu
 * themselves, its aspects are saved with the time in the account-wide {@code raid-pool.json}
 * through {@link Saver}, whichever source is shown. The mod never opens that menu.
 *
 * <p>The views of the Wynnventory pool for the class in play (aspects by raid, raids by aspect)
 * are built once per snapshot or class change and kept in maps: the tab asks {@link #holds},
 * {@link #markOf} and {@link #raidsHolding} for every row in every frame.
 *
 * <p>API contracts of groups 1 and 7 (design.md): the signatures below stay as they are.
 */
final class RaidPool {

    /** How a pool aspect compares with what the player owns. */
    enum Mark {
        /** Owned at or above the tier shown, or owned tiers unknown. */
        NONE,
        /** Not owned. */
        NEW,
        /** Owned below the tier shown. */
        TIER_UP
    }

    /** After this long the pool is drawn grey with "may be out of date", milliseconds (7 days). */
    static final long STALE_MS = 7L * 24 * 60 * 60 * 1000;

    /** The file's format number. */
    static final int FORMAT = 1;
    /** The menu type {@link Wyn#container()} reports for the raid reward preview. */
    private static final String PREVIEW_MENU = "RaidRewardPreviewContainer";
    private static final String FILE = "raid-pool.json";
    private static final long MINUTE = 60_000L;
    /** The source is chosen again at least this often (the week may turn while the game runs). */
    private static final long RECHECK_MS = MINUTE;
    private static final Map<String, String> SHORT = Map.of(
            "Nest of the Grootslangs", "NOG",
            "Orphion's Nexus of Light", "NOL",
            "The Canyon Colossus", "TCC",
            "The Nameless Anomaly", "TNA",
            "The Wartorn Palace", "WTP");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile List<PoolAspect> pool = List.of();
    private static volatile long seenAt = -1;
    private static boolean loaded;
    private static boolean previewing;
    /** The preview menu is open and its pool was saved during this opening. */
    private static boolean savedThisOpening;
    private static Path rootOverride;
    private static int tick;
    private static volatile LongSupplier clock = System::currentTimeMillis;

    /**
     * What is shown, built from the inputs it names and replaced whole. {@code byName} and
     * {@code raidsByAspect} are keyed by the lower-cased aspect name.
     */
    private record View(WeeklyPool api, List<PoolAspect> game, long gameSeenAt, Klass klass, long checkedAt,
            Source source, boolean current, List<PoolAspect> aspects, Map<String, PoolAspect> byName,
            List<String> raids, Map<String, List<PoolAspect>> byRaid, Map<String, List<String>> raidsByAspect,
            Map<String, Long> raidSeen) {}

    private static volatile View view;
    /** The max tier by aspect name, built from {@link AspectBook#classAspects()} (kept with its list). */
    private static volatile Object[] maxTiers = {null, Map.of()};

    private RaidPool() {}

    /** Every client tick: reads the preview menu's aspects while the player has it open ({@link Wyn#poolAspects()}). */
    static void onTick(Minecraft mc) {
        if (++tick % 10 != 0 || previewing) {
            return;
        }
        if (!loaded) {
            load();
        }
        WynnventoryPools.tick(System.currentTimeMillis());
        if (mc == null || mc.player == null) {
            return;
        }
        MenuKind menu = Wyn.container();
        if (menu == null || !PREVIEW_MENU.equals(menu.type())) {
            savedThisOpening = false;
            return;
        }
        List<PoolAspect> shown = Wyn.poolAspects();
        if (!shown.isEmpty()) {
            seen(shown, System.currentTimeMillis());
        }
    }

    /**
     * The chosen source's aspects. From Wynnventory: the class's aspects across raids, without
     * repeats, in raid order then Wynnventory's order, tier 0. From the game: as seen, in the
     * menu's order. Empty when there is neither.
     */
    static List<PoolAspect> aspects() {
        View v = view();
        return v.source() == Source.WYNNVENTORY ? v.aspects() : v.source() == Source.GAME ? v.game() : List.of();
    }

    /** When the pool shown was seen, epoch milliseconds (Wynnventory: the newest raid's timestamp); -1 when never. */
    static long seenAt() {
        View v = view();
        return switch (v.source()) {
            case WYNNVENTORY -> v.api().newestSeenAt() >= 0 ? v.api().newestSeenAt() : v.api().fetchedAt();
            case GAME -> v.gameSeenAt();
            case NONE -> -1;
        };
    }

    /** Whether a pool was ever seen. */
    static boolean seen() {
        return seenAt() >= 0;
    }

    /**
     * Whether the pool shown may be out of date at {@code now} (epoch milliseconds): the game's
     * after {@link #STALE_MS}, Wynnventory's when its week is not the current one.
     */
    static boolean stale(long now) {
        View v = view();
        if (v.source() == Source.WYNNVENTORY) {
            return !WynnventoryPools.current(v.api(), now);
        }
        return seen() && now - seenAt() > STALE_MS;
    }

    /** How long ago it was seen, in the menu's language ("2 days ago"); "" when never seen. */
    static String ageText(long now) {
        return age(seenAt(), now);
    }

    /** The mark for a pool aspect against an owned tier (0 not owned, -1 unknown → NONE). */
    static Mark mark(PoolAspect aspect, int ownedTier) {
        if (aspect == null || ownedTier < 0) {
            return Mark.NONE;
        }
        if (ownedTier == 0) {
            return Mark.NEW;
        }
        return ownedTier < aspect.tier() ? Mark.TIER_UP : Mark.NONE;
    }

    /** The mark of the aspect with this name in the pool shown, against {@link AspectBook#ownedTier}; NONE when not in the pool. */
    static Mark markOf(String aspect) {
        return markOf(aspect, AspectBook::ownedTier);
    }

    /** Whether the pool shown holds the aspect with this name. */
    static boolean holds(String aspect) {
        return find(view(), aspect) != null;
    }

    /** How many aspects of the pool shown are marked NEW or TIER_UP. */
    static int markedCount() {
        return markedCount(AspectBook::ownedTier);
    }

    // ---- group 7: the pool from Wynnventory (design decision 10) ----

    /** Where the pool shown comes from. */
    enum Source {
        /** This week's pool from Wynnventory (or a stale one newer than the game's). */
        WYNNVENTORY,
        /** The pool the player saw in the raid reward preview menu. */
        GAME,
        /** Neither. */
        NONE
    }

    /** Where the pool shown comes from (decision 10, "Which source"). */
    static Source source() {
        return view().source();
    }

    /**
     * Pure, for tests: the source for this Wynnventory pool, whether its week is current, and
     * the game's {@code seenAt}. A current Wynnventory pool wins; else the newer of the two
     * (a stale Wynnventory pool by its newest raid timestamp); else whichever exists.
     */
    static Source choose(WeeklyPool api, boolean apiCurrent, long gameSeenAt) {
        boolean hasApi = api != null && api.present();
        boolean hasGame = gameSeenAt >= 0;
        if (hasApi && apiCurrent) {
            return Source.WYNNVENTORY;
        }
        if (hasApi && hasGame) {
            return api.newestSeenAt() > gameSeenAt ? Source.WYNNVENTORY : Source.GAME;
        }
        return hasApi ? Source.WYNNVENTORY : hasGame ? Source.GAME : Source.NONE;
    }

    /** Wynnventory's week; -1 for the game source or none. */
    static int week() {
        View v = view();
        return v.source() == Source.WYNNVENTORY ? v.api().week() : -1;
    }

    /** The raids in Wynnventory's data, the built-in five first in {@link RaidLog#RAIDS} order, then others by name; empty for the game. */
    static List<String> poolRaids() {
        View v = view();
        return v.source() == Source.WYNNVENTORY ? v.raids() : List.of();
    }

    /** The class's aspects in this raid, in Wynnventory's order; empty when none. */
    static List<PoolAspect> aspectsIn(String raid) {
        View v = view();
        if (v.source() != Source.WYNNVENTORY || raid == null) {
            return List.of();
        }
        return v.byRaid().getOrDefault(raid, List.of());
    }

    /** The full names of the raids that hold this aspect, in {@link #poolRaids()} order; empty for the game source or when none. */
    static List<String> raidsHolding(String aspect) {
        View v = view();
        if (v.source() != Source.WYNNVENTORY || aspect == null) {
            return List.of();
        }
        return v.raidsByAspect().getOrDefault(aspect.trim().toLowerCase(Locale.ROOT), List.of());
    }

    /** That raid's timestamp, epoch milliseconds; -1 when unknown. */
    static long raidSeenAt(String raid) {
        View v = view();
        if (v.source() != Source.WYNNVENTORY || raid == null) {
            return -1;
        }
        return v.raidSeen().getOrDefault(raid, -1L);
    }

    /** NOG, NOL, TCC, TNA, WTP; for another raid, the initials of its words. */
    static String shortName(String raid) {
        if (raid == null) {
            return "";
        }
        String known = SHORT.get(raid.trim());
        if (known != null) {
            return known;
        }
        StringBuilder b = new StringBuilder();
        for (String w : raid.trim().split(" ")) {
            for (int i = 0; i < w.length(); i++) {
                if (Character.isLetterOrDigit(w.charAt(i))) {
                    b.append(Character.toUpperCase(w.charAt(i)));
                    break;
                }
            }
        }
        return b.toString();
    }

    /**
     * Pure: the mark against an owned tier and the aspect's max tier (decision 10, "Marks").
     * A Wynnventory aspect (tier 0): owned 0 → NEW, 1 to max − 1 → TIER_UP, max or unknown
     * (-1, or no max known) → NONE. An aspect seen in game (tier 1 or more) keeps the rule of
     * {@link #mark(PoolAspect, int)}.
     */
    static Mark mark(PoolAspect aspect, int ownedTier, int maxTier) {
        if (aspect == null || ownedTier < 0) {
            return Mark.NONE;
        }
        if (aspect.tier() >= 1) {
            return mark(aspect, ownedTier);
        }
        if (ownedTier == 0) {
            return Mark.NEW;
        }
        return maxTier > 0 && ownedTier < maxTier ? Mark.TIER_UP : Mark.NONE;
    }

    /** The tier a TIER_UP tag names (game: the tier shown; Wynnventory: owned + 1); 0 otherwise. */
    static int tagTier(String aspect) {
        return tagTier(aspect, AspectBook::ownedTier);
    }

    /** For the no-game preview: this pool, seen at {@code seenAt} (epoch milliseconds; -1 for never seen). */
    static synchronized void preview(List<PoolAspect> pool, long seenAt) {
        previewing = true;
        loaded = true;
        RaidPool.pool = seenAt < 0 || pool == null ? List.of() : List.copyOf(pool);
        RaidPool.seenAt = seenAt < 0 ? -1 : seenAt;
    }

    // ---- also for tests ----

    /** {@link #markOf(String)} against these owned tiers (max tiers from {@link AspectBook#classAspects()}). */
    static Mark markOf(String aspect, ToIntFunction<String> ownedTier) {
        View v = view();
        PoolAspect a = find(v, aspect);
        return a == null ? Mark.NONE : markIn(v, a, ownedTier);
    }

    /** {@link #markedCount()} against these owned tiers. */
    static int markedCount(ToIntFunction<String> ownedTier) {
        View v = view();
        int n = 0;
        for (PoolAspect a : aspects()) {
            if (markIn(v, a, ownedTier) != Mark.NONE) {
                n++;
            }
        }
        return n;
    }

    /** {@link #tagTier(String)} against these owned tiers. */
    static int tagTier(String aspect, ToIntFunction<String> ownedTier) {
        View v = view();
        PoolAspect a = find(v, aspect);
        if (a == null || markIn(v, a, ownedTier) != Mark.TIER_UP) {
            return 0;
        }
        return a.tier() >= 1 ? a.tier() : ownedTier.applyAsInt(a.name()) + 1;
    }

    /** For tests: the clock the source is chosen by (null: the system clock). */
    static void clock(LongSupplier now) {
        clock = now == null ? System::currentTimeMillis : now;
        view = null;
    }

    /**
     * The preview menu showed these aspects at {@code now}: saved once per opening, and again
     * when the list changes while it is open (its slots load one by one).
     */
    static synchronized void seen(List<PoolAspect> shown, long now) {
        if (previewing || shown == null || shown.isEmpty()) {
            return;
        }
        if (!loaded) {
            load();
        }
        List<PoolAspect> copy = List.copyOf(shown);
        if (savedThisOpening && copy.equals(pool)) {
            return;
        }
        boolean changed = !copy.equals(pool);
        seenAt = now;
        pool = copy;
        savedThisOpening = true;
        if (changed) {
            System.out.println("[WynnLabs] raid reward pool seen: " + copy.size() + " aspects");
        }
        String text = toJson(copy, now);
        Saver.mark(file(), () -> text);
    }

    /** For tests: the file under {@code root} instead of the config folder; nothing in memory. */
    static synchronized void use(Path root) {
        reset();
        rootOverride = root;
    }

    /** For tests: forget what was seen (not the file) and go back to the config folder. */
    static synchronized void reset() {
        forget();
        rootOverride = null;
        tick = 0;
        clock = System::currentTimeMillis;
    }

    /** For tests: drop what is in memory, so the next {@link #load()} reads the file again. */
    static synchronized void forget() {
        pool = List.of();
        seenAt = -1;
        loaded = false;
        previewing = false;
        savedThisOpening = false;
        view = null;
    }

    /** The account-wide file. */
    static Path file() {
        return (rootOverride != null ? rootOverride : Dirs.config()).resolve(FILE);
    }

    /** Reads the file once (on a tick, never while drawing). */
    static synchronized void load() {
        loaded = true;
        Path f = file();
        Saver.settle(f);
        if (!Files.exists(f)) {
            return;
        }
        try {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            JsonObject o = GSON.fromJson(text, JsonObject.class);
            if (o == null) {
                return;
            }
            List<PoolAspect> list = new ArrayList<>();
            if (o.has("aspects") && o.get("aspects").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("aspects")) {
                    if (!e.isJsonObject()) {
                        continue;
                    }
                    JsonObject j = e.getAsJsonObject();
                    String name = str(j, "name");
                    if (name.isEmpty()) {
                        continue;
                    }
                    list.add(new PoolAspect(name, klass(str(j, "class")), Tier.of(str(j, "rarity")),
                            Math.max(1, j.has("tier") && j.get("tier").isJsonPrimitive() ? j.get("tier").getAsInt() : 1)));
                }
            }
            long at = o.has("seenAt") && o.get("seenAt").isJsonPrimitive() ? o.get("seenAt").getAsLong() : -1;
            seenAt = at < 0 ? -1 : at;
            pool = at < 0 ? List.of() : List.copyOf(list);
            Saver.known(f, text);
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not read " + FILE + ": " + e);
        }
    }

    // ---- inside ----

    /**
     * The view for the current inputs: rebuilt when the Wynnventory snapshot, the game's pool or
     * the class changes, and at least once a minute (the week may turn); else a field read.
     */
    private static View view() {
        long now = clock.getAsLong();
        WeeklyPool api = WynnventoryPools.raids();
        List<PoolAspect> game = pool;
        long gameAt = seenAt;
        Klass klass = AspectBook.klass();
        View v = view;
        if (v != null && v.api() == api && v.game() == game && v.gameSeenAt() == gameAt && v.klass() == klass
                && now >= v.checkedAt() && now - v.checkedAt() < RECHECK_MS) {
            return v;
        }
        boolean current = WynnventoryPools.current(api, now);
        Source source = choose(api, current, gameAt);
        if (v != null && v.api() == api && v.klass() == klass) {
            // only the time or the game's pool moved: keep the Wynnventory maps
            v = new View(api, game, gameAt, klass, now, source, current, v.aspects(), v.byName(), v.raids(),
                    v.byRaid(), v.raidsByAspect(), v.raidSeen());
        } else {
            v = build(api, game, gameAt, klass, now, source, current);
        }
        view = v;
        return v;
    }

    private static View build(WeeklyPool api, List<PoolAspect> game, long gameAt, Klass klass, long now, Source source,
            boolean current) {
        Map<String, List<PoolAspect>> byRaid = new HashMap<>();
        Map<String, Long> raidSeen = new HashMap<>();
        for (PoolRegion r : api.regions()) {
            List<PoolAspect> list = byRaid.computeIfAbsent(r.name(), k -> new ArrayList<>());
            raidSeen.merge(r.name(), (long) r.seenAt(), Math::max);
            for (PoolItem i : r.items()) {
                if (klass != Klass.NONE && i.aspect() && i.aspectClass() == klass
                        && list.stream().noneMatch(a -> a.name().equalsIgnoreCase(i.name()))) {
                    list.add(new PoolAspect(i.name(), klass, i.rarity(), 0));
                }
            }
        }
        List<String> raids = new ArrayList<>();
        for (String r : RaidLog.RAIDS) {
            if (byRaid.containsKey(r)) {
                raids.add(r);
            }
        }
        byRaid.keySet().stream().filter(r -> !RaidLog.RAIDS.contains(r)).sorted().forEach(raids::add);
        List<PoolAspect> aspects = new ArrayList<>();
        Map<String, PoolAspect> byName = new LinkedHashMap<>();
        Map<String, List<String>> holding = new HashMap<>();
        Map<String, List<PoolAspect>> frozen = new HashMap<>();
        for (String r : raids) {
            List<PoolAspect> in = byRaid.get(r);
            frozen.put(r, List.copyOf(in));
            for (PoolAspect a : in) {
                String k = a.name().toLowerCase(Locale.ROOT);
                if (byName.putIfAbsent(k, a) == null) {
                    aspects.add(a);
                }
                holding.computeIfAbsent(k, x -> new ArrayList<>()).add(r);
            }
        }
        Map<String, List<String>> frozenHolding = new HashMap<>();
        holding.forEach((k, l) -> frozenHolding.put(k, List.copyOf(l)));
        return new View(api, game, gameAt, klass, now, source, current, List.copyOf(aspects), Map.copyOf(byName),
                List.copyOf(raids), Map.copyOf(frozen), Map.copyOf(frozenHolding), Map.copyOf(raidSeen));
    }

    /** The aspect with this name in the view's source, or null. */
    private static PoolAspect find(View v, String aspect) {
        if (aspect == null || aspect.isBlank()) {
            return null;
        }
        String n = aspect.trim();
        if (v.source() == Source.WYNNVENTORY) {
            return v.byName().get(n.toLowerCase(Locale.ROOT));
        }
        if (v.source() == Source.GAME) {
            for (PoolAspect a : v.game()) {
                if (a.name().equalsIgnoreCase(n)) {
                    return a;
                }
            }
        }
        return null;
    }

    private static Mark markIn(View v, PoolAspect a, ToIntFunction<String> ownedTier) {
        int owned = ownedTier.applyAsInt(a.name());
        return v.source() == Source.WYNNVENTORY ? mark(a, owned, maxTier(a.name())) : mark(a, owned);
    }

    /** The aspect's tier count from the class's aspect data; -1 when unknown. */
    @SuppressWarnings("unchecked")
    private static int maxTier(String aspect) {
        List<AspectEntry> list = AspectBook.classAspects();
        Object[] cached = maxTiers;
        if (cached[0] != list) {
            Map<String, Integer> m = new HashMap<>();
            for (AspectEntry e : list) {
                m.put(e.name().toLowerCase(Locale.ROOT), e.tiers());
            }
            cached = new Object[] {list, Map.copyOf(m)};
            maxTiers = cached;
        }
        return ((Map<String, Integer>) cached[1]).getOrDefault(aspect.toLowerCase(Locale.ROOT), -1);
    }

    private static String age(long at, long now) {
        if (at < 0) {
            return "";
        }
        long min = Math.max(0, now - at) / MINUTE;
        if (min < 1) {
            return L.t("wynnlabs.raid.pool_age_now");
        }
        if (min < 60) {
            return L.t("wynnlabs.raid.pool_age_min", min);
        }
        if (min < 24 * 60) {
            return L.t("wynnlabs.raid.pool_age_hours", min / 60);
        }
        long days = min / (24 * 60);
        return L.n("wynnlabs.raid.pool_age_days", days);
    }

    private static Klass klass(String name) {
        try {
            return Klass.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Klass.NONE;
        }
    }

    private static String toJson(List<PoolAspect> list, long at) {
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT);
        o.addProperty("seenAt", at);
        JsonArray arr = new JsonArray();
        for (PoolAspect a : list) {
            JsonObject j = new JsonObject();
            j.addProperty("name", a.name());
            j.addProperty("class", (a.klass() == null ? Klass.NONE : a.klass()).name());
            j.addProperty("rarity", (a.rarity() == null ? Tier.NORMAL : a.rarity()).name());
            j.addProperty("tier", a.tier());
            arr.add(j);
        }
        o.add("aspects", arr);
        return GSON.toJson(o);
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
