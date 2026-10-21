package io.github.anilken.wynnlabs;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * How many orders the Trade Market lets the account have at once, and which items get them.
 *
 * <p>Wynncraft limits the orders open on the Trade Market (buy and sell orders count together)
 * by supporter rank. When the advice sends more items there than that, the ones that bring in
 * the most keep their Trade Market verdict; the rest go to the Blacksmith, and say why. The
 * ranking covers the bag and the banks you can open (the account bank and this character's
 * own), is built once whenever something it depends on changes ({@link Stamp}, the bank, the
 * slot count), and a tooltip only looks its item up.
 *
 * <p>Orders already open are not counted: Wynntils keeps no list of the player's own orders,
 * and the mod never opens a menu itself. Every slot counts as free.
 */
final class MarketSlots {

    private MarketSlots() {}

    /** The most the rule may set (rules.trade.marketSlots): no rank has more. */
    static final int MAX = 30;

    /**
     * Orders a supporter rank allows (the Wynncraft wiki): none and VIP 5, VIP+ 10, HERO 15,
     * HERO+ 18, CHAMPION 21. Unknown (null) or anything else: 5.
     */
    static int forRank(String rank) {
        if (rank == null) {
            return 5;
        }
        return switch (rank) {
            case "VIP_PLUS" -> 10;
            case "HERO" -> 15;
            case "HERO_PLUS" -> 18;
            case "CHAMPION" -> 21;
            default -> 5;
        };
    }

    /** The orders the advice may fill: the rule when set (1-30), else what the rank allows. */
    static int slots() {
        long set = Math.round(Rules.sub("trade", "marketSlots", 0));
        return set >= 1 ? (int) Math.min(MAX, set) : forRank(Wyn.supporterRank());
    }

    /** "Auto (HERO: 15)": what the rule's automatic setting comes to now. */
    static String autoText() {
        String rank = Wyn.supporterRank();
        String name = rank == null ? L.t("wynnlabs.rules.market_slots_unknown")
                : rank.equals("NONE") ? L.t("wynnlabs.rules.market_slots_no_rank") : rank.replace("_PLUS", "+");
        return L.t("wynnlabs.rules.market_slots_auto", name, forRank(rank));
    }

    /** "Auto: 15": the same in a settings row, where the rule's name must fit too. */
    static String autoShort() {
        return L.t("wynnlabs.rules.market_slots_auto_short", forRank(Wyn.supporterRank()));
    }

    /**
     * One item the advice sends to the Trade Market. {@code gain}: emeralds it should bring in
     * after the fee, NaN when unknown. {@code copies}: the orders it needs (one per piece of
     * gear or box; a stack of materials lists as one order).
     */
    record Candidate(Object id, double gain, int copies) {}

    /** Where a candidate ranks (1 = brings in the most) and whether it gets an order. */
    record Seat(int place, boolean fits) {}

    /**
     * Ranks the candidates, most emeralds first, those of unknown worth last; equal ones keep
     * their order (the bag before the bank). The first {@code free} orders fit. An id that comes
     * twice keeps its first seat.
     */
    static Map<Object, Seat> rank(List<Candidate> all, int free) {
        List<Candidate> order = new ArrayList<>(all);
        order.sort((a, b) -> Double.isNaN(a.gain()) || Double.isNaN(b.gain())
                ? Boolean.compare(Double.isNaN(a.gain()), Double.isNaN(b.gain()))
                : Double.compare(b.gain(), a.gain()));
        Map<Object, Seat> out = new HashMap<>();
        int place = 1;
        for (Candidate c : order) {
            out.putIfAbsent(c.id(), new Seat(place, place <= free));
            place += Math.max(1, c.copies());
        }
        return out;
    }

    /** True for a verdict that sends the item to the Trade Market. */
    static boolean toMarket(Advice.Entry e) {
        return e != null && e.verdict() == Verdict.SELL && "Trade Market".equals(Advice.sellPlace(e));
    }

    /** The note for an item left without an order: the Blacksmith, the slots you have, its place. */
    static String note(int slots, int place) {
        return L.n("wynnlabs.auto.market_full", slots, slots, ordinal(place));
    }

    /** "18th" / "18.": a place in the language the mod speaks. */
    static String ordinal(int n) {
        if (L.code().startsWith("tr")) {
            return n + ".";
        }
        int tens = n % 100;
        String end = tens >= 11 && tens <= 13 ? "th" : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + end;
    }

    // ---- the ranking of the bag and the bank ----

    /**
     * The verdicts the limit changed: bag stacks by identity, kept bank stacks by {@link Vault#id},
     * and by {@link ItemJson#keyOf} when every bag copy of an item was left out (for a stack moved
     * into a shop's slots, which is a new stack there).
     */
    private record Ranking(long key, long built, Map<ItemStack, Advice.Entry> bag, Map<String, Advice.Entry> bank,
            Map<String, Advice.Entry> byKey) {}

    private static volatile Ranking ranking;
    private static boolean building;                // a verdict asked while ranking: no ranking inside it

    /**
     * The stack's verdict under the limit: {@code e} itself, unless it sends the item to the
     * Trade Market and the item ranks below the orders you have. Only in the game: a recorded
     * one (tests, the preview) has no rank and no bank.
     */
    static Advice.Entry apply(ItemStack stack, Advice.Entry e) {
        if (!toMarket(e) || !GameState.current().running()) {
            return e;
        }
        Ranking r = current();
        if (r == null) {
            return e;
        }
        Advice.Entry cut = r.bag().get(stack);
        if (cut == null) {
            String id = Vault.idOf(stack);              // a stack in the bank page open now
            cut = id == null ? null : r.bank().get(id);
        }
        if (cut == null && shopOpen()) {
            cut = r.byKey().get(ItemJson.keyOf(stack));
        }
        return cut != null ? cut : e;
    }

    /** The note for a kept bank stack the limit sends to the Blacksmith, else null. */
    static String cutNote(String bankId) {
        if (!GameState.current().running()) {
            return null;
        }
        Ranking r = current();
        Advice.Entry cut = r == null ? null : r.bank().get(bankId);
        return cut == null ? null : cut.note();
    }

    /**
     * A shop or other container is open (not the bag, not the bank): items you put into its slots
     * leave the bag, so the ranking holds still until it closes.
     */
    private static boolean shopOpen() {
        try {
            var screen = net.minecraft.client.Minecraft.getInstance().screen;
            return screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                    && !(screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)
                    && !Vault.bankOpen();
        } catch (Throwable t) {
            return false;
        }
    }

    private static long key() {
        long k = Stamp.now();
        k = k * 31 + Stamp.bag;
        k = k * 31 + Vault.version;
        k = k * 31 + slots();
        return k * 31 + Profile.id().hashCode();
    }

    /** The ranking for now: rebuilt when its key changes, or after half a minute like the verdicts. */
    private static Ranking current() {
        long key = key();
        long now = System.currentTimeMillis();
        Ranking r = ranking;
        if (r != null && shopOpen()) {
            return r;                                   // items moved into the shop's slots keep their place
        }
        if (r != null && r.key() == key && now - r.built() < 30_000) {
            return r;
        }
        synchronized (MarketSlots.class) {
            r = ranking;
            if (building || r != null && r.key() == key && now - r.built() < 30_000) {
                return r;
            }
            building = true;
            long t = Perf.start();
            try {
                r = build(key, now);
            } catch (Throwable ignored) {
                r = new Ranking(key, now, Map.of(), Map.of(), Map.of());  // no limit rather than a broken tooltip
            } finally {
                building = false;
            }
            Perf.end("advice.marketSlots", t);
            ranking = r;
            return r;
        }
    }

    private static Ranking build(long key, long now) {
        GameState state = GameState.current();
        List<Candidate> all = new ArrayList<>();
        for (ItemStack s : state.inventory()) {
            if (s == null || s.isEmpty() || !toMarket(AutoAdvice.uncapped(s))) {
                continue;
            }
            double gain = AutoAdvice.gain(s);
            String kind = state.view(s).map(AutoAdvice::kindOf).orElse("unknown");
            all.add(new Candidate(s, Double.isNaN(gain) ? priced(ItemJson.clean(s.getHoverName().getString())) : gain,
                    copies(kind, s.getCount())));
        }
        for (Vault.Group g : Vault.groups()) {
            for (Vault.Stored s : g.places()) {
                if (Vault.reachable(s.page()) && toMarket(new Advice.Entry(s.verdict(), s.note()))) {
                    all.add(new Candidate(Vault.id(s), bankGain(s), copies(s.kind(), s.count())));
                }
            }
        }
        int slots = slots();
        Map<ItemStack, Advice.Entry> bag = new IdentityHashMap<>();
        Map<String, Advice.Entry> bank = new HashMap<>();
        Map<String, Advice.Entry> byKey = new HashMap<>();
        java.util.Set<String> seated = new java.util.HashSet<>();
        if (all.stream().mapToInt(c -> Math.max(1, c.copies())).sum() > slots) {
            Map<Object, Seat> seats = rank(all, slots);
            for (Candidate c : all) {
                Seat seat = seats.get(c.id());
                String k = c.id() instanceof ItemStack st ? ItemJson.keyOf(st) : null;
                if (seat.fits()) {
                    if (k != null) {
                        seated.add(k);
                    }
                    continue;
                }
                Advice.Entry cut = new Advice.Entry(Verdict.SELL, note(slots, seat.place()));
                if (c.id() instanceof ItemStack st) {
                    bag.put(st, cut);
                    if (k != null) {
                        byKey.putIfAbsent(k, cut);
                    }
                } else {
                    bank.put((String) c.id(), cut);
                }
            }
        }
        seated.forEach(byKey::remove);
        return new Ranking(key, now, bag, bank, byKey);
    }

    /** A kept stack's worth: judged again from the kept item when it can be, else its price. */
    private static double bankGain(Vault.Stored s) {
        if (s.item() != null) {
            ItemStack st = Vault.stackOf(s);
            if (toMarket(AutoAdvice.uncapped(st)) && !Double.isNaN(AutoAdvice.gain(st))) {
                return AutoAdvice.gain(st);
            }
        }
        return priced(s.name());
    }

    /** The item's market price after the fee, NaN when it has none. */
    private static double priced(String name) {
        Prices.Price p = GameState.current().price(name);
        return p == null || p.typical() == null ? Double.NaN : p.typical() * (1 - AutoAdvice.marketFee() / 100.0);
    }

    /** Orders a stack needs: one per piece of gear or box, one for a stack of anything else. */
    private static int copies(String kind, int count) {
        return "gear".equals(kind) || "gearbox".equals(kind) ? Math.max(1, count) : 1;
    }
}
