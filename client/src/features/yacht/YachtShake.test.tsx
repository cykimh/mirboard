import { act, fireEvent, render, screen } from '@testing-library/react';
import { closeGameMenu, getMenuItem, queryMenuItem } from '@/test/gameMenu';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { YachtTable } from './YachtTable';
import { SHAKE_MIN_MS } from './DiceTray';
import { YachtDie, PIP_COLOR } from './YachtDie';
import { MOTION_MIN_JOLTS, MOTION_QUIET_MS } from './useDeviceShake';
import { useToastStore } from '@/components/toast/toastStore';
import { useYachtStore } from './yachtStore';
import { useAuthStore } from '@/features/auth/authStore';
import type { Held, YachtSeatView, YachtTableView } from '@/types/yacht';

/** D-264 — 얏추 굴림 소리·흔들어 굴리기·1 눈 색. 소리는 가짜 `useSfx` 로 무엇을 불렀는지만 본다(합성은 `useSfx.test`). */
const sendAction = vi.fn();
let socketConnected = true;
vi.mock('@/ws/useStompRoom', () => ({
  useStompRoom: () => ({
    connected: socketConnected,
    sendAction: (a: Record<string, unknown>) => sendAction(a),
    sendChat: vi.fn(),
    sendReaction: vi.fn(),
    sendVoice: vi.fn(),
    subscribeVoice: () => () => {},
    chatPanelOpenRef: { current: false },
    requestResync: vi.fn(),
  }),
}));

vi.mock('@/api/users', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/users')>();
  return { ...actual, usersApi: { ...actual.usersApi, stats: () => Promise.resolve({ games: [] }) } };
});

const playRoll = vi.fn();
const startShake = vi.fn();
const stopShake = vi.fn();
vi.mock('@/features/sound/useSfx', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/sound/useSfx')>();
  return {
    ...actual,
    useSfx: () => ({
      muted: false,
      toggleMute: () => {},
      play: () => {},
      playChime: () => {},
      playSlam: () => {},
      playFive: () => {},
      playYacht: () => {},
      playMade: () => {},
      playRoll: (mine?: boolean) => playRoll(mine),
      startShake: () => {
        startShake();
        return stopShake;
      },
    }),
  };
});

const NONE: Held = [false, false, false, false, false];
const seat = (n: number, over: Partial<YachtSeatView> = {}): YachtSeatView => ({
  seat: n,
  scores: {},
  upperSubtotal: 0,
  bonus: 0,
  total: 0,
  deserted: false,
  ...over,
});

function seed(over: Partial<YachtTableView> = {}) {
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
  useYachtStore.getState().reset('r-1');
  useYachtStore.getState().applySnapshot({
    roomId: 'r-1',
    phase: t.phase,
    eventSeq: 1,
    tableView: t,
    privateHand: null,
    disconnectedSeats: [],
  });
}

const simul = (myPending = true) =>
  seed({
    turnSeat: -1,
    simultaneous: true,
    turns: [
      { seat: 0, dice: null, held: NONE, rolls: 0, pending: myPending, candidates: {} },
      { seat: 1, dice: [6, 6, 5, 5, 1], held: NONE, rolls: 1, pending: true, candidates: {} },
    ],
  });

function renderTable(props: Partial<Parameters<typeof YachtTable>[0]> = {}) {
  return render(
    <YachtTable roomId="r-1" playerIds={[10, 20]} myUserId={10} usernames={{ 10: '민지', 20: '준호' }} onExit={vi.fn()} {...props} />,
  );
}

const shakeButton = () => screen.getByRole('button', { name: '흔들기' });
const rolled = (seatNo: number, seq: number, rolls = 1) =>
  act(() => {
    useYachtStore.getState().applyEvent({
      type: 'DICE_ROLLED',
      seq,
      payload: { seat: seatNo, dice: [1, 2, 3, 4, 6], held: NONE, rolls, candidates: {} },
    });
  });

describe('얏추 D-264', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    sendAction.mockReset();
    playRoll.mockReset();
    startShake.mockReset();
    stopShake.mockReset();
    socketConnected = true;
    useToastStore.getState().clear?.();
    useAuthStore.setState({ token: 't' } as never);
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    window.localStorage.removeItem('mirboard.yachtMotionShake');
  });

  describe('1 눈 색', () => {
    it('작은 주사위의 1 눈은 다른 눈과 같은 검정이다(붉은 눈 없음)', () => {
      const one = render(<YachtDie face={1} mini />);
      const five = render(<YachtDie face={5} mini />);
      const fillOne = one.container.querySelector('circle.yt-die-svg-one')?.getAttribute('fill');
      expect(fillOne).toBe(PIP_COLOR);
      expect(five.container.querySelector('circle.yt-die-svg-pip')?.getAttribute('fill')).toBe(fillOne);
      expect(one.container.innerHTML).not.toMatch(/a8243c/i);
    });

    it('큰 주사위의 1 눈도 다른 눈과 같은 그라데이션을 쓴다 — 붉은 그라데이션 정의가 없다', () => {
      const { container } = render(
        <>
          <YachtDie face={1} />
          <YachtDie face={4} />
        </>,
      );
      const fills = [...container.querySelectorAll('circle.yt-die-svg-pip')].map((c) => c.getAttribute('fill') ?? '');
      expect(fills).toHaveLength(5);
      for (const f of fills) expect(f).toMatch(/^url\(#yt-pp-/);
      expect(container.querySelector('[id^="yt-wn-"]')).toBeNull();
      expect(container.innerHTML).not.toMatch(/a8243c|e06a7c|6d1324/i);
      // 1 눈은 여전히 pip 이면서 크기만 크다.
      const one = container.querySelector('circle.yt-die-svg-one');
      expect(one).toHaveClass('yt-die-svg-pip');
      expect(Number(one?.getAttribute('r'))).toBeGreaterThan(Number(container.querySelectorAll('svg')[1].querySelector('circle.yt-die-svg-pip')?.getAttribute('r')));
    });
  });

  describe('굴림 소리', () => {
    it('내 굴림은 또렷하게 한 번 — 연출을 다시 그려도 또 울리지 않는다', () => {
      seed();
      const { rerender } = renderTable();
      expect(playRoll).not.toHaveBeenCalled();
      rolled(0, 2);
      expect(playRoll).toHaveBeenCalledTimes(1);
      expect(playRoll).toHaveBeenLastCalledWith(true);
      rerender(
        <YachtTable roomId="r-1" playerIds={[10, 20]} myUserId={10} usernames={{ 10: '민지', 20: '준호' }} onExit={vi.fn()} />,
      );
      expect(playRoll).toHaveBeenCalledTimes(1);
      rolled(0, 3, 2);
      expect(playRoll).toHaveBeenCalledTimes(2);
    });

    it('차례대로 — 가운데 판에 보이는 남의 굴림은 작게', () => {
      seed({ turnSeat: 1 });
      renderTable();
      rolled(1, 2);
      expect(playRoll).toHaveBeenCalledTimes(1);
      expect(playRoll).toHaveBeenLastCalledWith(false);
    });

    it('모두 같이 — 좌석 띠에서만 구르는 남의 굴림은 소리가 없고, 내 굴림만 울린다', () => {
      simul();
      renderTable();
      rolled(1, 2, 2);
      expect(playRoll).not.toHaveBeenCalled();
      rolled(0, 3);
      expect(playRoll).toHaveBeenCalledTimes(1);
      expect(playRoll).toHaveBeenLastCalledWith(true);
    });

    it('남의 판을 열고 닫는 것은 굴림이 아니다 — 울리지 않고, 구경 중인 판의 굴림은 작게', () => {
      simul();
      renderTable();
      fireEvent.click(screen.getByTitle('준호 판 보기'));
      expect(playRoll).not.toHaveBeenCalled();
      rolled(1, 2, 2);
      expect(playRoll).toHaveBeenCalledTimes(1);
      expect(playRoll).toHaveBeenLastCalledWith(false);
      fireEvent.click(screen.getByRole('button', { name: /내 판으로/ }));
      expect(playRoll).toHaveBeenCalledTimes(1);
    });

    it('이미 굴린 판에 다시 들어와도(스토어에 지난 굴림 신호가 남아 있어도) 울리지 않는다', () => {
      seed();
      const first = renderTable();
      rolled(0, 2);
      first.unmount();
      playRoll.mockReset();
      renderTable();
      expect(playRoll).not.toHaveBeenCalled();
    });
  });

  describe('흔들어 굴리기', () => {
    it('꾹 누르고 있다가 떼면 굴리기 버튼과 같은 ROLL 을 한 번 보낸다 — 누르는 동안 컵이 흔들리고 달그락 소리가 난다', () => {
      seed();
      const { container } = renderTable();
      fireEvent.pointerDown(shakeButton(), { button: 0 });
      expect(startShake).toHaveBeenCalledTimes(1);
      expect(container.querySelector('.yt-dice')).toHaveClass('yt-dice-shaking');
      expect(container.querySelector('.yt-cup-shake')).not.toBeNull();
      expect(shakeButton()).toHaveAttribute('aria-pressed', 'true');
      expect(sendAction).not.toHaveBeenCalled();
      act(() => {
        vi.advanceTimersByTime(SHAKE_MIN_MS + 50);
      });
      expect(sendAction).not.toHaveBeenCalled();
      fireEvent.pointerUp(shakeButton());
      expect(sendAction).toHaveBeenCalledTimes(1);
      expect(sendAction).toHaveBeenCalledWith({ '@action': 'ROLL' });
      expect(stopShake).toHaveBeenCalledTimes(1);
      expect(container.querySelector('.yt-dice')).not.toHaveClass('yt-dice-shaking');
      expect(container.querySelector('.yt-cup-shake')).toBeNull();
    });

    it('두 번째 굴림부터는 지금 고정을 함께 싣고, 모으던 HOLD 는 보내지 않는다(D-183 그대로)', () => {
      seed({ dice: [1, 2, 3, 4, 6], rolls: 1 });
      renderTable();
      fireEvent.pointerDown(shakeButton(), { button: 0 });
      act(() => {
        vi.advanceTimersByTime(SHAKE_MIN_MS);
      });
      // 흔드는 동안에도 다른 손가락으로 고정을 바꿀 수 있다 — 떼는 순간의 고정이 굴림에 실린다.
      fireEvent.click(screen.getByRole('button', { name: '주사위 2: 2' }));
      fireEvent.pointerUp(shakeButton());
      act(() => {
        vi.advanceTimersByTime(1000);
      });
      expect(sendAction).toHaveBeenCalledTimes(1);
      expect(sendAction).toHaveBeenCalledWith({ '@action': 'ROLL', held: [false, true, false, false, false] });
    });

    it('너무 짧게 누르면 굴리지 않고 방법을 알려 준다', () => {
      seed();
      renderTable();
      fireEvent.pointerDown(shakeButton(), { button: 0 });
      act(() => {
        vi.advanceTimersByTime(SHAKE_MIN_MS - 100);
      });
      fireEvent.pointerUp(shakeButton());
      expect(sendAction).not.toHaveBeenCalled();
      expect(stopShake).toHaveBeenCalledTimes(1);
      expect(useToastStore.getState().toasts.map((t) => t.message)).toContain('꾹 누른 채 흔들다가 떼면 굴러요');
    });

    it('누르다 취소되면(포인터 취소·초점 잃음) 굴리지 않는다', () => {
      seed();
      renderTable();
      fireEvent.pointerDown(shakeButton(), { button: 0 });
      act(() => {
        vi.advanceTimersByTime(SHAKE_MIN_MS + 200);
      });
      fireEvent.pointerCancel(shakeButton());
      fireEvent.pointerUp(shakeButton());
      expect(sendAction).not.toHaveBeenCalled();
      expect(stopShake).toHaveBeenCalledTimes(1);
    });

    it('키보드 — 스페이스를 누르고 있다가 떼면 굴린다', () => {
      seed();
      renderTable();
      fireEvent.keyDown(shakeButton(), { key: ' ' });
      fireEvent.keyDown(shakeButton(), { key: ' ', repeat: true });
      expect(startShake).toHaveBeenCalledTimes(1);
      act(() => {
        vi.advanceTimersByTime(SHAKE_MIN_MS);
      });
      fireEvent.keyUp(shakeButton(), { key: ' ' });
      expect(sendAction).toHaveBeenCalledWith({ '@action': 'ROLL' });
    });

    it('굴릴 수 없으면 꺼져 있다 — 세 번 다 굴림·다섯 개 모두 고정·끊김', () => {
      seed({ dice: [1, 2, 3, 4, 6], rolls: 3 });
      const a = renderTable();
      expect(shakeButton()).toBeDisabled();
      fireEvent.pointerDown(shakeButton(), { button: 0 });
      expect(startShake).not.toHaveBeenCalled();
      a.unmount();

      seed({ dice: [1, 2, 3, 4, 6], rolls: 1, held: [true, true, true, true, true] });
      const b = renderTable();
      expect(shakeButton()).toBeDisabled();
      b.unmount();

      socketConnected = false;
      seed();
      renderTable();
      expect(shakeButton()).toBeDisabled();
    });

    it('내 차례가 아니면·관전자면·남의 판을 구경 중이면 버튼이 아예 없다', () => {
      seed({ turnSeat: 1 });
      const a = renderTable();
      expect(screen.queryByRole('button', { name: '흔들기' })).toBeNull();
      a.unmount();

      seed();
      const b = renderTable({ spectator: true, myUserId: 99 });
      expect(screen.queryByRole('button', { name: '흔들기' })).toBeNull();
      b.unmount();

      simul();
      renderTable();
      expect(shakeButton()).toBeEnabled();
      fireEvent.click(screen.getByTitle('준호 판 보기'));
      expect(screen.queryByRole('button', { name: '흔들기' })).toBeNull();
      expect(sendAction).not.toHaveBeenCalled();
    });

    it('흔드는 중에 굴릴 수 없게 되면(차례가 넘어감) 굴리지 않고 걷는다', () => {
      seed();
      const { container } = renderTable();
      fireEvent.pointerDown(shakeButton(), { button: 0 });
      act(() => {
        vi.advanceTimersByTime(SHAKE_MIN_MS + 100);
        useYachtStore.getState().applyEvent({ type: 'TURN_CHANGED', seq: 2, payload: { seat: 1, round: 1, turnSeconds: 0 } });
      });
      expect(stopShake).toHaveBeenCalledTimes(1);
      expect(container.querySelector('.yt-dice')).not.toHaveClass('yt-dice-shaking');
      expect(sendAction).not.toHaveBeenCalled();
    });

    it('흔드는 채로 화면을 떠나면 달그락 소리를 끈다', () => {
      seed();
      const { unmount } = renderTable();
      fireEvent.pointerDown(shakeButton(), { button: 0 });
      unmount();
      expect(stopShake).toHaveBeenCalledTimes(1);
      expect(sendAction).not.toHaveBeenCalled();
    });
  });

  describe('휴대폰 흔들기(DeviceMotion)', () => {
    const jolt = (n: number) => {
      for (let i = 0; i < n; i++) {
        const e = new Event('devicemotion');
        Object.assign(e, { acceleration: { x: 14, y: 6, z: 3 }, accelerationIncludingGravity: null });
        act(() => {
          window.dispatchEvent(e);
        });
      }
    };
    const touchDevice = (requestPermission?: () => Promise<string>) => {
      vi.stubGlobal('DeviceMotionEvent', requestPermission ? { requestPermission } : {});
      Object.defineProperty(navigator, 'maxTouchPoints', { value: 5, configurable: true });
    };
    afterEach(() => {
      Object.defineProperty(navigator, 'maxTouchPoints', { value: 0, configurable: true });
    });

    const MENU = '휴대폰 흔들어 굴리기';
    const turnOn = async () => {
      const item = getMenuItem(MENU);
      await act(async () => {
        fireEvent.click(item);
      });
    };
    const isOn = () => {
      const on = getMenuItem(MENU).getAttribute('aria-checked') === 'true';
      closeGameMenu();
      return on;
    };

    it('지원하지 않는 기기에는 ⋮ 메뉴에 켜는 항목이 없다(꾹 누르기는 그대로)', () => {
      seed();
      renderTable();
      expect(queryMenuItem(MENU)).toBeNull();
      closeGameMenu();
      expect(shakeButton()).toBeEnabled();
    });

    it('관전자에게는 켜는 항목이 없다', () => {
      touchDevice();
      seed();
      renderTable({ spectator: true, myUserId: 99 });
      expect(queryMenuItem(MENU)).toBeNull();
    });

    it('켜 두고 휴대폰을 흔들다 멈추면 ROLL 을 한 번 보낸다 — 툭 한 번 친 것은 굴리지 않는다', async () => {
      touchDevice();
      seed();
      const { container } = renderTable();
      expect(isOn()).toBe(false);
      jolt(MOTION_MIN_JOLTS);
      expect(startShake).not.toHaveBeenCalled();
      await turnOn();
      expect(isOn()).toBe(true);

      jolt(1);
      expect(startShake).toHaveBeenCalledTimes(1);
      expect(container.querySelector('.yt-dice')).toHaveClass('yt-dice-shaking');
      act(() => {
        vi.advanceTimersByTime(MOTION_QUIET_MS);
      });
      expect(stopShake).toHaveBeenCalledTimes(1);
      expect(sendAction).not.toHaveBeenCalled();

      jolt(MOTION_MIN_JOLTS);
      expect(sendAction).not.toHaveBeenCalled();
      act(() => {
        vi.advanceTimersByTime(MOTION_QUIET_MS);
      });
      expect(sendAction).toHaveBeenCalledTimes(1);
      expect(sendAction).toHaveBeenCalledWith({ '@action': 'ROLL' });
    });

    it('권한을 거절하면 켜지지 않고 안내만 한다', async () => {
      touchDevice(() => Promise.resolve('denied'));
      seed();
      renderTable();
      await turnOn();
      expect(isOn()).toBe(false);
      expect(useToastStore.getState().toasts.some((t) => t.message.includes('흔들기를 쓸 수 없어요'))).toBe(true);
      jolt(MOTION_MIN_JOLTS);
      act(() => {
        vi.advanceTimersByTime(MOTION_QUIET_MS);
      });
      expect(sendAction).not.toHaveBeenCalled();
    });

    it('권한을 허락하면 켜진다(iOS)', async () => {
      const ask = vi.fn(() => Promise.resolve('granted'));
      touchDevice(ask);
      seed();
      renderTable();
      await turnOn();
      expect(ask).toHaveBeenCalledTimes(1);
      expect(isOn()).toBe(true);
    });

    it('남의 차례에는 흔들어도 아무 일도 없다', async () => {
      touchDevice();
      window.localStorage.setItem('mirboard.yachtMotionShake', '1');
      seed({ turnSeat: 1 });
      renderTable();
      jolt(MOTION_MIN_JOLTS + 2);
      act(() => {
        vi.advanceTimersByTime(MOTION_QUIET_MS);
      });
      expect(startShake).not.toHaveBeenCalled();
      expect(sendAction).not.toHaveBeenCalled();
    });
  });
});
