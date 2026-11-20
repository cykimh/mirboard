import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  HIDDEN_IDLE_IN_GAME_MS,
  HIDDEN_IDLE_MS,
  hiddenIdleLimitMs,
  setHiddenIdleInGame,
  watchHiddenIdle,
} from './hiddenIdle';

/** D-273 — 숨긴 탭은 정해진 시간이 지나면 연결을 끊는다(운영 서버가 잠들 수 있게). */
function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true });
  document.dispatchEvent(new Event('visibilitychange'));
}

describe('watchHiddenIdle (D-273)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
    setHiddenIdleInGame(false);
  });

  afterEach(() => {
    vi.useRealTimers();
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
    setHiddenIdleInGame(false);
  });

  it('기본은 10분, 진행 중인 판에 앉아 있으면 60분', () => {
    expect(HIDDEN_IDLE_MS).toBe(10 * 60_000);
    expect(hiddenIdleLimitMs()).toBe(HIDDEN_IDLE_MS);
    setHiddenIdleInGame(true);
    expect(hiddenIdleLimitMs()).toBe(HIDDEN_IDLE_IN_GAME_MS);
    expect(HIDDEN_IDLE_IN_GAME_MS).toBe(60 * 60_000);
  });

  it('보이는 동안에는 아무리 지나도 부르지 않는다', () => {
    const onIdle = vi.fn();
    const stop = watchHiddenIdle(onIdle);
    vi.advanceTimersByTime(HIDDEN_IDLE_MS * 3);
    expect(onIdle).not.toHaveBeenCalled();
    stop();
  });

  it('숨긴 채 10분이 지나면 한 번 부른다', () => {
    const onIdle = vi.fn();
    const stop = watchHiddenIdle(onIdle);
    setVisibility('hidden');
    vi.advanceTimersByTime(HIDDEN_IDLE_MS - 1);
    expect(onIdle).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(onIdle).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(HIDDEN_IDLE_MS * 2);
    expect(onIdle).toHaveBeenCalledTimes(1);
    stop();
  });

  it('그 전에 다시 보이면 취소되고, 다시 숨기면 처음부터 잰다', () => {
    const onIdle = vi.fn();
    const stop = watchHiddenIdle(onIdle);
    setVisibility('hidden');
    vi.advanceTimersByTime(HIDDEN_IDLE_MS - 1000);
    setVisibility('visible');
    vi.advanceTimersByTime(HIDDEN_IDLE_MS);
    expect(onIdle).not.toHaveBeenCalled();

    setVisibility('hidden');
    vi.advanceTimersByTime(HIDDEN_IDLE_MS - 1);
    expect(onIdle).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(onIdle).toHaveBeenCalledTimes(1);
    stop();
  });

  it('숨김 이벤트가 겹쳐도 처음 숨긴 시각부터 잰다', () => {
    const onIdle = vi.fn();
    const stop = watchHiddenIdle(onIdle);
    setVisibility('hidden');
    vi.advanceTimersByTime(HIDDEN_IDLE_MS / 2);
    setVisibility('hidden');
    vi.advanceTimersByTime(HIDDEN_IDLE_MS / 2);
    expect(onIdle).toHaveBeenCalledTimes(1);
    stop();
  });

  it('다시 보였다가 또 오래 숨기면 또 부른다', () => {
    const onIdle = vi.fn();
    const stop = watchHiddenIdle(onIdle);
    setVisibility('hidden');
    vi.advanceTimersByTime(HIDDEN_IDLE_MS);
    setVisibility('visible');
    setVisibility('hidden');
    vi.advanceTimersByTime(HIDDEN_IDLE_MS);
    expect(onIdle).toHaveBeenCalledTimes(2);
    stop();
  });

  it('판에 앉아 있으면 60분을 기다린다', () => {
    setHiddenIdleInGame(true);
    const onIdle = vi.fn();
    const stop = watchHiddenIdle(onIdle);
    setVisibility('hidden');
    vi.advanceTimersByTime(HIDDEN_IDLE_IN_GAME_MS - 1);
    expect(onIdle).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(onIdle).toHaveBeenCalledTimes(1);
    stop();
  });

  it('숨긴 동안 판이 끝나면 남은 시간을 10분 기준으로 다시 잰다 — 이미 지났으면 곧바로', () => {
    setHiddenIdleInGame(true);
    const onIdle = vi.fn();
    const stop = watchHiddenIdle(onIdle);
    setVisibility('hidden');
    vi.advanceTimersByTime(HIDDEN_IDLE_MS + 5000);
    expect(onIdle).not.toHaveBeenCalled();
    setHiddenIdleInGame(false);
    expect(onIdle).toHaveBeenCalledTimes(1);
    stop();
  });

  it('숨긴 동안 판이 시작되면 60분으로 늘어난다', () => {
    const onIdle = vi.fn();
    const stop = watchHiddenIdle(onIdle);
    setVisibility('hidden');
    vi.advanceTimersByTime(HIDDEN_IDLE_MS - 1000);
    setHiddenIdleInGame(true);
    vi.advanceTimersByTime(HIDDEN_IDLE_MS);
    expect(onIdle).not.toHaveBeenCalled();
    vi.advanceTimersByTime(HIDDEN_IDLE_IN_GAME_MS - HIDDEN_IDLE_MS);
    expect(onIdle).toHaveBeenCalledTimes(1);
    stop();
  });

  it('처음부터 숨겨진 채 시작해도 잰다(백그라운드 탭으로 열기)', () => {
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
    const onIdle = vi.fn();
    const stop = watchHiddenIdle(onIdle);
    vi.advanceTimersByTime(HIDDEN_IDLE_MS);
    expect(onIdle).toHaveBeenCalledTimes(1);
    stop();
  });

  it('정리하면 더 부르지 않는다(판 상태가 바뀌어도)', () => {
    const onIdle = vi.fn();
    const stop = watchHiddenIdle(onIdle);
    setVisibility('hidden');
    stop();
    vi.advanceTimersByTime(HIDDEN_IDLE_MS * 2);
    setHiddenIdleInGame(true);
    setHiddenIdleInGame(false);
    expect(onIdle).not.toHaveBeenCalled();
  });
});
