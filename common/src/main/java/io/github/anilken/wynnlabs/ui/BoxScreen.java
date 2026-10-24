package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.advice.BoxVerdict;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.market.Listings;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.market.Shopping;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.search.BoxMarket;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The box contents window: every item an unidentified gear box can hold, with what the Trade
 * Market lists for it now (Wynnventory's listings: count, prices, roll percentages); or, for any
 * other tradable item, that one item's listings across the whole window. Opened by
 * {@link BoxKey} over the container screen the item is in; Esc goes back to that screen, which
 * stays open behind it, and the window closes when the server closes the container. Information
 * only: nothing is clicked, identified or sent to the server. Requests go through the mod's one
 * Wynnventory worker ({@link Listings}); the rows are rebuilt when an answer arrives, never per frame.
 */
public final class BoxScreen extends Screen {

    /** One item instead of a box: its row, its own overall roll (null if none shown), and whether it is unidentified. */
    record Single(BoxMarket.Item item, Float ownRoll, boolean unidentified) {}

    /** The box; null for one item. */
    final ItemView.Box box;
    /** The one item; null for a box. */
    final Single single;
    /** The hovered stack, the one item's icon. */
    private final net.minecraft.world.item.ItemStack singleIcon;
    private final Screen parent;
    /** "RELIK:LEGENDARY:70-80" or "item:Name": what a refresh is for. */
    private final String boxKey;
    final List<BoxMarket.Item> items;
    final BoxItemsPanel left = new BoxItemsPanel(this);
    final BoxListingsPanel right = new BoxListingsPanel(this);

    BoxMarket.Filter filter = BoxMarket.Filter.ALL;
    BoxMarket.Sort sort = BoxMarket.Sort.ROLL;
    /** The row shown on the right, by name. */
    String selected;

    List<BoxMarket.Row> rows = List.of();
    private BoxMarket.Summary summary;
    private BoxVerdict.Result verdict;
    private String builtFor = "";
    private int verdictFor = -1;

    /** This frame's click areas: {x, y, w, h} and what a click does. */
    record Hit(int x, int y, int w, int h, Runnable action) {}

    final List<Hit> hits = new ArrayList<>();

    public BoxScreen(ItemView.Box box, Screen parent) {
        super(Component.translatable("wynnlabs.box.title"));
        this.box = box;
        this.single = null;
        this.singleIcon = null;
        this.parent = parent;
        this.boxKey = box.slot().name() + ":" + box.tier().name() + ":" + box.low() + "-" + box.high();
        this.items = items(box);
        // the player's own class and level first: those are what they would look at first
        Klass cls = GameState.current().classType();
        int level = GameState.current().level();
        List<String> ask = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (BoxMarket.Item it : items) {
            if (it.tradable()) {
                boolean mine = (it.klass() == null || it.klass() == Klass.NONE || it.klass() == cls)
                        && Math.abs(it.level() - level) <= 10;
                (mine ? ask : rest).add(it.name());
            }
        }
        ask.addAll(rest);
        Listings.request(ask);
    }

    /** The window for one tradable item; an unidentified piece starts on its unidentified listings. */
    BoxScreen(Single one, net.minecraft.world.item.ItemStack icon, Screen parent) {
        super(Component.translatable("wynnlabs.box.title"));
        this.box = null;
        this.single = one;
        this.singleIcon = icon;
        this.parent = parent;
        this.boxKey = "item:" + one.item().name();
        this.items = List.of(one.item());
        this.selected = one.item().name();
        if (one.unidentified()) {
            filter = BoxMarket.Filter.UNID;
        }
        Listings.request(List.of(one.item().name()));
    }

    /** The box's possible items, as Wynntils lists them, each once. */
    static List<BoxMarket.Item> items(ItemView.Box box) {
        List<BoxMarket.Item> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ItemData d : GameState.current().possibleGears(box)) {
            if (seen.add(d.name())) {
                Klass k = d.requirements().klass().orElse(d.slot().klass());
                out.add(new BoxMarket.Item(d.name(), d.slot(), d.tier(), d.level(), k, box.tier().identificationCost(d.level()),
                        Shopping.tradable(d)));
            }
        }
        return out;
    }

    @Override
    protected void init() {
        UiFont.apply(this);
        io.github.anilken.wynnlabs.search.ItemIndex.startLoading(io.github.anilken.wynnlabs.core.Platform.get().gameDir(),
                io.github.anilken.wynnlabs.core.Dirs.config().resolve("chests.json"));
        super.init();
    }

    /** The rows again when an answer came, the filter or the sort changed, or the prices moved. */
    private void rebuild() {
        boolean live = Listings.live();
        if (!rolled()) {
            filter = BoxMarket.Filter.ALL;
            sort = BoxMarket.Sort.PRICE;
        }
        String key = Listings.version + "/" + Prices.version + "/" + filter + "/" + sort + "/" + live;
        if (key.equals(builtFor)) {
            return;
        }
        builtFor = key;
        Map<String, BoxMarket.Held> held = new HashMap<>();
        Map<String, Double> past = new HashMap<>();
        for (BoxMarket.Item it : items) {
            BoxMarket.Held h = Listings.held(it.name());
            if (h != null) {
                held.put(it.name(), h);
            }
            Prices.Price p = it.tradable() ? Prices.of(it.name()) : null;
            if (p != null && p.typical() != null) {
                past.put(it.name(), p.typical());
            }
        }
        rows = BoxMarket.rows(items, held, past, live, filter, sort);
        if (box != null && (verdictFor != Prices.version || verdict == null)) {
            verdictFor = Prices.version;
            verdict = BoxVerdict.of(box, GameState.current().classType(), GameState.current().level());
        }
        summary = BoxMarket.summary(rows, verdict == null ? null : verdict.value());
        if (selected == null && !rows.isEmpty()) {
            selected = rows.get(0).name();
        }
    }

    /** The selected row, or null. */
    BoxMarket.Row selectedRow() {
        for (BoxMarket.Row r : rows) {
            if (r.name().equals(selected)) {
                return r;
            }
        }
        return null;
    }

    @Override
    public void tick() {
        // the server closed the container behind (or opened another): nothing to go back to
        if (parent instanceof AbstractContainerScreen<?> c && minecraft != null
                && (minecraft.player == null || minecraft.player.containerMenu != c.getMenu())) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);        // back to the container, still open; nothing is sent
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- drawing ----

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        MenuScale.render(this, g, mx, my, (x, y) -> draw(g, x, y, pt));
    }

    private int[] closeRect;

    private void draw(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        rebuild();
        hits.clear();
        // a window over the container, not a second menu: about four fifths of the screen at most
        int w = Math.min(width - 20, Math.max(360, Math.min(480, width * 4 / 5)));
        int h = Math.min(height - 20, Math.max(220, Math.min(280, height * 4 / 5)));
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        Kit.window(g, x - 5, y - 5, w + 10, h + 10);
        Kit.paper(g, x, y, w, h);
        int[] cr = closeRect;
        boolean closeHover = cr != null && mx >= cr[0] && mx < cr[0] + cr[2] && my >= cr[1] && my < cr[1] + cr[3];
        closeRect = Kit.closeOutside(g, width, x, y, w, closeHover);
        int in = x + 10;
        int iw = w - 20;
        header(g, in, y + 12, iw, mx, my);      // clear of the frame's top edge
        if (single != null) {
            right.singleChips(g, in, y + 28, single);
        } else {
            chips(g, in, y + 28, iw, mx, my);
        }
        filters(g, in, y + 46, iw, mx, my);
        int top = rolled() ? y + 66 : y + 48;     // no rolls: no filter row, the list right under the chips
        int bottom = y + h - 16;
        g.fill(in, top - 4, in + iw, top - 3, Theme.LINE);
        if (items.isEmpty()) {
            // Wynntils' gear data is not in yet: say so, keep the fee
            ink(g, fit(L.t("wynnlabs.box.unknown_contents"), iw), in, top + 4, Theme.INK_2);
        } else if (single != null) {
            // one item: its stats on the left (what the listings' rolls are out of), its listings on the right
            int leftW = Math.max(150, Math.min(200, iw * 2 / 5));
            BoxStatsPanel.draw(this, g, single.item().name(), in, top, leftW, bottom - top);
            right.draw(g, in + leftW + 8, top, iw - leftW - 8, bottom - top, mx, my);
        } else {
            int leftW = Math.max(180, Math.min(260, iw * 2 / 5));
            left.draw(g, in, top, leftW, bottom - top, mx, my);
            right.draw(g, in + leftW + 8, top, iw - leftW - 8, bottom - top, mx, my);
        }
        String foot = !Listings.live() ? L.t("wynnlabs.box.live_off")
                : single != null ? L.t("wynnlabs.box.footer_item") : L.t("wynnlabs.box.footer");
        // clear of the page's corner ornaments
        small(g, fit(foot, (int) ((iw - 12) / MenuPaint.DESC_SCALE)), in + 6, y + h - 13, Listings.live() ? Theme.INK_3 : Theme.WARN);
    }

    /** Box name, tier, level range, class, fee; on the right the refresh button and its state. */
    private void header(GuiGraphics g, int x, int y, int w, int mx, int my) {
        String refreshText = L.t("wynnlabs.box.refresh");
        int bw = font.width(refreshText) + 12;
        int bx = x + w - bw;
        Listings.Refresh r = Listings.refreshOf(boxKey);
        boolean live = Listings.live();
        boolean running = r != null && r.running();
        boolean hover = mx >= bx && mx < bx + bw && my >= y - 3 && my < y + 11;
        int state = !live || running ? Kit.REST : hover ? Kit.HOVER : Kit.REST;
        Kit.button(g, bx, y - 3, bw, 14, state);
        g.drawString(font, refreshText, bx + 6, y, live && !running ? Kit.buttonText(state) : Theme.CREAM_DIM, live && !running);
        if (live && !running) {
            hits.add(new Hit(bx, y - 3, bw, 14, () -> Listings.refresh(boxKey, tradableNames(), selected)));
        }
        String status = running ? L.t("wynnlabs.box.refreshing", r.done(), r.total()) : updated(r);
        int sw = status.isEmpty() ? 0 : font.width(status);
        if (sw > 0) {
            ink(g, status, bx - 6 - sw, y, running ? Theme.WARN : Theme.INK_3);
        }
        if (single != null) {
            right.singleHeader(g, x, y, bx - 10 - sw - x, single, singleIcon);
            return;
        }
        String name = "Unidentified " + MenuPaint.nice(box.slot().name());
        String klass = items.isEmpty() || items.get(0).klass() == null || items.get(0).klass() == Klass.NONE ? ""
                : " · " + MenuPaint.nice(items.get(0).klass().name());
        int fee = verdict == null ? box.tier().identificationCost(box.high()) : -1;
        String cost = fee >= 0 ? fee + " E"
                : verdict.costLow() == verdict.costHigh() ? verdict.costLow() + " E" : verdict.costLow() + "-" + verdict.costHigh() + " E";
        String rest = " · " + MenuPaint.nice(box.tier().name()) + " · Lv " + box.low() + "-" + box.high() + klass + " · "
                + L.t("wynnlabs.box.identify_cost", cost);
        int room = bx - 10 - sw - x;
        String head = fit(name, room);
        ink(g, head, x, y, HomePart.tierColour(box.tier()));
        int nx = x + font.width(head);
        ink(g, fit(rest, room - font.width(head)), nx, y, Theme.INK_2);
    }

    /** "Güncellendi 2 dk önce": the last refresh, else the newest answer among the box's items. */
    private String updated(Listings.Refresh r) {
        long t = r != null && !r.running() ? r.finishedAt() : 0;
        if (t == 0) {
            for (BoxMarket.Item it : items) {
                t = Math.max(t, Listings.fetchedAt(it.name()));
            }
        }
        return t <= 0 ? "" : L.t("wynnlabs.box.updated", MenuPaint.ago(t));
    }

    List<String> tradableNames() {
        List<String> out = new ArrayList<>();
        for (BoxMarket.Item it : items) {
            if (it.tradable()) {
                out.add(it.name());
            }
        }
        return out;
    }

    /** The summary chips, then the filter and sort chips on the line under them. */
    private void chips(GuiGraphics g, int x, int y, int w, int mx, int my) {
        int cx = x;
        BoxMarket.Summary s = summary;
        cx += chip(g, cx, y, L.t("wynnlabs.box.average", money(s == null ? null : s.average())), Theme.PLAQUE, Theme.INK) + 4;
        cx += chip(g, cx, y, L.t("wynnlabs.box.best", money(s == null ? null : s.best())), Theme.PLAQUE, Theme.INK) + 4;
        if (verdict != null) {
            int fill = switch (verdict.label()) {
                case IDENTIFY -> Theme.SEAL_2;
                case SELL -> Theme.MARK_SELL;
                case KEEP -> Theme.NEAR;
            };
            int vw = chip(g, cx, y, verdict.text(), fill, Theme.SEAL_TEXT);
            if (mx >= cx && mx < cx + vw && my >= y && my < y + 14) {
                // the route, what the word means and why this box got it
                Glossary.draw(g, font, verdict.tipLines().stream().map(Glossary::grey).toList(), mx, my, width);
            }
            cx += vw + 4;
        }
        if (cx < x + w - 60) {
            chip(g, cx, y, fit(L.t("wynnlabs.box.unidentified_value", money(s == null ? null : s.unidentified())), x + w - cx),
                    Theme.PLAQUE, Theme.INK);
        }
    }

    /** Hepsi / Tanımlı / Tanımsız on the left, the sort on the right. */
    private void filters(GuiGraphics g, int x, int fy, int w, int mx, int my) {
        if (!rolled()) {
            return;                             // an ingredient or material: no rolls, no identifying, sorted by price
        }
        int fx = x;
        BoxMarket.Filter[] fs = BoxMarket.Filter.values();
        for (BoxMarket.Filter f : fs) {
            String label = L.t("wynnlabs.box.filter." + f.name().toLowerCase(Locale.ROOT));
            fx += toggle(g, fx, fy, label, filter == f, mx, my, () -> filter = f) + 4;
        }
        String sortLabel = L.t(sort == BoxMarket.Sort.ROLL ? "wynnlabs.box.sort_roll" : "wynnlabs.box.sort_price");
        int sw = font.width(sortLabel) + 12;
        toggle(g, x + w - sw, fy, sortLabel, false, mx, my,
                () -> sort = sort == BoxMarket.Sort.ROLL ? BoxMarket.Sort.PRICE : BoxMarket.Sort.ROLL);
    }

    /**
     * Whether this window's items have rolls: a box (gear), or one piece of gear. Ingredients,
     * materials and the like sell without rolls, so their roll column, the identified filter and
     * the roll sort are left out.
     */
    boolean rolled() {
        if (single == null) {
            return true;
        }
        io.github.anilken.wynnlabs.search.Entry e = io.github.anilken.wynnlabs.search.ItemIndex.get().byName(single.item().name());
        return e != null ? e.kind() == io.github.anilken.wynnlabs.search.Kind.GEAR
                : single.item().slot() != io.github.anilken.wynnlabs.model.Slot.UNKNOWN;
    }

    /** "72%" or "%72", as the language writes it. */
    static String pct(Object v) {
        return L.t("wynnlabs.box.pct", v);
    }

    /** "12 EB", or "?" when unknown (never 0). */
    static String money(Double v) {
        return v == null ? "?" : Prices.fmt(v);
    }

    int chip(GuiGraphics g, int x, int y, String text, int fill, int ink) {
        int cw = font.width(text) + 10;
        Kit.seal(g, x, y, cw, 14, fill);
        g.drawString(font, text, x + 5, y + 3, ink, false);
        return cw;
    }

    /** A wooden chip, gold when on; returns its width. */
    int toggle(GuiGraphics g, int x, int y, String label, boolean on, int mx, int my, Runnable action) {
        int cw = font.width(label) + 12;
        boolean hover = mx >= x && mx < x + cw && my >= y && my < y + 14;
        int state = on ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
        Kit.button(g, x, y, cw, 14, state);
        g.drawString(font, label, x + 6, y + 3, Kit.buttonText(state), state != Kit.ON);
        hits.add(new Hit(x, y, cw, 14, action));
        return cw;
    }

    // ---- text on paper ----

    void ink(GuiGraphics g, String s, int x, int y, int colour) {
        Kit.text(g, font, s, x, y, Theme.paperRgb(colour));
    }

    /** Text at the description size (0.85×). */
    void small(GuiGraphics g, String s, int x, int y, int colour) {
        Component c = Component.literal(s);
        if (Settings.on("webFont")) {
            Kit.text(g, font, c.copy().withStyle(st -> UiFont.sized(st, MenuPaint.DESC_SCALE)), x, y, Theme.paperRgb(colour));
            return;
        }
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(MenuPaint.DESC_SCALE, MenuPaint.DESC_SCALE);
        Kit.text(g, font, c, 0, 0, Theme.paperRgb(colour));
        g.pose().popMatrix();
    }

    /** {@code s} cut to {@code w} px with "…". */
    String fit(String s, int w) {
        if (w <= 0) {
            return "";
        }
        if (font.width(s) <= w) {
            return s;
        }
        return font.plainSubstrByWidth(s, Math.max(0, w - font.width("…"))) + "…";
    }

    net.minecraft.client.gui.Font font() {
        return font;
    }

    /** Text width at the description size. */
    int smallWidth(String s) {
        return (int) Math.ceil(font.width(s) * MenuPaint.DESC_SCALE);
    }

    /** The high-roll threshold (rules boxHighRoll, 80 %). */
    static double highRoll() {
        return Rules.num("boxHighRoll", 80);
    }

    // ---- input ----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        event = MenuScale.event(event);
        double ex = event.x();
        double ey = event.y();
        int[] cr = closeRect;
        if (cr != null && ex >= cr[0] && ex < cr[0] + cr[2] && ey >= cr[1] && ey < cr[1] + cr[3]) {
            onClose();
            return true;
        }
        if (left.mouseClicked(ex, ey) || right.mouseClicked(ex, ey)) {
            return true;
        }
        for (Hit h : List.copyOf(hits)) {
            if (ex >= h.x() && ex < h.x() + h.w() && ey >= h.y() && ey < h.y() + h.h()) {
                h.action().run();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        double f = MenuScale.factor();
        MouseButtonEvent e = MenuScale.event(event);
        if (left.mouseDragged(e.y()) || right.mouseDragged(e.y())) {
            return true;
        }
        return super.mouseDragged(e, dx * f, dy * f);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        left.mouseReleased();
        right.mouseReleased();
        return super.mouseReleased(MenuScale.event(event));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        x = MenuScale.at(x);
        y = MenuScale.at(y);
        if (!left.mouseScrolled(x, y, v)) {
            right.mouseScrolled(x, y, v);
        }
        return true;
    }
}
