package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Named timing sections for the mod's hot paths: how often each ran and its average and worst
 * time, read on the debug route /perf. Only records while the Debug setting is on; while it is
 * off {@link #start()} returns 0 and {@link #end} returns at once, one field read each.
 *
 * <pre>
 * long t = Perf.start();
 * ... work ...
 * Perf.end("groundMarkers.tick", t);
 * </pre>
 */
public final class Perf {

    /** Mirrors the Debug setting, refreshed once a tick by {@link #onTick}. */
    public static volatile boolean on;
    /** Per section: {count, total ns, worst ns}. Each array is updated under its own lock. */
    private static final Map<String, long[]> SECTIONS = new ConcurrentHashMap<>();
    private static volatile long since = System.nanoTime();

    private Perf() {}

    /** The start mark of a section, or 0 while not recording. */
    public static long start() {
        return on ? System.nanoTime() : 0L;
    }

    /** Ends a section started with {@link #start()}; nothing happens for a 0 mark. */
    public static void end(String name, long started) {
        if (started == 0L) {
            return;
        }
        long took = System.nanoTime() - started;
        long[] s = SECTIONS.computeIfAbsent(name, k -> new long[3]);
        synchronized (s) {
            s[0]++;
            s[1] += took;
            if (took > s[2]) {
                s[2] = took;
            }
        }
    }

    /** Times a piece of work as one section; runs it plainly while not recording. */
    static void time(String name, Runnable work) {
        long t = start();
        try {
            work.run();
        } finally {
            end(name, t);
        }
    }

    /** A tick listener timed as the section {@code name}. */
    static net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.EndTick tick(
            String name, net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.EndTick body) {
        return client -> {
            long t = start();
            try {
                body.onEndTick(client);
            } finally {
                end(name, t);
            }
        };
    }

    static void onTick(net.minecraft.client.Minecraft mc) {
        boolean want = Settings.on("debug");
        if (want != on) {
            on = want;
            if (want) {
                reset();
            }
        }
    }

    static void reset() {
        SECTIONS.clear();
        since = System.nanoTime();
    }

    /** The sections, the most total time first: name, count, average, worst and total in ms. */
    static JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("recording", on);
        o.addProperty("seconds", Math.round((System.nanoTime() - since) / 1e8) / 10.0);
        List<Map.Entry<String, long[]>> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : SECTIONS.entrySet()) {
            long[] copy;
            synchronized (e.getValue()) {
                copy = e.getValue().clone();
            }
            rows.add(Map.entry(e.getKey(), copy));
        }
        rows.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
        JsonArray arr = new JsonArray();
        for (Map.Entry<String, long[]> r : rows) {
            long[] s = r.getValue();
            JsonObject j = new JsonObject();
            j.addProperty("section", r.getKey());
            j.addProperty("count", s[0]);
            j.addProperty("avgMs", ms(s[0] == 0 ? 0 : s[1] / s[0]));
            j.addProperty("maxMs", ms(s[2]));
            j.addProperty("totalMs", ms(s[1]));
            arr.add(j);
        }
        o.add("sections", arr);
        // file reads that must never happen while drawing: these only grow in the background
        JsonObject disk = new JsonObject();
        disk.addProperty("priceKeyFile", Prices.KEY_FILE_READS.get());
        disk.addProperty("characterMeta", Vault.META_READS.get());
        o.add("diskReads", disk);
        o.addProperty("backgroundWrites", Saver.WRITES.get());
        // what invalidates the advice caches: each counter's value now, and how often the bag changed
        JsonObject v = new JsonObject();
        v.addProperty("rules", Rules.version);
        v.addProperty("prices", Prices.version);
        v.addProperty("advice", Advice.version);
        v.addProperty("inventoryChanges", Stamp.INVENTORY_CHANGES.get());
        o.add("versions", v);
        return o;
    }

    private static double ms(long ns) {
        return Math.round(ns / 1000.0) / 1000.0;
    }

    /** For tests: one section's {count, total, worst}, or null. */
    static long[] section(String name) {
        long[] s = SECTIONS.get(name);
        return s == null ? null : s.clone();
    }
}
