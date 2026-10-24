package io.github.anilken.wynnlabs.build;

import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.model.AbilityNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ability tree's passive damage bonuses, read from the nodes' text as the game words them
 * (Wynntils' data), for {@link TreeModel}'s overlay:
 * <ul>
 *   <li>"Main Attack Damage: +5%" (the weapon proficiencies): the main attack only;</li>
 *   <li>"Earth Damage: +20%" (the masteries): every hit in that element;</li>
 *   <li>"Total Damage: +4% (of your DPS) ( Damage: +4%)" on a node that names one spell
 *       ("Increase Totem's damage"), or "(of your DPS, for Arrow Shield)": that spell's conversion;</li>
 *   <li>the same on a node that names one summon and no spell ("Your Puppet's knives become
 *       stronger"): that summon's hits.</li>
 * </ul>
 * Kept to what reads plainly: a lowering ("-10% per arrow" comes with more arrows), a bonus
 * that holds only now and then ("+15% (per Focus)", "Damage Bonus to Self" for a few seconds),
 * one for a part that is not a spell (Fire Creep, Guardian Angels), a node naming two spells,
 * or damage parts that do not add up to the total are left out, as before.
 */
final class TreeBonuses {

    private static final Pattern MAIN = Pattern.compile("Main Attack Damage: \\+(\\d+)%(?! \\(per)");
    private static final Pattern MASTERY = Pattern.compile("(Earth|Thunder|Water|Fire|Air) Damage: \\+(\\d+)%(?! \\(per)");
    private static final Pattern TOTAL = Pattern.compile(
            "Total Damage: \\+(\\d+)% \\(of your DPS(?:, for ([A-Za-z' ]+?))?(?:, per [a-z]+)?\\)");
    private static final Pattern PART = Pattern.compile("\\(\\S*\\s*(Damage|Earth|Thunder|Water|Fire|Air): \\+(\\d+)%\\)");

    private TreeBonuses() {}

    /**
     * What one node adds: {@code spell} the spell its {@code conv} (per hit, {@link Damage#ELEMENTS}
     * order) goes to, or {@code summon} the summon's noun it goes to (null: neither);
     * {@code mainAttack} % on the main attack; {@code element} % per element (neutral unused).
     */
    record Bonus(String spell, String summon, double[] conv, double mainAttack, double[] element) {}

    /**
     * The bonuses of the tree's nodes by node name, for the class's spells ({@code spellNames}, as
     * rules.model.spells has them) and the summons {@link TreeModel#read} found ({@code summons},
     * their nouns). Nodes with nothing readable are left out.
     */
    static Map<String, Bonus> read(List<AbilityNode> tree, String cls, Collection<String> spellNames,
            Collection<String> summons) {
        Map<String, Bonus> out = new LinkedHashMap<>();
        List<String> spells = new ArrayList<>(spellNames);
        for (AbilityNode n : tree) {
            boolean ultimate = n.type() != null && n.type().contains("ULTIMATE");
            if (ultimate || TreeModel.spellNode(n, cls) || containsIgnoreCase(spells, n.name())) {
                continue;                               // a spell's own numbers are its rules entry's
            }
            Bonus b = bonus(TreeModel.text(n), spells, summons);
            if (b != null) {
                out.put(n.name(), b);
            }
        }
        return out;
    }

    /** One node's text read for its bonuses; null when it has none the model can use. */
    static Bonus bonus(String t, List<String> spells, Collection<String> summons) {
        double main = 0;
        Matcher mm = MAIN.matcher(t);
        while (mm.find()) {
            main += Integer.parseInt(mm.group(1));
        }
        double[] element = new double[Damage.ELEMENTS.length];
        boolean anyElement = false;
        Matcher em = MASTERY.matcher(t);
        while (em.find()) {
            element[index(em.group(1))] += Integer.parseInt(em.group(2));
            anyElement = true;
        }
        String spell = null;
        String summon = null;
        double[] conv = null;
        int at = t.indexOf("Total Damage:");
        Matcher tm = TOTAL.matcher(t);
        // only the first damage block, and only when it is a plain addition
        if (at >= 0 && tm.find(at) && tm.start() == at) {
            int next = t.indexOf("Total Damage", tm.end());
            double[] c = parts(t.substring(tm.end(), next < 0 ? t.length() : next));
            double sum = 0;
            for (double v : c) {
                sum += v;
            }
            if (Math.abs(sum * 100 - Integer.parseInt(tm.group(1))) < 1.5) {
                String target = tm.group(2);
                if (target != null) {
                    spell = named(spells, target.trim());       // "for Guardian Angels" is not a spell
                } else {
                    List<String> in = mentioned(t, spells);
                    if (in.size() == 1) {
                        spell = in.get(0);
                    } else if (in.isEmpty()) {
                        List<String> nouns = new ArrayList<>();
                        String low = t.toLowerCase(Locale.ROOT);
                        for (String s : summons) {
                            if (low.contains(s) && !nouns.contains(s)) {
                                nouns.add(s);
                            }
                        }
                        summon = nouns.size() == 1 ? nouns.get(0) : null;
                    }
                }
                conv = spell != null || summon != null ? c : null;
            }
        }
        if (main <= 0 && !anyElement && conv == null) {
            return null;
        }
        return new Bonus(spell, summon, conv, main, element);
    }

    /** The "( Earth: +20%)" parts of a damage block as fractions, neutral first. */
    private static double[] parts(String block) {
        double[] c = new double[Damage.ELEMENTS.length];
        Matcher p = PART.matcher(block);
        while (p.find()) {
            c[index(p.group(1))] += Integer.parseInt(p.group(2)) / 100.0;
        }
        return c;
    }

    private static int index(String word) {
        String el = word.equals("Damage") ? "neutral" : word.toLowerCase(Locale.ROOT);
        return List.of(Damage.ELEMENTS).indexOf(el);
    }

    /** The spells named in {@code t} as whole words, in the case the game writes them. */
    private static List<String> mentioned(String t, List<String> spells) {
        List<String> out = new ArrayList<>();
        for (String s : spells) {
            if (Pattern.compile("\\b" + Pattern.quote(s) + "\\b").matcher(t).find()) {
                out.add(s);
            }
        }
        return out;
    }

    private static String named(List<String> spells, String name) {
        for (String s : spells) {
            if (s.equalsIgnoreCase(name)) {
                return s;
            }
        }
        return null;
    }

    private static boolean containsIgnoreCase(List<String> list, String name) {
        return named(list, name) != null;
    }

    /**
     * The bonuses of the nodes in {@code have} added up: spell name → conversion per hit, summon
     * noun → conversion per hit, main attack %, element %.
     */
    record Sum(Map<String, double[]> spells, Map<String, double[]> summons, double mainAttack, double[] element) {

        static final Sum NONE = new Sum(Map.of(), Map.of(), 0, new double[Damage.ELEMENTS.length]);

        static Sum of(Map<String, Bonus> bonuses, Collection<String> have) {
            if (bonuses.isEmpty()) {
                return NONE;
            }
            Map<String, double[]> spells = new TreeMap<>();
            Map<String, double[]> summons = new TreeMap<>();
            double main = 0;
            double[] element = new double[Damage.ELEMENTS.length];
            for (String name : have) {
                Bonus b = bonuses.get(name);
                if (b == null) {
                    continue;
                }
                main += b.mainAttack();
                for (int i = 0; i < element.length; i++) {
                    element[i] += b.element()[i];
                }
                if (b.conv() != null) {
                    double[] into = b.spell() != null ? spells.computeIfAbsent(b.spell(), k -> new double[element.length])
                            : summons.computeIfAbsent(b.summon(), k -> new double[element.length]);
                    for (int i = 0; i < into.length; i++) {
                        into[i] += b.conv()[i];
                    }
                }
            }
            return new Sum(spells, summons, main, element);
        }

        /**
         * A rules.model.spells entry ({@code s}, a copy) with this spell's bonuses in its conversion:
         * per hit, as the spell's own text is, so times the hits its conversion has.
         */
        void raise(String spell, JsonObject s) {
            double[] add = spells.get(spell);
            if (add == null || !s.has("conv") || !s.get("conv").isJsonObject()) {
                return;
            }
            double hits = s.has("hits") ? s.get("hits").getAsDouble() : 1;
            JsonObject conv = s.getAsJsonObject("conv");
            for (int i = 0; i < add.length; i++) {
                if (add[i] != 0) {
                    String el = Damage.ELEMENTS[i];
                    double was = conv.has(el) ? conv.get(el).getAsDouble() : 0;
                    conv.addProperty(el, Math.round((was + add[i] * hits) * 10000) / 10000.0);
                }
            }
        }

        /** The main attack and element % bonuses into a build's stats ({@code s}, as Damage sums them). */
        void addStats(Map<String, Double> s) {
            if (mainAttack != 0) {
                s.merge("mainAttackDamage", mainAttack, Double::sum);
            }
            for (int i = 1; i < element.length; i++) {
                if (element[i] != 0) {
                    s.merge(Damage.ELEMENTS[i] + "Damage", element[i], Double::sum);
                }
            }
        }

        boolean isEmpty() {
            if (mainAttack != 0 || !spells.isEmpty() || !summons.isEmpty()) {
                return false;
            }
            for (double e : element) {
                if (e != 0) {
                    return false;
                }
            }
            return true;
        }

        /** What the sum is, for cache keys. */
        String key() {
            StringBuilder k = new StringBuilder();
            k.append(Math.round(mainAttack * 100));
            for (double e : element) {
                k.append(',').append(Math.round(e * 100));
            }
            for (var m : List.of(spells, summons)) {
                k.append('|');
                m.forEach((n, c) -> {
                    k.append(n).append(':');
                    for (double v : c) {
                        k.append(Math.round(v * 10000)).append(',');
                    }
                });
            }
            return k.toString();
        }
    }
}
