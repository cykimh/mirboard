import { describe, expect, it } from 'vitest';
import { BOARD_SIZE, TILES, gridOf, partnerOf } from './board';
import { describe as describeEntry } from './describe';

describe('board — 서버 판 표의 표시용 사본 (룰 §2·§6.3)', () => {
  it('24칸이고 모서리·편지·세관이 룰 정본 자리에 있다', () => {
    expect(TILES).toHaveLength(BOARD_SIZE);
    expect([0, 6, 9, 12, 18].map((t) => TILES[t].kind)).toEqual(['START', 'WHIRLPOOL', 'CUSTOMS', 'FESTIVAL', 'STATION']);
    expect([3, 15, 21].map((t) => TILES[t].kind)).toEqual(['LETTER', 'LETTER', 'LETTER']);
    expect(TILES.filter((t) => t.kind === 'LAND')).toHaveLength(16);
  });

  it('대표 수치가 룰 정본과 같다', () => {
    expect(TILES[1]).toMatchObject({ name: '이끼 나루', price: 60, buildCost: 30, tolls: [10, 40, 80, 170] });
    expect(TILES[23]).toMatchObject({ name: '구름왕관 성채', price: 240, buildCost: 120, tolls: [50, 140, 340, 670] });
  });

  it('섬마다 짝 칸이 하나다', () => {
    expect(partnerOf(1)).toBe(2);
    expect(partnerOf(23)).toBe(22);
    expect(partnerOf(0)).toBe(-1);
  });

  it('7×7 테두리에 겹치지 않게 놓이고, 출발은 아래 오른쪽 · 진행은 시계 방향이다', () => {
    const cells = new Set(TILES.map((t) => JSON.stringify(gridOf(t.id))));
    expect(cells.size).toBe(BOARD_SIZE);
    for (const t of TILES) {
      const { row, col } = gridOf(t.id);
      expect(row === 0 || row === 6 || col === 0 || col === 6).toBe(true);
    }
    expect(gridOf(0)).toEqual({ row: 6, col: 6 });
    expect(gridOf(6)).toEqual({ row: 6, col: 0 });
    expect(gridOf(12)).toEqual({ row: 0, col: 0 });
    expect(gridOf(18)).toEqual({ row: 0, col: 6 });
    // 이웃 칸은 격자에서도 이웃이다(한 칸씩).
    for (let t = 0; t < BOARD_SIZE; t++) {
      const a = gridOf(t);
      const b = gridOf((t + 1) % BOARD_SIZE);
      expect(Math.abs(a.row - b.row) + Math.abs(a.col - b.col)).toBe(1);
    }
  });
});

describe('describe — 사건 줄 문장', () => {
  const nameOf = (seat: number) => ['나', '민'][seat];
  it.each([
    ['TOLL_PAID', { payer: 0, owner: 1, tile: 7, amount: 140, monopoly: true, festival: false, payerCash: 0, ownerCash: 0 }, '나 → 민 버섯 숲길 통행료 140G (독점)'],
    ['LAND_BOUGHT', { seat: 1, tile: 4, level: 2, paid: 160, cash: 0 }, '민 조개 모래톱 구매 + 상점 (160G)'],
    ['LETTER_DRAWN', { seat: 0, letter: 'GUST', deckCount: 9, reshuffled: false }, '나 바람 편지 「등 떠미는 바람」 — 2칸 앞으로'],
    ['PLAYER_ELIMINATED', { seat: 1, reason: 'BANKRUPT', creditor: 0, returnedTiles: [4, 5], cashForfeited: 0 }, '민 파산 — 땅 2칸이 은행으로'],
    ['TOKEN_MOVED', { seat: 0, from: 20, to: 1, kind: 'ROLL', salary: 200, cash: 0 }, '나 이끼 나루(으)로 · 급여 200G'],
  ])('%s', (type, payload, text) => {
    expect(describeEntry({ id: 1, type, payload }, nameOf)).toBe(text);
  });

  it('모르는 종류와 0G 변화는 줄을 만들지 않는다', () => {
    expect(describeEntry({ id: 1, type: 'TURN_CHANGED', payload: {} }, nameOf)).toBeNull();
    expect(describeEntry({ id: 1, type: 'MONEY_CHANGED', payload: { seat: 0, delta: 0, reason: 'CUSTOMS', cash: 0 } }, nameOf)).toBeNull();
  });
});
