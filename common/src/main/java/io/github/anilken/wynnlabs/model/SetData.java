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

    /** The bonus for {@code n} pieces worn, counted between 1 and the set's size; null if none. */
    public Bonus bonusFor(int n) {
        return bonuses.get(n < 1 ? 1 : Math.min(n, pieces));
    }
}
