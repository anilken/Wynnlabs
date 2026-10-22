package io.github.anilken.wynnlabs.game;

import io.github.anilken.wynnlabs.build.GearScore;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Slot;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What you wear right now: armour, accessories and the weapon you fight with, read so that a
 * menu that hides your armour slots does not empty them.
 */
public final class WornGear {

    private WornGear() {}

    /** The weakest worn piece: its score, name, the gear (null if crafted) and the stack. */
    public record Weakest(double score, String name, ItemView gear, ItemStack stack) {}

    /** The weakest of what you wear there (score -1, name null for an empty slot); null if none. */
    public static Weakest weakest(List<ItemStack> worn) {
        if (worn.isEmpty()) {
            return null;
        }
        Weakest out = null;
        for (ItemStack w : worn) {
            Optional<ItemView> p = w.isEmpty() ? Optional.empty() : GameState.current().view(w);
            Weakest here;
            if (p.isPresent() && p.get().isGear()) {
                ItemView wg = p.get();
                here = new Weakest(GearScore.score(wg), wg.data().name(), wg, w);
            } else if (p.isPresent() && p.get().isCrafted()) {
                ItemView.Crafted wc = p.get().crafted();
                here = new Weakest(GearScore.score(wc), wc.name(), null, w);
            } else {
                return new Weakest(-1, null, null, ItemStack.EMPTY);
            }
            if (out == null || here.score() < out.score()) {
                out = here;
            }
        }
        return out;
    }

    /** Every piece you wear (armour and accessories), for counting set pieces. */
    public static List<ItemStack> allWorn() {
        List<ItemStack> out = new ArrayList<>();
        for (Slot t : List.of(Slot.HELMET, Slot.CHESTPLATE, Slot.LEGGINGS, Slot.BOOTS,
                Slot.RING, Slot.BRACELET, Slot.NECKLACE)) {
            for (ItemStack w : worn(t)) {
                if (!out.contains(w)) {
                    out.add(w);
                }
            }
        }
        return out;
    }

    /** What you wear in the slot this gear goes to (both rings for a ring). */
    private static final java.util.Map<EquipmentSlot, ItemStack> LAST_ARMOUR = new java.util.EnumMap<>(EquipmentSlot.class);

    /**
     * An armour slot. On a mount Wynncraft takes the armour off the player model, so the slots
     * read empty: then the armour seen last on foot stands in, and nothing looks "missing".
     */
    public static ItemStack armour(net.minecraft.world.entity.player.Player player, EquipmentSlot slot) {
        ItemStack now = player.getItemBySlot(slot);
        // all four empty at once is the mount hiding them too, even without a vehicle entity
        boolean hidden = player.isPassenger()
                || player.getItemBySlot(EquipmentSlot.HEAD).isEmpty() && player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                && player.getItemBySlot(EquipmentSlot.LEGS).isEmpty() && player.getItemBySlot(EquipmentSlot.FEET).isEmpty();
        synchronized (LAST_ARMOUR) {
            ItemStack last = LAST_ARMOUR.get(slot);
            if (last != null && !last.isEmpty() && !WynntilsItems.known(last)) {
                // a kept copy Wynntils has not read: let it read it, so its stats count
                Wyn.annotate(last);
            }
            if (hidden) {
                return now.isEmpty() && last != null ? last : now;
            }
            // A piece that left its slot without turning up in the bag was hidden by the game (mount
            // menus hide some slots, not always all four), not taken off by you: keep the last one.
            if (now.isEmpty() && last != null && !last.isEmpty()) {
                boolean inBag = false;
                var inv = player.getInventory();
                for (int i = 0; i < 36 && !inBag; i++) {
                    inBag = ItemStack.isSameItemSameComponents(inv.getItem(i), last);
                }
                var carried = player.containerMenu == null ? ItemStack.EMPTY : player.containerMenu.getCarried();
                if (!inBag && !ItemStack.isSameItemSameComponents(carried, last)) {
                    return last;
                }
            }
            LAST_ARMOUR.put(slot, now);         // the stack itself: a copy loses Wynntils' reading of it
        }
        return now;
    }

    /** Every second: note the armour while it shows, so a mount taken later has it to stand in. */
    public static void snapshotArmour(net.minecraft.client.Minecraft mc) {
        if (mc.player == null || mc.player.tickCount % 20 != 0) {
            return;
        }
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET}) {
            armour(mc.player, slot);
        }
    }

    /** What you wear in the slot this gear goes to (both rings for a ring); nothing when not in game. */
    public static List<ItemStack> worn(Slot type) {
        GameState state = GameState.current();
        List<ItemStack> out = new ArrayList<>();
        if (!state.inGame()) {
            return out;
        }
        switch (type) {
            case HELMET -> out.add(state.armour(EquipmentSlot.HEAD));
            case CHESTPLATE -> out.add(state.armour(EquipmentSlot.CHEST));
            case LEGGINGS -> out.add(state.armour(EquipmentSlot.LEGS));
            case BOOTS -> out.add(state.armour(EquipmentSlot.FEET));
            case RING, BRACELET, NECKLACE -> {
                List<ItemStack> acc = state.accessories();
                // order in Wynncraft: ring, ring, bracelet, necklace
                int[] idx = switch (type) {
                    case RING -> new int[] {0, 1};
                    case BRACELET -> new int[] {2};
                    default -> new int[] {3};
                };
                for (int i : idx) {
                    out.add(i < acc.size() ? acc.get(i) : ItemStack.EMPTY);
                }
            }
            default -> {
                if (type.klass() != null) {
                    out.add(bestWeapon());
                }
            }
        }
        return out;
    }

    /** The weapon you fight with: the one in hand, else the strongest in the hotbar. */
    public static ItemStack bestWeapon() {
        GameState state = GameState.current();
        ItemStack hand = state.mainHand();
        if (isOwnWeapon(hand)) {
            return hand;
        }
        ItemStack best = ItemStack.EMPTY;
        double bestScore = -1;
        List<ItemStack> inv = state.inventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = i < inv.size() ? inv.get(i) : ItemStack.EMPTY;
            if (isOwnWeapon(s)) {
                double sc = GearScore.score(state.view(s).get());
                if (sc > bestScore) {
                    best = s;
                    bestScore = sc;
                }
            }
        }
        return best;
    }

    private static boolean isOwnWeapon(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        Optional<ItemView> p = GameState.current().view(s);
        if (p.isEmpty() || !p.get().isGear() || p.get().data().slot().klass() == null) {
            return false;
        }
        return p.get().data().slot().klass() == GameState.current().classType();
    }
}
