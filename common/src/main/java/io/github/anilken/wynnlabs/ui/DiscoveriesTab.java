package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.advice.Advice;
import io.github.anilken.wynnlabs.core.DevHooks;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.quests.Discoveries;
import io.github.anilken.wynnlabs.quests.DiscoveryBook;
import io.github.anilken.wynnlabs.quests.DiscoveryWiki;
import io.github.anilken.wynnlabs.quests.QuestBook;
import io.github.anilken.wynnlabs.quests.QuestWiki;
import io.github.anilken.wynnlabs.quests.Waypoints;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** The secret discoveries tab: every one in the order picked, with its lore, requirements and steps. */
final class DiscoveriesTab extends MenuTab {

    DiscoveriesTab(RoadmapScreen menu) {
        super(menu);
    }

    // ---- the tab ----

    @Override
    void draw(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float pt) {
        drawDiscoveries(g, x, y, w, h, mx, my);
    }

    @Override
    void mouseScrolled(double x, double y, double v) {
        // over the chosen discovery its steps scroll, elsewhere the list
        if (discDetailRect != null && in(x, y, discDetailRect)) {
            discStepScroll = Math.max(0, discStepScroll - 3 * (int) Math.signum(v));
        } else {
            discScroll = Math.max(0, discScroll - (int) Math.signum(v));
        }
    }

    // ---- secret discoveries tab (Gizli Keşifler) ----

    /** The discovery the detail shows (the home's nearest discovery opens on it). */
    String discSelected;
    private int discScroll;
    private int discStepScroll;
    private int[] discDetailRect;

    /**
     * Secret discoveries: the order and filters on top (kept in settings), the list on the left
     * with a pin button at each row's end, and the chosen discovery on the right: lore,
     * requirements met or not, and its numbered steps with a pin for each place.
     */
    private void drawDiscoveries(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        GameState gs = GameState.current();
        java.util.Set<String> found = Discoveries.foundInGame();
        String character = gs.characterId();
        double[] pos = gs.position();
        int level = gs.level();
        Discoveries.Sort sort = Discoveries.sort();
        Discoveries.Show show = Discoveries.show();
        boolean within = Discoveries.withinLevel();
        List<Discoveries.Entry> sorted = Discoveries.sorted(Discoveries.all(), sort, pos[0], pos[1]);
        int[] counts = new int[3];
        int doneAll = 0;
        List<Discoveries.Entry> list = new ArrayList<>();
        for (Discoveries.Entry e : sorted) {
            boolean done = Discoveries.done(e, found, character);
            doneAll += done ? 1 : 0;
            if (!within || e.minLevel() <= level) {
                counts[done ? 1 : 0]++;
                counts[2]++;
            }
            if (Discoveries.shown(e, done, show, within, level)) {
                list.add(e);
            }
        }
        // 1. the order
        int cy = y;
        String sortLabel = L.t("wynnlabs.discoveries.sort");
        ink(g, sortLabel, x, cy + 2, FAINT);
        int fx = x + font().width(sortLabel) + 6;
        for (int i = 0; i < Discoveries.SORT_KEYS.length; i++) {
            fx += pickChip(g, fx, cy, L.t(Discoveries.SORT_KEYS[i]), sort.ordinal() == i, mx, my, "#discsort:" + i) + 3;
        }
        // the progress, and why it may still be 0
        boolean unread = found.isEmpty() && !DiscoveryBook.seen();
        String progress = unread ? L.t("wynnlabs.discoveries.book_unread") : L.t("wynnlabs.discoveries.progress", doneAll, sorted.size());
        // in the room the sort chips leave (cut short when narrow; the tip has it whole)
        String shown = fit(progress, x + w - fx - 6);
        int pw = font().width(shown);
        ink(g, shown, x + w - pw, cy + 2, unread ? 0xFFFAC775 : 0xFF9FE1CB);
        List<Component> ptip = new ArrayList<>();
        ptip.add(Component.literal(progress).withStyle(ChatFormatting.WHITE));
        for (String l : Advice.wrap(L.t("wynnlabs.discoveries.book_tip"), 48)) {
            ptip.add(Component.literal(l).withStyle(ChatFormatting.GRAY));
        }
        menu.hits.add(new MenuHit(x + w - pw, cy, pw, 12, null, ptip, null));
        cy += 15;
        // 2. the filters, with their counts
        String showLabel = L.t("wynnlabs.discoveries.show");
        ink(g, showLabel, x, cy + 2, FAINT);
        fx = x + font().width(showLabel) + 6;
        for (int i = 0; i < Discoveries.SHOW_KEYS.length; i++) {
            fx += pickChip(g, fx, cy, L.t(Discoveries.SHOW_KEYS[i]) + " " + counts[i], show.ordinal() == i, mx, my, "#discshow:" + i) + 3;
        }
        fx += 6;
        pickChip(g, fx, cy, (within ? "✔ " : "") + L.t("wynnlabs.discoveries.within_level", level), within, mx, my, "#disclevel");
        cy += 17;
        // 3. the list (left) and the chosen discovery (right)
        int listW = (w - 8) * 46 / 100;
        int colH = y + h - cy;
        int rowH = 24;
        int fit = Math.max(1, (colH - 12) / rowH);
        discScroll = Math.max(0, Math.min(discScroll, Math.max(0, list.size() - fit)));
        if (discSelected == null || list.stream().noneMatch(e -> e.id().equals(discSelected))) {
            if (!list.isEmpty() && (discSelected == null || Discoveries.get(discSelected) == null)) {
                discSelected = list.get(0).id();
                discStepScroll = 0;
            }
        }
        for (int i = discScroll; i < list.size() && i < discScroll + fit; i++) {
            Discoveries.Entry e = list.get(i);
            int ry = cy + (i - discScroll) * rowH;
            boolean sel = e.id().equals(discSelected);
            boolean done = Discoveries.done(e, found, character);
            boolean hover = mx >= x && mx < x + listW && my >= ry && my < ry + rowH - 1;
            g.fill(x, ry, x + listW, ry + rowH - 1, sel ? Theme.SELECTED : hover ? Theme.CARD_HOVER : Theme.CARD);
            if (sel) {
                g.fill(x, ry, x + 2, ry + rowH - 1, ACCENT);
            }
            g.fill(x + 5, ry + 4, x + 20, ry + 19, done ? 0xFF3F7A34 : 0xFF5A4A7A);
            Kit.outline(g, x + 5, ry + 4, 15, 15, 0x55000000);
            String num = done ? "✔" : String.valueOf(i + 1);
            g.drawString(font(), num, x + 13 - font().width(num) / 2, ry + 8, 0xFFFFFFFF, true);
            // the pin button at the row's end: the way to the first place, without opening the guide
            int bx = x + listW - 18;
            boolean pinHover = mx >= bx && mx < bx + 14 && my >= ry + 4 && my < ry + 18;
            Kit.button(g, bx, ry + 4, 14, 14, pinHover ? Kit.ON : Kit.REST);
            Art.icon(g, "map-pin", bx + 3, ry + 7, 8, pinHover ? Theme.INK : Theme.CREAM);
            menu.hits.add(new MenuHit(bx, ry + 4, 14, 14, null, List.of(L.c("wynnlabs.discoveries.pin_tip", e.name()).withStyle(ChatFormatting.LIGHT_PURPLE)),
                    "#discpin:0:" + e.id()));
            int textW = listW - 26 - 24;
            ink(g, Component.literal(fit(e.name(), textW)).withStyle(done ? ChatFormatting.GRAY : ChatFormatting.WHITE),
                    x + 25, ry + 4, 0xFFFFFFFF);
            // the distance first: a narrow row cuts the region, not the number
            String sub = RoadmapScreen.dist((int) Math.round(Discoveries.distance(e, pos[0], pos[1]))) + " · Lv " + e.minLevel() + " · " + e.region();
            ink(g, fit(sub, textW), x + 25, ry + 14, e.minLevel() > level ? Theme.BAD : MUTED);
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(e.name()).withStyle(ChatFormatting.AQUA).withStyle(UiFont::bold));
            for (String l : Advice.wrap(e.lore(), 48)) {
                tip.add(Component.literal(l).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            }
            tip.add(L.c("wynnlabs.quests.click_detail").withStyle(ChatFormatting.DARK_GRAY));
            menu.hits.add(new MenuHit(x, ry, listW - 20, rowH - 1, null, tip, "#discsel:" + e.id()));
            if (DevHooks.ON) {
                DevHooks.part("disc", g.pose(), x, ry, listW - 20, rowH - 1);
            }
        }
        if (list.isEmpty()) {
            ink(g, L.c("wynnlabs.discoveries.none_shown").withStyle(ChatFormatting.GRAY), x + 6, cy + 6, 0xFFFFFFFF);
        }
        String foot = list.size() > fit ? (discScroll + 1) + "–" + Math.min(list.size(), discScroll + fit) + " / " + list.size()
                + " · " + L.t("wynnlabs.screen.scroll_wheel") : L.t("wynnlabs.discoveries.licence");
        ink(g, fit(foot, listW), x, y + h - 9, FAINT);
        Discoveries.Entry chosen = discSelected == null ? null : Discoveries.get(discSelected);
        drawDiscoveryDetail(g, x + listW + 8, cy, w - listW - 8, colH, chosen, found, character, pos, mx, my);
    }

    /** Extra space before each guide step after the first. */
    private static final int STEP_GAP = 6;

    private void drawDiscoveryDetail(GuiGraphics g, int x, int y, int w, int h, Discoveries.Entry e, java.util.Set<String> found,
            String character, double[] pos, int mx, int my) {
        card(g, x, y, w, h, false, 0);
        discDetailRect = new int[] {x, y, w, h};
        if (e == null) {
            ink(g, L.c("wynnlabs.discoveries.pick_left").withStyle(ChatFormatting.GRAY), x + 8, y + 8, 0xFFFFFFFF);
            return;
        }
        int ix = x + 7;
        int iy = y + 5;
        int iw = w - 14;
        boolean done = Discoveries.done(e, found, character);
        boolean byWynntils = Discoveries.foundByWynntils(e, found);
        ink(g, Component.literal(fit(e.name(), iw)).withStyle(ChatFormatting.AQUA).withStyle(UiFont::bold), ix, iy, 0xFFFFFFFF);
        iy += Kit.Ui.LINE;
        String sub = "Lv " + e.minLevel() + " · " + e.region() + " · " + RoadmapScreen.dist((int) Math.round(Discoveries.distance(e, pos[0], pos[1])))
                + " · " + L.t(done ? "wynnlabs.discoveries.state_done" : "wynnlabs.discoveries.state_open");
        ink(g, fit(sub, iw), ix, iy, done ? 0xFF9FE1CB : MUTED);
        iy += Kit.Ui.LINE;
        // requirements, each met (green), unmet (red) or unknown (grey)
        Discoveries.Entry en = Discoveries.englishOf(e.id());
        if (!e.requires().isEmpty()) {
            List<QuestBook.Entry> quests = GameState.current().quests();
            net.minecraft.network.chat.MutableComponent line = L.c("wynnlabs.discoveries.needs").withStyle(ChatFormatting.DARK_GRAY);
            List<Component> tip = new ArrayList<>();
            tip.add(L.c("wynnlabs.discoveries.needs").withStyle(ChatFormatting.WHITE));
            for (int i = 0; i < e.requires().size(); i++) {
                Boolean met = en == null || i >= en.requires().size() ? null
                        : Discoveries.met(en, en.requires().get(i), quests, found, character);
                ChatFormatting c = met == null ? ChatFormatting.GRAY : met ? ChatFormatting.GREEN : ChatFormatting.RED;
                String mark = met == null ? "" : met ? "✔ " : "✘ ";
                line.append(Component.literal((i > 0 ? " · " : " ") + mark + e.requires().get(i)).withStyle(c));
                tip.add(Component.literal(mark + e.requires().get(i)).withStyle(c));
            }
            if (font().width(line) <= iw) {
                ink(g, line, ix, iy, 0xFFFFFFFF);
            } else {
                ink(g, Component.literal(fit(line.getString(), iw)).withStyle(ChatFormatting.GRAY), ix, iy, 0xFFFFFFFF);
            }
            menu.hits.add(new MenuHit(ix, iy - 1, iw, Kit.Ui.LINE, null, tip, null));
            iy += Kit.Ui.LINE;
        }
        // the actions, measured first: the steps stop above their rows
        String tickText = byWynntils ? L.t("wynnlabs.discoveries.btn_found") : done ? L.t("wynnlabs.discoveries.btn_undo")
                : L.t("wynnlabs.discoveries.btn_done");
        List<Object[]> acts = List.of(
                new Object[] {tickText, !done, byWynntils ? "#discfound" : "#disctick:" + e.id()},
                new Object[] {L.t("wynnlabs.discoveries.btn_pin_all"), false, "#discpin:0:" + e.id()},
                new Object[] {L.t("wynnlabs.discoveries.btn_wiki"), false, e.wiki()});
        int bottom = y + h - 24 - buttonRowExtra(x + w - 6 - ix, acts);
        Discoveries.Guide guide = Discoveries.guide(e);
        String go = L.t("wynnlabs.quests.go");
        int textW = iw - 14 - font().width(go) - 13;
        List<Object[]> lines = new ArrayList<>();          // {step index, line, first line?}
        for (int s = 0; s < guide.steps().size(); s++) {
            List<net.minecraft.util.FormattedCharSequence> wrapped = font().split(Component.literal(guide.steps().get(s).text()), textW);
            for (int k = 0; k < wrapped.size(); k++) {
                lines.add(new Object[] {s, wrapped.get(k), k == 0});
            }
        }
        // the lore, in at most two lines, cut with "…" (the whole of it on hover); the steps come
        // first, so it keeps one line when its second would push a step out of sight
        int stepsH = lines.size() * Kit.Ui.LINE_DENSE + Math.max(0, guide.steps().size() - 1) * STEP_GAP;
        int notesH = guide.newer() ? 2 * Kit.Ui.LINE_DENSE + 2 : !QuestWiki.on() ? Kit.Ui.LINE : 0;
        int loreRows = bottom - (iy + 2 * Kit.Ui.LINE_DENSE + 10 + notesH) >= stepsH ? 2 : 1;
        List<String> lore = wrapText(e.lore(), iw, loreRows);
        List<Component> loreTip = new ArrayList<>();
        for (String l : Advice.wrap(e.lore(), 48)) {
            loreTip.add(Component.literal(l).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        }
        for (String l : lore) {
            ink(g, l, ix, iy, 0xFF77756E);
            iy += Kit.Ui.LINE_DENSE;
        }
        menu.hits.add(new MenuHit(ix, iy - Kit.Ui.LINE_DENSE * lore.size(), iw, Kit.Ui.LINE_DENSE * lore.size(), null, loreTip, null));
        iy += 4;
        g.fill(ix, iy, ix + iw, iy + 1, Theme.LINE);       // a rule between the story and the steps
        iy += 6;
        if (guide.newer()) {
            for (net.minecraft.util.FormattedCharSequence l : font().split(L.c("wynnlabs.discoveries.newer"), iw)) {
                ink(g, l, ix, iy, 0xFFFAC775);
                iy += Kit.Ui.LINE_DENSE;
            }
            iy += 2;
        } else if (!QuestWiki.on()) {
            ink(g, fit(L.t("wynnlabs.discoveries.wiki_off"), iw), ix, iy, FAINT);
            iy += Kit.Ui.LINE;
        }
        // the steps, numbered, wrapped; "way ›" pins that step's place and the ones after it
        int room = Math.max(1, (bottom - iy) / Kit.Ui.LINE_DENSE);
        discStepScroll = Math.max(0, Math.min(discStepScroll, Math.max(0, lines.size() - room)));
        for (int i = discStepScroll; i < lines.size() && iy + 9 <= bottom; i++) {
            int s = (Integer) lines.get(i)[0];
            boolean first = (Boolean) lines.get(i)[2];
            Discoveries.Step st = guide.steps().get(s);
            if (first && i > discStepScroll) {
                iy += STEP_GAP;                                 // air between steps, so each reads as one
                if (iy + 9 > bottom) {
                    break;
                }
            }
            if (first) {
                ink(g, (s + 1) + ".", ix, iy, 0xFFAFA9EC);
                if (st.at() != null) {
                    int gx = ix + iw - font().width(go) - 9;
                    boolean hv = mx >= gx - 2 && mx < ix + iw && my >= iy - 1 && my < iy + 9;
                    ink(g, go, gx, iy, hv ? 0xFFCECBF6 : 0xFF7F77DD);
                    Art.icon(g, "chevron-right", ix + iw - 8, iy, 8, Theme.paperRgb(hv ? 0xFFCECBF6 : 0xFF7F77DD));
                    int py = st.at()[1] == DiscoveryWiki.NO_Y ? e.y() : st.at()[1];
                    menu.hits.add(new MenuHit(gx - 2, iy - 1, font().width(go) + 13, 10, null, List.of(
                            L.c("wynnlabs.discoveries.pin", e.name(), s + 1).withStyle(ChatFormatting.LIGHT_PURPLE),
                            Component.literal(st.at()[0] + ", " + py + ", " + st.at()[2]).withStyle(ChatFormatting.GRAY)),
                            "#discpin:" + s + ":" + e.id()));
                }
            }
            ink(g, (net.minecraft.util.FormattedCharSequence) lines.get(i)[1], ix + 14, iy, 0xFFCFCDC6);
            iy += Kit.Ui.LINE_DENSE;
        }
        if (lines.size() > room) {
            String more = (discStepScroll + 1) + "–" + Math.min(lines.size(), discStepScroll + room) + " / " + lines.size();
            ink(g, more, x + w - 7 - font().width(more), y + 5, FAINT);
        }
        buttonRow(g, ix, y + h - 20, x + w - 6 - ix, acts, mx, my);
    }

    /** A click on the discoveries tab; true when it was one. */
    boolean discoveryClick(String c) {
        if (c.startsWith("#discsort:")) {
            Settings.setNumber("discoverySort", Integer.parseInt(c.substring(10)));
            discScroll = 0;
        } else if (c.startsWith("#discshow:")) {
            Settings.setNumber("discoveryShow", Integer.parseInt(c.substring(10)));
            discScroll = 0;
        } else if (c.equals("#disclevel")) {
            Settings.setNumber("discoveryWithinLevel", Discoveries.withinLevel() ? 0 : 1);
            discScroll = 0;
        } else if (c.startsWith("#discsel:")) {
            String id = c.substring(9);
            if (!id.equals(discSelected)) {
                discSelected = id;
                discStepScroll = 0;
            }
        } else if (c.startsWith("#disctick:")) {
            String id = c.substring(10);
            String character = GameState.current().characterId();
            boolean now = !Discoveries.ticked(character, id);
            Discoveries.tick(character, id, now);
            menu.flash(L.t(now ? "wynnlabs.discoveries.ticked" : "wynnlabs.discoveries.unticked"));
        } else if (c.equals("#discfound")) {
            menu.flash(L.t("wynnlabs.discoveries.found_by_book"));
        } else if (c.startsWith("#discpin:")) {
            String[] p = c.substring(9).split(":", 2);
            Discoveries.Entry e = Discoveries.get(p[1]);
            if (e != null) {
                // the guide's steps (the wiki's newer ones when it changed), from the clicked one on
                List<int[]> pts = new ArrayList<>();
                List<String> labels = new ArrayList<>();
                Discoveries.pins(e, Discoveries.guide(e).steps(), Integer.parseInt(p[0]), pts, labels);
                int n = Waypoints.all(pts, labels);
                menu.flash(n > 1 ? L.n("wynnlabs.quests.pins_added", n) : n == 1 ? L.t("wynnlabs.screen.guiding", labels.get(0))
                        : L.t("wynnlabs.home.mark_failed"));
            }
        } else {
            return false;
        }
        return true;
    }
}
