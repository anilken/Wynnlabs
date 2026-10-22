package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.advice.Advice;
import io.github.anilken.wynnlabs.coach.DeathCoach;
import io.github.anilken.wynnlabs.core.L;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The deaths tab: the death coach, what killed you and what would have helped. */
final class DeathsTab extends MenuTab {

    DeathsTab(RoadmapScreen menu) {
        super(menu);
    }

    // ---- the tab ----

    @Override
    void draw(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float pt) {
        drawDeaths(g, x, y, w, h, mx, my);
    }

    // ---- deaths tab (Ölüm koçu) ----

    /** A death worth showing: one recorded live (the ones copied from old logs have no details). */
    private static boolean detailed(DeathCoach.Death d) {
        return d.level() > 0 && d.ehp() > 0;
    }

    /** The death's main cause, as a language key; drives its colour too. */
    private static String cause(DeathCoach.Death d) {
        if (d.areaLevel() > d.level() + 2) {
            return "wynnlabs.deaths.cause_over";
        }
        if (d.secondsToZero() > 0 && d.secondsToZero() <= 3) {
            return "wynnlabs.deaths.cause_burst";
        }
        if (d.potions() == 0) {
            return "wynnlabs.deaths.cause_nopots";
        }
        if (d.secondsToZero() > 3) {
            return "wynnlabs.deaths.cause_slow";
        }
        return "wynnlabs.deaths.cause_unknown";
    }

    private static int causeColour(String cause) {
        return switch (cause) {
            case "wynnlabs.deaths.cause_over" -> 0xFFE24B4A;
            case "wynnlabs.deaths.cause_burst" -> 0xFFEF9F27;
            case "wynnlabs.deaths.cause_nopots" -> 0xFFD4537E;
            case "wynnlabs.deaths.cause_slow" -> 0xFF378ADD;
            default -> 0xFF5F5E5A;
        };
    }

    /** Nameplate leftovers off a mob name ("Rai-Elisu  1s" → "Rai-Elisu"). */
    private static String mobName(String m) {
        return m.replaceAll("\\s{2,}.*$", "").replaceAll("\\s+\\d+s$", "").trim();
    }

    /**
     * Deaths: three tiles (this week, the most dangerous place, the main cause), the coach's
     * advice, then each death with its cause stripe, place, when, and the numbers that matter;
     * click one to put its spot on the compass. Old entries without details fold into one line.
     */
    private void drawDeaths(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        List<DeathCoach.Death> all = DeathCoach.all();
        if (all.isEmpty()) {
            int ly = y + 4;
            for (var l : font().split(L.c("wynnlabs.deaths.none").withStyle(ChatFormatting.GRAY), w)) {
                ink(g, l, x, ly, 0xFFFFFFFF);
                ly += Kit.Ui.LINE_DENSE;
            }
            return;
        }
        long now = System.currentTimeMillis();
        List<DeathCoach.Death> real = new ArrayList<>();
        int legacy = 0;
        for (DeathCoach.Death d : all) {
            if (detailed(d)) {
                real.add(d);
            } else {
                legacy++;
            }
        }
        List<DeathCoach.Death> week = new ArrayList<>();
        for (DeathCoach.Death d : real) {
            if (now - d.time() < 7 * 24 * 3600_000L) {
                week.add(d);
            }
        }
        Map<String, Integer> places = new HashMap<>();
        Map<String, Integer> causes = new HashMap<>();
        Map<String, Integer> advice = new HashMap<>();
        for (DeathCoach.Death d : week) {
            places.merge(d.place().isEmpty() ? "?" : L.cap(d.place()), 1, Integer::sum);
            causes.merge(cause(d), 1, Integer::sum);
            for (String a : d.adviceText()) {
                advice.merge(a, 1, Integer::sum);
            }
        }
        String worstPlace = best(places);
        String mainCause = best(causes);
        String topAdvice = best(advice);

        // three tiles
        int gap = 6;
        int tw = (w - 2 * gap) / 3;
        int th = 34;
        tile(g, x, y, tw, th, L.t("wynnlabs.deaths.tile_week"), L.n("wynnlabs.deaths.tile_count", week.size()), 0xFFFFFFFF);
        tile(g, x + tw + gap, y, tw, th, L.t("wynnlabs.deaths.tile_place"),
                worstPlace == null ? "—" : worstPlace + " ×" + places.get(worstPlace), 0xFFE8E6DF);
        tile(g, x + 2 * (tw + gap), y, tw, th, L.t("wynnlabs.deaths.tile_cause"),
                mainCause == null ? "—" : L.t(mainCause), mainCause == null ? 0xFFE8E6DF : causeColour(mainCause));
        int ry = y + th + 6;
        if (topAdvice != null) {
            // a card like the deaths under it (same edges, one border), its stripe in the coach's colour
            Kit.card(g, x, ry, w, 16, 0, false);
            g.fill(x + 1, ry + 1, x + 4, ry + 15, Theme.ACCENT);
            inkIconText(g, "sparkles", fit(L.t("wynnlabs.deaths.advice_head") + topAdvice, w - 25), x + 9, ry + 4, Theme.ACCENT);
            ry += 21;
        }

        // the list, newest first, as many as fit
        int rowH = 34;
        int footer = legacy > 0 ? 14 : 0;
        int fit = Math.max(1, (y + h - ry - footer) / rowH);
        List<DeathCoach.Death> recent = new ArrayList<>(real);
        java.util.Collections.reverse(recent);
        for (int i = 0; i < recent.size() && i < fit; i++) {
            DeathCoach.Death d = recent.get(i);
            String cause = cause(d);
            boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + rowH - 3;
            Kit.card(g, x, ry, w, rowH - 3, 0, hover);
            g.fill(x + 1, ry + 1, x + 4, ry + rowH - 4, causeColour(cause));
            String when = ago(d.time());
            ink(g, when, x + w - 6 - font().width(when), ry + 4, FAINT);
            ink(g, fit(d.place().isEmpty() ? "?" : L.cap(d.place()), w - 24 - font().width(when)), x + 9, ry + 4, 0xFFFFFFFF);
            // the numbers: zone vs you, how fast, potions
            int cx = x + 9;
            int cy = ry + 15;
            if (d.areaLevel() > 0) {
                cx = chip(g, cx, cy, L.t("wynnlabs.deaths.chip_zone", d.areaLevel(), d.level()),
                        d.areaLevel() > d.level() + 2 ? 0xFFE24B4A : 0xFF9C9A92);
            }
            if (d.secondsToZero() > 0) {
                cx = chip(g, cx, cy, L.t("wynnlabs.deaths.chip_seconds", dec(d.secondsToZero(), 1)),
                        d.secondsToZero() <= 3 ? 0xFFEF9F27 : 0xFF9C9A92);
            }
            if (d.potions() >= 0) {
                cx = chip(g, cx, cy, L.n("wynnlabs.deaths.chip_potions", d.potions()), d.potions() == 0 ? 0xFFD4537E : 0xFF9C9A92);
            }
            if (!d.advice().isEmpty() && cx < x + w - 60) {
                ink(g, fit("→ " + d.adviceText().get(0), x + w - cx - 8), cx + 4, cy + 2, 0xFFAFA9EC);
            }
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(L.cap(d.place())).withStyle(ChatFormatting.WHITE));
            tip.add(Component.literal(L.t(cause)).withStyle(ChatFormatting.GRAY));
            if (!d.mobs().isEmpty()) {
                List<String> mobs = new ArrayList<>();
                for (String m : d.mobs()) {
                    mobs.add(mobName(m));
                }
                for (String l : Advice.wrap(L.t("wynnlabs.deaths.tip_mobs", String.join(", ", mobs)), 46)) {
                    tip.add(Component.literal(l).withStyle(ChatFormatting.GRAY));
                }
            }
            for (String a : d.adviceText()) {
                for (String l : Advice.wrap("→ " + a, 46)) {
                    tip.add(Component.literal(l).withStyle(ChatFormatting.LIGHT_PURPLE));
                }
            }
            tip.add(Component.literal(L.t("wynnlabs.deaths.tip_click")).withStyle(ChatFormatting.DARK_GRAY));
            menu.hits.add(new MenuHit(x, ry, w, rowH - 3, null, tip,
                    "#qnear:" + d.x() + " " + d.y() + " " + d.z() + " " + L.t("wynnlabs.deaths.pin", d.place())));
            ry += rowH;
        }
        int more = recent.size() - Math.min(recent.size(), fit);
        List<String> foot = new ArrayList<>();
        if (more > 0) {
            foot.add(L.n("wynnlabs.deaths.more", more));
        }
        if (legacy > 0) {
            foot.add(L.n("wynnlabs.deaths.legacy", legacy));
        }
        if (!foot.isEmpty()) {
            ink(g, fit(String.join(" · ", foot), w), x, Math.max(ry + 2, y + h - 11), FAINT);
        }
    }

    private static String best(Map<String, Integer> counts) {
        String best = null;
        for (var e : counts.entrySet()) {
            if (best == null || e.getValue() > counts.get(best)) {
                best = e.getKey();
            }
        }
        return best;
    }

    /** A small number tile: a dim label over a bright value. */
    private void tile(GuiGraphics g, int x, int y, int w, int h, String label, String value, int colour) {
        Kit.plaque(g, x, y, w, h);
        ink(g, fit(label, w - 10), x + 6, y + 5, FAINT);
        ink(g, fit(value, w - 10), x + 6, y + 18, colour);
    }

    /** A bordered word in a row of facts; returns where the next one starts. */
    private int chip(GuiGraphics g, int x, int y, String text, int colour) {
        int cw = font().width(text) + 12;
        Kit.pillLine(g, x, y, cw, 12, Theme.paperRgb(colour));
        ink(g, text, x + 6, y + 2, colour);
        return x + cw + 4;
    }
}
