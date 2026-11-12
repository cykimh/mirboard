import { queryMenuItem, clickMenuItem } from '@/test/gameMenu';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './testPointerEvent';
import { OmokTable } from './OmokTable';
import { SLAM_HOLD_MS } from './OmokPlaceButton';
import { useOmokStore } from './omokStore';
import { useAuthStore } from '@/features/auth/authStore';
import type { OmokMatchResult, OmokMove, OmokTableView } from '@/types/omok';

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

const PLAYERS = [10, 20];
const NAMES = { 10: '가람', 20: '나래' };

function seed(over: Partial<OmokTableView> = {}) {
  const tableView: OmokTableView = {
    mode: 'SOLO',
    rule: 'RENJU',
    seatColors: ['BLACK', 'WHITE'],
    moves: [],
    turnSeat: 0,
    forbidden: [],
    timeouts: [0, 0],
    result: null,
    ...over,
  };
  act(() => {
    useOmokStore.getState().reset('r1');
    useOmokStore.getState().applySnapshot({
      roomId: 'r1',
      phase: 'PLAYING',
      eventSeq: 1,
      tableView,
      privateHand: null,
      turnRemainingMs: null,
    });
  });
}

function renderTable(props: Partial<React.ComponentProps<typeof OmokTable>> = {}) {
  const utils = render(
    <OmokTable roomId="r1" playerIds={PLAYERS} myUserId={10} usernames={NAMES} turnSeconds={30} onExit={vi.fn()} {...props} />,
  );
  const svg = utils.container.querySelector('svg.om-board') as SVGSVGElement;
  svg.getBoundingClientRect = () => ({ left: 0, top: 0, width: 380, height: 380, right: 380, bottom: 380, x: 0, y: 0, toJSON: () => ({}) });
  return { ...utils, svg };
}

const tap = (svg: Element, x: number, y: number) => {
  const point = { clientX: 10 + x * 20, clientY: 10 + y * 20, pointerId: 1, pointerType: 'touch' };
  fireEvent.pointerDown(svg, point);
  fireEvent.pointerUp(svg, point);
};

describe('OmokTable (S4)', () => {
  beforeEach(() => {
    sendAction.mockClear();
    requestResync.mockClear();
    socketConnected = true;
    useAuthStore.setState({ token: 't' } as never);
  });
  afterEach(() => vi.restoreAllMocks());

  it('내 차례에 판을 누르면 미리보기, 다시 누르면 PLACE_STONE 을 보낸다', () => {
    seed();
    const { svg } = renderTable();
    expect(screen.getByText('내 차례예요 — 둘 자리를 누르세요')).toBeInTheDocument();
    tap(svg, 7, 7);
    expect(screen.getByTestId('om-preview')).toBeInTheDocument();
    expect(sendAction).not.toHaveBeenCalled();
    tap(svg, 7, 7);
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'PLACE_STONE', x: 7, y: 7 });
    expect(useOmokStore.getState().pending).toEqual({ x: 7, y: 7 });
  });

  it('[여기에 두기]는 미리보기가 있을 때만 켜지고 짧게 누르면 보통으로 보낸다', () => {
    seed();
    const { svg } = renderTable();
    const button = screen.getByRole('button', { name: /여기에 두기/ });
    expect(button).toBeDisabled();
    tap(svg, 3, 4);
    expect(button).toBeEnabled();
    fireEvent.pointerDown(button, { pointerId: 2, pointerType: 'touch' });
    fireEvent.pointerUp(button, { pointerId: 2, pointerType: 'touch' });
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'PLACE_STONE', x: 3, y: 4 });
  });

  it('흑 금수 자리의 미리보기는 이유를 보이고 확정하지 않는다', () => {
    seed({ forbidden: [{ x: 5, y: 5, kind: 'DOUBLE_THREE' }] });
    const { svg } = renderTable();
    tap(svg, 5, 5);
    expect(screen.getByText('33 금수예요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /여기에 두기/ })).toBeDisabled();
    tap(svg, 5, 5);
    expect(sendAction).not.toHaveBeenCalled();
  });

  it('상대 차례에는 아무것도 둘 수 없고 누구 차례인지 보인다', () => {
    seed({ turnSeat: 1 });
    const { svg } = renderTable();
    expect(screen.getByText('나래(백) 차례')).toBeInTheDocument();
    tap(svg, 7, 7);
    tap(svg, 7, 7);
    expect(sendAction).not.toHaveBeenCalled();
  });

  it('관전자에게는 조작 줄이 없다', () => {
    seed();
    renderTable({ spectator: true, myUserId: 99 });
    expect(screen.queryByRole('button', { name: '여기에 두기' })).toBeNull();
    expect(queryMenuItem('기권')).toBeNull();
  });

  it('기권은 확인 뒤에 RESIGN 을 보낸다(2:2 는 팀 전체라고 알린다)', () => {
    seed({ mode: 'TEAMS_2V2', seatColors: ['BLACK', 'WHITE', 'BLACK', 'WHITE'], timeouts: [0, 0, 0, 0] });
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    renderTable({ playerIds: [10, 20, 30, 40] });
    clickMenuItem('기권');
    expect(confirm.mock.calls[0][0]).toContain('팀 전체');
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'RESIGN' });
  });

  it('2:2 차례 띠는 좌석 네 명을 보이고 방금 둔 사람을 알린다', () => {
    const moves: OmokMove[] = [{ seat: 2, x: 7, y: 7, timedOut: true }];
    seed({ mode: 'TEAMS_2V2', seatColors: ['BLACK', 'WHITE', 'BLACK', 'WHITE'], moves, turnSeat: 3, timeouts: [0, 0, 1, 0] });
    renderTable({ playerIds: [10, 20, 30, 40], usernames: { 10: '가', 20: '나', 30: '다', 40: '라' } });
    expect(screen.getByRole('list', { name: '차례 순서' }).querySelectorAll('li')).toHaveLength(4);
    expect(screen.getByText(/방금 다\(시간 초과\)/)).toBeInTheDocument();
  });

  it('끝난 판은 결과와 [대기실로]를 보인다', () => {
    const result: OmokMatchResult = {
      winnerColor: 'BLACK',
      winnerSeats: [0],
      reason: 'FIVE',
      causeSeat: -1,
      winLine: [
        { x: 3, y: 7 },
        { x: 7, y: 7 },
      ],
    };
    seed({ result, turnSeat: -1 });
    const back = vi.fn();
    renderTable({ roomFinished: true, onBackToRoom: back });
    expect(screen.getByText('이겼어요!')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '대기실로' }));
    expect(back).toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: '여기에 두기' })).toBeNull();
  });

  it('연결이 끊기면 둘 수 없다', () => {
    socketConnected = false;
    seed();
    const { svg } = renderTable();
    tap(svg, 7, 7);
    tap(svg, 7, 7);
    expect(sendAction).not.toHaveBeenCalled();
  });
  describe('D-195 — [두기] 길게 누르기 = 강하게 두기(횟수 제한 없음)', () => {
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => vi.useRealTimers());

    it('끝까지(SLAM_HOLD_MS) 누르고 있으면 게이지가 차는 순간 slam:true 로 보내고, 뗄 때 또 보내지 않는다', () => {
      seed();
      const { svg } = renderTable();
      tap(svg, 9, 9);
      const button = screen.getByRole('button', { name: /여기에 두기/ });
      fireEvent.pointerDown(button, { pointerId: 2, pointerType: 'touch' });
      expect(button.className).toContain('om-charging');
      expect(screen.getByText(/끝까지 누르고 있으면 강하게!/)).toBeInTheDocument();
      act(() => vi.advanceTimersByTime(SLAM_HOLD_MS - 1));
      expect(sendAction).not.toHaveBeenCalled();
      act(() => vi.advanceTimersByTime(1));
      expect(sendAction).toHaveBeenCalledWith({ '@action': 'PLACE_STONE', x: 9, y: 9, slam: true });
      expect(useOmokStore.getState().pendingSlam).toBe(true);
      fireEvent.pointerUp(button, { pointerId: 2, pointerType: 'touch' });
      expect(sendAction).toHaveBeenCalledTimes(1);
      // 내 화면에도 발동 배너(상대 화면과 같은 배너).
      expect(screen.getByTestId('om-slam-banner')).toHaveTextContent('흑 강하게 두기!');
    });

    it('다 차기 전에 떼면 보통 두기, 버튼 밖으로 빠지면 아무것도 두지 않는다', () => {
      seed();
      const { svg } = renderTable();
      tap(svg, 9, 9);
      const button = screen.getByRole('button', { name: /여기에 두기/ });
      fireEvent.pointerDown(button, { pointerId: 2, pointerType: 'touch' });
      fireEvent.pointerLeave(button, { pointerId: 2, pointerType: 'touch' });
      act(() => vi.advanceTimersByTime(SLAM_HOLD_MS * 2));
      expect(sendAction).not.toHaveBeenCalled();
      fireEvent.pointerDown(button, { pointerId: 3, pointerType: 'touch' });
      act(() => vi.advanceTimersByTime(SLAM_HOLD_MS / 2));
      fireEvent.pointerUp(button, { pointerId: 3, pointerType: 'touch' });
      expect(sendAction).toHaveBeenCalledWith({ '@action': 'PLACE_STONE', x: 9, y: 9 });
    });

    it('키보드: Space 를 길게 누르면 강하게, 짧게 누르면 보통 — 문맥 메뉴는 막는다', () => {
      seed({ moves: [], turnSeat: 0 });
      const { svg } = renderTable();
      tap(svg, 9, 9);
      const button = screen.getByRole('button', { name: /여기에 두기/ });
      expect(fireEvent.contextMenu(button)).toBe(false);
      fireEvent.keyDown(button, { key: ' ' });
      fireEvent.keyDown(button, { key: ' ', repeat: true });
      act(() => vi.advanceTimersByTime(SLAM_HOLD_MS));
      expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'PLACE_STONE', x: 9, y: 9, slam: true });
    });

    it('강하게 두기는 몇 번이든 된다 — 남은 횟수·스킬 표시가 없다', () => {
      seed({ moves: [{ seat: 0, x: 0, y: 0, timedOut: false, }, { seat: 1, x: 1, y: 0, timedOut: false }], turnSeat: 0 });
      act(() => useOmokStore.setState({ slamMoves: new Set([1, 2]) }));
      const { svg } = renderTable();
      expect(screen.queryByTestId('om-seat-skill')).toBeNull();
      expect(screen.queryByText(/사용함|1\/1/)).toBeNull();
      tap(svg, 9, 9);
      const button = screen.getByRole('button', { name: /여기에 두기/ });
      expect(button).toBeEnabled();
      fireEvent.pointerDown(button, { pointerId: 2, pointerType: 'mouse', button: 0 });
      act(() => vi.advanceTimersByTime(SLAM_HOLD_MS));
      expect(sendAction).toHaveBeenCalledWith({ '@action': 'PLACE_STONE', x: 9, y: 9, slam: true });
    });

    it('상대가 강하게 두면(STONE_PLACED.slam) 내 화면에도 발동 배너가 뜬다', () => {
      seed({ moves: [{ seat: 0, x: 9, y: 9, timedOut: false }], turnSeat: 1 });
      renderTable();
      expect(screen.queryByTestId('om-slam-banner')).toBeNull();
      act(() => {
        useOmokStore.getState().applyEvent({
          type: 'STONE_PLACED',
          seq: 2,
          payload: { seat: 1, color: 'WHITE', x: 10, y: 9, moveNumber: 2, timedOut: false, slam: true },
        });
      });
      const banner = screen.getByTestId('om-slam-banner');
      expect(banner).toHaveTextContent('백 강하게 두기!');
      expect(banner).toHaveTextContent('나래');
    });
  });

  describe('D-194 — 종료 배너(모두에게 같은 배너)', () => {
    const FIVE = [0, 1, 2, 3, 4].map((x) => ({ x: 3 + x, y: 4 }));
    const end = (over: Partial<OmokMatchResult>) =>
      act(() => {
        useOmokStore.getState().applyEvent({
          type: 'MATCH_ENDED',
          seq: 2,
          payload: { winnerColor: 'WHITE', winnerSeats: [1], reason: 'FIVE', causeSeat: -1, winLine: FIVE, ...over },
        });
      });

    it('진 쪽 화면에도 "백 5목!"과 이긴 사람 이름이 뜨고, 승리 줄이 위쪽이면 아래에 뜬다', () => {
      seed({ turnSeat: 1 });
      renderTable(); // 나는 흑(가람) — 진 쪽
      expect(screen.queryByTestId('om-end-banner')).toBeNull();
      end({});
      const banner = screen.getByTestId('om-end-banner');
      expect(banner).toHaveTextContent('백 5목!');
      expect(banner).toHaveTextContent('나래');
      expect(banner.getAttribute('class')).toContain('om-end-banner-five');
      expect(banner.getAttribute('class')).toContain('om-end-banner-white');
      expect(banner.getAttribute('class')).toContain('om-end-banner-low');
    });

    it('기권은 작은 띠("흑 승" + 누가 기권), 무승부는 "무승부" — 5목 배너 모양이 아니다', () => {
      seed({ turnSeat: 1 });
      const first = renderTable();
      end({ winnerColor: 'BLACK', winnerSeats: [0], reason: 'RESIGN', causeSeat: 1, winLine: [] });
      const quiet = screen.getByTestId('om-end-banner');
      expect(quiet).toHaveTextContent('흑 승');
      expect(quiet).toHaveTextContent('나래 기권');
      expect(quiet.getAttribute('class')).toContain('om-end-banner-quiet');
      expect(quiet.getAttribute('class')).not.toContain('om-end-banner-five');
      first.unmount();

      seed({ turnSeat: 1 });
      renderTable();
      end({ winnerColor: null, winnerSeats: [], reason: 'BOARD_FULL', causeSeat: -1, winLine: [] });
      const draw = screen.getByTestId('om-end-banner');
      expect(draw).toHaveTextContent('무승부');
      expect(draw.getAttribute('class')).toContain('om-end-banner-draw');
    });

    it('이미 끝난 판에 들어오면 배너 없이 현판만 보인다', () => {
      seed({
        turnSeat: -1,
        result: { winnerColor: 'WHITE', winnerSeats: [1], reason: 'FIVE', causeSeat: -1, winLine: FIVE },
      });
      renderTable();
      expect(screen.queryByTestId('om-end-banner')).toBeNull();
      expect(screen.getByRole('status', { name: '판 결과' })).toBeInTheDocument();
    });
  });
});
