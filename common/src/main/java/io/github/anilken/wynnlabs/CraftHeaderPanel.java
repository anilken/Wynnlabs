package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.CraftingTab.Act;
import io.github.anilken.wynnlabs.CraftingTab.Drop;
import io.github.anilken.wynnlabs.crafting.Craft;
import io.github.anilken.wynnlabs.crafting.CraftChecks;
import io.github.anilken.wynnlabs.crafting.CraftData;
import io.github.anilken.wynnlabs.crafting.RecipeCode;
import io.github.anilken.wynnlabs.crafting.WynnBuilderCode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static io.github.anilken.wynnlabs.CraftFormat.thousands;

/**
 * The crafting tab's frame: the header line (profession progress, reset, paste and code buttons),
 * the calculator/suggestions tabs, the sub-tabs of the one-column page and the card shown when there
 * is no ingredient data.
 */
final class CraftHeaderPanel extends CraftPanel {

    CraftHeaderPanel(CraftingTab tab) {
        super(tab);
    }

    void noData(GuiGraphics g, int x, int y, int w, int h) {
        int cw = Math.min(w - 40, 320);
        int cx = x + (w - cw) / 2;
        int cy = y + Math.max(10, h / 2 - 40);
        Kit.card(g, cx, cy, cw, 62, Theme.GOLD_DEEP);
        Art.icon(g, "hammer", cx + (cw - 16) / 2, cy + 8, 16, Theme.paperRgb(Theme.INK_2));
        String t = L.t("wynnlabs.craft.no_data");
        text(g, Component.literal(t).withStyle(UiFont::bold), cx + (cw - width(t)) / 2, cy + 30, Theme.INK);
        String s = fit(L.t("wynnlabs.craft.no_data_hint"), cw - 16);
        text(g, s, cx + (cw - width(s)) / 2, cy + 44, Theme.INK_3);
    }

    void drawHeader(GuiGraphics g, int x, int ty, int w, int mx, int my) {
        int bx = x + w;
        String[][] buttons = {{"refresh", L.t("wynnlabs.craft.reset"), "reset"}, {"clipboard", L.t("wynnlabs.craft.paste"), "paste"},
            {"copy", L.t("wynnlabs.craft.code"), "copy"}};
        for (String[] b : buttons) {
            int bw = width(b[1]) + 18;
            bx -= bw;
            int by = ty - 2;
            boolean hover = in(mx, my, bx, by, bw, 12);
            int state = hover ? Kit.HOVER : Kit.REST;
            Kit.button(g, bx, by, bw, 12, state);
            Art.icon(g, b[0], bx + 4, by + 2, 8, Kit.buttonText(state));
            wood(g, b[1], bx + 14, by + 2, Kit.buttonText(state), true);
            String what = b[2];
            tab.acts.add(new Act(bx, by, bw, 12, () -> header(what), null,
                    List.of(L.c("wynnlabs.craft.tip." + what))));
            bx -= 3;
        }
        if (tab.type == null) {
            return;
        }
        CraftData.Recipe r = tab.recipe();
        int known = tab.knownLevel(r.profession());
        Object lv = known > 0 ? known : "?";
        int[] xp = Crafting.professionXp(r.profession());
        int crafts = known > 0 ? CraftChecks.craftsToLevel(r.xp(), xp[0], xp[1]) : -1;
        String prof = Professions.name(r.profession().toUpperCase(Locale.ROOT));
        String line = crafts < 0 ? L.t("wynnlabs.craft.prof_line_unknown", prof, lv, thousands(r.xp()))
                : L.n("wynnlabs.craft.prof_line", crafts, prof, known, known + 1, crafts, thousands(r.xp()));
        int titleW = tab.font.width(L.t("wynnlabs.screen.tab_craft")) + 12;
        int room = bx - (x + titleW) - 6;
        String shown = fitFine(line, room);
        fine(g, shown, bx - 6 - fineWidth(shown), ty + 1, Theme.INK_3);
        tab.acts.add(new Act(bx - 6 - fineWidth(shown), ty - 2, fineWidth(shown), 12, null, null,
                List.of(Component.literal(line), L.c("wynnlabs.craft.xp_estimate"))));
    }

    private void header(String what) {
        switch (what) {
            case "copy" -> {
                List<String> names = new ArrayList<>();
                for (String s : tab.slots) {
                    names.add(s);
                }
                RecipeCode.Recipe here = new RecipeCode.Recipe(tab.type, tab.minLevel, tab.tier1, tab.tier2, tab.speed, names);
                // WynnBuilder's code, the one everyone shares; the mod's own when WynnBuilder lacks an id
                String code = WynnBuilderCode.encode(here);
                if (code == null) {
                    code = RecipeCode.encode(here);
                }
                Minecraft.getInstance().keyboardHandler.setClipboard(code);
                tab.flash.accept(L.t("wynnlabs.craft.code_copied"));
            }
            case "paste" -> {
                String text = Minecraft.getInstance().keyboardHandler.getClipboard();
                RecipeCode.Recipe r = WynnBuilderCode.looksLike(text) ? WynnBuilderCode.decode(text)
                        : RecipeCode.decode(text, tab.byName.keySet());
                if (r == null) {
                    tab.flash.accept(L.t("wynnlabs.craft.code_bad"));
                    return;
                }
                tab.type = r.type();
                tab.minLevel = r.minLevel();
                tab.tier1 = r.tier1();
                tab.tier2 = r.tier2();
                tab.speed = r.speed();
                for (int i = 0; i < Craft.SLOTS; i++) {
                    tab.slots[i] = r.slots().get(i);
                }
                tab.flash.accept(L.t("wynnlabs.craft.code_read"));
            }
            default -> {
                Arrays.fill(tab.slots, null);
                tab.tier1 = 1;
                tab.tier2 = 1;
                tab.speed = Craft.Speed.NORMAL;
                tab.results = List.of();
            }
        }
    }

    int viewTabs(GuiGraphics g, int x, int y, int w, int mx, int my) {
        String[][] tabs = {{"hammer", L.t("wynnlabs.craft.view_calc")}, {"star-filled", L.t("wynnlabs.craft.view_suggest")}};
        int tx = x;
        for (int i = 0; i < tabs.length; i++) {
            int bw = width(tabs[i][1]) + 24;
            boolean on = i == tab.view;
            int state = on ? Kit.ON : in(mx, my, tx, y, bw, 14) ? Kit.HOVER : Kit.REST;
            Kit.ledger(g, tx, y, bw, 14, on);
            Art.icon(g, tabs[i][0], tx + 6, y + 3, 8, on ? Theme.INK : Theme.CREAM);
            wood(g, tabs[i][1], tx + 17, y + 3, Kit.buttonText(state), !on);
            int v = i;
            tab.acts.add(new Act(tx, y, bw, 14, () -> {
                tab.view = v;
                tab.open = Drop.NONE;
                if (v == 1) {
                    if (tab.suggestMode == 0) {
                        tab.setsPanel.startSets(false);
                    } else {
                        tab.suggestionsPanel.startSuggestions(false);
                    }
                }
            }, null, null));
            tx += bw + 3;
        }
        g.fill(x, y + 14, x + w, y + 15, Theme.GOLD_EDGE);
        return 14;
    }

    int subTabs(GuiGraphics g, int x, int y, int w, int mx, int my) {
        String[] names = {L.t("wynnlabs.craft.sub_recipe"), L.t("wynnlabs.craft.sub_result"), L.t("wynnlabs.craft.sub_search")};
        int bw = (w - 8) / 3;
        for (int i = 0; i < 3; i++) {
            int bx = x + i * (bw + 4);
            boolean on = i == tab.subTab;
            int state = on ? Kit.ON : in(mx, my, bx, y, bw, 14) ? Kit.HOVER : Kit.REST;
            Kit.ledger(g, bx, y, bw, 14, on);
            wood(g, names[i], bx + (bw - width(names[i])) / 2, y + 3, Kit.buttonText(state), !on);
            int t = i;
            clipped(bx, y, bw, 14, () -> {
                tab.subTab = t;
                tab.scroll = 0;
            }, null, null);
        }
        return 14;
    }
}
