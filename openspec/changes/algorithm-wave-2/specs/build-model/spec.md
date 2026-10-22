# Spec Delta

## ADDED Requirements

### Requirement: The road map search starts from every element
Besides the weapons the slot ranking keeps, each milestone's search SHALL build a set around, and search from, the strongest weapon alone of every element that none of its other weapon starts (nor the weapon held) is of. The choice SHALL not depend on the last milestone's set and SHALL be the same on every run.

#### Scenario: A weapon the last set cannot wear
- **WHEN** the shaman-44 road map reaches Lv 90 and no Thunder weapon can be swapped into the Lv 80 set
- **THEN** the search still tries a set around Loaded Question, and the milestone's set judges within 3 % of the same level searched from scratch, both judged against the Lv 80 points

### Requirement: Every milestone looks the same distance down
The candidates for your own level SHALL reach as far below it as those of every later milestone (`shopping.milestoneWindow`, 20 levels), gear you have being kept at any level.

#### Scenario: A level searched alone
- **WHEN** a character at Lv 70 has its level searched alone
- **THEN** the Lv 60 pieces the chained map wears there are among its candidates, and its set scores within 10 % of the chained map's
