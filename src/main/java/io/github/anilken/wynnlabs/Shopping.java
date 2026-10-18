package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.Obtain;
import io.github.anilken.wynnlabs.model.Requirement;
import io.github.anilken.wynnlabs.model.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A shopping list: the best gear per slot for your level and build that beats what you wear,
 * picked from Wynntils' item database with the {@link Rules} weights. No tokens, no network.
 *
 * <p>Rebuilt when your class, level or the rules change. Items on the list are marked in any
 * menu (Trade Market, shops, chests) with a tooltip line and a gold glow.
 */
final class Shopping {

    /**
     * rank 1.. are Trade Market picks (tradable); rank 0 is the alternative: something better
     * you cannot buy (quest reward, untradable dungeon or merchant item) shown beside them.
     */
    record Pick(ItemData info, String slot, int rank, double score, double gain, String source) {
        boolean alternative() {
            return rank == 0;
        }
    }

    /** Can be bought from other players: no untradable, soulbound or quest flag. */
    static boolean tradable(ItemData info) {
        return info.restriction().equals("NONE");
    }

    private static final Slot[] SLOTS = {Slot.HELMET, Slot.CHESTPLATE, Slot.LEGGINGS,
        Slot.BOOTS, Slot.RING, Slot.BRACELET, Slot.NECKLACE};

    private static volatile Map<String, Pick> byName = Map.of();
    private static volatile List<Pick> picks = List.of();
    private static volatile String builtFor = "";

    private Shopping() {}

    /** The list for the current character; call on the client thread. */
    static List<Pick> list() {
        refresh();
        return picks;
    }

    /** The top pick for a slot, or null when nothing on the list beats what you wear. */
    static Pick best(Slot type) {
        for (Pick p : list()) {
            if (p.slot().equals(type.name()) && p.rank() == 1) {
                return p;
            }
        }
        return null;
    }

    /** "MERCHANT: Detlas" becomes "Detlas tüccarı" ("Detlas merchant") and so on, for tooltips. */
    static String where(String source) {
        String[] parts = source.split(": ", 2);
        String name = parts.length > 1 ? parts[1] : "";
        return switch (parts[0]) {
            case "MERCHANT" -> L.t("wynnlabs.shop.where_merchant", name);
            case "DUNGEON_MERCHANT" -> L.t("wynnlabs.shop.where_dungeon_merchant", name);
            case "QUEST" -> L.t("wynnlabs.shop.where_quest", name);
            case "DUNGEON" -> L.t("wynnlabs.shop.where_dungeon", name);
            case "CAVE_LOOT_CHEST", "CAVE_COMPLETION" -> L.t("wynnlabs.shop.where_cave_chest");
            case "LOOT_CHEST" -> L.t("wynnlabs.shop.where_loot_chest");
            case "NORMAL_MOB_DROP", "MOB_REGION" -> L.t("wynnlabs.shop.where_mob_drop");
            case "SPECIFIC_MOB_DROP", "MINIBOSS" -> L.t("wynnlabs.shop.where_dropped_by", name);
            case "WORLD_EVENT" -> L.t("wynnlabs.shop.where_event", name);
            case "BOSS_ALTAR" -> L.t("wynnlabs.shop.where_altar", name);
            default -> source.toLowerCase(java.util.Locale.ROOT);
        };
    }

    /**
     * What kind of source this is, the same in every language, for code that decides by it
     * (instead of reading the words {@link #where} shows): "merchant" (town or dungeon merchant),
     * "event", "dungeon", "quest", "chest", "drop", "altar", or "other". Takes the raw source
     * ({@link #sourceOf}, "MERCHANT: Detlas" style); null gives "other".
     */
    static String kind(String source) {
        if (source == null) {
            return "other";
        }
        String type = source.split(": ", 2)[0];
        return switch (type) {
            case "MERCHANT", "DUNGEON_MERCHANT" -> "merchant";
            case "WORLD_EVENT" -> "event";
            case "DUNGEON" -> "dungeon";
            case "QUEST" -> "quest";
            case "CAVE_LOOT_CHEST", "CAVE_COMPLETION", "LOOT_CHEST" -> "chest";
            case "NORMAL_MOB_DROP", "MOB_REGION", "SPECIFIC_MOB_DROP", "MINIBOSS" -> "drop";
            case "BOSS_ALTAR" -> "altar";
            default -> "other";
        };
    }

    /** {@link #kind(String)} of where this gear comes from. */
    static String kind(ItemData info) {
        return kind(source(info));
    }

    /** A short word for a {@link #kind} ("tüccar" / "merchant"), or null for kinds without one. */
    static String kindLabel(String kind) {
        return switch (kind) {
            case "merchant" -> L.t("wynnlabs.shop.kind_merchant");
            case "event" -> L.t("wynnlabs.shop.kind_event");
            case "dungeon" -> L.t("wynnlabs.shop.kind_dungeon");
            default -> null;
        };
    }

    /** The pick with this gear name, or null; cheap enough for tooltips. */
    static Pick find(String gearName) {
        refresh();
        return byName.get(gearName);
    }

    private static void refresh() {
        GameState state = GameState.current();
        if (!state.inGame()) {
            return;
        }
        Klass cls = state.classType();
        int level = state.level();
        String key = cls + ":" + level + ":" + Rules.version + ":" + System.identityHashCode(Build.plan());
        if (key.equals(builtFor)) {
            return;
        }
        try {
            build(cls, level);
            builtFor = key;
        } catch (Throwable t) {
            System.out.println("[WynnLabs] shopping list failed: " + t);
        }
    }

    /** The walk speed an item takes away (middle of its roll), 0 if none. */
    static double walkPenalty(ItemData info) {
        for (var v : info.stats()) {
            if (v.key().equals("walkSpeed")) {
                double mid = (v.min() + v.max()) / 2.0;
                return mid < 0 ? -mid : 0;
            }
        }
        return 0;
    }

    private static void build(Klass cls, int level) {
        int ahead = (int) Rules.sub("shopping", "aheadLevels", 5);
        int perSlot = (int) Rules.sub("shopping", "perSlot", 3);
        int earnedNow = Math.max(0, (Math.min(level, 101) - 1) * 2);
        // off-build skill points scale with what you have earned (10 at low level, ~30 at 100+)
        int maxOff = (int) Math.max(Rules.sub("shopping", "maxOffSkillPoints", 10), earnedNow * 0.15);
        List<String> preferred = Rules.list("shopping", "preferredSkills");
        // Mythics and raid gear are for endgame: excluded while levelling, allowed from lv 80
        boolean endgame = level >= Rules.sub("shopping", "endgameLevel", 80);
        List<String> badTiers = endgame ? List.of() : Rules.list("shopping", "excludeTiers");
        List<String> badSources = new ArrayList<>(Rules.list("shopping", "excludeSources"));
        if (endgame) {
            badSources.remove("RAID");
        }
        // The player's hard no-gos (rules.shopping.excludeAttackSpeeds / maxWalkSpeedPenalty)
        List<String> badSpeeds = Rules.list("shopping", "excludeAttackSpeeds");
        double maxWalkPenalty = Rules.sub("shopping", "maxWalkSpeedPenalty", 999);

        // What you wear now, per slot: the weakest piece is the one to replace
        Map<Slot, Double> current = new EnumMap<>(Slot.class);
        Slot weaponType = Slot.weaponOf(cls);
        List<Slot> slots = new ArrayList<>(List.of(SLOTS));
        if (weaponType != null) {
            slots.add(weaponType);
        }
        for (Slot t : slots) {
            double weakest = Double.MAX_VALUE;
            for (ItemStack w : AutoAdvice.worn(t)) {
                double s = AutoAdvice.scoreAny(w);
                if (Double.isNaN(s)) {
                    s = -1;
                }
                weakest = Math.min(weakest, s);
            }
            current.put(t, weakest == Double.MAX_VALUE ? -1 : weakest);
        }

        Map<Slot, List<Pick>> perType = new HashMap<>();
        GameState.current().allGear().forEach(info -> {
            Slot t = info.slot();
            if (!current.containsKey(t)) {
                return;
            }
            int lv = info.requirements().level();
            // window: a few levels ahead, never past the level cap; older gear only near the top
            int low = level >= 100 ? 90 : level - Math.max(3, level / 12);
            if (lv > level + ahead || lv < low) {
                return;
            }
            if (!badSpeeds.isEmpty() && info.base().attackSpeed().isPresent()
                    && badSpeeds.contains(info.base().attackSpeed().get())) {
                return;
            }
            if (walkPenalty(info) > maxWalkPenalty) {
                return;
            }
            if (badTiers.contains(info.tier().name())) {
                return;
            }
            Klass need = info.requirements().klass().orElse(t.klass());
            if (need != null && need != Klass.NONE && need != cls) {
                return;
            }
            // Skill points: what the item asks for must fit the points you will have by its level
            int earnedThen = Math.max(0, (Math.min(Math.max(lv, level), 101) - 1) * 2);
            int total = 0;
            int off = 0;
            for (Requirement.SkillReq req : info.requirements().skills()) {
                total += req.points();
                if (!preferred.contains(req.skill().name())) {
                    off += req.points();
                }
            }
            if (total > earnedThen || off > maxOff) {
                return;
            }
            String source = source(info);
            if (source == null || source.startsWith("UNKNOWN") && badSources.contains("UNKNOWN")
                    || badSources.stream().anyMatch(source::startsWith)) {
                return;
            }
            double s;
            double gain;
            Build.Plan plan = Build.plan();
            if (plan != null && plan.result().objective() > 0) {
                // Same damage model as the advice on your own items: % the best build gains
                Damage.Piece cand = Damage.piece(info);
                double best = Double.NaN;
                for (String slot : t == Slot.RING ? new String[] {"RING1", "RING2"}
                        : new String[] {Build.slotOf(t, 0)}) {
                    if (slot == null) {
                        continue;
                    }
                    double v = Build.trySlot(plan.pieces(), plan.allocation(), slot, cand, Math.max(level, lv),
                            Math.max(plan.earned(), (Math.min(Math.max(level, lv), 101) - 1) * 2));
                    if (!Double.isNaN(v) && (Double.isNaN(best) || v > best)) {
                        best = v;
                    }
                }
                if (Double.isNaN(best)) {
                    return;
                }
                s = (best / plan.result().objective() - 1) * 100 + Rules.communityBonus(info.name());
                String rs = Build.slotOf(t, 0);
                if (rs != null && Build.needsRespec(plan.pieces(), plan.allocation(), rs, cand,
                        Math.max(plan.earned(), (Math.min(Math.max(level, lv), 101) - 1) * 2))) {
                    s -= Rules.sub("shopping", "respecPenaltyPercent", 3);
                }
                gain = s;
                // not worth a purchase, unless what you have in that slot is getting old
                double bar = Rules.sub("shopping", "minGainPercent", 10);
                for (String slot : t == Slot.RING ? new String[] {"RING1", "RING2"}
                        : new String[] {Build.slotOf(t, 0)}) {
                    if (slot != null) {
                        bar = Math.min(bar, Build.gainThreshold(plan.pieces().get(slot), level));
                    }
                }
                if (gain < bar) {
                    return;
                }
            } else {
                s = AutoAdvice.score(info);
                gain = s - current.get(t);
                if (gain <= 0.5) {
                    return;
                }
            }
            perType.computeIfAbsent(t, k -> new ArrayList<>()).add(new Pick(info, t.name(), 0, s, gain, source));
        });

        List<Pick> all = new ArrayList<>();
        Map<String, Pick> names = new HashMap<>();
        boolean preferMarket = Rules.sub("shopping", "preferTradeMarket", 1) > 0;
        for (Slot t : slots) {
            List<Pick> sorted = new ArrayList<>(perType.getOrDefault(t, List.of()));
            sorted.sort(Comparator.comparingDouble(Pick::score).reversed());
            List<Pick> market = preferMarket ? sorted.stream().filter(p -> tradable(p.info())).toList() : sorted;
            for (int i = 0; i < Math.min(perSlot, market.size()); i++) {
                Pick p = market.get(i);
                Pick ranked = new Pick(p.info(), p.slot(), i + 1, p.score(), p.gain(), p.source());
                all.add(ranked);
                names.put(p.info().name(), ranked);
            }
            // The best you cannot buy, when it beats the best you can
            if (preferMarket) {
                double topMarket = market.isEmpty() ? Double.NEGATIVE_INFINITY : market.get(0).score();
                sorted.stream().filter(p -> !tradable(p.info()) && p.score() > topMarket).findFirst().ifPresent(p -> {
                    Pick alt = new Pick(p.info(), p.slot(), 0, p.score(), p.gain(), p.source());
                    all.add(alt);
                    names.putIfAbsent(p.info().name(), alt);
                });
            }
        }
        picks = List.copyOf(all);
        byName = Map.copyOf(names);
        GameState.current().requestPrices(names.keySet());   // fills in market prices, if the player has a key
    }

    static String sourceOf(ItemData info) {
        return source(info);
    }

    /** Where it comes from, "MERCHANT: Ragni" style; null when it cannot be had. */
    private static String source(ItemData info) {
        List<Obtain> obtain = GameState.current().obtain(info);
        if (obtain == null || obtain.isEmpty()) {
            return "UNKNOWN";
        }
        // prefer a source you can walk to over random loot
        Obtain best = obtain.get(0);
        for (Obtain o : obtain) {
            String n = o.type();
            if (n.equals("MERCHANT") || n.equals("QUEST") || n.equals("DUNGEON_MERCHANT")) {
                best = o;
                break;
            }
        }
        return best.type() + best.name().map(n -> ": " + n).orElse("");
    }

    static JsonObject json() {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Pick p : list()) {
            JsonObject j = new JsonObject();
            ItemData info = p.info();
            j.addProperty("slot", p.slot());
            j.addProperty("rank", p.rank());
            j.addProperty("tradable", tradable(info));
            if (p.alternative()) {
                j.addProperty("alternative", true);
            }
            j.addProperty("name", info.name());
            j.addProperty("tier", info.tier().name());
            j.addProperty("level", info.requirements().level());
            j.addProperty("score", Math.round(p.score() * 10) / 10.0);
            j.addProperty("gain", Math.round(p.gain() * 10) / 10.0);
            JsonObject skills = new JsonObject();
            for (var req : info.requirements().skills()) {
                skills.addProperty(req.skill().name(), req.points());
            }
            if (!skills.isEmpty()) {
                j.add("skillRequirements", skills);
            }
            info.requirements().quest().ifPresent(q -> j.addProperty("questRequirement", q));
            j.addProperty("source", p.source());
            j.addProperty("restriction", info.restriction());
            j.addProperty("powderSlots", info.powderSlots());
            Prices.Price price = GameState.current().price(info.name());
            if (price != null && price.typical() != null) {
                j.addProperty("price", Math.round(price.typical()));
                j.addProperty("priceText", Prices.fmt(price.typical()));
            }
            arr.add(j);
        }
        o.addProperty("count", arr.size());
        o.add("items", arr);
        o.addProperty("note", "best gear per slot that beats what you wear, at most "
                + (int) Rules.sub("shopping", "aheadLevels", 5) + " levels ahead; "
                + "score uses the rule weights with every stat at the middle of its range");
        return o;
    }

    /** True when this stack sits in your own inventory or armour (not a shop or chest). */
    static boolean isOwn(ItemStack stack) {
        for (ItemStack s : GameState.current().inventory()) {
            if (s == stack) {
                return true;
            }
        }
        return false;
    }
}
