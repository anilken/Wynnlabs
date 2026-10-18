package io.github.anilken.wynnlabs.crafting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WynnBuilder's crafter codes ("CR-..."), the ones players share. Written and read by the
 * mod's own code from the format alone: a bit string, lowest bit first, six bits per letter
 * (0-9, A-Z, a-z, +, -). The current form is a 0 bit, a 7-bit version (2), six 12-bit
 * ingredient ids (4000 for an empty slot), the 12-bit recipe id, both material tiers minus one
 * in 3 bits each, for weapons the attack speed in 4 bits (slow 0, normal 1, fast 2), then 1 to 6
 * zero bits to end on a whole letter. The older form starts with "1": six 2-letter ingredient ids, a 2-letter
 * recipe id, one letter for the tiers and one for the attack speed, each number high letter
 * first. The ids are WynnBuilder's, shipped in {@code wynnbuilder-ids.json}.
 */
public final class WynnBuilderCode {

    public static final String PREFIX = "CR-";
    private static final String DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz+-";
    private static final int VERSION = 2;
    private static final int NO_INGREDIENT = 4000;

    private static volatile Ids ids;

    private record Ids(Map<String, Integer> ingredient, Map<Integer, String> ingredientName,
                       Map<String, Integer> recipe, Map<Integer, String> recipeKey) {}

    private WynnBuilderCode() {}

    /** The code for a recipe, or null when WynnBuilder has no id for its recipe or an ingredient. */
    public static String encode(RecipeCode.Recipe r) {
        Ids t = ids();
        Integer recipe = t.recipe().get(key(r.type(), r.minLevel()));
        if (recipe == null) {
            return null;
        }
        Bits b = new Bits();
        b.put(0, 1);
        b.put(VERSION, 7);
        for (int i = 0; i < Craft.SLOTS; i++) {
            String name = i < r.slots().size() ? r.slots().get(i) : null;
            if (name == null || name.isEmpty()) {
                b.put(NO_INGREDIENT, 12);
            } else {
                Integer id = t.ingredient().get(name);
                if (id == null) {
                    return null;
                }
                b.put(id, 12);
            }
        }
        b.put(recipe, 12);
        b.put(clamp(r.tier1()) - 1, 3);
        b.put(clamp(r.tier2()) - 1, 3);
        if (Types.weapon(r.type())) {
            b.put(r.speed().ordinal(), 4);
        }
        return PREFIX + b.text();
    }

    /** A recipe from a code ("CR-" optional), or null when it is not one or names something unknown. */
    public static RecipeCode.Recipe decode(String text) {
        if (text == null) {
            return null;
        }
        String s = text.strip();
        if (s.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            s = s.substring(PREFIX.length());
        }
        if (s.isEmpty() || s.chars().anyMatch(c -> DIGITS.indexOf(c) < 0)) {
            return null;
        }
        try {
            return (DIGITS.indexOf(s.charAt(0)) & 1) == 1 ? legacy(s) : current(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static RecipeCode.Recipe current(String s) {
        Bits b = new Bits(s);
        b.take(1);
        b.take(7);                                  // the version: 2 is the only one so far
        List<String> slots = new ArrayList<>();
        for (int i = 0; i < Craft.SLOTS; i++) {
            slots.add(ingredient(b.take(12)));
        }
        String key = ids().recipeKey().get(b.take(12));
        int t1 = b.take(3) + 1;
        int t2 = b.take(3) + 1;
        return build(key, t1, t2, () -> b.take(4), slots);
    }

    private static RecipeCode.Recipe legacy(String s) {
        // "1" + six 2-letter ingredients + 2-letter recipe + tiers + attack speed
        if (s.length() < 17) {
            return null;
        }
        String body = s.substring(1);
        List<String> slots = new ArrayList<>();
        for (int i = 0; i < Craft.SLOTS; i++) {
            slots.add(ingredient(number(body.substring(2 * i, 2 * i + 2))));
        }
        String key = ids().recipeKey().get(number(body.substring(12, 14)));
        int tiers = number(body.substring(14, 15));
        int t1 = tiers % 3 == 0 ? 3 : tiers % 3;
        int t2 = (tiers - 1) / 3 + 1;
        int speed = number(body.substring(15, 16));
        return build(key, t1, t2, () -> speed, slots);
    }

    private static RecipeCode.Recipe build(String key, int t1, int t2, java.util.function.IntSupplier speed,
                                           List<String> slots) {
        if (key == null || slots.contains("?")) {
            return null;
        }
        CraftData.Recipe r = byKey(key);
        if (r == null || t1 < 1 || t1 > 3 || t2 < 1 || t2 > 3) {
            return null;
        }
        Craft.Speed sp = Craft.Speed.NORMAL;
        if (Types.weapon(r.type())) {
            int v = speed.getAsInt();
            if (v < 0 || v >= Craft.Speed.values().length) {
                return null;
            }
            sp = Craft.Speed.values()[v];
        }
        return new RecipeCode.Recipe(r.type(), r.minLevel(), t1, t2, sp, Collections.unmodifiableList(slots));
    }

    /** An ingredient's name from its id: null for the empty slot, "?" when the id is unknown. */
    private static String ingredient(int id) {
        if (id == NO_INGREDIENT) {
            return null;
        }
        String n = ids().ingredientName().get(id);
        return n == null ? "?" : n;
    }

    /** "Leggings1-3": WynnBuilder's recipe name without its first dash. */
    private static String key(String type, int minLevel) {
        CraftData.Recipe r = CraftData.find(type, minLevel);
        if (r == null) {
            return "";
        }
        return Character.toUpperCase(type.charAt(0)) + type.substring(1) + r.bracket();
    }

    private static CraftData.Recipe byKey(String key) {
        for (String type : Types.ALL) {
            for (CraftData.Recipe r : CraftData.ofType(type)) {
                if (key.equals(key(type, r.minLevel()))) {
                    return r;
                }
            }
        }
        return null;
    }

    private static int clamp(int t) {
        return Math.max(1, Math.min(3, t));
    }

    /** A number written high letter first (the older form). */
    private static int number(String s) {
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            v = v * 64 + DIGITS.indexOf(s.charAt(i));
        }
        return v;
    }

    /** A bit string, lowest bit first, read and written six bits per letter. */
    private static final class Bits {
        private final List<Boolean> bits = new ArrayList<>();
        private int at;

        Bits() {}

        Bits(String text) {
            for (int i = 0; i < text.length(); i++) {
                put(DIGITS.indexOf(text.charAt(i)), 6);
            }
        }

        void put(int value, int n) {
            for (int i = 0; i < n; i++) {
                bits.add(((value >> i) & 1) == 1);
            }
        }

        int take(int n) {
            if (at + n > bits.size()) {
                throw new IllegalArgumentException("code too short");
            }
            int v = 0;
            for (int i = 0; i < n; i++) {
                if (bits.get(at + i)) {
                    v |= 1 << i;
                }
            }
            at += n;
            return v;
        }

        String text() {
            put(0, 6 - bits.size() % 6);       // like the site: a whole letter of zeros when it already fits
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < bits.size(); i += 6) {
                int v = 0;
                for (int j = 0; j < 6; j++) {
                    if (bits.get(i + j)) {
                        v |= 1 << j;
                    }
                }
                sb.append(DIGITS.charAt(v));
            }
            return sb.toString();
        }
    }

    private static Ids ids() {
        Ids t = ids;
        if (t == null) {
            t = load();
            ids = t;
        }
        return t;
    }

    private static Ids load() {
        Map<String, Integer> ing = new HashMap<>();
        Map<Integer, String> ingName = new HashMap<>();
        Map<String, Integer> rec = new HashMap<>();
        Map<Integer, String> recKey = new HashMap<>();
        try (InputStream in = WynnBuilderCode.class.getResourceAsStream("/assets/wynnlabs/crafting/wynnbuilder-ids.json")) {
            if (in != null) {
                JsonObject doc = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : doc.getAsJsonObject("ingredients").entrySet()) {
                    ing.put(e.getKey(), e.getValue().getAsInt());
                    ingName.put(e.getValue().getAsInt(), e.getKey());
                }
                for (Map.Entry<String, JsonElement> e : doc.getAsJsonObject("recipes").entrySet()) {
                    rec.put(e.getKey(), e.getValue().getAsInt());
                    recKey.put(e.getValue().getAsInt(), e.getKey());
                }
            }
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not read WynnBuilder's ids: " + e);
        }
        return new Ids(Map.copyOf(ing), Map.copyOf(ingName), Map.copyOf(rec), Map.copyOf(recKey));
    }

    /** True when a pasted text looks like a WynnBuilder code rather than the mod's own. */
    public static boolean looksLike(String text) {
        return text != null && text.strip().toUpperCase(Locale.ROOT).startsWith(PREFIX);
    }
}
