package io.github.anilken.wynnlabs.crafting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The game's crafting recipes, shipped in {@code assets/wynnlabs/crafting/recipes.json} (written
 * by {@code tools/crafting/update.py} from Wynncraft's official API). Plain data, no Minecraft
 * types, so the calculator and its tests run anywhere.
 */
public final class CraftData {

    /** One of a recipe's two materials: its internal name, the name the game shows, how many. */
    public record Material(String item, String shown, int amount) {}

    /**
     * One recipe: item type and profession, level bracket, the two materials, and the ranges the
     * game rolls within (health or damage, durability for gear; duration and the no-ingredient
     * duration for consumables), plus the profession XP it gives. Absent ranges are null.
     */
    public record Recipe(String id, String type, String profession, int minLevel, int maxLevel, List<Material> materials,
                         int[] healthOrDamage, int[] durability, int[] duration, int[] basicDuration, int xp) {

        public boolean consumable() {
            return Types.consumable(type);
        }

        public String bracket() {
            return minLevel + "-" + maxLevel;
        }
    }

    private static volatile List<Recipe> recipes;
    private static volatile String fetched = "";

    private CraftData() {}

    /** Every recipe, sorted by type then level. Loaded once. */
    public static List<Recipe> all() {
        List<Recipe> r = recipes;
        if (r == null) {
            r = load();
            recipes = r;
        }
        return r;
    }

    /** When the shipped file was fetched from the API ("2026-10-17"). */
    public static String fetched() {
        all();
        return fetched;
    }

    /** The recipes of one item type ("chestplate"), lowest bracket first. */
    public static List<Recipe> ofType(String type) {
        List<Recipe> out = new ArrayList<>();
        for (Recipe r : all()) {
            if (r.type().equals(type)) {
                out.add(r);
            }
        }
        return out;
    }

    /** The recipe of a type whose bracket starts at {@code minLevel}, or null. */
    public static Recipe find(String type, int minLevel) {
        for (Recipe r : all()) {
            if (r.type().equals(type) && r.minLevel() == minLevel) {
                return r;
            }
        }
        return null;
    }

    /** The recipe with this id ("Spear1-3"), or null. */
    public static Recipe byId(String id) {
        for (Recipe r : all()) {
            if (r.id().equals(id)) {
                return r;
            }
        }
        return null;
    }

    /** Recipe counts per type, for checks. */
    public static Map<String, Integer> countByType() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Recipe r : all()) {
            out.merge(r.type(), 1, Integer::sum);
        }
        return out;
    }

    private static List<Recipe> load() {
        try (InputStream in = CraftData.class.getResourceAsStream("/assets/wynnlabs/crafting/recipes.json")) {
            if (in == null) {
                return List.of();
            }
            JsonObject doc = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            fetched = doc.has("fetched") ? doc.get("fetched").getAsString() : "";
            List<Recipe> out = new ArrayList<>();
            for (JsonElement e : doc.getAsJsonArray("recipes")) {
                JsonObject o = e.getAsJsonObject();
                List<Material> mats = new ArrayList<>();
                for (JsonElement m : o.getAsJsonArray("materials")) {
                    JsonObject mo = m.getAsJsonObject();
                    mats.add(new Material(mo.get("item").getAsString(), mo.get("shown").getAsString(), mo.get("amount").getAsInt()));
                }
                JsonArray lv = o.getAsJsonArray("level");
                out.add(new Recipe(o.get("id").getAsString(), o.get("type").getAsString(), o.get("skill").getAsString(),
                        lv.get(0).getAsInt(), lv.get(1).getAsInt(), List.copyOf(mats), range(o, "healthOrDamage"),
                        range(o, "durability"), range(o, "duration"), range(o, "basicDuration"),
                        o.has("xp") ? o.get("xp").getAsInt() : 0));
            }
            return Collections.unmodifiableList(out);
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not read the shipped crafting recipes: " + e);
            return List.of();
        }
    }

    private static int[] range(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonArray()) {
            return null;
        }
        JsonArray a = e.getAsJsonArray();
        return new int[] {a.get(0).getAsInt(), a.get(1).getAsInt()};
    }
}
