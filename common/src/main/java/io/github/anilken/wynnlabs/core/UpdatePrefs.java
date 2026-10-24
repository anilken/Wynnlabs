package io.github.anilken.wynnlabs.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * The updater's folder ({@code config/wynnlabs/updates/}) and the player's ignore choices:
 * "Ignore now" lasts this run, "Ignore this version" is kept in {@code updates/state.json}. Both
 * silence the chat line and the auto update only; the About page always shows the truth.
 */
public final class UpdatePrefs {

    /** The updates folder; tests point it at a temporary one. */
    static Supplier<Path> folder = () -> Dirs.config().resolve("updates");

    private static volatile boolean ignoredNow;
    private static String ignored;
    private static boolean loaded;

    private UpdatePrefs() {}

    public static Path folder() {
        return folder.get();
    }

    /** "Ignore now": no reminder and no auto update for the rest of this run. */
    public static void ignoreNow() {
        ignoredNow = true;
    }

    /** "Ignore this version": {@code tag} stays silent across runs; a later tag is announced again. */
    public static synchronized void ignore(String tag) {
        ignored = tag;
        loaded = true;
        try {
            Path f = folder().resolve("state.json");
            Files.createDirectories(f.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("ignored", tag);
            Files.writeString(f, o.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[WynnLabs] update: could not save the ignored version: " + e.getClass().getSimpleName());
        }
    }

    /** The version the player chose to ignore, null when none. */
    public static synchronized String ignored() {
        if (!loaded) {
            loaded = true;
            try {
                Path f = folder().resolve("state.json");
                if (Files.isRegularFile(f)) {
                    JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                    ignored = o.has("ignored") ? o.get("ignored").getAsString() : null;
                }
            } catch (Exception e) {
                ignored = null;
            }
        }
        return ignored;
    }

    /** Whether the reminder and the auto update keep quiet about {@code tag}. */
    public static boolean silenced(String tag) {
        return ignoredNow || tag != null && tag.equals(ignored());
    }

    /** Whether {@code tag} is the one ignored across runs (the About page says so). */
    public static boolean ignoredVersion(String tag) {
        return tag != null && tag.equals(ignored());
    }

    /** Tests: as a fresh run, the stored choice read again. */
    static synchronized void reset() {
        ignoredNow = false;
        ignored = null;
        loaded = false;
    }
}
