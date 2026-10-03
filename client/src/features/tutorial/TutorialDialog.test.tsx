import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { TutorialDialog } from './TutorialDialog';
import type { GameTutorial } from './types';

/** D-121 — 게임 중립 다이얼로그. 단계 내용은 주입받으므로 임의 2단계로 껍데기만 검증한다. */
const TUTORIAL: GameTutorial = {
  steps: [
    { title: '첫 단계', body: <p>하나</p>, source: '§1' },
    { title: '둘째 단계', body: <p>둘</p> },
  ],
  description: '테스트 게임 규칙 안내',
  seenKey: 'test.tutorial.seen',
  bodyClassName: 'sk-tokens',
};

describe('TutorialDialog', () => {
  it('닫혀 있으면 아무것도 그리지 않는다', () => {
    render(<TutorialDialog tutorial={TUTORIAL} open={false} onClose={() => {}} />);
    expect(screen.queryByText('첫 단계')).toBeNull();
  });

  it('주입한 단계를 순서대로 넘기고, 마지막 단계의 시작하기가 onClose 를 부른다', () => {
    const onClose = vi.fn();
    render(<TutorialDialog tutorial={TUTORIAL} open onClose={onClose} />);

    expect(screen.getByRole('heading', { name: '첫 단계' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '이전' })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: '다음' }));
    expect(screen.getByRole('heading', { name: '둘째 단계' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '다음' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '시작하기' }));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('닫았다 다시 열면 처음 단계부터 보인다', () => {
    const { rerender } = render(<TutorialDialog tutorial={TUTORIAL} open onClose={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: '다음' }));
    expect(screen.getByRole('heading', { name: '둘째 단계' })).toBeInTheDocument();

    rerender(<TutorialDialog tutorial={TUTORIAL} open={false} onClose={() => {}} />);
    rerender(<TutorialDialog tutorial={TUTORIAL} open onClose={() => {}} />);
    expect(screen.getByRole('heading', { name: '첫 단계' })).toBeInTheDocument();
  });

  it('description 은 화면에 안 보이는 접근성 설명으로 렌더된다', () => {
    render(<TutorialDialog tutorial={TUTORIAL} open onClose={() => {}} />);
    const desc = screen.getByText('테스트 게임 규칙 안내');
    expect(desc).toHaveClass('sr-only');
    // source 는 근거 인용일 뿐 화면에 그리지 않는다.
    expect(screen.queryByText('§1')).toBeNull();
  });

  it('bodyClassName 이 스크롤 본문(.tutorial-body)에 붙는다 — 포털 안에서 게임 토큰을 푸는 지점', () => {
    render(<TutorialDialog tutorial={TUTORIAL} open onClose={() => {}} />);
    const body = document.querySelector('.tutorial-body');
    expect(body).not.toBeNull();
    expect(body).toHaveClass('sk-tokens');
    expect(body).toHaveClass('overflow-y-auto');
  });
});
