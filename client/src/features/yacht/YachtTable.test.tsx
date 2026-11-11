import { closeGameMenu, menuInfoText } from '@/test/gameMenu';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BUSY_RETRY_DELAY_MS, HOLD_SEND_DELAY_MS, YachtTable, STALE_TURN_GRACE_MS, STALE_TURN_JITTER_MS } from './YachtTable';
import { useYachtStore } from './yachtStore';
import { yachtRoomSink } from './yachtRoomSink';
import { useAuthStore } from '@/features/auth/authStore';
import type { Held, YachtSeatView, YachtTableView } from '@/types/yacht';

const sendAction = vi.fn();
const requestResync = vi.fn();
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
    requestResync,
  }),
}));

// D-201 — 판 시작 때의 내 최고 점수(종료 화면의 "새 최고 기록!").
const statsMock = vi.fn((_token: string, _userId: number) =>
  Promise.resolve({ games: [{ gameType: 'YACHT', bestScore: 150 }] }),
);
vi.mock('@/api/users', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/users')>();
  return { ...actual, usersApi: { ...actual.usersApi, stats: (t: string, id: number) => statsMock(t, id) } };
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

const PLAYERS = [10, 20];
const NAMES = { 10: '민지', 20: '준호' };

function renderTable(props: Partial<Parameters<typeof YachtTable>[0]> = {}) {
  return render(
    <YachtTable roomId="r-1" playerIds={PLAYERS} myUserId={10} usernames={NAMES} onExit={vi.fn()} {...props} />,
  );
}

describe('YachtTable', () => {
  beforeEach(() => {
    sendAction.mockReset();
    requestResync.mockReset();
    socketConnected = true;
    useAuthStore.setState({ token: 't' } as never);
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('on my turn before rolling shows a roll button that rolls all five', () => {
    seed();
    renderTable();
    fireEvent.click(screen.getByRole('button', { name: /굴리기 · 3번 남음/ }));
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'ROLL' });
    expect(screen.getByText('내 차례')).toBeInTheDocument();
  });

  it('quick taps are batched into one hold with the whole mask', () => {
    vi.useFakeTimers();
    seed({ dice: [6, 6, 1, 2, 6], rolls: 1 });
    renderTable();
    fireEvent.click(screen.getByRole('button', { name: '주사위 1: 6' }));
    fireEvent.click(screen.getByRole('button', { name: '주사위 2: 6' }));
    fireEvent.click(screen.getByRole('button', { name: '주사위 5: 6' }));
    expect(sendAction).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: '주사위 1: 6, 고정' })).toHaveAttribute('aria-pressed', 'true');
    act(() => {
      vi.advanceTimersByTime(HOLD_SEND_DELAY_MS);
    });
    expect(sendAction).toHaveBeenCalledTimes(1);
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'HOLD', held: [true, true, false, false, true] });
  });

  it('taps fired before a re-render do not overwrite each other', () => {
    vi.useFakeTimers();
    seed({ dice: [6, 6, 1, 2, 6], rolls: 1 });
    renderTable();
    const dice = screen.getAllByRole('button', { name: /^주사위 \d/ });
    act(() => {
      dice[0].click();
      dice[2].click();
      dice[4].click();
    });
    act(() => {
      vi.advanceTimersByTime(HOLD_SEND_DELAY_MS);
    });
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'HOLD', held: [true, false, true, false, true] });
  });

  it('rolling right after a tap carries the hold and drops the pending send', () => {
    vi.useFakeTimers();
    seed({ dice: [6, 6, 1, 2, 6], rolls: 1 });
    renderTable();
    fireEvent.click(screen.getByRole('button', { name: '주사위 1: 6' }));
    fireEvent.click(screen.getByRole('button', { name: /굴리기 · 2번 남음/ }));
    act(() => {
      vi.advanceTimersByTime(HOLD_SEND_DELAY_MS * 2);
    });
    expect(sendAction).toHaveBeenCalledTimes(1);
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'ROLL', held: [true, false, false, false, false] });
  });

  it("the server's hold echo does not undo my newer taps, but others' holds show live", () => {
    seed({ dice: [6, 6, 1, 2, 6], rolls: 1 });
    renderTable();
    fireEvent.click(screen.getByRole('button', { name: '주사위 1: 6' }));
    fireEvent.click(screen.getByRole('button', { name: '주사위 2: 6' }));
    act(() => {
      useYachtStore.getState().applyEvent({ type: 'DICE_HELD', seq: 3, payload: { seat: 0, held: [true, false, false, false, false] } });
    });
    expect(screen.getByRole('button', { name: '주사위 2: 6, 고정' })).toBeInTheDocument();
  });

  it('cannot hold before the first roll and cannot roll with all five held', () => {
    seed({ dice: [5, 5, 5, 5, 5], held: [true, true, true, true, true], rolls: 2 });
    renderTable();
    expect(screen.getByRole('button', { name: /굴리기/ })).toBeDisabled();
  });

  it('candidates in my column are buttons that record; a zero asks first', () => {
    seed({ dice: [3, 3, 3, 5, 5], rolls: 1, candidates: { FULL_HOUSE: 19, YACHT: 0 } });
    renderTable();
    fireEvent.click(screen.getByRole('button', { name: '풀 하우스에 19점 기록' }));
    expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'SCORE', category: 'FULL_HOUSE' });

    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    fireEvent.click(screen.getByRole('button', { name: '요트에 0점 기록' }));
    expect(confirm).toHaveBeenCalled();
    expect(sendAction).toHaveBeenCalledTimes(1);
    confirm.mockRestore();
  });

  it("on someone else's turn the dice are view-only and candidates are dim text", () => {
    seed({ turnSeat: 1, dice: [1, 2, 3, 4, 5], rolls: 1, candidates: { LARGE_STRAIGHT: 30 } });
    renderTable();
    expect(screen.queryByRole('button', { name: /굴리기/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '주사위 1: 1' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: /기록/ })).not.toBeInTheDocument();
    const card = screen.getByRole('table', { name: '점수표' });
    expect(within(card).getByText('30')).toHaveClass('yt-cell-candidate');
    expect(screen.getByText(/준호 차례 · 굴림 1\/3/)).toBeInTheDocument();
  });

  it('a spectator gets no controls', () => {
    seed({ dice: [1, 2, 3, 4, 5], rolls: 1, candidates: { CHOICE: 15 } });
    renderTable({ spectator: true, myUserId: 99 });
    expect(screen.queryByRole('button', { name: /굴리기/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /기록/ })).not.toBeInTheDocument();
  });

  it('the scorecard shows written scores, the upper subtotal and the bonus', () => {
    seed({
      seats: [seat(0, { scores: { SIXES: 30, FIVES: 25, FOURS: 8 }, upperSubtotal: 63, bonus: 35, total: 98 }), seat(1)],
      turnSeat: 1,
    });
    renderTable();
    const card = screen.getByRole('table', { name: '점수표' });
    expect(within(card).getByText('63/63')).toBeInTheDocument();
    expect(within(card).getByText('+35')).toBeInTheDocument();
    expect(within(card).getByText('98')).toBeInTheDocument();
  });

  it('shows the result with ranks and a solo result without them', async () => {
    seed({
      phase: 'ENDED',
      turnSeat: -1,
      result: {
        reason: 'FINISHED',
        placements: [
          { seat: 1, rank: 1, total: 200, deserted: false },
          { seat: 0, rank: 2, total: 150, deserted: false },
        ],
      },
    });
    const { unmount } = renderTable({ onBackToRoom: vi.fn() });
    const end = screen.getByRole('region', { name: '게임 결과' });
    expect(within(end).getByText('1위')).toBeInTheDocument();
    expect(within(end).getByText(/민지 \(나\)/)).toBeInTheDocument();
    expect(within(end).getByRole('button', { name: '대기실로' })).toBeInTheDocument();
    unmount();

    seed({
      seats: [seat(0)],
      phase: 'ENDED',
      turnSeat: -1,
      result: { reason: 'FINISHED', placements: [{ seat: 0, rank: 1, total: 180, deserted: false }] },
    });
    render(<YachtTable roomId="r-1" playerIds={[10]} myUserId={10} usernames={NAMES} />);
    expect(screen.getByText('총점 180점')).toBeInTheDocument();
    expect(screen.getByText('혼자 한 판은 승패 없이 최고 기록에만 남습니다.')).toBeInTheDocument();
    // D-201 — 시작 때 최고 150점 → 180점은 새 기록.
    expect(await screen.findByText('새 최고 기록! (이전 150점)')).toBeInTheDocument();
  });

  it('counts down the whole turn and asks for a resync once when the turn seems stuck', () => {
    vi.useFakeTimers();
    vi.setSystemTime(1_000_000);
    vi.spyOn(Math, 'random').mockReturnValue(0);
    seed({ turnRemainingMs: 3_000 });
    renderTable({ turnSeconds: 45 });
    expect(screen.getByRole('timer')).toHaveAccessibleName('이번 차례 남은 시간 3초');
    act(() => {
      vi.advanceTimersByTime(3_000 + STALE_TURN_GRACE_MS + STALE_TURN_JITTER_MS);
    });
    expect(requestResync).toHaveBeenCalledTimes(1);
    vi.mocked(Math.random).mockRestore();
  });

  it('a disconnected opponent can be kicked by the host', () => {
    seed();
    useYachtStore.getState().applyEvent({ type: 'PLAYER_DISCONNECTED', seq: 2, payload: { seat: 1 } });
    const onKick = vi.fn();
    renderTable({ onKick });
    fireEvent.click(screen.getByRole('button', { name: '내보내기' }));
    expect(onKick).toHaveBeenCalledWith(20);
  });

  it('a roll refused as BUSY is sent once more, a second BUSY shows the message', () => {
    vi.useFakeTimers();
    seed({ dice: [1, 2, 3, 4, 5], rolls: 1 });
    renderTable();
    fireEvent.click(screen.getByRole('button', { name: /굴리기 · 2번 남음/ }));
    expect(sendAction).toHaveBeenCalledTimes(1);
    act(() => {
      yachtRoomSink.applyPrivateEvent({ type: 'ERROR', payload: { code: 'BUSY', message: 'busy' } } as never);
    });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(BUSY_RETRY_DELAY_MS);
    });
    expect(sendAction).toHaveBeenCalledTimes(2);
    expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'ROLL', held: [false, false, false, false, false] });
    act(() => {
      yachtRoomSink.applyPrivateEvent({ type: 'ERROR', payload: { code: 'BUSY', message: 'busy' } } as never);
    });
    expect(screen.getByRole('alert')).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(BUSY_RETRY_DELAY_MS * 3);
    });
    expect(sendAction).toHaveBeenCalledTimes(2);
  });

  it('a BUSY with nothing pending (or after the result came) is shown as an error', () => {
    seed({ dice: [3, 3, 3, 5, 5], rolls: 1, candidates: { CHOICE: 19 } });
    renderTable();
    fireEvent.click(screen.getByRole('button', { name: '초이스에 19점 기록' }));
    act(() => {
      useYachtStore.getState().applyEvent({
        type: 'SCORED',
        seq: 2,
        payload: { seat: 0, category: 'CHOICE', points: 19, upperSubtotal: 0, bonus: 0, total: 19, auto: false },
      });
      yachtRoomSink.applyPrivateEvent({ type: 'ERROR', payload: { code: 'BUSY', message: 'busy' } } as never);
    });
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(sendAction).toHaveBeenCalledTimes(1);
  });

  it('does not send while disconnected', () => {
    socketConnected = false;
    seed();
    renderTable();
    expect(screen.getByRole('button', { name: /굴리기/ })).toBeDisabled();
  });

  it('D-202 — 모두 같이: 내 주사위는 가운데에서 굴리고, 남의 주사위는 좌석 띠에 작게, 기록했으면 기다림', () => {
    seed({
      turnSeat: -1,
      simultaneous: true,
      turns: [
        { seat: 0, dice: null, held: NONE, rolls: 0, pending: true, candidates: {} },
        { seat: 1, dice: [6, 6, 5, 5, 1], held: [true, true, false, false, false], rolls: 1, pending: true, candidates: {} },
      ],
    });
    const { unmount } = renderTable({ turnSeconds: 45 });
    // D-186 — 진행 방식은 ⋮ 메뉴의 방 정보 줄에.
    expect(menuInfoText()).toContain('모두 같이');
    closeGameMenu();
    expect(screen.getByText('내 차례')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: '주사위 6, 6, 5, 5, 1 · 굴림 1/3' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /굴리기/ }));
    expect(sendAction).toHaveBeenCalledWith(expect.objectContaining({ '@action': 'ROLL' }));
    unmount();

    seed({
      turnSeat: -1,
      simultaneous: true,
      turns: [
        { seat: 0, dice: null, held: NONE, rolls: 0, pending: false, candidates: {} },
        { seat: 1, dice: null, held: NONE, rolls: 0, pending: true, candidates: {} },
      ],
    });
    renderTable({ turnSeconds: 45 });
    expect(screen.queryByText('내 차례')).toBeNull();
    expect(screen.getByText('기록했어요 · 다른 사람을 기다리는 중')).toBeInTheDocument();
    expect(screen.getByText('기록함 ✓')).toBeInTheDocument();
  });

  it('모두 같이: 주사위 줄이 없는 좌석도 줄 자리를 남겨 좌석 띠 높이가 바뀌지 않는다 — 차례대로면 자리가 없다', () => {
    seed({
      turnSeat: -1,
      simultaneous: true,
      turns: [
        { seat: 0, dice: null, held: NONE, rolls: 0, pending: true, candidates: {} },
        { seat: 1, dice: null, held: NONE, rolls: 0, pending: false, candidates: {} },
      ],
    });
    const { container, unmount } = renderTable({ turnSeconds: 45 });
    // 나(0)와 기록한 좌석(1) 모두 빈 줄 자리를 가진다.
    expect(container.querySelectorAll('.yt-seat-dice-empty')).toHaveLength(2);
    unmount();

    seed();
    const again = renderTable();
    expect(again.container.querySelectorAll('.yt-seat-dice-empty')).toHaveLength(0);
  });
});
