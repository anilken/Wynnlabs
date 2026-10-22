package io.github.anilken.wynnlabs.game;

import com.google.gson.JsonObject;

import io.github.anilken.wynnlabs.core.L;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The twelve professions: their levels on this character (Wynntils' profession model) and
 * their names in the mod's languages. Gathering first, then crafting, as the game lists them.
 */
public final class Professions {

    /** Wynntils' names, in the game's order. */
    public static final List<String> ALL = List.of("MINING", "WOODCUTTING", "FISHING", "FARMING",
            "ALCHEMISM", "ARMOURING", "COOKING", "JEWELING", "SCRIBING", "TAILORING", "WEAPONSMITHING", "WOODWORKING");

    private Professions() {}

    /**
     * A profession's level, or -1 when it is not known (no Wynntils, or a recording without it).
     * Wynntils says 0 until it has read the levels; then the ones Character Info showed this
     * character last time are used (0 when the menu has never been read).
     */
    public static int level(String profession) {
        String p = profession.toUpperCase(Locale.ROOT);
        int l;
        try {
            l = GameState.current().professionLevel(p);
        } catch (Throwable t) {
            return -1;
        }
        if (l <= 0 && GameState.current().running()) {
            try {
                l = Math.max(l, MenuWatcher.profession(p));
            } catch (Throwable ignored) {
                // no saved read
            }
        }
        return l;
    }

    /** Every profession's level, -1 for unknown, in {@link #ALL}'s order. */
    public static Map<String, Integer> levels() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String p : ALL) {
            out.put(p, level(p));
        }
        return out;
    }

    /** The profession's name in the mod's language ("Madencilik"). */
    public static String name(String profession) {
        return L.t("wynnlabs.prof." + profession.toLowerCase(Locale.ROOT));
    }

    /**
     * The requirements not met yet: profession → {level now, level needed}, in the order given.
     * A profession whose level is unknown is left out (nothing to say about it).
     */
    public static Map<String, int[]> missing(Map<String, Integer> needed) {
        Map<String, int[]> out = new LinkedHashMap<>();
        if (needed == null) {
            return out;
        }
        needed.forEach((p, need) -> {
            int have = level(p);
            if (have >= 0 && have < need) {
                out.put(p, new int[] {have, need});
            }
        });
        return out;
    }

    /** The gathering professions, the ones quests ask for. */
    public static boolean gathering(String profession) {
        return ALL.indexOf(profession) < 4;
    }

    /** For the local API: {"MINING": 12, ...}, unknown ones left out. */
    public static JsonObject json() {
        JsonObject o = new JsonObject();
        levels().forEach((k, v) -> {
            if (v >= 0) {
                o.addProperty(k, v);
            }
        });
        return o;
    }
}
