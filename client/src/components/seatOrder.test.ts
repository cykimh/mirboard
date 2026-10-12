import { describe, expect, it } from 'vitest';
import { sideCounts, tableLayout, viewOrder, type TableSide, type TableSlot } from './seatOrder';

/**
 * 테이블 좌석 배치 (D-132, 설계 `docs/plans/table-seating.md` §4.1). 진행은 시계 방향 — 위에서 내려다볼 때 나(아래) 다음
 * 차례가 내 **왼쪽**이다. 그래서 상대는 왼쪽 옆줄(아래→위) → 윗줄(왼쪽→오른쪽) → 오른쪽 옆줄(위→아래) 순으로 앉는다.
 * (`viewOrder`·`seatMinWidth` 의 전수 테스트는 스컬킹 재내보내기와 함께 `features/skullking/seatLayout.test.ts` 에 있다.)
 */

const SEAT_COUNTS = [2, 3, 4, 5, 6, 7, 8];

/** 설계 §4.1 표 — 상대 수 → [left, top, right]. */
const SIDES: Record<number, [number, number, number]> = {
  1: [0, 1, 0],
  2: [1, 0, 1],
  3: [1, 1, 1],
  4: [2, 0, 2],
  5: [2, 1, 2],
  6: [2, 2, 2],
  7: [2, 3, 2],
};

const SIDE_ORDER: TableSide[] = ['left', 'top', 'right'];

/** 슬롯이 옆줄 순서(left → top → right)대로 놓이고 각 옆줄의 index 가 0 부터 1씩 느는지. */
function expectSideRuns(slots: TableSlot[], label: string) {
  let sideRank = 0;
  const next = new Map<TableSide, number>();
  for (const slot of slots) {
    const rank = SIDE_ORDER.indexOf(slot.side);
    expect(rank, `${label} 옆줄 순서`).toBeGreaterThanOrEqual(sideRank);
    sideRank = rank;
    expect(slot.index, `${label} ${slot.side} index`).toBe(next.get(slot.side) ?? 0);
    next.set(slot.side, slot.index + 1);
  }
}

function countBySide(slots: TableSlot[]): [number, number, number] {
  return SIDE_ORDER.map((side) => slots.filter((s) => s.side === side).length) as [number, number, number];
}

describe('sideCounts — 상대 수별 옆줄 인원 (설계 §4.1 표)', () => {
  it.each(Object.entries(SIDES))('상대 %s명', (k, [left, top, right]) => {
    expect(sideCounts(Number(k))).toEqual({ left, top, right });
  });

  it('좌우 옆줄은 같은 수이고 2명을 넘지 않으며, 합이 상대 수다', () => {
    for (let k = 0; k <= 9; k++) {
      const { left, top, right } = sideCounts(k);
      expect(left, `k=${k}`).toBe(right);
      expect(left, `k=${k}`).toBeLessThanOrEqual(2);
      expect(left + top + right, `k=${k}`).toBe(k);
    }
  });

  it('상대가 없거나 음수면 모두 0', () => {
    expect(sideCounts(0)).toEqual({ left: 0, top: 0, right: 0 });
    expect(sideCounts(-1)).toEqual({ left: 0, top: 0, right: 0 });
  });
});

describe('tableLayout — 2~8인 × 내 좌석 전수', () => {
  it('상대 좌석 = 나를 뺀 전 좌석(중복 없음), 내 자리는 아래 — bottomSeat 은 null', () => {
    for (const n of SEAT_COUNTS) {
      for (let mySeat = 0; mySeat < n; mySeat++) {
        const { bottomSeat, slots } = tableLayout(n, mySeat);
        const seats = slots.map((s) => s.seat);

        expect(bottomSeat, `n=${n} mySeat=${mySeat}`).toBeNull();
        expect(seats, `n=${n} mySeat=${mySeat}`).toHaveLength(n - 1);
        expect(new Set(seats).size, `n=${n} mySeat=${mySeat} 중복`).toBe(n - 1);
        expect(seats).not.toContain(mySeat);
      }
    }
  });

  it('슬롯 순서가 시계 방향 — 내 다음 좌석부터 viewOrder 그대로', () => {
    for (const n of SEAT_COUNTS) {
      for (let mySeat = 0; mySeat < n; mySeat++) {
        const seats = tableLayout(n, mySeat).slots.map((s) => s.seat);
        expect(seats, `n=${n} mySeat=${mySeat}`).toEqual(viewOrder(n, mySeat));
        expect(seats[0], `n=${n} mySeat=${mySeat} 첫 좌석`).toBe((mySeat + 1) % n);
      }
    }
  });

  it('옆줄별 인원이 §4.1 표와 같고, 왼쪽 → 위 → 오른쪽 순서에 옆줄 안 index 가 0 부터 이어진다', () => {
    for (const n of SEAT_COUNTS) {
      for (let mySeat = 0; mySeat < n; mySeat++) {
        const { slots } = tableLayout(n, mySeat);
        expect(countBySide(slots), `n=${n} mySeat=${mySeat}`).toEqual(SIDES[n - 1]);
        expectSideRuns(slots, `n=${n} mySeat=${mySeat}`);
      }
    }
  });
});

describe('tableLayout — 예시', () => {
  it('2인: 상대가 위에 혼자 앉는다', () => {
    expect(tableLayout(2, 1)).toEqual({ bottomSeat: null, slots: [{ seat: 0, side: 'top', index: 0 }] });
  });

  it('4인 내 좌석 0: 다음(1)이 왼쪽, 맞은편(2)이 위, 직전(3)이 오른쪽', () => {
    expect(tableLayout(4, 0)).toEqual({
      bottomSeat: null,
      slots: [
        { seat: 1, side: 'left', index: 0 },
        { seat: 2, side: 'top', index: 0 },
        { seat: 3, side: 'right', index: 0 },
      ],
    });
  });

  it('8인 내 좌석 5: 내 좌석 기준으로 돌아 왼쪽 2·위 3·오른쪽 2', () => {
    expect(tableLayout(8, 5).slots).toEqual([
      { seat: 6, side: 'left', index: 0 },
      { seat: 7, side: 'left', index: 1 },
      { seat: 0, side: 'top', index: 0 },
      { seat: 1, side: 'top', index: 1 },
      { seat: 2, side: 'top', index: 2 },
      { seat: 3, side: 'right', index: 0 },
      { seat: 4, side: 'right', index: 1 },
    ]);
  });
});

describe('tableLayout — 관전자 (mySeat < 0)', () => {
  it('좌석 0 을 아래에 두고 나머지를 0 기준 시계 방향으로 앉힌다', () => {
    for (const n of SEAT_COUNTS) {
      const { bottomSeat, slots } = tableLayout(n, -1);

      expect(bottomSeat, `n=${n}`).toBe(0);
      expect(slots.map((s) => s.seat), `n=${n}`).toEqual(viewOrder(n, 0));
      expect(countBySide(slots), `n=${n}`).toEqual(SIDES[n - 1]);
      expectSideRuns(slots, `관전 n=${n}`);
    }
  });

  it('6인 관전: 1·2 왼쪽, 3 위, 4·5 오른쪽', () => {
    expect(tableLayout(6, -1)).toEqual({
      bottomSeat: 0,
      slots: [
        { seat: 1, side: 'left', index: 0 },
        { seat: 2, side: 'left', index: 1 },
        { seat: 3, side: 'top', index: 0 },
        { seat: 4, side: 'right', index: 0 },
        { seat: 5, side: 'right', index: 1 },
      ],
    });
  });
});

describe('tableLayout — 범위 밖 입력 (viewOrder 의 기존 폴백)', () => {
  it('내 좌석이 좌석 수 이상이면 전 좌석을 상대로 앉히고 아래는 비운다', () => {
    const { bottomSeat, slots } = tableLayout(4, 9);

    expect(bottomSeat).toBeNull();
    expect(slots.map((s) => s.seat)).toEqual([0, 1, 2, 3]);
    expect(countBySide(slots)).toEqual([2, 0, 2]);
  });

  it('좌석 수가 2 미만이면 앉힐 상대가 없다', () => {
    expect(tableLayout(1, 0)).toEqual({ bottomSeat: null, slots: [] });
    expect(tableLayout(0, 0)).toEqual({ bottomSeat: null, slots: [] });
    expect(tableLayout(0, -1)).toEqual({ bottomSeat: null, slots: [] });
    expect(tableLayout(1, -1)).toEqual({ bottomSeat: 0, slots: [] });
  });
});
