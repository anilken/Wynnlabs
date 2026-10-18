package io.github.anilken.wynnlabs.model;

/**
 * Totals over a character's recorded lootruns (lootrun-tracking spec, lootrun summary).
 *
 * @param runs              recorded runs
 * @param completed         completed runs among them
 * @param completionPct     completed share, 0–100 rounded; -1 when there are no runs
 * @param pulls             reward pulls over all runs
 * @param pullsPerCompleted average pulls per completed run; -1 when no run was completed
 */
public record LootrunTotals(int runs, int completed, int completionPct, int pulls, double pullsPerCompleted) {

    /** No runs yet. */
    public static final LootrunTotals NONE = new LootrunTotals(0, 0, -1, 0, -1);
}
