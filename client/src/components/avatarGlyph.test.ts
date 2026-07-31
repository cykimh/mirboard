import { describe, expect, it } from 'vitest';
import { ANIMALS, animalFor } from './avatarGlyph';

/**
 * D-103 에서 `SeatAvatar` 밖으로 추출한 결과를 고정한다. `SeatAvatar` 전용 테스트가 없고
 * `GameTable.test` 는 렌더만 하므로, 추출 회귀(순서 뒤바뀜·모듈로 변경)를 잡을 유일한 수단이다.
 * 배열 순서가 곧 사용자별 배정 결과라 순서가 바뀌면 기존 사용자 아바타가 전부 변한다.
 */
describe('avatarGlyph — 추출 전 동작 고정', () => {
  it('동물 풀은 16종이고 순서가 고정이다', () => {
    expect(ANIMALS).toHaveLength(16);
    expect([...ANIMALS]).toEqual([
      '🐶', '🐱', '🦊', '🐼', '🐯', '🦁', '🐸', '🐵',
      '🐰', '🐻', '🐨', '🐷', '🐹', '🐧', '🐢', '🐙',
    ]);
  });

  it('userId 를 16으로 나눈 나머지로 배정한다', () => {
    expect(animalFor(0, 0)).toBe('🐶');
    expect(animalFor(2, 0)).toBe('🦊');
    expect(animalFor(15, 0)).toBe('🐙');
    expect(animalFor(16, 0)).toBe('🐶'); // 한 바퀴
    expect(animalFor(19, 0)).toBe('🐼');
  });

  it('userId 가 없으면 좌석으로 폴백한다', () => {
    expect(animalFor(undefined, 3)).toBe(ANIMALS[3]);
    expect(animalFor(undefined, 0)).toBe(ANIMALS[0]);
  });

  it('음수 id 도 안전하다 (절대값)', () => {
    expect(animalFor(-2, 0)).toBe('🦊');
    expect(animalFor(undefined, -1)).toBe(ANIMALS[1]);
  });

  it('같은 userId 는 좌석이 달라도 같은 글리프 — 게임 간 일관성의 근거', () => {
    expect(animalFor(42, 0)).toBe(animalFor(42, 7));
  });
});
