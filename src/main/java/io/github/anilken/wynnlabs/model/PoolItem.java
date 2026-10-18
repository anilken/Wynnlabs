package io.github.anilken.wynnlabs.model;

import java.util.Locale;

/**
 * One reward in a weekly pool from Wynnventory (raid-lootrun-tracking, design decisions 10 and 11).
 * Names stay as Wynnventory writes them, in English.
 *
 * @param name       the item's English name
 * @param itemType   Wynnventory's item type ("AspectItem", "GearItem", ...); kept when unknown
 * @param rarity     the item's rarity; NORMAL when unknown
 * @param subtype    Wynnventory's subtype ("ShamanAspect" for an aspect); "" when none
 * @param amount     how many the pool holds
 * @param iconFormat the icon's format ("attribute", "aspect_attribute", "skin", ...); "" when none
 * @param iconValue  the icon's value (a model key such as "wand.multi3"); "" when none
 * @param shiny      whether this is the shiny version of the item
 * @param shinyStat  the shiny tracker's English label ("Raids Won"); "" when none
 * @param tier       the item's tier (powders); 0 when none
 */
public record PoolItem(String name, String itemType, Tier rarity, String subtype, int amount,
                       String iconFormat, String iconValue, boolean shiny, String shinyStat, int tier) {

    private static final String ASPECT_TYPE = "AspectItem";
    private static final String GEAR_TYPE = "GearItem";
    private static final String ASPECT_SUFFIX = "Aspect";

    public PoolItem {
        name = name == null ? "" : name;
        itemType = itemType == null ? "" : itemType;
        rarity = rarity == null ? Tier.NORMAL : rarity;
        subtype = subtype == null ? "" : subtype;
        iconFormat = iconFormat == null ? "" : iconFormat;
        iconValue = iconValue == null ? "" : iconValue;
        shinyStat = shinyStat == null ? "" : shinyStat;
    }

    /** The group 7 shape: no icon, not shiny, no shiny stat, tier 0. */
    public PoolItem(String name, String itemType, Tier rarity, String subtype, int amount) {
        this(name, itemType, rarity, subtype, amount, "", "", false, "", 0);
    }

    /** Whether the item carries an icon (format and value both set). */
    public boolean hasIcon() {
        return !iconFormat.isEmpty() && !iconValue.isEmpty();
    }

    /** Whether this is an aspect ({@code itemType} is {@code AspectItem}). */
    public boolean aspect() {
        return ASPECT_TYPE.equals(itemType);
    }

    /** The class of a {@code <Class>Aspect} subtype; NONE for any other subtype. */
    public Klass aspectClass() {
        if (!subtype.endsWith(ASPECT_SUFFIX) || subtype.length() == ASPECT_SUFFIX.length()) {
            return Klass.NONE;
        }
        String k = subtype.substring(0, subtype.length() - ASPECT_SUFFIX.length()).toUpperCase(Locale.ROOT);
        for (Klass c : Klass.values()) {
            if (c != Klass.NONE && c.name().equals(k)) {
                return c;
            }
        }
        return Klass.NONE;
    }

    /** Whether this is mythic gear ({@code GearItem} of rarity MYTHIC). */
    public boolean mythicGear() {
        return GEAR_TYPE.equals(itemType) && rarity == Tier.MYTHIC;
    }
}
