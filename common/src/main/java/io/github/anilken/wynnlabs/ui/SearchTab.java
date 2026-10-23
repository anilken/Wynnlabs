package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.build.roadmap.Roadmap;
import io.github.anilken.wynnlabs.core.Dirs;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Platform;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.game.ItemJson;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.market.Vault;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.search.Entry;
import io.github.anilken.wynnlabs.search.ItemIndex;
import io.github.anilken.wynnlabs.search.Kind;
import io.github.anilken.wynnlabs.search.RecentSearches;
import io.github.anilken.wynnlabs.search.SearchQuery;
import io.github.anilken.wynnlabs.search.SearchWorker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The search tab ("Ara"): a search box over every item Wynntils knows and the world's things
 * (mobs, places, dungeons, services, resources, quests, NPCs, discoveries), its sort and filter
 * chips ("All" first, then one per kind with its count), the results on the left
 * ({@link SearchResultsPanel}) and the chosen entry's card on the right ({@link SearchCardPanel}).
 * Typing runs the query on a background thread ({@link SearchWorker}); the frame draws its last
 * answer. The selection follows the entry's key, so it stays put while the list changes. Shows
 * only: the one thing it changes is a compass pin or the map's marks.
 */
final class SearchTab extends MenuTab {

    static final String[] SORTS = {"wynnlabs.search.sort_easiest", "wynnlabs.search.sort_level", "wynnlabs.search.sort_name",
        "wynnlabs.search.sort_price"};
    private static final Kind[] KINDS = Kind.values();

    /** A click area of this frame and what it does. */
    record Click(int[] rect, Runnable run) {}

    final List<Click> clicks = new ArrayList<>();
    final SearchIcons icons = new SearchIcons();
    final SearchResultsPanel results = new SearchResultsPanel(this);
    final SearchCardPanel card = new SearchCardPanel(this);
    EditBox box;
    SearchQuery.Sort sort = SearchQuery.Sort.EASIEST;
    final Set<Kind> kinds = EnumSet.noneOf(Kind.class);
    /** 0: every level, 1: up to mine, 2: mine and the ten below. */
    int levelMode;
    boolean myClass;
    boolean owned;
    boolean onMarket;
    private boolean sortOpen;
    private SearchWorker worker;
    private SearchQuery submitted;
    private ItemIndex submittedFor;
    /** Lower-case names of what the player holds (bag and every seen bank page), as of the last query. */
    Set<String> ownedNames = Set.of();
    /** The chosen entry's key (an item's name, a thing's "kind:name"); the row it is on is found again each frame. */
    String selName;
    /** An entry asked for from outside (the search key over a hovered item, a card's link), chosen once the index is there. */
    private String wanted;

    SearchTab(RoadmapScreen menu) {
        super(menu);
    }

    @Override
    List<EditBox> init() {
        String typed = box == null ? "" : box.getValue();
        box = new EditBox(font(), 0, 0, 200, 12, L.c("wynnlabs.search.box"));
        box.setMaxLength(60);
        box.setValue(typed);
        box.setResponder(v -> results.scroll = 0);
        paperField(box);
        return List.of(box);
    }

    @Override
    void beginFrame() {
        clicks.clear();
    }

    /** Adds a click area for this frame. */
    void click(int x, int y, int w, int h, Runnable run) {
        clicks.add(new Click(new int[] {x, y, w, h}, run));
    }

    /**
     * Opens on this entry, by its key (an item's name, a thing's "kind:name"): its name in the box
     * and its card shown (the search key over a hovered item, a card's link to a mob or an item).
     */
    void show(String key) {
        wanted = key;
        if (box != null) {
            Entry e = ItemIndex.get().byKey(key);
            box.setValue(e != null ? e.name() : key.substring(key.indexOf(':') + 1));
        }
        selName = key;
    }

    /** The tab is shown: the box takes the keys. */
    void focus() {
        box.setFocused(true);
        menu.setFocused(box);
    }

    /** The tab is left or the menu closes: the worker stops, the map's marks go unless kept. */
    void closed() {
        if (worker != null) {
            worker.close();
            worker = null;
        }
        card.closed();
    }

    // ---- drawing ----

    @Override
    void draw(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float pt) {
        ItemIndex index = ItemIndex.get();
        if (index.missing() != null) {
            ItemIndex.startLoading(Platform.get().gameDir(), Dirs.config().resolve("chests.json"));
        }
        // the box and the sort on one line
        String sortLabel = L.t(SORTS[sort.ordinal()]);
        int sortW = font().width(sortLabel) + 24;
        textBox(g, box, x, y, w - sortW - 6, "wynnlabs.search.box_hint", mx, my, pt);
        int sx = x + w - sortW;
        boolean sortHover = mx >= sx && mx < sx + sortW && my >= y - 1 && my < y + 13;
        int st = sortOpen ? Kit.ON : sortHover ? Kit.HOVER : Kit.REST;
        Kit.button(g, sx, y - 1, sortW, 14, st);
        onWood(g, sortLabel, sx + 6, y + 2, st);
        Art.icon(g, "chevron-down", sx + sortW - 12, y + 2, 8, Kit.buttonText(st));
        click(sx, y - 1, sortW, 14, () -> sortOpen = !sortOpen);

        SearchQuery q = query();
        SearchQuery.Result result = submit(q, index);
        int cy = chips(g, x, y + 17, w, result, mx, my);
        int bodyY = cy + 6;
        int bodyH = y + h - 12 - bodyY;
        // results on the left, the card on the right
        int cardW = Math.max(170, Math.min(300, (int) (w * 0.46)));
        int listW = w - cardW - 8;
        if (index.missing() != null) {
            String why = "loading".equals(index.missing()) ? L.t("wynnlabs.search.loading") : L.t("wynnlabs.search.no_data");
            ink(g, fit(why, listW), x, bodyY + 4, FAINT);
        } else {
            // an empty box lists the recent searches, unless a world kind is picked (all services, all quests)
            boolean browse = q.kinds().stream().anyMatch(k -> !k.item());
            List<SearchResultsPanel.Row> rows = q.text().isBlank() && !browse ? results.emptyRows(index) : results.rows(result);
            String pick = results.draw(g, x, bodyY, listW, bodyH, rows, mx, my);
            choose(pick, index);
        }
        card.draw(g, x + listW + 8, bodyY, cardW, bodyH, mx, my);
        // the keys, at the foot of the page
        ink(g, fit(L.t("wynnlabs.search.footer"), w), x, y + h - 9, FAINT);
        if (sortOpen) {
            sortList(g, sx, y + 14, sortW, mx, my);
        }
    }

    /** The chips under the box: kinds with their counts, the level range, my class, owned, on the market. Returns the bottom. */
    private int chips(GuiGraphics g, int x, int y, int w, SearchQuery.Result result, int mx, int my) {
        int cx = x;
        int cy = y;
        List<Object[]> chips = new ArrayList<>();       // {label, on, action}
        int total = result.counts().values().stream().mapToInt(Integer::intValue).sum();
        chips.add(new Object[] {L.t("wynnlabs.search.kind.all") + " " + total, kinds.isEmpty(), (Runnable) kinds::clear});
        for (Kind k : KINDS) {
            Integer n = result.counts().get(k);
            if (!k.item() && (n == null || n == 0) && !kinds.contains(k)) {
                continue;                               // a world kind with nothing to show takes no room
            }
            String label = L.t("wynnlabs.search.kind." + k.name().toLowerCase(Locale.ROOT)) + " " + (n == null ? 0 : n);
            chips.add(new Object[] {label, kinds.contains(k), (Runnable) () -> {
                if (!kinds.remove(k)) {
                    kinds.add(k);
                }
                results.scroll = 0;
            }});
        }
        int lv = GameState.current().level();
        String level = switch (levelMode) {
            case 1 -> L.t("wynnlabs.search.level_upto", lv);
            case 2 -> L.t("wynnlabs.search.level_near", Math.max(1, lv - 10), lv);
            default -> L.t("wynnlabs.search.level_all");
        };
        chips.add(new Object[] {level, levelMode != 0, (Runnable) () -> levelMode = lv <= 0 ? 0 : (levelMode + 1) % 3});
        chips.add(new Object[] {L.t("wynnlabs.search.my_class"), myClass, (Runnable) () -> myClass = !myClass});
        chips.add(new Object[] {L.t("wynnlabs.search.owned"), owned, (Runnable) () -> owned = !owned});
        chips.add(new Object[] {L.t("wynnlabs.search.on_market"), onMarket, (Runnable) () -> onMarket = !onMarket});
        for (Object[] c : chips) {
            String label = (String) c[0];
            int cw = font().width(label) + 12;
            if (cx + cw > x + w && cx > x) {
                cx = x;
                cy += 16;
            }
            boolean on = (boolean) c[1];
            boolean hover = mx >= cx && mx < cx + cw && my >= cy && my < cy + 14;
            int state = on ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
            Kit.button(g, cx, cy, cw, 14, state);
            onWood(g, label, cx + 6, cy + 3, state);
            Runnable run = (Runnable) c[2];
            click(cx, cy, cw, 14, () -> {
                run.run();
                results.scroll = 0;
            });
            cx += cw + 4;
        }
        return cy + 14;
    }

    /** The sort's list, open under its button, over everything else. */
    private void sortList(GuiGraphics g, int x, int y, int w, int mx, int my) {
        int lw = w;
        for (String s : SORTS) {
            lw = Math.max(lw, font().width(L.t(s)) + 16);
        }
        int lx = x + w - lw;
        Kit.card(g, lx, y, lw, SORTS.length * 14 + 4, 0);
        for (int i = 0; i < SORTS.length; i++) {
            int ry = y + 2 + i * 14;
            boolean hover = mx >= lx && mx < lx + lw && my >= ry && my < ry + 14;
            if (hover || i == sort.ordinal()) {
                g.fill(lx + 2, ry, lx + lw - 2, ry + 14, i == sort.ordinal() ? Theme.SELECTED : Theme.CARD_HOVER);
            }
            ink(g, L.t(SORTS[i]), lx + 8, ry + 3, Theme.INK);
            int pick = i;
            click(lx, ry, lw, 14, () -> {
                sort = SearchQuery.Sort.values()[pick];
                sortOpen = false;
                results.scroll = 0;
            });
        }
    }

    // ---- the query ----

    SearchQuery query() {
        int lv = GameState.current().level();
        int lo = levelMode == 2 ? Math.max(0, lv - 10) : 0;
        int hi = levelMode == 0 || lv <= 0 ? Integer.MAX_VALUE : lv;
        return new SearchQuery(box.getValue(), sort, kinds, lo, hi, myClass, owned, onMarket);
    }

    /** Sends the query to the worker when it or the index changed; the last finished answer. */
    private SearchQuery.Result submit(SearchQuery q, ItemIndex index) {
        if (index.missing() != null) {
            return SearchQuery.Result.EMPTY;
        }
        if (worker == null) {
            worker = new SearchWorker();
            submitted = null;
        }
        if (!q.equals(submitted) || index != submittedFor) {
            ownedNames = ownedNow();
            Set<String> have = ownedNames;
            Klass k = GameState.current().classType();
            worker.submit(q, new SearchQuery.Facts(k == null ? Klass.NONE : k, n -> have.contains(n.toLowerCase(Locale.ROOT)),
                    n -> {
                        Prices.Price p = Prices.of(n);
                        return p == null ? null : p.typical();
                    }));
            submitted = q;
            submittedFor = index;
        }
        return worker.latest();
    }

    /** What the player holds now: the bag's names and every bank group's, lower case. */
    static Set<String> ownedNow() {
        Set<String> out = new HashSet<>();
        try {
            for (ItemStack s : GameState.current().inventory()) {
                if (!s.isEmpty()) {
                    out.add(ItemJson.clean(s.getHoverName().getString()).toLowerCase(Locale.ROOT));
                }
            }
            for (Vault.Group gr : Vault.groups()) {
                out.add(gr.name().toLowerCase(Locale.ROOT));
            }
        } catch (RuntimeException ignored) {
            // no bag or bank read yet
        }
        return out;
    }

    /** The row the results panel shows as chosen; a new one opens its card. */
    private void choose(String pick, ItemIndex index) {
        if (wanted != null && index.byKey(wanted) != null) {
            pick = index.byKey(wanted).key();
            wanted = null;
            RecentSearches.get().add(pick);
        }
        if (pick != null && !pick.equals(card.key())) {
            Entry e = index.byKey(pick);
            if (e != null) {
                card.select(e);
            }
        }
        selName = pick;
    }

    /** The road map's next milestone: the pieces it wants that the player does not hold yet. */
    List<String> missingPieces() {
        List<String> out = new ArrayList<>();
        Set<String> have = ownedNames;
        for (Roadmap.Step s : menu.steps) {
            for (Roadmap.Gear gear : s.gear()) {
                String n = gear.info().name();
                if (!have.contains(n.toLowerCase(Locale.ROOT)) && !out.contains(n)) {
                    out.add(n);
                }
            }
            if (!out.isEmpty()) {
                break;
            }
        }
        return out;
    }

    // ---- input ----

    @Override
    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double ex = event.x();
        double ey = event.y();
        // the two scroll bars first: a press on a thumb starts a drag, on a track it pages
        if (card.barPress(ex, ey) || results.barPress(ex, ey)) {
            return true;
        }
        for (int i = clicks.size() - 1; i >= 0; i--) {
            Click c = clicks.get(i);
            if (in(ex, ey, c.rect())) {
                boolean wasOpen = sortOpen;
                c.run().run();
                if (wasOpen && sortOpen) {
                    sortOpen = false;               // a click anywhere else closes the sort's list
                }
                return true;
            }
        }
        sortOpen = false;
        if (box.mouseClicked(event, doubleClick)) {
            menu.setFocused(box);
            return true;
        }
        return false;
    }

    @Override
    boolean sharedClicks() {
        return false;
    }

    /** A drag with the button held: a scroll bar's thumb follows; true while one is dragged. */
    boolean mouseDragged(double x, double y) {
        return card.barDrag(y) || results.barDrag(y);
    }

    void mouseReleased() {
        card.barRelease();
        results.barRelease();
    }

    @Override
    void mouseScrolled(double x, double y, double v) {
        if (!card.scrolled(x, y, v)) {
            results.scrolled(v);
        }
    }

    @Override
    boolean keyPressed(int key) {
        switch (key) {
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> {
                results.move(key == GLFW.GLFW_KEY_UP ? -1 : 1);
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_UP, GLFW.GLFW_KEY_PAGE_DOWN -> {
                results.move(key == GLFW.GLFW_KEY_PAGE_UP ? -8 : 8);
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (card.key() != null) {
                    RecentSearches.get().add(card.key());
                    card.pinEasiest();
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** The player's {x, z} now, or null out of the world. */
    static double[] position() {
        var p = Minecraft.getInstance().player;
        return p == null ? null : new double[] {p.getX(), p.getZ()};
    }
}
