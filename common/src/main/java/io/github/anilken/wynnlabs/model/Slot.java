package io.github.anilken.wynnlabs.model;

/**
 * What kind of gear a piece is. The constants and their order follow the gear data the mod
 * reads (names go into the API's JSON as they are); UNKNOWN stands for a kind added later.
 * WEAPON and ACCESSORY are the generic kinds some data uses before the exact one is known.
 */
public enum Slot {
    SPEAR(Klass.WARRIOR), WAND(Klass.MAGE), DAGGER(Klass.ASSASSIN), BOW(Klass.ARCHER), RELIK(Klass.SHAMAN),
    WEAPON(null), ACCESSORY(null), RING(null), BRACELET(null), NECKLACE(null),
    HELMET(null), CHESTPLATE(null), LEGGINGS(null), BOOTS(null), MASTERY_TOME(null), CHARM(null),
    UNKNOWN(null);

    private final Klass klass;

    Slot(Klass klass) {
        this.klass = klass;
    }

    /** The class whose weapon this is; null for everything that is not a class weapon. */
    public Klass klass() {
        return klass;
    }

    /** A class weapon, or the generic WEAPON. */
    public boolean isWeapon() {
        return klass != null || this == WEAPON;
    }

    public boolean isArmour() {
        return this == HELMET || this == CHESTPLATE || this == LEGGINGS || this == BOOTS;
    }

    public boolean isAccessory() {
        return this == RING || this == BRACELET || this == NECKLACE;
    }

    /** The first slot whose class is {@code k}: that class's weapon, null for NONE. */
    public static Slot weaponOf(Klass k) {
        for (Slot s : values()) {
            if (s.klass == k) {
                return s;
            }
        }
        return null;
    }

    /** The slot with this name, UNKNOWN for a name this version does not have. */
    public static Slot of(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return UNKNOWN;
        }
    }
}
