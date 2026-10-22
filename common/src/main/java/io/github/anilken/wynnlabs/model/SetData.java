package io.github.anilken.wynnlabs.model;

import java.util.List;
import java.util.Map;

/**
 * A gear set: its name, the name shown to players, how many pieces it has and the bonus for each
 * number of pieces worn.
 */
public record SetData(String name, String cleanName, int pieces, Map<Integer, Bonus> bonuses) {

    /** Named effects ({@code major}) and stat bonuses by API name ({@code minor}, in the data's order). */
    public record Bonus(List<String> major, List<StatValue> minor) {}

    public record StatValue(String key, int value) {}

    /**
     * The bonus for {@code n} pieces worn: the one for the most pieces up to {@code n}, null if
     * none. A ring worn in both hands counts twice, so {@code n} can pass the number of items
     * (Black Catalyst is one ring with its bonus at two, Jester three items with one at four).
     */
    public Bonus bonusFor(int n) {
        int at = 0;
        for (int k : bonuses.keySet()) {
            if (k <= n && k > at) {
                at = k;
            }
        }
        return at < 1 ? null : bonuses.get(at);
    }

    /** The most pieces that count: the items, or the largest bonus when a doubled ring reaches past them. */
    public int full() {
        int most = pieces;
        for (int k : bonuses.keySet()) {
            most = Math.max(most, k);
        }
        return most;
    }
}
