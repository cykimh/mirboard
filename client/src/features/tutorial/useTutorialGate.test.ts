import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { hasSeenTutorial, markTutorialSeen, useTutorialGate } from './useTutorialGate';

/** D-121 — 키는 게임별이고 필수다. 중립 계층에 특정 게임의 기본 키가 없다. */
const KEY = 'test.tutorial.seen';
const OTHER = 'test.tutorial.other.seen';

describe('useTutorialGate', () => {
  beforeEach(() => localStorage.clear());

  it('auto-opens on first visit', () => {
    const { result } = renderHook(() => useTutorialGate(KEY));
    expect(result.current.open).toBe(true);
  });

  it('stays closed on later visits once dismissed', () => {
    const first = renderHook(() => useTutorialGate(KEY));
    act(() => first.result.current.close());
    expect(first.result.current.open).toBe(false);

    const second = renderHook(() => useTutorialGate(KEY));
    expect(second.result.current.open).toBe(false);
  });

  it('show() reopens even after it was seen', () => {
    markTutorialSeen(KEY);
    const { result } = renderHook(() => useTutorialGate(KEY));
    expect(result.current.open).toBe(false);
    act(() => result.current.show());
    expect(result.current.open).toBe(true);
  });

  it('게임별 키가 격리된다 — A 를 본 기록이 B 의 첫 노출을 막지 않는다', () => {
    markTutorialSeen(KEY);
    expect(hasSeenTutorial(KEY)).toBe(true);
    expect(hasSeenTutorial(OTHER)).toBe(false);

    const { result } = renderHook(() => useTutorialGate(OTHER));
    expect(result.current.open).toBe(true);
  });

  it('autoOpen=false 면 미열람이어도 자동으로 열지 않고, show 로만 연다', () => {
    const { result } = renderHook(() => useTutorialGate(KEY, false));
    expect(result.current.open).toBe(false);
    act(() => result.current.show());
    expect(result.current.open).toBe(true);
  });

  it('seenKey=null 이면 자동 노출도 기록도 하지 않는다', () => {
    const { result } = renderHook(() => useTutorialGate(null));
    expect(result.current.open).toBe(false);
    act(() => result.current.show());
    act(() => result.current.close());
    expect(result.current.open).toBe(false);
    expect(localStorage.length).toBe(0);
  });

  it('null → 키 전환 뒤 close 는 새 키를 기록한다 (첫 렌더 클로저가 남지 않는다)', () => {
    // RoomPage 첫 렌더는 room 이 없어 키가 null 이다 — 그때 잡힌 close 가 남으면 기록이 빠진다.
    const { result, rerender } = renderHook(
      ({ k }: { k: string | null }) => useTutorialGate(k),
      { initialProps: { k: null as string | null } },
    );
    expect(result.current.open).toBe(false);

    rerender({ k: KEY });
    expect(result.current.open).toBe(true);
    act(() => result.current.close());
    expect(localStorage.getItem(KEY)).toBe('1');
  });

  it('hide() 는 닫되 열람으로 기록하지 않는다', () => {
    const { result } = renderHook(() => useTutorialGate(KEY));
    expect(result.current.open).toBe(true);
    act(() => result.current.hide());
    expect(result.current.open).toBe(false);
    expect(hasSeenTutorial(KEY)).toBe(false);
  });
});
