#!/usr/bin/env node
// 씨앗포 시안 HTML 생성 — node docs/design/artillery/src/build.mjs
// shell.html + engine.js + theme-{a,b,c}.js → ../option-{a,b,c}.html (외부 의존 없는 단독 파일).
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const dir = dirname(fileURLToPath(import.meta.url));
const read = (f) => readFileSync(resolve(dir, f), 'utf8');
const shell = read('shell.html');
const engine = read('engine.js');
for (const id of ['a', 'b', 'c']) {
  const theme = read(`theme-${id}.js`);
  const meta = (key) => (theme.match(new RegExp(`^// @${key} (.*)$`, 'm')) ?? [])[1] ?? '';
  const css = (theme.match(/\/\*CSS([\s\S]*?)CSS\*\//) ?? [])[1] ?? '';
  const rep = {
    LETTER: id.toUpperCase(),
    ID: id,
    NAME: meta('name'),
    LEDE: meta('lede'),
    THEME_CSS: css.trim(),
    THEME_JS: theme.replace(/\/\*CSS[\s\S]*?CSS\*\//, ''),
    ENGINE_JS: engine,
  };
  for (const k of ['a', 'b', 'c']) rep[`CUR_${k.toUpperCase()}`] = k === id ? 'aria-current="page"' : '';
  // 한 번에 치환한다 — 끼워 넣은 코드 안의 글자는 다시 치환하지 않는다.
  const out = shell.replace(/\{\{([A-Z_]+)\}\}/g, (m, k) => (k in rep ? rep[k] : m));
  writeFileSync(resolve(dir, '..', `option-${id}.html`), out);
  console.log(`option-${id}.html ${(Buffer.byteLength(out) / 1024).toFixed(1)} KB`);
}
