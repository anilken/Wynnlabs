# Design

## Context

Evidence (shaman-44, narrow test search, both sets judged against the chain's Lv 80 points):
chained Lv 90 90172, fresh 93338 (-3.4 %). Trace: the chained Lv 90 weapon pool is Thermals,
Sparkling Tones, Stormdrain, Triple Point, Veins, Guacamole (Fire, Water, Earth only). Loaded
Question swapped into the Lv 80 set "cannot be worn" (skill points), so the slot ranking drops it.
Forcing it as a weapon start: +28 % start, +10.4 % over the chained end. The Lv 80 gap (raw
49863 vs 53757) is the judge: scored against the chain's points the chained set wins (51131 vs
50102), so the old test compared numbers the chain may not choose by.

The reverse gap (a level searched alone behind the chain, 8-50 %) is mostly the candidate window:
`MilestonePlanner` drops gear you do not have below `level - 8`, `MilestonePicks` floors later
milestones at `m - 20`. The chain's Lv 110/120 sets also hold pieces it carried from more than 20
levels below (The Modulator Lv 88, Diamond Fusion Lv 95), which no search of that level alone may
look at; those milestones are noted, not compared.

## Goals / Non-Goals

- Goals: the chained search reaches what a fresh search of the same level reaches (3 %, judged
  alike); a level searched alone stays within 10 % of the chain where it can reach the same gear;
  deterministic; runtime within +5 % of the base.
- Non-Goals: widening the window past 20 (tried 30: bigger pools crowd the narrow test search and
  the chain falls 5-7 % behind fresh at Lv 70 and 110); seeding a level searched alone with the
  chain's set (the map's first milestone is that same search, so in the game they already agree).

## Decisions

**ADR-1 (amended).** Wave 1 kept the search untouched. Wave 2 changes the search's starts and the
first milestone's candidate window only; the score, the judge (respec charge, keep bonus), the beam
and the local search stay as they are.

**ADR-2: Element weapon starts, chosen without the last set.**
Status: accepted. Context: the weapon pool is ranked by a swap into the last milestone's set, so an
element whose weapons need other skills drops out whole. Decision: for each element not covered by
the pool's weapon starts or the held weapon, add the weapon that scores best alone (no armour,
points spread for it). Alternatives: rank every weapon around itself (`aroundWeapon` for all,
rejected: about 60-90 weapons a milestone, far over the runtime bound); a beam from an empty set
(rejected: it draws from the same biased pools); one start per element always (works, but costs
more tries for no gain where the element is already started). Consequences: at most four more
weapon starts a milestone, each bounded like the others (`weaponStartReach`, `weaponStartsMax`).

**ADR-3: One candidate window for every milestone.**
Status: accepted. Decision: the candidate list keeps gear down to `level - shopping.milestoneWindow`
(20) instead of `level - 8`. Consequences: the first milestone (and the build window's "now") may
suggest gear up to 20 levels below; its pools are a little larger; the chain's path from Lv 44 moves
(fixture drift).

## Pseudocode

```
weaponStarts(c, inc):
    out   = first k of weaponPool(c.pools) at level <= m, names unique (held excluded)
    all   = every weapon in c.wide
    covered = { element(w) : w in all, w.name in names }  ∪ { NEUTRAL }
    for el in ELEMENTS (fixed order), el not in covered:
        best = argmax over w in all, element(w) = el, w.level <= m of
                   allocate({WEAPON: w}).objective        (tie: smaller name)
        if best: out += best
    out += Mythics and owned weapons until weaponStartsMax
    return out

element(w) = index of w's largest elemental damage (low + high), 0 when it has none

candidates(level):
    keep info unless lv > last milestone
                  or lv < level - milestoneWindow and not owned      (was level - 8)
                  or bad tier / walk speed / attack speed
```

Each added start goes through the existing `runStarts`: built with `aroundWeapon`, searched only
when within `weaponStartReach` of the best, folded in the starts' order (deterministic).

## Measurements (BaselineProbe, 3 runs, median; same session and machine)

| run | shaman-44 ms | tried | synthetic ms | tried |
|---|---|---|---|---|
| base 0a58f13 | 9617 | 216984 | 2699 | 37223 |
| before 9475955 | 9559 | 249198 | 2529 | 42984 |
| after | 8922 | 246963 | 2439 | 46599 |

shaman-44 judged / XP-free objective per milestone, before → after:
44 9191/7656 → 9311/8193; 50 11075/9522 → 11024/9494; 60 23767/21375 → 22867/20531;
70 35840/30809 → 34165/30452; 80 51131/42593 → 50624/37801; 90 90172/79479 → 103496/92770;
100 128243/128624 → 131031/132434; 110 142729/127161 → 142454/127162; 120 136477/127804 →
149674/142286. Synthetic: unchanged at every milestone.

Watch: from Lv 44 the chain takes another path (Desert Winds ×2 at Lv 44, the ornaments at Lv 60,
the Cosmic set and Ring of Wisdom at Lv 80, raw objective +2.4 % but DPS -27 %). That is the
score's XP weight choosing, not the search; judged against its own chain each milestone is within
3 % of a fresh search.

## Risks

- Path dependence: a better first milestone can lead to a lower judged score later (Lv 60/70
  -4 % judged). Mitigated by the chained-vs-fresh bound at every milestone.
- The full-width game search (jointPool 40, beam 200) was not measured here; the starts only add
  to it.
