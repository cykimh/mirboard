import { describe, expect, it } from 'vitest';
import { playerCountLabel } from './playerCountLabel';

describe('playerCountLabel (D-196)', () => {
  it('이어진 인원은 "최소~최대인"', () => {
    expect(playerCountLabel({ minPlayers: 2, maxPlayers: 6, playerCounts: [2, 3, 4, 5, 6] })).toBe('2~6인');
  });

  it('한 가지 인원은 "N인"', () => {
    expect(playerCountLabel({ minPlayers: 4, maxPlayers: 4, playerCounts: [4] })).toBe('4인');
  });

  it('끊긴 인원은 "·" 로 잇는다 — 오목은 2인 또는 4인', () => {
    expect(playerCountLabel({ minPlayers: 2, maxPlayers: 4, playerCounts: [2, 4] })).toBe('2·4인');
  });

  it('playerCounts 가 없으면(구 서버·픽스처) min..max 로 본다', () => {
    expect(playerCountLabel({ minPlayers: 1, maxPlayers: 4 })).toBe('1~4인');
    expect(playerCountLabel({ minPlayers: 4, maxPlayers: 4, playerCounts: [] })).toBe('4인');
  });
});
