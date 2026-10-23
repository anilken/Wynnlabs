package io.github.anilken.wynnlabs.build;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.endgame.PoolView;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.game.ItemJson;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.market.Shopping;
import io.github.anilken.wynnlabs.market.Vault;
import io.github.anilken.wynnlabs.model.ItemData;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where a build's pieces come from, for the build window and the panel: the item database's
 * piece by name, what you wear and have (re-read each second), this week's raid pools, the
 * Trade Market price, a quest, merchant, chest or drop.
 */
public final class BuildSources {

    private BuildSources() {}

    private static Map<String, String> wornCache = Map.of();
    private static long wornAt;

    /** What you wear now, by build slot (names); read again each second. */
    public static Map<String, String> worn() {
        long now = System.currentTimeMillis();
        if (now - wornAt < 1000) {
            return wornCache;
        }
        Map<String, String> out = new LinkedHashMap<>();
        Build.Plan p = Build.plan();
        if (p != null && p.worn() != null) {
            p.worn().forEach((s, piece) -> {
                if (piece != null) {
                    out.put(s, piece.name());
                }
            });
        }
        wornCache = out;
        wornAt = now;
        return out;
    }

    /** The item database's piece of this name (looked up once). */
    private static final Map<String, java.util.Optional<ItemData>> INFO = new java.util.concurrent.ConcurrentHashMap<>();

    public static ItemData itemOf(String name) {
        return INFO.computeIfAbsent(name, n -> GameState.current().allGear().filter(i -> i.name().equals(n)).findFirst()).orElse(null);
    }

    private static java.util.Set<String> haveCache = java.util.Set.of();
    private static long haveAt;

    /** Names of what you have in the bag and bank; read again each second. */
    public static java.util.Set<String> haveNames() {
        long now = System.currentTimeMillis();
        if (now - haveAt < 1000) {
            return haveCache;
        }
        haveAt = now;
        java.util.Set<String> out = new java.util.HashSet<>();
        haveCache = out;
        try {
            for (ItemStack s : GameState.current().inventory()) {
                if (!s.isEmpty()) {
                    out.add(ItemJson.clean(s.getHoverName().getString()).trim());
                }
            }
            for (Vault.Group gr : Vault.groups()) {
                out.add(gr.name());
            }
        } catch (Throwable ignored) {
            // what was read
        }
        return out;
    }

    /**
     * The icon of where a piece comes from, by the same order as {@link #source}: this week's
     * pool (the raid trophy), the Trade Market (a coin), a merchant (the cart), a quest or a quest
     * item (the scroll), a cave or loot chest (the box), mobs (the sword), a dungeon (the
     * skull); a question mark when nothing is known.
     */
    public static String sourceIcon(String name) {
        if (poolOf(name) != null) {
            return "trophy";
        }
        ItemData info = itemOf(name);
        if (info == null) {
            return "question-mark";
        }
        boolean tradable = Shopping.tradable(info);
        if (tradable) {
            Prices.Price p = GameState.current().price(name);
            if (p != null && p.typical() != null) {
                return "coin";
            }
        }
        String src = Shopping.origin(info);
        boolean known = src != null && !src.isBlank() && !src.toUpperCase(Locale.ROOT).startsWith("UNKNOWN");
        if (known) {
            return kindIcon(Shopping.kind(src));
        }
        if ("QUEST_ITEM".equals(info.restriction())) {
            return "script";
        }
        return tradable ? "coin" : "question-mark";
    }

    /** The badge of a {@link Shopping#kind} ("merchant" → the cart); the search tab's sources use it too. */
    public static String kindIcon(String kind) {
        return switch (kind) {
            case "merchant" -> "shopping-cart";
            case "quest" -> "script";
            case "chest" -> "box";
            case "drop" -> "sword";
            case "dungeon" -> "skull";
            default -> "question-mark";
        };
    }

    /**
     * One more line about where a piece comes from, under {@link #sourceText}, or null: the mob
     * area of a drop or the region of a chest (the line itself names neither), or, when the
     * line is the Trade Market's price, the place it also comes from.
     */
    public static String sourceDetail(String name) {
        if (poolOf(name) != null) {
            return null;
        }
        ItemData info = itemOf(name);
        if (info == null) {
            return null;
        }
        String src = Shopping.origin(info);
        if (src == null || src.isBlank() || src.toUpperCase(Locale.ROOT).startsWith("UNKNOWN")) {
            return null;
        }
        if (source(name)[1] != null) {
            return L.t("wynnlabs.homev.source_also", Shopping.where(src));
        }
        String[] parts = src.split(": ", 2);
        String place = parts.length > 1 ? parts[1].trim() : "";
        if (place.isEmpty()) {
            return null;
        }
        return switch (parts[0]) {
            case "NORMAL_MOB_DROP", "MOB_REGION" -> L.t("wynnlabs.homev.source_area", place);
            case "CAVE_LOOT_CHEST", "CAVE_COMPLETION", "LOOT_CHEST" -> L.t("wynnlabs.homev.source_region", place);
            default -> null;
        };
    }

    /** Where a piece comes from, as one line (a Trade Market price written out). */
    public static String sourceText(String name) {
        Object[] s = source(name);
        return s[1] != null ? L.t("wynnlabs.results.market", Prices.fmt((double) s[1])) : (String) s[0];
    }

    /**
     * Where a piece comes from: {text, Trade Market price (null when none)}: this week's raid
     * pool, else its Trade Market price, a quest, its merchant, chest or drop; with no known
     * source, a quest item as a quest reward, an untradable piece as such, a tradable one as
     * the Trade Market with no price; "price unknown" only when nothing is known.
     */
    public static Object[] source(String name) {
        String pool = poolOf(name);
        if (pool != null) {
            return new Object[] {L.t("wynnlabs.results.in_pool", pool), null};
        }
        ItemData info = itemOf(name);
        if (info == null) {
            return new Object[] {L.t("wynnlabs.results.price_unknown"), null};
        }
        String src = Shopping.origin(info);
        boolean tradable = Shopping.tradable(info);
        if (tradable) {
            Prices.Price p = GameState.current().price(name);
            if (p != null && p.typical() != null) {
                return new Object[] {"", p.typical()};
            }
        }
        boolean known = src != null && !src.isBlank() && !src.toUpperCase(Locale.ROOT).startsWith("UNKNOWN");
        if (known && src.startsWith("QUEST")) {
            return new Object[] {L.t("wynnlabs.results.quest", Shopping.where(src)), null};
        }
        if (known) {
            return new Object[] {L.cap(Shopping.where(src)), null};
        }
        if ("QUEST_ITEM".equals(info.restriction())) {
            return new Object[] {L.t("wynnlabs.results.quest_reward"), null};
        }
        if (!tradable) {
            return new Object[] {L.t("wynnlabs.results.untradable"), null};
        }
        return new Object[] {L.t("wynnlabs.results.market_unknown"), null};
    }

    private static Map<String, String> pools;
    private static long poolsAt;

    /** The raid whose pool this week holds the item, or null. */
    private static String poolOf(String name) {
        long now = System.currentTimeMillis();
        if (pools == null || now - poolsAt > 30_000) {
            Map<String, String> m = new java.util.HashMap<>();
            try {
                List<PoolView.Region> regions = PoolView.raidRegions();
                for (PoolView.Region reg : regions == null ? List.<PoolView.Region>of() : regions) {
                    for (PoolView.Group gr : PoolView.raid(reg.name(), false)) {
                        for (PoolView.Row row : gr.rows()) {
                            m.putIfAbsent(row.item().name(), reg.name());
                        }
                    }
                }
            } catch (Throwable ignored) {
                // no pool data
            }
            pools = m;
            poolsAt = now;
        }
        return pools.get(name);
    }
}
