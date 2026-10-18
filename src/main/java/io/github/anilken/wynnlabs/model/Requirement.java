package io.github.anilken.wynnlabs.model;

import java.util.List;
import java.util.Optional;

/** What it takes to wear a piece: level, class, skill points (in the game's order) and a quest. */
public record Requirement(int level, Optional<Klass> klass, List<SkillReq> skills, Optional<String> quest) {

    /** Points needed in one skill. */
    public record SkillReq(SkillKind skill, int points) {}
}
