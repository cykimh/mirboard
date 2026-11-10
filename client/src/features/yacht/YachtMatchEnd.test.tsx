import { describe, expect, it } from 'vitest';
import { bestLine } from './YachtMatchEnd';
import type { MatchResult } from '@/types/yacht';

const solo = (total: number, reason: MatchResult['reason'] = 'FINISHED', deserted = false): MatchResult => ({
  reason,
  placements: [{ seat: 0, rank: 1, total, deserted }],
});

describe('bestLine (D-201 — 종료 화면의 최고 기록)', () => {
  it('처음이면 첫 기록, 넘으면 새 기록, 못 넘으면 내 최고', () => {
    expect(bestLine(solo(180), 0, null, false)).toEqual({ text: '첫 최고 기록 180점!', record: true });
    expect(bestLine(solo(200), 0, 180, false)).toEqual({ text: '새 최고 기록! (이전 180점)', record: true });
    expect(bestLine(solo(180), 0, 180, false)).toEqual({ text: '내 최고 180점', record: false });
  });

  it('끝까지 안 친 판·탈주는 기록이 아니다', () => {
    expect(bestLine(solo(300, 'ACTION_LIMIT'), 0, 180, false)).toEqual({ text: '내 최고 180점', record: false });
    expect(bestLine(solo(300, 'FINISHED', true), 0, null, false)).toBeNull();
  });

  it('게스트는 안내만, 관전자·모름은 없음', () => {
    expect(bestLine(solo(180), 0, undefined, true)?.text).toBe('게스트는 최고 기록이 남지 않아요.');
    expect(bestLine(solo(180), -1, 100, false)).toBeNull();
    expect(bestLine(solo(180), 0, undefined, false)).toBeNull();
  });
});
