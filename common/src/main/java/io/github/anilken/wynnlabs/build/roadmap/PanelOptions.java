package io.github.anilken.wynnlabs.build.roadmap;

import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Option;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.market.Shopping;
import io.github.anilken.wynnlabs.model.ItemData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Which of a slot's candidates the comparison panel judges and shows: the best by gain, and
 * always some the Trade Market sells (tradable, with a known price), since the best are often
 * a quest's, a challenge's or a dungeon merchant's. Judged once when the road map is built;
 * the panel only picks from the list.
 */
public final class PanelOptions {

    private PanelOptions() {}

    /** How many options the panel shows (rules.roadmap.panelOptions), at least. */
    public static int count() {
        return (int) Rules.sub("roadmap", "panelOptions", 8);
    }

    /** Can be bought on the Trade Market: tradable, with a known price. */
    public static boolean onMarket(Option o) {
        return o.price() != null && Shopping.tradable(o.info());
    }

    /**
     * The panel's others, best gain first. {@code ranked} is the slot's pool in its rough order,
     * the pick and what the panel leaves out already gone. Judged: its first {@link #count}, then
     * the next the Trade Market sells until {@link #count} of those are in, and a few you can wear
     * at {@code level} (rules.roadmap.alternativesNow). Each judging spreads the skill points
     * anew, so the pool is never judged whole.
     */
    static List<Option> others(List<ItemData> ranked, Function<ItemData, Option> judge, int level) {
        int n = count();
        int nowMin = (int) Rules.sub("roadmap", "alternativesNow", 3);
        GameState.current().requestPrices(ranked.stream().filter(Shopping::tradable).limit(3L * n)
                .map(ItemData::name).toList());
        List<Option> out = new ArrayList<>();
        Set<String> tried = new HashSet<>();
        int market = 0;
        int now = 0;
        for (ItemData info : ranked) {
            boolean head = tried.size() < n;
            boolean buy = market < n && Shopping.tradable(info) && Prices.buyText(info.name()) != null;
            boolean wear = now < nowMin && info.requirements().level() <= level;
            if (!head && !buy && !wear || !tried.add(info.name())) {
                continue;
            }
            Option op = judge.apply(info);
            if (op != null) {
                out.add(op);
                market += onMarket(op) ? 1 : 0;
                now += info.requirements().level() <= level ? 1 : 0;
            }
        }
        out.sort((a, b) -> Double.compare(b.gain(), a.gain()));
        return out;
    }

    /**
     * What the panel shows of a slot's {@code options} (the pick first, the rest best gain
     * first). With {@code market}: only what the Trade Market sells. Otherwise the first
     * {@link #count}, and past them the best the Trade Market sells until two are in, and the
     * best you can wear at {@code level} until rules.roadmap.alternativesNow are in.
     */
    public static List<Option> shown(List<Option> options, boolean market, int level) {
        if (options == null) {
            return List.of();
        }
        if (market) {
            return options.stream().filter(PanelOptions::onMarket).toList();
        }
        int n = count();
        int nowMin = (int) Rules.sub("roadmap", "alternativesNow", 3);
        List<Option> out = new ArrayList<>();
        int buy = 0;
        int now = 0;
        for (Option o : options) {
            boolean b = onMarket(o);
            boolean w = o.info().requirements().level() <= level;
            if (out.size() < n || b && buy < 2 || w && now < nowMin) {
                out.add(o);
                buy += b ? 1 : 0;
                now += w ? 1 : 0;
            }
        }
        return out;
    }
}
