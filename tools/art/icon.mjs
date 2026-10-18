// Renders the mod icon (icon.svg, a shield with a flask) into assets/wynnlabs/icon.png, the
// picture mod lists show. Run: npm install && npm run icon   (from tools/art)
import { Resvg } from '@resvg/resvg-js';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const svg = readFileSync(join(here, 'icon.svg'), 'utf8');
const out = join(here, '../../src/main/resources/assets/wynnlabs/icon.png');
const r = new Resvg(svg, { fitTo: { mode: 'width', value: 128 } });
writeFileSync(out, r.render().asPng());
console.log('wrote ' + out);
