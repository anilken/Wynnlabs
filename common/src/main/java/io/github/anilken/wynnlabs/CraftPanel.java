package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.CraftingTab.Act;
import io.github.anilken.wynnlabs.crafting.CraftSearch;
import io.github.anilken.wynnlabs.crafting.Ingredient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.github.anilken.wynnlabs.CraftFormat.signed;
import static io.github.anilken.wynnlabs.CraftFormat.skill;

/**
 * A part of the crafting tab ({@link CraftingTab}): the tab's text sizes, hit areas and the small
 * controls every part draws with. The tab holds the state; a part reads and changes it through
 * {@link #tab}.
 */
abstract class CraftPanel {

    static final int ROW_H = 12;               // fields, buttons and segments
    // text: the mock-up's sizes (body 7.8 GUI px, fine print 7) when the web font is on
    private static final float BODY = 0.87f;
    static final float FINE = 0.75f;

    final CraftingTab tab;

    CraftPanel(CraftingTab tab) {
        this.tab = tab;
    }

    /** {@code n} small filled stars from {@code x}. */
    static void starIcons(GuiGraphics g, int x, int y, int n, int colour) {
        for (int i = 0; i < n; i++) {
            Art.icon(g, "star-filled", x + i * 6, y, 6, colour);
        }
    }

    /** A small coloured tag ending at {@code right}; returns its width. */
    int tag(GuiGraphics g, int right, int y, String text, boolean good, boolean draw) {
        int tw = fineWidth(text) + 8;
        if (draw) {
            Art.pill(g, right - tw, y, tw, 10, good ? 0xFFD9E7C7 : 0xFFF3D0C8, 0);
            fine(g, text, right - tw + 4, y + 1, good ? Theme.GOOD : Theme.BAD);
        }
        return tw;
    }

    /** A hit area inside the scrolled page: only where it can be seen. */
    void clipped(int x, int y, int w, int h, Runnable left, Runnable right, List<Component> tip) {
        if (tab.clip == null) {
            tab.acts.add(new Act(x, y, w, h, left, right, tip));
            return;
        }
        int x1 = Math.max(x, tab.clip[0]);
        int y1 = Math.max(y, tab.clip[1]);
        int x2 = Math.min(x + w, tab.clip[0] + tab.clip[2]);
        int y2 = Math.min(y + h, tab.clip[1] + tab.clip[3]);
        if (x2 > x1 && y2 > y1) {
            tab.acts.add(new Act(x1, y1, x2 - x1, y2 - y1, left, right, tip));
        }
    }

    boolean visible(int x, int y, int w, int h) {
        return tab.clip == null || (y + h > tab.clip[1] && y < tab.clip[1] + tab.clip[3]);
    }

    /** An ingredient's card as tooltip lines. */
    List<Component> card(Ingredient in) {
        List<Component> out = new ArrayList<>();
        out.add(Component.literal(in.name() + " ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal("[" + "✫".repeat(Math.max(0, in.tier())) + "]").withStyle(ChatFormatting.GOLD)));
        for (Map.Entry<String, Ingredient.Stat> e : in.ids().entrySet()) {
            Ingredient.Stat s = e.getValue();
            String u = s.unit() == null ? "" : s.unit();
            boolean good = s.max() > 0;
            out.add(Component.literal(signed(s.min()) + u + " … " + signed(s.max()) + u + " " + tab.statName(e.getKey()))
                    .withStyle(good ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
        String[] names = {"left", "right", "above", "under", "touching", "not_touching"};
        for (int p = 0; p < 6; p++) {
            int v = in.positions()[p];
            if (v != 0) {
                out.add(L.c("wynnlabs.craft.pos." + names[p], (v > 0 ? "+" : "−") + Math.abs(v))
                        .withStyle(v > 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
            }
        }
        List<String> extra = new ArrayList<>();
        if (in.durability() != 0) {
            extra.add(L.t("wynnlabs.craft.card_durability", signed(in.durability())));
        }
        if (in.duration() != 0) {
            extra.add(L.t("wynnlabs.craft.card_duration", signed(in.duration())));
        }
        if (in.charges() != 0) {
            extra.add(L.t("wynnlabs.craft.card_charges", signed(in.charges())));
        }
        for (int s = 0; s < 5; s++) {
            if (in.requirements()[s] != 0) {
                extra.add(skill(s) + " " + signed(in.requirements()[s]));
            }
        }
        if (!extra.isEmpty()) {
            out.add(Component.literal(String.join(" · ", extra)).withStyle(ChatFormatting.GRAY));
        }
        List<String> profs = new ArrayList<>();
        for (String p : in.professions()) {
            profs.add(Professions.name(p.toUpperCase(Locale.ROOT)));
        }
        out.add(Component.literal("Lv " + in.level() + " · " + String.join(", ", profs)).withStyle(ChatFormatting.GRAY));
        CraftSearch.Price p = tab.prices.get(in.name());
        out.add(L.c("wynnlabs.craft.card_own", tab.owned(in.name()), p == null ? "?" : "~" + Prices.fmt(p.emeralds()))
                .withStyle(ChatFormatting.GOLD));
        return out;
    }

    /** A short name for a chip: the first word, or the first two when the first is a raw/element prefix. */
    String shortStat(String id) {
        String n = tab.statName(id);
        return n.length() <= 12 ? n : fit(n, 60);
    }

    String fit(String s, int w) {
        if (w <= 0) {
            return "";
        }
        if (width(s) <= w) {
            return s;
        }
        int lo = 0;
        int hi = s.length();
        while (lo < hi) {                   // the longest start that fits with the ellipsis
            int mid = (lo + hi + 1) / 2;
            if (width(s.substring(0, mid) + "…") <= w) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return s.substring(0, lo) + "…";
    }

    static net.minecraft.network.chat.MutableComponent sized(Component c, float f) {
        return Settings.on("webFont") ? c.copy().withStyle(st -> UiFont.sized(st, f)) : c.copy();
    }

    /** A section title in the tab's size: bold ink, a brass dot and a rule to the right edge. */
    void section(GuiGraphics g, String title, int x, int y, int w) {
        Component t = Component.literal(title).withStyle(UiFont::bold);
        text(g, t, x, y, Theme.INK_2);
        int dx = x + width(t) + 4;
        g.fill(dx, y + 3, dx + 3, y + 6, Theme.BRASS);
        if (dx + 7 < x + w) {
            g.fill(dx + 7, y + 4, x + w, y + 5, Theme.LINE);
        }
    }

    void text(GuiGraphics g, String s, int x, int y, int colour) {
        Kit.text(g, tab.font, sized(Component.literal(s), BODY), x, y, colour);
    }

    void text(GuiGraphics g, Component c, int x, int y, int colour) {
        Kit.text(g, tab.font, sized(c, BODY), x, y, colour);
    }

    /** Fine print: hints, units, counts. */
    void fine(GuiGraphics g, String s, int x, int y, int colour) {
        Kit.text(g, tab.font, sized(Component.literal(s), FINE), x, y, colour);
    }

    String fitFine(String s, int w) {
        if (w <= 0) {
            return "";
        }
        if (fineWidth(s) <= w) {
            return s;
        }
        int lo = 0;
        int hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (fineWidth(s.substring(0, mid) + "…") <= w) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return s.substring(0, lo) + "…";
    }

    int fineWidth(String s) {
        return tab.font.width(sized(Component.literal(s), FINE));
    }

    int width(String s) {
        return tab.font.width(sized(Component.literal(s), BODY));
    }

    int width(Component c) {
        return tab.font.width(sized(c, BODY));
    }

    /** Text on wood (buttons, segments): cream with the game's shadow, ink on gold. */
    void wood(GuiGraphics g, String s, int x, int y, int colour, boolean shadow) {
        g.drawString(tab.font, sized(Component.literal(s), BODY), x, y, colour, shadow);
    }

    static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
