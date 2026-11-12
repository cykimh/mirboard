import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SLOW_NOTICE_MS, useSlowNotice } from './useSlowNotice';

/** D-197 — 요청이 2초를 넘길 때만 true, 끝나면 타이머를 남기지 않는다. */
describe('useSlowNotice (D-197)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('진행 중이 아니면 타이머를 걸지 않는다', () => {
    const { result } = renderHook(() => useSlowNotice(false));
    expect(result.current).toBe(false);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('2초를 넘기면 true 로 바뀐다', () => {
    const { result } = renderHook(() => useSlowNotice(true));
    expect(SLOW_NOTICE_MS).toBe(2000);
    act(() => void vi.advanceTimersByTime(1999));
    expect(result.current).toBe(false);
    act(() => void vi.advanceTimersByTime(1));
    expect(result.current).toBe(true);
  });

  it('2초 전에 끝나면 타이머를 지운다 — 뒤늦게 켜지지 않는다', () => {
    const { result, rerender } = renderHook(({ active }) => useSlowNotice(active), {
      initialProps: { active: true },
    });
    expect(vi.getTimerCount()).toBe(1);
    rerender({ active: false });
    expect(vi.getTimerCount()).toBe(0);
    act(() => void vi.advanceTimersByTime(5000));
    expect(result.current).toBe(false);
  });

  it('켜진 뒤 끝나면 바로 false, 다음 요청은 다시 2초부터', () => {
    const { result, rerender } = renderHook(({ active }) => useSlowNotice(active), {
      initialProps: { active: true },
    });
    act(() => void vi.advanceTimersByTime(2000));
    expect(result.current).toBe(true);
    rerender({ active: false });
    expect(result.current).toBe(false);
    rerender({ active: true });
    expect(result.current).toBe(false);
    act(() => void vi.advanceTimersByTime(2000));
    expect(result.current).toBe(true);
  });

  it('언마운트하면 타이머를 지운다', () => {
    const { unmount } = renderHook(() => useSlowNotice(true));
    expect(vi.getTimerCount()).toBe(1);
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });
});
