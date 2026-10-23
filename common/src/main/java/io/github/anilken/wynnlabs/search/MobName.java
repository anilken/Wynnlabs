package io.github.anilken.wynnlabs.search;

import io.github.anilken.wynnlabs.game.ItemJson;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a mob's floating name ("§cReggie §7[Lv. 40]", icons and all) and a tracked name from the
 * item data into the same plain key ("reggie"), so the two can be compared: colour codes and font
 * icons go, the level part goes, only letters, digits, spaces, apostrophes and dashes stay, in
 * lower case.
 */
public final class MobName {

    /** "[Lv. 40]", "(Lvl 40)", "Lv 40" or "Lv. 40-45", at the start or the end of the name. */
    private static final Pattern LEVEL = Pattern.compile(
            "(?i)[\\[(]?\\s*lv(?:l)?\\.?\\s*\\d+(?:\\s*[-–]\\s*\\d+)?\\s*[\\])]?");
    private static final Pattern OTHER = Pattern.compile("[^\\p{L}\\p{N} '\\-]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private MobName() {}

    /** The comparable key of a name: only its first line, cleaned; "" when nothing is left. */
    public static String key(String raw) {
        if (raw == null) {
            return "";
        }
        String first = raw;
        int nl = first.indexOf('\n');
        if (nl >= 0) {
            first = first.substring(0, nl);
        }
        String plain = ItemJson.clean(first);
        plain = LEVEL.matcher(plain).replaceAll(" ");
        plain = OTHER.matcher(plain).replaceAll(" ");
        plain = SPACES.matcher(plain).replaceAll(" ").strip();
        // a dash or apostrophe left alone by the cleaning ("Reggie -") is not part of the name
        while (!plain.isEmpty() && "-'".indexOf(plain.charAt(plain.length() - 1)) >= 0) {
            plain = plain.substring(0, plain.length() - 1).strip();
        }
        return plain.toLowerCase(Locale.ROOT);
    }

    /** Whether the floating name {@code label} names one of the tracked keys (see {@link #key}). */
    public static boolean matches(String label, Set<String> trackedKeys) {
        if (trackedKeys.isEmpty()) {
            return false;
        }
        String k = key(label);
        return !k.isEmpty() && trackedKeys.contains(k);
    }
}
