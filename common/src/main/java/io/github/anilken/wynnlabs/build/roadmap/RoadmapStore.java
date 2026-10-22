package io.github.anilken.wynnlabs.build.roadmap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.build.Locks;
import io.github.anilken.wynnlabs.core.Dirs;
import io.github.anilken.wynnlabs.core.Saver;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.model.ItemData;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The last road map built for a character, kept in
 * {@code config/wynnlabs/characters/<id>/roadmap.json} so the next session shows it at once
 * (marked out of date when its inputs changed): the milestones with their picks, comparisons,
 * reasons and prices as they were, the inputs key it was built for, the level and when. Pieces
 * are stored by name and read back from the item database (an owned piece comes back with the
 * database's average rolls). A file from another format or one that cannot be read counts as
 * none and is kept aside as {@code .broken}.
 */
final class RoadmapStore {

    /** The file's format; another one is read as none. */
    static final int FORMAT = 1;
    private static final String FILE = "roadmap.json";

    /** A road map read back: its milestones, the inputs key, the level and when it was built. */
    record Loaded(List<Roadmap.Step> steps, String key, int level, long builtAt) {}

    private RoadmapStore() {}

    static Path file(String id) {
        Path root = Locks.rootOverride != null ? Locks.rootOverride : Dirs.config().resolve("characters");
        return root.resolve(id).resolve(FILE);
    }

    // ---- writing ----

    /** Saves this road map for the character (in the background, Saver). */
    static void save(String id, List<Roadmap.Step> steps, String key, int level, long builtAt) {
        if (id == null || id.isEmpty()) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT);
        o.addProperty("key", key);
        o.addProperty("level", level);
        o.addProperty("builtAt", builtAt);
        JsonArray a = new JsonArray();
        steps.forEach(s -> a.add(step(s)));
        o.add("steps", a);
        String text = o.toString();
        try {
            Saver.mark(file(id), () -> text);
        } catch (Throwable t) {
            // no config folder (tests without the game): kept in memory only
        }
    }

    private static JsonObject step(Roadmap.Step s) {
        JsonObject o = new JsonObject();
        o.addProperty("level", s.level());
        JsonArray gear = new JsonArray();
        s.gear().forEach(g -> gear.add(gear(g)));
        o.add("gear", gear);
        o.add("abilities", strings(s.abilities()));
        o.add("places", strings(s.places()));
        o.addProperty("skillPoints", s.skillPoints());
        JsonObject vs = new JsonObject();
        s.vsWorn().forEach(vs::addProperty);
        o.add("vsWorn", vs);
        JsonObject why = new JsonObject();
        s.whyVsWorn().forEach((k, v) -> why.add(k, strings(v)));
        o.add("whyVsWorn", why);
        JsonObject build = new JsonObject();
        s.build().forEach((slot, p) -> build.addProperty(slot, p == null ? null : p.name()));
        o.add("build", build);
        if (s.set() != null) {
            o.add("set", eval(s.set()));
        }
        return o;
    }

    private static JsonObject gear(Roadmap.Gear g) {
        JsonObject o = new JsonObject();
        o.addProperty("slot", g.slot());
        o.addProperty("name", g.info().name());
        o.addProperty("gain", g.gain());
        o.addProperty("source", g.source());
        o.addProperty("price", g.price());
        o.addProperty("alt", g.alt() == null ? null : g.alt().name());
        o.addProperty("altSource", g.altSource());
        o.add("detail", strings(g.detail()));
        o.add("alternatives", strings(g.alternatives()));
        JsonArray opts = new JsonArray();
        if (g.options() != null) {
            for (Roadmap.Option op : g.options()) {
                JsonObject j = new JsonObject();
                j.addProperty("name", op.info().name());
                j.addProperty("gain", op.gain());
                j.addProperty("price", op.price());
                j.addProperty("source", op.source());
                j.add("cautions", strings(op.cautions()));
                j.addProperty("skills", op.skills());
                j.addProperty("respec", op.respec());
                j.add("factorPct", doubles(op.factorPct()));
                j.add("from", result(op.from()));
                j.add("to", result(op.to()));
                opts.add(j);
            }
        }
        o.add("options", opts);
        return o;
    }

    private static JsonObject eval(Build.Eval e) {
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray();
        for (int v : e.allocation()) {
            a.add(v);
        }
        o.add("allocation", a);
        o.add("result", result(e.result()));
        return o;
    }

    private static JsonElement result(Damage.Result r) {
        if (r == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject o = new JsonObject();
        o.addProperty("objective", r.objective());
        o.addProperty("spellDps", r.spellDps());
        o.addProperty("mainDps", r.mainDps());
        o.addProperty("casts", r.casts());
        o.addProperty("ehp", r.ehp());
        o.addProperty("xp", r.xp());
        o.addProperty("speed", r.speed());
        o.addProperty("dmgPerCast", r.dmgPerCast());
        o.add("factors", doubles(r.factors()));
        o.addProperty("ehpElemental", r.ehpElemental());
        o.addProperty("health", r.health());
        return o;
    }

    private static JsonArray strings(List<String> l) {
        JsonArray a = new JsonArray();
        if (l != null) {
            l.forEach(a::add);
        }
        return a;
    }

    private static JsonArray doubles(double[] d) {
        JsonArray a = new JsonArray();
        if (d != null) {
            for (double v : d) {
                a.add(Double.isFinite(v) ? v : 0);
            }
        }
        return a;
    }

    // ---- reading ----

    /** The road map saved for this character, or null when there is none, it is of another format, or broken (kept aside). */
    static Loaded load(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        Path f;
        try {
            f = file(id);
        } catch (Throwable t) {
            return null;                                // no config folder (tests without the game)
        }
        try {
            Saver.settle(f);
            if (!Files.exists(f)) {
                return null;
            }
            String text = Files.readString(f, StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            if (!o.has("format") || o.get("format").getAsInt() != FORMAT) {
                throw new IllegalStateException("format " + (o.has("format") ? o.get("format") : "none"));
            }
            Map<String, ItemData> byName = new HashMap<>();
            GameState.current().allGear().forEach(i -> byName.putIfAbsent(i.name(), i));
            List<Roadmap.Step> steps = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("steps")) {
                steps.add(step(e.getAsJsonObject(), byName));
            }
            Saver.known(f, text);
            return new Loaded(List.copyOf(steps), o.get("key").getAsString(), o.get("level").getAsInt(), o.get("builtAt").getAsLong());
        } catch (Throwable t) {
            System.out.println("[WynnLabs] could not read " + FILE + ", kept as " + FILE + ".broken: " + t);
            try {
                Files.move(f, f.resolveSibling(FILE + ".broken"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable ignored) {
                // nothing to keep
            }
            return null;
        }
    }

    private static Roadmap.Step step(JsonObject o, Map<String, ItemData> byName) {
        List<Roadmap.Gear> gear = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("gear")) {
            Roadmap.Gear g = gear(e.getAsJsonObject(), byName);
            if (g != null) {
                gear.add(g);
            }
        }
        Map<String, Double> vs = new LinkedHashMap<>();
        o.getAsJsonObject("vsWorn").entrySet().forEach(e -> vs.put(e.getKey(), e.getValue().getAsDouble()));
        Map<String, List<String>> why = new LinkedHashMap<>();
        o.getAsJsonObject("whyVsWorn").entrySet().forEach(e -> why.put(e.getKey(), strings(e.getValue())));
        Map<String, Damage.Piece> build = new LinkedHashMap<>();
        o.getAsJsonObject("build").entrySet().forEach(e -> {
            ItemData info = e.getValue().isJsonNull() ? null : byName.get(e.getValue().getAsString());
            build.put(e.getKey(), info == null ? null : Damage.piece(info));
        });
        Build.Eval set = o.has("set") ? eval(o.getAsJsonObject("set")) : null;
        return new Roadmap.Step(o.get("level").getAsInt(), List.copyOf(gear), strings(o.get("abilities")), strings(o.get("places")),
                o.get("skillPoints").getAsInt(), vs, why, java.util.Collections.unmodifiableMap(build), set);
    }

    /** A pick read back; null when the database no longer has the piece. */
    private static Roadmap.Gear gear(JsonObject o, Map<String, ItemData> byName) {
        ItemData info = byName.get(o.get("name").getAsString());
        if (info == null) {
            return null;
        }
        ItemData alt = o.get("alt").isJsonNull() ? null : byName.get(o.get("alt").getAsString());
        List<Roadmap.Option> opts = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("options")) {
            JsonObject j = e.getAsJsonObject();
            ItemData oi = byName.get(j.get("name").getAsString());
            if (oi == null) {
                continue;
            }
            opts.add(new Roadmap.Option(oi, j.get("gain").getAsDouble(), str(j.get("price")), str(j.get("source")),
                    strings(j.get("cautions")), str(j.get("skills")), j.get("respec").getAsBoolean(), doubles(j.get("factorPct")),
                    result(j.get("from")), result(j.get("to"))));
        }
        return new Roadmap.Gear(o.get("slot").getAsString(), info, o.get("gain").getAsDouble(), str(o.get("source")),
                str(o.get("price")), alt, str(o.get("altSource")), strings(o.get("detail")), strings(o.get("alternatives")), opts);
    }

    private static Build.Eval eval(JsonObject o) {
        JsonArray a = o.getAsJsonArray("allocation");
        int[] alloc = new int[a.size()];
        for (int i = 0; i < alloc.length; i++) {
            alloc[i] = a.get(i).getAsInt();
        }
        return new Build.Eval(alloc, result(o.get("result")));
    }

    private static Damage.Result result(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        JsonObject o = e.getAsJsonObject();
        return new Damage.Result(o.get("objective").getAsDouble(), o.get("spellDps").getAsDouble(), o.get("mainDps").getAsDouble(),
                o.get("casts").getAsDouble(), o.get("ehp").getAsDouble(), o.get("xp").getAsDouble(), o.get("speed").getAsDouble(),
                o.get("dmgPerCast").getAsDouble(), doubles(o.get("factors")), o.get("ehpElemental").getAsDouble(),
                o.get("health").getAsDouble());
    }

    private static String str(JsonElement e) {
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static List<String> strings(JsonElement e) {
        List<String> out = new ArrayList<>();
        if (e != null && e.isJsonArray()) {
            e.getAsJsonArray().forEach(x -> out.add(x.getAsString()));
        }
        return List.copyOf(out);
    }

    private static double[] doubles(JsonElement e) {
        if (e == null || !e.isJsonArray()) {
            return new double[0];
        }
        JsonArray a = e.getAsJsonArray();
        double[] d = new double[a.size()];
        for (int i = 0; i < d.length; i++) {
            d[i] = a.get(i).getAsDouble();
        }
        return d;
    }
}
