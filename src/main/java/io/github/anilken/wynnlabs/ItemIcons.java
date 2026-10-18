package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.PoolItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.TooltipDisplay;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Draws a pool item's icon (raid-lootrun-tracking, design decision 11): the game's own item, a pack
 * texture, or a glyph, tried in that order. The only new class of group 8 that names Minecraft types;
 * no Wynntils type.
 *
 * <p>Source 1 builds the item the way the game shows it: the base item of {@link PoolIcons.Ref}
 * with the model table's float as its {@code CUSTOM_MODEL_DATA} (the Wynncraft pack dispatches its
 * item models on that float), drawn with {@link GuiGraphics#renderItem}. It is used only while the
 * pack is loaded (without it a potion would show a water bottle). Stacks are built on the client
 * thread on first use and kept per base item and model key until {@link #clear()}.
 *
 * <p>Read-only: nothing here touches the game's state; it only draws.
 *
 * <p>API contract of group 8 (design.md): the signatures below stay as they are.
 */
final class ItemIcons {

    /** Which source drew. */
    enum Drawn { ITEM, TEXTURE, GLYPH }

    /** A model the Wynncraft pack has and vanilla does not: its presence means the pack is loaded. */
    private static final Identifier PACK_PROBE =
            Identifier.withDefaultNamespace("models/item/wynn/template/ability_tree/aspect.json");
    private static final String SHINY_LAYER = "wynn/gui/layer/shiny";
    /** The {@code PAPER} wash over a dimmed icon (another class's aspect). */
    private static final int WASH = 0x99000000 | (Theme.PAPER & 0xFFFFFF);

    /** Base item + model key → stack ({@link ItemStack#EMPTY} when it cannot be built). Client thread only. */
    private static final Map<String, ItemStack> STACKS = new HashMap<>();
    /** The pack check of this menu opening; null until checked. */
    private static Boolean pack;

    private ItemIcons() {}

    /** Draws the icon at {@code size} 16 or 12 and reports which source drew. */
    static Drawn draw(GuiGraphics g, PoolItem item, Klass aspectClass, int x, int y, int size, boolean dim) {
        PoolIcons.Ref ref = PoolIcons.resolve(item, aspectClass);
        Drawn drawn = Drawn.GLYPH;
        ItemStack stack = packLoaded() ? stack(ref) : ItemStack.EMPTY;
        if (!stack.isEmpty()) {
            if (size == 16) {
                g.renderItem(stack, x, y);
            } else {
                g.pose().pushMatrix();
                g.pose().translate(x, y);
                g.pose().scale(size / 16f, size / 16f);
                g.renderItem(stack, 0, 0);
                g.pose().popMatrix();
            }
            drawn = Drawn.ITEM;
        } else if (texture(g, ref, x, y, size)) {
            drawn = Drawn.TEXTURE;
        } else {
            glyph(g, ref, x, y, size);
        }
        if (item != null && item.shiny()) {
            shinyMark(g, x, y, size);
        }
        if (dim) {
            g.fill(x, y, x + size, y + size, WASH);
        }
        if (LayoutCheck.on()) {
            LayoutCheck.record(g.pose(), x, y, size, size, "icon/" + (item == null ? "?" : item.name()), false);
        }
        return drawn;
    }

    /** Whether the Wynncraft pack is loaded; checked once after each {@link #clear()}. */
    static boolean packLoaded() {
        Boolean p = pack;
        if (p == null) {
            try {
                p = Minecraft.getInstance().getResourceManager().getResource(PACK_PROBE).isPresent();
            } catch (RuntimeException e) {
                p = false;
            }
            pack = p;
            if (p) {
                ModelData.load(wynntilsCache());     // the table only matters with the pack
            }
        }
        return p;
    }

    /** Drops cached stacks and the pack check. */
    static void clear() {
        STACKS.clear();
        pack = null;
    }

    // ---- source 1: the game's own item ----

    private static ItemStack stack(PoolIcons.Ref ref) {
        if (!ModelData.ready()) {
            ModelData.load(wynntilsCache());     // no-op while it runs; looked for again while missing
        }
        if (!PoolIcons.stackable(ref)) {
            return ItemStack.EMPTY;
        }
        String key = ref.itemId() + "|" + ref.modelKey();
        ItemStack s = STACKS.get(key);
        if (s == null) {
            s = build(ref);
            STACKS.put(key, s);
        }
        return s;
    }

    private static ItemStack build(PoolIcons.Ref ref) {
        Item item = item(ref.itemId());
        if (item == null) {
            return ItemStack.EMPTY;
        }
        try {
            ItemStack s = new ItemStack(item);
            if (!ref.modelKey().isEmpty()) {
                float f = ModelData.of(ref.modelKey());
                if (Float.isNaN(f)) {
                    return ItemStack.EMPTY;
                }
                s.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(f), List.of(), List.of(), List.of()));
            }
            s.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(true, new LinkedHashSet<>()));
            return s;
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    /** The base items {@link PoolIcons} names; null for any other. */
    private static Item item(String id) {
        return switch (id) {
            case PoolIcons.POTION -> Items.POTION;
            case "minecraft:leather_helmet" -> Items.LEATHER_HELMET;
            case "minecraft:leather_chestplate" -> Items.LEATHER_CHESTPLATE;
            case "minecraft:leather_leggings" -> Items.LEATHER_LEGGINGS;
            case "minecraft:leather_boots" -> Items.LEATHER_BOOTS;
            case PoolIcons.PLAYER_HEAD -> Items.PLAYER_HEAD;
            case PoolIcons.EMERALD -> Items.EMERALD;
            case PoolIcons.EMERALD_BLOCK -> Items.EMERALD_BLOCK;
            case PoolIcons.LIQUID_EMERALD -> Items.EXPERIENCE_BOTTLE;
            default -> null;
        };
    }

    private static Path wynntilsCache() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("wynntils").resolve("cache");
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- source 2: a pack texture ----

    private static boolean texture(GuiGraphics g, PoolIcons.Ref ref, int x, int y, int size) {
        String path = ref.texture();
        if (path.isEmpty()) {
            return false;
        }
        if (ref.tint() == 0) {
            return WynnTex.draw(g, path, x, y, size, size);
        }
        int[] s = WynnTex.size(path);                // a grey powder, in its element's colour
        if (s[0] == 0) {
            return false;
        }
        int frame = s[1] > s[0] && s[1] % s[0] == 0 ? s[0] : s[1];
        g.blit(RenderPipelines.GUI_TEXTURED, WynnTex.id(path), x, y, 0, 0, size, size, s[0], frame, s[0], s[1], ref.tint());
        return true;
    }

    // ---- source 3: a glyph ----

    /** A rounded square in the rarity (or class) colour with the kind's letter in {@code PAPER}. Never fails. */
    private static void glyph(GuiGraphics g, PoolIcons.Ref ref, int x, int y, int size) {
        int c = ref.glyphColour();
        g.fill(x + 2, y, x + size - 2, y + size, c);
        g.fill(x + 1, y + 1, x + size - 1, y + size - 1, c);
        g.fill(x, y + 2, x + size, y + size - 2, c);
        Font font = Minecraft.getInstance().font;
        String letter = String.valueOf(ref.glyphLetter());
        int tx = x + (size - font.width(letter) + 1) / 2;
        int ty = y + (size - 7) / 2;
        g.drawString(font, letter, tx, ty, Theme.PAPER, false);
    }

    // ---- the shiny mark ----

    /** The pack's shiny layer over the icon, else a small gold star in its top right corner. */
    private static void shinyMark(GuiGraphics g, int x, int y, int size) {
        if (WynnTex.draw(g, SHINY_LAYER, x, y, size, size)) {
            return;
        }
        int cx = x + size - 3;
        int cy = y + 2;
        g.fill(cx - 2, cy - 1, cx + 3, cy + 2, Theme.GOLD_EDGE);
        g.fill(cx - 1, cy - 2, cx + 2, cy + 3, Theme.GOLD_EDGE);
        g.fill(cx - 1, cy, cx + 2, cy + 1, Theme.GOLD);
        g.fill(cx, cy - 1, cx + 1, cy + 2, Theme.GOLD);
    }
}
