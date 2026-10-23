package io.github.anilken.wynnlabs.search;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loot chest positions: the list shipped in the jar ({@code assets/wynnlabs/chests.json}, public
 * world data) merged with the player's own {@code config/wynnlabs/chests.json} when present. Both
 * are arrays of {@code {x, y, z, stars}}; stars is the chest's tier (0 when not recorded). The
 * same position is kept once, the shipped entry first unless it has no tier and the player's has.
 * Chests carry no level here; {@link Places} gives each the level of the nearest known place.
 */
public final class LootChests {

    static final String SHIPPED = "/assets/wynnlabs/chests.json";

    private LootChests() {}

    /** Shipped and player chests, merged; {@code userFile} may be null or missing. */
    public static List<Area> load(Path userFile) {
        List<Area> shipped = List.of();
        try (InputStream in = LootChests.class.getResourceAsStream(SHIPPED)) {
            if (in != null) {
                shipped = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            System.out.println("[WynnLabs] shipped chest list unreadable: " + e);
        }
        List<Area> user = List.of();
        if (userFile != null && Files.isRegularFile(userFile)) {
            try {
                user = parse(Files.readString(userFile, StandardCharsets.UTF_8));
            } catch (Exception e) {
                System.out.println("[WynnLabs] chests.json unreadable: " + e);
            }
        }
        return merge(shipped, user);
    }

    /** One area per {x, y, z, stars} object; anything else in the array is skipped. */
    static List<Area> parse(String json) {
        List<Area> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) {
            return out;
        }
        for (JsonElement e : (JsonArray) root) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            if (!o.has("x") || !o.has("y") || !o.has("z")) {
                continue;
            }
            try {
                int stars = o.has("stars") ? o.get("stars").getAsInt() : 0;
                out.add(new Area(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(), 0, -1,
                        Math.max(0, Math.min(4, stars)), null));
            } catch (RuntimeException ignored) {
                // a malformed entry
            }
        }
        return out;
    }

    /** {@code a} then the new positions of {@code b}; a tier from {@code b} fills a tierless one of {@code a}. */
    static List<Area> merge(List<Area> a, List<Area> b) {
        List<Area> out = new ArrayList<>();
        Map<Long, Integer> at = new HashMap<>();
        for (List<Area> list : List.of(a, b)) {
            for (Area c : list) {
                long k = key(c);
                Integer i = at.get(k);
                if (i == null) {
                    at.put(k, out.size());
                    out.add(c);
                } else if (out.get(i).stars() == 0 && c.stars() > 0) {
                    out.set(i, c);
                }
            }
        }
        return out;
    }

    private static long key(Area c) {
        return ((long) c.x() & 0x1FFFFF) << 42 | ((long) c.y() & 0x1FFFFF) << 21 | ((long) c.z() & 0x1FFFFF);
    }
}
