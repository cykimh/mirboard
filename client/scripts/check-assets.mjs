#!/usr/bin/env node
// Phase 8F PoC 보조 — client/public/ 의 카드/캐릭터/사운드 자산이 얼마나 채워졌는지
// 한눈에 보여준다. 빌드 시점 검증은 아니라 정보 출력만.
//
// 사용: `npm --prefix client run check:assets`

import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const SUITS = ['jade', 'sword', 'star', 'pagoda'];
const RANKS = ['2', '3', '4', '5', '6', '7', '8', '9', '10', 'J', 'Q', 'K', 'A'];
const SPECIALS = ['mahjong', 'dog', 'phoenix', 'dragon'];

const __dirname = dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = resolve(__dirname, '..', 'public');

function checkSet(label, paths) {
  const present = paths.filter((p) => existsSync(resolve(PUBLIC_DIR, p)));
  const missing = paths.filter((p) => !existsSync(resolve(PUBLIC_DIR, p)));
  const pct = Math.round((present.length / paths.length) * 100);
  const bar =
    '█'.repeat(Math.floor(pct / 5)) + '░'.repeat(20 - Math.floor(pct / 5));
  console.log(
    `${label.padEnd(18)} ${bar} ${present.length}/${paths.length} (${pct}%)`,
  );
  if (missing.length > 0 && missing.length <= 12) {
    console.log(`  누락: ${missing.join(', ')}`);
  } else if (missing.length > 12) {
    console.log(`  누락: ${missing.slice(0, 8).join(', ')} ... 외 ${missing.length - 8}개`);
  }
}

console.log('\nMirboard 자산 진행률\n');

const normalCards = SUITS.flatMap((s) => RANKS.map((r) => `cards/${s}-${r}.webp`));
checkSet('일반 카드 52장', normalCards);

const specialCards = SPECIALS.map((s) => `cards/${s}.webp`);
checkSet('특수 카드 4장', specialCards);

checkSet('카드 뒷면 1장', ['cards/back.webp']);

const characters = [0, 1, 2, 3].map((i) => `characters/seat-${i}.webp`);
checkSet('캐릭터 4종', characters);

checkSet('보드 배경 (선택)', ['board/felt.webp']);

const sfx = ['sfx/bomb.mp3', 'sfx/straight-flush.mp3'];
checkSet('효과음 2개', sfx);

console.log('\n실제 자산이 없어도 게임은 graceful fallback 으로 동작합니다.');
console.log('생성 가이드: docs/assets/card-prompts.md\n');
