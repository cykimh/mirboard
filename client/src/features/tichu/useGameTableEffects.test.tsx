import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Hand } from '@/types/tichu';
import { useGameTableEffects } from './useGameTableEffects';

/**
 * D-78 Phase B 카드 비행(`fly`) 정리 회귀 테스트.
 *
 * 라이브 실측(2026-10-05): rAF 가 420ms 타이머보다 먼저 settled=true 로 바꾸면 effect
 * cleanup 이 타이머를 지우고 새 effect 는 settled 라 조기 반환 → fly 가 다음 play 까지
 * 남았다(다음 라운드 DEALING·TRICK_TAKEN 뒤 "(리드 대기)" 위에 낡은 카드). 백그라운드
 * 탭은 rAF 가 멈춰 타이머가 이기므로 가려졌던 버그라, rAF 를 직접 돌려 순서를 고정한다.
 */

const FULL_HOUSE: Hand = {
  type: 'FULL_HOUSE',
  cards: [
    { suit: 'JADE', rank: 9, special: null },
    { suit: 'SWORD', rank: 9, special: null },
    { suit: 'PAGODA', rank: 9, special: null },
    { suit: 'JADE', rank: 4, special: null },
    { suit: 'STAR', rank: 4, special: null },
  ],
  rank: 9,
  length: 5,
};

const NO_PASS = { left: null, partner: null, right: null };

type Props = {
  currentTop: Hand | null;
  currentTopSeat: number;
  isInPlaying: boolean;
};

const BASE: Props = { currentTop: null, currentTopSeat: -1, isInPlaying: true };

// rAF 큐를 손으로 돌린다 — 테스트가 "프레임이 타이머보다 먼저" 순서를 강제한다.
let frames: Map<number, FrameRequestCallback>;
let frameSeq: number;
function flushFrame() {
  const due = [...frames.values()];
  frames.clear();
  due.forEach((cb) => cb(0));
}

function renderEffects() {
  const hook = renderHook((p: Props) =>
    useGameTableEffects({
      mySeat: 0,
      myTeam: 'A',
      myTurn: false,
      matchEnded: null,
      triggerEffect: vi.fn(),
      playChime: vi.fn(),
      cardAnimEnabled: true,
      spectator: false,
      isInPassing: false,
      iAmPassSubmitted: false,
      passCardsBySlot: NO_PASS,
      sendAction: vi.fn(),
      ...p,
    }),
    { initialProps: BASE },
  );
  // 비행 시작은 arena 안의 좌석 엘리먼트와 중앙 트릭 앵커가 있어야 한다.
  const arena = document.createElement('div');
  ['s', 'w', 'n', 'e'].forEach((pos) => {
    const seat = document.createElement('div');
    seat.className = `seat-${pos}`;
    arena.appendChild(seat);
  });
  hook.result.current.arenaRef.current = arena;
  hook.result.current.centerTrickRef.current = document.createElement('div');
  return hook;
}

/** 좌석 1 이 풀하우스를 내 비행이 시작된 상태까지 진행. */
function playFullHouse(hook: ReturnType<typeof renderEffects>) {
  hook.rerender({ ...BASE, currentTop: FULL_HOUSE, currentTopSeat: 1 });
  expect(hook.result.current.fly).not.toBeNull();
  expect(hook.result.current.fly?.settled).toBe(false);
}

describe('useGameTableEffects — 카드 비행(fly) 정리', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    frames = new Map();
    frameSeq = 0;
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => {
      frames.set(++frameSeq, cb);
      return frameSeq;
    });
    vi.stubGlobal('cancelAnimationFrame', (id: number) => {
      frames.delete(id);
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('rAF 로 settled 된 뒤에도 420ms 후 오버레이를 걷는다', () => {
    const hook = renderEffects();
    playFullHouse(hook);

    act(() => flushFrame());
    act(() => flushFrame());
    expect(hook.result.current.fly?.settled).toBe(true);

    act(() => vi.advanceTimersByTime(420));
    expect(hook.result.current.fly).toBeNull();
  });

  it('트릭을 가져가 currentTop 이 비면 즉시 걷는다', () => {
    const hook = renderEffects();
    playFullHouse(hook);
    act(() => flushFrame());
    act(() => flushFrame());

    hook.rerender({ ...BASE, currentTop: null, currentTopSeat: -1 });
    expect(hook.result.current.fly).toBeNull();
  });

  it('PLAYING 을 벗어나면(다음 라운드 DEALING) 즉시 걷는다', () => {
    const hook = renderEffects();
    playFullHouse(hook);

    hook.rerender({
      currentTop: FULL_HOUSE,
      currentTopSeat: 1,
      isInPlaying: false,
    });
    expect(hook.result.current.fly).toBeNull();
  });
});
