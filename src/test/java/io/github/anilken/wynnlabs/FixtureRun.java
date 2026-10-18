package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runs the real logic on every fixture and compares the results with the approved files. */
final class FixtureRun {

    /** What one fixture gives, in one language. */
    interface Result {
        List<String> check(FixtureState fx, Path dir, String language) throws Exception;
    }

    private FixtureRun() {}

    /**
     * For each fixture folder and each language: install it, run {@code result}, collect the
     * differences; skipped (one log line) when there are no fixtures, failed with every difference.
     */
    static void each(String what, List<String> languages, Result result) throws Exception {
        List<Path> dirs = Fixtures.folders();
        org.junit.jupiter.api.Assumptions.assumeFalse(dirs.isEmpty(), Fixtures::none);
        List<String> problems = new ArrayList<>();
        for (Path dir : dirs) {
            FixtureState fx = FixtureState.load(dir);
            for (String lang : languages) {
                try {
                    fx.install(lang);
                    problems.addAll(result.check(fx, dir, lang));
                } finally {
                    FixtureState.uninstall();
                }
            }
            if (!fx.missingPrices.isEmpty()) {
                Fixtures.note(dir.getFileName() + ": " + fx.missingPrices.size() + " prices asked for but not recorded: "
                        + String.join(", ", fx.missingPrices));
            }
        }
        Expected.assertNone(problems, what);
    }

    private static JsonObject line(String where, String name, Advice.Entry e) {
        JsonObject o = new JsonObject();
        o.addProperty("where", where);
        o.addProperty("name", name);
        o.addProperty("verdict", e == null || e.verdict() == null ? null : e.verdict().name());
        o.addProperty("note", e == null ? null : e.note());
        return o;
    }

    /**
     * Every item's verdict and note: the bag (hotbar 0-8, bag 9-35), worn pieces that are not in
     * the bag, then the bank pages.
     */
    static JsonArray verdicts(FixtureState fx) {
        JsonArray out = new JsonArray();
        Set<ItemStack> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<Integer, ItemStack> e : fx.bag().entrySet()) {
            int i = e.getKey();
            seen.add(e.getValue());
            out.add(line((i < 9 ? "hotbar " : "bag ") + i, fx.nameOf(e.getValue()), AutoAdvice.decide(e.getValue())));
        }
        for (var slot : Fixture.ARMOUR) {
            ItemStack s = fx.armour(slot);
            if (!s.isEmpty() && seen.add(s)) {
                out.add(line("worn " + slot.name(), fx.nameOf(s), AutoAdvice.decide(s)));
            }
        }
        List<ItemStack> acc = fx.accessories();
        for (int i = 0; i < acc.size(); i++) {
            ItemStack s = acc.get(i);
            if (!s.isEmpty() && seen.add(s)) {
                out.add(line("worn accessory " + i, fx.nameOf(s), AutoAdvice.decide(s)));
            }
        }
        for (FixtureState.BankItem b : fx.bank()) {
            Advice.Entry e = b.view() == null ? null
                    : AutoAdvice.decide(b.view(), new AutoAdvice.StackFacts(null, b.name(), b.count()));
            out.add(line(b.where(), b.name(), e));
        }
        return out;
    }

    /** The answers of GET /inventory, /equipped, /build and /roadmap, by route. */
    static JsonObject api() {
        JsonObject o = new JsonObject();
        o.add("inventory", ItemJson.inventory());
        o.add("equipped", ItemJson.equipped());
        o.add("build", Build.json());
        o.add("roadmap", Roadmap.json());
        return o;
    }

    static JsonElement shopping() {
        return Shopping.json();
    }
}
