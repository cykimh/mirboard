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

/** 좌표를 고정한 DOMRect — jsdom 은 레이아웃을 하지 않아 getBoundingClientRect 가 늘 0 이다. */
function rect(left: number, top: number, width: number, height: number): DOMRect {
  return {
    left,
    top,
    width,
    height,
    right: left + width,
    bottom: top + height,
    x: left,
    y: top,
    toJSON: () => ({}),
  } as DOMRect;
}

/**
 * D-132 — 트릭 카드 비행은 낸 사람의 좌석에서 가운데로 날아온다. 경기장 CSS 대로 좌석을 놓는다: `.seat-e` 왼쪽,
 * `.seat-w` 오른쪽, `.seat-n` 위, `.seat-s` 아래(가운데 트릭은 (500, 300)). `dx`·`dy` 는 가운데 → 좌석 벡터다.
 */
function renderPositioned(mySeat: number) {
  const hook = renderHook((p: Props) =>
    useGameTableEffects({
      mySeat,
      myTeam: 'A',
      myTurn: false,
      matchEnded: null,
      triggerEffect: vi.fn(),
      playChime: vi.fn(),
      cardAnimEnabled: true,
      spectator: mySeat < 0,
      isInPassing: false,
      iAmPassSubmitted: false,
      passCardsBySlot: NO_PASS,
      sendAction: vi.fn(),
      ...p,
    }),
    { initialProps: BASE },
  );
  const arena = document.createElement('div');
  arena.getBoundingClientRect = () => rect(0, 0, 1000, 600);
  const at: Record<string, DOMRect> = {
    s: rect(450, 500, 100, 80),
    e: rect(20, 260, 100, 80),
    n: rect(450, 20, 100, 80),
    w: rect(880, 260, 100, 80),
  };
  Object.entries(at).forEach(([pos, r]) => {
    const seat = document.createElement('div');
    seat.className = `seat seat-${pos}`;
    seat.getBoundingClientRect = () => r;
    arena.appendChild(seat);
  });
  const center = document.createElement('div');
  center.getBoundingClientRect = () => rect(450, 250, 100, 100);
  hook.result.current.arenaRef.current = arena;
  hook.result.current.centerTrickRef.current = center;
  return hook;
}

describe('useGameTableEffects — 트릭 카드 비행 출발 좌석 (D-132 시계 방향)', () => {
  beforeEach(() => {
    vi.stubGlobal('requestAnimationFrame', () => 0);
    vi.stubGlobal('cancelAnimationFrame', () => {});
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('내 다음 차례(+1)가 내면 왼쪽 좌석에서 날아온다', () => {
    const hook = renderPositioned(0);
    hook.rerender({ ...BASE, currentTop: FULL_HOUSE, currentTopSeat: 1 });

    expect(hook.result.current.fly?.dx).toBeLessThan(0);
    expect(hook.result.current.fly?.dy).toBe(0);
  });

  it('직전 차례(+3)가 내면 오른쪽, 파트너(+2)가 내면 위에서 날아온다', () => {
    const right = renderPositioned(0);
    right.rerender({ ...BASE, currentTop: FULL_HOUSE, currentTopSeat: 3 });
    expect(right.result.current.fly?.dx).toBeGreaterThan(0);

    const partner = renderPositioned(0);
    partner.rerender({ ...BASE, currentTop: FULL_HOUSE, currentTopSeat: 2 });
    expect(partner.result.current.fly?.dx).toBe(0);
    expect(partner.result.current.fly?.dy).toBeLessThan(0);
  });

  it('관전자에게는 아래 좌석(3) 다음인 좌석 0 이 왼쪽에서 날아온다', () => {
    const hook = renderPositioned(-1);
    hook.rerender({ ...BASE, currentTop: FULL_HOUSE, currentTopSeat: 0 });

    expect(hook.result.current.fly?.dx).toBeLessThan(0);
  });
});
