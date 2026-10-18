# Quest guide translation glossary (en.json → tr.json)

`tr.json` is a list in the same order as `en.json`. Each entry is `{"id", "stages": [{"title", "task", "hints": [...]}]}`.
It has the same number of stages, and each stage has the same number of hints. Coordinates, places and pins come from
`en.json`; only the text is translated.

## Same words as the game in Turkish (WynnChaYuan)
The player runs the game with the WynnChaYuan Turkish translation (rules in `wynn-tr-tools/REHBER.md`). The guide uses the
same words, so a line reads the way the game shows it:
- **Dialogue and objectives:** a hint that is an NPC line, or a task that is the game's own objective, takes WynnChaYuan's
  Turkish for that line (`quest-dialogue.json`), matched on the English text. Only lines with no match are translated
  here.
- **Quest names** are translated with `quest-name.json` ("A Grave Mistake" → "Ölümcül Bir Hata"), in the text and in
  each entry's `"name"` (the title the mod shows in Turkish). Quests WynnChaYuan does not name (old and event quests)
  get a name in the same style here; made-up names ("???", "Hollow Sirene") have none and stay English.
- **NPC names and titles** follow `npc.json`: titles are translated (King of Ragni → Ragni Kralı, Guard Captain → Muhafız
  Kaptanı, Soldier → Asker), personal names stay (Worid, Ankou).
- **Terms:** Emerald → Zümrüt, Lv. → Sv., mob → yaratık, Quest → Görev, Dungeon → Zindan, Slaying Post → Av Noktası,
  Gathering Post → Toplama Noktası, The Cursed One → Lanetli, Corruption → Yozlaşma, Decay → Çürüme, Portal → Geçit,
  Health → Can, Guild → Lonca, Trade Market → Ticaret Pazarı. Vanilla mobs and blocks take Minecraft's Turkish names
  (Zombie → Zombi, Chest → Sandık, Lever → Şalter); Wynncraft races stay (Orc, Grook, Dernic).

## Keep exactly as in English
- **Names:** places and regions (Detlas, Almuj, Emerald Labyrinth, Frog Bog) and items ([12 Bone Meal], Liquified
  Emeralds) stay in English. Suffixes take an apostrophe and vowel harmony: Detlas'a, Ragni'ye.
- **Coordinates:** every coordinate keeps its exact form, `[-760, 79, -666]` or `-760, 79, -666`. Do not reorder,
  round or drop numbers.
- **Quoted text:** anything in double quotes (dialogue options, signs, passwords, "[Offer Auburn Absinthe]") stays as it
  is. Wrap it in Turkish: `"…" seçeneğini seç`.
- **Titles:** a stage title like "Stage 3" stays "Stage 3" (the mod shows it as "Aşama 3"). Other titles are translated.
- **Commands and keys:** `/compass`, `/class`, "Shift + right-click" become `Shift + sağ tık`. Command text stays the
  same.

## Phrasing (from the secret discovery translations)
| English | Türkçe |
|---|---|
| Talk to X at [x, y, z] | [x, y, z] noktasındaki X ile konuş |
| Go to / Head to X | X'e git (correct suffix) |
| Return to X | X'e dön |
| Kill / Defeat / Slay N X | N X öldür / X'i yen |
| Collect / Gather / Bring N X to Y | N X topla / N X'i Y'ye götür |
| Give X to Y | X'i Y'ye ver |
| Enter X | X'e gir |
| Find X | X'i bul |
| Follow X | X'i takip et |
| Use X on Y | X'i Y üzerinde kullan |
| Right-click / Left-click | sağ tıkla / sol tıkla |
| Choose the option "…" | "…" seçeneğini seç |
| Note: / Tip: | Not: / İpucu: |
| (optional) | (isteğe bağlı) |
| the quest ends / you complete the quest | görev tamamlanır |

## Style
- Use short imperative sentences in the second person singular ("git", "konuş"), the same as the discovery guides.
- Do not add explanations the English lacks, and do not drop sentences.
- Keep the English sentence order where it reads naturally. Split a long sentence when Turkish needs it, inside the
  same task or hint string.
- Write numbers as digits. Turkish casing applies (İ/ı).
