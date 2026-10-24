package io.github.anilken.wynnlabs.crafting;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The XP the last gather or craft of each profession gave this session, as the game showed it
 * (Wynntils' profession XP event). The planner divides by it: the mod's data has no XP per
 * gather, so a gather has to happen once before gathers can be counted.
 */
public final class ProfessionXp {

    private static final Map<String, Double> LAST = new ConcurrentHashMap<>();

    private ProfessionXp() {}

    /** A gain the game showed for a profession ("MINING"). */
    public static void gained(String profession, double xp) {
        if (profession != null && xp > 0) {
            LAST.put(profession.toUpperCase(Locale.ROOT), xp);
        }
    }

    /** The last gain of a profession this session, 0 when there was none. */
    public static double last(String profession) {
        Double v = LAST.get(profession.toUpperCase(Locale.ROOT));
        return v == null ? 0 : v;
    }

    /** For tests and the preview. */
    static void clear() {
        LAST.clear();
    }
}
