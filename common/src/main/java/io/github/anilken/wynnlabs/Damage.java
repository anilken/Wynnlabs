package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Roll;
import io.github.anilken.wynnlabs.model.SetData;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.model.Slot;
import io.github.anilken.wynnlabs.model.StatRange;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A small damage model of a whole build, after Wynncraft 2.x's formula (see the skill's
 * mekanikler.md): spells scale from the weapon's base DPS times the spell's conversion, % IDs
 * add up per element, raw spell damage is added times the conversion, then Strength and crit
 * multiply. Mana decides how often you can cast. Health, XP bonus and walk speed weigh in as
 * the rules say, so the number means "how fast this build levels", not just "how hard it hits".
 *
 * <p>The spells, their costs, conversions and shares live in rules.model so they can be set per
 * class and archetype.
 */
final class Damage {

    static final String[] ELEMENTS = {"neutral", "earth", "thunder", "water", "fire", "air"};

    /** One wearable piece, reduced to what the model needs. */
    /**
     * {@code key} is the item's advice key (name and rolls): Wynncraft swaps the ItemStack objects
     * in your inventory all the time, so pieces are matched by key, not by object.
     */
    record Piece(ItemStack stack, String key, String name, Slot type, int level, Map<String, Double> stats,
            Map<SkillKind, Integer> reqs, double baseDps, Map<String, Double> weaponShares, double health,
            boolean crafted) {

        Piece(ItemStack stack, String key, String name, Slot type, int level, Map<String, Double> stats,
                Map<SkillKind, Integer> reqs, double baseDps, Map<String, Double> weaponShares, double health) {
            this(stack, key, name, type, level, stats, reqs, baseDps, weaponShares, health, false);
        }

        /** Weapons and crafted gear never help other items' skill requirements (Wynncraft rule). */
        boolean helpsRequirements() {
            return !crafted && (type == null || !type.isWeapon());
        }
    }

    /**
     * {@code ehp} is effective health as WynnBuilder shows it (Defence and Agility only);
     * {@code ehpElemental} also counts the build's elemental defences against the incoming split
     * (rules.model.incomingElements), and is what the survival factor uses. {@code pieces} and
     * {@code weapon}: the set it was worked out for (null when read back from disk), so the road
     * map can tell which pieces a set keeps from the milestone before it.
     */
    record Result(double objective, double spellDps, double mainDps, double casts, double ehp, double xp,
            double speed, double dmgPerCast, double[] factors, double ehpElemental, double health,
            List<Piece> pieces, Piece weapon) {

        Result(double objective, double spellDps, double mainDps, double casts, double ehp, double xp,
                double speed, double dmgPerCast) {
            this(objective, spellDps, mainDps, casts, ehp, xp, speed, dmgPerCast, new double[0], ehp, 0);
        }

        /** A result with no pieces to it (read back from disk, made up in tests). */
        Result(double objective, double spellDps, double mainDps, double casts, double ehp, double xp,
                double speed, double dmgPerCast, double[] factors, double ehpElemental, double health) {
            this(objective, spellDps, mainDps, casts, ehp, xp, speed, dmgPerCast, factors, ehpElemental, health, null, null);
        }
    }

    /**
     * The parts the objective multiplies, in this order (for the gain breakdown): language keys,
     * shown with {@link #factor}.
     */
    static final String[] FACTORS = {"wynnlabs.damage.factor_damage", "wynnlabs.damage.factor_health",
            "wynnlabs.damage.factor_mana", "wynnlabs.damage.factor_speed", "wynnlabs.damage.factor_xp",
            "wynnlabs.damage.factor_attack_speed"};

    /** The name of factor {@code i} in the game's language. */
    static String factor(int i) {
        return L.t(FACTORS[i]);
    }

    private Damage() {}

    /** The model's view of a stack, or null for anything it cannot read (unidentified, not gear). */
    /** A piece you do not own yet, from Wynntils' item data, every roll at the middle of its range. */
    /** Pseudo-stat on weapons: how a weapon's attack speed feels to play (1 = no penalty). */
    static final String FEEL = "_attackSpeedFeel";
    /** Pseudo-stat on weapons: attack speed tier, 0 = super slow … 6 = super fast. */
    static final String TIER = "_attackSpeedTier";
    /** Hits per second by attack speed tier (Wynncraft / WynnBuilder). */
    static final double[] HITS = {0.51, 0.83, 1.5, 2.05, 2.5, 3.1, 4.3};

    /** Pseudo-stat: one per piece of a set ("_set:Morph"); evaluate() adds the set bonus. */
    static final String SET = "_set:";
    static final Map<String, SetData> SETS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Powders (WynnBuilder powders.js): per element e/t/w/f/a and tier I–VII: {min, max, convert %}. */
    private static final double[][][] POWDER = {
        {{4, 5, 17}, {6, 7, 21}, {7, 9, 25}, {8, 9, 31}, {9, 11, 38}, {11, 12, 46}, {12, 14, 52}},
        {{1, 8, 9}, {1, 12, 11}, {2, 14, 13}, {2, 15, 17}, {3, 17, 22}, {4, 19, 28}, {5, 21, 32}},
        {{3, 4, 13}, {5, 6, 15}, {6, 8, 17}, {7, 8, 21}, {8, 10, 26}, {10, 13, 32}, {11, 15, 38}},
        {{2, 5, 14}, {4, 7, 16}, {5, 9, 19}, {6, 9, 24}, {7, 11, 30}, {9, 14, 37}, {10, 16, 44}},
        {{2, 6, 11}, {3, 9, 14}, {4, 11, 17}, {5, 11, 22}, {7, 12, 28}, {8, 15, 35}, {9, 17, 42}}};
    private static final double[] POWDER_HP = {5, 10, 20, 30, 45, 60, 75};
    private static final int[] POWDER_LEVEL = {1, 5, 15, 25, 40, 55, 70};

    private static int elementIndex(String el) {
        return switch (el) {
            case "earth" -> 0;
            case "thunder" -> 1;
            case "water" -> 2;
            case "fire" -> 3;
            default -> 4;
        };
    }

    /**
     * Powder tiers are not shown on the item: assume the best tier the item's level allows,
     * capped by rules.model.powderTier (default VI, what most players use).
     */
    static int powderTier(int itemLevel) {
        int t = 1;
        for (int i = 0; i < POWDER_LEVEL.length; i++) {
            if (itemLevel >= POWDER_LEVEL[i]) {
                t = i + 1;
            }
        }
        return Math.max(1, Math.min(t, (int) Rules.sub("model", "powderTier", 6)));
    }

    static double tierOf(String speed) {
        return switch (speed) {
            case "SUPER_SLOW" -> 0;
            case "VERY_SLOW" -> 1;
            case "SLOW" -> 2;
            case "NORMAL" -> 3;
            case "FAST" -> 4;
            case "VERY_FAST" -> 5;
            default -> 6;
        };
    }

    /**
     * Very slow weapons hit hard on paper but feel sluggish and leave gaps while levelling;
     * rules.model.attackSpeedFeel holds the factor per speed (SUPER_SLOW, VERY_SLOW, SLOW...).
     */
    static double feel(String speed) {
        double fallback = switch (speed) {
            case "SUPER_SLOW" -> 0.7;
            case "VERY_SLOW" -> 0.8;
            case "SLOW" -> 0.95;
            default -> 1.0;
        };
        var m = Rules.get().getAsJsonObject("model");
        var f = m == null ? null : m.getAsJsonObject("attackSpeedFeel");
        return f != null && f.has(speed) ? f.get(speed).getAsDouble() : fallback;
    }

    static Piece piece(ItemData info) {
        Map<String, Double> stats = new HashMap<>();
        for (StatRange v : info.stats()) {
            stats.merge(v.key(), (v.min() + v.max()) / 2.0, Double::sum);
        }
        Map<SkillKind, Integer> reqs = new java.util.EnumMap<>(SkillKind.class);
        info.requirements().skills().forEach(r -> reqs.put(r.skill(), r.points()));
        Map<String, Double> shares = new HashMap<>();
        info.base().damages().forEach(d -> {
            String el = d.type().toLowerCase(Locale.ROOT);
            if (List.of(ELEMENTS).contains(el)) {
                shares.merge(el, (d.low() + d.high()) / 2.0, Double::sum);
            }
        });
        info.base().attackSpeed().ifPresent(a -> {
            stats.put(FEEL, feel(a));
            stats.put(TIER, tierOf(a));
        });
        info.set().ifPresent(si -> {
            SETS.put(si.name(), si);
            stats.put(SET + si.name(), 1.0);
        });
        double dps = info.base().averageDps();
        Map<String, Double> sh = normalise(shares);
        double health = info.base().health();
        // a piece you would buy gets its slots filled, as the one you wear has them: weapons with
        // their own strongest element (players powder a weapon in its element), armour for health
        List<String> powders = assumedPowders(info, sh);
        if (!powders.isEmpty()) {
            int ptier = powderTier(info.requirements().level());
            if (info.slot() != null && info.slot().isWeapon()) {
                Powdered out = powdered(powders, ptier, dps, sh, stats);
                dps = out.dps();
                sh = out.shares();
            } else {
                health += powders.size() * POWDER_HP[ptier - 1];
            }
        }
        return new Piece(null, "info:" + info.name(), info.name(), info.slot(), info.requirements().level(), stats,
                reqs, dps, sh, health);
    }

    /**
     * The powders a piece from the item data is judged with: every slot, the weapon's strongest
     * element (none for a neutral-only weapon), any element for armour (only health counts).
     * rules.model.assumePowders 0 turns it off.
     */
    static List<String> assumedPowders(ItemData info, Map<String, Double> shares) {
        return assumedPowders(info, shares, info.powderSlots(), null);
    }

    /**
     * {@code n} assumed powders for a piece of {@code info}: a weapon's in {@code keep} (the
     * element its own powders have) or else its strongest element; armour's in any element. Empty
     * when powders are turned off or a weapon has no element to take.
     */
    private static List<String> assumedPowders(ItemData info, Map<String, Double> shares, int n, String keep) {
        if (n <= 0 || Rules.sub("model", "assumePowders", 1) <= 0) {
            return List.of();
        }
        String el = "earth";
        if (info.slot() != null && info.slot().isWeapon()) {
            el = keep == null ? null : keep.toLowerCase(Locale.ROOT);
            double best = 0;
            for (var e : shares.entrySet()) {
                if (keep == null && !e.getKey().equals("neutral") && e.getValue() > best) {
                    best = e.getValue();
                    el = e.getKey();
                }
            }
            if (el == null) {
                return List.of();
            }
        }
        return java.util.Collections.nCopies(n, el);
    }

    /**
     * A piece you have with its empty powder slots filled the way {@link #piece(ItemData)} fills
     * a piece you would buy (a weapon in the element its powders already have, else its own
     * strongest; armour for health), so the road map weighs what you have and what you would buy
     * alike. Its own powders stay. A piece from the item data, a crafted one or one with every
     * slot taken comes back as it is. The /build "now" keeps the real state.
     */
    static Piece withAssumedPowders(Piece p) {
        ItemView g = ownGear(p);
        if (g == null || g.data().powderSlots() <= g.rolled().powders().size()) {
            return p;
        }
        // a key of its own: Build's memo knows a set by its keys, and the piece scores otherwise now
        Piece out = piece(g, p.key() + "|powdered", p.stack(), true);
        return out == null ? p : out;
    }

    /**
     * What {@link #withAssumedPowders} adds to a piece you have: {element, count, tier}, the
     * element null for armour (its powders count for health in any element); null for none.
     */
    static Object[] addedPowders(Piece p) {
        ItemView g = ownGear(p);
        if (g == null) {
            return null;
        }
        ItemData info = g.data();
        List<String> real = g.rolled().powders();
        List<String> add = assumedPowders(info, normalise(sharesOf(info)), info.powderSlots() - real.size(),
                real.isEmpty() ? null : real.get(0));
        if (add.isEmpty()) {
            return null;
        }
        boolean weapon = info.slot() != null && info.slot().isWeapon();
        return new Object[] {weapon ? add.get(0) : null, add.size(), powderTier(info.level())};
    }

    /**
     * The powders the road map assumes in a piece on top of its own, as {count, tier}: a piece
     * from the item data ({@code info}) has every slot filled, one you have its empty ones.
     */
    static int[] assumedCount(Piece p, ItemData info) {
        if (p == null || p.crafted()) {
            return new int[2];
        }
        if (p.stack() == null || p.stack().isEmpty()) {
            int n = info == null ? 0 : assumedPowders(info, normalise(sharesOf(info))).size();
            return new int[] {n, n == 0 ? 0 : powderTier(info.requirements().level())};
        }
        Object[] a = addedPowders(p);
        return a == null ? new int[2] : new int[] {(int) a[1], (int) a[2]};
    }

    /** The identified gear behind a piece you have (its stack), or null. */
    private static ItemView ownGear(Piece p) {
        if (p == null || p.crafted() || p.stack() == null || p.stack().isEmpty()) {
            return null;
        }
        Optional<ItemView> v = GameState.current().view(p.stack());
        return v.isEmpty() || !v.get().isGear() || v.get().unidentified() || v.get().rolled() == null ? null : v.get();
    }

    /** A weapon's base damage by element, not normalised. */
    private static Map<String, Double> sharesOf(ItemData info) {
        Map<String, Double> shares = new HashMap<>();
        info.base().damages().forEach(d -> {
            String el = d.type().toLowerCase(Locale.ROOT);
            if (List.of(ELEMENTS).contains(el)) {
                shares.merge(el, (d.low() + d.high()) / 2.0, Double::sum);
            }
        });
        return shares;
    }

    /**
     * The powders a weapon pick is judged with, for its tooltip: {element, count, tier}; null when
     * it gets none (no slots, neutral only, powders turned off) and for armour, whose powders only
     * count for health in any element.
     */
    static Object[] powderHint(ItemData info) {
        if (info.slot() == null || !info.slot().isWeapon()) {
            return null;
        }
        List<String> p = assumedPowders(info, normalise(sharesOf(info)));
        return p.isEmpty() ? null : new Object[] {p.get(0), p.size(), powderTier(info.requirements().level())};
    }

    /** A weapon's DPS and element shares with its powders in. */
    private record Powdered(double dps, Map<String, Double> shares) {}

    /** Weapon powders: each converts part of the neutral damage to its element and adds flat damage. */
    private static Powdered powdered(List<String> powders, int ptier, double dps, Map<String, Double> sh,
            Map<String, Double> stats) {
        int t = (int) Math.round(stats.getOrDefault(TIER, 3.0));
        double perHit = dps / HITS[Math.max(0, Math.min(6, t))];
        Map<String, Double> parts = new HashMap<>();
        sh.forEach((k, v) -> parts.put(k, v * perHit));
        Map<String, double[]> byEl = new java.util.LinkedHashMap<>();   // el -> {conv, flat}
        for (String pw : powders) {
            String el = pw.toLowerCase(Locale.ROOT);
            double[] row = POWDER[elementIndex(el)][ptier - 1];
            double[] acc = byEl.computeIfAbsent(el, k -> new double[2]);
            acc[0] += row[2] / 100.0;
            acc[1] += (row[0] + row[1]) / 2.0;
        }
        for (var e : byEl.entrySet()) {
            double neutral = parts.getOrDefault("neutral", 0.0);
            double moved = Math.min(neutral, e.getValue()[0] * neutral);
            parts.put("neutral", neutral - moved);
            parts.merge(e.getKey(), moved + e.getValue()[1], Double::sum);
        }
        double sum = parts.values().stream().mapToDouble(Double::doubleValue).sum();
        return new Powdered(sum * HITS[Math.max(0, Math.min(6, t))], normalise(parts));
    }

    static Piece piece(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Optional<ItemView> p = GameState.current().view(stack);
        if (p.isEmpty()) {
            return null;
        }
        return piece(p.get(), ItemJson.keyOf(stack), stack);
    }

    /** The same from the mod's reading of an item; {@code stack} may be null (not one you carry). */
    static Piece piece(ItemView item, String key, ItemStack stack) {
        return piece(item, key, stack, false);
    }

    /** {@code fill}: its empty powder slots filled too ({@link #withAssumedPowders}). */
    private static Piece piece(ItemView item, String key, ItemStack stack, boolean fill) {
        Map<String, Double> stats = new HashMap<>();
        Map<SkillKind, Integer> reqs = new java.util.EnumMap<>(SkillKind.class);
        Map<String, Double> shares = new HashMap<>();
        if (item.isGear()) {
            ItemView g = item;
            if (g.unidentified()) {
                return null;
            }
            ItemData info = g.data();
            for (Roll v : g.rolled().rolls()) {
                stats.merge(v.key(), (double) v.value(), Double::sum);
            }
            info.requirements().skills().forEach(r -> reqs.put(r.skill(), r.points()));
            var fixed = info.base();
            fixed.damages().forEach(d -> {
                String el = d.type().toLowerCase(Locale.ROOT);
                if (List.of(ELEMENTS).contains(el)) {
                    shares.merge(el, (d.low() + d.high()) / 2.0, Double::sum);
                }
            });
            fixed.attackSpeed().ifPresent(a -> {
                stats.put(FEEL, feel(a));
                stats.put(TIER, tierOf(a));
            });
            info.set().ifPresent(si -> {
                SETS.put(si.name(), si);
                stats.put(SET + si.name(), 1.0);
            });
            double dps = fixed.averageDps();
            Map<String, Double> sh = normalise(shares);
            double health = fixed.health();
            List<String> powders = g.rolled().powders();
            if (fill) {
                List<String> add = assumedPowders(info, sh, info.powderSlots() - powders.size(),
                        powders.isEmpty() ? null : powders.get(0));
                if (!add.isEmpty()) {
                    List<String> all = new java.util.ArrayList<>(powders);
                    all.addAll(add);
                    powders = all;
                }
            }
            if (!powders.isEmpty()) {
                int ptier = powderTier(info.level());
                if (info.slot() != null && info.slot().isWeapon()) {
                    // weapon powders: convert part of the neutral damage and add flat damage
                    Powdered out = powdered(powders, ptier, dps, sh, stats);
                    dps = out.dps();
                    sh = out.shares();
                } else {
                    // armour powders: health by tier (same for every element)
                    health += powders.size() * POWDER_HP[ptier - 1];
                }
            }
            return new Piece(stack, key, info.name(), info.slot(), info.level(), stats, reqs,
                    dps, sh, health);
        }
        if (item.isCrafted()) {
            ItemView.Crafted c = item.crafted();
            for (Roll v : c.rolls()) {
                stats.merge(v.key(), (double) v.value(), Double::sum);
            }
            c.requirements().skills().forEach(r -> reqs.put(r.skill(), r.points()));
            c.damages().forEach(d -> {
                String el = d.type().toLowerCase(Locale.ROOT);
                if (List.of(ELEMENTS).contains(el)) {
                    shares.merge(el, (d.low() + d.high()) / 2.0, Double::sum);
                }
            });
            return new Piece(stack, key, c.name(), c.slot(), c.level(), stats, reqs,
                    c.dps(),
                    normalise(shares), c.health(), true);
        }
        return null;
    }

    private static Map<String, Double> normalise(Map<String, Double> m) {
        double sum = m.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<String, Double> out = new HashMap<>();
        if (sum <= 0) {
            out.put("neutral", 1.0);
            return out;
        }
        m.forEach((k, v) -> out.put(k, v / sum));
        return out;
    }

    // ---- skill point curve -----------------------------------------------------------------

    /**
     * Percent effect of this many points in a skill (Str/Dex/Int scale), 0 to 80.8: Wynncraft's
     * f(p) = 0.9908 / (1 − 0.9908) × (1 − 0.9908^p), capped at 150 points (WynnBuilder build_utils).
     */
    static double curve(double points) {
        double p = Math.max(0, Math.min(150, points));
        return 0.9908 / (1 - 0.9908) * (1 - Math.pow(0.9908, p));
    }

    /** % more damage the class takes (base resistances): Shaman 1.4, Archer 1.3, Mage 1.2. */
    /** The class, taken on the game thread, while a build plan is worked out in the background. */
    static final ThreadLocal<io.github.anilken.wynnlabs.model.Klass> CLASS_FOR_THREAD = new ThreadLocal<>();

    static double classDamageTaken() {
        try {
            var forThread = CLASS_FOR_THREAD.get();
            var cls = forThread != null ? forThread : GameState.current().classType();
            return switch (cls.name()) {
                case "SHAMAN" -> 1.4;
                case "ARCHER" -> 1.3;
                case "MAGE" -> 1.2;
                default -> 1.0;
            };
        } catch (Throwable t) {
            return 1.0;
        }
    }

    /**
     * One hit of a damage source with these conversions (fractions per element, neutral first,
     * already split by the weapon's elements): % IDs add up per element, generic raw is spread
     * over the hit times the total conversion, element raw counts times the total conversion when
     * that element is present (WynnBuilder damage_calc). Before Strength and crit.
     */
    private static double hit(Map<String, Double> s, double[] c, double baseDps, double[] skillBonus,
            boolean spell, double[] byElement) {
        double convTotal = 0;
        for (double v : c) {
            convTotal += Math.max(0, v);
        }
        double dmg = 0;
        String kind = spell ? "Spell" : "MainAttack";
        double raw = stat(s, "raw" + kind + "Damage") + stat(s, "rawDamage");
        double rawElemental = stat(s, "rawElemental" + kind + "Damage") + stat(s, "rawElementalDamage");
        for (int i = 0; i < ELEMENTS.length; i++) {
            if (c[i] <= 0) {
                continue;
            }
            String el = ELEMENTS[i];
            double pct = stat(s, spell ? "spellDamage" : "mainAttackDamage") + stat(s, "damage")
                    + stat(s, el + kind + "Damage") + stat(s, el + "Damage") + skillBonus[i];
            if (i > 0) {
                pct += stat(s, "elemental" + kind + "Damage") + stat(s, "elementalDamage");
            }
            dmg += baseDps * c[i] * (1 + pct / 100.0);
            String cap = Character.toUpperCase(el.charAt(0)) + el.substring(1);
            double elementRaw = 0;
            if (i > 0) {
                elementRaw = (stat(s, "raw" + cap + kind + "Damage") + stat(s, "raw" + cap + "Damage")) * convTotal;
                dmg += elementRaw;
            }
            if (byElement != null) {
                // the generic raw goes to each element by its share of the conversion
                byElement[i] += baseDps * c[i] * (1 + pct / 100.0) + elementRaw + raw * c[i]
                        + (i > 0 ? rawElemental * c[i] : 0);
            }
        }
        dmg += raw * convTotal;
        dmg += rawElemental * (convTotal - Math.max(0, c[0]));
        return dmg;
    }

    /** Spreads a neutral conversion over the weapon's own elements (2.x). */
    private static double[] split(double[] conv, Map<String, Double> shares) {
        double[] c = conv.clone();
        double neutral = c[0];
        c[0] = neutral * shares.getOrDefault("neutral", 0.0);
        for (int i = 1; i < ELEMENTS.length; i++) {
            c[i] += neutral * shares.getOrDefault(ELEMENTS[i], 0.0);
        }
        return c;
    }

    // ---- the model ---------------------------------------------------------------------------

    private static double stat(Map<String, Double> s, String key) {
        return s.getOrDefault(key, 0.0);
    }

    private static double model(String key, double fallback) {
        // the road map's tuning strip (Tuning) leans a few weights; 1 everywhere else. The ability
        // tree you have at the level worked out for (TreeModel) sets summons, mana and the main attack
        double v = TreeModel.adjust(key, Rules.sub("model", key, fallback)) * Tuning.weight(key);
        // what deaths taught (DeathCoach, rules.model.survivalLearned, 1 to 2) is kept apart from
        // the goal's weights, so choosing a goal again does not wipe it
        if (key.equals("survivalWeight") || key.equals("survivalPower")) {
            v *= Math.max(1, Math.min(2, Rules.sub("model", "survivalLearned", 1)));
        }
        return v;
    }

    /**
     * The elemental effective health a build should keep at this level before damage counts in
     * full: the table below (about what dies in the open world at that level, a Lv 69-72 Shaman
     * died again and again at 5.5-7k), times the goal's rules.model.ehpFloorScale (Damage 1,
     * Tank 1.6), raised by what recent deaths say (rules.model.deathFloorRatio, at most 2×) and
     * scaled by rules.roadmap.ehpFloorScale (0 turns it off).
     */
    static double ehpFloor(int level) {
        double scale = Rules.sub("roadmap", "ehpFloorScale", 1.0);
        if (scale <= 0) {
            return 0;
        }
        double deaths = Math.min(2, Rules.sub("model", "deathFloorRatio", 0));
        return scale * ehpTable(level) * Math.max(model("ehpFloorScale", 1.0), deaths);
    }

    /** The floor's table for a Damage build: level, elemental effective health, straight lines between. */
    static double ehpTable(int level) {
        int[][] t = {{1, 0}, {50, 2000}, {60, 4000}, {70, 6500}, {80, 9000}, {90, 12000}, {100, 16000},
            {110, 20000}, {120, 25000}};
        for (int i = 1; i < t.length; i++) {
            if (level <= t[i][0]) {
                return t[i - 1][1] + (t[i][1] - t[i - 1][1]) * (double) (Math.max(1, level) - t[i - 1][0])
                        / (t[i][0] - t[i - 1][0]);
            }
        }
        return t[t.length - 1][1];
    }

    /**
     * Evaluates a build: the armour and accessories, the weapon (may be null) and the skill
     * points you assigned yourself (indexed by SkillKind.ordinal()).
     */
    static Result evaluate(List<Piece> pieces, Piece weapon, int[] assigned, int level) {
        return evaluate(pieces, weapon, assigned, level, null);
    }

    /**
     * Where a build's DPS comes from, per element (in {@link #ELEMENTS} order): spells, the main
     * attack and summons. Filled by the same pass as the Result, so {@code spell} adds up to its
     * spellDps and {@code main} to its mainDps (before mainAttackWeight and the attack speed feel).
     * {@code spells}: each spell of rules.model.spells as the model sees one cast of it, for the
     * damage meter to compare a cast with; {@code summonRaw}[0] the summon DPS before
     * rules.model.summonCalibration (puppets and lasting spells such as Totem).
     */
    record Breakdown(double[] spell, double[] main, double[] summon, List<SpellPart> spells, double[] summonRaw) {

        Breakdown() {
            this(new double[ELEMENTS.length], new double[ELEMENTS.length], new double[ELEMENTS.length],
                    new java.util.ArrayList<>(), new double[1]);
        }

        double spellTotal() {
            return sum(spell);
        }

        double mainTotal() {
            return sum(main);
        }

        double summonTotal() {
            return sum(summon);
        }

        /** Spells, main attack and summons together: the DPS the what-ifs compare. */
        double total() {
            return spellTotal() + mainTotal() + summonTotal();
        }

        /** Element {@code i} over all three sources. */
        double element(int i) {
            return spell[i] + main[i] + summon[i];
        }

        private static double sum(double[] a) {
            double t = 0;
            for (double v : a) {
                t += v;
            }
            return t;
        }
    }

    /**
     * One spell as the model sees it: {@code perCast} the whole cast (all its hits and ticks, with
     * Strength and crit) before calibration, {@code cal} the calibration it gets (its own
     * rules.model.spells[i].cal, else spellCalibration). A lasting spell (Totem) counts with the
     * summons; {@code duration} is how long its ticks run (0: one go).
     */
    record SpellPart(String name, int index, double share, double perCast, double cal, boolean lasting,
            double duration) {}

    record Detail(Result result, Breakdown breakdown) {}

    /** {@link #evaluate} with the breakdown of its DPS. */
    static Detail detail(List<Piece> pieces, Piece weapon, int[] assigned, int level) {
        Breakdown b = new Breakdown();
        return new Detail(evaluate(pieces, weapon, assigned, level, b), b);
    }

    private static Result evaluate(List<Piece> pieces, Piece weapon, int[] assigned, int level, Breakdown parts) {
        double[] spellEl = parts == null ? null : new double[ELEMENTS.length];
        double[] mainEl = parts == null ? null : new double[ELEMENTS.length];
        double[] summonEl = parts == null ? null : new double[ELEMENTS.length];
        Map<String, Double> s = new HashMap<>();
        double health = 0;
        for (Piece p : pieces) {
            if (p != null) {
                p.stats().forEach((k, v) -> s.merge(k, v, Double::sum));
                health += p.health();
            }
        }
        if (weapon != null) {
            weapon.stats().forEach((k, v) -> s.merge(k, v, Double::sum));
            health += weapon.health();
        }
        // Set bonuses: n pieces of one set add that set's bonus for n (Morph, Adventurer's, …);
        // a ring in both hands is two, and some sets pay from one piece (Goblin, Black Catalyst)
        for (var e : new java.util.ArrayList<>(s.entrySet())) {
            if (!e.getKey().startsWith(SET)) {
                continue;
            }
            var set = SETS.get(e.getKey().substring(SET.length()));
            int n = (int) Math.round(e.getValue());
            var bonus = set == null || n < 1 ? null : set.bonusFor(n);
            if (bonus != null) {
                bonus.minor().forEach(sv -> s.merge(sv.key(), (double) sv.value(), Double::sum));
            }
        }
        double[] skill = new double[SkillKind.values().length];
        for (SkillKind sk : SkillKind.values()) {
            String api = "raw" + sk.name().charAt(0) + sk.name().substring(1).toLowerCase(Locale.ROOT);
            skill[sk.ordinal()] = assigned[sk.ordinal()] + stat(s, api);
        }
        double str = curve(skill[SkillKind.STRENGTH.ordinal()]);
        double dex = curve(skill[SkillKind.DEXTERITY.ordinal()]);
        double intel = curve(skill[SkillKind.INTELLIGENCE.ordinal()]);
        double def = curve(skill[SkillKind.DEFENCE.ordinal()]) * 0.867;     // 70 % cap
        double agi = curve(skill[SkillKind.AGILITY.ordinal()]) * 0.951;     // 76.8 % cap
        // element bonuses: Def and Agi scaled the same way (WynnBuilder skillpoint_damage_mult)
        double[] skillBonus = {0, str, dex, intel, def, agi};

        double baseDps = weapon == null ? 0 : weapon.baseDps();
        Map<String, Double> shares = weapon == null ? Map.of("neutral", 1.0) : weapon.weaponShares();
        // Strength adds, a crit adds +100 % plus Crit Damage % (additive with Strength, not ×)
        double multiplier = 1 + str / 100.0 + dex / 100.0 * (1 + stat(s, "criticalDamageBonus") / 100.0);
        double intReduction = 0.5 * intel / 80.8;

        // Spells: each takes its own calibration (rules.model.spells[i].cal, measured per spell by
        // the damage meter, Coach), else the overall rules.model.spellCalibration
        double dmgSum = 0;
        double costSum = 0;
        double shareSum = 0;
        double globalCal = model("spellCalibration", 1.0);
        // lasting spells (Totem: a hit every 0.4 s for 30 s) deal alongside the casts, as summons
        // do, and cost their mana once per duration
        double lastingDps = 0;
        double upkeepPer5 = 0;
        double[] lastingEl = parts == null ? null : new double[ELEMENTS.length];
        JsonElement spellsEl = Rules.get().has("model") ? Rules.get().getAsJsonObject("model").get("spells") : null;
        // with the tree's cost changes and the spells not bought yet at this level (TreeModel)
        JsonArray spells = TreeModel.spells(spellsEl != null && spellsEl.isJsonArray() ? spellsEl.getAsJsonArray() : new JsonArray());
        for (JsonElement e : spells) {
            JsonObject sp = e.getAsJsonObject();
            double share = sp.has("share") ? sp.get("share").getAsDouble() : 1;
            if (share <= 0) {
                continue;
            }
            JsonObject conv = sp.getAsJsonObject("conv");
            double[] c = new double[ELEMENTS.length];
            for (int i = 0; i < ELEMENTS.length; i++) {
                c[i] = conv.has(ELEMENTS[i]) ? conv.get(ELEMENTS[i]).getAsDouble() : 0;
            }
            double[] el = spellEl == null ? null : new double[ELEMENTS.length];
            double dmg = hit(s, split(c, shares), baseDps, skillBonus, true, el);
            int idx = sp.has("index") ? sp.get("index").getAsInt() : 0;
            String nth = idx == 1 ? "1st" : idx == 2 ? "2nd" : idx == 3 ? "3rd" : "4th";
            double cost = sp.has("cost") ? sp.get("cost").getAsDouble() : 30;
            // Intelligence cuts the base cost; raw and % cost IDs apply after (display.js getSpellCost)
            cost = (cost * (1 - intReduction) + stat(s, "raw" + nth + "SpellCost"))
                    * (1 + stat(s, nth + "SpellCost") / 100.0);
            cost = Math.max(1, cost);
            double duration = sp.has("duration") ? sp.get("duration").getAsDouble() : 0;
            boolean lasting = ClassProfiles.lasting(sp) && duration > 0;
            double cal = sp.has("cal") ? sp.get("cal").getAsDouble() : globalCal;
            if (parts != null) {
                parts.spells().add(new SpellPart(sp.has("name") ? sp.get("name").getAsString() : nth, idx, share,
                        dmg * multiplier, lasting ? 1 : cal, lasting, duration));
            }
            if (lasting) {
                lastingDps += dmg * multiplier / duration;
                upkeepPer5 += cost * 5.0 / duration;
                if (el != null) {
                    for (int i = 0; i < el.length; i++) {
                        lastingEl[i] += el[i] * multiplier / duration;
                    }
                }
                continue;
            }
            if (el != null) {
                for (int i = 0; i < el.length; i++) {
                    spellEl[i] += share * el[i] * multiplier * cal;
                }
            }
            dmgSum += share * dmg * multiplier * cal;
            costSum += share * cost;
            shareSum += share;
        }
        // measured in game by the damage meter (Coach): what your casts really deal against the model
        double dmgPerCast = shareSum > 0 ? dmgSum / shareSum : 0;
        double avgCost = shareSum > 0 ? costSum / shareSum : 30;
        double manaPer5 = model("baseManaPer5s", 25) + stat(s, "manaRegen")
                + stat(s, "manaSteal") * 5.0 / 3.0 * model("manaStealFactor", 0.3) - upkeepPer5;
        // Over a fight of fightSeconds you spend your mana pool plus what comes back
        double fight = model("fightSeconds", 30);
        double pool = model("baseMana", 100) + stat(s, "rawMaxMana") + stat(s, "maxMana") + Math.floor(intel);
        double casts = Math.max(0, Math.min(model("maxCastsPer5s", 4),
                (pool + manaPer5 * fight / 5.0) / avgCost / (fight / 5.0)));
        double spellDps = dmgPerCast * casts / 5.0;

        // Main attack: per hit times hits per second from the weapon's tier plus Attack Speed IDs
        int tier = weapon == null ? 3 : (int) Math.round(weapon.stats().getOrDefault(TIER, 3.0));
        int tierNow = Math.max(0, Math.min(6, tier + (int) Math.round(stat(s, "rawAttackSpeed"))));
        double perHitBase = baseDps / HITS[tier];
        double[] mc = split(new double[] {1, 0, 0, 0, 0, 0}, shares);
        double mainHit = hit(s, mc, perHitBase, skillBonus, false, mainEl) * model("mainAttackClassMult", 1.0);
        double mainDps = mainHit * HITS[tierNow] * multiplier;

        // Summons (Summoner's puppets and the like): spell hits on their own clock, no mana;
        // they take spell %, raw spell and element % like spells (Puppet Master: 16 % neutral,
        // 2 % earth, 2 % air per hit, up to 3 puppets × 2 hits/s). With the lasting spells they are
        // what the damage meter hears between casts, and rules.model.summonCalibration scales them to it
        double passive = 0;
        double summons = model("passiveDpsFactor", 0);
        if (summons > 0) {
            double[] pc = {model("summonNeutral", 0.16), model("summonEarth", 0.02), model("summonThunder", 0),
                model("summonWater", 0), model("summonFire", 0), model("summonAir", 0.02)};
            passive = hit(s, split(pc, shares), baseDps, skillBonus, true, summonEl) * model("summonHitsPerSecond", 6)
                    * summons * multiplier;
        }
        double summonCal = model("summonCalibration", 1.0);
        double passiveRaw = passive + lastingDps;
        passive = passiveRaw * summonCal;
        if (parts != null) {
            // the per-hit parts scaled the way the totals above are (the spells' calibration is in spellEl)
            double perSpell = shareSum > 0 ? 1 / shareSum * casts / 5.0 : 0;
            double perMain = model("mainAttackClassMult", 1.0) * HITS[tierNow] * multiplier;
            double perSummon = summons > 0 ? model("summonHitsPerSecond", 6) * summons * multiplier : 0;
            for (int i = 0; i < ELEMENTS.length; i++) {
                parts.spell()[i] = spellEl[i] * perSpell;
                parts.main()[i] = mainEl[i] * perMain;
                parts.summon()[i] = (summonEl[i] * perSummon + lastingEl[i]) * summonCal;
            }
            parts.summonRaw()[0] = passiveRaw;
        }
        // Slow weapons feel sluggish on the main attack only: spells and summons do not care
        double feel = weapon == null ? 1.0 : weapon.stats().getOrDefault(FEEL, 1.0);
        double mainPart = model("mainAttackWeight", 0.5) * mainDps;
        double total = spellDps + passive + mainPart * feel;
        double totalNoFeel = spellDps + passive + mainPart;
        double feelFactor = totalNoFeel > 0 ? total / totalNoFeel : 1;

        // Staying alive: real HP (5 × level + 5), Defence and Agility as Wynncraft applies them,
        // and how much more damage the class takes
        health += model("hpBase", 5) + model("hpPerLevel", 5) * level + stat(s, "rawHealth");
        double d = def / 100.0;
        double a = agi / 100.0;
        double ehp = Math.max(1, health) / (0.1 * a + (1 - a) * (1 - d)) / classDamageTaken();
        // Elemental defences: the damage you take splits by element (rules.model.incomingElements,
        // half neutral and a tenth each by default); each element's share shrinks by the build's
        // defence % for it, a negative one grows it. WynnBuilder's EHP leaves this out, so ehp
        // above stays comparable and the elemental view is its own number
        double taken = 0;
        double[] incoming = incomingElements();
        for (int i = 0; i < ELEMENTS.length; i++) {
            double pct = i == 0 ? 0 : stat(s, ELEMENTS[i] + "Defence") + stat(s, "elementalDefence");
            taken += incoming[i] * Math.max(0.1, 1 - pct / 100.0);
        }
        double ehpElemental = ehp / Math.max(0.1, taken);
        double xp = stat(s, "combatExperience") + stat(s, "xpBonus");
        double speed = stat(s, "walkSpeed");
        // XP is worth nothing once you are at the level cap
        double xpWeight = level >= model("maxLevel", 120) ? 0 : model("xpWeight", 1.0) * model("goalXp", 1.0);
        // the fighting goals (rules.model.xpLog 1: Damage, Safe, Boss) count XP with diminishing
        // returns, so +300 % XP does not outweigh staying alive; the levelling goals keep it linear
        double fXp = model("xpLog", 0) > 0
                ? 1 + xpWeight * Math.log1p(Math.max(-0.9, xp / 100.0))
                : 1 + xpWeight * xp / 100.0;
        // speed penalties count more than bonuses (rules.model.speedPenaltyWeight)
        double fSpeed = speedFactor(speed);
        double fLife = Math.max(0.2, 1 + model("survivalWeight", 0.2)
                * Math.log(ehpElemental / (model("hpBase", 5) + model("hpPerLevel", 5) * level)));
        // Sustain: health regen (raw, raised by its %) and half the life steal, which only works
        // while hitting. Below zero the health drains even out of a fight, so it weighs far more
        // than a bonus does (Winter's Essence, −65 regen, was picked as an upgrade).
        double regen = stat(s, "healthRegenRaw") * (1 + stat(s, "healthRegen") / 100.0) + 0.5 * stat(s, "lifeSteal");
        double regenScale = model("hpBase", 5) + model("hpPerLevel", 5) * level;
        double fRegen = regen < 0
                ? Math.max(0.3, 1 + model("negativeRegenWeight", 2.0) * regen / regenScale)
                : 1 + model("regenWeight", 0.2) * Math.min(1, regen / regenScale);
        fLife *= fRegen;
        // health also counts as a power of itself (rules.model.survivalPower, set by the goal: 0.25
        // for Damage up to 1 for a Tank), so more of it keeps paying where the log above flattens
        double power = model("survivalPower", 0.25);
        if (power > 0) {
            fLife *= Math.pow(ehpElemental / regenScale, power);
        }
        // below the floor for this level a build pays by the cube of the shortfall
        // (rules.model.ehpFloorPower), so damage is not bought with paper armour: the skill
        // points, the road map and the build plan all see it, as it is part of the score
        double floor = ehpFloor(level);
        if (floor > 0 && ehpElemental < floor) {
            fLife *= Math.pow(ehpElemental / floor, model("ehpFloorPower", 3));
        }
        // Mana comfort: being able to keep casting is worth more than the DPS number shows
        double fMana = Math.max(0.3, 1 + model("manaWeight", 0.3) * Math.log(Math.max(0.2, casts)));
        // A role besides damage (the Tank and Healer goals): heals in every class are a share of
        // the caster's max health, raised by Healing Efficiency, once per cast; damage then counts
        // less (damageWeight below 1), so a tank or healer is not judged as a damage dealer
        double heal = Math.max(1, health) * (1 + stat(s, "healingEfficiency") / 100.0) * Math.max(0.2, casts);
        double fHeal = Math.max(0.2, 1 + model("healWeight", 0) * Math.log(heal / regenScale));
        // Boss fights reward a big hit per cast (rules.model.burstWeight), loot runs Loot Bonus and
        // Loot Quality (rules.model.lootWeight); both 0 unless the goal asks for them
        double fBurst = Math.pow(Math.max(1, dmgPerCast) / regenScale, model("burstWeight", 0));
        double fLoot = 1 + model("lootWeight", 0) * Math.max(0, stat(s, "lootBonus") + stat(s, "lootQuality")) / 100.0;
        double objective = Math.pow(Math.max(0, total), model("damageWeight", 1.0)) * fXp * fSpeed * fLife * fMana * fHeal
                * fBurst * fLoot;
        return new Result(objective, spellDps, mainDps, casts, ehp, xp, speed, dmgPerCast,
                new double[] {totalNoFeel, fLife, fMana, fSpeed, fXp, feelFactor}, ehpElemental, Math.max(1, health),
                pieces, weapon);
    }

    /**
     * Walk speed's share of the score. Penalties count linearly (rules.model.speedPenaltyWeight);
     * bonuses rise with the slope speedWeight at first and level off below speedCeiling, so a
     * build at +146% is not worth 2.45 times one without speed (it was, and speed drowned out
     * damage: Lv 100+ rings scored below a Lv 61 ring that gave 8% speed).
     */
    /**
     * How the damage you take splits by element, neutral first in {@link #ELEMENTS} order and
     * normalised to 1: rules.model.incomingElements ({"neutral": 0.5, "earth": 0.1, ...}), or
     * half neutral and a tenth each. Tunable per area later.
     */
    static double[] incomingElements() {
        double[] out = {0.5, 0.1, 0.1, 0.1, 0.1, 0.1};
        try {
            var m = Rules.get().getAsJsonObject("model");
            var inc = m == null || !m.has("incomingElements") || !m.get("incomingElements").isJsonObject()
                    ? null : m.getAsJsonObject("incomingElements");
            if (inc != null) {
                for (int i = 0; i < ELEMENTS.length; i++) {
                    if (inc.has(ELEMENTS[i])) {
                        out[i] = Math.max(0, inc.get(ELEMENTS[i]).getAsDouble());
                    }
                }
            }
        } catch (Exception e) {
            return new double[] {0.5, 0.1, 0.1, 0.1, 0.1, 0.1};
        }
        double sum = 0;
        for (double v : out) {
            sum += v;
        }
        if (sum <= 0) {
            return new double[] {0.5, 0.1, 0.1, 0.1, 0.1, 0.1};
        }
        for (int i = 0; i < out.length; i++) {
            out[i] /= sum;
        }
        return out;
    }

    static double speedFactor(double speed) {
        if (speed < 0) {
            return Math.max(0.2, 1 + model("speedPenaltyWeight", model("speedWeight", 0.3)) * speed / 100.0);
        }
        double w = model("speedWeight", 0.3) * model("goalSpeed", 1.0);
        double c = Math.max(0.01, (model("speedCeiling", 1.35) - 1) * model("goalSpeedRoom", 1.0));
        return 1 + c * (1 - Math.exp(-w * speed / 100.0 / c));
    }

    static JsonObject json(Result r) {
        JsonObject o = new JsonObject();
        o.addProperty("objective", Math.round(r.objective() * 10) / 10.0);
        o.addProperty("spellDps", Math.round(r.spellDps() * 10) / 10.0);
        o.addProperty("mainAttackDps", Math.round(r.mainDps() * 10) / 10.0);
        o.addProperty("damagePerCast", Math.round(r.dmgPerCast()));
        o.addProperty("castsPer5s", Math.round(r.casts() * 100) / 100.0);
        o.addProperty("health", Math.round(r.health()));
        o.addProperty("effectiveHealth", Math.round(r.ehp()));
        o.addProperty("effectiveHealthElemental", Math.round(r.ehpElemental()));
        // the factors the objective multiplies: damage is the weighted DPS sum, the rest are factors
        JsonObject parts = new JsonObject();
        String[] names = {"damage", "life", "mana", "speed", "xp", "attackSpeedFeel"};
        for (int i = 0; i < r.factors().length && i < names.length; i++) {
            parts.addProperty(names[i], Math.round(r.factors()[i] * 1000) / 1000.0);
        }
        if (!parts.isEmpty()) {
            o.add("parts", parts);
        }
        o.addProperty("xpBonus", r.xp());
        o.addProperty("walkSpeed", r.speed());
        return o;
    }
}
