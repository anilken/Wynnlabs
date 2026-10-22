# Proposal

## Why

Assumption: "the algorithm" in this mission means the build scoring and the road map search that
ranks pieces with it. Three faults in the score lead the road map astray today: summons are counted
as if they never die and their calibration cannot go low enough to match what the damage meter
hears; a build just under the health floor is punished by the cube of the shortfall, so the search
pours skill points into Defence; and on fighting goals XP bonuses still weigh enough (and feedback
can raise them further) that a Lv 72 Shaman is told to wear two Precious rings.

## What Changes

- **F1 Summons:** summons count only for the time they are alive; the summon calibration may fall
  to about 0.05 of the model once the measurement is steady; the main attack is taken off the
  measured background at its full modelled rate, not at its score weight.
- **F2 Health floor:** below the floor the life factor falls smoothly and stays above a lower
  bound, instead of falling with the cube of the shortfall; on the Damage goal what deaths taught
  can raise survival and the floor by at most about 1.2×.
- **F4 XP on fighting goals:** the Damage and Boss goals weigh XP at about 0.3 of the base weight
  (stored rules are brought up to it once), and swapping to an XP piece no longer nudges the XP
  weight up while a fighting goal is set.
- The road map cache's model version goes up once, so cached plans scored by the old model are
  not reused.
- Every fix ships with its own test, a before/after report of judged scores per milestone and a
  runtime check against the recorded baseline.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `build-model`: adds requirements for summon uptime and calibration, a bounded health-floor
  penalty, how much XP counts on fighting goals and the acceptance gate a score change must pass;
  the feedback requirement no longer lets XP be nudged up on fighting goals.

## Impact

- Code: `build/Damage.java` (shared by all three fixes), `build/TreeModel.java`,
  `coach/Coach.java` (F1); `coach/DeathCoach.java` if its wording needs it (F2);
  `build/Setup.java`, `coach/Feedback.java` (F4); `build/roadmap/BeamCache.java` (integration).
- Tests: new unit tests next to `SpellCalibrationTest`, `SummonsCountedOnceTest`, `SurvivalTest`,
  `ModelLimitsTest`; fixture verdicts may move and each move must be explained.
- Players: saved road maps are rebuilt once; Damage/Boss players see fewer XP pieces suggested.
- No new dependencies, no API shape changes, no UI changes.
