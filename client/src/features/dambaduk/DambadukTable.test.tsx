import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DambadukTable } from './DambadukTable';
import { PENDING_GUARD_MS, useDambadukStore } from './dambadukStore';
import { dambadukRoomSink } from './dambadukRoomSink';
import { dambadukSfxSeat } from './dambadukSfx';
import { useAuthStore } from '@/features/auth/authStore';
import type { DambadukMoveOption, DambadukTableView } from '@/types/dambaduk';
import { ONE_REGION, placingView, playingView, snapshot } from './dambadukFixtures';

const sendAction = vi.fn();
const requestResync = vi.fn();
vi.mock('@/ws/useStompRoom', () => ({
  useStompRoom: () => ({
    connected: true,
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
const NAMES = { 10: '가람', 20: '나래', 30: '다솜' };

function seed(view: DambadukTableView, turnRemainingMs: number | null = null) {
  act(() => {
    useDambadukStore.getState().reset('r1');
    useDambadukStore.getState().applySnapshot(snapshot(view, turnRemainingMs));
  });
}

function renderTable(props: Partial<React.ComponentProps<typeof DambadukTable>> = {}) {
  return render(
    <DambadukTable roomId="r1" playerIds={PLAYERS} myUserId={10} usernames={NAMES} turnSeconds={60} onExit={vi.fn()} {...props} />,
  );
}

const cell = (name: string) => screen.getByRole('button', { name: new RegExp(`^${name} `) });

describe('DambadukTable (S3)', () => {
  beforeEach(() => {
    sendAction.mockClear();
    requestResync.mockClear();
    useAuthStore.setState({ token: 't' } as never);
  });
  afterEach(() => vi.restoreAllMocks());

  it('배치 — 빈 칸을 누르면 흐린 돌, 한 번 더 누르면 PLACE_STONE', () => {
    seed(placingView());
    renderTable();
    expect(screen.getByText('내 차례 — 곧은돌을 놓을 빈 칸을 누르세요')).toBeInTheDocument();
    const place = screen.getByRole('button', { name: '여기에 놓기' });
    expect(place).toBeDisabled();
    fireEvent.click(cell('D4'));
    expect(screen.getByTestId('db-place-ghost')).toBeInTheDocument();
    expect(place).toBeEnabled();
    expect(sendAction).not.toHaveBeenCalled();
    fireEvent.click(cell('D4'));
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'PLACE_STONE', kind: 'STRAIGHT', x: 3, y: 3 });
    expect(useDambadukStore.getState().pending).toMatchObject({ type: 'place', x: 3, y: 3 });
  });

  it('배치 — 3인은 종류를 고르고, 다 쓴 종류는 잠긴다', () => {
    seed(placingView({ seatCount: 3, seatColors: [0, 1, 2], stones: [], placementQueue: [0, 1, 2, 2, 1, 0], placeKinds: ['BENT'] }));
    renderTable({ playerIds: [10, 20, 30] });
    const group = screen.getByRole('group', { name: '놓을 돌' });
    expect(within(group).getByRole('button', { name: /곧은돌/ })).toBeDisabled();
    expect(within(group).getByRole('button', { name: /꺾은돌/ })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(cell('A1'));
    fireEvent.click(screen.getByRole('button', { name: '여기에 놓기' }));
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'PLACE_STONE', kind: 'BENT', x: 0, y: 0 });
  });

  it('D-307 — 받은 수로 담이 서면 닿는 연출(빛·먹 고리·돌가루·판 흔들림)이 붙고, 판을 다시 그리면 붙지 않는다', () => {
    seed(playingView());
    const first = renderTable();
    const board = () => document.querySelector('.db-board')!;
    expect(board().querySelector('.db-wall-fx')).toBeNull();
    const move = (seq: number, stoneId: number, fromX: number, toX: number, y: number, seat: number) =>
      act(() => {
        useDambadukStore.getState().applyEvent({
          type: 'STONE_MOVED',
          seq,
          payload: { seat, stoneId, path: ['RIGHT'], fromX, fromY: y, toX, toY: y, wall: 'UP', wallFace: 60 + seq, jumpedFace: -1, timedOut: false },
        });
      });
    move(2, 5, 4, 5, 3, 1); // 남의 수(봇 수도 같은 이벤트)
    expect(board().querySelectorAll('.db-wall-fresh')).toHaveLength(1);
    expect(board().querySelectorAll('.db-wall-fx')).toHaveLength(1);
    expect(board().querySelectorAll('.db-wall-fx .db-wall-glow')).toHaveLength(2);
    expect(board().querySelectorAll('.db-wall-fx .db-wall-ring')).toHaveLength(1);
    expect(board().querySelectorAll('.db-wall-fx .db-wall-grit')).toHaveLength(12);
    const shake = board().className.match(/db-board-thud-[ab]/)?.[0];
    expect(shake).toBeDefined();
    // 연달아 두면 흔들림 이름이 바뀌어 다시 돈다. 연출 조각은 새 담 하나에만.
    move(3, 4, 2, 3, 3, 0);
    expect(board().className.match(/db-board-thud-[ab]/)?.[0]).not.toBe(shake);
    expect(board().querySelectorAll('.db-wall-fx')).toHaveLength(1);
    // 판을 다시 그린 것(재입장)에는 큰 연출이 돌지 않는다.
    first.unmount();
    renderTable();
    expect(board().querySelector('.db-wall-fx')).toBeNull();
    expect(board().className).not.toMatch(/db-board-thud/);
  });

  it('옮김 — 내 돌 → 갈 칸 → 담 면을 누르면 MOVE_STONE 한 번', () => {
    seed(playingView());
    renderTable();
    expect(screen.getByText('내 차례 — 옮길 돌을 누르세요')).toBeInTheDocument();
    fireEvent.click(cell('C4'));
    expect(screen.getByText('갈 칸을 누르세요')).toBeInTheDocument();
    expect(cell('C3')).toHaveAccessibleName(/갈 수 있는 칸/);
    fireEvent.click(cell('C3'));
    expect(screen.getByTestId('db-move-ghost')).toBeInTheDocument();
    const pad = screen.getByRole('group', { name: '담을 세울 면' });
    fireEvent.click(within(pad).getByRole('button', { name: '위 담' }));
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'MOVE_STONE', stoneId: 4, path: ['UP'], wall: 'UP', jump: false });
    expect(useDambadukStore.getState().pending).toMatchObject({ type: 'move', stoneId: 4, toX: 2, toY: 2 });
  });

  it('판 위 담 손잡이로도 고를 수 있고, 세울 수 없는 면은 없다', () => {
    seed(playingView());
    renderTable();
    fireEvent.click(cell('C4'));
    fireEvent.click(cell('A4'));
    expect(screen.queryByRole('button', { name: 'A4 왼쪽에 담 세우기' })).toBeNull();
    expect(within(screen.getByRole('group', { name: '담을 세울 면' })).getByRole('button', { name: '왼쪽 담' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'A4 오른쪽에 담 세우기' }));
    expect(sendAction).toHaveBeenCalledWith({
      '@action': 'MOVE_STONE',
      stoneId: 4,
      path: ['LEFT', 'LEFT'],
      wall: 'RIGHT',
      jump: false,
    });
  });

  it('[다시 고르기]로 고르던 것을 비운다', () => {
    seed(playingView());
    renderTable();
    fireEvent.click(cell('C4'));
    fireEvent.click(cell('C3'));
    fireEvent.click(screen.getByRole('button', { name: '다시 고르기' }));
    expect(useDambadukStore.getState().choice).toBeNull();
    expect(useDambadukStore.getState().selectedStoneId).toBeNull();
    expect(screen.queryByRole('group', { name: '담을 세울 면' })).toBeNull();
  });

  it('남의 차례에는 칸이 잠기고 그 사람 차례를 알린다', () => {
    seed(playingView({ turnSeat: 1, options: [] }), 30_000);
    renderTable();
    expect(screen.getByText('나래(백) 차례')).toBeInTheDocument();
    expect(cell('C4')).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(cell('C4'));
    expect(useDambadukStore.getState().selectedStoneId).toBeNull();
    expect(screen.getAllByRole('timer')).toHaveLength(1);
  });

  it('3인 담 넘기 — 켜면 담 너머 칸만 보이고, 같은 칸의 다른 길은 골라서 보낸다', () => {
    const jumpA: DambadukMoveOption = { stoneId: 4, path: ['UP', 'RIGHT'], toX: 3, toY: 2, jump: true, jumpedFace: 59, walls: ['UP', 'DOWN'] };
    const jumpB: DambadukMoveOption = { stoneId: 4, path: ['RIGHT', 'UP'], toX: 3, toY: 2, jump: true, jumpedFace: 16, walls: ['UP', 'LEFT'] };
    const plain: DambadukMoveOption = { stoneId: 4, path: ['UP'], toX: 2, toY: 2, jump: false, jumpedFace: -1, walls: ['UP'] };
    seed(
      playingView({
        seatCount: 3,
        seatColors: [0, 1, 2],
        jumpsLeft: [1, 1, 1],
        stones: [{ id: 4, seat: 0, kind: 'BENT', x: 2, y: 3 }],
        options: [plain, jumpA, jumpB],
      }),
    );
    renderTable({ playerIds: [10, 20, 30] });
    fireEvent.click(cell('C4'));
    expect(cell('C3')).toHaveAccessibleName(/갈 수 있는 칸/);
    fireEvent.click(screen.getByRole('button', { name: /담 넘기/ }));
    expect(useDambadukStore.getState().jumpMode).toBe(true);
    expect(useDambadukStore.getState().selectedStoneId).toBeNull();            // 모드가 바뀌면 다시 고른다
    fireEvent.click(cell('C4'));
    expect(cell('D3')).toHaveAccessibleName(/담 넘어 갈 수 있는 칸/);
    fireEvent.click(cell('D3'));
    const routes = screen.getByRole('group', { name: '어느 길로 갈까요' });
    fireEvent.click(within(routes).getByRole('button', { name: '오른쪽 → 위' }));
    fireEvent.click(within(screen.getByRole('group', { name: '담을 세울 면' })).getByRole('button', { name: '왼쪽 담' }));
    expect(sendAction).toHaveBeenCalledWith({
      '@action': 'MOVE_STONE',
      stoneId: 4,
      path: ['RIGHT', 'UP'],
      wall: 'LEFT',
      jump: true,
    });
  });

  it('담을 넘어야만 옮길 수 있으면 넘기가 저절로 켜지고, 넘기 수가 없는 돌은 고를 수 없다', () => {
    const only: DambadukMoveOption = { stoneId: 4, path: ['UP'], toX: 2, toY: 2, jump: true, jumpedFace: 59, walls: ['UP', 'DOWN'] };
    seed(
      playingView({
        seatCount: 3,
        seatColors: [0, 1, 2],
        jumpsLeft: [1, 1, 1],
        stones: [
          { id: 4, seat: 0, kind: 'STRAIGHT', x: 2, y: 3 },
          { id: 9, seat: 0, kind: 'BENT', x: 6, y: 6 },
        ],
        options: [only],
      }),
    );
    renderTable({ playerIds: [10, 20, 30] });
    const toggle = screen.getByRole('button', { name: /넘어야만 옮길 수 있어요/ });
    expect(toggle).toBeDisabled();
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    expect(cell('G7')).toHaveAttribute('aria-disabled', 'true');                   // 갈 칸이 없는 돌
    fireEvent.click(cell('C4'));
    expect(cell('C3')).toHaveAccessibleName(/담 넘어 갈 수 있는 칸/);
    fireEvent.click(cell('C3'));
    fireEvent.click(within(screen.getByRole('group', { name: '담을 세울 면' })).getByRole('button', { name: '아래 담' }));
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'MOVE_STONE', stoneId: 4, path: ['UP'], wall: 'DOWN', jump: true });
  });

  it('좌석 띠는 서버가 계산한 집·가장 큰 집을 보이고, 빠진 좌석을 알린다', () => {
    seed(
      playingView({
        seatCount: 3,
        seatColors: [0, 1, 2],
        turnSeat: 2,
        options: [],
        exits: [{ seat: 1, reason: 'DESERTED' }],
        territory: { ...ONE_REGION, scores: [12, 0, 7], largest: [9, 0, 7] },
      }),
    );
    renderTable({ playerIds: [10, 20, 30] });
    const strip = screen.getByRole('list', { name: '차례 순서와 점수' });
    expect(within(strip).getByText('가람 (나)').closest('li')).toHaveTextContent('집 12칸 · 큰 집 9');
    expect(within(strip).getByText('나감')).toBeInTheDocument();
  });

  it('판이 끝나면 순위표와 [대기실로]를 보인다', () => {
    const onBackToRoom = vi.fn();
    seed(
      playingView({
        phase: 'ENDED',
        turnSeat: -1,
        options: [],
        result: {
          reason: 'SEPARATED',
          standings: [
            { seat: 0, score: 21, largest: 21, rank: 1, exit: null },
            { seat: 1, score: 16, largest: 16, rank: 2, exit: null },
          ],
          winners: [0],
          draw: false,
          causeSeat: -1,
        },
      }),
    );
    renderTable({ onBackToRoom });
    const end = screen.getByRole('status', { name: '판 결과' });
    expect(within(end).getByText('이겼어요!')).toBeInTheDocument();
    expect(within(end).getAllByText('21칸', { selector: 'td' })).toHaveLength(2);
    fireEvent.click(within(end).getByRole('button', { name: '대기실로' }));
    expect(onBackToRoom).toHaveBeenCalled();
  });

  it('보낸 수가 기다림 상한 동안 소식이 없으면 흐린 수를 풀고 다시 맞춘다', () => {
    vi.useFakeTimers();
    try {
      seed(playingView());
      renderTable();
      fireEvent.click(cell('C4'));
      fireEvent.click(cell('C3'));
      fireEvent.click(within(screen.getByRole('group', { name: '담을 세울 면' })).getByRole('button', { name: '위 담' }));
      expect(useDambadukStore.getState().pending).not.toBeNull();
      expect(screen.getByText('보내는 중…')).toBeInTheDocument();
      act(() => {
        vi.advanceTimersByTime(PENDING_GUARD_MS);
      });
      expect(useDambadukStore.getState().pending).toBeNull();
      expect(requestResync).toHaveBeenCalled();
      expect(screen.getByText('내 차례 — 옮길 돌을 누르세요')).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('끊겨서 보내지 못한 수는 기다림을 걸지 않고 알린다', () => {
    seed(playingView());
    renderTable();
    sendAction.mockReturnValueOnce(false);
    fireEvent.click(cell('C4'));
    fireEvent.click(cell('C3'));
    fireEvent.click(within(screen.getByRole('group', { name: '담을 세울 면' })).getByRole('button', { name: '위 담' }));
    expect(useDambadukStore.getState().pending).toBeNull();
    expect(useDambadukStore.getState().errorMessage).toMatch(/보내지 못했어요/);
  });

  it('건너뜀 안내는 바로 다음 차례에만 붙는다', () => {
    seed(playingView({ seatCount: 3, seatColors: [0, 1, 2], turnSeat: 2, options: [] }));
    renderTable({ playerIds: [10, 20, 30] });
    const turn = (seat: number, seq: number) => ({
      type: 'TURN_CHANGED',
      seq,
      payload: { seat, phase: 'PLAYING', territory: ONE_REGION, placeKinds: [], options: [] },
    });
    act(() => {
      useDambadukStore.getState().applyEvent({ type: 'TURN_SKIPPED', seq: 2, payload: { seat: 1 } });
      useDambadukStore.getState().applyEvent(turn(2, 3));
    });
    expect(screen.getByText(/나래 — 옮길 돌이 없어 건너뜀/)).toBeInTheDocument();
    act(() => {
      useDambadukStore.getState().applyEvent(turn(0, 4));
    });
    expect(screen.queryByText(/옮길 돌이 없어 건너뜀/)).toBeNull();
  });

  it('좌석 색은 화면 읽기에 글자로 읽힌다', () => {
    seed(playingView());
    renderTable();
    const me = within(screen.getByRole('list', { name: '차례 순서와 점수' })).getByText('가람 (나)').closest('li')!;
    expect(me.querySelector('.db-dot')).toHaveAttribute('aria-hidden', 'true');
    expect(within(me).getByText('흑', { exact: false, selector: '.db-sr' })).toBeInTheDocument();
  });

  it('효과음 좌석을 알려 주고, 거절 ERROR 는 흐린 수를 지우고 안내를 띄운다', () => {
    seed(playingView());
    const { unmount } = renderTable();
    expect(dambadukSfxSeat()).toBe(0);
    act(() => {
      useDambadukStore.getState().markPending({
        type: 'move',
        stoneId: 4,
        toX: 2,
        toY: 2,
        wallFace: 51,
        jumpedFace: -1,
        at: { moveCount: 0, stoneCount: 8, sentAt: Date.now() },
      });
      dambadukRoomSink.applyPrivateEvent({ eventId: 'e', type: 'ERROR', ts: 0, payload: { code: 'WALL_IN_PATH', message: 'x' } });
    });
    expect(useDambadukStore.getState().pending).toBeNull();
    expect(useDambadukStore.getState().errorMessage).toBe('가는 길에 담이 있습니다.');
    unmount();
    expect(dambadukSfxSeat()).toBe(-1);
  });
});
