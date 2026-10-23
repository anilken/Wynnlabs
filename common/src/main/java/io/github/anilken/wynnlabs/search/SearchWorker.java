package io.github.anilken.wynnlabs.search;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs search queries on one background thread, latest wins: each {@link #submit} gets a newer
 * generation, and a query that finishes after a newer one was submitted is thrown away. The
 * screen submits as the player types and draws {@link #latest()} every frame.
 */
public final class SearchWorker {

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wynnlabs-search");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger generation = new AtomicInteger();
    private volatile SearchQuery.Result latest = SearchQuery.Result.EMPTY;
    private volatile int latestGeneration;

    /** Queues the query against the index now loaded; returns its generation. */
    public int submit(SearchQuery q, SearchQuery.Facts facts) {
        int gen = generation.incrementAndGet();
        exec.execute(() -> {
            if (gen != generation.get()) {
                return;                                 // a newer query is waiting
            }
            SearchQuery.Result r = q.run(ItemIndex.get(), facts);
            if (gen == generation.get()) {
                latest = r;
                latestGeneration = gen;
            }
        });
        return gen;
    }

    /** The last finished result (empty before the first). */
    public SearchQuery.Result latest() {
        return latest;
    }

    /** True while a submitted query has not finished yet. */
    public boolean busy() {
        return latestGeneration != generation.get();
    }

    /** Stops the thread (the screen closing for good). */
    public void close() {
        exec.shutdownNow();
    }
}
