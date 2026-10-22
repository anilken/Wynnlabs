package io.github.anilken.wynnlabs.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The approved results next to a fixture ({@code <fixture>/expected/}) and how a new result
 * differs from them, as lines a person can read: per item, per JSON field.
 */
public final class Expected {

    /** How two results differ, one line per difference; empty when they agree. */
    public interface Differ {
        List<String> diff(JsonElement expected, JsonElement now);
    }

    private Expected() {}

    /**
     * Compares {@code now} with {@code <fixture>/expected/<file>}. With -PupdateFixtures the
     * differences are printed and the file is rewritten instead, and nothing is returned.
     */
    public static List<String> check(Path fixture, String file, JsonElement now, Differ differ) throws Exception {
        Path f = fixture.resolve("expected").resolve(file);
        String label = fixture.getFileName() + "/expected/" + file;
        JsonElement old = Files.exists(f) ? JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)) : null;
        List<String> out = new ArrayList<>();
        if (old == null) {
            out.add(label + " is missing: run gradle test -PupdateFixtures to write it");
        } else {
            for (String d : differ.diff(old, now)) {
                out.add(label + ": " + d);
            }
        }
        if (!Fixtures.update()) {
            return out;
        }
        out.stream().limit(200).forEach(Fixtures::note);
        String text = Fixture.pretty(now);
        if (old == null || !text.equals(Files.readString(f, StandardCharsets.UTF_8))) {
            Files.createDirectories(f.getParent());
            Files.writeString(f, text, StandardCharsets.UTF_8);
            Fixtures.note("wrote " + label + " (" + out.size() + " differences)");
        }
        return List.of();
    }

    /** Fails with every line, or passes when there are none. */
    static void assertNone(List<String> problems, String what) {
        if (!problems.isEmpty()) {
            int shown = Math.min(problems.size(), 80);
            StringBuilder sb = new StringBuilder(what + ": " + problems.size() + " differences"
                    + " (an intended change? approve it with gradle test -PupdateFixtures)\n");
            problems.subList(0, shown).forEach(p -> sb.append("  ").append(p).append('\n'));
            if (shown < problems.size()) {
                sb.append("  … and ").append(problems.size() - shown).append(" more\n");
            }
            throw new AssertionError(sb.toString());
        }
    }

    // ---- verdicts ----

    /**
     * Verdict lists ({where, name, verdict, note}), item by item:
     * "bank char:char0002:… p. 3#6 Unidentified Dagger: SELL → IDENTIFY".
     */
    static List<String> verdicts(JsonElement expected, JsonElement now) {
        Map<String, JsonObject> was = byItem(expected.getAsJsonArray());
        Map<String, JsonObject> is = byItem(now.getAsJsonArray());
        List<String> out = new ArrayList<>();
        for (var e : was.entrySet()) {
            JsonObject n = is.get(e.getKey());
            if (n == null) {
                out.add(e.getKey() + ": gone");
                continue;
            }
            String v0 = text(e.getValue(), "verdict", "no verdict");
            String v1 = text(n, "verdict", "no verdict");
            String n0 = text(e.getValue(), "note", "");
            String n1 = text(n, "note", "");
            if (!v0.equals(v1)) {
                out.add(e.getKey() + ": " + v0 + " → " + v1 + (n0.equals(n1) ? "" : "\n      was: " + n0 + "\n      now: " + n1));
            } else if (!n0.equals(n1)) {
                out.add(e.getKey() + ": " + v1 + ", note changed\n      was: " + n0 + "\n      now: " + n1);
            }
        }
        for (String k : is.keySet()) {
            if (!was.containsKey(k)) {
                out.add(k + ": new, " + text(is.get(k), "verdict", "no verdict"));
            }
        }
        return out;
    }

    private static Map<String, JsonObject> byItem(JsonArray a) {
        Map<String, JsonObject> m = new LinkedHashMap<>();
        for (JsonElement e : a) {
            JsonObject o = e.getAsJsonObject();
            m.put(o.get("where").getAsString() + " " + o.get("name").getAsString(), o);
        }
        return m;
    }

    private static String text(JsonObject o, String key, String none) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? none : e.getAsString();
    }

    // ---- JSON ----

    /** Every field that differs, by path: "roadmap.steps[1].gear[0].gain: 12.3 → 11.8". */
    public static List<String> json(JsonElement expected, JsonElement now) {
        List<String> out = new ArrayList<>();
        walk("", expected, now, false, out);
        return out;
    }

    /** Only field names and types: "missing field inventory.items[3].key", "… : string → number". */
    static List<String> shape(JsonElement expected, JsonElement now) {
        List<String> out = new ArrayList<>();
        walk("", expected, now, true, out);
        return out;
    }

    private static String type(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return "null";
        }
        if (e.isJsonObject()) {
            return "object";
        }
        if (e.isJsonArray()) {
            return "array";
        }
        var p = e.getAsJsonPrimitive();
        return p.isBoolean() ? "boolean" : p.isNumber() ? "number" : "string";
    }

    private static void walk(String path, JsonElement a, JsonElement b, boolean shapeOnly, List<String> out) {
        String ta = type(a);
        String tb = type(b);
        if (!ta.equals(tb)) {
            // a value that is sometimes null (no price yet) is a value change, not a shape change
            if (!shapeOnly || !ta.equals("null") && !tb.equals("null")) {
                out.add((shapeOnly ? "type of " : "") + where(path) + ": " + (shapeOnly ? ta + " → " + tb : show(a) + " → " + show(b)));
            }
            return;
        }
        switch (ta) {
            case "object" -> {
                JsonObject oa = a.getAsJsonObject();
                JsonObject ob = b.getAsJsonObject();
                Set<String> keys = new LinkedHashSet<>(oa.keySet());
                keys.addAll(ob.keySet());
                for (String k : keys) {
                    String p = path.isEmpty() ? k : path + "." + k;
                    if (!ob.has(k)) {
                        out.add("missing field " + p);
                    } else if (!oa.has(k)) {
                        out.add("new field " + p);
                    } else {
                        walk(p, oa.get(k), ob.get(k), shapeOnly, out);
                    }
                }
            }
            case "array" -> {
                JsonArray xa = a.getAsJsonArray();
                JsonArray xb = b.getAsJsonArray();
                if (xa.size() != xb.size() && !shapeOnly) {
                    out.add(where(path) + ": " + xa.size() + " entries → " + xb.size());
                }
                for (int i = 0; i < Math.min(xa.size(), xb.size()); i++) {
                    walk(path + "[" + i + "]", xa.get(i), xb.get(i), shapeOnly, out);
                }
            }
            case "null" -> {
            }
            default -> {
                if (!shapeOnly && !Objects.equals(a.getAsJsonPrimitive(), b.getAsJsonPrimitive())) {
                    out.add(where(path) + ": " + show(a) + " → " + show(b));
                }
            }
        }
    }

    private static String where(String path) {
        return path.isEmpty() ? "(top)" : path;
    }

    private static String show(JsonElement e) {
        String s = e == null ? "null" : e.toString();
        return s.length() > 160 ? s.substring(0, 157) + "…" : s;
    }
}
