# Tasks

Three writers, one branch each off mission/algorithm, each in its own worktree. Files are owned
by one writer except `build/Damage.java`, which all three touch in separate hunks (F1: summon block
~799-825; F2: `model()` survival keys ~561-568, `ehpFloor` ~578-585 and the floor lines ~878-884;
F4: XP lines ~850-858, only if needed). Integration resolves Damage.java and owns BeamCache.
Every writer follows the SOP in design.md: test first, mutation check, measure.

## 1. F1 summons (owner: writer F1 — TreeModel.java, Coach.java, Damage.java summon hunk)

- [ ] 1.1 Write `SummonCalibrationFloorTest` (steady 600 s at 362/s → summonCalibration < 0.5; same result for two mainAttackWeight values) and see it fail on the baseline
- [ ] 1.2 Add `boundedSummon` (steady 0.05-10) and subtract unweighted `mainTotal()` in `Coach.calibrationChanges`; verify 1.1 passes and `SpellCalibrationTest` still passes
- [ ] 1.3 Add `summonAlive()` method (no static field) to TreeModel summon reads and `case "summonAlive" -> 1` in `Overlay.adjust`; apply `model("summonAlive", 0.6)` in the Damage summon block and `perSummon`; verify `SummonsCountedOnceTest` and `DamageBreakdownTest` pass and a new assert shows the factor applied once
- [ ] 1.4 Mutation check: revert each fix line, confirm 1.1 / the counted-once assert fail, restore
- [ ] 1.5 Report shaman-44 summon share of the score before/after at Lv 44, 72, 100 and the judged-score table with a reason per fixture diff

## 2. F2 health floor (owner: writer F2 — Damage.java survival/floor hunks, DeathCoach.java text only)

- [ ] 2.1 Extend `SurvivalTest`: floor factor = 1 at/above floor, monotone and < 1% change per 1% step from 100% to 95%, ≥ 0.4 at 10% and 0%; survivalLearned 2.0 gives 1.2× on DAMAGE and 2.0× on SAFE; see the new asserts fail on the baseline
- [ ] 2.2 Add `floorFactor` (m + (1-m)·exp(-a·(1-r)²), keys ehpFloorMin 0.4, ehpFloorSharpness 4) and use it in `evaluate`; verify 2.1 floor asserts pass
- [ ] 2.3 Add `deathCap()` (1.2 on DAMAGE via `Setup.goal()`, else 2) to `model()` and `ehpFloor`; verify 2.1 cap asserts and the existing `ehpFloor` asserts pass (any changed expectation gets a written reason)
- [ ] 2.4 Update DeathCoach comments/chat text only if they promise a fixed 2× on every goal; verify `checkLang` if a lang key changes
- [ ] 2.5 Mutation check on 2.2 and 2.3, then report synthetic Lv 60-120 assigned Defence per milestone (≤ ~70) and the judged-score table with reasons

## 3. F4 XP on fighting goals (owner: writer F4 — Setup.java, Feedback.java, Damage.java XP hunk if needed)

- [ ] 3.1 Write tests: `Setup.weights` gives goalXp 0.3 for DAMAGE and BOSS; `upgrade` of old DAMAGE rules sets goalXp 0.3 once and keeps byHand; Feedback on Damage goal with a more-XP piece writes no `xpWeight`; see them fail on the baseline
- [ ] 3.2 Change goalXp in `weights` (DAMAGE/default and BOSS → 0.3), add `goalXp` to the `upgrade` key list, `SURVIVAL_MODEL` 2 → 3; verify 3.1 Setup tests and `RoleGoalTest`, `ModelLimitsTest` pass
- [ ] 3.3 Skip the `xpWeight` nudge in `Feedback.learn` when `model.xpLog > 0`; verify the 3.1 Feedback test passes and other nudges still fire
- [ ] 3.4 Road map test: shaman-44 `atLevel(72)`, xpWeight 1.5, Damage goal → the two rings are not both Precious; only if it still fails, cap the XP weight in the Damage XP hunk and say why
- [ ] 3.5 Mutation check on 3.2-3.4, then the judged-score table with reasons

## 4. Integration (owner: integration — merges, Damage.java conflicts, BeamCache.java)

- [ ] 4.1 Merge F1, F2, F4 into mission/algorithm, resolving Damage.java hunk by hunk; verify each writer's new tests still pass after the merge
- [ ] 4.2 Bump `BeamCache.modelVersion()` 7 → 8 (once); verify `BeamCacheTest` passes
- [ ] 4.3 One full build without the probe; verify all tests and checks pass (checkWynntilsImports, checkPersonal, checkLang); grep the diff for new static fields with initializers (none allowed)
- [ ] 4.4 BaselineProbe three runs each: medians ≤ 9527 ms (shaman-44) and ≤ 2605 ms (synthetic)
- [ ] 4.5 Held-out: ReferenceModelTest unchanged (EHP exact, melee within 1%), synthetic Lv 20-50 judged not lower, shaman-44 atLevel(72) and (100) reported with reasons
- [ ] 4.6 Final before/after table of shaman-44 judged score per milestone (Lv 44-120) with every fixture diff explained; update fixtures with `-PupdateFixtures` only for explained diffs

## Workflow follow-up

- Open a draft PR only when the user says so; merge only on the user's word.
- Archive the change after merge.
