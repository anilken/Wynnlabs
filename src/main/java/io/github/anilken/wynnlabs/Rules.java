package io.github.anilken.wynnlabs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * The rules {@link AutoAdvice} follows, kept in {@code config/wynnlabs/rules.json}.
 *
 * <p>The assistant edits them (POST /rules) whenever the player decides something new, so the
 * same kind of item is judged in game next time without asking again. Missing keys fall back
 * to the defaults below.
 */
final class Rules {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String DEFAULTS = """
            {
              "goal": "fast levelling, damage build",
              "tooLowLevels": 4,
              "tooHighLevels": 5,
              "levelWeight": 1.0,
              "weaponDpsWeight": 0.3,
              "armourHealthWeight": 0.04,
              "tierBonus": {"UNIQUE": 1, "RARE": 2, "SET": 2, "LEGENDARY": 4, "FABLED": 5, "MYTHIC": 8},
              "statWeights": {
                "spellDamage": 2.0, "rawSpellDamage": 2.0, "elementalSpellDamage": 1.5, "neutralSpellDamage": 1.5,
                "waterSpellDamage": 2.0, "thunderSpellDamage": 1.0, "mainAttackDamage": 1.0, "damage": 1.0,
                "elementalDamage": 1.0, "neutralDamage": 0.8, "waterDamage": 1.4, "thunderDamage": 0.8,
                "waterMainAttackDamage": 1.0, "thunderMainAttackDamage": 0.6, "thunderMelee": 0.6, "earthDamage": 0.3,
                "fireDamage": 0.3, "airDamage": 0.3, "earthSpellDamage": 0.3, "fireSpellDamage": 0.3,
                "airSpellDamage": 0.3, "earthMainAttackDamage": 0.3, "fireMainAttackDamage": 0.3, "airMainAttackDamage": 0.3,
                "mainAttackFireDamage": 0.3, "criticalDamageBonus": 1.0, "attackSpeed": 0.2, "mainAttackRange": 0.3,
                "SpellCost": -1.5, "manaRegen": 2.0, "manaSteal": 0.5, "maxMana": 0.5,
                "intelligence": 1.0, "dexterity": 1.0, "strength": 0.2, "defence": 0.2,
                "agility": 0.3, "walkSpeed": 1.0, "sprint": 0.3, "jumpHeight": 0.2,
                "combatExperience": 1.5, "xpBonus": 1.5, "lootBonus": 0.3, "lootQuality": 0.3,
                "stealing": 0.1, "health": 0.3, "healthRegen": 0.3, "lifeSteal": 0.5,
                "healing": 0.3, "damageFromMobs": -0.5, "exploding": 0.1, "poison": 0.2,
                "thorns": 0.1, "reflection": 0.1, "slowEnemy": 0.3, "weakenEnemy": 0.3,
                "soulPointRegen": 0.1
              },
              "powders": {"weapon": ["WATER", "THUNDER"], "armour": []},
              "shopping": {
                "aheadLevels": 5,
                "perSlot": 3,
                "preferTradeMarket": 1,
                "minGainPercent": 10,
                "preferredSkills": ["INTELLIGENCE", "DEFENCE", "DEXTERITY"],
                "maxOffSkillPoints": 10,
                "excludeTiers": ["MYTHIC"],
                "excludeSources": ["UNAVAILABLE", "RAID", "EVENT"]
              },
              "rerollBelowPercent": 30,
              "roadmap": {"targets": [], "targetNote": "topluluk önerisi"},
              "model": {
                "spells": [
                  {"name": "Aura", "index": 3, "cost": 40, "share": 0.55, "conv": {"neutral": 1.5, "water": 0.3}},
                  {"name": "Uproot", "index": 4, "cost": 30, "share": 0.25, "conv": {"neutral": 0.8, "earth": 0.3, "thunder": 0.2}},
                  {"name": "Totem", "index": 1, "cost": 30, "share": 0.2, "conv": {"neutral": 1.2, "fire": 0.3}}
                ],
                "mainAttackWeight": 0.5, "baseManaPer5s": 25, "baseMana": 100, "fightSeconds": 30, "manaStealFactor": 0.3, "maxCastsPer5s": 4,
                "xpWeight": 1.0, "speedWeight": 0.3, "speedCeiling": 1.35, "nudgeCap": 1.5, "survivalWeight": 0.4, "regenWeight": 0.2, "negativeRegenWeight": 2.0, "manaWeight": 0.3, "skillPriority": ["INTELLIGENCE", "DEFENCE"], "skillPriorityShare": 0.6, "baseHealth": 100, "healthPerLevel": 5,
                "incomingElements": {"neutral": 0.5, "earth": 0.1, "thunder": 0.1, "water": 0.1, "fire": 0.1, "air": 0.1}
              },
              "grind": {"levelsBelow": 3, "levelsAbove": 3},
              "trade": {"valuableTiers": ["MYTHIC", "FABLED"], "junkTiers": ["NORMAL"], "minTradeMarketPrice": 1920, "legendaryMinPercent": 80, "anyMinPercent": 95},
              "names": {},
              "kinds": {}
            }
            """;

    private static volatile JsonObject rules = GSON.fromJson(DEFAULTS, JsonObject.class);
    /** Bumped on every change, so cached results built from the rules know to rebuild. */
    static volatile int version;

    private Rules() {}

    private static Path file() {
        return Profile.dir().resolve("rules.json");
    }

    /** True right after load() for a character that has no rules file yet. */
    static volatile boolean fresh;

    static void load() {
        JsonObject base = GSON.fromJson(DEFAULTS, JsonObject.class);
        try {
            Path f = file();
            if (Files.exists(f)) {
                Saver.settle(f);                    // a change still waiting to be written goes first
                merge(base, GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), JsonObject.class));
            }
            fresh = !Files.exists(f) && !Profile.id().isEmpty();   // first time: Setup picks a build
            if (!Profile.currentClass().isEmpty()) {
                base.addProperty("class", Profile.currentClass());
            }
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not read rules.json: " + e);
        }
        clampNudged(base);
        rules = base;
        version++;
        if (!Profile.id().isEmpty()) {
            save();                             // the shared file is only read, never rewritten
        }
    }

    /** Model weights that player feedback may nudge, with their defaults (Feedback.nudge). */
    static final Map<String, Double> NUDGED = Map.of("xpWeight", 1.0, "speedWeight", 0.3,
            "survivalWeight", 0.4, "mainAttackWeight", 0.5);

    /** Weights clamped at the last load, for one chat line (WynnLabs prints and clears it). */
    static volatile java.util.List<String> clamped = java.util.List.of();

    /**
     * Once per character: feedback nudges used to reach 3× a weight's default (speed at 1.0 made
     * walk speed outweigh damage), now they stop at rules.model.nudgeCap × default. A weight above
     * that which the player did not set by hand (rules.model.byHand) is brought down to it once.
     */
    private static void clampNudged(JsonObject base) {
        JsonObject model = base.getAsJsonObject("model");
        if (model == null || model.has("capsApplied") && model.get("capsApplied").getAsBoolean()) {
            return;
        }
        java.util.Set<String> byHand = new java.util.HashSet<>();
        if (model.has("byHand") && model.get("byHand").isJsonArray()) {
            model.getAsJsonArray("byHand").forEach(e -> byHand.add(e.getAsString()));
        }
        double cap = model.has("nudgeCap") ? model.get("nudgeCap").getAsDouble() : 1.5;
        java.util.List<String> done = new java.util.ArrayList<>();
        for (var e : NUDGED.entrySet()) {
            String k = e.getKey();
            double max = e.getValue() * cap;
            if (!byHand.contains(k) && model.has(k) && model.get(k).getAsDouble() > max + 1e-9) {
                model.addProperty(k, Math.round(max * 1000) / 1000.0);
                done.add(k);
            }
        }
        model.addProperty("capsApplied", true);
        clamped = java.util.List.copyOf(done);
    }

    /** Tests only: a character's saved rules over the defaults, as load() reads them; never saved. */
    static synchronized void use(JsonObject saved) {
        JsonObject base = GSON.fromJson(DEFAULTS, JsonObject.class);
        merge(base, saved.deepCopy());
        clampNudged(base);
        rules = base;
        version++;
    }

    /**
     * rules.json, written in the background a few seconds after the last change. The document
     * is replaced, never edited in place, so the one handed over stays as it was.
     */
    private static void save() {
        Path f = file();
        JsonObject snapshot = rules;
        Saver.mark(f, () -> GSON.toJson(snapshot));
    }

    /** Objects merge key by key, anything else replaces; a null value removes the key. */
    static synchronized JsonObject update(JsonObject change) {
        JsonObject next = rules.deepCopy();
        merge(next, change);
        rules = next;
        version++;
        save();
        return rules;
    }

    static synchronized JsonObject reset() {
        rules = GSON.fromJson(DEFAULTS, JsonObject.class);
        version++;
        save();
        return rules;
    }

    static JsonObject get() {
        return rules;
    }

    private static void merge(JsonObject into, JsonObject change) {
        for (Map.Entry<String, JsonElement> e : change.entrySet()) {
            JsonElement v = e.getValue();
            if (v == null || v.isJsonNull()) {
                into.remove(e.getKey());
            } else if (v.isJsonObject() && into.has(e.getKey()) && into.get(e.getKey()).isJsonObject()) {
                merge(into.getAsJsonObject(e.getKey()), v.getAsJsonObject());
            } else {
                into.add(e.getKey(), v);
            }
        }
    }

    static double num(String key, double fallback) {
        JsonElement e = rules.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsDouble() : fallback;
    }

    /** A list of strings under group.key (upper-cased), empty if missing. */
    static java.util.List<String> list(String group, String key) {
        java.util.List<String> out = new java.util.ArrayList<>();
        JsonObject g = rules.getAsJsonObject(group);
        if (g != null && g.has(key) && g.get(key).isJsonArray()) {
            g.getAsJsonArray(key).forEach(e -> out.add(e.getAsString().toUpperCase(Locale.ROOT)));
        }
        return out;
    }

    /**
     * Items the community picks in its builds (rules.community.picks: name → the build level it
     * was seen at). Null when the item is not on the list.
     */
    static Integer communityLevel(String item) {
        JsonObject c = rules.getAsJsonObject("community");
        JsonObject picks = c == null ? null : c.getAsJsonObject("picks");
        return picks != null && picks.has(item) ? picks.get(item).getAsInt() : null;
    }

    /** % added to a community pick's gain (rules.community.bonusPercent, default 3). */
    static double communityBonus(String item) {
        return communityLevel(item) == null ? 0 : sub("community", "bonusPercent", 3);
    }

    static String subText(String group, String key, String fallback) {
        JsonObject g = rules.getAsJsonObject(group);
        return g != null && g.has(key) && g.get(key).isJsonPrimitive() ? g.get(key).getAsString() : fallback;
    }

    static boolean subBool(String group, String key, boolean fallback) {
        JsonObject g = rules.getAsJsonObject(group);
        return g != null && g.has(key) && g.get(key).isJsonPrimitive() ? g.get(key).getAsBoolean() : fallback;
    }

    /**
     * Whether the mod advises on this character at all (rules.advice.enabled, per character):
     * off on an alt, the tooltips, glows, chat notes and the road map keep quiet.
     */
    static boolean adviceHere() {
        return subBool("advice", "enabled", true);
    }

    /** Turns the advice on this character on or off. */
    static void setAdviceHere(boolean on) {
        JsonObject a = new JsonObject();
        a.addProperty("enabled", on);
        JsonObject change = new JsonObject();
        change.add("advice", a);
        update(change);
    }

    private static JsonObject hashedRules;
    private static int hashedVersion = -1;
    private static int hash;

    /**
     * A hash of the whole rules document, for cache keys (the road map asks every two seconds):
     * worked out again only when the rules changed, instead of serializing them on every ask.
     */
    static synchronized int hash() {
        JsonObject r = rules;
        if (r != hashedRules || version != hashedVersion) {
            hash = r == null ? 0 : r.toString().hashCode();
            hashedRules = r;
            hashedVersion = version;
        }
        return hash;
    }

    static double sub(String group, String key, double fallback) {
        JsonObject g = rules.getAsJsonObject(group);
        return g != null && g.has(key) && g.get(key).isJsonPrimitive() ? g.get(key).getAsDouble() : fallback;
    }

    /**
     * A grind spot's level known from the wiki (Wynntils' map data has none, and the guess from
     * nearby caves can be far off: Llevigar Spider Nest came out 60, its mobs are 40–42), or null.
     * A rules file saved before this key existed still gets the built-in values.
     */
    static Integer spotLevel(String name) {
        JsonObject g = rules.getAsJsonObject("grind");
        JsonObject known = g != null && g.has("spotLevels") && g.get("spotLevels").isJsonObject()
                ? g.getAsJsonObject("spotLevels") : null;
        if (known != null && known.has(name)) {
            return known.get(name).getAsInt();
        }
        return SPOT_LEVELS.get(name);
    }

    /**
     * The middle of each grind spot's suggested level range in the wiki's "List of Grind Caves"
     * (wynncraft.wiki.gg/wiki/Caves, 2026-10-14), matched to Wynntils' grind spots by name or by
     * coordinates within 60 blocks. Spots with no match keep the guess from nearby caves.
     */
    private static final java.util.Map<String, Integer> SPOT_LEVELS = java.util.Map.ofEntries(
            Map.entry("Ancient Nemract", 25),
            Map.entry("Bandit's Base", 82),
            Map.entry("Defiled Cave", 72),
            Map.entry("Desert Altar", 37),
            Map.entry("Dullahan's Castle", 65),
            Map.entry("Frozen Fort", 100),
            Map.entry("Herb Cave", 60),
            Map.entry("Ice Wraith Cave", 45),
            Map.entry("Idol Cave", 60),
            Map.entry("Living Scrap Waterfall", 75),
            Map.entry("Llevigar Spider Nest", 42),
            Map.entry("Mirror Cave", 97),
            Map.entry("Mushroom Plain", 12),
            Map.entry("Myconid Cave", 75),
            Map.entry("The Scrapyard", 100),
            Map.entry("Witness Church", 102),
            Map.entry("Zombie Tree", 5));

    static double tierBonus(String tier) {
        JsonObject t = rules.getAsJsonObject("tierBonus");
        return t != null && t.has(tier) ? t.get(tier).getAsDouble() : 0;
    }

    /** Weight of a stat by its API name: an exact key wins, else the longest key it contains. */
    static double statWeight(String apiName) {
        JsonObject w = rules.getAsJsonObject("statWeights");
        if (w == null) {
            return 0;
        }
        if (w.has(apiName)) {
            return w.get(apiName).getAsDouble();
        }
        String lower = apiName.toLowerCase(Locale.ROOT);
        String best = null;
        for (String k : w.keySet()) {
            if (lower.contains(k.toLowerCase(Locale.ROOT)) && (best == null || k.length() > best.length())) {
                best = k;
            }
        }
        return best == null ? 0 : w.get(best).getAsDouble();
    }

    /** A fixed verdict from {"verdict","note"} under names.<item name> or kinds.<kind>. */
    static Advice.Entry fixed(String group, String key) {
        JsonObject g = rules.getAsJsonObject(group);
        if (g == null || key == null || !g.has(key) || !g.get(key).isJsonObject()) {
            return null;
        }
        JsonObject r = g.getAsJsonObject(key);
        if (!r.has("verdict")) {
            return null;
        }
        String note = r.has("note") ? r.get("note").getAsString() : null;
        return new Advice.Entry(r.get("verdict").getAsString().toUpperCase(Locale.ROOT), note);
    }
}
