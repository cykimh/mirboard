import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useCardAnimStore } from './cardAnimStore';

const KEY = 'mirboard.cardAnim';

describe('cardAnimStore', () => {
  beforeEach(() => {
    localStorage.clear();
    useCardAnimStore.setState({ enabled: true });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('기본값은 ON', () => {
    expect(useCardAnimStore.getState().enabled).toBe(true);
  });

  it('setEnabled 가 상태 + localStorage 반영', () => {
    useCardAnimStore.getState().setEnabled(false);
    expect(useCardAnimStore.getState().enabled).toBe(false);
    expect(localStorage.getItem(KEY)).toBe('0');

    useCardAnimStore.getState().setEnabled(true);
    expect(useCardAnimStore.getState().enabled).toBe(true);
    expect(localStorage.getItem(KEY)).toBe('1');
  });

  it('toggle 가 enabled 를 반전', () => {
    const before = useCardAnimStore.getState().enabled;
    useCardAnimStore.getState().toggle();
    expect(useCardAnimStore.getState().enabled).toBe(!before);
  });

  it('init 이 저장값 0 을 복원 (OFF)', () => {
    localStorage.setItem(KEY, '0');
    useCardAnimStore.getState().init();
    expect(useCardAnimStore.getState().enabled).toBe(false);
  });

  it('init 이 저장값 1 을 복원 (ON)', () => {
    localStorage.setItem(KEY, '1');
    useCardAnimStore.setState({ enabled: false });
    useCardAnimStore.getState().init();
    expect(useCardAnimStore.getState().enabled).toBe(true);
  });

  it('저장값 없고 reduced-motion 선호면 기본 OFF', () => {
    vi.stubGlobal('matchMedia', (q: string) => ({
      matches: q.includes('reduce'),
      media: q,
      onchange: null,
      addEventListener() {},
      removeEventListener() {},
      addListener() {},
      removeListener() {},
      dispatchEvent() {
        return false;
      },
    }));
    useCardAnimStore.getState().init();
    expect(useCardAnimStore.getState().enabled).toBe(false);
  });

  it('저장값 없고 reduced-motion 아니면 기본 ON', () => {
    useCardAnimStore.setState({ enabled: false });
    useCardAnimStore.getState().init();
    expect(useCardAnimStore.getState().enabled).toBe(true);
  });
});
