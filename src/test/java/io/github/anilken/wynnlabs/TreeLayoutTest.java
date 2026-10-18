package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.AbilityNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tree's grid: pages one under the other, lines straight or one turn. */
class TreeLayoutTest {

    @Test
    void pagesStandOneUnderTheOther() {
        List<AbilityNode> tree = SyntheticTree.nodes();
        assertEquals(2, TreeLayout.pages(tree));
        assertEquals(12, TreeLayout.rows(tree));
        int first = TreeLayout.firstPage(tree);
        assertEquals(1, first);
        // Totem: page 1 row 0; Awakened: page 2 row 2 → row 8 of the column
        assertEquals(0, TreeLayout.row(tree.get(0), first));
        assertEquals(8, TreeLayout.row(tree.get(14), first));
        assertEquals(6, TreeLayout.pageRow(2, first));
    }

    @Test
    void aDiagonalConnectionTurnsOnce() {
        List<TreeLayout.Segment> same = TreeLayout.segments(1, 4, 3, 4);
        assertEquals(1, same.size());
        assertTrue(!same.get(0).horizontal());
        List<TreeLayout.Segment> turn = TreeLayout.segments(1, 4, 2, 6);
        assertEquals(2, turn.size());
        assertEquals(new TreeLayout.Segment(1, 4, 2, 4), turn.get(0), "down the first column");
        assertEquals(new TreeLayout.Segment(2, 4, 2, 6), turn.get(1), "then along the second row");
        assertTrue(turn.get(1).horizontal());
    }

    @Test
    void routedLinesNeverCrossAnotherNode() {
        List<AbilityNode> tree = SyntheticTree.nodes();
        TreeLayout.Lines lines = TreeLayout.lines(tree, java.util.Map.of());
        // 17 connections, each its own stretch between its two nodes
        assertEquals(17, lines.ends().size());
        int first = TreeLayout.firstPage(tree);
        java.util.Map<Long, AbilityNode> at = new java.util.HashMap<>();
        for (AbilityNode n : tree) {
            at.put((long) TreeLayout.row(n, first) << 8 | n.where().col(), n);
        }
        for (TreeLayout.Line ln : lines.lines()) {
            assertTrue(ln.r0() == ln.r1() || ln.c0() == ln.c1(), "straight: " + ln);
            // every cell centre the line crosses is one of the stretch's own two nodes, or empty
            for (int r = Math.min(ln.r0(), ln.r1()); r <= Math.max(ln.r0(), ln.r1()); r++) {
                for (int c = Math.min(ln.c0(), ln.c1()); c <= Math.max(ln.c0(), ln.c1()); c++) {
                    if (r % 2 == 1 && c % 2 == 1) {
                        AbilityNode n = at.get((long) (r / 2) << 8 | c / 2);
                        assertTrue(n == null || lines.ends().get(ln.group()).contains(n.id()),
                                ln + " runs through " + (n == null ? "" : n.name()));
                    }
                }
            }
        }
        // Mask of the Coward (page 2 row 0 col 4) → Awakened (page 2 row 2 col 1): around nothing, one turn
        java.util.Set<Integer> pair = java.util.Set.of(13, 15);
        int g = lines.ends().indexOf(pair);
        assertTrue(g >= 0);
        long count = lines.lines().stream().filter(l -> l.group() == g).count();
        assertEquals(2, count, "two straight runs");
    }

    @Test
    void theGamesPiecesDrawTheLineTheyShow() {
        List<AbilityNode> tree = SyntheticTree.nodes();
        // Totemic Smash (row 1 col 4) to Haul (row 2 col 2): left along row 1, then down at column 2
        java.util.Map<String, boolean[]> pieces = new java.util.HashMap<>();
        pieces.put(TreeLayout.connectorKey(1, 1, 3), new boolean[] {false, true, false, true});
        pieces.put(TreeLayout.connectorKey(1, 1, 2), new boolean[] {false, true, true, false});
        TreeLayout.Lines lines = TreeLayout.lines(tree, pieces);
        int g = lines.ends().indexOf(java.util.Set.of(2, 3));
        assertTrue(g >= 0, "one stretch joins the two nodes");
        List<TreeLayout.Line> arms = lines.lines().stream().filter(l -> l.group() == g).toList();
        // two arms a piece, and the run into each node
        assertEquals(6, arms.size());
        String k13 = TreeLayout.connectorKey(1, 1, 3);
        String k12 = TreeLayout.connectorKey(1, 1, 2);
        assertTrue(arms.contains(new TreeLayout.Line(3, 7, 3, 8, g, k13, TreeLayout.RIGHT)), "the right arm of (1,3) reaches the cell's edge");
        assertTrue(arms.contains(new TreeLayout.Line(3, 8, 3, 9, g, k13, TreeLayout.RIGHT)), "and continues into Totemic Smash");
        assertTrue(arms.contains(new TreeLayout.Line(3, 5, 4, 5, g, k12, TreeLayout.DOWN)), "the down arm of (1,2)");
        assertTrue(arms.contains(new TreeLayout.Line(4, 5, 5, 5, g, k12, TreeLayout.DOWN)), "into Haul");
        assertEquals(Integer.valueOf(g), lines.pieces().get(k13), "the piece cell knows its stretch");
        // lit arm by arm: both nodes unlocked lights the way between them, one alone lights nothing
        java.util.Map<String, boolean[]> lit = TreeLayout.lit(tree, pieces, java.util.Set.of(2, 3));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new boolean[] {false, true, false, true}, lit.get(k13));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new boolean[] {false, true, true, false}, lit.get(k12));
        assertTrue(TreeLayout.lit(tree, pieces, java.util.Set.of(2)).isEmpty());
        // a three-way piece lights only the arms on the way
        pieces.put(TreeLayout.connectorKey(1, 1, 3), new boolean[] {false, true, true, true});
        lit = TreeLayout.lit(tree, pieces, java.util.Set.of(2, 3));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new boolean[] {false, true, false, true}, lit.get(k13), "the down arm stays dark");
        // page 1 was seen: its other connections are not routed any more, page 2's still are
        assertTrue(lines.ends().stream().noneMatch(e -> e.equals(java.util.Set.of(1, 2))), "Totem - Totemic Smash has no piece and no route");
        assertTrue(lines.ends().contains(java.util.Set.of(13, 15)), "page 2 is routed");
    }
}
