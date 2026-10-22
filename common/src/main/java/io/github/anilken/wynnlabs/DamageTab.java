package io.github.anilken.wynnlabs;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** The damage tab: the model against the damage meter, the live DPS and what each stat would add. */
final class DamageTab extends MenuTab {

    DamageTab(RoadmapScreen menu) {
        super(menu);
    }

    // ---- the tab ----

    @Override
    void draw(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float pt) {
        drawDamage(g, x, y, w, h, mx, my);
    }

    /** Its switches and buttons are the settings' kind of click ({@link SettingsTab#toggleClicked}). */
    @Override
    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return menu.settings.toggleClicked(event, doubleClick);
    }

    @Override
    boolean sharedClicks() {
        return false;
    }

    // ---- damage tab (Hasar) ----

    /** Spells, main attack, summons; then the elements in Damage.ELEMENTS order. */
    private static final int[] SOURCE_COLOURS = {0xFF7F77DD, 0xFFEF9F27, 0xFF1D9E75};
    private static final int[] ELEMENT_COLOURS = {0xFFFAC775, 0xFF5DA548, 0xFFF4E04D, 0xFF55C8E8, 0xFFE8584A, 0xFFA8A090};

    /** A gain or share in % with one decimal below 10 ("%4,5", "%12"). */
    private static String pctOf(double v) {
        return RoadmapScreen.pct(Math.abs(v) < 0.05 ? "0" : Math.abs(v) < 10 ? dec(v, 1) : String.valueOf(Math.round(v)));
    }

    /**
     * The Damage tab: the model's damage per cast next to what the damage meter measured, the live
     * DPS meter, where the build's DPS comes from (spells, main attack, summons; per element), and
     * what one more step of each stat would add to it, best first.
     */
    private void drawDamage(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        DamageReport.Report report = DamageReport.get();
        if (report == null) {
            ink(g, L.c("wynnlabs.damage.no_plan").withStyle(ChatFormatting.GRAY), x, y + 4, 0xFFFFFFFF);
            return;
        }
        int gap = 8;
        // compact: the measured card leaves its note to the tooltip, to give the what-ifs rows
        boolean slim = menu.layout.compact();
        int topH = slim ? 57 : 68;
        int lw = (w - gap) * 56 / 100;
        drawMeasured(g, x, y, lw, topH, mx, my);
        drawLive(g, x + lw + gap, y, w - lw - gap, topH, mx, my);

        // where the DPS comes from
        Damage.Breakdown b = report.detail().breakdown();
        double total = b.total();
        int sy = y + topH + 8;
        section(g, L.t("wynnlabs.damage.breakdown", QuestAdvisor.fmt(Math.round(total))), x, sy, w);
        sy += 12;
        int colW = (w - 16) / 2;
        String[] sources = {L.t("wynnlabs.damage.src_spell"), L.t("wynnlabs.damage.src_main"), L.t("wynnlabs.damage.src_summon")};
        double[] values = {b.spellTotal(), b.mainTotal(), b.summonTotal()};
        for (int i = 0; i < sources.length; i++) {
            shareRow(g, x, sy + i * 11, colW, sources[i], values[i], total, SOURCE_COLOURS[i]);
        }
        // the build score weighs the main attack less: say by how much
        double weight = DamageReport.mainAttackWeight();
        desc(g, L.t("wynnlabs.damage.main_weight", dec(weight, 2)), x, sy + 36, colW);
        menu.hits.add(new MenuHit(x, sy + 11, colW, 11, null, List.of(
                L.c("wynnlabs.damage.src_main").withStyle(ChatFormatting.GOLD),
                L.c("wynnlabs.damage.main_weight_tip", dec(weight, 2)).withStyle(ChatFormatting.GRAY)), null));
        int ex = x + colW + 16;
        if (slim) {
            // compact: the elements in two columns of three, a colour mark and the share, no bars
            int subW = (colW - 8) / 2;
            for (int i = 0; i < Damage.ELEMENTS.length; i++) {
                int cx = ex + (i / 3) * (subW + 8);
                int cy = sy + (i % 3) * 11;
                double v = b.element(i);
                String share = pctOf(total > 0 ? v / total * 100 : 0);
                g.fill(cx, cy + 2, cx + 4, cy + 6, ELEMENT_COLOURS[i]);
                ink(g, fit(L.t(DamageReport.ELEMENT_KEYS[i]), subW - 10 - font().width(share)), cx + 7, cy, 0xFFDDDDDD);
                ink(g, share, cx + subW - font().width(share), cy, v > 0 ? 0xFFE8E6DF : FAINT);
                menu.hits.add(new MenuHit(cx, cy - 1, subW, 11, null, List.of(Component.literal(
                        L.t(DamageReport.ELEMENT_KEYS[i]) + "  " + QuestAdvisor.fmt(Math.round(v)) + "  " + share)), null));
            }
            sy += 4 * 11 + 4;
        } else {
            for (int i = 0; i < Damage.ELEMENTS.length; i++) {
                shareRow(g, ex, sy + i * 11, colW, L.t(DamageReport.ELEMENT_KEYS[i]), b.element(i), total, ELEMENT_COLOURS[i]);
            }
            sy += 6 * 11 + 6;
        }

        // what one more step of each stat would add
        String free = report.freePoints() > 0 ? L.n("wynnlabs.damage.free_points", report.freePoints())
                : L.t("wynnlabs.damage.no_free_points");
        // the note in the room the title leaves (cut short on a narrow page)
        free = fit(free, w - font().width(L.t("wynnlabs.damage.what_if")) - 24);
        section(g, L.t("wynnlabs.damage.what_if"), x, sy, w - font().width(free) - 8);
        ink(g, free, x + w - font().width(free), sy, FAINT);
        sy += 12;
        List<DamageReport.WhatIf> ifs = report.whatIf();
        double maxGain = 0;
        for (DamageReport.WhatIf wi : ifs) {
            maxGain = Math.max(maxGain, wi.gain());
        }
        // compact: denser rows without bars, so more of them fit
        int rowH = slim ? Kit.Ui.LINE_DENSE : Kit.Ui.LINE;
        int per = Math.max(1, Math.min((ifs.size() + 1) / 2, (y + h - sy) / rowH));
        for (int i = 0; i < ifs.size() && i < per * 2; i++) {
            DamageReport.WhatIf wi = ifs.get(i);
            int rx = x + (i / per) * (colW + 16);
            int ry = sy + (i % per) * rowH;
            whatIfRow(g, rx, ry, colW, wi, maxGain, mx, my);
        }
    }

    /** The longest bar in the damage tab's rows: on a large page a longer one reads no better. */
    private static final int BAR_MAX = 150;

    /** The damage rows' value column, the same width on every row so every bar ends at the same x. */
    private int numColW() {
        return font().width("+00.0k");
    }

    private int pctColW() {
        return Math.max(font().width("%100"), font().width("%00,0"));
    }

    /**
     * A bar row's bar and value: a track of {@code frac} from {@code bx}, then the number and the share
     * right-aligned in their fixed columns. {@code room} is the width from {@code bx} to the row's end.
     */
    private void barAndValue(GuiGraphics g, int bx, int y, int room, double frac, int colour, String num, String share, int valColour) {
        int valW = numColW() + 6 + pctColW();
        int bw = Math.max(10, Math.min(BAR_MAX, room - valW - 8));
        Art.pill(g, bx, y + 2, bw, 5, Theme.WELL, 0);
        int fill = (int) Math.round(bw * Math.max(0, Math.min(1, frac)));
        if (fill >= 2) {
            Art.pill(g, bx, y + 2, fill, 5, colour, 0);
        }
        int vx = bx + bw + 8;
        ink(g, num, vx + numColW() - font().width(num), y, valColour);
        ink(g, share, vx + valW - font().width(share), y, valColour);
    }

    /** Computed against measured: damage per cast by the model and by the meter, the ratio, the calibration. */
    private void drawMeasured(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        Kit.card(g, x, y, w, h, 0);
        ink(g, Component.literal(L.t("wynnlabs.damage.vs_title")).withStyle(UiFont::bold), x + 8, y + 7, 0xFFFFFFFF);
        double model = DamageReport.modelPerCast();
        double cal = DamageReport.calibration();
        int casts = DamageReport.measuredCasts();
        double measured = DamageReport.measuredPerCast();
        String na = "—";
        String[][] rows = {
            {L.t("wynnlabs.damage.model_per_cast"), model > 0 ? QuestAdvisor.fmt(Math.round(model)) : na},
            {L.t("wynnlabs.damage.measured_per_cast"), casts > 0
                    ? L.n("wynnlabs.damage.measured_value", casts, QuestAdvisor.fmt(Math.round(measured)), casts) : na},
            {L.t("wynnlabs.damage.ratio"), (casts > 0 && model > 0 ? RoadmapScreen.pct(Math.round(measured / model * 100)) : na)
                    + " · " + L.t("wynnlabs.damage.calibration", dec(cal, 2))}};
        int ry = y + 20;
        // a large card keeps each value near its label
        int kvW = menu.layout.large() ? Math.min(w - 16, 225) : w - 16;
        for (String[] r : rows) {
            ink(g, fit(r[0], kvW - font().width(r[1]) - 6), x + 8, ry, MUTED);
            ink(g, r[1], x + 8 + kvW - font().width(r[1]), ry, 0xFFE8E6DF);
            ry += Kit.Ui.LINE_DENSE;
        }
        String note = casts == 0 ? L.t("wynnlabs.damage.none_measured")
                : casts < DamageReport.ENOUGH_CASTS ? L.n("wynnlabs.damage.few_casts", casts, casts, DamageReport.ENOUGH_CASTS)
                : L.t("wynnlabs.damage.enough_casts");
        if (ry + 9 <= y + h - 4) {
            desc(g, note, x + 8, y + h - 13, w - 16);
        }
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(L.t("wynnlabs.damage.vs_title")).withStyle(ChatFormatting.WHITE));
        for (String l : Advice.wrap(note + " " + L.t("wynnlabs.damage.vs_explain"), 48)) {
            tip.add(Component.literal(l).withStyle(ChatFormatting.GRAY));
        }
        menu.hits.add(new MenuHit(x, y, w, h, null, tip, null));
    }

    /** The live meter: DPS of the last 10 seconds, the session's best 10 seconds, this fight's damage. */
    private void drawLive(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        DpsMeter.State s = DpsMeter.LIVE.state();
        Kit.card(g, x, y, w, h, s.fighting() ? Theme.AMBER : 0);
        if (s.fighting()) {
            Kit.outline(g, x - 1, y - 1, w + 2, h + 2, Theme.AMBER_LO);
        }
        ink(g, Component.literal(L.t("wynnlabs.damage.live_title")).withStyle(UiFont::bold), x + 8, y + 7, 0xFFFFFFFF);
        // the same meter on the game screen: its switch, right here
        String hud = L.t("wynnlabs.damage.live_hud");
        int px = x + w - 26;
        int lx = px - 4 - font().width(hud);
        boolean hov = mx >= lx && mx < x + w - 4 && my >= y + 3 && my < y + 16;
        ink(g, hud, lx, y + 5, hov ? 0xFFDDDDDD : FAINT);
        pill(g, px, y + 5, Settings.on("dpsHud"));
        menu.toggles.add(new Object[] {new int[] {lx, y + 3, x + w - 4 - lx, 13}, "dpsHud"});
        if (hov) {
            rowTip(lx, y + 3, x + w - 4 - lx, 13, L.t("wynnlabs.setting.dpsHud"), L.t("wynnlabs.setting_desc.dpsHud"));
        }
        if (s.sessionDamage() <= 0) {
            // wrapped over the card's room rather than cut
            descWrap(g, L.t("wynnlabs.damage.live_empty"), x + 8, y + 20, w - 16, Math.max(1, (h - 24) / 9));
            return;
        }
        String dps = QuestAdvisor.fmt(Math.round(s.dps()));
        scaledText(g, Component.literal(dps).withStyle(UiFont::bold), x + 8, y + 20, 1.5f, s.fighting() ? Theme.WARN : MUTED);
        ink(g, "DPS", x + 12 + (int) (font().width(dps) * 1.5f), y + 24, FAINT);
        String best = L.t("wynnlabs.damage.live_best", QuestAdvisor.fmt(Math.round(s.best())));
        ink(g, fit(best, w - 16), x + 8, y + 39, MUTED);
        String fight = L.t(s.fighting() ? "wynnlabs.damage.live_fight" : "wynnlabs.damage.live_last_fight",
                QuestAdvisor.fmt(Math.round(s.fightDamage())), Math.round(s.fightSeconds()));
        ink(g, fit(fight, w - 16), x + 8, y + 39 + Kit.Ui.LINE_DENSE, MUTED);
    }

    /** A label, a bar of its share of {@code total}, the value and the share in %. */
    private void shareRow(GuiGraphics g, int x, int y, int w, String label, double value, double total, int colour) {
        int labelW = 74;
        double share = total > 0 ? value / total : 0;
        ink(g, fit(label, labelW - 4), x, y, 0xFFDDDDDD);
        barAndValue(g, x + labelW, y, w - labelW, share, colour, QuestAdvisor.fmt(Math.round(value)),
                pctOf(share * 100), value > 0 ? 0xFFE8E6DF : FAINT);
    }

    /** One what-if: the change, a green bar of its gain against the biggest, "+123 DPS (%4,5)". */
    private void whatIfRow(GuiGraphics g, int x, int y, int w, DamageReport.WhatIf wi, double maxGain, int mx, int my) {
        long gain = Math.round(wi.gain());
        String num = (gain > 0 ? "+" : "") + QuestAdvisor.fmt(gain);
        String share = pctOf(wi.percent());
        boolean useful = wi.gain() >= 0.5;
        int valColour = useful ? 0xFF9FE1CB : FAINT;
        int valW = numColW() + 6 + pctColW();
        if (menu.layout.compact()) {
            // compact: no bar, the label up to the value column
            ink(g, fit(L.t(wi.key()), w - valW - 6), x, y, useful ? 0xFFDDDDDD : MUTED);
            ink(g, num, x + w - valW + numColW() - font().width(num), y, valColour);
            ink(g, share, x + w - font().width(share), y, valColour);
        } else {
            int labelW = 124;
            ink(g, fit(L.t(wi.key()), labelW - 4), x, y, useful ? 0xFFDDDDDD : MUTED);
            barAndValue(g, x + labelW, y, w - labelW, maxGain > 0 ? Math.max(0, wi.gain()) / maxGain : 0,
                    Theme.GREEN, num, share, valColour);
        }
        if (mx >= x && mx < x + w && my >= y && my < y + 11) {
            menu.hits.add(new MenuHit(x, y, w, 11, null, List.of(
                    Component.literal(L.t(wi.key())).withStyle(ChatFormatting.WHITE),
                    L.c("wynnlabs.damage.what_if_tip", QuestAdvisor.fmt(Math.round(wi.dps())), dec(wi.casts(), 2))
                            .withStyle(ChatFormatting.GRAY)), null));
        }
    }
}
