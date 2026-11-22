import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '@/features/auth/authStore';
import { StarSquareTable } from './StarSquareTable';
import { useStarSquareStore } from './starSquareStore';
import { LO_SHU, seat, seedStore } from './starSquareTestFixtures';
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
    expect(screen.getAllByRole('gridcell')).toHaveLength(9);
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

  it('스토어가 다시 받기를 청하면 훅의 resync 를 부른다', () => {
    seedStore();
    renderTable();
    act(() => st().requestResync());
    expect(requestResync).toHaveBeenCalled();
  });
});
