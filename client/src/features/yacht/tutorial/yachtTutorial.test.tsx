import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { YACHT_TUTORIAL, YACHT_TUTORIAL_STEPS } from './yachtTutorial';
import { QUESTIONS, ScoreQuiz } from './ScoreQuiz';
import { CATEGORY_LABEL } from '../yachtLabels';

/** 룰(`docs/rules-yacht.md`)의 핵심 수치를 붙잡는다 — 룰을 바꾸면 여기가 먼저 빨개진다. */
describe('얏추(요트) 튜토리얼', () => {
  it('D-207 — 게임 이름은 얏추, 다섯 개 같은 눈 칸 이름은 요트 그대로', () => {
    expect(YACHT_TUTORIAL_STEPS[0].title).toBe('얏추에 오신 걸 환영합니다');
    expect(YACHT_TUTORIAL.description).toBe('얏추 기본 규칙 안내 튜토리얼');
    expect(CATEGORY_LABEL.YACHT).toBe('요트');
    const { container } = render(<div>{YACHT_TUTORIAL_STEPS[0].body}</div>);
    expect(container.textContent).toContain('얏추는 1~4명');
    expect(container.textContent).not.toContain('요트');
  });

  it('열람 키·포털 토큰 클래스가 게임 전용이다', () => {
    expect(YACHT_TUTORIAL.seenKey).toBe('mirboard.tutorial.yacht.seen.v1');
    expect(YACHT_TUTORIAL.bodyClassName).toBe('yt-tokens');
  });

  it('핵심 수치를 말한다 — 1~4명·12라운드·3번·63/+35·15/30/50', () => {
    const { container } = render(
      <div>
        {YACHT_TUTORIAL_STEPS.map((s) => (
          <section key={s.title}>{s.body}</section>
        ))}
      </div>,
    );
    const text = container.textContent ?? '';
    for (const needle of ['1~4명', '12라운드', '최대 3번', '63점 이상', '+35점', '15점', '30점', '50점', '0점']) {
      expect(text, needle).toContain(needle);
    }
    expect(text).toContain('5개 모두 같아도 됨');
  });

  it('퀴즈 정답은 그 문제에서 가장 점수가 높은 보기다', () => {
    for (const q of QUESTIONS) {
      const best = q.options.find((o) => o.category === q.best);
      expect(best).toBeDefined();
    }
    render(<ScoreQuiz />);
    fireEvent.click(screen.getByRole('button', { name: '풀 하우스 19점' }));
    expect(screen.getByText(/정답!/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '다음 문제' }));
    fireEvent.click(screen.getByRole('button', { name: '스몰 스트레이트 15점' }));
    expect(screen.getByText(/아쉬워요/)).toBeInTheDocument();
  });
});
