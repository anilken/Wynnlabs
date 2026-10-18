package io.github.anilken.wynnlabs.crafting;

import java.util.List;

/** The 15 craftable item types, grouped as the tab's type list shows them, and their kinds. */
public final class Types {

    public static final List<String> ARMOUR = List.of("helmet", "chestplate", "leggings", "boots");
    public static final List<String> ACCESSORIES = List.of("ring", "bracelet", "necklace");
    public static final List<String> WEAPONS = List.of("spear", "dagger", "bow", "wand", "relik");
    public static final List<String> CONSUMABLES = List.of("potion", "scroll", "food");
    public static final List<String> ALL = List.of("helmet", "chestplate", "leggings", "boots", "ring", "bracelet",
            "necklace", "spear", "dagger", "bow", "wand", "relik", "potion", "scroll", "food");

    private Types() {}

    public static boolean weapon(String type) {
        return WEAPONS.contains(type);
    }

    public static boolean armour(String type) {
        return ARMOUR.contains(type);
    }

    public static boolean accessory(String type) {
        return ACCESSORIES.contains(type);
    }

    public static boolean consumable(String type) {
        return CONSUMABLES.contains(type);
    }
}
