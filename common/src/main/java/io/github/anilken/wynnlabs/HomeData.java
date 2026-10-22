package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.PoolItem;
import io.github.anilken.wynnlabs.model.RaidDry;
import io.github.anilken.wynnlabs.model.RankedAspect;
import io.github.anilken.wynnlabs.model.Tier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What the home tab's tiles read, twice a second rather than every frame. */
record HomeData(List<PoolItem> pool, Klass klass, boolean raidsUp, int dryPulls, boolean aspectsUp, int owned,
        int total, RankedAspect next, List<String> profs, Map<String, Integer> levels, int[] bank,
        double sellWorth, int priced, Discoveries.Entry near, int nearDist) {

    static HomeData read() {
        Klass klass = Wyn.classType();
        RaidDry dry = RaidLog.dry();
        // this week's aspects of the class, as the Endgame tab's pool view lists them (its icons), mythic first
        List<PoolItem> pool = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<PoolView.Region> regions = PoolView.raidRegions();
        for (String region : regions == null || regions.isEmpty() ? List.of("") : regions.stream().map(PoolView.Region::name).toList()) {
            for (PoolView.Group gr : PoolView.raid(region, true)) {
                for (PoolView.Row row : gr.rows()) {
                    if (row.ownClass() && row.item().aspect() && seen.add(row.item().name())) {
                        pool.add(row.item());
                    }
                }
            }
        }
        pool.sort(java.util.Comparator.comparingInt(i -> rarityRank(i.rarity())));
        List<?> classAspects = AspectBook.classAspects();
        // the professions: the crafting tab's one first, then the highest; unknown (-1) and never read (0) left out
        Map<String, Integer> levels = Professions.levels();
        List<String> profs = new ArrayList<>();
        String crafting = CraftingTab.INSTANCE.profession();
        if (crafting != null && levels.getOrDefault(crafting, -1) > 0) {
            profs.add(crafting);
        }
        List<String> byLevel = new ArrayList<>(Professions.ALL);
        byLevel.sort((a, b) -> levels.get(b) - levels.get(a));
        for (String pr : byLevel) {
            if (profs.size() < 3 && levels.get(pr) > 0 && !profs.contains(pr)) {
                profs.add(pr);
            }
        }
        // the bank: each kind of item once, as the bank tab's filters sort them (sell, quest, profession, gear, other)
        int[] bank = null;
        double worth = 0;
        int priced = 0;
        List<Vault.Group> all = Vault.groups();
        if (!all.isEmpty()) {
            bank = new int[5];
            Set<String> junk = new HashSet<>();
            for (Vault.Group j : Vault.junk()) {
                junk.add(j.key());
                Double v = "Trade Market".equals(Vault.place(j)) ? Vault.value(j) : null;
                if (v != null) {
                    worth += v;
                    priced++;
                }
            }
            for (Vault.Group gr : all) {
                Vault.Stored st = gr.first();
                int k = junk.contains(gr.key()) ? 0 : !Vault.questsFor(gr).isEmpty() ? 1 : !st.prof().isEmpty() ? 2
                        : st.gear() || st.kind().contains("gear") ? 3 : 4;
                bank[k]++;
            }
        }
        // the nearest secret discovery not done yet and open at your level
        Discoveries.Entry near = null;
        double best = Double.MAX_VALUE;
        LocalPlayer me = Minecraft.getInstance().player;
        if (me != null) {
            Set<String> found = Discoveries.foundInGame();
            String who = GameState.current().characterId();
            int level = Wyn.level();
            for (Discoveries.Entry e : Discoveries.all()) {
                if (e.minLevel() > level || Discoveries.done(e, found, who)) {
                    continue;
                }
                double dd = Discoveries.distance(e, me.getX(), me.getZ());
                if (dd < best) {
                    best = dd;
                    near = e;
                }
            }
        }
        return new HomeData(pool, klass == null ? Klass.NONE : klass, Wyn.up(Wyn.Feature.RAIDS),
                dry == null ? -1 : dry.aspectPulls(), Wyn.up(Wyn.Feature.ASPECTS), AspectBook.ownedCount(),
                classAspects == null ? 0 : classAspects.size(), AspectBook.next(), profs, levels, bank, worth, priced, near, near == null ? -1 : (int) Math.round(best));
    }

    /** Mythic first, then fabled, legendary and the rest. */
    static int rarityRank(Tier t) {
        return t == Tier.MYTHIC ? 0 : t == Tier.FABLED ? 1 : t == Tier.LEGENDARY ? 2 : 3;
    }
}
