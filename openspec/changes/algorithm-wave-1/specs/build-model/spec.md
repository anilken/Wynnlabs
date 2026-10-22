# Spec Delta

## ADDED Requirements

### Requirement: Summons count only while alive
The summon part of the damage score SHALL be scaled by the share of time the summons are alive and hitting (a configurable factor, default about 0.6). The factor SHALL be applied exactly once, whether summon damage comes from the ability tree or from the class profile.

#### Scenario: Summon share drops
- **WHEN** the shaman-44 fixture is scored at its own level before and after this change
- **THEN** the summon share of the damage score is lower after it, and spell and main attack DPS are unchanged

#### Scenario: Counted once
- **WHEN** a build with tree summons is scored with the tree overlay active
- **THEN** the summon damage equals the tree's summon damage times the alive factor once, not its square

### Requirement: Summon calibration follows the meter
Once the background damage between casts has been measured for the steady time (300 s or more), the summon calibration SHALL be the measured background less the full modelled main attack DPS, divided by the modelled summon DPS, kept within about 0.05 to 10. Before it is steady the 0.7 to 1.4 bounds stay.

#### Scenario: A weak background
- **WHEN** a steady measurement says 362 damage per second in the background for a Summoner build whose modelled summons are far above that
- **THEN** the written summon calibration is below 0.5

#### Scenario: Main attack not weighted
- **WHEN** the main attack weight of the goal changes and the measurement does not
- **THEN** the summon calibration written is the same

### Requirement: The health floor penalty is bounded and smooth
A build below the health floor for its level SHALL lose score by a factor that is 1 at the floor, falls smoothly (no kink) as health drops, and never goes below a lower bound (default about 0.4).

#### Scenario: Near the floor
- **WHEN** elemental effective health goes from 100% to 95% of the floor in small steps
- **THEN** the life factor falls monotonically, each step changes it by less than 1%, and it is never below the bound

#### Scenario: Far below the floor
- **WHEN** elemental effective health is 10% of the floor
- **THEN** the life factor is at or above the bound

#### Scenario: Defence is not hoarded
- **WHEN** the synthetic character's road map is built for Lv 60 to 120
- **THEN** no milestone puts more than about 70 assigned points in Defence

### Requirement: Death lessons are capped on the Damage goal
On the Damage goal, what deaths taught SHALL raise the survival weights and the health floor by at most about 1.2×. Other goals keep the 2× cap. The stored lesson SHALL be kept as is, so switching goal restores its full effect.

#### Scenario: Many deaths on Damage
- **WHEN** the stored survival lesson is 2.0 and the goal is Damage
- **THEN** the survival weight used in scoring is at most 1.2× the goal's own

#### Scenario: Switching to Safe
- **WHEN** the same rules are scored with the Safe goal
- **THEN** the full 2.0 lesson applies

### Requirement: XP weighs little on fighting goals
On the Damage and Boss goals the XP weight SHALL be about 0.3 of the player's XP weight. Rules saved before this change SHALL be brought up to it once, keeping any weight set by hand.

#### Scenario: Precious rings at Lv 72
- **WHEN** the shaman-44 fixture's road map is built at Lv 72 on the Damage goal with an XP weight of 1.5
- **THEN** the ring slots are not both Precious

#### Scenario: Old rules
- **WHEN** the mod starts with Damage-goal rules from before this change
- **THEN** the goal's XP factor becomes 0.3, once, unless it was set by hand

### Requirement: Score changes pass an acceptance gate
A change to the build score or the road map search SHALL be accepted only when all existing tests pass, the reference builds still match (effective health exact, main attack within 1%), every fixture verdict that moved is explained, and the road map's median runtime over three runs is at most 5% above the recorded baseline.

#### Scenario: Runtime check
- **WHEN** the baseline probe runs three times on shaman-44 and on the synthetic character
- **THEN** the medians are at most 9527 ms and 2605 ms (baseline 9073 ms and 2481 ms plus 5%)

#### Scenario: Judged scores reported
- **WHEN** a score change is ready for review
- **THEN** the judged score of shaman-44 at each milestone (Lv 44 to 120) is listed before and after, and each fixture diff has a one-line reason

#### Scenario: Held-out cases
- **WHEN** the change is checked on cases not used to tune it
- **THEN** the synthetic character's judged scores at Lv 20 to 50 are not lower than before, and shaman-44 at Lv 72 and Lv 100 is reported with any change explained

## MODIFIED Requirements

### Requirement: Feedback moves weights within limits
When the player wears something other than the advice, the model's weights MAY be nudged. Each nudge SHALL be small, and no weight SHALL go past a cap (default 1.5× its default). Values the player set by hand SHALL be kept. Weights above the cap from earlier nudges SHALL be clamped once, with a chat line naming them. While a fighting goal (Damage, Safe, Boss) is set, the XP weight SHALL NOT be nudged up.

#### Scenario: Repeated nudges
- **WHEN** the player keeps choosing faster pieces over the advice
- **THEN** the speed weight rises step by step and stops at its cap

#### Scenario: Old nudged values
- **WHEN** the mod starts with a speed weight of 1.0 from earlier nudges and a cap of 0.45
- **THEN** the weight becomes 0.45, and the player sees one chat line saying the speed weight was reset to its limit

#### Scenario: XP piece on a fighting goal
- **WHEN** the goal is Damage and the player wears a piece with more XP bonus than the advice
- **THEN** the XP weight is not changed, and other nudges still apply
