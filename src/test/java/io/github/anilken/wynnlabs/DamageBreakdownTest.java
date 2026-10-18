package io.github.anilken.wynnlabs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The Damage tab's breakdown is the model's own numbers, split: on the recorded Shaman it adds up. */
class DamageBreakdownTest {

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    private static Build.Plan shaman() throws Exception {
        Path dir = Fixtures.root().resolve("shaman-44");
        assumeTrue(Files.exists(dir.resolve("state.json")), "no shaman-44 fixture");
        FixtureState.load(dir).install("en");
        Build.Plan p = Build.plan();
        assertNotNull(p);
        return p;
    }

    private static void close(double expected, double actual, String what) {
        assertEquals(expected, actual, Math.max(1e-6, Math.abs(expected) * 1e-9), what);
    }

    @Test
    void partsAddUpToTheModel() throws Exception {
        Build.Plan p = shaman();
        Damage.Detail d = p.currentDetail(null, null);
        Damage.Result r = d.result();
        Damage.Breakdown b = d.breakdown();
        // the same pass as the plan's own number for what you wear
        assertEquals(p.current().objective(), r.objective(), "objective");
        close(r.spellDps(), b.spellTotal(), "spells");
        close(r.mainDps(), b.mainTotal(), "main attack");
        // the Summoner's puppets are in the model (passiveDpsFactor 1)
        assertTrue(b.summonTotal() > 0, "summons");
        double byElement = 0;
        for (int i = 0; i < Damage.ELEMENTS.length; i++) {
            assertTrue(b.element(i) >= 0, Damage.ELEMENTS[i]);
            byElement += b.element(i);
        }
        close(r.spellDps() + r.mainDps() + b.summonTotal(), b.total(), "total");
        close(b.total(), byElement, "elements");
    }

    @Test
    void whatIfsAreSortedAndCheap() throws Exception {
        Build.Plan p = shaman();
        DamageReport.Report report = DamageReport.get();
        assertNotNull(report);
        // computed once per plan, not per call
        assertTrue(report == DamageReport.get(), "cached");
        assertFalse(report.whatIf().isEmpty());
        double last = Double.MAX_VALUE;
        for (DamageReport.WhatIf w : report.whatIf()) {
            assertTrue(w.gain() <= last, "sorted by gain: " + w.change());
            last = w.gain();
        }
        double base = report.detail().breakdown().total();
        for (DamageReport.WhatIf w : report.whatIf()) {
            close(base + w.gain(), w.dps(), w.change());
            if (w.change().equals("+10% spell damage")) {
                assertTrue(w.gain() > 0, "more spell damage helps a spell build");
            }
        }
        // the what-ifs leave the plan's own result alone
        assertEquals(p.current().objective(), p.currentDetail(null, null).result().objective());
    }
}
