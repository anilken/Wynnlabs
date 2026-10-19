// The J menu's wood-and-parchment kit (openspec change j-menu-restyle): frames, buttons, cards,
// slots, seals and ornaments, drawn here as SVG and rendered to GUI sprites. Our own drawings;
// nothing is taken from wynncraft.com or the resource pack.
//
// Kit sprites are drawn at 6 texels per GUI pixel. Every nine-slice keeps a 32 GUI px middle, so a large box needs only a few pieces even where
// the game tiles a slice's edges. Wood and gold are baked in colour (their bevel would not
// survive tinting); seals, pills and the bar rim are greyscale and tinted in game.
import { Resvg } from '@resvg/resvg-js';
import { writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

const MID = 32;

function svg(w, h, body, density) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w * density}" height="${h * density}" viewBox="0 0 ${w} ${h}">${body}</svg>`;
}

function write(dir, name, w, h, body, density, border) {
  const file = join(dir, name + '.png');
  const r = new Resvg(svg(w, h, body, density), { fitTo: { mode: 'width', value: w * density } });
  writeFileSync(file, r.render().asPng());
  if (border) {
    const b = typeof border === 'number' ? border : { left: border[0], top: border[1], right: border[2], bottom: border[3] };
    writeFileSync(file + '.mcmeta', JSON.stringify({
      gui: { scaling: { type: 'nine_slice', width: w, height: h, border: b, stretch_inner: true } },
    }, null, 2) + '\n');
  }
  return file;
}

/** A vertical gradient as an SVG defs block. */
function grad(id, stops) {
  return `<linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1">`
    + stops.map(([o, c, a]) => `<stop offset="${o}" stop-color="${c}"${a === undefined ? '' : ` stop-opacity="${a}"`}/>`).join('')
    + '</linearGradient>';
}

/** A bevelled button: rounded, 1px edge, light top line, dark bottom line. */
function button(w, h, [top, mid, bottom], edge, hi, lo, open = false) {
  const r = 2;
  const shape = open
    ? `M0,${h} V${r} Q0,0 ${r},0 H${w - r} Q${w},0 ${w},${r} V${h} Z`
    : null;
  const body = `<defs>${grad('g', [[0, top], [0.45, mid], [1, bottom]])}</defs>`
    + (open
      ? `<path d="${shape}" fill="${edge}"/>`
        + `<path d="M1,${h} V${r} Q1,1 ${r + 0.5},1 H${w - r - 0.5} Q${w - 1},1 ${w - 1},${r} V${h} Z" fill="url(#g)"/>`
        + `<rect x="2" y="1" width="${w - 4}" height="1" fill="${hi}"/>`
      : `<rect width="${w}" height="${h}" rx="${r}" fill="${edge}"/>`
        + `<rect x="1" y="1" width="${w - 2}" height="${h - 2}" rx="${r - 1}" fill="url(#g)"/>`
        + `<rect x="2" y="1" width="${w - 4}" height="1" fill="${hi}"/>`
        + `<rect x="2" y="${h - 2}" width="${w - 4}" height="1" fill="${lo}"/>`);
  return body;
}

/** A torn line along one edge of length len: small notches, the same every MID px. */
function tornPoints(len, depth) {
  const pts = [];
  const pattern = [0, 1, 0.4, 1.4, 0.2, 0.9, 0, 1.2];
  for (let x = 0; x <= len; x += 4) {
    pts.push([x, pattern[(x / 4) % pattern.length] * depth / 1.4]);
  }
  return pts;
}

export function renderKit(out, density) {
  const kit = join(out, 'kit');
  mkdirSync(kit, { recursive: true });
  // 6 texels per GUI pixel: sharp and smooth at GUI scale 2, 3 and 6 (2 looked blocky at scale 3)
  const D = Math.max(density, 6);
  const names = [];
  const add = (name, w, h, body, border, d = D) => { write(kit, name, w, h, body, d, border); names.push(name); };

  // window: dark outline, 4px wood, a light inner line, brass nails in the corners
  {
    const w = 44;
    const nail = (cx, cy) => `<circle cx="${cx}" cy="${cy}" r="2.4" fill="url(#brass)"/><circle cx="${cx - 0.6}" cy="${cy - 0.6}" r="0.7" fill="#ffe9a8"/>`;
    add('window', w, w,
      `<defs><radialGradient id="brass" cx="0.35" cy="0.35" r="0.7"><stop offset="0" stop-color="#ffe9a8"/><stop offset="0.45" stop-color="#c9973a"/><stop offset="1" stop-color="#6a4a14"/></radialGradient></defs>`
      + `<rect width="${w}" height="${w}" rx="5" fill="#120d09"/>`
      + `<rect x="1" y="1" width="${w - 2}" height="${w - 2}" rx="4" fill="#3b2a1d"/>`
      + `<rect x="5" y="5" width="${w - 10}" height="${w - 10}" fill="#8a6a50"/>`
      + `<rect x="6" y="6" width="${w - 12}" height="${w - 12}" fill="#4a3626"/>`
      + nail(3, 3) + nail(w - 3, 3) + nail(3, w - 3) + nail(w - 3, w - 3),
      6);
  }

  // parchment: torn rim (period MID), a darker band toward the rim, the inner rule at 4px
  {
    const w = 2 * 10 + MID;
    const top = tornPoints(w, 1.4);
    let d = `M0,${top[0][1]}`;
    for (const [x, y] of top) d += ` L${x},${y}`;
    for (const [y, x] of tornPoints(w, 1.4)) d += ` L${w - x},${y}`;
    for (const [x, y] of tornPoints(w, 1.4).reverse()) d += ` L${x},${w - y}`;
    for (const [y, x] of tornPoints(w, 1.4).reverse()) d += ` L${x},${y}`;
    d += ' Z';
    add('paper', w, w,
      `<defs><clipPath id="c"><path d="${d}"/></clipPath></defs>`
      + `<g clip-path="url(#c)">`
      + `<rect width="${w}" height="${w}" fill="#c4ad78"/>`
      + `<rect x="2" y="2" width="${w - 4}" height="${w - 4}" rx="2" fill="#ccb683"/>`
      + `<rect x="4" y="4" width="${w - 8}" height="${w - 8}" rx="2" fill="#d2be8c"/>`
      + `<rect x="6" y="6" width="${w - 12}" height="${w - 12}" rx="2" fill="#d8c595"/>`
      + `<rect x="4.5" y="4.5" width="${w - 9}" height="${w - 9}" rx="1.5" fill="none" stroke="#9a855c" stroke-width="1"/>`
      + `</g>`,
      10);
  }

  // corner ornaments: two nested curls and a dot, one file per corner
  {
    const orn = '<path d="M1.5,11.5 V4.5 a3,3 0 0 1 3,-3 H11.5" fill="none" stroke="#7a6446" stroke-width="1" stroke-linecap="round"/>'
      + '<path d="M3.5,9.5 V6 a2.5,2.5 0 0 1 2.5,-2.5 H9.5" fill="none" stroke="#7a6446" stroke-width="1" stroke-linecap="round"/>'
      + '<circle cx="6" cy="6" r="1" fill="#7a6446"/>';
    for (const [name, t] of [['corner_tl', ''], ['corner_tr', 'translate(13,0) scale(-1,1)'],
      ['corner_bl', 'translate(0,13) scale(1,-1)'], ['corner_br', 'translate(13,13) scale(-1,-1)']]) {
      add(name, 13, 13, `<g transform="${t}">${orn}</g>`);
    }
  }

  // paper grain: drawn once over the page and cropped to it
  add('grain', 256, 256,
    '<filter id="n" x="0" y="0" width="100%" height="100%"><feTurbulence type="fractalNoise" baseFrequency="0.45" numOctaves="2" seed="7" stitchTiles="stitch"/>'
    + '<feColorMatrix values="0 0 0 0 0.38  0 0 0 0 0.30  0 0 0 0 0.20  0 0 0 0.16 0"/></filter>'
    + '<rect width="256" height="256" filter="url(#n)"/>', null, 1);

  // the rail's planks, as in the mock-up: a warm left-to-right shade, a seam every 32px, fine
  // grain lines and faint cross lines. 100px wide (the rail), 104px tall so it repeats down the
  // rail without a seam (the cross lines run every 6.5px: 16 of them in 104).
  {
    const w = 100;
    const h = 104;
    let body = `<defs><linearGradient id="s" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#664c36"/><stop offset="1" stop-color="#573f2c"/></linearGradient></defs>`
      + `<rect width="${w}" height="${h}" fill="url(#s)"/>`;
    for (let x = 0; x < w; x += 4.5) body += `<rect x="${x}" y="0" width="1" height="${h}" fill="#ffffff" fill-opacity="0.028"/>`;
    for (let y = 0; y < h; y += 6.5) body += `<rect x="0" y="${y}" width="${w}" height="0.5" fill="#000000" fill-opacity="0.063"/>`;
    for (let x = 32; x < w; x += 33) {
      body += `<rect x="${x}" y="0" width="1" height="${h}" fill="#000000" fill-opacity="0.23"/>`
        + `<rect x="${x + 1}" y="0" width="0.5" height="${h}" fill="#ffffff" fill-opacity="0.063"/>`;
    }
    add('planks', w, h, body);
  }

  // buttons and chips
  const B = 2 * 3 + MID;
  add('wood', B, B, button(B, B, ['#7d5f47', '#6e5440', '#5f4734'], '#3b2a1d', '#ffffff3d', '#00000040'), 3);
  add('wood_hover', B, B, button(B, B, ['#8c6c52', '#7c5f49', '#6a503c'], '#3b2a1d', '#ffffff4d', '#00000040'), 3);
  add('gold', B, B, button(B, B, ['#fff0a0', '#ffe071', '#d9a93a'], '#8a6a1c', '#ffffffa0', '#a8781e'), 3);
  add('ledger_wood', B, B, button(B, B, ['#7d5f47', '#6e5440', '#644b37'], '#3b2a1d', '#ffffff3d', '', true), [3, 3, 3, 1]);
  add('ledger_gold', B, B, button(B, B, ['#fff0a0', '#ffe071', '#e8c050'], '#8a6a1c', '#ffffffa0', '', true), [3, 3, 3, 1]);
  // the archetype's purple: the primary action and the items you chose (Choose items, Suggest
  // build, Apply, the Plan pill), the same bevel as wood and gold with a purple ramp
  add('purple', B, B, button(B, B, ['#9a7cc8', '#7a58ae', '#5a3a8a'], '#2f1c4e', '#ffffff50', '#00000040'), 3);
  add('purple_hover', B, B, button(B, B, ['#ab8ed6', '#8a68bd', '#694799'], '#2f1c4e', '#ffffff60', '#00000040'), 3);
  add('ledger_purple', B, B, button(B, B, ['#9a7cc8', '#7a58ae', '#6a4a9a'], '#2f1c4e', '#ffffff50', '', true), [3, 3, 3, 1]);

  // the active tab's tip: a gold arrow that reaches into the page
  add('tip', 6, 20,
    `<defs>${grad('g', [[0, '#fff0a0'], [0.45, '#ffe071'], [1, '#d9a93a']])}</defs>`
    + '<path d="M0,0 L6,10 L0,20 Z" fill="#8a6a1c"/><path d="M0,1.6 L4.9,10 L0,18.4 Z" fill="url(#g)"/>');

  // parchment card and stat plaque
  add('card', 2 * 4 + MID, 2 * 4 + MID,
    `<defs>${grad('g', [[0, '#eadcb4'], [1, '#d4c193']])}</defs>`
    + `<rect width="40" height="40" rx="3.5" fill="#624e3c"/>`
    + `<rect x="1.5" y="1.5" width="37" height="37" rx="2.2" fill="url(#g)"/>`
    + `<rect x="2.5" y="2.5" width="35" height="35" rx="1.6" fill="none" stroke="#f4ead0" stroke-opacity="0.65" stroke-width="1"/>`, 4);
  // the same card lit: a gold edge and a lighter face (hover, the selected card)
  add('card_lit', 2 * 4 + MID, 2 * 4 + MID,
    `<defs>${grad('g', [[0, '#f6ecca'], [1, '#e2d0a2']])}</defs>`
    + `<rect width="40" height="40" rx="3.5" fill="#c08a20"/>`
    + `<rect x="1.5" y="1.5" width="37" height="37" rx="2.2" fill="url(#g)"/>`
    + `<rect x="2.5" y="2.5" width="35" height="35" rx="1.6" fill="none" stroke="#fff8e0" stroke-opacity="0.8" stroke-width="1"/>`, 4);
  add('plaque', B, B,
    `<defs>${grad('g', [[0, '#e9dab2'], [1, '#cdb985']])}</defs>`
    + `<rect width="${B}" height="${B}" rx="2.5" fill="#624e3c"/>`
    + `<rect x="1" y="1" width="${B - 2}" height="${B - 2}" rx="1.8" fill="url(#g)"/>`
    + `<rect x="2" y="1" width="${B - 4}" height="1" fill="#fffaec" fill-opacity="0.7"/>`, 3);

  // an inset item slot
  add('slot', B, B,
    '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#8a7656"/><stop offset="1" stop-color="#a8936c"/></linearGradient></defs>'
    + `<rect width="${B}" height="${B}" rx="2" fill="#d4c49a"/>`
    + `<path d="M0,${B - 2} V2 Q0,0 2,0 H${B - 2} V1.5 H1.5 V${B - 2} Z" fill="#5a4a34"/>`
    + `<rect x="1.5" y="1.5" width="${B - 3}" height="${B - 3}" rx="1" fill="url(#g)"/>`, 3);

  // greyscale, tinted in game: the gain seal / pills, an outlined pill, the bar's rim
  add('seal', 2 * 6 + MID, 12,
    `<defs>${grad('g', [[0, '#e6e6e6'], [1, '#b4b4b4']])}</defs>`
    + '<rect width="44" height="12" rx="6" fill="#484848"/>'
    + '<rect x="1" y="1" width="42" height="10" rx="5" fill="url(#g)"/>'
    + '<rect x="5" y="1.5" width="34" height="0.8" fill="#ffffff" fill-opacity="0.55"/>', [6, 3, 6, 3]);
  add('pill_line', 2 * 6 + MID, 12,
    '<rect x="0.5" y="0.5" width="43" height="11" rx="5.5" fill="none" stroke="#ffffff" stroke-width="1"/>', [6, 3, 6, 3]);
  add('bar', 2 * 3 + MID, 8,
    '<rect width="38" height="8" rx="3" fill="#2e2e2e"/><rect x="1" y="1" width="36" height="6" rx="2" fill="#465b50"/>', [3, 2, 3, 2]);
  add('bar_rim', 2 * 4 + MID, 10,
    '<rect x="0.5" y="0.5" width="39" height="9" rx="4.5" fill="none" stroke="#c9973a" stroke-opacity="0.55" stroke-width="1"/>', [4, 3, 4, 3]);

  // switches
  add('switch_on', 19, 11,
    `<defs>${grad('g', [[0, '#4f8a5a'], [1, '#3f7a4a']])}</defs>`
    + '<rect width="19" height="11" rx="5.5" fill="#1e3a24"/><rect x="1" y="1" width="17" height="9" rx="4.5" fill="url(#g)"/>'
    + '<circle cx="13.5" cy="5.5" r="3.6" fill="#f3e6c4"/>');
  add('switch_off', 19, 11,
    '<rect width="19" height="11" rx="5.5" fill="#3b2a1d"/><rect x="1" y="1" width="17" height="9" rx="4.5" fill="#5f4734"/>'
    + '<circle cx="5.5" cy="5.5" r="3.6" fill="#c4b184"/>');

  // the close seal
  for (const [name, a, b] of [['close', '#c0504a', '#8a2a22'], ['close_hover', '#e0605a', '#a8342a']]) {
    add(name, 14, 14,
      `<defs><radialGradient id="r" cx="0.4" cy="0.35" r="0.7"><stop offset="0" stop-color="${a}"/><stop offset="0.6" stop-color="${b}"/><stop offset="1" stop-color="#5a1812"/></radialGradient></defs>`
      + '<circle cx="7" cy="7" r="7" fill="#3b1a12"/><circle cx="7" cy="7" r="6" fill="url(#r)"/>'
      + '<path d="M4.6,4.6 L9.4,9.4 M9.4,4.6 L4.6,9.4" stroke="#ffe9d0" stroke-width="1.3" stroke-linecap="round"/>');
  }

  // the class medal: a brass hexagon with a paper face
  add('medal', 23, 23,
    '<defs><radialGradient id="p" cx="0.5" cy="0.4" r="0.6"><stop offset="0" stop-color="#f3e4b8"/><stop offset="0.7" stop-color="#cdb47e"/><stop offset="1" stop-color="#a88c58"/></radialGradient></defs>'
    + '<polygon points="5.75,0.7 17.25,0.7 23,11.5 17.25,22.3 5.75,22.3 0,11.5" fill="#c9973a"/>'
    + '<polygon points="6.6,2.2 16.4,2.2 21.3,11.5 16.4,20.8 6.6,20.8 1.7,11.5" fill="url(#p)"/>');

  // the section mark
  add('diamond', 5, 5, '<polygon points="2.5,0 5,2.5 2.5,5 0,2.5" fill="#c9973a"/>');

  // the build gauge (58x29): a half ring, outer radius 29, 5 thick, white to tint; at four
  // times the density so its curve stays smooth at any GUI scale. The fill's outer two
  // pixels are brighter than its inner three, a bevel once it is tinted gold.
  {
    const ring = (ro, ri, colour) => `<path d="M0,29 A29,29 0 0 1 58,29 L${29 + ri},29 A${ri},${ri} 0 0 0 ${29 - ri},29 Z" fill="${colour}"/>`
      .replace('A29,29', `A${ro},${ro}`).replace('M0,29', `M${29 - ro},29`).replace('58,29', `${29 + ro},29`);
    add('gauge_track', 58, 29, ring(29, 24, '#ffffff'), null, 4);
    add('gauge_fill', 58, 29, ring(29, 24, '#b8b8b8') + ring(29, 27, '#ffffff'), null, 4);
  }

  return names;
}
