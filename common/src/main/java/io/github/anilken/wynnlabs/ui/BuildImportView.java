package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.build.LinkImport;
import io.github.anilken.wynnlabs.build.Locks;
import io.github.anilken.wynnlabs.build.WynnBuilderLink;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.SkillKind;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The PICK view's "Import from WynnBuilder": the link on the clipboard read into its pieces, shown
 * under the picker's title in place of the rest (each piece's slot, name and powders, or why it is
 * left out; the tomes, the skill points set by hand and whether the kept ones fit together;
 * powders, tomes and points stay information only), then "Build around these"
 * makes the kept ones the items to build around. Nothing here acts in the game.
 */
final class BuildImportView {

    private static final int ROW_H = 12;
    private static final String[] SLOTS = {"wynnlabs.road.slot_helmet", "wynnlabs.road.slot_chestplate", "wynnlabs.road.slot_leggings",
        "wynnlabs.road.slot_boots", "wynnlabs.road.slot_ring", "wynnlabs.road.slot_ring", "wynnlabs.road.slot_bracelet",
        "wynnlabs.road.slot_necklace", "wynnlabs.road.slot_weapon"};

    /** Why a piece is left out, by LinkImport.Why (KEPT shows the powders instead). */
    private static final String[] WHY = {null, "wynnlabs.import.why_crafted", "wynnlabs.import.why_custom", "wynnlabs.import.why_unknown",
        "wynnlabs.import.why_wrong_slot", "wynnlabs.import.why_class"};

    private final BuildControls ui;
    /** The link on show, or null when the list shows. */
    LinkImport.Result shown;

    BuildImportView(BuildControls ui) {
        this.ui = ui;
    }

    /** Reads the clipboard: true when it held a build link (now on show), else a flash says why not. */
    boolean read(BuildWindow.Host host) {
        String text;
        try {
            text = Minecraft.getInstance().keyboardHandler.getClipboard();
        } catch (Throwable t) {
            text = null;
        }
        WynnBuilderLink.Build b = WynnBuilderLink.decode(text);
        if (b == null) {
            if (host != null) {
                host.flash(L.t("wynnlabs.import.bad"));
            }
            return false;
        }
        shown = LinkImport.of(GameState.current(), b);
        return true;
    }

    /** The items to build around from the link on show; it closes. */
    List<ItemData> take() {
        List<ItemData> out = shown == null ? List.of() : shown.kept();
        shown = null;
        return out;
    }

    void draw(GuiGraphics g, BuildWindow.Host host, int x, int y, int w, int h, int mx, int my) {
        LinkImport.Result r = shown;
        Kit.outline(g, x, y, w, h, Theme.LINE);
        if (r == null) {
            return;
        }
        WynnBuilderLink.Build b = r.build();
        int cy = y + 4;
        String title = L.t("wynnlabs.import.title", b.level()) + " · " + b.version();
        Kit.text(g, ui.font, Component.literal(ui.fit(title, w - 12, true)).withStyle(UiFont::bold), x + 6, cy, BuildControls.CHOSEN);
        cy += 12;
        if (b.newer()) {
            Kit.text(g, ui.font, ui.fit(L.t("wynnlabs.import.newer"), w - 12, false), x + 6, cy, Theme.BAD);
            cy += 11;
        }
        int bottom = y + h - 18;                       // the buttons' row
        Locks.Problem problem = r.kept().isEmpty() ? null : Locks.problem(r.kept(), BuildWindow.planLevel(r.kept()));
        // the pieces first; the lines under them as many as fit (the problem kept longest)
        int rowsEnd = Math.min(bottom - 2, cy + Math.max(1, r.rows().size()) * ROW_H);
        List<String> info = info(r, problem, Math.max(0, (bottom - 2 - rowsEnd - 3) / 11));
        int slotW = 0;
        for (String k : SLOTS) {
            slotW = Math.max(slotW, ui.font.width(L.t(k)));
        }
        int i = 0;
        for (LinkImport.Row row : r.rows()) {
            if (cy + ROW_H > rowsEnd) {
                break;
            }
            drawRow(g, host, row, x + 1, cy, w - 2, slotW, i++, mx, my);
            cy += ROW_H;
        }
        if (r.rows().isEmpty()) {
            Kit.text(g, ui.font, ui.fit(L.t("wynnlabs.import.empty"), w - 12, false), x + 6, cy + 2, Theme.INK_3);
        }
        int iy = rowsEnd + 3;
        for (String line : info) {
            boolean bad = problem != null && line.equals(problemLine(problem));
            Kit.text(g, ui.font, ui.fit(line, w - 12, false), x + 6, iy, bad ? Theme.BAD : Theme.INK_3);
            iy += 11;
        }
        // the buttons, right: Build around these (purple) and Cancel
        String cancel = L.t("wynnlabs.import.cancel");
        String ok = L.t("wynnlabs.import.confirm");
        int cw = ui.controlW("x", cancel, w / 3);
        int ow = ui.controlW("target", ok, w / 2);
        int bx = x + w - 4 - cw;
        ui.control(g, host, bx, bottom, 14, "x", cancel, BuildControls.WOOD, false, w / 3, "wbCancel", null, null, mx, my);
        boolean can = !r.kept().isEmpty();
        List<Component> tip = List.of(L.c(can ? "wynnlabs.import.confirm_tip" : "wynnlabs.import.none").withStyle(ChatFormatting.GRAY),
                L.c("wynnlabs.import.note").withStyle(ChatFormatting.DARK_GRAY));
        ui.control(g, host, bx - 4 - ow, bottom, 14, "target", ok, can ? BuildControls.PRIMARY : BuildControls.WOOD, false, w / 2,
                can ? "wbConfirm" : null, null, tip, mx, my);
    }

    /** One piece: its slot, the item's name in its tier's colour (else what it is), and why it is left out or its powders. */
    private void drawRow(GuiGraphics g, BuildWindow.Host host, LinkImport.Row row, int x, int y, int w, int slotW, int i, int mx, int my) {
        WynnBuilderLink.Piece p = row.piece();
        boolean hov = BuildControls.in(mx, my, x, y, w, ROW_H);
        Kit.row(g, x, y, w, ROW_H, i, hov);
        boolean kept = row.why() == LinkImport.Why.KEPT;
        if (kept) {
            g.fill(x, y, x + 2, y + ROW_H, BuildControls.CHOSEN);
        }
        Kit.text(g, ui.font, L.t(SLOTS[Math.min(p.slot(), SLOTS.length - 1)]), x + 5, y + 2, Theme.INK_3);
        String right = kept ? p.powders() : L.t(WHY[row.why().ordinal()]);
        int rw = ui.font.width(right);
        int nx = x + 5 + slotW + 6;
        int room = x + w - 5 - rw - 6 - nx;
        String name = p.kind() == WynnBuilderLink.Kind.ITEM ? (p.name() == null ? "#" + p.id() : p.name())
                : L.t(p.kind() == WynnBuilderLink.Kind.CRAFTED ? "wynnlabs.import.crafted" : "wynnlabs.import.custom");
        Component nc = row.info() != null ? Component.literal(ui.fit(row.info().name(), room, false)).withStyle(row.info().tier().colour())
                : Component.literal(ui.fit(name, room, false));
        Kit.text(g, ui.font, nc, nx, y + 2, row.info() != null ? Theme.INK : Theme.INK_3);
        Kit.text(g, ui.font, right, x + w - 5 - rw, y + 2, kept ? Theme.INK_3 : Theme.BAD);
        if (row.info() != null) {
            ItemStack icon = host.icon(row.info());
            host.item(x, y, w, ROW_H, row.info(), icon, List.of());
        }
    }

    /**
     * The lines under the pieces, at most {@code room}: the tomes, the skill points set by hand, and
     * whether the kept ones fit together (the last to go when the room is short).
     */
    private static List<String> info(LinkImport.Result r, Locks.Problem problem, int room) {
        List<String> out = new ArrayList<>();
        WynnBuilderLink.Build b = r.build();
        if (!b.tomes().isEmpty()) {
            out.add(L.t("wynnlabs.import.tomes", String.join(", ", b.tomes())));
        }
        if (b.skills() != null) {
            List<String> parts = new ArrayList<>();
            for (SkillKind k : SkillKind.values()) {
                Integer v = k.ordinal() < b.skills().size() ? b.skills().get(k.ordinal()) : null;
                if (v != null) {
                    parts.add(v + " " + k.displayName().substring(0, 3));
                }
            }
            if (!parts.isEmpty()) {
                out.add(L.t("wynnlabs.import.skills", String.join(" · ", parts)));
            }
        }
        while (out.size() > Math.max(0, room - (problem != null ? 1 : 0))) {
            out.remove(out.size() - 1);
        }
        if (problem != null && room > 0) {
            out.add(problemLine(problem));
        }
        return out;
    }

    private static String problemLine(Locks.Problem p) {
        return L.t("wynnlabs.pick.problem", p.text());
    }
}
