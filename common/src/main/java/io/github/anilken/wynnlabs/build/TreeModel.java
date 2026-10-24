package io.github.anilken.wynnlabs.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.game.Wyn;
import io.github.anilken.wynnlabs.model.AbilityNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * The ability tree as the damage model sees it at a level: the nodes you have and the plan's
 * nodes up to that level ({@link TreePlanner}), read from their text. "Mana Cost: -10" on a
 * Cheaper node lowers the spell it names; a spell not bought yet gets no share; summons and
 * traps that hit on their own clock ("Total Damage: 40% (of your DPS)" … "every 0.25s") add
 * passive damage in their elements; "0.5 Mana per second" adds to the mana that comes back;
 * before the archetype's first node the main attack weighs as for no archetype; and with the
 * treeBonuses setting on, the passive damage bonuses ({@link TreeBonuses}: "Main Attack Damage:
 * +5%", "Earth Damage: +20%", "Total Damage: +4%" for a spell or a summon) raise what they name.
 *
 * <p>An {@link Overlay} is in force on a thread while a build or a road map milestone is worked
 * out ({@link #with}, like {@link Tuning#with}); {@link Damage}'s model reads it. A tree whose
 * text the model cannot read (no mana costs in it) gives no overlay: the rules as they are.
 */
public final class TreeModel {

    private TreeModel() {}

    // ---- the overlay in force -----------------------------------------------------------------

    /**
     * What is in force per thread, made on first use (a field set where it is declared stays null
     * after a hot swap): an {@link Overlay}, {@link Boolean#FALSE} for none on purpose (the rules
     * as they are), or nothing set: then the build plan's own ({@link Build.Plan#tree}).
     */
    private static volatile ThreadLocal<Object> slot;

    private static ThreadLocal<Object> slot() {
        ThreadLocal<Object> t = slot;
        if (t == null) {
            synchronized (TreeModel.class) {
                if (slot == null) {
                    slot = new ThreadLocal<>();
                }
                t = slot;
            }
        }
        return t;
    }

    /**
     * The overlay in force on this thread, or null (the rules as they are). With none set, the
     * tree the build plan was scored with: a verdict, a shop or bank pick that weighs a piece
     * against the plan scores both with the same summons, spells and costs, the ones the damage
     * meter measures against.
     */
    public static Overlay active() {
        ThreadLocal<Object> t = slot;
        Object o = t == null ? null : t.get();
        if (o instanceof Overlay ov) {
            return ov;
        }
        if (o != null) {
            return null;                            // none, on purpose
        }
        Build.Plan p = Build.plan();
        return p == null ? null : p.tree();
    }

    /** Runs {@code body} with {@code o} in force on this thread (null: none), the one before restored after. */
    public static <T> T with(Overlay o, Supplier<T> body) {
        ThreadLocal<Object> t = slot();
        Object before = t.get();
        use(o);
        try {
            return body.get();
        } finally {
            if (before == null) {
                t.remove();
            } else {
                t.set(before);
            }
        }
    }

    /** Puts {@code o} in force on this thread (null: none); inside a {@link #with}, which restores the one before. */
    public static void use(Overlay o) {
        slot().set(o == null ? Boolean.FALSE : o);
    }

    /** A model weight (rules.model.{@code key}, {@code v}) as the overlay in force has it. */
    static double adjust(String key, double v) {
        Overlay o = active();
        return o == null ? v : o.adjust(key, v);
    }

    /** rules.model.spells as the overlay in force has them: the same array when none is. */
    static JsonArray spells(JsonArray rules) {
        Overlay o = active();
        return o == null ? rules : o.spells(rules);
    }

    /** The tree's main attack and element % bonuses (overlay in force) into a build's stats; none: as they are. */
    static void addStats(Map<String, Double> s) {
        Overlay o = active();
        if (o != null) {
            o.addStats(s);
        }
    }

    /** The overlay in force for cache keys: "" when none. */
    public static String key() {
        Overlay o = active();
        return o == null ? "" : o.key();
    }

    /**
     * What the tree changes at one level. {@code cost}: spell name → mana cost change;
     * {@code known}: the spells the tree has, {@code unlocked}: those you have by then;
     * {@code passive}: summon and trap damage per second as conversions in
     * {@link Damage#ELEMENTS} order; {@code manaPer5}: mana every 5 s on top of the base;
     * {@code mainAttack}: the factor on rules.model.mainAttackWeight; {@code bonus}: the passive
     * damage bonuses of the nodes taken ({@link TreeBonuses.Sum#NONE} with the setting off).
     */
    public static final class Overlay {
        final int level;
        final Map<String, Double> cost;
        final Set<String> known;
        final Set<String> unlocked;
        final double[] passive;
        final double manaPer5;
        final double mainAttack;
        final TreeBonuses.Sum bonus;
        private final String key;
        /** {rules array, adjusted array}: the model asks for the spells on every evaluation. */
        private volatile Object[] cached;

        Overlay(int level, Map<String, Double> cost, Set<String> known, Set<String> unlocked, double[] passive,
                double manaPer5, double mainAttack, TreeBonuses.Sum bonus) {
            this.level = level;
            this.cost = cost;
            this.known = known;
            this.unlocked = unlocked;
            this.passive = passive;
            this.manaPer5 = manaPer5;
            this.mainAttack = mainAttack;
            this.bonus = bonus;
            StringBuilder k = new StringBuilder();
            k.append(cost).append('|').append(new TreeSet<>(unlocked)).append('|');
            for (double p : passive) {
                k.append(Math.round(p * 10000)).append(',');
            }
            k.append('|').append(Math.round(manaPer5 * 1000)).append('|').append(Math.round(mainAttack * 1000));
            if (!bonus.isEmpty()) {
                k.append("|b").append(bonus.key());             // none: the key as it was before bonuses
            }
            this.key = k.toString();
        }

        /** Every passive conversion together: 0 when nothing hits on its own. */
        double passiveTotal() {
            double t = 0;
            for (double p : passive) {
                t += p;
            }
            return t;
        }

        /** What the overlay is, for cache keys: the same text for the same overlay. */
        public String key() {
            return key;
        }

        double adjust(String key, double v) {
            return switch (key) {
                case "passiveDpsFactor" -> passiveTotal() > 0 ? 1 : 0;
                // the conversions below are already per second
                case "summonHitsPerSecond" -> 1;
                // the alive share is already in the tree's summon numbers: not a second time
                case "summonAlive" -> 1;
                case "summonNeutral" -> passive[0];
                case "summonEarth" -> passive[1];
                case "summonThunder" -> passive[2];
                case "summonWater" -> passive[3];
                case "summonFire" -> passive[4];
                case "summonAir" -> passive[5];
                case "baseManaPer5s" -> v + manaPer5;
                case "mainAttackWeight" -> v * mainAttack;
                default -> v;
            };
        }

        JsonArray spells(JsonArray rules) {
            Object[] c = cached;
            if (c != null && c[0] == rules) {
                return (JsonArray) c[1];
            }
            JsonArray out = new JsonArray();
            for (JsonElement e : rules) {
                if (!e.isJsonObject()) {
                    out.add(e);
                    continue;
                }
                JsonObject s = e.getAsJsonObject().deepCopy();
                String name = s.has("name") ? s.get("name").getAsString() : "";
                if (known.contains(name) && !unlocked.contains(name)) {
                    s.addProperty("share", 0.0);            // not bought yet at this level
                }
                Double d = cost.get(name);
                if (d != null && s.has("cost")) {
                    s.addProperty("cost", Math.max(1, s.get("cost").getAsDouble() + d));
                }
                bonus.raise(name, s);                       // "Total Damage: +50%" nodes for this spell
                out.add(s);
            }
            cached = new Object[] {rules, out};
            return out;
        }

        /** The main attack and element % bonuses into a build's stats ({@code s}, as Damage sums them). */
        void addStats(Map<String, Double> s) {
            bonus.addStats(s);
        }

        @Override
        public String toString() {
            return "Lv " + level + " " + key;
        }
    }

    // ---- reading the tree ---------------------------------------------------------------------

    /** The class's tree: the game state's, else Wynntils' own (the state may not have it yet); null when unknown. */
    static List<AbilityNode> tree() {
        List<AbilityNode> t = GameState.current().abilityTree();
        if (t != null && !t.isEmpty()) {
            return t;
        }
        try {
            List<AbilityNode> w = Wyn.abilityTree(Wyn.classType());
            return w == null || w.isEmpty() ? null : w;
        } catch (Throwable e) {
            return null;
        }
    }

    /** The archetype the model follows: the planner's, else the one you lean to (Lv 10 on), else "". */
    public static String archetype() {
        String chosen = TreePlanner.chosen();
        return chosen.isEmpty() ? TreePlanner.mostUnlocked() : chosen;
    }

    /** A node as one line of text (lines joined by spaces), as the game shows it. */
    static String text(AbilityNode n) {
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
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    /** True when the tree's text carries what the model reads (a made-up or unread tree has no mana costs). */
    static boolean readable(List<AbilityNode> tree) {
        if (tree == null) {
            return false;
        }
        for (AbilityNode n : tree) {
            if (text(n).toLowerCase(Locale.ROOT).contains("mana cost:")) {
                return true;
            }
        }
        return false;
    }

    /** A spell node of the class: its type says so and it has a click combo (ultimates are on a meter). */
    static boolean spellNode(AbilityNode n, String cls) {
        return n.type() != null && cls != null && n.type().startsWith(cls + "_ABILITY")
                && text(n).contains("Click Combo");
    }

    /**
     * One node as the model reads it: the spell a mana cost change is for and by how much, the
     * passive damage it adds (conversions per hit, hits per second, how many, summon or area),
     * what it adds to other summons' maximum and the mana per second it gives.
     */
    record Read(String name, String archetype, String costSpell, double costDelta, double[] conv, double rate,
            int count, String noun, boolean summon, Map<String, Integer> more, double manaPerSecond) {}

    /** Reads every node of the tree for the class's spells ({@code spellNames}, as rules.model.spells has them). */
    static List<Read> read(List<AbilityNode> tree, String cls, Collection<String> spellNames) {
        return TreeReading.read(tree, cls, spellNames);
    }

    /**
     * The overlay for the nodes in {@code taken}. {@code mainAttack} is the factor on the main
     * attack's weight (1 once an archetype node is in).
     */
    static Overlay overlay(int level, List<Read> reads, Collection<String> spellNames, Collection<String> taken,
            double mainAttack, double passiveCap) {
        return TreeReading.overlay(level, reads, spellNames, taken, mainAttack, passiveCap, Map.of());
    }

    /** {@link #overlay} with the tree's passive damage bonuses ({@link TreeBonuses#read}; none: as before). */
    static Overlay overlay(int level, List<Read> reads, Collection<String> spellNames, Collection<String> taken,
            double mainAttack, double passiveCap, Map<String, TreeBonuses.Bonus> bonuses) {
        return TreeReading.overlay(level, reads, spellNames, taken, mainAttack, passiveCap, bonuses);
    }

    /**
     * Share of the time a summon is alive and hitting (rules.model.summonAlive, 0.6): puppets and
     * hounds die, wait to be summoned again and walk to the next pack. A method, not a field.
     */
    static double summonAlive() {
        return Math.max(0.05, Math.min(1, Rules.sub("model", "summonAlive", 0.6)));
    }

    // ---- per level ----------------------------------------------------------------------------

    /**
     * The tree for a run of the model: its nodes read once, what you have, the plan from there and
     * your level. {@link #at} gives the overlay for a level: your nodes at your level, the plan's
     * up to that level after it.
     */
    public static final class Run {
        final List<Read> reads;
        final List<String> spellNames;
        final List<String> unlocked;
        final List<TreePlanner.Step> plan;
        final int level;
        final String archetype;
        /** The main attack's weight with no archetype node yet over the archetype's own (1: no archetype). */
        final double earlyMainAttack;
        final double passiveCap;
        /** The nodes' passive damage bonuses by node name; empty with the treeBonuses setting off. */
        final Map<String, TreeBonuses.Bonus> bonuses;

        Run(List<Read> reads, List<String> spellNames, List<String> unlocked, List<TreePlanner.Step> plan, int level,
                String archetype, double earlyMainAttack, double passiveCap) {
            this(reads, spellNames, unlocked, plan, level, archetype, earlyMainAttack, passiveCap, Map.of());
        }

        Run(List<Read> reads, List<String> spellNames, List<String> unlocked, List<TreePlanner.Step> plan, int level,
                String archetype, double earlyMainAttack, double passiveCap, Map<String, TreeBonuses.Bonus> bonuses) {
            this.bonuses = bonuses;
            this.reads = reads;
            this.spellNames = spellNames;
            this.unlocked = unlocked;
            this.plan = plan;
            this.level = level;
            this.archetype = archetype;
            this.earlyMainAttack = earlyMainAttack;
            this.passiveCap = passiveCap;
        }

        /** The nodes you have at {@code m}: yours at your level, then the plan's steps up to it. */
        List<String> taken(int m) {
            List<String> out = new ArrayList<>(unlocked);
            if (m <= level && !unlocked.isEmpty()) {
                return out;
            }
            for (TreePlanner.Step s : plan) {
                if (s.level() <= m && !out.contains(s.name())) {
                    out.add(s.name());
                }
            }
            return out;
        }

        public Overlay at(int m) {
            List<String> taken = taken(m);
            double main = 1;
            if (!archetype.isEmpty()) {
                boolean in = false;
                for (Read r : reads) {
                    if (r.archetype().equalsIgnoreCase(archetype) && taken.contains(r.name())) {
                        in = true;
                        break;
                    }
                }
                main = in ? 1 : earlyMainAttack;
            }
            return overlay(m, reads, spellNames, taken, main, passiveCap, bonuses);
        }
    }

    /** The spells rules.model.spells names (the class profile's), or the tree's spell nodes when it has none. */
    static List<String> spellNames(List<AbilityNode> tree, String cls) {
        List<String> out = new ArrayList<>();
        JsonObject m = Rules.get().getAsJsonObject("model");
        JsonElement sp = m == null ? null : m.get("spells");
        if (sp != null && sp.isJsonArray()) {
            sp.getAsJsonArray().forEach(e -> {
                if (e.isJsonObject() && e.getAsJsonObject().has("name")) {
                    out.add(e.getAsJsonObject().get("name").getAsString());
                }
            });
        }
        if (out.isEmpty()) {
            for (AbilityNode n : tree) {
                if (spellNode(n, cls)) {
                    out.add(n.name());
                }
            }
        }
        return out;
    }

    /** The run for the game as it is, following {@link #archetype}; null when the tree cannot be read. */
    public static Run run() {
        return run(archetype());
    }

    /** The last run and what it was made from ({key, Run}): a build plan asks every few seconds. */
    private static volatile Object[] lastRun;

    /** {@link #run()} for {@code archetype}'s plan; null when the tree cannot be read. */
    static Run run(String archetype) {
        try {
            List<AbilityNode> tree = tree();
            if (tree == null) {
                return null;
            }
            GameState gs = GameState.current();
            String cls = gs.classType() == null ? "" : gs.classType().name();
            String arch = archetype == null ? "" : archetype;
            List<String> have = gs.unlockedAbilities() == null ? List.of() : List.copyOf(gs.unlockedAbilities());
            List<String> spells = spellNames(tree, cls);
            double cap = Rules.sub("model", "summonCap", 2.5);
            boolean withBonuses = Settings.on("treeBonuses");
            String key = withBonuses + "|" + cls + "|" + arch + "|" + gs.level() + "|" + have + "|" + spells + "|" + cap + "|" + summonAlive() + "|"
                    + System.identityHashCode(tree) + ":" + tree.size();
            Object[] last = lastRun;
            if (last != null && last[0].equals(key)) {
                return (Run) last[1];
            }
            if (!readable(tree)) {
                return null;
            }
            // nothing read as unlocked (the tree menu not opened yet): the plan from the first node
            List<TreePlanner.Step> plan = have.isEmpty() ? TreePlanner.wholePlan(arch) : TreePlanner.plan(arch, have);
            double archWeight = ClassProfiles.mainAttackWeight(cls, arch, tree);
            double early = arch.isEmpty() || archWeight <= 0 ? 1 : ClassProfiles.mainAttackWeight(cls, "", tree) / archWeight;
            List<Read> reads = read(tree, cls, spells);
            Map<String, TreeBonuses.Bonus> bonuses = Map.of();
            if (withBonuses) {
                List<String> summons = new ArrayList<>();
                for (Read rd : reads) {
                    if (rd.summon() && rd.conv() != null && rd.noun() != null) {
                        summons.add(rd.noun());
                    }
                }
                bonuses = TreeBonuses.read(tree, cls, spells, summons);
            }
            Run r = new Run(reads, spells, have, plan, gs.level(), arch, early, cap, bonuses);
            lastRun = new Object[] {key, r};
            return r;
        } catch (Throwable t) {
            System.out.println("[WynnLabs] tree model failed: " + t);
            return null;
        }
    }
}
