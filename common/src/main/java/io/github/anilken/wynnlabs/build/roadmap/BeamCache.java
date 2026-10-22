package io.github.anilken.wynnlabs.build.roadmap;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.core.Profile;
import io.github.anilken.wynnlabs.game.GameState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The road map's beam results per character, on disk (roadmap-cache.json in the character's
 * folder): input key → the set it chose, slot → piece key and name, or none. The beam is the
 * slow part of a map (about 20 s); with its inputs unchanged a restart needs none of it.
 * At most {@link #MAX} entries, the oldest go first. In tests (no character folder) memory only.
 */
public final class BeamCache {

    private static final int MAX = 400;
    private static final Gson GSON = new Gson();
    private static final String NONE = "-";

    private BeamCache() {}

    /**
     * The scoring model the kept results were made with, part of every key: raised whenever the
     * way sets are scored or searched changes (survival floor, skill point search, set bonuses,
     * prices, the ability tree per milestone, the search's starts), so a set chosen by an older
     * model is searched again instead of coming back from disk. A method, not a field: a field
     * added by a hot swap keeps no value.
     */
    static int modelVersion() {
        return 9;
    }

    /** The loaded entries and whose they are; access-ordered, so the oldest drop first. */
    private static Map<String, Map<String, String>> entries = new LinkedHashMap<>(16, 0.75f, true);
    private static String loadedFor;
    private static boolean dirty;

    private static synchronized Map<String, Map<String, String>> entries() {
        String id = Profile.id();
        if (!id.equals(loadedFor)) {
            loadedFor = id;
            entries = new LinkedHashMap<>(16, 0.75f, true);
            dirty = false;
            Path f = file();
            if (f != null && Files.exists(f)) {
                try {
                    Map<String, Map<String, String>> read = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8),
                            new TypeToken<Map<String, Map<String, String>>>() {}.getType());
                    if (read != null) {
                        entries.putAll(read);
                    }
                } catch (Exception e) {
                    // a broken cache is an empty one
                }
            }
        }
        return entries;
    }

    private static Path file() {
        String id = Profile.id();
        return id.isEmpty() ? null : Profile.dir().resolve("roadmap-cache.json");
    }

    static synchronized boolean has(String key) {
        return entries().containsKey(key);
    }

    /** True when the beam found nothing better for these inputs. */
    static synchronized boolean isNone(String key) {
        Map<String, String> e = entries().get(key);
        return e != null && e.containsKey(NONE);
    }

    /**
     * The set kept for the key, its pieces taken from the two starting sets or the item data;
     * null when there is none, or a piece is gone or rolls otherwise now (search again then).
     */
    static synchronized Map<String, Damage.Piece> get(String key, Map<String, Damage.Piece> start,
            Map<String, Damage.Piece> build) {
        return get(key, java.util.List.of(start, build));
    }

    /** {@link #get(String, Map, Map)} with the pieces taken from any of {@code sets} (what you wear too). */
    static synchronized Map<String, Damage.Piece> get(String key, java.util.List<Map<String, Damage.Piece>> sets) {
        Map<String, String> e = entries().get(key);
        if (e == null || e.containsKey(NONE)) {
            return null;
        }
        Map<String, Damage.Piece> out = new LinkedHashMap<>();
        for (var se : e.entrySet()) {
            String[] kn = se.getValue().split("\n", 2);
            Damage.Piece p = null;
            for (Map<String, Damage.Piece> s : sets) {
                for (Damage.Piece q : s.values()) {         // any slot: a ring may change hands
                    if (p == null && q != null && q.key().equals(kn[0])) {
                        p = q;
                    }
                }
            }
            if (p == null && kn.length == 2) {
                var info = GameState.current().allGear().filter(i -> i.name().equals(kn[1])).findFirst().orElse(null);
                p = info == null ? null : Roadmap.pieceOf(info);     // a copy you have, with its rolls
            }
            if (p == null || !p.key().equals(kn[0])) {
                return null;
            }
            out.put(se.getKey(), p);
        }
        return out;
    }

    static synchronized void put(String key, Map<String, Damage.Piece> set) {
        Map<String, String> e = new LinkedHashMap<>();
        if (set == null) {
            e.put(NONE, "");
        } else {
            set.forEach((slot, p) -> {
                if (p != null) {
                    e.put(slot, p.key() + "\n" + p.name());
                }
            });
        }
        Map<String, Map<String, String>> all = entries();
        all.put(key, e);
        while (all.size() > MAX) {
            all.remove(all.keySet().iterator().next());
        }
        dirty = true;
    }

    /** Writes the entries when they changed (the road map builder calls this after a map). */
    static synchronized void save() {
        Path f = file();
        if (!dirty || f == null) {
            return;
        }
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(entries), StandardCharsets.UTF_8);
            dirty = false;
        } catch (Exception e) {
            // kept in memory; written next time
        }
    }

    /** Tests only: forget everything. */
    public static synchronized void clear() {
        entries = new LinkedHashMap<>(16, 0.75f, true);
        loadedFor = null;
        dirty = false;
    }
}
