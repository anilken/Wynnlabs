package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.crafting.Craft;
import io.github.anilken.wynnlabs.crafting.CraftData;
import io.github.anilken.wynnlabs.crafting.Ingredient;
import io.github.anilken.wynnlabs.crafting.Types;
import io.github.anilken.wynnlabs.game.Professions;
import io.github.anilken.wynnlabs.ui.CraftingTab.Drop;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.github.anilken.wynnlabs.ui.CraftFormat.dec;
import static io.github.anilken.wynnlabs.ui.CraftFormat.pct;
import static io.github.anilken.wynnlabs.ui.CraftFormat.typeIcon;
import static io.github.anilken.wynnlabs.ui.CraftFormat.typeName;

/**
 * The crafting tab's recipe column: type and level fields, attack speed, the two materials with
 * their tier pickers, and the 2×3 grid of ingredients with each slot's effectiveness.
 */
final class CraftRecipePanel extends CraftPanel {

    private static final int SLOT_BG = 0xFF3B332A;      // the grid's dark slots (mock-up v3)
    private static final int SLOT_EDGE = 0xFF241E16;
    private static final int SLOT_TEXT = 0xFFF2E6CC;
    private static final int SLOT_DIM = 0xFFCBB995;
    private static final int SLOT_EMPTY = 0xFF9C8C70;
    private static final int SLOT_UP = 0xFF9FE07A;
    private static final int SLOT_DOWN = 0xFFFF9A8A;
    private static final int STAR = 0xFFF0D27A;

    CraftRecipePanel(CraftingTab tab) {
        super(tab);
    }

    int drawRecipe(GuiGraphics g, int x, int y, int w, int mx, int my) {
        CraftData.Recipe r = tab.recipe();
        int lv = tab.level(r.profession());
        int yy = y;
        // type and level as drop-down fields
        String typeLabel = L.t("wynnlabs.craft.type");
        text(g, typeLabel, x, yy + 2, Theme.INK_2);
        int fx = x + width(typeLabel) + 4;
        int tw = 78;
        dropField(g, fx, yy, tw, typeName(tab.type), tab.open == Drop.TYPE, mx, my, false);
        Art.icon(g, typeIcon(tab.type), fx + tw - 22, yy + 2, 8, Theme.paperRgb(Theme.INK_2));
        int[] typeAnchor = {fx, yy, tw, ROW_H};
        clipped(fx, yy, tw, ROW_H, () -> tab.toggle(Drop.TYPE, -1, typeAnchor), null, null);
        int lx = fx + tw + 8;
        String levelLabel = L.t("wynnlabs.craft.level");
        text(g, levelLabel, lx, yy + 2, Theme.INK_2);
        int lfx = lx + width(levelLabel) + 4;
        int lw = Math.min(58, x + w - lfx);
        boolean locked = r.minLevel() > lv;
        dropField(g, lfx, yy, lw, r.bracket(), tab.open == Drop.LEVEL, mx, my, locked);
        int[] levelAnchor = {lfx, yy, lw, ROW_H};
        clipped(lfx, yy, lw, ROW_H, () -> tab.toggle(Drop.LEVEL, -1, levelAnchor), null, locked
                ? List.of(L.c("wynnlabs.craft.locked_recipe", Professions.name(r.profession().toUpperCase(Locale.ROOT)),
                        r.minLevel(), lv)) : null);
        yy += ROW_H + 4;
        if (Types.weapon(tab.type)) {
            String sl = L.t("wynnlabs.craft.speed");
            text(g, sl, x, yy + 2, Theme.INK_2);
            int sx = x + width(sl) + 4;
            for (Craft.Speed s : Craft.Speed.values()) {
                String name = L.t("wynnlabs.craft.speed." + s.name().toLowerCase(Locale.ROOT));
                int bw = width(name) + 10;
                boolean on = s == tab.speed;
                int state = on ? Kit.ON : in(mx, my, sx, yy, bw, ROW_H) ? Kit.HOVER : Kit.REST;
                Kit.button(g, sx, yy, bw, ROW_H, state);
                wood(g, name, sx + 5, yy + 2, Kit.buttonText(state), !on);
                clipped(sx, yy, bw, ROW_H, () -> tab.speed = s, null, null);
                sx += bw + 2;
            }
            yy += ROW_H + 4;
        }
        // the two materials, each with its amount and a three-part tier picker showing what is owned
        int segW = 36;
        for (int m = 0; m < 2; m++) {
            CraftData.Material mat = r.materials().get(m);
            int chosen = m == 0 ? tab.tier1 : tab.tier2;
            Component amount = Component.literal(mat.amount() + "x ").withStyle(UiFont::bold);
            text(g, amount, x, yy + 2, Theme.INK);
            int segX = x + w - 3 * segW;
            int nameX = x + width(amount);
            text(g, fit(mat.shown(), segX - nameX - 4), nameX, yy + 2, Theme.INK);
            int mi = m;
            for (int t = 1; t <= 3; t++) {
                int have = tab.stock.material(mat.shown(), t);
                int sx = segX + (t - 1) * segW;
                boolean on = t == chosen;
                boolean hover = in(mx, my, sx, yy, segW - 1, ROW_H);
                Kit.button(g, sx, yy, segW - 1, ROW_H, on ? Kit.ON : hover ? Kit.HOVER : Kit.REST);
                starIcons(g, sx + 4, yy + 3, t, on ? 0xFF7A4A00 : STAR);
                String c = String.valueOf(have);
                boolean shortOf = have < mat.amount();
                int col = on ? (shortOf ? Theme.BAD : Theme.INK) : (shortOf ? 0xFFFFB0A0 : Theme.CREAM);
                text(g, c, sx + segW - 3 - width(c), yy + 2, col);
                int tier = t;
                clipped(sx, yy, segW, ROW_H, () -> {
                    if (mi == 0) {
                        tab.tier1 = tier;
                    } else {
                        tab.tier2 = tier;
                    }
                }, null, List.of(L.c("wynnlabs.craft.tier_tip", mat.shown(), tier, have, mat.amount()),
                        L.c("wynnlabs.craft.tier_weights")));
            }
            yy += ROW_H + 3;
        }
        String mult = L.t("wynnlabs.craft.multiplier", dec(Craft.weightPercent(r, tab.tier1, tab.tier2) / 100.0, 2));
        fine(g, mult, x + w - fineWidth(mult), yy, Theme.INK_3);
        yy += 10;
        yy += drawGrid(g, x, yy, w, mx, my);
        // the hint under the grid; one column (a small window) scrolls it out of view, so it goes
        if (tab.twoColumns) {
            fine(g, fitFine(L.t("wynnlabs.craft.grid_hint"), w), x, yy, Theme.INK_3);
            yy += 10;
        }
        return yy - y;
    }

    private void dropField(GuiGraphics g, int x, int y, int w, String text, boolean opened, int mx, int my, boolean locked) {
        boolean hover = in(mx, my, x, y, w, ROW_H);
        Kit.field(g, x, y, w, ROW_H, opened || hover);
        int tx = x + 4;
        if (locked) {
            Art.icon(g, "lock", tx, y + 2, 8, Theme.paperRgb(Theme.BAD));
            tx += 10;
        }
        text(g, fit(text, x + w - 14 - tx), tx, y + 2, locked ? Theme.BAD : Theme.INK);
        Art.icon(g, "chevron-down", x + w - 11, y + 2, 8, Theme.paperRgb(Theme.INK_2));
    }

    /** Each meta's letter colour, in the order the metas sit on the grid (A, B, C …). */
    private static final int[] META_HUES = {0xFF77D1D7, 0xFFF0B04A, 0xFFD8BCF5, 0xFF9FE07A, 0xFFF09595, 0xFF85B7EB};

    private static final String[] POS_KEYS = {"left", "right", "above", "under", "touching", "not_touching"};

    /**
     * What each meta gives each slot in this layout, in effectiveness points: {@code give[from][to]},
     * 0 where it changes nothing. A meta's own slot is 0.
     */
    private static int[][] gives(List<Ingredient> lay) {
        int[][] give = new int[Craft.SLOTS][Craft.SLOTS];
        for (int from = 0; from < Craft.SLOTS; from++) {
            Ingredient m = lay.get(from);
            if (m.empty() || !m.meta()) {
                continue;
            }
            List<Ingredient> alone = new ArrayList<>(Craft.slots());
            alone.set(from, m);
            int[] only = Craft.effectiveness(alone);
            for (int to = 0; to < Craft.SLOTS; to++) {
                give[from][to] = to == from ? 0 : only[to] - 100;
            }
        }
        return give;
    }

    /** A meta's effects that reach no slot from where it sits (left of the left column …), as position indices. */
    private static List<Integer> wasted(Ingredient m, int slot) {
        List<Integer> out = new ArrayList<>();
        int row = slot / 2;
        int col = slot % 2;
        int[] p = m.positions();
        if (p[Ingredient.LEFT] != 0 && col == 0) {
            out.add(Ingredient.LEFT);
        }
        if (p[Ingredient.RIGHT] != 0 && col == 1) {
            out.add(Ingredient.RIGHT);
        }
        if (p[Ingredient.ABOVE] != 0 && row == 0) {
            out.add(Ingredient.ABOVE);
        }
        if (p[Ingredient.UNDER] != 0 && row == 2) {
            out.add(Ingredient.UNDER);
        }
        return out;
    }

    /**
     * The 2×3 grid of dark slots (option B with mini-maps): a meta carries a letter and a small
     * map of the slots it pushes up (green) or down (red); every slot shows its effectiveness on a
     * coloured chip and what each meta gives it ("A+60 B+25 C−15").
     */
    private int drawGrid(GuiGraphics g, int x, int y, int w, int mx, int my) {
        List<Ingredient> lay = tab.layout();
        List<Ingredient> shown = tab.preview != null ? tab.preview : lay;
        int[] eff = Craft.effectiveness(shown);
        int[] base = Craft.effectiveness(lay);
        int[][] give = gives(shown);
        int[] letter = new int[Craft.SLOTS];
        int metas = 0;
        for (int s = 0; s < Craft.SLOTS; s++) {
            letter[s] = shown.get(s).meta() ? metas++ : -1;
        }
        int gap = 3;
        int cellW = (w - gap) / 2;
        int cellH = 26;
        CraftData.Recipe r = tab.recipe();
        int lv = tab.level(r.profession());
        for (int s = 0; s < Craft.SLOTS; s++) {
            int cx = x + (s % 2) * (cellW + gap);
            int cy = y + (s / 2) * (cellH + gap);
            Ingredient in = shown.get(s);
            boolean hover = in(mx, my, cx, cy, cellW, cellH);
            boolean opened = tab.open == Drop.SLOT && tab.openSlot == s;
            boolean changed = tab.preview != null && (eff[s] != base[s] || !in.name().equals(lay.get(s).name()));
            int e = eff[s];
            g.fill(cx, cy, cx + cellW, cy + cellH, hover ? 0xFF4A4034 : SLOT_BG);
            Kit.outline(g, cx, cy, cellW, cellH, SLOT_EDGE);
            Kit.outline(g, cx + 1, cy + 1, cellW - 2, cellH - 2, e <= 0 ? 0xFFE0503C : SLOT_EDGE);
            if (opened) {
                Kit.outline(g, cx, cy, cellW, cellH, 0xFFE3BD55);
                Kit.outline(g, cx + 1, cy + 1, cellW - 2, cellH - 2, 0xFFE3BD55);
            } else if (changed) {
                dashed(g, cx, cy, cellW, cellH, 0xFFE3BD55);
            }
            // line 1: the meta's letter, the name, how many are owned, the meta's map
            int nx = cx + 5;
            int right = cx + cellW - 5;
            if (letter[s] >= 0) {
                int hue = META_HUES[letter[s] % META_HUES.length];
                // the letter in the name's own font and line, its square around the letter's height
                Component l = Component.literal(String.valueOf((char) ('A' + letter[s]))).withStyle(UiFont::bold);
                int lw = width(l);
                g.fill(nx, cy + 4, nx + lw + 4, cy + 13, hue);
                text(g, l, nx + 2, cy + 4, 0xFF241E16);
                nx += lw + 7;
                miniMap(g, right - 9, cy + 4, s, give[s]);
                right -= 13;
            }
            if (in.empty()) {
                String unknown = tab.slots[s] != null && tab.preview == null ? tab.slots[s] : null;
                text(g, unknown != null ? fit("? " + unknown, right - nx) : L.t("wynnlabs.craft.add"), nx, cy + 4,
                        unknown != null ? SLOT_DOWN : SLOT_EMPTY);
            } else {
                boolean locked = in.level() > lv;
                String count = "×" + tab.owned(in.name());
                if (locked) {
                    Art.icon(g, "lock", nx, cy + 4, 8, SLOT_DOWN);
                    nx += 10;
                }
                int cw = fineWidth(count);
                fine(g, count, right - cw, cy + 5, tab.owned(in.name()) > 0 ? SLOT_DIM : SLOT_EMPTY);
                text(g, fit(in.name(), right - cw - 4 - nx), nx, cy + 4, locked ? SLOT_EMPTY : SLOT_TEXT);
            }
            // line 2: the % chip, then what each meta gives this slot
            int chipX = cx + 4;
            int chipY = cy + 15;
            int[] band = band(e);
            g.fill(chipX, chipY, chipX + 30, chipY + 9, band[0]);
            String pc = pct(e);
            Component pcc = band[2] == 1 ? Component.literal(pc).withStyle(UiFont::bold) : Component.literal(pc);
            Kit.text(g, tab.font, sized(pcc, FINE), chipX + (30 - tab.font.width(sized(pcc, FINE))) / 2, chipY + 1, band[1]);
            int tx = chipX + 34;
            for (int from = 0; from < Craft.SLOTS; from++) {
                int d = give[from][s];
                if (d == 0 || letter[from] < 0) {
                    continue;
                }
                String l = String.valueOf((char) ('A' + letter[from]));
                String v = (d > 0 ? "+" : "−") + Math.abs(d);
                int need = fineWidth(l) + fineWidth(v) + 4;
                if (tx + need > cx + cellW - 4) {
                    fine(g, "…", tx, chipY + 1, SLOT_DIM);
                    break;
                }
                fine(g, l, tx, chipY + 1, META_HUES[letter[from] % META_HUES.length]);
                tx += fineWidth(l);
                fine(g, v, tx, chipY + 1, d > 0 ? SLOT_UP : SLOT_DOWN);
                tx += fineWidth(v) + 4;
            }
            int slot = s;
            int[] anchor = {cx, cy, cellW, cellH};
            List<Component> tip = new ArrayList<>(breakdown(shown, give, letter, s));
            if (in.empty()) {
                tip.add(L.c("wynnlabs.craft.slot_tip", pct(e)));
            } else {
                tip.addAll(card(in));
            }
            clipped(cx, cy, cellW, cellH, () -> {
                tab.toggle(Drop.SLOT, slot, anchor);
                tab.pickSearch.setValue("");
                tab.screen.setFocused(tab.pickSearch);
                tab.pickSearch.setFocused(true);
            }, () -> tab.slots[slot] = null, tip);
        }
        return 3 * cellH + 2 * gap + 3;
    }

    /** {fill, text colour, bold} of the % chip: strong green, green, neutral, pink, and red at 0% or below. */
    private static int[] band(int e) {
        if (e >= 200) {
            return new int[] {0xFF3E6B26, 0xFFE4FFC9, 1};
        }
        if (e > 100) {
            return new int[] {0xFF2F4423, 0xFF9FE07A, 0};
        }
        if (e == 100) {
            return new int[] {0xFF2E2820, 0xFFCBB995, 0};
        }
        if (e > 0) {
            return new int[] {0xFF4A2C22, 0xFFFF9A8A, 0};
        }
        return new int[] {0xFF7A2A1C, 0xFFFFE3DA, 1};
    }

    /** A meta's 2×3 map (9×11): its own slot gold, slots it pushes up green, down red, the rest dark. */
    private static void miniMap(GuiGraphics g, int x, int y, int self, int[] give) {
        for (int t = 0; t < Craft.SLOTS; t++) {
            int sx = x + (t % 2) * 5;
            int sy = y + (t / 2) * 4;
            int c = t == self ? 0xFFE3BD55 : give[t] > 0 ? 0xFF5FB040 : give[t] < 0 ? 0xFFE0503C : SLOT_EDGE;
            g.fill(sx, sy, sx + 4, sy + 3, c);
        }
    }

    /** The tooltip's first lines for a slot: how its % adds up, and for a meta what it gives and wastes. */
    private List<Component> breakdown(List<Ingredient> lay, int[][] give, int[] letter, int s) {
        List<Component> out = new ArrayList<>();
        int e = Craft.effectiveness(lay)[s];
        StringBuilder sb = new StringBuilder("100");
        boolean any = false;
        for (int from = 0; from < Craft.SLOTS; from++) {
            int d = give[from][s];
            if (d != 0) {
                any = true;
                sb.append(' ').append(d > 0 ? "+" : "−").append(Math.abs(d)).append(' ')
                        .append((char) ('A' + letter[from])).append(' ').append(lay.get(from).name());
            }
        }
        if (any) {
            out.add(L.c("wynnlabs.craft.slot_sum", pct(e), sb.toString()).withStyle(ChatFormatting.GRAY));
        }
        Ingredient m = lay.get(s);
        if (m.meta()) {
            List<String> parts = new ArrayList<>();
            for (int to = 0; to < Craft.SLOTS; to++) {
                if (give[s][to] != 0) {
                    parts.add((give[s][to] > 0 ? "+" : "−") + Math.abs(give[s][to]) + " → " + (to + 1));
                }
            }
            if (!parts.isEmpty()) {
                out.add(L.c("wynnlabs.craft.slot_gives", (char) ('A' + letter[s]), String.join(", ", parts))
                        .withStyle(ChatFormatting.GRAY));
            }
            for (int p : wasted(m, s)) {
                out.add(L.c("wynnlabs.craft.slot_wasted", L.t("wynnlabs.craft.pos." + POS_KEYS[p],
                        (m.positions()[p] > 0 ? "+" : "−") + Math.abs(m.positions()[p])))
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        return out;
    }

    private static void dashed(GuiGraphics g, int x, int y, int w, int h, int c) {
        for (int i = 0; i < w; i += 4) {
            g.fill(x + i, y, x + Math.min(w, i + 2), y + 1, c);
            g.fill(x + i, y + h - 1, x + Math.min(w, i + 2), y + h, c);
        }
        for (int i = 0; i < h; i += 4) {
            g.fill(x, y + i, x + 1, y + Math.min(h, i + 2), c);
            g.fill(x + w - 1, y + i, x + w, y + Math.min(h, i + 2), c);
        }
    }
}
