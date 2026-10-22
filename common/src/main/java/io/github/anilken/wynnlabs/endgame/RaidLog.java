package io.github.anilken.wynnlabs.endgame;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.core.Dirs;
import io.github.anilken.wynnlabs.core.Profile;
import io.github.anilken.wynnlabs.core.Saver;
import io.github.anilken.wynnlabs.game.Wyn;
import io.github.anilken.wynnlabs.model.RaidDry;
import io.github.anilken.wynnlabs.model.RaidRun;
import io.github.anilken.wynnlabs.model.RaidSummary;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * The raids each character runs (openspec raid-tracking, design decisions 4 and 5): recorded
 * from Wynntils' raid events, kept in {@code characters/<id>/raids.json} through {@link Saver}
 * (newest 500 runs, with running totals per raid so the cap never lowers a count), and
 * summarised per raid, per session and as a streak. The dry counters are Wynntils' own and
 * count the whole account. Only reads; never clicks or opens anything.
 *
 * <p>API contract of group 1 (design.md, "API contract"): the signatures below stay as they
 * are; group 2 fills the bodies and may add private helpers and test hooks.
 */
public final class RaidLog {

    /** The built-in raids, in the order the summary lists them. */
    public static final List<String> RAIDS = List.of("Nest of the Grootslangs", "Orphion's Nexus of Light", "The Canyon Colossus",
            "The Nameless Anomaly", "The Wartorn Palace");

    /** The file's format number. */
    static final int FORMAT = 1;
    /** Runs kept per character, newest first; the running totals keep counting past it. */
    static final int CAP = 500;
    /** Raid mythics kept per character. */
    private static final int MYTHIC_CAP = 200;
    /** Runs (and mythics) held in memory while no character is known. */
    private static final int HELD_CAP = 50;
    private static final String FILE = "raids.json";
    private static final String PREVIEW_ID = "#preview";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One raid's running totals: never lowered when old runs fall off the history. */
    private static final class Total {
        int done;
        int failed;
        long bestMs = -1;
        long lastAt = -1;

        Total copy() {
            Total t = new Total();
            t.done = done;
            t.failed = failed;
            t.bestMs = bestMs;
            t.lastAt = lastAt;
            return t;
        }
    }

    /** A mythic aspect or tome from a raid reward chest. */
    private record Mythic(String item, boolean aspect, long at) {}

    /** One character's raid history, as in its file. */
    private static final class Book {
        final String id;
        /** Newest first. */
        final List<RaidRun> runs = new ArrayList<>();
        final Map<String, Total> totals = new LinkedHashMap<>();
        /** Newest first. */
        final List<Mythic> mythics = new ArrayList<>();
        int streak;

        Book(String id) {
            this.id = id;
        }
    }

    private static Path rootOverride;
    private static Supplier<String> idSource = Profile::id;

    /** The history of the character last seen (or the preview's). */
    private static Book book;
    private static boolean previewing;
    private static final List<RaidRun> held = new ArrayList<>();
    private static final List<Mythic> heldMythics = new ArrayList<>();
    /** Session counts per character id: {done, failed}; cleared on disconnect. */
    private static final Map<String, int[]> session = new HashMap<>();
    private static List<RaidSummary> summaryCache;
    private static Book summaryBook;

    private static String startedRaid;
    private static long startedAt = -1;
    private static volatile RaidDry dry = RaidDry.UNKNOWN;
    private static volatile boolean tracking = true;
    private static int tick;

    private RaidLog() {}

    // ---- fed by WynntilsEvents and WynnLabs ----

    /** A raid started (Wynntils' RaidStartedEvent): its English name, epoch milliseconds. */
    public static synchronized void started(String raid, long at) {
        startedRaid = raid;
        startedAt = at;
    }

    /**
     * A raid ended (RaidEndedEvent.Completed or .Failed): recorded for the character in play,
     * or held in memory until {@link Profile#id()} is known.
     */
    public static synchronized void record(RaidRun run) {
        if (run == null || run.raid() == null || run.raid().isBlank()) {
            return;
        }
        run = withStart(run);
        Book b = book();
        if (b == null) {
            if (held.size() < HELD_CAP) {
                held.add(run);
            }
            return;
        }
        add(b, run);
        save(b);
    }

    /**
     * A mythic from a raid reward chest (ValuableFoundEvent, RAID_REWARD_CHEST): the item's
     * English name, whether it is an aspect (else a tome or another item), epoch milliseconds.
     */
    public static synchronized void mythic(String item, boolean aspect, long at) {
        if (item == null || item.isBlank()) {
            return;
        }
        Mythic m = new Mythic(item, aspect, at);
        tick = 0;                               // Wynntils' dry counters change now: read them on the next tick
        Book b = book();
        if (b == null) {
            if (heldMythics.size() < HELD_CAP) {
                heldMythics.add(m);
            }
            return;
        }
        addMythic(b, m);
        save(b);
    }

    /** Every client tick: loads the character's file when the character changes, writes held runs. */
    public static void onTick(Minecraft mc) {
        if (++tick % 20 != 1 || mc == null || mc.player == null) {
            return;
        }
        tick();
        RaidDry d = Wyn.raidDry();
        boolean on = Wyn.raidTrackingOn();
        synchronized (RaidLog.class) {
            if (!previewing) {
                dry = d;
                tracking = on;
            }
        }
    }

    /** Left the server: the session counts start again. */
    public static synchronized void disconnected() {
        session.clear();
        startedRaid = null;
        startedAt = -1;
    }

    // ---- read by the Endgame tab ----

    /** This character's kept runs, newest first; empty when none. */
    public static synchronized List<RaidRun> runs() {
        Book b = view();
        return b == null ? List.of() : List.copyOf(b.runs);
    }

    /**
     * One row per raid: the built-in five in {@link #RAIDS} order (rows with zero runs kept),
     * then any other raid a run names, by name.
     */
    public static synchronized List<RaidSummary> summaries() {
        Book b = view();
        if (summaryCache != null && summaryBook == b) {
            return summaryCache;
        }
        List<String> names = new ArrayList<>(RAIDS);
        if (b != null) {
            TreeSet<String> others = new TreeSet<>(b.totals.keySet());
            b.runs.forEach(r -> others.add(r.raid()));
            others.removeAll(RAIDS);
            names.addAll(others);
        }
        List<RaidSummary> out = new ArrayList<>();
        for (String name : names) {
            out.add(b == null ? RaidSummary.empty(name) : summary(b, name));
        }
        summaryCache = List.copyOf(out);
        summaryBook = b;
        return summaryCache;
    }

    /** Raids completed this session (since joining the server). */
    public static synchronized int sessionDone() {
        Book b = view();
        return b == null ? 0 : session.getOrDefault(b.id, new int[2])[0];
    }

    /** Raids failed this session. */
    public static synchronized int sessionFailed() {
        Book b = view();
        return b == null ? 0 : session.getOrDefault(b.id, new int[2])[1];
    }

    /** Completed raids in a row for this character, ended by a failure (persisted). */
    public static synchronized int streak() {
        Book b = view();
        return b == null ? 0 : b.streak;
    }

    /** Wynntils' dry counters (whole account), -1 for each one unknown. */
    public static RaidDry dry() {
        return dry;
    }

    /** Whether Wynntils' own raid tracking (trackRaids) is on; nothing is recorded while it is off. */
    public static boolean trackingOn() {
        return tracking;
    }

    // ---- preview ----

    /**
     * For the no-game preview: these runs (newest first), streak, session counts and dry
     * counters for the character in play, instead of its file.
     */
    public static synchronized void preview(List<RaidRun> runs, int streak, int sessionDone, int sessionFailed, RaidDry dry) {
        previewing = true;
        Book b = new Book(PREVIEW_ID);
        List<RaidRun> oldestFirst = new ArrayList<>(runs == null ? List.of() : runs);
        java.util.Collections.reverse(oldestFirst);
        for (RaidRun r : oldestFirst) {
            add(b, r);
        }
        b.streak = streak;
        session.put(PREVIEW_ID, new int[] {sessionDone, sessionFailed});
        book = b;
        summaryCache = null;
        RaidLog.dry = dry == null ? RaidDry.UNKNOWN : dry;
        tracking = true;
    }

    // ---- test hooks ----

    /** For tests: files under {@code root} instead of the config folder, the character id from {@code id}. */
    static synchronized void use(Path root, Supplier<String> id) {
        reset();
        rootOverride = root;
        idSource = id;
    }

    /** For tests: forget everything in memory and go back to the config folder and {@link Profile#id()}. */
    static synchronized void reset() {
        forget();
        held.clear();
        heldMythics.clear();
        session.clear();
        rootOverride = null;
        idSource = Profile::id;
        dry = RaidDry.UNKNOWN;
        tracking = true;
        startedRaid = null;
        startedAt = -1;
        tick = 0;
    }

    /** For tests: drop the loaded history (not the files), so the next read loads it again. */
    static synchronized void forget() {
        book = null;
        previewing = false;
        summaryCache = null;
        summaryBook = null;
    }

    /** What {@link #onTick} does once a second, without the game: load the character, write held runs. */
    static synchronized void tick() {
        if (previewing) {
            return;
        }
        Book b = book();
        if (b == null || held.isEmpty() && heldMythics.isEmpty()) {
            return;
        }
        held.forEach(r -> add(b, r));
        heldMythics.forEach(m -> addMythic(b, m));
        held.clear();
        heldMythics.clear();
        save(b);
    }

    /** This character's file. */
    static Path file(String id) {
        return root().resolve("characters").resolve(id).resolve(FILE);
    }

    // ---- inside ----

    private static Path root() {
        return rootOverride != null ? rootOverride : Dirs.config();
    }

    private static String id() {
        try {
            String id = idSource.get();
            return id == null ? "" : id;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The loaded history of the character in play, loading it when the character changed; null while unknown. */
    private static Book book() {
        if (previewing) {
            return book;
        }
        String id = id();
        if (id.isEmpty()) {
            return null;
        }
        if (book == null || !book.id.equals(id)) {
            book = load(id);
            summaryCache = null;
        }
        return book;
    }

    /** What the tab reads: the loaded history when it is the character in play; never loads (drawing). */
    private static Book view() {
        if (previewing) {
            return book;
        }
        return book != null && book.id.equals(id()) ? book : null;
    }

    /** The start time fills a missing duration when the run is the raid that started. */
    private static RaidRun withStart(RaidRun run) {
        RaidRun out = run;
        if (run.durationMs() < 0 && startedAt > 0 && run.endedAt() >= startedAt
                && startedRaid != null && startedRaid.equalsIgnoreCase(run.raid())) {
            out = new RaidRun(run.raid(), run.abbreviation(), run.completed(), run.endedAt(), run.endedAt() - startedAt,
                    run.rooms());
        }
        startedRaid = null;
        startedAt = -1;
        return out;
    }

    /** A built-in raid's own spelling when the name matches it but for case. */
    private static String canonical(String raid) {
        String r = raid.trim();
        for (String known : RAIDS) {
            if (known.equalsIgnoreCase(r)) {
                return known;
            }
        }
        return r;
    }

    private static void add(Book b, RaidRun run) {
        String name = canonical(run.raid());
        if (!name.equals(run.raid())) {
            run = new RaidRun(name, run.abbreviation(), run.completed(), run.endedAt(), run.durationMs(), run.rooms());
        }
        int at = 0;
        while (at < b.runs.size() && b.runs.get(at).endedAt() > run.endedAt()) {
            at++;
        }
        b.runs.add(at, run);
        while (b.runs.size() > CAP) {
            b.runs.remove(b.runs.size() - 1);
        }
        Total t = b.totals.computeIfAbsent(name, k -> new Total());
        if (run.completed()) {
            t.done++;
            if (run.durationMs() >= 0 && (t.bestMs < 0 || run.durationMs() < t.bestMs)) {
                t.bestMs = run.durationMs();
            }
        } else {
            t.failed++;
        }
        t.lastAt = Math.max(t.lastAt, run.endedAt());
        b.streak = run.completed() ? b.streak + 1 : 0;
        int[] s = session.computeIfAbsent(b.id, k -> new int[2]);
        s[run.completed() ? 0 : 1]++;
        summaryCache = null;
    }

    private static void addMythic(Book b, Mythic m) {
        b.mythics.add(0, m);
        while (b.mythics.size() > MYTHIC_CAP) {
            b.mythics.remove(b.mythics.size() - 1);
        }
    }

    private static RaidSummary summary(Book b, String name) {
        Total t = b.totals.get(name);
        RaidRun last = null;
        long best = t == null ? -1 : t.bestMs;
        long sum = 0;
        int timed = 0;
        List<RaidRun> completed = new ArrayList<>();
        for (RaidRun r : b.runs) {
            if (!r.raid().equals(name)) {
                continue;
            }
            if (last == null) {
                last = r;
            }
            if (r.completed() && r.durationMs() >= 0) {
                completed.add(r);
                sum += r.durationMs();
                timed++;
                if (best < 0 || r.durationMs() < best) {
                    best = r.durationMs();
                }
            }
        }
        if (t == null && last == null) {
            return RaidSummary.empty(name);
        }
        int done = t == null ? 0 : t.done;
        int failed = t == null ? 0 : t.failed;
        long lastAt = Math.max(t == null ? -1 : t.lastAt, last == null ? -1 : last.endedAt());
        long average = timed == 0 ? -1 : Math.round((double) sum / timed);
        return new RaidSummary(name, done, failed, best, average, lastAt, last, bestRooms(completed));
    }

    /** Each room's best time over these completed runs, in the order of the run with the most rooms. */
    private static List<RaidRun.Room> bestRooms(List<RaidRun> completed) {
        List<String> order = new ArrayList<>();
        Map<String, Long> best = new HashMap<>();
        RaidRun longest = null;
        for (RaidRun r : completed) {
            if (longest == null || r.rooms().size() > longest.rooms().size()) {
                longest = r;
            }
            for (RaidRun.Room room : r.rooms()) {
                if (room.name() == null) {
                    continue;
                }
                if (room.ms() >= 0) {
                    best.merge(room.name(), room.ms(), Math::min);
                }
            }
        }
        if (longest != null) {
            longest.rooms().forEach(room -> order.add(room.name()));
        }
        for (RaidRun r : completed) {
            for (RaidRun.Room room : r.rooms()) {
                if (room.name() != null && !order.contains(room.name())) {
                    order.add(room.name());
                }
            }
        }
        List<RaidRun.Room> out = new ArrayList<>();
        for (String n : order) {
            if (n != null) {
                out.add(new RaidRun.Room(n, best.getOrDefault(n, -1L)));
            }
        }
        return out;
    }

    // ---- the file ----

    private static void save(Book b) {
        if (previewing || PREVIEW_ID.equals(b.id)) {
            return;
        }
        List<RaidRun> runs = List.copyOf(b.runs);
        Map<String, Total> totals = new LinkedHashMap<>();
        b.totals.forEach((k, v) -> totals.put(k, v.copy()));
        List<Mythic> mythics = List.copyOf(b.mythics);
        int streak = b.streak;
        Saver.mark(file(b.id), () -> toJson(runs, totals, mythics, streak));
    }

    private static String toJson(List<RaidRun> runs, Map<String, Total> totals, List<Mythic> mythics, int streak) {
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT);
        o.addProperty("streak", streak);
        JsonObject tot = new JsonObject();
        totals.forEach((name, t) -> {
            JsonObject j = new JsonObject();
            j.addProperty("done", t.done);
            j.addProperty("failed", t.failed);
            j.addProperty("bestMs", t.bestMs);
            j.addProperty("lastAt", t.lastAt);
            tot.add(name, j);
        });
        o.add("totals", tot);
        JsonArray arr = new JsonArray();
        for (RaidRun r : runs) {
            JsonObject j = new JsonObject();
            j.addProperty("raid", r.raid());
            j.addProperty("abbreviation", r.abbreviation());
            j.addProperty("completed", r.completed());
            j.addProperty("endedAt", r.endedAt());
            j.addProperty("durationMs", r.durationMs());
            JsonArray rooms = new JsonArray();
            for (RaidRun.Room room : r.rooms()) {
                JsonObject rj = new JsonObject();
                rj.addProperty("name", room.name());
                rj.addProperty("ms", room.ms());
                rooms.add(rj);
            }
            j.add("rooms", rooms);
            arr.add(j);
        }
        o.add("runs", arr);
        JsonArray ms = new JsonArray();
        for (Mythic m : mythics) {
            JsonObject j = new JsonObject();
            j.addProperty("item", m.item());
            j.addProperty("aspect", m.aspect());
            j.addProperty("at", m.at());
            ms.add(j);
        }
        o.add("mythics", ms);
        return GSON.toJson(o);
    }

    private static Book load(String id) {
        Book b = new Book(id);
        Path f = file(id);
        Saver.settle(f);
        if (!Files.exists(f)) {
            return b;
        }
        try {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            JsonObject o = GSON.fromJson(text, JsonObject.class);
            if (o == null) {
                return b;
            }
            b.streak = num(o, "streak", 0);
            if (o.has("totals") && o.get("totals").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("totals").entrySet()) {
                    if (!e.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject j = e.getValue().getAsJsonObject();
                    Total t = new Total();
                    t.done = num(j, "done", 0);
                    t.failed = num(j, "failed", 0);
                    t.bestMs = lng(j, "bestMs", -1);
                    t.lastAt = lng(j, "lastAt", -1);
                    b.totals.put(canonical(e.getKey()), t);
                }
            }
            if (o.has("runs") && o.get("runs").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("runs")) {
                    if (!e.isJsonObject()) {
                        continue;
                    }
                    JsonObject j = e.getAsJsonObject();
                    String raid = str(j, "raid");
                    if (raid.isEmpty()) {
                        continue;
                    }
                    List<RaidRun.Room> rooms = new ArrayList<>();
                    if (j.has("rooms") && j.get("rooms").isJsonArray()) {
                        for (JsonElement re : j.getAsJsonArray("rooms")) {
                            if (re.isJsonObject()) {
                                rooms.add(new RaidRun.Room(str(re.getAsJsonObject(), "name"), lng(re.getAsJsonObject(), "ms", -1)));
                            }
                        }
                    }
                    b.runs.add(new RaidRun(canonical(raid), str(j, "abbreviation"),
                            j.has("completed") && j.get("completed").getAsBoolean(), lng(j, "endedAt", 0),
                            lng(j, "durationMs", -1), rooms));
                }
                b.runs.sort((x, y) -> Long.compare(y.endedAt(), x.endedAt()));
                while (b.runs.size() > CAP) {
                    b.runs.remove(b.runs.size() - 1);
                }
            }
            // a file written before a raid had a total (or edited by hand): count its runs
            for (RaidRun r : b.runs) {
                b.totals.computeIfAbsent(r.raid(), k -> {
                    Total t = new Total();
                    for (RaidRun x : b.runs) {
                        if (x.raid().equals(k)) {
                            if (x.completed()) {
                                t.done++;
                            } else {
                                t.failed++;
                            }
                            t.lastAt = Math.max(t.lastAt, x.endedAt());
                        }
                    }
                    return t;
                });
            }
            if (o.has("mythics") && o.get("mythics").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("mythics")) {
                    if (e.isJsonObject()) {
                        JsonObject j = e.getAsJsonObject();
                        b.mythics.add(new Mythic(str(j, "item"), j.has("aspect") && j.get("aspect").getAsBoolean(),
                                lng(j, "at", 0)));
                    }
                }
            }
            Saver.known(f, text);
        } catch (Exception e) {
            // a broken file: kept aside so the next save does not lose what can still be read by hand
            System.out.println("[WynnLabs] could not read " + f + ": " + e);
            try {
                Files.move(f, f.resolveSibling(FILE + ".broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
                // left as it is
            }
            return new Book(id);
        }
        return b;
    }

    private static int num(JsonObject o, String k, int fallback) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : fallback;
    }

    private static long lng(JsonObject o, String k, long fallback) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : fallback;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
