package io.github.anilken.wynnlabs.build.roadmap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Gear;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Option;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Step;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.market.Shopping;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.model.Slot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The road map as JSON for the local API: the milestones, the rows the J menu shows, and a trace. */
final class RoadmapJson {

    private RoadmapJson() {}

    /** The milestones with their picks, abilities and places (the local API's road map). */
    static JsonObject steps(List<Step> steps) {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Step s : steps) {
            JsonObject j = new JsonObject();
            j.addProperty("level", s.level());
            JsonArray g = new JsonArray();
            for (Gear x : s.gear()) {
                JsonObject gj = new JsonObject();
                gj.addProperty("slot", x.slot());
                gj.addProperty("name", x.info().name());
                gj.addProperty("level", x.info().requirements().level());
                gj.addProperty("tier", x.info().tier().name());
                gj.addProperty("gain", Double.isNaN(x.gain()) ? null : Math.round(x.gain() * 10) / 10.0);
                if (Double.isNaN(x.gain())) {
                    gj.addProperty("target", true);
                }
                gj.addProperty("from", x.source());
                gj.addProperty("price", x.price());
                gj.addProperty("tradable", Shopping.tradable(x.info()));
                if (x.alt() != null) {
                    gj.addProperty("alternative", x.alt().name() + " (Lv. " + x.alt().requirements().level()
                            + ", " + x.altSource() + ")");
                }
                if (x.options() != null) {
                    JsonArray opts = new JsonArray();
                    for (Option opt : x.options()) {
                        JsonObject oj = new JsonObject();
                        oj.addProperty("name", opt.info().name());
                        oj.addProperty("level", opt.info().requirements().level());
                        oj.addProperty("gain", Math.round(opt.gain() * 10) / 10.0);
                        oj.addProperty("from", opt.source());
                        oj.addProperty("tradable", Shopping.tradable(opt.info()));
                        opts.add(oj);
                    }
                    gj.add("options", opts);
                }
                g.add(gj);
            }
            j.add("gear", g);
            JsonArray a = new JsonArray();
            s.abilities().forEach(a::add);
            j.add("abilities", a);
            JsonArray p = new JsonArray();
            s.places().forEach(p::add);
            j.add("places", p);
            j.addProperty("skillPoints", s.skillPoints());
            arr.add(j);
        }
        o.add("steps", arr);
        return o;
    }

    /**
     * The local API's /roadmap: the map the J menu has ({@code steps}, built at {@code builtAt}),
     * plus per milestone the rows the menu shows: the latest pick per slot up to that milestone,
     * the number on the row (gain against what you wear) and its reasons.
     */
    static JsonObject live(List<Step> steps, long builtAt, boolean building) {
        JsonObject o = new JsonObject();
        o.addProperty("builtAgoSeconds", steps.isEmpty() ? -1 : (GameState.current().now() - builtAt) / 1000);
        o.addProperty("building", building);
        JsonArray arr = new JsonArray();
        Map<String, Gear> latest = new LinkedHashMap<>();
        // what each set was judged against: the points you placed, then the milestone before
        int[] prev = OwnedGear.assignedPoints(GameState.current());
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            for (Gear g : s.gear()) {
                latest.put(g.slot(), g);
            }
            RoadmapRows.keepWorn(latest, s);
            JsonObject j = new JsonObject();
            j.addProperty("level", s.level());
            if (s.set() != null) {
                JsonObject set = new JsonObject();
                s.build().forEach((slot, p) -> set.addProperty(slot, p == null ? null : p.name()));
                JsonObject pts = new JsonObject();
                for (SkillKind k : SkillKind.values()) {
                    pts.addProperty(k.name(), s.set().allocation()[k.ordinal()]);
                }
                set.add("skillPoints", pts);
                set.addProperty("spell", Math.round(s.set().result().spellDps()));
                set.addProperty("ehp", Math.round(s.set().result().ehp()));
                set.addProperty("build", Math.round(s.set().result().objective()));
                // what the search chose it by: the build with the respec charge and the keep bonus
                // against the milestone before (builds of two milestones are no measure of each
                // other: XP counts nothing at the cap, the health floor rises, the points stop at 101)
                set.addProperty("judged", Math.round(SetJudge.judged(s.build(), s.set(), prev, s.level())));
                j.add("set", set);
                prev = SetJudge.carry(s.set(), s.build(), s.level());
            }
            JsonArray rows = new JsonArray();
            for (var e : latest.entrySet()) {
                Gear g = e.getValue();
                JsonObject r = new JsonObject();
                r.addProperty("slot", e.getKey());
                r.addProperty("name", g.info().name());
                r.addProperty("level", g.info().requirements().level());
                r.addProperty("tier", g.info().tier().name());
                Double row = s.vsWorn().get(e.getKey());
                r.addProperty("row", row == null ? null : Math.round(row * 10) / 10.0);
                r.addProperty("step", Double.isNaN(g.gain()) ? null : Math.round(g.gain() * 10) / 10.0);
                r.addProperty("pill", RoadmapGains.pill(RoadmapGains.shownGain(steps, i, g), g));      // what the J menu's row shows
                JsonArray why = new JsonArray();
                s.whyVsWorn().getOrDefault(e.getKey(), List.of()).forEach(why::add);
                r.add("why", why);
                if (g.options() != null) {
                    JsonArray opts = new JsonArray();
                    for (Option opt : g.options()) {
                        opts.add(opt.info().name() + " Lv" + opt.info().requirements().level() + " "
                                + Math.round(opt.gain() * 10) / 10.0 + "%");
                    }
                    r.add("options", opts);
                }
                rows.add(r);
            }
            j.add("rows", rows);
            arr.add(j);
        }
        o.add("milestones", arr);
        return o;
    }

    /** The local API's /roadmap/trace: the trace lines a build gave for {@code slot} at {@code level}. */
    static JsonObject trace(int level, Slot slot, List<String> lines) {
        JsonObject o = new JsonObject();
        o.addProperty("level", level);
        o.addProperty("slot", slot.name());
        JsonArray a = new JsonArray();
        synchronized (lines) {
            lines.forEach(a::add);
        }
        o.add("candidates", a);
        return o;
    }
}
