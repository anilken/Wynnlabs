package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.CampMythics;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.LootrunRun;
import io.github.anilken.wynnlabs.model.LootrunTotals;
import io.github.anilken.wynnlabs.model.MythicFind;
import io.github.anilken.wynnlabs.model.PoolAspect;
import io.github.anilken.wynnlabs.model.RaidDry;
import io.github.anilken.wynnlabs.model.RaidRun;
import io.github.anilken.wynnlabs.model.RaidSummary;
import io.github.anilken.wynnlabs.model.RankedAspect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the Endgame tab reads, once every quarter second rather than every frame: the raid and
 * lootrun logs, the weekly pool, the aspect book, and the pools of the Havuzlar view.
 */
record EndgameData(boolean raidsUp, boolean lootUp, boolean aspectsUp, boolean tracking, List<RaidSummary> summaries,
        List<RaidRun> raids, int sessionDone, int sessionFailed, int streak, RaidDry dry, List<LootrunRun> lootruns,
        LootrunTotals totals, int dryRuns, int dryPulls, List<MythicFind> mythics, int accountDry, List<PoolAspect> pool,
        long poolSeen, boolean poolStale, String poolAge, int poolMarked, Klass klass, boolean dataReady, boolean known,
        List<RankedAspect> ranked, RankedAspect next, int ownedCount, String archetype, boolean guessed, Pools p, Views v) {

    static EndgameData read(long now, boolean classOnly) {
        List<RaidSummary> sums = new ArrayList<>(safe(RaidLog.summaries()));
        if (sums.isEmpty()) {
            RaidLog.RAIDS.forEach(r -> sums.add(RaidSummary.empty(r)));
        }
        RaidDry dry = RaidLog.dry();
        return new EndgameData(Wyn.up(Wyn.Feature.RAIDS), Wyn.up(Wyn.Feature.LOOTRUNS), Wyn.up(Wyn.Feature.ASPECTS),
                RaidLog.trackingOn(), sums, safe(RaidLog.runs()), RaidLog.sessionDone(), RaidLog.sessionFailed(),
                RaidLog.streak(), dry == null ? RaidDry.UNKNOWN : dry, safe(LootrunLog.runs()),
                LootrunLog.totals() == null ? LootrunTotals.NONE : LootrunLog.totals(), LootrunLog.dryRuns(),
                LootrunLog.dryPulls(), safe(LootrunLog.mythics()), LootrunLog.accountDryPulls(), safe(RaidPool.aspects()),
                RaidPool.seenAt(), RaidPool.stale(now), RaidPool.ageText(now), RaidPool.markedCount(),
                AspectBook.klass() == null ? Klass.NONE : AspectBook.klass(), AspectBook.dataReady(), AspectBook.known(),
                safe(AspectBook.ranked()), AspectBook.next(), AspectBook.ownedCount(), AspectBook.archetype(),
                AspectBook.archetypeGuessed(), Pools.read(now), Views.read(now, classOnly));
    }

    static <T> List<T> safe(List<T> l) {
        return l == null ? List.of() : l;
    }

    /**
     * This week's pools (design decision 10): where the raid pool shown comes from, its raids with
     * the class's aspects and their times, and the lootrun camps holding mythics.
     */
    record Pools(RaidPool.Source source, int week, WynnventoryPools.Status status, List<String> raids,
            Map<String, List<PoolAspect>> byRaid, Map<String, Long> raidSeen, List<CampMythics> camps, List<String> campsNone,
            boolean lootStale, int lootWeek) {

        static Pools read(long now) {
            RaidPool.Source src = RaidPool.source();
            List<String> raids = EndgameData.safe(RaidPool.poolRaids());
            Map<String, List<PoolAspect>> by = new LinkedHashMap<>();
            Map<String, Long> seen = new LinkedHashMap<>();
            for (String r : raids) {
                by.put(r, EndgameData.safe(RaidPool.aspectsIn(r)));
                seen.put(r, RaidPool.raidSeenAt(r));
            }
            WynnventoryPools.Status st = WynnventoryPools.status();
            return new Pools(src == null ? RaidPool.Source.NONE : src, RaidPool.week(), st == null ? WynnventoryPools.Status.OFF : st,
                    raids, by, seen, EndgameData.safe(LootrunLog.mythicCamps()), EndgameData.safe(LootrunLog.campsWithoutMythic()),
                    LootrunLog.poolStale(now), LootrunLog.poolWeek());
        }

        /** The raid pool shown is Wynnventory's. */
        boolean wynnventory() {
            return source == RaidPool.Source.WYNNVENTORY;
        }

        /** Wynnventory's lootrun pool has data (the mythic line is drawn only then). */
        boolean loot() {
            return !camps.isEmpty() || !campsNone.isEmpty();
        }

        long seenOf(String raid) {
            Long s = raidSeen.get(raid);
            return s == null ? -1 : s;
        }
    }

    /**
     * The Havuzlar view's data (design decision 11): the raid and camp region rows, the groups of
     * every region (raids with the class chip applied), and whether each pool is from an earlier week.
     */
    record Views(List<PoolView.Region> raids, List<PoolView.Region> camps, Map<String, List<PoolView.Group>> raidGroups,
            Map<String, List<PoolView.Group>> campGroups, boolean raidStale, boolean campStale) {

        static Views read(long now, boolean classOnly) {
            List<PoolView.Region> raids = EndgameData.safe(PoolView.raidRegions());
            List<PoolView.Region> camps = EndgameData.safe(PoolView.campRegions());
            Map<String, List<PoolView.Group>> rg = new LinkedHashMap<>();
            for (PoolView.Region r : raids) {
                rg.put(r.name(), EndgameData.safe(PoolView.raid(r.name(), classOnly)));
            }
            Map<String, List<PoolView.Group>> cg = new LinkedHashMap<>();
            for (PoolView.Region r : camps) {
                cg.put(r.name(), EndgameData.safe(PoolView.camp(r.name())));
            }
            return new Views(raids, camps, rg, cg, !raids.isEmpty() && RaidPool.stale(now),
                    !camps.isEmpty() && LootrunLog.poolStale(now));
        }

        List<PoolView.Region> regions(boolean raid) {
            return raid ? raids : camps;
        }

        List<PoolView.Group> groups(boolean raid, String region) {
            return (raid ? raidGroups : campGroups).getOrDefault(region, List.of());
        }

        boolean stale(boolean raid) {
            return raid ? raidStale : campStale;
        }
    }
}
