package io.github.anilken.wynnlabs.advice;

import io.github.anilken.wynnlabs.core.Platform;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.search.Entry;
import io.github.anilken.wynnlabs.search.ItemIndex;
import io.github.anilken.wynnlabs.search.ItemNames;
import io.github.anilken.wynnlabs.search.ItemWiki;
import io.github.anilken.wynnlabs.search.SourceText;
import io.github.anilken.wynnlabs.search.Sources;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The tooltip's one "From: …" line (setting {@code sourceHint}): the easiest way to get the item
 * as the search tab ranks it. Kept per item name until prices or the index change, so a frame
 * costs a name clean-up and one map lookup; the wiki is only peeked at, never fetched from here.
 */
public final class SourceHint {

    private static final int MAX = 512;
    private static final String NONE = "";
    private static final Map<String, String> LINES = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX;
        }
    };
    private static ItemIndex linesFor;
    private static int pricesFor;

    private SourceHint() {}

    /** The line for this stack, or null (setting off, an unknown name, nothing known). */
    public static Component line(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !Settings.on("sourceHint")) {
            return null;
        }
        ItemIndex index = ItemIndex.get();
        if (index.missing() != null) {
            ItemIndex.startLoading(Platform.get().gameDir(), io.github.anilken.wynnlabs.core.Dirs.config().resolve("chests.json"));
            return null;
        }
        String text = text(index, stack.getHoverName().getString());
        return text == null ? null : Component.literal(text).withStyle(ChatFormatting.DARK_GRAY);
    }

    /** The line's text for a hovered name, from the cache or worked out once. */
    static synchronized String text(ItemIndex index, String hover) {
        if (index != linesFor || Prices.version != pricesFor) {
            LINES.clear();
            linesFor = index;
            pricesFor = Prices.version;
        }
        String name = ItemNames.clean(hover);
        String hit = LINES.get(name);
        if (hit == null) {
            Entry e = index.byName(name);
            String made = null;
            if (e != null) {
                Prices.Price price = e.tradable() ? Prices.of(e.name()) : null;
                ItemWiki.Info wiki = ItemWiki.peek(e.name());
                made = SourceText.tooltip(Sources.of(e, index.places(), Sources.Owned.NONE, price, null), wiki);
            }
            hit = made == null ? NONE : made;
            LINES.put(name, hit);
        }
        return hit.isEmpty() ? null : hit;
    }

    /** Works the item's line out again next time (its wiki page just loaded: a merchant's cost). */
    public static synchronized void forget(String name) {
        LINES.remove(name);
    }
}
