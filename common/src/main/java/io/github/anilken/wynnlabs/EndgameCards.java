package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.AspectEntry;
import io.github.anilken.wynnlabs.model.MythicFind;
import io.github.anilken.wynnlabs.model.RaidDry;
import io.github.anilken.wynnlabs.model.RankedAspect;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * The Endgame tab's cards: the dry card (since the last mythic, raid and lootrun halves), the
 * aspect card, and the compact layout's three small cards with their section lines.
 */
final class EndgameCards extends EndgamePanel {

    EndgameCards(EndgameTab tab) {
        super(tab);
    }

    // ---- the dry card ----

    /** Since the last mythic: the raid half (Wynntils' account counters) and the lootrun half (this character). */
    void dryCard(GuiGraphics g, int x, int y, int w, int h, boolean large, int mx, int my) {
        Kit.card(g, x, y, w, h, Theme.TIER_MYTHIC);
        // each label's glyph is ICON tall, 2 px above its line; the label starts 3 px after it
        int gap = ICON + 3;
        mythicGlyph(g, x + 6, y + 4, ICON);
        ink(g, fit(L.t("wynnlabs.endgame.dry_head"), w - 12 - gap), x + 6 + gap, y + 6, Theme.INK_2);
        int mid = x + w / 2;
        g.fill(mid, y + 18, mid + 1, y + h - 5, Theme.LINE);
        // the raid half
        int hx = x + 6;
        int hw = mid - 5 - hx;
        glyph(g, TEX_RAID, "sword", hx, y + 16, ICON, Theme.INK_2);
        ink(g, fit(L.t("wynnlabs.endgame.raid"), hw - gap), hx + gap, y + 18, Theme.INK_2);
        String[] rv = raidDryValue();
        value(g, rv[0], rv[1], hx, y + 30, hw, tab.d.raidsUp() ? Theme.TIER_MYTHIC : Theme.WARN);
        ink(g, fit(rv[2], hw), hx, y + 42, Theme.INK_3);
        if (large) {
            ink(g, fit(L.t("wynnlabs.endgame.dry_account"), hw), hx, y + 54, Theme.INK_3);
        }
        tab.tips.add(new Tip(x, y, mid - x, h, raidDryTip()));
        // the lootrun half
        int lx = mid + 6;
        int lw = x + w - 6 - lx;
        glyph(g, TEX_CAMP, "map-pin", lx, y + 16, ICON, Theme.INK_2);
        ink(g, fit(L.t("wynnlabs.endgame.lootrun"), lw - gap), lx + gap, y + 18, Theme.INK_2);
        String[] lv = lootDryValue();
        value(g, lv[0], lv[1], lx, y + 30, lw, tab.d.lootUp() ? Theme.TIER_MYTHIC : Theme.WARN);
        if (tab.d.lootUp() && !tab.d.mythics().isEmpty()) {
            mythicGlyph(g, lx, y + 40, ICON);
            ink(g, fit(lv[2], lw - gap), lx + gap, y + 42, Theme.INK_3);
        } else {
            ink(g, fit(lv[2], lw), lx, y + 42, Theme.INK_3);
        }
        if (large && tab.d.accountDry() >= 0) {
            ink(g, fit(L.n("wynnlabs.endgame.dry_account_pulls", tab.d.accountDry()), lw), lx, y + 54, Theme.INK_3);
        }
        tab.tips.add(new Tip(mid, y, x + w - mid, h, lootDryTip()));
    }

    /** The raid half's {value, value with numbers only, line under it}. */
    private String[] raidDryValue() {
        RaidDry r = tab.d.dry();
        if (!tab.d.raidsUp()) {
            return new String[] {L.t("wynnlabs.endgame.off"), L.t("wynnlabs.endgame.off"), L.t("wynnlabs.endgame.off_wynntils")};
        }
        String tome = r.tomePulls() >= 0 ? L.n("wynnlabs.endgame.dry_tome", r.tomePulls()) : L.t("wynnlabs.endgame.dry_tome_unknown");
        if (r.aspectPulls() < 0 && r.aspectRaids() < 0) {
            return new String[] {L.t("wynnlabs.endgame.unknown"), L.t("wynnlabs.endgame.unknown"),
                r.tomePulls() >= 0 ? tome : L.t("wynnlabs.endgame.after_first_raid")};
        }
        return new String[] {L.t("wynnlabs.endgame.dry_raid_value", num(r.aspectPulls()), num(r.aspectRaids())),
            num(r.aspectPulls()) + " · " + num(r.aspectRaids()), tome};
    }

    /** The lootrun half's {value, value with numbers only, line under it}. */
    private String[] lootDryValue() {
        if (!tab.d.lootUp()) {
            return new String[] {L.t("wynnlabs.endgame.off"), L.t("wynnlabs.endgame.off"), L.t("wynnlabs.endgame.off_wynntils")};
        }
        if (tab.d.lootruns().isEmpty()) {
            return new String[] {L.t("wynnlabs.endgame.no_runs"), L.t("wynnlabs.endgame.no_runs"),
                L.t("wynnlabs.endgame.after_first_lootrun")};
        }
        String sub = tab.d.mythics().isEmpty() ? L.t("wynnlabs.endgame.no_mythic")
                : L.n("wynnlabs.endgame.mythic_count", tab.d.mythics().size(), tab.d.mythics().size(), tab.d.mythics().get(0).name());
        return new String[] {L.t("wynnlabs.endgame.dry_loot_value", tab.d.dryPulls(), tab.d.dryRuns()),
            tab.d.dryPulls() + " · " + tab.d.dryRuns(), sub};
    }

    /** A bold value; when it is too long, its numbers alone (numbers are never cut). */
    private void value(GuiGraphics g, String full, String numbers, int x, int y, int w, int colour) {
        Component c = bold(full);
        if (tab.font.width(c) > w) {
            c = bold(numbers);
        }
        Kit.text(g, tab.font, c, x, y, colour);
    }

    private List<Component> raidDryTip() {
        List<Component> t = new ArrayList<>();
        t.add(title(L.t("wynnlabs.endgame.tip_raid_dry_head"), ChatFormatting.LIGHT_PURPLE));
        if (!tab.d.raidsUp()) {
            t.add(warn(L.t("wynnlabs.endgame.raids_off")));
            return t;
        }
        RaidDry r = tab.d.dry();
        t.add(grey(L.t("wynnlabs.endgame.tip_dry_aspect", num(r.aspectPulls()), num(r.aspectRaids()))));
        t.add(grey(L.t("wynnlabs.endgame.tip_dry_tome", num(r.tomePulls()), num(r.tomeRaids()))));
        t.add(dark(L.t("wynnlabs.endgame.tip_account")));
        return t;
    }

    private List<Component> lootDryTip() {
        List<Component> t = new ArrayList<>();
        t.add(title(L.t("wynnlabs.endgame.tip_loot_dry_head"), ChatFormatting.LIGHT_PURPLE));
        if (!tab.d.lootUp()) {
            t.add(warn(L.t("wynnlabs.endgame.lootruns_off")));
            return t;
        }
        t.add(grey(L.t("wynnlabs.endgame.tip_loot_dry", tab.d.dryRuns(), tab.d.dryPulls())));
        if (tab.d.accountDry() >= 0) {
            t.add(dark(L.n("wynnlabs.endgame.tip_loot_account", tab.d.accountDry())));
        }
        if (tab.d.mythics().isEmpty()) {
            t.add(grey(L.t("wynnlabs.endgame.no_mythic")));
        } else {
            t.add(grey(L.t("wynnlabs.endgame.tip_mythics", tab.d.mythics().size())));
            for (MythicFind m : tab.d.mythics()) {
                t.add(Component.literal("  ◆ ").withStyle(ChatFormatting.DARK_PURPLE)
                        .append(Component.literal(m.name()).withStyle(ChatFormatting.LIGHT_PURPLE))
                        .append(Component.literal(" · " + agoLong(m.at())).withStyle(ChatFormatting.GRAY)));
            }
        }
        return t;
    }

    // ---- the aspect card ----

    /** The next aspect for the build, or the one picked from the list, with a control back to the recommendation. */
    void aspectCard(GuiGraphics g, int x, int y, int w, int h, boolean large, int mx, int my) {
        RankedAspect a = tab.pinned != null ? AspectBook.byName(tab.pinned) : tab.d.next();
        int accent = a != null && (tab.d.known() || tab.pinned != null) ? tierColour(a.aspect().rarity()) : Theme.GOLD_DEEP;
        Kit.card(g, x, y, w, h, accent);
        int sx = x + 6;
        int sy = y + (h - 28) / 2 + 1;
        int tx = x + 40;
        int tw = x + w - 6 - tx;
        if (!tab.d.aspectsUp() || !tab.d.dataReady() || a == null || !tab.d.known() && tab.pinned == null) {
            // nothing to recommend yet: say what to do in game, with the game's Aspects button when unread
            Kit.slot(g, sx, sy, 28, 0);
            String[] lines;
            if (!tab.d.aspectsUp()) {
                Art.icon(g, "alert-triangle", sx + 6, sy + 6, 16, Theme.WARN);
                lines = new String[] {L.t("wynnlabs.endgame.aspects_off"), L.t("wynnlabs.endgame.off_wynntils")};
            } else if (!tab.d.dataReady()) {
                Art.icon(g, "clock", sx + 6, sy + 6, 16, Theme.INK_3);
                lines = new String[] {L.t("wynnlabs.endgame.aspects_no_data"), L.t("wynnlabs.endgame.aspects_no_data_2")};
            } else if (!tab.d.known()) {
                if (!WynnTex.drawTrimmed(g, TEX_ASPECTS_BUTTON, sx + 2, sy + 2, 24)) {
                    Art.icon(g, "hierarchy", sx + 6, sy + 6, 16, Theme.INK_2);
                }
                lines = new String[] {L.t("wynnlabs.endgame.aspects_unread"), L.t("wynnlabs.endgame.aspects_unread_2"),
                    L.t("wynnlabs.endgame.aspects_unread_3")};
            } else {
                Art.icon(g, "check", sx + 6, sy + 6, 16, Theme.GOOD);
                lines = new String[] {L.t("wynnlabs.endgame.aspects_all_done")};
            }
            int ly = y + 6;
            for (int i = 0; i < lines.length; i++) {
                ink(g, fit(lines[i], tw), tx, ly, i == 0 ? Theme.INK : Theme.INK_3);
                ly += 12;
            }
            return;
        }
        int ring = tierColour(a.aspect().rarity());
        Kit.slot(g, sx, sy, 28, ring);
        // the aspect's own icon filling the slot, as the list and the Pools view draw it (the
        // pack's 48 px picture holds a 16 px crystal: drawn whole into the slot it was a dot)
        aspectGlyph(g, a.aspect(), sx + 2, sy + 2, 24);
        // line 1: the label, the tier pips and, after a pick, the way back to the recommendation
        int right = x + w - 6;
        if (tab.pinned != null) {
            int bx = x + w - 18;
            int by = y + 4;
            boolean hover = mx >= bx && mx < bx + 12 && my >= by && my < by + 12;
            Kit.button(g, bx, by, 12, 12, hover ? Kit.HOVER : Kit.REST);
            Art.icon(g, "refresh", bx + 2, by + 2, 8, Theme.CREAM);
            tab.acts.add(new Act(bx - 2, y + 2, 16, 15, tab::unpin, "endgame:back"));
            tab.tips.add(new Tip(bx - 2, y + 2, 16, 15, List.of(grey(L.t("wynnlabs.endgame.back_tip")))));
            right = bx - 4;
        }
        int pw = pipsW(a.aspect().tiers());
        pips(g, right - pw, y + 7, a.aspect().tiers(), a.ownedTier(), ring);
        String label = L.t(tab.pinned != null ? "wynnlabs.endgame.card_selected" : "wynnlabs.endgame.card_next");
        ink(g, fit(label, right - pw - 6 - tx), tx, y + 6, Theme.INK_2);
        // the name in bold, measured in bold; on the large card a long one takes two lines (the
        // fifth line gives way), else it is cut with "…" inside the card
        List<MutableComponent> title = boldLines(a.aspect().name(), tw, large ? 2 : 1);
        int ny = y + 17;
        for (MutableComponent line : title) {
            Kit.text(g, tab.font, line, tx, ny, ring);
            ny += 10;
        }
        int shift = Math.max(0, title.size() - 1) * 10;
        String reason = a.reason().isEmpty() ? L.t("wynnlabs.endgame.reason_none") : a.reason();
        ink(g, fit(reason, tw), tx, y + 29 + shift, Theme.INK);
        // the source, and its pool mark when the pool shown holds it: from Wynnventory, the raids
        // holding it this week (full names when they fit, else short ones)
        String name = a.aspect().name();
        String tag = tagText(name);
        int tagW = tag == null ? 0 : Kit.Ui.tagW(tab.font, tag) + 4;
        String src;
        String fullRaids = null;
        if (tab.d.p().wynnventory()) {
            List<String> held = RaidPool.raidsHolding(name);
            if (held == null || held.isEmpty()) {
                src = L.t("wynnlabs.endgame.source_not_in_pool");
            } else {
                src = L.t("wynnlabs.endgame.source_week", String.join(", ", held));
                if (tab.font.width(src) > tw - tagW) {
                    List<String> shorts = new ArrayList<>();
                    held.forEach(r -> shorts.add(RaidPool.shortName(r)));
                    src = L.t("wynnlabs.endgame.source_week", String.join(", ", shorts));
                    fullRaids = String.join(", ", held);
                }
            }
        } else {
            src = L.t("wynnlabs.endgame.source_raid") + (RaidPool.holds(name) ? L.t("wynnlabs.endgame.source_pool") : "");
        }
        String srcFit = fit(src, tw - tagW);
        ink(g, srcFit, tx, y + 41 + shift, Theme.INK_3);
        if (tag != null) {
            tag(g, tag, tx + tab.font.width(srcFit) + 4, y + 40 + shift);
        }
        if (large && shift == 0) {
            // the fifth line: the raids' full names when line four had to shorten them
            ink(g, fit(fullRaids != null ? fullRaids : nextTierText(a), tw), tx, y + 53, Theme.INK_3);
        }
        tab.tips.add(new Tip(x, y, (tab.pinned != null ? x + w - 20 : x + w) - x, h, tab.aspectList.aspectTip(a)));
    }

    /** "Next tier: 5 copies" from the thresholds, or that it is at its top tier. */
    private String nextTierText(RankedAspect a) {
        AspectEntry e = a.aspect();
        int owned = Math.max(0, a.ownedTier());
        if (a.ownedTier() >= e.tiers()) {
            return L.t("wynnlabs.endgame.tier_max");
        }
        if (owned < e.thresholds().size()) {
            return L.n("wynnlabs.endgame.tier_next", e.thresholds().get(owned), owned + 1, e.thresholds().get(owned));
        }
        return "";
    }

    // ---- compact cards ----

    /** One of the compact layout's three cards: its answer in two lines; a click opens its section. */
    void smallCard(GuiGraphics g, int i, int x, int y, int w, int h, int mx, int my) {
        boolean lit = tab.section == i;
        RankedAspect a = i == EndgameTab.SEC_ASPECTS ? (tab.pinned != null ? AspectBook.byName(tab.pinned) : tab.d.next()) : null;
        int accent = i == EndgameTab.SEC_ASPECTS ? (a != null && tab.d.known() ? tierColour(a.aspect().rarity()) : Theme.GOLD_DEEP) : Theme.TIER_MYTHIC;
        Kit.card(g, x, y, w, h, accent, lit);
        if (lit) {
            // a gold tip under the lit card, like the rail's
            int cx = x + w / 2;
            for (int k = 0; k < 4; k++) {
                g.fill(cx - 3 + k, y + h + k, cx + 4 - k, y + h + k + 1, Theme.GOLD_DEEP);
            }
        }
        int tx = x + 6;
        int tw = w - 12;
        int areaW = w;
        List<Component> tip;
        switch (i) {
            case EndgameTab.SEC_RAIDS -> {
                glyph(g, TEX_RAID, "sword", tx, y + 3, ICON, Theme.INK_2);
                ink(g, fit(L.t("wynnlabs.endgame.raid"), tw - ICON - 4), tx + ICON + 4, y + 5, Theme.INK_2);
                String v = !tab.d.raidsUp() ? L.t("wynnlabs.endgame.off")
                        : tab.d.dry().aspectPulls() >= 0 ? L.n("wynnlabs.endgame.pulls_n", tab.d.dry().aspectPulls()) : L.t("wynnlabs.endgame.unknown");
                value(g, v, tab.d.dry().aspectPulls() >= 0 ? String.valueOf(tab.d.dry().aspectPulls()) : v, tx, y + 17, tw,
                        tab.d.raidsUp() ? Theme.TIER_MYTHIC : Theme.WARN);
                tip = new ArrayList<>(raidDryTip());
            }
            case EndgameTab.SEC_LOOTRUNS -> {
                glyph(g, TEX_CAMP, "map-pin", tx, y + 3, ICON, Theme.INK_2);
                ink(g, fit(L.t("wynnlabs.endgame.lootrun"), tw - ICON - 4), tx + ICON + 4, y + 5, Theme.INK_2);
                String v = !tab.d.lootUp() ? L.t("wynnlabs.endgame.off")
                        : tab.d.lootruns().isEmpty() ? L.t("wynnlabs.endgame.no_runs") : L.n("wynnlabs.endgame.pulls_n", tab.d.dryPulls());
                value(g, v, tab.d.lootruns().isEmpty() ? v : String.valueOf(tab.d.dryPulls()), tx, y + 17, tw,
                        tab.d.lootUp() ? Theme.TIER_MYTHIC : Theme.WARN);
                tip = new ArrayList<>(lootDryTip());
            }
            default -> {
                if (tab.pinned != null && a != null) {
                    // the way back to the recommendation, beside the card's own click area
                    int bx = x + w - 15;
                    boolean hover = mx >= bx && mx < bx + 12 && my >= y + 3 && my < y + 15;
                    Kit.button(g, bx, y + 3, 12, 12, hover ? Kit.HOVER : Kit.REST);
                    Art.icon(g, "refresh", bx + 2, y + 5, 8, Theme.CREAM);
                    tab.acts.add(new Act(x + w - 16, y, 16, h, tab::unpin, "endgame:back"));
                    tab.tips.add(new Tip(x + w - 16, y, 16, h, List.of(grey(L.t("wynnlabs.endgame.back_tip")))));
                    areaW = w - 16;
                    tw = w - 12 - 16;
                }
                if (a != null && tab.d.known() || a != null && tab.pinned != null) {
                    int ring = tierColour(a.aspect().rarity());
                    aspectGlyph(g, a.aspect(), tx, y + 3, ICON);
                    ink(g, fit(L.t(tab.pinned != null ? "wynnlabs.endgame.card_selected" : "wynnlabs.endgame.card_next"), tw - ICON - 4),
                            tx + ICON + 4, y + 5, Theme.INK_2);
                    Kit.text(g, tab.font, boldFit(a.aspect().name(), tw), tx, y + 17, ring);
                    tip = new ArrayList<>(tab.aspectList.aspectTip(a));
                } else {
                    if (!WynnTex.drawTrimmed(g, TEX_ASPECTS_BUTTON, tx, y + 3, ICON)) {
                        Art.icon(g, "hierarchy", tx, y + 3, ICON, Theme.INK_2);
                    }
                    ink(g, fit(L.t("wynnlabs.endgame.aspects"), tw - ICON - 4), tx + ICON + 4, y + 5, Theme.INK_2);
                    String v = !tab.d.aspectsUp() ? L.t("wynnlabs.endgame.off") : !tab.d.dataReady() ? L.t("wynnlabs.endgame.unknown")
                            : L.t("wynnlabs.endgame.open_aspects");
                    ink(g, fit(v, tw), tx, y + 17, tab.d.aspectsUp() ? Theme.INK_3 : Theme.WARN);
                    tip = new ArrayList<>(List.of(title(L.t("wynnlabs.endgame.aspects"), ChatFormatting.AQUA),
                            grey(!tab.d.aspectsUp() ? L.t("wynnlabs.endgame.aspects_off") : !tab.d.dataReady()
                                    ? L.t("wynnlabs.endgame.aspects_no_data") : L.t("wynnlabs.endgame.aspects_unread_tip"))));
                }
            }
        }
        if (!lit) {
            tip.add(dark(L.t("wynnlabs.endgame.card_click")));
        }
        int sec = i;
        tab.acts.add(new Act(x, y, areaW, h, () -> tab.section = sec, "endgame:section" + i));
        tab.tips.add(new Tip(x, y, areaW, h, tip));
    }

    /** The compact raid section's dry lines in full words (the card only has room for the number); returns the y under them. */
    int dryLines(GuiGraphics g, int x, int y, int w, boolean compact) {
        if (!tab.d.raidsUp()) {
            return y;
        }
        RaidDry r = tab.d.dry();
        String a = r.aspectPulls() < 0 && r.aspectRaids() < 0 ? L.t("wynnlabs.endgame.line_aspect_unknown")
                : L.t("wynnlabs.endgame.line_aspect", num(r.aspectPulls()), num(r.aspectRaids()));
        String t = r.tomePulls() < 0 && r.tomeRaids() < 0 ? L.t("wynnlabs.endgame.line_tome_unknown")
                : L.t("wynnlabs.endgame.line_tome", num(r.tomePulls()), num(r.tomeRaids()));
        ink(g, fit(a, w), x, y, Theme.INK_2);
        ink(g, fit(t, w), x, y + 12, Theme.INK_2);
        tab.tips.add(new Tip(x, y, w, 23, raidDryTip()));
        return y + 24;
    }

    /** The compact lootrun section's dry line; returns the y under it. */
    int lootDryLine(GuiGraphics g, int x, int y, int w) {
        if (!tab.d.lootUp()) {
            ink(g, fit(L.t("wynnlabs.endgame.lootruns_off"), w), x, y, Theme.WARN);
            return y + 12;
        }
        if (tab.d.lootruns().isEmpty()) {
            return y;
        }
        String line = L.t("wynnlabs.endgame.line_loot", tab.d.dryPulls(), tab.d.dryRuns());
        String m = tab.d.mythics().isEmpty() ? "" : L.n("wynnlabs.endgame.mythic_n", tab.d.mythics().size());
        int mw = m.isEmpty() ? 0 : tab.font.width(m) + ROW_ICON + 6;
        String lf = fit(line, w - mw);
        ink(g, lf, x, y, Theme.INK_2);
        if (!m.isEmpty()) {
            int gx = x + tab.font.width(lf) + 4;
            mythicGlyph(g, gx, y - 1, ROW_ICON);
            ink(g, m, gx + ROW_ICON + 2, y, Theme.TIER_MYTHIC);
        }
        tab.tips.add(new Tip(x, y, w, 11, lootDryTip()));
        return y + 12;
    }
}
