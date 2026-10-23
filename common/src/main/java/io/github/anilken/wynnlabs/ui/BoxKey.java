package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.Platform;
import io.github.anilken.wynnlabs.game.WynntilsItems;
import io.github.anilken.wynnlabs.market.Shopping;
import io.github.anilken.wynnlabs.mixin.ContainerHoverAccessor;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.Tier;
import io.github.anilken.wynnlabs.search.BoxMarket;
import io.github.anilken.wynnlabs.search.Entry;
import io.github.anilken.wynnlabs.search.ItemIndex;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * The "Box contents" key (default T, rebindable in Controls): in any container screen or the
 * inventory, over an unidentified gear box it opens the box contents window ({@link BoxScreen})
 * over that screen; over any other item the Trade Market sells it opens the same window for that
 * one item. Anywhere else, and over an untradable item, the press goes on as without the mod (no
 * screen: chat opens; a text box: the letter is typed). It only reads the hovered stack.
 */
public final class BoxKey {

    private static KeyMapping key;

    private BoxKey() {}

    /** The screen the key was pressed on, as far as the key cares. */
    enum Where { NONE, CHAT, OWN, CONTAINER, OTHER }

    /** What a press opens: the window for a box, or for one item. */
    record Pick(ItemView.Box box, BoxScreen.Single item) {}

    /** Adds the key to Controls in the mod's category. With no screen open it does nothing: T opens chat. */
    public static void register(Platform platform, KeyMapping.Category category) {
        KeyMapping k = platform.registerKey(new KeyMapping("key.wynnlabs.box_guide", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_T, category));
        key = k;
        platform.onEndTick(client -> {
            while (k.consumeClick()) {
                // presses in the world belong to chat (T); only the screen key below acts
            }
        });
    }

    /**
     * What to open, or null when the press is not ours: only in a container screen, with no text
     * box focused, over a gear box or over an item {@code tradable} accepts.
     */
    static Pick opensFor(Where where, boolean typing, Optional<ItemView> hovered, Predicate<ItemView> tradable) {
        if (where != Where.CONTAINER || typing || hovered == null || hovered.isEmpty()) {
            return null;
        }
        ItemView v = hovered.get();
        if (v.isBox()) {
            return v.box() == null ? null : new Pick(v.box(), null);
        }
        BoxScreen.Single one = single(v);
        return one != null && tradable.test(v) ? new Pick(null, one) : null;
    }

    /** One item as the window shows it; null for what is never listed (crafted gear, skill points, menu items). */
    static BoxScreen.Single single(ItemView v) {
        switch (v.kind()) {
            case GEAR -> {
                ItemData d = v.data();
                if (d == null) {
                    return null;
                }
                Klass k = d.requirements().klass().orElse(d.slot().klass());
                return new BoxScreen.Single(new BoxMarket.Item(d.name(), d.slot(), d.tier(), d.level(), k, 0, true),
                        v.rolled() == null ? null : v.rolled().overall(), v.unidentified());
            }
            case INGREDIENT, MATERIAL, POWDER, POTION, DUNGEON_KEY, OTHER -> {
                if (v.name() == null || v.name().isBlank()) {
                    return null;
                }
                int level = v.level() == null ? 0 : v.level();
                return new BoxScreen.Single(new BoxMarket.Item(v.name(), io.github.anilken.wynnlabs.model.Slot.UNKNOWN, Tier.NORMAL, level, null, 0, true),
                        null, false);
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * Whether the Trade Market sells it: gear by its restriction; anything else by the search
     * index's entry (an unknown "other" item is left alone, an unknown ingredient or material is not).
     */
    private static boolean tradable(ItemView v) {
        if (v.isGear()) {
            return v.data() != null && Shopping.tradable(v.data());
        }
        Entry e = ItemIndex.get().byName(v.name());
        if (e != null) {
            return e.tradable();
        }
        return v.kind() != ItemView.Kind.OTHER;
    }

    /** A press while a screen is open; true when it opened the window (the screen then never sees it). */
    public static boolean onScreenKey(Screen screen, KeyEvent event) {
        if (screen == null || key == null || !key.matches(event)) {
            return false;
        }
        Where where = screen instanceof ChatScreen ? Where.CHAT
                : screen instanceof BoxScreen || screen instanceof RoadmapScreen ? Where.OWN
                : screen instanceof AbstractContainerScreen<?> ? Where.CONTAINER : Where.OTHER;
        if (where != Where.CONTAINER) {
            return false;
        }
        Slot slot = ((ContainerHoverAccessor) screen).wynnlabs$hoveredSlot();
        Optional<ItemView> view = slot == null || !slot.hasItem() ? Optional.empty() : WynntilsItems.view(slot.getItem());
        Pick p = opensFor(where, screen.getFocused() instanceof EditBox, view, BoxKey::tradable);
        if (p == null) {
            return false;
        }
        Minecraft.getInstance().setScreen(p.box() != null ? new BoxScreen(p.box(), screen)
                : new BoxScreen(p.item(), slot.getItem().copy(), screen));
        return true;
    }
}
