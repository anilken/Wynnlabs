package io.github.anilken.wynnlabs.advice;

import io.github.anilken.wynnlabs.build.GearScore;
import io.github.anilken.wynnlabs.core.Perf;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.core.Stamp;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.game.ItemJson;
import io.github.anilken.wynnlabs.game.WornGear;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.Slot;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Instant verdicts from {@link Rules}, so not every item needs a question to the assistant.
 *
 * <p>Fixed rules first (names.&lt;item&gt;, kinds.&lt;kind&gt;), then plain checks: wrong class,
 * far below your level, not yet wearable, skill points missing, or better or worse than what
 * you wear in the same slot, scored with the rule weights. Advice the assistant wrote for one
 * item always wins. Items the rules cannot judge (equipped gear, close calls) get no line.
 *
 * <p>This class caches the verdicts and hands each kind of item to its own class:
 * {@link GearAdvice} (gear and crafted gear), {@link BoxAdvice} (gear boxes) and
 * {@link OtherItemAdvice} (skill points, powders, dungeon keys, ingredients and materials).
 * {@link SellAdvice} picks the selling route, {@link WornGear} reads what you wear,
 * {@link GearScore} scores gear and {@link AltCharacters} looks at your other characters.
 */
public final class AutoAdvice {

    private AutoAdvice() {}

    /** {@code gain}: what a Trade Market verdict expects to bring in ({@link #market}), NaN otherwise. */
    private record Cached(long until, int stamp, Advice.Entry entry, double gain) {}

    private static final java.util.Map<ItemStack, Cached> CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * {@link #decide}, remembered until something it depends on changes ({@link Stamp}: rules,
     * settings, prices, advice, what you carry and wear, your level), and at most about half a
     * minute for what the stamp does not cover, staggered per stack so a full menu never
     * expires in one frame.
     */
    public static Advice.Entry cached(ItemStack stack) {
        return MarketSlots.apply(stack, raw(stack).entry());
    }

    /** {@link #cached} before the Trade Market's order limit: what {@link MarketSlots} ranks. */
    public static Advice.Entry uncapped(ItemStack stack) {
        return raw(stack).entry();
    }

    /** Emeralds the stack's Trade Market verdict expects after the fee (and a reveal), NaN when unknown. */
    static double gain(ItemStack stack) {
        return raw(stack).gain();
    }

    private static Cached raw(ItemStack stack) {
        long now = System.currentTimeMillis();
        int stamp = Stamp.now();
        Cached c = CACHE.get(stack);
        if (c != null && now < c.until() && c.stamp() == stamp) {
            return c;
        }
        long t = Perf.start();                  // a miss: /perf counts them as advice.decide
        double[] noted = GAIN.get();
        double outer = noted[0];                // a verdict that asks for another stack's keeps its own
        noted[0] = Double.NaN;
        Advice.Entry e = decide(stack);
        double gain = noted[0];
        noted[0] = outer;
        Perf.end("advice.decide", t);
        c = new Cached(now + 30_000 + (System.identityHashCode(stack) & 4095), stamp, e, gain);
        CACHE.put(stack, c);
        return c;
    }

    /** The gain the verdict being decided on this thread noted with {@link #market}. */
    private static final ThreadLocal<double[]> GAIN = ThreadLocal.withInitial(() -> new double[] {Double.NaN});

    /**
     * A verdict that sends the item to the Trade Market, with what it should bring in after the
     * fee: {@link MarketSlots} ranks these when there are more of them than market orders.
     */
    static Advice.Entry market(Advice.Entry e, double gain) {
        GAIN.get()[0] = gain;
        return e;
    }

    /** Rolled at least rules.trade.keepRollPercent overall (0 turns it off): never for the Blacksmith. */
    static boolean highRoll(ItemView gear) {
        double min = Rules.sub("trade", "keepRollPercent", 80);
        ItemView.Rolled r = gear.rolled();
        return min > 0 && r != null && r.hasOverall() && r.overallPercent() >= min;
    }

    /** True when this stack is what you wear (armour, accessory or your weapon). */
    static boolean isWorn(ItemStack stack) {
        GameState state = GameState.current();
        if (!state.inGame() || stack == null || stack.isEmpty()) {
            return false;
        }
        Optional<ItemView> p = state.view(stack);
        Slot type = p.isPresent() && p.get().isGear() ? p.get().data().slot()
                : p.isPresent() && p.get().isCrafted() ? p.get().crafted().slot() : null;
        if (type == null) {
            return false;
        }
        for (ItemStack w : WornGear.worn(type)) {
            if (w == stack) {
                return true;
            }
        }
        return false;
    }

    /**
     * What a verdict needs from the stack besides the mod's reading of it: its shown name
     * (cleaned) and count, and the stack itself, to tell what you wear and carry apart from a
     * copy elsewhere. {@code stack} may be null for an item that is in neither (a bank page).
     */
    public record StackFacts(ItemStack stack, String name, int count) {

        static StackFacts of(ItemStack stack) {
            return new StackFacts(stack, ItemJson.clean(stack.getHoverName().getString()), stack.getCount());
        }
    }

    public static Advice.Entry decide(ItemStack stack) {
        GameState state = GameState.current();
        if (!state.inGame() || stack == null || stack.isEmpty()) {
            return null;
        }
        try {
            Optional<ItemView> parsed = state.view(stack);
            if (parsed.isEmpty()) {
                return null;
            }
            return decide(parsed.get(), StackFacts.of(stack));
        } catch (Throwable ignored) {
            // no automatic advice rather than a broken tooltip
        }
        return null;
    }

    /** The verdict for an item the mod has read; null when the rules cannot judge it. */
    public static Advice.Entry decide(ItemView item, StackFacts stack) {
        try {
            Advice.Entry fixed = Rules.fixed("names", nameOf(item, stack));
            if (fixed == null) {
                fixed = Rules.fixed("kinds", kindOf(item));
            }
            if (fixed != null) {
                return fixed;
            }
            GameState state = GameState.current();
            Klass cls = state.classType();
            int level = state.level();
            switch (item.kind()) {
                case GEAR -> {
                    return GearAdvice.gear(stack, item, cls, level);
                }
                case BOX -> {
                    return BoxAdvice.box(item.box(), cls, level);
                }
                case CRAFTED -> {
                    return GearAdvice.crafted(stack.stack(), item.crafted(), cls, level);
                }
                case SKILL_POINT -> {
                    return OtherItemAdvice.skillButton(item.skillPoint().skill());
                }
                case POWDER -> {
                    return OtherItemAdvice.powder(item.powder());
                }
                case DUNGEON_KEY -> {
                    return OtherItemAdvice.dungeonKey(item.dungeonKey(), level, stack);
                }
                case INGREDIENT, MATERIAL -> {
                    return OtherItemAdvice.craftingItem(item, stack);
                }
                default -> {
                }
            }
        } catch (Throwable ignored) {
            // no automatic advice rather than a broken tooltip
        }
        return null;
    }

    /** "gear", "gearbox", else the item's kind from its reader's name ("ingredient", "emeraldpouch" …). */
    public static String kindOf(ItemView item) {
        if (item.isGear()) {
            return "gear";
        }
        if (item.isBox()) {
            return "gearbox";
        }
        return item.type().replace("Item", "").toLowerCase(Locale.ROOT);
    }

    private static String nameOf(ItemView item, StackFacts stack) {
        if (item.isGear()) {
            return item.data().name();
        }
        return stack.name();
    }

    public static String nice(String upper) {
        String n = upper.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    public static String nice(Klass c) {
        if (c == null) {
            return "?";
        }
        String n = c.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    // ---- kept for Roadmap, Build and RoadmapScreen until they are split too ---------------

    /** {@link WornGear#armour}. */
    public static ItemStack armour(net.minecraft.world.entity.player.Player player, EquipmentSlot slot) {
        return WornGear.armour(player, slot);
    }

    /** {@link WornGear#worn}. */
    public static List<ItemStack> worn(Slot type) {
        return WornGear.worn(type);
    }

    /** {@link WornGear#bestWeapon}. */
    public static ItemStack bestWeapon() {
        return WornGear.bestWeapon();
    }

    /** {@link GearScore#scoreAny}. */
    public static double scoreAny(ItemStack stack) {
        return GearScore.scoreAny(stack);
    }

    /** {@link GearScore#score(ItemData)}. */
    public static double score(ItemData info) {
        return GearScore.score(info);
    }

    /** {@link OtherItemAdvice#powderPicks}. */
    public static List<String> powderPicks(boolean weapon, int free) {
        return OtherItemAdvice.powderPicks(weapon, free);
    }
}
