import { describe, expect, it } from 'vitest';
import { primaryGame } from './primaryGame';
import type { GameRecord } from '@/api/users';

const rec = (gameType: string, win: number, lose: number, rating = 1000): GameRecord => ({
  gameType,
  rating,
  tier: 'BRONZE',
  winCount: win,
  loseCount: lose,
  desertCount: 0,
});

describe('primaryGame (D-115 — 헤더 티어 배지의 기준 게임)', () => {
  it('가장 많이 한 게임을 고른다', () => {
    expect(primaryGame([rec('TICHU', 1, 1), rec('SKULL_KING', 3, 2)])?.gameType).toBe('SKULL_KING');
  });

  it('판 수가 같으면 목록 순서(서버 정렬)대로 앞의 것', () => {
    expect(primaryGame([rec('SKULL_KING', 1, 0), rec('TICHU', 0, 1)])?.gameType).toBe('SKULL_KING');
  });

  it('한 판도 안 했으면 없음', () => {
    expect(primaryGame([])).toBeNull();
  });
});
