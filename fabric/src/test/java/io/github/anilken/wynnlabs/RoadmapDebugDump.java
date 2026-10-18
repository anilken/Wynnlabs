package io.github.anilken.wynnlabs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Debug only (run with -Dwynnlabs.debugDump=true): every number the J menu shows for each slot and milestone. */
class RoadmapDebugDump {

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    @Test
    void dump() throws Exception {
        assumeTrue(Boolean.getBoolean("wynnlabs.debugDump"), "debug dump off");
        Path dir = Fixtures.root().resolve("shaman-44");
        assumeTrue(Files.exists(dir.resolve("state.json")));
        FixtureState.load(dir).install("en");
        StringBuilder out = new StringBuilder();
        Build.Plan plan = Build.plan();
        Map<String, Damage.Piece> worn = new LinkedHashMap<>(plan.worn() != null ? plan.worn() : plan.pieces());
        Map<String, Roadmap.Gear> latest = new LinkedHashMap<>();
        StringBuilder trace = new StringBuilder();
        Roadmap.trace = (m, slot, line) -> {
            if (m >= 110 && slot == io.github.anilken.wynnlabs.model.Slot.RING) {
                trace.append("  Lv ").append(m).append(' ').append(line).append(System.lineSeparator());
            }
        };
        List<Roadmap.Step> built;
        try {
            built = Roadmap.build();
        } finally {
            Roadmap.trace = null;
        }
        for (Roadmap.Step s : built) {
            for (Roadmap.Gear g : s.gear()) {
                latest.put(g.slot(), g);
            }
            out.append("== Lv ").append(s.level()).append('\n');
            StringBuilder whole = new StringBuilder("  build:");
            s.build().forEach((slot, p) -> whole.append(' ').append(slot).append('=').append(p == null ? "-" : p.name()));
            Build.Eval ev = s.build().isEmpty() ? null
                    : Build.allocate(s.build(), s.level(), Math.max(0, (Math.min(s.level(), 101) - 1) * 2));
            out.append(whole).append(ev == null ? "" : " points=" + java.util.Arrays.toString(ev.allocation())).append('\n');
            for (var e : latest.entrySet()) {
                Roadmap.Gear g = e.getValue();
                // the alternative metric: only this slot changed from what you wear now, at the milestone's level
                int earned = Math.max(0, (Math.min(s.level(), 101) - 1) * 2);
                String single = "-";
                String bslot = e.getKey().equals(Roadmap.RING2) ? "RING2"
                        : Build.slotOf(io.github.anilken.wynnlabs.model.Slot.valueOf(e.getKey()), 0);
                if (bslot != null) {
                    Build.Eval a = Build.allocate(worn, s.level(), earned);
                    Map<String, Damage.Piece> one = new LinkedHashMap<>(worn);
                    one.put(bslot, Damage.piece(g.info()));
                    Build.Eval b = Build.allocate(one, s.level(), earned);
                    if (a != null && b != null && a.result().objective() > 0) {
                        single = String.format("%.1f", (b.result().objective() / a.result().objective() - 1) * 100);
                    }
                }
                out.append(String.format("  %-9s %-28s row(vsWorn)=%s step(gain)=%.1f singleSwap=%s%n", e.getKey(),
                        g.info().name() + " Lv" + g.info().requirements().level(),
                        s.vsWorn().containsKey(e.getKey()) ? String.format("%.1f", s.vsWorn().get(e.getKey())) : "-",
                        g.gain(), single));
                if (g.options() != null) {
                    for (Roadmap.Option o : g.options()) {
                        out.append(String.format("      option %-26s card=%.1f%n",
                                o.info().name() + " Lv" + o.info().requirements().level(), o.gain()));
                    }
                }
            }
        }
        out.append("== ring candidates at Lv 110 and 120").append(System.lineSeparator()).append(trace);
        out.append("== rings Lv 95+ in the item data: name, level, tier, skill needs").append('\n');
        GameState.current().allGear().filter(i -> i.slot() == io.github.anilken.wynnlabs.model.Slot.RING
                && i.requirements().level() >= 95).sorted(java.util.Comparator.comparingInt(i -> i.requirements().level()))
                .forEach(i -> out.append(String.format("  %-26s Lv%-3d %-9s %s%n", i.name(), i.requirements().level(),
                        i.tier().name(), i.requirements().skills())));
        Path f = Path.of("roadmap-debug.txt").toAbsolutePath();
        Files.writeString(f, out);
        System.out.println("[debug] wrote " + f.toAbsolutePath());
    }
}
