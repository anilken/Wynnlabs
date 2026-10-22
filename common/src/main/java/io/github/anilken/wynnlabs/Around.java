package io.github.anilken.wynnlabs;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The "Choose items" searches: whole builds at your level around the chosen items, worked out on
 * a background thread of their own ({@link Roadmap#compute} with {@link Roadmap.Inputs#nowOnly}),
 * never on the render or tick thread. Each request has a generation: a newer request or Cancel
 * stops the one running ({@link Roadmap.Job#cancel}) and whatever an older one still finds is
 * dropped, so only the newest result is ever shown. The result is a plain value the render thread
 * reads ({@link #result}); nothing here acts in the game.
 */
final class Around {

    /** How many builds a search ranks. */
    static final int BUILDS = 4;

    /**
     * A finished search: its generation, the items and tuning it was for, the level, the ranked
     * builds, why the chosen items could not be held (null when they could), the milestone's
     * budget note, and whether it was the level-up check rather than the window's request.
     */
    record Result(int gen, List<String> around, Tuning.Choice tuning, int level, List<Roadmap.Candidate> builds,
            Locks.Problem problem, boolean budgetCut, boolean check) {}

    private static final AtomicInteger GEN = new AtomicInteger();
    private static final ExecutorService SEARCH = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wynnlabs-around");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static volatile Roadmap.Job job;
    private static volatile int jobGen = -1;
    private static volatile boolean jobCheck;
    private static volatile List<String> jobAround = List.of();
    private static volatile Result result;
    private static volatile Result checkResult;
    /** The preview: a frozen loading view (progress 0 to 1, phase), never set in the game. */
    static volatile double[] previewLoading;

    private Around() {}

    /**
     * Searches for builds around {@code names} with {@code tuning}: in the background, the one
     * running stopped. {@code check}: the level-up check for the better-build hint. Returns the
     * request's generation.
     */
    static int request(List<String> names, Tuning.Choice tuning, boolean check) {
        int gen = GEN.incrementAndGet();
        Roadmap.Job old = job;
        if (old != null) {
            old.cancel();
        }
        Roadmap.Job j = new Roadmap.Job();
        job = j;
        jobGen = gen;
        jobCheck = check;
        jobAround = List.copyOf(names);
        List<String> around = List.copyOf(names);
        SEARCH.execute(() -> run(gen, j, around, tuning, check));
        return gen;
    }

    private static void run(int gen, Roadmap.Job j, List<String> around, Tuning.Choice tuning, boolean check) {
        if (GEN.get() != gen || j.cancelled) {
            return;                                 // a newer request came before this one started
        }
        try {
            Roadmap.Computed c = Roadmap.compute(new Roadmap.Inputs(around, tuning, true, BUILDS), j);
            if (c == null || GEN.get() != gen || j.cancelled) {
                return;
            }
            Result r = new Result(gen, around, tuning, c.level(), c.builds(), c.problem(), !c.budgetCut().isEmpty(), check);
            if (check) {
                checkResult = r;
                Plans.Build plan = Plans.current();
                double planScore = plan == null ? Double.NaN : scoreAt(plan, c.level(), tuning);
                if (plan != null && !r.builds().isEmpty()) {
                    Plans.HINT.offer(c.level(), planScore, r.builds().get(0).score());
                }
            } else {
                result = r;
            }
        } catch (Roadmap.Cancelled e) {
            // stopped: the old result stands
        } catch (Throwable t) {
            System.out.println("[WynnLabs] build search failed: " + t);
        } finally {
            if (job == j) {
                job = null;
            }
        }
    }

    /** A plan's score at {@code level} with its pieces and the tuning it was found with (NaN when it cannot be worn there). */
    static double scoreAt(Plans.Build plan, int level, Tuning.Choice tuning) {
        java.util.Map<String, Damage.Piece> set = new java.util.LinkedHashMap<>();
        GameState state = GameState.current();
        plan.items().forEach((slot, name) -> state.allGear().filter(i -> i.name().equals(name)).findFirst()
                .ifPresent(i -> set.put(slot, Damage.piece(i))));
        int earned = Math.max(0, (Math.min(level, 101) - 1) * 2);
        // with the ability tree you will have at that level, as the road map scores it
        TreeModel.Run tree = TreeModel.run();
        return Tuning.with(tuning, () -> TreeModel.with(tree == null ? null : tree.at(level), () -> {
            Build.Eval e = Build.allocate(set, level, earned);
            return e == null ? Double.NaN : Roadmap.judged(e, null, level);
        }));
    }

    /** Stops the search running (Cancel): the result before it stays. */
    static void cancel() {
        GEN.incrementAndGet();
        Roadmap.Job j = job;
        if (j != null) {
            j.cancel();
        }
        job = null;
    }

    /** Whether the window's search is running (not the quiet level-up check). */
    static boolean running() {
        return previewLoading != null || job != null && !jobCheck;
    }

    /** The items the running search is for. */
    static List<String> runningFor() {
        return jobAround;
    }

    /** The running search's progress, or null. */
    static Roadmap.Job progress() {
        return jobCheck ? null : job;
    }

    /** The newest finished search for the window, or null. */
    static Result result() {
        return result;
    }

    /** The newest level-up check, or null. */
    static Result checkResult() {
        return checkResult;
    }

    /** The result list is for another choice now, or was cancelled: dropped. */
    static void forget() {
        result = null;
    }

    /** The level-up check's list was cancelled: dropped. */
    static void forgetCheck() {
        checkResult = null;
    }

    /** Tests and the preview: this result as the newest. */
    static void use(Result r) {
        result = r;
    }

    /** Tests: wait for the search thread to finish what it was given. */
    static void settle() throws Exception {
        SEARCH.submit(() -> { }).get(120, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * The client tick: once per level after a level-up, the plan's search again in the
     * background with its chosen items and strip; the hint shows when it beats the plan.
     */
    static void watch() {
        GameState state = GameState.current();
        if (!state.inGame() || !Rules.adviceHere()) {
            return;
        }
        Plans.Build plan = Plans.current();
        if (plan == null || job != null) {
            return;
        }
        if (Plans.HINT.shouldCheck(state.level(), plan.level())) {
            request(plan.locks(), plan.tuning(), true);
        }
    }
}
