import type { Card, HandType } from '@/types/tichu';

/**
 * Phase 12C — 선택한 카드 조합을 표시용으로 판별하는 **경량 클라 hint**.
 *
 * ⚠️ 서버 `HandDetector` 가 룰 단일 진실공급원 (Phase 10). 본 함수는 UI 표시 전용
 * hint 이며 합법성 강제는 서버 `ActionValidator` 가 계속 담당한다. Phoenix 와일드
 * 같은 복잡 케이스는 best-effort — 애매하면 `null` 을 반환해 "조합 확인 중" 으로
 * 표시하고, 실제 플레이는 서버 판정을 따른다.
 *
 * 지원: SINGLE / PAIR / TRIPLE / FULL_HOUSE / STRAIGHT(≥5) /
 * CONSECUTIVE_PAIRS(≥6 짝수) / BOMB(동일 rank 4) / STRAIGHT_FLUSH_BOMB(≥5 동일
 * suit 연속). Mahjong 은 STRAIGHT 의 rank 1 로 인정. Dog/Dragon 은 단독만.
 */
export function detectHandType(cards: Card[]): HandType | null {
  if (cards.length === 0) return null;
  if (cards.length === 1) return 'SINGLE';

  // Dog/Dragon 은 단독 외 조합 불가.
  if (cards.some((c) => c.special === 'DOG' || c.special === 'DRAGON')) return null;

  const phoenixCount = cards.filter((c) => c.special === 'PHOENIX').length;
  const nonPhoenix = cards.filter((c) => c.special !== 'PHOENIX');

  // Mahjong 은 STRAIGHT 에서 rank 1. 그 외 일반 카드는 rank 2..14.
  const ranks = nonPhoenix.map((c) => (c.special === 'MAHJONG' ? 1 : c.rank));
  const hasNonMahjongSpecial = nonPhoenix.some(
    (c) => c.special !== null && c.special !== 'MAHJONG',
  );

  // ---- Phoenix 포함: best-effort (PAIR/TRIPLE 만) ----
  if (phoenixCount > 0) {
    if (hasNonMahjongSpecial) return null; // Phoenix + Dog/Dragon/Mahjong 혼합 불가
    if (phoenixCount >= 2) return null; // Phoenix 1장뿐
    if (cards.length === 2) return 'PAIR'; // Phoenix + 일반 1 → 페어
    if (cards.length === 3) {
      // 같은 rank 2장 + Phoenix → 트리플
      if (ranks.length === 2 && ranks[0] === ranks[1]) return 'TRIPLE';
      return null;
    }
    return null; // 그 외 Phoenix 콤보는 서버 판정에 위임
  }

  // ---- Phoenix 없음 ----
  if (hasNonMahjongSpecial) return null; // Mahjong 외 특수카드는 콤보 불가

  const counts = new Map<number, number>();
  for (const r of ranks) counts.set(r, (counts.get(r) ?? 0) + 1);
  const distinctRanks = [...counts.keys()].sort((a, b) => a - b);

  // 동일 rank n장
  if (distinctRanks.length === 1) {
    if (cards.length === 2) return 'PAIR';
    if (cards.length === 3) return 'TRIPLE';
    if (cards.length === 4) return 'BOMB';
    return null;
  }

  // FULL_HOUSE: 정확히 3+2
  if (cards.length === 5 && distinctRanks.length === 2) {
    const c = [...counts.values()].sort();
    if (c[0] === 2 && c[1] === 3) return 'FULL_HOUSE';
    return null;
  }

  // STRAIGHT / STRAIGHT_FLUSH_BOMB: ≥5, 모두 distinct, 연속
  if (
    cards.length >= 5 &&
    distinctRanks.length === cards.length &&
    isConsecutive(distinctRanks)
  ) {
    const suits = nonPhoenix.map((c) => c.suit);
    const allSameSuit =
      suits.every((s) => s !== null && s === suits[0]) &&
      !nonPhoenix.some((c) => c.special === 'MAHJONG'); // Mahjong 은 suit 없음
    return allSameSuit ? 'STRAIGHT_FLUSH_BOMB' : 'STRAIGHT';
  }

  // CONSECUTIVE_PAIRS: ≥6, 짝수, 모든 rank 2장씩, 연속
  if (
    cards.length >= 6 &&
    cards.length % 2 === 0 &&
    [...counts.values()].every((v) => v === 2) &&
    isConsecutive(distinctRanks)
  ) {
    return 'CONSECUTIVE_PAIRS';
  }

  return null;
}

function isConsecutive(sortedDistinct: number[]): boolean {
  for (let i = 1; i < sortedDistinct.length; i++) {
    if (sortedDistinct[i] !== sortedDistinct[i - 1] + 1) return false;
  }
  return true;
}

const SPECIAL_LABEL: Record<string, string> = {
  DRAGON: '드래곤',
  PHOENIX: '피닉스',
  DOG: '개',
  MAHJONG: '마작',
};

/** 일반 rank → 표시 글자 (2~10, 11=J, 12=Q, 13=K, 14=A, 1=Mahjong). */
export function rankGlyph(rank: number): string {
  if (rank === 11) return 'J';
  if (rank === 12) return 'Q';
  if (rank === 13) return 'K';
  if (rank === 14) return 'A';
  return String(rank);
}

/** 조합의 대표 rank 글자 (없으면 ''). */
function representativeRank(type: HandType, cards: Card[]): string {
  if (type === 'SINGLE') {
    const c = cards[0];
    if (c.special) return SPECIAL_LABEL[c.special] ?? '';
    return rankGlyph(c.rank);
  }
  const normals = cards.filter((c) => c.special === null);
  if (normals.length === 0) return '';
  const counts = new Map<number, number>();
  for (const c of normals) counts.set(c.rank, (counts.get(c.rank) ?? 0) + 1);

  if (type === 'FULL_HOUSE') {
    // 트리플 rank
    for (const [r, n] of counts) if (n === 3) return rankGlyph(r);
    // Phoenix 와일드로 3장 된 경우 — 더 많은 쪽
  }
  if (type === 'PAIR' || type === 'TRIPLE' || type === 'BOMB') {
    // 동일 rank — 가장 많은 rank
    let best = normals[0].rank;
    let bestN = 0;
    for (const [r, n] of counts) if (n > bestN) { bestN = n; best = r; }
    return rankGlyph(best);
  }
  // STRAIGHT / SFB / CONSECUTIVE_PAIRS → 최고 rank
  const maxRank = Math.max(...normals.map((c) => c.rank));
  return rankGlyph(maxRank);
}

/**
 * Phase 12 (#2) — 선택 카드 조합을 "페어2", "풀하우스5" 형식으로 표시.
 * 조합 불명 시 "?".
 */
export function comboLabel(cards: Card[]): string {
  const type = detectHandType(cards);
  if (type === null) return '?';
  const base = handTypeLabel(type);
  const rank = representativeRank(type, cards);
  return rank ? `${base}${rank}` : base;
}

/** 조합 타입 → 한국어 표시 라벨. */
export function handTypeLabel(type: HandType | null): string {
  switch (type) {
    case 'SINGLE':
      return '싱글';
    case 'PAIR':
      return '페어';
    case 'TRIPLE':
      return '트리플';
    case 'FULL_HOUSE':
      return '풀하우스';
    case 'STRAIGHT':
      return '스트레이트';
    case 'CONSECUTIVE_PAIRS':
      return '연속 페어';
    case 'BOMB':
      return '폭탄';
    case 'STRAIGHT_FLUSH_BOMB':
      return '스트레이트 플러시 폭탄';
    default:
      return '?';
  }
}
