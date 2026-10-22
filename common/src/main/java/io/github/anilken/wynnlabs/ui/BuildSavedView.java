package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.build.BuildSources;
import io.github.anilken.wynnlabs.build.Plans;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.model.Klass;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The build window's SAVED view ("My builds"): a row per saved build with Apply, Edit, Rename
 * and Delete, the edit area under the list, "+ Save current plan" and the undo line. It owns
 * the list's scroll.
 */
final class BuildSavedView {

    private static final int SAVED_ROW_H = 30;

    private final BuildWindow win;
    private final BuildControls ui;

    int savedScroll;
    final ScrollBar savedBar = new ScrollBar();

    BuildSavedView(BuildWindow win, BuildControls ui) {
        this.win = win;
        this.ui = ui;
    }

    void drawSaved(GuiGraphics g, BuildWindow.Host host, int x, int y, int w, int h, int mx, int my, float pt) {
        List<Plans.Build> all = Plans.saved();
        Klass cls = GameState.current().classType();
        String title = L.t("wynnlabs.saved.title", cls == null ? "?" : BuildText.nice(cls.name()), all.size());
        Art.icon(g, "bookmark", x, y, 8, Theme.INK_2);
        Kit.text(g, ui.font, Component.literal(ui.fit(title, w - 30, true)).withStyle(UiFont::bold), x + 11, y, Theme.INK);
        int top = y + 14;
        int footY = y + h - 14;
        int editH = win.editId == null ? 0 : 54;
        int listH = footY - (win.editId == null ? 4 : 8) - editH - top;
        Plans.Build current = Plans.current();
        Map<String, String> worn = BuildSources.worn();
        Kit.outline(g, x, top, w, listH, Theme.LINE);
        int rows = Math.max(1, (listH - 2) / SAVED_ROW_H);
        int max = Math.max(0, all.size() - rows);
        savedScroll = Math.max(0, Math.min(savedScroll, max));
        int rw = w - 2 - (max > 0 ? ScrollBar.W + 2 : 0);
        boolean renaming = false;
        if (all.isEmpty()) {
            Kit.text(g, ui.font, ui.fit(L.t("wynnlabs.saved.none"), w - 12, false), x + 6, top + 6, Theme.INK_3);
        }
        for (int i = 0; i < rows && savedScroll + i < all.size(); i++) {
            Plans.Build b = all.get(savedScroll + i);
            int ry = top + 1 + i * SAVED_ROW_H;
            boolean mine = current != null && current.id().equals(b.id());
            Kit.row(g, x + 1, ry, rw, SAVED_ROW_H, i, BuildControls.in(mx, my, x + 1, ry, rw, SAVED_ROW_H) || b.id().equals(win.editId));
            if (mine) {
                Kit.outline(g, x + 1, ry, rw, SAVED_ROW_H, BuildControls.CHOSEN);
            }
            // the buttons on the right: Apply (not on your plan), Edit, Rename, Delete
            int bx = x + 1 + rw - 4;
            String[][] all4 = {{"trash", "delete", "wynnlabs.saved.delete_tip"}, {"pencil", "rename", "wynnlabs.saved.rename_tip"},
                {"edit", "edit", "wynnlabs.saved.edit_tip"}, {"check", "applySaved", "wynnlabs.saved.apply_tip"}};
            String[][] acts = mine ? java.util.Arrays.copyOf(all4, 3) : all4;
            for (String[] a : acts) {
                bx -= 14;
                boolean hov = BuildControls.in(mx, my, bx, ry + 3, 13, 12);
                Kit.button(g, bx, ry + 3, 13, 12, a[1].equals("applySaved") ? Kit.ON : hov ? Kit.HOVER : Kit.REST);
                Art.icon(g, a[0], bx + 2, ry + 5, 8, Kit.buttonText(a[1].equals("applySaved") ? Kit.ON : Kit.REST));
                ui.btn(bx, ry + 3, 13, 12, a[1], b.id());
                host.tip(bx, ry + 3, 13, 12, List.of(L.c(a[2]).withStyle(ChatFormatting.GRAY)));
                bx -= 1;
            }
            int nx = x + 6;
            int nameRoom = bx - 4 - nx;
            if (b.id().equals(win.renameId)) {
                renaming = true;
                int fw = Math.max(40, nameRoom - 30);
                Kit.field(g, nx - 2, ry + 2, fw, 12, true);
                win.rename.setX(nx + 2);
                win.rename.setY(ry + 4);
                win.rename.setWidth(fw - 8);
                win.rename.render(g, mx, my, pt);
                win.rename.setFocused(true);
                int okX = nx - 2 + fw + 2;
                Kit.button(g, okX, ry + 2, 12, 12, Kit.ON);
                Art.icon(g, "check", okX + 2, ry + 4, 8, Kit.buttonText(Kit.ON));
                ui.btn(okX, ry + 2, 12, 12, "renameOk", b.id());
                Kit.button(g, okX + 14, ry + 2, 12, 12, Kit.REST);
                Art.icon(g, "x", okX + 16, ry + 4, 8, Kit.buttonText(Kit.REST));
                ui.btn(okX + 14, ry + 2, 12, 12, "renameCancel", b.id());
            } else {
                String suffix = mine ? " · " + L.t("wynnlabs.saved.your_plan") : "";
                int sw = ui.font.width(suffix);
                Component nm = Component.literal(ui.fit(b.name(), nameRoom - sw, true)).withStyle(UiFont::bold);
                Kit.text(g, ui.font, nm, nx, ry + 4, Theme.INK);
                if (mine) {
                    Kit.text(g, ui.font, suffix, nx + ui.font.width(nm), ry + 4, BuildControls.CHOSEN);
                }
            }
            // the squares, the score and how much of it you wear
            boolean[] on = Plans.wornSlots(b.items(), worn);
            int wornN = 0;
            for (int k = 0; k < Plans.SLOTS.length; k++) {
                wornN += on[k] ? 1 : 0;
            }
            savedSquares(g, nx, ry + 18, b, on);
            String info = String.format(Locale.ROOT, "%,d", Math.round(b.score())) + " · " + wornN + "/" + b.items().size();
            Kit.text(g, ui.font, ui.fit(info, bx - 4 - (nx + 70), false), nx + 70, ry + 17, Theme.INK_3);
            host.tip(x + 1, ry, Math.max(1, bx - 2 - x), SAVED_ROW_H, savedTip(b));
        }
        if (!renaming) {
            win.rename.setFocused(false);
        }
        savedBar.place(x + w - ScrollBar.W - 1, top + 1, listH - 2, rows, all.size(), savedScroll);
        savedBar.draw(g, mx, my);
        if (savedBar.shown()) {
            int[] t = savedBar.track();
            ui.btn(t[0], t[1], t[2], t[3], "scrollbar", null);
        }
        if (win.editId != null) {
            drawEdit(g, host, x, top + listH + 4, w, editH, mx, my);
        }
        // the footer: "+ Save current plan", and the undo line after a delete
        boolean can = current != null;
        List<Component> addTip = List.of(L.c(can ? "wynnlabs.saved.add_tip" : "wynnlabs.saved.add_none").withStyle(ChatFormatting.GRAY));
        int aw = ui.control(g, host, x, footY, 14, "plus", L.t("wynnlabs.saved.add"), can ? BuildControls.PRIMARY : BuildControls.WOOD, false, w / 2,
                can ? "addCurrent" : null, null, addTip, mx, my);
        long now = System.currentTimeMillis();
        Plans.Build gone = Plans.undoable(now);
        if (gone != null) {
            String del = L.t("wynnlabs.saved.deleted") + " · ";
            String undo = L.t("wynnlabs.saved.undo");
            int uw = ui.font.width(del) + ui.font.width(undo);
            int ux = x + w - uw;
            if (ux > x + aw + 6) {
                Kit.text(g, ui.font, del, ux, footY + 3, Theme.INK_3);
                Kit.text(g, ui.font, undo, ux + ui.font.width(del), footY + 3, Theme.LINK);
                ui.btn(ux + ui.font.width(del), footY + 1, ui.font.width(undo), 12, "undo", null);
            }
        }
    }

    private void savedSquares(GuiGraphics g, int x, int y, Plans.Build b, boolean[] worn) {
        for (int i = 0; i < Plans.SLOTS.length; i++) {
            String name = b.items().get(Plans.SLOTS[i]);
            int c = name != null && b.locked(name) ? BuildControls.CHOSEN : worn[i] ? Theme.GREEN : Theme.WELL;
            int sx = x + i * 7;
            g.fill(sx, y, sx + 6, y + 6, Theme.INK_2);
            g.fill(sx + 1, y + 1, sx + 5, y + 5, c);
        }
    }

    private static List<Component> savedTip(Plans.Build b) {
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(b.name()).withStyle(ChatFormatting.LIGHT_PURPLE).withStyle(UiFont::bold));
        for (String slot : Plans.SLOTS) {
            String n = b.items().get(slot);
            if (n != null) {
                tip.add(Component.literal((b.locked(n) ? "◆ " : "  ") + n).withStyle(b.locked(n) ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GRAY));
            }
        }
        tip.add(L.c("wynnlabs.saved.row_tip", BuildText.summary(b.tuning())).withStyle(ChatFormatting.DARK_GRAY));
        return tip;
    }

    /** The edit area under the list: the build's chosen items as chips with "+ item", its strip, and its three buttons. */
    private void drawEdit(GuiGraphics g, BuildWindow.Host host, int x, int y, int w, int h, int mx, int my) {
        Plans.Build b = Plans.byId(win.editId);
        if (b == null) {
            win.editId = null;
            return;
        }
        Kit.card(g, x, y, w, h, BuildControls.CHOSEN);
        int cx = x + 5;
        int cy = y + 5;
        String head = L.t("wynnlabs.saved.editing", b.name());
        Kit.text(g, ui.font, Component.literal(ui.fit(head, w - 10, true)).withStyle(UiFont::bold), cx, cy, Theme.INK);
        cy += 12;
        int plusW = ui.controlW("plus", L.t("wynnlabs.saved.add_item"), 70);
        for (String n : win.editLocks) {
            int cw = Math.min(ui.font.width(n) + 18, 110);
            if (cx + cw + 3 + plusW > x + w - 5) {
                break;
            }
            BuildControls.chip(g, cx, cy, cw, 14, BuildControls.in(mx, my, cx, cy, cw, 14));
            Kit.text(g, ui.font, ui.fit(n, cw - 18, false), cx + 5, cy + 3, Theme.INK);
            Art.icon(g, "x", cx + cw - 11, cy + 3, 8, BuildControls.CHOSEN);
            ui.btn(cx, cy, cw, 14, "editUnlock", n);
            host.tip(cx, cy, cw, 14, List.of(Component.literal(n).withStyle(ChatFormatting.LIGHT_PURPLE),
                    L.c("wynnlabs.pick.chip_tip").withStyle(ChatFormatting.DARK_GRAY)));
            cx += cw + 3;
        }
        ui.control(g, host, cx, cy, 14, "plus", L.t("wynnlabs.saved.add_item"), BuildControls.WOOD, false, 70, "editAdd", null,
                List.of(L.c("wynnlabs.saved.add_item_tip").withStyle(ChatFormatting.GRAY)), mx, my);
        cy += 17;
        // the buttons on the right of the strip's summary: Recompute (purple), Save as new (a disk), Cancel (✕)
        int bx = x + w - 5;
        int rw = ui.controlW("refresh", L.t("wynnlabs.saved.recompute"), w / 2);
        bx -= rw;
        ui.control(g, host, bx, cy, 14, "refresh", L.t("wynnlabs.saved.recompute"), BuildControls.PRIMARY, false, w / 2, "editRecompute", null,
                List.of(L.c("wynnlabs.saved.recompute_tip").withStyle(ChatFormatting.GRAY)), mx, my);
        bx -= 3 + BuildControls.ICON_W;
        ui.control(g, host, bx, cy, 14, "device-floppy", L.t("wynnlabs.saved.save_new"), BuildControls.WOOD, false, 0, "editSaveNew", null,
                List.of(L.c("wynnlabs.saved.save_new_tip").withStyle(ChatFormatting.GRAY)), mx, my);
        bx -= 3 + BuildControls.ICON_W;
        ui.control(g, host, bx, cy, 14, "x", L.t("wynnlabs.pick.cancel"), BuildControls.WOOD, false, 0, "editCancel", null,
                List.of(L.c("wynnlabs.saved.edit_cancel_tip").withStyle(ChatFormatting.GRAY)), mx, my);
        bx -= 3;
        cy += 1;
        Kit.text(g, ui.font, ui.fit(BuildText.summary(win.editTuning), bx - 4 - (x + 5), false), x + 5, cy + 2, Theme.INK_3);
    }
}
