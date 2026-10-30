import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Toaster } from './Toaster';
import { showToast, TOAST_MS, useToastStore } from './toastStore';

/** D-157 — 버튼을 누른 뒤의 짧은 안내는 버튼 줄이 아니라 화면 위 토스트로 뜬다. */
describe('Toaster', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    useToastStore.getState().clear();
    vi.useRealTimers();
  });

  it('라이브 영역은 처음부터 비어 있는 채로 있다', () => {
    render(<Toaster />);

    expect(screen.getByRole('status').textContent).toBe('');
  });

  it('띄운 문구를 보여 주고 잠시 뒤 내린다', () => {
    render(<Toaster />);

    act(() => showToast('링크를 복사했어요'));
    expect(screen.getByRole('status')).toHaveTextContent('링크를 복사했어요');

    act(() => vi.advanceTimersByTime(TOAST_MS));
    expect(screen.getByRole('status').textContent).toBe('');
  });

  it('오류 토스트는 오류 색으로 그린다', () => {
    render(<Toaster />);

    act(() => showToast('마이크를 쓸 수 없어요', 'error'));
    expect(screen.getByText('마이크를 쓸 수 없어요')).toHaveClass('bg-destructive');
  });

  it('같은 문구를 다시 띄우면 쌓지 않고 시간만 다시 잰다', () => {
    render(<Toaster />);

    act(() => showToast('링크를 복사했어요'));
    act(() => vi.advanceTimersByTime(TOAST_MS - 100));
    act(() => showToast('링크를 복사했어요'));
    expect(screen.getAllByText('링크를 복사했어요')).toHaveLength(1);

    act(() => vi.advanceTimersByTime(TOAST_MS - 100));
    expect(screen.getByText('링크를 복사했어요')).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(100));
    expect(screen.queryByText('링크를 복사했어요')).toBeNull();
  });

  it('한 번에 셋까지만 보이고 넘치면 오래된 것부터 내린다', () => {
    render(<Toaster />);

    act(() => ['하나', '둘', '셋', '넷'].forEach((m) => showToast(m)));

    expect(screen.queryByText('하나')).toBeNull();
    expect(screen.getByText('넷')).toBeInTheDocument();
    expect(useToastStore.getState().toasts).toHaveLength(3);
  });
});
