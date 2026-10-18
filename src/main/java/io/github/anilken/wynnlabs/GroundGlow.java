package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Tier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which items on the ground glow, and in what colour: their rarity's colour (Unique yellow,
 * Rare pink, Legendary aqua, Fabled red, Mythic purple, Set green), gold for shopping-list
 * gear. Uses the game's own outline, so it shows through walls like any glowing entity.
 * Decided once a second per item; switches on the J menu's settings tab.
 */
public final class GroundGlow {

    private record Cached(long until, Integer colour) {}

    /** Per entity id; client thread only. Entries live about a second, staggered so they do not all expire in one frame. */
    private static final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<Cached> CACHE = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();

    private GroundGlow() {}

    /** The outline colour for this ground item, or null for no glow. */
    public static Integer colour(ItemEntity entity) {
        return colour(entity.getId(), entity.getItem());
    }

    /** The same for any entity showing an item (drops can be item displays too). */
    public static Integer colour(int entityId, ItemStack stack) {
        if (!Settings.on("groundGlow")) {
            return null;
        }
        long now = System.currentTimeMillis();
        Cached c = CACHE.get(entityId);
        if (c != null && now < c.until()) {
            return c.colour();
        }
        Integer colour = null;
        try {
            colour = decide(stack);
        } catch (Throwable ignored) {
            // no glow rather than a broken frame
        }
        if (CACHE.size() > 2000) {
            CACHE.clear();
        }
        CACHE.put(entityId, new Cached(now + 1000 + (entityId & 255), colour));
        return colour;
    }

    /** The tier whose colour the name uses, looking inside its parts too. */
    private static Tier tierFromColour(net.minecraft.network.chat.Component name) {
        Tier t = WynntilsItems.tierOf(name);
        if (t != null) {
            return t;
        }
        java.util.concurrent.atomic.AtomicReference<Tier> found = new java.util.concurrent.atomic.AtomicReference<>();
        name.visit((style, text) -> {
            if (found.get() == null && style.getColor() != null && !text.isBlank()) {
                for (Tier g : Tier.values()) {
                    Integer c = g.colour().getColor();
                    if (c != null && c == style.getColor().getValue()) {
                        found.set(g);
                        return Optional.of(Boolean.TRUE);
                    }
                }
            }
            return Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return found.get();
    }

    private static Integer decide(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Optional<ItemView> parsed = WynntilsItems.view(stack);
        Tier tier = null;
        String gearName = null;
        if (parsed.isPresent() && parsed.get().isGear()) {
            tier = parsed.get().data().tier();
            gearName = parsed.get().data().name();
        } else if (parsed.isPresent() && parsed.get().isBox()) {
            tier = parsed.get().box().tier();
        } else if (parsed.isPresent() && !parsed.get().plain()) {
            return null;                        // emeralds, potions, materials, powders: no glow
        } else {
            // Drops come with little data, so Wynntils only knows them as a plain item: look the
            // name up in the item database first (exact tier), then fall back to the name colour
            String plain = ItemJson.clean(stack.getHoverName().getString());
            var info = WynntilsItems.gearByName(plain);
            if (info != null) {
                if (Settings.on("groundGlowShop") && Shopping.find(info.name()) != null) {
                    return 0xFFAA00;
                }
                if (info.tier() == Tier.NORMAL && !Settings.on("groundGlowNormal")) {
                    return null;
                }
                Integer rgb = info.tier().colour().getColor();
                return rgb == null ? 0xFFFFFF : rgb;
            }
            // not recognised yet: only gear-looking drops by their name colour, never emeralds
            String name = stack.getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("emerald") || name.contains("zümrüt")
                    || stack.is(net.minecraft.world.item.Items.EMERALD)
                    || stack.is(net.minecraft.world.item.Items.EMERALD_BLOCK)) {
                return null;
            }
            tier = tierFromColour(stack.getHoverName());
            if (tier == Tier.SET) {
                return null;                    // green names are mostly emeralds and misc items
            }
        }
        if (tier == null) {
            return null;
        }
        if (gearName != null && Settings.on("groundGlowShop") && Shopping.find(gearName) != null) {
            return 0xFFAA00;                    // gold: on your shopping list
        }
        if (tier == Tier.NORMAL && !Settings.on("groundGlowNormal")) {
            return null;
        }
        Integer rgb = tier.colour().getColor();
        return rgb == null ? 0xFFFFFF : rgb;
    }
}
