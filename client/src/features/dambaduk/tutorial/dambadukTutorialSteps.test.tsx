import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DAMBADUK_TUTORIAL_STEPS } from './dambadukTutorialSteps';
import { tutorialFor } from '@/features/tutorial/gameTutorials';

/** 룰을 바꾸면 여기가 먼저 빨개진다 — 핵심 문구를 붙잡는다(`docs/rules-dambaduk.md`). */
describe('담바둑 튜토리얼', () => {
  const text = (i: number) => {
    const { container, unmount } = render(<>{DAMBADUK_TUTORIAL_STEPS[i].body}</>);
    const t = container.textContent ?? '';
    unmount();
    return t;
  };

  it('레지스트리에 등록돼 있고 판 토큰을 푼다', () => {
    expect(tutorialFor('DAMBADUK')?.seenKey).toBe('mirboard.tutorial.dambaduk.seen.v1');
    expect(tutorialFor('DAMBADUK')?.bodyClassName?.split(' ')).toContain('db-tokens');
  });

  it('두 돌·배치 순서·옮기고 담·집과 동점·끝·담 넘기를 설명한다', () => {
    const all = DAMBADUK_TUTORIAL_STEPS.map((_, i) => text(i)).join('\n');
    expect(all).toContain('한 방향으로 1칸 또는 2칸');
    expect(all).toContain('왼쪽이나 오른쪽으로 꺾어');
    expect(all).toContain('흑 → 백 → 백 → 흑');
    expect(all).toContain('흑 → 백 → 청 → 청 → 백 → 흑');
    expect(all).toContain('반드시 담');
    expect(all).toContain('가장 큰 집');
    expect(all).toContain('섞인 터가 하나도 없어지면');
    expect(all).toContain('두 번 연속');
    expect(all).toContain('한 판에 한 번');
  });

  it('판 그림은 누를 수 없는 작은 그림이고 갈 수 있는 칸을 표시한다', () => {
    const { container } = render(<>{DAMBADUK_TUTORIAL_STEPS[1].body}</>);
    expect(container.querySelectorAll('.db-board-mini')).toHaveLength(2);
    expect(container.querySelectorAll('button')).toHaveLength(0);
    expect(container.querySelectorAll('.db-mark')).toHaveLength(11);
  });
});
