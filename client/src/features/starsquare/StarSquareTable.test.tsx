import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '@/features/auth/authStore';
import { StarSquareTable } from './StarSquareTable';
import { RESET_CONFIRM_MS } from './NumberPad';
import { starSquareRoomSink } from './starSquareRoomSink';
import { useStarSquareStore } from './starSquareStore';
import { LO_SHU, LO_SHU_CLUES, seat, seedStore } from './starSquareTestFixtures';
import { SYNC_THROTTLE_MS } from './useSyncSender';

const sendAction = vi.fn((_: Record<string, unknown>) => true);
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
const st = () => useStarSquareStore.getState();

function renderTable(props: Partial<React.ComponentProps<typeof StarSquareTable>> = {}) {
  return render(
    <StarSquareTable roomId="r1" playerIds={PLAYERS} myUserId={10} usernames={NAMES} onExit={vi.fn()} {...props} />,
  );
}

const cell = (r: number, c: number) => screen.getByRole('gridcell', { name: new RegExp(`^${r}행 ${c}열`) });

describe('StarSquareTable (S3)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    sendAction.mockClear();
    requestResync.mockClear();
    socketConnected = true;
    useAuthStore.setState({ token: 't' } as never);
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('내 판·숫자판·모두의 진행 — 남의 판 숫자는 없다', () => {
    seedStore({ seats: [seat(0), seat(1, { filled: 5, lines: 1 })] });
    renderTable();
    expect(screen.getByRole('grid', { name: '내 판' })).toBeInTheDocument();
    expect(screen.getAllByRole('gridcell', { name: /^\d행 \d열/ })).toHaveLength(9); // 별 칸(줄 합 칩도 gridcell)
    expect(cell(1, 1)).toHaveAccessibleName(/2 \(단서\)/);
    expect(cell(1, 1)).toBeDisabled();
    expect(screen.getByRole('button', { name: '9' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^2 \(쓰는 중/ })).toBeInTheDocument();
    expect(screen.getByLabelText('맞은 줄 1 / 8')).toBeInTheDocument();
    expect(screen.getByRole('timer', { name: '남은 시간' })).toHaveTextContent('7:00');
  });

  it('칸을 누르고 숫자를 누르면 바로 화면, 판 전체는 모아서 SYNC 로', () => {
    seedStore();
    renderTable();
    fireEvent.click(cell(1, 2));
    fireEvent.click(screen.getByRole('button', { name: '7' }));
    expect(cell(1, 2)).toHaveAccessibleName(/^1행 2열 7/);
    act(() => vi.advanceTimersByTime(0));
    expect(sendAction).toHaveBeenLastCalledWith({
      '@action': 'SYNC',
      puzzle: 0,
      version: 1,
      grid: [2, 7, 0, 0, 0, 1, 0, 0, 0],
    });
    fireEvent.click(cell(1, 3));
    fireEvent.click(screen.getByRole('button', { name: '6' }));
    fireEvent.click(screen.getByRole('button', { name: '지우기' }));
    fireEvent.click(screen.getByRole('button', { name: '되돌리기' }));
    expect(cell(1, 3)).toHaveAccessibleName(/^1행 3열 6/);
    act(() => vi.advanceTimersByTime(SYNC_THROTTLE_MS));
    expect(sendAction).toHaveBeenCalledTimes(2); // 세 번 고쳤지만 한 번에
    expect(sendAction.mock.calls[1][0]).toMatchObject({ version: 2, grid: [2, 7, 6, 0, 0, 1, 0, 0, 0] });
  });

  it('키보드 — 두 자리 수는 이어 치면 한 수, 방향키는 단서를 건너뛰고, Backspace 는 지운다', () => {
    const clues = Array(16).fill(0);
    clues[0] = 16;
    clues[1] = 3;
    seedStore(
      { size: 4, magicSum: 34, clues, seats: [seat(0, { filled: 2 }), seat(1, { filled: 2 })] },
      { puzzleIndex: 0, grid: clues, version: 0, solved: false },
    );
    renderTable();
    expect(st().selected).toBe(2);
    fireEvent.keyDown(window, { key: '1' });
    fireEvent.keyDown(window, { key: '2' });
    expect(st().board[2]).toBe(12);
    fireEvent.keyDown(window, { key: 'ArrowLeft' }); // 1·0 번은 단서 — 갈 곳이 없어 그대로
    expect(st().selected).toBe(2);
    fireEvent.keyDown(window, { key: 'ArrowRight' });
    expect(st().selected).toBe(3);
    fireEvent.keyDown(window, { key: '9' });
    expect(st().board[3]).toBe(9);
    fireEvent.keyDown(window, { key: 'Backspace' });
    expect(st().board[3]).toBe(0);
    fireEvent.keyDown(window, { key: 'z', ctrlKey: true });
    expect(st().board[3]).toBe(9);
  });

  /** 리뷰 5 — 방향키로 고른 칸에 DOM 포커스도 간다(roving tabindex). */
  it('방향키로 옮기면 고른 칸이 포커스를 받는다 — 탭 순서에 드는 칸도 하나', () => {
    seedStore();
    renderTable();
    expect(st().selected).toBe(1);
    fireEvent.keyDown(window, { key: 'ArrowRight' });
    expect(st().selected).toBe(2);
    expect(document.activeElement).toBe(cell(1, 3));
    fireEvent.keyDown(window, { key: 'ArrowDown' });
    expect(st().selected).toBe(5 + 3); // 2행 3열(5)은 단서 — 건너뛰어 3행 3열
    expect(document.activeElement).toBe(cell(3, 3));
    const tabbable = screen.getAllByRole('gridcell').filter((el) => el.getAttribute('tabindex') === '0');
    expect(tabbable).toEqual([cell(3, 3)]);
  });

  /** 리뷰 4 — 규칙 다이얼로그·게임 메뉴가 열려 있으면 판 키보드는 쉰다(뒤의 판을 고치거나 SYNC 를 내보내지 않는다). */
  it('게임 메뉴·규칙 다이얼로그가 열려 있으면 숫자·Backspace·방향키가 판을 고치지 않는다', () => {
    seedStore();
    renderTable();
    fireEvent.keyDown(window, { key: '7' });
    expect(st().board[1]).toBe(7);
    act(() => vi.advanceTimersByTime(SYNC_THROTTLE_MS));
    sendAction.mockClear();

    fireEvent.click(screen.getByRole('button', { name: '게임 메뉴' }));
    const menu = screen.getByRole('menu', { name: '게임 메뉴' });
    fireEvent.keyDown(menu, { key: '9' });
    fireEvent.keyDown(menu, { key: 'Backspace' });
    fireEvent.keyDown(window, { key: 'ArrowRight' }); // 포커스가 메뉴 밖에 남아도 층이 열려 있으면 쉰다
    expect(st().board[1]).toBe(7);
    expect(st().selected).toBe(1);

    fireEvent.click(within(menu).getByRole('menuitem', { name: '규칙 보기' }));
    const dialog = screen.getByRole('dialog');
    fireEvent.keyDown(dialog, { key: 'Backspace' });
    fireEvent.keyDown(dialog, { key: '3' });
    expect(st().board[1]).toBe(7);
    act(() => vi.advanceTimersByTime(SYNC_THROTTLE_MS * 3));
    expect(sendAction).not.toHaveBeenCalled();
  });

  /** 리뷰 6 — 줄 합 칩의 상태(맞음·넘침·모자람)가 보조기기에 읽힌다. */
  it('줄 합 칩은 줄·합·상태를 읽는 글자로 낸다', () => {
    seedStore({}, { puzzleIndex: 0, grid: [2, 7, 6, 3, 4, 1, 0, 0, 0], version: 1, solved: false });
    renderTable();
    expect(screen.getByText('1행 합 15, 맞음')).toBeInTheDocument();
    expect(screen.getByText('2행 합 8, 모자람')).toBeInTheDocument();
    expect(screen.getByText('3행 비었음')).toBeInTheDocument();
    expect(screen.getByText('1열 합 5, 채우는 중')).toBeInTheDocument();
    // 가로 줄 칩은 grid 의 row 안 — gridcell 로 읽힌다
    expect(screen.getByRole('gridcell', { name: '1행 합 15, 맞음' })).toBeInTheDocument();
  });

  it('관전자는 단서 판만 보고 숫자판이 없다', () => {
    seedStore({}, null, -1);
    renderTable({ spectator: true });
    expect(screen.getByRole('grid', { name: '퍼즐(단서)' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '지우기' })).toBeNull();
    expect(cell(1, 2)).toBeDisabled();
    expect(screen.getByText(/관전 중 — 모두의 진행만/)).toBeInTheDocument();
  });

  it('완성한 뒤에는 판이 잠기고 기다린다는 안내', () => {
    seedStore({ seats: [seat(0, { solved: true, solvePlace: 1, elapsedMs: 42_000, filled: 9, lines: 8 }), seat(1)] }, {
      puzzleIndex: 0,
      grid: LO_SHU,
      version: 3,
      solved: true,
    });
    renderTable();
    expect(screen.queryByRole('button', { name: '지우기' })).toBeNull();
    expect(screen.getByText(/완성! 1번째/)).toBeInTheDocument();
    expect(screen.getByLabelText('1번째 완성, 0:42')).toBeInTheDocument();
  });

  it('퍼즐 결과 — 순위·점수와 모두의 판·정답 하나, 다음 퍼즐까지', () => {
    seedStore({
      phase: 'RESULT',
      remainingMs: 8_000,
      lastPuzzle: {
        index: 0,
        reason: 'CLOSED',
        results: [
          { seat: 0, rank: 2, points: 0, solved: false, elapsedMs: -1, filled: 6, lines: 2, deserted: false },
          { seat: 1, rank: 1, points: 1, solved: true, elapsedMs: 61_000, filled: 9, lines: 8, deserted: false },
        ],
        grids: [[2, 7, 6, 0, 5, 1, 0, 0, 0], LO_SHU],
        solution: LO_SHU,
      },
    });
    renderTable();
    const result = screen.getByRole('region', { name: '퍼즐 1 결과' });
    expect(within(result).getByText('첫 완성 뒤 마감')).toBeInTheDocument();
    expect(within(result).getByText('완성 1:01')).toBeInTheDocument();
    expect(within(result).getByText('✦ 2줄 · 6칸')).toBeInTheDocument();
    expect(within(result).getByRole('grid', { name: '정답 하나' })).toBeInTheDocument();
    expect(within(result).getByRole('grid', { name: '나래의 판' })).toBeInTheDocument();
    expect(within(result).getByText(/다음 퍼즐까지 8초/)).toBeInTheDocument();
    // 리뷰 7 — 매초 바뀌는 글자라 알림 영역이 아니다(1초마다 다시 읽지 않게)
    expect(within(result).getByText(/다음 퍼즐까지 8초/)).toHaveAttribute('aria-live', 'off');
    expect(screen.queryByRole('button', { name: '지우기' })).toBeNull();
  });

  it('매치 끝 — 점수 합 순위와 대기실로', () => {
    const onBackToRoom = vi.fn();
    seedStore({
      phase: 'ENDED',
      result: {
        reason: 'FINISHED',
        placements: [
          { seat: 1, rank: 1, points: 4, firsts: 2, deserted: false },
          { seat: 0, rank: 2, points: 2, firsts: 1, deserted: false },
        ],
      },
    });
    renderTable({ onBackToRoom });
    const end = screen.getByRole('region', { name: '판 결과' });
    expect(within(end).getByRole('heading', { name: '2위' })).toBeInTheDocument();
    expect(within(end).getByText('4점 · 1등 2번')).toBeInTheDocument();
    fireEvent.click(within(end).getByRole('button', { name: '대기실로' }));
    expect(onBackToRoom).toHaveBeenCalled();
  });

  it('D-294 전체 리셋 — 두 번 눌러야 지운다, 3초 지나거나 다른 입력이 끼면 풀린다, 되돌리기로 복구', () => {
    seedStore();
    renderTable();
    const reset = () => screen.getByRole('button', { name: /전체 리셋|한 번 더 누르면/ });
    expect(reset()).toBeDisabled(); // 지울 내 숫자가 없다
    fireEvent.click(cell(1, 2));
    fireEvent.click(screen.getByRole('button', { name: '7' }));
    fireEvent.click(cell(1, 3));
    fireEvent.click(screen.getByRole('button', { name: '6' }));
    expect(reset()).toBeEnabled();
    expect(reset()).toHaveTextContent('리셋');

    fireEvent.click(reset()); // 첫 누름 — 지우지 않는다
    expect(st().board).toEqual([2, 7, 6, 0, 0, 1, 0, 0, 0]);
    expect(reset()).toHaveTextContent('한 번 더');
    expect(screen.getByRole('status')).toHaveTextContent('한 번 더 누르면 내가 넣은 숫자를 모두 지워요.');
    act(() => vi.advanceTimersByTime(RESET_CONFIRM_MS));
    expect(reset()).toHaveTextContent('리셋'); // 시간이 지나 풀림

    fireEvent.click(reset());
    fireEvent.click(screen.getByRole('button', { name: '9' })); // 다른 입력이 끼면 풀린다
    expect(reset()).toHaveTextContent('리셋');
    expect(st().board[2]).toBe(9);

    fireEvent.click(reset());
    fireEvent.click(reset()); // 두 번째 누름 — 단서만 남는다
    expect(st().board).toEqual(LO_SHU_CLUES);
    expect(reset()).toBeDisabled();
    act(() => vi.advanceTimersByTime(SYNC_THROTTLE_MS));
    expect(sendAction).toHaveBeenLastCalledWith(expect.objectContaining({ '@action': 'SYNC', grid: LO_SHU_CLUES }));
    fireEvent.click(screen.getByRole('button', { name: '되돌리기' }));
    expect(st().board).toEqual([2, 7, 9, 0, 0, 1, 0, 0, 0]);
  });

  it('D-294 남의 판 보기 — 푸는 동안은 선택지가 없고, 완성한 뒤에만 아직 푸는 사람의 판을 골라 본다(보기 전용)', () => {
    const three = [10, 20, 30];
    const names = { 10: '가람', 20: '나래', 30: '다솜' };
    seedStore({ seats: [seat(0), seat(1), seat(2)] });
    renderTable({ playerIds: three, usernames: names });
    expect(screen.queryByRole('group', { name: '누구 판 보기' })).toBeNull();

    act(() => {
      starSquareRoomSink.applyEvent({ type: 'SOLVED', seq: 2, payload: { seat: 0, place: 1, elapsedMs: 9_000 } });
      starSquareRoomSink.applyPrivateEvent({
        eventId: 'p',
        type: 'PEER_BOARD',
        ts: 0,
        payload: { viewer: 0, puzzle: 0, seat: 1, grid: [2, 7, 6, 0, 0, 1, 0, 0, 0] },
      });
    });
    const tabs = screen.getByRole('group', { name: '누구 판 보기' });
    expect(within(tabs).getAllByRole('button').map((b) => b.textContent)).toEqual(['내 판', '나래', '다솜']);
    expect(within(tabs).getByRole('button', { name: '내 판' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByRole('grid', { name: '나래의 판' })).toBeNull();
    expect(screen.queryByRole('button', { name: '지우기' })).toBeNull(); // 완성 뒤 — 숫자판·리셋 없음

    fireEvent.click(within(tabs).getByRole('button', { name: '나래' }));
    const peer = screen.getByRole('grid', { name: '나래의 판' });
    expect(within(peer).getByRole('gridcell', { name: /^1행 2열 7/ })).toBeDisabled();
    // 줄 합은 그 사람 판 기준 — 윗줄 2+7+6 = 15 맞음
    expect(within(peer).getByText('1행 합 15, 맞음')).toBeInTheDocument();
    expect(screen.getByText(/나래의 판을 보는 중/)).toBeInTheDocument();
    // 판이 바뀌면 따라 바뀐다
    act(() =>
      starSquareRoomSink.applyPrivateEvent({
        eventId: 'p2',
        type: 'PEER_BOARD',
        ts: 0,
        payload: { viewer: 0, puzzle: 0, seat: 1, grid: [2, 7, 6, 9, 0, 1, 0, 0, 0] },
      }),
    );
    expect(within(screen.getByRole('grid', { name: '나래의 판' })).getByRole('gridcell', { name: /^2행 1열 9/ })).toBeInTheDocument();
    // 아직 판을 받지 못한 좌석은 단서 판부터
    fireEvent.click(within(tabs).getByRole('button', { name: '다솜' }));
    expect(
      within(screen.getByRole('grid', { name: '다솜의 판' })).getByRole('gridcell', { name: /^1행 2열 빈 칸/ }),
    ).toBeInTheDocument();

    // 보던 사람이 완성하면 선택지에서 빠지고 내 판으로 돌아온다
    act(() => void starSquareRoomSink.applyEvent({ type: 'SOLVED', seq: 3, payload: { seat: 2, place: 2, elapsedMs: 11_000 } }));
    expect(screen.queryByRole('grid', { name: '다솜의 판' })).toBeNull();
    const left = within(screen.getByRole('group', { name: '누구 판 보기' })).getAllByRole('button');
    expect(left.map((b) => b.textContent)).toEqual(['내 판', '나래']);
    expect(left[0]).toHaveAttribute('aria-pressed', 'true');
  });

  it('D-294 남의 판 보기 — 포커스가 있던 탭이 사라지면 내 판 탭으로 옮긴다(다른 곳에 있던 포커스는 건드리지 않는다)', () => {
    seedStore({ seats: [seat(0), seat(1), seat(2)] });
    renderTable({ playerIds: [10, 20, 30], usernames: { 10: '가람', 20: '나래', 30: '다솜' } });
    act(() => void starSquareRoomSink.applyEvent({ type: 'SOLVED', seq: 2, payload: { seat: 0, place: 1, elapsedMs: 9_000 } }));
    const tab = (name: string) => screen.getByRole('button', { name });
    fireEvent.click(tab('다솜'));
    act(() => tab('다솜').focus());
    act(() => void starSquareRoomSink.applyEvent({ type: 'SOLVED', seq: 3, payload: { seat: 2, place: 2, elapsedMs: 11_000 } }));
    expect(tab('내 판')).toHaveFocus();
    // 포커스가 채팅 단추에 있을 때 남은 탭이 사라져도 포커스는 그대로
    act(() => tab('나래').focus());
    act(() => screen.getByRole('button', { name: /^채팅/ }).focus());
    act(() => void starSquareRoomSink.applyEvent({ type: 'SEAT_DESERTED', seq: 4, payload: { seat: 1 } }));
    expect(screen.getByRole('button', { name: /^채팅/ })).toHaveFocus();
  });

  it('D-294 남의 판 보기 — 관전자에게는 선택지가 없다(판도 오지 않는다)', () => {
    seedStore({ seats: [seat(0), seat(1, { solved: true, solvePlace: 1, elapsedMs: 5_000 })] }, null, -1);
    renderTable({ spectator: true, myUserId: 99 });
    expect(screen.queryByRole('group', { name: '누구 판 보기' })).toBeNull();
    expect(screen.getByText('관전 중 — 모두의 진행만 보여요')).toBeInTheDocument();
  });

  it('D-294 줄 합 — 두 대각선 합은 판 아래 줄이 아니라 모서리 칩이고 읽는 글자는 그대로', () => {
    seedStore({}, { puzzleIndex: 0, grid: [2, 7, 6, 9, 5, 1, 4, 3, 0], version: 3, solved: false });
    const { container } = renderTable();
    expect(container.querySelector('.sq-diag-sums')).toBeNull();
    const diag = container.querySelector('.sq-sum-diag')!;
    const anti = container.querySelector('.sq-sum-anti')!;
    expect(diag).toHaveAttribute('data-state', 'partial');
    expect(diag).toHaveTextContent('↘ 대각선 합 7, 채우는 중');
    expect(anti).toHaveAttribute('data-state', 'ok');
    expect(anti).toHaveTextContent('↙ 대각선 합 15, 맞음');
    expect(container.querySelector('.sq-sum-row[data-state="ok"]')).toHaveTextContent('1행 합 15, 맞음');
  });

  it('스토어가 다시 받기를 청하면 훅의 resync 를 부른다', () => {
    seedStore();
    renderTable();
    act(() => st().requestResync());
    expect(requestResync).toHaveBeenCalled();
  });
});
