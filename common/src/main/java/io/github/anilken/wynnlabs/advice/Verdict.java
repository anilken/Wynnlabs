package io.github.anilken.wynnlabs.advice;

import io.github.anilken.wynnlabs.core.L;

import net.minecraft.ChatFormatting;

import java.util.Locale;

/**
 * What to do with an item, ability node or skill: the logic and colour live here, the word the
 * player reads comes from the language file ({@code wynnlabs.verdict.<name>}). Old saved advice
 * and tools may still send the Turkish words ("SAT", "GİY", …): {@link #of} maps them.
 */
public enum Verdict {
    WEAR(ChatFormatting.GREEN, "GİY", "GIY"),
    USE(ChatFormatting.GREEN, "KULLAN"),
    BUY(ChatFormatting.GREEN, "AL"),
    SPEND(ChatFormatting.GREEN, "PUAN VER"),
    IDENTIFY(ChatFormatting.YELLOW, "TANIMLA"),
    PRICE(ChatFormatting.GOLD, "FİYATLA", "FIYATLA"),
    REROLL(ChatFormatting.GOLD, "YENİDEN TANIMLA", "YENIDEN TANIMLA"),
    SELL(ChatFormatting.RED, "SAT"),
    SKIP(ChatFormatting.RED, "ALMA"),
    KEEP(ChatFormatting.AQUA, "SAKLA"),
    BUY_LATER(ChatFormatting.AQUA, "SONRA AL"),
    WAIT(ChatFormatting.AQUA, "BEKLE"),
    /** A plan node already unlocked: keeps its number in the tree, no glow. */
    TAKEN(ChatFormatting.GRAY, "ALINDI"),
    NOTE(ChatFormatting.WHITE);

    private final ChatFormatting colour;
    private final String[] legacy;

    Verdict(ChatFormatting colour, String... legacy) {
        this.colour = colour;
        this.legacy = legacy;
    }

    ChatFormatting colour() {
        return colour;
    }

    /** ARGB, for glows and frames. */
    int rgb() {
        Integer c = colour.getColor();
        return 0xFF000000 | (c == null ? 0xFFFFFF : c);
    }

    /** The word shown to the player, in the game's language. */
    public String label() {
        return L.t("wynnlabs.verdict." + name().toLowerCase(Locale.ROOT));
    }

    /**
     * What the verdict asks of you, in one short line for under it in a tooltip, led by the
     * slot's short badge word when it differs from the full one ("ID: Its stats are hidden…").
     */
    public String meaning() {
        String key = name().toLowerCase(Locale.ROOT);
        String badge = L.t("wynnlabs.badge." + key);
        String text = L.t("wynnlabs.verdict_tip." + key);
        return badge.equals(label()) ? text : badge + ": " + text;
    }

    /** Any spelling: enum name, old Turkish word (any case). Unknown or empty → null. */
    static Verdict parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String t = token.strip().toUpperCase(Locale.forLanguageTag("tr"));
        String plain = token.strip().toUpperCase(Locale.ROOT).replace(' ', '_');
        for (Verdict v : values()) {
            if (v.name().equals(plain)) {
                return v;
            }
            for (String l : v.legacy) {
                if (l.equals(t) || l.equalsIgnoreCase(token.strip())) {
                    return v;
                }
            }
        }
        return null;
    }

    /** Like {@link #parse}, but never null: unknown words become NOTE. */
    public static Verdict of(String token) {
        Verdict v = parse(token);
        return v == null ? NOTE : v;
    }
}
