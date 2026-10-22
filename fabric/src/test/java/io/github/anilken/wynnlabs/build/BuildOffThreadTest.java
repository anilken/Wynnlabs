package io.github.anilken.wynnlabs.build;

import io.github.anilken.wynnlabs.game.FixtureRun;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The build plan worked out on another thread from a snapshot equals the one worked out in place. */
class BuildOffThreadTest {

    @Test
    void aPlanFromASnapshotOnAnotherThreadIsTheSame() throws Exception {
        FixtureRun.each("build off thread", List.of("en"), (fx, dir, lang) -> {
            List<String> diffs = new ArrayList<>();
            Build.Plan here = Build.compute();
            Build.Inputs in = Build.prepare();
            Build.Plan there = CompletableFuture.supplyAsync(() -> Build.compute(in)).get();
            if (here == null || there == null) {
                if (here != there) {
                    diffs.add("one plan is null");
                }
                return diffs;
            }
            if (Math.abs(here.result().objective() - there.result().objective()) > 1e-9) {
                diffs.add("objective " + here.result().objective() + " vs " + there.result().objective());
            }
            if (!Arrays.toString(here.allocation()).equals(Arrays.toString(there.allocation()))) {
                diffs.add("allocation " + Arrays.toString(here.allocation()) + " vs " + Arrays.toString(there.allocation()));
            }
            here.pieces().forEach((slot, p) -> {
                Damage.Piece q = there.pieces().get(slot);
                String a = p == null ? null : p.key();
                String b = q == null ? null : q.key();
                if (!java.util.Objects.equals(a, b)) {
                    diffs.add(slot + " " + a + " vs " + b);
                }
            });
            return diffs;
        });
    }

    @Test
    void theSignatureChangesOnASwapAndNotOtherwise() {
        Object helmet = new Object();
        Object ring = new Object();
        List<Object> stacks = new ArrayList<>(Arrays.asList(helmet, null, ring));
        List<Integer> counts = List.of(1, 0, 1);
        int[] points = {10, 0, 5, 0, 0};
        String a = Build.signature(stacks, counts, points, 57, 3);
        assertEquals(a, Build.signature(new ArrayList<>(stacks), counts, points.clone(), 57, 3), "same stacks, same signature");
        stacks.set(0, new Object());                // another helmet put on
        assertNotEquals(a, Build.signature(stacks, counts, points, 57, 3));
        stacks.set(0, helmet);
        assertNotEquals(a, Build.signature(stacks, List.of(1, 0, 2), points, 57, 3), "a count changed");
        assertNotEquals(a, Build.signature(stacks, counts, new int[] {11, 0, 5, 0, 0}, 57, 3), "a point placed");
        assertNotEquals(a, Build.signature(stacks, counts, points, 58, 3), "a level up");
        assertNotEquals(a, Build.signature(stacks, counts, points, 57, 4), "a rules change");
    }
}
