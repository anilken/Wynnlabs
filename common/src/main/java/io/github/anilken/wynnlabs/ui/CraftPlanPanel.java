package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.crafting.CraftData;
import io.github.anilken.wynnlabs.crafting.Crafting;
import io.github.anilken.wynnlabs.crafting.ProfessionPlan;
import io.github.anilken.wynnlabs.crafting.ProfessionXp;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.game.Professions;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.quests.Waypoints;
import io.github.anilken.wynnlabs.search.Area;
import io.github.anilken.wynnlabs.search.Compass;
import io.github.anilken.wynnlabs.search.Entry;
import io.github.anilken.wynnlabs.search.ItemIndex;
import io.github.anilken.wynnlabs.search.Kind;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.github.anilken.wynnlabs.ui.CraftFormat.thousands;
import static io.github.anilken.wynnlabs.ui.CraftFormat.typeIcon;
import static io.github.anilken.wynnlabs.ui.CraftFormat.typeName;

/**
 * The crafting tab's planner page: a profession and a target level, then for a gathering
 * profession the nearest spots of the resources the level allows (with a compass button) and the
 * gathers to the target, for a crafting one the recipe of each item type with the crafts to the
 * target and their materials' cost at the prices already kept. Counts past this level are
 * estimates ({@link ProfessionPlan}); it reads only what the mod already has.
 */
final class CraftPlanPanel extends CraftPanel {

    /** A page this wide shows the cards in two columns. */
    private static final int WIDE = 600;
    private static final int SPOTS = 5;

    /** For the preview without the game: {xp into the level, xp it needs} instead of Wynntils'. */
    static volatile int[] previewXp;

    /** The planned profession ("MINING"), null until the page is first drawn. */
    String profession;
    /** The target level per profession, as last set. */
    private final Map<String, Integer> targets = new HashMap<>();
    private ItemIndex resourcesOf;
    private List<ProfessionPlan.Resource> resources = List.of();
    private String note;
    // worked out again only when what they depend on changes, not every frame
    private String planKey = "";
    private long planXp = -1;
    private List<ProfessionPlan.CraftOption> options = List.of();
    private String spotsKey = "";
    private List<ProfessionPlan.Spot> spots = List.of();

    /** The XP to the target and the recipe options, for this profession, level, target, XP and prices. */
    private void plan(int level, int target) {
        int[] xp = xp(profession);
        String key = profession + "|" + level + "|" + target + "|" + xp[0] + "|" + xp[1] + "|" + Prices.version;
        if (!key.equals(planKey)) {
            planKey = key;
            planXp = ProfessionPlan.xpToTarget(CraftData.all(), level, target, xp[0], xp[1]);
            options = Professions.gathering(profession) ? List.of()
                    : ProfessionPlan.crafting(CraftData.all(), profession, level, target, xp[0], xp[1], Crafting::materialPrice);
        }
    }

    CraftPlanPanel(CraftingTab tab) {
        super(tab);
    }

    /** Draws the page from {@code y} (already scrolled); returns its height. */
    int drawPlan(GuiGraphics g, int x, int y, int w, int mx, int my) {
        if (profession == null) {
            String p = tab.profession();
            profession = p != null ? p : Professions.ALL.get(0);
        }
        int yy = y;
        section(g, L.t("wynnlabs.plan.pick"), x, yy, w);
        yy += 13;
        yy += chips(g, x, yy, w, mx, my) + 6;
        int level = Math.max(1, tab.knownLevel(profession.toLowerCase(Locale.ROOT)));
        int target = ProfessionPlan.clampTarget(level, targets.getOrDefault(profession, level + 5));
        plan(level, target);
        yy += targetRow(g, x, yy, w, level, target, mx, my) + 8;
        if (Professions.gathering(profession)) {
            yy += gathering(g, x, yy, w, level, mx, my);
        } else {
            yy += crafting(g, x, yy, w, mx, my);
        }
        if (note != null) {
            text(g, fit(note, w), x, yy + 2, Theme.GOOD);
            yy += 14;
        }
        return yy - y;
    }

    /** The twelve professions as buttons, gathering then crafting, with their levels. */
    private int chips(GuiGraphics g, int x, int y, int w, int mx, int my) {
        int cx = x;
        int cy = y;
        for (int i = 0; i < Professions.ALL.size(); i++) {
            String p = Professions.ALL.get(i);
            int lv = tab.knownLevel(p.toLowerCase(Locale.ROOT));
            String label = Professions.name(p) + " " + (lv > 0 ? lv : "?");
            int bw = width(label) + 10;
            if (i == 4 && cx > x) {
                cx += 6;                                // a gap between gathering and crafting
            }
            if (cx + bw > x + w && cx > x) {
                cx = x;
                cy += ROW_H + 3;
            }
            boolean on = p.equals(profession);
            int state = on ? Kit.ON : in(mx, my, cx, cy, bw, ROW_H) ? Kit.HOVER : Kit.REST;
            Kit.button(g, cx, cy, bw, ROW_H, state);
            wood(g, label, cx + 5, cy + 2, Kit.buttonText(state), !on);
            clipped(cx, cy, bw, ROW_H, () -> {
                profession = p;
                note = null;
            }, null, null);
            cx += bw + 3;
        }
        return cy + ROW_H - y;
    }

    /** "Target level  − 45 +  +10", then the XP to it. */
    private int targetRow(GuiGraphics g, int x, int y, int w, int level, int target, int mx, int my) {
        String label = L.t("wynnlabs.plan.target");
        text(g, label, x, y + 2, Theme.INK_2);
        int bx = x + width(label) + 6;
        bx += step(g, bx, y, "−", -1, target, level, mx, my) + 2;
        String shown = "Lv " + target;
        int tw = Math.max(width("Lv 132"), width(shown)) + 8;
        Kit.field(g, bx, y, tw, ROW_H, false);
        text(g, shown, bx + (tw - width(shown)) / 2, y + 2, Theme.INK);
        bx += tw + 2;
        bx += step(g, bx, y, "+", 1, target, level, mx, my) + 2;
        bx += step(g, bx, y, "+10", 10, target, level, mx, my) + 8;
        long need = planXp;
        boolean known = tab.knownLevel(profession.toLowerCase(Locale.ROOT)) > 0;
        String line = !known ? L.t("wynnlabs.prof.unknown")
                : need < 0 ? L.t("wynnlabs.plan.xp_unknown", level, target)
                : L.t("wynnlabs.plan.xp_to", level, target, big(need));
        int room = x + w - bx;
        if (room < width(line) && room < 120) {
            // a narrow page: the XP goes under the buttons
            text(g, fit(line, w), x, y + ROW_H + 4, Theme.INK_3);
            clipped(x, y + ROW_H + 2, Math.min(w, width(line)), 12, null, null, List.of(L.c("wynnlabs.plan.xp_tip")));
            return ROW_H * 2 + 4;
        }
        String fitted = fit(line, room);
        text(g, fitted, bx, y + 2, Theme.INK_3);
        clipped(bx, y, width(fitted), ROW_H, null, null, List.of(L.c("wynnlabs.plan.xp_tip")));
        return ROW_H;
    }

    private int step(GuiGraphics g, int x, int y, String label, int by, int target, int level, int mx, int my) {
        int bw = width(label) + 10;
        int state = in(mx, my, x, y, bw, ROW_H) ? Kit.HOVER : Kit.REST;
        Kit.button(g, x, y, bw, ROW_H, state);
        wood(g, label, x + 5, y + 2, Kit.buttonText(state), true);
        String p = profession;
        clipped(x, y, bw, ROW_H, () -> targets.put(p, ProfessionPlan.clampTarget(level, target + by)), null, null);
        return bw;
    }

    // ---- gathering ------------------------------------------------------------------------------

    private int gathering(GuiGraphics g, int x, int y, int w, int level, int mx, int my) {
        int yy = y;
        section(g, L.t("wynnlabs.plan.spots"), x, yy, w);
        yy += 13;
        double each = ProfessionXp.last(profession);
        long gathers = ProfessionPlan.actions(planXp, each);
        String line = each <= 0 ? L.t("wynnlabs.plan.gather_xp_none")
                : gathers < 0 ? L.t("wynnlabs.plan.gather_xp_only", CraftFormat.dec(each, 1))
                : L.n("wynnlabs.plan.gather_xp", gathers, CraftFormat.dec(each, 1), big(gathers));
        String fitted = fit(line, w);
        text(g, fitted, x, yy, Theme.INK_2);
        clipped(x, yy - 1, width(fitted), 11, null, null, List.of(Component.literal(line), L.c("wynnlabs.plan.gather_tip")));
        yy += 14;
        ItemIndex index = ItemIndex.get();
        if (index.missing() != null) {
            text(g, L.t("wynnlabs.plan.loading"), x, yy, Theme.INK_3);
            return yy + 12 - y;
        }
        double[] pos = position();
        // a few steps of walking do not change which node is nearest enough to matter
        String key = profession + "|" + level + "|" + System.identityHashCode(index)
                + (pos == null ? "|-" : "|" + Math.round(pos[0] / 8) + "," + Math.round(pos[1] / 8));
        if (!key.equals(spotsKey)) {
            spotsKey = key;
            spots = ProfessionPlan.spots(resources(index), profession, level, pos, SPOTS);
        }
        if (spots.isEmpty()) {
            text(g, fit(L.t("wynnlabs.plan.no_spots"), w), x, yy, Theme.INK_3);
            return yy + 12 - y;
        }
        int cols = w >= WIDE ? 2 : 1;
        int cw = (w - (cols - 1) * 6) / cols;
        for (int i = 0; i < spots.size(); i++) {
            int cx = x + (i % cols) * (cw + 6);
            int cy = yy + (i / cols) * 30;
            spot(g, cx, cy, cw, spots.get(i), pos, mx, my);
        }
        return yy + ((spots.size() + cols - 1) / cols) * 30 - y;
    }

    private void spot(GuiGraphics g, int x, int y, int w, ProfessionPlan.Spot s, double[] pos, int mx, int my) {
        Kit.card(g, x, y, w, 26, 0);
        Art.icon(g, gatherIcon(profession), x + 5, y + 9, 8, Theme.paperRgb(Theme.INK_2));
        String bl = L.t("wynnlabs.plan.compass");
        int bw = width(bl) + 20;
        int bx = x + w - bw - 5;
        String title = L.t("wynnlabs.plan.spot_line", s.resource().name(), s.resource().level());
        text(g, Component.literal(fit(title, bx - x - 22)).withStyle(UiFont::bold), x + 17, y + 4, Theme.INK);
        String where = Double.isNaN(s.distance()) ? L.t("wynnlabs.plan.nowhere")
                : L.t("wynnlabs.grind.distance", Math.round(s.distance()), dir(Compass.direction(s.node(), pos)));
        String sub = where + (s.resource().materials().isEmpty() ? "" : " · " + String.join(", ", s.resource().materials()));
        fine(g, fitFine(sub, bx - x - 22), x + 17, y + 15, Theme.INK_3);
        boolean hover = in(mx, my, bx, y + 7, bw, ROW_H);
        int state = hover ? Kit.HOVER : Kit.REST;
        Kit.button(g, bx, y + 7, bw, ROW_H, state);
        Art.icon(g, "map-pin", bx + 4, y + 9, 8, Kit.buttonText(state));
        wood(g, bl, bx + 15, y + 9, Kit.buttonText(state), true);
        Area a = s.node();
        String label = s.resource().name() + " (" + Professions.name(profession) + ")";
        clipped(bx, y + 7, bw, ROW_H, () -> note = Waypoints.go(a.x(), a.y(), a.z(), label)
                        ? L.t("wynnlabs.plan.compass_set", label) : L.t("wynnlabs.plan.compass_failed"), null,
                List.of(L.c("wynnlabs.plan.compass_tip", a.x(), a.y(), a.z())));
    }

    private static String gatherIcon(String p) {
        return switch (p) {
            case "MINING" -> "pick";
            case "WOODCUTTING" -> "leaf";
            case "FISHING" -> "droplet";
            default -> "sun";
        };
    }

    private static String dir(String d) {
        return d == null ? "" : SearchCardPanel.dirName(d);
    }

    /** The index's gathering resources, read again only when the index is new. */
    private List<ProfessionPlan.Resource> resources(ItemIndex index) {
        if (index != resourcesOf) {
            List<ProfessionPlan.Resource> out = new ArrayList<>();
            for (Entry e : index.all()) {
                if (e.kind() != Kind.RESOURCE || e.type() == null) {
                    continue;
                }
                List<String> mats = new ArrayList<>();
                for (Entry.Origin o : e.links(Entry.ITEM)) {
                    mats.add(o.name());
                }
                out.add(new ProfessionPlan.Resource(e.name(), e.type().toLowerCase(Locale.ROOT), e.level(), e.places(),
                        List.copyOf(mats)));
            }
            resources = List.copyOf(out);
            resourcesOf = index;
        }
        return resources;
    }

    private static double[] position() {
        try {
            return GameState.current().position();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- crafting -------------------------------------------------------------------------------

    private int crafting(GuiGraphics g, int x, int y, int w, int mx, int my) {
        int yy = y;
        String stationL = L.t("wynnlabs.craft.station");
        int sw = width(stationL) + 22;
        section(g, L.t("wynnlabs.plan.recipes"), x, yy, w - sw - 6);
        int sx = x + w - sw;
        int ss = in(mx, my, sx, yy - 2, sw, ROW_H) ? Kit.HOVER : Kit.REST;
        Kit.button(g, sx, yy - 2, sw, ROW_H, ss);
        Art.icon(g, "map-pin", sx + 5, yy, 8, Kit.buttonText(ss));
        wood(g, stationL, sx + 16, yy, Kit.buttonText(ss), true);
        String p = profession;
        clipped(sx, yy - 2, sw, ROW_H, () -> {
            int d = Crafting.pinStation(p, L.t("wynnlabs.craft.station_label", Professions.name(p)));
            note = d < 0 ? L.t("wynnlabs.craft.station_none") : L.t("wynnlabs.craft.station_pinned", d);
        }, null, List.of(L.c("wynnlabs.craft.station_tip", Professions.name(p))));
        yy += 14;
        ProfessionPlan.CraftOption cheapest = ProfessionPlan.cheapest(options);
        int cols = w >= WIDE ? 2 : 1;
        int cw = (w - (cols - 1) * 6) / cols;
        for (int i = 0; i < options.size(); i++) {
            int cx = x + (i % cols) * (cw + 6);
            int cy = yy + (i / cols) * 40;
            option(g, cx, cy, cw, options.get(i), i == 0 && options.get(i).crafts() >= 0, options.get(i) == cheapest, mx, my);
        }
        yy += ((options.size() + cols - 1) / cols) * 40;
        fine(g, fitFine(L.t("wynnlabs.plan.cost_note"), w), x, yy, Theme.INK_3);
        return yy + 10 - y;
    }

    private void option(GuiGraphics g, int x, int y, int w, ProfessionPlan.CraftOption o, boolean fastest, boolean cheapest,
                        int mx, int my) {
        boolean hover = in(mx, my, x, y, w, 36);
        Kit.card(g, x, y, w, 36, fastest ? Theme.GOOD : 0, hover);
        Art.icon(g, typeIcon(o.type()), x + 5, y + 5, 8, Theme.paperRgb(Theme.INK_2));
        int right = x + w - 5;
        if (cheapest) {
            right -= tag(g, right, y + 4, L.t("wynnlabs.plan.cheapest"), true, true) + 3;
        }
        if (fastest) {
            right -= tag(g, right, y + 4, L.t("wynnlabs.plan.fastest"), true, true) + 3;
        }
        CraftData.Recipe r = o.recipe();
        String title = L.t("wynnlabs.plan.recipe_line", typeName(o.type()), r.minLevel() + "–" + r.maxLevel(), thousands(r.xp()));
        text(g, Component.literal(fit(title, right - x - 19)).withStyle(UiFont::bold), x + 17, y + 4, Theme.INK);
        String count = o.crafts() < 0 ? L.t("wynnlabs.plan.crafts_unknown") : L.n("wynnlabs.plan.crafts", o.crafts(), big(o.crafts()));
        String cost = o.cost() != null ? "~" + Prices.fmt(o.cost()) : L.t("wynnlabs.plan.no_price");
        text(g, count, x + 17, y + 15, Theme.INK_2);
        text(g, cost, x + w - 5 - width(cost), y + 15, o.cost() != null ? Theme.PRICE : Theme.INK_3);
        List<String> mats = new ArrayList<>();
        o.materials().forEach((name, n) -> mats.add(big(n) + "x " + name));
        String matLine = String.join(", ", mats);
        fine(g, fitFine(matLine, w - 22), x + 17, y + 26, Theme.INK_3);
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(matLine));
        if (!o.unpriced().isEmpty()) {
            tip.add(L.c("wynnlabs.plan.unpriced", String.join(", ", o.unpriced())));
        }
        tip.add(L.c("wynnlabs.plan.open_tip"));
        clipped(x, y, w, 36, () -> {
            tab.type = o.type();
            tab.minLevel = r.minLevel();
            tab.view = 0;
            tab.scroll = 0;
        }, null, tip);
    }

    /** {xp into the level, xp it needs} of a profession; {0, 0} when not known. */
    private static int[] xp(String profession) {
        int[] p = previewXp;
        return p != null ? p : Crafting.professionXp(profession);
    }

    /** A count with thousands separators. */
    private static String big(long v) {
        return v > Integer.MAX_VALUE ? String.valueOf(v) : thousands((int) v);
    }
}
