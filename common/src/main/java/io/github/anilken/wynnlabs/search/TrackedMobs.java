package io.github.anilken.wynnlabs.search;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import io.github.anilken.wynnlabs.core.Profile;
import io.github.anilken.wynnlabs.core.Saver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The mobs the player chose to track from the search card ("Track" on a mob row), per
 * character in {@code characters/<id>/tracked-mobs.json} (written in the background by
 * {@link Saver}). Read again when the character changes; {@link #keys} is what the tracker
 * compares floating names with ({@link MobName#key}).
 */
public final class TrackedMobs {

    /** More than this and the oldest one is dropped. */
    public static final int MAX = 12;
    private static final Gson GSON = new Gson();
    private static TrackedMobs shared;

    private final Path file;
    private final List<String> names = new ArrayList<>();
    private volatile Set<String> keys = Set.of();

    TrackedMobs(Path file) {
        this.file = file;
        try {
            if (file != null && Files.isRegularFile(file)) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                JsonElement root = GSON.fromJson(text, JsonElement.class);
                if (root != null && root.isJsonArray()) {
                    for (JsonElement e : root.getAsJsonArray()) {
                        if (e.isJsonPrimitive() && names.size() < MAX && !has(e.getAsString())) {
                            names.add(e.getAsString());
                        }
                    }
                }
                Saver.known(file, text);
            }
        } catch (Exception e) {
            System.out.println("[WynnLabs] tracked-mobs.json unreadable: " + e);
        }
        rekey();
    }

    /** This character's list, read again when another character is played. */
    public static synchronized TrackedMobs get() {
        Path f = Profile.dir().resolve("tracked-mobs.json");
        if (shared == null || !f.equals(shared.file)) {
            shared = new TrackedMobs(f);
        }
        return shared;
    }

    /** The tracked names as the item data writes them, oldest first. */
    public synchronized List<String> names() {
        return List.copyOf(names);
    }

    /** The cleaned keys of the tracked names; never null, cheap to call. */
    public Set<String> keys() {
        return keys;
    }

    public synchronized boolean has(String name) {
        String k = MobName.key(name);
        for (String n : names) {
            if (MobName.key(n).equals(k)) {
                return true;
            }
        }
        return false;
    }

    /** Tracks {@code name}, or stops tracking it when it already is; returns whether it is tracked now. */
    public synchronized boolean toggle(String name) {
        if (name == null || MobName.key(name).isEmpty()) {
            return false;
        }
        boolean now;
        if (has(name)) {
            String k = MobName.key(name);
            names.removeIf(n -> MobName.key(n).equals(k));
            now = false;
        } else {
            names.add(name.strip());
            while (names.size() > MAX) {
                names.remove(0);
            }
            now = true;
        }
        changed();
        return now;
    }

    /** Stops tracking every mob. */
    public synchronized void clear() {
        names.clear();
        changed();
    }

    private void changed() {
        rekey();
        if (file == null) {
            return;
        }
        JsonArray arr = new JsonArray();
        names.forEach(arr::add);
        String text = GSON.toJson(arr);
        Saver.mark(file, () -> text);
    }

    private void rekey() {
        Set<String> k = new HashSet<>();
        for (String n : names) {
            k.add(MobName.key(n));
        }
        keys = Set.copyOf(k);
    }
}
