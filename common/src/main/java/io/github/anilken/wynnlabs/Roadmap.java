package io.github.anilken.wynnlabs;

import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.Slot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The road ahead, level by level: at each milestone the gear that becomes the best for its slot
 * (with price and where it comes from), the ability tree nodes planned for those levels and
 * places to level. Built from Wynntils' item and map data, the rules and the saved ability
 * plan; no network apart from the price lookups Prices already does.
 *
 * <p>This class is the road map's face: its data types, the character's map (built, saved,
 * out of date, building in the background) and the calls the screens, the build window and the
 * local API make. The work is done by its parts: {@link MilestonePlanner} (the milestones),
 * {@link MilestonePicks} (one milestone's slot-by-slot candidates and picks), {@link SetSearch}
 * with {@link BeamSearch}, {@link LocalSearch} and {@link SetSeeds} (the search over whole sets),
 * {@link SetJudge} (how a set is scored), {@link CandidateRules} and {@link OwnedGear} (what may
 * be offered, what it costs, what you have), {@link RoadmapRows} and {@link RoadmapGains} (the
 * rows and their numbers), {@link BuildRanking} (the build window's list), {@link RoadmapTrace}
 * and {@link RoadmapJson}.
 */
final class Roadmap {

    /** {@code alt}: something better you cannot buy (quest reward, untradable), or null. */
    record Gear(String slot, ItemData info, double gain, String source, String price, ItemData alt, String altSource,
            List<String> detail, List<String> alternatives, List<Option> options) {}

    /**
     * One choice for a slot, for the comparison panel: what it costs, what it takes away, and
     * the gain split into the parts the build score multiplies (Damage.FACTORS).
     */
    record Option(ItemData info, double gain, String price, String source, List<String> cautions, String skills,
            boolean respec, double[] factorPct, Damage.Result from, Damage.Result to) {}

    /**
     * {@code vsWorn}: slot type name ("HELMET", "RING", "RELIK" …) → the % the build of this
     * milestone (every pick up to its level, judged at its level and skill points) loses when
     * that slot goes back to what you wear now, for every slot changed by then. This is the
     * number for a "worn → pick" row; {@link Gear#gain} is only the step over the build just
     * before the pick (the last milestone's pick, the slots picked before it at this level).
     * A piece keeps the number of the milestone that first picked it, so the same piece shows
     * the same number on every tab. {@code set}: the milestone's whole set ({@code build}) at its
     * level, with its skill points; null when it does not fit or there is no build plan.
     */
    record Step(int level, List<Gear> gear, List<String> abilities, List<String> places, int skillPoints,
            Map<String, Double> vsWorn, Map<String, List<String>> whyVsWorn, Map<String, Damage.Piece> build,
            Build.Eval set) {

        Step(int level, List<Gear> gear, List<String> abilities, List<String> places, int skillPoints) {
            this(level, gear, abilities, places, skillPoints, Map.of(), Map.of(), Map.of(), null);
        }

        Step(int level, List<Gear> gear, List<String> abilities, List<String> places, int skillPoints,
                Map<String, Double> vsWorn) {
            this(level, gear, abilities, places, skillPoints, vsWorn, Map.of(), Map.of(), null);
        }
    }

    /**
     * {@link Step#vsWorn} and {@link Step#whyVsWorn} of one milestone: per slot type name the %
     * and the reason lines ({@link RoadmapGains#why}), from the same two builds.
     */
    record Versus(Map<String, Double> gain, Map<String, List<String>> why) {}

    /** The road map's name for a pick in the second ring slot ({@link Gear#slot}); the first ring is "RING". */
    static final String RING2 = "RING2";

    /** The order the beam fills a set: the weapon sets the element, then armour, then the accessories. */
    static final String[] BEAM_ORDER = {"WEAPON", "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "RING1", "RING2",
        "BRACELET", "NECKLACE"};

    /** A whole set with its skill points and its judged score. */
    record Scored(Map<String, Damage.Piece> set, Build.Eval eval, double j) {}

    /**
     * What one milestone's search works from: the set it started with (what you wear, or the last
     * milestone's), each slot type's best few ({@code pools}, ranked against the slot-by-slot
     * build) and everything the milestone allows there ({@code wide}), the sets it can put
     * together with what you have and could buy of them, what you wear now, the chosen items'
     * slots, the level, the points earned, the last milestone's spread, and the pieces made so far.
     */
    record Ctx(Map<String, Damage.Piece> start, Map<Slot, List<ItemData>> pools, Map<Slot, List<ItemData>> wide,
            Map<String, List<ItemData>> sets, Map<String, Integer> owned, java.util.Set<String> buyable,
            Map<String, Damage.Piece> worn, java.util.Set<String> fixed, int m, int earned, int[] prev,
            Map<String, Damage.Piece> pieces) {

        /**
         * The item's piece, made once a milestone (the search tries each many times): the copy
         * you have ({@link OwnedGear#pieceOf}), or the fresh copy the slot ranking chose over it,
         * which {@code pieces} starts with.
         */
        Damage.Piece piece(ItemData info) {
            return pieces.computeIfAbsent(info.name(), n -> OwnedGear.pieceOf(info));
        }
    }

    /** A whole-set try: the build with a set put on or taken off, and the slots changed for it. */
    record Seed(Map<String, Damage.Piece> build, java.util.Set<String> held) {}

    /** Tests only: told each milestone's search result, with the slot pools its last pass tried. */
    static volatile Searched searched;

    interface Searched {
        void accept(int m, int earned, int[] prev, Map<String, List<Damage.Piece>> pools, Scored result);
    }

    /** Tests only: told why each candidate was skipped or how it scored ({@code m, slot, line}). */
    static volatile TriConsumer trace;

    interface TriConsumer {
        void accept(int m, Slot slot, String line);
    }

    /** How many reason lines {@link RoadmapGains#why} gives at most. */
    static final int WHY_LINES = 4;

    /** How far two builds must differ, in slots outside the chosen ones, to be listed apart. */
    static final int MIN_DISTANCE = 2;
    /** Builds within this share of the best count as "as good" for the Cheapest label. */
    static final double CHEAP_WITHIN = 0.03;

    // The road map's calls into its parts, kept here for the screens, the build window and the tests

    /** {@link SetJudge#judged}: a set's score with the respec charge and the keep bonus. */
    static double judged(Build.Eval e, int[] prev, int m) {
        return SetJudge.judged(e, prev, m);
    }

    /** {@link SetJudge#judged} with {@code build}'s pieces, for a result that does not carry them (read back from disk). */
    static double judged(Map<String, Damage.Piece> build, Build.Eval e, int[] prev, int m) {
        return SetJudge.judged(build, e, prev, m);
    }

    /** {@link SetJudge#carry}: what the next milestone's sets are judged against. */
    static int[] carry(Build.Eval set, Map<String, Damage.Piece> build, int m) {
        return SetJudge.carry(set, build, m);
    }

    /** {@link SetSearch#searchBest}: the best set the search finds. */
    static Scored searchBest(Ctx c, Map<String, Damage.Piece> build, Build.Eval cur, Build.Eval s0) {
        return SetSearch.searchBest(c, build, cur, s0);
    }

    /** {@link SetSeeds#setSeeds}: the build with whole sets put on or taken off. */
    static List<Seed> setSeeds(Map<String, Damage.Piece> build, Build.Eval cur, Map<Slot, List<ItemData>> pools,
            Map<String, List<ItemData>> sets, Map<String, Integer> owned, java.util.Set<String> buyable,
            java.util.Set<String> fixed, int m, int earned, int[] prev) {
        return SetSeeds.setSeeds(build, cur, pools, sets, owned, buyable, fixed, m, earned, prev);
    }

    /** {@link RoadmapRows#keepWorn}. */
    static void keepWorn(Map<String, Gear> latest, Step s) {
        RoadmapRows.keepWorn(latest, s);
    }

    /** {@link RoadmapGains#shownGain}: the number a row's pill shows. */
    static double shownGain(List<Step> steps, int selected, Gear u) {
        return RoadmapGains.shownGain(steps, selected, u);
    }

    /** {@link RoadmapGains#showsStep}. */
    static boolean showsStep(List<Step> steps, int selected, Gear u) {
        return RoadmapGains.showsStep(steps, selected, u);
    }

    /** {@link RoadmapGains#isTarget}. */
    static boolean isTarget(Gear g) {
        return RoadmapGains.isTarget(g);
    }

    /** {@link RoadmapGains#pill}. */
    static String pill(double gain, Gear g) {
        return RoadmapGains.pill(gain, g);
    }

    /** {@link RoadmapRows#bagRows}: what the build plan puts on from your bag, as rows. */
    static Map<String, Gear> bagRows() {
        return RoadmapRows.bagRows();
    }

    /** {@link RoadmapRows#fromBag}. */
    static boolean fromBag(Gear g) {
        return RoadmapRows.fromBag(g);
    }

    /** {@link RoadmapRows#isPowder}. */
    static boolean isPowder(Gear g) {
        return RoadmapRows.isPowder(g);
    }

    /** {@link CandidateRules#maxOff}. */
    static int maxOff(int m) {
        return CandidateRules.maxOff(m);
    }

    /** {@link CandidateRules#laterVersions}. */
    static Map<String, Integer> laterVersions(GameState state, java.util.function.Predicate<ItemData> gettable) {
        return CandidateRules.laterVersions(state, gettable);
    }

    /** {@link CandidateRules#lastOfItsKind}. */
    static boolean lastOfItsKind(ItemData info, int m, Map<String, Integer> laterVersion) {
        return CandidateRules.lastOfItsKind(info, m, laterVersion);
    }

    /** {@link CandidateRules#badTiers}. */
    static List<String> badTiers(int m) {
        return CandidateRules.badTiers(m);
    }

    /** {@link CandidateRules#badSources}. */
    static List<String> badSources(int m) {
        return CandidateRules.badSources(m);
    }

    /** {@link CandidateRules#availability}. */
    static double availability(Prices.Price p) {
        return CandidateRules.availability(p);
    }

    /** {@link CandidateRules#cost}. */
    static double cost(GameState state, ItemData info) {
        return CandidateRules.cost(state, info);
    }

    /** {@link CandidateRules#uncertainPrice}. */
    static boolean uncertainPrice(GameState state, ItemData info) {
        return CandidateRules.uncertainPrice(state, info);
    }

    /** {@link CandidateRules#spend}: what the buys in a set cost. */
    static double spend(GameState state, Map<String, Damage.Piece> set, java.util.Set<String> fixed, java.util.Set<String> owned) {
        return CandidateRules.spend(state, set, fixed, owned);
    }

    /** {@link CandidateRules#powderPrice}. */
    static double powderPrice(int tier) {
        return CandidateRules.powderPrice(tier);
    }

    /** {@link OwnedGear#pieceOf}: the piece the road map judges an item as. */
    static Damage.Piece pieceOf(ItemData info) {
        return OwnedGear.pieceOf(info);
    }

    /** {@link OwnedGear#powdered}. */
    static Map<String, Damage.Piece> powdered(Map<String, Damage.Piece> set) {
        return OwnedGear.powdered(set);
    }

    /** {@link OwnedGear#ownedCounts}. */
    static Map<String, Integer> ownedCounts(GameState state) {
        return OwnedGear.ownedCounts(state);
    }

    /** {@link OwnedGear#assignedPoints}. */
    static int[] assignedPoints(GameState state) {
        return OwnedGear.assignedPoints(state);
    }

    /** {@link BuildRanking#distance}. */
    static int distance(Map<String, Damage.Piece> a, Map<String, Damage.Piece> b, java.util.Set<String> fixed) {
        return BuildRanking.distance(a, b, fixed);
    }

    /** {@link BuildRanking#label}. */
    static List<Candidate> label(List<Candidate> builds) {
        return BuildRanking.label(builds);
    }

    /** Slot → language key of its name. */
    private static final Map<String, String> SLOT_NAMES = Map.of("HELMET", "wynnlabs.road.slot_helmet",
            "CHESTPLATE", "wynnlabs.road.slot_chestplate", "LEGGINGS", "wynnlabs.road.slot_leggings",
            "BOOTS", "wynnlabs.road.slot_boots", "RING", "wynnlabs.road.slot_ring",
            "BRACELET", "wynnlabs.road.slot_bracelet", "NECKLACE", "wynnlabs.road.slot_necklace",
            "WEAPON", "wynnlabs.road.slot_weapon");

    private Roadmap() {}

    static String slotName(String slot) {
        return L.t(SLOT_NAMES.getOrDefault(slot, "wynnlabs.road.slot_weapon"));
    }

    private static volatile List<Step> lastSteps = List.of();
    private static volatile long lastBuilt;
    private static volatile String lastFor = "";
    /** The level the last map was built at, and whether it came from the file (a past session). */
    private static volatile int lastLevel;
    private static volatile boolean lastFromFile;
    /** The character whose saved map was looked for (once per character). */
    private static volatile String loadedFor;

    /**
     * Every upgrade name in the road map built this session (for keeping their prices fresh); none
     * for a map read back from a past session, whose prices stay as they were.
     */
    static List<String> lastNames() {
        List<String> out = new ArrayList<>();
        if (lastFromFile) {
            return out;
        }
        for (Step s : lastSteps) {
            for (Gear g : s.gear()) {
                out.add(g.info().name());
                if (g.options() != null) {
                    for (Option o : g.options()) {
                        out.add(o.info().name());
                    }
                }
            }
        }
        return out;
    }

    /**
     * The upgrade the damage model found for this slot at your level, from the character's road
     * map (built this session or read back from the last one); null when there is none. Lets
     * tooltips agree with the J menu. Never starts a build.
     */
    static Gear lastUpgrade(Slot type) {
        List<Step> steps = cached();
        if (steps.isEmpty()) {
            return null;
        }
        for (Gear g : steps.get(0).gear()) {
            if (g.slot().equals(type.name())) {
                return g;
            }
        }
        return null;
    }

    /**
     * The character's road map: the one built last (this session, or read back from the file of
     * the last one), empty when none was ever built. Never starts a build: the J menu's button,
     * its refresh and the explicit actions ({@link #forceRefresh}) do.
     */
    static List<Step> cached() {
        String id = GameState.current().characterId();
        load(id);
        return id.equals(lastFor) ? lastSteps : List.of();
    }

    /** The character's saved map, read once when the character is known (a missing or broken file: none). */
    private static synchronized void load(String id) {
        if (id == null || id.isEmpty() || id.equals(loadedFor) || id.equals(lastFor) && !lastSteps.isEmpty()) {
            return;
        }
        loadedFor = id;
        RoadmapStore.Loaded l = RoadmapStore.load(id);
        if (l == null || !id.equals(GameState.current().characterId())) {
            return;
        }
        lastSteps = l.steps();
        lastFor = id;
        lastBuilt = l.builtAt();
        lastLevel = l.level();
        cachedKey = l.key();
        lastFromFile = true;
        version++;
    }

    /** Tests: the saved map is looked for again, and nothing is kept in memory. */
    static synchronized void forget() {
        lastSteps = List.of();
        lastFor = "";
        loadedFor = null;
        cachedKey = "";
        lastFromFile = false;
        staleKey = null;
    }

    /** Tests: {@link #stale} reads the inputs again at once (it rereads them each half second). */
    static void recheck() {
        staleKey = null;
    }

    /**
     * The map is out of date: what it was built from (level, gear, skill points, archetype, rules,
     * language: {@link #key}) is not what it is now. {@code level}: the level changed (to
     * {@code now}); {@code ageMs}: how long ago it was built.
     */
    record Stale(boolean level, int now, int builtLevel, long ageMs) {}

    private static volatile String staleKey;
    private static volatile long staleAt;

    /** Why the character's map is out of date, or null when it is current or there is none. Read again each half second. */
    static Stale stale() {
        GameState state = GameState.current();
        if (cached().isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        String k = staleKey;
        if (k == null || now - staleAt > 500) {
            k = key(state);
            staleKey = k;
            staleAt = now;
        }
        if (k.equals(cachedKey)) {
            return null;
        }
        return new Stale(state.level() != lastLevel, state.level(), lastLevel, Math.max(0, state.now() - lastBuilt));
    }

    /** Whether the character has a map at all (built or saved): else the gear panel offers to calculate one. */
    static boolean hasMap() {
        return !cached().isEmpty();
    }

    /** The J menu's button and refresh, and the explicit actions: a new map now, in the background. */
    static void forceRefresh() {
        GameState state = GameState.current();
        if (state.inGame()) {
            refresh();
        }
    }

    /** Cancel: the map being built is dropped, the last one stays. */
    static void cancelBuild() {
        again = false;
        Job j = buildJob;
        if (j != null) {
            j.cancel();
        }
    }

    /** True while a map is being built in the background. */
    static boolean isBuilding() {
        return building;
    }

    /** How far the map being built is, 0 to 1, by milestones done. */
    static double progress() {
        Job j = buildJob;
        if (j != null) {
            return Math.min(1, j.done / (double) Math.max(1, j.total));
        }
        int total = progressTotal;
        return total <= 0 ? 0 : Math.min(1, progressDone / (double) total);
    }

    /** Tests: waits for the builder to finish what it was given. */
    static void awaitIdle() throws Exception {
        BUILDER.submit(() -> { }).get(120, java.util.concurrent.TimeUnit.SECONDS);
        while (building) {
            BUILDER.submit(() -> { }).get(120, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    /** Bumped each time a new map is ready, so an open J menu can pick it up. */
    static int version() {
        return version;
    }

    /**
     * What the map depends on, by content: the character, level, language, rules, and the build
     * plan's pieces, what is worn and the skill points. A map whose key is not today's is out of
     * date ({@link #stale}); it is never rebuilt for that on its own.
     */
    static String key(GameState state) {
        Build.Plan p = Build.plan();
        StringBuilder plan = new StringBuilder();
        if (p != null) {
            p.pieces().forEach((slot, piece) -> plan.append(slot).append('=').append(piece == null ? "-" : piece.key()).append(';'));
            if (p.worn() != null) {
                p.worn().forEach((slot, piece) -> plan.append(slot).append('~').append(piece == null ? "-" : piece.key()).append(';'));
            }
            plan.append(java.util.Arrays.toString(p.wornAllocation()));
            // the ability tree the plan was scored with: a node bought changes every milestone's model
            plan.append('|').append(p.tree() == null ? "" : p.tree().key());
        }
        // the map's text (where a piece comes from, the reasons) is written in the language of
        // the day it was built: a switch to English left "mob'lardan düşer" on the rows. A map an
        // older scoring model made is out of date too
        return state.characterId() + ":" + state.level() + ":" + L.code() + ":" + Rules.hash() + ":"
                + plan.toString().hashCode() + ":m" + BeamCache.modelVersion();
    }

    private static void refresh() {
        if (building) {
            again = true;                       // asked again mid-build (a setting changed): once more after it
            return;
        }
        building = true;
        Job j = new Job();
        buildJob = j;
        BUILDER.execute(() -> {
            try {
                build(j);
                version++;
            } catch (Throwable t) {
                // cancelled, or the game state changed under us (a menu, a world change): the last map stays
            } finally {
                buildJob = null;
                building = false;
                if (again && !j.cancelled) {
                    again = false;
                    refresh();
                }
                again = false;
            }
        });
    }

    private static volatile boolean building;
    private static volatile Job buildJob;
    /** The J menu's build (no job): milestones done of all; {@link MilestonePlanner} counts them. */
    static volatile int progressDone;
    static volatile int progressTotal;
    private static volatile boolean again;
    private static volatile int version;
    private static final java.util.concurrent.ExecutorService BUILDER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "wynnlabs-roadmap");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

    /** The inputs key the character's map was built for. */
    private static volatile String cachedKey = "";

    /**
     * What one computation takes: the items to build around (Locks), the tuning strip, your level
     * only or every milestone, and how many whole builds at your level to rank (0: none).
     */
    record Inputs(List<String> around, Tuning.Choice tuning, boolean nowOnly, int builds) {
        /** The road map as it always was: nothing chosen, Balanced, no budget, every milestone. */
        static final Inputs PLAIN = new Inputs(List.of(), Tuning.Choice.PLAIN, false, 0);
    }

    /**
     * A computation's result: the milestones, the ranked builds at your level ({@link BuildRanking#rank}),
     * the chosen items it held, why it held none ({@code problem}: they cannot be worn
     * together), the milestones where the budget left a pick out, and whom it was for.
     */
    record Computed(List<Step> steps, List<Candidate> builds, List<String> around, Locks.Problem problem,
            java.util.Set<Integer> budgetCut, String forId, int level) {}

    /**
     * One whole build at your level, for the window's result list: the set by build slot, its
     * skill points and result, its score (the tuned, judged objective), what its buys cost on the
     * Trade Market (chosen items and what you own count nothing), its labels ("best", "cheap",
     * "tanky", "fast") and the slots the chosen items hold.
     */
    record Candidate(Map<String, Damage.Piece> set, Build.Eval eval, double score, double price, List<String> labels,
            java.util.Set<String> fixed) {}

    /**
     * A computation's progress and its stop: the window's loading view reads it, its Cancel and a
     * newer request set {@link #cancel}; the next check ({@link #tick}) then ends the run.
     */
    static final class Job {
        /** 0 preparing the items, 1 filling the slots, 2 trying whole sets, 3 checking skill points, 4 checking the budget, 5 ranking builds. */
        volatile int phase;
        volatile int done;
        volatile int total = 1;
        final java.util.concurrent.atomic.AtomicLong tried = new java.util.concurrent.atomic.AtomicLong();
        /** The best set's score so far (NaN before the first). */
        volatile double best = Double.NaN;
        volatile boolean cancelled;

        void cancel() {
            cancelled = true;
        }
    }

    /** A run stopped by its Job: nothing it found is kept. */
    static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("cancelled", null, false, false);
        }
    }

    /** The run's job on this thread (the search's worker threads get it too, {@link SetSearch}). */
    static final ThreadLocal<Job> JOB = new ThreadLocal<>();

    /** A set tried: counted for the loading view, and the run ends here when it was cancelled. */
    static void tick() {
        Job j = JOB.get();
        if (j != null) {
            j.tried.incrementAndGet();
            if (j.cancelled) {
                throw new Cancelled();
            }
        }
    }

    /** The run's phase for the loading view ({@link Job#phase}); the run ends here when it was cancelled. */
    static void phase(int p) {
        Job j = JOB.get();
        if (j != null) {
            j.phase = p;
            if (j.cancelled) {
                throw new Cancelled();
            }
        }
    }

    /** The road map for the J menu: built as it always was (nothing chosen, no tuning), kept and saved for the character. */
    static List<Step> build() {
        return build(null);
    }

    /** {@link #build} with a job for its progress and Cancel (the background build). */
    static List<Step> build(Job job) {
        GameState state = GameState.current();
        String key = state.inGame() ? key(state) : "";
        Computed c = compute(Inputs.PLAIN, job);
        if (c == null) {
            return List.of();
        }
        if (!c.forId().equals(GameState.current().characterId())) {
            return c.steps();                       // built for the character you just left
        }
        lastSteps = c.steps();
        lastBuilt = GameState.current().now();
        lastFor = c.forId();
        lastLevel = c.level();
        cachedKey = key;
        lastFromFile = false;
        loadedFor = c.forId();
        staleKey = null;
        RoadmapStore.save(c.forId(), c.steps(), key, c.level(), lastBuilt);
        BeamCache.save();
        return c.steps();
    }

    /**
     * Works a road map out from {@code in} with its tuning in force ({@link Tuning#with}); null
     * outside the game. {@code job} (null for the J menu's map) gets the progress and can stop
     * the run ({@link Cancelled}). Safe off the game thread, as {@link #build} always was.
     */
    static Computed compute(Inputs in, Job job) {
        Job before = JOB.get();
        JOB.set(job);
        try {
            // one memo of skill point spreads for the run (Build.Memo), emptied each milestone; the
            // tree each milestone sets (TreeModel) is gone after the run
            return Tuning.with(in.tuning(), () -> TreeModel.with(null,
                    () -> Build.withMemo(new Build.Memo(), () -> MilestonePlanner.compute(in, job))));
        } finally {
            if (before == null) {
                JOB.remove();
            } else {
                JOB.set(before);
            }
        }
    }

    /** The road map built now, as JSON ({@link RoadmapJson#steps}). */
    static JsonObject json() {
        return RoadmapJson.steps(build());
    }

    /**
     * {@link #json} worked out on the road map's own background thread (one build at a time,
     * after any warm-up already running), for API callers: the game thread never waits for it.
     */
    static JsonObject jsonOffThread() throws Exception {
        return BUILDER.submit(Roadmap::json).get(120, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * The local API's /roadmap: the map the J menu has (never rebuilt here, so the game does not
     * stall), plus per milestone the rows the menu shows ({@link RoadmapJson#live}).
     */
    static JsonObject jsonLive() {
        return RoadmapJson.live(lastSteps, lastBuilt, building);
    }

    /**
     * The local API's /roadmap/trace: builds the map once more on the background builder with
     * the trace on, and returns why each candidate for {@code slot} at {@code level} was kept,
     * scored or dropped. Waits at most 60 s; the game keeps running meanwhile.
     */
    static JsonObject traceJson(int level, Slot slot) throws Exception {
        List<String> lines = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.Future<?> f = BUILDER.submit(() -> {
            trace = (m, t, line) -> {
                if (m == level && t == slot) {
                    lines.add(line);
                }
            };
            try {
                build();
            } finally {
                trace = null;
            }
        });
        f.get(60, java.util.concurrent.TimeUnit.SECONDS);
        return RoadmapJson.trace(level, slot, lines);
    }

    /** Gear by name, ignoring case (rule lists are upper-cased). */
    static ItemData findGear(String name) {
        return GameState.current().allGear().filter(i -> i.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    /** Milestones are kept in order of level for the screen. */
    static Map<Integer, Step> byLevel(List<Step> steps) {
        Map<Integer, Step> m = new LinkedHashMap<>();
        steps.forEach(s -> m.put(s.level(), s));
        return m;
    }
}
