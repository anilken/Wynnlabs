package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes personal values out of a recorded fixture before it is written. Character ids become
 * char0001, char0002 … in the order they first turn up (a full UUID keeps that id with the rest
 * zeroed); character, pet and player names become Character, Pet and Player.
 *
 * <p>Works on the JSON tree, keys and string values alike, never on the written text, so an id
 * inside a bank page key ("char:&lt;id&gt;:…") gets the same fake id as everywhere else. One
 * instance cleans every file of a fixture, so the mapping holds across them.
 */
final class Sanitiser {

    private static final Pattern UUID = Pattern.compile(
            "\\b([0-9a-fA-F]{8})-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");

    /** Lower-case real id (8 characters) → fake id. */
    private final Map<String, String> ids = new LinkedHashMap<>();
    private final List<String> known = new ArrayList<>();
    /** Real name → fake name, longest first, so a longer name is replaced before a shorter one inside it. */
    private final Map<String, String> names = new LinkedHashMap<>();
    private int replacedNames;

    /**
     * {@code characterIds}: every id the mod knows (folder names, the character list, bank
     * keys), 8 characters or a full UUID; {@code names}: real name → the fake one.
     */
    Sanitiser(List<String> characterIds, Map<String, String> names) {
        for (String id : characterIds) {
            String base = base(id);
            if (base != null && !known.contains(base)) {
                known.add(base);
            }
        }
        names.entrySet().stream()
                .filter(e -> e.getKey() != null && !e.getKey().isBlank())
                .sorted((a, b) -> b.getKey().length() - a.getKey().length())
                .forEach(e -> this.names.put(e.getKey(), e.getValue()));
    }

    /** The 8-character id of an id or UUID, lower case; null for anything else. */
    private static String base(String id) {
        if (id == null) {
            return null;
        }
        Matcher m = UUID.matcher(id);
        if (m.matches()) {
            return m.group(1).toLowerCase(Locale.ROOT);
        }
        return id.length() >= 4 ? id.toLowerCase(Locale.ROOT) : null;
    }

    /** How many different character ids were replaced so far. */
    int idCount() {
        return ids.size();
    }

    /** How many names were replaced so far. */
    int nameCount() {
        return replacedNames;
    }

    /** A cleaned copy of the tree. */
    JsonElement clean(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return e;
        }
        if (e.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (var en : e.getAsJsonObject().entrySet()) {
                out.add(text(en.getKey()), clean(en.getValue()));
            }
            return out;
        }
        if (e.isJsonArray()) {
            JsonArray out = new JsonArray();
            e.getAsJsonArray().forEach(x -> out.add(clean(x)));
            return out;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        return p.isString() ? new JsonPrimitive(text(p.getAsString())) : p;
    }

    /** One string with every id and name replaced. */
    String text(String s) {
        // full UUIDs first: char0001-0000-0000-0000-000000000000
        Matcher m = UUID.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(fake(m.group(1)) + "-0000-0000-0000-000000000000"));
        }
        m.appendTail(sb);
        String out = sb.toString();
        for (String id : known) {
            out = replace(out, id, false, () -> fake(id));
        }
        for (var n : names.entrySet()) {
            String before = out;
            out = replace(out, n.getKey(), true, n::getValue);
            if (!out.equals(before)) {
                replacedNames++;
            }
        }
        return out;
    }

    private String fake(String id) {
        return ids.computeIfAbsent(id.toLowerCase(Locale.ROOT), k -> String.format(Locale.ROOT, "char%04d", ids.size() + 1));
    }

    /**
     * Every occurrence of {@code what}, ignoring case. A name ({@code word}) only as a whole
     * word, so a short name does not eat into other words.
     */
    private static String replace(String s, String what, boolean word, java.util.function.Supplier<String> with) {
        String low = s.toLowerCase(Locale.ROOT);
        String key = what.toLowerCase(Locale.ROOT);
        int at = low.indexOf(key);
        if (at < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder();
        int from = 0;
        while (at >= 0) {
            int end = at + key.length();
            boolean whole = !word || (at == 0 || !Character.isLetterOrDigit(s.charAt(at - 1)))
                    && (end >= s.length() || !Character.isLetterOrDigit(s.charAt(end)));
            if (whole) {
                out.append(s, from, at).append(with.get());
                from = end;
            }
            at = low.indexOf(key, whole ? end : at + 1);
        }
        return out.append(s.substring(from)).toString();
    }
}
