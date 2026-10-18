package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.AbilityNode;
import io.github.anilken.wynnlabs.model.AspectEntry;
import io.github.anilken.wynnlabs.model.RankedAspect;
import io.github.anilken.wynnlabs.model.Tier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The aspect ranking of design decision 7, as a pure function of plain values (testable without
 * the game). Score per named ability of an aspect below its top tier: chosen archetype and
 * unlocked 3, chosen archetype and planned 2, class spell or "Main Attack" (no archetype) 1,
 * another archetype but unlocked 1, else 0; summed per aspect; ties broken by rarity (mythic,
 * fabled, legendary), then missing before under-max. A mythic named "&lt;Archetype&gt;'s
 * Embodiment of …" also counts for that archetype.
 *
 * <p>API contract of group 1 (design.md, "API contract"): the signature below stays as it is;
 * group 4 fills the body and may add private helpers.
 */
final class AspectRank {

    private AspectRank() {}

    /** The ability every class has without a node in the tree. */
    static final String MAIN_ATTACK = "Main Attack";
    private static final String EMBODIMENT = "'s Embodiment of ";

    /**
     * Every aspect in display order: ranked unfinished ones first (rank 1, 2, 3 …), then
     * unfinished ones with score 0 by rarity, then maxed ones. While owned tiers are unknown
     * ({@code owned} null) the order is by build fit alone, every rank is 0 and every owned tier
     * -1.
     *
     * @param aspects   the class's aspects, with their ability names
     * @param owned     owned tier by aspect name (absent = not owned), or null when never read
     * @param equipped  names of the aspects this character has equipped
     * @param tree      the class's ability tree (each node's archetype), or null when unknown
     * @param unlocked  names of the unlocked abilities
     * @param planned   names of the abilities the plan takes
     * @param archetype the archetype to rank for (chosen, or the most unlocked), or null
     */
    static List<RankedAspect> rank(List<AspectEntry> aspects, Map<String, Integer> owned, Set<String> equipped,
            List<AbilityNode> tree, Set<String> unlocked, Set<String> planned, String archetype) {
        if (aspects == null || aspects.isEmpty()) {
            return List.of();
        }
        Map<String, AbilityNode> byName = new HashMap<>();
        Set<String> archetypes = new LinkedHashSet<>();
        if (tree != null) {
            for (AbilityNode n : tree) {
                if (n == null || n.name() == null) {
                    continue;
                }
                byName.putIfAbsent(key(n.name()), n);
                String a = blankToNull(n.archetype());
                if (a != null) {
                    archetypes.add(a);
                }
            }
        }
        Set<String> have = keys(unlocked);
        Set<String> plan = keys(planned);
        String chosen = blankToNull(archetype);

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < aspects.size(); i++) {
            AspectEntry a = aspects.get(i);
            String override = embodiment(a, archetypes);
            List<RankedAspect.Match> matches = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (String ability : a.abilities()) {
                RankedAspect.Match m = match(ability, a, byName, have, plan, chosen, override);
                if (m != null && seen.add(key(m.ability()))) {
                    matches.add(m);
                }
            }
            int score = 0;
            for (RankedAspect.Match m : matches) {
                score += m.points();
            }
            int tier = owned == null ? -1 : Math.max(0, owned.getOrDefault(a.name(), 0));
            rows.add(new Row(i, a, score, tier, isEquipped(a.name(), equipped), matches));
        }

        List<RankedAspect> out = new ArrayList<>();
        if (owned == null) {
            rows.sort(BY_FIT);
            for (Row r : rows) {
                out.add(r.result(0));
            }
            return out;
        }
        List<Row> ranked = new ArrayList<>();
        List<Row> loose = new ArrayList<>();
        List<Row> done = new ArrayList<>();
        for (Row r : rows) {
            boolean unfinished = r.tier < r.aspect.tiers();
            if (!unfinished) {
                done.add(r);
            } else if (r.score > 0) {
                ranked.add(r);
            } else {
                loose.add(r);
            }
        }
        ranked.sort(BY_NEED);
        loose.sort(BY_NEED);
        done.sort(BY_FIT);
        int n = 1;
        for (Row r : ranked) {
            out.add(r.result(n++));
        }
        for (Row r : loose) {
            out.add(r.result(0));
        }
        for (Row r : done) {
            out.add(r.result(0));
        }
        return out;
    }

    /**
     * The archetype with the most unlocked nodes in the tree; the tree's first archetype when
     * none is unlocked; null when the tree has none (same rule as {@link TreePlanner#mostUnlocked}).
     */
    static String mostUnlocked(List<AbilityNode> tree, Set<String> unlocked) {
        if (tree == null) {
            return null;
        }
        Set<String> have = keys(unlocked);
        Map<String, Integer> count = new LinkedHashMap<>();
        for (AbilityNode n : tree) {
            String a = n == null ? null : blankToNull(n.archetype());
            if (a == null) {
                continue;
            }
            count.merge(a, have.contains(key(n.name())) ? 1 : 0, Integer::sum);
        }
        String best = null;
        for (var e : count.entrySet()) {
            if (best == null || e.getValue() > count.get(best)) {
                best = e.getKey();
            }
        }
        return best;
    }

    // ---- scoring ----

    private static RankedAspect.Match match(String ability, AspectEntry a, Map<String, AbilityNode> byName,
            Set<String> have, Set<String> plan, String chosen, String override) {
        if (ability == null || ability.isBlank()) {
            return null;
        }
        if (key(ability).equals(key(MAIN_ATTACK))) {
            return new RankedAspect.Match(MAIN_ATTACK, null, false, false, 1);
        }
        AbilityNode node = byName.get(key(ability));
        if (node == null) {
            return null;                         // not an ability of this tree ("Ultimate", a typo)
        }
        String arch = override != null ? override : blankToNull(node.archetype());
        boolean unlocked = have.contains(key(node.name()));
        boolean planned = !unlocked && plan.contains(key(node.name()));
        boolean mine = chosen != null && arch != null && arch.equalsIgnoreCase(chosen);
        boolean spell = arch == null && spell(node, a);
        int points = 0;
        if (mine && unlocked) {
            points = 3;
        } else if (mine && planned) {
            points = 2;
        } else if (spell || (!mine && unlocked)) {
            points = 1;
        }
        return new RankedAspect.Match(node.name(), arch, unlocked, planned, points);
    }

    /** A class spell: the node type the tree gives the class's four spells ("SHAMAN_ABILITY_…"). */
    private static boolean spell(AbilityNode n, AspectEntry a) {
        return n.type() != null && a.klass() != null && n.type().startsWith(a.klass().name() + "_ABILITY");
    }

    /**
     * The archetype a mythic "&lt;Archetype&gt;'s Embodiment of …" names, as the tree spells it;
     * null for other aspects and for a prefix that is not an archetype ("Shaman's Embodiment").
     */
    private static String embodiment(AspectEntry a, Set<String> archetypes) {
        String name = a.name();
        int at = name == null ? -1 : name.indexOf(EMBODIMENT);
        if (at <= 0) {
            return null;
        }
        String prefix = name.substring(0, at).strip();
        for (String arch : archetypes) {
            if (arch.equalsIgnoreCase(prefix)) {
                return arch;
            }
        }
        if (archetypes.isEmpty() && (a.klass() == null || !a.klass().name().equalsIgnoreCase(prefix))) {
            return prefix;                       // tree unknown: trust the name unless it is the class
        }
        return null;
    }

    private static boolean isEquipped(String name, Set<String> equipped) {
        if (equipped == null || name == null) {
            return false;
        }
        for (String e : equipped) {
            if (e != null && e.endsWith(name)) {
                return true;
            }
        }
        return false;
    }

    // ---- order ----

    private static int rarity(Tier t) {
        return switch (t == null ? Tier.NORMAL : t) {
            case MYTHIC -> 0;
            case FABLED -> 1;
            case LEGENDARY -> 2;
            default -> 3;
        };
    }

    /** Build fit alone: score, then rarity, then the data's order. */
    private static final Comparator<Row> BY_FIT = Comparator.<Row>comparingInt(r -> -r.score)
            .thenComparingInt(r -> rarity(r.aspect.rarity()))
            .thenComparingInt(r -> r.index);

    /** For unfinished aspects: score, rarity, missing before under-max, the data's order. */
    private static final Comparator<Row> BY_NEED = Comparator.<Row>comparingInt(r -> -r.score)
            .thenComparingInt(r -> rarity(r.aspect.rarity()))
            .thenComparingInt(r -> r.tier == 0 ? 0 : 1)
            .thenComparingInt(r -> r.index);

    private record Row(int index, AspectEntry aspect, int score, int tier, boolean equipped,
            List<RankedAspect.Match> matches) {

        RankedAspect result(int rank) {
            return new RankedAspect(aspect, rank, score, tier, equipped, matches, reason(matches));
        }
    }

    // ---- reason ----

    /**
     * "boosts Malediction (Ritualist, unlocked), Totem (planned)": the matches that scored,
     * or every match when none did; "not tied to an ability" when the text names none.
     */
    static String reason(List<RankedAspect.Match> matches) {
        List<RankedAspect.Match> shown = new ArrayList<>();
        for (RankedAspect.Match m : matches) {
            if (m.points() > 0) {
                shown.add(m);
            }
        }
        if (shown.isEmpty()) {
            shown = matches;
        }
        if (shown.isEmpty()) {
            return L.t("wynnlabs.aspect.reason_none");
        }
        StringBuilder sb = new StringBuilder();
        for (RankedAspect.Match m : shown) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(m.ability());
            List<String> notes = new ArrayList<>();
            if (m.archetype() != null) {
                notes.add(m.archetype());
            }
            if (m.unlocked()) {
                notes.add(L.t("wynnlabs.aspect.unlocked"));
            } else if (m.planned()) {
                notes.add(L.t("wynnlabs.aspect.planned"));
            }
            if (!notes.isEmpty()) {
                sb.append(" (").append(String.join(", ", notes)).append(')');
            }
        }
        return L.t("wynnlabs.aspect.reason_boosts", sb.toString());
    }

    // ---- names ----

    /** Ability names compared without case, spaces or punctuation ("Mystics Masks" = "Mystic's Masks"). */
    static String key(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private static Set<String> keys(Set<String> names) {
        Set<String> out = new java.util.HashSet<>();
        if (names != null) {
            for (String s : names) {
                out.add(key(s));
            }
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
