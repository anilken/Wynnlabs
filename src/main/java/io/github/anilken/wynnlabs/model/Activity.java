package io.github.anilken.wynnlabs.model;

import java.util.List;

/**
 * One entry of the content book as shown in the open menu. {@code type} is a {@link QuestKind}
 * name and {@code quest} whether it counts as a quest. Requirements: the level (null when not
 * shown) and whether each level, profession and quest requirement is met. {@code xp}: the XP
 * reward lines (null when it shows none); {@code description} and {@code distance} as plain text.
 */
public record Activity(String type, boolean quest, String name, String status, boolean hasRequirements,
        Integer level, Boolean levelMet, List<Boolean> professionsMet, List<Boolean> questsMet, List<String> xp,
        String description, String distance, String length, String difficulty) {}
