package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.model.Tier;
import io.github.anilken.wynnlabs.search.Entry;
import io.github.anilken.wynnlabs.search.Hit;
import io.github.anilken.wynnlabs.search.ItemIndex;
import io.github.anilken.wynnlabs.search.RecentSearches;
import io.github.anilken.wynnlabs.search.SearchQuery;
import io.github.anilken.wynnlabs.search.Source;
import io.github.anilken.wynnlabs.search.SourceRank;
import io.github.anilken.wynnlabs.search.SourceText;
import io.github.anilken.wynnlabs.search.Things;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The search tab's results table: the item's real icon, its name in its rarity's colour with its
 * kind (or why it matched) under it, its level, the easiest way to get it as the home page's
 * coloured badge with a short hint, and a check when the player holds it. A thing of the world
 * (mob, place, dungeon, ...) shows its source badge for a picture and how far its nearest place
 * is. With an empty box it lists the road map's missing pieces and the recent searches instead.
 * Arrow keys move the choice, the wheel scrolls. Rows are told apart by {@link Entry#key}.
 */
final class SearchResultsPanel extends MenuPaint {

    static final int ROW = 22;
    static final int HEADER = 14;

    /** A row: an item with a note under its name, or a section header ({@code e} null). */
    record Row(Entry e, String header, String note) {}

    private final SearchTab tab;
    int scroll;
    private int scrollMax;
    private List<Row> last = List.of();
    private boolean reveal;
    private final ScrollBar bar = new ScrollBar();

    SearchResultsPanel(SearchTab tab) {
        super(tab.menu);
        this.tab = tab;
    }

    /** The rows of a query's answer, each with why it matched when it was not its name. */
    List<Row> rows(SearchQuery.Result result) {
        List<Row> out = new ArrayList<>(result.hits().size());
        for (Hit h : result.hits()) {
            String note = switch (h.match()) {
                case MOB -> L.t("wynnlabs.search.match_mob", h.detail());
                case STAT -> L.t("wynnlabs.search.match_stat", statName(h.detail()));
                default -> null;
            };
            out.add(new Row(h.entry(), null, note));
        }
        return out;
    }

    /** The empty box: the recent searches first (the quickest way back), then the road map's missing pieces. */
    List<Row> emptyRows(ItemIndex index) {
        List<Row> out = new ArrayList<>();
        List<String> recent = RecentSearches.get().names();
        if (!recent.isEmpty()) {
            out.add(new Row(null, L.t("wynnlabs.search.recent"), null));
            for (String n : recent) {
                Entry e = index.byKey(n);
                if (e != null) {
                    out.add(new Row(e, null, null));
                }
            }
        }
        List<String> missing = tab.missingPieces();
        if (!missing.isEmpty()) {
            out.add(new Row(null, L.t("wynnlabs.search.roadmap_missing"), null));
            for (String n : missing) {
                Entry e = index.byName(n);
                if (e != null) {
                    out.add(new Row(e, null, null));
                }
            }
        }
        return out;
    }

    /** Draws the rows; returns the chosen item's name (the first row's when the choice is not listed). */
    String draw(GuiGraphics g, int x, int y, int w, int h, List<Row> rows, int mx, int my) {
        last = rows;
        String sel = tab.selName;
        int selIndex = indexOf(rows, sel);
        if (selIndex < 0) {
            selIndex = next(rows, -1, 1);
            sel = selIndex < 0 ? null : rows.get(selIndex).e().key();
        }
        if (rows.isEmpty()) {
            String empty = tab.box.getValue().isBlank() ? L.t("wynnlabs.search.empty_hint") : L.t("wynnlabs.search.none");
            descWrap(g, empty, x, y + 4, w, 4);
            return null;
        }
        // the columns: name, level, easiest, owned
        int easyW = Math.max(56, Math.min(96, w / 3));
        int lvW = 30;
        int ownW = 10;
        int easyX = x + w - 8 - ownW - easyW;
        int lvX = easyX - lvW;
        ink(g, L.t("wynnlabs.search.col_item"), x + 24, y, FAINT);
        ink(g, "Lv", lvX, y, FAINT);
        ink(g, fit(L.t("wynnlabs.search.col_easiest"), easyW), easyX, y, FAINT);
        int top = y + 11;
        int listH = h - 11;
        int total = 0;
        int selTop = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (i == selIndex) {
                selTop = total;
            }
            total += rows.get(i).e() == null ? HEADER : ROW;
        }
        scrollMax = Math.max(0, total - listH);
        if (reveal) {
            // a key moved the choice: keep it in sight
            if (selTop < scroll) {
                scroll = selTop;
            } else if (selTop + ROW > scroll + listH) {
                scroll = selTop + ROW - listH;
            }
            reveal = false;
        }
        scroll = Math.max(0, Math.min(scrollMax, scroll));
        g.enableScissor(x, top, x + w, top + listH);
        int ry = top - scroll;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            int rh = r.e() == null ? HEADER : ROW;
            if (ry + rh > top && ry < top + listH) {
                if (r.e() == null) {
                    section(g, r.header(), x, ry + 3, w - 8);
                } else {
                    row(g, r, x, ry, w - 8, i == selIndex, lvX, easyX, easyW, mx, my, top, listH);
                }
            }
            ry += rh;
        }
        g.disableScissor();
        bar.place(x + w - ScrollBar.W, top, listH, listH, total, scroll);
        bar.draw(g, mx, my);
        return sel;
    }

    private void row(GuiGraphics g, Row r, int x, int y, int w, boolean chosen, int lvX, int easyX, int easyW, int mx, int my,
            int top, int listH) {
        Entry e = r.e();
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + ROW && my >= top && my < top + listH;
        if (chosen) {
            g.fill(x, y, x + w, y + ROW, Theme.SELECTED);
            g.fill(x, y, x + 2, y + ROW, Theme.GOLD_DEEP);
        } else if (hover) {
            g.fill(x, y, x + w, y + ROW, Theme.CARD_HOVER);
        }
        if (e.item()) {
            g.renderItem(tab.icons.of(e), x + 4, y + 3);
        } else {
            SearchThingCard.picture(g, e, x + 4, y + 3, 16);
        }
        int nameW = lvX - (x + 24) - 4;
        ink(g, Component.literal(fit(e.name(), nameW)), x + 24, y + 2, colour(e));
        String sub = r.note() != null ? r.note() : e.item() ? kindText(e) : SearchThingCard.sub(e);
        scaledText(g, Component.literal(fit(sub, (int) (nameW / DESC_SCALE))), x + 24, y + 12, DESC_SCALE, FAINT);
        if (e.level() > 0) {
            ink(g, String.valueOf(e.level()), lvX, y + 7, MUTED);
        }
        if (!e.item()) {
            // a thing: its badge and how far its nearest place is
            String badge = Things.badge(e);
            Kit.seal(g, easyX, y + 5, 12, 12, Theme.sourceFill(badge));
            Art.icon(g, badge, easyX + 2, y + 7, 8, Theme.CREAM);
            ink(g, fit(SearchThingCard.hint(e, SearchTab.position()), easyW - 16), easyX + 15, y + 7, MUTED);
        } else {
            Prices.Price price = e.tradable() ? Prices.of(e.name()) : null;
            Source.Type easy = SourceRank.easiest(e, price != null && price.typical() != null);
            String badge = SourceRank.badge(easy);
            Kit.seal(g, easyX, y + 5, 12, 12, Theme.sourceFill(badge));
            Art.icon(g, badge, easyX + 2, y + 7, 8, Theme.CREAM);
            ink(g, fit(SourceText.brief(easy, price, e.level()), easyW - 16), easyX + 15, y + 7,
                    easy == Source.Type.MARKET ? GOLD : MUTED);
            if (tab.ownedNames.contains(e.name().toLowerCase(Locale.ROOT))) {
                Art.icon(g, "check", x + w - 9, y + 7, 8, Theme.paperRgb(Theme.GOOD));
            }
            if (hover) {
                menu.hits.add(new MenuHit(x + 2, y + 1, 20, 20, tab.icons.of(e), null, null));
            }
        }
        String key = e.key();
        tab.click(x, Math.max(y, top), w, Math.min(y + ROW, top + listH) - Math.max(y, top), () -> {
            tab.selName = key;
            RecentSearches.get().add(key);
        });
    }

    /** "Ingredient · Woodworking", "Chestplate", "Material"; the data's words with a capital. */
    static String kindText(Entry e) {
        String kind = L.t("wynnlabs.search.kind." + e.kind().name().toLowerCase(Locale.ROOT));
        if (e.kind() == io.github.anilken.wynnlabs.search.Kind.GEAR || e.kind() == io.github.anilken.wynnlabs.search.Kind.TOME) {
            return e.type() == null ? kind : words(e.type());
        }
        if (e.stars() > 0) {
            kind += " " + "✫".repeat(e.stars());
        }
        return e.professions().isEmpty() ? kind : kind + " · " + words(e.professions().get(0));
    }

    /** An item's name colour on paper: its rarity's, ink for the rest. */
    static int colour(Entry e) {
        if (e.tier() == null) {
            return Theme.INK;
        }
        return HomePart.tierColour(Tier.of(e.tier().toUpperCase(Locale.ROOT)));
    }

    /** "manaSteal" → "Mana steal", "WEAPON_TOME" → "Weapon tome". */
    static String statName(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isUpperCase(c) && i > 0 && !Character.isUpperCase(key.charAt(i - 1))) {
                b.append(' ');
            }
            b.append(Character.toLowerCase(c));
        }
        return words(b.toString());
    }

    static String words(String s) {
        String t = s.replace('_', ' ').toLowerCase(Locale.ROOT).trim();
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    // ---- keys and wheel ----

    /** Moves the choice by {@code delta} selectable rows (headers skipped). */
    void move(int delta) {
        int at = indexOf(last, tab.selName);
        int step = delta < 0 ? -1 : 1;
        int to = at;
        for (int n = 0; n < Math.abs(delta); n++) {
            int nx = next(last, to, step);
            if (nx < 0) {
                break;
            }
            to = nx;
        }
        if (to >= 0 && to != at) {
            tab.selName = last.get(to).e().key();
            reveal = true;
        }
    }

    /** A press on the list's bar: the thumb starts a drag, the track pages; true when it took it. */
    boolean barPress(double mx, double my) {
        if (bar.mouseClicked(mx, my)) {
            scroll = bar.offset();
            return true;
        }
        return false;
    }

    /** The bar's thumb dragged; true while it is. */
    boolean barDrag(double my) {
        if (bar.mouseDragged(my)) {
            scroll = bar.offset();
            return true;
        }
        return false;
    }

    void barRelease() {
        bar.mouseReleased();
    }

    void scrolled(double v) {
        scroll = Math.max(0, Math.min(scrollMax, scroll - ROW * (int) Math.signum(v)));
    }

    private static int indexOf(List<Row> rows, String name) {
        // name: the row's key (an item's name, a thing's "kind:name")
        if (name == null) {
            return -1;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).e() != null && rows.get(i).e().key().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** The next item row after {@code from} in direction {@code step}, or -1. */
    private static int next(List<Row> rows, int from, int step) {
        for (int i = from + step; i >= 0 && i < rows.size(); i += step) {
            if (rows.get(i).e() != null) {
                return i;
            }
        }
        return -1;
    }
}
