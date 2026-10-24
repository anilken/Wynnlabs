package io.github.anilken.wynnlabs.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WynnBuilder's build links ("wynnbuilder.github.io/builder/#..."), read by the mod's own code
 * from the published format alone. Only what the build-around list needs is read: the nine
 * pieces with their powders, the tomes, the skill points set by hand and the level; the aspects
 * and the ability tree after them are left alone.
 *
 * <p>The current form is a bit string, lowest bit first, six bits per letter (0-9, A-Z, a-z, +,
 * -): 12 in six bits, the data version in ten, then per piece a 2-bit kind (an item by id + 1, 0
 * for none; a crafted or a custom item, skipped) and, for armour and the weapon, a powder flag and
 * the powders; a tome flag and one id per tome slot; a skill point flag and each skill's points;
 * a level flag and the level. The widths of the ids change with the data version. The older form
 * is "N_" and fixed-width letters, high letter first: 3 per item, 2 per skill, 2 for the level,
 * blocks of powders, then the tomes. The ids are WynnBuilder's, shipped in
 * {@code wynnbuilder-items.json}; nothing here acts in the game.
 */
public final class WynnBuilderLink {

    private static final String DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz+-";
    /** The first letter of the current form; the older forms start with their version, 0 to 11. */
    private static final int BINARY = 12;
    private static final String ELEMENTS = "ETWFA";
    /** The pieces in a link's order: four armour, two rings, bracelet, necklace, weapon. */
    public static final int PIECES = 9;
    public static final int WEAPON = 8;

    public enum Kind { ITEM, CRAFTED, CUSTOM }

    /**
     * One piece of the link: its place (0 helmet … 8 weapon), its kind, the item's name as the game
     * writes it and WynnBuilder's own name when that differs (else null; both null for an id the
     * table does not have), and its powders ("T6T6E6", empty for none).
     */
    public record Piece(int slot, Kind kind, int id, String name, String alias, String powders) {}

    /**
     * A decoded link: its WynnBuilder data version ("2.2.5.0"; {@code newer} when the mod's table
     * is older than the link, read with the newest widths it knows), the pieces it fills, the
     * tomes' names, the skill points set by hand (Strength … Agility, null when WynnBuilder works
     * them out) and the level.
     */
    public record Build(String version, boolean newer, List<Piece> pieces, List<String> tomes, List<Integer> skills, int level) {}

    /** The widths of one data version's fields. */
    record Widths(String name, int items, int tomes, int tomeSlots, int slots, int powderTiers, int powderBits, int wrapBits,
                  int maxLevel, int levelBits, int spBits, int spTypes) {}

    record Table(List<Widths> versions, String[] items, Map<Integer, String> itemAlias, Map<Integer, Integer> itemMoved,
                 String[] tomes, Map<Integer, Integer> tomeMoved) {}

    private static volatile Table table;

    private WynnBuilderLink() {}

    /** The build in a pasted link or its hash; null when the text is not a WynnBuilder build link. */
    public static Build decode(String text) {
        if (text == null) {
            return null;
        }
        String s = text.strip();
        int hash = s.indexOf('#');
        String query = hash < 0 ? "" : s.substring(0, hash);
        String tag = hash < 0 ? s : s.substring(hash + 1);
        if (tag.isEmpty()) {
            return null;
        }
        try {
            int under = tag.indexOf('_');
            if (under > 0 && under <= 2) {
                return legacy(tag.substring(0, under), tag.substring(under + 1), query);
            }
            if (tag.chars().anyMatch(c -> DIGITS.indexOf(c) < 0) || DIGITS.indexOf(tag.charAt(0)) != BINARY) {
                return null;
            }
            return binary(tag);
        } catch (RuntimeException e) {
            return null;                                // cut short or not a build: not a link
        }
    }

    // ---- the current form ----

    private static Build binary(String tag) {
        Bits b = new Bits(tag);
        b.take(6);
        int v = b.take(10);
        Table t = table();
        boolean newer = v >= t.versions().size();
        Widths w = t.versions().get(newer ? t.versions().size() - 1 : v);
        List<Piece> pieces = new ArrayList<>();
        for (int i = 0; i < w.slots(); i++) {
            int kind = b.take(2);
            Piece p = null;
            if (kind == 0) {
                int id = b.take(w.items());
                if (id > 0) {
                    p = item(i, t.itemMoved().getOrDefault(id - 1, id - 1), "");
                }
            } else if (kind == 1) {
                skipCraft(b, i == WEAPON);
                p = new Piece(i, Kind.CRAFTED, -1, null, null, "");
            } else if (kind == 2) {
                b.skip(b.take(12) * 6);
                p = new Piece(i, Kind.CUSTOM, -1, null, null, "");
            } else {
                return null;
            }
            String powders = "";
            if ((i < 4 || i == WEAPON) && b.take(1) == 1) {
                powders = powders(b, w);
            }
            if (p != null) {
                pieces.add(new Piece(p.slot(), p.kind(), p.id(), p.name(), p.alias(), powders));
            }
        }
        List<String> tomes = new ArrayList<>();
        if (b.take(1) == 1) {
            for (int i = 0; i < w.tomeSlots(); i++) {
                if (b.take(1) == 1) {
                    tome(tomes, t, b.take(w.tomes()));
                }
            }
        }
        List<Integer> skills = null;
        if (b.take(1) == 0) {                           // set by hand
            Integer[] sp = new Integer[5];
            for (int i = 0; i < w.spTypes(); i++) {
                if (b.take(1) == 1) {
                    int raw = b.take(w.spBits());
                    int value = raw << (32 - w.spBits()) >> (32 - w.spBits());
                    if (i < sp.length) {
                        sp[i] = value;
                    }
                }
            }
            skills = Collections.unmodifiableList(Arrays.asList(sp));
        }
        int level = b.take(1) == 0 ? w.maxLevel() : b.take(w.levelBits());
        return new Build(w.name(), newer, List.copyOf(pieces), List.copyOf(tomes), skills, level);
    }

    /** A crafted piece's bits: a 0 bit, 7-bit version, six ingredients and the recipe (12 each), two tiers, a weapon's speed, padding. */
    private static void skipCraft(Bits b, boolean weapon) {
        int start = b.at;
        if (b.take(1) != 0) {
            throw new IllegalArgumentException("old crafted form inside a link");
        }
        b.skip(7 + 6 * 12 + 12 + 2 * 3 + (weapon ? 4 : 0));
        b.skip(6 - (b.at - start) % 6);
    }

    /**
     * One piece's powders: an id (element × tiers + tier − 1), then per further powder "the same
     * again" (0), "the same tier, the element moved on by wrap + 1" (1, 0, wrap) or "a new id" /
     * "done" (1, 1, then 0 + id or 1).
     */
    private static String powders(Bits b, Widths w) {
        StringBuilder out = new StringBuilder();
        int last = b.take(w.powderBits());
        out.append(powder(last, w.powderTiers()));
        while (true) {
            if (b.take(1) == 0) {
                out.append(powder(last, w.powderTiers()));
            } else if (b.take(1) == 0) {
                int element = (last / w.powderTiers() + b.take(w.wrapBits()) + 1) % ELEMENTS.length();
                last = element * w.powderTiers() + last % w.powderTiers();
                out.append(powder(last, w.powderTiers()));
            } else if (b.take(1) == 0) {
                last = b.take(w.powderBits());
                out.append(powder(last, w.powderTiers()));
            } else {
                return out.toString();
            }
        }
    }

    private static String powder(int id, int tiers) {
        int element = id / tiers;
        if (element >= ELEMENTS.length()) {
            throw new IllegalArgumentException("no such powder");
        }
        return ELEMENTS.charAt(element) + String.valueOf(id % tiers + 1);
    }

    // ---- the older form ----

    private static Build legacy(String version, String data, String query) {
        int v = Integer.parseInt(version);
        if (v < 0 || v > 11) {
            return null;
        }
        Table t = table();
        List<Piece> pieces = new ArrayList<>();
        int at = 0;
        for (int i = 0; i < PIECES; i++) {
            if (v == 4 && data.charAt(at) == '-') {
                pieces.add(new Piece(i, Kind.CRAFTED, -1, null, null, ""));
                at += 18;
            } else if (v > 4 && data.startsWith("CR-", at)) {
                pieces.add(new Piece(i, Kind.CRAFTED, -1, null, null, ""));
                at += 20;
            } else if (v > 4 && data.startsWith("CI-", at + 3)) {
                pieces.add(new Piece(i, Kind.CUSTOM, -1, null, null, ""));
                at += 3 + number(data, at, 3);
            } else {
                int id = number(data, at, 3);
                if (id < 10000) {                       // 10000 and up: the site's empty slots
                    pieces.add(item(i, id, ""));
                }
                at += 3;
            }
        }
        List<Integer> skills = null;
        int level = 106;
        if (v >= 2) {
            Integer[] sp = new Integer[5];
            for (int i = 0; i < 5; i++) {
                int raw = number(data, at + 2 * i, 2);
                sp[i] = raw >= 2048 ? raw - 4096 : raw;
            }
            skills = Collections.unmodifiableList(Arrays.asList(sp));
            at += 10;
        }
        if (v >= 3) {
            level = number(data, at, 2);
            at += 2;
        }
        if (v >= 1) {
            // five blocks of powders, for the armour and the weapon: a count, then up to six 5-bit powders per 5 letters
            int[] powderable = {0, 1, 2, 3, WEAPON};
            for (int slot : powderable) {
                int blocks = number(data, at, 1);
                at += 1;
                StringBuilder ps = new StringBuilder();
                for (int j = 0; j < blocks; j++) {
                    int six = number(data, at, 5);
                    at += 5;
                    for (int k = 0; k < 6 && six != 0; k++) {
                        ps.append(powder((six & 0x1f) - 1, 6));
                        six >>>= 5;
                    }
                }
                for (int j = 0; j < pieces.size(); j++) {
                    Piece p = pieces.get(j);
                    if (p.slot() == slot) {
                        pieces.set(j, new Piece(p.slot(), p.kind(), p.id(), p.name(), p.alias(), ps.toString()));
                    }
                }
            }
        }
        List<String> tomes = new ArrayList<>();
        if (v >= 6) {
            int count = v < 9 ? 7 : v == 9 ? 8 : 14;
            int width = v < 8 ? 1 : 2;
            for (int i = 0; i < count && at + width <= data.length(); i++) {
                tome(tomes, t, number(data, at, width));
                at += width;
            }
        }
        return new Build(legacyVersion(v, query, t), false, List.copyOf(pieces), List.copyOf(tomes), skills, level);
    }

    /** The data version an older link names: "?v=N" from form 8 on (else the last the older form knew), the first one before. */
    private static String legacyVersion(int form, String query, Table t) {
        int last = Math.min(18, t.versions().size() - 1);
        int v = form >= 8 ? last : 0;
        int q = query.indexOf("v=");
        if (form >= 8 && q >= 0) {
            int end = q + 2;
            while (end < query.length() && Character.isDigit(query.charAt(end))) {
                end++;
            }
            try {
                v = Math.max(0, Math.min(last, Integer.parseInt(query.substring(q + 2, end))));
            } catch (NumberFormatException e) {
                v = last;
            }
        }
        return t.versions().get(v).name();
    }

    // ---- the names ----

    private static Piece item(int slot, int id, String powders) {
        Table t = table();
        String name = id >= 0 && id < t.items().length ? t.items()[id] : null;
        return new Piece(slot, Kind.ITEM, id, name, name == null ? null : t.itemAlias().get(id), powders);
    }

    private static void tome(List<String> out, Table t, int id) {
        int real = t.tomeMoved().getOrDefault(id, id);
        String name = real >= 0 && real < t.tomes().length ? t.tomes()[real] : null;
        if (name != null) {
            out.add(name);
        }
    }

    /** A number written high letter first (the older form). */
    private static int number(String s, int at, int n) {
        if (at + n > s.length()) {
            throw new IllegalArgumentException("link too short");
        }
        int v = 0;
        for (int i = at; i < at + n; i++) {
            int d = DIGITS.indexOf(s.charAt(i));
            if (d < 0) {
                throw new IllegalArgumentException("not a link letter");
            }
            v = v * 64 + d;
        }
        return v;
    }

    /** A bit string read lowest bit first, six bits per letter. */
    private static final class Bits {
        private final boolean[] bits;
        int at;

        Bits(String text) {
            bits = new boolean[text.length() * 6];
            for (int i = 0; i < text.length(); i++) {
                int v = DIGITS.indexOf(text.charAt(i));
                for (int j = 0; j < 6; j++) {
                    bits[i * 6 + j] = (v >> j & 1) == 1;
                }
            }
        }

        int take(int n) {
            if (at + n > bits.length) {
                throw new IllegalArgumentException("link too short");
            }
            int v = 0;
            for (int i = 0; i < n; i++) {
                if (bits[at + i]) {
                    v |= 1 << i;
                }
            }
            at += n;
            return v;
        }

        void skip(int n) {
            if (at + n > bits.length) {
                throw new IllegalArgumentException("link too short");
            }
            at += n;
        }
    }

    // ---- the table ----

    static Table table() {
        Table t = table;
        if (t == null) {
            t = load();
            table = t;
        }
        return t;
    }

    private static Table load() {
        List<Widths> versions = new ArrayList<>();
        String[] items = new String[0];
        String[] tomes = new String[0];
        Map<Integer, String> alias = new HashMap<>();
        Map<Integer, Integer> moved = new HashMap<>();
        Map<Integer, Integer> tomeMoved = new HashMap<>();
        try (InputStream in = WynnBuilderLink.class.getResourceAsStream("/assets/wynnlabs/build/wynnbuilder-items.json")) {
            if (in != null) {
                JsonObject doc = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (JsonElement e : doc.getAsJsonArray("versions")) {
                    JsonObject o = e.getAsJsonObject();
                    versions.add(new Widths(o.get("name").getAsString(), o.get("items").getAsInt(), o.get("tomes").getAsInt(),
                            o.get("tomeSlots").getAsInt(), o.get("slots").getAsInt(), o.get("powderTiers").getAsInt(),
                            o.get("powderBits").getAsInt(), o.get("wrapBits").getAsInt(), o.get("maxLevel").getAsInt(),
                            o.get("levelBits").getAsInt(), o.get("spBits").getAsInt(), o.get("spTypes").getAsInt()));
                }
                items = names(doc.getAsJsonArray("items"));
                tomes = names(doc.getAsJsonArray("tomes"));
                doc.getAsJsonObject("itemAliases").entrySet().forEach(e -> alias.put(Integer.parseInt(e.getKey()), e.getValue().getAsString()));
                doc.getAsJsonObject("itemMoved").entrySet().forEach(e -> moved.put(Integer.parseInt(e.getKey()), e.getValue().getAsInt()));
                doc.getAsJsonObject("tomeMoved").entrySet().forEach(e -> tomeMoved.put(Integer.parseInt(e.getKey()), e.getValue().getAsInt()));
            }
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not read WynnBuilder's item ids: " + e);
        }
        if (versions.isEmpty()) {
            throw new IllegalStateException("no WynnBuilder versions");
        }
        return new Table(List.copyOf(versions), items, Map.copyOf(alias), Map.copyOf(moved), tomes, Map.copyOf(tomeMoved));
    }

    private static String[] names(JsonArray a) {
        String[] out = new String[a.size()];
        for (int i = 0; i < a.size(); i++) {
            out[i] = a.get(i).isJsonNull() ? null : a.get(i).getAsString();
        }
        return out;
    }
}
