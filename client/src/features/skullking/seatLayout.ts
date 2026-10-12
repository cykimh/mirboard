import {
  isSuitCard,
  type PlayedCardView,
  type SkullCard,
  type SkullSuit,
} from '@/types/skullking';

/**
 * 2~8인 가변 좌석의 순수 배치 계산 (D-103, 테이블 배치 D-132). DOM 을 모른다 — 전수 테스트가
 * jsdom 없이 돈다. 좌석 순서·최소 폭은 원카드도 쓰는 공용 모듈에서 다시 내보낸다(D-129).
 */

export { seatMinWidth, viewOrder } from '@/components/seatOrder';

/** 좌석별 accent 색 — 트릭 레일에서 "누가 냈는지"를 잇는 단서 중 하나. */
const ACCENTS = [
  'var(--sk-accent-0)',
  'var(--sk-accent-1)',
  'var(--sk-accent-2)',
  'var(--sk-accent-3)',
  'var(--sk-accent-4)',
  'var(--sk-accent-5)',
  'var(--sk-accent-6)',
  'var(--sk-accent-7)',
];

export function seatAccent(seat: number): string {
  return ACCENTS[((seat % ACCENTS.length) + ACCENTS.length) % ACCENTS.length];
}

/**
 * 일반 진행 기준 다음 차례 (D-132 — 게임판의 "다음" 표시). 서버 진행은 트릭 안에서 +1(`TrickState`)이다. 차례가 없거나
 * (`currentTurnSeat` −1 — 입찰·라운드 정리) 지금 사람이 이번 트릭의 마지막이면(`trickLength + 1 >= seatCount` — 다음 리드는 트릭
 * 승자라 아직 모른다) −1. 탈주 좌석은 유령이 대신 두므로 건너뛰지 않는다(룰 ⑱). 표시 전용이다.
 *
 * <p>트릭이 막 끝난 순간(`TRICK_TAKEN` 뒤 승자의 `TURN_CHANGED` 전)은 스토어가 차례를 −1 로 만들지 않는다(지난 사람이 남는다) —
 * 그 구간은 게임판(`SkullKingTable`)이 −1 로 다룬다.
 *
 * @param trickLength 이번 트릭에 이미 나온 카드 수(지금 사람 것 제외).
 */
export function nextTurnSeat(currentTurnSeat: number, seatCount: number, trickLength: number): number {
  if (currentTurnSeat < 0 || seatCount <= 0) return -1;
  if (trickLength + 1 >= seatCount) return -1;
  return (currentTurnSeat + 1) % seatCount;
}

/** 캐릭터 카드인가 — 인어·해적·스컬킹·해적 선언 티그리스 (§6.1). */
function isCharacter(pc: PlayedCardView): boolean {
  const s = pc.card.special;
  if (s === 'MERMAID' || s === 'PIRATE' || s === 'SKULL_KING') return true;
  return s === 'TIGRESS' && pc.declaredAs === 'PIRATE';
}

/**
 * 리드 수트 파생 — 서버 `LeadSuitResolver` 의 클라 미러 (§6.1, §13-⑤). **표시·힌트 전용**이고
 * 합법성의 권위는 서버다.
 *
 * 세 갈래: 첫 카드가 캐릭터면 그 트릭엔 리드 수트가 **영구히 없다**. 그 외에는 처음 나온
 * 색상 카드의 색이며, 탈출로 리드하면 확정이 뒤로 밀린다(그 사이 캐릭터가 끼어도 미확정).
 */
export function leadSuitOf(trick: PlayedCardView[]): SkullSuit | null {
  if (trick.length === 0) return null;
  if (isCharacter(trick[0])) return null;
  for (const pc of trick) {
    if (isSuitCard(pc.card)) return pc.card.suit;
  }
  return null;
}

/**
 * 이 카드를 지금 낼 수 있는가 — follow 의무 힌트 (§6.2). **표시 전용**(회색 처리)이고
 * 최종 판정은 서버가 한다.
 */
export function isLegalPlayHint(
  card: SkullCard,
  hand: SkullCard[],
  leadSuit: SkullSuit | null,
): boolean {
  if (leadSuit === null) return true;
  if (!isSuitCard(card)) return true; // 특수 카드는 언제나 합법
  if (card.suit === leadSuit) return true;
  // 리드 수트를 하나도 안 들고 있으면 아무 색이나 가능.
  return !hand.some((c) => isSuitCard(c) && c.suit === leadSuit);
}
