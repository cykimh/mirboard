import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DiceTray, ROLL_ANIMATION_MS } from './DiceTray';
import { MADE_FX_MS, YachtTable } from './YachtTable';
import { MADE_HANDS, madeDice, madeHand } from './yachtLabels';
import { useYachtStore } from './yachtStore';
import { useAuthStore } from '@/features/auth/authStore';
import { useCardAnimStore } from '@/features/theme/cardAnimStore';
import { responsiveCssSource, yachtCssSource } from '@/styles/cssSources';
import type { Category, Dice, Held, YachtSeatTurn, YachtSeatView, YachtTableView } from '@/types/yacht';

/** D-208 — 내 굴림이 족보로 멈춘 순간의 효과·효과음. 소리는 훅을 모의로 두고 불렸는지만 본다(합성·음소거는 `useSfx.test`). */
vi.mock('@/ws/useStompRoom', () => ({
  useStompRoom: () => ({
    connected: true,
    sendAction: vi.fn(),
    sendChat: vi.fn(),
    sendReaction: vi.fn(),
    sendVoice: vi.fn(),
    subscribeVoice: () => () => {},
    chatPanelOpenRef: { current: false },
    requestResync: vi.fn(),
  }),
}));
const sfx = vi.hoisted(() => ({ playYacht: vi.fn(), playChime: vi.fn(), playMade: vi.fn() }));
vi.mock('@/features/sound/useSfx', () => ({
  useSfx: () => ({ muted: false, toggleMute: vi.fn(), playRoll: vi.fn(), startShake: () => () => {}, ...sfx }),
}));
vi.mock('@/api/users', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/users')>();
  return { ...actual, usersApi: { ...actual.usersApi, stats: () => Promise.resolve({ games: [] }) } };
});

const NONE: Held = [false, false, false, false, false];
const seat = (n: number): YachtSeatView => ({ seat: n, scores: {}, upperSubtotal: 0, bonus: 0, total: 0, deserted: false });
const turn = (n: number, over: Partial<YachtSeatTurn> = {}): YachtSeatTurn => ({
  seat: n,
  dice: null,
  held: NONE,
  rolls: 0,
  pending: true,
  candidates: {},
  ...over,
});

function snapshot(over: Partial<YachtTableView> = {}) {
  const t: YachtTableView = {
    phase: 'PLAYING',
    seats: [seat(0), seat(1)],
    round: 1,
    turnSeat: 0,
    dice: null,
    held: NONE,
    rolls: 0,
    candidates: {},
    turnRemainingMs: null,
    result: null,
    ...over,
  };
  useYachtStore.getState().applySnapshot({
    roomId: 'r-1',
    phase: t.phase,
    eventSeq: 1,
    tableView: t,
    privateHand: null,
    disconnectedSeats: [],
  });
}

function seed(over: Partial<YachtTableView> = {}) {
  useYachtStore.getState().reset('r-1');
  snapshot(over);
}

const renderTable = (props: { myUserId?: number; spectator?: boolean } = {}) =>
  render(
    <YachtTable
      roomId="r-1"
      playerIds={[10, 20]}
      myUserId={props.myUserId ?? 10}
      spectator={props.spectator}
      usernames={{ 10: '민지', 20: '준호' }}
      onExit={vi.fn()}
    />,
  );

function rolled(seatNo: number, dice: Dice, candidates: Partial<Record<Category, number>>, rolls = 1, held: Held = NONE) {
  act(() => {
    useYachtStore.getState().applyEvent({ type: 'DICE_ROLLED', seq: 5, payload: { seat: seatNo, dice, held, rolls, candidates } });
  });
}

const stop = (ms = ROLL_ANIMATION_MS) =>
  act(() => {
    vi.advanceTimersByTime(ms);
  });

describe('madeDice · madeHand (D-208)', () => {
  it('족보마다 세기가 정해져 있다 — 요트 강, 라지 스트레이트·포 카드 중, 풀 하우스·스몰 스트레이트 약', () => {
    expect(Object.fromEntries(MADE_HANDS)).toEqual({
      YACHT: 'big',
      LARGE_STRAIGHT: 'mid',
      FOUR_OF_A_KIND: 'mid',
      FULL_HOUSE: 'light',
      SMALL_STRAIGHT: 'light',
    });
  });

  it('빛낼 주사위 — 포 카드는 같은 눈 넷, 스몰 스트레이트는 이어진 네 눈을 하나씩, 나머지는 다섯 개', () => {
    expect(madeDice('FOUR_OF_A_KIND', [4, 4, 2, 4, 4])).toEqual([0, 1, 3, 4]);
    expect(madeDice('FOUR_OF_A_KIND', [6, 6, 6, 6, 6])).toEqual([0, 1, 2, 3, 4]);
    expect(madeDice('FOUR_OF_A_KIND', [4, 4, 2, 4, 1])).toBeNull();
    expect(madeDice('SMALL_STRAIGHT', [3, 6, 1, 2, 4])).toEqual([0, 2, 3, 4]);
    expect(madeDice('SMALL_STRAIGHT', [3, 3, 4, 5, 6])).toEqual([0, 2, 3, 4]);
    expect(madeDice('SMALL_STRAIGHT', [1, 2, 3, 5, 6])).toBeNull();
    expect(madeDice('LARGE_STRAIGHT', [2, 3, 4, 5, 6])).toEqual([0, 1, 2, 3, 4]);
    expect(madeDice('LARGE_STRAIGHT', [1, 2, 3, 4, 6])).toBeNull();
    expect(madeDice('FULL_HOUSE', [2, 5, 2, 5, 5])).toEqual([0, 1, 2, 3, 4]);
    expect(madeDice('FULL_HOUSE', [2, 2, 2, 2, 5])).toBeNull();
    expect(madeDice('YACHT', [3, 3, 3, 3, 3])).toEqual([0, 1, 2, 3, 4]);
    expect(madeDice('YACHT', [3, 3, 3, 3, 1])).toBeNull();
    expect(madeDice('CHOICE', [3, 3, 3, 3, 3])).toBeNull();
    expect(madeDice('YACHT', null)).toBeNull();
  });

  it('족보인지는 서버 후보 점수가 정한다 — 가장 높은 빈 칸 하나, 이미 채운 칸(후보에 없음)·0점은 알리지 않는다', () => {
    // 라지 스트레이트는 스몰 스트레이트이기도 하다 — 높은 쪽만.
    expect(madeHand([1, 2, 3, 4, 5], { SMALL_STRAIGHT: 15, LARGE_STRAIGHT: 30, CHOICE: 15 })).toMatchObject({
      category: 'LARGE_STRAIGHT',
      tier: 'mid',
    });
    // 라지 스트레이트 칸을 이미 채웠으면 스몰 스트레이트로.
    expect(madeHand([1, 2, 3, 4, 5], { SMALL_STRAIGHT: 15, CHOICE: 15 })).toMatchObject({ category: 'SMALL_STRAIGHT', tier: 'light' });
    // 요트 칸을 이미 채웠으면 포 카드로(다섯 개 다 빛난다).
    expect(madeHand([6, 6, 6, 6, 6], { FOUR_OF_A_KIND: 30, FULL_HOUSE: 30 })).toEqual({
      category: 'FOUR_OF_A_KIND',
      tier: 'mid',
      indices: [0, 1, 2, 3, 4],
    });
    expect(madeHand([6, 6, 6, 6, 6], { YACHT: 50, FOUR_OF_A_KIND: 30 })).toMatchObject({ category: 'YACHT', tier: 'big' });
    // 다 채웠거나 족보가 아니면 없음.
    expect(madeHand([2, 2, 5, 5, 5], { CHOICE: 19, ONES: 0 })).toBeNull();
    expect(madeHand([1, 2, 2, 4, 6], { FULL_HOUSE: 0, SMALL_STRAIGHT: 0, YACHT: 0 })).toBeNull();
    expect(madeHand(null, { YACHT: 50 })).toBeNull();
  });
});

describe('굴림이 족보로 멈춘 순간 (D-208)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    sfx.playMade.mockClear();
    sfx.playYacht.mockClear();
    useAuthStore.setState({ token: 't' } as never);
    useCardAnimStore.setState({ enabled: true });
  });
  afterEach(() => vi.useRealTimers());

  it.each([
    ['SMALL_STRAIGHT', 'light', [1, 2, 3, 4, 6], { SMALL_STRAIGHT: 15 }, '스몰 스트레이트!', 4],
    ['FULL_HOUSE', 'light', [2, 2, 5, 5, 5], { FULL_HOUSE: 19 }, '풀 하우스!', 5],
    ['LARGE_STRAIGHT', 'mid', [2, 3, 4, 5, 6], { LARGE_STRAIGHT: 30, SMALL_STRAIGHT: 15 }, '라지 스트레이트!', 5],
    ['FOUR_OF_A_KIND', 'mid', [4, 4, 2, 4, 4], { FOUR_OF_A_KIND: 18 }, '포 카드!', 4],
    ['YACHT', 'big', [6, 6, 6, 6, 6], { YACHT: 50, FOUR_OF_A_KIND: 30, FULL_HOUSE: 30 }, '요트!', 5],
  ] as const)('%s — %s 단계 소리 한 번 + 꼬리표 + 족보 주사위 고리', (_category, tier, dice, candidates, label, ringed) => {
    seed();
    const { container } = renderTable();
    rolled(0, [...dice] as Dice, candidates);
    // 구르는 동안에는 아직 없다.
    stop(ROLL_ANIMATION_MS - 1);
    expect(sfx.playMade).not.toHaveBeenCalled();
    expect(container.querySelector('.yt-made-tag')).toBeNull();
    stop(1);
    expect(sfx.playMade).toHaveBeenCalledTimes(1);
    expect(sfx.playMade).toHaveBeenCalledWith(tier);
    expect(container.querySelector(`.yt-dice-made-${tier}.yt-dice-made-live .yt-made-tag-${tier}`)?.textContent).toBe(label);
    expect(container.querySelectorAll('.yt-die-made .yt-die-madering')).toHaveLength(ringed);
    // 낭독.
    expect(container.querySelector('.yt-sr-only')?.textContent).toBe(label);
    // 굴림 쪽은 팡파르가 아니다(팡파르는 기록할 때 — D-207).
    expect(sfx.playYacht).not.toHaveBeenCalled();
    // 시간이 지나면 걷힌다.
    stop(MADE_FX_MS[tier]);
    expect(container.querySelector('.yt-made-tag, .yt-die-madering, .yt-dice-made')).toBeNull();
  });

  it('포 카드는 같은 눈 넷에만 고리가 붙는다', () => {
    seed();
    const { container } = renderTable();
    rolled(0, [4, 4, 2, 4, 4], { FOUR_OF_A_KIND: 18 });
    stop();
    const dice = [...container.querySelectorAll('.yt-die')];
    expect(dice.map((d) => d.classList.contains('yt-die-made'))).toEqual([true, true, false, true, true]);
  });

  it('족보가 아니거나 그 칸을 이미 채웠으면 조용하다', () => {
    seed();
    const { container } = renderTable();
    rolled(0, [1, 2, 2, 4, 6], { CHOICE: 15, FULL_HOUSE: 0, SMALL_STRAIGHT: 0 });
    stop();
    // 풀 하우스 모양이지만 풀 하우스 칸은 이미 채워 후보에 없다.
    rolled(0, [2, 2, 5, 5, 5], { CHOICE: 19 }, 2);
    stop();
    expect(sfx.playMade).not.toHaveBeenCalled();
    expect(container.querySelector('.yt-made-tag, .yt-die-madering')).toBeNull();
  });

  it('굴림 하나에 한 번만 — 고정 변경·다시 받기·다시 그리기·재마운트에는 다시 울리지 않는다', () => {
    seed();
    const first = renderTable();
    rolled(0, [2, 2, 5, 5, 5], { FULL_HOUSE: 19 });
    stop();
    expect(sfx.playMade).toHaveBeenCalledTimes(1);
    // 고정 변경(내 탭 + 서버 메아리).
    fireEvent.click(screen.getByRole('button', { name: '주사위 1: 2' }));
    act(() => {
      useYachtStore.getState().applyEvent({ type: 'DICE_HELD', seq: 6, payload: { seat: 0, held: [true, false, false, false, false] } });
    });
    stop(ROLL_ANIMATION_MS + 400);
    // 다시 받기(resync) — 같은 굴림이 스냅샷으로 다시 온다.
    act(() => snapshot({ dice: [2, 2, 5, 5, 5], rolls: 1, candidates: { FULL_HOUSE: 19 } }));
    stop(ROLL_ANIMATION_MS + 400);
    // 다시 그리기.
    first.rerender(
      <YachtTable roomId="r-1" playerIds={[10, 20]} myUserId={10} usernames={{ 10: '민지', 20: '준호' }} onExit={vi.fn()} />,
    );
    stop(ROLL_ANIMATION_MS + 400);
    // 재마운트(다시 연결돼 게임판이 새로 뜸) — 스토어의 굴림 신호는 그대로 남아 있다.
    first.unmount();
    const again = renderTable();
    stop(ROLL_ANIMATION_MS + MADE_FX_MS.light);
    expect(sfx.playMade).toHaveBeenCalledTimes(1);
    expect(again.container.querySelector('.yt-made-tag')).toBeNull();
    // 다음 굴림은 새로 알린다.
    rolled(0, [5, 5, 5, 5, 5], { YACHT: 50 }, 2);
    stop();
    expect(sfx.playMade).toHaveBeenCalledTimes(2);
    expect(sfx.playMade).toHaveBeenLastCalledWith('big');
  });

  it('구르는 중에 다음 굴림이 오면 마지막 굴림만 판정한다', () => {
    seed();
    renderTable();
    rolled(0, [2, 2, 5, 5, 5], { FULL_HOUSE: 19 });
    stop(300);
    rolled(0, [1, 2, 2, 4, 6], { CHOICE: 15 }, 2);
    stop(ROLL_ANIMATION_MS * 2);
    expect(sfx.playMade).not.toHaveBeenCalled();
  });

  it('기록하면 굴림 알림은 바로 걷힌다 — 멈추기 전에 기록했으면 아예 울리지 않는다', () => {
    seed();
    const { container } = renderTable();
    rolled(0, [6, 6, 6, 6, 6], { YACHT: 50 });
    stop();
    expect(container.querySelector('.yt-made-tag')).not.toBeNull();
    act(() => {
      useYachtStore.getState().applyEvent({
        type: 'SCORED',
        seq: 7,
        payload: { seat: 0, category: 'YACHT', points: 50, upperSubtotal: 0, bonus: 0, total: 50, auto: false },
      });
    });
    expect(container.querySelector('.yt-made-tag, .yt-die-madering')).toBeNull();
    expect(sfx.playMade).toHaveBeenCalledTimes(1);
    expect(sfx.playYacht).toHaveBeenCalledTimes(1);

    // 모두 같이 — 굴리자마자(멈추기 전) 기록.
    sfx.playMade.mockClear();
    seed({ simultaneous: true, turnSeat: -1, turns: [turn(0), turn(1)] });
    rolled(0, [2, 2, 5, 5, 5], { FULL_HOUSE: 19 });
    stop(200);
    act(() => {
      useYachtStore.getState().applyEvent({
        type: 'SCORED',
        seq: 8,
        payload: { seat: 0, category: 'FULL_HOUSE', points: 19, upperSubtotal: 0, bonus: 0, total: 19, auto: false },
      });
    });
    stop(ROLL_ANIMATION_MS);
    expect(sfx.playMade).not.toHaveBeenCalled();
  });

  it('남의 굴림은 알리지 않는다 — 차례대로의 남의 차례', () => {
    seed({ turnSeat: 1 });
    const { container } = renderTable();
    rolled(1, [6, 6, 6, 6, 6], { YACHT: 50 });
    stop();
    expect(sfx.playMade).not.toHaveBeenCalled();
    expect(container.querySelector('.yt-made-tag, .yt-die-madering')).toBeNull();
  });

  it('모두 같이 — 내 굴림만 알리고, 좌석 명패에 뜨는 남의 굴림은 조용하다', () => {
    seed({ simultaneous: true, turnSeat: -1, turns: [turn(0), turn(1)] });
    const { container } = renderTable();
    rolled(1, [6, 6, 6, 6, 6], { YACHT: 50 });
    stop();
    expect(sfx.playMade).not.toHaveBeenCalled();
    expect(container.querySelector('.yt-made-tag')).toBeNull();
    rolled(0, [1, 2, 3, 4, 5], { LARGE_STRAIGHT: 30, SMALL_STRAIGHT: 15 });
    stop();
    expect(sfx.playMade).toHaveBeenCalledTimes(1);
    expect(sfx.playMade).toHaveBeenCalledWith('mid');
    expect(container.querySelector('.yt-tray .yt-made-tag')?.textContent).toBe('라지 스트레이트!');
  });

  it('관전자에게는 누구의 굴림도 알리지 않는다', () => {
    seed();
    const sequential = renderTable({ myUserId: 99, spectator: true });
    rolled(0, [6, 6, 6, 6, 6], { YACHT: 50 });
    stop();
    expect(sequential.container.querySelector('.yt-made-tag')).toBeNull();
    sequential.unmount();
    seed({ simultaneous: true, turnSeat: -1, turns: [turn(0), turn(1)] });
    const together = renderTable({ myUserId: 99, spectator: true });
    rolled(0, [6, 6, 6, 6, 6], { YACHT: 50 });
    rolled(1, [1, 2, 3, 4, 5], { LARGE_STRAIGHT: 30 });
    stop();
    expect(together.container.querySelector('.yt-made-tag')).toBeNull();
    expect(sfx.playMade).not.toHaveBeenCalled();
  });

  it('남의 판을 구경하는 중(D-205)에는 알리지 않는다 — 구경하는 사람의 굴림도, 그 사이 멈춘 내 굴림도', () => {
    seed({ simultaneous: true, turnSeat: -1, turns: [turn(0), turn(1)] });
    const { container } = renderTable();
    fireEvent.click(screen.getByRole('button', { name: /준호/ }));
    expect(container.querySelector('.yt-felt-watch')).not.toBeNull();
    // 구경하는 사람이 족보를 굴린다.
    rolled(1, [6, 6, 6, 6, 6], { YACHT: 50 });
    stop();
    // 내 굴림이 구경 중에 멈춘다.
    rolled(0, [2, 2, 5, 5, 5], { FULL_HOUSE: 19 });
    stop();
    expect(sfx.playMade).not.toHaveBeenCalled();
    expect(container.querySelector('.yt-made-tag, .yt-die-madering')).toBeNull();
    // 내 판으로 돌아와도 지난 굴림을 뒤늦게 알리지 않는다.
    fireEvent.click(screen.getByRole('button', { name: /내 판으로/ }));
    stop(ROLL_ANIMATION_MS);
    expect(sfx.playMade).not.toHaveBeenCalled();
    expect(container.querySelector('.yt-made-tag')).toBeNull();
  });

  it('연출을 꺼도 소리와 정지 상태의 고리·꼬리표는 난다(움직임 층·금가루만 없다)', () => {
    useCardAnimStore.setState({ enabled: false });
    seed();
    const { container } = renderTable();
    rolled(0, [6, 6, 6, 6, 6], { YACHT: 50 });
    // 굴림 연출이 없으니 기다리지 않는다.
    stop(0);
    expect(sfx.playMade).toHaveBeenCalledWith('big');
    expect(container.querySelector('.yt-dice-made .yt-made-tag')?.textContent).toBe('요트!');
    expect(container.querySelectorAll('.yt-die-madering')).toHaveLength(5);
    expect(container.querySelector('.yt-dice-made-live, .yt-burst')).toBeNull();
  });

  it('요트로 멈추면 작은 금가루가 한 번 터진다(연출 켬)', () => {
    seed();
    const { container } = renderTable();
    rolled(0, [3, 3, 3, 3, 3], { YACHT: 50 });
    stop();
    expect(container.querySelectorAll('.yt-burst')).toHaveLength(1);
  });
});

describe('DiceTray — 족보 표시 (D-208)', () => {
  const props = { dice: [4, 4, 2, 4, 4] as Dice, held: NONE, rolls: 1, canHold: true, canRoll: true, turnName: null };
  const made = { nonce: 1, tier: 'mid' as const, label: '포 카드!', indices: [0, 1, 3, 4], ms: 1500 };

  it('구르는 동안에는 띄우지 않고, 꼬리표 길이를 CSS 변수로 넘긴다', () => {
    vi.useFakeTimers();
    const { container, rerender } = render(<DiceTray {...props} animate rollNonce={0} />);
    rerender(<DiceTray {...props} animate rollNonce={1} made={made} />);
    expect(container.querySelector('.yt-made-tag')).toBeNull();
    act(() => {
      vi.advanceTimersByTime(ROLL_ANIMATION_MS);
    });
    const tag = container.querySelector<HTMLElement>('.yt-made-tag');
    expect(tag?.style.getPropertyValue('--yt-made-ms')).toBe('1500ms');
    expect(tag?.getAttribute('aria-hidden')).toBe('true');
    vi.useRealTimers();
  });

  it('주사위가 비면 띄우지 않는다', () => {
    const { container } = render(<DiceTray {...props} dice={null} animate rollNonce={0} made={made} />);
    expect(container.querySelector('.yt-made-tag, .yt-die-madering')).toBeNull();
  });
});

describe('CSS — 족보 표시 (D-208)', () => {
  const css = yachtCssSource.replace(/\/\*[\s\S]*?\*\//g, '');

  it('움직임은 살롱 + live 아래에만 있고, 움직임 줄이기에서 꺼진다(고리·꼬리표는 남는다)', () => {
    const ring = /\n\.yt-die-madering\s*\{([^}]*)\}/.exec(css)?.[1] ?? '';
    const tag = /\n\.yt-made-tag\s*\{([^}]*)\}/.exec(css)?.[1] ?? '';
    for (const body of [ring, tag]) {
      expect(body).toContain('pointer-events: none');
      expect(body).not.toContain('animation');
    }
    for (const needle of [
      '.yt-theme-salon .yt-dice-made-live .yt-die-madering',
      '.yt-theme-salon .yt-dice-made-live.yt-dice-made-big .yt-die-madering',
      '.yt-theme-salon .yt-dice-made-live .yt-made-tag',
      '.yt-theme-salon .yt-dice-made-live.yt-dice-made-mid::after',
      '.yt-theme-salon .yt-dice-made-live.yt-dice-made-big::after',
    ]) {
      expect(css, needle).toContain(needle);
      expect(responsiveCssSource, needle).toContain(needle);
    }
  });
});
