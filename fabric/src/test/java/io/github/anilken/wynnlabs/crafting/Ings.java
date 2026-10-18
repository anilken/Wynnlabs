package io.github.anilken.wynnlabs.crafting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Real ingredients (values from Wynntils' ingredient data, October 2026) for the calculator tests. */
final class Ings {

    private Ings() {}

    /** pos: left, right, above, under, touching, not touching; req: str, dex, int, def, agi. */
    static Ingredient ing(String name, List<String> profs, Object[][] ids, int[] pos, int durability, int charges,
                          int duration, int[] req) {
        Map<String, Ingredient.Stat> m = new LinkedHashMap<>();
        for (Object[] id : ids) {
            m.put((String) id[0], new Ingredient.Stat((Integer) id[1], (Integer) id[2], (String) id[0], ""));
        }
        return new Ingredient(name, 3, 1, profs, m, pos, durability, charges, duration, req);
    }

    static final int[] NO_POS = new int[6];
    static final int[] NO_REQ = new int[5];

    static final Ingredient FOUL_FAIRY_DUST = ing("Foul Fairy Dust", List.of("weaponsmithing", "woodworking"),
            new Object[][] {{"manaRegen", -8, -7}, {"spellDamage", 12, 16}}, NO_POS, -96, 0, 0, new int[] {0, 0, 30, 0, 0});
    static final Ingredient OBELISK_CORE = ing("Obelisk Core", List.of("woodworking", "armouring", "jeweling"),
            new Object[][] {}, new int[] {-200, 0, 0, 0, 0, 0}, -131, 0, 0, NO_REQ);
    static final Ingredient DIZZYING_VOID = ing("Dizzying Void", List.of("armouring", "woodworking"),
            new Object[][] {{"rawHealth", -1200, -1000}}, new int[] {0, 0, 115, 115, -115, 0}, -175, 0, 0,
            new int[] {0, 0, 0, -25, 0});
    static final Ingredient DEPURINATED_GENOME = ing("Depurinated Genome", List.of("weaponsmithing", "woodworking"),
            new Object[][] {{"mainAttackDamage", 22, 24}, {"rawAgility", -4, -4}, {"rawDefence", -4, -4},
                    {"rawDexterity", -4, -4}, {"rawIntelligence", -4, -4}, {"rawStrength", -4, -4}, {"spellDamage", 22, 24}},
            NO_POS, -162, 0, 0, new int[] {-10, -10, -10, -10, -10});
    static final Ingredient EXQUISITE_MARSH_SCALE = ing("Exquisite Marsh Scale", List.of("woodworking", "tailoring"),
            new Object[][] {{"healingEfficiency", -5, -4}, {"rawStrength", 6, 7}}, NO_POS, -110, 0, 0,
            new int[] {22, 0, -11, 0, 0});
    static final Ingredient GUNKFUELED_CORE = ing("Gunkfueled Core", List.of("armouring", "woodworking"),
            new Object[][] {{"exploding", 10, 15}, {"healthRegenRaw", -180, -150}, {"rawStrength", 5, 7}}, NO_POS, -160,
            0, 0, new int[] {0, 0, 0, -25, 0});
    static final Ingredient MAJORS_BADGE = ing("Major's Badge", List.of("armouring", "tailoring", "weaponsmithing",
                    "woodworking", "jeweling", "alchemism"), new Object[][] {}, new int[] {-15, -180, -110, -20, 35, -30},
            -175, 0, -300, NO_REQ);
    static final Ingredient BAT_HEART = ing("Bat Heart", List.of("alchemism"),
            new Object[][] {{"mainAttackDamage", -20, -20}, {"spellDamage", -20, -20}, {"walkSpeed", 24, 28}}, NO_POS, 0,
            -1, 90, NO_REQ);
    static final Ingredient ACTIVE_FIREBALL = ing("Active Fireball", List.of("scribing", "cooking", "alchemism"),
            new Object[][] {{"fireDamage", 2, 4}}, NO_POS, 0, 0, -160, NO_REQ);

    static final List<Ingredient> ALL = List.of(FOUL_FAIRY_DUST, OBELISK_CORE, DIZZYING_VOID, DEPURINATED_GENOME,
            EXQUISITE_MARSH_SCALE, GUNKFUELED_CORE, MAJORS_BADGE, BAT_HEART, ACTIVE_FIREBALL);

    /** Foul Fairy Dust inverted by Obelisk Core on its right, Dizzying Void under it, then three more. */
    static List<Ingredient> wandLayout() {
        return Craft.slots(FOUL_FAIRY_DUST, OBELISK_CORE, DIZZYING_VOID, DEPURINATED_GENOME, EXQUISITE_MARSH_SCALE,
                GUNKFUELED_CORE);
    }
}
