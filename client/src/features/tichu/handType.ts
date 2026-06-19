import type { Card, Hand, HandType } from '@/types/tichu';

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

  // CONSECUTIVE_PAIRS: ≥4(2페어 이상), 짝수, 모든 rank 2장씩, 연속 (D-73)
  if (
    cards.length >= 4 &&
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

/** 선택 카드 분석 결과(서버 Hand 비교에 필요한 최소 정보). */
export interface AnalyzedHand {
  type: HandType;
  rank: number;
  length: number;
  phoenixSingle: boolean;
}

/** 특수 싱글의 비교 rank — 서버 Card 센티넬과 일치(개 0·마작 1·드래곤 100). */
function singleRank(c: Card): number {
  switch (c.special) {
    case 'DOG':
      return 0;
    case 'MAHJONG':
      return 1;
    case 'DRAGON':
      return 100;
    case 'PHOENIX':
      return 0; // phoenixSingle 로 별도 처리
    default:
      return c.rank;
  }
}

/** 인식된 조합의 대표 비교 rank(서버 Hand.rank 의미와 일치). 불확실하면 null. */
function comboRank(type: HandType, cards: Card[]): number | null {
  const nonPhoenix = cards.filter((c) => c.special !== 'PHOENIX');
  const rankOf = (c: Card): number => (c.special === 'MAHJONG' ? 1 : c.rank);
  const counts = new Map<number, number>();
  for (const c of nonPhoenix) {
    const r = rankOf(c);
    counts.set(r, (counts.get(r) ?? 0) + 1);
  }
  switch (type) {
    case 'PAIR':
    case 'TRIPLE':
    case 'BOMB':
      // 동일 rank(피닉스는 자연 카드 rank 에 매칭) — 자연 카드가 모두 같은 rank.
      return counts.size === 1 ? [...counts.keys()][0] : null;
    case 'FULL_HOUSE':
      for (const [r, count] of counts) if (count === 3) return r;
      return null;
    case 'STRAIGHT':
    case 'CONSECUTIVE_PAIRS':
    case 'STRAIGHT_FLUSH_BOMB': {
      let max = -Infinity;
      for (const r of counts.keys()) max = Math.max(max, r);
      return Number.isFinite(max) ? max : null;
    }
    default:
      return null;
  }
}

/**
 * 선택 카드를 {type, rank, length, phoenixSingle} 로 분석(서버 미러). detectHandType
 * 가 인식 못 하는(피닉스 와일드 등) 경우 null → "불확실" 로 취급해 버튼을 막지 않는다.
 */
export function analyzeHand(cards: Card[]): AnalyzedHand | null {
  const type = detectHandType(cards);
  if (type === null) return null;
  const length = cards.length;
  if (type === 'SINGLE') {
    const c = cards[0];
    if (c.special === 'PHOENIX') {
      return { type, rank: 0, length, phoenixSingle: true };
    }
    return { type, rank: singleRank(c), length, phoenixSingle: false };
  }
  const rank = comboRank(type, cards);
  if (rank === null) return null;
  return { type, rank, length, phoenixSingle: false };
}

function isBombType(t: HandType): boolean {
  return t === 'BOMB' || t === 'STRAIGHT_FLUSH_BOMB';
}

/**
 * 서버 `HandComparator.canBeat` 의 클라 미러 — challenger 가 current 를 이기는가.
 * (피닉스 싱글, 폭탄/스트레이트플러시폭탄, 동일 타입·길이·고랭크 규칙)
 */
export function canBeat(challenger: AnalyzedHand, current: Hand): boolean {
  if (challenger.phoenixSingle) {
    if (current.type !== 'SINGLE') return false;
    if (isBombType(current.type)) return false;
    const top = current.cards[0];
    return top?.special !== 'DRAGON';
  }
  const c = challenger.type;
  const o = current.type;
  if (c === 'STRAIGHT_FLUSH_BOMB') {
    if (o === 'STRAIGHT_FLUSH_BOMB') {
      if (challenger.length !== current.length) return challenger.length > current.length;
      return challenger.rank > current.rank;
    }
    return true;
  }
  if (o === 'STRAIGHT_FLUSH_BOMB') return false;
  if (c === 'BOMB') {
    if (o === 'BOMB') return challenger.rank > current.rank;
    return true;
  }
  if (o === 'BOMB') return false;
  if (c !== o) return false;
  if (challenger.length !== current.length) return false;
  return challenger.rank > current.rank;
}

/**
 * UI 게이트: 선택한 카드를 "지금" 합법적으로 낼 수 있는지(서버 미러, 좁게 판정).
 * - 인식 못 한 조합 → false(어차피 selectedCombo '?' 로 비활성)
 * - 리드(currentTop 없음) → 인식된 조합은 모두 가능(개 단독 포함). 위시 제약은 서버.
 * - currentTop 이 피닉스 싱글 → 비교값(소수) 의미 불확실 → 허용(서버 판정에 위임)
 * - follow → 개 단독은 불가, 그 외 canBeat 판정.
 * 확실히 불가일 때만 false 라서, 서버가 허용할 수를 잘못 막지 않는다(거짓 비활성 회피).
 */
export function isSelectionPlayable(cards: Card[], currentTop: Hand | null): boolean {
  const analyzed = analyzeHand(cards);
  if (!analyzed) return false;
  if (currentTop === null) return true;
  const isDogSingle = cards.length === 1 && cards[0].special === 'DOG';
  if (isDogSingle) return false; // 개는 단독 리드만 — follow 불가
  if (currentTop.phoenixSingle) {
    // 피닉스 싱글 위 비교값(소수)은 불확실 → 싱글/폭탄만 이길 가능성(서버가 최종 판정).
    // 다른 타입은 싱글을 못 이기므로 확실히 불가.
    return analyzed.type === 'SINGLE' || isBombType(analyzed.type);
  }
  return canBeat(analyzed, currentTop);
}
