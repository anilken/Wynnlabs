package io.github.anilken.wynnlabs.model;

import java.util.List;
import java.util.Map;

/**
 * A quest as the quest list knows it. Status, difficulty and length by their names (null when
 * unknown); {@code nextTask} as plain text, null when there is none; {@code rewards} by reward
 * type ("XP", "ITEM" …) in the order given, null when unknown.
 */
public record Quest(String name, boolean mini, String status, int level, String difficulty, String length,
        String nextTask, Requirements requirements, Map<String, List<String>> rewards) {

    /** {@code level} is null when none is given; each requirement with whether it is met. */
    public record Requirements(Integer level, Boolean levelMet, List<Profession> professions, List<Need> quests) {}

    public record Profession(String profession, Integer level, Boolean met) {}

    public record Need(String quest, Boolean met) {}
}
