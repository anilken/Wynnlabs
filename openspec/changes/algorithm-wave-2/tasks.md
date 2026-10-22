# Tasks

One writer on mission/algorithm. SOP as in algorithm-wave-1 (test first, mutation check, measure).

## 1. Tests

- [x] 1.1 Restate `aChainedMilestoneKeepsUpWithOneSearchedFresh`: both sets judged against the chain's previous points and pieces, 3 % bound; see it fail at Lv 90 on 9475955
- [x] 1.2 Add `aMilestoneSearchedAloneKeepsUpWithTheChain`: raw scores, 10 % bound, milestones where the chain wears unreachable pieces noted not compared; see it fail at Lv 50, 70 and 110 on 9475955 (Lv 120 is noted: the chain wears Lv 86-95 pieces there)

## 2. Search

- [x] 2.1 Element weapon starts in `SetSearch.weaponStarts` (`elementBests`, `element`)
- [x] 2.2 One candidate window in `MilestonePlanner` (`shopping.milestoneWindow`)
- [x] 2.3 Bump `BeamCache.modelVersion()` to 9
- [x] 2.4 Mutation check: no element starts → 1.2 fails at Lv 90; window back to 8 → 1.2 fails at Lv 50 and 70

## 3. Measure

- [x] 3.1 BaselineProbe 3 runs on base 0a58f13, 9475955 and the change in one session: runtime medians, tried, judged and XP-free objective per milestone
- [ ] 3.2 Final targeted run `build.*` and `coach.*`: only fixture drift may fail; fixtures regenerated at integration
