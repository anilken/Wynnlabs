package io.github.anilken.wynnlabs.coach;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The live DPS meter on a clock the test turns: the window, the end of a fight, the best 10 seconds. */
class DpsMeterTest {

    private long now = 1_000_000;
    private final DpsMeter meter = new DpsMeter(() -> now);

    private void at(long ms, double amount) {
        now = ms;
        meter.hit(amount);
    }

    @Test
    void rollingWindow() {
        long t0 = now;
        for (int s = 0; s < 10; s++) {
            at(t0 + s * 1000, 100);             // 100 a second for 10 s
        }
        now = t0 + 9_500;
        DpsMeter.State st = meter.state();
        assertTrue(st.fighting());
        assertEquals(1000 / 9.5, st.dps(), 1e-9);   // all ten over the 9.5 s fought
        // 10.5 s on: the first hit has left the window, nine are left in a full window
        now = t0 + 10_500;
        assertEquals(90, meter.state().dps(), 1e-9);
        assertEquals(1000, meter.state().fightDamage(), 1e-9);
    }

    @Test
    void earlyInAFightDividesByTheTimeFought() {
        long t0 = now;
        at(t0, 300);
        now = t0 + 200;
        assertEquals(300, meter.state().dps(), 1e-9);   // at least one second
        now = t0 + 3000;
        assertEquals(100, meter.state().dps(), 1e-9);
    }

    @Test
    void fightEndsAfterEightQuietSeconds() {
        long t0 = now;
        at(t0, 50);
        at(t0 + 4000, 50);
        now = t0 + 4000 + 7999;
        assertTrue(meter.state().fighting());
        now = t0 + 4000 + 8001;
        DpsMeter.State st = meter.state();
        assertFalse(st.fighting());
        assertEquals(100, st.fightDamage(), 1e-9);      // the last fight stays shown
        assertEquals(4, st.fightSeconds(), 1e-9);
        // the next hit starts a new fight
        at(t0 + 20_000, 70);
        st = meter.state();
        assertTrue(st.fighting());
        assertEquals(70, st.fightDamage(), 1e-9);
        assertEquals(0, st.fightSeconds(), 1e-9);
        assertEquals(170, st.sessionDamage(), 1e-9);
        // a gap of exactly 8 s still belongs to the same fight
        at(t0 + 28_000, 30);
        assertEquals(100, meter.state().fightDamage(), 1e-9);
    }

    @Test
    void bestTenSecondsOfTheSession() {
        long t0 = now;
        at(t0, 500);
        at(t0 + 5000, 500);                     // 1000 inside one window: 100 DPS
        at(t0 + 12_000, 200);                   // the first hit has left: 700
        assertEquals(100, meter.state().best(), 1e-9);
        at(t0 + 14_000, 800);                   // 500 + 200 + 800 = 1500 in (4 s, 14 s]
        assertEquals(150, meter.state().best(), 1e-9);
        // quiet for a minute: the window empties, the best stays
        now = t0 + 80_000;
        assertEquals(0, meter.state().dps(), 1e-9);
        assertEquals(150, meter.state().best(), 1e-9);
        meter.reset();
        assertEquals(0, meter.state().best(), 1e-9);
        assertEquals(0, meter.state().sessionDamage(), 1e-9);
    }

    @Test
    void nothingDealtNothingShown() {
        meter.hit(0);
        meter.hit(-5);
        DpsMeter.State st = meter.state();
        assertFalse(st.fighting());
        assertEquals(0, st.sessionDamage(), 1e-9);
        assertEquals(0, st.dps(), 1e-9);
    }
}
