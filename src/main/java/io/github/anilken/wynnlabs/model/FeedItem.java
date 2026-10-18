package io.github.anilken.wynnlabs.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One row of the recent-runs feed (endgame-tab spec): a raid run or a lootrun, exactly one of
 * {@code raid} and {@code lootrun} set.
 *
 * @param raid    the raid run, or null
 * @param lootrun the lootrun, or null
 */
public record FeedItem(RaidRun raid, LootrunRun lootrun) {

    public FeedItem {
        if ((raid == null) == (lootrun == null)) {
            throw new IllegalArgumentException("a feed item is a raid or a lootrun");
        }
    }

    /** True for a raid run. */
    public boolean isRaid() {
        return raid != null;
    }

    /** The raid's or the camp's English name. */
    public String name() {
        return raid != null ? raid.raid() : lootrun.camp();
    }

    public boolean completed() {
        return raid != null ? raid.completed() : lootrun.completed();
    }

    /** When it ended, epoch milliseconds. */
    public long endedAt() {
        return raid != null ? raid.endedAt() : lootrun.endedAt();
    }

    /** Its time, milliseconds; -1 when unknown. */
    public long durationMs() {
        if (raid != null) {
            return raid.durationMs();
        }
        return lootrun.durationS() < 0 ? -1 : lootrun.durationS() * 1000L;
    }

    /** A lootrun's reward pulls; -1 for a raid or a failed lootrun (drawn as "-"). */
    public int pulls() {
        return lootrun != null && lootrun.completed() ? lootrun.pulls() : -1;
    }

    /** The mythic a lootrun's reward chest gave, or null. */
    public String mythic() {
        return lootrun != null ? lootrun.mythic() : null;
    }

    /**
     * Raids and lootruns in one list, newest first (ties: raid first), at most {@code max} rows
     * (all when {@code max} is negative).
     */
    public static List<FeedItem> merge(List<RaidRun> raids, List<LootrunRun> lootruns, int max) {
        List<FeedItem> out = new ArrayList<>();
        if (raids != null) {
            raids.forEach(r -> out.add(new FeedItem(r, null)));
        }
        if (lootruns != null) {
            lootruns.forEach(l -> out.add(new FeedItem(null, l)));
        }
        out.sort(Comparator.comparingLong(FeedItem::endedAt).reversed().thenComparing(f -> !f.isRaid()));
        return max >= 0 && out.size() > max ? List.copyOf(out.subList(0, max)) : List.copyOf(out);
    }
}
