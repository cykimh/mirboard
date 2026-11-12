import { beforeEach, describe, expect, it } from 'vitest';
import { useOmokStore } from './omokStore';
import { omokRoomSink } from './omokRoomSink';
import type { OmokTableView } from '@/types/omok';

const view = (over: Partial<OmokTableView> = {}): OmokTableView => ({
  mode: 'SOLO',
  rule: 'RENJU',
  seatColors: ['BLACK', 'WHITE'],
  moves: [],
  turnSeat: 0,
  forbidden: [],
  timeouts: [0, 0],
  result: null,
  ...over,
});

const snap = (tableView: OmokTableView, turnRemainingMs: number | null = null) => ({
  roomId: 'r1',
  phase: 'PLAYING',
  eventSeq: 3,
  tableView,
  privateHand: null,
  turnRemainingMs,
});

const placed = (seat: number, x: number, y: number, moveNumber: number, color: 'BLACK' | 'WHITE' = 'BLACK') => ({
  type: 'STONE_PLACED',
  seq: moveNumber,
  payload: { seat, color, x, y, moveNumber, timedOut: false },
});

describe('omokStore', () => {
  beforeEach(() => useOmokStore.getState().reset('r1'));

  it('스냅샷을 그대로 반영한다 — 비공개 뷰(privateHand)가 없어도 된다', () => {
    useOmokStore.getState().applySnapshot(snap(view({ moves: [{ seat: 0, x: 7, y: 7, timedOut: false }], turnSeat: 1 }), 12_000));
    const s = useOmokStore.getState();
    expect(s.mode).toBe('SOLO');
    expect(s.moves).toHaveLength(1);
    expect(s.turnSeat).toBe(1);
    expect(s.turnClock?.remainingMs).toBe(12_000);
  });

  it('STONE_PLACED 는 수 번호로 중복을 거르고 빈 수가 있으면 resync 를 청한다', () => {
    useOmokStore.getState().applySnapshot(snap(view()));
    expect(useOmokStore.getState().applyEvent(placed(0, 7, 7, 1))).toBe('applied');
    expect(useOmokStore.getState().applyEvent(placed(0, 7, 7, 1))).toBe('applied');
    expect(useOmokStore.getState().moves).toHaveLength(1);
    expect(useOmokStore.getState().applyEvent(placed(0, 3, 3, 3))).toBe('unhandled');
    expect(useOmokStore.getState().moves).toHaveLength(1);
  });

  it('돌이 놓이면 차례·카운트다운을 멈추고 TURN_CHANGED 가 다음 차례·금수 목록·카운트다운을 세운다', () => {
    useOmokStore.getState().applySnapshot(snap(view(), 5_000));
    useOmokStore.getState().applyEvent(placed(0, 7, 7, 1));
    expect(useOmokStore.getState().turnSeat).toBe(-1);
    expect(useOmokStore.getState().turnClock).toBeNull();
    useOmokStore.getState().applyEvent({
      type: 'TURN_CHANGED',
      seq: 2,
      payload: { seat: 1, color: 'WHITE', forbidden: [{ x: 1, y: 1, kind: 'DOUBLE_THREE' }] },
    });
    const s = useOmokStore.getState();
    expect(s.turnSeat).toBe(1);
    expect(s.forbidden).toEqual([{ x: 1, y: 1, kind: 'DOUBLE_THREE' }]);
    expect(s.turnClock?.remainingMs).toBeNull();
    expect(s.turnEpoch).toBe(1);
  });

  it('내가 보낸 자리가 놓이면 흐린 돌을 지우고, 거절(ERROR)이 오면 지우고 문구를 보인다', () => {
    useOmokStore.getState().applySnapshot(snap(view()));
    useOmokStore.getState().markPending({ x: 7, y: 7 });
    useOmokStore.getState().applyEvent(placed(0, 7, 7, 1));
    expect(useOmokStore.getState().pending).toBeNull();

    useOmokStore.getState().markPending({ x: 2, y: 2 });
    omokRoomSink.applyPrivateEvent({
      eventId: 'e',
      type: 'ERROR',
      ts: 0,
      payload: { code: 'FORBIDDEN_MOVE', message: 'x' },
    } as never);
    expect(useOmokStore.getState().pending).toBeNull();
    expect(useOmokStore.getState().errorMessage).toContain('금수');
  });

  it('MATCH_ENDED 뒤에는 진행 이벤트를 반영하지 않는다', () => {
    useOmokStore.getState().applySnapshot(snap(view()));
    useOmokStore.getState().applyEvent({
      type: 'MATCH_ENDED',
      seq: 1,
      payload: { winnerColor: 'WHITE', winnerSeats: [1], reason: 'RESIGN', causeSeat: 0, winLine: [], totalMoves: 0 },
    });
    expect(useOmokStore.getState().result?.reason).toBe('RESIGN');
    expect(useOmokStore.getState().applyEvent(placed(0, 7, 7, 1))).toBe('ignored');
    expect(useOmokStore.getState().applyEvent({ type: 'PLAYER_DISCONNECTED', payload: { seat: 1 } })).toBe('applied');
    expect(useOmokStore.getState().disconnectedSeats.has(1)).toBe(true);
  });

  it('스냅샷은 미리보기를 비우고, 아직 놓이지 않은 보낸 자리는 지킨다', () => {
    useOmokStore.getState().applySnapshot(snap(view()));
    useOmokStore.getState().setPreview({ x: 1, y: 1 });
    useOmokStore.getState().markPending({ x: 4, y: 4 });
    useOmokStore.getState().applySnapshot(snap(view()));
    expect(useOmokStore.getState().preview).toBeNull();
    expect(useOmokStore.getState().pending).toEqual({ x: 4, y: 4 });
    useOmokStore.getState().applySnapshot(snap(view({ moves: [{ seat: 0, x: 4, y: 4, timedOut: false }] })));
    expect(useOmokStore.getState().pending).toBeNull();
  });

  it('멈춘 차례 resync 는 턴 번호마다 한 번만 신호를 올린다', () => {
    useOmokStore.getState().requestTurnResync(3);
    useOmokStore.getState().requestTurnResync(3);
    expect(useOmokStore.getState().resyncNonce).toBe(1);
  });
});

describe('omokStore — 강하게 두기 (D-193·D-195)', () => {
  beforeEach(() => useOmokStore.getState().reset('r1'));

  it('STONE_PLACED.slam 이면 수 번호를 기억한다 — 같은 이벤트를 두 번 받아도 한 번, 몇 번이든', () => {
    useOmokStore.getState().applySnapshot(snap(view()));
    const slam = (seat: number, x: number, n: number, c: 'BLACK' | 'WHITE') => ({
      ...placed(seat, x, 7, n, c),
      payload: { ...placed(seat, x, 7, n, c).payload, slam: true },
    });
    useOmokStore.getState().applyEvent(slam(0, 7, 1, 'BLACK'));
    useOmokStore.getState().applyEvent(slam(0, 7, 1, 'BLACK'));
    useOmokStore.getState().applyEvent(placed(1, 8, 8, 2, 'WHITE'));
    useOmokStore.getState().applyEvent(slam(0, 9, 3, 'BLACK'));
    expect([...useOmokStore.getState().slamMoves]).toEqual([1, 3]);
  });

  it('강하게 보낸 수는 pendingSlam 으로 그리고, 거절되면 푼다', () => {
    useOmokStore.getState().applySnapshot(snap(view()));
    useOmokStore.getState().markPending({ x: 7, y: 7 }, true);
    expect(useOmokStore.getState().pendingSlam).toBe(true);
    omokRoomSink.applyPrivateEvent({ eventId: 'e', seq: 0, type: 'ERROR', ts: 0, payload: { code: 'OCCUPIED', message: '' } } as never);
    expect(useOmokStore.getState().pending).toBeNull();
    expect(useOmokStore.getState().pendingSlam).toBe(false);
  });
});
