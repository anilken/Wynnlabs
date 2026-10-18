package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.ItemData;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Item groups Wynncraft lets you wear only one piece of: wearing a second is refused in game
 * (WynnBuilder warns "illegal item combination"). Most are not sets in the item data, so the
 * groups are listed here; a set whose bonus data is marked illegal joins them as well.
 */
final class Exclusive {

    private Exclusive() {}

    private static final Map<String, List<String>> GROUPS = Map.of(
            "Ornate Shadow", List.of("Ornate Shadow Cowl", "Ornate Shadow Cloak", "Ornate Shadow Cover", "Ornate Shadow Cloud"),
            "Grookwarts", List.of("Dragon's Eye Bracelet", "Draoi Fair", "Renda Langit"),
            "Master Hive", List.of("Abyss-Imbued Leggings", "Boreal-Patterned Crown", "Anima-Infused Cuirass",
                    "Chaos-Woven Greaves", "Elysium-Engraved Aegis", "Eden-Blessed Guards", "Gaea-Hewn Boots",
                    "Hephaestus-Forged Sabatons", "Obsidian-Framed Helmet", "Twilight-Gilded Cloak", "Infused Hive Relik",
                    "Infused Hive Wand", "Infused Hive Spear", "Infused Hive Dagger", "Infused Hive Bow", "Contrast",
                    "Prowess", "Intensity"),
            "Thunder Hive", List.of("Sparkling Visor", "Insulated Plate Mail", "Static-Charged Leggings", "Thunderous Step",
                    "Bottled Thunderstorm", "Lightning Flash"),
            "Air Hive", List.of("Pride of the Aerie", "Gale's Freedom", "Turbine Greaves", "Flashstep", "Breezehands",
                    "Vortex Bracer"),
            "Earth Hive", List.of("Ambertoise Shell", "Beetle Aegis", "Elder Oak Roots", "Humbark Moccasins", "Subur Clip",
                    "Golemlus Core"),
            "Water Hive", List.of("Whitecap Crown", "Stillwater Blue", "Trench Scourer", "Silt of the Seafloor", "Coral Ring",
                    "Moon Pool Circlet"),
            "Fire Hive", List.of("Sparkweaver", "Soulflare", "Cinderchain", "Mantlewalkers", "Clockwork", "Dupliblaze"));

    private static final Map<String, String> BY_ITEM = new HashMap<>();

    static {
        GROUPS.forEach((group, items) -> items.forEach(i -> BY_ITEM.put(key(i), group)));
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replace("'", "").replace("’", "").trim();
    }

    /** The exclusive group of an item by name, or null. */
    static String group(String itemName) {
        return itemName == null ? null : BY_ITEM.get(key(itemName));
    }

    /** The exclusive group of an item, also from its set data when that set is marked illegal. */
    static String group(ItemData info) {
        String g = group(info.name());
        if (g != null) {
            return g;
        }
        return info.set().filter(s -> s.bonuses().values().stream()
                        .anyMatch(b -> b.major().stream().anyMatch(m -> m.toLowerCase(Locale.ROOT).contains("illegal"))))
                .map(s -> s.name()).orElse(null);
    }

    /**
     * The item in {@code build} (other than {@code slot}) that shares {@code group}, or null:
     * with it worn, a piece of the group cannot go in {@code slot}.
     */
    static String clash(Map<String, Damage.Piece> build, String slot, String group) {
        if (group == null) {
            return null;
        }
        for (var e : build.entrySet()) {
            Damage.Piece p = e.getValue();
            if (p != null && !e.getKey().equals(slot) && group.equals(group(p.name()))) {
                return p.name();
            }
        }
        return null;
    }
}
