package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.L;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Game words a new or returning player may not know (EHP, archetype, aspect, roll, …), each with
 * a short explanation from the language files ({@code wynnlabs.glossary.<term>}). A menu tooltip
 * that mentions one gets its explanation under it ({@link #with}); the words looked for are in
 * the language files too ({@code wynnlabs.glossary.<term>.words}), since the menu's own text
 * says them differently in each language.
 */
public final class Glossary {

    /** The terms, most specific first: when a tooltip names several, the first ones are explained. */
    static final String[] TERMS = {"ehp", "dry", "pool_tier", "reveal_fee", "spectral", "milestone", "roll",
            "archetype", "powder", "identify", "aspect"};
    /** At most this many explanations under one tooltip. */
    static final int MAX = 2;

    private Glossary() {}

    /** The explanation of one term ("EHP (effective health): …"). */
    static String explain(String term) {
        return L.t("wynnlabs.glossary." + term);
    }

    /**
     * The terms {@code text} mentions, in {@link #TERMS} order, at most {@link #MAX}. A word in
     * capitals ("EHP") must match as written and as a whole word; any other matches at the start
     * of a word in any case, so "Identif" finds "identified" and "Identify".
     */
    static List<String> termsIn(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String[][] all = words();
        for (int t = 0; t < TERMS.length && out.size() < MAX; t++) {
            for (String w : all[t]) {
                boolean caps = w.length() > 1 && w.equals(w.toUpperCase(Locale.ROOT)) && !w.equals(w.toLowerCase(Locale.ROOT));
                if (caps ? found(text, w, true) : found(lower, w.toLowerCase(Locale.ROOT), false)) {
                    out.add(TERMS[t]);
                    break;
                }
            }
        }
        return out;
    }

    /** The words of each term, per {@link #TERMS}, read once per language (render thread only). */
    private static String[][] words;
    private static String wordsLang;

    private static String[][] words() {
        String code = L.code();
        if (words == null || !code.equals(wordsLang)) {
            String[][] w = new String[TERMS.length][];
            for (int t = 0; t < TERMS.length; t++) {
                w[t] = java.util.Arrays.stream(L.t("wynnlabs.glossary." + TERMS[t] + ".words").split(","))
                        .map(String::strip).filter(s -> !s.isEmpty()).toArray(String[]::new);
            }
            words = w;
            wordsLang = code;
        }
        return words;
    }

    /** {@code word} in {@code text} at the start of a word (and at its end too when {@code whole}). */
    static boolean found(String text, String word, boolean whole) {
        for (int i = text.indexOf(word); i >= 0; i = text.indexOf(word, i + 1)) {
            int end = i + word.length();
            if ((i == 0 || !Character.isLetter(text.charAt(i - 1)))
                    && (!whole || end == text.length() || !Character.isLetter(text.charAt(end)))) {
                return true;
            }
        }
        return false;
    }

    /** One explanation as a tooltip line: the term in aqua, what it means in grey. */
    static Component line(String explanation) {
        int colon = explanation.indexOf(": ");
        if (colon < 0) {
            return Component.literal(explanation).withStyle(ChatFormatting.GRAY);
        }
        return Component.literal(explanation.substring(0, colon + 1)).withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.literal(explanation.substring(colon + 1)).withStyle(ChatFormatting.GRAY));
    }

    /** Whether {@code text} names a term {@link #with} would explain. */
    public static boolean mentions(String text) {
        return !termsIn(text).isEmpty();
    }

    /**
     * {@code tip} with the explanations of the terms it names under it, after an empty line; the
     * same list when it names none or already explains them. Built for the hovered tooltip only.
     */
    public static List<Component> with(List<Component> tip) {
        if (tip == null || tip.isEmpty()) {
            return tip;
        }
        StringBuilder all = new StringBuilder();
        for (Component c : tip) {
            all.append(c.getString()).append('\n');
        }
        String text = all.toString();
        List<Component> extra = new ArrayList<>();
        for (String term : termsIn(text)) {
            String e = explain(term);
            if (!text.contains(e)) {
                extra.add(line(e));
            }
        }
        if (extra.isEmpty()) {
            return tip;
        }
        List<Component> out = new ArrayList<>(tip);
        out.add(Component.empty());
        out.addAll(extra);
        return out;
    }

    /** A grey tooltip line. */
    static Component grey(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    /**
     * A text tooltip for this frame, its game words explained ({@link #with}) and long lines
     * wrapped to at most half the window ({@code screenW}), never narrower than 160.
     */
    static void draw(GuiGraphics g, Font font, List<Component> tip, int mx, int my, int screenW) {
        int maxW = Math.max(160, Math.min(260, screenW / 2));
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component c : with(tip)) {
            List<FormattedCharSequence> part = font.split(c, maxW);
            lines.addAll(part.isEmpty() ? List.of(FormattedCharSequence.EMPTY) : part);
        }
        g.setTooltipForNextFrame(font, lines, mx, my);
    }
}
