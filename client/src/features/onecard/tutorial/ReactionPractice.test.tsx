/* eslint-disable @typescript-eslint/ban-ts-comment */
// @ts-ignore — 클라 tsconfig 에 @types/node 가 없다. vitest 는 Node 에서 돌아 런타임은 안전하다.
import { readFileSync } from 'node:fs';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  PRACTICE_DELAY_SPAN_MS,
  PRACTICE_MIN_DELAY_MS,
  PRACTICE_WINDOW_MS,
  ReactionPractice,
} from './ReactionPractice';
import { raceLeftCss } from '../raceSlots';

/** D-129 — '원카드!' 반응 연습. 무작위 대기 뒤 슬롯 표의 무작위 자리에 버튼이 뜨고, 누르기까지의 시간을 보여 준다. */

function readSource(relative: string): string {
  const read = readFileSync as unknown as (p: URL, enc: string) => string;
  return read(new URL(relative, import.meta.url), 'utf-8');
}

const status = () => screen.getByRole('status');

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
  vi.setSystemTime(0);
});

afterEach(() => {
  vi.useRealTimers();
});

describe('ReactionPractice', () => {
  it('시작하면 대기 뒤 버튼이 뜨고, 누르면 반응 시간을 보여 준다', () => {
    render(<ReactionPractice random={() => 0.5} />);

    fireEvent.click(screen.getByRole('button', { name: '시작' }));
    expect(status()).toHaveTextContent('곧 나타납니다');
    expect(screen.queryByRole('button', { name: '원카드!' })).toBeNull();

    act(() => {
      vi.advanceTimersByTime(PRACTICE_MIN_DELAY_MS + PRACTICE_DELAY_SPAN_MS / 2);
    });
    const button = screen.getByRole('button', { name: '원카드!' });
    expect(document.activeElement).not.toBe(button);

    act(() => {
      vi.advanceTimersByTime(432);
    });
    fireEvent.click(button);

    expect(status()).toHaveTextContent('반응 시간 432ms');
    expect(screen.getByRole('button', { name: '다시' })).toBeEnabled();
  });

  it('버튼은 칸 가장자리에서 버튼 반폭만큼 안쪽으로 보정된 자리에 뜬다 — 경쟁 레이어와 같은 clamp', () => {
    render(<ReactionPractice random={() => 0} />);

    fireEvent.click(screen.getByRole('button', { name: '시작' }));
    act(() => {
      vi.advanceTimersByTime(PRACTICE_MIN_DELAY_MS);
    });

    // random 이 0 이면 슬롯 0(22%) 에 지터 −100(−10%) — 칸 왼쪽 끝에 가장 가까운 12%.
    expect(screen.getByRole('button', { name: '원카드!' }).style.left).toBe(raceLeftCss(12));
  });

  it('3초 안에 안 누르면 실제 창처럼 닫힌다', () => {
    render(<ReactionPractice random={() => 0} />);

    fireEvent.click(screen.getByRole('button', { name: '시작' }));
    act(() => {
      vi.advanceTimersByTime(PRACTICE_MIN_DELAY_MS);
    });
    expect(screen.getByRole('button', { name: '원카드!' })).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(PRACTICE_WINDOW_MS);
    });
    expect(screen.queryByRole('button', { name: '원카드!' })).toBeNull();
    expect(status()).toHaveTextContent('3초가 지났습니다');
  });

  it('대기·표시 중에는 다시 시작할 수 없다', () => {
    render(<ReactionPractice random={() => 0} />);

    fireEvent.click(screen.getByRole('button', { name: '시작' }));
    expect(screen.getByRole('button', { name: '다시' })).toBeDisabled();
  });

  it('소켓·스토어를 쓰지 않는다 — 로컬 연습이다', () => {
    const source = readSource('./ReactionPractice.tsx');
    expect(source).not.toMatch(/useStompRoom|onecardStore|onecardRoomSink/);
  });
});
