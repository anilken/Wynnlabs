package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.advice.SourceHint;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.game.Wyn;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.market.Shopping;
import io.github.anilken.wynnlabs.quests.Waypoints;
import io.github.anilken.wynnlabs.search.Area;
import io.github.anilken.wynnlabs.search.Compass;
import io.github.anilken.wynnlabs.search.Entry;
import io.github.anilken.wynnlabs.search.ItemIndex;
import io.github.anilken.wynnlabs.search.ItemWiki;
import io.github.anilken.wynnlabs.search.MapMark;
import io.github.anilken.wynnlabs.search.Source;
import io.github.anilken.wynnlabs.search.SourceText;
import io.github.anilken.wynnlabs.search.Sources;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The search tab's card for the chosen item: its icon, name, kind and level, what it asks of
 * the player (✔/✘ as tooltips mark them), the build gain when the shopping list knows it, the
 * buttons (pin the easiest place, open Wynntils' map there, the wiki), then "How do I get it":
 * every way ranked easiest first, each with its distance and direction and a pin. The folding
 * sections below are {@link SearchCardSections}. Sources are worked out once per choice; the
 * wiki page loads in the background and fills in a merchant's cost when it arrives.
 */
final class SearchCardPanel extends MenuPaint {

    private final SearchTab tab;
    final SearchCardSections sections;
    Entry entry;
    List<Source> rows = List.of();
    Sources.Owned have = Sources.Owned.NONE;
    /** The row the map and "open on map" follow (0: the easiest). */
    int picked;
    private CompletableFuture<ItemWiki.Info> wikiLoad;
    ItemWiki.Info wiki;
    private Double gain;
    /** The marks stay on Wynntils' map after the menu closes ("open on map", "mark all"). */
    private boolean kept;
    private boolean all;
    int scroll;
    private int scrollMax;
    private int[] view = new int[4];

    SearchCardPanel(SearchTab tab) {
        super(tab.menu);
        this.tab = tab;
        this.sections = new SearchCardSections(this, tab);
    }

    String name() {
        return entry == null ? null : entry.name();
    }

    /** Shows this item: its sources from where the player stands, its wiki page asked for, the map's marks replaced. */
    void select(Entry e) {
        entry = e;
        picked = 0;
        scroll = 0;
        all = false;
        kept = false;
        have = SearchCardSections.owned(e.name());
        Prices.Price price = e.tradable() ? Prices.of(e.name()) : null;
        rows = Sources.of(e, ItemIndex.get().places(), have, price, SearchTab.position());
        wikiLoad = ItemWiki.load(e.name());
        wiki = null;
        Shopping.Pick pick = e.kind() == io.github.anilken.wynnlabs.search.Kind.GEAR ? Shopping.find(e.name()) : null;
        gain = pick == null || pick.gain() <= 0 ? null : pick.gain();
        sections.select(e);
        marks();
    }

    /** The menu closes or the tab is left: our marks leave the map unless the player asked to keep them. */
    void closed() {
        if (!kept) {
            Wyn.showMarks(List.of());
        }
    }

    private void marks() {
        ItemWiki.Info w = wiki;
        Wyn.showMarks(MapMark.of(rows, picked, all, s -> SourceText.row(s, w)));
    }

    void draw(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        Kit.card(g, x, y, w, h, Theme.GOLD_DEEP);
        if (entry == null) {
            descWrap(g, L.t("wynnlabs.search.card_empty"), x + 10, y + 12, w - 20, 4);
            return;
        }
        if (wiki == null && wikiLoad != null && wikiLoad.isDone()) {
            wiki = wikiLoad.getNow(ItemWiki.Info.EMPTY);
            SourceHint.forget(entry.name());        // the tooltip's line may now name a cost
            marks();
        }
        view = new int[] {x + 1, y + 4, w - 2, h - 6};
        g.enableScissor(view[0], view[1], view[0] + view[2], view[1] + view[3]);
        int top = y + 8 - scroll;
        int cy = header(g, x + 8, top, w - 16 - ScrollBar.W, mx, my);
        cy = sources(g, x + 8, cy + 6, w - 16 - ScrollBar.W, mx, my);
        cy = sections.draw(g, x + 8, cy + 4, w - 16 - ScrollBar.W, mx, my);
        g.disableScissor();
        int total = cy - top + 6;
        scrollMax = Math.max(0, total - view[3]);
        scroll = Math.min(scroll, scrollMax);
        if (scrollMax > 0) {
            ScrollBar bar = new ScrollBar();
            bar.place(x + w - ScrollBar.W - 2, view[1], view[3], view[3], total, scroll);
            bar.draw(g, mx, my);
        }
    }

    /** A click area clipped to the card's visible part. */
    void click(int x, int y, int w, int h, Runnable run) {
        int top = Math.max(y, view[1]);
        int bottom = Math.min(y + h, view[1] + view[3]);
        if (bottom > top) {
            tab.click(x, top, w, bottom - top, run);
        }
    }

    /** Whether (mx, my) is on the card's visible part. */
    boolean over(int mx, int my) {
        return mx >= view[0] && mx < view[0] + view[2] && my >= view[1] && my < view[1] + view[3];
    }

    // ---- the head: picture, name, needs, buttons ----

    private int header(GuiGraphics g, int x, int y, int w, int mx, int my) {
        var stack = tab.icons.of(entry);
        Kit.slot(g, x, y, 36, 0);
        bigItem(g, stack, x + 2, y + 2);
        if (over(mx, my) && mx >= x && mx < x + 36 && my >= y && my < y + 36) {
            menu.hits.add(new MenuHit(x, y, 36, 36, stack, null, null));
        }
        int tx = x + 42;
        int tw = w - 42;
        ink(g, Component.literal(fit(entry.name(), tw)).withStyle(UiFont::bold), tx, y + 1, SearchResultsPanel.colour(entry));
        String tier = entry.tier() == null ? "" : SearchResultsPanel.words(entry.tier()) + " · ";
        String lv = entry.level() > 0 ? " · Lv " + entry.level() : "";
        ink(g, fit(tier + SearchResultsPanel.kindText(entry) + lv, tw), tx, y + 12, MUTED);
        // the needs wrap to a second line when the card is narrow
        List<net.minecraft.util.FormattedCharSequence> need = font().split(sections.needs(entry), tw);
        for (int i = 0; i < Math.min(2, need.size()); i++) {
            ink(g, need.get(i), tx, y + 23 + i * 10, Theme.INK);
        }
        int cy = Math.max(y + 38, y + 23 + Math.min(2, need.size()) * 10 + 3);
        if (gain != null) {
            String text = L.t("wynnlabs.search.gain", dec(gain, 1));
            int pw = font().width(text) + 10;
            Kit.seal(g, x, cy, pw, 11, Theme.SEAL_2);
            g.drawString(font(), text, x + 5, cy + 2, Theme.SEAL_TEXT, true);
            cy += 14;
        }
        // the buttons: pin the easiest place, Wynntils' map there, the wiki
        Area easiest = firstPlace(0);
        boolean mapOk = easiest != null && GameState.current().running() && Wyn.up(Wyn.Feature.WAYPOINTS);
        int bx = x;
        bx += iconButton(g, bx, cy, "map-pin", L.t("wynnlabs.search.pin_easiest"), easiest != null, mx, my, this::pinEasiest) + 4;
        bx += iconButton(g, bx, cy, "map-2", L.t("wynnlabs.search.open_map"), mapOk, mx, my, this::openMap) + 4;
        if (bx + font().width(L.t("wynnlabs.search.wiki")) + 22 > x + w) {
            bx = x;
            cy += 17;
        }
        String url = ItemWiki.url(entry.name());
        iconButton(g, bx, cy, "arrow-up-right", L.t("wynnlabs.search.wiki"), true, mx, my,
                () -> net.minecraft.client.gui.screens.ConfirmLinkScreen.confirmLinkNow(menu, java.net.URI.create(url), true));
        return cy + 14;
    }

    /** A small wooden button with an icon; greyed and inert when {@code on} is false. Returns its width. */
    int iconButton(GuiGraphics g, int x, int y, String icon, String label, boolean on, int mx, int my, Runnable run) {
        int bw = font().width(label) + 22;
        boolean hover = on && over(mx, my) && mx >= x && mx < x + bw && my >= y && my < y + 14;
        int state = hover ? Kit.HOVER : Kit.REST;
        Kit.button(g, x, y, bw, 14, state);
        woodIconText(g, icon, label, x + 6, y + 3, state);
        if (!on) {
            g.fill(x, y, x + bw, y + 14, 0x80D8C595);
        } else {
            click(x, y, bw, 14, run);
        }
        return bw;
    }

    // ---- how do I get it ----

    private int sources(GuiGraphics g, int x, int y, int w, int mx, int my) {
        section(g, "▾ " + L.t("wynnlabs.search.how"), x, y, w);
        String allText = L.t("wynnlabs.search.mark_all");
        boolean places = rows.stream().anyMatch(Source::hasPlace);
        if (places && GameState.current().running()) {
            int aw = font().width(allText);
            int ax = x + w - aw;
            boolean hover = over(mx, my) && mx >= ax && mx < ax + aw && my >= y - 1 && my < y + 9;
            g.fill(ax - 3, y - 1, ax + aw + 2, y + 9, Theme.CARD);
            ink(g, allText, ax, y, hover ? Theme.GOLD_DEEP : Theme.LINK);
            click(ax - 3, y - 1, aw + 5, 10, () -> {
                all = true;
                kept = true;
                marks();
                menu.flash(L.t("wynnlabs.search.marked"));
            });
        }
        int cy = y + 13;
        double[] pos = SearchTab.position();
        for (int i = 0; i < rows.size(); i++) {
            Source s = rows.get(i);
            int rh = s.hasPlace() ? 22 : 13;
            boolean hover = over(mx, my) && mx >= x && mx < x + w && my >= cy && my < cy + rh;
            if (i == picked && s.hasPlace()) {
                g.fill(x - 2, cy - 1, x + w, cy + rh - 1, Theme.SELECTED);
            } else if (hover) {
                g.fill(x - 2, cy - 1, x + w, cy + rh - 1, Theme.CARD_HOVER);
            }
            Kit.seal(g, x, cy, 11, 11, Theme.sourceFill(s.badge()));
            Art.icon(g, s.badge(), x + 1, cy + 1, 8, Theme.CREAM);
            int pinW = s.hasPlace() ? 14 : 0;
            String text = SourceText.row(s, wiki);
            ink(g, fit(text, w - 16 - pinW), x + 15, cy + 1, i == 0 ? Theme.INK : MUTED);
            if (font().width(text) > w - 16 - pinW && hover) {
                menu.hits.add(new MenuHit(x, cy, w - pinW, rh, null, List.of(Component.literal(text)), null));
            }
            if (s.hasPlace()) {
                Area a = s.main();
                double d = Compass.distance(a, pos);
                String dir = Compass.direction(a, pos);
                String where = Double.isNaN(d) ? "" : RoadmapScreen.dist((int) Math.round(d))
                        + (dir == null ? "" : " " + dirName(dir));
                if (a.name() != null && !a.name().equals(s.name())) {
                    where = where.isEmpty() ? a.name() : where + " · " + a.name();
                }
                if (s.areas().size() > 1) {
                    where += (where.isEmpty() ? "" : " · ") + L.t("wynnlabs.search.more_places", s.areas().size() - 1);
                }
                scaledText(g, Component.literal(fit(where, (int) ((w - 16 - pinW) / DESC_SCALE))), x + 15, cy + 11, DESC_SCALE, FAINT);
                int px = x + w - 12;
                boolean ph = over(mx, my) && mx >= px && mx < px + 12 && my >= cy && my < cy + 12;
                Kit.button(g, px, cy, 12, 12, ph ? Kit.HOVER : Kit.REST);
                Art.icon(g, "map-pin", px + 2, cy + 2, 8, Kit.buttonText(ph ? Kit.HOVER : Kit.REST));
                String label = text;
                // a row of several places (loot chests, spawn areas) pins them all, nearest first
                List<Area> rowPlaces = s.areas();
                click(px, cy, 12, 12, () -> {
                    if (rowPlaces.size() > 1) {
                        pinAll(rowPlaces);
                    } else {
                        pin(a, label);
                    }
                });
                int row = i;
                click(x, cy - 1, w - 14, rh, () -> {
                    picked = row;
                    marks();
                });
            }
            cy += rh;
        }
        return cy;
    }

    /** A compass point of {@link Compass#direction} in the player's language. */
    static String dirName(String dir) {
        return switch (dir) {
            case "east" -> L.t("wynnlabs.grind.dir_east");
            case "southeast" -> L.t("wynnlabs.grind.dir_southeast");
            case "south" -> L.t("wynnlabs.grind.dir_south");
            case "southwest" -> L.t("wynnlabs.grind.dir_southwest");
            case "west" -> L.t("wynnlabs.grind.dir_west");
            case "northwest" -> L.t("wynnlabs.grind.dir_northwest");
            case "north" -> L.t("wynnlabs.grind.dir_north");
            default -> L.t("wynnlabs.grind.dir_northeast");
        };
    }

    /** The first place of the rows from {@code from} on, or null. */
    private Area firstPlace(int from) {
        for (int i = from; i < rows.size(); i++) {
            if (rows.get(i).hasPlace()) {
                return rows.get(i).main();
            }
        }
        return null;
    }

    /** Enter and "📍 Easiest": the compass on the easiest place that has one. */
    void pinEasiest() {
        for (Source s : rows) {
            if (s.hasPlace()) {
                pin(s.main(), SourceText.row(s, wiki));
                return;
            }
        }
    }

    private void pin(Area a, String label) {
        String what = entry.name().length() > 32 ? entry.name().substring(0, 32) : entry.name();
        if (Waypoints.go(a.x(), a.y(), a.z(), what)) {
            menu.flash(L.t("wynnlabs.screen.guiding", what));
        } else {
            String at = "/compass at " + a.x() + " " + a.y() + " " + a.z();
            net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(at);
            menu.flash(L.t("wynnlabs.screen.copied", at));
        }
    }

    private void pinAll(List<Area> places) {
        String what = entry.name().length() > 32 ? entry.name().substring(0, 32) : entry.name();
        List<int[]> points = new java.util.ArrayList<>();
        for (Area p : places) {
            points.add(new int[] {p.x(), p.y(), p.z()});
        }
        int n = Waypoints.goAll(points, what);
        if (n > 0) {
            menu.flash(L.n("wynnlabs.search.pinned_many", n));
        } else {
            pin(places.get(0), what);
        }
    }

    /** "Open on map": the marks kept, Wynntils' map centred on the chosen row's place. */
    private void openMap() {
        Area a = picked < rows.size() && rows.get(picked).hasPlace() ? rows.get(picked).main() : firstPlace(0);
        if (a == null) {
            return;
        }
        if (!rows.get(picked).hasPlace()) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).hasPlace()) {
                    picked = i;
                    break;
                }
            }
        }
        kept = true;
        marks();
        Wyn.openMap(a.x(), a.z());
    }

    // ---- the wheel ----

    /** The wheel over the card scrolls it; true when it was over the card. */
    boolean scrolled(double x, double y, double v) {
        if (entry == null || !over((int) x, (int) y)) {
            return false;
        }
        scroll = Math.max(0, Math.min(scrollMax, scroll - 20 * (int) Math.signum(v)));
        return true;
    }
}
