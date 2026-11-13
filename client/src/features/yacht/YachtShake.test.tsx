import { act, fireEvent, render, screen } from '@testing-library/react';
import { closeGameMenu, getMenuItem, queryMenuItem } from '@/test/gameMenu';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BUSY_RETRY_DELAY_MS, OTHER_SCORE_SFX_GAIN, YachtTable } from './YachtTable';
import { CLICK_SWALLOW_MS, ROLL_ANIMATION_MS, SHAKE_HOLD_MS } from './DiceTray';
import { SEAT_ROLL_MS } from './SeatDice';
import { yachtRoomSink } from './yachtRoomSink';
import { useCardAnimStore } from '@/features/theme/cardAnimStore';
import { YachtDie, PIP_COLOR } from './YachtDie';
import { MOTION_MIN_JOLTS, MOTION_QUIET_MS } from './useDeviceShake';
import { useToastStore } from '@/components/toast/toastStore';
import { ROLL_GUARD_MS, SHAKE_AFTER_ROLL_QUIET_MS, SHAKE_KEEPALIVE_MS, SHAKE_STALE_MS, useYachtStore } from './yachtStore';
import { useAuthStore } from '@/features/auth/authStore';
import type { Held, YachtSeatView, YachtTableView } from '@/types/yacht';

/**
 * D-264 — 얏추 굴림 소리·흔들어 굴리기·1 눈 색. 소리는 가짜 `useSfx` 로 무엇을 불렀는지만 본다(합성은 `useSfx.test`).
 * D-268 — [굴리기] 하나로 합침(톡 = 굴림, 꾹 = 흔들다 떼면 굴림)·남은 굴림 수 표·버튼 딸깍·남에게 보이는 흔들기(미리보기).
 */
const sendAction = vi.fn();
const sendPreview = vi.fn();
let socketConnected = true;
vi.mock('@/ws/useStompRoom', () => ({
  useStompRoom: () => ({
    connected: socketConnected,
    sendAction: (a: Record<string, unknown>) => sendAction(a),
    sendPreview: (a: Record<string, unknown>) => sendPreview(a),
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

const playTableSfx = vi.fn();
vi.mock('@/features/sound/tableSfx', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/sound/tableSfx')>();
  return { ...actual, playTableSfx: (cue: string, opts?: unknown) => playTableSfx(cue, opts) };
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

const rollButton = () => screen.getByRole('button', { name: /^굴리기, \d번 남음$/ });
const queryRollButton = () => screen.queryByRole('button', { name: /^굴리기, \d번 남음$/ });
/** 꾹 누르기 — 누른 뒤 흔들기가 시작될 때까지 기다린다. */
const hold = (ms = SHAKE_HOLD_MS + 50) => {
  fireEvent.pointerDown(rollButton(), { button: 0 });
  act(() => {
    vi.advanceTimersByTime(ms);
  });
};
/** 떼기 — 브라우저처럼 pointerup 뒤에 click 이 따라온다. */
const release = () => {
  fireEvent.pointerUp(rollButton());
  fireEvent.click(rollButton());
};
let shakeSeq = 0;
/** 번호를 주지 않으면 늘 새 번호(보낸 순서대로 닿은 프레임). */
const shakingEvent = (seatNo: number, shaking: boolean, n = ++shakeSeq) =>
  act(() => {
    useYachtStore.getState().applyEvent({ type: 'DICE_SHAKING', payload: { seat: seatNo, shaking, n } });
  });
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
    sendPreview.mockReset();
    playTableSfx.mockReset();
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

  describe('굴리기 버튼 하나 — 톡 누르면 굴리고, 꾹 누르면 흔들다 떼면 굴린다(D-268)', () => {
    it('[흔들기] 버튼은 없다 — [굴리기] 하나에 남은 굴림 수가 둥근 숫자 표로 붙는다', () => {
      seed({ dice: [1, 2, 3, 4, 6], rolls: 1 });
      const { container } = renderTable();
      expect(screen.queryByRole('button', { name: '흔들기' })).toBeNull();
      expect(container.querySelector('.yt-shake')).toBeNull();
      const button = rollButton();
      expect(button).toHaveAccessibleName('굴리기, 2번 남음');
      expect(button.querySelector('.yt-roll-label')).toHaveTextContent('굴리기');
      const badge = button.querySelector('.yt-roll-left');
      expect(badge).toHaveTextContent(/^2$/);
      expect(badge).toHaveAttribute('aria-hidden', 'true');
      expect(button.textContent).not.toMatch(/남음|·/);
    });

    it('톡 누르면(짧은 누름) 흔들지 않고 바로 ROLL 을 한 번 — 뒤따르는 click 으로 또 가지 않는다', () => {
      seed();
      const { container } = renderTable();
      fireEvent.pointerDown(rollButton(), { button: 0 });
      act(() => {
        vi.advanceTimersByTime(SHAKE_HOLD_MS - 80);
      });
      expect(container.querySelector('.yt-dice')).not.toHaveClass('yt-dice-shaking');
      release();
      expect(sendAction).toHaveBeenCalledTimes(1);
      expect(sendAction).toHaveBeenCalledWith({ '@action': 'ROLL' });
      expect(startShake).not.toHaveBeenCalled();
      expect(sendPreview).not.toHaveBeenCalled();
      // 떼고 나서는 흔들기가 시작되지 않는다(걸어 둔 타이머를 걷었다).
      act(() => {
        vi.advanceTimersByTime(SHAKE_HOLD_MS * 3);
      });
      expect(startShake).not.toHaveBeenCalled();
      expect(sendAction).toHaveBeenCalledTimes(1);
    });

    it('누름 없이 온 click(보조기기·스크립트)도 한 번 굴린다', () => {
      seed();
      renderTable();
      fireEvent.click(rollButton());
      expect(sendAction).toHaveBeenCalledTimes(1);
      // D-270 — 다음 굴림은 앞선 굴림의 결과가 온 뒤에.
      rolled(0, 2);
      fireEvent.click(rollButton());
      expect(sendAction).toHaveBeenCalledTimes(2);
    });

    it('삼킨 click 은 한 번뿐이고 잠깐만 — 그 뒤의 click 은 다시 굴린다', () => {
      seed();
      renderTable();
      fireEvent.pointerDown(rollButton(), { button: 0 });
      fireEvent.pointerUp(rollButton());
      expect(sendAction).toHaveBeenCalledTimes(1);
      // click 이 따라오지 않은 누름(포인터를 버튼 밖에서 뗌) — 한참 뒤의 click 은 새 굴림이다.
      rolled(0, 2);
      act(() => {
        vi.advanceTimersByTime(CLICK_SWALLOW_MS + 10);
      });
      fireEvent.click(rollButton());
      expect(sendAction).toHaveBeenCalledTimes(2);
    });

    it('꾹 누르면 컵이 흔들리고 달그락 소리가 나며, 떼는 순간 ROLL 을 한 번 보낸다', () => {
      seed();
      const { container } = renderTable();
      fireEvent.pointerDown(rollButton(), { button: 0 });
      expect(startShake).not.toHaveBeenCalled();
      act(() => {
        vi.advanceTimersByTime(SHAKE_HOLD_MS);
      });
      expect(startShake).toHaveBeenCalledTimes(1);
      expect(container.querySelector('.yt-dice')).toHaveClass('yt-dice-shaking');
      expect(container.querySelector('.yt-cup-shake')).not.toBeNull();
      expect(rollButton()).toHaveClass('yt-roll-shaking');
      expect(sendAction).not.toHaveBeenCalled();
      act(() => {
        vi.advanceTimersByTime(3000);
      });
      expect(sendAction).not.toHaveBeenCalled();
      release();
      expect(sendAction).toHaveBeenCalledTimes(1);
      expect(sendAction).toHaveBeenCalledWith({ '@action': 'ROLL' });
      expect(stopShake).toHaveBeenCalledTimes(1);
      expect(container.querySelector('.yt-dice')).not.toHaveClass('yt-dice-shaking');
      expect(container.querySelector('.yt-cup-shake')).toBeNull();
      expect(rollButton()).not.toHaveClass('yt-roll-shaking');
    });

    it('두 번째 굴림부터는 지금 고정을 함께 싣고, 모으던 HOLD 는 보내지 않는다(D-183 그대로)', () => {
      seed({ dice: [1, 2, 3, 4, 6], rolls: 1 });
      renderTable();
      hold();
      // 흔드는 동안에도 다른 손가락으로 고정을 바꿀 수 있다 — 떼는 순간의 고정이 굴림에 실린다.
      fireEvent.click(screen.getByRole('button', { name: '주사위 2: 2' }));
      release();
      act(() => {
        vi.advanceTimersByTime(1000);
      });
      expect(sendAction).toHaveBeenCalledTimes(1);
      expect(sendAction).toHaveBeenCalledWith({ '@action': 'ROLL', held: [false, true, false, false, false] });
    });

    it('누르다 취소되면(포인터 취소·초점 잃음) 굴리지 않는다', () => {
      seed();
      renderTable();
      hold(SHAKE_HOLD_MS + 200);
      fireEvent.pointerCancel(rollButton());
      fireEvent.pointerUp(rollButton());
      expect(sendAction).not.toHaveBeenCalled();
      expect(stopShake).toHaveBeenCalledTimes(1);

      hold();
      fireEvent.blur(rollButton());
      fireEvent.pointerUp(rollButton());
      expect(sendAction).not.toHaveBeenCalled();
      expect(stopShake).toHaveBeenCalledTimes(2);

      // 흔들기 전에 취소된 짧은 누름도 굴리지 않는다.
      fireEvent.pointerDown(rollButton(), { button: 0 });
      fireEvent.pointerCancel(rollButton());
      act(() => {
        vi.advanceTimersByTime(SHAKE_HOLD_MS * 2);
      });
      expect(startShake).toHaveBeenCalledTimes(2);
      expect(sendAction).not.toHaveBeenCalled();
    });

    it('길게 누르기 메뉴(contextmenu)는 뜨지 않고, 굴리지도 않는다', () => {
      seed();
      renderTable();
      const menu = new MouseEvent('contextmenu', { bubbles: true, cancelable: true });
      act(() => {
        rollButton().dispatchEvent(menu);
      });
      expect(menu.defaultPrevented).toBe(true);
      expect(sendAction).not.toHaveBeenCalled();
    });

    it('키보드 — 스페이스를 눌렀다 떼면 한 번 굴리고, 누르고 있으면 흔들다가 떼는 순간 한 번 굴린다', () => {
      seed();
      renderTable();
      // 톡: 스페이스는 떼는 순간 click 이 따라온다.
      fireEvent.keyDown(rollButton(), { key: ' ' });
      fireEvent.keyUp(rollButton(), { key: ' ' });
      fireEvent.click(rollButton());
      expect(sendAction).toHaveBeenCalledTimes(1);
      expect(startShake).not.toHaveBeenCalled();
      rolled(0, 2);

      // 꾹: 되풀이 keydown 은 누름을 새로 시작하지 않는다.
      fireEvent.keyDown(rollButton(), { key: ' ' });
      fireEvent.keyDown(rollButton(), { key: ' ', repeat: true });
      act(() => {
        vi.advanceTimersByTime(SHAKE_HOLD_MS);
      });
      fireEvent.keyDown(rollButton(), { key: ' ', repeat: true });
      expect(startShake).toHaveBeenCalledTimes(1);
      expect(sendAction).toHaveBeenCalledTimes(1);
      fireEvent.keyUp(rollButton(), { key: ' ' });
      fireEvent.click(rollButton());
      expect(sendAction).toHaveBeenCalledTimes(2);
    });

    it('키보드 — 엔터는 누르는 순간·누르고 있는 동안 click 을 내지만 떼는 순간에 한 번만 굴린다', () => {
      seed();
      renderTable();
      fireEvent.keyDown(rollButton(), { key: 'Enter' });
      fireEvent.click(rollButton());
      expect(sendAction).not.toHaveBeenCalled();
      act(() => {
        vi.advanceTimersByTime(SHAKE_HOLD_MS + 100);
      });
      fireEvent.keyDown(rollButton(), { key: 'Enter', repeat: true });
      fireEvent.click(rollButton());
      fireEvent.click(rollButton());
      expect(startShake).toHaveBeenCalledTimes(1);
      expect(sendAction).not.toHaveBeenCalled();
      fireEvent.keyUp(rollButton(), { key: 'Enter' });
      expect(sendAction).toHaveBeenCalledTimes(1);
    });

    it('굴릴 수 없으면 꺼져 있다 — 세 번 다 굴림(표는 0)·다섯 개 모두 고정·끊김', () => {
      seed({ dice: [1, 2, 3, 4, 6], rolls: 3 });
      const a = renderTable();
      expect(rollButton()).toBeDisabled();
      expect(rollButton().querySelector('.yt-roll-left')).toHaveTextContent(/^0$/);
      fireEvent.pointerDown(rollButton(), { button: 0 });
      act(() => {
        vi.advanceTimersByTime(SHAKE_HOLD_MS * 2);
      });
      fireEvent.pointerUp(rollButton());
      expect(startShake).not.toHaveBeenCalled();
      expect(sendAction).not.toHaveBeenCalled();
      a.unmount();

      seed({ dice: [1, 2, 3, 4, 6], rolls: 1, held: [true, true, true, true, true] });
      const b = renderTable();
      expect(rollButton()).toBeDisabled();
      b.unmount();

      socketConnected = false;
      seed();
      renderTable();
      expect(rollButton()).toBeDisabled();
    });

    it('내 차례가 아니면·관전자면·남의 판을 구경 중이면 버튼이 아예 없다', () => {
      seed({ turnSeat: 1 });
      const a = renderTable();
      expect(queryRollButton()).toBeNull();
      a.unmount();

      seed();
      const b = renderTable({ spectator: true, myUserId: 99 });
      expect(queryRollButton()).toBeNull();
      b.unmount();

      simul();
      renderTable();
      expect(rollButton()).toBeEnabled();
      fireEvent.click(screen.getByTitle('준호 판 보기'));
      expect(queryRollButton()).toBeNull();
      expect(sendAction).not.toHaveBeenCalled();
    });

    it('흔드는 중에 굴릴 수 없게 되면(차례가 넘어감) 굴리지 않고 걷는다', () => {
      seed();
      const { container } = renderTable();
      hold(SHAKE_HOLD_MS + 100);
      act(() => {
        useYachtStore.getState().applyEvent({ type: 'TURN_CHANGED', seq: 2, payload: { seat: 1, round: 1, turnSeconds: 0 } });
      });
      expect(stopShake).toHaveBeenCalledTimes(1);
      expect(container.querySelector('.yt-dice')).not.toHaveClass('yt-dice-shaking');
      expect(sendAction).not.toHaveBeenCalled();
    });

    it('누르고 있는 사이에 새 굴림이 오면(앞선 굴림의 결과) 그 누름은 버린다 — 떼어도 또 굴리지 않는다(이중 ROLL 방지)', () => {
      seed();
      const { container } = renderTable();
      fireEvent.click(rollButton());
      expect(sendAction).toHaveBeenCalledTimes(1);
      // D-270 — 결과를 기다리는 동안에는 흔들 수도 없다. 결과가 끝내 늦어 막음이 풀린 뒤(안전장치)에 누르고 있는 경우다.
      act(() => {
        vi.advanceTimersByTime(ROLL_GUARD_MS);
      });
      hold();
      expect(container.querySelector('.yt-dice')).toHaveClass('yt-dice-shaking');
      rolled(0, 2);
      expect(container.querySelector('.yt-dice')).not.toHaveClass('yt-dice-shaking');
      expect(stopShake).toHaveBeenCalledTimes(1);
      release();
      expect(sendAction).toHaveBeenCalledTimes(1);
      // 다음 누름은 다시 굴린다.
      act(() => {
        vi.advanceTimersByTime(CLICK_SWALLOW_MS + 10);
      });
      fireEvent.pointerDown(rollButton(), { button: 0 });
      release();
      expect(sendAction).toHaveBeenCalledTimes(2);
    });

    it('흔드는 채로 화면을 떠나면 달그락 소리를 끈다', () => {
      seed();
      const { unmount } = renderTable();
      hold();
      unmount();
      expect(stopShake).toHaveBeenCalledTimes(1);
      expect(sendAction).not.toHaveBeenCalled();
    });
  });

  describe('흔들기를 남들도 본다 — 미리보기(D-268)', () => {
    const START = expect.objectContaining({ '@action': 'SHAKE', shaking: true, n: expect.any(Number) });
    const STOP = expect.objectContaining({ '@action': 'SHAKE', shaking: false, n: expect.any(Number) });

    it('흔들기 시작·멈춤을 미리보기로 알린다 — 톡 누른 굴림은 알리지 않는다', () => {
      seed();
      renderTable();
      fireEvent.pointerDown(rollButton(), { button: 0 });
      release();
      expect(sendPreview).not.toHaveBeenCalled();
      rolled(0, 2);
      act(() => {
        vi.advanceTimersByTime(CLICK_SWALLOW_MS + 10);
      });
      hold();
      expect(sendPreview).toHaveBeenCalledTimes(1);
      expect(sendPreview).toHaveBeenLastCalledWith(START);
      release();
      expect(sendPreview).toHaveBeenCalledTimes(2);
      expect(sendPreview).toHaveBeenLastCalledWith(STOP);
      // 미리보기는 액션이 아니다 — 액션 길로는 ROLL 만 갔다.
      expect(sendAction.mock.calls.every(([a]) => a['@action'] === 'ROLL')).toBe(true);
    });

    it('오래 흔들면 유지 프레임을 되풀이해 보내고, 멈추면 더 보내지 않는다', () => {
      seed();
      renderTable();
      hold(SHAKE_HOLD_MS);
      act(() => {
        vi.advanceTimersByTime(SHAKE_KEEPALIVE_MS * 3 + 10);
      });
      expect(sendPreview.mock.calls.filter(([p]) => p.shaking === true)).toHaveLength(4);
      release();
      const sent = sendPreview.mock.calls.length;
      expect(sendPreview).toHaveBeenLastCalledWith(STOP);
      act(() => {
        vi.advanceTimersByTime(SHAKE_KEEPALIVE_MS * 3);
      });
      expect(sendPreview).toHaveBeenCalledTimes(sent);
    });

    it('취소·차례 넘어감·화면 떠남에도 멈춤을 알린다', () => {
      seed();
      const a = renderTable();
      hold();
      fireEvent.pointerCancel(rollButton());
      expect(sendPreview).toHaveBeenLastCalledWith(STOP);
      hold();
      act(() => {
        useYachtStore.getState().applyEvent({ type: 'TURN_CHANGED', seq: 2, payload: { seat: 1, round: 1, turnSeconds: 0 } });
      });
      expect(sendPreview.mock.calls.map(([p]) => p.shaking)).toEqual([true, false, true, false]);
      a.unmount();

      sendPreview.mockReset();
      seed();
      const b = renderTable();
      hold();
      b.unmount();
      expect(sendPreview.mock.calls.map(([p]) => p.shaking)).toEqual([true, false]);
      act(() => {
        vi.advanceTimersByTime(SHAKE_KEEPALIVE_MS * 3);
      });
      expect(sendPreview).toHaveBeenCalledTimes(2);
    });

    it('차례대로 — 남이 흔들면 가운데 판의 컵과 그 사람 명패의 컵이 흔들린다(소리·버튼 없음), 멈추면 걷힌다', () => {
      seed({ turnSeat: 1 });
      const { container } = renderTable();
      expect(container.querySelector('.yt-cup-shake')).toBeNull();
      shakingEvent(1, true);
      expect(container.querySelector('.yt-dice')).toHaveClass('yt-dice-shaking');
      expect(container.querySelector('.yt-cup-shake')).not.toBeNull();
      const seat1 = container.querySelector('[data-yt-seat="1"]');
      expect(seat1).toHaveClass('yt-seat-shaking');
      expect(seat1?.querySelector('.yt-seat-cup')).not.toBeNull();
      expect(container.querySelector('[data-yt-seat="0"] .yt-seat-cup')).toBeNull();
      expect(startShake).not.toHaveBeenCalled();
      expect(queryRollButton()).toBeNull();
      expect(sendPreview).not.toHaveBeenCalled();
      shakingEvent(1, false);
      expect(container.querySelector('.yt-cup-shake')).toBeNull();
      expect(container.querySelector('.yt-seat-cup')).toBeNull();
    });

    it('관전자도 본다', () => {
      seed({ turnSeat: 1 });
      const { container } = renderTable({ spectator: true, myUserId: 99 });
      shakingEvent(1, true);
      expect(container.querySelector('.yt-cup-shake')).not.toBeNull();
      expect(container.querySelector('[data-yt-seat="1"] .yt-seat-cup')).not.toBeNull();
    });

    it('모두 같이 — 남의 흔들기는 그 사람 명패에서(작은 주사위도 떤다), 내 가운데 판은 그대로. 그 판을 열어 보면 가운데 컵도 흔들린다', () => {
      simul();
      const { container } = renderTable();
      shakingEvent(1, true);
      const seat1 = container.querySelector('[data-yt-seat="1"]');
      expect(seat1?.querySelector('.yt-seat-cup')).not.toBeNull();
      expect(seat1?.querySelector('.yt-seat-dice')).toHaveClass('yt-seat-dice-shaking');
      expect(container.querySelector('.yt-dice')).not.toHaveClass('yt-dice-shaking');
      expect(container.querySelector('.yt-cup-shake')).toBeNull();
      fireEvent.click(screen.getByTitle('준호 판 보기'));
      expect(container.querySelector('.yt-dice')).toHaveClass('yt-dice-shaking');
      expect(container.querySelector('.yt-cup-shake')).not.toBeNull();
      fireEvent.click(screen.getByRole('button', { name: /내 판으로/ }));
      expect(container.querySelector('.yt-cup-shake')).toBeNull();
    });

    it('그 사람이 굴리면 멈춘다(멈춤 프레임을 기다리지 않는다)', () => {
      seed({ turnSeat: 1 });
      const { container } = renderTable();
      shakingEvent(1, true);
      rolled(1, 2);
      expect(container.querySelector('.yt-cup-shake')).toBeNull();
      expect(container.querySelector('.yt-seat-cup')).toBeNull();
      expect(useYachtStore.getState().shaking).toEqual({});
    });

    it('멈춤 프레임을 놓쳐도 소식이 끊기면 스스로 걷는다 — 유지 프레임이 오는 동안은 남는다', () => {
      seed({ turnSeat: 1 });
      const { container } = renderTable();
      shakingEvent(1, true);
      act(() => {
        vi.advanceTimersByTime(SHAKE_STALE_MS - 500);
      });
      expect(container.querySelector('.yt-seat-cup')).not.toBeNull();
      shakingEvent(1, true);
      act(() => {
        vi.advanceTimersByTime(SHAKE_STALE_MS - 500);
      });
      expect(container.querySelector('.yt-seat-cup')).not.toBeNull();
      act(() => {
        vi.advanceTimersByTime(600);
      });
      expect(container.querySelector('.yt-seat-cup')).toBeNull();
      expect(container.querySelector('.yt-cup-shake')).toBeNull();
    });

    it('보내는 프레임마다 번호가 오른다(시작·유지·멈춤 모두)', () => {
      seed();
      renderTable();
      hold(SHAKE_HOLD_MS);
      act(() => {
        vi.advanceTimersByTime(SHAKE_KEEPALIVE_MS + 10);
      });
      release();
      const ns = sendPreview.mock.calls.map(([p]) => p.n as number);
      expect(ns).toHaveLength(3);
      expect(ns.every((n) => Number.isSafeInteger(n) && n >= 1)).toBe(true);
      expect(ns[1]).toBeGreaterThan(ns[0]);
      expect(ns[2]).toBeGreaterThan(ns[1]);
    });

    it('순서가 뒤바뀐 프레임 — 멈춤 뒤에 닿은 그 전의 시작은 버린다(유령 컵 없음), 번호 없는 프레임도 버린다', () => {
      seed({ turnSeat: 1 });
      const { container } = renderTable();
      shakingEvent(1, false, 1001);
      shakingEvent(1, true, 1000);
      expect(container.querySelector('.yt-seat-cup')).toBeNull();
      expect(useYachtStore.getState().shaking).toEqual({});
      // 새 시작 뒤에 닿은 옛 멈춤도 버린다 — 컵이 남는다.
      shakingEvent(1, true, 1003);
      shakingEvent(1, false, 1002);
      expect(container.querySelector('.yt-seat-cup')).not.toBeNull();
      // 같은 번호가 두 번 와도 한 번만.
      shakingEvent(1, false, 1004);
      shakingEvent(1, true, 1004);
      expect(container.querySelector('.yt-seat-cup')).toBeNull();
      act(() => {
        useYachtStore.getState().applyEvent({ type: 'DICE_SHAKING', payload: { seat: 1, shaking: true } });
      });
      expect(container.querySelector('.yt-seat-cup')).toBeNull();
    });

    it('굴림보다 늦게 닿은 그 굴림 전의 시작(멈춤도 아직 안 옴)은 컵을 다시 세우지 않는다 — 잠깐 뒤의 새 흔들기는 보인다', () => {
      seed({ turnSeat: 1 });
      const { container } = renderTable();
      rolled(1, 2);
      shakingEvent(1, true, 2000);
      expect(container.querySelector('.yt-seat-cup')).toBeNull();
      expect(container.querySelector('.yt-cup-shake')).toBeNull();
      act(() => {
        vi.advanceTimersByTime(SHAKE_AFTER_ROLL_QUIET_MS + 10);
      });
      shakingEvent(1, true, 2001);
      expect(container.querySelector('.yt-seat-cup')).not.toBeNull();
    });

    it('내 흔들기의 메아리·차례가 아닌 좌석의 낡은 시작은 담지 않고, 순번 없는 프레임이라 다시 받기를 부르지 않는다', () => {
      seed();
      useYachtStore.getState().setOwnSeat(0);
      const apply = (seatNo: number, shaking: boolean) =>
        useYachtStore.getState().applyEvent({ type: 'DICE_SHAKING', payload: { seat: seatNo, shaking, n: ++shakeSeq } });
      expect(apply(0, true)).toBe('ignored');
      expect(apply(1, true)).toBe('ignored');
      expect(useYachtStore.getState().shaking).toEqual({});
      expect(apply(1, false)).toBe('applied');
      act(() => {
        useYachtStore.getState().applyEvent({ type: 'TURN_CHANGED', seq: 2, payload: { seat: 1, round: 1, turnSeconds: 0 } });
      });
      expect(apply(1, true)).toBe('applied');
      expect(Object.keys(useYachtStore.getState().shaking)).toEqual(['1']);
      // 차례가 넘어가면·다시 받으면 지운다.
      act(() => {
        useYachtStore.getState().applyEvent({ type: 'TURN_CHANGED', seq: 3, payload: { seat: 0, round: 2, turnSeconds: 0 } });
      });
      expect(useYachtStore.getState().shaking).toEqual({});
    });
  });

  describe('버튼 딸깍(D-268)', () => {
    const cues = () => playTableSfx.mock.calls.map(([cue]) => cue);

    it('다시 마운트된 게임판(대기실에 다녀온 다음 판)은 스토어에 남은 지난 기록으로 소리·강조·축하를 다시 내지 않는다', () => {
      const scoredRaw = (seatNo: number, seq: number, category: string, points: number, auto = false) =>
        useYachtStore.getState().applyEvent({
          type: 'SCORED',
          seq,
          payload: { seat: seatNo, category, points, upperSubtotal: 0, bonus: 0, total: points, auto },
        });
      // 내 자동 기록이 마지막이었던 판.
      seed({ dice: [2, 2, 2, 2, 2], rolls: 1 });
      scoredRaw(0, 2, 'YACHT', 50, true);
      const a = renderTable();
      expect(cues()).toEqual([]);
      expect(a.container.querySelector('.yt-cell-flash')).toBeNull();
      expect(a.container.querySelector('.yt-chip')).toBeNull();
      expect(a.container.querySelector('.yt-fx')).toBeNull();
      expect(a.container.querySelector('.yt-seat-total-bump')).toBeNull();
      // 그 뒤의 새 기록은 평소대로.
      act(() => {
        scoredRaw(1, 3, 'CHOICE', 10);
      });
      expect(playTableSfx.mock.calls).toEqual([['score.commit', { gain: OTHER_SCORE_SFX_GAIN }]]);
      expect(a.container.querySelector('.yt-cell-flash')).not.toBeNull();
      a.unmount();

      // 남의 요트가 마지막이었던 판 — 명패 축하도 다시 뜨지 않는다.
      playTableSfx.mockReset();
      seed({ turnSeat: 1, dice: [2, 2, 2, 2, 2], rolls: 1 });
      scoredRaw(1, 2, 'YACHT', 50);
      const b = renderTable();
      expect(cues()).toEqual([]);
      expect(b.container.querySelector('.yt-seat-yacht')).toBeNull();
      expect(b.container.querySelector('.yt-cell-flash')).toBeNull();
    });

    it('주사위 고정은 톡, 풀기는 다른 톡 — 일반 버튼 딸깍과 겹치지 않는다', () => {
      seed({ dice: [1, 2, 3, 4, 6], rolls: 1 });
      renderTable();
      fireEvent.click(screen.getByRole('button', { name: '주사위 2: 2' }));
      expect(cues()).toEqual(['die.hold']);
      fireEvent.click(screen.getByRole('button', { name: '주사위 2: 2, 고정' }));
      expect(cues()).toEqual(['die.hold', 'die.release']);
    });

    it('점수 칸을 고르면 적는 소리 — 0점 버리기를 취소하면 울리지 않는다', () => {
      seed({ dice: [1, 2, 3, 4, 6], rolls: 1, candidates: { CHOICE: 16, YACHT: 0 } });
      renderTable();
      fireEvent.click(screen.getByRole('button', { name: '초이스에 16점 기록' }));
      expect(cues()).toEqual(['score.commit']);
      expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'SCORE', category: 'CHOICE' });
      playTableSfx.mockReset();
      vi.stubGlobal('confirm', () => false);
      fireEvent.click(screen.getByRole('button', { name: '요트에 0점 기록' }));
      expect(cues()).toEqual([]);
    });

    it('굴리기는 딸깍을 내지 않는다(굴림 소리가 따로 있다)', () => {
      seed();
      renderTable();
      fireEvent.click(rollButton());
      expect(cues()).toEqual([]);
    });

    it('그 밖의 버튼(채팅·좌석 눌러 판 보기·내 판으로·메뉴 항목)은 작은 딸깍 하나', () => {
      simul();
      renderTable();
      fireEvent.click(screen.getByTitle('준호 판 보기'));
      expect(cues()).toEqual(['ui.tap']);
      fireEvent.click(screen.getByRole('button', { name: /내 판으로/ }));
      expect(cues()).toEqual(['ui.tap', 'ui.tap']);
      fireEvent.click(screen.getByRole('button', { name: /^채팅/ }));
      expect(cues()).toEqual(['ui.tap', 'ui.tap', 'ui.tap']);
    });

    it('남의 기록은 작게, 남의 요트는 두 음이 대신하고(딸깍 없음), 내가 고른 기록은 다시 울리지 않는다', () => {
      seed({ turnSeat: 1, dice: [2, 2, 2, 2, 2], rolls: 1 });
      renderTable();
      const scored = (seatNo: number, seq: number, category: string, points: number, auto = false) =>
        act(() => {
          useYachtStore.getState().applyEvent({
            type: 'SCORED',
            seq,
            payload: { seat: seatNo, category, points, upperSubtotal: 0, bonus: 0, total: points, auto },
          });
        });
      scored(1, 2, 'CHOICE', 10);
      expect(playTableSfx.mock.calls).toEqual([['score.commit', { gain: OTHER_SCORE_SFX_GAIN }]]);
      playTableSfx.mockReset();
      scored(1, 3, 'YACHT', 50);
      expect(cues()).toEqual([]);
      scored(0, 4, 'CHOICE', 10);
      expect(cues()).toEqual([]);
      // 시간 초과로 대신 적힌 내 기록은 누른 적이 없으니 여기서 울린다.
      scored(0, 5, 'ONES', 0, true);
      expect(cues()).toEqual(['score.commit']);
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
      expect(rollButton()).toBeEnabled();
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

    it('[굴리기]를 꾹 누른 채 휴대폰을 흔들다 잠깐 멎어도 굴리지 않는다 — 떼야 한 번만 간다(이중 ROLL 방지)', async () => {
      touchDevice();
      seed();
      renderTable();
      await turnOn();
      closeGameMenu();
      hold();
      jolt(MOTION_MIN_JOLTS);
      act(() => {
        vi.advanceTimersByTime(MOTION_QUIET_MS + 50);
      });
      expect(sendAction).not.toHaveBeenCalled();
      jolt(MOTION_MIN_JOLTS);
      release();
      expect(sendAction).toHaveBeenCalledTimes(1);
      act(() => {
        vi.advanceTimersByTime(MOTION_QUIET_MS + 50);
      });
      expect(sendAction).toHaveBeenCalledTimes(1);
    });

    it('누르자마자(흔들기가 되기 전에) 휴대폰을 흔들다 멈추고 계속 누르다 떼어도 ROLL 은 한 번 — 흔들기는 누름이 이어받는다', async () => {
      touchDevice();
      seed();
      const { container } = renderTable();
      await turnOn();
      closeGameMenu();
      fireEvent.pointerDown(rollButton(), { button: 0 });
      jolt(MOTION_MIN_JOLTS);
      expect(startShake).toHaveBeenCalledTimes(1);
      act(() => {
        vi.advanceTimersByTime(MOTION_QUIET_MS + 50);
      });
      // 휴대폰은 멎었지만 손은 아직 누르고 있다 — 굴리지 않고 계속 흔든다.
      expect(sendAction).not.toHaveBeenCalled();
      expect(container.querySelector('.yt-dice')).toHaveClass('yt-dice-shaking');
      release();
      expect(sendAction).toHaveBeenCalledTimes(1);
      expect(container.querySelector('.yt-dice')).not.toHaveClass('yt-dice-shaking');
      act(() => {
        vi.advanceTimersByTime(MOTION_QUIET_MS + 500);
      });
      expect(sendAction).toHaveBeenCalledTimes(1);
      // 남들에게도 시작과 멈춤이 짝이 맞게 간다(마지막은 멈춤).
      const sent = sendPreview.mock.calls.map(([p]) => p.shaking);
      expect(sent[sent.length - 1]).toBe(false);
      expect(sent.filter(Boolean).length).toBe(sent.filter((x: boolean) => !x).length);
    });

    it('아주 짧게 누른 사이(흔들기가 되기 전)에 휴대폰이 멎어도 ROLL 은 떼는 순간 한 번', async () => {
      touchDevice();
      seed();
      renderTable();
      await turnOn();
      closeGameMenu();
      jolt(MOTION_MIN_JOLTS);
      act(() => {
        vi.advanceTimersByTime(MOTION_QUIET_MS - 100);
      });
      fireEvent.pointerDown(rollButton(), { button: 0 });
      act(() => {
        vi.advanceTimersByTime(120);
      });
      expect(sendAction).not.toHaveBeenCalled();
      release();
      expect(sendAction).toHaveBeenCalledTimes(1);
      act(() => {
        vi.advanceTimersByTime(MOTION_QUIET_MS + 500);
      });
      expect(sendAction).toHaveBeenCalledTimes(1);
    });

    it('휴대폰을 흔드는 중에 [굴리기]를 톡 누르면 한 번만 굴린다 — 멎으면서 또 가지 않는다', async () => {
      touchDevice();
      seed();
      renderTable();
      await turnOn();
      closeGameMenu();
      jolt(MOTION_MIN_JOLTS);
      expect(startShake).toHaveBeenCalledTimes(1);
      fireEvent.pointerDown(rollButton(), { button: 0 });
      release();
      expect(sendAction).toHaveBeenCalledTimes(1);
      expect(stopShake).toHaveBeenCalledTimes(1);
      jolt(2);
      act(() => {
        vi.advanceTimersByTime(MOTION_QUIET_MS + 50);
      });
      expect(sendAction).toHaveBeenCalledTimes(1);
    });

    it('휴대폰 흔들기도 남들에게 보인다 — 시작과 멈춤을 미리보기로 알린다', async () => {
      touchDevice();
      seed();
      renderTable();
      await turnOn();
      closeGameMenu();
      jolt(MOTION_MIN_JOLTS);
      expect(sendPreview.mock.calls.map(([p]) => p.shaking)).toEqual([true]);
      act(() => {
        vi.advanceTimersByTime(MOTION_QUIET_MS);
      });
      expect(sendPreview.mock.calls.map(([p]) => p.shaking)).toEqual([true, false]);
      expect(sendAction).toHaveBeenCalledTimes(1);
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

  describe('D-270 — 굴림 결과가 오기 전에는 또 굴리지 않는다·흔드는 동안 버튼이 바뀐다·마지막 굴림 뒤 모두 고정 모습', () => {
    const busy = () =>
      act(() => {
        yachtRoomSink.applyPrivateEvent({ type: 'ERROR', payload: { code: 'BUSY', message: 'busy' } } as never);
      });
    const label = () => rollButton().querySelector('.yt-roll-label');

    it('결과가 오기 전의 연속 누름 — ROLL 은 한 번만 나간다(톡톡톡·누름 없는 click 모두)', () => {
      seed();
      renderTable();
      fireEvent.pointerDown(rollButton(), { button: 0 });
      release();
      // 아무 일도 없어 보여 다시 누른다 — 서버 결과는 아직 오지 않았다.
      for (let i = 0; i < 3; i++) {
        act(() => {
          vi.advanceTimersByTime(CLICK_SWALLOW_MS + 50);
        });
        fireEvent.pointerDown(rollButton(), { button: 0 });
        release();
        fireEvent.click(rollButton());
      }
      expect(sendAction).toHaveBeenCalledTimes(1);
      // 결과가 오면 다시 굴릴 수 있다.
      rolled(0, 2);
      act(() => {
        vi.advanceTimersByTime(CLICK_SWALLOW_MS + 50);
      });
      fireEvent.pointerDown(rollButton(), { button: 0 });
      release();
      expect(sendAction).toHaveBeenCalledTimes(2);
    });

    it('같은 렌더 안에서 겹친 click 도 한 번만 — 렌더 값이 아니라 스토어의 지금 값으로 막는다', () => {
      seed();
      renderTable();
      const button = rollButton();
      act(() => {
        button.click();
        button.click();
        button.click();
      });
      expect(sendAction).toHaveBeenCalledTimes(1);
    });

    it('꾹 눌렀다 뗀 뒤 결과가 오기 전에 다시 꾹 눌렀다 떼도 ROLL 은 한 번 — 흔들기도 시작하지 않는다', () => {
      seed();
      renderTable();
      hold();
      release();
      expect(sendAction).toHaveBeenCalledTimes(1);
      expect(startShake).toHaveBeenCalledTimes(1);
      act(() => {
        vi.advanceTimersByTime(CLICK_SWALLOW_MS + 50);
      });
      hold();
      expect(startShake).toHaveBeenCalledTimes(1);
      release();
      expect(sendAction).toHaveBeenCalledTimes(1);
    });

    it('결과를 기다리는 동안 누르기 시작한 누름은 결과가 온 뒤에 떼어도 굴리지 않는다(뒤따르는 click 도 삼킨다)', () => {
      seed();
      renderTable();
      fireEvent.click(rollButton());
      fireEvent.pointerDown(rollButton(), { button: 0 });
      rolled(0, 2);
      release();
      expect(sendAction).toHaveBeenCalledTimes(1);
    });

    it('떼는 순간 바로 "굴리는 중…" — 버튼·컵이 결과를 기다리고, 눈은 서버 결과가 와야 바뀐다', () => {
      seed({ dice: [1, 1, 2, 2, 3], rolls: 1 });
      const { container } = renderTable();
      expect(label()).toHaveTextContent('굴리기');
      fireEvent.pointerDown(rollButton(), { button: 0 });
      release();
      expect(label()).toHaveTextContent('굴리는 중…');
      expect(rollButton()).toHaveClass('yt-roll-pending');
      expect(rollButton()).toHaveAttribute('aria-disabled', 'true');
      expect(rollButton().querySelector('.yt-roll-left')).toHaveTextContent(/^2$/);
      expect(container.querySelector('.yt-dice')).toHaveClass('yt-dice-waiting');
      expect(container.querySelector('.yt-cup-wait')).not.toBeNull();
      // 눈은 그대로다(지어내지 않는다).
      expect(screen.getByRole('group', { name: '주사위 1, 1, 2, 2, 3' })).toBeInTheDocument();
      rolled(0, 2, 2);
      expect(label()).toHaveTextContent('굴리기');
      expect(rollButton()).not.toHaveClass('yt-roll-pending');
      expect(rollButton()).not.toHaveAttribute('aria-disabled');
      expect(container.querySelector('.yt-dice')).not.toHaveClass('yt-dice-waiting');
      expect(container.querySelector('.yt-cup-wait')).toBeNull();
      expect(screen.getByRole('group', { name: '주사위 1, 2, 3, 4, 6' })).toBeInTheDocument();
    });

    it('BUSY 한 번 재전송 동안에도 막음이 유지된다 — 재전송은 한 번 가고, 그 사이의 누름은 나가지 않는다', () => {
      seed();
      renderTable();
      fireEvent.click(rollButton());
      busy();
      fireEvent.click(rollButton());
      act(() => {
        vi.advanceTimersByTime(BUSY_RETRY_DELAY_MS);
      });
      expect(sendAction).toHaveBeenCalledTimes(2);
      fireEvent.click(rollButton());
      expect(sendAction).toHaveBeenCalledTimes(2);
      expect(label()).toHaveTextContent('굴리는 중…');
      // 두 번째 BUSY 는 오류 문구 — 막음이 풀려 다시 굴릴 수 있다.
      busy();
      expect(label()).toHaveTextContent('굴리기');
      fireEvent.click(rollButton());
      expect(sendAction).toHaveBeenCalledTimes(3);
    });

    it('거절(오류)·다시 받기·차례 넘어감이 막음을 푼다', () => {
      seed();
      renderTable();
      fireEvent.click(rollButton());
      act(() => {
        yachtRoomSink.applyPrivateEvent({ type: 'ERROR', payload: { code: 'INVALID_ACTION', message: 'x' } } as never);
      });
      fireEvent.click(rollButton());
      expect(sendAction).toHaveBeenCalledTimes(2);
      // 다시 받기(스냅샷).
      act(() => seed());
      fireEvent.click(rollButton());
      expect(sendAction).toHaveBeenCalledTimes(3);
      act(() => {
        useYachtStore.getState().applyEvent({ type: 'TURN_CHANGED', seq: 5, payload: { seat: 1, round: 1, turnSeconds: 0 } });
      });
      expect(useYachtStore.getState().rollInFlight).toBe(false);
    });

    it('아무 소식이 없으면 잠깐 뒤에 스스로 풀린다(안전장치)', () => {
      seed();
      renderTable();
      fireEvent.click(rollButton());
      act(() => {
        vi.advanceTimersByTime(ROLL_GUARD_MS - 100);
      });
      fireEvent.click(rollButton());
      expect(sendAction).toHaveBeenCalledTimes(1);
      act(() => {
        vi.advanceTimersByTime(200);
      });
      expect(label()).toHaveTextContent('굴리기');
      fireEvent.click(rollButton());
      expect(sendAction).toHaveBeenCalledTimes(2);
    });

    it('결과를 기다리는 동안에는 고정·기록도 보내지 않는다 — 모으던 HOLD 가 굴림 뒤에 따로 나가지도 않는다', () => {
      seed({ dice: [1, 2, 3, 4, 6], rolls: 1, candidates: { CHOICE: 16 } });
      renderTable();
      fireEvent.click(screen.getByRole('button', { name: '주사위 2: 2' }));
      fireEvent.click(rollButton());
      fireEvent.click(screen.getByRole('button', { name: '주사위 3: 3' }));
      fireEvent.click(screen.getByRole('button', { name: '초이스에 16점 기록' }));
      act(() => {
        vi.advanceTimersByTime(1000);
      });
      expect(sendAction).toHaveBeenCalledTimes(1);
      expect(sendAction).toHaveBeenCalledWith({ '@action': 'ROLL', held: [false, true, false, false, false] });
    });

    it('모두 같이 — 내 판의 굴림도 결과가 오기 전에는 한 번만, 남의 굴림은 막음을 풀지 않는다', () => {
      simul();
      renderTable();
      fireEvent.click(rollButton());
      rolled(1, 2, 2);
      fireEvent.click(rollButton());
      expect(sendAction).toHaveBeenCalledTimes(1);
      rolled(0, 3);
      fireEvent.click(rollButton());
      expect(sendAction).toHaveBeenCalledTimes(2);
    });

    it('휴대폰 흔들기 — 흔드는 동안 버튼이 바뀌고, 결과를 기다리는 동안 또 흔들어도 굴리지 않는다', async () => {
      vi.stubGlobal('DeviceMotionEvent', {});
      Object.defineProperty(navigator, 'maxTouchPoints', { value: 5, configurable: true });
      window.localStorage.setItem('mirboard.yachtMotionShake', '1');
      const jolt = (n: number) => {
        for (let i = 0; i < n; i++) {
          const e = new Event('devicemotion');
          Object.assign(e, { acceleration: { x: 14, y: 6, z: 3 }, accelerationIncludingGravity: null });
          act(() => {
            window.dispatchEvent(e);
          });
        }
      };
      try {
        seed();
        renderTable();
        jolt(MOTION_MIN_JOLTS);
        expect(label()).toHaveTextContent('흔드는 중…');
        expect(rollButton().querySelector('.yt-roll-hint')).toHaveTextContent('멈추면 굴려요');
        expect(rollButton()).toHaveClass('yt-roll-shaking');
        act(() => {
          vi.advanceTimersByTime(MOTION_QUIET_MS);
        });
        expect(sendAction).toHaveBeenCalledTimes(1);
        expect(label()).toHaveTextContent('굴리는 중…');
        jolt(MOTION_MIN_JOLTS);
        act(() => {
          vi.advanceTimersByTime(MOTION_QUIET_MS);
        });
        expect(sendAction).toHaveBeenCalledTimes(1);
        expect(startShake).toHaveBeenCalledTimes(1);
      } finally {
        Object.defineProperty(navigator, 'maxTouchPoints', { value: 0, configurable: true });
      }
    });

    it('꾹 누르는 동안 버튼 글·모습이 바뀐다 — "흔드는 중…·떼면 굴려요", 숫자 표는 그대로. 연출을 꺼도 글·색은 바뀐다', () => {
      seed({ dice: [1, 2, 3, 4, 6], rolls: 1 });
      renderTable();
      expect(rollButton().querySelector('.yt-roll-hint')).toBeNull();
      hold();
      expect(label()).toHaveTextContent('흔드는 중…');
      expect(rollButton().querySelector('.yt-roll-hint')).toHaveTextContent('떼면 굴려요');
      expect(rollButton()).toHaveClass('yt-roll-shaking', 'yt-roll-shaking-live');
      expect(rollButton().querySelector('.yt-roll-left')).toHaveTextContent(/^2$/);
      expect(rollButton()).toHaveAccessibleName('굴리기, 2번 남음');
      fireEvent.pointerCancel(rollButton());
      expect(label()).toHaveTextContent('굴리기');
      expect(rollButton()).not.toHaveClass('yt-roll-shaking');

      act(() => useCardAnimStore.setState({ enabled: false }));
      try {
        hold();
        expect(label()).toHaveTextContent('흔드는 중…');
        expect(rollButton()).toHaveClass('yt-roll-shaking');
        expect(rollButton()).not.toHaveClass('yt-roll-shaking-live');
      } finally {
        act(() => useCardAnimStore.setState({ enabled: true }));
      }
    });

    it('세 번째 굴림 뒤에는 다섯 개 모두 고정 모습 — 표시일 뿐 HOLD 를 보내지 않고 스토어의 고정도 그대로다', () => {
      seed({ dice: [1, 2, 3, 4, 6], rolls: 2, held: [true, false, false, false, false] });
      const { container } = renderTable();
      expect(container.querySelectorAll('.yt-die-held')).toHaveLength(1);
      fireEvent.click(rollButton());
      act(() => {
        useYachtStore.getState().applyEvent({
          type: 'DICE_ROLLED',
          seq: 2,
          payload: { seat: 0, dice: [1, 5, 5, 2, 6], held: [true, false, false, false, false], rolls: 3, candidates: {} },
        });
      });
      // 구르는 동안에는 굴린 주사위가 고정 모습이 아니다(쏟아져 멈춘 뒤에 잠긴다).
      expect(container.querySelectorAll('.yt-die-held')).toHaveLength(1);
      expect(container.querySelectorAll('.yt-die-rolling')).toHaveLength(4);
      act(() => {
        vi.advanceTimersByTime(ROLL_ANIMATION_MS);
      });
      expect(container.querySelectorAll('.yt-die-held')).toHaveLength(5);
      expect(container.querySelectorAll('.yt-die-final')).toHaveLength(4);
      expect(container.querySelectorAll('.yt-die-tag')).toHaveLength(5);
      // 실제 고정 상태(읽어 주는 이름·눌림)는 그대로.
      expect(container.querySelectorAll('.yt-die[aria-pressed="true"]')).toHaveLength(1);
      expect(useYachtStore.getState().held).toEqual([true, false, false, false, false]);
      act(() => {
        vi.advanceTimersByTime(2000);
      });
      expect(sendAction).toHaveBeenCalledTimes(1);
      // 차례가 넘어가면 걷힌다.
      act(() => {
        useYachtStore.getState().applyEvent({ type: 'TURN_CHANGED', seq: 3, payload: { seat: 1, round: 1, turnSeconds: 0 } });
      });
      expect(container.querySelectorAll('.yt-die-held')).toHaveLength(0);
    });

    it('남의 판도 같다 — 가운데 판에 비친 남의 세 번째 굴림·좌석 띠의 작은 주사위(모두 같이)', () => {
      seed({ turnSeat: 1, dice: [2, 2, 3, 4, 6], rolls: 3 });
      const a = renderTable();
      expect(a.container.querySelectorAll('.yt-die-held')).toHaveLength(5);
      a.unmount();

      seed({
        turnSeat: -1,
        simultaneous: true,
        turns: [
          { seat: 0, dice: null, held: NONE, rolls: 0, pending: true, candidates: {} },
          { seat: 1, dice: [6, 6, 5, 5, 1], held: [true, true, false, false, false], rolls: 2, pending: true, candidates: {} },
        ],
      });
      const { container } = renderTable();
      expect(container.querySelectorAll('.yt-mini-die-held')).toHaveLength(2);
      act(() => {
        useYachtStore.getState().applyEvent({
          type: 'DICE_ROLLED',
          seq: 2,
          payload: { seat: 1, dice: [6, 6, 5, 4, 1], held: [true, true, false, false, false], rolls: 3, candidates: {} },
        });
      });
      expect(container.querySelectorAll('.yt-mini-die-held')).toHaveLength(2);
      act(() => {
        vi.advanceTimersByTime(SEAT_ROLL_MS);
      });
      expect(container.querySelectorAll('.yt-mini-die-held')).toHaveLength(5);
      // 새 라운드면 걷힌다.
      act(() => {
        useYachtStore.getState().applyEvent({ type: 'ROUND_STARTED', seq: 3, payload: { round: 2, turnSeconds: 0 } });
      });
      expect(container.querySelectorAll('.yt-mini-die-held')).toHaveLength(0);
    });
  });
});
