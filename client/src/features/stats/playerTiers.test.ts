import { describe, expect, it } from 'vitest';
import { gameRecordOf, isGuestName, playerTiers } from './playerTiers';
import type { GameRecord, UserStats } from '@/api/users';

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

/** D-198 — 명예 계정(`fixedTier`)은 안 해 본 게임에도 그 계급 줄이 생긴다. */
describe('gameRecordOf (D-198)', () => {
  const stats = (games: GameRecord[], fixedTier?: UserStats['fixedTier']): UserStats => ({
    userId: 1,
    username: '창엽2',
    winCount: 0,
    loseCount: 0,
    rating: 1000,
    tier: 'MISSILE',
    desertCount: 0,
    games,
    fixedTier,
  });

  it('보통 계정은 그 게임 줄 그대로, 없으면 null', () => {
    expect(gameRecordOf(stats([rec('GOLD_STAR')]), 'ONE_CARD')?.tier).toBe('GOLD_STAR');
    expect(gameRecordOf(stats([rec('GOLD_STAR')]), 'YACHT')).toBeNull();
    expect(gameRecordOf(stats([rec('GOLD_STAR')], null), 'YACHT')).toBeNull();
  });

  it('명예 계정은 있는 줄도 없는 줄도 고정 계급', () => {
    expect(gameRecordOf(stats([rec('MISSILE')], 'STAR_CROWN'), 'ONE_CARD')).toMatchObject({
      tier: 'STAR_CROWN',
      winCount: 1,
    });
    const none = gameRecordOf(stats([], 'STAR_CROWN'), 'YACHT');
    expect(none).toMatchObject({ gameType: 'YACHT', tier: 'STAR_CROWN', winCount: 0, loseCount: 0 });
    expect(playerTiers([1], [], { 1: '창엽2' }, { 1: none })).toEqual({ 1: 'STAR_CROWN' });
  });
});
