package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.AbilityNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Where the ability tree's nodes and lines go on a grid. The game's tree menu is pages of
 * 9 columns by 6 rows; here the pages stand one under the other, so the whole tree is one
 * column of {@code rows()} rows that the J menu scrolls.
 *
 * <p>The lines come from the game's own connector pieces where a page has been seen in the
 * tree menu ({@link TreeConnectors}); on pages not seen yet a connection is routed over the
 * free cells, never through another node. Line ends are in half cells (two per cell, a cell's
 * centre at 2r+1, 2c+1), so a line can stop at a cell's edge.
 */
final class TreeLayout {

    static final int COLS = 9;
    static final int ROWS = 6;

    /** Connector directions, in the game's order. */
    static final int UP = 0;
    static final int RIGHT = 1;
    static final int DOWN = 2;
    static final int LEFT = 3;

    /**
     * A straight line between two half-cell points; {@code group} the stretch it belongs to.
     * A line one of the game's pieces draws names the piece's cell ("page:row:col") and the
     * arm ({@link #UP} …), so the picture can stand in and the arm can be lit on its own; a
     * routed line has no cell and -1.
     */
    record Line(int r0, int c0, int r1, int c1, int group, String cell, int arm) {

        boolean horizontal() {
            return r0 == r1;
        }

        boolean piece() {
            return cell != null;
        }
    }

    /**
     * Every line, for each group the ids of the nodes the stretch joins, and each piece cell
     * ("page:row:col") with its group.
     */
    record Lines(List<Line> lines, List<Set<Integer>> ends, Map<String, Integer> pieces) {}

    /** A straight line between two cell centres: the same row, or the same column. */
    record Segment(int row0, int col0, int row1, int col1) {

        boolean horizontal() {
            return row0 == row1;
        }
    }

    private TreeLayout() {}

    /** The first page's number (1 in the game; the lowest seen, in case a tree starts later). */
    static int firstPage(List<AbilityNode> nodes) {
        int first = Integer.MAX_VALUE;
        for (AbilityNode n : nodes) {
            if (n.where() != null) {
                first = Math.min(first, n.where().page());
            }
        }
        return first == Integer.MAX_VALUE ? 1 : first;
    }

    /** How many pages the tree has. */
    static int pages(List<AbilityNode> nodes) {
        int first = firstPage(nodes);
        int last = first - 1;
        for (AbilityNode n : nodes) {
            if (n.where() != null) {
                last = Math.max(last, n.where().page());
            }
        }
        return Math.max(0, last - first + 1);
    }

    /** The grid's height in rows: every page's 6. */
    static int rows(List<AbilityNode> nodes) {
        return pages(nodes) * ROWS;
    }

    /** A node's row in the whole column, or -1 without a place. */
    static int row(AbilityNode n, int firstPage) {
        return n.where() == null ? -1 : (n.where().page() - firstPage) * ROWS + n.where().row();
    }

    /** The row a page starts at. */
    static int pageRow(int page, int firstPage) {
        return (page - firstPage) * ROWS;
    }

    /**
     * The naive line from one cell to another: one straight segment when they share a row or
     * a column, else down the first cell's column and then along the second's row.
     */
    static List<Segment> segments(int row0, int col0, int row1, int col1) {
        if (row0 == row1 || col0 == col1) {
            return List.of(new Segment(row0, col0, row1, col1));
        }
        return List.of(new Segment(row0, col0, row1, col0), new Segment(row1, col0, row1, col1));
    }

    private static long key(int row, int col) {
        return (long) row << 8 | col;
    }

    /** The connector key of a cell ({@link TreeConnectors}): "page:row:col". */
    static String connectorKey(int page, int row, int col) {
        return page + ":" + row + ":" + col;
    }

    /**
     * Every line of the tree. {@code connectors}: the connector cells seen in the game
     * ("page:row:col" → the piece's directions up, right, down, left), may be empty.
     */
    static Lines lines(List<AbilityNode> nodes, Map<String, boolean[]> connectors) {
        int first = firstPage(nodes);
        int rows = rows(nodes);
        Map<Long, AbilityNode> at = new HashMap<>();
        for (AbilityNode n : nodes) {
            if (n.where() != null) {
                at.put(key(row(n, first), n.where().col()), n);
            }
        }
        List<Line> lines = new ArrayList<>();
        List<Set<Integer>> ends = new ArrayList<>();
        // 1. the pieces seen in the game: each one's arms, joined into stretches
        Map<Long, boolean[]> cells = new HashMap<>();
        Map<Long, String> cellKeys = new HashMap<>();
        Set<Integer> seenPages = new HashSet<>();
        for (var e : connectors.entrySet()) {
            String[] p = e.getKey().split(":");
            if (p.length != 3 || e.getValue() == null || e.getValue().length < 4) {
                continue;
            }
            int page = Integer.parseInt(p[0]);
            int r = (page - first) * ROWS + Integer.parseInt(p[1]);
            int c = Integer.parseInt(p[2]);
            if (r < 0 || r >= rows || c < 0 || c >= COLS || at.containsKey(key(r, c))) {
                continue;
            }
            cells.put(key(r, c), e.getValue());
            cellKeys.put(key(r, c), e.getKey());
            seenPages.add(page);
        }
        Map<Long, Integer> group = new HashMap<>();
        for (long k : cells.keySet()) {
            if (group.containsKey(k)) {
                continue;
            }
            // flood this stretch: a piece joins its neighbour when both point at each other
            int g = ends.size();
            Set<Integer> endIds = new HashSet<>();
            ends.add(endIds);
            java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
            queue.add(k);
            group.put(k, g);
            while (!queue.isEmpty()) {
                long cur = queue.poll();
                int r = (int) (cur >> 8);
                int c = (int) (cur & 0xFF);
                boolean[] d = cells.get(cur);
                for (int dir = 0; dir < 4; dir++) {
                    if (!d[dir]) {
                        continue;
                    }
                    int nr = r + (dir == DOWN ? 1 : dir == UP ? -1 : 0);
                    int nc = c + (dir == RIGHT ? 1 : dir == LEFT ? -1 : 0);
                    long nk = key(nr, nc);
                    AbilityNode node = at.get(nk);
                    if (node != null) {
                        endIds.add(node.id());
                        continue;
                    }
                    boolean[] nd = cells.get(nk);
                    if (nd != null && nd[(dir + 2) % 4] && !group.containsKey(nk)) {
                        group.put(nk, g);
                        queue.add(nk);
                    }
                }
            }
        }
        Map<String, Integer> pieceGroups = new HashMap<>();
        for (var e : cells.entrySet()) {
            int r = (int) (e.getKey() >> 8);
            int c = (int) (e.getKey() & 0xFF);
            int g = group.get(e.getKey());
            pieceGroups.put(cellKeys.get(e.getKey()), g);
            boolean[] d = e.getValue();
            for (int dir = 0; dir < 4; dir++) {
                if (!d[dir]) {
                    continue;
                }
                int er = 2 * r + 1 + (dir == DOWN ? 1 : dir == UP ? -1 : 0);
                int ec = 2 * c + 1 + (dir == RIGHT ? 1 : dir == LEFT ? -1 : 0);
                String cellKey = cellKeys.get(e.getKey());
                lines.add(new Line(2 * r + 1, 2 * c + 1, er, ec, g, cellKey, dir));
                int nr = r + (dir == DOWN ? 1 : dir == UP ? -1 : 0);
                int nc = c + (dir == RIGHT ? 1 : dir == LEFT ? -1 : 0);
                if (at.containsKey(key(nr, nc))) {
                    lines.add(new Line(er, ec, 2 * nr + 1, 2 * nc + 1, g, cellKey, dir));     // into the node
                }
            }
        }
        // 2. pages not seen yet: each connection routed over free cells
        Set<Long> done = new HashSet<>();
        Map<Integer, AbilityNode> byId = new HashMap<>();
        for (AbilityNode n : nodes) {
            byId.put(n.id(), n);
        }
        for (AbilityNode a : nodes) {
            if (a.where() == null || a.connections() == null) {
                continue;
            }
            for (int id : a.connections()) {
                AbilityNode b = byId.get(id);
                if (b == null || b.where() == null) {
                    continue;
                }
                if (seenPages.contains(a.where().page()) && seenPages.contains(b.where().page())) {
                    continue;                           // the pieces draw it
                }
                long pair = (long) Math.min(a.id(), b.id()) << 32 | Math.max(a.id(), b.id());
                if (!done.add(pair)) {
                    continue;
                }
                int g = ends.size();
                ends.add(new HashSet<>(List.of(a.id(), b.id())));
                List<int[]> path = route(row(a, first), a.where().col(), row(b, first), b.where().col(), at, rows);
                for (int i = 0; i + 1 < path.size(); i++) {
                    lines.add(new Line(2 * path.get(i)[0] + 1, 2 * path.get(i)[1] + 1,
                            2 * path.get(i + 1)[0] + 1, 2 * path.get(i + 1)[1] + 1, g, null, -1));
                }
            }
        }
        return new Lines(lines, ends, pieceGroups);
    }

    /**
     * The arms that are lit: for every connection between two nodes in {@code ids}, the way
     * the pieces run from one to the other, arm by arm ("page:row:col" → up, right, down,
     * left), as the game lights its connectors. A piece's other arms stay dark.
     */
    static Map<String, boolean[]> lit(List<AbilityNode> nodes, Map<String, boolean[]> connectors, Set<Integer> ids) {
        int first = firstPage(nodes);
        int rows = rows(nodes);
        Map<Long, AbilityNode> at = new HashMap<>();
        Map<Integer, AbilityNode> byId = new HashMap<>();
        for (AbilityNode n : nodes) {
            byId.put(n.id(), n);
            if (n.where() != null) {
                at.put(key(row(n, first), n.where().col()), n);
            }
        }
        Map<Long, boolean[]> cells = new HashMap<>();
        Map<Long, String> cellKeys = new HashMap<>();
        for (var e : connectors.entrySet()) {
            String[] p = e.getKey().split(":");
            if (p.length != 3 || e.getValue() == null || e.getValue().length < 4) {
                continue;
            }
            int r = (Integer.parseInt(p[0]) - first) * ROWS + Integer.parseInt(p[1]);
            int c = Integer.parseInt(p[2]);
            if (r < 0 || r >= rows || c < 0 || c >= COLS || at.containsKey(key(r, c))) {
                continue;
            }
            cells.put(key(r, c), e.getValue());
            cellKeys.put(key(r, c), e.getKey());
        }
        Map<String, boolean[]> out = new HashMap<>();
        int[] dr = {-1, 0, 1, 0};
        int[] dc = {0, 1, 0, -1};
        for (AbilityNode a : nodes) {
            if (!ids.contains(a.id()) || a.where() == null || a.connections() == null) {
                continue;
            }
            for (int id : a.connections()) {
                AbilityNode b = byId.get(id);
                if (b == null || !ids.contains(id) || b.where() == null) {
                    continue;
                }
                // breadth first from a's cell over the pieces whose arms join, to b's cell
                long start = key(row(a, first), a.where().col());
                long goal = key(row(b, first), b.where().col());
                Map<Long, long[]> from = new HashMap<>();      // cell → {previous cell, arm taken from it}
                java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
                queue.add(start);
                from.put(start, null);
                boolean found = false;
                while (!queue.isEmpty() && !found) {
                    long cur = queue.poll();
                    int r = (int) (cur >> 8);
                    int c = (int) (cur & 0xFF);
                    boolean[] arms = cur == start ? new boolean[] {true, true, true, true} : cells.get(cur);
                    for (int dir = 0; dir < 4 && !found; dir++) {
                        if (arms == null || !arms[dir]) {
                            continue;
                        }
                        long nk = key(r + dr[dir], c + dc[dir]);
                        if (from.containsKey(nk)) {
                            continue;
                        }
                        if (nk == goal) {
                            from.put(nk, new long[] {cur, dir});
                            found = true;
                        } else {
                            boolean[] next = cells.get(nk);
                            if (next != null && next[(dir + 2) % 4]) {
                                from.put(nk, new long[] {cur, dir});
                                queue.add(nk);
                            }
                        }
                    }
                }
                if (!found) {
                    continue;
                }
                for (long[] step = from.get(goal); step != null; step = from.get(step[0])) {
                    long cell = step[0];
                    int dir = (int) step[1];
                    if (cells.containsKey(cell)) {
                        out.computeIfAbsent(cellKeys.get(cell), k -> new boolean[4])[dir] = true;
                    }
                    long[] before = from.get(cell);
                    if (before != null && cells.containsKey(cell)) {
                        out.get(cellKeys.get(cell))[((int) before[1] + 2) % 4] = true;    // the arm it came in by
                    }
                }
            }
        }
        return out;
    }

    /**
     * The cells from one node to another, around every other node, with as few turns as the
     * grid allows (a turn costs three steps). The naive two-segment line when there is no way.
     */
    static List<int[]> route(int r0, int c0, int r1, int c1, Map<Long, AbilityNode> at, int rows) {
        if (r0 == r1 && c0 == c1) {
            return List.of(new int[] {r0, c0});
        }
        // Dijkstra over (row, col, heading)
        int[] dr = {-1, 0, 1, 0};
        int[] dc = {0, 1, 0, -1};
        int states = rows * COLS * 4;
        int[] cost = new int[states];
        int[] from = new int[states];
        java.util.Arrays.fill(cost, Integer.MAX_VALUE);
        java.util.Arrays.fill(from, -1);
        PriorityQueue<int[]> queue = new PriorityQueue<>((x, y) -> Integer.compare(x[1], y[1]));
        for (int h = 0; h < 4; h++) {
            int s = (r0 * COLS + c0) * 4 + h;
            cost[s] = 0;
            queue.add(new int[] {s, 0});
        }
        int goal = -1;
        while (!queue.isEmpty()) {
            int[] top = queue.poll();
            int s = top[0];
            if (top[1] > cost[s]) {
                continue;
            }
            int r = s / 4 / COLS;
            int c = s / 4 % COLS;
            int h = s % 4;
            if (r == r1 && c == c1) {
                goal = s;
                break;
            }
            for (int nh = 0; nh < 4; nh++) {
                int nr = r + dr[nh];
                int nc = c + dc[nh];
                if (nr < 0 || nr >= rows || nc < 0 || nc >= COLS) {
                    continue;
                }
                if (at.containsKey(key(nr, nc)) && !(nr == r1 && nc == c1)) {
                    continue;                           // never through another node
                }
                int ns = (nr * COLS + nc) * 4 + nh;
                int nCost = cost[s] + 1 + (nh != h ? 3 : 0);
                if (nCost < cost[ns]) {
                    cost[ns] = nCost;
                    from[ns] = s;
                    queue.add(new int[] {ns, nCost});
                }
            }
        }
        List<int[]> path = new ArrayList<>();
        if (goal < 0) {
            for (Segment sg : segments(r0, c0, r1, c1)) {
                if (path.isEmpty()) {
                    path.add(new int[] {sg.row0(), sg.col0()});
                }
                path.add(new int[] {sg.row1(), sg.col1()});
            }
            return path;
        }
        for (int s = goal; s >= 0; s = from[s]) {
            int r = s / 4 / COLS;
            int c = s / 4 % COLS;
            if (path.isEmpty() || path.get(0)[0] != r || path.get(0)[1] != c) {
                path.add(0, new int[] {r, c});
            }
            if (r == r0 && c == c0) {
                break;
            }
        }
        // only the corners and the ends matter
        List<int[]> corners = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            if (i == 0 || i == path.size() - 1) {
                corners.add(path.get(i));
                continue;
            }
            int[] p = path.get(i - 1);
            int[] q = path.get(i + 1);
            if (p[0] != q[0] && p[1] != q[1]) {
                corners.add(path.get(i));
            }
        }
        return corners;
    }
}
