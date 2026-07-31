import {
  isSuitCard,
  type PlayedCardView,
  type SkullCard,
  type SkullSuit,
} from '@/types/skullking';

/**
 * 2~8인 가변 좌석의 순수 배치 계산 (D-103, Row-Flow). DOM 을 모른다 — 전수 테스트가
 * jsdom 없이 돈다.
 */

/**
 * 상대 좌석을 화면에 늘어놓을 순서. **내 다음 차례부터 좌→우**라 진행 방향이 읽힌다.
 *
 * @param mySeat 관전자는 -1 — 그때는 내 좌석을 뺄 것이 없으므로 전 좌석을 돌려준다.
 */
export function viewOrder(seatCount: number, mySeat: number): number[] {
  if (seatCount <= 0) return [];
  if (mySeat < 0 || mySeat >= seatCount) {
    return Array.from({ length: seatCount }, (_, i) => i);
  }
  return Array.from(
    { length: seatCount - 1 },
    (_, i) => (mySeat + 1 + i) % seatCount,
  );
}

/**
 * 좌석 카드의 최소 폭. `repeat(auto-fit, minmax(이 값, 168px))` 에 꽂으면 인원이 늘수록
 * 한 행에 더 많이 들어가고, 넘치면 **폭 미디어 쿼리 없이** 자동 줄바꿈된다.
 */
export function seatMinWidth(seatCount: number): string {
  if (seatCount <= 4) return '132px';
  if (seatCount <= 6) return '116px';
  return '100px';
}

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
