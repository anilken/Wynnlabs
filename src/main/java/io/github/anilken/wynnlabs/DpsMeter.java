package io.github.anilken.wynnlabs;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * The live damage meter: every damage number you deal (not only the ones after a cast), over
 * a rolling 10-second window. Gives the DPS right now, the best 10 seconds of the session and
 * the damage of the current fight; a fight ends after 8 seconds without damage. Fed by
 * {@link Coach#onDamage}; the clock is passed in so the tests can drive it.
 */
final class DpsMeter {

    static final long WINDOW = 10_000;
    static final long FIGHT_GAP = 8_000;

    /** The one the game feeds; reset when you leave the server or switch character. */
    static final DpsMeter LIVE = new DpsMeter(System::currentTimeMillis);

    /** What the meter shows at one moment. {@code fight*} is the last fight once it is over. */
    record State(double dps, double best, boolean fighting, double fightDamage, double fightSeconds,
            double sessionDamage) {}

    private record Hit(long time, double amount) {}

    private final LongSupplier clock;
    private final Deque<Hit> window = new ArrayDeque<>();
    private double windowSum;
    private double best;
    private long fightStart = -1;
    private long fightLast;
    private double fightDamage;
    private double session;

    DpsMeter(LongSupplier clock) {
        this.clock = clock;
    }

    /** One damage number (all its damage types together). */
    synchronized void hit(double amount) {
        if (amount <= 0) {
            return;
        }
        long now = clock.getAsLong();
        if (fightStart < 0 || now - fightLast > FIGHT_GAP) {
            fightStart = now;                   // a new fight
            fightDamage = 0;
        }
        fightLast = now;
        fightDamage += amount;
        session += amount;
        window.addLast(new Hit(now, amount));
        windowSum += amount;
        prune(now);
        // the window's sum only grows at a hit, so its best is always at one
        best = Math.max(best, windowSum / (WINDOW / 1000.0));
    }

    private void prune(long now) {
        while (!window.isEmpty() && now - window.peekFirst().time() >= WINDOW) {
            windowSum -= window.removeFirst().amount();
        }
        if (window.isEmpty()) {
            windowSum = 0;                      // no rounding left over
        }
    }

    synchronized State state() {
        long now = clock.getAsLong();
        prune(now);
        boolean fighting = fightStart >= 0 && now - fightLast <= FIGHT_GAP;
        // early in a fight the window is not full yet: divide by the time fought (at least 1 s)
        double span = fighting ? Math.max(1000, Math.min(WINDOW, now - fightStart)) : WINDOW;
        double dps = windowSum / (span / 1000.0);
        double seconds = fightStart < 0 ? 0 : (fightLast - fightStart) / 1000.0;
        return new State(dps, best, fighting, fightDamage, seconds, session);
    }

    synchronized void reset() {
        window.clear();
        windowSum = 0;
        best = 0;
        fightStart = -1;
        fightLast = 0;
        fightDamage = 0;
        session = 0;
    }

    static JsonObject json(State s) {
        JsonObject o = new JsonObject();
        o.addProperty("dps", Math.round(s.dps()));
        o.addProperty("best10s", Math.round(s.best()));
        o.addProperty("fighting", s.fighting());
        o.addProperty(s.fighting() ? "fightDamage" : "lastFightDamage", Math.round(s.fightDamage()));
        o.addProperty(s.fighting() ? "fightSeconds" : "lastFightSeconds", Math.round(s.fightSeconds() * 10) / 10.0);
        o.addProperty("sessionDamage", Math.round(s.sessionDamage()));
        o.addProperty("note", "all damage numbers you deal, 10 s rolling window; a fight ends after 8 s without damage");
        return o;
    }
}
