package io.github.anilken.wynnlabs.crafting;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * A recipe as a short code to copy and paste: "wl1" (the version), the item type, the level
 * bracket, both material tiers with the attack speed, then each grid slot as a 6-letter mark
 * of the ingredient's name ("u" for an empty slot), and two check letters. Our own format; it
 * does not try to match any other crafter's.
 */
public final class RecipeCode {

    private static final String PREFIX = "wl1";
    private static final String DIGITS = "0123456789abcdefghjkmnpqrstvwxyz";
    private static final char EMPTY = 'u';
    private static final int MARK_LEN = 6;

    /** What a code holds; {@code slots} has six names, null where the slot is empty. */
    public record Recipe(String type, int minLevel, int tier1, int tier2, Craft.Speed speed, List<String> slots) {}

    private RecipeCode() {}

    public static String encode(Recipe r) {
        int type = Types.ALL.indexOf(r.type());
        int bracket = bracketIndex(r.type(), r.minLevel());
        if (type < 0 || bracket < 0) {
            throw new IllegalArgumentException("no such recipe: " + r.type() + " " + r.minLevel());
        }
        StringBuilder sb = new StringBuilder(PREFIX);
        sb.append(DIGITS.charAt(type));
        sb.append(DIGITS.charAt(bracket / 32)).append(DIGITS.charAt(bracket % 32));
        int tiers = (clamp(r.tier1()) - 1) * 3 + (clamp(r.tier2()) - 1);
        sb.append(DIGITS.charAt(tiers * 3 + r.speed().ordinal()));
        for (int i = 0; i < Craft.SLOTS; i++) {
            String name = i < r.slots().size() ? r.slots().get(i) : null;
            if (name == null || name.isEmpty()) {
                sb.append(EMPTY);
            } else {
                sb.append(digits(mark(name), MARK_LEN));
            }
        }
        return sb.append(digits(check(sb), 2)).toString();
    }

    /**
     * Reads a code back, matching each ingredient mark against {@code known} names. Returns
     * null when the text is not a code of this kind, is damaged, names a recipe that does not
     * exist, or holds an ingredient none of the known names gives.
     */
    public static Recipe decode(String text, Collection<String> known) {
        if (text == null) {
            return null;
        }
        String s = text.strip().toLowerCase(Locale.ROOT);
        if (!s.startsWith(PREFIX) || s.length() < PREFIX.length() + 4 + Craft.SLOTS + 2) {
            return null;
        }
        String body = s.substring(0, s.length() - 2);
        Long sum = number(s.substring(s.length() - 2));
        if (sum == null || sum != check(body)) {
            return null;
        }
        int at = PREFIX.length();
        int type = DIGITS.indexOf(s.charAt(at++));
        int b1 = DIGITS.indexOf(s.charAt(at++));
        int b2 = DIGITS.indexOf(s.charAt(at++));
        int ts = DIGITS.indexOf(s.charAt(at++));
        if (type < 0 || type >= Types.ALL.size() || b1 < 0 || b2 < 0 || ts < 0 || ts >= 27) {
            return null;
        }
        String typeName = Types.ALL.get(type);
        List<CraftData.Recipe> brackets = CraftData.ofType(typeName);
        int bracket = b1 * 32 + b2;
        if (bracket >= brackets.size()) {
            return null;
        }
        Map<Long, String> byMark = new HashMap<>();
        for (String name : known) {
            byMark.put(mark(name), name);
        }
        List<String> slots = new ArrayList<>(Craft.SLOTS);
        while (slots.size() < Craft.SLOTS) {
            if (at >= body.length()) {
                return null;
            }
            if (body.charAt(at) == EMPTY) {
                slots.add(null);
                at++;
                continue;
            }
            if (at + MARK_LEN > body.length()) {
                return null;
            }
            Long m = number(body.substring(at, at + MARK_LEN));
            String name = m == null ? null : byMark.get(m);
            if (name == null) {
                return null;
            }
            slots.add(name);
            at += MARK_LEN;
        }
        if (at != body.length()) {
            return null;
        }
        return new Recipe(typeName, brackets.get(bracket).minLevel(), ts / 9 + 1, ts / 3 % 3 + 1,
                Craft.Speed.values()[ts % 3], java.util.Collections.unmodifiableList(slots));
    }

    private static int bracketIndex(String type, int minLevel) {
        List<CraftData.Recipe> rs = CraftData.ofType(type);
        for (int i = 0; i < rs.size(); i++) {
            if (rs.get(i).minLevel() == minLevel) {
                return i;
            }
        }
        return -1;
    }

    /** A 30-bit mark of an ingredient's name (FNV-1a over its lower-case letters). */
    static long mark(String name) {
        int h = 0x811c9dc5;
        for (byte b : name.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xff;
            h *= 0x01000193;
        }
        return h & 0x3fffffffL;
    }

    private static long check(CharSequence body) {
        CRC32 crc = new CRC32();
        crc.update(body.toString().getBytes(StandardCharsets.US_ASCII));
        return crc.getValue() & 0x3ff;
    }

    private static String digits(long v, int len) {
        char[] out = new char[len];
        for (int i = len - 1; i >= 0; i--) {
            out[i] = DIGITS.charAt((int) (v & 31));
            v >>>= 5;
        }
        return new String(out);
    }

    private static Long number(String s) {
        long v = 0;
        for (int i = 0; i < s.length(); i++) {
            int d = DIGITS.indexOf(s.charAt(i));
            if (d < 0) {
                return null;
            }
            v = v << 5 | d;
        }
        return v;
    }

    private static int clamp(int t) {
        return Math.max(1, Math.min(3, t));
    }
}
