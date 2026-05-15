#!/usr/bin/env node
// Phase 8F — 트럼프 풍 SVG 카드 생성기.
//
// Tichu 도메인 슈트 → 트럼프 시각 매핑:
//   JADE   → ♣ Clubs    (green  #2d8c4e)
//   SWORD  → ♦ Diamonds (blue   #2f6fe0)
//   STAR   → ♥ Hearts   (red    #d4253c)
//   PAGODA → ♠ Spades   (black  #1a1a1a)
//
// 출력:
//   client/public/cards/{suit-lowercase}-{rank}.svg  (52장 + back.svg)
//   client/public/cards/face-ornate/{suit}-{J|Q|K}.svg  (12장 — 왕관/장식 변형)
//
// 실행: node scripts/generate-cards.mjs

import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, '..');
const OUT_DIR = resolve(ROOT, 'client/public/cards');
const ORNATE_DIR = resolve(OUT_DIR, 'face-ornate');

const SUITS = {
  jade:   { trump: 'club',    color: '#2d8c4e' },
  sword:  { trump: 'diamond', color: '#2f6fe0' },
  star:   { trump: 'heart',   color: '#d4253c' },
  pagoda: { trump: 'spade',   color: '#1a1a1a' },
};

// 0..100 viewBox 안에서 정의된 슈트 도형. <g> 그룹으로 반환되어 transform 으로 배치/회전 가능.
function suitShape(trump, fill) {
  switch (trump) {
    case 'heart':
      return `<path fill="${fill}" d="M50 88 C 50 60 4 60 4 30 C 4 12 26 4 50 26 C 74 4 96 12 96 30 C 96 60 50 60 50 88 Z"/>`;
    case 'diamond':
      return `<path fill="${fill}" d="M50 4 L 92 50 L 50 96 L 8 50 Z"/>`;
    case 'club':
      return [
        `<circle fill="${fill}" cx="50" cy="26" r="20"/>`,
        `<circle fill="${fill}" cx="28" cy="58" r="20"/>`,
        `<circle fill="${fill}" cx="72" cy="58" r="20"/>`,
        `<path fill="${fill}" d="M40 92 L 60 92 L 56 58 L 44 58 Z"/>`,
      ].join('');
    case 'spade':
      return [
        // 거꾸로 된 하트 + 줄기
        `<path fill="${fill}" d="M50 6 C 50 36 96 36 96 64 C 96 82 76 90 56 72 L 62 92 L 38 92 L 44 72 C 24 90 4 82 4 64 C 4 36 50 36 50 6 Z"/>`,
      ].join('');
    default:
      return '';
  }
}

const W = 250;
const H = 350;
const RADIUS = 14;
const BORDER = '#d0d0d0';
const BG = '#ffffff';

const CORNER_RANK_SIZE = 30;
const CORNER_SUIT_SIZE = 20;
const CORNER_PAD_X = 14;
const CORNER_PAD_Y = 26;

// 가운데 pip 영역. 좌상단 코너 라벨 영역을 침범하지 않도록 충분히 안쪽으로.
const PIP_AREA = { x0: 56, y0: 70, x1: 194, y1: 280 };
const PIP_W = PIP_AREA.x1 - PIP_AREA.x0;
const PIP_H = PIP_AREA.y1 - PIP_AREA.y0;

// 각 rank 별 pip 좌표 (relative 0..1) — 표준 트럼프 레이아웃.
const PIP_LAYOUTS = {
  2:  [[0.5, 0.05], [0.5, 0.95]],
  3:  [[0.5, 0.05], [0.5, 0.5], [0.5, 0.95]],
  4:  [[0.18, 0.05], [0.82, 0.05], [0.18, 0.95], [0.82, 0.95]],
  5:  [[0.18, 0.05], [0.82, 0.05], [0.5, 0.5], [0.18, 0.95], [0.82, 0.95]],
  6:  [[0.18, 0.05], [0.82, 0.05], [0.18, 0.5], [0.82, 0.5], [0.18, 0.95], [0.82, 0.95]],
  7:  [[0.18, 0.05], [0.82, 0.05], [0.5, 0.28], [0.18, 0.5], [0.82, 0.5], [0.18, 0.95], [0.82, 0.95]],
  8:  [[0.18, 0.05], [0.82, 0.05], [0.5, 0.28], [0.18, 0.5], [0.82, 0.5], [0.5, 0.72], [0.18, 0.95], [0.82, 0.95]],
  9:  [[0.18, 0.05], [0.82, 0.05], [0.18, 0.36], [0.82, 0.36], [0.5, 0.5], [0.18, 0.64], [0.82, 0.64], [0.18, 0.95], [0.82, 0.95]],
  10: [[0.18, 0.05], [0.82, 0.05], [0.5, 0.22], [0.18, 0.36], [0.82, 0.36], [0.18, 0.64], [0.82, 0.64], [0.5, 0.78], [0.18, 0.95], [0.82, 0.95]],
};

const PIP_SIZE = 30; // 한 변 (viewBox unit 100 기준 스케일 대상)

function pipAt(trump, fill, relX, relY) {
  const cx = PIP_AREA.x0 + relX * PIP_W;
  const cy = PIP_AREA.y0 + relY * PIP_H;
  const half = PIP_SIZE / 2;
  // 하단 절반은 180° 회전 (트럼프 관례). 정확히 중앙 (relY===0.5) 은 회전 안 함.
  const rotate = relY > 0.5;
  const shape = suitShape(trump, fill);
  const transform = rotate
    ? `translate(${cx + half} ${cy + half}) rotate(180) scale(${PIP_SIZE / 100})`
    : `translate(${cx - half} ${cy - half}) scale(${PIP_SIZE / 100})`;
  return `<g transform="${transform}">${shape}</g>`;
}

function cornerLabel(trump, color, rankLabel, atTopLeft) {
  // 좌상단 또는 우하단 (180° 회전) 에 rank + 작은 슈트.
  const sizeR = CORNER_RANK_SIZE;
  const sizeS = CORNER_SUIT_SIZE;
  if (atTopLeft) {
    return `
      <g>
        <text x="${CORNER_PAD_X}" y="${CORNER_PAD_Y + 4}" fill="${color}" font-family="Georgia, 'Times New Roman', serif" font-size="${sizeR}" font-weight="700" text-anchor="middle">${rankLabel}</text>
        <g transform="translate(${CORNER_PAD_X - sizeS / 2} ${CORNER_PAD_Y + 10}) scale(${sizeS / 100})">${suitShape(trump, color)}</g>
      </g>`;
  }
  return `
    <g transform="translate(${W} ${H}) rotate(180)">
      <text x="${CORNER_PAD_X}" y="${CORNER_PAD_Y + 4}" fill="${color}" font-family="Georgia, 'Times New Roman', serif" font-size="${sizeR}" font-weight="700" text-anchor="middle">${rankLabel}</text>
      <g transform="translate(${CORNER_PAD_X - sizeS / 2} ${CORNER_PAD_Y + 10}) scale(${sizeS / 100})">${suitShape(trump, color)}</g>
    </g>`;
}

function svgHeader() {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}">`;
}

function svgBg() {
  return `<rect x="2" y="2" width="${W - 4}" height="${H - 4}" rx="${RADIUS}" ry="${RADIUS}" fill="${BG}" stroke="${BORDER}" stroke-width="1.5"/>`;
}

function rankLabel(rank) {
  if (rank === 11) return 'J';
  if (rank === 12) return 'Q';
  if (rank === 13) return 'K';
  if (rank === 14) return 'A';
  return String(rank);
}

function buildNumberCard(suitKey, rank) {
  const { trump, color } = SUITS[suitKey];
  const label = rankLabel(rank);
  const pips = PIP_LAYOUTS[rank].map(([x, y]) => pipAt(trump, color, x, y)).join('');
  return [
    svgHeader(),
    svgBg(),
    pips,
    cornerLabel(trump, color, label, true),
    cornerLabel(trump, color, label, false),
    '</svg>',
  ].join('\n');
}

function buildAce(suitKey) {
  const { trump, color } = SUITS[suitKey];
  // 중앙 단일 큰 슈트.
  const size = 140;
  const cx = W / 2 - size / 2;
  const cy = H / 2 - size / 2;
  return [
    svgHeader(),
    svgBg(),
    `<g transform="translate(${cx} ${cy}) scale(${size / 100})">${suitShape(trump, color)}</g>`,
    cornerLabel(trump, color, 'A', true),
    cornerLabel(trump, color, 'A', false),
    '</svg>',
  ].join('\n');
}

function buildFaceSimple(suitKey, rank) {
  // J/Q/K — 큰 letter + 중앙 슈트 심볼.
  const { trump, color } = SUITS[suitKey];
  const label = rankLabel(rank);
  const suitSize = 60;
  const suitX = W / 2 - suitSize / 2;
  const suitY = H / 2 + 10;
  return [
    svgHeader(),
    svgBg(),
    // 중앙에 큰 글자 + 그 아래 슈트
    `<text x="${W / 2}" y="${H / 2 - 12}" fill="${color}" font-family="Georgia, 'Times New Roman', serif" font-size="120" font-weight="700" text-anchor="middle" dominant-baseline="middle">${label}</text>`,
    `<g transform="translate(${suitX} ${suitY}) scale(${suitSize / 100})">${suitShape(trump, color)}</g>`,
    cornerLabel(trump, color, label, true),
    cornerLabel(trump, color, label, false),
    '</svg>',
  ].join('\n');
}

function buildFaceOrnate(suitKey, rank) {
  // J/Q/K — 왕관/장식 + 슈트 외곽 패널 + 큰 letter.
  const { trump, color } = SUITS[suitKey];
  const label = rankLabel(rank);

  // 색을 살짝 밝게 (장식용 보조 색)
  const accent = color === '#1a1a1a' ? '#5a5a5a' : color;

  // 중앙 직사각형 패널 (장식 테두리)
  const panel = `
    <rect x="50" y="60" width="${W - 100}" height="${H - 120}" rx="10" ry="10"
          fill="none" stroke="${accent}" stroke-width="2" opacity="0.6"/>
    <rect x="58" y="68" width="${W - 116}" height="${H - 136}" rx="6" ry="6"
          fill="none" stroke="${accent}" stroke-width="1" opacity="0.4"/>`;

  // 왕관 (Q 는 더 둥근, J 는 더 뾰족하게)
  const crownY = 110;
  let crown;
  if (rank === 13) {
    // K — 큰 왕관 + 십자
    crown = `
      <g transform="translate(${W / 2 - 40} ${crownY})">
        <path d="M0 30 L0 8 L12 22 L20 0 L28 22 L40 0 L48 22 L60 8 L60 30 Z" fill="${accent}" stroke="${color}" stroke-width="1.5"/>
        <circle cx="20" cy="6" r="3" fill="${color}"/>
        <circle cx="40" cy="6" r="3" fill="${color}"/>
        <circle cx="60" cy="14" r="3" fill="${color}"/>
        <circle cx="0" cy="14" r="3" fill="${color}"/>
        <rect x="28" y="-12" width="4" height="14" fill="${color}"/>
        <rect x="24" y="-8" width="12" height="4" fill="${color}"/>
        <rect x="0" y="30" width="60" height="4" fill="${color}"/>
      </g>`;
  } else if (rank === 12) {
    // Q — 둥근 왕관 + 보석
    crown = `
      <g transform="translate(${W / 2 - 40} ${crownY})">
        <path d="M0 30 L6 6 L20 22 L30 0 L40 22 L54 6 L60 30 Z" fill="${accent}" stroke="${color}" stroke-width="1.5"/>
        <circle cx="6" cy="6" r="4" fill="${color}"/>
        <circle cx="30" cy="0" r="5" fill="${color}"/>
        <circle cx="54" cy="6" r="4" fill="${color}"/>
        <rect x="0" y="30" width="60" height="4" fill="${color}"/>
      </g>`;
  } else {
    // J — 뾰족한 모자
    crown = `
      <g transform="translate(${W / 2 - 36} ${crownY})">
        <path d="M0 30 L4 14 L14 22 L24 4 L34 22 L44 14 L48 14 L58 22 L68 14 L72 30 Z" fill="${accent}" stroke="${color}" stroke-width="1.5"/>
        <circle cx="24" cy="2" r="3" fill="${color}"/>
        <circle cx="48" cy="12" r="3" fill="${color}"/>
        <rect x="0" y="30" width="72" height="4" fill="${color}"/>
      </g>`;
  }

  // 중앙 큰 글자 + 슈트 (장식 패널 안)
  const letter = `
    <text x="${W / 2}" y="${H / 2 + 26}" fill="${color}" font-family="Georgia, 'Times New Roman', serif"
          font-size="100" font-weight="700" text-anchor="middle" dominant-baseline="middle">${label}</text>`;

  // 작은 슈트 심볼 2개 (글자 좌우)
  const sLeft = `<g transform="translate(${W / 2 - 78} ${H / 2 + 4}) scale(0.36)">${suitShape(trump, color)}</g>`;
  const sRight = `<g transform="translate(${W / 2 + 50} ${H / 2 + 4}) scale(0.36)">${suitShape(trump, color)}</g>`;

  // 하단 장식 띠 (180° 회전된 왕관 미러)
  const crownBottom = `
    <g transform="translate(${W} ${H}) rotate(180)">
      ${crown.match(/<g[^>]*>([\s\S]*)<\/g>/)[0]}
    </g>`;

  return [
    svgHeader(),
    svgBg(),
    panel,
    crown,
    letter,
    sLeft,
    sRight,
    crownBottom,
    cornerLabel(trump, color, label, true),
    cornerLabel(trump, color, label, false),
    '</svg>',
  ].join('\n');
}

function buildBack() {
  // 진한 청록 배경 + 다이아몬드 격자 패턴.
  const cells = [];
  const step = 24;
  for (let y = 12; y < H - 8; y += step) {
    for (let x = 12; x < W - 8; x += step) {
      cells.push(`<path d="M${x} ${y - 8} L${x + 8} ${y} L${x} ${y + 8} L${x - 8} ${y} Z" fill="#0e4a52" opacity="0.65"/>`);
    }
  }
  return [
    svgHeader(),
    `<rect x="2" y="2" width="${W - 4}" height="${H - 4}" rx="${RADIUS}" ry="${RADIUS}" fill="#0b3a3f" stroke="${BORDER}" stroke-width="1.5"/>`,
    `<rect x="10" y="10" width="${W - 20}" height="${H - 20}" rx="8" ry="8" fill="none" stroke="#1d6b73" stroke-width="2"/>`,
    cells.join('\n'),
    `<rect x="10" y="10" width="${W - 20}" height="${H - 20}" rx="8" ry="8" fill="none" stroke="#1d6b73" stroke-width="2"/>`,
    '</svg>',
  ].join('\n');
}

async function main() {
  await mkdir(OUT_DIR, { recursive: true });
  await mkdir(ORNATE_DIR, { recursive: true });

  const written = [];

  // 52장 + back
  for (const suitKey of Object.keys(SUITS)) {
    for (let r = 2; r <= 14; r++) {
      const label = rankLabel(r);
      let svg;
      if (r === 14) svg = buildAce(suitKey);
      else if (r >= 11) svg = buildFaceSimple(suitKey, r);
      else svg = buildNumberCard(suitKey, r);

      const path = resolve(OUT_DIR, `${suitKey}-${label}.svg`);
      await writeFile(path, svg, 'utf8');
      written.push(path);
    }
  }

  // 카드 뒷면
  await writeFile(resolve(OUT_DIR, 'back.svg'), buildBack(), 'utf8');
  written.push(resolve(OUT_DIR, 'back.svg'));

  // J/Q/K — ornate 변형 (4 suits × 3 ranks = 12장)
  for (const suitKey of Object.keys(SUITS)) {
    for (const r of [11, 12, 13]) {
      const label = rankLabel(r);
      const svg = buildFaceOrnate(suitKey, r);
      const path = resolve(ORNATE_DIR, `${suitKey}-${label}.svg`);
      await writeFile(path, svg, 'utf8');
      written.push(path);
    }
  }

  console.log(`Generated ${written.length} SVG files.`);
  console.log(`  - ${OUT_DIR}/{suit}-{rank}.svg  (52 + back.svg)`);
  console.log(`  - ${ORNATE_DIR}/{suit}-{J|Q|K}.svg  (12 ornate face variants)`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
