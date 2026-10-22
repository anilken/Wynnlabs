package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.advice.Advice;
import io.github.anilken.wynnlabs.build.TreePlanner;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.game.Wyn;
import io.github.anilken.wynnlabs.model.AbilityNode;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The ability tree tab: the tree as the game lays it out, with the plan's order on its nodes. */
public final class TreeTab extends MenuTab {

    TreeTab(RoadmapScreen menu) {
        super(menu);
    }

    // ---- the tab ----

    @Override
    List<EditBox> init() {
        String treeTyped = treeSearch == null ? "" : treeSearch.getValue();
        treeSearch = new EditBox(font(), 0, 0, 100, 12, L.c("wynnlabs.tree.search_hint"));
        treeSearch.setMaxLength(40);
        treeSearch.setValue(treeTyped);
        treeSearch.setResponder(v -> treeJump = true);
        paperField(treeSearch);
        return List.of(treeSearch);
    }

    @Override
    void draw(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float pt) {
        drawTree(g, x, y, w, h, mx, my, pt);
    }

    @Override
    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (treeSearch.mouseClicked(event, doubleClick)) {
            menu.setFocused(treeSearch);
            return true;
        }
        for (MenuHit h : menu.hits) {
            if (h.copy() != null && h.copy().startsWith("#tree") && in(event.x(), event.y(), new int[] {h.x(), h.y(), h.w(), h.h()})) {
                treeClick(h.copy());
                return true;
            }
        }
        return false;
    }

    /** The tab's clicks are its own: the rest go to the screen's widgets. */
    @Override
    boolean sharedClicks() {
        return false;
    }

    @Override
    void mouseScrolled(double x, double y, double v) {
        treeScroll = Math.max(0, Math.min(treeScrollMax, treeScroll - treeCell * (int) Math.signum(v)));
    }

    // ---- ability tree ----

    /** The search over the tree (its first match opens). */
    public EditBox treeSearch;
    private int treeScroll;
    /** Room above the tree's first row, so the root and the "s. 1" label are not cut at the top. */
    private static final int TREE_TOP = 6;
    private int treeScrollMax;
    private int treeCell = 30;
    private String treeFocus = "";              // the next pick the view was last opened on
    private boolean treeJump;                   // the search changed: open the first match
    private TreeView.View treeView;
    private String treeKey = "";
    private List<TreePlanner.Step> treePlan = List.of();
    private String treePlanKey = "";
    private TreeLayout.Lines treeLines;
    private String treeLinesKey = "";
    private Map<String, boolean[]> treeLit = Map.of();
    private Map<String, boolean[]> treePlanArms = Map.of();
    private String treeLitKey = "";

    /** Which arms are lit (between unlocked nodes) and which carry the plan, for the view. */
    private void treeArms(List<AbilityNode> tree, TreeView.View v, Map<String, boolean[]> pieces) {
        java.util.Set<Integer> have = new java.util.HashSet<>();
        java.util.Set<Integer> plan = new java.util.HashSet<>();
        for (TreeView.Node n : v.nodes()) {
            if (n.state() == TreeView.State.UNLOCKED) {
                have.add(n.node().id());
            }
            if (n.state() == TreeView.State.UNLOCKED || n.state() == TreeView.State.NEXT || n.state() == TreeView.State.PLANNED) {
                plan.add(n.node().id());
            }
        }
        String key = have.hashCode() + "|" + plan.hashCode() + "|" + System.identityHashCode(pieces) + "|" + System.identityHashCode(tree);
        if (!key.equals(treeLitKey)) {
            treeLit = TreeLayout.lit(tree, pieces, have);
            treePlanArms = TreeLayout.lit(tree, pieces, plan);
            treeLitKey = key;
        }
    }

    /** The tree's lines, from the pieces seen in the game; rebuilt when the tree or the pieces change. */
    private TreeLayout.Lines treeLines(List<AbilityNode> tree) {
        Map<String, boolean[]> pieces = TreeConnectors.get(GameState.current().classType());
        String key = tree.size() + "|" + System.identityHashCode(tree) + "|" + pieces.size() + "|" + System.identityHashCode(pieces);
        if (treeLines == null || !key.equals(treeLinesKey)) {
            treeLines = TreeLayout.lines(tree, pieces);
            treeLinesKey = key;
        }
        return treeLines;
    }

    /** The view, rebuilt when the tree, the unlocked nodes, the archetype or the search change. */
    private TreeView.View treeView(List<AbilityNode> tree) {
        GameState gs = GameState.current();
        List<String> unlocked = gs.unlockedAbilities();
        String chosen = TreePlanner.chosen();
        int level = gs.level();
        String planKey = chosen + "|" + level + "|" + tree.size() + "|" + System.identityHashCode(tree);
        if (!planKey.equals(treePlanKey)) {
            treePlan = chosen.isEmpty() ? List.of() : TreePlanner.wholePlan(chosen);
            treePlanKey = planKey;
        }
        String q = treeSearch == null ? "" : treeSearch.getValue();
        String key = planKey + "|" + q + "|" + unlocked.hashCode();
        if (treeView == null || !key.equals(treeKey)) {
            int total = Math.max(Wyn.abilityPointsAt(level), TreePlanner.basePoints(level));
            treeView = TreeView.build(tree, unlocked, treePlan, total, q);
            treeKey = key;
        }
        return treeView;
    }

    /**
     * The ability tree as the game lays it out: 9 columns, the pages one under the other in one
     * scrolling column, lines between connected nodes, the plan's order on the nodes. Archetype
     * and points on top with the search, the legends on the right, page buttons below.
     */
    private void drawTree(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float pt) {
        List<AbilityNode> tree = GameState.current().abilityTree();
        if (tree == null || tree.isEmpty()) {
            emptyState(g, x, y, w, h, new ItemStack(net.minecraft.world.item.Items.OAK_SAPLING),
                    L.t("wynnlabs.tree.unknown_title"), L.t("wynnlabs.tree.unknown_1"), L.t("wynnlabs.tree.unknown_2"));
            return;
        }
        TreeView.View v = treeView(tree);
        String chosen = TreePlanner.chosen();
        // 1. the top bar: archetype, points, the next pick, search
        int cy = y;
        String archLabel = chosen.isEmpty() ? L.t("wynnlabs.tree.arch_off") : chosen;
        int ax = x + pickChip(g, x, cy, "sparkles", archLabel, !chosen.isEmpty(), mx, my, "#tree:arch") + 8;
        String pts = v.afterPlan() > v.spent() ? L.t("wynnlabs.tree.points_plan", v.spent(), v.total(), v.afterPlan())
                : L.t("wynnlabs.tree.points", v.spent(), v.total());
        ink(g, pts, ax, cy + 3, Theme.INK_2);
        ax += font().width(pts) + 10;
        if (v.next() != null) {
            ink(g, L.t("wynnlabs.tree.next_at", v.nextLevel()), ax, cy + 3, Theme.ACCENT);
        }
        int sw = 104;
        textBox(g, treeSearch, x + w - sw, cy + 1, sw, "wynnlabs.tree.search_hint", mx, my, pt);
        cy += 18;
        // 2. the panel: square cells, 9 across, every page under the one before
        int legendW = 124;
        int cell = Math.max(18, Math.min(34, (w - legendW - 14) / TreeLayout.COLS));
        treeCell = cell;
        int pw = cell * TreeLayout.COLS + 6;
        int ph = h - (cy - y) - 18;
        int px = x;
        int py = cy;
        Kit.slate(g, px, py, pw, ph);
        int first = TreeLayout.firstPage(tree);
        int pages = TreeLayout.pages(tree);
        int contentH = TreeLayout.rows(tree) * cell + 6 + TREE_TOP;
        treeScrollMax = Math.max(0, contentH - (ph - 2));
        // open on the next pick; the search opens its first match
        if (v.next() != null && !v.next().name().equals(treeFocus)) {
            treeFocus = v.next().name();
            // centred on it, rounded to whole rows so no node is cut at the top
            treeScroll = Math.max(0, TreeLayout.row(v.next(), first) * cell + cell / 2 - (ph - 2) / 2) / cell * cell;
        }
        if (treeJump) {
            treeJump = false;
            if (!treeSearch.getValue().isBlank()) {
                for (TreeView.Node n : v.nodes()) {
                    if (n.matches() && n.node().where() != null) {
                        treeScroll = TreeLayout.row(n.node(), first) * cell + cell / 2 - (ph - 2) / 2;
                        break;
                    }
                }
            }
        }
        treeScroll = Math.max(0, Math.min(treeScrollMax, treeScroll));
        int ox = px + 3;
        int oy = py + 3 + TREE_TOP - treeScroll;
        Map<Integer, TreeView.Node> byId = new HashMap<>();
        for (TreeView.Node n : v.nodes()) {
            byId.put(n.node().id(), n);
        }
        g.enableScissor(px + 1, py + 1, px + pw - 1, py + ph - 1);
        // the board's faint grid, then the pages: a line where each one starts, its number on it
        int rows = TreeLayout.rows(tree);
        for (int c = 0; c <= TreeLayout.COLS; c++) {
            g.fill(ox + c * cell, oy, ox + c * cell + 1, oy + rows * cell, 0x10FFFFFF);
        }
        for (int r = 0; r <= rows; r++) {
            g.fill(ox, oy + r * cell, ox + TreeLayout.COLS * cell, oy + r * cell + 1, 0x10FFFFFF);
        }
        for (int p = 0; p < pages; p++) {
            int ly = oy + p * TreeLayout.ROWS * cell;
            if (p > 0) {
                g.fill(px + 1, ly - 1, px + pw - 1, ly + 1, 0x50FFFFFF);
            }
            String label = L.t("wynnlabs.tree.page_short", p + 1);
            int lw2 = font().width(label);
            g.fill(px + pw - 9 - lw2, ly - 5, px + pw - 3, ly + 5, 0xC0262B30);
            g.drawString(font(), label, px + pw - 6 - lw2, ly - 4, 0xB0FFFFFF, false);
        }
        // the lines, under the nodes: the game's pieces where a page was seen, routed around
        // the nodes elsewhere; lit between unlocked nodes, the archetype's colour along the
        // plan, grey elsewhere
        TreeLayout.Lines tl = treeLines(tree);
        int thick = cell >= 28 ? 3 : 2;
        // the game's own pieces, each arm lit only on the way between two unlocked nodes, as
        // the game lights them; their lines are left out then, the plan drawn thin over them
        io.github.anilken.wynnlabs.model.Klass cls = GameState.current().classType();
        Map<String, boolean[]> pieces = TreeConnectors.get(cls);
        treeArms(tree, v, pieces);
        boolean pictured = false;
        for (var pc : tl.pieces().entrySet()) {
            String[] k = pc.getKey().split(":");
            int pr = (Integer.parseInt(k[0]) - first) * TreeLayout.ROWS + Integer.parseInt(k[1]);
            int pcx = Integer.parseInt(k[2]);
            boolean[] arms = pieces.get(pc.getKey());
            boolean[] litArms = treeLit.getOrDefault(pc.getKey(), new boolean[4]);
            ItemStack pic = arms == null ? null : Wyn.connectorIcon(arms, litArms);
            if (pic == null) {
                continue;
            }
            pictured = true;
            int py2 = oy + pr * cell;
            if (py2 + cell < py || py2 > py + ph) {
                continue;
            }
            float sc = cell / 16f;
            g.pose().pushMatrix();
            g.pose().translate(ox + pcx * cell, py2);
            g.pose().scale(sc, sc);
            g.renderItem(pic, 0, 0);
            g.pose().popMatrix();
        }
        for (TreeLayout.Line ln : tl.lines()) {
            int colour;
            int t = thick;
            if (ln.piece()) {
                boolean[] la = treeLit.get(ln.cell());
                boolean[] pa = treePlanArms.get(ln.cell());
                boolean lit = la != null && la[ln.arm()];
                boolean plan = pa != null && pa[ln.arm()];
                if (pictured) {
                    if (lit || !plan) {
                        continue;                   // the picture shows it
                    }
                    t = Math.max(1, thick - 1);     // the plan, thin over the dark piece
                }
                colour = lit ? 0xFFE8DCB4 : plan ? planColour(tl.ends().get(ln.group()), byId) : 0xFF4A4E56;
            } else {
                colour = stretchColour(tl.ends().get(ln.group()), byId);
            }
            int x0 = ox + Math.min(ln.c0(), ln.c1()) * cell / 2;
            int x1 = ox + Math.max(ln.c0(), ln.c1()) * cell / 2;
            int y0 = oy + Math.min(ln.r0(), ln.r1()) * cell / 2;
            int y1 = oy + Math.max(ln.r0(), ln.r1()) * cell / 2;
            if (ln.horizontal()) {
                g.fill(x0, y0 - t / 2, x1, y0 - t / 2 + t, colour);
            } else {
                g.fill(x0 - t / 2, y0, x0 - t / 2 + t, y1, colour);
            }
        }
        // the nodes: the game's own icon when the pack gives one, else our shapes; a ring for
        // the state, the plan's number, the blocked mark; what the search misses goes dark
        long now = System.currentTimeMillis();
        TreeView.Node hover = null;
        int[] hoverRect = null;
        int size = cell - 6;
        for (TreeView.Node n : v.nodes()) {
            AbilityNode a = n.node();
            if (a.where() == null) {
                continue;
            }
            int nx = ox + a.where().col() * cell;
            int ny = oy + TreeLayout.row(a, first) * cell;
            if (ny + cell < py || ny > py + ph) {
                continue;
            }
            int ix = nx + 3;
            int iy = ny + 3;
            ItemStack icon = Wyn.nodeIcon(a.type(), n.iconState());
            int fill = switch (n.state()) {
                case UNLOCKED, NEXT -> n.colour();
                case PLANNED -> mix(n.colour(), 0xFF1E1A22, 0.55f);
                case AVAILABLE -> 0xFF2A2530;
                case BLOCKED -> 0xFF4A4452;
                default -> 0xFF2C2C2A;
            };
            String shape = n.ultimate() ? "tree-diamond" : "tree-circle";
            if (icon != null) {
                // the game's picture, a little inside its cell (its glow already reaches out)
                float sc = size / 16f;
                g.pose().pushMatrix();
                g.pose().translate(ix, iy);
                g.pose().scale(sc, sc);
                g.renderItem(icon, 0, 0);
                g.pose().popMatrix();
            } else {
                Art.icon(g, shape, ix, iy, size, fill);
            }
            String ring = shape + "-ring";
            // the game's picture shows unlocked and unlockable itself: only the next pick gets a ring
            switch (icon != null && n.state() != TreeView.State.NEXT ? TreeView.State.LOCKED : n.state()) {
                case UNLOCKED -> Art.icon(g, ring, ix - 1, iy - 1, size + 2, 0xFFEEEDFE);
                case NEXT -> {
                    int alpha = 110 + (int) (145 * (0.5 + 0.5 * Math.sin(now / 1000.0 * Math.PI * 2)));
                    Art.icon(g, ring, ix - 2, iy - 2, size + 4, alpha << 24 | 0xFFE071);
                }
                case AVAILABLE -> Art.icon(g, ring, ix - 1, iy - 1, size + 2, n.colour());
                default -> { }
            }
            if (n.step() > 0) {
                String mark = String.valueOf(n.step());
                g.drawString(font(), mark, nx + (cell - font().width(mark)) / 2, ny + (cell - 8) / 2,
                        n.state() == TreeView.State.NEXT ? 0xFFFFE071 : 0xFFFFFFFF, true);
            } else if (n.state() == TreeView.State.BLOCKED) {
                Art.icon(g, "x", nx + (cell - 8) / 2, ny + (cell - 8) / 2, 8, 0xFFFFFFFF);
            }
            if (!n.matches()) {
                g.fill(nx, ny, nx + cell, ny + cell, 0xB01E1A22);
            }
            if (mx >= nx && mx < nx + cell && my >= Math.max(ny, py) && my < Math.min(ny + cell, py + ph)) {
                hover = n;
                hoverRect = new int[] {nx, Math.max(ny, py), cell, Math.min(ny + cell, py + ph) - Math.max(ny, py)};
            }
        }
        g.disableScissor();
        if (hover != null) {
            menu.hits.add(new MenuHit(hoverRect[0], hoverRect[1], hoverRect[2], hoverRect[3], null, treeTip(hover, v), null));
        }
        // 3. the page buttons: the one in view is lit; a click jumps
        int by = y + h - 14;
        int bx = x;
        // the page at the top of the view; at the end of the scroll, the last page that starts in it
        int pageH = Math.max(1, TreeLayout.ROWS * cell);
        int page = Math.min(pages - 1, Math.max(0, (treeScroll + cell / 2) / pageH));
        if (treeScroll >= treeScrollMax) {
            while (page + 1 < pages && TREE_TOP + (page + 1) * pageH < treeScroll + ph - 2) {
                page++;
            }
        }
        for (int p = 0; p < pages; p++) {
            bx += pickChip(g, bx, by, L.t("wynnlabs.tree.page", p + 1), p == page, mx, my, "#tree:page:" + p) + 3;
        }
        String hint = L.t("wynnlabs.tree.wheel_hint");
        if (bx + 6 + font().width(hint) <= px + pw) {
            ink(g, hint, bx + 6, by + 3, FAINT);
        }
        // 4. the legends: each archetype's points now (and after the plan), then the states
        int lx = px + pw + 8;
        int lw = x + w - lx;
        int ly = py;
        int ah = 18 + v.archetypes().size() * 11 + 2;
        card(g, lx, ly, lw, ah, false, Theme.GOLD_DEEP);
        ink(g, Component.literal(L.t("wynnlabs.tree.legend_arch")).withStyle(UiFont::bold), lx + 6, ly + 5, Theme.INK);
        int ry = ly + 17;
        for (TreeView.Archetype a : v.archetypes()) {
            g.fill(lx + 7, ry + 1, lx + 13, ry + 7, a.colour());
            String t = a.after() > a.now() ? a.now() + " → " + a.after() : String.valueOf(a.now());
            ink(g, fit(a.name(), lw - 24 - font().width(t)), lx + 17, ry, Theme.INK_2);
            ink(g, t, lx + lw - 6 - font().width(t), ry, Theme.INK_2);
            ry += 11;
        }
        ly += ah + 6;
        String[] states = {"wynnlabs.tree.state_unlocked", "wynnlabs.tree.state_next", "wynnlabs.tree.state_planned",
            "wynnlabs.tree.state_available", "wynnlabs.tree.state_blocked", "wynnlabs.tree.state_locked"};
        int[] sample = {0xFF7F77DD, 0xFFFFE071, mix(0xFF7F77DD, 0xFF1E1A22, 0.55f), 0xFF2A2530, 0xFF4A4452, 0xFF2C2C2A};
        // one column; two when that is too tall (a short window), so the states still show
        int stateCols = ly + 18 + states.length * 11 + 2 <= py + ph ? 1 : 2;
        int perCol = (states.length + stateCols - 1) / stateCols;
        int sh = 18 + perCol * 11 + 2;
        int scw = (lw - 6) / stateCols;
        if (ly + sh <= py + ph) {
            card(g, lx, ly, lw, sh, false, Theme.GOLD_DEEP);
            ink(g, Component.literal(L.t("wynnlabs.tree.legend_states")).withStyle(UiFont::bold), lx + 6, ly + 5, Theme.INK);
            for (int i = 0; i < states.length; i++) {
                int sx = lx + (i / perCol) * scw;
                ry = ly + 17 + (i % perCol) * 11;
                Art.icon(g, "tree-circle", sx + 6, ry - 1, 9, sample[i]);
                if (i == 0 || i == 1 || i == 3) {
                    Art.icon(g, "tree-circle-ring", sx + 6, ry - 1, 9, i == 0 ? 0xFFEEEDFE : i == 1 ? 0xFFFFE071 : 0xFF7F77DD);
                }
                ink(g, fit(L.t(states[i]), scw - 20), sx + 18, ry, Theme.INK_2);
            }
        }
    }

    /** The plan's colour on a stretch: the archetype of a planned node it joins. */
    private static int planColour(java.util.Set<Integer> ends, Map<Integer, TreeView.Node> byId) {
        for (int id : ends) {
            TreeView.Node n = byId.get(id);
            if (n != null && (n.state() == TreeView.State.NEXT || n.state() == TreeView.State.PLANNED)) {
                return mix(n.colour(), 0xFF262B30, 0.25f);
            }
        }
        return mix(0xFF7F77DD, 0xFF262B30, 0.25f);
    }

    /**
     * A stretch's colour from the nodes it joins: lit when two of them are unlocked, the
     * archetype's colour when two are unlocked or planned, grey otherwise.
     */
    private static int stretchColour(java.util.Set<Integer> ends, Map<Integer, TreeView.Node> byId) {
        int unlocked = 0;
        int plan = 0;
        int colour = 0;
        for (int id : ends) {
            TreeView.Node n = byId.get(id);
            if (n == null) {
                continue;
            }
            if (n.state() == TreeView.State.UNLOCKED) {
                unlocked++;
                plan++;
            } else if (n.state() == TreeView.State.NEXT || n.state() == TreeView.State.PLANNED) {
                plan++;
                colour = n.colour();
            }
        }
        if (unlocked >= 2) {
            return 0xFFE8DCB4;
        }
        if (plan >= 2) {
            return mix(colour == 0 ? 0xFF7F77DD : colour, 0xFF262B30, 0.35f);
        }
        return 0xFF4A4E56;
    }

    /** {@code a} towards {@code b} by {@code t} (0: a, 1: b), opaque. */
    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int gr = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | r << 16 | gr << 8 | bl;
    }

    /** A node's tooltip: name, description, cost and requirements, what it locks, its state. */
    private List<Component> treeTip(TreeView.Node n, TreeView.View v) {
        AbilityNode a = n.node();
        List<Component> tip = new ArrayList<>();
        net.minecraft.network.chat.MutableComponent name = Component.literal(a.name()).withStyle(ChatFormatting.WHITE).withStyle(UiFont::bold);
        if (n.ultimate()) {
            name.append(Component.literal(" · " + L.t("wynnlabs.tree.ultimate")).withStyle(ChatFormatting.GOLD).withStyle(UiFont::bold));
        }
        tip.add(name);
        if (a.description() != null) {
            for (var line : a.description()) {
                for (String l : Advice.wrap(TreeView.plain(line), 46)) {
                    tip.add(Component.literal(l).withStyle(ChatFormatting.GRAY));
                }
            }
        }
        tip.add(Component.empty());
        String cost = L.n("wynnlabs.tree.tip_cost", a.cost());
        if (a.requiredLevel() > 0) {
            cost += " · " + L.t("wynnlabs.tree.tip_level", a.requiredLevel());
        }
        tip.add(Component.literal(cost).withStyle(ChatFormatting.GREEN));
        if (a.archetype() != null && !a.archetype().isBlank()) {
            tip.add(Component.literal(L.t("wynnlabs.tree.tip_archetype", a.archetype()))
                    .withStyle(st -> st.withColor(n.colour() & 0xFFFFFF)));
        }
        String req = a.requiredAbility();
        if (req != null && !req.isBlank() && !req.equals("None")) {
            tip.add(Component.literal(L.t("wynnlabs.tree.tip_requires", req)).withStyle(ChatFormatting.YELLOW));
        }
        if (a.requiredArchetype() != null && !a.requiredArchetype().isBlank()) {
            tip.add(Component.literal(L.n("wynnlabs.tree.tip_arch_points", a.requiredArchetypePoints(), a.requiredArchetype(), a.requiredArchetypePoints()))
                    .withStyle(ChatFormatting.YELLOW));
        }
        if (a.willBlock() != null && !a.willBlock().isEmpty()) {
            tip.add(Component.literal(L.t("wynnlabs.tree.tip_blocks", String.join(", ", a.willBlock()))).withStyle(ChatFormatting.RED));
        }
        int planned = 0;
        for (TreeView.Node o : v.nodes()) {
            planned = Math.max(planned, o.step());
        }
        String state = switch (n.state()) {
            case UNLOCKED -> L.t("wynnlabs.tree.state_unlocked");
            case NEXT -> L.t("wynnlabs.tree.tip_next") + " · " + L.t("wynnlabs.tree.tip_plan", n.step(), planned);
            case PLANNED -> L.t("wynnlabs.tree.tip_plan", n.step(), planned);
            case AVAILABLE -> L.t("wynnlabs.tree.state_available");
            case BLOCKED -> L.t("wynnlabs.tree.state_blocked");
            default -> L.t("wynnlabs.tree.state_locked");
        };
        tip.add(Component.literal(state).withStyle(n.state() == TreeView.State.NEXT || n.state() == TreeView.State.PLANNED
                ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.DARK_GRAY));
        return tip;
    }

    /** A click on the tab: the archetype chip or a page button. */
    private void treeClick(String action) {
        if (action.equals("#tree:arch")) {
            TreePlanner.cycle();
            String a = TreePlanner.chosen();
            menu.flash(L.t("wynnlabs.home.archetype_set", a.isEmpty() ? L.t("wynnlabs.settings.archetype_off") : a));
            treeFocus = "";                         // the view opens on the new plan's next pick
            menu.rebuildMap();                      // the build follows the archetype: rebuilt in the background
        } else if (action.startsWith("#tree:page:")) {
            treeScroll = Integer.parseInt(action.substring(11)) * TreeLayout.ROWS * treeCell;
        }
    }
}
