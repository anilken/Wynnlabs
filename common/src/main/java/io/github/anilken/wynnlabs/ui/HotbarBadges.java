package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.advice.TooltipListener;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.core.Stamp;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * The menus' verdict badges on the hotbar too (setting hotbarBadges, on by default): the word
 * (SAT, TANI, TUT …) small, just above each slot that has advice, and the where-to-sell icon in
 * the slot's bottom-left corner. Prices and slots without advice draw nothing. Hidden while a
 * screen is open (the menu shows its own badges), with F1 and in spectator. Draw only.
 */
public final class HotbarBadges {

    private HotbarBadges() {}

    /** What each of the nine slots shows: built when its stack or the stamp changes, else once a second. */
    private static final Memo<ItemStack, TooltipListener.BadgeFacts> MEMO = new Memo<>(9, 1000);

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null || mc.options.hideGui || mc.screen != null || player.isSpectator()
                || !Settings.on("hotbarBadges") || !Rules.adviceHere()) {
            return;
        }
        var inv = player.getInventory();
        int stamp = Stamp.now();
        long now = System.currentTimeMillis();
        float s = wordScale((int) Math.round(mc.getWindow().getGuiScale()));
        // where the game draws the hotbar's items (Gui#renderItemHotbar): 20 apart, 3 above the bottom
        int left = g.guiWidth() / 2 - 88;
        int top = g.guiHeight() - 19;
        boolean layer = false;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            TooltipListener.BadgeFacts f = MEMO.get(i, stack, stamp, now, TooltipListener::badgeFacts);
            if (f.word() == null) {
                continue;
            }
            if (!layer) {
                g.nextStratum();                    // over the item pictures, once for all nine
                layer = true;
            }
            draw(g, mc, left + i * 20, top, f, s);
        }
    }

    /**
     * Half the menu badge's size, in whole screen pixels per font pixel so it stays sharp: 1/2
     * at GUI scale 2 and 4, 2/3 at 3; at 1 a font pixel cannot be smaller than a screen pixel.
     */
    static float wordScale(int guiScale) {
        int gs = Math.max(1, guiScale);
        return Math.max(1, Math.round(gs * 0.5f)) / (float) gs;
    }

    private static void draw(GuiGraphics g, Minecraft mc, int x, int y, TooltipListener.BadgeFacts f, float scale) {
        // never wider than the slot, centred over it; its foot on the item's top edge, but not
        // above the selected slot's frame (the XP bar sits right over it)
        float s = Math.min(scale, 18f / Math.max(1, f.wordWidth()));
        float wx = x + 8 - f.wordWidth() * s / 2;
        float wy = Math.max(y - 7 * s, y - 4);
        TooltipListener.outlined(g, mc.font, f.word(), f.wordWidth(), wx, wy, s, f.colour(), false);
        if (f.where() != null) {
            // the menu's disc and icon, smaller: bottom-left, clear of the count on the right
            Art.box(g, x - 1, y + 10, 7, 7, 3, 0xE0101010, f.colour());
            Art.icon(g, f.where(), x, y + 11, 5, f.colour());
        }
    }

    /**
     * Per slot: the last stack seen (by identity, not by contents), the stamp then and what was
     * worked out for it. The same stack under the same stamp reuses the result until it is
     * {@code life} ms old, so a frame does no lookups for slots that did not change.
     */
    static final class Memo<S, F> {
        private final Object[] stacks;
        private final Object[] facts;
        private final int[] stamps;
        private final long[] until;
        private final long life;

        Memo(int slots, long life) {
            stacks = new Object[slots];
            facts = new Object[slots];
            stamps = new int[slots];
            until = new long[slots];
            this.life = life;
        }

        @SuppressWarnings("unchecked")
        F get(int slot, S stack, int stamp, long now, java.util.function.Function<S, F> build) {
            if (stacks[slot] == stack && stamps[slot] == stamp && now < until[slot]) {
                return (F) facts[slot];
            }
            F f = build.apply(stack);
            stacks[slot] = stack;
            facts[slot] = f;
            stamps[slot] = stamp;
            until[slot] = now + life;
            return f;
        }
    }
}
