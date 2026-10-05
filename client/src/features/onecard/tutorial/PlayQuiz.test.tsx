/* eslint-disable @typescript-eslint/ban-ts-comment */
// @ts-ignore — 클라 tsconfig 에 @types/node 가 없다. vitest 는 Node 에서 돌아 런타임은 안전하다.
import { readFileSync } from 'node:fs';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PLAY_QUESTIONS, PlayQuiz } from './PlayQuiz';
import { canPlay } from '../onecardRules';

/**
 * D-129 — '낼 수 있을까?' 연습. 정답은 정적 데이터지만, 문제마다 클라 규칙 미러(`canPlay`)와 대조해 룰이 바뀌면
 * 여기서 먼저 빨개진다.
 */

function readSource(relative: string): string {
  const read = readFileSync as unknown as (p: URL, enc: string) => string;
  return read(new URL(relative, import.meta.url), 'utf-8');
}

const status = () => screen.getByRole('status');

describe('PlayQuiz', () => {
  it('정답 데이터가 클라 규칙 미러와 일치한다', () => {
    for (const q of PLAY_QUESTIONS) {
      expect(canPlay(q.candidate, q.top, q.declaredSuit, q.attackStack), q.explain).toBe(q.answer);
    }
  });

  it('낼 수 있는 경우와 없는 경우를 모두 묻는다', () => {
    expect(PLAY_QUESTIONS.some((q) => q.answer)).toBe(true);
    expect(PLAY_QUESTIONS.some((q) => !q.answer)).toBe(true);
    expect(PLAY_QUESTIONS.some((q) => q.attackStack > 0)).toBe(true);
  });

  it('맞히면 정답과 해설, 틀리면 바른 답과 해설을 보여 준다', () => {
    render(<PlayQuiz />);
    expect(status()).toHaveTextContent('스페이드 9, 지금 낼 수 있을까요?');

    fireEvent.click(screen.getByRole('button', { name: '낼 수 있다' }));
    expect(status()).toHaveTextContent('정답! 숫자가 같으면');

    fireEvent.click(screen.getByRole('button', { name: '낼 수 없다' }));
    expect(status()).toHaveTextContent('아니에요 — 낼 수 있습니다.');
  });

  it('고른 답 버튼은 눌린 상태로 알린다 (aria-pressed) — 다음 문제로 가면 풀린다', () => {
    render(<PlayQuiz />);
    expect(screen.getByRole('button', { name: '낼 수 있다', pressed: false })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '낼 수 없다', pressed: false })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '낼 수 없다' }));
    expect(screen.getByRole('button', { name: '낼 수 없다', pressed: true })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '낼 수 있다', pressed: false })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '다음 문제' }));
    expect(screen.getByRole('button', { name: '낼 수 없다', pressed: false })).toBeInTheDocument();
  });

  it('다음 문제로 넘어가며 마지막 다음은 처음이다', () => {
    render(<PlayQuiz />);
    const next = () => fireEvent.click(screen.getByRole('button', { name: '다음 문제' }));

    next();
    expect(screen.getByText(`문제 2 / ${PLAY_QUESTIONS.length}`)).toBeInTheDocument();
    for (let i = 1; i < PLAY_QUESTIONS.length; i++) next();
    expect(screen.getByText(`문제 1 / ${PLAY_QUESTIONS.length}`)).toBeInTheDocument();
  });

  it('소켓·스토어를 쓰지 않는다 — 로컬 연습이다', () => {
    const source = readSource('./PlayQuiz.tsx');
    expect(source).not.toMatch(/useStompRoom|onecardStore|onecardRoomSink/);
  });
});
