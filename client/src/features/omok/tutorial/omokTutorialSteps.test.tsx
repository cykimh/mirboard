import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { OMOK_TUTORIAL_STEPS } from './omokTutorialSteps';
import { ForbiddenQuiz } from './ForbiddenQuiz';
import { tutorialFor } from '@/features/tutorial/gameTutorials';

/** 룰을 바꾸면 여기가 먼저 빨개진다 — 핵심 문구를 붙잡는다(`docs/rules-omok.md`). */
describe('오목 튜토리얼', () => {
  const text = (i: number) => {
    const { container, unmount } = render(<>{OMOK_TUTORIAL_STEPS[i].body}</>);
    const t = container.textContent ?? '';
    unmount();
    return t;
  };

  it('레지스트리에 등록돼 있다', () => {
    expect(tutorialFor('OMOK')?.seenKey).toBe('mirboard.tutorial.omok.seen.v1');
    expect(tutorialFor('OMOK')?.bodyClassName).toBe('om-tokens');
  });

  it('두 단계 확정·2:2 번갈아·룰 셋·금수·시간 초과 두 번을 설명한다', () => {
    const all = OMOK_TUTORIAL_STEPS.map((_, i) => text(i)).join('\n');
    expect(all).toContain('한 번 더 누르거나');
    expect(all).toContain('같은 색 돌을 번갈아');
    expect(all).toContain('렌주');
    expect(all).toContain('삼삼만 금지');
    expect(all).toContain('자유 오목');
    expect(all).toContain('두 번 연속');
    expect(all).toContain('5목을 만들면');
  });

  it('퀴즈: 33 자리를 고르면 정답, 아니면 이유를 보인다', () => {
    render(<ForbiddenQuiz />);
    fireEvent.click(screen.getByRole('button', { name: 'B' }));
    expect(screen.getByText(/아니에요/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'A' }));
    expect(screen.getByText(/정답!/)).toBeInTheDocument();
  });
});
