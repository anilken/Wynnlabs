package io.github.anilken.wynnlabs.endgame;

import io.github.anilken.wynnlabs.game.ModelData;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.PoolItem;
import io.github.anilken.wynnlabs.model.Tier;
import io.github.anilken.wynnlabs.ui.Theme;
import io.github.anilken.wynnlabs.ui.WynnTex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Where a pool item's icon comes from (raid-lootrun-tracking, design decision 11): the game's own
 * item, a pack texture, or a glyph. Pure: no Minecraft or Wynntils type.
 *
 * <p>The model key is the item's icon value (format {@code attribute} or {@code aspect_attribute}),
 * then a key made from the item itself (its class, or its name); each is tried with its
 * table-style aliases ({@link #aliases}) against {@link ModelData}, and the first one the table
 * knows wins. The base item follows Wynntils' guide items: leather armour for armour keys, a
 * potion for everything else, vanilla items for emeralds and a player head for a {@code skin}.
 *
 * <p>API contract of group 8 (design.md): the signatures below stay as they are.
 */
final class PoolIcons {

    /**
     * Every icon candidate for one item, in the order {@link ItemIcons} tries them.
     *
     * @param modelKey    the model table key; "" = none
     * @param itemId      the base item, e.g. {@code minecraft:potion}
     * @param texture     a {@link WynnTex} path, or ""
     * @param glyphColour the glyph's ARGB colour (rarity, or the class colour for an aspect)
     * @param glyphLetter the glyph's letter
     * @param tint        ARGB potion colour for a powder; 0 = none
     */
    record Ref(String modelKey, String itemId, String texture, int glyphColour, char glyphLetter, int tint) {}

    static final String POTION = "minecraft:potion";
    static final String PLAYER_HEAD = "minecraft:player_head";
    static final String EMERALD = "minecraft:emerald";
    static final String EMERALD_BLOCK = "minecraft:emerald_block";
    static final String LIQUID_EMERALD = "minecraft:experience_bottle";

    /** Base items that draw as themselves, without a model float. */
    private static final Set<String> PLAIN = Set.of(PLAYER_HEAD, EMERALD, EMERALD_BLOCK, LIQUID_EMERALD);
    private static final String[] ARMOUR = {"helmet", "chestplate", "leggings", "boots"};
    private static final String[] TOMES = {"weapon", "armour", "mana", "movement", "utility", "lootrun", "guild"};
    private static final String[] RUNES = {"az", "nii", "uth", "tol", "ek"};
    private static final String[] WARDS = {"black", "blue", "cyan", "green", "orange", "pink", "purple", "red", "white", "yellow"};
    private static final String[] AUGMENTS = {"amplifier", "simulator", "insulator"};
    /** Powder elements and their colours (open question 17: the game's chat colours for the elements). */
    private static final String[] ELEMENTS = {"fire", "water", "earth", "thunder", "air"};
    private static final int[] ELEMENT_COLOURS = {0xFFFF5555, 0xFF55FFFF, 0xFF00AA00, 0xFFFFFF55, 0xFFAAAAAA};

    /** What an item is, for its icon. */
    enum Kind { ASPECT, TOME, CHARM, GEAR, RUNE, WARD, KEY, AUGMENT, EMERALD, POWDER, OTHER }

    private PoolIcons() {}

    /** The icon candidates of decision 11 for this item. */
    static Ref resolve(PoolItem item, Klass aspectClass) {
        if (item == null) {
            return new Ref("", POTION, "", Theme.INK_3, '?', 0);
        }
        Kind kind = kind(item);
        Klass k = kind == Kind.ASPECT ? (item.aspectClass() != Klass.NONE ? item.aspectClass()
                : aspectClass == null ? Klass.NONE : aspectClass) : Klass.NONE;
        List<String> keys = candidates(item, kind, k);
        String modelKey = "";
        for (String key : keys) {
            for (String a : aliases(key)) {
                if (!Float.isNaN(ModelData.of(a))) {
                    modelKey = a;
                    break;
                }
            }
            if (!modelKey.isEmpty()) {
                break;
            }
        }
        String baseKey = !modelKey.isEmpty() ? modelKey : keys.isEmpty() ? "" : keys.get(0);
        String itemId = itemId(item, kind, baseKey);
        String texture = texture(item, kind, k, keys);
        int colour = kind == Kind.ASPECT && k != Klass.NONE ? classColour(k) : rarityColour(item.rarity());
        int tint = kind == Kind.POWDER ? powderColour(item, keys) : 0;
        return new Ref(modelKey, itemId, texture, colour, letter(item, kind), tint);
    }

    /**
     * Whether {@link ItemIcons} can build a stack for this ref: a model key the table knows, or a
     * base item that draws as itself (emeralds, a player head).
     */
    static boolean stackable(Ref r) {
        return r != null && (!r.modelKey().isEmpty() || PLAIN.contains(r.itemId()));
    }

    /** The class colour of an aspect icon; {@link Theme#INK_3} for NONE. */
    static int classColour(Klass k) {
        if (k == null) {
            return Theme.INK_3;
        }
        return switch (k) {
            case ARCHER -> 0xFFC5589B;
            case ASSASSIN -> 0xFFA143AF;
            case MAGE -> 0xFFCC8B3D;
            case SHAMAN -> 0xFF479AC0;
            case WARRIOR -> 0xFFCA5D50;
            case NONE -> Theme.INK_3;
        };
    }

    /** The colour a rarity's names and glyphs use on paper; {@link Theme#INK_3} for none. */
    static int rarityColour(Tier t) {
        if (t == null) {
            return Theme.INK_3;
        }
        return switch (t) {
            case MYTHIC -> Theme.TIER_MYTHIC;
            case FABLED -> Theme.TIER_FABLED;
            case LEGENDARY -> Theme.TIER_LEGENDARY;
            case RARE -> Theme.TIER_RARE;
            case UNIQUE -> Theme.TIER_UNIQUE;
            case SET -> Theme.TIER_SET;
            case NORMAL, CRAFTED -> Theme.INK_3;
        };
    }

    /** The key and its table-style aliases, in try order. */
    static List<String> aliases(String key) {
        if (key == null || key.isBlank()) {
            return List.of();
        }
        String k = key.trim();
        List<String> out = new ArrayList<>();
        out.add(k);
        int dot = k.indexOf('.');
        if (dot > 0 && dot < k.length() - 1) {
            String prefix = k.substring(0, dot);
            String rest = snake(k.substring(dot + 1));
            switch (prefix) {
                case "augment" -> add(out, rest);                       // augment.corkianSimulator → corkian_simulator
                case "rune", "ward", "dungeon", "emerald", "powder" -> add(out, prefix + "_" + rest);
                default -> { }
            }
            add(out, prefix + "_" + rest);
        }
        add(out, k.replace(':', '_'));
        return List.copyOf(out);
    }

    /** What the item is: its Wynnventory type first, its name when the type says nothing. */
    static Kind kind(PoolItem i) {
        String t = i.itemType().toLowerCase(Locale.ROOT);
        String n = i.name().toLowerCase(Locale.ROOT);
        if (i.aspect() || t.startsWith("aspect")) {
            return Kind.ASPECT;
        }
        if (t.startsWith("tome")) {
            return Kind.TOME;
        }
        if (t.startsWith("charm")) {
            return Kind.CHARM;
        }
        if (t.startsWith("gear")) {
            return Kind.GEAR;
        }
        if (t.startsWith("rune")) {
            return Kind.RUNE;
        }
        if (t.startsWith("ward")) {
            return Kind.WARD;
        }
        if (t.startsWith("dungeon")) {
            return Kind.KEY;
        }
        if (t.startsWith("amplifier") || t.startsWith("simulator") || t.startsWith("insulator") || t.startsWith("augment")) {
            return Kind.AUGMENT;
        }
        if (t.startsWith("emerald")) {
            return Kind.EMERALD;
        }
        if (t.startsWith("powder")) {
            return Kind.POWDER;
        }
        // no known type: the name
        if (n.endsWith(" rune")) {
            return Kind.RUNE;
        }
        if (n.endsWith(" ward") || n.startsWith("ward of")) {
            return Kind.WARD;
        }
        if (n.endsWith(" key")) {
            return Kind.KEY;
        }
        if (n.startsWith("corkian ")) {
            return Kind.AUGMENT;
        }
        if (n.contains("emerald")) {
            return Kind.EMERALD;
        }
        if (n.contains(" powder")) {
            return Kind.POWDER;
        }
        if (n.startsWith("charm of")) {
            return Kind.CHARM;
        }
        if (n.contains("tome of")) {
            return Kind.TOME;
        }
        return Kind.OTHER;
    }

    // ---- inside ----

    /** The model keys to try: the icon's value, then one made from the item itself. */
    private static List<String> candidates(PoolItem i, Kind kind, Klass k) {
        List<String> out = new ArrayList<>();
        String f = i.iconFormat();
        if (!i.iconValue().isEmpty() && (f.equals("attribute") || f.equals("aspect_attribute"))) {
            out.add(i.iconValue());
        }
        String own = ownKey(i, kind, k);
        if (!own.isEmpty() && !out.contains(own)) {
            out.add(own);
        }
        return out;
    }

    /** A model key from the item itself (no usable icon): an aspect's class, a rune, ward, augment, key or charm by name. */
    private static String ownKey(PoolItem i, Kind kind, Klass k) {
        String n = i.name().toLowerCase(Locale.ROOT);
        return switch (kind) {
            case ASPECT -> k == Klass.NONE ? "" : "abilityTree.aspect" + capital(k.name());
            case RUNE -> word(n, RUNES).isEmpty() ? "" : "rune_" + word(n, RUNES);
            case WARD -> word(n, WARDS).isEmpty() ? "" : "ward_" + word(n, WARDS);
            case AUGMENT -> word(n, AUGMENTS).isEmpty() ? "" : "corkian_" + word(n, AUGMENTS);
            case KEY -> n.contains("broken") ? "dungeon_key_broken" : "dungeon_key";
            case CHARM -> charmKey(n);
            case TOME -> word(n, TOMES).isEmpty() ? "" : "tome." + word(n, TOMES);
            default -> "";
        };
    }

    /** "Charm of the Void" → "charm.void". */
    private static String charmKey(String lowerName) {
        String s = lowerName;
        if (!s.startsWith("charm of ")) {
            return "";
        }
        s = s.substring("charm of ".length());
        if (s.startsWith("the ")) {
            s = s.substring(4);
        }
        s = s.trim();
        return s.isEmpty() || s.contains(" ") ? "" : "charm." + s;
    }

    private static String itemId(PoolItem i, Kind kind, String key) {
        if (kind == Kind.EMERALD) {
            String n = i.name().toLowerCase(Locale.ROOT) + " " + key.toLowerCase(Locale.ROOT);
            if (n.contains("liquid")) {
                return LIQUID_EMERALD;
            }
            return n.contains("block") ? EMERALD_BLOCK : EMERALD;
        }
        if (i.iconFormat().equals("skin")) {
            return PLAYER_HEAD;
        }
        for (String slot : ARMOUR) {
            if (key.startsWith(slot + ".")) {
                return "minecraft:leather_" + slot;
            }
        }
        return POTION;
    }

    /** The pack texture of decision 11, source 2. */
    private static String texture(PoolItem i, Kind kind, Klass k, List<String> keys) {
        String n = i.name().toLowerCase(Locale.ROOT);
        String keyText = String.join(" ", keys).toLowerCase(Locale.ROOT);
        String found = switch (kind) {
            case ASPECT -> k == Klass.NONE ? "" : "wynn/gui/ability_tree/aspect/" + k.name().toLowerCase(Locale.ROOT);
            case TOME -> {
                String type = tomeType(keys);
                if (type.isEmpty()) {
                    type = word(n, TOMES);
                }
                yield type.isEmpty() ? "" : "wynn/mastery_tome/" + type;
            }
            case AUGMENT -> {
                String a = word(keyText.replace('_', ' ').replace('.', ' ') + " " + n, AUGMENTS);
                if (a.isEmpty()) {
                    a = word(splitCamel(String.join(" ", keys)), AUGMENTS);
                }
                yield a.isEmpty() ? "" : "wynn/augment/corkian_" + a;
            }
            case RUNE -> {
                String r = word(n, RUNES);
                if (r.isEmpty()) {
                    r = word(keyText.replace('_', ' ').replace('.', ' '), RUNES);
                }
                yield r.isEmpty() ? "" : "wynn/rune/" + r;
            }
            case WARD -> {
                String w = word(keyText.replace('_', ' ').replace('.', ' '), WARDS);
                if (w.isEmpty()) {
                    w = word(n, WARDS);
                }
                yield w.isEmpty() ? "" : "wynn/ward/" + w;
            }
            case KEY -> n.contains("broken") || keyText.contains("broken") ? "wynn/dungeon/key_broken" : "wynn/dungeon/key";
            case POWDER -> large(i, keyText) ? "wynn/powder/large" : "wynn/powder/small";
            default -> "";
        };
        return found.isEmpty() ? "wynn/loot/" + lootGlyph(i.rarity()) : found;
    }

    /** The type of a {@code tome.<type>} key; "" when none. */
    private static String tomeType(List<String> keys) {
        for (String key : keys) {
            if (key.startsWith("tome.") && key.length() > 5) {
                return key.substring(5).toLowerCase(Locale.ROOT);
            }
        }
        return "";
    }

    /** A large powder: "Large" in its key, else tier IV or more (from the item or its name's numeral). */
    private static boolean large(PoolItem i, String keyText) {
        if (keyText.contains("large")) {
            return true;
        }
        if (keyText.contains("small")) {
            return false;
        }
        int tier = i.tier() > 0 ? i.tier() : roman(i.name());
        return tier >= 4;
    }

    private static int powderColour(PoolItem i, List<String> keys) {
        String text = (i.subtype() + " " + i.name() + " " + splitCamel(String.join(" ", keys))).toLowerCase(Locale.ROOT);
        for (int e = 0; e < ELEMENTS.length; e++) {
            if (text.contains(ELEMENTS[e])) {
                return ELEMENT_COLOURS[e];
            }
        }
        return 0;
    }

    /** The pack's rarity glyph names ({@code wynn/loot/<name>}). */
    private static String lootGlyph(Tier t) {
        return switch (t) {
            case MYTHIC -> "mythic";
            case FABLED -> "fabled";
            case LEGENDARY -> "legendary";
            case RARE -> "rare";
            case UNIQUE -> "unique";
            case SET -> "set";
            case NORMAL, CRAFTED -> "base";
        };
    }

    private static char letter(PoolItem i, Kind kind) {
        return switch (kind) {
            case ASPECT -> 'A';
            case TOME -> 'T';
            case CHARM -> 'C';
            case GEAR -> 'G';
            case RUNE -> 'R';
            case WARD -> 'W';
            case KEY -> 'K';
            case AUGMENT -> 'A';
            case EMERALD -> 'E';
            case POWDER -> 'P';
            case OTHER -> {
                String t = i.itemType().isEmpty() ? i.name() : i.itemType();
                yield t.isEmpty() ? '?' : Character.toUpperCase(t.charAt(0));
            }
        };
    }

    /** The first of {@code words} that is a whole word of {@code text}; "". */
    private static String word(String text, String[] words) {
        String[] parts = text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        for (String w : words) {
            for (String p : parts) {
                if (p.equals(w)) {
                    return w;
                }
            }
        }
        return "";
    }

    /** A roman numeral word (I to VI) in the name; 0 when none. */
    private static int roman(String name) {
        String[] parts = name.split(" ");
        String last = parts.length == 0 ? "" : parts[parts.length - 1];
        return switch (last) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            case "IV" -> 4;
            case "V" -> 5;
            case "VI" -> 6;
            default -> 0;
        };
    }

    /** "corkianSimulator" → "corkian_simulator", "FireSmall" → "fire_small", "keyBroken" → "key_broken". */
    private static String snake(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                if (b.length() > 0 && b.charAt(b.length() - 1) != '_') {
                    b.append('_');
                }
                b.append(Character.toLowerCase(c));
            } else if (c == '.' || c == '-' || c == ' ') {
                b.append('_');
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static String splitCamel(String s) {
        return snake(s).replace('_', ' ');
    }

    private static String capital(String upper) {
        String l = upper.toLowerCase(Locale.ROOT);
        return l.isEmpty() ? l : Character.toUpperCase(l.charAt(0)) + l.substring(1);
    }

    private static void add(List<String> out, String s) {
        if (!s.isEmpty() && !out.contains(s)) {
            out.add(s);
        }
    }
}
