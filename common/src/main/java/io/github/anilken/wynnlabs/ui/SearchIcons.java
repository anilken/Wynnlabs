package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.game.WynntilsItems;
import io.github.anilken.wynnlabs.search.Entry;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The real icon of a search entry, as the home page and the bank show items: Wynntils' guide
 * stack (the item with its full tooltip) when Wynntils is there, else the base item of the
 * cache's icon spec with its model number (the Wynncraft pack draws it; without the pack the
 * plain item shows). Made on the render thread on first use, at most {@link #MAX} kept.
 */
final class SearchIcons {

    private static final int MAX = 256;

    private final Map<String, ItemStack> stacks = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ItemStack> eldest) {
            return size() > MAX;
        }
    };

    ItemStack of(Entry e) {
        ItemStack s = stacks.get(e.name());
        if (s == null) {
            s = make(e);
            stacks.put(e.name(), s);
        }
        return s;
    }

    private static ItemStack make(Entry e) {
        if (GameState.current().running()) {
            try {
                ItemStack guide = WynntilsItems.guideStack(e.kind(), e.name());
                if (guide != null && !guide.isEmpty()) {
                    return guide;
                }
            } catch (LinkageError ignored) {
                // Wynntils changed: the icon spec below
            }
        }
        return fromSpec(e.icon());
    }

    /** The base item of the icon spec with its custom model number; paper when the spec names nothing known. */
    static ItemStack fromSpec(Entry.Icon icon) {
        Item item = Items.PAPER;
        if (icon != null && icon.id() != null) {
            try {
                Identifier id = Identifier.parse(icon.id());
                if (BuiltInRegistries.ITEM.containsKey(id)) {
                    item = BuiltInRegistries.ITEM.getValue(id);
                }
            } catch (RuntimeException ignored) {
                // an odd id: paper
            }
        }
        ItemStack s = new ItemStack(item);
        if (icon != null && !Float.isNaN(icon.model())) {
            s.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(icon.model()), List.of(), List.of(), List.of()));
        }
        return s;
    }
}
