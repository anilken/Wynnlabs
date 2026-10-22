package io.github.anilken.wynnlabs.ui;

import net.minecraft.ChatFormatting;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The J menu's colours: wood, gold and parchment, and ink that reads on the paper. Text on
 * paper keeps its meaning (green good, red bad, tier hues) in a darker tone of the same hue;
 * {@link #paperRgb} does that for any colour, and ThemeContrastTest keeps every token readable.
 */
public final class Theme {

    private Theme() {}

    // parchment
    public static final int PAPER = 0xFFD8C595;
    static final int PAPER_DEEP = 0xFFC4AD78;       // the page's darkened rim
    static final int CARD = 0xFFEADCB4;
    static final int CARD_LOW = 0xFFD4C193;         // a card's bottom; text must read here too
    static final int CARD_HOVER = 0xFFF3E6BF;
    static final int SELECTED = 0xFFF6E3A8;         // a selected row: a light gold wash
    static final int PLAQUE = 0xFFDCCB9C;
    static final int WELL = 0xFFC9B582;             // an inset box on paper (value fields)
    static final int LINE = 0xFF9A855C;             // rules and row lines
    static final int STRIPE = 0x1CFFFFFF;           // every other row

    // ink on paper
    static final int INK = 0xFF2E2E2E;
    static final int INK_2 = 0xFF5E4A38;            // labels
    public static final int INK_3 = 0xFF6B5A46;            // secondary, hints
    static final int GOOD = 0xFF285C16;
    static final int BAD = 0xFF932C1D;
    static final int WARN = 0xFF754506;
    static final int LINK = 0xFF17557C;
    static final int PRICE = 0xFF6F480E;
    static final int ACCENT = 0xFF5A3A8A;           // the archetype, the build score, the coach

    // tier hues on paper (Wynncraft's colours, darkened)
    public static final int TIER_LEGENDARY = 0xFF085961;
    public static final int TIER_RARE = 0xFF8E2370;
    public static final int TIER_UNIQUE = 0xFF614F00;
    public static final int TIER_MYTHIC = 0xFF67389C;
    public static final int TIER_FABLED = 0xFF902B48;
    public static final int TIER_SET = 0xFF285C16;

    // wood and gold
    static final int WOOD = 0xFF6E5440;
    static final int WOOD_HI = 0xFF85684F;
    static final int WOOD_LO = 0xFF4F3A2A;
    static final int WOOD_EDGE = 0xFF3B2A1D;
    static final int PLANK_A = 0xFF664C36;
    static final int PLANK_B = 0xFF5B432F;
    static final int CREAM = 0xFFF3E6C4;            // text on wood
    static final int CREAM_DIM = 0xFFC9B48F;
    public static final int GOLD = 0xFFFFE071;
    static final int GOLD_DEEP = 0xFFD9A93A;
    public static final int GOLD_EDGE = 0xFF8A6A1C;
    static final int BRASS = 0xFFC9973A;
    static final int RED_BADGE = 0xFFA03020;

    // bars and seals
    static final int BAR_BG = 0xFF465B50;
    static final int TEAL = 0xFF77D1D7;
    static final int TEAL_HI = 0xFF9EE6EA;
    static final int TEAL_LO = 0xFF61ABB0;
    static final int AMBER = 0xFFE8A040;
    static final int AMBER_HI = 0xFFF8D088;
    static final int AMBER_LO = 0xFFC07820;
    static final int GREEN = 0xFF5AA04A;
    static final int GREEN_HI = 0xFF9AD08A;
    static final int GREEN_LO = 0xFF3F7A34;
    static final int SEAL_3 = 0xFF3F7A4A;           // the strongest gain
    static final int SEAL_2 = 0xFF5A8A54;
    static final int SEAL_TEXT = 0xFFEAF7D8;
    static final int NEAR = 0xFF3D5A46;             // the nearest bank / Trade Market pill
    static final int NEAR_TEXT = 0xFFE0F0D0;

    // the bank's verdict rings, readable on a brown slot
    static final int MARK_SELL = 0xFFC0302A;
    static final int MARK_QUEST = 0xFF2A8AC0;
    static final int MARK_IDENTIFY = 0xFFD08A20;
    static final int MARK_USE = 0xFF3F9A4F;

    /** Chat colours as the screen uses them, each mapped to its tone on paper. */
    private static final Map<Integer, Integer> LEGACY = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> DARKENED = new ConcurrentHashMap<>();

    static {
        legacy(ChatFormatting.BLACK, INK);
        legacy(ChatFormatting.WHITE, INK);
        legacy(ChatFormatting.GRAY, INK_3);
        legacy(ChatFormatting.DARK_GRAY, INK_3);
        legacy(ChatFormatting.GREEN, TIER_SET);          // good, and the Set tier
        legacy(ChatFormatting.DARK_GREEN, GOOD);
        legacy(ChatFormatting.RED, BAD);                 // bad, and the Fabled tier's red family
        legacy(ChatFormatting.DARK_RED, BAD);
        legacy(ChatFormatting.GOLD, PRICE);
        legacy(ChatFormatting.YELLOW, TIER_UNIQUE);
        legacy(ChatFormatting.AQUA, TIER_LEGENDARY);
        legacy(ChatFormatting.DARK_AQUA, TIER_LEGENDARY);
        legacy(ChatFormatting.BLUE, LINK);
        legacy(ChatFormatting.DARK_BLUE, LINK);
        legacy(ChatFormatting.LIGHT_PURPLE, TIER_RARE);
        legacy(ChatFormatting.DARK_PURPLE, TIER_MYTHIC);
    }

    private static void legacy(ChatFormatting f, int paper) {
        LEGACY.put(f.getColor() & 0xFFFFFF, paper);
    }

    /** A chat colour's tone on paper. */
    static int paper(ChatFormatting f) {
        Integer c = f.getColor() == null ? null : LEGACY.get(f.getColor() & 0xFFFFFF);
        return c == null ? INK : c;
    }

    /**
     * Any text colour as it should be on paper: chat colours through the fixed map, others
     * darkened in their own hue until they reach 4.5:1 on a card's bottom.
     */
    static int paperRgb(int rgb) {
        int key = rgb & 0xFFFFFF;
        Integer c = LEGACY.get(key);
        if (c != null) {
            return c;
        }
        return DARKENED.computeIfAbsent(key, k -> {
            int x = k;
            for (double f = 1.0; contrast(x, CARD_LOW) < 4.5 && f > 0; f -= 0.04) {
                x = scale(k, f);
            }
            return 0xFF000000 | x;
        });
    }

    /** WCAG contrast ratio of two colours (alpha ignored). */
    static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int rgb) {
        return 0.2126 * channel(rgb >> 16) + 0.7152 * channel(rgb >> 8) + 0.0722 * channel(rgb);
    }

    private static double channel(int v) {
        double c = (v & 255) / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static int scale(int rgb, double f) {
        int r = (int) (((rgb >> 16) & 255) * f);
        int g = (int) (((rgb >> 8) & 255) * f);
        int b = (int) ((rgb & 255) * f);
        return (r << 16) | (g << 8) | b;
    }
}
