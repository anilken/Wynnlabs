// Renders Wynn Labs' GUI sprites into src/main/resources/assets/wynnlabs/textures/gui/sprites/:
//  - rounded shapes (fill and 1px outline) for each corner radius, nine-sliced so one file draws
//    any size; white, tinted in game, so one shape serves every colour
//  - Tabler icons (MIT, https://tabler.io/icons), white, tinted in game
// Textures are drawn at DENSITY pixels per GUI pixel, so they stay sharp at GUI scale 2.
// Run: npm install && npm run render   (from tools/art)
import { Resvg } from '@resvg/resvg-js';
import { readFileSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { renderKit } from './kit.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const out = join(here, '../../src/main/resources/assets/wynnlabs/textures/gui/sprites');
const DENSITY = 2;

rmSync(out, { recursive: true, force: true });
mkdirSync(join(out, 'shape'), { recursive: true });
mkdirSync(join(out, 'icon'), { recursive: true });

function png(svg, file, size) {
  const r = new Resvg(svg, { fitTo: { mode: 'width', value: size } });
  writeFileSync(file, r.render().asPng());
}

// The centre is stretched, not tiled: these sprites are 2r + 2 px, so a tiled 2 px centre made
// tens of thousands of quads for one large panel every frame.
function meta(file, gui, border) {
  writeFileSync(file + '.mcmeta', JSON.stringify({
    gui: { scaling: { type: 'nine_slice', width: gui, height: gui, border, stretch_inner: true } },
  }, null, 2) + '\n');
}

// Rounded shapes: radius r (GUI px). The sprite is (2r + 2) GUI px square; its border is r.
for (const r of [2, 3, 4, 5, 6, 8]) {
  const gui = 2 * r + 2;
  const px = gui * DENSITY;
  const rad = r * DENSITY;
  const fill = `<svg xmlns="http://www.w3.org/2000/svg" width="${px}" height="${px}">`
    + `<rect width="${px}" height="${px}" rx="${rad}" fill="#fff"/></svg>`;
  const half = DENSITY / 2;
  const line = `<svg xmlns="http://www.w3.org/2000/svg" width="${px}" height="${px}">`
    + `<rect x="${half}" y="${half}" width="${px - DENSITY}" height="${px - DENSITY}" rx="${rad - half}"`
    + ` fill="none" stroke="#fff" stroke-width="${DENSITY}"/></svg>`;
  for (const [name, svg] of [[`r${r}_fill`, fill], [`r${r}_line`, line]]) {
    const f = join(out, 'shape', name + '.png');
    png(svg, f, px);
    meta(f, gui, Math.max(r, 1));
  }
}

// Icons: Tabler outline names, rendered white at 16 GUI px.
const ICONS = [
  'home', 'sun', 'map-2', 'building-bank', 'skull', 'settings', 'world', 'clock', 'clock-hour-4',
  'trending-up', 'x', 'gift', 'check', 'pick', 'coin', 'map-pin', 'diamond', 'bolt', 'arrow-up-right',
  'wand', 'plus', 'minus', 'chevron-right', 'chevron-left', 'chevron-down', 'compass', 'star', 'alert-triangle', 'ticket',
  'sword', 'shield', 'heart', 'flame', 'droplet', 'leaf', 'search', 'filter', 'list-check', 'target',
  'trophy', 'route', 'bookmark', 'refresh', 'info-circle', 'lock', 'user', 'package', 'shopping-cart',
  'arrow-up', 'arrow-down', 'player-play', 'eye', 'sparkles', 'tag', 'box', 'hierarchy', 'hammer',
  'copy', 'clipboard', 'pencil', 'trash', 'arrows-horizontal', 'arrow-left', 'device-floppy',
  'helmet', 'shirt', 'hanger', 'shoe', 'circle-dot', 'circles', 'trident', 'bow', 'crystal-ball', 'flask-2', 'script', 'meat',
];
const tabler = join(here, 'node_modules/@tabler/icons/icons/outline');
for (const name of ICONS) {
  let svg = readFileSync(join(tabler, name + '.svg'), 'utf8')
    .replace(/currentColor/g, '#fff')
    .replace(/width="24"/, 'width="64"').replace(/height="24"/, 'height="64"');
  png(svg, join(out, 'icon', name + '.png'), 16 * DENSITY);
}
// Filled Tabler icons, named "<name>-filled".
for (const name of ['star']) {
  const svg = readFileSync(join(here, 'node_modules/@tabler/icons/icons/filled', name + '.svg'), 'utf8')
    .replace(/currentColor/g, '#fff')
    .replace(/width="24"/, 'width="64"').replace(/height="24"/, 'height="64"');
  png(svg, join(out, 'icon', name + '-filled.png'), 16 * DENSITY);
}

// Ability tree nodes without the Wynncraft pack: a circle for abilities, a diamond for
// ultimates, each with a ring for the state; white, tinted in game.
const TREE = {
  'tree-circle': '<circle cx="32" cy="32" r="30" fill="#fff"/>',
  'tree-circle-ring': '<circle cx="32" cy="32" r="28.5" fill="none" stroke="#fff" stroke-width="6"/>',
  'tree-diamond': '<polygon points="32,2 62,32 32,62 2,32" fill="#fff"/>',
  'tree-diamond-ring': '<polygon points="32,5 59,32 32,59 5,32" fill="none" stroke="#fff" stroke-width="6" stroke-linejoin="round"/>',
};
for (const [name, body] of Object.entries(TREE)) {
  png(`<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64">${body}</svg>`,
    join(out, 'icon', name + '.png'), 16 * DENSITY);
}

writeFileSync(join(out, 'icon', 'NOTICE.txt'),
  'Icons from Tabler Icons (https://tabler.io/icons), MIT License, Copyright (c) 2020-2024 Paweł Kuna.\n');
// The J menu's wood-and-parchment kit (kit.mjs).
const kit = renderKit(out, DENSITY);

// Fonts: Noto Sans (SIL OFL 1.1) at each size, once per GUI scale. The game draws a TTF from a
// glyph texture without smoothing, so it is only sharp when oversample equals the GUI scale;
// UiFont picks the file for the scale in use. Size 9 with no shift fits the game's 9px line
// (the same setup WynnChaYuan's settings screen uses). Sizes in GUI px: micro 4.3 (the words under
// items), tiny 7, small 7.8,
// base 9, large 11.5, big 13.5. The game keeps a TTF's baseline where size 9 has it, so larger
// sizes grow upwards; SHIFT moves large and big down so their tops line up with the line's top.
const fontDir = join(here, '../../src/main/resources/assets/wynnlabs/font');
const fs = await import('node:fs');
for (const f of fs.readdirSync(fontDir)) {
  if (f.endsWith('.json') || f.endsWith('.ttf')) rmSync(join(fontDir, f));
}
const noto = join(here, 'node_modules/@expo-google-fonts/noto-sans');
fs.copyFileSync(join(noto, 'NotoSans_500Medium.ttf'), join(fontDir, 'noto_medium.ttf'));
fs.copyFileSync(join(noto, 'NotoSans_700Bold.ttf'), join(fontDir, 'noto_bold.ttf'));
const SIZES = { micro: 4.3, tiny: 7, small: 7.8, '': 9, large: 11.5, big: 13.5 };
const SHIFT = { large: 2, big: 3 };
for (const [name, size] of Object.entries(SIZES)) {
  for (const bold of [false, true]) {
    for (let scale = 1; scale <= 6; scale++) {
      const id = ['ui', name, bold ? 'bold' : ''].filter(Boolean).join('_') + '_s' + scale;
      writeFileSync(join(fontDir, id + '.json'), JSON.stringify({
        providers: [
          { type: 'ttf', file: 'wynnlabs:' + (bold ? 'noto_bold.ttf' : 'noto_medium.ttf'),
            size, oversample: scale, shift: [0, SHIFT[name] ?? 0] },
          { type: 'reference', id: 'minecraft:default' },
        ],
      }, null, 2) + '\n');
    }
  }
}

console.log(`rendered ${6 * 2} shapes, ${ICONS.length} icons and ${kit.length} kit sprites into ${out}`);
