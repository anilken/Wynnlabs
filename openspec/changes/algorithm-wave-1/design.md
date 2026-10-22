# Design

## Context

See proposal.md (Why) and specs/build-model/spec.md (acceptance criteria). Baseline is main
0a58f13, measured with BaselineProbe (kept outside the repo in the mission scratchpad base
worktree): shaman-44 median 9073 ms (216984 tried), synthetic median 2481 ms (37223 tried),
deterministic. Judged per level: 44 16320 | 50 18755 | 60 38934 | 70 55428 | 80 100679 |
90 147971 | 100 190805 | 110 227348 | 120 164424. ReferenceModelTest passes (EHP exact, melee +1%).

Code facts the fixes rest on (paths under `common/src/main/java/io/github/anilken/wynnlabs/`):

- `build/TreeModel.java` `overlay(...)` (about lines 445-467) sums tree reads into `passive[]`;
  summon reads use uptime 1, area reads `AREA_UPTIME` 0.5, then `passiveCap` scales the total.
  `Overlay.adjust` (about line 187) maps `passiveDpsFactor`, `summonHitsPerSecond`, `summonX` to
  the tree's numbers, so with a tree the profile's summon keys are replaced, not added.
- `build/Damage.java` summon block (about 799-825) turns `summonX` into `passive`, adds
  `lastingDps`, multiplies by `summonCalibration`, fills `parts.summon` and `parts.summonRaw`.
- `build/Damage.java` life block (about 861-884): `fLife` log + power, then
  `pow(ehpElemental/floor, ehpFloorPower=3)` below `ehpFloor(level)` (578-585, death raise up to
  2×); `model()` (561-568) multiplies survival keys by `survivalLearned` (1-2).
- `coach/Coach.java` `calibrationChanges` (504-515) sets `summonCalibration` from the background,
  subtracting `mainTotal × mainAttackWeight`; `bounded` (527) keeps 0.5-10 steady, 0.7-1.4 not.
- `build/Setup.java` `weights` (144-161): index 6 is `goalXp` (DAMAGE default 1, BOSS 0.5);
  `upgrade` rewrites only survivalWeight, survivalPower, ehpFloorScale, xpLog.
- `coach/Feedback.java` `learn` (83-84) nudges `xpWeight` whenever the worn piece has more XP.
- `build/roadmap/BeamCache.java` `modelVersion()` returns 7.

## Goals / Non-Goals

**Goals:**
- Land F1, F2, F4 as three independent writer branches that merge cleanly except for
  `Damage.java`, where each owns a separate hunk.
- Keep the judged score honest: every fixture diff explained, runtime within +5%.

**Non-Goals:**
- F3 (nowOnly starts), F5 (tomes), F6 (armour elemental defence), F9 (pair pruning): later waves.
- Retuning spell calibration, the speed curve or the search's beam widths.
- Any UI, lang key or API change.

## Decisions

**ADR-1: Fix the score's parts, not the search.**
Status: accepted. Context: the three wrong picks (summon-led scores, Defence hoarding, Precious
rings) all come from a factor in `Damage.evaluate`, not from the search missing better builds.
Decision: change the factors (alive share, floor shape, goal XP) and the two learners that feed
them (Coach calibration, Feedback nudge), keep the search untouched, bump `modelVersion` once.
Alternatives: special-case items in the search (rejected: hides the cause, breaks other classes);
retune `ehpFloorPower` only (rejected: any power stays unbounded near zero and kinks at the floor).
Consequences: fixture verdicts will move; cached plans rebuild once.

**SOP (each writer, then integration):**
1. Test first: write the scenario's unit test, see it fail on the baseline.
2. Fix with the smallest change in the owned hunk.
3. Mutation check: revert the fix line(s) locally, confirm the new test fails, restore.
4. Measure: targeted tests, then BaselineProbe three runs (median) and judged score per milestone,
   before/after table with a reason per fixture diff (`-PupdateFixtures` only when intended).
5. Commit on the writer branch; integration merges, runs one full build, re-measures.
6. Draft PR only when the user says so; merge only on the user's word.

**D1 (F1): one alive factor, applied once per path.** Rules key `model.summonAlive`, default 0.6.
TreeModel multiplies summon reads by it; `Overlay.adjust("summonAlive")` returns 1 so Damage does
not apply it a second time; without a tree Damage applies it to the profile summons. Chosen over a
single multiply in Damage because area reads already carry their own `AREA_UPTIME` there.

**D2 (F1): summon calibration range.** A separate `boundedSummon(ratio, steady)`: steady 0.05-10,
not steady 0.7-1.4 (unchanged). Spell `bounded` keeps 0.5. Main attack is subtracted at full
`mainTotal()` (the meter hears real hits, the weight is a score preference), numerator kept ≥ 0.

**D3 (F2): smooth bounded floor factor.** For r = ehpElemental/floor in [0,1):
`f = m + (1 - m) · exp(-a · (1 - r)²)` with m = `model.ehpFloorMin` (0.4) and a =
`model.ehpFloorSharpness` (4). f(1) = 1 with zero slope (no kink), f(0.5) ≈ 0.62, f → m.
Chosen over `max(m, r^3)` (kinked at the bound and at the floor).

**D4 (F2): death raise cap per goal, applied at read time.** In `Damage.model` and `ehpFloor`,
the lesson multiplier is capped by `deathCap()` = 1.2 when `Setup.goal()` is DAMAGE, else 2. The
stored `survivalLearned` / `deathFloorRatio` stay as DeathCoach writes them, so goal switches are
lossless. DeathCoach logic is unchanged; only its comment/chat text if it states a fixed 2×.

**D5 (F4): goal XP factor 0.3 for DAMAGE and BOSS; no XP nudge on fighting goals.** Safe keeps 1
(it is a survival goal that still levels). Fighting goal = `model.xpLog > 0` (Damage, Safe, Boss),
the same flag Damage uses for the log XP curve. `Setup.upgrade` adds `goalXp` to the keys it
rewrites and `SURVIVAL_MODEL` goes 2 → 3 so saved rules get it once.

**Code rules for all writers:** no new static fields with initializers (hot swap leaves them
null); constants become literals or methods reading `Rules.sub(...)`. Wynntils imports only in
`game/`. `BeamCache.modelVersion()` 7 → 8 exactly once, by integration.

## Pseudocode

### F1 — summons (TreeModel, Damage summon hunk, Coach)

```
// build/TreeModel.java, overlay(...), loop at ~line 446
static double summonAlive():                       // method, not a field
    return clamp(Rules.sub("model", "summonAlive", 0.6), 0.05, 1)
for r in reads with conv:
    count = ...
    times = r.rate * count * (r.summon ? summonAlive() : AREA_UPTIME)
    passive[i] += r.conv[i] * times
// then the existing passiveCap scaling and rounding, unchanged

// build/TreeModel.java, Overlay.adjust switch (~line 187)
case "summonAlive" -> 1        // already inside passive[]

// build/Damage.java, summon block (~799-825)
alive = model("summonAlive", 0.6)                  // 1 under a tree overlay
if summons > 0:
    passive = hit(...) * model("summonHitsPerSecond", 6) * summons * multiplier * alive
perSummon = summons > 0 ? model("summonHitsPerSecond", 6) * summons * multiplier * alive : 0
// passiveRaw, summonCal, parts.summonRaw unchanged: summonRaw now already includes alive

// coach/Coach.java, calibrationChanges summon part (~504-515)
main   = b.mainTotal()                              // no × mainAttackWeight
steady = bg.seconds >= STEADY_SECONDS
target = boundedSummon(max(0, bg.perSecond - main) / summons, steady)
static double boundedSummon(ratio, steady):
    r = steady ? clamp(ratio, 0.05, 10) : clamp(ratio, 0.7, 1.4)
    return round2(r)
// mainAttackWeight parameter stays in the signature (callers unchanged), now unused here
```

Test (new, `fabric/src/test/.../build/SummonCalibrationFloorTest.java`): steady background
`{seconds: 600, perSecond: 362}` with the shaman-44 breakdown → `summonCalibration < 0.5`; same
measurement with two different mainAttackWeight values → same result; tree overlay vs. no overlay
counts alive once (extend `SummonsCountedOnceTest` if simpler).

### F2 — health floor (Damage life hunk + model()/ehpFloor, DeathCoach text)

```
// build/Damage.java
static double deathCap():
    return "DAMAGE".equals(Setup.goal()) ? 1.2 : 2

model(key, fallback):                               // ~561-568
    v = TreeModel.adjust(...) * Tuning.weight(key)
    if key in {survivalWeight, survivalPower}:
        v *= clamp(Rules.sub("model", "survivalLearned", 1), 1, deathCap())

ehpFloor(level):                                    // ~578-585
    deaths = min(deathCap(), Rules.sub("model", "deathFloorRatio", 0))
    return scale * ehpTable(level) * max(model("ehpFloorScale", 1.0), deaths)
    // note: deaths is a ratio of the table; on Damage (scale 1) the floor rises at most 1.2×

static double floorFactor(ehp, floor):              // new pure method, unit-testable
    if floor <= 0 or ehp >= floor: return 1
    r = max(0, ehp / floor)
    m = clamp(model("ehpFloorMin", 0.4), 0.05, 1)
    a = max(0.1, model("ehpFloorSharpness", 4))
    return m + (1 - m) * exp(-a * (1 - r)^2)

evaluate(...):                                      // ~878-884
    fLife *= floorFactor(ehpElemental, ehpFloor(level))
    // ehpFloorPower is no longer read; leave the key harmless in old rules
```

Tests (extend `SurvivalTest`): monotone and < 1% per 1% step from r = 1.00 to 0.95; ≥ 0.4 at
r = 0.1 and r = 0; = 1 at and above the floor; survivalLearned 2.0 → factor 1.2 on DAMAGE, 2.0 on
SAFE; existing `ehpFloor` assertions keep passing (they use deathFloorRatio ≤ 1.2 or non-Damage
goals; adjust only with a written reason). Road map check: synthetic Lv 60-120 Defence ≤ ~70.

### F4 — XP on fighting goals (Setup, Feedback, Damage XP hunk)

```
// build/Setup.java
weights("BOSS")    -> goalXp index 6: 0.5 -> 0.3
weights(default)   -> goalXp index 6: 1   -> 0.3        // DAMAGE
SURVIVAL_MODEL = 3                                      // existing constant, value change only
upgrade(rules):
    for k in {survivalWeight, survivalPower, ehpFloorScale, xpLog, goalXp}:
        if k not byHand: model[k] = w[indexOf(k)]

// coach/Feedback.java, learn (~83)
fighting = Rules.sub("model", "xpLog", 0) > 0
if !fighting and b.xp > a.xp:
    nudge(model, "xpWeight", 1.0, ...)

// build/Damage.java XP hunk (~850-858): expected unchanged; F4 owns it if the Lv 72 test
// still picks 2× Precious, e.g. cap xpWeight·goalXp at 0.5 when xpLog > 0
```

Tests: `RoleGoalTest`/`ModelLimitsTest` style unit for weights and upgrade (old DAMAGE rules →
goalXp 0.3, byHand kept, upgrade runs once); Feedback unit: Damage goal + more-XP piece → no
`xpWeight` key in the change; road map test: shaman-44 `atLevel(72)`, xpWeight 1.5, Damage goal →
the two RING picks are not both Precious.

### Integration

```
merge F1, F2, F4 into mission/algorithm; resolve Damage.java (three disjoint hunks)
BeamCache.modelVersion(): 7 -> 8
one full build; BaselineProbe ×3; judged table Lv 44..120; held-out checks
```

## Risks / Trade-offs

- [Subtracting full main attack can drive summon calibration to the floor for players who swing a
  lot] → floor 0.05 not 0, numerator ≥ 0, steady time required; report the shaman-44 value.
- [Softer floor may let builds drop too much health] → bound 0.4 plus log/power life terms stay;
  check deaths-prone fixtures (Lv 69-72 Shaman) do not fall below the old floor by more than 10%.
- [`deathCap()` reads the goal inside the hot scoring path] → only the two survival keys and
  `ehpFloor` call it; if the probe shows > 1% cost, read it once per evaluate and pass it down.
- [Three writers on Damage.java conflict] → each touches only its hunk; integration owns merge.
- [Fixture verdicts move] → expected; every diff gets a one-line reason before update.
- [goalXp change silently alters saved rules] → done once via SURVIVAL_MODEL, byHand kept.

## Migration Plan

Rules upgrade happens on load (SURVIVAL_MODEL 3). Cached road maps are invalidated by the
modelVersion bump. Rollback: revert the merge commit; old rules with goalXp 0.3 stay valid input.
