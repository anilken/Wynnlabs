package io.github.anilken.wynnlabs;

import net.minecraft.client.Minecraft;

/**
 * One number that changes whenever something advice depends on changes: the rules and
 * settings ({@link Rules#version}), prices ({@link Prices#version}), the advice entries
 * ({@link Advice#version}) and what the player carries and wears. Caches keep a stamp next to
 * a result and rebuild it when the stamp differs, instead of on a timer.
 */
final class Stamp {

    /** What the player carries and wears, and their level: rebuilt every tick from stack identities. */
    static volatile int inventory;
    /** How often {@link #inventory} changed (for /perf). */
    static final java.util.concurrent.atomic.AtomicInteger INVENTORY_CHANGES = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * The language the mod speaks ({@link L#code}), checked every tick: cached advice is worded,
     * and with "Auto" a change of the game's own language must reword it too (picking EN or TR in
     * the menu already changes {@link Rules#version}).
     */
    static volatile int language;

    private Stamp() {}

    static int now() {
        return (((Rules.version * 31 + Prices.version) * 31 + Advice.version) * 31 + inventory) * 31 + language;
    }

    /**
     * Every tick: which gear stacks are in the bag, armour and off hand, by identity, and where.
     * Advice compares gear with gear (what you wear, what you carry), so only gear counts: the
     * game resends other items (a compass, a counter in the lore) often, and those must not
     * throw every cached verdict away. A move, a pickup or a sale replaces stacks.
     */
    static void onTick(Minecraft mc) {
        language = L.code().hashCode();
        if (mc.player == null) {
            inventory = 0;
            return;
        }
        var inv = mc.player.getInventory();
        int h = 1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            var view = WynntilsItems.view(s);
            if (view.isEmpty() || !(view.get().isGear() || view.get().isCrafted())) {
                continue;
            }
            h = h * 31 + i;
            h = h * 31 + System.identityHashCode(s);
        }
        int now = h * 31 + Wyn.level();
        if (now != inventory) {
            INVENTORY_CHANGES.incrementAndGet();
        }
        inventory = now;
    }
}
