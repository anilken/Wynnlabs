package io.github.anilken.wynnlabs.crafting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The player's saved recipes, by unique name, in the order they were saved. Each keeps the
 * recipe, tiers, attack speed, the six slots by ingredient name (an ingredient the game data no
 * longer has stays in the recipe and is shown as unknown) and the goal it was made for. Plain
 * data: the caller stores the JSON (shared by every character).
 */
public final class SavedRecipes {

    private static final int FORMAT = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** A goal as saved. */
    public record SavedGoal(Map<String, Double> weights, String pick, List<String> noNegative, int[] caps,
                            int durabilityFloor) {

        public static SavedGoal of(CraftSearch.Goal g) {
            return new SavedGoal(Map.copyOf(g.weights()), g.pick().name(), List.copyOf(g.noNegative()), g.caps().clone(),
                    g.durabilityFloor());
        }

        public CraftSearch.Goal goal() {
            CraftSearch.Pick p;
            try {
                p = CraftSearch.Pick.valueOf(pick);
            } catch (Exception e) {
                p = CraftSearch.Pick.MEAN;
            }
            int[] c = caps == null || caps.length != 5 ? CraftSearch.Goal.uncapped() : caps.clone();
            return new CraftSearch.Goal(weights == null ? Map.of() : Map.copyOf(weights), p,
                    noNegative == null ? Set.of() : Set.copyOf(noNegative), c, durabilityFloor);
        }
    }

    /** One saved recipe; {@code slots} has six names, null for an empty slot. */
    public record Saved(String name, String type, int minLevel, int tier1, int tier2, String speed, List<String> slots,
                        SavedGoal goal, long savedAt) {

        public Craft.Speed attackSpeed() {
            try {
                return Craft.Speed.valueOf(speed);
            } catch (Exception e) {
                return Craft.Speed.NORMAL;
            }
        }

        public CraftData.Recipe recipe() {
            return CraftData.find(type, minLevel);
        }

        Saved named(String n) {
            return new Saved(n, type, minLevel, tier1, tier2, speed, slots, goal, savedAt);
        }
    }

    private final List<Saved> list = new ArrayList<>();

    public List<Saved> all() {
        return Collections.unmodifiableList(list);
    }

    public Saved get(String name) {
        for (Saved s : list) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }

    public boolean has(String name) {
        return get(name) != null;
    }

    /** Saves; when the name is taken it replaces that recipe only if {@code replace}, else returns false. */
    public boolean save(Saved s, boolean replace) {
        String name = s.name() == null ? "" : s.name().strip();
        if (name.isEmpty()) {
            return false;
        }
        Saved clean = s.named(name);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name().equals(name)) {
                if (!replace) {
                    return false;
                }
                list.set(i, clean);
                return true;
            }
        }
        list.add(clean);
        return true;
    }

    /** Renames; false when the old one is missing or the new name is empty or taken. */
    public boolean rename(String from, String to) {
        String n = to == null ? "" : to.strip();
        if (n.isEmpty() || has(n)) {
            return false;
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name().equals(from)) {
                list.set(i, list.get(i).named(n));
                return true;
            }
        }
        return false;
    }

    /** Copies under the first free "name (2)", "name (3)" …, right after the original; null when it is missing. */
    public Saved copy(String name) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name().equals(name)) {
                Saved c = list.get(i).named(free(name));
                list.add(i + 1, c);
                return c;
            }
        }
        return null;
    }

    public boolean delete(String name) {
        return list.removeIf(s -> s.name().equals(name));
    }

    /** {@code base} when free, else the first free "base (n)". */
    public String free(String base) {
        if (!has(base)) {
            return base;
        }
        for (int n = 2; ; n++) {
            String c = base + " (" + n + ")";
            if (!has(c)) {
                return c;
            }
        }
    }

    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT);
        o.add("recipes", GSON.toJsonTree(list));
        return GSON.toJson(o);
    }

    /** Reads saved JSON; anything unreadable gives an empty list (the file is left for the player). */
    public static SavedRecipes fromJson(String json) {
        SavedRecipes r = new SavedRecipes();
        if (json == null || json.isBlank()) {
            return r;
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            List<Saved> read = GSON.fromJson(o.get("recipes"), new TypeToken<List<Saved>>() {}.getType());
            if (read != null) {
                for (Saved s : read) {
                    if (s != null && s.name() != null && s.type() != null) {
                        List<String> slots = s.slots() == null ? Arrays.asList(new String[Craft.SLOTS])
                                : new ArrayList<>(s.slots());
                        while (slots.size() < Craft.SLOTS) {
                            slots.add(null);
                        }
                        r.save(new Saved(s.name(), s.type(), s.minLevel(), s.tier1(), s.tier2(), s.speed(),
                                Collections.unmodifiableList(slots.subList(0, Craft.SLOTS)), s.goal(), s.savedAt()), false);
                    }
                }
            }
        } catch (Exception e) {
            return new SavedRecipes();
        }
        return r;
    }
}
