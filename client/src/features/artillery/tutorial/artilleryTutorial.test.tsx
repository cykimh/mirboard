import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ARTILLERY_TUTORIAL, ARTILLERY_TUTORIAL_STEPS } from './artilleryTutorial';

/** 룰(`docs/rules-artillery.md`)의 핵심 수치를 붙잡는다 — 룰을 바꾸면 여기가 먼저 빨개진다. */
describe('씨앗포 튜토리얼', () => {
  it('열람 키가 게임 전용이다', () => {
    expect(ARTILLERY_TUTORIAL.seenKey).toBe('mirboard.tutorial.artillery.seen.v1');
  });

  it('핵심 수치를 말한다 — 2~4명·연료 100·탄 30/45·2발·20칸·10번씩', () => {
    const { container } = render(
      <div>
        {ARTILLERY_TUTORIAL_STEPS.map((s) => (
          <section key={s.title}>{s.body}</section>
        ))}
      </div>,
    );
    const text = container.textContent ?? '';
    for (const needle of ['2~4명', '−10~10', '100', '최대 30', '최대 45', '한 판에 2발', '20칸', '10번씩', '넘어갑니다']) {
      expect(text, needle).toContain(needle);
    }
  });
});
