import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { markTutorialSeen, useTutorialGate } from './useTutorialGate';

describe('useTutorialGate', () => {
  beforeEach(() => localStorage.clear());

  it('auto-opens on first visit', () => {
    const { result } = renderHook(() => useTutorialGate());
    expect(result.current.open).toBe(true);
  });

  it('stays closed on later visits once dismissed', () => {
    const first = renderHook(() => useTutorialGate());
    act(() => first.result.current.close());
    expect(first.result.current.open).toBe(false);

    const second = renderHook(() => useTutorialGate());
    expect(second.result.current.open).toBe(false);
  });

  it('show() reopens even after it was seen', () => {
    markTutorialSeen();
    const { result } = renderHook(() => useTutorialGate());
    expect(result.current.open).toBe(false);
    act(() => result.current.show());
    expect(result.current.open).toBe(true);
  });
});
