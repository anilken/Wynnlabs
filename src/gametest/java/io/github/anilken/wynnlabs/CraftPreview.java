package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.anilken.wynnlabs.crafting.Ingredient;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The crafting tab in the preview. The recorded game has no Wynntils, so a sample of real
 * ingredients (src/gametest/resources) stands in, and every crafting profession is at 105.
 */
final class CraftPreview {

    private CraftPreview() {}

    static List<Ingredient> ingredients() {
        try (var in = CraftPreview.class.getResourceAsStream("/crafting-preview-ingredients.json")) {
            JsonObject doc = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            List<Ingredient> out = new ArrayList<>();
            for (JsonElement e : doc.getAsJsonArray("ingredients")) {
                JsonObject o = e.getAsJsonObject();
                Map<String, Ingredient.Stat> ids = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> id : o.getAsJsonObject("ids").entrySet()) {
                    JsonObject r = id.getValue().getAsJsonObject();
                    ids.put(id.getKey(), new Ingredient.Stat(r.get("min").getAsInt(), r.get("max").getAsInt(),
                            shown(id.getKey()), unit(id.getKey())));
                }
                List<String> profs = new ArrayList<>();
                o.getAsJsonArray("professions").forEach(p -> profs.add(p.getAsString()));
                out.add(new Ingredient(o.get("name").getAsString(), o.get("tier").getAsInt(), o.get("level").getAsInt(),
                        List.copyOf(profs), ids, ints(o.getAsJsonArray("positions")), o.get("durability").getAsInt(),
                        o.get("charges").getAsInt(), o.get("duration").getAsInt(), ints(o.getAsJsonArray("requirements"))));
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int[] ints(JsonArray a) {
        int[] out = new int[a.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = a.get(i).getAsInt();
        }
        return out;
    }

    /** "rawSpellDamage" → "Spell Damage" (Wynntils gives the real names in game). */
    private static String shown(String id) {
        String s = id.startsWith("raw") ? id.substring(3) : id;
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isUpperCase(c) && !b.isEmpty()) {
                b.append(' ');
            }
            b.append(b.isEmpty() ? Character.toUpperCase(c) : c);
        }
        return b.toString();
    }

    private static String unit(String id) {
        if (id.equals("manaRegen")) {
            return "/5s";
        }
        if (id.equals("manaSteal") || id.equals("lifeSteal")) {
            return "/3s";
        }
        return id.startsWith("raw") || id.equals("healthRegenRaw") || id.equals("poison") ? "" : "%";
    }

    static void levels() {
        Map<String, Integer> lv = new LinkedHashMap<>();
        for (String p : List.of("alchemism", "armouring", "cooking", "jeweling", "scribing", "tailoring", "weaponsmithing",
                "woodworking")) {
            lv.put(p, 105);
        }
        CraftingTab.previewLevels = lv;
    }

    /** Sets the tab's recipe: type, bracket, tiers and the six slots (null for empty). */
    static void recipe(String type, int minLevel, int t1, int t2, String... slots) throws ReflectiveOperationException {
        CraftingTab tab = CraftingTab.INSTANCE;
        set(tab, "type", type);
        set(tab, "minLevel", minLevel);
        set(tab, "tier1", t1);
        set(tab, "tier2", t2);
        String[] s = (String[]) get(tab, "slots");
        Arrays.fill(s, null);
        for (int i = 0; i < slots.length; i++) {
            s[i] = slots[i];
        }
        set(tab, "scroll", 0);
        set(tab, "results", List.of());
    }

    static void call(String method) throws ReflectiveOperationException {
        Method m = CraftingTab.class.getDeclaredMethod(method);
        m.setAccessible(true);
        m.invoke(CraftingTab.INSTANCE);
    }

    /** Opens a slot's ingredient list. */
    static void openSlot(int slot) throws ReflectiveOperationException {
        Class<?> drop = Class.forName("io.github.anilken.wynnlabs.CraftingTab$Drop");
        Object slotDrop = Arrays.stream(drop.getEnumConstants()).filter(c -> c.toString().equals("SLOT")).findFirst().orElseThrow();
        Method toggle = CraftingTab.class.getDeclaredMethod("toggle", drop, int.class, int[].class);
        toggle.setAccessible(true);
        // the anchor is where the slot was drawn last frame: the act list holds it
        toggle.invoke(CraftingTab.INSTANCE, slotDrop, slot, gridCell(slot));
    }

    /** The grid cell of a slot from the last frame's click areas: the cells are the 2×3 block of equal areas. */
    private static int[] gridCell(int slot) throws ReflectiveOperationException {
        List<int[]> rects = rects("acts");
        List<int[]> cells = new ArrayList<>();
        for (int[] r : rects) {
            if (r[3] == 26) {
                cells.add(r);
            }
        }
        return cells.get(slot);
    }

    /** {x, y, w, h} of each click area in a list field ("acts" or "overlay"). */
    static List<int[]> rects(String field) throws ReflectiveOperationException {
        List<?> acts = (List<?>) get(CraftingTab.INSTANCE, field);
        List<int[]> out = new ArrayList<>();
        for (Object a : acts) {
            int[] r = new int[4];
            String[] names = {"x", "y", "w", "h"};
            for (int i = 0; i < 4; i++) {
                Method m = a.getClass().getDeclaredMethod(names[i]);
                m.setAccessible(true);
                r[i] = (Integer) m.invoke(a);
            }
            out.add(r);
        }
        return out;
    }

    /** Puts the cursor on the middle of a GUI rectangle (the window is GUI scale × larger). */
    static void hover(ClientGameTestContext ctx, int[] r) {
        double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
        ctx.getInput().setCursorPos((r[0] + r[2] / 2.0) * scale, (r[1] + r[3] / 2.0) * scale);
    }

    static void set(Object o, String field, Object value) throws ReflectiveOperationException {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(o, value);
    }

    static Object get(Object o, String field) throws ReflectiveOperationException {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(o);
    }
}
