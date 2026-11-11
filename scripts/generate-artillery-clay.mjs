#!/usr/bin/env node
// D-252 — 씨앗포 "말랑 클레이" 스킨 시트 생성기.
//
// 그림 코드(`scripts/artillery-clay/art.js`, Canvas 2D)를 저장소에 이미 있는 Playwright 크로미움(client devDependency)에서 돌려
// 시트 한 장을 받고, PNG 는 Node 기본 모듈(zlib)로 직접 쓴다 — 새 의존성 없음.
//
// 출력:
//   client/public/artillery/skins/clay/atlas.png   — 시트 한 장
//   client/public/artillery/skins/clay/atlas.json  — 프레임 정의(형식: docs/artillery-skins.md)
//
// 실행: node scripts/generate-artillery-clay.mjs
//   (먼저 `npm --prefix client install`, 크로미움이 없으면 `npx --prefix client playwright install chromium`)

import { mkdir, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, '..');
const OUT_DIR = resolve(ROOT, 'client/public/artillery/skins/clay');
const { chromium } = createRequire(resolve(ROOT, 'client/package.json'))('@playwright/test');

const browser = await chromium.launch();
let built;
try {
  const page = await browser.newPage({ deviceScaleFactor: 1 });
  await page.setContent('<!doctype html><html><body></body></html>');
  await page.addScriptTag({ path: resolve(__dirname, 'artillery-clay/art.js') });
  built = await page.evaluate(() => window.CLAY_ART.build());
} finally {
  await browser.close();
}
const { width: W, height: H } = built;
const rgba = Buffer.from(built.rgba, 'base64');
// 완전히 투명한 픽셀의 색을 0 으로 — 압축이 잘 되고 결과가 고르다.
for (let i = 0; i < rgba.length; i += 4) if (rgba[i + 3] === 0) rgba[i] = rgba[i + 1] = rgba[i + 2] = 0;

// ─── PNG (줄마다 가장 잘 줄어드는 필터를 고른다) ──────────────────────
const CRC_TABLE = new Uint32Array(256).map((_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
const crc32 = (buf) => {
  let c = 0xffffffff;
  for (const b of buf) c = CRC_TABLE[(c ^ b) & 255] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
};
function chunk(type, data) {
  const out = Buffer.alloc(12 + data.length);
  out.writeUInt32BE(data.length, 0);
  out.write(type, 4, 'ascii');
  Buffer.from(data).copy(out, 8);
  out.writeUInt32BE(crc32(out.subarray(4, 8 + data.length)), 8 + data.length);
  return out;
}
function png(w, h, px) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; // 비트 깊이
  ihdr[9] = 6; // RGBA
  const stride = w * 4;
  const raw = Buffer.alloc((stride + 1) * h);
  const cand = [0, 1, 2, 3, 4].map(() => Buffer.alloc(stride));
  const paeth = (a, b, c) => {
    const p = a + b - c;
    const pa = Math.abs(p - a);
    const pb = Math.abs(p - b);
    const pc = Math.abs(p - c);
    return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
  };
  for (let j = 0; j < h; j++) {
    const row = j * stride;
    const sums = [0, 0, 0, 0, 0];
    for (let i = 0; i < stride; i++) {
      const x = px[row + i];
      const a = i >= 4 ? px[row + i - 4] : 0;
      const b = j > 0 ? px[row - stride + i] : 0;
      const c = i >= 4 && j > 0 ? px[row - stride + i - 4] : 0;
      const v = [x, (x - a) & 255, (x - b) & 255, (x - ((a + b) >> 1)) & 255, (x - paeth(a, b, c)) & 255];
      for (let f = 0; f < 5; f++) {
        cand[f][i] = v[f];
        sums[f] += v[f] < 128 ? v[f] : 256 - v[f];
      }
    }
    let best = 0;
    for (let f = 1; f < 5; f++) if (sums[f] < sums[best]) best = f;
    raw[j * (stride + 1)] = best;
    cand[best].copy(raw, j * (stride + 1) + 1);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

const atlas = {
  format: 'mirboard-artillery-skin',
  version: 1,
  image: 'atlas.png',
  pxPerCell: built.pxPerCell,
  labelLift: 21,
  recoilCells: 2,
  soil: { grass: '#74d44a', grassDark: '#46a63a', topsoil: '#b0683c', subsoil: '#884c2c' },
  anims: built.anims,
};

await mkdir(OUT_DIR, { recursive: true });
const file = png(W, H, rgba);
await writeFile(resolve(OUT_DIR, 'atlas.png'), file);
// 프레임 사각형·기준점은 한 줄로(사람이 읽기 쉽게).
const json = JSON.stringify(atlas, null, 2).replace(/\[\s+(-?[\d.]+),\s+(-?[\d.]+)(?:,\s+(-?[\d.]+),\s+(-?[\d.]+))?\s+\]/g, (_m, a, b, c, d) =>
  c === undefined ? `[${a}, ${b}]` : `[${a}, ${b}, ${c}, ${d}]`,
);
await writeFile(resolve(OUT_DIR, 'atlas.json'), `${json}\n`);
console.log(`atlas.png ${W}×${H} (${(file.length / 1024).toFixed(1)}KB), 조각 ${built.pieces}개, 애니메이션 ${Object.keys(atlas.anims).length}개 → ${OUT_DIR}`);
