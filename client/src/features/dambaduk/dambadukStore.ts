import { create } from 'zustand';
import type { ApplyEventResult, ResyncEnvelope } from '@/types/stomp';
import type {
  DambadukCell,
  DambadukExit,
  DambadukMatchEndedPayload,
  DambadukMatchResult,
  DambadukMoveOption,
  DambadukPhase,
  DambadukStone,
  DambadukStoneKind,
  DambadukTableView,
  DambadukTerritory,
  DambadukTurnChangedPayload,
  SeatOutPayload,
  StoneMovedPayload,
  StonePlacedPayload,
} from '@/types/dambaduk';
import { errorNoticePatch } from '@/components/notice/errorNotice';

/** D-131 — 턴 카운트다운 기준(오목과 같은 모양). remainingMs null 이면 한 턴 전체. */
export interface TurnClock {
  since: number;
  remainingMs: number | null;
}

/** 보낸 액션 — 서버 응답(이벤트·ERROR) 전까지 흐리게 그린다(낙관적 표시). `at` 은 보낼 때의 판 크기(이미 적용됐는지 가린다). */
export type DambadukPending =
  | { type: 'place'; x: number; y: number; kind: DambadukStoneKind; at: { moveCount: number; stoneCount: number } }
  | {
      type: 'move';
      stoneId: number;
      toX: number;
      toY: number;
      wallFace: number;
      jumpedFace: number;
      at: { moveCount: number; stoneCount: number };
    };

const EMPTY_TERRITORY: DambadukTerritory = { regions: [], scores: [], largest: [] };

export interface DambadukRoomState {
  roomId: string | null;

  // ── 공개 상태 (tableView 미러, 결과값) ──
  seatCount: number;
  firstSeat: number;
  seatColors: number[];
  phase: DambadukPhase | null;
  stones: DambadukStone[];
  walls: number[];
  placementQueue: number[];
  turnSeat: number;
  jumpsLeft: number[];
  exits: DambadukExit[];
  moveCount: number;
  territory: DambadukTerritory;
  placeKinds: DambadukStoneKind[];
  options: DambadukMoveOption[];
  result: DambadukMatchResult | null;

  // ── 연출용 (이 세션이 받은 이벤트만 — resync 로 받은 판은 다시 움직이지 않는다) ──
  /** 마지막으로 받은 옮김(돌이 미끄러지고 담이 쌓이는 연출). */
  lastMove: StoneMovedPayload | null;
  /** 마지막으로 받은 배치. */
  lastPlaced: StonePlacedPayload | null;
  /** 연출 열쇠 — 받은 배치·옮김마다 오른다. */
  fxSeq: number;
  /** 마지막 건너뜀(좌석·번호). */
  skipped: { seat: number; n: number } | null;

  // ── UI 로컬 ──
  /** 옮김 단계 — 고른 내 돌. */
  selectedStoneId: number | null;
  /** 옮김 단계 — 고른 도착(옵션). 담 면을 고르는 중이다. */
  choice: DambadukMoveOption | null;
  /** 담 넘기로 갈 칸을 보는 중(3인, 사람마다 1번). */
  jumpMode: boolean;
  /** 배치 단계 — 고른 칸(한 번 더 누르거나 [여기에 놓기]로 확정). */
  placeCell: DambadukCell | null;
  /** 배치 단계 — 고른 종류(3인). null 이면 놓을 수 있는 첫 종류. */
  placeKind: DambadukStoneKind | null;
  pending: DambadukPending | null;
  turnClock: TurnClock | null;
  /** D-140 — 차례를 처음부터 다시 세는 공개 이벤트(TURN_CHANGED)마다 오른다. */
  turnEpoch: number;
  staleTurnResyncFor: number | null;
  /** 다시 받기 신호 — 게임판이 이 값의 변화를 보고 훅의 requestResync 를 부른다. */
  resyncNonce: number;

  // ── 메타 ──
  disconnectedSeats: Set<number>;
  errorMessage: string | null;
  errorSeq: number;
  errorSticky: boolean;
}

export interface DambadukActions {
  reset: (roomId: string) => void;
  applySnapshot: (snapshot: ResyncEnvelope<DambadukTableView, unknown>) => void;
  applyEvent: (envelope: { type: string; seq?: number; payload: unknown }) => ApplyEventResult;
  setError: (message: string | null, sticky?: boolean) => void;
  selectStone: (stoneId: number | null) => void;
  choose: (option: DambadukMoveOption | null) => void;
  setJumpMode: (on: boolean) => void;
  setPlaceCell: (cell: DambadukCell | null) => void;
  setPlaceKind: (kind: DambadukStoneKind | null) => void;
  markPending: (pending: DambadukPending) => void;
  /** 서버가 거절했다 — 흐린 돌을 지운다. */
  clearPending: () => void;
  requestTurnResync: (turnEpoch: number) => void;
}

const NO_SELECTION = {
  selectedStoneId: null,
  choice: null,
  jumpMode: false,
  placeCell: null,
  placeKind: null,
} as const;

const INITIAL: DambadukRoomState = {
  roomId: null,
  seatCount: 0,
  firstSeat: 0,
  seatColors: [],
  phase: null,
  stones: [],
  walls: [],
  placementQueue: [],
  turnSeat: -1,
  jumpsLeft: [],
  exits: [],
  moveCount: 0,
  territory: EMPTY_TERRITORY,
  placeKinds: [],
  options: [],
  result: null,
  lastMove: null,
  lastPlaced: null,
  fxSeq: 0,
  skipped: null,
  ...NO_SELECTION,
  pending: null,
  turnClock: null,
  turnEpoch: 0,
  staleTurnResyncFor: null,
  resyncNonce: 0,
  disconnectedSeats: new Set(),
  errorMessage: null,
  errorSeq: 0,
  errorSticky: false,
};

/** 보낸 액션이 아직 판에 없나 — 판 크기(옮김 수·돌 수)가 보낼 때와 같으면 아직이다. */
function stillPending(p: DambadukPending | null, moveCount: number, stoneCount: number): DambadukPending | null {
  if (!p) return null;
  return p.at.moveCount === moveCount && p.at.stoneCount === stoneCount ? p : null;
}

/**
 * 담바둑 방 상태 — 순수 리듀서 (D-124: 순번 판정은 `useStompRoom` 이 sink 앞에서 끝낸다). 공개 payload 가 결과값이라 같은 이벤트를
 * 두 번 적용해도 같다 — `STONE_PLACED` 는 돌 번호로 이미 가진 돌을 거르고, 옮길 돌이 없거나 자리가 어긋나면 `'unhandled'` 로 resync 를
 * 청한다. 터·점수·고를 수 있는 수는 `TURN_CHANGED`·`MATCH_ENDED` 가 실어 온 값을 그대로 쓴다(클라는 판정하지 않는다). 비공개 상태가
 * 없어 내 좌석은 스토어가 아니라 게임판이 방의 `playerIds` 로 정한다.
 */
export const useDambadukStore = create<DambadukRoomState & DambadukActions>((set, get) => ({
  ...INITIAL,

  reset(roomId) {
    set({ ...INITIAL, roomId, disconnectedSeats: new Set() });
  },

  applySnapshot(snap) {
    const t = snap.tableView;
    const state = get();
    const over = t.result !== null;
    set({
      seatCount: t.seatCount,
      firstSeat: t.firstSeat,
      seatColors: t.seatColors,
      phase: t.phase,
      stones: t.stones,
      walls: t.walls,
      placementQueue: t.placementQueue,
      turnSeat: over ? -1 : t.turnSeat,
      jumpsLeft: t.jumpsLeft,
      exits: t.exits,
      moveCount: t.moveCount,
      territory: t.territory ?? EMPTY_TERRITORY,
      placeKinds: t.placeKinds ?? [],
      options: t.options ?? [],
      result: t.result,
      // 판이 바뀌었을 수 있다 — 고르던 것은 비우고, 보낸 것은 아직 판에 없으면 지킨다.
      ...NO_SELECTION,
      pending: over ? null : stillPending(state.pending, t.moveCount, t.stones.length),
      disconnectedSeats: new Set(snap.disconnectedSeats ?? []),
      errorMessage: null,
      turnClock:
        typeof snap.turnRemainingMs === 'number' ? { since: Date.now(), remainingMs: snap.turnRemainingMs } : null,
    });
  },

  applyEvent(envelope) {
    const { type, payload } = envelope;
    const state = get();
    if (state.result && type !== 'PLAYER_DISCONNECTED' && type !== 'PLAYER_RECONNECTED') {
      return 'ignored';
    }
    switch (type) {
      case 'PLAYER_DISCONNECTED':
      case 'PLAYER_RECONNECTED': {
        const { seat } = payload as { seat: number };
        const next = new Set(state.disconnectedSeats);
        if (type === 'PLAYER_DISCONNECTED') next.add(seat);
        else next.delete(seat);
        set({ disconnectedSeats: next });
        return 'applied';
      }

      case 'STONE_PLACED': {
        const p = payload as StonePlacedPayload;
        if (state.stones.some((s) => s.id === p.stoneId)) return 'applied'; // 이미 가진 돌
        const placedMine =
          state.pending?.type === 'place' && state.pending.x === p.x && state.pending.y === p.y;
        const [head, ...rest] = state.placementQueue;
        set({
          stones: [...state.stones, { id: p.stoneId, seat: p.seat, kind: p.kind, x: p.x, y: p.y }].sort(
            (a, b) => a.id - b.id,
          ),
          placementQueue: head === p.seat ? rest : state.placementQueue,
          lastPlaced: p,
          fxSeq: state.fxSeq + 1,
          // 다음 차례는 TURN_CHANGED 가 정한다 — 그 사이 '내 차례'·카운트다운을 세우지 않는다.
          turnSeat: -1,
          turnClock: null,
          placeKinds: [],
          options: [],
          ...NO_SELECTION,
          pending: placedMine ? null : state.pending,
        });
        return 'applied';
      }

      case 'STONE_MOVED': {
        const p = payload as StoneMovedPayload;
        const stone = state.stones.find((s) => s.id === p.stoneId);
        if (!stone) return 'unhandled';
        if (stone.x === p.toX && stone.y === p.toY && state.walls.includes(p.wallFace)) return 'applied'; // 이미 적용
        if (stone.x !== p.fromX || stone.y !== p.fromY) return 'unhandled'; // 판이 어긋났다 — 권위 스냅샷으로
        const walls = state.walls.filter((f) => f !== p.jumpedFace);
        walls.push(p.wallFace);
        walls.sort((a, b) => a - b);
        const jumpsLeft =
          p.jumpedFace >= 0 ? state.jumpsLeft.map((j, seat) => (seat === p.seat ? 0 : j)) : state.jumpsLeft;
        const movedMine = state.pending?.type === 'move' && state.pending.stoneId === p.stoneId;
        set({
          stones: state.stones.map((s) => (s.id === p.stoneId ? { ...s, x: p.toX, y: p.toY } : s)),
          walls,
          jumpsLeft,
          moveCount: state.moveCount + 1,
          lastMove: p,
          fxSeq: state.fxSeq + 1,
          turnSeat: -1,
          turnClock: null,
          placeKinds: [],
          options: [],
          ...NO_SELECTION,
          pending: movedMine ? null : state.pending,
        });
        return 'applied';
      }

      case 'TURN_CHANGED': {
        const p = payload as DambadukTurnChangedPayload;
        set({
          turnSeat: p.seat,
          phase: p.phase,
          territory: p.territory,
          placeKinds: p.placeKinds,
          options: p.options,
          placementQueue: p.phase === 'PLAYING' ? [] : state.placementQueue,
          turnClock: { since: Date.now(), remainingMs: null },
          turnEpoch: state.turnEpoch + 1,
          ...NO_SELECTION,
        });
        return 'applied';
      }

      case 'TURN_SKIPPED': {
        const { seat } = payload as { seat: number };
        set({ skipped: { seat, n: (state.skipped?.n ?? 0) + 1 } });
        return 'applied';
      }

      case 'SEAT_OUT': {
        const p = payload as SeatOutPayload;
        if (state.exits.some((e) => e.seat === p.seat)) return 'applied';
        const removed = new Set(p.removedStoneIds);
        set({
          exits: [...state.exits, { seat: p.seat, reason: p.reason }],
          stones: state.stones.filter((s) => !removed.has(s.id)),
          placementQueue: state.placementQueue.filter((q) => q !== p.seat),
          ...NO_SELECTION,
        });
        return 'applied';
      }

      case 'MATCH_ENDED': {
        const p = payload as DambadukMatchEndedPayload;
        set({
          result: { reason: p.reason, standings: p.standings, winners: p.winners, draw: p.draw, causeSeat: p.causeSeat },
          territory: p.territory,
          phase: 'ENDED',
          turnSeat: -1,
          turnClock: null,
          placeKinds: [],
          options: [],
          ...NO_SELECTION,
          pending: null,
        });
        return 'applied';
      }

      default:
        return 'ignored';
    }
  },

  setError(message, sticky) {
    set((s) => errorNoticePatch(s, message, sticky));
  },

  selectStone(stoneId) {
    set({ selectedStoneId: stoneId, choice: null });
  },

  choose(option) {
    set({ choice: option });
  },

  setJumpMode(on) {
    set({ jumpMode: on, choice: null });
  },

  setPlaceCell(cell) {
    set({ placeCell: cell });
  },

  setPlaceKind(kind) {
    set({ placeKind: kind });
  },

  markPending(pending) {
    set({ pending, ...NO_SELECTION });
  },

  clearPending() {
    set({ pending: null });
  },

  requestTurnResync(turnEpoch) {
    const state = get();
    if (state.staleTurnResyncFor === turnEpoch) return;
    set({ staleTurnResyncFor: turnEpoch, resyncNonce: state.resyncNonce + 1 });
  },
}));
