package io.github.anilken.wynnlabs.search;

import java.util.regex.Pattern;

/**
 * An item's name as a slot shows it, turned into the name the index knows: "§dUnidentified
 * Papyrus", "Lucky Rabbit's Foot [✫✫✫]", "Shiny Nirvana" and padded names
 * ("DecrepitÀÀÀSewers Key") all find their entry. Used by the search key over a hovered item.
 */
public final class ItemNames {

    private static final Pattern CODES = Pattern.compile("§.");
    private static final Pattern BRACKETS = Pattern.compile("\\s*\\[[^\\]]*]\\s*");
    private static final Pattern PADDING = Pattern.compile("À+");
    private static final Pattern SPACES = Pattern.compile("\\s{2,}");
    private static final String[] PREFIXES = {"Unidentified ", "Shiny ", "Crafted "};

    private ItemNames() {}

    /** The hover name without colour codes, icon glyphs, tier stars, brackets and prefixes. */
    public static String clean(String hover) {
        if (hover == null) {
            return "";
        }
        String s = CODES.matcher(hover).replaceAll("");
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints()
                .filter(c -> Character.getType(c) != Character.PRIVATE_USE && Character.getType(c) != Character.UNASSIGNED
                        && c != '✫' && c != '★' && c != '⬡' && !Character.isISOControl(c))
                .forEach(b::appendCodePoint);
        s = BRACKETS.matcher(b.toString()).replaceAll(" ");
        s = PADDING.matcher(s).replaceAll(" ");
        s = SPACES.matcher(s).replaceAll(" ").strip();
        boolean cut = true;
        while (cut) {
            cut = false;
            for (String p : PREFIXES) {
                if (s.startsWith(p)) {
                    s = s.substring(p.length()).strip();
                    cut = true;
                }
            }
        }
        return s.replace('’', '\'');
    }

    /** The index's entry for a hovered item's name, or null. */
    public static Entry find(ItemIndex index, String hover) {
        String name = clean(hover);
        return name.isEmpty() ? null : index.byName(name);
    }
}
