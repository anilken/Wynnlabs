package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.advice.Advice;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Settings;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The menu's shared drawing kit: ink on paper, cards, section titles, chips, step buttons and
 * the hover and click areas every tab adds to the screen's {@link RoadmapScreen#hits}.
 */
class MenuPaint {

    /** The menu screen this draws on. */
    final RoadmapScreen menu;

    MenuPaint(RoadmapScreen menu) {
        this.menu = menu;
    }

    /** The screen's font (it changes with the webFont setting). */
    net.minecraft.client.gui.Font font() {
        return menu.font();
    }

    /** A wooden button with its click action; returns its width. */
    int button(GuiGraphics g, int x, int y, String text, boolean primary, int mx, int my, String action) {
        return button(g, x, y, text, primary, mx, my, action, 7);
    }

    int button(GuiGraphics g, int x, int y, String text, boolean primary, int mx, int my, String action, int pad) {
        int w = font().width(text) + 2 * pad;
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 14;
        int state = primary ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
        Kit.button(g, x, y, w, 14, state);
        onWood(g, text, x + pad, y + 3, state);
        menu.hits.add(new MenuHit(x, y, w, 14, null, null, action));
        return w;
    }

    // palette: the J menu theme (Theme), under the names the screen has always used
    static final int CARD = Theme.CARD;
    static final int CARD_HOVER = Theme.CARD_HOVER;
    static final int LINE = Theme.LINE;
    static final int ACCENT = Theme.GOLD_DEEP;
    static final int ACCENT_DARK = Theme.WOOD;
    static final int GOOD = Theme.GOOD;
    static final int MUTED = Theme.INK_2;
    static final int FAINT = Theme.INK_3;
    static final int GOLD = Theme.PRICE;
    // tags on paper: a light wash of the colour with dark text in it
    static final int TAG_RED = 0xFFF2C9BE;
    static final int TAG_AMBER = 0xFFF0D090;
    static final int TAG_PURPLE = 0xFFDCC8EC;
    static final int TAG_GREEN = 0xFFCFE0B0;

    // ---- drawing kit ----

    /**
     * A parchment card with its accent as a strip along the top; on hover a gold outline. Cards
     * 13px or lower are buttons drawn as cards: those become wooden buttons.
     */
    void card(GuiGraphics g, int x, int y, int w, int h, boolean hover, int accent) {
        if (h <= 13) {
            Kit.button(g, x, y, w, h, hover ? Kit.HOVER : Kit.REST);
            return;
        }
        Kit.card(g, x, y, w, h, accent, hover);
    }

    /** An 8 px icon and its text on wood, in the button state's colour. */
    void woodIconText(GuiGraphics g, String icon, String text, int x, int y, int state) {
        Art.icon(g, icon, x, y, 8, Kit.buttonText(state));
        onWood(g, text, x + 10, y, state);
    }

    /** An 8 px icon and its text in ink on paper. */
    void inkIconText(GuiGraphics g, String icon, String text, int x, int y, int colour) {
        Art.icon(g, icon, x, y, 8, Theme.paperRgb(colour));
        ink(g, text, x + 10, y, colour);
    }

    /** A small section title: a brass diamond, the title, a rule running to the right edge. */
    void section(GuiGraphics g, String title, int x, int y, int w) {
        Kit.section(g, font(), title, x, y, w);
    }

    // ---- text on paper: dark ink, no shadow, chat colours in their paper tone ----

    void ink(GuiGraphics g, String s, int x, int y, int colour) {
        Kit.text(g, font(), s, x, y, Theme.paperRgb(colour));
    }

    void ink(GuiGraphics g, String s, int x, int y, int colour, boolean shadow) {
        ink(g, s, x, y, colour);
    }

    void ink(GuiGraphics g, Component c, int x, int y, int colour) {
        Kit.text(g, font(), c, x, y, Theme.paperRgb(colour));
    }

    void ink(GuiGraphics g, Component c, int x, int y, int colour, boolean shadow) {
        ink(g, c, x, y, colour);
    }

    void ink(GuiGraphics g, FormattedCharSequence s, int x, int y, int colour) {
        g.drawString(font(), Kit.ink(s), x, y, Theme.paperRgb(colour), false);
    }

    void ink(GuiGraphics g, FormattedCharSequence s, int x, int y, int colour, boolean shadow) {
        ink(g, s, x, y, colour);
    }

    /** Text on a wooden button or chip: cream with the game's shadow, ink on gold. */
    void onWood(GuiGraphics g, String s, int x, int y, int state) {
        g.drawString(font(), s, x, y, Kit.buttonText(state), state != Kit.ON);
    }

    void scaledText(GuiGraphics g, Component c, int x, int y, float s, int colour) {
        if (s != 1f && Settings.on("webFont")) {
            // a font of that size instead of scaling the glyphs, which would make them blocky
            ink(g, c.copy().withStyle(st -> UiFont.sized(st, s)), x, y, colour);
            return;
        }
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(s, s);
        ink(g, c, 0, 0, colour);
        g.pose().popMatrix();
    }

    void bigItem(GuiGraphics g, ItemStack stack, int x, int y) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(2f, 2f);
        g.renderItem(stack, 0, 0);
        g.pose().popMatrix();
    }

    String fit(String s, int w) {
        if (font().width(s) <= w) {
            return s;
        }
        return font().plainSubstrByWidth(s, Math.max(0, w - font().width("…"))) + "…";
    }

    void badge(GuiGraphics g, int tab, int x, int y, int size) {
        String icon;
        int bg;
        int fg;
        switch (tab) {
            case 0 -> { icon = "home"; bg = 0xFF26233A; fg = 0xFFAFA9EC; }
            case 1 -> { icon = "sun"; bg = 0xFF3A2A12; fg = 0xFFFAC775; }
            case 2 -> { icon = "map-2"; bg = 0xFF12273A; fg = 0xFF85B7EB; }
            case 3 -> { icon = "skull"; bg = 0xFF3A1616; fg = 0xFFF09595; }
            case RoadmapScreen.TAB_BANK -> { icon = "building-bank"; bg = 0xFF0F2A22; fg = 0xFF5DCAA5; }
            case RoadmapScreen.TAB_AI -> { icon = "sparkles"; bg = 0xFF12303A; fg = 0xFF7FD4E8; }
            case RoadmapScreen.TAB_DAMAGE -> { icon = "sword"; bg = 0xFF3A2414; fg = 0xFFF0B27A; }
            case RoadmapScreen.TAB_DISCOVERIES -> { icon = "compass"; bg = 0xFF2A1F3A; fg = 0xFFC9A7F0; }
            case RoadmapScreen.TAB_TREE -> { icon = "hierarchy"; bg = 0xFF1E3220; fg = 0xFF9FDC8A; }
            case RoadmapScreen.TAB_CRAFT -> { icon = "hammer"; bg = 0xFF3A2E14; fg = 0xFFE8C07A; }
            case RoadmapScreen.TAB_ENDGAME -> { icon = "trophy"; bg = 0xFF40280C; fg = 0xFFFFD36B; }
            case RoadmapScreen.TAB_SEARCH -> { icon = "search"; bg = 0xFF2E2414; fg = 0xFFF2D59A; }
            default -> { icon = "settings"; bg = 0xFF24242E; fg = 0xFFB4B2A9; }
        }
        Art.iconTile(g, icon, x, y, size, bg, fg);
    }

    /** A centred card for a page with nothing yet: what to do, step by step. */
    void emptyState(GuiGraphics g, int x, int y, int w, int h, ItemStack icon, String title, String... stepsText) {
        emptyState(g, x, y, w, h, icon, title, null, stepsText);
    }

    /** A button under an empty card's title, and a faint centred note under it ({@code note} may be null). */
    record EmptyButton(String label, int state, List<Component> tip, String action, String note) {}

    /**
     * A centred card for a page with nothing yet: the icon, the title, an optional button with a
     * note under it, then numbered steps. Its height follows what is in it, with the card's
     * padding below the last line at every layout size.
     */
    void emptyState(GuiGraphics g, int x, int y, int w, int h, ItemStack icon, String title, EmptyButton button,
            String... stepsText) {
        int pad = 10;
        int lh = font().lineHeight;
        int step = lh + 4;
        int cw = Math.min(w - 40, 300);
        List<FormattedCharSequence> note = button == null || button.note() == null ? List.of()
                : font().split(Component.literal(button.note()), cw - 2 * pad - 8);
        int titleY = 44;                            // under the 32 px icon, 8 from the top
        int chh = titleY + lh;
        int buttonY = chh + 6;
        if (button != null) {
            chh = buttonY + Kit.Ui.BTN_H;
        }
        int noteY = chh + 5;
        if (!note.isEmpty()) {
            chh = noteY + note.size() * (lh + 2) - 2;
        }
        int stepsY = chh + 5;
        if (stepsText.length > 0) {
            chh = stepsY + stepsText.length * step - 4;
        }
        chh += pad;
        int cx = x + (w - cw) / 2;
        int cy = y + Math.max(Math.min(10, Math.max(0, h - chh)), (h - chh) / 2 - 10);
        card(g, cx, cy, cw, chh, false, Theme.GOLD_DEEP);
        bigItem(g, icon, cx + (cw - 32) / 2, cy + 8);
        Component t = Component.literal(fit(title, cw - 2 * pad)).withStyle(UiFont::bold);
        ink(g, t, cx + (cw - font().width(t)) / 2, cy + titleY, Theme.INK);
        if (button != null) {
            String label = fit(button.label(), cw - 2 * pad - 2 * Kit.Ui.BTN_PAD);
            int bw = Kit.Ui.btnW(font(), label);
            int bx = cx + (cw - bw) / 2;
            int by = cy + buttonY;
            int state = button.state();          // gold when it can run, plain wood while it runs or cannot
            Kit.button(g, bx, by, bw, Kit.Ui.BTN_H, state);
            onWood(g, label, bx + Kit.Ui.BTN_PAD, by + 3, state);
            menu.hits.add(new MenuHit(bx, by, bw, Kit.Ui.BTN_H, null, button.tip(), button.action()));
        }
        for (int i = 0; i < note.size(); i++) {
            ink(g, note.get(i), cx + (cw - font().width(note.get(i))) / 2, cy + noteY + i * (lh + 2), FAINT);
        }
        int sy = cy + stepsY;
        for (int i = 0; i < stepsText.length; i++) {
            Component c = Component.literal((i + 1) + "  ").withStyle(ChatFormatting.DARK_PURPLE)
                    .append(Component.literal(fit(stepsText[i], cw - 30)).withStyle(ChatFormatting.GRAY));
            ink(g, c, cx + 14, sy, Theme.INK);
            sy += step;
        }
    }

    /** An edit box for paper: no frame of its own (Kit.field draws one), dark ink without a shadow. */
    static void paperField(net.minecraft.client.gui.components.EditBox box) {
        box.setBordered(false);
        box.setTextColor(Theme.INK);
        box.setTextShadow(false);
    }

    /**
     * A text box on the parchment: the field, the box's text inside it, and its hint (a
     * language key) in faint ink while it is empty and not being typed in. The game's own hint
     * is not used: it is drawn with a shadow, which smears on paper.
     */
    void textBox(GuiGraphics g, net.minecraft.client.gui.components.EditBox box, int x, int y, int w,
            String hint, int mx, int my, float pt) {
        Kit.field(g, x, y, w, 12, box.isFocused());
        box.setX(x + 4);
        box.setY(y + 2);
        box.setWidth(w - 8);
        box.render(g, mx, my, pt);
        if (box.getValue().isEmpty() && !box.isFocused()) {
            ink(g, fit(L.t(hint), w - 8), x + 4, y + 2, Theme.INK_3, false);
        }
    }

    static String ago(long t) {
        long min = (System.currentTimeMillis() - t) / 60000;
        if (min < 60) {
            return L.t("wynnlabs.screen.ago_min", min);
        }
        if (min < 24 * 60) {
            return L.t("wynnlabs.screen.ago_hours", min / 60);
        }
        return L.n("wynnlabs.screen.ago_days", min / 1440);
    }

    /**
     * A row of buttons ({text, primary, action}) that stays inside {@code maxW}: 7px each side of
     * the text when there is room, less down to 3px, and a second row above when even that does not fit.
     */
    /** How much higher than one row {@link #buttonRow} reaches: 17 when it needs two rows, else 0. */
    int buttonRowExtra(int maxW, List<Object[]> acts) {
        int text = 0;
        for (Object[] a : acts) {
            text += font().width((String) a[0]);
        }
        int gaps = 4 * (acts.size() - 1);
        int pad = Math.max(3, Math.min(7, (maxW - gaps - text) / (2 * acts.size())));
        return text + gaps + 2 * pad * acts.size() > maxW && acts.size() > 1 ? 17 : 0;
    }

    void buttonRow(GuiGraphics g, int x, int y, int maxW, List<Object[]> acts, int mx, int my) {
        int text = 0;
        for (Object[] a : acts) {
            text += font().width((String) a[0]);
        }
        int gaps = 4 * (acts.size() - 1);
        int pad = Math.max(3, Math.min(7, (maxW - gaps - text) / (2 * acts.size())));
        int split = acts.size();
        if (text + gaps + 2 * pad * acts.size() > maxW && acts.size() > 1) {
            split = (acts.size() + 1) / 2;          // two rows: the first half on top
            pad = 7;
        }
        int bx = x;
        int by = split < acts.size() ? y - 17 : y;
        for (int i = 0; i < acts.size(); i++) {
            if (i == split) {
                bx = x;
                by = y;
            }
            Object[] a = acts.get(i);
            bx += button(g, bx, by, (String) a[0], (boolean) a[1], mx, my, (String) a[2], pad) + 4;
        }
    }

    /** A chip that is on or off, with its action; returns its width. */
    /** A chip with an 8 px icon before its label. */
    int pickChip(GuiGraphics g, int x, int y, String icon, String label, boolean on, int mx, int my, String action) {
        int cw = font().width(label) + 22;
        boolean hover = mx >= x && mx < x + cw && my >= y && my < y + 14;
        int state = on ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
        Kit.button(g, x, y, cw, 14, state);
        woodIconText(g, icon, label, x + 6, y + 3, state);
        menu.hits.add(new MenuHit(x, y, cw, 14, null, null, action));
        return cw;
    }

    int pickChip(GuiGraphics g, int x, int y, String label, boolean on, int mx, int my, String action) {
        int cw = font().width(label) + 12;
        boolean hover = mx >= x && mx < x + cw && my >= y && my < y + 14;
        int state = on ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
        Kit.button(g, x, y, cw, 14, state);
        onWood(g, label, x + 6, y + 3, state);
        menu.hits.add(new MenuHit(x, y, cw, 14, null, null, action));
        return cw;
    }

    /** The gold dot of a setting changed from its default. */
    static void dot(GuiGraphics g, int x, int y) {
        Art.box(g, x, y, 6, 6, 3, Theme.GOLD_DEEP, Theme.GOLD_EDGE);
    }

    static final float DESC_SCALE = 0.85f;

    /** A setting's one-line description: a little smaller and dimmer than its title, cut to fit. */
    void desc(GuiGraphics g, String text, int x, int y, int w) {
        String cut = fit(text, (int) (w / DESC_SCALE));
        scaledText(g, Component.literal(cut), x, y, DESC_SCALE, 0xFF6E6C78);
    }

    /** A description wrapped to at most {@code lines} lines, 9px apart; the last one cut with "…". Returns the lines drawn. */
    int descWrap(GuiGraphics g, String text, int x, int y, int w, int lines) {
        List<String> rows = wrapText(text, (int) (w / DESC_SCALE), lines);
        for (int i = 0; i < rows.size(); i++) {
            scaledText(g, Component.literal(rows.get(i)), x, y + i * 9, DESC_SCALE, 0xFF6E6C78);
        }
        return rows.size();
    }

    /** {@code text} split into at most {@code lines} lines of {@code w}px, the last one cut with "…" when more is left. */
    List<String> wrapText(String text, int w, int lines) {
        List<String> out = new ArrayList<>();
        String rest = text.trim();
        while (!rest.isEmpty() && out.size() < lines) {
            if (out.size() == lines - 1 || font().width(rest) <= w) {
                out.add(fit(rest, w));
                break;
            }
            String head = font().plainSubstrByWidth(rest, w);
            int cut = head.lastIndexOf(' ');
            if (cut > 0) {
                head = head.substring(0, cut);
            }
            out.add(head);
            rest = rest.substring(head.length()).trim();
        }
        return out;
    }

    /** A setting's full title and description as a tooltip, for the text the row had to cut. */
    void rowTip(int x, int y, int w, int h, String title, String desc) {
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(title).withStyle(ChatFormatting.WHITE));
        for (String l : Advice.wrap(desc, 44)) {
            tip.add(Component.literal(l).withStyle(ChatFormatting.GRAY));
        }
        menu.hits.add(new MenuHit(x, y, w, h, null, tip, null));
    }

    /** An on/off switch: a small pill with its knob left (off) or right (on). */
    void pill(GuiGraphics g, int x, int y, boolean on) {
        Kit.toggle(g, x, y, on);
    }

    void stepButton(GuiGraphics g, int x, int y, String s, int mx, int my, String id) {
        boolean hover = mx >= x && mx < x + 12 && my >= y && my < y + 12;
        String tex = s.equals("+") ? "wynn/gui/button/plus" : "wynn/gui/button/minus";
        Kit.button(g, x, y, 12, 12, hover ? Kit.HOVER : Kit.REST);
        if (!WynnTex.draw(g, tex, x + 2, y + 2, 8, 8)) {
            onWood(g, s, x + (12 - font().width(s)) / 2 + 1, y + 2, hover ? Kit.HOVER : Kit.REST);
        }
        menu.toggles.add(new Object[] {new int[] {x, y, 12, 12}, id});
    }

    /** {@code v} with {@code digits} decimals and the language's separator ("4.5", "4,5"). */
    static String dec(double v, int digits) {
        String s = String.format(Locale.ROOT, "%." + digits + "f", v);
        int dot = s.indexOf('.');
        return dot < 0 ? s : L.t("wynnlabs.damage.decimal", s.substring(0, dot), s.substring(dot + 1));
    }

    static boolean in(double x, double y, int[] r) {
        return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

    static String nice(String upper) {
        if (upper == null || upper.isEmpty()) {
            return "";
        }
        String n = upper.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
