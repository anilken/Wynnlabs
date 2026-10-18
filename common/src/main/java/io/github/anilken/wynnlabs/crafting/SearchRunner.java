package io.github.anilken.wynnlabs.crafting;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Runs optimiser searches on their own background thread, one at a time: a new request cancels
 * the one before it, and a cancelled search never reports. The game thread only submits and
 * reads progress.
 */
public final class SearchRunner {

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wynnlabs-craft");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private volatile CraftSearch.Budget current;

    /** Starts a search of all three tiers within {@code millis}; {@code done} gets them unless a newer search replaced it. */
    public CraftSearch.Budget submit(CraftSearch.Request q, long millis, Consumer<List<CraftSearch.Outcome>> done) {
        return run(millis, b -> {
            List<CraftSearch.Outcome> r = CraftSearch.searchAll(q, millis, b);
            if (!b.cancelled()) {
                done.accept(r);
            }
        });
    }

    /** Runs any search work under a fresh budget, cancelling the previous one. */
    public CraftSearch.Budget run(long millis, Consumer<CraftSearch.Budget> work) {
        CraftSearch.Budget prev = current;
        if (prev != null) {
            prev.cancel();
        }
        CraftSearch.Budget b = new CraftSearch.Budget(millis + 1000);
        current = b;
        exec.execute(() -> {
            if (!b.cancelled()) {
                work.accept(b);
            }
        });
        return b;
    }

    /** The running or last search's budget (for progress), or null. */
    public CraftSearch.Budget current() {
        return current;
    }

    public void cancel() {
        CraftSearch.Budget b = current;
        if (b != null) {
            b.cancel();
        }
    }
}
