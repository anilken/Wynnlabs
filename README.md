# Wynn Labs

An in-game assistant for [Wynncraft](https://wynncraft.com): a client-side mod for Minecraft
1.21.11 on Fabric or NeoForge. It reads your own character, bag and bank, and helps you decide what to do.

- **Items:** wear, sell, identify or keep, in the tooltip with the reason; hover a badge or a game word for what it means.
- **Upgrades and ability tree:** the best gear for your level and build, the tree with the plan's order, an archetype picker, and a WynnBuilder link as the target build.
- **Quests and secret discoveries:** step-by-step guides in English or Turkish, with waypoints.
- **Search:** items, mobs, places, dungeons and quests, with where to get things and a compass.
- **Market:** Trade Market listings with roll %, box contents and their prices.
- **Endgame:** raid and lootrun records, mythic dry streaks, your aspects, and this week's raid and lootrun pools.
- **Crafting and professions:** recipe odds, best layouts, and a planner to level each profession.
- **Coach:** death warning, mana and DPS meters, session report, where to level next.

**Read-only.** Wynn Labs never clicks, moves, casts or sends anything to the server for you.

## Install

Take the jar for your loader from the release and put it into `mods`:

- **Fabric:** [Fabric Loader](https://fabricmc.net) for 1.21.11, Fabric API, and
  [Wynntils](https://modrinth.com/mod/wynntils) 4.1.0 or newer.
- **NeoForge:** [NeoForge](https://neoforged.net) 21.11.48 or newer, and Wynntils for NeoForge 4.1.0 or newer.
- Optional: [Wynnventory](https://modrinth.com/mod/wynnventory) for item prices.

Press **Y** for the menu. The mod follows the game's language (English and Turkish). New versions
can be downloaded from inside the game: click the link in chat or use Settings › About.

## Privacy

The mod contacts only:

- `www.wynnventory.com`, when **Live prices** is on (you're asked once): listings and the weekly pools.
- `wynncraft.wiki.gg`, when **Quest guides from the wiki** is on: quest and discovery pages, cached for a week.
- `api.github.com` / `github.com`, when **Update reminder** is on: once per session to check for a release, and to download it when you ask.

Everything it remembers is under `config/wynnlabs/`. Delete it to start fresh.

## AI assistant and local API

Turn on the local API in the menu's **AI assistant** tab. It serves your game state as JSON on
`127.0.0.1:47591` (`GET /` lists the endpoints). **Install for Claude Code** copies the skill
([`ai/skills/wynnlabs/SKILL.md`](ai/skills/wynnlabs/SKILL.md)); **Copy prompt** works with any other
assistant. Writing (`POST /advice`, `/rules`, `/compass`) needs the **AI may write advice and rules**
switch and the header `X-Wynninv: 1`.

## Development

Put the Wynntils Fabric jar into `libs/`, then:

```bash
gradle build                              # both loaders, with tests and checks
gradle build -Penabled_platforms=fabric   # Fabric only
```

Tests run on fixtures recorded from a real session (`gradle test -PupdateFixtures` to approve intended
changes). `install-when-closed.sh` copies the built jar into your instance once Minecraft is closed.

Commits and PR titles follow [Conventional Commits](https://www.conventionalcommits.org/); PRs are
squash-merged and the title sets the next version. Releases run from Actions › Release (tick
`dry_run` to preview the version and notes).

## License

Copyright (c) 2026 anilken. [GNU LGPL v3.0](LICENSE) (with the [GPL v3.0](COPYING) it builds on). Not affiliated with Wynncraft, Wynntils, Wynnventory or WynnBuilder.

- [Noto Sans](https://github.com/notofonts/latin-greek-cyrillic), SIL OFL 1.1: `assets/wynnlabs/font/OFL.txt`.
- [Tabler Icons](https://tabler.io/icons), MIT: `assets/wynnlabs/textures/gui/sprites/icon/NOTICE.txt`.
- `assets/wynnlabs/trees/`: ability tree layout from `api.wynncraft.com/v3/ability/map/<class>`.
- `assets/wynnlabs/quests/` and `assets/wynnlabs/discoveries/`: adapted from the
  [Wynncraft Wiki](https://wynncraft.wiki.gg) under
  [CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/); every entry links its page
  (see each folder's `NOTICE.txt`). Not covered by the LGPL, not for commercial use.
