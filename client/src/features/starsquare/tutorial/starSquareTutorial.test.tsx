import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { tutorialFor } from '@/features/tutorial/gameTutorials';
import { STAR_SQUARE_TUTORIAL_STEPS } from './starSquareTutorialSteps';

/** 룰을 바꾸면 여기가 먼저 빨개진다 — 핵심 문구를 붙잡는다(`docs/rules-starsquare.md`). */
describe('별자리 수판 튜토리얼', () => {
  const text = (i: number) => {
    const { container, unmount } = render(<>{STAR_SQUARE_TUTORIAL_STEPS[i].body}</>);
    const t = container.textContent ?? '';
    unmount();
    return t;
  };

  it('레지스트리에 등록돼 있다', () => {
    expect(tutorialFor('STAR_SQUARE')?.seenKey).toBe('mirboard.tutorial.starsquare.seen.v1');
    expect(tutorialFor('STAR_SQUARE')?.bodyClassName?.split(' ')).toContain('sq-tokens');
  });

  it('여섯 단계 — 합 공식·단서·옮기기·줄 합 칩·30초 마감·점수', () => {
    expect(STAR_SQUARE_TUTORIAL_STEPS).toHaveLength(6);
    const all = STAR_SQUARE_TUTORIAL_STEPS.map((_, i) => text(i)).join('\n');
    for (const phrase of ['1부터 N²까지 한 번씩', '15', '34', '65', '단서 별', '어떤 마방진이든 정답', '이 칸으로 옮겨져요',
      '▽ 모자람', '△ 넘침', '30초', '남의 판 숫자는 보이지 않아요', '맞은 줄 → 채운 칸', '혼자 하기']) {
      expect(all).toContain(phrase);
    }
  });
});
