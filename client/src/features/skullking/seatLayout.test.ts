import { describe, expect, it } from 'vitest';
import {
  isLegalPlayHint,
  leadSuitOf,
  seatAccent,
  seatMinWidth,
  viewOrder,
} from './seatLayout';
import type { PlayedCardView, SkullCard, SkullSuit } from '@/types/skullking';

const suit = (s: SkullSuit, rank: number): SkullCard => ({
  suit: s,
  rank,
  special: null,
});
const special = (k: SkullCard['special']): SkullCard => ({
  suit: null,
  rank: 0,
  special: k,
});
const played = (
  seat: number,
  card: SkullCard,
  declaredAs: PlayedCardView['declaredAs'] = null,
): PlayedCardView => ({ seat, card, declaredAs });

const SEAT_COUNTS = [2, 3, 4, 5, 6, 7, 8];

describe('viewOrder — 2~8인 × mySeat 전수 불변식', () => {
  it('플레이어 시점: 내 좌석을 빼고 n-1개, 중복 없음, 전부 범위 내', () => {
    for (const n of SEAT_COUNTS) {
      for (let mySeat = 0; mySeat < n; mySeat++) {
        const order = viewOrder(n, mySeat);

        expect(order, `n=${n} mySeat=${mySeat}`).toHaveLength(n - 1);
        expect(new Set(order).size, `n=${n} mySeat=${mySeat} 중복`).toBe(n - 1);
        expect(order).not.toContain(mySeat);
        order.forEach((s) => {
          expect(s).toBeGreaterThanOrEqual(0);
          expect(s).toBeLessThan(n);
        });
      }
    }
  });

  it('첫 원소는 항상 내 다음 차례 — 진행 방향이 좌→우로 읽힌다', () => {
    for (const n of SEAT_COUNTS) {
      for (let mySeat = 0; mySeat < n; mySeat++) {
        expect(viewOrder(n, mySeat)[0], `n=${n} mySeat=${mySeat}`).toBe(
          (mySeat + 1) % n,
        );
      }
    }
  });

  it('순서가 좌석 번호 순환을 그대로 따른다', () => {
    expect(viewOrder(8, 5)).toEqual([6, 7, 0, 1, 2, 3, 4]);
    expect(viewOrder(4, 0)).toEqual([1, 2, 3]);
    expect(viewOrder(2, 1)).toEqual([0]);
  });

  it('관전자(mySeat<0)는 전 좌석을 본다', () => {
    for (const n of SEAT_COUNTS) {
      expect(viewOrder(n, -1)).toEqual(Array.from({ length: n }, (_, i) => i));
    }
  });

  it('범위 밖 mySeat 도 전 좌석으로 폴백한다 (방어)', () => {
    expect(viewOrder(4, 9)).toEqual([0, 1, 2, 3]);
    expect(viewOrder(0, 0)).toEqual([]);
  });
});

describe('seatMinWidth — 인원이 늘면 좁아진다', () => {
  it('경계값', () => {
    expect(seatMinWidth(2)).toBe('132px');
    expect(seatMinWidth(4)).toBe('132px');
    expect(seatMinWidth(5)).toBe('116px');
    expect(seatMinWidth(6)).toBe('116px');
    expect(seatMinWidth(7)).toBe('100px');
    expect(seatMinWidth(8)).toBe('100px');
  });

  it('단조 감소 (넓어지는 구간이 없다)', () => {
    const px = (s: string) => Number(s.replace('px', ''));
    for (let n = 3; n <= 8; n++) {
      expect(px(seatMinWidth(n))).toBeLessThanOrEqual(px(seatMinWidth(n - 1)));
    }
  });
});

describe('seatAccent — 8좌석이 서로 다른 색', () => {
  it('0~7 이 전부 구별된다', () => {
    const accents = Array.from({ length: 8 }, (_, i) => seatAccent(i));
    expect(new Set(accents).size).toBe(8);
  });

  it('음수·초과 좌석도 안전하게 감싼다', () => {
    expect(seatAccent(8)).toBe(seatAccent(0));
    expect(seatAccent(-1)).toBe(seatAccent(7));
  });
});

describe('leadSuitOf — 지연 확정 3갈래 (§6.1, §13-⑤)', () => {
  it('빈 트릭은 미확정', () => {
    expect(leadSuitOf([])).toBeNull();
  });

  it('색상 카드로 리드하면 즉시 확정되고 이후 카드가 바꾸지 못한다', () => {
    expect(leadSuitOf([played(0, suit('GREEN', 5))])).toBe('GREEN');
    expect(
      leadSuitOf([
        played(0, suit('GREEN', 5)),
        played(1, suit('BLACK', 14)),
        played(2, suit('YELLOW', 1)),
      ]),
    ).toBe('GREEN');
  });

  it('캐릭터로 리드하면 영구히 리드 수트가 없다', () => {
    for (const k of ['PIRATE', 'MERMAID', 'SKULL_KING'] as const) {
      expect(
        leadSuitOf([played(0, special(k)), played(1, suit('GREEN', 5))]),
        `${k} 리드`,
      ).toBeNull();
    }
    // 해적 선언 티그리스도 캐릭터다.
    expect(
      leadSuitOf([
        played(0, special('TIGRESS'), 'PIRATE'),
        played(1, suit('GREEN', 5)),
      ]),
    ).toBeNull();
  });

  it('탈출로 리드하면 확정이 뒤로 밀린다', () => {
    expect(leadSuitOf([played(0, special('ESCAPE'))])).toBeNull();
    expect(
      leadSuitOf([played(0, special('ESCAPE')), played(1, suit('PURPLE', 7))]),
    ).toBe('PURPLE');
    // 탈출 선언 티그리스도 탈출처럼 밀린다.
    expect(
      leadSuitOf([
        played(0, special('TIGRESS'), 'ESCAPE'),
        played(1, suit('YELLOW', 3)),
      ]),
    ).toBe('YELLOW');
  });

  /** §13-⑤ — 원문이 답하지 않는 "탈출 → 캐릭터 → 색상" 연쇄. */
  it('탈출 리드 뒤 캐릭터가 끼어도 그 다음 색상 카드가 리드 수트가 된다', () => {
    expect(
      leadSuitOf([
        played(0, special('ESCAPE')),
        played(1, special('PIRATE')),
        played(2, suit('GREEN', 6)),
      ]),
    ).toBe('GREEN');
  });

  it('색상 카드가 하나도 없으면 끝까지 미확정', () => {
    expect(
      leadSuitOf([
        played(0, special('ESCAPE')),
        played(1, special('ESCAPE')),
        played(2, special('PIRATE')),
      ]),
    ).toBeNull();
  });
});

describe('isLegalPlayHint — follow 의무 (§6.2)', () => {
  const hand = [suit('GREEN', 3), suit('GREEN', 9), suit('YELLOW', 2), special('PIRATE')];

  it('리드 수트가 미확정이면 아무 카드나 (양성 3)', () => {
    expect(isLegalPlayHint(suit('YELLOW', 2), hand, null)).toBe(true);
    expect(isLegalPlayHint(suit('GREEN', 3), hand, null)).toBe(true);
    expect(isLegalPlayHint(special('PIRATE'), hand, null)).toBe(true);
  });

  it('특수 카드는 리드 수트를 들고 있어도 항상 합법 (양성 3)', () => {
    expect(isLegalPlayHint(special('PIRATE'), hand, 'GREEN')).toBe(true);
    expect(isLegalPlayHint(special('ESCAPE'), hand, 'GREEN')).toBe(true);
    expect(isLegalPlayHint(special('TIGRESS'), hand, 'GREEN')).toBe(true);
  });

  it('리드 수트와 같은 색은 합법', () => {
    expect(isLegalPlayHint(suit('GREEN', 3), hand, 'GREEN')).toBe(true);
    expect(isLegalPlayHint(suit('GREEN', 9), hand, 'GREEN')).toBe(true);
  });

  it('리드 수트를 들고 있으면 다른 색은 불법 (음성 3 — 검정 포함)', () => {
    expect(isLegalPlayHint(suit('YELLOW', 2), hand, 'GREEN')).toBe(false);
    expect(isLegalPlayHint(suit('BLACK', 14), hand, 'GREEN')).toBe(false);
    expect(isLegalPlayHint(suit('PURPLE', 1), hand, 'GREEN')).toBe(false);
  });

  it('리드 수트가 손에 없으면 아무 색이나 합법', () => {
    const voidInGreen = [suit('YELLOW', 2), suit('BLACK', 5)];
    expect(isLegalPlayHint(suit('YELLOW', 2), voidInGreen, 'GREEN')).toBe(true);
    expect(isLegalPlayHint(suit('BLACK', 5), voidInGreen, 'GREEN')).toBe(true);
  });
});
