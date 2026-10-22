# Proposal

## Why

Wave 1 fixed the score and left the road map search alone (ADR-1). Two search faults remain, both
seen on the shaman-44 fixture:

- The chained map at Lv 90 ends on a Fire set (Thermals, The Modulator) while a search of the same
  level from scratch finds a Thunder set (Loaded Question, two Black Catalyst, Cranial Panel) that
  judges 3.4 % higher against the same Lv 80 points. The chained search never tries it: its weapon
  starts come from a pool ranked by a swap into the Lv 80 set, where Loaded Question cannot be
  worn at all, so no Thunder weapon is in the pool.
- A search of one level alone (the build window's "now", and the map's first milestone) only looks
  8 levels down for gear you do not have, while every later milestone looks 20 down. A Lv 70
  searched alone never sees the Lv 60 Stronghold, Photon or Lightshow the chained map wears there
  and lands 11 % below it; Lv 50 lands 19 % below.

## What Changes

- **Element weapon starts:** for each element no weapon start (nor the weapon held) is of, the
  weapon of that element that does most on its own is also built around and searched.
- **One candidate window:** your own level looks as far down as every other milestone
  (`shopping.milestoneWindow`, 20).
- The chained-vs-fresh test judges both sets the way the chain judges its own (against the last
  milestone's points and pieces) with the 3 % bound, and a second test checks that a level searched
  alone stays within 10 % of the chained map where it can reach the same pieces.
- The road map cache's model version goes up once.

## Capabilities

### Modified Capabilities

- `build-model`: adds requirements for the road map search's weapon starts and its candidate window.

## Impact

- Code: `build/roadmap/SetSearch.java`, `build/roadmap/MilestonePlanner.java`, `build/roadmap/BeamCache.java`.
- Tests: `RoadmapMilestonesTest` restated and extended; the shaman-44 road map fixture moves.
- Runtime: within +5 % of the wave-1 base (measured; shaman-44 is faster than before).
- Players: saved road maps are rebuilt once; the first milestone may suggest gear up to 20 levels
  below your level.
