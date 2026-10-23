package io.github.anilken.wynnlabs.search;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import io.github.anilken.wynnlabs.core.Dirs;
import io.github.anilken.wynnlabs.core.Saver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The last {@link #MAX} item names the player searched for or opened, newest first, kept across
 * restarts in {@code config/wynnlabs/search-recent.json} (written in the background by
 * {@link Saver}). Read once when first asked for, not per frame.
 */
public final class RecentSearches {

    public static final int MAX = 10;
    private static final Gson GSON = new Gson();
    private static RecentSearches shared;

    private final Path file;
    private final List<String> names = new ArrayList<>();

    RecentSearches(Path file) {
        this.file = file;
        try {
            if (Files.isRegularFile(file)) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                JsonElement root = GSON.fromJson(text, JsonElement.class);
                if (root != null && root.isJsonArray()) {
                    for (JsonElement e : root.getAsJsonArray()) {
                        if (e.isJsonPrimitive() && names.size() < MAX && !names.contains(e.getAsString())) {
                            names.add(e.getAsString());
                        }
                    }
                }
                Saver.known(file, text);
            }
        } catch (Exception e) {
            System.out.println("[WynnLabs] search-recent.json unreadable: " + e);
        }
    }

    /** The player's list ({@code config/wynnlabs/search-recent.json}), read on first use. */
    public static synchronized RecentSearches get() {
        if (shared == null) {
            shared = new RecentSearches(Dirs.config().resolve("search-recent.json"));
        }
        return shared;
    }

    /** Newest first. */
    public synchronized List<String> names() {
        return List.copyOf(names);
    }

    /** Puts {@code name} first (moving it up when already listed), drops the oldest past {@link #MAX}, saves soon. */
    public synchronized void add(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        String n = name.trim();
        names.remove(n);
        names.add(0, n);
        while (names.size() > MAX) {
            names.remove(names.size() - 1);
        }
        JsonArray arr = new JsonArray();
        names.forEach(arr::add);
        String text = GSON.toJson(arr);
        Saver.mark(file, () -> text);
    }
}
