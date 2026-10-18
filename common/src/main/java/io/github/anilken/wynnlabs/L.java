package io.github.anilken.wynnlabs;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Player-facing text from the language files ({@code assets/wynnlabs/lang/*.json}), in the
 * game's current language with English as the fallback. {@link #t} gives a String for code that
 * measures or joins text; {@link #c} a Component. Arguments fill {@code %s} / {@code %1$s}.
 */
final class L {

    private L() {}

    private static final java.util.Map<String, java.util.Map<String, String>> TABLES = new java.util.concurrent.ConcurrentHashMap<>();

    /** One shipped language file, read once from the jar. */
    private static java.util.Map<String, String> table(String code) {
        return TABLES.computeIfAbsent(code, c -> {
            try (var in = L.class.getResourceAsStream("/assets/wynnlabs/lang/" + c + ".json")) {
                if (in == null) {
                    return java.util.Map.of();
                }
                java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<java.util.Map<String, String>>() {}.getType();
                java.util.Map<String, String> m = new com.google.gson.Gson().fromJson(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8), type);
                return m == null ? java.util.Map.of() : m;
            } catch (Exception e) {
                return java.util.Map.of();
            }
        });
    }

    /**
     * The mod's text in the language picked in settings (0 = follow the game, 1 = English,
     * 2 = Türkçe); the game's own language is left alone. Without the game (tests) "follow the
     * game" is English, read from the same files.
     */
    static String t(String key, Object... args) {
        try {
            int pick = Settings.number("langOverride", 0);
            boolean game = GameState.current().running();
            if (pick == 1 || pick == 2 || !game) {
                String v = table(pick == 2 ? "tr_tr" : "en_us").get(key);
                if (v != null) {
                    return String.format(java.util.Locale.ROOT, v, args);
                }
                if (!game) {
                    return key;                 // what I18n shows for a key it does not know
                }
            }
            return I18n.get(key, args);
        } catch (java.util.IllegalFormatException e) {
            return key;                         // a bad "%" in a language file: show the key, never crash
        }
    }

    /** The language {@link #t} speaks now ("en_us", "tr_tr" …): text built ahead of time keys on it. */
    static String code() {
        int pick = Settings.number("langOverride", 0);
        if (pick == 1 || pick == 2 || !GameState.current().running()) {
            return pick == 2 ? "tr_tr" : "en_us";
        }
        try {
            return net.minecraft.client.Minecraft.getInstance().getLanguageManager().getSelected();
        } catch (Throwable t) {
            return "en_us";
        }
    }

    static MutableComponent c(String key, Object... args) {
        return Component.literal(t(key, args));
    }

    /**
     * {@link #t} for a text that counts something. A value may hold two forms, "one|other"
     * ("%s day ago|%s days ago"): {@code count} 1 picks the first, anything else the second. A
     * value without "|" (Turkish needs none) is used as it is. Without {@code args} the count
     * fills the text.
     */
    static String n(String key, long count, Object... args) {
        try {
            String v = raw(key);
            if (v == null) {
                return key;
            }
            int bar = v.indexOf('|');
            if (bar >= 0) {
                v = count == 1 ? v.substring(0, bar) : v.substring(bar + 1);
            }
            return String.format(java.util.Locale.ROOT, v, args.length == 0 ? new Object[] {count} : args);
        } catch (java.util.IllegalFormatException e) {
            return key;
        }
    }

    static MutableComponent cn(String key, long count, Object... args) {
        return Component.literal(n(key, count, args));
    }

    /** The unformatted text {@link #t} would use, or null when no language has the key. */
    private static String raw(String key) {
        int pick = Settings.number("langOverride", 0);
        boolean game = GameState.current().running();
        if (pick == 1 || pick == 2 || !game) {
            String v = table(pick == 2 ? "tr_tr" : "en_us").get(key);
            if (v != null || !game) {
                return v;
            }
        }
        var lang = net.minecraft.locale.Language.getInstance();
        return lang.has(key) ? lang.getOrDefault(key) : null;
    }
}
