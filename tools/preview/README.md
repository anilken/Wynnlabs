# J menu preview

Draws the J menu without the game: a dev Minecraft client (no account, no server, no
Wynntils) loads the recorded Shaman from `fabric/src/test/resources/fixtures/shaman-44`, opens the
menu in a throwaway world and saves one screenshot per tab, then closes.

    gradle runClientGameTest

The pictures land in `build/preview/`: `jmenu-now.png`, then `jmenu-step1.png` … one per
milestone, `jmenu-tab-<n>.png` for every rail tab and `jmenu-tree-end.png` for the ability
tree scrolled to its last page. A run takes about half a minute and opens a window while it works.

- Another fixture: `-Dwynnlabs.previewFixture=synthetic` in the run's VM options
  (`fabric/build.gradle`, `loom.runs` → `clientGameTest`).
- English: `-Dwynnlabs.previewLang=en`.
- Item icons are plain vanilla items: Wynntils' own stacks need Wynntils, which the dev
  client does not load. Layout, text and numbers are the real ones.

The test is `fabric/src/gametest/java/io/github/anilken/wynnlabs/ui/JMenuPreview.java`.
