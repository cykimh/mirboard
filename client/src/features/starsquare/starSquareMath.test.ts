import { describe, expect, it } from 'vitest';
import { clockText, lineSums, linesOf, looksComplete, magicSumOf, progressOf } from './starSquareMath';
import { LO_SHU } from './starSquareTestFixtures';

/** 화면용 셈 — 서버 `MagicCheck.progress` 와 같은 규칙(`docs/rules-starsquare.md` §5.1). */
describe('starSquareMath', () => {
  it('합 M 과 줄 2N+2', () => {
    expect([3, 4, 5].map(magicSumOf)).toEqual([15, 34, 65]);
    expect(linesOf(4)).toHaveLength(10);
    expect(linesOf(3)[6].cells).toEqual([0, 4, 8]); // ↘
    expect(linesOf(3)[7].cells).toEqual([2, 4, 6]); // ↙
  });

  it('줄 상태 — 비었음·채우는 중·모자람·넘침·맞음', () => {
    const states = (g: number[]) => lineSums(g, 3).map((s) => s.state);
    expect(states([0, 0, 0, 0, 0, 0, 0, 0, 0])[0]).toBe('empty');
    expect(states([2, 7, 0, 0, 0, 0, 0, 0, 0])[0]).toBe('partial');
    expect(states([2, 7, 5, 0, 0, 0, 0, 0, 0])[0]).toBe('under');
    expect(states([9, 8, 0, 0, 0, 0, 0, 0, 0])[0]).toBe('over');
    expect(states([2, 7, 6, 0, 0, 0, 0, 0, 0])[0]).toBe('ok');
    expect(states(LO_SHU).every((s) => s === 'ok')).toBe(true);
  });

  it('중복 숫자로 합만 맞춘 줄은 맞은 줄이 아니다(서버와 같은 꼼수 방지)', () => {
    const fives = [5, 5, 5, 5, 5, 5, 5, 5, 5];
    expect(progressOf(fives, 3)).toEqual({ filled: 0, lines: 0 });
    expect(lineSums(fives, 3).every((s) => s.state === 'partial')).toBe(true);
    expect(progressOf([2, 7, 6, 0, 5, 0, 0, 0, 2], 3)).toEqual({ filled: 3, lines: 0 });
  });

  it('완성처럼 보이는가', () => {
    expect(looksComplete(LO_SHU, 3)).toBe(true);
    expect(looksComplete([...LO_SHU.slice(0, 8), 0], 3)).toBe(false);
    expect(looksComplete([1, 2, 3, 4, 5, 6, 7, 8, 9], 3)).toBe(false);
    expect(looksComplete(LO_SHU, 4)).toBe(false);
  });

  it('시계 글자', () => {
    expect(clockText(0)).toBe('0:00');
    expect(clockText(61_000)).toBe('1:01');
    expect(clockText(420_000)).toBe('7:00');
    expect(clockText(-5)).toBe('0:00');
  });
});
