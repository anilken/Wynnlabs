package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.search.Area;
import io.github.anilken.wynnlabs.search.Compass;
import io.github.anilken.wynnlabs.search.Entry;
import io.github.anilken.wynnlabs.search.ItemIndex;
import io.github.anilken.wynnlabs.search.Kind;
import io.github.anilken.wynnlabs.search.Source;
import io.github.anilken.wynnlabs.search.SourceText;
import io.github.anilken.wynnlabs.search.Sources;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphics;

/**
 * The left column of the one-item window: what the item is (kind, level, class, skill points
 * it asks for) and its stats with their roll ranges, so the listings' percentages on the right
 * read against something. An item without rolls (an ingredient, a material) also gets where
 * it comes from (the three easiest ways besides the market and what the player holds) and how
 * many the player has in the bag and the bank. Lines past the column's height are left out.
 */
final class BoxStatsPanel {

    /** How many ways to get the item are listed. */
    private static final int FROM = 3;
    /** The sources and holdings, read once per item and then every few seconds, never per frame. */
    private static String readFor;
    private static long readAt;
    private static List<Source> from;
    private static Sources.Owned owned;

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
        if (!s.rolled()) {
            more(s, g, e, x, cy, w, y + h);
        }
    }

    /** "Nereden" and "Sende" under the stats, from {@code cy} down to {@code bottom}. */
    private static void more(BoxScreen s, GuiGraphics g, Entry e, int x, int cy, int w, int bottom) {
        long now = System.currentTimeMillis();
        if (!e.name().equals(readFor) || now - readAt > 3000) {
            readFor = e.name();
            readAt = now;
            owned = SearchCardSections.owned(e.name());
            List<Source> keep = new ArrayList<>();
            for (Source src : Sources.of(e, ItemIndex.get().places(), Sources.Owned.NONE, null, SearchTab.position())) {
                if (src.type() != Source.Type.MARKET && keep.size() < FROM) {
                    keep.add(src);              // the market is this whole window; the bag and bank have their own lines
                }
            }
            from = keep;
        }
        double[] pos = SearchTab.position();
        cy = heading(s, g, L.t("wynnlabs.box.from"), x, cy, w, bottom);
        for (Source src : from) {
            if (cy + 10 > bottom) {
                return;
            }
            Kit.seal(g, x, cy - 1, 10, 10, Theme.sourceFill(src.badge()));
            Art.icon(g, src.badge(), x + 1, cy, 8, Theme.CREAM);
            s.ink(g, s.fit(SourceText.row(src, null), w - 13), x + 13, cy, Theme.INK_2);
            cy += 11;
            Area a = src.main();
            double d = Compass.distance(a, pos);
            if (a != null && !Double.isNaN(d) && cy + 8 <= bottom) {
                String dir = Compass.direction(a, pos);
                String where = RoadmapScreen.dist((int) Math.round(d)) + (dir == null ? "" : " · " + SearchCardPanel.dirName(dir));
                s.small(g, s.fit(where, (int) ((w - 13) / MenuPaint.DESC_SCALE)), x + 13, cy - 1, Theme.INK_3);
                cy += 9;
            }
        }
        cy = heading(s, g, L.t("wynnlabs.box.owned"), x, cy, w, bottom);
        if (cy + 10 > bottom) {
            return;
        }
        int bank = owned.bank().values().stream().mapToInt(Integer::intValue).sum();
        if (!owned.any()) {
            s.ink(g, s.fit(L.t("wynnlabs.box.owned_none"), w), x, cy, Theme.INK_3);
            return;
        }
        String line = L.t("wynnlabs.box.owned_bag", owned.bag()) + " · " + L.t("wynnlabs.box.owned_bank", bank);
        s.ink(g, s.fit(line, w), x, cy, Theme.GOOD);
    }

    /** A section's name with a line under it; returns where its first line goes (past {@code bottom}: nothing drawn). */
    private static int heading(BoxScreen s, GuiGraphics g, String t, int x, int cy, int w, int bottom) {
        cy += 3;
        if (cy + 10 > bottom) {
            return bottom;
        }
        s.ink(g, s.fit(t, w), x, cy, Theme.INK);
        g.fill(x, cy + 10, x + w, cy + 11, Theme.LINE);
        return cy + 13;
    }

    private static String signed(int v) {
        return v > 0 ? "+" + v : String.valueOf(v);
    }
}
