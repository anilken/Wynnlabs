package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.CraftingTab.Act;
import io.github.anilken.wynnlabs.crafting.Craft;
import io.github.anilken.wynnlabs.crafting.CraftChecks;
import io.github.anilken.wynnlabs.crafting.CraftData;
import io.github.anilken.wynnlabs.crafting.CraftSearch;
import io.github.anilken.wynnlabs.crafting.Ingredient;
import io.github.anilken.wynnlabs.crafting.SavedRecipes;
import io.github.anilken.wynnlabs.crafting.StockView;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.github.anilken.wynnlabs.CraftFormat.range;
import static io.github.anilken.wynnlabs.CraftFormat.skill;
import static io.github.anilken.wynnlabs.CraftFormat.typeName;

/** The crafting tab's saved recipes: beside the menu, or a drawer when the screen is too narrow. */
final class CraftSavedPanel extends CraftPanel {

    private static final int SIDE_W = 120;
    private String deleteArmed;
    private long deleteArmedAt;
    String renaming;

    CraftSavedPanel(CraftingTab tab) {
        super(tab);
    }

    void drawSaved(GuiGraphics g, int[] frame, int screenW, int px, int py, int pw, int ph, boolean two,
                           int mx, int my) {
        int fx = frame[0];
        int fy = frame[1];
        int fw = frame[2];
        int fh = frame[3];
        int sideX = fx + fw + 5 + 4;
        boolean room = sideX + SIDE_W + 4 <= screenW;
        if (room) {
            Kit.window(g, sideX - 5, fy - 5, SIDE_W + 10, fh + 10);
            Kit.paper(g, sideX, fy, SIDE_W, fh);
            savedList(g, sideX + 8, fy + 10, SIDE_W - 16, fh - 20, mx, my, false);
            tab.drawerOpen = false;
            return;
        }
        // the ribbon on the menu's right edge
        // (over the frame's edge when the window ends there)
        int rx = Math.min(fx + fw + 1, screenW - 9);
        int ry = fy + 24;
        LayoutCheck.frame(g.pose(), rx, ry, 9, 40);
        if (DevHooks.ON) {
            DevHooks.frame(g.pose(), rx, ry, 9, 40);         // the dev screenshot tool's crop keeps the ribbon
        }
        boolean rh = in(mx, my, rx, ry, 9, 40);
        Kit.button(g, rx, ry, 9, 40, tab.drawerOpen ? Kit.ON : rh ? Kit.HOVER : Kit.REST);
        Art.icon(g, "bookmark", rx + 1, ry + 16, 7, tab.drawerOpen ? Theme.INK : Theme.CREAM);
        tab.acts.add(new Act(rx, ry, 9, 40, () -> tab.drawerOpen = !tab.drawerOpen, null, List.of(L.c("wynnlabs.craft.saved"))));
        if (tab.drawerOpen) {
            int dw = two ? Math.min(150, pw / 2) : Math.min(170, pw);
            int dx = px + pw - dw;
            g.nextStratum();
            Kit.card(g, dx, py, dw, ph, Theme.GOLD_DEEP);
            tab.overlayBox = new int[] {dx, py, dw, ph};
            savedList(g, dx + 6, py + 6, dw - 12, ph - 12, mx, my, true);
        }
    }

    private void savedList(GuiGraphics g, int x, int y, int w, int h, int mx, int my, boolean inDrawer) {
        List<Act> into = inDrawer ? tab.overlay : tab.acts;
        SavedRecipes saved = Crafting.saved();
        text(g, Component.literal(L.t("wynnlabs.craft.saved")).withStyle(UiFont::bold), x, y, Theme.INK);
        int yy = y + 13;
        String save = L.t(renaming != null ? "wynnlabs.craft.rename" : "wynnlabs.craft.save");
        int bw = width(save) + 10;
        int fw = w - bw - 3;
        Kit.field(g, x, yy, fw, 12, tab.saveName.isFocused());
        tab.saveName.setX(x + 3);
        tab.saveName.setY(yy + 2);
        tab.saveName.setWidth(fw - 6);
        tab.saveName.render(g, mx, my, 0);
        if (tab.saveName.getValue().isEmpty() && !tab.saveName.isFocused()) {
            text(g, fit(L.t("wynnlabs.craft.saved_name"), fw - 6), x + 3, yy + 2, Theme.INK_3);
        }
        into.add(new Act(x, yy, fw, 12, () -> {
            tab.screen.setFocused(tab.saveName);
            tab.saveName.setFocused(true);
        }, null, null));
        int sbx = x + fw + 3;
        boolean sh = in(mx, my, sbx, yy - 1, bw, 14);
        Kit.button(g, sbx, yy - 1, bw, 14, sh ? Kit.HOVER : Kit.ON);
        wood(g, save, sbx + 5, yy + 2, sh ? Theme.CREAM : Theme.INK, sh);
        into.add(new Act(sbx, yy - 1, bw, 14, this::saveCurrent, null, null));
        yy += 18;
        if (saved.all().isEmpty()) {
            for (FormattedCharSequence line : tab.font.split(sized(L.c("wynnlabs.craft.saved_empty"), FINE), w)) {
                g.drawString(tab.font, Kit.ink(line), x, yy, Theme.INK_3, false);
                yy += 10;
            }
        }
        int rowH = 32;
        int bottom = y + h - 22;
        for (int i = 0; i < saved.all().size() && yy + rowH <= bottom; i++) {
            SavedRecipes.Saved s = saved.all().get(i);
            boolean hover = in(mx, my, x - 2, yy, w + 4, rowH - 2);
            Kit.row(g, x - 2, yy, w + 4, rowH - 2, i, hover);
            text(g, Component.literal(fit(s.name(), w - (hover ? 34 : 2))).withStyle(UiFont::bold), x, yy + 2, Theme.INK);
            CraftData.Recipe r = s.recipe();
            List<Ingredient> lay = new ArrayList<>();
            for (String n : s.slots()) {
                lay.add(tab.ing(n));
            }
            String sub = typeName(s.type()) + " " + (r == null ? "?" : r.bracket());
            String wear = "";
            boolean wearOk = true;
            String mats = "";
            boolean matsOk = true;
            if (r != null) {
                Craft.Result c = Craft.craft(r, s.tier1(), s.tier2(), s.attackSpeed(), lay);
                String main = s.goal() == null || s.goal().weights() == null || s.goal().weights().isEmpty() ? null
                        : s.goal().weights().keySet().iterator().next();
                Craft.Range v = main == null ? null : c.ids().get(main);
                if (v != null) {
                    sub += " · " + range(v, tab.unit(main));
                }
                List<CraftChecks.Gap> gaps = r.consumable() ? List.of() : CraftChecks.gaps(c.requirements(), tab.points);
                wearOk = gaps.isEmpty();
                wear = r.consumable() ? L.t("wynnlabs.craft.can_use") : wearOk ? L.t("wynnlabs.craft.can_wear")
                        : L.t("wynnlabs.craft.cannot_wear", skill(gaps.get(0).skill()), gaps.get(0).missing());
                matsOk = StockView.missing(r, s.tier1(), s.tier2(), tab.stock).isEmpty();
                mats = " · " + (matsOk ? L.t("wynnlabs.craft.mats_ok") : L.t("wynnlabs.craft.mats_short"));
            }
            text(g, fit(sub, w), x, yy + 11, Theme.INK_2);
            String wearShown = fit(wear, w);
            text(g, wearShown, x, yy + 20, wearOk ? Theme.GOOD : Theme.BAD);
            int mx3 = x + width(wearShown);
            text(g, fit(mats, x + w - mx3), mx3, yy + 20, matsOk ? Theme.GOOD : Theme.BAD);
            String name = s.name();
            int ry = yy;
            // the row opens the recipe; its buttons, added after, are found first on a click
            into.add(new Act(x - 2, yy, w + 4, rowH - 2, () -> openSaved(s), null, null));
            if (hover) {
                // copy, rename, delete on the hovered row
                int ix = x + w - 30;
                boolean armed = name.equals(deleteArmed) && System.currentTimeMillis() - deleteArmedAt < 3000;
                Art.icon(g, "copy", ix, ry + 2, 8, Theme.paperRgb(Theme.INK_2));
                Art.icon(g, "pencil", ix + 11, ry + 2, 8, Theme.paperRgb(Theme.INK_2));
                Art.icon(g, "trash", ix + 22, ry + 2, 8, Theme.paperRgb(armed ? Theme.BAD : Theme.INK_2));
                into.add(new Act(ix, ry + 1, 10, 10, () -> {
                    SavedRecipes.Saved c = saved.copy(name);
                    Crafting.persist();
                    if (c != null) {
                        tab.flash.accept(L.t("wynnlabs.craft.copied", c.name()));
                    }
                }, null, List.of(L.c("wynnlabs.craft.copy_tip"))));
                into.add(new Act(ix + 11, ry + 1, 10, 10, () -> {
                    if (name.equals(renaming)) {
                        stopRenaming();                 // the pencil again: back to saving
                        return;
                    }
                    renaming = name;
                    tab.saveName.setValue(name);
                    tab.screen.setFocused(tab.saveName);
                    tab.saveName.setFocused(true);
                }, null, List.of(L.c("wynnlabs.craft.rename_tip"))));
                into.add(new Act(ix + 22, ry + 1, 10, 10, () -> deleteClick(name), null,
                        List.of(L.c(armed ? "wynnlabs.craft.delete_again" : "wynnlabs.craft.delete_tip"))));
            }
            yy += rowH;
        }
        for (FormattedCharSequence line : tab.font.split(sized(L.c("wynnlabs.craft.saved_hint"), FINE), w)) {
            if (bottom + 10 > y + h) {
                break;
            }
            g.drawString(tab.font, Kit.ink(line), x, bottom, Theme.INK_3, false);
            bottom += 10;
        }
    }

    private void deleteClick(String name) {
        long now = System.currentTimeMillis();
        if (name.equals(deleteArmed) && now - deleteArmedAt < 3000) {
            Crafting.saved().delete(name);
            Crafting.persist();
            deleteArmed = null;
            tab.flash.accept(L.t("wynnlabs.craft.deleted", name));
        } else {
            deleteArmed = name;
            deleteArmedAt = now;
        }
    }

    /** Back from renaming a saved recipe to saving a new one: the button reads "Kaydet" again. */
    void stopRenaming() {
        renaming = null;
        tab.saveName.setValue("");
        tab.saveName.setFocused(false);
    }

    void saveCurrent() {
        String name = tab.saveName.getValue().strip();
        if (name.isEmpty()) {
            tab.screen.setFocused(tab.saveName);
            tab.saveName.setFocused(true);
            tab.flash.accept(L.t("wynnlabs.craft.name_first"));
            return;
        }
        SavedRecipes saved = Crafting.saved();
        if (renaming != null) {
            if (name.equals(renaming)) {
                stopRenaming();                         // the same name: nothing to change
                return;
            }
            if (saved.rename(renaming, name)) {
                tab.flash.accept(L.t("wynnlabs.craft.renamed", name));
                renaming = null;
                tab.saveName.setValue("");
                Crafting.persist();
            } else {
                tab.flash.accept(L.t("wynnlabs.craft.name_taken", name));
            }
            return;
        }
        SavedRecipes.Saved s = new SavedRecipes.Saved(name, tab.type, tab.minLevel, tab.tier1, tab.tier2, tab.speed.name(),
                java.util.Collections.unmodifiableList(Arrays.asList(tab.slots.clone())), SavedRecipes.SavedGoal.of(tab.goal()),
                System.currentTimeMillis());
        boolean replace = name.equals(deleteArmed);      // a second click on Save with the same taken name replaces it
        if (!saved.save(s, replace)) {
            deleteArmed = name;
            deleteArmedAt = System.currentTimeMillis();
            tab.flash.accept(L.t("wynnlabs.craft.name_taken_replace", name));
            return;
        }
        deleteArmed = null;
        Crafting.persist();
        tab.saveName.setValue("");
        tab.flash.accept(L.t("wynnlabs.craft.saved_one", name));
    }

    private void openSaved(SavedRecipes.Saved s) {
        if (s.recipe() == null) {
            return;
        }
        tab.type = s.type();
        tab.minLevel = s.minLevel();
        tab.tier1 = s.tier1();
        tab.tier2 = s.tier2();
        tab.speed = s.attackSpeed();
        for (int i = 0; i < Craft.SLOTS; i++) {
            tab.slots[i] = i < s.slots().size() ? s.slots().get(i) : null;
        }
        if (s.goal() != null) {
            CraftSearch.Goal goal = s.goal().goal();
            tab.weights.clear();
            tab.weights.putAll(goal.weights());
            tab.noNegative.clear();
            tab.noNegative.addAll(goal.noNegative());
            tab.floorIndex = 0;
            for (int i = 0; i < CraftingTab.FLOORS.length; i++) {
                if (CraftingTab.FLOORS[i] == goal.durabilityFloor()) {
                    tab.floorIndex = i;
                }
            }
        }
        tab.drawerOpen = false;
        tab.flash.accept(L.t("wynnlabs.craft.opened", s.name()));
    }
}
