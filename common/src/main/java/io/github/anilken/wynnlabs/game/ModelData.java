package io.github.anilken.wynnlabs.game;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Wynntils' downloaded model table ({@code wynntils/cache/dataStaticModelData}), read once off the
 * game thread as plain JSON: model key to the {@code CUSTOM_MODEL_DATA} float (raid-lootrun-tracking,
 * design decision 11). No Minecraft or Wynntils type, and nothing is downloaded: the file is the one
 * Wynntils already keeps.
 *
 * <p>API contract of group 8 (design.md): the signatures below stay as they are.
 */
public final class ModelData {

    /** The file's name inside Wynntils' cache folder. */
    public static final String FILE = "dataStaticModelData";
    /** While the file is missing (Wynntils has not downloaded it yet), it is looked for again this often. */
    private static final long RETRY_MS = 30_000L;

    /** The table; null until read. */
    private static volatile Map<String, Float> floats;
    private static volatile boolean reading;
    private static volatile long lastTry = -RETRY_MS;

    private ModelData() {}

    /** Starts the one read of {@code dataStaticModelData} off the game thread; no-op when running or done. */
    public static void load(Path wynntilsCacheDir) {
        if (floats != null || reading || wynntilsCacheDir == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTry < RETRY_MS) {
            return;
        }
        reading = true;
        lastTry = now;
        Path f = wynntilsCacheDir.resolve(FILE);
        Thread t = new Thread(() -> {
            try {
                if (Files.isRegularFile(f)) {
                    Map<String, Float> table = parse(Files.readString(f, StandardCharsets.UTF_8));
                    if (floats == null) {
                        floats = table;
                    }
                }
            } catch (Throwable e) {
                System.out.println("[WynnLabs] model data unavailable: " + e.getClass().getSimpleName());
            } finally {
                reading = false;
            }
        }, "wynnlabs-models");
        t.setDaemon(true);
        t.start();
    }

    /** The float for this model key; {@link Float#NaN} when unknown or not loaded. */
    public static float of(String key) {
        Map<String, Float> m = floats;
        if (m == null || key == null) {
            return Float.NaN;
        }
        Float f = m.get(key);
        return f == null ? Float.NaN : f;
    }

    /** Whether the table is read. */
    public static boolean ready() {
        return floats != null;
    }

    /** Tests and preview: use this table. */
    public static void use(Map<String, Float> table) {
        floats = table == null ? Map.of() : Map.copyOf(table);
    }

    /** Tests and preview: forget the table. */
    public static void reset() {
        floats = null;
        lastTry = -RETRY_MS;
    }

    /** Pure: the {@code floats} object of the file ({@code {"floats":{key: f}, "ranges":{...}}}); empty on bad input. */
    public static Map<String, Float> parse(String json) {
        Map<String, Float> out = new HashMap<>();
        try {
            JsonElement root = JsonParser.parseString(json == null ? "" : json);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("floats")
                    || !root.getAsJsonObject().get("floats").isJsonObject()) {
                return Map.of();
            }
            JsonObject fl = root.getAsJsonObject().getAsJsonObject("floats");
            for (Map.Entry<String, JsonElement> e : fl.entrySet()) {
                JsonElement v = e.getValue();
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                    float f = v.getAsFloat();
                    if (Float.isFinite(f)) {
                        out.put(e.getKey(), f);
                    }
                }
            }
        } catch (RuntimeException e) {
            return Map.of();
        }
        return Map.copyOf(out);
    }
}
