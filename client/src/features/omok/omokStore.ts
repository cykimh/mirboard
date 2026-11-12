import { create } from 'zustand';
import type { ApplyEventResult, ResyncEnvelope } from '@/types/stomp';
import type {
  OmokForbiddenPoint,
  OmokMatchEndedPayload,
  OmokMatchResult,
  OmokMode,
  OmokMove,
  OmokPoint,
  OmokRule,
  OmokStone,
  OmokTableView,
  OmokTurnChangedPayload,
  StonePlacedPayload,
} from '@/types/omok';

/** D-131 — 턴 카운트다운 기준(원카드와 같은 모양). remainingMs null 이면 한 턴 전체. */
export interface TurnClock {
  since: number;
  remainingMs: number | null;
}

export interface OmokRoomState {
  roomId: string | null;

  // ── 공개 상태 (tableView 미러, 결과값) ──
  mode: OmokMode | null;
  rule: OmokRule | null;
  seatColors: OmokStone[];
  moves: OmokMove[];
  turnSeat: number;
  forbidden: OmokForbiddenPoint[];
  result: OmokMatchResult | null;
  /** D-193 — 이 세션에서 받은 강하게 둔 수의 번호(1부터). 연출만 본다 — resync 로 받은 판은 다시 내리치지 않는다. */
  slamMoves: ReadonlySet<number>;

  // ── UI 로컬 ──
  /** 두 단계 확정의 미리보기 자리(D-180). 내 차례에만 둔다. */
  preview: OmokPoint | null;
  /** 확정해 보낸 자리 — 서버 응답(STONE_PLACED·ERROR) 전까지 흐리게 그린다(낙관적 표시). */
  pending: OmokPoint | null;
  /** 보낸 수가 강하게 두기인가 — 내 화면은 응답을 기다리지 않고 바로 내리친다. */
  pendingSlam: boolean;
  turnClock: TurnClock | null;
  /** D-140 — 차례를 처음부터 다시 세는 공개 이벤트(TURN_CHANGED)마다 오른다. */
  turnEpoch: number;
  staleTurnResyncFor: number | null;
  /** 다시 받기 신호 — 게임판이 이 값의 변화를 보고 훅의 requestResync 를 부른다. */
  resyncNonce: number;

  // ── 메타 ──
  disconnectedSeats: Set<number>;
  errorMessage: string | null;
}

export interface OmokActions {
  reset: (roomId: string) => void;
  applySnapshot: (snapshot: ResyncEnvelope<OmokTableView, unknown>) => void;
  applyEvent: (envelope: { type: string; seq?: number; payload: unknown }) => ApplyEventResult;
  setError: (message: string | null) => void;
  setPreview: (p: OmokPoint | null) => void;
  /** 확정 — 미리보기를 보낸 자리로 옮긴다. `slam` 은 강하게 두기(D-193). */
  markPending: (p: OmokPoint, slam?: boolean) => void;
  /** 서버가 거절했다 — 흐린 돌을 지운다. */
  clearPending: () => void;
  requestTurnResync: (turnEpoch: number) => void;
}

const INITIAL: OmokRoomState = {
  roomId: null,
  mode: null,
  rule: null,
  seatColors: [],
  moves: [],
  turnSeat: -1,
  forbidden: [],
  result: null,
  slamMoves: new Set(),
  preview: null,
  pending: null,
  pendingSlam: false,
  turnClock: null,
  turnEpoch: 0,
  staleTurnResyncFor: null,
  resyncNonce: 0,
  disconnectedSeats: new Set(),
  errorMessage: null,
};

/**
 * 오목 방 상태 — 순수 리듀서 (D-124: 순번 판정은 `useStompRoom` 이 sink 앞에서 끝낸다). 공개 payload 가 결과값이라 같은 이벤트를
 * 두 번 적용해도 같다 — `STONE_PLACED` 는 수 번호로 이미 가진 수를 거르고, 빈 수가 있으면 `'unhandled'` 로 resync 를 청한다.
 * 비공개 상태가 없어 내 좌석은 스토어가 아니라 게임판이 방의 `playerIds` 로 정한다.
 */
export const useOmokStore = create<OmokRoomState & OmokActions>((set, get) => ({
  ...INITIAL,

  reset(roomId) {
    set({ ...INITIAL, roomId, disconnectedSeats: new Set(), slamMoves: new Set() });
  },

  applySnapshot(snap) {
    const t = snap.tableView;
    const state = get();
    const stillPending =
      state.pending !== null && !t.moves.some((m) => m.x === state.pending!.x && m.y === state.pending!.y)
        ? state.pending
        : null;
    set({
      mode: t.mode,
      rule: t.rule,
      seatColors: t.seatColors,
      moves: t.moves,
      turnSeat: t.result ? -1 : t.turnSeat,
      forbidden: t.forbidden,
      result: t.result,
      // 판이 바뀌었을 수 있다 — 미리보기는 비우고, 보낸 자리는 아직 놓이지 않았으면 지킨다.
      preview: null,
      pending: t.result ? null : stillPending,
      pendingSlam: t.result || stillPending === null ? false : state.pendingSlam,
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
        if (p.moveNumber <= state.moves.length) return 'applied'; // 이미 가진 수
        if (p.moveNumber !== state.moves.length + 1) return 'unhandled'; // 빈 수 — 권위 스냅샷으로
        const placedPending = state.pending !== null && state.pending.x === p.x && state.pending.y === p.y;
        const slamMoves = p.slam ? new Set(state.slamMoves).add(p.moveNumber) : state.slamMoves;
        set({
          moves: [...state.moves, { seat: p.seat, x: p.x, y: p.y, timedOut: p.timedOut }],
          slamMoves,
          // 다음 차례는 TURN_CHANGED 가 정한다 — 그 사이 '내 차례'·카운트다운을 세우지 않는다.
          turnSeat: -1,
          turnClock: null,
          forbidden: [],
          preview: null,
          pending: placedPending ? null : state.pending,
          pendingSlam: placedPending ? false : state.pendingSlam,
        });
        return 'applied';
      }

      case 'TURN_CHANGED': {
        const p = payload as OmokTurnChangedPayload;
        set({
          turnSeat: p.seat,
          forbidden: p.forbidden,
          turnClock: { since: Date.now(), remainingMs: null },
          turnEpoch: state.turnEpoch + 1,
          preview: null,
        });
        return 'applied';
      }

      case 'MATCH_ENDED': {
        const p = payload as OmokMatchEndedPayload;
        set({
          result: {
            winnerColor: p.winnerColor,
            winnerSeats: p.winnerSeats,
            reason: p.reason,
            causeSeat: p.causeSeat,
            winLine: p.winLine,
          },
          turnSeat: -1,
          turnClock: null,
          forbidden: [],
          preview: null,
          pending: null,
          pendingSlam: false,
        });
        return 'applied';
      }

      default:
        return 'ignored';
    }
  },

  setError(message) {
    set({ errorMessage: message });
  },

  setPreview(p) {
    set({ preview: p });
  },

  markPending(p, slam = false) {
    set({ pending: p, pendingSlam: slam, preview: null });
  },

  clearPending() {
    set({ pending: null, pendingSlam: false });
  },

  requestTurnResync(turnEpoch) {
    const state = get();
    if (state.staleTurnResyncFor === turnEpoch) return;
    set({ staleTurnResyncFor: turnEpoch, resyncNonce: state.resyncNonce + 1 });
  },
}));
