import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './testPointerEvent';
import { OmokTable } from './OmokTable';
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

  it('[여기에 두기]는 미리보기가 있을 때만 켜지고 누르면 보낸다', () => {
    seed();
    const { svg } = renderTable();
    const button = screen.getByRole('button', { name: '여기에 두기' });
    expect(button).toBeDisabled();
    tap(svg, 3, 4);
    expect(button).toBeEnabled();
    fireEvent.click(button);
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'PLACE_STONE', x: 3, y: 4 });
  });

  it('흑 금수 자리의 미리보기는 이유를 보이고 확정하지 않는다', () => {
    seed({ forbidden: [{ x: 5, y: 5, kind: 'DOUBLE_THREE' }] });
    const { svg } = renderTable();
    tap(svg, 5, 5);
    expect(screen.getByText('33 금수예요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '여기에 두기' })).toBeDisabled();
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
    expect(screen.queryByRole('button', { name: '기권' })).toBeNull();
  });

  it('기권은 확인 뒤에 RESIGN 을 보낸다(2:2 는 팀 전체라고 알린다)', () => {
    seed({ mode: 'TEAMS_2V2', seatColors: ['BLACK', 'WHITE', 'BLACK', 'WHITE'], timeouts: [0, 0, 0, 0] });
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    renderTable({ playerIds: [10, 20, 30, 40] });
    fireEvent.click(screen.getByRole('button', { name: '기권' }));
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
  it('D-193 — [강하게]를 켜고 두면 PLACE_STONE 에 slam:true 를 싣고 토글이 풀린다', () => {
    seed({ slamsLeft: [1, 1] });
    const { svg } = renderTable();
    const toggle = screen.getByRole('button', { name: /강하게/ });
    expect(toggle).toHaveTextContent('1회');
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('💥 강하게 둘 자리를 누르세요')).toBeInTheDocument();
    tap(svg, 7, 7);
    fireEvent.click(screen.getByRole('button', { name: '강하게 두기!' }));
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'PLACE_STONE', x: 7, y: 7, slam: true });
    expect(useOmokStore.getState().pendingSlam).toBe(true);
    expect(screen.getByRole('button', { name: /강하게/ })).toHaveAttribute('aria-pressed', 'false');
  });

  it('D-193 — 이미 썼으면 [강하게]가 막히고 보통 착수만 나간다', () => {
    seed({ slamsLeft: [0, 1] });
    const { svg } = renderTable();
    const toggle = screen.getByRole('button', { name: /강하게/ });
    expect(toggle).toBeDisabled();
    expect(toggle).toHaveTextContent('다 씀');
    tap(svg, 7, 7);
    tap(svg, 7, 7);
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'PLACE_STONE', x: 7, y: 7 });
  });
  it('D-193 — 미리보기를 두면 돋보기가 반대쪽 모서리에 좌표(A~T, I 없음)와 함께 뜬다', () => {
    seed();
    const { svg } = renderTable();
    tap(svg, 2, 3); // 왼쪽 위 → 돋보기는 오른쪽 아래
    const loupe = screen.getByTestId('om-loupe');
    expect(loupe.getAttribute('class')).toContain('om-loupe-bottom-right');
    expect(loupe).toHaveTextContent('C16');
    tap(svg, 9, 9); // 다른 자리로 옮기면 돋보기도 따라간다(아직 확정 아님)
    expect(screen.getByTestId('om-loupe')).toHaveTextContent('K10');
    expect(sendAction).not.toHaveBeenCalled();
  });
});
