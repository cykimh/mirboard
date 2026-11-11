import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cloudrouteCssSource } from '@/styles/cssSources';
import { cloudrouteFxBus } from './cloudrouteFxBus';
import { cloudrouteRoomSink } from './cloudrouteRoomSink';
import { useCloudRouteStore } from './cloudrouteStore';
import { coinCount, MAX_COINS } from './fxParticles';
import { ROUNDS_PER_PHASE, skyPhaseOf } from './sky';
import { seatOf, snapshotOf, tableOf } from './testTables';
import { CLOUD_ROUTE_FX_LEVEL } from './theme';
import {
  CATCH_UP_LOAD,
  cloudrouteFxStats,
  HURRY_SKIP_LOAD,
  paceFor,
  SKIP_LOAD,
  SKIP_WAIT_MS,
  useCloudRouteFx,
  type Pace,
} from './useCloudRouteFx';

/**
 * D-219 — 연출 큐 따라잡기. 1차 리스크("봇 차례가 3번 연속일 때 화면이 늦게 따라감")를 고정한다:
 * 밀린 연출이 2개를 넘으면 빨리 감고, 많이 밀리면 건너뛰고, 화면 값은 늘 최신으로 끝난다.
 */

const num = (p: Pace) => (p === 'skip' ? Infinity : p);

describe('배속(paceFor) — 밀리면 빨리 감고, 많이 밀리면 건너뛴다', () => {
  it('밀린 것이 없으면 제 속도다', () => {
    expect(paceFor(0, 0, false)).toBe(1);
  });

  it('밀린 연출이 2개까지는 조금만 서두른다', () => {
    expect(num(paceFor(1, 0, false))).toBeLessThan(1.5);
    expect(num(paceFor(CATCH_UP_LOAD, 0, false))).toBeLessThanOrEqual(1.5);
  });

  it('밀린 연출이 2개를 넘으면 3배 이상으로 빨리 감는다 — 더 밀릴수록 더 빠르다', () => {
    expect(num(paceFor(CATCH_UP_LOAD + 1, 0, false))).toBeGreaterThanOrEqual(3);
    for (let n = CATCH_UP_LOAD + 1; n < SKIP_LOAD; n++) {
      expect(num(paceFor(n + 1, 0, false)), `backlog ${n + 1}`).toBeGreaterThanOrEqual(num(paceFor(n, 0, false)));
    }
  });

  it('건너뛰는 기준 — 연출 수 또는 다음 묶음이 기다린 시간', () => {
    expect(paceFor(SKIP_LOAD, 0, false)).toBe('skip');
    expect(paceFor(SKIP_LOAD - 1, 0, false)).not.toBe('skip');
    expect(paceFor(0, SKIP_WAIT_MS, false)).toBe('skip');
    expect(paceFor(0, SKIP_WAIT_MS - 1, false)).not.toBe('skip');
  });

  it('다음 묶음이 오래 기다릴수록 빨라진다(밀린 수가 적어도)', () => {
    expect(paceFor(1, 0, false)).toBeLessThan(num(paceFor(1, 600, false)));
    expect(num(paceFor(1, 600, false))).toBeLessThan(num(paceFor(1, 1500, false)));
    expect(num(paceFor(1, 1500, false))).toBeLessThan(num(paceFor(1, 2500, false)));
  });

  it('내 결정이 와 있으면 남의 연출은 적어도 3.4배, 많이 남았으면 건너뛴다', () => {
    expect(num(paceFor(0, 0, true))).toBeGreaterThanOrEqual(3.4);
    expect(num(paceFor(1, 0, true))).toBeGreaterThan(num(paceFor(0, 0, true)));
    expect(num(paceFor(3, 0, true))).toBeGreaterThan(num(paceFor(3, 0, false)));
    expect(paceFor(HURRY_SKIP_LOAD - 1, 0, true)).not.toBe('skip');
    expect(paceFor(HURRY_SKIP_LOAD, 0, true)).toBe('skip');
    expect(paceFor(HURRY_SKIP_LOAD, 0, false)).not.toBe('skip');
  });
});

describe('연출 감독 — 봇이 이어 두어도 화면이 따라잡는다', () => {
  const BOT_ACTION_MS = 700;
  let seq = 0;

  const apply = (type: string, payload: unknown) =>
    act(() => {
      cloudrouteRoomSink.applyEvent({ type, seq: ++seq, payload });
    });
  const advance = (ms: number) => act(() => vi.advanceTimersByTimeAsync(ms));

  /** 봇 한 차례 — 굴리기(주사위·이동·통행료) → 0.7초 뒤 다음 차례. */
  async function botTurn(seat: number, from: number, to: number, next: number) {
    apply('DICE_ROLLED', { seat, die1: 2, die2: 3, doubles: false, doublesThisTurn: 0 });
    apply('TOKEN_MOVED', { seat, from, to, kind: 'ROLL', salary: 0, cash: 1500 });
    apply('MONEY_CHANGED', { seat, delta: -50, cash: 1450, reason: 'TAX' });
    await advance(BOT_ACTION_MS);
    apply('LAND_BOUGHT', { seat, tile: to, level: 0, paid: 100, cash: 1350 });
    apply('TURN_CHANGED', { seat: next, round: 1 });
    await advance(BOT_ACTION_MS);
  }

  function mount(mySeat = 0) {
    const root = document.createElement('div');
    document.body.append(root);
    const ref = { current: root };
    return renderHook(({ hurry }) => useCloudRouteFx(ref, { enabled: true, hurry, mySeat }), {
      initialProps: { hurry: false },
    });
  }

  beforeEach(() => {
    vi.useFakeTimers({
      toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'requestAnimationFrame', 'cancelAnimationFrame', 'performance', 'Date'],
    });
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false });
    seq = 0;
    cloudrouteFxStats.reset();
    useCloudRouteStore.getState().reset('r-1');
    useCloudRouteStore
      .getState()
      .applySnapshot(snapshotOf(tableOf({ seats: [seatOf(0), seatOf(1), seatOf(2), seatOf(3)], turnSeat: 1, pending: null })));
  });
  afterEach(() => {
    vi.useRealTimers();
    document.body.replaceChildren();
  });

  it('한 번 굴린 연출은 제 속도로 다 보여 준다 — 밀리지 않으면 서두르지 않는다', async () => {
    const { result, unmount } = mount();
    apply('DICE_ROLLED', { seat: 1, die1: 2, die2: 3, doubles: false, doublesThisTurn: 0 });
    apply('TOKEN_MOVED', { seat: 1, from: 0, to: 5, kind: 'ROLL', salary: 0, cash: 1500 });
    await advance(400);
    expect(result.current.busy).toBe(true);
    // 보여 주는 값은 아직 출발 칸, 스토어(진짜 값)는 이미 도착 칸.
    expect(result.current.view?.seats[1].position).toBe(0);
    expect(useCloudRouteStore.getState().seats[1].position).toBe(5);
    await advance(1200);
    expect(result.current.busy).toBe(true);
    await advance(2500);
    expect(result.current.busy).toBe(false);
    expect(result.current.view).toBeNull();
    expect(cloudrouteFxStats.skipped).toBe(0);
    unmount();
  });

  it('봇 차례 3번 연속 뒤 내 차례가 오면 1초 안에 최신 화면이 된다', async () => {
    const { result, rerender, unmount } = mount(0);
    await botTurn(1, 0, 5, 2);
    await botTurn(2, 0, 7, 3);
    await botTurn(3, 0, 9, 0);
    // 내 차례 — 결정이 와 있다(게임판이 hurry 를 켠다).
    apply('DECISION_REQUIRED', { seat: 0, pending: { kind: 'ROLL' } });
    rerender({ hurry: true });
    await advance(1000);
    expect(result.current.busy).toBe(false);
    expect(result.current.view).toBeNull();
    expect(result.current.tokens).toEqual({});
    // 다음 묶음이 건너뛰는 기준만큼 기다린 적이 없다.
    expect(cloudrouteFxStats.maxWaitedMs).toBeLessThan(SKIP_WAIT_MS);
    unmount();
  });

  it('봇이 이어 두는 동안에도 보여 주는 차례가 진짜 차례보다 한 차례 넘게 뒤지지 않는다', async () => {
    const { result, unmount } = mount(-1);
    const turns: [number, number, number, number][] = [
      [1, 0, 5, 2],
      [2, 0, 7, 3],
      [3, 0, 9, 0],
      [0, 0, 4, 1],
      [1, 5, 11, 2],
      [2, 7, 13, 3],
    ];
    const order = [1, 2, 3, 0, 1, 2, 3];
    let worst = 0;
    for (const [seat, from, to, next] of turns) {
      await botTurn(seat, from, to, next);
      const real = useCloudRouteStore.getState().turnSeat;
      const shown = result.current.view?.turnSeat ?? real;
      worst = Math.max(worst, order.lastIndexOf(real) - order.lastIndexOf(shown));
    }
    expect(worst).toBeLessThanOrEqual(1);
    unmount();
  });

  it('한꺼번에 많이 밀리면 앞의 연출은 건너뛰고, 보여 주는 값은 곧바로 뒤쪽 것이다', async () => {
    const { result, unmount } = mount(-1);
    for (let i = 0; i < 12; i++) {
      apply('TOKEN_MOVED', { seat: 1, from: i, to: i + 1, kind: 'ROLL', salary: 0, cash: 1500 });
    }
    await advance(0);
    // 첫 연출은 재생 중에 끊기고, 밀린 수가 기준 아래로 내려올 때까지 그 뒤 것들은 시작도 하지 않고 넘어간다.
    expect(cloudrouteFxStats.skipped).toBeGreaterThanOrEqual(12 - SKIP_LOAD - 1);
    expect(result.current.view?.seats[1].position ?? 12).toBeGreaterThanOrEqual(12 - SKIP_LOAD - 1);
    await advance(2500);
    expect(result.current.busy).toBe(false);
    expect(result.current.view).toBeNull();
    expect(useCloudRouteStore.getState().seats[1].position).toBe(12);
    unmount();
  });

  it('권위 스냅샷이 오면 남은 연출을 버리고 스토어를 그린다', async () => {
    const { result, unmount } = mount();
    apply('TOKEN_MOVED', { seat: 1, from: 0, to: 5, kind: 'ROLL', salary: 0, cash: 1500 });
    await advance(100);
    expect(result.current.view).not.toBeNull();
    act(() => cloudrouteRoomSink.applySnapshot(snapshotOf(tableOf())));
    await advance(50);
    expect(result.current.view).toBeNull();
    expect(result.current.busy).toBe(false);
    expect(cloudrouteFxBus.attached).toBe(true);
    unmount();
    expect(cloudrouteFxBus.attached).toBe(false);
  });
});

describe('동전 수 — 금액에 따라 다르다', () => {
  it('50G 마다 하나씩 늘고, 2개~12개 사이다', () => {
    expect(coinCount(0)).toBe(0);
    expect(coinCount(10)).toBe(2);
    expect(coinCount(100)).toBe(3);
    expect(coinCount(300)).toBe(7);
    expect(coinCount(5000)).toBe(MAX_COINS);
    let prev = 0;
    for (let amount = 10; amount <= 1000; amount += 10) {
      const n = coinCount(amount);
      expect(n).toBeGreaterThanOrEqual(prev);
      prev = n;
    }
  });
});

describe('하늘 시간대 — 라운드를 따라 아침 → 낮 → 노을 → 밤', () => {
  it('라운드 제한이 있으면 판을 넷으로 나눈다', () => {
    expect(skyPhaseOf(1, 30)).toBe('morning');
    expect(skyPhaseOf(8, 30)).toBe('morning');
    expect(skyPhaseOf(9, 30)).toBe('day');
    expect(skyPhaseOf(16, 30)).toBe('sunset');
    expect(skyPhaseOf(24, 30)).toBe('night');
    expect(skyPhaseOf(30, 30)).toBe('night');
    expect(skyPhaseOf(99, 30)).toBe('night');
  });

  it('뒤로 가지 않는다', () => {
    const rank = { morning: 0, day: 1, sunset: 2, night: 3 };
    for (const limit of [8, 12, 20, 30]) {
      let prev = 0;
      for (let round = 1; round <= limit; round++) {
        const now = rank[skyPhaseOf(round, limit)];
        expect(now, `${round}/${limit}`).toBeGreaterThanOrEqual(prev);
        prev = now;
      }
      expect(prev, `limit ${limit}`).toBe(3);
    }
  });

  it('제한이 없으면 몇 라운드마다 넘어가고 밤 다음은 다시 아침이다', () => {
    expect(skyPhaseOf(1, 0)).toBe('morning');
    expect(skyPhaseOf(ROUNDS_PER_PHASE + 1, 0)).toBe('day');
    expect(skyPhaseOf(ROUNDS_PER_PHASE * 3 + 1, 0)).toBe('night');
    expect(skyPhaseOf(ROUNDS_PER_PHASE * 4 + 1, 0)).toBe('morning');
    expect(skyPhaseOf(0, 0)).toBe('morning');
  });
});

describe('2차 연출 층 — 되돌리기는 theme.ts 한 줄', () => {
  it('기본값은 2차 연출이다', () => {
    expect(CLOUD_ROUTE_FX_LEVEL).toBe(2);
  });

  it('2차 연출 규칙은 모두 `.cr-fx2` 아래에 있고, 움직임은 `.cr-motion` 아래에만 있다', () => {
    const layer = cloudrouteCssSource.slice(cloudrouteCssSource.indexOf('2차 연출 (D-219)'));
    const rules = layer.slice(0, layer.indexOf('@keyframes'));
    const selectors = [...rules.matchAll(/(^|\})\s*([^{}@/]+?)\s*\{/g)].flatMap((m) => m[2].split(',').map((x) => x.trim()));
    expect(selectors.length).toBeGreaterThan(40);
    const hiddenByDefault = ['.cr-daylight', '.cr-sky-orb', '.cr-sky-birds', '.cr-cloud-flow', '.cr-win-rainbow'];
    const outside = selectors.filter((x) => !x.includes('.cr-fx2') && !hiddenByDefault.includes(x) && x !== '.cr-fx .cr-particle-puff');
    expect(outside, `.cr-fx2 밖: ${outside.join(' | ')}`).toEqual([]);
    const animated = [...rules.matchAll(/([^{}]+)\{([^}]*\banimation(-name)?\s*:[^}]*)\}/g)].map((m) => m[1].trim());
    expect(animated.length).toBeGreaterThan(10);
    expect(animated.filter((x) => x.split(',').some((one) => !one.includes('.cr-motion')))).toEqual([]);
  });

  it('하늘빛은 투명도와 transform 만 바꾼다(레이아웃을 흔들지 않는다)', () => {
    const layer = cloudrouteCssSource.slice(cloudrouteCssSource.indexOf('2차 연출 (D-219)'));
    const transitions = [...layer.matchAll(/transition:\s*([a-z-]+)/g)].map((m) => m[1]);
    expect(transitions.length).toBeGreaterThan(3);
    expect(transitions.filter((x) => !['opacity', 'transform', 'fill'].includes(x))).toEqual([]);
    const frames = layer.slice(layer.indexOf('@keyframes'));
    expect(frames).not.toMatch(/\b(width|height|top|left|right|bottom|margin|padding)\s*:/);
  });
});
