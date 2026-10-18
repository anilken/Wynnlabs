# Reference builds (WynnBuilder)

`ReferenceModelTest` checks the mod's build model against WynnBuilder, the community's build
calculator. The reference numbers live in `src/test/resources/reference/wynnbuilder/*.json`;
this is how to make or refresh one. Only numbers and links are stored, never WynnBuilder's code
or data.

## Entering a build

1. Open https://wynnbuilder.github.io/builder/ .
2. Type each item's name into its slot (helmet, chestplate, leggings, boots, ring 1, ring 2,
   bracelet, necklace, weapon) and the level into "Build level".
3. Skill points: the five boxes take the **total** of each skill (assigned + what the items
   give), not the points you assign. To match a character's assigned points:
   - set all five boxes to 0 first; the "Assign" line under each skill then shows minus the
     items' bonus (e.g. "Assign: -18" means the items give +18 Intelligence);
   - enter assigned + bonus in each box; "Assign" must now show the assigned points and the
     line below must read "Assigned N skillpoints" with N = 2 × (level − 1) (up to Lv 101).
4. WynnBuilder uses each item's **max** rolls; the test builds the items with max rolls too.
5. If WynnBuilder warns "illegal item combination", the build cannot be worn: fix the build
   (the model must never propose it), do not store it.

## Reading the numbers (Summary tab)

- `totalHealth`: "Total HP"
- `effectiveHealth`: "Effective HP"
- `effectiveHealthNoAgility`: "Effective HP (no agi)"
- `meleeDps`: "Relik Melee" (or the class's melee) → "Average DPS"

Spell damage is not compared here: WynnBuilder needs the ability tree clicked in by hand.
Spells are checked against the mod's own in-game measurement (measured / model on the
Damage tab) instead.

## File format

See `worn-lv49.json`: name, date, wynnbuilderData (the data version WynnBuilder loads, shown
in its network requests as `/data/<version>/`), link ("Copy for sharing" or the address bar),
level, items by build slot (HELMET … RING1, RING2 … WEAPON), assigned points, rolls ("max"),
the WynnBuilder numbers and the tolerance (share, 0.05 = 5%).

## When to refresh

After a Wynncraft balance update, if `ReferenceModelTest` drifts: re-enter the builds, update
the numbers, date and data version. Do not widen the tolerance to make a drift pass.
