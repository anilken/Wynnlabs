## What and why

<!-- One or two sentences: what changes for the player, and why. -->

## How it was checked

- [ ] `gradle build` passes (tests, `checkLang`, `checkPersonal`, `checkWynntilsImports`)
- [ ] UI change: the one-tab preview was run (`gradle runClientGameTest -PpreviewOnly=layout -PpreviewTab=<tab>`) and shows no problems
- [ ] Tried in game (say what)

## Checklist

- [ ] Read-only toward the game: nothing clicks, moves, casts or sends for the player
- [ ] New text goes through lang keys in both `en_us.json` and `tr_tr.json`
- [ ] No real character ids, player names or API keys in code, tests or fixtures
- [ ] Wynntils is only used through `Wyn`, `WynntilsItems` or `WynntilsEvents`

## Screenshots

<!-- For UI changes: before and after. -->
