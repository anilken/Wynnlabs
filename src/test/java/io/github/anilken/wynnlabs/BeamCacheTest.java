package io.github.anilken.wynnlabs;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The beam's results are kept: the same inputs build the same map again, without the search. */
class BeamCacheTest {

    @AfterEach
    void reset() {
        FixtureState.uninstall();
        BeamCache.clear();
    }

    private static String sets(List<Roadmap.Step> steps) {
        StringBuilder s = new StringBuilder();
        for (Roadmap.Step st : steps) {
            s.append(st.level()).append(':');
            st.build().forEach((slot, p) -> s.append(slot).append('=').append(p == null ? "-" : p.key()).append(';'));
            st.gear().forEach(g -> s.append(g.slot()).append('>').append(g.info().name()).append(';'));
            s.append('\n');
        }
        return s.toString();
    }

    @Test
    void aSecondMapSkipsTheBeamAndMatches() throws Exception {
        BeamCache.clear();
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("en");
        // a wider beam than the other tests, so skipping it shows
        JsonObject rm = new JsonObject();
        rm.addProperty("jointPool", 20);
        rm.addProperty("beamWidth", 60);
        rm.addProperty("beamFinal", 12);
        JsonObject change = new JsonObject();
        change.add("roadmap", rm);
        Rules.update(change);
        long t0 = System.nanoTime();
        String first = sets(Roadmap.build());
        long cold = System.nanoTime() - t0;
        t0 = System.nanoTime();
        String second = sets(Roadmap.build());
        long warm = System.nanoTime() - t0;
        Fixtures.note(String.format("road map %d ms, again with the beam cache %d ms", cold / 1_000_000, warm / 1_000_000));
        assertEquals(first, second);
        assertTrue(warm < cold, "the second map took " + warm / 1_000_000 + " ms against " + cold / 1_000_000);
    }
}
