package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import io.github.anilken.wynnlabs.model.AbilityNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the Ability Tree tab shows about each node: its state, its place in the plan, its
 * archetype's colour and whether it matches the search. Pure: the screen only draws this.
 */
final class TreeView {

    /** A node's state, the first that applies. */
    enum State { UNLOCKED, NEXT, PLANNED, BLOCKED, AVAILABLE, LOCKED }

    /** {@code step}: the node's place in the plan from 1, or 0; {@code colour}: ARGB. */
    record Node(AbilityNode node, State state, int step, int colour, boolean matches) {

        boolean ultimate() {
            return node.type() != null && node.type().contains("ULTIMATE");
        }

        /** The Wynntils node state the game draws it with. */
        String iconState() {
            return switch (state) {
                case UNLOCKED -> "UNLOCKED";
                case NEXT, AVAILABLE -> "UNLOCKABLE";
                case BLOCKED -> "BLOCKED";
                default -> "LOCKED";
            };
        }
    }

    /** An archetype's points: nodes of it unlocked now, and after the plan. */
    record Archetype(String name, int colour, int now, int after) {}

    /**
     * The whole view. {@code spent}: points in unlocked nodes; {@code total}: points earned at
     * the level; {@code afterPlan}: spent plus the plan's; {@code next}: the plan's first node
     * still locked, or null; {@code nextLevel}: the level the plan takes it at.
     */
    record View(List<Node> nodes, List<Archetype> archetypes, int spent, int total, int afterPlan,
            AbilityNode next, int nextLevel) {

        Node byName(String name) {
            for (Node n : nodes) {
                if (n.node().name().equals(name)) {
                    return n;
                }
            }
            return null;
        }
    }

    /** Colours for archetypes whose node data carries none, in order of first appearance. */
    static final int[] PALETTE = {0xFF7F77DD, 0xFF1D9E75, 0xFFD85A30, 0xFFD4537E, 0xFF378ADD, 0xFFEF9F27};

    private TreeView() {}

    /**
     * The view of a tree: {@code unlocked} the nodes the character has, {@code plan} the
     * whole plan from the first node ({@link TreePlanner#wholePlan}: a step keeps its number
     * once taken), {@code total} the points earned at the level, {@code query} the search
     * text ("" matches everything).
     */
    static View build(List<AbilityNode> tree, Collection<String> unlocked, List<TreePlanner.Step> plan, int total,
            String query) {
        Set<String> have = new HashSet<>(unlocked);
        Map<Integer, AbilityNode> byId = new HashMap<>();
        Map<String, AbilityNode> byName = new HashMap<>();
        for (AbilityNode n : tree) {
            byId.put(n.id(), n);
            byName.put(n.name(), n);
        }
        // the plan's order by name, taken steps included; the next pick is the first still locked
        Map<String, Integer> step = new LinkedHashMap<>();
        AbilityNode next = null;
        int nextLevel = 0;
        int planCost = 0;
        int i = 0;
        for (TreePlanner.Step s : plan) {
            step.put(s.name(), ++i);
            if (have.contains(s.name())) {
                continue;
            }
            planCost += s.cost();
            if (next == null) {
                next = byName.get(s.name());
                nextLevel = s.level();
            }
        }
        // what an unlocked or planned node shuts
        Set<String> blocked = new HashSet<>();
        for (AbilityNode n : tree) {
            if (have.contains(n.name()) || step.containsKey(n.name())) {
                blocked.addAll(n.willBlock());
                for (AbilityNode other : tree) {
                    if (other.willBlock().contains(n.name())) {
                        blocked.add(other.name());
                    }
                }
            }
        }
        // archetype colours and points
        Map<String, int[]> points = new LinkedHashMap<>();
        Map<String, Integer> colours = new LinkedHashMap<>();
        for (AbilityNode n : tree) {
            String a = n.archetype();
            if (a == null || a.isBlank()) {
                continue;
            }
            if (!colours.containsKey(a)) {
                colours.put(a, colour(n.colour(), colours.size()));
                points.put(a, new int[2]);
            }
            if (have.contains(n.name())) {
                points.get(a)[0]++;
                points.get(a)[1]++;
            } else if (step.containsKey(n.name())) {
                points.get(a)[1]++;
            }
        }
        int spent = 0;
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Node> nodes = new ArrayList<>(tree.size());
        AbilityNode first = tree.isEmpty() ? null : tree.get(0);
        for (AbilityNode n : tree) {
            State state;
            if (have.contains(n.name())) {
                state = State.UNLOCKED;
                spent += n.cost();
            } else if (next != null && n.name().equals(next.name())) {
                state = State.NEXT;
            } else if (step.containsKey(n.name())) {
                state = State.PLANNED;
            } else if (blocked.contains(n.name())) {
                state = State.BLOCKED;
            } else if (available(n, have, byId, byName, first)) {
                state = State.AVAILABLE;
            } else {
                state = State.LOCKED;
            }
            int colour = n.archetype() == null || !colours.containsKey(n.archetype()) ? 0xFFB4B2A9 : colours.get(n.archetype());
            boolean matches = q.isEmpty() || n.name().toLowerCase(Locale.ROOT).contains(q) || text(n).contains(q);
            nodes.add(new Node(n, state, step.getOrDefault(n.name(), 0), colour, matches));
        }
        List<Archetype> archetypes = new ArrayList<>();
        for (var e : points.entrySet()) {
            archetypes.add(new Archetype(e.getKey(), colours.get(e.getKey()), e.getValue()[0], e.getValue()[1]));
        }
        return new View(nodes, archetypes, spent, total, spent + planCost, next, nextLevel);
    }

    /** A node you could take now: next to an unlocked one, its requirements met, nothing in the way. */
    private static boolean available(AbilityNode n, Set<String> have, Map<Integer, AbilityNode> byId,
            Map<String, AbilityNode> byName, AbilityNode first) {
        if (have.isEmpty()) {
            return first != null && n.id() == first.id();
        }
        boolean touches = false;
        for (int id : n.connections()) {
            AbilityNode nb = byId.get(id);
            if (nb != null && have.contains(nb.name())) {
                touches = true;
                break;
            }
        }
        if (!touches) {
            for (AbilityNode other : byId.values()) {
                if (have.contains(other.name()) && other.connections().contains(n.id())) {
                    touches = true;
                    break;
                }
            }
        }
        if (!touches) {
            return false;
        }
        String req = n.requiredAbility();
        if (req != null && !req.isBlank() && !req.equals("None") && !have.contains(req)) {
            return false;
        }
        if (n.requiredArchetype() != null && !n.requiredArchetype().isBlank()) {
            long got = have.stream().map(byName::get)
                    .filter(t -> t != null && n.requiredArchetype().equals(t.archetype())).count();
            if (got < n.requiredArchetypePoints()) {
                return false;
            }
        }
        return true;
    }

    /** "#RRGGBB" as opaque ARGB, or the palette's colour for the archetype's index. */
    static int colour(String hex, int index) {
        if (hex != null) {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            if (h.length() == 8) {
                h = h.substring(0, 6);
            }
            try {
                return 0xFF000000 | Integer.parseInt(h, 16);
            } catch (NumberFormatException ignored) {
                // the palette then
            }
        }
        return PALETTE[Math.floorMod(index, PALETTE.length)];
    }

    /** A description line as plain text. */
    static String plain(JsonArray line) {
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : line) {
            if (e.isJsonObject() && e.getAsJsonObject().has("text")) {
                sb.append(e.getAsJsonObject().get("text").getAsString());
            } else if (e.isJsonPrimitive()) {
                sb.append(e.getAsString());
            }
        }
        return sb.toString();
    }

    /** The whole description, lower case, for the search. */
    static String text(AbilityNode n) {
        StringBuilder sb = new StringBuilder();
        if (n.description() != null) {
            for (JsonArray line : n.description()) {
                sb.append(plain(line)).append(' ');
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
