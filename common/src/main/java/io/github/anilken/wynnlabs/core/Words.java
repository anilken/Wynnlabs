package io.github.anilken.wynnlabs.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text kept as its language key and arguments, worded only when it is shown ({@link #text}):
 * advice saved to a file or kept in memory then follows a switch between English and Turkish.
 * An argument may itself be Words ("near %s" inside a tip). A null key is text kept as it is,
 * for old saved text that could not be matched back to a key.
 */
public record Words(String key, List<Object> args) {

    /** The language files the mod ships, the ones old saved text may be worded in. */
    private static final List<String> SHIPPED = List.of("en_us", "tr_tr");
    private static final Pattern ARG = Pattern.compile("%(?:(\\d+)\\$)?s|%%");

    public Words {
        List<Object> a = new ArrayList<>(args.size());
        for (Object o : args) {
            a.add(o == null ? "" : o);
        }
        args = List.copyOf(a);
    }

    public static Words of(String key, Object... args) {
        return new Words(key, Arrays.asList(args));
    }

    /** Text that stays as it is in every language (a place name, an old line no key matches). */
    public static Words literal(String text) {
        return new Words(null, List.of(text == null ? "" : text));
    }

    /** The text in the language the mod speaks now. */
    public String text() {
        if (key == null) {
            return args.isEmpty() ? "" : String.valueOf(args.get(0));
        }
        Object[] a = new Object[args.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = args.get(i) instanceof Words w ? w.text() : args.get(i);
        }
        return L.t(key, a);
    }

    /** {"key": …, "args": [...]} for a saved file; {"text": …} for literal text. */
    public JsonObject json() {
        JsonObject o = new JsonObject();
        if (key == null) {
            o.addProperty("text", text());
            return o;
        }
        o.addProperty("key", key);
        JsonArray a = new JsonArray();
        for (Object v : args) {
            if (v instanceof Words w) {
                a.add(w.json());
            } else if (v instanceof Number n) {
                a.add(n);
            } else if (v instanceof Boolean b) {
                a.add(b);
            } else {
                a.add(String.valueOf(v));
            }
        }
        o.add("args", a);
        return o;
    }

    /** What {@link #json} wrote (a plain string reads as literal text); null when it is neither. */
    public static Words fromJson(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e.isJsonPrimitive()) {
            return literal(e.getAsString());
        }
        if (!e.isJsonObject()) {
            return null;
        }
        JsonObject o = e.getAsJsonObject();
        if (!o.has("key")) {
            return o.has("text") ? literal(o.get("text").getAsString()) : null;
        }
        List<Object> args = new ArrayList<>();
        if (o.has("args") && o.get("args").isJsonArray()) {
            for (JsonElement v : o.getAsJsonArray("args")) {
                args.add(arg(v));
            }
        }
        return new Words(o.get("key").getAsString(), args);
    }

    private static Object arg(JsonElement v) {
        if (v.isJsonObject()) {
            Words w = fromJson(v);
            return w == null ? "" : w;
        }
        if (v.isJsonNull() || !v.isJsonPrimitive()) {
            return "";
        }
        var p = v.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return p.getAsBoolean();
        }
        if (p.isNumber()) {
            String s = p.getAsString();
            boolean whole = !s.isEmpty();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (!(c >= '0' && c <= '9' || c == '-' && i == 0)) {
                    whole = false;
                }
            }
            try {
                return whole ? (Object) Long.parseLong(s) : (Object) Double.parseDouble(s);
            } catch (NumberFormatException x) {
                return s;
            }
        }
        return p.getAsString();
    }

    /**
     * Text an older version saved already worded, back into its key and arguments: the first of
     * {@code keys} whose text in a shipped language it is. Null when none matches.
     */
    public static Words unword(String text, List<String> keys) {
        if (text == null) {
            return null;
        }
        for (String key : keys) {
            for (String code : SHIPPED) {
                String t = L.template(code, key);
                List<Object> args = t == null ? null : match(t, text);
                if (args != null) {
                    return new Words(key, args);
                }
            }
        }
        return null;
    }

    /** The arguments that fill {@code template} into {@code text}, by position; null when it does not fit. */
    private static List<Object> match(String template, String text) {
        StringBuilder re = new StringBuilder();
        List<Integer> slots = new ArrayList<>();
        Matcher m = ARG.matcher(template);
        int last = 0;
        int next = 0;
        while (m.find()) {
            re.append(Pattern.quote(template.substring(last, m.start())));
            if (m.group().equals("%%")) {
                re.append('%');
            } else {
                slots.add(m.group(1) != null ? Integer.parseInt(m.group(1)) - 1 : next++);
                re.append("(.*?)");
            }
            last = m.end();
        }
        re.append(Pattern.quote(template.substring(last)));
        Matcher t = Pattern.compile(re.toString(), Pattern.DOTALL).matcher(text);
        if (!t.matches()) {
            return null;
        }
        int n = 0;
        for (int s : slots) {
            n = Math.max(n, s + 1);
        }
        Object[] out = new Object[n];
        Arrays.fill(out, "");
        for (int k = 0; k < slots.size(); k++) {
            out[slots.get(k)] = t.group(k + 1);
        }
        return Arrays.asList(out);
    }
}
