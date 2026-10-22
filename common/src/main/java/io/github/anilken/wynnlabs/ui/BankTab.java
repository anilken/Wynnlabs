package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.advice.Advice;
import io.github.anilken.wynnlabs.advice.AutoAdvice;
import io.github.anilken.wynnlabs.advice.TooltipListener;
import io.github.anilken.wynnlabs.advice.Verdict;
import io.github.anilken.wynnlabs.core.DevHooks;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Profile;
import io.github.anilken.wynnlabs.game.WynntilsItems;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.market.Vault;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.ItemView;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The bank tab: the bank as a grid of real items, with filters, pages and a search. */
public final class BankTab extends MenuTab {

    BankTab(RoadmapScreen menu) {
        super(menu);
    }

    // ---- the tab ----

    @Override
    List<EditBox> init() {
        String typed = search == null ? "" : search.getValue();
        search = new EditBox(font(), 0, 0, 150, 12, L.c("wynnlabs.bank.search"));
        search.setMaxLength(40);
        search.setValue(typed);
        search.setResponder(v -> bankScroll = 0);
        paperField(search);
        return List.of(search);
    }

    @Override
    void beginFrame() {
        bankFilterHits.clear();
    }

    @Override
    void draw(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float pt) {
        drawBank(g, x, y, w, h, mx, my, pt);
    }

    @Override
    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (search.mouseClicked(event, doubleClick)) {
            menu.setFocused(search);
            return true;
        }
        for (Object[] f : bankFilterHits) {
            if (in(event.x(), event.y(), (int[]) f[0])) {
                bankFilter = (Integer) f[1];
                bankScroll = 0;
                return true;
            }
        }
        for (Object[] f : bankPageHits) {
            if (in(event.x(), event.y(), (int[]) f[0])) {
                bankPage = (String) f[1];
                bankScroll = 0;
                return true;
            }
        }
        return false;
    }

    @Override
    void mouseScrolled(double x, double y, double v) {
        bankScroll = Math.max(0, Math.min(bankScrollMax, bankScroll - PITCH * (int) Math.signum(v)));
    }

    private static final String[] BANK_FILTERS = {"wynnlabs.bank.filter_all", "wynnlabs.bank.filter_sell", "wynnlabs.bank.filter_quest",
        "wynnlabs.bank.filter_prof", "wynnlabs.bank.filter_gear"};

    // ---- bank tab (Banka): the bank as a grid of real items ----

    /** The search over the bank's items. */
    public EditBox search;
    private int bankFilter;
    private int bankScroll;
    private String bankPage = "";                                       // "" = every item, by kind
    private final List<Object[]> bankFilterHits = new ArrayList<>();   // {int[] rect, Integer filter}
    private final List<Object[]> bankPageHits = new ArrayList<>();     // {int[] rect, String page}
    private static final int SLOT = 24;                 // the bank's slots: big enough to tell items apart
    private static final int PITCH = 26;
    private static final int ICON = 20;                 // the item in a slot, 2px from its edge
    private static final int MARK_SELL = Theme.MARK_SELL;
    private static final int MARK_QUEST = Theme.MARK_QUEST;
    private static final int MARK_IDENTIFY = Theme.MARK_IDENTIFY;
    private static final int MARK_USE = Theme.MARK_USE;

    private void drawBank(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float pt) {
        bankPageHits.clear();
        List<Vault.Group> all = Vault.groups();
        if (all.isEmpty()) {
            emptyState(g, x, y, w, h, menu.railIcon(RoadmapScreen.TAB_BANK), L.t("wynnlabs.bank.empty_title"),
                    L.t("wynnlabs.bank.empty_step1"),
                    L.t("wynnlabs.bank.empty_step2"),
                    L.t("wynnlabs.bank.empty_step3"));
            return;
        }
        List<Vault.Group> junk = Vault.junk();
        java.util.Set<String> junkNames = new java.util.HashSet<>();
        junk.forEach(j -> junkNames.add(j.key()));
        Map<String, Vault.Group> byName = new HashMap<>();
        all.forEach(gr -> byName.put(gr.key(), gr));

        // summary
        long tm = junk.stream().filter(j -> "Trade Market".equals(Vault.place(j))).count();
        double worth = junk.stream().filter(j -> "Trade Market".equals(Vault.place(j)))
                .map(Vault::value).filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).sum();
        Component head = L.cn("wynnlabs.bank.summary", Vault.pages(), Vault.count(), Vault.pages()).withStyle(ChatFormatting.WHITE)
                .append(Component.literal(junk.isEmpty() ? L.t("wynnlabs.bank.nothing_to_sell") : L.n("wynnlabs.bank.kinds_sellable", junk.size()))
                        .withStyle(junk.isEmpty() ? ChatFormatting.GRAY : ChatFormatting.RED))
                .append(Component.literal(junk.isEmpty() ? "" : " (Blacksmith " + (junk.size() - tm) + ", Trade Market " + tm
                        + (worth > 0 ? " ~" + Prices.fmt(worth) : "") + ")").withStyle(ChatFormatting.GRAY));
        int unpriced = 0;
        for (Vault.Group gr : all) {
            String k = gr.first().kind();
            if (gr.reachable() && Vault.value(gr) == null && (k.equals("gear") || k.equals("gearbox") || k.equals("ingredient")
                    || k.equals("material") || k.equals("powder"))) {
                unpriced++;
            }
        }
        if (unpriced > 0) {
            Component u = L.cn("wynnlabs.bank.unpriced", unpriced)
                    .withStyle(ChatFormatting.GOLD);
            int uw = font().width(u);
            if (font().width(head) + uw + 16 <= w) {
                ink(g, u, x + w - uw, y, 0xFFFFFFFF);
            }
        }
        ink(g, head, x, y, 0xFFFFFFFF);

        // search and filters
        int sy = y + 12;
        // the chips' room decides the search box: narrower on a small page, down to 70px
        int chipsW = 0;
        for (String f : BANK_FILTERS) {
            chipsW += font().width(L.t(f)) + 16;
        }
        int searchW = Math.max(70, Math.min(130, w - 8 - chipsW));
        textBox(g, search, x, sy, searchW, "wynnlabs.bank.search_hint", mx, my, pt);
        int fx = x + searchW + 8;
        for (int i = 0; i < BANK_FILTERS.length; i++) {
            boolean on = i == bankFilter;
            String ft = L.t(BANK_FILTERS[i]);
            int fw = font().width(ft) + 12;
            if (fx + fw > x + w) {
                // still too narrow: the rest of the chips on a line of their own
                fx = x;
                sy += 16;
            }
            boolean fh = mx >= fx && mx < fx + fw && my >= sy - 1 && my < sy + 13;
            int state = on ? Kit.ON : fh ? Kit.HOVER : Kit.REST;
            Kit.button(g, fx, sy - 1, fw, 14, state);
            onWood(g, ft, fx + 6, sy + 2, state);
            bankFilterHits.add(new Object[] {new int[] {fx, sy - 1, fw, 14}, i});
            fx += fw + 4;
        }

        // pages: Tümü · Hesap 1 2 3 … · Karakter 1 · Mage 1 2 …
        int py = sy + 17;
        int px = x;
        px += pageChip(g, px, py, L.t("wynnlabs.bank.page_all"), "", mx, my) + 8;
        // one group a bank (each character its own, even when two share a name), a "|" between
        // groups; a group starts a new line rather than leave its label apart from its pages
        List<String> keys = Vault.pageKeys();
        String group = null;
        String groupName = null;
        for (int k = 0; k < keys.size(); k++) {
            String key = keys.get(k);
            String gk = pageGroup(key);
            if (!gk.equals(group)) {
                String name = Vault.bankName(key);
                boolean label = !name.equals(groupName);
                boolean bar = group != null && !label;     // the same name again: a "|" tells them apart
                group = gk;
                groupName = name;
                int labelW = label ? font().width(name) + 4 : bar ? font().width("|") + 6 : 0;
                int gw = labelW;
                for (int j = k; j < keys.size() && pageGroup(keys.get(j)).equals(gk); j++) {
                    gw += font().width(String.valueOf(Vault.pageNumber(keys.get(j)))) + 14;
                }
                int firstW = labelW + font().width(String.valueOf(Vault.pageNumber(key))) + 12;
                // a new line when the whole group fits one, or the label and its first page do not fit this one
                if (px > x && (px + gw > x + w && gw <= w || px + firstW > x + w)) {
                    px = x;
                    py += 16;
                    label = true;                   // a group starting a line says whose it is again
                }
                if (bar && !label) {
                    ink(g, "|", px, py + 3, FAINT);
                    px += font().width("|") + 6;
                }
                if (label) {
                    ink(g, name, px, py + 3, FAINT);
                    px += font().width(name) + 4;
                }
            }
            String num = String.valueOf(Vault.pageNumber(key));
            if (px + font().width(num) + 12 > x + w) {
                px = x;
                py += 16;
            }
            px += pageChip(g, px, py, num, key, mx, my) + 2;
            if (k + 1 < keys.size() && !pageGroup(keys.get(k + 1)).equals(gk)) {
                px += 8;
            }
        }
        if (!bankPage.isEmpty() && !Vault.pageKeys().contains(bankPage)) {
            bankPage = "";
        }

        // the grid
        int gy = py + 19;
        int bottom = y + h - 12;
        if (DevHooks.ON) {
            DevHooks.part("banklist", g.pose(), x, gy, w, bottom - gy);
        }
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        g.enableScissor(x, gy, x + w, bottom);
        int contentH;
        if (bankPage.isEmpty()) {
            contentH = drawAllItems(g, x, gy - bankScroll, w, gy, bottom, all, junkNames, q, mx, my);
        } else {
            contentH = drawOnePage(g, x, gy - bankScroll, w, gy, bottom, bankPage, junkNames, byName, q, mx, my);
        }
        g.disableScissor();
        bankScrollMax = Math.max(0, contentH - (bottom - gy));
        bankScroll = Math.min(bankScroll, bankScrollMax);

        // legend
        int lx = x;
        int ly = y + h - 9;
        lx = legend(g, lx, ly, MARK_SELL, L.t("wynnlabs.bank.legend_sell"));
        lx = legend(g, lx, ly, MARK_QUEST, L.t("wynnlabs.bank.legend_quest"));
        lx = legend(g, lx, ly, MARK_IDENTIFY, L.t("wynnlabs.bank.legend_identify"));
        lx = legend(g, lx, ly, MARK_USE, L.t("wynnlabs.bank.legend_use"));
        String more = bankScrollMax > 0 ? L.t("wynnlabs.screen.scroll_wheel") : "";
        ink(g, more, x + w - font().width(more), ly, FAINT);
    }

    private int bankScrollMax;

    private int legend(GuiGraphics g, int x, int y, int colour, String text) {
        g.fill(x, y + 1, x + 6, y + 7, colour);
        ink(g, text, x + 9, y, MUTED);
        return x + 9 + font().width(text) + 12;
    }

    private int pageChip(GuiGraphics g, int x, int y, String text, String key, int mx, int my) {
        boolean on = bankPage.equals(key);
        int w = font().width(text) + 12;
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 14;
        int state = on ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
        Kit.button(g, x, y, w, 14, state);
        onWood(g, text, x + 6, y + 3, state);
        bankPageHits.add(new Object[] {new int[] {x, y, w, 14}, key});
        return w;
    }

    /** One slot: a sunken square, the item, its count, a coloured frame for the verdict, dimmed if filtered out. */
    private void slot(GuiGraphics g, int x, int y, ItemStack stack, String count, int mark, boolean dim, boolean hover) {
        Kit.slot(g, x, y, SLOT, 0);
        if (DevHooks.ON && stack != null && !dim && menu.tab() == RoadmapScreen.TAB_BANK) {
            DevHooks.part("bankitem", g.pose(), x, y, SLOT, SLOT);
        }
        if (stack != null) {
            int in = (SLOT - ICON) / 2;
            g.pose().pushMatrix();
            g.pose().translate(x + in, y + in);
            g.pose().scale(ICON / 16f, ICON / 16f);
            g.renderItem(stack, 0, 0);
            // the bar and such without the game's count, which sits low enough to spill out of the slot
            g.renderItemDecorations(font(), stack, 0, 0, "");
            g.pose().popMatrix();
            if (count != null) {
                // the count inside the slot, bottom right, 1px in
                g.drawString(font(), count, x + SLOT - 1 - font().width(count), y + SLOT - 9, 0xFFFFFFFF, true);
            }
        }
        if (mark != 0) {
            g.renderOutline(x, y, SLOT, SLOT, mark);
        }
        if (dim) {
            g.fill(x, y, x + SLOT, y + SLOT, 0xB0D8C595);   // faded into the paper
        }
        if (hover) {
            g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0x50FFFFFF);
        }
    }

    /** The bank a page key belongs to: "acct", or "char:" and the character's id. */
    private static String pageGroup(String key) {
        return key.startsWith("char:") && key.indexOf(':', 5) > 0 ? key.substring(0, key.indexOf(':', 5)) : "acct";
    }

    private static String shortCount(int n) {
        // "1k" past 999: a slot has room for three digits
        return n <= 1 ? null : n < 1000 ? String.valueOf(n) : n / 1000 + "k";
    }

    private int markOf(Vault.Group gr, java.util.Set<String> junkNames) {
        if (gr == null) {
            return 0;
        }
        if (junkNames.contains(gr.key())) {
            return MARK_SELL;
        }
        if (!Vault.questsFor(gr).isEmpty()) {
            return MARK_QUEST;
        }
        String v = gr.first().verdict();
        if (v == null) {
            return 0;
        }
        Verdict vv = Verdict.of(v);
        return vv == Verdict.IDENTIFY ? MARK_IDENTIFY : vv == Verdict.WEAR || vv == Verdict.USE ? MARK_USE : 0;
    }

    private boolean matches(Vault.Group gr, java.util.Set<String> junkNames, String q) {
        Vault.Stored st = gr.first();
        boolean keep = switch (bankFilter) {
            case 1 -> junkNames.contains(gr.key());
            case 2 -> !Vault.questsFor(gr).isEmpty();
            case 3 -> !st.prof().isEmpty();
            case 4 -> st.gear() || st.kind().contains("gear");
            default -> true;
        };
        if (keep && !q.isEmpty()) {
            String hay = (gr.name() + " " + Vault.where(gr) + " " + st.kind() + " " + st.prof() + " "
                    + (st.verdict() == null ? "" : Verdict.of(st.verdict()).label())).toLowerCase(Locale.ROOT);
            keep = hay.contains(q);
        }
        return keep;
    }

    /** Every item once (with its total), in sections: to sell, quests, gear, boxes, crafting, the rest. */
    private int drawAllItems(GuiGraphics g, int x, int y, int w, int top, int bottom, List<Vault.Group> all,
            java.util.Set<String> junkNames, String q, int mx, int my) {
        String[] titles = {L.t("wynnlabs.bank.sec_sell"), L.t("wynnlabs.bank.sec_quest"), L.t("wynnlabs.bank.sec_gear"),
            L.t("wynnlabs.bank.sec_boxes"), L.t("wynnlabs.bank.sec_crafting"), L.t("wynnlabs.bank.sec_other")};
        List<List<Vault.Group>> sections = new ArrayList<>();
        for (int i = 0; i < titles.length; i++) {
            sections.add(new ArrayList<>());
        }
        for (Vault.Group gr : all) {
            if (!matches(gr, junkNames, q)) {
                continue;
            }
            Vault.Stored st = gr.first();
            int sec = junkNames.contains(gr.key()) ? 0
                    : !Vault.questsFor(gr).isEmpty() ? 1
                    : st.gear() || st.kind().equals("craftedgear") ? 2
                    : st.kind().equals("gearbox") ? 3
                    : !st.prof().isEmpty() ? 4 : 5;
            sections.get(sec).add(gr);
        }
        int cols = Math.max(1, (w + 2) / PITCH);
        int cy = y;
        boolean any = false;
        for (int i = 0; i < titles.length; i++) {
            List<Vault.Group> list = sections.get(i);
            if (list.isEmpty()) {
                continue;
            }
            any = true;
            int total = list.stream().mapToInt(Vault.Group::total).sum();
            section(g, titles[i] + "  " + (total > list.size() ? L.n("wynnlabs.bank.sec_kinds_items", list.size(), list.size(), total)
                    : String.valueOf(list.size())), x, cy, w);
            cy += 12;
            for (int k = 0; k < list.size(); k++) {
                Vault.Group gr = list.get(k);
                int sx = x + (k % cols) * PITCH;
                int sy = cy + (k / cols) * PITCH;
                boolean visible = sy + SLOT > top && sy < bottom;
                boolean hover = visible && mx >= sx && mx < sx + SLOT && my >= sy && my < sy + SLOT && my >= top && my < bottom;
                slot(g, sx, sy, Vault.stackOf(gr.first()), shortCount(gr.total()), markOf(gr, junkNames), !gr.reachable(), hover);
            if (gr != null && Vault.value(gr) == null) {
                String kd = gr.first().kind();
                if (kd.equals("gear") || kd.equals("gearbox") || kd.equals("ingredient") || kd.equals("material") || kd.equals("powder")) {
                    g.pose().pushMatrix();
                    g.pose().translate(sx + 1, sy + 1);
                    g.pose().scale(0.75f, 0.75f);
                    ink(g, "?", 0, 0, 0xFFFAC775);
                    g.pose().popMatrix();
                }
            }
                if (visible) {
                    addSlotTip(sx, Math.max(sy, top), SLOT, Math.min(sy + SLOT, bottom) - Math.max(sy, top), gr, junkNames, mx, my);
                }
            }
            cy += ((list.size() + cols - 1) / cols) * PITCH + 6;
        }
        if (!any) {
            ink(g, L.c("wynnlabs.bank.no_match").withStyle(ChatFormatting.GRAY), x, cy + 4, 0xFFFFFFFF);
            cy += 16;
        }
        return cy - y;
    }

    /** One bank page laid out as in the game; filtered-out items are dimmed, not hidden. */
    private int drawOnePage(GuiGraphics g, int x, int y, int w, int top, int bottom, String key,
            java.util.Set<String> junkNames, Map<String, Vault.Group> byName, String q, int mx, int my) {
        int[] size = Vault.gridSize();
        int gw = size[0] * PITCH - 2;
        int ox = x + (w - gw) / 2;
        Map<Integer, Vault.Stored> at = new HashMap<>();
        List<Vault.Stored> loose = new ArrayList<>();
        for (Vault.Stored st : Vault.page(key)) {
            if (st.slot() >= 0) {
                at.put(st.slot(), st);
            } else {
                loose.add(st);
            }
        }
        ink(g, Component.literal(Vault.label(key)).withStyle(UiFont::bold), ox, y, 0xFFFFFFFF);
        String n = L.n("wynnlabs.bank.page_stacks", Vault.page(key).size()) + (Vault.page(key).isEmpty() ? L.t("wynnlabs.bank.page_empty") : "");
        ink(g, n, ox + gw - font().width(n), y, MUTED);
        int gy = y + 12;
        int slots = size[0] * size[1];
        for (int i = 0; i < slots; i++) {
            int col = i % size[0];
            int row = i / size[0];
            int menuIndex = row * 9 + col;
            Vault.Stored st = at.get(menuIndex);
            if (st == null && i < loose.size()) {
                st = loose.get(i);              // pages saved before slots were kept: in order
            }
            int sx = ox + col * PITCH;
            int sy = gy + row * PITCH;
            boolean visible = sy + SLOT > top && sy < bottom;
            boolean hover = visible && mx >= sx && mx < sx + SLOT && my >= sy && my < sy + SLOT;
            if (st == null) {
                slot(g, sx, sy, null, null, 0, false, false);
                continue;
            }
            Vault.Group gr = byName.get(st.key());
            boolean on = gr == null || matches(gr, junkNames, q);
            slot(g, sx, sy, Vault.stackOf(st), shortCount(st.count()), on ? markOf(gr, junkNames) : 0, !on, hover);
            if (gr != null && Vault.value(gr) == null) {
                String kd = gr.first().kind();
                if (kd.equals("gear") || kd.equals("gearbox") || kd.equals("ingredient") || kd.equals("material") || kd.equals("powder")) {
                    g.pose().pushMatrix();
                    g.pose().translate(sx + 1, sy + 1);
                    g.pose().scale(0.75f, 0.75f);
                    ink(g, "?", 0, 0, 0xFFFAC775);
                    g.pose().popMatrix();
                }
            }
            if (visible && gr != null) {
                addSlotTip(sx, Math.max(sy, top), SLOT, Math.min(sy + SLOT, bottom) - Math.max(sy, top), gr, junkNames, mx, my);
            }
        }
        return 12 + size[1] * PITCH;
    }

    /**
     * A box: its levels and tier, which of your characters it suits, and the best that can come
     * out for them (by the rules' score) with market prices, so you know whether to identify it.
     */
    private void boxLines(ItemView.Box box, List<Component> tip, boolean outcomesToo) {
        if (box == null) {
            return;
        }
        int low = box.low();
        int high = box.high();
        tip.add(L.c("wynnlabs.bank.box_head", nice(box.slot().name()), nice(box.tier().name()), low, high)
                .withStyle(box.tier().colour(), ChatFormatting.BOLD));
        // your characters this box is for: right class, levels from 15 below to 5 above them
        java.util.Set<String> mine = new java.util.LinkedHashSet<>();
        List<String> forWho = new ArrayList<>();
        try {
            for (var e : Profile.list().getAsJsonArray("characters")) {
                var o = e.getAsJsonObject();
                String cls = o.get("class").getAsString();
                int lv = o.get("level").getAsInt();
                boolean classFits = false;
                for (var info : WynntilsItems.possibleGears(box)) {
                    var c = info.requirements().klass().orElse(info.slot().klass());
                    if (c == null || c.name().equals("NONE") || c.name().equals(cls)) {
                        classFits = true;
                        break;
                    }
                }
                if (classFits && high >= lv - 15 && low <= lv + 5) {
                    mine.add(cls);
                    forWho.add(nice(cls) + " Lv. " + lv);
                }
            }
        } catch (Throwable ignored) {
        }
        tip.add(forWho.isEmpty()
                ? L.c("wynnlabs.bank.box_fits_none").withStyle(ChatFormatting.RED)
                : L.c("wynnlabs.bank.box_fits", String.join(", ", forWho)).withStyle(ChatFormatting.GREEN));
        // every outcome by level, as Wynntils shows it in the bank: identify cost, names, best price
        List<ItemData> outcomes = new ArrayList<>(WynntilsItems.possibleGears(box));
        if (!outcomesToo || outcomes.isEmpty()) {
            tip.add(Component.empty());
            return;
        }
        java.util.TreeMap<Integer, List<ItemData>> byLevel = new java.util.TreeMap<>();
        outcomes.forEach(i -> byLevel.computeIfAbsent(i.requirements().level(), k -> new ArrayList<>()).add(i));
        tip.add(L.c("wynnlabs.bank.box_outcomes", outcomes.size()).withStyle(ChatFormatting.GREEN));
        int lines = 0;
        for (var e : byLevel.entrySet()) {
            if (lines++ == 10) {
                tip.add(L.cn("wynnlabs.bank.box_more_levels", byLevel.size() - 10).withStyle(ChatFormatting.DARK_GRAY));
                break;
            }
            int cost = box.tier().identificationCost(e.getKey());
            Double best = null;
            List<String> names = new ArrayList<>();
            for (ItemData info : e.getValue()) {
                var c = info.requirements().klass().orElse(info.slot().klass());
                boolean forMe = mine.isEmpty() || c == null || c.name().equals("NONE") || mine.contains(c.name());
                names.add((forMe ? "" : "·") + info.name());
                Double v = unidPrice(info.name());
                if (v != null && (best == null || v > best)) {
                    best = v;
                }
            }
            tip.add(Component.literal("  – Lv. " + e.getKey() + " ").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal("[" + cost + " E]").withStyle(ChatFormatting.DARK_GREEN))
                    .append(Component.literal(": " + fit(String.join(", ", names), 200)).withStyle(box.tier().colour()))
                    .append(Component.literal(best == null ? "" : " (" + Prices.fmt(best) + ")").withStyle(ChatFormatting.GOLD)));
        }
        // the three dearest outcomes: what identifying could be worth
        List<String> dear = outcomes.stream()
                .filter(i -> unidPrice(i.name()) != null)
                .sorted((a, b) -> Double.compare(unidPrice(b.name()), unidPrice(a.name())))
                .limit(3).map(i -> i.name() + " ~" + Prices.fmt(unidPrice(i.name()))).toList();
        if (!dear.isEmpty()) {
            tip.add(L.c("wynnlabs.bank.box_market", String.join(", ", dear)).withStyle(ChatFormatting.GOLD));
        }
        tip.add(Component.empty());
    }

    /** Lowest Trade Market listing of an identified item (what you'd sell it for), or null. */
    private static Double unidPrice(String name) {
        Prices.Price p = Prices.of(name);
        return p == null ? null : p.typical();
    }

    /** The item's own tooltip, then where it is kept, the rules' verdict, quests, crafting and price. */
    /**
     * The slot's tooltip is the item's own, drawn by Wynntils like in the bank (possible items,
     * stat ranges, …); the mod's lines for it (where, verdict, quests, crafting, price) are
     * handed to the tooltip through {@link Vault#tipExtra}. Built only for the hovered slot.
     */
    private void addSlotTip(int x, int y, int w, int h, Vault.Group gr, java.util.Set<String> junkNames, int mx, int my) {
        if (h <= 0) {
            return;
        }
        Vault.Stored st = gr.first();
        ItemStack stack = Vault.stackOf(st);
        menu.hits.add(new MenuHit(x, y, w, h, stack, null, gr.name()));
        if (!(mx >= x && mx < x + w && my >= y && my < y + h)) {
            return;
        }
        List<Component> tip = new ArrayList<>();
        tip.add(Component.empty());
        if (st.verdict() != null) {
            boolean sell = junkNames.contains(gr.key());
            String where = sell ? Vault.place(gr) : null;
            tip.add(Component.literal(TooltipListener.marker()).withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(Component.literal(Verdict.of(st.verdict()).label() + (where == null ? "" : " → " + where))
                            .withStyle(sell ? ChatFormatting.RED : ChatFormatting.GREEN).withStyle(UiFont::bold))
                    .append(Component.literal(st.by() == null ? "" : L.t("wynnlabs.bank.tip_checked_by", AutoAdvice.nice(st.by())))
                            .withStyle(ChatFormatting.DARK_GRAY)));
            String note = Vault.noteNow(st);
            if (note != null) {
                for (String l : Advice.wrap(note, 40)) {
                    tip.add(Component.literal(l).withStyle(ChatFormatting.GRAY));
                }
            }
        }
        // a box Wynntils could not read: its levels and outcomes from what was saved
        boolean known = WynntilsItems.known(stack);
        if (Vault.boxOf(st) != null) {
            boxLines(Vault.boxOf(st), tip, !known);
        }
        tip.add(Component.literal("▣ " + (gr.total() > 1 ? "×" + gr.total() + " · " : "") + Vault.where(gr))
                .withStyle(ChatFormatting.DARK_AQUA));
        if (!gr.reachable()) {
            tip.add(L.c("wynnlabs.bank.tip_other_char").withStyle(ChatFormatting.DARK_GRAY));
        }
        for (String qn : Vault.questsFor(gr)) {
            tip.add(L.c("wynnlabs.bank.tip_quest", qn).withStyle(ChatFormatting.AQUA));
        }
        String prof = Vault.professions(st);
        if (prof != null) {
            tip.add(L.c("wynnlabs.bank.tip_prof", prof).withStyle(ChatFormatting.GREEN));
        }
        Double v = Vault.value(gr);
        if (v != null && gr.total() > 1) {
            tip.add(L.c("wynnlabs.bank.tip_all_worth", Prices.fmt(v)).withStyle(ChatFormatting.GOLD));
        }
        tip.add(L.c("wynnlabs.bank.click_copy").withStyle(ChatFormatting.DARK_GRAY));
        Vault.setTipExtra(stack, tip);
    }
}
