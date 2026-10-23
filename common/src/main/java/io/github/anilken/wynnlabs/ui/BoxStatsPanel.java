package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.search.Entry;
import io.github.anilken.wynnlabs.search.ItemIndex;
import io.github.anilken.wynnlabs.search.Kind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphics;

/**
 * The left column of the one-item window: what the item is (kind, level, class, skill points
 * it asks for) and its stats with their roll ranges, so the listings' percentages on the right
 * read against something. Lines past the column's height are left out.
 */
final class BoxStatsPanel {

    private BoxStatsPanel() {}

    static void draw(BoxScreen s, GuiGraphics g, String name, int x, int y, int w, int h) {
        Entry e = ItemIndex.get().byName(name);
        g.fill(x + w + 3, y - 2, x + w + 4, y + h, Theme.LINE);
        if (e == null) {
            s.ink(g, s.fit(L.t("wynnlabs.box.stats_unknown"), w), x, y, Theme.INK_3);
            return;
        }
        List<String[]> rows = new ArrayList<>();       // {name, value, "bad"?} or {heading}
        rows.add(new String[] {L.t("wynnlabs.box.stats_needs")});
        rows.add(new String[] {L.t("wynnlabs.box.stats_level"), String.valueOf(e.level()), ""});
        if (e.klass() != null && e.klass() != Klass.NONE) {
            rows.add(new String[] {L.t("wynnlabs.box.stats_class"), SearchResultsPanel.words(e.klass().name()), ""});
        }
        for (Map.Entry<String, Integer> sk : e.skills().entrySet()) {
            if (sk.getValue() != null && sk.getValue() > 0) {
                rows.add(new String[] {SearchResultsPanel.words(sk.getKey()), String.valueOf(sk.getValue()), ""});
            }
        }
        if (e.kind() == Kind.INGREDIENT && !e.professions().isEmpty()) {
            List<String> prof = new ArrayList<>();
            e.professions().forEach(p -> prof.add(SearchResultsPanel.words(p)));
            rows.add(new String[] {L.t("wynnlabs.box.stats_used_in"), String.join(", ", prof), ""});
        }
        if (!e.stats().isEmpty() || !e.modifiers().isEmpty()) {
            rows.add(new String[] {L.t("wynnlabs.box.stats_rolls")});
        }
        for (Entry.Stat st : e.stats()) {
            String range = st.min() == st.max() ? signed(st.min()) : signed(st.min()) + " … " + signed(st.max());
            rows.add(new String[] {SearchResultsPanel.statName(st.key()), range, st.max() < 0 && st.min() < 0 ? "bad" : ""});
        }
        for (Map.Entry<String, Integer> m : e.modifiers().entrySet()) {
            rows.add(new String[] {SearchResultsPanel.statName(m.getKey()), signed(m.getValue()), m.getValue() < 0 ? "bad" : ""});
        }
        int cy = y;
        for (String[] r : rows) {
            if (cy + 10 > y + h) {
                break;
            }
            if (r.length == 1) {
                cy += cy == y ? 0 : 3;
                s.ink(g, s.fit(r[0], w), x, cy, Theme.INK);
                g.fill(x, cy + 10, x + w, cy + 11, Theme.LINE);
                cy += 13;
                continue;
            }
            int vw = s.font().width(r[1]);
            s.ink(g, s.fit(r[0], w - vw - 6), x, cy, Theme.INK_3);
            s.ink(g, r[1], x + w - vw, cy, "bad".equals(r[2]) ? Theme.BAD : Theme.GOOD);
            cy += 11;
        }
    }

    private static String signed(int v) {
        return v > 0 ? "+" + v : String.valueOf(v);
    }
}
