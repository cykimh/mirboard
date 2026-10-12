/**
 * 가변 좌석 게임판의 순수 배치 계산 — 스컬킹(2~8인, D-103)과 원카드(2~6인, D-129)가 같이 쓴다. DOM 을 모른다 —
 * 전수 테스트가 jsdom 없이 돈다(`viewOrder`·`seatMinWidth` 는 `features/skullking/seatLayout.test.ts`, 테이블 배치는
 * `seatOrder.test.ts`).
 */

/**
 * 상대 좌석의 진행 순서 — **내 다음 차례부터**(시계 방향). `tableLayout` 이 이 순서대로 테이블에 앉힌다(D-132).
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
 * 좌석 카드의 최소 폭 — 인원이 늘수록 좁아져 한 줄에 더 많이 들어간다(**폭 미디어 쿼리 없이**). 테이블 배치(D-132)
 * 에서는 윗줄 좌석의 기준 폭이다(옆줄 좌석 폭은 각 게임 CSS 의 `clamp()`).
 */
export function seatMinWidth(seatCount: number): string {
  if (seatCount <= 4) return '132px';
  if (seatCount <= 6) return '116px';
  return '100px';
}

/** 테이블에서 상대가 앉는 옆줄 (D-132). 내 자리는 늘 아래라 여기 없다. */
export type TableSide = 'left' | 'top' | 'right';

export interface TableSlot {
  /** 서버 좌석 번호. */
  seat: number;
  side: TableSide;
  /** 옆줄 안 순서(0 부터, 시계 방향) — left 는 아래→위, top 은 왼쪽→오른쪽, right 는 위→아래. */
  index: number;
}

/**
 * 상대 수 → 옆줄별 인원. 좌우 옆줄은 같은 수로 최대 2명씩이고 나머지가 윗줄이다 — 5인 이상은 위쪽 반원을 따라
 * 앉는다(왼쪽 옆줄 → 윗줄 → 오른쪽 옆줄). 설계 `docs/plans/table-seating.md` §4.1 표.
 */
export function sideCounts(opponents: number): { left: number; top: number; right: number } {
  const k = Math.max(0, opponents);
  const side = Math.min(2, Math.floor(k / 2));
  return { left: side, top: k - 2 * side, right: side };
}

/**
 * 내 좌석 기준 테이블 배치 (D-132). 진행은 **시계 방향**이라 위에서 내려다볼 때 나(아래) 다음 차례가 내 왼쪽이다 —
 * 상대를 `viewOrder` 순서(내 다음 좌석부터) 그대로 왼쪽 옆줄(아래→위) → 윗줄(왼쪽→오른쪽) → 오른쪽 옆줄(위→아래)에
 * 채운다. 그래서 `slots` 순서가 곧 진행 순서다(DOM 순서로 쓰면 스크린리더가 다음 사람부터 읽는다).
 *
 * <p>내 자리는 늘 아래다(`bottomSeat = null` — 게임판의 "나" 줄이 그 자리). 관전자(`mySeat < 0`)는 좌석 0 을 아래
 * (`bottomSeat = 0`)에 두고 나머지를 0 기준으로 앉힌다. 범위 밖 `mySeat`(≥ 좌석 수)은 `viewOrder` 의 폴백(전 좌석)을 따른다.
 */
export function tableLayout(
  seatCount: number,
  mySeat: number,
): { bottomSeat: number | null; slots: TableSlot[] } {
  if (mySeat < 0) {
    if (seatCount <= 0) return { bottomSeat: null, slots: [] };
    return { bottomSeat: 0, slots: toSlots(viewOrder(seatCount, 0)) };
  }
  return { bottomSeat: null, slots: toSlots(viewOrder(seatCount, mySeat)) };
}

function toSlots(order: number[]): TableSlot[] {
  const { left, top } = sideCounts(order.length);
  return order.map((seat, i) => {
    if (i < left) return { seat, side: 'left', index: i };
    if (i < left + top) return { seat, side: 'top', index: i - left };
    return { seat, side: 'right', index: i - left - top };
  });
}
