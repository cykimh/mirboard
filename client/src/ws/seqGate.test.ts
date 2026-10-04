import { describe, expect, it } from 'vitest';
import { judgeSeq } from './seqGate';

describe('judgeSeq — 공개 이벤트 순번 판정 (D-124)', () => {
  it('바로 다음 순번은 next', () => {
    expect(judgeSeq(10, 11)).toBe('next');
  });

  it('같거나 지난 순번은 duplicate', () => {
    expect(judgeSeq(10, 10)).toBe('duplicate');
    expect(judgeSeq(10, 3)).toBe('duplicate');
  });

  it('건너뛴 순번은 gap', () => {
    expect(judgeSeq(10, 12)).toBe('gap');
    expect(judgeSeq(10, 99)).toBe('gap');
  });

  it('순번 없는 메타 이벤트는 unsequenced', () => {
    expect(judgeSeq(10, undefined)).toBe('unsequenced');
    expect(judgeSeq(10, null)).toBe('unsequenced');
  });

  it('방 진입 직후(기준점 0)에는 1 만 next', () => {
    expect(judgeSeq(0, 1)).toBe('next');
    expect(judgeSeq(0, 2)).toBe('gap');
  });
});
