package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.model.AbilityNode;
import io.github.anilken.wynnlabs.model.Klass;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sets up the damage model for any class by itself, read from the class's ability tree text
 * (Wynntils' data): each spell's combo (→ 1st..4th spell), mana cost and damage conversion
 * ("Total Damage 150%: Damage 120%, Fire 30%"). The archetype's masteries and the spells
 * decide the build's elements, which set the element stat weights, weapon powders and the
 * skills gear may ask for. No assistant needed when you play a new class.
 */
final class ClassProfiles {

    private static final String[] ELEMENTS = {"earth", "thunder", "water", "fire", "air"};
    private static final Map<String, String> SKILL_OF = Map.of("earth", "STRENGTH", "thunder", "DEXTERITY",
            "water", "INTELLIGENCE", "fire", "DEFENCE", "air", "AGILITY");
    private static final Pattern COST = Pattern.compile("Mana Cost: (\\d+)");
    private static final Pattern PART = Pattern.compile("\\(\\S*\\s*(Damage|Earth|Thunder|Water|Fire|Air): (\\d+)%\\)");

    private ClassProfiles() {}

    private static List<String> lines(AbilityNode n) {
        List<String> out = new ArrayList<>();
        if (n.description() != null) {
            for (JsonArray line : n.description()) {
                StringBuilder sb = new StringBuilder();
                line.forEach(e -> {
                    if (e.isJsonObject() && e.getAsJsonObject().has("text")) {
                        sb.append(e.getAsJsonObject().get("text").getAsString());
                    }
                });
                out.add(sb.toString().strip());
            }
        }
        return out;
    }

    /** 1..4 from a combo like RIGHT-LEFT-RIGHT (archers start with LEFT; same rule). */
    private static int index(String combo) {
        String[] c = combo.replace("Click Combo:", "").trim().split("-");
        if (c.length != 3) {
            return 0;
        }
        boolean second = c[1].equals(c[0]);
        boolean third = c[2].equals(c[0]);
        if (!second && third) {
            return 1;
        }
        if (second && third) {
            return 2;
        }
        if (!second) {
            return 3;
        }
        return 4;
    }

    /** The class's spells as rules.model.spells entries; movement spells get no share. */
    static JsonArray spells() {
        Klass cls = Wyn.classType();
        var tree = Wyn.abilityTree(cls);
        JsonArray out = new JsonArray();
        if (tree == null || cls == null) {
            return out;
        }
        for (AbilityNode n : tree) {
            if (n.type() == null || !n.type().startsWith(cls.name() + "_ABILITY")) {
                continue;
            }
            List<String> ls = lines(n);
            String combo = ls.stream().filter(l -> l.startsWith("Click Combo")).findFirst().orElse("");
            int idx = index(combo);
            if (idx == 0) {
                continue;
            }
            int cost = 30;
            JsonObject conv = new JsonObject();
            boolean perHit = false;
            for (String l : ls) {
                Matcher m = COST.matcher(l);
                if (m.find()) {
                    cost = Integer.parseInt(m.group(1));
                }
                if (l.contains("Total Damage") && (l.contains("per hit") || l.contains("per arrow"))) {
                    perHit = true;
                }
                Matcher p = PART.matcher(l);
                while (p.find()) {
                    String el = p.group(1).equals("Damage") ? "neutral" : p.group(1).toLowerCase(Locale.ROOT);
                    conv.addProperty(el, Integer.parseInt(p.group(2)) / 100.0 * (perHit ? 3 : 1));
                }
            }
            JsonObject s = new JsonObject();
            s.addProperty("name", n.name());
            s.addProperty("index", idx);
            s.addProperty("cost", cost);
            s.addProperty("share", conv.isEmpty() ? 0.0 : 1.0);
            s.add("conv", conv);
            out.add(s);
        }
        // shares: damage spells split evenly
        long dmg = 0;
        for (var e : out) {
            if (e.getAsJsonObject().get("share").getAsDouble() > 0) {
                dmg++;
            }
        }
        for (var e : out) {
            JsonObject s = e.getAsJsonObject();
            if (s.get("share").getAsDouble() > 0) {
                s.addProperty("share", Math.round(1000.0 / dmg) / 1000.0);
            }
        }
        return out;
    }

    /**
     * Element scores: spell conversions plus the archetype's mastery nodes, and "neutral" for the
     * plain damage part of the spells (no element of its own: Strength and Dexterity raise it).
     * A mastery counts about as much as one spell's conversion: at 1 each it outweighed every
     * spell, and an Archer's gear was held to two elements its spells barely use.
     */
    private static Map<String, Double> elements(JsonArray spells, String archetype) {
        Map<String, Double> score = new HashMap<>();
        for (var e : spells) {
            JsonObject s = e.getAsJsonObject();
            double share = s.get("share").getAsDouble();
            s.getAsJsonObject("conv").entrySet().forEach(c ->
                    score.merge(c.getKey(), share * c.getValue().getAsDouble(), Double::sum));
        }
        var tree = Wyn.abilityTree(Wyn.classType());
        if (tree != null && archetype != null && !archetype.isEmpty()) {
            for (AbilityNode n : tree) {
                String a = n.archetype() == null ? "" : n.archetype();
                String name = n.name().toLowerCase(Locale.ROOT);
                if (archetype.equalsIgnoreCase(a) && name.endsWith(" mastery")) {
                    score.merge(name.replace(" mastery", ""), 0.3, Double::sum);
                }
            }
        }
        return score;
    }

    /**
     * The rules a class and archetype need: spells, the two main elements' stat weights,
     * weapon powders, the skills gear may ask for, and summons for a Summoner.
     */
    private static final List<String> MELEE = List.of("fallen", "battle monk", "shadestepper", "acrobat", "boltslinger");

    /**
     * How much the main attack counts against spells and summons for a class and archetype: a
     * melee archetype 1, a Summoner 0.2 (its puppets and spells carry it), and so on. Feedback may
     * raise it at most to rules.model.nudgeCap times this.
     */
    static double mainAttackWeight(String cls, String archetype) {
        String arch = archetype == null ? "" : archetype.toLowerCase(Locale.ROOT);
        return MELEE.contains(arch) ? 1.0
                : arch.equals("summoner") ? 0.2
                : arch.equals("ritualist") ? 0.6
                : arch.isEmpty() ? (cls.equals("WARRIOR") || cls.equals("ASSASSIN") ? 1.0 : cls.equals("ARCHER") ? 0.7 : 0.4)
                : 0.3;
    }

    static JsonObject rulesFor(String archetype, boolean newCharacter) {
        JsonArray spells = spells();
        JsonObject change = new JsonObject();
        if (spells.isEmpty()) {
            return change;
        }
        Map<String, Double> el = elements(spells, archetype);
        double neutral = el.getOrDefault("neutral", 0.0);
        el.remove("neutral");
        List<String> top = new ArrayList<>(el.keySet());
        top.sort((a, b) -> Double.compare(el.get(b), el.get(a)));
        List<String> main = top.subList(0, Math.min(2, top.size()));

        JsonObject model = new JsonObject();
        model.add("spells", spells);
        model.addProperty("passiveDpsFactor", "Summoner".equalsIgnoreCase(archetype) ? 1.0 : 0.0);
        // How much the main attack matters, how hard it hits per click, and how well mana steal
        // works (it needs main-attack hits): per class and archetype, not one Shaman number
        String cls = Wyn.classType().name();
        String arch = archetype == null ? "" : archetype.toLowerCase(Locale.ROOT);
        boolean melee = MELEE.contains(arch);
        model.addProperty("mainAttackWeight", mainAttackWeight(cls, archetype));
        model.addProperty("mainAttackClassMult", switch (cls) {
            case "SHAMAN" -> 1.05;                  // relik: 3 beams × 35 %
            case "ASSASSIN" -> arch.equals("acrobat") ? 1.2 : 1.0;
            default -> 1.0;
        });
        model.addProperty("manaStealFactor", cls.equals("SHAMAN") ? 0.33 : melee ? 1.0 : 0.6);
        if (melee) {
            JsonObject feel = new JsonObject();     // hits per second are modelled; no extra penalty
            for (String sp : List.of("SUPER_SLOW", "VERY_SLOW", "SLOW")) {
                feel.addProperty(sp, 1.0);
            }
            model.add("attackSpeedFeel", feel);
        }
        JsonObject weights = new JsonObject();
        for (String e : ELEMENTS) {
            boolean on = main.contains(e);
            weights.addProperty(e + "SpellDamage", on ? 1.8 : 0.3);
            weights.addProperty(e + "Damage", on ? 1.2 : 0.3);
            weights.addProperty(e + "MainAttackDamage", on ? 1.0 : 0.3);
        }
        JsonArray powders = new JsonArray();
        main.forEach(e -> powders.add(e.toUpperCase(Locale.ROOT)));
        JsonObject pw = new JsonObject();
        pw.add("weapon", powders);
        JsonObject shopping = new JsonObject();
        JsonArray pref = new JsonArray();
        main.forEach(e -> pref.add(SKILL_OF.get(e)));
        // spells that are mostly plain damage: Dexterity and Strength raise every hit of it
        if (neutral > 0 && (top.isEmpty() || neutral >= el.get(top.get(0)))) {
            for (String sk : List.of("DEXTERITY", "STRENGTH")) {
                if (!pref.contains(new com.google.gson.JsonPrimitive(sk))) {
                    pref.add(sk);
                }
            }
        }
        if (!main.contains("water")) {
            pref.add("INTELLIGENCE");           // cheaper spells help every class
        }
        if (!main.contains("fire")) {
            pref.add("DEFENCE");                // and so does taking less damage
        }
        shopping.add("preferredSkills", pref);
        change.add("model", model);
        change.add("statWeights", weights);
        change.add("powders", pw);
        change.add("shopping", shopping);
        if (newCharacter) {
            // the skills behind the build's own elements (earth → Strength, …), not a fixed pair
            JsonArray prio = new JsonArray();
            main.forEach(e -> {
                if (SKILL_OF.containsKey(e) && !prio.contains(new com.google.gson.JsonPrimitive(SKILL_OF.get(e)))) {
                    prio.add(SKILL_OF.get(e));
                }
            });
            if (prio.isEmpty()) {
                prio.add("STRENGTH");
            }
            model.add("skillPriority", prio);
            model.addProperty("skillPriorityShare", 0.4);
            change.addProperty("goal", "otomatik profil: " + Wyn.classType().name()
                    + (archetype == null || archetype.isEmpty() ? "" : " " + archetype));
        }
        return change;
    }

    /** Applies the profile; called for new characters and when the archetype changes. */
    static void apply(String archetype, boolean newCharacter) {
        try {
            JsonObject change = rulesFor(archetype, newCharacter);
            if (!change.isEmpty()) {
                Rules.update(change);
            }
        } catch (Throwable t) {
            System.out.println("[WynnLabs] class profile failed: " + t);
        }
    }
}
