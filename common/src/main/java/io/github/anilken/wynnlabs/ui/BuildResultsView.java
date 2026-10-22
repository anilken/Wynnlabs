package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.build.Around;
import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.BuildSources;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.build.Plans;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap;
import io.github.anilken.wynnlabs.core.DevHooks;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.SkillKind;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The build window's RESULTS view: the ranked builds as cards (score, label, price, the nine
 * pieces as icons), the picked one's details (changes against what you wear, skill points, the
 * equip order, what to get and where from), and Cancel / "Apply build N".
 */
final class BuildResultsView {

    private final BuildWindow win;
    private final BuildControls ui;

    BuildResultsView(BuildWindow win, BuildControls ui) {
        this.win = win;
        this.ui = ui;
    }

    void drawResults(GuiGraphics g, BuildWindow.Host host, int x, int y, int w, int h, int mx, int my) {
        if (DevHooks.ON) {
            DevHooks.part("results", g.pose(), x, y - 3, w, h + 3);
        }
        Around.Result r = win.result;
        // the header: back to the items, the count found around them
        int bw = ui.control(g, host, x, y - 3, 14, "arrow-left", L.t("wynnlabs.results.back"), BuildControls.WOOD, false, 0, "back", null,
                List.of(L.c("wynnlabs.results.back_tip").withStyle(ChatFormatting.GRAY)), mx, my);
        int n = r == null ? 0 : r.builds().size();
        String around = r == null || r.around().isEmpty() ? null : String.join(", ", r.around());
        // planned for a level above yours when a chosen item needs it
        String planned = r != null && r.level() > GameState.current().level() ? L.t("wynnlabs.results.planned_for", r.level()) : null;
        int headRoom = w - bw - 24;
        String plain = L.n("wynnlabs.results.found_plain", n, n);
        String[] tries = around == null ? new String[] {plain}
                : new String[] {L.n("wynnlabs.results.found", n, n, around), plain};
        String head = tries[tries.length - 1];
        for (String c : tries) {
            if (ui.width(c, true) + (planned == null ? 0 : 8 + ui.font.width(planned)) <= headRoom) {
                head = c;
                break;
            }
        }
        if (around != null && !head.contains(around)) {
            host.tip(x + bw + 6, y, Math.min(headRoom, ui.width(head, true)), 9,
                    List.of(Component.literal(around).withStyle(ChatFormatting.LIGHT_PURPLE)));
        }
        Kit.text(g, ui.font, Component.literal(ui.fit(head, headRoom, true)).withStyle(UiFont::bold), x + bw + 6, y, Theme.INK);
        if (planned != null && ui.width(head, true) + 8 + ui.font.width(planned) <= headRoom) {
            Kit.text(g, ui.font, planned, x + bw + 6 + ui.width(head, true) + 8, y, BuildControls.CHOSEN);
        }
        int top = y + 16;
        int footY = y + h - 14;
        if (r == null || r.builds().isEmpty()) {
            String why = r != null && r.problem() != null ? L.t("wynnlabs.pick.problem", r.problem().text())
                    : r != null && r.tuning().budget() > 0 ? L.t("wynnlabs.results.none_budget", BuildText.budgetText(r.tuning().budget()))
                    : L.t("wynnlabs.results.none");
            Kit.text(g, ui.font, ui.fit(why, w, false), x, top + 4, Theme.BAD);
            footer(g, host, x, footY, w, mx, my, false);
            return;
        }
        win.card = Math.max(0, Math.min(win.card, n - 1));
        if (r.problem() != null) {
            // the chosen items could not be held: these are the builds without them
            Kit.text(g, ui.font, ui.fit(L.t("wynnlabs.pick.problem", r.problem().text()), w, false), x, top, Theme.BAD);
            top += 12;
        }
        int bodyH = footY - 4 - top;
        boolean two = !win.compact && w >= 380;             // the win.compact menu: one column, the cards on top
        if (two) {
            int lw = Math.max(176, Math.min(220, w * 40 / 100));
            int ch = Math.min(CARD_H, (bodyH - (n - 1) * 3) / n);
            for (int i = 0; i < n; i++) {
                cardBox(g, host, r.builds().get(i), i, x, top + i * (ch + 3), lw, ch, mx, my, false);
            }
            details(g, host, r.builds().get(win.card), x + lw + 8, top, w - lw - 8, bodyH, 16);
        } else {
            // narrow: the cards two by two on top, the details under them
            int cols = Math.min(2, n);
            int rowsN = (n + cols - 1) / cols;
            int cw = (w - (cols - 1) * 3) / cols;
            int ch = SMALL_CARD_H;
            for (int i = 0; i < n; i++) {
                cardBox(g, host, r.builds().get(i), i, x + (i % cols) * (cw + 3), top + (i / cols) * (ch + 3), cw, ch, mx, my, true);
            }
            int used = rowsN * (ch + 3) + 2;
            details(g, host, r.builds().get(win.card), x, top + used, w, bodyH - used, 12);
        }
        footer(g, host, x, footY, w, mx, my, true);
    }

    /** A win.card's height: its score line, its price line and the row of item icons. */
    private static final int CARD_H = 47;
    /** A win.compact win.card's: the score line, then the 12px icons with the price beside them. */
    private static final int SMALL_CARD_H = 34;

    /**
     * One build's win.card: rank and score with its label, the price (coin and amount), and its nine
     * pieces as item icons in the panel's slot order, each framed by what it is (purple a chosen
     * item, green a change from what you wear, plain unchanged); an icon's hover is the item's tooltip.
     */
    private void cardBox(GuiGraphics g, BuildWindow.Host host, Roadmap.Candidate c, int i, int x, int y, int w, int h, int mx, int my, boolean small) {
        boolean on = i == win.card;
        boolean hov = BuildControls.in(mx, my, x, y, w, h);
        Kit.card(g, x, y, w, h, 0, on || hov);
        if (on) {
            Kit.outline(g, x, y, w, h, BuildControls.CHOSEN);
        }
        String score = "#" + (i + 1) + " · " + String.format(Locale.ROOT, "%,d", Math.round(c.score()));
        if (small && ui.width(score, true) > w / 2) {
            score = "#" + (i + 1) + " · " + shortNum(c.score());
        }
        Kit.text(g, ui.font, Component.literal(score).withStyle(UiFont::bold), x + 5, y + 4, Theme.INK);
        int sw = ui.width(score, true);
        if (!c.labels().isEmpty()) {
            // the label as a small icon on a seal of its colour (star, coin, shield, shoe); its name in the tooltip
            String l = c.labels().get(0);
            Kit.seal(g, x + w - 4 - 14, y + 3, 14, 11, labelColour(l));
            Art.icon(g, labelIcon(l), x + w - 4 - 11, y + 4, 8, 0xFFFFFFFF);
            host.tip(x + w - 18, y + 3, 14, 11, List.of(L.c(labelKey(l, false)).withStyle(ChatFormatting.WHITE),
                    L.c(labelKey(l, true)).withStyle(ChatFormatting.GRAY)));
        }
        int s = small ? 12 : 16;
        int iy = small ? y + 17 : y + h - s - 5;
        int iconsW = icons(g, host, x + 5, iy, s, c.set(), c.fixed(), BuildSources.worn(), w - 10);
        // the price: beside the icons on a win.compact win.card, on its own line otherwise
        int px = small ? x + 5 + iconsW + 4 : x + 5;
        int py = small ? iy + (s - 8) / 2 : y + 16;
        price(g, c.price(), px, py, x + w - 4 - px);
        ui.btn(x, y, w, h, "card", i);
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(score).withStyle(ChatFormatting.WHITE));
        for (String l : c.labels()) {
            tip.add(L.c(labelKey(l, true)).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        tip.add(L.c("wynnlabs.results.squares_tip").withStyle(ChatFormatting.DARK_GRAY));
        // the win.card's own tooltip on its text only: the icons have the items'
        host.tip(x, y, w, iy - y - 1, tip);
    }

    /** "1.2 LE" after the coin, "nothing to buy", or "price unknown", within {@code room}. */
    private void price(GuiGraphics g, double price, int x, int y, int room) {
        if (Double.isNaN(price) || price <= 0) {
            String t = L.t(Double.isNaN(price) ? "wynnlabs.results.price_unknown" : "wynnlabs.results.price_free");
            Kit.text(g, ui.font, ui.fit(t, room, false), x, y, Theme.INK_3);
            return;
        }
        if (room < 20) {
            return;
        }
        Art.icon(g, "coin", x, y, 8, Theme.PRICE);
        Kit.text(g, ui.font, ui.fit(Prices.fmt(price), room - 10, false), x + 10, y, Theme.PRICE);
    }

    private static String labelIcon(String l) {
        return switch (l) {
            case "best" -> "star-filled";
            case "cheap" -> "coin";
            case "tanky" -> "shield";
            default -> "shoe";
        };
    }

    private static int labelColour(String l) {
        return switch (l) {
            case "best" -> 0xFF8A6A1C;
            case "cheap" -> Theme.SEAL_3;
            case "tanky" -> 0xFF2F5E8A;
            default -> 0xFF2C7A7E;
        };
    }

    private static String shortNum(double v) {
        return v >= 10_000 ? Math.round(v / 1000) + "k" : String.valueOf(Math.round(v));
    }

    /**
     * The nine pieces as item icons of {@code s} pixels in the panel's slot order, each in a 1px
     * frame: purple a chosen item, green a change from what you wear, the slot's edge colour when
     * it stays; an empty slot is its frame alone. Each icon's hover is the item's own tooltip
     * with where it comes from. Returns the width drawn; fewer gaps when {@code room} is tight.
     */
    private int icons(GuiGraphics g, BuildWindow.Host host, int x, int y, int s, Map<String, Damage.Piece> set, java.util.Set<String> fixed,
            Map<String, String> worn, int room) {
        int gap = room >= 9 * (s + 2) + 8 * 2 ? 2 : 1;
        int step = s + 2 + gap;
        for (int i = 0; i < Plans.SLOTS.length; i++) {
            String slot = Plans.SLOTS[i];
            Damage.Piece p = set.get(slot);
            String name = p == null ? null : p.name();
            boolean locked = fixed.contains(slot);
            int frame = locked ? BuildControls.CHOSEN : name != null && !name.equals(worn.get(slot)) ? Theme.GREEN : Theme.INK_3;
            int fx = x + i * step;
            // the kit's item slot; its edge in the slot's colour (none when it stays as you wear it)
            Kit.slot(g, fx, y - 1, s + 2, frame == Theme.INK_3 ? 0 : frame);
            if (name == null) {
                continue;
            }
            ItemData info = BuildSources.itemOf(name);
            ItemStack icon = info == null ? ItemStack.EMPTY : host.icon(info);
            if (!icon.isEmpty()) {
                renderIcon(g, icon, fx + 1, y, s);
                host.item(fx, y - 1, s + 2, s + 2, info, icon, itemExtra(name, locked, p.stack() != null));
                if (locked && win.firstIcon == null) {
                    win.firstIcon = new int[] {fx, y - 1, s + 2, s + 2};
                }
            }
        }
        return 9 * step - gap;
    }

    /** An item icon at 16 or 12 pixels (scaled down whole). */
    private static void renderIcon(GuiGraphics g, ItemStack stack, int x, int y, int s) {
        if (s == 16) {
            g.renderItem(stack, x, y);
            return;
        }
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(s / 16f, s / 16f);
        g.renderItem(stack, 0, 0);
        g.pose().popMatrix();
    }

    /** The lines under an item's own tooltip: where it comes from, and "chosen by you" for a chosen one. */
    private List<Component> itemExtra(String name, boolean locked, boolean ownedStack) {
        List<Component> out = new ArrayList<>();
        boolean have = ownedStack || BuildSources.haveNames().contains(name) || BuildSources.worn().containsValue(name);
        Object[] src = BuildSources.source(name);
        String where = have ? L.t("wynnlabs.results.have")
                : src[1] != null ? L.t("wynnlabs.results.market", Prices.fmt((double) src[1])) : (String) src[0];
        out.add(Component.literal(where).withStyle(have ? ChatFormatting.GREEN : ChatFormatting.DARK_AQUA));
        if (locked) {
            out.add(L.c("wynnlabs.results.chosen_by_you").withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        return out;
    }

    /**
     * The picked build: its nine pieces as icons, what changes against what you wear, its skill
     * points, the equip order, and what to get (each with its icon and where it comes from, a
     * price after the coin). {@code s}: the icon size.
     */
    private void details(GuiGraphics g, BuildWindow.Host host, Roadmap.Candidate c, int x, int y, int w, int h, int s) {
        Kit.outline(g, x, y, w, h, Theme.LINE);
        int ix = x + 5;
        int iw = w - 10;
        int ly = y + 5;
        int bottom = y + h - 4;
        Map<String, String> worn = BuildSources.worn();
        icons(g, host, ix, ly + 1, s, c.set(), c.fixed(), worn, iw);
        ly += s + 6;
        Build.Plan plan = Build.plan();
        Damage.Result now = plan == null ? null : plan.current();
        Damage.Result to = c.eval().result();
        // the changes: damage, effective health, mana regen, walk speed
        List<Object[]> deltas = new ArrayList<>();
        if (now != null && now.factors().length > 0 && to.factors().length > 0) {
            deltas.add(new Object[] {L.t("wynnlabs.results.d_damage"), pct(now.factors()[0], to.factors()[0])});
        }
        if (now != null) {
            deltas.add(new Object[] {L.t("wynnlabs.results.d_ehp"), pct(now.ehp(), to.ehp())});
        }
        double regenNow = plan == null || plan.worn() == null ? 0 : stat(plan.worn(), "manaRegen");
        double regenTo = stat(c.set(), "manaRegen");
        if (Math.abs(regenTo - regenNow) >= 0.5) {
            deltas.add(new Object[] {L.t("wynnlabs.results.d_mana"), (regenTo - regenNow >= 0 ? "+" : "−") + Math.round(Math.abs(regenTo - regenNow))});
        }
        if (now != null && Math.abs(to.speed() - now.speed()) >= 1) {
            deltas.add(new Object[] {L.t("wynnlabs.results.d_speed"), (to.speed() - now.speed() >= 0 ? "+" : "−")
                + Math.round(Math.abs(to.speed() - now.speed())) + "%"});
        }
        // the changes are against what you wear now
        String vs = L.t("wynnlabs.results.vs_now");
        int dx = ix + ui.font.width(vs) + 6;
        Kit.text(g, ui.font, vs, ix, ly, Theme.INK_3);
        for (Object[] d : deltas) {
            String v = (String) d[1];
            String part = d[0] + " " + v;
            int pw = ui.font.width(part) + 8;
            if (dx + pw > ix + iw && dx > ix) {
                dx = ix;
                ly += 10;
            }
            if (ly + 9 > bottom) {
                break;
            }
            Kit.text(g, ui.font, (String) d[0], dx, ly, Theme.INK_2);
            Kit.text(g, ui.font, v, dx + ui.font.width(d[0] + " "), ly, v.startsWith("−") ? Theme.BAD : Theme.GOOD);
            dx += pw;
        }
        ly += 12;
        // the skill points to assign
        StringBuilder sp = new StringBuilder();
        int[] a = c.eval().allocation();
        for (SkillKind k : SkillKind.values()) {
            if (a[k.ordinal()] > 0) {
                sp.append(sp.length() > 0 ? " · " : "").append(k.displayName(), 0, 3).append(' ').append(a[k.ordinal()]);
            }
        }
        if (ly + 9 <= bottom) {
            Kit.text(g, ui.font, ui.fit(L.t("wynnlabs.results.points", sp.length() == 0 ? "—" : sp.toString()), iw, false), ix, ly, Theme.INK);
            ly += 11;
        }
        String order = equipOrder(c);
        if (order != null && ly + 9 <= bottom) {
            Kit.text(g, ui.font, ui.fit(L.t("wynnlabs.results.order", order), iw, false), ix, ly, Theme.WARN);
            ly += 11;
        }
        ly += 2;
        if (ly + 9 > bottom) {
            return;
        }
        Kit.text(g, ui.font, Component.literal(L.t("wynnlabs.results.to_get")).withStyle(UiFont::bold), ix, ly, Theme.INK_2);
        ly += 11;
        java.util.Set<String> have = BuildSources.haveNames();
        List<String> slots = new ArrayList<>();
        for (String slot : Plans.SLOTS) {
            Damage.Piece p = c.set().get(slot);
            if (p != null && !p.name().equals(worn.get(slot))) {
                slots.add(slot);
            }
        }
        if (slots.isEmpty()) {
            Kit.text(g, ui.font, ui.fit(L.t("wynnlabs.results.nothing_to_get"), iw, false), ix, ly, Theme.GOOD);
            return;
        }
        int lineH = s + 2;
        for (int i = 0; i < slots.size(); i++) {
            if (ly + lineH > bottom || ly + 2 * lineH > bottom && i < slots.size() - 1) {
                int left = slots.size() - i;
                Kit.text(g, ui.font, L.n("wynnlabs.results.more", left, left), ix, ly + (lineH - 8) / 2, Theme.INK_3);
                break;
            }
            String slot = slots.get(i);
            Damage.Piece p = c.set().get(slot);
            boolean locked = c.fixed().contains(slot);
            boolean owned = p.stack() != null || have.contains(p.name());
            ItemData info = BuildSources.itemOf(p.name());
            ItemStack icon = info == null ? ItemStack.EMPTY : host.icon(info);
            if (!icon.isEmpty()) {
                renderIcon(g, icon, ix, ly, s);
                host.item(ix, ly, s, s, info, icon, itemExtra(p.name(), locked, p.stack() != null));
            }
            int tx = ix + s + 4;
            int ty = ly + (s - 8) / 2;
            Component nm = Component.literal(p.name()).withStyle(info == null ? ChatFormatting.WHITE : info.tier().colour());
            String nameText = ui.fit(p.name(), Math.max(30, iw - (tx - ix)) * 55 / 100, false);
            nm = Component.literal(nameText).withStyle(nm.getStyle());
            Kit.text(g, ui.font, nm, tx, ty, Theme.INK);
            tx += ui.font.width(nm);
            if (locked) {
                Art.icon(g, "lock", tx + 3, ty, 8, BuildControls.CHOSEN);
                tx += 11;
            }
            int room = ix + iw - tx - 10;
            if (owned) {
                Kit.text(g, ui.font, ui.fit("— " + L.t("wynnlabs.results.have"), room + 6, false), tx + 4, ty, Theme.GOOD);
            } else {
                Object[] src = BuildSources.source(p.name());
                if (src[1] != null) {
                    Kit.text(g, ui.font, "—", tx + 4, ty, Theme.INK_3);
                    int cx = tx + 4 + ui.font.width("— ");
                    price(g, (double) src[1], cx, ty, ix + iw - cx);
                } else {
                    Kit.text(g, ui.font, ui.fit("— " + src[0], room + 6, false), tx + 4, ty, Theme.INK_3);
                }
            }
            ly += lineH;
        }
    }

    private static String pct(double from, double to) {
        if (from <= 0) {
            return "—";
        }
        double p = (to / from - 1) * 100;
        return (p >= 0 ? "+" : "−") + Math.round(Math.abs(p)) + "%";
    }

    private static double stat(Map<String, Damage.Piece> set, String key) {
        double s = 0;
        for (Damage.Piece p : set.values()) {
            if (p != null) {
                s += p.stats().getOrDefault(key, 0.0);
            }
        }
        return s;
    }

    /**
     * The order to put the pieces on when it matters: a piece whose skill requirement is met only
     * by another piece's skill bonus goes on after it ("Yume before Prologue"); null when any order works.
     */
    private static String equipOrder(Roadmap.Candidate c) {
        int[] a = c.eval().allocation();
        for (Damage.Piece q : c.set().values()) {
            if (q == null) {
                continue;
            }
            for (var e : q.reqs().entrySet()) {
                if (a[e.getKey().ordinal()] >= e.getValue()) {
                    continue;
                }
                String key = "raw" + e.getKey().displayName();
                for (Damage.Piece p : c.set().values()) {
                    if (p != null && p != q && p.helpsRequirements() && p.stats().getOrDefault(key, 0.0) > 0) {
                        return L.t("wynnlabs.results.before", p.name(), q.name());
                    }
                }
            }
        }
        return null;
    }

    /** "Cancel" and the purple "Apply build N" (or "Overwrite" for a saved build's recompute). */
    private void footer(GuiGraphics g, BuildWindow.Host host, int x, int y, int w, int mx, int my, boolean any) {
        String apply = win.overwriteId != null ? L.t("wynnlabs.results.overwrite", win.card + 1) : L.t("wynnlabs.results.apply", win.card + 1);
        int aw = ui.controlW("check", apply, w / 2);
        int ax = x + w - aw;
        if (any) {
            ui.control(g, host, ax, y, 14, "check", apply, BuildControls.PRIMARY, false, w / 2, "apply", null,
                    List.of(L.c("wynnlabs.results.apply_tip").withStyle(ChatFormatting.GRAY)), mx, my);
        }
        int cx = any ? ax - 4 - BuildControls.ICON_W : x + w - BuildControls.ICON_W;
        ui.control(g, host, cx, y, 14, "x", L.t("wynnlabs.pick.cancel"), BuildControls.WOOD, false, 0, "close", null,
                List.of(L.c("wynnlabs.results.cancel_tip").withStyle(ChatFormatting.GRAY)), mx, my);
    }

    /** A label's pill text (or its tooltip line). */
    private static String labelKey(String label, boolean tip) {
        return switch (label) {
            case "best" -> tip ? "wynnlabs.results.label_best_tip" : "wynnlabs.results.label_best";
            case "cheap" -> tip ? "wynnlabs.results.label_cheap_tip" : "wynnlabs.results.label_cheap";
            case "tanky" -> tip ? "wynnlabs.results.label_tanky_tip" : "wynnlabs.results.label_tanky";
            default -> tip ? "wynnlabs.results.label_fast_tip" : "wynnlabs.results.label_fast";
        };
    }
}
