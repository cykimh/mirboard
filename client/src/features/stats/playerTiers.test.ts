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

/** D-305 — 계급은 통합 하나(`stats.tier`): 어느 게임 줄이든, 안 해 본 게임이든 같은 계급이다. */
describe('gameRecordOf (D-305)', () => {
  const stats = (games: GameRecord[], tier: UserStats['tier'], username = '철수'): UserStats => ({
    userId: 1,
    username,
    winCount: 0,
    loseCount: 0,
    rating: 1130,
    tier,
    desertCount: 0,
    games,
  });

  it('해 본 게임 줄은 전적 그대로 + 통합 계급', () => {
    expect(gameRecordOf(stats([rec('MISSILE')], 'GOLD_STAR'), 'ONE_CARD')).toMatchObject({
      tier: 'GOLD_STAR',
      winCount: 1,
    });
  });

  it('안 해 본 게임도 0승 0패 줄에 통합 계급 — 좌석·대기실 카드가 같은 계급을 그린다', () => {
    const none = gameRecordOf(stats([rec('GOLD_STAR')], 'GOLD_STAR'), 'YACHT');
    expect(none).toMatchObject({ gameType: 'YACHT', tier: 'GOLD_STAR', winCount: 0, loseCount: 0 });
    expect(playerTiers([1], [], { 1: '철수' }, { 1: none })).toEqual({ 1: 'GOLD_STAR' });
  });

  it('게스트는 계급이 없다 — 줄이 없으면 null', () => {
    expect(gameRecordOf(stats([], 'MISSILE', 'guest-ab12'), 'YACHT')).toBeNull();
  });
});
