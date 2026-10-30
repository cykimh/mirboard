import { describe, expect, it } from 'vitest';
import { isGuestName, playerTiers } from './playerTiers';
import type { GameRecord } from '@/api/users';

const rec = (tier: GameRecord['tier']): GameRecord => ({
  gameType: 'ONE_CARD',
  rating: 1100,
  tier,
  winCount: 1,
  loseCount: 0,
  desertCount: 0,
});

/** D-166 — 게임판 좌석 계급: 봇·게스트는 빼고, 안 해 본 게임이면 시작 계급(미사일). */
describe('playerTiers (D-166)', () => {
  it('봇·게스트·아직 못 받은 사람은 빼고, 안 해 본 사람은 미사일', () => {
    const tiers = playerTiers(
      [1, 2, 3, 4, 5],
      [2], // 좌석 2(= userId 3)가 봇
      { 1: '철수', 2: 'guest-ab12', 4: '영희', 5: '민수' },
      { 1: rec('GOLD_STAR'), 2: rec('MISSILE'), 4: null },
    );

    expect(tiers).toEqual({ 1: 'GOLD_STAR', 4: 'MISSILE' });
  });

  it('게스트 이름 규약은 guest- 접두', () => {
    expect(isGuestName('guest-x1')).toBe(true);
    expect(isGuestName('게스트')).toBe(false);
    expect(isGuestName(undefined)).toBe(false);
  });
});
