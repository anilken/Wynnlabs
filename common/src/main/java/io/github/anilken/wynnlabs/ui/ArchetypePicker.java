package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.build.TreePlanner;
import io.github.anilken.wynnlabs.core.L;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The archetype list under an archetype button (the home's HUD, the tree's chip, the setting):
 * the class's archetypes, each with a line of what it plays like, and Automatic (no tree plan, the
 * build follows the one you lean to). The one you have the most nodes in is marked Suggested.
 * A row sets the same choice the button used to cycle through ({@link TreePlanner#choose}).
 */
final class ArchetypePicker extends MenuPaint {

    private boolean open;
    private int[] anchor;
    private int[] box;
    private final List<Object[]> rows = new ArrayList<>();     // {int[] rect, archetype ("" automatic)}

    ArchetypePicker(RoadmapScreen menu) {
        super(menu);
    }

    /** Opens under {@code anchor} (x, y, w, h: the button clicked). */
    void open(int[] anchor) {
        this.anchor = anchor;
        open = true;
    }

    /** Opens under this frame's click area with that action (the button just clicked). */
    void openAt(String action) {
        int[] at = {menu.frameMx, menu.frameMy, 1, 1};
        for (MenuHit h : menu.hits) {
            if (action.equals(h.copy())) {
                at = new int[] {h.x(), h.y(), h.w(), h.h()};
            }
        }
        open(at);
    }

    void close() {
        open = false;
        box = null;
    }

    /** The language key of an archetype's one-line description: "Battle Monk" → wynnlabs.arch.desc.battlemonk. */
    static String descKey(String archetype) {
        return "wynnlabs.arch.desc." + archetype.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    /**
     * The archetype to mark: the one you have the most nodes in ({@link TreePlanner#mostUnlocked}),
     * "" (Automatic) while you have none of any, null when the tree is unknown.
     */
    static String suggested(List<String> archetypes, String most, int mostNodes) {
        if (archetypes.isEmpty()) {
            return null;
        }
        return !most.isEmpty() && mostNodes > 0 ? most : "";
    }

    /** One row per archetype and Automatic last, over everything; the chosen one checked, the hovered one lit. */
    void draw(GuiGraphics g, int mx, int my) {
        if (!open || anchor == null) {
            return;
        }
        rows.clear();
        MenuLayout lay = MenuLayout.of(menu.width, menu.height);
        int left = lay.x() + lay.railW() + 6;
        int right = lay.x() + lay.w() - 6;
        List<String> archs = TreePlanner.archetypes();
        String most = TreePlanner.mostUnlocked();
        int mostNodes = most.isEmpty() ? 0 : TreePlanner.unlockedIn(most);
        String sugg = suggested(archs, most, mostNodes);
        String chosen = TreePlanner.chosen();
        List<String> names = new ArrayList<>(archs);
        names.add("");
        String tagText = L.t("wynnlabs.arch.suggested");
        String title = L.t("wynnlabs.arch.pick_title");
        String foot = sugg == null ? L.t("wynnlabs.arch.no_tree")
                : sugg.isEmpty() ? L.t("wynnlabs.arch.why_auto") : L.t("wynnlabs.arch.why_nodes", sugg, mostNodes);

        // as wide as the longest line wants, within 170..250 and the page
        int want = font().width(title);
        for (String a : names) {
            want = Math.max(want, font().width(label(a)) + Kit.Ui.tagW(font(), tagText) + 24);
            want = Math.max(want, (int) (font().width(desc(a)) * DESC_SCALE) + 4);
        }
        int w = Math.min(Math.min(250, right - left), Math.max(170, want + 16));
        int textW = w - 16;
        List<Integer> heights = new ArrayList<>();
        int h = 18;
        for (String a : names) {
            int lines = desc(a).isEmpty() ? 0 : wrapText(desc(a), (int) (textW / DESC_SCALE), 2).size();
            int rh = 13 + lines * 9 + 2;
            heights.add(rh);
            h += rh;
        }
        int footLines = wrapText(foot, (int) (textW / DESC_SCALE), 2).size();
        h += 4 + footLines * 9 + 4;

        // under the button, its left edge on the button's; above it when the page ends first
        int x = Math.max(left, Math.min(anchor[0], right - w));
        int y = anchor[1] + anchor[3] + 2;
        if (y + h > lay.y() + lay.h() - 4) {
            y = Math.max(lay.y() + 4, anchor[1] - h - 2);
        }
        box = new int[] {x, y, w, h};
        int[] b = box;
        // what is under the list shows no tooltip
        menu.hits.removeIf(hit -> hit.x() < b[0] + b[2] && hit.x() + hit.w() > b[0] && hit.y() < b[1] + b[3] && hit.y() + hit.h() > b[1]);

        g.nextStratum();                            // over the menu's own text and items
        Kit.card(g, x, y, w, h, Theme.ACCENT);
        ink(g, Component.literal(title).withStyle(UiFont::bold), x + 7, y + 6, Theme.INK);
        int ry = y + 17;
        for (int i = 0; i < names.size(); i++) {
            String a = names.get(i);
            int rh = heights.get(i);
            if (a.isEmpty() && i > 0) {
                g.fill(x + 6, ry - 1, x + w - 6, ry, Theme.LINE);     // Automatic set apart from the archetypes
            }
            boolean on = a.equalsIgnoreCase(chosen);
            boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + rh;
            Kit.row(g, x + 2, ry, w - 4, rh - 1, i, hover || on);
            int nx = x + 7;
            if (on) {
                Art.icon(g, "check", nx, ry + 3, 8, Theme.paperRgb(Theme.INK));
                nx += 10;
            }
            ink(g, Component.literal(label(a)).withStyle(UiFont::bold), nx, ry + 3, on ? Theme.INK : Theme.INK_2);
            if (a.equals(sugg)) {
                int tw = Kit.Ui.tagW(font(), tagText);
                Art.pill(g, x + w - 7 - tw, ry + 2, tw, 10, Theme.ACCENT, 0);
                Kit.text(g, font(), tagText, x + w - 7 - tw + Kit.Ui.TAG_PAD, ry + 3, Theme.CARD);
            }
            if (!desc(a).isEmpty()) {
                descWrap(g, desc(a), x + 7, ry + 13, textW, 2);
            }
            rows.add(new Object[] {new int[] {x + 2, ry, w - 4, rh}, a});
            ry += rh;
        }
        descWrap(g, foot, x + 7, ry + 4, textW, 2);
    }

    /** A click while the list is open: it closes, and a row sets that archetype. True when the click is used up. */
    boolean clicked(double ex, double ey) {
        if (!open) {
            return false;
        }
        int[] was = box;
        close();
        for (Object[] r : rows) {
            if (in(ex, ey, (int[]) r[0])) {
                String a = (String) r[1];
                if (!a.equalsIgnoreCase(TreePlanner.chosen())) {
                    TreePlanner.choose(a);
                    menu.flash(L.t("wynnlabs.home.archetype_set", label(a)));
                    menu.rebuildMap();              // the build follows the archetype: rebuilt in the background
                }
                return true;
            }
        }
        // a click on the list or its button again only closes it; elsewhere it goes on
        return was != null && in(ex, ey, was) || anchor != null && in(ex, ey, anchor);
    }

    /** Esc closes the list, not the menu. */
    boolean keyPressed(int key) {
        if (open && key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return false;
    }

    private static String label(String archetype) {
        return archetype.isEmpty() ? L.t("wynnlabs.settings.archetype_off") : archetype;
    }

    /** What the archetype plays like; "" for one the language files do not know. */
    private static String desc(String archetype) {
        if (archetype.isEmpty()) {
            return L.t("wynnlabs.arch.auto_desc");
        }
        String key = descKey(archetype);
        String d = L.t(key);
        return d.equals(key) ? "" : d;
    }
}
