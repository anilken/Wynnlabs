package io.github.anilken.wynnlabs.advice;

import io.github.anilken.wynnlabs.core.Dirs;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.model.Klass;

import java.util.Locale;

/**
 * Your other characters as the verdicts name them: whether one of them can still use an item
 * ("keep it for your Archer"), and class names for the notes.
 */
final class AltCharacters {

    private AltCharacters() {}

    /**
     * Another character of this class, as the notes name it: Turkish possessive ("Mage'in",
     * "Archer'ın"), English "your Mage". Whole words per class, since the suffix is Turkish only.
     */
    static String genitive(String cls) {
        return switch (cls.toUpperCase(Locale.ROOT)) {
            case "MAGE" -> L.t("wynnlabs.auto.class_mage_gen");
            case "ARCHER" -> L.t("wynnlabs.auto.class_archer_gen");
            case "WARRIOR" -> L.t("wynnlabs.auto.class_warrior_gen");
            case "ASSASSIN" -> L.t("wynnlabs.auto.class_assassin_gen");
            case "SHAMAN" -> L.t("wynnlabs.auto.class_shaman_gen");
            default -> L.t("wynnlabs.auto.class_other_gen", AutoAdvice.nice(cls));
        };
    }

    /** A class name shown in a note ("Mage"), from the language file. */
    static String className(Klass c) {
        if (c == null) {
            return "?";
        }
        return switch (c.name()) {
            case "MAGE" -> L.t("wynnlabs.auto.class_mage");
            case "ARCHER" -> L.t("wynnlabs.auto.class_archer");
            case "WARRIOR" -> L.t("wynnlabs.auto.class_warrior");
            case "ASSASSIN" -> L.t("wynnlabs.auto.class_assassin");
            case "SHAMAN" -> L.t("wynnlabs.auto.class_shaman");
            default -> AutoAdvice.nice(c);
        };
    }

    /**
     * Another of your characters of this class for whom an item of these levels still matters
     * (not more than 15 levels below them, nor more than 15 above: a Lv 2 alt will not wear a
     * Lv 61 box for a long time): "Archer'ın (Lv. 70)", or null. An alt whose advice is off
     * (rules.advice.enabled in its own folder) is never kept for.
     */
    static String altOfClass(Klass need, int low, int high) {
        if (!Settings.on("altAdvice")) {
            return null;                        // the player wants no "keep for an alt" advice
        }
        try {
            for (var e : GameState.current().characters().getAsJsonArray("characters")) {
                var o = e.getAsJsonObject();
                if (o.get("active").getAsBoolean() || !need.name().equals(o.get("class").getAsString()) || !altAdvised(o)) {
                    continue;
                }
                int lv = o.get("level").getAsInt();
                if (high >= lv - 15 && low <= lv + 15) {
                    return L.t("wynnlabs.auto.alt_with_level", genitive(need.name()), lv);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Whether the advice is on for that character (its own rules file); unknown counts as on. */
    private static boolean altAdvised(com.google.gson.JsonObject character) {
        try {
            String id = character.has("id") ? character.get("id").getAsString() : "";
            if (id.isEmpty()) {
                return true;
            }
            var f = Dirs.config().resolve("characters").resolve(id).resolve("rules.json");
            if (!java.nio.file.Files.exists(f)) {
                return true;
            }
            var r = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(f, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            var a = r.has("advice") && r.get("advice").isJsonObject() ? r.getAsJsonObject("advice") : null;
            return a == null || !a.has("enabled") || a.get("enabled").getAsBoolean();
        } catch (Exception e) {
            return true;
        }
    }

    /** Another character (of this class, or any for armour) these levels suit: within 15 below to 5 above. */
    static String altOfLevel(Klass need, int low, int high) {
        if (!Settings.on("altAdvice")) {
            return null;
        }
        try {
            for (var e : GameState.current().characters().getAsJsonArray("characters")) {
                var o = e.getAsJsonObject();
                if (o.get("active").getAsBoolean() || !altAdvised(o)) {
                    continue;
                }
                if (need != null && need != Klass.NONE && !need.name().equals(o.get("class").getAsString())) {
                    continue;
                }
                int lv = o.get("level").getAsInt();
                if (high >= lv - 15 && low <= lv + 5) {
                    return L.t("wynnlabs.auto.alt_with_level", genitive(o.get("class").getAsString()), lv);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
