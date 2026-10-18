package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.Activity;
import io.github.anilken.wynnlabs.model.MenuKind;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.ArrayList;
import java.util.List;

/**
 * The activities (quests, discoveries …) on the open menu's own slots, read once per tick and
 * shared by {@link QuestBook} and {@link DiscoveryBook}, which both used to parse every slot of
 * every menu. Only the content book is read; a menu Wynntils cannot name is read too, as before.
 */
final class ContentBook {

    private static AbstractContainerMenu readMenu;
    private static int readTick = Integer.MIN_VALUE;
    private static List<Activity> read = List.of();

    private ContentBook() {}

    /** The activities on the open menu, or none when it is not the content book. Game thread. */
    static List<Activity> activities(Minecraft mc) {
        if (mc.player == null) {
            return List.of();
        }
        var menu = mc.player.containerMenu;
        if (menu == null || menu == mc.player.inventoryMenu) {
            return List.of();
        }
        int now = mc.player.tickCount;
        if (menu == readMenu && now == readTick) {
            return read;
        }
        List<Activity> out = new ArrayList<>();
        MenuKind kind = Wyn.container();
        if (kind == null || "ContentBookContainer".equals(kind.type())) {
            for (var slot : menu.slots) {
                if (slot.container == mc.player.getInventory()) {
                    continue;                       // only the book's own items
                }
                var stack = slot.getItem();
                if (stack.isEmpty()) {
                    continue;
                }
                WynntilsItems.activity(stack).ifPresent(out::add);
            }
        }
        readMenu = menu;
        readTick = now;
        read = out;
        return out;
    }
}
