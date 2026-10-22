package io.github.anilken.wynnlabs.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.advice.Advice;
import io.github.anilken.wynnlabs.advice.Verdict;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Profile;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.game.Wyn;
import io.github.anilken.wynnlabs.model.AbilityNode;
import io.github.anilken.wynnlabs.model.Klass;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the ability tree plan by itself, no assistant needed: pick an archetype (rules.tree.
 * archetype, or the settings tab), and it lays out a legal 50-point path from what you already
 * have, then writes AL / SONRA AL ("Lv. N") / ALMA on the nodes, which the tree shows and glows.
 *
 * <p>Greedy with look-ahead: every step it takes the legal node with the best value per point,
 * where value favours the chosen archetype, the class spells, cheaper-spell and healing nodes,
 * and anything on the shortest way to the archetype's ultimate and its next archetype nodes.
 * Legal means: next to a taken node, required ability and archetype count met, not blocked.
 */
public final class TreePlanner {

    public record Step(String name, int cost, int level, String archetype) {}

    private TreePlanner() {}

    /**
     * Below this level a character with no archetype node leans to none yet (its main attack
     * carries it). A method, not a field: a field added by a hot swap keeps no value.
     */
    static int leanLevel() {
        return 10;
    }

    /**
     * The archetype with the most nodes you have unlocked; "" when the tree is unknown, and below
     * Lv {@link #leanLevel} while no archetype node is unlocked (a Lv 3 character leans to none:
     * the tree's first archetype was no lean, only the tree's order). From that level on with
     * none unlocked (Wynntils has not read them yet), the tree's first.
     */
    static String mostUnlocked() {
        List<String> archs = archetypes();
        if (archs.isEmpty()) {
            return "";
        }
        java.util.Map<String, Integer> count = new java.util.HashMap<>();
        Set<String> have = new HashSet<>(GameState.current().unlockedAbilities());
        for (AbilityNode n : nodes()) {
            String a = arch(n);
            if (!a.isEmpty() && have.contains(n.name())) {
                count.merge(a, 1, Integer::sum);
            }
        }
        if (count.isEmpty() && Wyn.level() < leanLevel()) {
            return "";
        }
        String best = archs.get(0);
        for (String a : archs) {
            if (count.getOrDefault(a, 0) > count.getOrDefault(best, 0)) {
                best = a;
            }
        }
        return best;
    }

    /** Archetypes of the current class's tree, e.g. Summoner, Ritualist, Acolyte. */
    static List<String> archetypes() {
        Set<String> out = new LinkedHashSet<>();
        for (AbilityNode n : nodes()) {
            String a = arch(n);
            if (!a.isEmpty()) {
                out.add(a);
            }
        }
        return new ArrayList<>(out);
    }

    /** The class's tree: the game state's, else Wynntils' own ({@link TreeModel#tree}); empty when unknown. */
    private static List<AbilityNode> nodes() {
        var tree = TreeModel.tree();
        return tree == null ? List.of() : tree;
    }

    private static String arch(AbilityNode n) {
        return n.archetype() == null ? "" : n.archetype();
    }

    private static boolean ultimate(AbilityNode n) {
        return n.type() != null && n.type().contains("ULTIMATE");
    }

    private static boolean spell(AbilityNode n) {
        Klass cls = Wyn.classType();
        return n.type() != null && cls != null
                && n.type().startsWith(cls.name() + "_ABILITY");
    }

    private static String text(AbilityNode n) {
        StringBuilder sb = new StringBuilder();
        if (n.description() != null) {
            for (JsonArray line : n.description()) {
                line.forEach(e -> {
                    if (e.isJsonObject() && e.getAsJsonObject().has("text")) {
                        sb.append(e.getAsJsonObject().get("text").getAsString());
                    }
                });
                sb.append(' ');
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** How much a node is worth on its own for the chosen archetype. */
    private static double value(AbilityNode n, String chosen) {
        String a = arch(n);
        String d = text(n);
        String name = n.name().toLowerCase(Locale.ROOT);
        double v = 1;
        if (spell(n)) {
            v += 6;                             // the class's spells come first
        }
        if (a.equalsIgnoreCase(chosen)) {
            v += 3;
        } else if (!a.isEmpty()) {
            v -= 1.5;                           // other archetypes only when on the way
        }
        if (ultimate(n)) {
            v += a.equalsIgnoreCase(chosen) ? 8 : -10;
        }
        if (name.startsWith("cheaper") || d.contains("mana cost: -")) {
            v += 2;                             // mana comfort
        }
        if (d.contains("heal") || d.contains("resistance") || d.contains("health")) {
            v += 1.5;                           // staying alive
        }
        if (d.contains("total damage") || d.contains("damage bonus")) {
            v += 1;
        }
        if (d.contains("overall damage by +")) {
            v += 6;                             // e.g. Hand of the Shaman: +70 % main attack
        }
        if (d.contains("walk speed") || d.contains("launch you") || d.contains("leap")) {
            v += 0.5;
        }
        if (name.contains("mastery")) {
            v += 0.5;
        }
        return v;
    }

    /** The plan from what you have now; empty when the tree is unknown. */
    static List<Step> plan(String chosen) {
        return plan(chosen, GameState.current().unlockedAbilities());
    }

    /**
     * The whole plan from the first node, so a step keeps its number once taken; empty when
     * the tree is unknown. The steps' levels count the points from level 1.
     */
    public static List<Step> wholePlan(String chosen) {
        return plan(chosen, List.of());
    }

    /** The plan from the given unlocked nodes on. */
    static List<Step> plan(String chosen, java.util.Collection<String> have) {
        List<AbilityNode> all = nodes();
        if (all.isEmpty()) {
            return List.of();
        }
        Map<Integer, AbilityNode> byId = new HashMap<>();
        Map<String, AbilityNode> byName = new HashMap<>();
        Map<Integer, Set<Integer>> adj = new HashMap<>();
        for (AbilityNode n : all) {
            byId.put(n.id(), n);
            byName.put(n.name(), n);
            adj.computeIfAbsent(n.id(), k -> new HashSet<>());
        }
        for (AbilityNode n : all) {
            for (int c : n.connections()) {
                adj.computeIfAbsent(n.id(), k -> new HashSet<>()).add(c);
                adj.computeIfAbsent(c, k -> new HashSet<>()).add(n.id());
            }
        }
        Set<Integer> taken = new LinkedHashSet<>();
        int spent = 0;
        for (String name : have) {
            AbilityNode n = byName.get(name);
            if (n != null) {
                taken.add(n.id());
                spent += n.cost();
            }
        }
        int level = Wyn.level();
        int maxPoints = 50;
        // Rank bonus: points you have now beyond the level table
        int bonus = Math.max(0, Wyn.abilityPointsAt(level) - basePoints(level));

        // Targets that pull the path: the chosen archetype's nodes and its ultimate
        Map<Integer, Double> value = new HashMap<>();
        for (AbilityNode n : all) {
            value.put(n.id(), value(n, chosen));
        }

        // distance of every node to the chosen archetype's ultimate, to steer the path there
        Map<Integer, Integer> toUlt = new HashMap<>();
        all.stream().filter(n -> ultimate(n) && arch(n).equalsIgnoreCase(chosen)).findFirst().ifPresent(u -> {
            toUlt.putAll(distances(Set.of(u.id()), adj));
        });

        List<Step> out = new ArrayList<>();
        while (spent < maxPoints) {
            AbilityNode best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            Map<Integer, Integer> dist = distances(taken, adj);
            for (AbilityNode n : all) {
                if (taken.contains(n.id()) || spent + n.cost() > maxPoints
                        || !legal(n, taken, byId, byName, adj, all.get(0).id())) {
                    continue;
                }
                // not before the level it asks for (an ultimate at 90): the points to buy it come at a level
                if (n.requiredLevel() > Math.max(level, levelFor(spent + n.cost() - bonus))) {
                    continue;
                }
                // look ahead: worth of good nodes this one brings closer
                double ahead = 0;
                for (int nb : adj.getOrDefault(n.id(), Set.of())) {
                    if (!taken.contains(nb) && dist.getOrDefault(nb, 99) > 1) {
                        ahead = Math.max(ahead, value.get(nb) * 0.5);
                    }
                }
                double path = toUlt.containsKey(n.id()) ? 3.0 / (1 + toUlt.get(n.id())) : 0;
                double sc = (value.get(n.id()) + ahead + path) / n.cost();
                if (ultimate(n) && arch(n).equalsIgnoreCase(chosen)) {
                    sc += 100;                  // take the ultimate as soon as it is allowed
                }
                if (sc > bestScore) {
                    bestScore = sc;
                    best = n;
                }
            }
            if (best == null || bestScore <= 0.2) {
                break;
            }
            taken.add(best.id());
            spent += best.cost();
            int lv = Math.max(levelFor(spent - bonus), best.requiredLevel());
            out.add(new Step(best.name(), best.cost(), lv, arch(best)));
        }
        return out;
    }

    private static boolean legal(AbilityNode n, Set<Integer> taken, Map<Integer, AbilityNode> byId,
            Map<String, AbilityNode> byName, Map<Integer, Set<Integer>> adj, int firstId) {
        if (taken.isEmpty()) {
            return n.id() == firstId;           // every tree starts at its first spell
        }
        boolean touches = false;
        for (int nb : adj.getOrDefault(n.id(), Set.of())) {
            if (taken.contains(nb)) {
                touches = true;
                break;
            }
        }
        if (!touches) {
            return false;
        }
        String req = n.requiredAbility();
        if (req != null && !req.isBlank() && !req.equals("None")) {
            AbilityNode r = byName.get(req);
            if (r == null || !taken.contains(r.id())) {
                return false;
            }
        }
        if (n.requiredArchetype() != null
                && !n.requiredArchetype().isBlank()) {
            long have = taken.stream().map(byId::get).filter(t -> arch(t).equals(n.requiredArchetype())).count();
            if (have < n.requiredArchetypePoints()) {
                return false;
            }
        }
        for (int id : taken) {
            AbilityNode t = byId.get(id);
            if (t.willBlock().contains(n.name()) || n.willBlock().contains(t.name())) {
                return false;
            }
        }
        return true;
    }

    private static Map<Integer, Integer> distances(Set<Integer> from, Map<Integer, Set<Integer>> adj) {
        Map<Integer, Integer> d = new HashMap<>();
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        for (int f : from) {
            d.put(f, 0);
            q.add(f);
        }
        while (!q.isEmpty()) {
            int x = q.poll();
            for (int nb : adj.getOrDefault(x, Set.of())) {
                if (!d.containsKey(nb)) {
                    d.put(nb, d.get(x) + 1);
                    q.add(nb);
                }
            }
        }
        return d;
    }

    private static final int[] POINT_LEVELS = {1, 2, 4, 6, 8, 10, 12, 13, 15, 17, 18, 20, 22, 23, 24, 26, 28, 30,
        32, 34, 37, 39, 41, 44, 46, 48, 50, 52, 54, 56, 58, 60, 62, 64, 67, 70, 73, 76, 80, 84, 88, 92, 96, 100,
        104, 107, 110, 113, 116, 120};

    /** Ability points the level table alone gives (no rank bonus). */
    public static int basePoints(int level) {
        int n = 0;
        for (int lv : POINT_LEVELS) {
            if (lv <= level) {
                n++;
            }
        }
        return n;
    }

    private static int levelFor(int points) {
        if (points <= 0) {
            return 1;
        }
        return points <= POINT_LEVELS.length ? POINT_LEVELS[points - 1] : 121;
    }

    /**
     * Writes the whole plan onto the tree as advice, numbered from its first node so a step
     * keeps its number once taken: ALINDI on the steps you have, AL on what you can take now,
     * SONRA AL with the level for later, ALMA for nodes that would block planned ones. Returns
     * the plan.
     */
    static List<Step> apply(String chosen) {
        List<Step> plan = wholePlan(chosen);
        if (plan.isEmpty()) {
            return plan;
        }
        int level = Wyn.level();
        JsonObject body = new JsonObject();
        JsonArray items = new JsonArray();
        Set<String> planned = new HashSet<>();
        plan.forEach(s -> planned.add(s.name()));
        Set<String> have = new HashSet<>(GameState.current().unlockedAbilities());
        for (AbilityNode n : nodes()) {
            JsonObject e = new JsonObject();
            e.addProperty("key", "ability:" + n.name());
            boolean blocksPlan = n.willBlock().stream().anyMatch(planned::contains);
            if (!planned.contains(n.name()) && blocksPlan && !have.contains(n.name())) {
                e.addProperty("verdict", Verdict.SKIP.name());
                e.addProperty("note", L.t("wynnlabs.tree.blocks_plan", n.willBlock().stream().filter(planned::contains)
                        .findFirst().orElse("")));
            } else {
                e.addProperty("verdict", "");   // cleared; planned ones are written below
            }
            items.add(e);
        }
        int i = 0;
        for (Step s : plan) {
            i++;
            JsonObject e = new JsonObject();
            e.addProperty("key", "ability:" + s.name());
            e.addProperty("verdict", (have.contains(s.name()) ? Verdict.TAKEN
                    : s.level() <= level ? Verdict.BUY : Verdict.BUY_LATER).name());
            // "Lv. N" is read back by Advice.promoteAbilities/abilityPlan: kept as is in every language
            e.addProperty("note", L.t("wynnlabs.tree.plan_note", i, plan.size(), s.level(), chosen));
            items.add(e);
        }
        body.add("items", items);
        Advice.merge(body);
        // another class's plan may be left in the file (old recordings, the assistant): it goes
        java.util.Set<String> names = new HashSet<>();
        nodes().forEach(n -> names.add(n.name()));
        Advice.dropAbilitiesExcept(names);
        return plan;
    }

    private static String applied = "";
    private static int tick;

    /** rules.tree.archetype, or "" when the planner is off. */
    public static String chosen() {
        return Rules.subText("tree", "archetype", "");
    }

    /** Every 5 s: re-plan when the archetype, your unlocked nodes or your level changed. */
    public static void onTick(net.minecraft.client.Minecraft mc) {
        if (++tick % 100 != 0 || mc.player == null || !Settings.on("autoTree")) {
            return;
        }
        try {
            String ch = chosen();
            if (ch.isEmpty()) {
                return;
            }
            // the plan's notes are saved worded (advice.json): a language switch writes them again
            String key = Profile.id() + "|" + ch + "|" + Wyn.level() + "|"
                    + new java.util.TreeSet<>(GameState.current().unlockedAbilities()) + "|" + L.code();
            if (!key.equals(applied)) {
                apply(ch);
                applied = key;
            }
        } catch (Throwable t) {
            System.out.println("[WynnLabs] tree plan failed: " + t);
        }
    }

    /** Next archetype in the list (the settings button cycles: off, A, B, C). */
    public static void cycle() {
        List<String> archs = archetypes();
        String now = chosen();
        int i = archs.indexOf(now);
        String next = i + 1 < archs.size() ? archs.get(i + 1) : "";
        JsonObject tree = new JsonObject();
        tree.addProperty("archetype", next);
        tree.add("guess", com.google.gson.JsonNull.INSTANCE);  // your choice: never guessed again
        JsonObject change = new JsonObject();
        change.add("tree", tree);
        Rules.update(change);
        applied = "";
        if (!next.isEmpty()) {
            ClassProfiles.apply(next, false);   // elements, powders, summons follow the archetype
        }
    }

    public static JsonObject json(String chosen) {
        JsonObject o = new JsonObject();
        o.addProperty("archetype", chosen);
        JsonArray archs = new JsonArray();
        archetypes().forEach(archs::add);
        o.add("archetypes", archs);
        JsonArray arr = new JsonArray();
        for (Step s : plan(chosen)) {
            JsonObject j = new JsonObject();
            j.addProperty("name", s.name());
            j.addProperty("cost", s.cost());
            j.addProperty("level", s.level());
            j.addProperty("archetype", s.archetype());
            arr.add(j);
        }
        o.add("plan", arr);
        return o;
    }
}
