package io.github.anilken.wynnlabs.build;

import io.github.anilken.wynnlabs.model.AbilityNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ability tree's text read for {@link TreeModel}: what each node does ({@link TreeModel.Read})
 * and the overlay a set of taken nodes makes of it.
 */
final class TreeReading {

    private static final Pattern COST_CHANGE = Pattern.compile("mana cost: ([+-]\\d+)");
    private static final Pattern DAMAGE = Pattern.compile("total damage: (\\d+)% \\(of your dps");
    private static final Pattern EVERY = Pattern.compile("every (\\d+(?:\\.\\d+)?)s");
    private static final Pattern MAX = Pattern.compile("\\(max (\\d+)");
    private static final Pattern SUMMON = Pattern.compile("summons? (a|an|\\d+) (\\w+)");
    private static final Pattern MORE = Pattern.compile("maximum (\\w+) by \\+(\\d+)");
    private static final Pattern MANA = Pattern.compile("(\\d+(?:\\.\\d+)?) mana per second");

    /** Hits per second a summon is counted with at most (a Hound's 0.25 s). */
    private static final double MAX_RATE = 4;
    /** Share of the time an area or a bleed that is not a summon is counted as hitting. */
    private static final double AREA_UPTIME = 0.5;

    private TreeReading() {}

    /** Singular, lower case: "Puppets" → "puppet". */
    private static String noun(String w) {
        String n = w.toLowerCase(Locale.ROOT);
        return n.endsWith("s") && n.length() > 3 ? n.substring(0, n.length() - 1) : n;
    }

    /** Reads every node of the tree for the class's spells ({@code spellNames}, as rules.model.spells has them). */
    static List<TreeModel.Read> read(List<AbilityNode> tree, String cls, Collection<String> spellNames) {
        List<String> spells = new ArrayList<>();
        spellNames.forEach(s -> spells.add(s.toLowerCase(Locale.ROOT)));
        // a node that names no spell may name another node that does: Mana Trap's "Traps" are
        // Basaltic Trap's, which comes from Arrow Bomb. Last words of such nodes, first one wins
        Map<String, String> viaNode = new TreeMap<>();
        for (AbilityNode n : tree) {
            if (spells.contains(n.name().toLowerCase(Locale.ROOT))) {
                continue;
            }
            String s = firstSpell(TreeModel.text(n).toLowerCase(Locale.ROOT), spells, Map.of());
            String[] words = n.name().split(" ");
            String last = noun(words[words.length - 1]);
            if (s != null && last.length() >= 4 && !spells.contains(last)) {
                viaNode.putIfAbsent(last, s);
            }
        }
        List<TreeModel.Read> out = new ArrayList<>();
        for (AbilityNode n : tree) {
            String t = TreeModel.text(n);
            String low = t.toLowerCase(Locale.ROOT);
            // a spell's own damage is never passive here: the Totem's ticks are its rules.model.spells
            // entry's (a lasting spell, counted with the summons there), so they are counted once
            boolean spell = TreeModel.spellNode(n, cls) || spells.contains(n.name().toLowerCase(Locale.ROOT));
            boolean ultimate = n.type() != null && n.type().contains("ULTIMATE");
            String costSpell = null;
            double costDelta = 0;
            Matcher cm = COST_CHANGE.matcher(low);
            if (!spell && cm.find()) {
                costSpell = firstSpell(low, spells, viaNode);
                costDelta = Integer.parseInt(cm.group(1).replace("+", ""));
            }
            double[] conv = null;
            double rate = 0;
            int count = 1;
            String noun = null;
            boolean summon = false;
            Matcher dm = DAMAGE.matcher(low);
            Matcher every = EVERY.matcher(low);
            if (!spell && !ultimate && dm.find()) {
                // hits on its own clock: the shortest "every" (a Puppet comes every 3 s, throws every 0.5 s)
                double interval = Double.MAX_VALUE;
                while (every.find()) {
                    interval = Math.min(interval, Double.parseDouble(every.group(1)));
                }
                if (interval < Double.MAX_VALUE && interval > 0) {
                    // the first damage block only (a second one is another part, an explosion)
                    int second = low.indexOf("total damage", dm.end());
                    String block = t.substring(dm.start(), second < 0 ? t.length() : second);
                    conv = new double[Damage.ELEMENTS.length];
                    Matcher p = ClassProfiles.PART.matcher(block);
                    while (p.find()) {
                        String el = p.group(1).equals("Damage") ? "neutral" : p.group(1).toLowerCase(Locale.ROOT);
                        int i = List.of(Damage.ELEMENTS).indexOf(el);
                        if (i >= 0) {
                            conv[i] += Integer.parseInt(p.group(2)) / 100.0;
                        }
                    }
                    rate = Math.min(MAX_RATE, 1 / interval);
                    // how many: "(Max 3)", else "summon 2 …", else one
                    Matcher sm = SUMMON.matcher(low);
                    summon = sm.find();
                    noun = summon ? noun(sm.group(2)) : null;
                    Matcher mx = MAX.matcher(low);
                    count = mx.find() ? Integer.parseInt(mx.group(1))
                            : summon && Character.isDigit(sm.group(1).charAt(0)) ? Integer.parseInt(sm.group(1)) : 1;
                }
            }
            Map<String, Integer> more = new TreeMap<>();
            Matcher mm = MORE.matcher(low);
            while (mm.find()) {
                more.merge(noun(mm.group(1)), Integer.parseInt(mm.group(2)), Integer::sum);
            }
            double mana = 0;
            Matcher mp = MANA.matcher(low);
            if (mp.find()) {
                mana = Double.parseDouble(mp.group(1));
            }
            out.add(new TreeModel.Read(n.name(), n.archetype() == null ? "" : n.archetype(), costSpell, costDelta, conv, rate,
                    count, noun, summon, more, mana));
        }
        return out;
    }

    /** The spell named first in {@code low} (or named through another node's last word); null when none. */
    private static String firstSpell(String low, List<String> spells, Map<String, String> viaNode) {
        String best = null;
        int at = Integer.MAX_VALUE;
        for (String s : spells) {
            int i = low.indexOf(s);
            if (i >= 0 && i < at) {
                at = i;
                best = s;
            }
        }
        if (best == null) {
            for (var e : viaNode.entrySet()) {
                int i = low.indexOf(e.getKey());
                if (i >= 0 && i < at) {
                    at = i;
                    best = e.getValue();
                }
            }
        }
        return best;
    }

    /** The overlay for the nodes in {@code taken} ({@link TreeModel#overlay}), with the tree's passive damage bonuses. */
    static TreeModel.Overlay overlay(int level, List<TreeModel.Read> reads, Collection<String> spellNames, Collection<String> taken,
            double mainAttack, double passiveCap, Map<String, TreeBonuses.Bonus> bonuses) {
        TreeBonuses.Sum bonus = TreeBonuses.Sum.of(bonuses, taken);
        Set<String> have = new HashSet<>(taken);
        Map<String, String> byLow = new TreeMap<>();
        spellNames.forEach(s -> byLow.put(s.toLowerCase(Locale.ROOT), s));
        Set<String> known = new LinkedHashSet<>();
        Set<String> unlocked = new LinkedHashSet<>();
        for (TreeModel.Read r : reads) {
            if (byLow.containsKey(r.name().toLowerCase(Locale.ROOT))) {
                known.add(r.name());
                if (have.contains(r.name())) {
                    unlocked.add(r.name());
                }
            }
        }
        Map<String, Double> cost = new TreeMap<>();
        Map<String, Integer> more = new TreeMap<>();
        double mana = 0;
        for (TreeModel.Read r : reads) {
            if (!have.contains(r.name())) {
                continue;
            }
            if (r.costSpell() != null && byLow.containsKey(r.costSpell())) {
                cost.merge(byLow.get(r.costSpell()), r.costDelta(), Double::sum);
            }
            r.more().forEach((k, v) -> more.merge(k, v, Integer::sum));
            mana += r.manaPerSecond();
        }
        double[] passive = new double[Damage.ELEMENTS.length];
        for (TreeModel.Read r : reads) {
            if (!have.contains(r.name()) || r.conv() == null) {
                continue;
            }
            int count = r.count() + (r.noun() == null ? 0 : more.getOrDefault(r.noun(), 0));
            double times = r.rate() * count * (r.summon() ? TreeModel.summonAlive() : AREA_UPTIME);
            // a node that makes this summon's hits stronger ("Your Puppet's knives become stronger")
            double[] add = r.summon() && r.noun() != null ? bonus.summons().get(r.noun()) : null;
            for (int i = 0; i < passive.length; i++) {
                passive[i] += (r.conv()[i] + (add == null ? 0 : add[i])) * times;
            }
        }
        // a few dozen puppets and traps do not hit a pack all at once: past the cap they scale down
        double total = 0;
        for (double p : passive) {
            total += p;
        }
        if (passiveCap > 0 && total > passiveCap) {
            for (int i = 0; i < passive.length; i++) {
                passive[i] *= passiveCap / total;
            }
        }
        for (int i = 0; i < passive.length; i++) {
            passive[i] = Math.round(passive[i] * 10000) / 10000.0;
        }
        return new TreeModel.Overlay(level, cost, known, unlocked, passive, mana * 5, mainAttack, bonus);
    }
}
