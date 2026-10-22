package io.github.anilken.wynnlabs.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Old bank pages drawn with today's pictures. Wynncraft picks an item's picture by the floats of
 * its {@code custom_model_data}, out of its server pack; when the pack is updated the numbers can
 * point at other pictures, so a page saved before draws something else (a box as a scroll). A
 * copy of the same thing saved later knows today's numbers, and for gear so does Wynntils' gear
 * data: only the picture is taken from them, the name, lore and verdicts stay the saved item's.
 * Plain JSON and no game; {@link Vault#stackOf} works it out once per item and vault change.
 */
final class BankIcons {

    private static final String COMPONENTS = "components";
    private static final String MODEL_DATA = "minecraft:custom_model_data";
    private static final String ITEM_MODEL = "minecraft:item_model";

    /**
     * An item's picture: its base item, model floats and item model (null when it has none),
     * when it was seen; {@code mixed}: copies seen with the same pack disagree, so none is taken
     * (two things share the name).
     */
    record Pic(String id, JsonArray floats, JsonElement model, long seen, boolean mixed) {

        boolean sameAs(Pic o) {
            return floats.equals(o.floats) && Objects.equals(model, o.model);
        }
    }

    private BankIcons() {}

    /**
     * What an item is for its picture, the closest first: a box by its type and tier, then by its
     * tier alone (every type of a tier has the same box), gear and the rest by name.
     */
    static List<String> identities(Vault.Stored s) {
        if (s.box() != null) {
            String[] p = s.box().split("\\|");
            if (p.length >= 2) {
                return List.of("box:" + p[0] + "|" + p[1], "boxtier:" + p[1]);
            }
        }
        return List.of((s.gear() ? "gear:" : "item:") + s.name());
    }

    /**
     * Copies seen this close together were drawn with one pack (a new pack comes with a new
     * login): when they differ, they are different things that share a name, not an old picture.
     */
    static final long SAME_PACK = 3_600_000L;

    /** The newest picture of each identity among these items (those saved with their item). */
    static Map<String, Pic> newest(Iterable<Vault.Stored> items) {
        Map<String, List<Pic>> by = new HashMap<>();
        for (Vault.Stored s : items) {
            Pic p = picOf(s);
            if (p == null) {
                continue;
            }
            for (String id : identities(s)) {
                by.computeIfAbsent(id, k -> new ArrayList<>()).add(p);
            }
        }
        Map<String, Pic> out = new HashMap<>();
        by.forEach((id, l) -> {
            Pic best = l.get(0);
            for (Pic p : l) {
                if (p.seen() > best.seen()) {
                    best = p;
                }
            }
            Pic b = best;
            boolean mixed = l.stream().anyMatch(p -> b.seen() - p.seen() < SAME_PACK
                    && !(p.id().equals(b.id()) && p.sameAs(b)));
            out.put(id, mixed ? new Pic(b.id(), b.floats(), b.model(), b.seen(), true) : b);
        });
        return out;
    }

    /**
     * The item JSON to draw for {@code s}: its own with today's picture, or {@code s.item()} itself
     * when nothing newer is known. Gear takes Wynntils' picture ({@code gearPicture}: name to its
     * base item and model float, null when unknown) before a later copy's, so every copy of a
     * piece agrees even when the newest saved one is old too.
     */
    static String repaint(Vault.Stored s, Map<String, Pic> newest, Function<String, Pic> gearPicture) {
        Pic own = picOf(s);
        if (own == null) {
            return s.item();
        }
        Pic to = null;
        if (s.gear()) {
            Pic w = gearPicture.apply(s.name());
            if (w != null && w.id().equals(own.id()) && !w.floats().isEmpty()) {
                // Wynntils knows the model number only: the rest of the floats stay ours
                JsonArray f = own.floats().deepCopy();
                f.set(0, w.floats().get(0));
                to = new Pic(own.id(), f, own.model(), own.seen(), false);
            }
        }
        if (to == null) {
            for (String id : identities(s)) {
                Pic n = newest.get(id);
                if (n != null && !n.mixed() && n.seen() - own.seen() >= SAME_PACK && n.id().equals(own.id())) {
                    to = n;
                    break;
                }
            }
        }
        if (to == null || to.sameAs(own)) {
            return s.item();
        }
        JsonObject item = JsonParser.parseString(s.item()).getAsJsonObject();
        JsonObject comps = item.getAsJsonObject(COMPONENTS);
        JsonObject cmd = comps.getAsJsonObject(MODEL_DATA);
        cmd.add("floats", to.floats().deepCopy());
        if (to.model() != null) {
            comps.add(ITEM_MODEL, to.model().deepCopy());
        } else {
            comps.remove(ITEM_MODEL);
        }
        return item.toString();
    }

    /** The picture of a saved item, or null when it has none to change (no item, no model floats). */
    static Pic picOf(Vault.Stored s) {
        if (s.item() == null) {
            return null;
        }
        try {
            JsonObject item = JsonParser.parseString(s.item()).getAsJsonObject();
            JsonObject comps = item.has(COMPONENTS) ? item.getAsJsonObject(COMPONENTS) : null;
            if (comps == null || !item.has("id") || !comps.has(MODEL_DATA) || !comps.get(MODEL_DATA).isJsonObject()) {
                return null;
            }
            JsonObject cmd = comps.getAsJsonObject(MODEL_DATA);
            if (!cmd.has("floats") || !cmd.get("floats").isJsonArray() || cmd.getAsJsonArray("floats").isEmpty()) {
                return null;
            }
            return new Pic(item.get("id").getAsString(), cmd.getAsJsonArray("floats"), comps.get(ITEM_MODEL), s.seen(), false);
        } catch (RuntimeException e) {
            return null;                        // an odd saved item: drawn as it was
        }
    }
}
