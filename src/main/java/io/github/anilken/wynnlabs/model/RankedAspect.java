package io.github.anilken.wynnlabs.model;

import java.util.List;

/**
 * One row of the aspect list in display order (design decision 7): the aspect, what the player
 * owns of it, and why it ranks where it does.
 *
 * @param aspect    the aspect
 * @param rank      1, 2, 3 … for an unfinished aspect that matches the build while owned tiers are
 *                  known; 0 for no rank number (score 0, maxed, or owned tiers unknown)
 * @param score     the build-fit score of decision 7 (sum over the matched abilities); 0 when none
 * @param ownedTier the tier owned, 0 when missing, -1 when owned tiers are unknown
 * @param equipped  whether this character has it equipped
 * @param matches   the abilities its text names that the score counted, in the aspect's order
 * @param reason    one line in the menu's language naming the matches ("Boosts: Malediction
 *                  (Ritualist, unlocked)") or saying it is not tied to an ability; ability and
 *                  archetype names stay English
 */
public record RankedAspect(AspectEntry aspect, int rank, int score, int ownedTier, boolean equipped, List<Match> matches,
        String reason) {

    public RankedAspect {
        matches = matches == null ? List.of() : List.copyOf(matches);
        reason = reason == null ? "" : reason;
    }

    /** Below its top tier (or not owned); false while owned tiers are unknown. */
    public boolean unfinished() {
        return ownedTier >= 0 && ownedTier < aspect.tiers();
    }

    /** Owned at its top tier. */
    public boolean maxed() {
        return ownedTier >= aspect.tiers() && aspect.tiers() > 0;
    }

    /**
     * An ability an aspect names, as the ranking matched it.
     *
     * @param ability   the ability's English name ("Malediction", "Main Attack")
     * @param archetype its archetype in the class tree ("Ritualist"), or null for a class spell or
     *                  an ability without one
     * @param unlocked  the character has it unlocked
     * @param planned   the ability plan takes it (and it is not unlocked yet)
     * @param points    what it added to the score (3, 2, 1 or 0)
     */
    public record Match(String ability, String archetype, boolean unlocked, boolean planned, int points) {}
}
