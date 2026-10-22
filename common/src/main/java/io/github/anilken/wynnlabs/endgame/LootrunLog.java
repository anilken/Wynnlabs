package io.github.anilken.wynnlabs.endgame;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.anilken.wynnlabs.core.Dirs;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Profile;
import io.github.anilken.wynnlabs.core.Saver;
import io.github.anilken.wynnlabs.game.Wyn;
import io.github.anilken.wynnlabs.model.CampMythics;
import io.github.anilken.wynnlabs.model.LootrunRun;
import io.github.anilken.wynnlabs.model.LootrunTotals;
import io.github.anilken.wynnlabs.model.MythicFind;
import io.github.anilken.wynnlabs.model.PoolItem;
import io.github.anilken.wynnlabs.model.PoolRegion;
import io.github.anilken.wynnlabs.model.WeeklyPool;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The lootruns each character runs (openspec lootrun-tracking, design decisions 4 and 6): beacon
 * colours counted from Wynntils' beacon events and missions snapshot once a second while a run
 * is going on (so they survive Wynntils clearing them at the end), the camp from the territory
 * where the run started, the run recorded when it ends, in {@code characters/<id>/lootruns.json}
 * through {@link Saver} (newest 500 runs). A mythic from the reward chest within 10 minutes of
 * the last completed run marks that run and resets the dry streak. Only reads.
 *
 * <p>API contract of group 1 (design.md, "API contract"): the signatures below stay as they
 * are; group 3 fills the bodies and may add private helpers and test hooks.
 */
public final class LootrunLog {

    /** A mythic found this long after the last completed run still belongs to it, milliseconds (10 minutes). */
    static final long MYTHIC_WINDOW_MS = 10L * 60 * 1000;

    /** Runs kept per character file; older ones are dropped. */
    static final int CAP = 500;

    /** The file's format number. */
    private static final int FORMAT = 1;

    /** How long after a run starts the camp is still looked up (the player walks off soon). */
    private static final long CAMP_LOOKUP_MS = 30_000;

    /** A run Wynntils stopped reporting this long ago without an end event is dropped. */
    private static final long IDLE_DROP_MS = 60_000;

    /** One character's history, as kept in its file. */
    private static final class Data {
        final List<LootrunRun> runs = new ArrayList<>();      // newest first
        final List<MythicFind> mythics = new ArrayList<>();   // newest first
        int dryRuns;
        int dryPulls;
    }

    /** What is collected while a run is going on. */
    private static final class Current {
        final long startedAt;
        String camp;
        final Map<String, Integer> beacons = new LinkedHashMap<>();
        final List<String> missions = new ArrayList<>();
        long idleSince = -1;

        Current(long startedAt) {
            this.startedAt = startedAt;
        }
    }

    private static Data data = new Data();
    /** The character whose file {@link #data} is; null until one is known. */
    private static String loadedId;
    /** What happened before a character was known, replayed onto its file when it is. */
    private static final List<Consumer<Data>> pending = new ArrayList<>();
    private static Current current;
    private static boolean previewing;
    /** Where the character folders are; null = {@code config/wynnlabs/characters}. */
    private static Path rootOverride;
    private static int tick;

    /** {@link #runs()} with the camp fallback filled in, rebuilt when the runs or the language change. */
    private static List<LootrunRun> view = List.of();
    private static int version;
    private static int viewVersion = -1;
    private static String viewLabel = "";

    private LootrunLog() {}

    // ---- fed by WynntilsEvents and WynnLabs ----

    /**
     * A lootrun ended (LootrunFinishedEvent.Completed or .Failed): recorded for the character in
     * play with the camp, beacons and missions collected during the run. Failed runs pass 0 for
     * pulls, rerolls, sacrifices and xp.
     *
     * @param durationS the run's time, seconds
     * @param endedAt   epoch milliseconds
     */
    public static synchronized void finished(boolean completed, int challenges, int durationS, int pulls, int rerolls, int sacrifices, int xp,
            long endedAt) {
        if (previewing) {
            return;
        }
        Current c = current;
        current = null;
        LootrunRun run = new LootrunRun(completed, endedAt, Math.max(0, durationS), Math.max(0, challenges),
                completed ? Math.max(0, pulls) : 0, completed ? Math.max(0, rerolls) : 0,
                completed ? Math.max(0, sacrifices) : 0, completed ? Math.max(0, xp) : 0,
                c == null || c.camp == null ? "" : c.camp,
                c == null ? Map.of() : c.beacons, c == null ? List.of() : c.missions, null);
        apply(d -> addRun(d, run));
    }

    /** A beacon was picked (LootrunBeaconSelectedEvent): its colour's English name ("Blue", "Rainbow"). */
    public static synchronized void beacon(String colour) {
        if (previewing || colour == null || colour.isBlank()) {
            return;
        }
        if (current == null) {
            current = new Current(System.currentTimeMillis());
        }
        current.beacons.merge(colour.trim(), 1, Integer::sum);
    }

    /** A mythic from a lootrun reward chest (ValuableFoundEvent, LOOTRUN_REWARD_CHEST): its English name, epoch milliseconds. */
    public static synchronized void mythic(String item, long at) {
        if (previewing || item == null || item.isBlank()) {
            return;
        }
        String name = item.trim();
        apply(d -> addMythic(d, name, at));
    }

    /**
     * Every client tick: loads the character's file when the character changes, notes the camp
     * when a run starts, snapshots missions once a second during a run.
     */
    public static void onTick(Minecraft mc) {
        if (++tick % 20 != 0 || mc.player == null || previewing) {
            return;
        }
        if (Profile.settled()) {
            switchTo(Profile.id());
        }
        LootrunRun.State state = Wyn.lootrunState();
        if (state == LootrunRun.State.UNKNOWN) {
            return;
        }
        String camp = null;
        synchronized (LootrunLog.class) {
            boolean wantCamp = state.running()
                    && (current == null || current.camp == null && System.currentTimeMillis() - current.startedAt < CAMP_LOOKUP_MS);
            if (wantCamp) {
                camp = Wyn.territoryAt(mc.player.position());
            }
        }
        step(state, camp, state.running() ? Wyn.lootrunMissions() : List.of(), System.currentTimeMillis());
    }

    /** Left the server: a run in progress is dropped (nothing is recorded without Wynntils' end event). */
    public static synchronized void disconnected() {
        current = null;
    }

    // ---- read by the Endgame tab ----

    /** This character's kept runs, newest first; empty when none. */
    public static synchronized List<LootrunRun> runs() {
        String label = L.t("wynnlabs.lootrun.unknown_camp");
        if (viewVersion != version || !label.equals(viewLabel)) {
            List<LootrunRun> out = new ArrayList<>(data.runs.size());
            for (LootrunRun r : data.runs) {
                out.add(r.camp().isBlank() ? withCamp(r, label) : r);
            }
            view = Collections.unmodifiableList(out);
            viewVersion = version;
            viewLabel = label;
        }
        return view;
    }

    /** Totals over all kept runs; {@link LootrunTotals#NONE} when none. */
    public static synchronized LootrunTotals totals() {
        return totalsOf(data.runs);
    }

    /** Runs since this character's last lootrun mythic (all its runs when none yet). */
    public static synchronized int dryRuns() {
        return data.dryRuns;
    }

    /** Reward pulls since this character's last lootrun mythic. */
    public static synchronized int dryPulls() {
        return data.dryPulls;
    }

    /** Every mythic this character found in a lootrun reward chest, newest first. */
    public static synchronized List<MythicFind> mythics() {
        return List.copyOf(data.mythics);
    }

    /** Wynntils' own pulls without a mythic (whole account, for the tooltip); -1 when unknown. */
    public static int accountDryPulls() {
        return previewing ? -1 : Wyn.lootrunDryPulls();
    }

    // ---- group 7: this week's mythics from Wynnventory (design decision 10) ----

    /** The mythic camps of one loot pool snapshot, built once per snapshot. */
    private record Camps(WeeklyPool pool, List<CampMythics> mythic, List<String> without) {}

    private static volatile Camps camps = new Camps(WeeklyPool.NONE, List.of(), List.of());

    /**
     * Camps of {@link WynnventoryPools#loot()} with at least one mythic, by count, then camp name;
     * empty without data. Counts distinct, non-shiny mythic gear names (each listed once).
     */
    public static List<CampMythics> mythicCamps() {
        return camps().mythic();
    }

    /** The other camps of that data, by name. */
    public static List<String> campsWithoutMythic() {
        return camps().without();
    }

    /** The newest camp timestamp, epoch milliseconds; -1 when unknown. */
    static long poolSeenAt() {
        return WynnventoryPools.loot().newestSeenAt();
    }

    /** Whether the loot pool's week is not current at {@code now}, or there is no data. */
    public static boolean poolStale(long now) {
        return !WynnventoryPools.current(WynnventoryPools.loot(), now);
    }

    /** The loot pool's week; -1 when there is no data. */
    public static int poolWeek() {
        WeeklyPool p = WynnventoryPools.loot();
        return p.present() ? p.week() : -1;
    }

    /** Only mythic gear counts; runes, keys, tomes and other gear are ignored. */
    private static Camps camps() {
        WeeklyPool p = WynnventoryPools.loot();
        Camps c = camps;
        if (c.pool() == p) {
            return c;
        }
        Map<String, List<String>> names = new LinkedHashMap<>();
        Map<String, Long> seen = new LinkedHashMap<>();
        for (PoolRegion r : p.regions()) {
            List<String> list = names.computeIfAbsent(r.name(), k -> new ArrayList<>());
            seen.merge(r.name(), r.seenAt(), Math::max);
            for (PoolItem i : r.items()) {
                // each name once; a shiny entry is the same mythic again (design decision 11)
                if (i.mythicGear() && !i.shiny() && list.stream().noneMatch(n -> n.equalsIgnoreCase(i.name()))) {
                    list.add(i.name());
                }
            }
        }
        List<CampMythics> mythic = new ArrayList<>();
        List<String> without = new ArrayList<>();
        names.forEach((camp, list) -> {
            if (list.isEmpty()) {
                without.add(camp);
            } else {
                mythic.add(new CampMythics(camp, list, seen.get(camp)));
            }
        });
        mythic.sort(java.util.Comparator.comparingInt(CampMythics::count).reversed().thenComparing(CampMythics::camp));
        Collections.sort(without);
        c = new Camps(p, List.copyOf(mythic), List.copyOf(without));
        camps = c;
        return c;
    }

    // ---- preview ----

    /**
     * For the no-game preview: these runs (newest first), mythics (newest first) and dry streak
     * for the character in play, instead of its file.
     */
    public static synchronized void preview(List<LootrunRun> runs, List<MythicFind> mythics, int dryRuns, int dryPulls) {
        previewing = true;
        current = null;
        pending.clear();
        Data d = new Data();
        if (runs != null) {
            d.runs.addAll(runs);
        }
        if (mythics != null) {
            d.mythics.addAll(mythics);
        }
        d.dryRuns = Math.max(0, dryRuns);
        d.dryPulls = Math.max(0, dryPulls);
        data = d;
        version++;
    }

    // ---- the work, without Minecraft (tests call these) ----

    /**
     * Once a second with what Wynntils shows: a run starting opens the collection (with the camp
     * when known), missions are merged in while it runs, and a run that went idle without an end
     * event for a minute is dropped.
     */
    static synchronized void step(LootrunRun.State state, String camp, List<String> missions, long now) {
        if (previewing || state == null || state == LootrunRun.State.UNKNOWN) {
            return;
        }
        if (!state.running()) {
            if (current != null) {
                if (current.idleSince < 0) {
                    current.idleSince = now;
                } else if (now - current.idleSince >= IDLE_DROP_MS) {
                    current = null;
                }
            }
            return;
        }
        if (current == null) {
            current = new Current(now);
        }
        current.idleSince = -1;
        if (current.camp == null && camp != null && !camp.isBlank()) {
            current.camp = camp.trim();
        }
        if (missions != null) {
            for (String m : missions) {
                if (m != null && !m.isBlank() && !current.missions.contains(m.trim())) {
                    current.missions.add(m.trim());
                }
            }
        }
    }

    /**
     * The character in play is {@code id}: its file is read once when it changes, and whatever
     * happened before any character was known is added to it.
     */
    static synchronized void switchTo(String id) {
        if (previewing || id == null || id.isBlank() || id.equals(loadedId)) {
            return;
        }
        boolean first = loadedId == null;
        loadedId = id;
        data = load(file(id));
        version++;
        if (first && !pending.isEmpty()) {
            for (Consumer<Data> op : pending) {
                op.accept(data);
            }
            pending.clear();
            save();
        }
    }

    /** Pure: totals over these runs. */
    static LootrunTotals totalsOf(List<LootrunRun> runs) {
        if (runs == null || runs.isEmpty()) {
            return LootrunTotals.NONE;
        }
        int done = 0;
        int pulls = 0;
        for (LootrunRun r : runs) {
            if (r.completed()) {
                done++;
            }
            pulls += r.pulls();
        }
        int pct = (int) Math.round(100.0 * done / runs.size());
        return new LootrunTotals(runs.size(), done, pct, pulls, done == 0 ? -1 : (double) pulls / done);
    }

    /** For tests: character folders under this directory instead of the config folder. */
    static synchronized void useRoot(Path charactersRoot) {
        rootOverride = charactersRoot;
    }

    /** For tests: forget everything held in memory (as after a restart); the root override stays. */
    static synchronized void reset() {
        data = new Data();
        loadedId = null;
        pending.clear();
        current = null;
        previewing = false;
        tick = 0;
        view = List.of();
        version++;
    }

    /** For tests and the merger: this character's file. */
    static Path file(String id) {
        Path root = rootOverride != null ? rootOverride : Dirs.config().resolve("characters");
        return root.resolve(id).resolve("lootruns.json");
    }

    // ---- helpers ----

    /** Applies a change to the character in play and saves it; held until a character is known. */
    private static void apply(Consumer<Data> op) {
        op.accept(data);
        version++;
        if (loadedId == null) {
            pending.add(op);
        } else {
            save();
        }
    }

    private static void addRun(Data d, LootrunRun run) {
        d.runs.add(0, run);
        d.runs.sort((a, b) -> Long.compare(b.endedAt(), a.endedAt()));
        while (d.runs.size() > CAP) {
            d.runs.remove(d.runs.size() - 1);
        }
        d.dryRuns++;
        d.dryPulls += run.pulls();
    }

    private static void addMythic(Data d, String name, long at) {
        // the newest completed run, when its reward chest can still be the one opened
        for (int i = 0; i < d.runs.size(); i++) {
            LootrunRun r = d.runs.get(i);
            if (!r.completed()) {
                continue;
            }
            if (r.mythic() == null && at >= r.endedAt() && at - r.endedAt() <= MYTHIC_WINDOW_MS) {
                d.runs.set(i, r.withMythic(name));
            }
            break;
        }
        d.mythics.add(0, new MythicFind(name, at));
        d.mythics.sort((a, b) -> Long.compare(b.at(), a.at()));
        d.dryRuns = 0;
        d.dryPulls = 0;
    }

    private static LootrunRun withCamp(LootrunRun r, String camp) {
        return new LootrunRun(r.completed(), r.endedAt(), r.durationS(), r.challenges(), r.pulls(), r.rerolls(),
                r.sacrifices(), r.xp(), camp, r.beacons(), r.missions(), r.mythic());
    }

    private static void save() {
        if (previewing || loadedId == null) {
            return;
        }
        Path f = file(loadedId);
        String text = toJson(data).toString();
        Saver.mark(f, () -> text);
    }

    private static Data load(Path f) {
        Saver.settle(f);
        Data d = new Data();
        if (!Files.exists(f)) {
            return d;
        }
        try {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            if (o.has("runs")) {
                for (JsonElement el : o.getAsJsonArray("runs")) {
                    d.runs.add(runFrom(el.getAsJsonObject()));
                }
            }
            if (o.has("mythics")) {
                for (JsonElement el : o.getAsJsonArray("mythics")) {
                    JsonObject m = el.getAsJsonObject();
                    d.mythics.add(new MythicFind(str(m, "name"), num(m, "at")));
                }
            }
            if (o.has("dry")) {
                JsonObject dry = o.getAsJsonObject("dry");
                d.dryRuns = (int) num(dry, "runs");
                d.dryPulls = (int) num(dry, "pulls");
            }
            d.runs.sort((a, b) -> Long.compare(b.endedAt(), a.endedAt()));
            d.mythics.sort((a, b) -> Long.compare(b.at(), a.at()));
            Saver.known(f, text);
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not read " + f.getFileName() + ": " + e);
        }
        return d;
    }

    private static JsonObject toJson(Data d) {
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT);
        JsonArray runs = new JsonArray();
        for (LootrunRun r : d.runs) {
            JsonObject j = new JsonObject();
            j.addProperty("completed", r.completed());
            j.addProperty("endedAt", r.endedAt());
            j.addProperty("durationS", r.durationS());
            j.addProperty("challenges", r.challenges());
            j.addProperty("pulls", r.pulls());
            j.addProperty("rerolls", r.rerolls());
            j.addProperty("sacrifices", r.sacrifices());
            j.addProperty("xp", r.xp());
            j.addProperty("camp", r.camp());
            JsonObject beacons = new JsonObject();
            r.beacons().forEach(beacons::addProperty);
            j.add("beacons", beacons);
            JsonArray missions = new JsonArray();
            r.missions().forEach(missions::add);
            j.add("missions", missions);
            if (r.mythic() != null) {
                j.addProperty("mythic", r.mythic());
            }
            runs.add(j);
        }
        o.add("runs", runs);
        JsonObject dry = new JsonObject();
        dry.addProperty("runs", d.dryRuns);
        dry.addProperty("pulls", d.dryPulls);
        o.add("dry", dry);
        JsonArray mythics = new JsonArray();
        for (MythicFind m : d.mythics) {
            JsonObject j = new JsonObject();
            j.addProperty("name", m.name());
            j.addProperty("at", m.at());
            mythics.add(j);
        }
        o.add("mythics", mythics);
        return o;
    }

    private static LootrunRun runFrom(JsonObject j) {
        Map<String, Integer> beacons = new LinkedHashMap<>();
        if (j.has("beacons") && j.get("beacons").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("beacons").entrySet()) {
                beacons.put(e.getKey(), e.getValue().getAsInt());
            }
        }
        List<String> missions = new ArrayList<>();
        if (j.has("missions") && j.get("missions").isJsonArray()) {
            j.getAsJsonArray("missions").forEach(e -> missions.add(e.getAsString()));
        }
        String mythic = j.has("mythic") && !j.get("mythic").isJsonNull() ? j.get("mythic").getAsString() : null;
        return new LootrunRun(j.has("completed") && j.get("completed").getAsBoolean(), num(j, "endedAt"),
                (int) num(j, "durationS"), (int) num(j, "challenges"), (int) num(j, "pulls"), (int) num(j, "rerolls"),
                (int) num(j, "sacrifices"), (int) num(j, "xp"), str(j, "camp"), beacons, missions, mythic);
    }

    private static long num(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : 0;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
