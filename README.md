# Wynn Labs

An in-game assistant for [Wynncraft](https://wynncraft.com): a client-side Fabric mod for
Minecraft 1.21.11. It reads your own character, bag and bank, and helps you decide what to do.

- **Items:** wear, sell, identify or keep, in the tooltip with the reason.
- **Upgrades and ability tree:** the best gear for your level and build, and the tree with the plan's order.
- **Quests and secret discoveries:** step-by-step guides in English or Turkish, with waypoints.
- **Endgame:** raid and lootrun records, mythic dry streaks, your aspects, and this week's raid and lootrun pools.
- **Crafting, bank and prices:** recipe odds, a searchable bank index, Trade Market prices.
- **Coach:** death warning, mana and DPS meters, session report, where to level next.

**Read-only.** Wynn Labs never clicks, moves, casts or sends anything to the server for you.

## Install

1. [Fabric Loader](https://fabricmc.net) for 1.21.11 with Fabric API.
2. [Wynntils](https://modrinth.com/mod/wynntils) 4.1.0 or newer (required).
3. Optional: [Wynnventory](https://modrinth.com/mod/wynnventory) for item prices.
4. Put `wynnlabs-fabric-<version>.jar` into `mods`.

Press **J** for the menu. The mod follows the game's language (English and Turkish).

## Settings and privacy

Everything is in the J menu's settings tab. The mod contacts only:

- `www.wynnventory.com`, with **Live prices** on: listings, and the weekly raid and lootrun pools at most once an hour.
- `wynncraft.wiki.gg`, with **Quest guides from the wiki** on: quest and discovery pages, cached for a week.

**Wynnventory key.** Paste your key in the settings tab, or build the mod where a key file exists
(`$WYNNLABS_WYNNVENTORY_KEY_FILE` or the instance's `config/wynnlabs/wynnventory-key.txt`) to embed
it in the jar, AES-256-GCM encrypted. Everything needed to decrypt ships in the same jar, so keep
such a jar to yourself. A key saved in settings wins.

Everything the mod remembers is under `config/wynnlabs/`. Delete it to start fresh.

## AI assistant and local API

Turn on the local API in the J menu's **AI assistant** tab. It serves your game state as JSON on
`127.0.0.1:47591` (`GET /` lists the endpoints). **Install for Claude Code** copies the skill
([`ai/skills/wynnlabs/SKILL.md`](ai/skills/wynnlabs/SKILL.md)); **Copy prompt** works with any other
assistant. Writing advice or rules (`POST /advice`, `POST /rules`) needs the **AI may write advice
and rules** switch and the header `X-Wynninv: 1`.

## Development

Put the Wynntils fabric jar into `libs/`, then:

```bash
gradle build
```

This also runs the tests (fixtures recorded from a real session, `gradle test -PupdateFixtures` to
approve intended changes) and the checks `checkLang`, `checkPersonal` and `checkWynntilsImports`.
`gradle checkWynntilsVersions` compiles against every jar in `libs/wynntils-matrix/`.
`install-when-closed.sh` copies the build into your mods folder once Minecraft is closed.

**Hot swap.** Start the game on [JetBrains Runtime 21](https://github.com/JetBrains/JetBrainsRuntime/releases)
with `-XX:+AllowEnhancedClassRedefinition -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005`,
then push a fresh build into the running game:

```bash
java --add-modules jdk.jdi tools/HotSwap.java fabric/build/libs/wynnlabs-fabric-<version>.jar <installed mod jar>
```

Changed methods and new or removed methods and fields load at once (screens show it when reopened;
F3+T reloads lang files and textures). New classes, mixins and keys or commands need a restart.

## License

Copyright (c) 2026 anilken. [GNU LGPL v3.0](LICENSE) (with the [GPL v3.0](COPYING) it builds on). Not affiliated with Wynncraft, Wynntils or Wynnventory.

- [Noto Sans](https://github.com/notofonts/latin-greek-cyrillic), SIL OFL 1.1: `assets/wynnlabs/font/OFL.txt`.
- [Tabler Icons](https://tabler.io/icons), MIT: `assets/wynnlabs/textures/gui/sprites/icon/NOTICE.txt`.
- `assets/wynnlabs/trees/`: ability tree layout from `api.wynncraft.com/v3/ability/map/<class>`.
- `assets/wynnlabs/quests/` and `assets/wynnlabs/discoveries/`: adapted from the
  [Wynncraft Wiki](https://wynncraft.wiki.gg) under
  [CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/); every entry links its page
  (see each folder's `NOTICE.txt`). Not covered by the LGPL, not for commercial use.
