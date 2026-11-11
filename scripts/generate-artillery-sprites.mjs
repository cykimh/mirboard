#!/usr/bin/env node
// D-227 — 씨앗포 임시 스프라이트 시트 생성기 ("doodle" 스킨).
//
// 진짜 그림이 오기 전까지 쓰는 자리표시 그림이다. 전부 이 파일의 도형(원·타원·다각형·굵은 선)을 칸 단위로 찍어 만든
// 새 그림이고(화분 포대·씨앗·흙 폭발 — 다른 게임의 그림을 본뜨지 않았다), 외부 라이브러리 없이 Node 기본 모듈만 쓴다.
//
// 출력:
//   client/public/artillery/skins/doodle/atlas.png   — 시트 한 장
//   client/public/artillery/skins/doodle/atlas.json  — 프레임 정의(형식: docs/artillery-skins.md)
//
// 실행: node scripts/generate-artillery-sprites.mjs   (결과는 결정적 — 같은 코드면 같은 파일)

import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = resolve(__dirname, '..', 'client/public/artillery/skins/doodle');

/** 시트 픽셀 / 전장 칸. 휴대폰(칸 ≈ 0.5px × 보정 1.6)부터 데스크톱 고해상도(칸 ≈ 4 장치 픽셀)까지 무난한 값. */
const PPC = 4;
const SHEET_W = 512;

// ─── 색 ───────────────────────────────────────────────────────────────
const hex = (h, a = 1) => {
  const n = parseInt(h.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255, a];
};
const WHITE = [255, 255, 255, 1];
const OUTLINE = [44, 32, 22, 0.9];
const C = {
  stem: hex('#3d8b3d'),
  stemLight: hex('#6fbf5a'),
  sprout: hex('#58b05a'),
  sproutDark: hex('#3f8a43'),
  acornLeaf: hex('#7aa83f'),
  acornLeafDark: hex('#5a8a2c'),
  acorn: hex('#a8703a'),
  acornCap: hex('#6b4423'),
  dandLeaf: hex('#6cbf4f'),
  fluff: [255, 255, 255, 0.95],
  wilt: hex('#9c8a4c'),
  wiltDark: hex('#7a6a36'),
  eye: hex('#2a1f17'),
  shade: [0, 0, 0, 0.16],
  rimShade: [0, 0, 0, 0.24],
  shine: [255, 255, 255, 0.28],
  flash: [255, 255, 255, 0.7],
};

// ─── 도형(칸 단위, y 는 아래로) ────────────────────────────────────────
const circle = (cx, cy, r) => (x, y) => (x - cx) ** 2 + (y - cy) ** 2 <= r * r;
const ellipse = (cx, cy, rx, ry, rot = 0) => {
  const c = Math.cos(rot);
  const s = Math.sin(rot);
  return (x, y) => {
    const dx = x - cx;
    const dy = y - cy;
    const u = dx * c + dy * s;
    const v = -dx * s + dy * c;
    return (u / rx) ** 2 + (v / ry) ** 2 <= 1;
  };
};
const poly = (pts) => (x, y) => {
  let inside = false;
  for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) {
    const [xi, yi] = pts[i];
    const [xj, yj] = pts[j];
    if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
};
const segment = (x0, y0, x1, y1, w) => (x, y) => {
  const dx = x1 - x0;
  const dy = y1 - y0;
  const t = Math.max(0, Math.min(1, ((x - x0) * dx + (y - y0) * dy) / (dx * dx + dy * dy)));
  return (x - x0 - t * dx) ** 2 + (y - y0 - t * dy) ** 2 <= (w / 2) ** 2;
};
const and = (a, b) => (x, y) => a(x, y) && b(x, y);
const below = (yy) => (_x, y) => y <= yy;

// ─── 래스터 ───────────────────────────────────────────────────────────
/**
 * 프레임 하나를 그린다. layers = [{ shape, color }] (뒤가 위). anchor = 프레임 안 기준점(칸). xf = 기준점 둘레 변환
 * { dx, dy, sx, sy, rot, px, py } (회전·늘이기는 (px, py) 둘레 — 기준점 좌표계). outline 이면 그림 둘레에 테두리 1픽셀.
 */
function render(wCells, hCells, anchor, layers, xf = {}, { outline = true, outlineFrom = null } = {}) {
  const w = Math.round(wCells * PPC);
  const h = Math.round(hCells * PPC);
  const px = new Float32Array(w * h * 4);
  const { dx = 0, dy = 0, sx = 1, sy = 1, rot = 0 } = xf;
  const pivotX = xf.px ?? 0;
  const pivotY = xf.py ?? 0;
  const c = Math.cos(-rot);
  const s = Math.sin(-rot);
  for (let j = 0; j < h; j++) {
    for (let i = 0; i < w; i++) {
      // 화면 칸 → 기준점 좌표 → 역변환
      let x = (i + 0.5) / PPC - anchor[0] - dx - pivotX;
      let y = (j + 0.5) / PPC - anchor[1] - dy - pivotY;
      const rx = x * c - y * s;
      const ry = x * s + y * c;
      x = rx / sx + pivotX;
      y = ry / sy + pivotY;
      let r = 0;
      let g = 0;
      let b = 0;
      let a = 0;
      for (const L of layers) {
        if (!L.shape(x, y)) continue;
        const [lr, lg, lb, la] = L.color;
        const na = la + a * (1 - la);
        if (na > 0) {
          r = (lr * la + r * a * (1 - la)) / na;
          g = (lg * la + g * a * (1 - la)) / na;
          b = (lb * la + b * a * (1 - la)) / na;
        }
        a = na;
      }
      const k = (j * w + i) * 4;
      px[k] = r;
      px[k + 1] = g;
      px[k + 2] = b;
      px[k + 3] = a;
    }
  }
  if (outline) {
    const solid = outlineFrom ?? px;
    const copy = Float32Array.from(px);
    const on = (i, j) => i >= 0 && j >= 0 && i < w && j < h && solid[(j * w + i) * 4 + 3] > 0.5;
    for (let j = 0; j < h; j++) {
      for (let i = 0; i < w; i++) {
        if (on(i, j)) continue;
        if (on(i - 1, j) || on(i + 1, j) || on(i, j - 1) || on(i, j + 1)) {
          const k = (j * w + i) * 4;
          copy[k] = OUTLINE[0];
          copy[k + 1] = OUTLINE[1];
          copy[k + 2] = OUTLINE[2];
          copy[k + 3] = OUTLINE[3];
        }
      }
    }
    return { w, h, px: copy };
  }
  return { w, h, px };
}

/** 두 그림의 알파 합(테두리 기준용). */
function union(a, b) {
  const out = Float32Array.from(a.px);
  for (let k = 3; k < out.length; k += 4) out[k] = Math.max(a.px[k], b.px[k]);
  return out;
}

// ─── 포대 ─────────────────────────────────────────────────────────────
// 기준점 = 발(아래 가운데). 화분은 아래가 좁은 사다리꼴 + 테두리 띠, 오른쪽을 본다.
const POT = poly([
  [-3.7, 0],
  [3.7, 0],
  [4.9, -5],
  [-4.9, -5],
]);
const RIM = poly([
  [-5.5, -6.4],
  [5.5, -6.4],
  [5.5, -4.6],
  [-5.5, -4.6],
]);
const POT_ALL = (x, y) => POT(x, y) || RIM(x, y);

function leaves(kind, lift, wilted) {
  const dy = -lift;
  if (wilted) {
    // 시든 잎 — 축 늘어져 화분 테두리 옆으로
    return [
      { shape: ellipse(-4.6, -5.6 + dy, 2.4, 0.9, 0.9), color: C.wilt },
      { shape: ellipse(4.4, -5.4 + dy, 2.2, 0.8, -0.9), color: C.wiltDark },
    ];
  }
  if (kind === 'ACORN') {
    return [
      { shape: ellipse(-2.9, -7 + dy, 3.2, 1.6, -0.4), color: C.acornLeaf },
      { shape: segment(-5.4, -6 + dy, -0.6, -7.8 + dy, 0.35), color: C.acornLeafDark },
      { shape: circle(2.8, -7.3 + dy, 1.1), color: C.acorn },
      { shape: and(circle(2.8, -7.6 + dy, 1.2), below(-7.6 + dy)), color: C.acornCap },
    ];
  }
  if (kind === 'DANDELION') {
    return [
      { shape: segment(0, -6, -4, -8.6 + dy, 0.9), color: C.dandLeaf },
      { shape: segment(0, -6, 0, -9.8 + dy, 0.9), color: C.dandLeaf },
      { shape: segment(0, -6, 4, -8.6 + dy, 0.9), color: C.dandLeaf },
      { shape: circle(-2.2, -7.4 + dy, 0.55), color: C.dandLeaf },
      { shape: circle(2.2, -7.4 + dy, 0.55), color: C.dandLeaf },
    ];
  }
  return [
    { shape: ellipse(-2.7, -7.1 + dy, 2.5, 1.15, 0.5), color: C.sprout },
    { shape: ellipse(2.7, -7.1 + dy, 2.5, 1.15, -0.5), color: C.sprout },
    { shape: segment(-4.4, -6.2 + dy, -1.1, -7.9 + dy, 0.3), color: C.sproutDark },
    { shape: segment(4.4, -6.2 + dy, 1.1, -7.9 + dy, 0.3), color: C.sproutDark },
  ];
}

/** 얼굴 — 오른쪽을 보니 가운데에서 살짝 오른쪽. */
function face(mood) {
  const ex = [-0.9, 2.1];
  const ey = -2.6;
  if (mood === 'x') {
    return ex.flatMap((x) => [
      { shape: segment(x - 0.6, ey - 0.6, x + 0.6, ey + 0.6, 0.35), color: C.eye },
      { shape: segment(x - 0.6, ey + 0.6, x + 0.6, ey - 0.6, 0.35), color: C.eye },
    ]);
  }
  if (mood === 'focus') {
    return ex.map((x) => ({ shape: ellipse(x, ey, 0.75, 0.3), color: C.eye }));
  }
  return [
    ...ex.map((x) => ({ shape: circle(x, ey, 0.55), color: C.eye })),
    { shape: and(circle(0.6, -1.6, 0.8), (_x, y) => y >= -1.3), color: C.eye },
  ];
}

/** 화분 위 음영·광택(좌석 색 위에 겹친다 — 화분 몸 자체는 투명, 색은 tint 프레임이 깐다). */
const POT_OVERLAY = [
  { shape: and(POT, (x) => x > 2.2), color: C.shade },
  { shape: and(POT, segment(-3.2, -0.8, -3.9, -4.2, 0.7)), color: C.shine },
  { shape: RIM, color: C.rimShade },
  { shape: and(RIM, (_x, y) => y < -6), color: C.shine },
];

const BODY_W = 22;
const BODY_H = 14;
const BODY_ANCHOR = [11, 12];

function unitFrame(kind, { lift = 0, mood = 'calm', xf = {}, flash = false, wilted = false }) {
  const leaf = leaves(kind, lift, wilted);
  const body = [...leaf, ...POT_OVERLAY, ...face(mood)];
  if (flash) body.push({ shape: (x, y) => POT_ALL(x, y) || leaf.some((l) => l.shape(x, y)), color: C.flash });
  const tint = render(BODY_W, BODY_H, BODY_ANCHOR, [{ shape: POT_ALL, color: WHITE }], xf, { outline: false });
  const plain = render(BODY_W, BODY_H, BODY_ANCHOR, body, xf, { outline: false });
  const art = render(BODY_W, BODY_H, BODY_ANCHOR, body, xf, { outlineFrom: union(plain, tint) });
  return { art, tint };
}

const DOWN = { rot: (84 * Math.PI) / 180, px: 3.7, py: 0, dy: -0.6 };
const UNIT_POSES = {
  idle: { frameMs: 520, loop: true, frames: [{}, { lift: 0.5 }] },
  aim: { frameMs: 260, loop: true, frames: [{ lift: 0.9, mood: 'focus' }, { lift: 0.6, mood: 'focus' }] },
  recoil: {
    frameMs: 60,
    loop: false,
    frames: [
      { mood: 'focus', xf: { sx: 1.12, sy: 0.82 } },
      { mood: 'focus', xf: { sx: 1.05, sy: 0.92 } },
      { mood: 'focus', xf: { sx: 0.98, sy: 1.03 } },
    ],
  },
  hit: { frameMs: 90, loop: true, frames: [{ mood: 'x', flash: true, xf: { dx: -0.8 } }, { mood: 'x', xf: { dx: 0.5 } }] },
  down: { frameMs: 0, loop: false, barrel: false, frames: [{ mood: 'x', wilted: true, xf: DOWN }] },
};

// ─── 포신(오른쪽을 가리킨다, 기준점 = 줄기 밑동 = 회전축) ───────────────
const BARREL_W = 14.5;
const BARREL_H = 7;
const BARREL_ANCHOR = [1.5, 3.5];

function barrelFrame(kind) {
  const stem = [
    { shape: segment(0, 0, 10, 0, 2.2), color: C.stem },
    { shape: segment(0.6, -0.45, 9.4, -0.45, 0.45), color: C.stemLight },
    { shape: ellipse(5, -1.6, 1.5, 0.7, -0.5), color: C.sprout },
  ];
  let art = stem;
  let tintLayers = null;
  if (kind === 'ACORN') {
    art = [...stem, { shape: circle(10, 0, 2.2), color: C.acorn }, { shape: and(circle(10, -0.7, 2.4), below(-0.7)), color: C.acornCap }];
  } else if (kind === 'DANDELION') {
    const spikes = [];
    for (let i = 0; i < 8; i++) {
      const a = (i * Math.PI) / 4;
      spikes.push({ shape: segment(10, 0, 10 + Math.cos(a) * 2.9, Math.sin(a) * 2.9, 0.35), color: C.fluff });
    }
    art = [...stem, { shape: circle(10, 0, 2.2), color: C.fluff }, ...spikes];
    tintLayers = [{ shape: circle(10, 0, 0.95), color: WHITE }];
  } else {
    // 새싹포 — 꽃봉오리(좌석 색) 위 광택만 그리고 봉오리 색은 tint 가 깐다
    art = [...stem, { shape: circle(9.4, -0.7, 0.5), color: C.shine }];
    tintLayers = [{ shape: circle(10, 0, 1.9), color: WHITE }];
  }
  const tint = tintLayers ? render(BARREL_W, BARREL_H, BARREL_ANCHOR, tintLayers, {}, { outline: false }) : null;
  const plain = render(BARREL_W, BARREL_H, BARREL_ANCHOR, art, {}, { outline: false });
  const outlined = render(BARREL_W, BARREL_H, BARREL_ANCHOR, art, {}, { outlineFrom: tint ? union(plain, tint) : null });
  return { art: outlined, tint };
}

// ─── 씨앗 ─────────────────────────────────────────────────────────────
const SEED_W = 9;
const SEED_ANCHOR = [4.5, 4.5];
const SEEDS = {
  PEA: [
    { shape: ellipse(0, 0, 2.6, 2), color: hex('#5a3a1e') },
    { shape: circle(-0.8, -0.7, 0.9), color: hex('#c99a5b') },
  ],
  PUMPKIN: [
    { shape: ellipse(0, 0, 3.2, 2.6), color: hex('#e9852a') },
    { shape: ellipse(0, 0, 1.2, 2.5), color: hex('#f3a24a') },
    { shape: segment(0, -2.4, 0.4, -3.7, 0.9), color: hex('#4f7a2a') },
  ],
  VINE: [
    { shape: circle(0, 0, 2.3), color: hex('#4f9a3a') },
    { shape: and(circle(1.6, -1.6, 1.6), (x, y) => (x - 1.6) ** 2 + (y + 1.6) ** 2 > 0.8), color: hex('#2f6b22') },
  ],
  CHESTNUT: [
    { shape: circle(0, 0, 3.4), color: hex('#7a4a24') },
    { shape: and(circle(0, 0, 3.4), (_x, y) => y > 1.4), color: hex('#d9b48a') },
    { shape: circle(-1.2, -1.2, 0.8), color: C.shine },
  ],
  SPORE: [
    { shape: circle(0, 0, 1.9), color: [255, 255, 255, 0.95] },
    { shape: circle(0, 1.2, 0.7), color: hex('#8a6a3a') },
  ],
};

// ─── 폭발·둔덕(진행도 6단계, 원 반경 8칸 = 32px 를 서버 구멍 반경에 맞춰 늘린다) ──
const BOOM_W = 24;
const BOOM_ANCHOR = [12, 12];
const BOOM_R = 8;
const BOOM_FRAMES = 6;

function boomFrame(k, fill) {
  const t = (k + 0.5) / BOOM_FRAMES;
  const r = BOOM_R * (0.45 + 0.9 * t);
  const fade = Math.max(0, 1 - t);
  const outer = fill ? hex('#9bd77a', fade) : hex('#ffd166', fade);
  const inner = fill ? hex('#5f9e45', fade) : hex('#ff8c42', fade);
  const layers = [{ shape: circle(0, 0, r), color: outer }, { shape: circle(0, 0, r * 0.6), color: inner }];
  if (fill) {
    // 둔덕 — 흙 반구가 솟고 새싹이 돋는다
    const soil = hex('#96663e', Math.min(1, 0.4 + t));
    layers.push({ shape: and(circle(0, r * 0.35, r * 0.75), (_x, y) => y <= r * 0.35), color: soil });
    for (let i = -1; i <= 1; i++) layers.push({ shape: segment(i * r * 0.35, -r * 0.3, i * r * 0.4, -r * 0.55, 0.6), color: hex('#58b05a', fade + 0.2) });
  } else {
    if (k < 2) layers.push({ shape: circle(0, 0, r * 0.3), color: [255, 250, 230, 0.9] });
    if (k >= 1 && k <= 3) {
      for (let i = 0; i < 8; i++) {
        const a = (i * Math.PI) / 4 + k * 0.3;
        layers.push({ shape: circle(Math.cos(a) * r * 1.15, Math.sin(a) * r * 1.15, 0.7), color: hex('#ffb347', fade) });
      }
    }
    if (k >= 3) layers.push({ shape: and(circle(0, 0, r * 1.05), (x, y) => x * x + y * y > (r * 0.85) ** 2), color: [120, 110, 100, 0.35 * fade] });
  }
  return render(BOOM_W, BOOM_W, BOOM_ANCHOR, layers, {}, { outline: false });
}

// ─── 시트 묶기 ─────────────────────────────────────────────────────────
const pieces = []; // { key, img }
const put = (key, img) => {
  pieces.push({ key, img });
  return key;
};

const anims = {};
const cellPx = (p) => [Math.round(p[0] * PPC), Math.round(p[1] * PPC)];
for (const kind of ['SPROUT', 'ACORN', 'DANDELION']) {
  for (const [pose, def] of Object.entries(UNIT_POSES)) {
    const frames = [];
    const tint = [];
    def.frames.forEach((f, i) => {
      const { art, tint: t } = unitFrame(kind, f);
      frames.push(put(`unit.${kind}.${pose}.${i}`, art));
      tint.push(put(`unit.${kind}.${pose}.${i}.tint`, t));
    });
    anims[`unit.${kind}.${pose}`] = {
      frames,
      tint,
      anchor: cellPx(BODY_ANCHOR),
      mount: cellPx([BODY_ANCHOR[0], BODY_ANCHOR[1] - 5.5]),
      frameMs: def.frameMs,
      loop: def.loop,
      ...(def.barrel === false ? { barrel: false } : {}),
    };
  }
  const { art, tint } = barrelFrame(kind);
  anims[`barrel.${kind}`] = {
    frames: [put(`barrel.${kind}`, art)],
    ...(tint ? { tint: [put(`barrel.${kind}.tint`, tint)] } : {}),
    anchor: cellPx(BARREL_ANCHOR),
  };
}
for (const [shell, layers] of Object.entries(SEEDS)) {
  anims[`seed.${shell}`] = { frames: [put(`seed.${shell}`, render(SEED_W, SEED_W, SEED_ANCHOR, layers))], anchor: cellPx(SEED_ANCHOR) };
}
for (const [name, fill] of [
  ['explosion', false],
  ['mound', true],
]) {
  const frames = [];
  for (let k = 0; k < BOOM_FRAMES; k++) frames.push(put(`${name}.${k}`, boomFrame(k, fill)));
  anims[name] = { frames, anchor: cellPx(BOOM_ANCHOR), radius: BOOM_R * PPC, loop: false };
}

// 선반 묶기 — 높이 순으로 줄을 채운다(1px 띄움, 이웃 프레임이 번지지 않게).
const order = [...pieces].sort((a, b) => b.img.h - a.img.h || a.key.localeCompare(b.key));
const rects = new Map();
let x = 0;
let y = 0;
let rowH = 0;
for (const p of order) {
  if (x + p.img.w > SHEET_W) {
    x = 0;
    y += rowH + 1;
    rowH = 0;
  }
  rects.set(p.key, [x, y, p.img.w, p.img.h]);
  x += p.img.w + 1;
  rowH = Math.max(rowH, p.img.h);
}
const SHEET_H = y + rowH;
const sheet = new Uint8Array(SHEET_W * SHEET_H * 4);
for (const p of pieces) {
  const [rx, ry, w, h] = rects.get(p.key);
  for (let j = 0; j < h; j++) {
    for (let i = 0; i < w; i++) {
      const s = (j * w + i) * 4;
      const d = ((ry + j) * SHEET_W + rx + i) * 4;
      const a = p.img.px[s + 3];
      sheet[d] = Math.round(p.img.px[s]);
      sheet[d + 1] = Math.round(p.img.px[s + 1]);
      sheet[d + 2] = Math.round(p.img.px[s + 2]);
      sheet[d + 3] = Math.round(Math.max(0, Math.min(1, a)) * 255);
    }
  }
}
for (const a of Object.values(anims)) {
  a.frames = a.frames.map((k) => rects.get(k));
  if (a.tint) a.tint = a.tint.map((k) => rects.get(k));
}

// ─── PNG ──────────────────────────────────────────────────────────────
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
function png(w, h, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; // 비트 깊이
  ihdr[9] = 6; // RGBA
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let j = 0; j < h; j++) {
    raw[j * (w * 4 + 1)] = 0;
    Buffer.from(rgba.buffer, j * w * 4, w * 4).copy(raw, j * (w * 4 + 1) + 1);
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
  pxPerCell: PPC,
  labelLift: 15,
  recoilCells: 2,
  soil: { grass: '#60a848', grassDark: '#468438', topsoil: '#96663e', subsoil: '#704a2e' },
  anims,
};

await mkdir(OUT_DIR, { recursive: true });
await writeFile(resolve(OUT_DIR, 'atlas.png'), png(SHEET_W, SHEET_H, sheet));
// 한 애니메이션이 한 줄 — 사람이 고치기 쉽게, 프레임 사각형은 [x, y, w, h] 한 줄로.
const json = JSON.stringify(atlas, null, 2).replace(/\[\s+(-?\d+),\s+(-?\d+)(?:,\s+(-?\d+),\s+(-?\d+))?\s+\]/g, (_m, a, b, c, d) =>
  c === undefined ? `[${a}, ${b}]` : `[${a}, ${b}, ${c}, ${d}]`,
);
await writeFile(resolve(OUT_DIR, 'atlas.json'), `${json}\n`);
console.log(`atlas.png ${SHEET_W}×${SHEET_H}, 프레임 ${pieces.length}개, 애니메이션 ${Object.keys(anims).length}개 → ${OUT_DIR}`);
