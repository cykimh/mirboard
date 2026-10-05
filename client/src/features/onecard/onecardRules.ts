import type { OneCardCard, OneCardSuit } from '@/types/onecard';

const SUIT_ORDER: Record<OneCardSuit, number> = { SPADE: 0, HEART: 1, DIAMOND: 2, CLUB: 3 };

/**
 * 원카드 카드 판정의 클라 미러 — 서버 `card/PlayingCard`·`rules/PlayRules` 와 같은 규칙이다
 * (`docs/rules-onecard.md` §1·§5·§6). **표시 전용**(낼 수 없는 카드 흐리게, 먹기 장수 안내)이고
 * 판정의 권위는 서버다.
 */

/** 먹일 장수 — 2 는 2, A 는 3, 흑백 조커 5, 컬러 조커 7, 그 밖은 0 (§1). */
export function attackValue(card: OneCardCard): number {
  if (card.joker === 'COLOR') return 7;
  if (card.joker === 'BLACK') return 5;
  if (card.rank === 2) return 2;
  return card.rank === 1 ? 3 : 0;
}

export function isAttack(card: OneCardCard): boolean {
  return attackValue(card) > 0;
}

/** 반격 세기 — 2 < A < 흑백 조커 < 컬러 조커 (§6.2). 공격 카드가 아니면 0. */
export function attackStrength(card: OneCardCard): number {
  if (card.joker === 'COLOR') return 4;
  if (card.joker === 'BLACK') return 3;
  if (card.rank === 1) return 2;
  return card.rank === 2 ? 1 : 0;
}

/** 7 — 낼 때 무늬를 지정한다 (§8.1). */
export function isSuitChange(card: OneCardCard): boolean {
  return !card.joker && card.rank === 7;
}

/** 기준 무늬 (§5.1) — 7 로 지정된 무늬, 없으면 맨 위 카드의 무늬(조커면 null). */
export function baseSuitOf(
  top: OneCardCard | null,
  declaredSuit: OneCardSuit | null,
): OneCardSuit | null {
  return declaredSuit ?? top?.suit ?? null;
}

/**
 * 이 카드를 지금 낼 수 있는가 (§5.2·§5.3·§6.2). 공격받는 중이면 맨 위 공격 이상의 세기인 공격 카드만 —
 * 같은 숫자는 무늬와 무관하고, 다른 숫자는 기준 무늬가 같아야 하며, 조커는 무늬와 무관하다. 아니면 기준 무늬·
 * 같은 숫자·조커이고, 맨 위가 조커면 아무 카드나 된다. 7 은 와일드가 아니다.
 */
export function canPlay(
  card: OneCardCard,
  top: OneCardCard | null,
  declaredSuit: OneCardSuit | null,
  attackStack: number,
): boolean {
  if (top === null) return true;
  const base = baseSuitOf(top, declaredSuit);
  if (attackStack > 0) {
    if (!isAttack(card) || attackStrength(card) < attackStrength(top)) return false;
    if (card.joker) return true;
    return card.rank === top.rank || card.suit === base;
  }
  if (card.joker || top.joker) return true;
  return card.suit === base || card.rank === top.rank;
}

/** 지금 먹으면 몇 장인가 (§7) — 공격받는 중이면 누적, 아니면 1. */
export function drawCount(attackStack: number): number {
  return attackStack > 0 ? attackStack : 1;
}

/** 손패 표시 순서 — 무늬(♠♥♦♣)별 숫자 오름차순, 조커는 맨 뒤(흑백 → 컬러). 표시 전용이다. */
export function sortForDisplay(hand: OneCardCard[]): OneCardCard[] {
  const order = (c: OneCardCard) =>
    c.joker ? 100 + (c.joker === 'BLACK' ? 0 : 1) : SUIT_ORDER[c.suit!] * 20 + c.rank;
  return [...hand].sort((a, b) => order(a) - order(b));
}
