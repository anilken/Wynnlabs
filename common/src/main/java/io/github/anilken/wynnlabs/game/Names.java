package io.github.anilken.wynnlabs.game;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Item names back to English. Translation mods (WynnChaYuan) rename ingredients, materials and
 * more in the game itself ("Natural Nitrates" shows as "Doğal Nitratlar"), but prices, the
 * market and the game data know the English name. When WynnChaYuan is loaded, its name tables
 * (every language) are read once from the loaded resources and turned around; without it every
 * name is returned as it is.
 */
public final class Names {

    private static volatile Map<String, String> english;

    private Names() {}

    /** The English name for a shown (maybe translated) item name; the name itself if unknown. */
    public static String english(String shown) {
        if (shown == null || shown.isBlank()) {
            return shown;
        }
        Map<String, String> m = english;
        if (m == null) {
            m = load();
        }
        String e = m.get(shown.strip().toLowerCase(java.util.Locale.ROOT));
        return e != null ? e : shown;
    }

    private static synchronized Map<String, String> load() {
        if (english != null) {
            return english;
        }
        Map<String, String> m = new ConcurrentHashMap<>();
        try {
            var rm = Minecraft.getInstance().getResourceManager();
            var files = rm.listResources("translations", id -> {
                if (!id.getNamespace().equals("wynnchayuan")) {
                    return false;
                }
                String f = id.getPath().substring(id.getPath().lastIndexOf('/') + 1);
                return f.equals("ingredient.json") || f.equals("material.json") || f.startsWith("gear-")
                        || f.equals("charm.json") || f.equals("tome.json");
            });
            for (var res : files.values()) {
                try (var in = new InputStreamReader(res.open(), StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(in).getAsJsonObject();
                    if (!root.has("entries")) {
                        continue;
                    }
                    for (var e : root.getAsJsonObject("entries").entrySet()) {
                        JsonObject o = e.getValue().getAsJsonObject();
                        if (!o.has("src") || !o.has("dst") || o.get("dst").isJsonNull()) {
                            continue;
                        }
                        if (o.has("role") && !"name".equals(o.get("role").getAsString())) {
                            continue;
                        }
                        String src = o.get("src").getAsString();
                        String dst = o.get("dst").getAsString().strip();
                        if (!dst.isEmpty() && !dst.equals(src)) {
                            m.putIfAbsent(dst.toLowerCase(java.util.Locale.ROOT), src);
                        }
                    }
                } catch (Exception ignored) {
                    // one bad file: skip it
                }
            }
        } catch (Throwable t) {
            System.out.println("[WynnLabs] could not read translation names: " + t);
        }
        english = m;
        System.out.println("[WynnLabs] translated item names known: " + m.size());
        return m;
    }

    /** Forget the table (resources reloaded). */
    static void clear() {
        english = null;
    }
}
