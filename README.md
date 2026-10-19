# Wynn Labs

An in-game assistant for [Wynncraft](https://wynncraft.com): a client-side mod for Minecraft
1.21.11 on Fabric or NeoForge. It reads your own character, bag and bank, and helps you decide what to do.

- **Items:** wear, sell, identify or keep, in the tooltip with the reason.
- **Upgrades and ability tree:** the best gear for your level and build, and the tree with the plan's order.
- **Quests and secret discoveries:** step-by-step guides in English or Turkish, with waypoints.
- **Endgame:** raid and lootrun records, mythic dry streaks, your aspects, and this week's raid and lootrun pools.
- **Crafting, bank and prices:** recipe odds, a searchable bank index, Trade Market prices.
- **Coach:** death warning, mana and DPS meters, session report, where to level next.

**Read-only.** Wynn Labs never clicks, moves, casts or sends anything to the server for you.

## Install

Take the jar for your loader from the release (same mod in both) and put it into `mods`:

- **Fabric:** [Fabric Loader](https://fabricmc.net) for 1.21.11, Fabric API, and
  [Wynntils](https://modrinth.com/mod/wynntils) for Fabric 4.1.0 or newer. Jar: `wynnlabs-fabric-<version>.jar`.
- **NeoForge:** [NeoForge](https://neoforged.net) 21.11.48 or newer for 1.21.11, and Wynntils for
  NeoForge 4.1.0 or newer. Jar: `wynnlabs-neoforge-<version>.jar`.
- Optional on both: [Wynnventory](https://modrinth.com/mod/wynnventory) for that loader, for item prices.

Press **Y** for the menu. The mod follows the game's language (English and Turkish).

## Settings and privacy

Everything is in the Wynn Labs menu's settings tab. The mod contacts only:

- `www.wynnventory.com`, with **Live prices** on: listings, and the weekly raid and lootrun pools at most once an hour.
- `wynncraft.wiki.gg`, with **Quest guides from the wiki** on: quest and discovery pages, cached for a week.
- `api.github.com`, with **Check for updates** on (the default): once per session, to see whether a newer release is out.

**Wynnventory key.** Paste your key in the settings tab, or build the mod where a key file exists
(`$WYNNLABS_WYNNVENTORY_KEY_FILE` or the instance's `config/wynnlabs/wynnventory-key.txt`) to embed
it in the jar, AES-256-GCM encrypted. Everything needed to decrypt ships in the same jar, so keep
such a jar to yourself. A key saved in settings wins.

Everything the mod remembers is under `config/wynnlabs/`. Delete it to start fresh.

## AI assistant and local API

Turn on the local API in the Wynn Labs menu's **AI assistant** tab. It serves your game state as JSON on
`127.0.0.1:47591` (`GET /` lists the endpoints). **Install for Claude Code** copies the skill
([`ai/skills/wynnlabs/SKILL.md`](ai/skills/wynnlabs/SKILL.md)); **Copy prompt** works with any other
assistant. Writing advice or rules (`POST /advice`, `POST /rules`) needs the **AI may write advice
and rules** switch and the header `X-Wynninv: 1`.

## Development

Put the Wynntils fabric jar into `libs/` (both loaders compile against it), then:

```bash
gradle build                              # fabric/build/libs/wynnlabs-fabric-<version>.jar and neoforge/...
gradle build -Penabled_platforms=fabric   # Fabric only, skips NeoForge's setup
```

This also runs the tests (fixtures recorded from a real session, `gradle test -PupdateFixtures` to
approve intended changes) and the checks `checkLang`, `checkPersonal` and `checkWynntilsImports`.
`gradle checkWynntilsVersions` compiles against every jar in `libs/wynntils-matrix/`.
`gradle :neoforge:runClient` starts a NeoForge dev client (with Wynntils if its neoforge jar is in `libs/`).

**Developer tools (dev builds only).** `common/src/dev` holds tools for the mod's own pages, such as
the menu screenshot command below. Every local build includes them. A release build (one that passes
`-Pmod_version`, as the Release workflow does) compiles without them. Its jars have no dev class and
no `wynnlabs.dev.mixins.json`, and do not register the command. `checkDevTools` fails the release when
they slip in. Add `-PdevTools` to a `-Pmod_version` build to keep them. The menu reaches them only
through `DevHooks`, whose hooks are `if (DevHooks.ON)` checks on a constant that is `false` in a
release jar. Their tests live in `fabric/src/devTest` and `fabric/src/devGametest`.

**Menu screenshots.** In a dev build with **Debug logging** on (Y → Settings → Prices and data), type
`/wynnlabs shots [en|tr|current] [all|<tab>] [x2|x3|x4] [hidename]` in a world (default `en all`).

- **What it does.** It switches the mod's menu language and opens the menu. Then it takes every view
  of every tab in rail order: the tab as it opens, then its sub-pages, filters and windows. Examples
  are the settings pages, the quest filters and detail tabs, the bank filters and a search, Endgame's
  aspect filter, Pools and each region, Crafting's lower part and suggestions, the ability tree's end,
  and Home's Choose items window (with a type filter), results, plan and My builds. It also takes
  tooltip shots of a gear slot, a Missing pill, a list row, the week tile, a pool row, a bank item,
  a quest and a discovery.
- **What it skips.** A view with nothing to show (no plan, no result, an empty filter) is skipped, and
  the numbers stay in a row.
- **Output.** The pictures go to
  `screenshots/wynnlabs/<yyyy-MM-dd_HH-mm-ss>/<lang>-<nn>-<tab>-<state>.png` (`main` for the tab as it
  opens). Close-ups of key panels (Home's gear panel and week tile, the build results, the pools, the
  bank list) go to `close/…-close-<part>.png`. An `index.html` lists them all, and a chat line links
  the folder.
- **Picture.** Each picture is the mod's window alone, plus a tooltip that reaches past it. Minecraft's
  own screenshot code grabs the view twice, over a black and a white backdrop instead of the world,
  and the two give every pixel its alpha. The picture is cropped with a 3 px transparent margin and
  transparent rounded corners.
- **Options.** `<tab>` is one of `home craft tree today endgame quests discoveries bank damage deaths
  ai settings`. `x2`/`x3`/`x4` draw the menu at that menu scale for the run. The menu fills the game
  window at every scale, so a larger scale gives larger text, not a larger image. `hidename` shows
  "Player" for your Minecraft name in any text the menu draws.
- **Restore.** The HUD stays out of the pictures. The language, the menu scale, the screen and the
  menu's views go back as they were at the end, on Esc or on an error.
- **Limits.** It only opens the mod's own menu and never starts a road map build or a build search.
  Build the road map in that language first if you want it in the pictures.

`gradle :fabric:runClientGameTest -PpreviewOnly=shots` checks the command on the recorded character.

`install-when-closed.sh` copies the Fabric jar into `$WYNNLABS_MODS` (or `.local-mods-path`) once
Minecraft is closed; with `neoforge` it copies the NeoForge jar into `$WYNNLABS_NEOFORGE_MODS` (or
`.local-mods-path-neoforge`).

**Hot swap.** Start the game on [JetBrains Runtime 21](https://github.com/JetBrains/JetBrainsRuntime/releases)
with `-XX:+AllowEnhancedClassRedefinition -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005`,
then push a fresh build into the running game:

```bash
java --add-modules jdk.jdi tools/HotSwap.java fabric/build/libs/wynnlabs-fabric-<version>.jar <installed mod jar>
```

Changed methods and new or removed methods and fields load at once (screens show it when reopened;
F3+T reloads lang files and textures). New classes, mixins and keys or commands need a restart.

**Commits and PR titles** follow [Conventional Commits](https://www.conventionalcommits.org/):
`type(scope)!: subject`, e.g. `fix(bank): pages save while flipping fast`. PRs are squash-merged,
so the PR title becomes the commit on `main` (the PR title check enforces it). The header sets the
next version (`.github/.config.js`): `chore(bump-mc)`, `feat(major)` or `chore!` → major,
`feat!` or `fix!` → minor, anything else → patch.

**Releases.** Actions → Release → Run workflow on `main` (tick `dry_run` to only see the next
version and its notes), or push to the `release` branch. It versions from the commits since the
last `v*` tag, builds both jars with `-Pmod_version=<next>`, tags `v<next>` and makes the GitHub
release with the notes; nothing is pushed to `main`. Optional repository secrets:

| Secret | Enables |
|---|---|
| `WYNNVENTORY_KEY` | the embedded Wynnventory key in the jars (without it: `-PnoEmbeddedKey`) |
| `MODRINTH_TOKEN`, `MODRINTH_PROJECT_ID` | uploading both jars to Modrinth |
| `CURSEFORGE_TOKEN`, `CURSEFORGE_PROJECT_ID` | uploading both jars to CurseForge |

## License

Copyright (c) 2026 anilken. [GNU LGPL v3.0](LICENSE) (with the [GPL v3.0](COPYING) it builds on). Not affiliated with Wynncraft, Wynntils or Wynnventory.

- [Noto Sans](https://github.com/notofonts/latin-greek-cyrillic), SIL OFL 1.1: `assets/wynnlabs/font/OFL.txt`.
- [Tabler Icons](https://tabler.io/icons), MIT: `assets/wynnlabs/textures/gui/sprites/icon/NOTICE.txt`.
- `assets/wynnlabs/trees/`: ability tree layout from `api.wynncraft.com/v3/ability/map/<class>`.
- `assets/wynnlabs/quests/` and `assets/wynnlabs/discoveries/`: adapted from the
  [Wynncraft Wiki](https://wynncraft.wiki.gg) under
  [CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/); every entry links its page
  (see each folder's `NOTICE.txt`). Not covered by the LGPL, not for commercial use.
