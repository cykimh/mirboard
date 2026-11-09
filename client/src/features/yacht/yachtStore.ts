import { create } from 'zustand';
import type { ApplyEventResult, ResyncEnvelope } from '@/types/stomp';
import {
  NO_HOLD,
  type Category,
  type Dice,
  type DiceHeldPayload,
  type DiceRolledPayload,
  type Held,
  type MatchEndedPayload,
  type MatchResult,
  type PlayerEliminatedPayload,
  type ScoredPayload,
  type TurnChangedPayload,
  type YachtPrivateView,
  type YachtSeatView,
  type YachtTableView,
} from '@/types/yacht';

/** 차례 전체 마감 기준 — `since` 에 `remainingMs` 가 남았다(null 이면 방의 턴 제한 전부). */
export interface TurnClock {
  since: number;
  remainingMs: number | null;
}

/** 방금 기록한 칸 — 점수표 강조·축하 효과용(잠깐). */
export interface LastScore {
  seat: number;
  category: Category;
  points: number;
  auto: boolean;
  nonce: number;
}

export interface YachtRoomState {
  roomId: string | null;
  phase: 'PLAYING' | 'ENDED';
  seats: YachtSeatView[];
  round: number;
  turnSeat: number;
  dice: Dice | null;
  held: Held;
  rolls: number;
  candidates: Partial<Record<Category, number>>;
  /** 굴림 애니메이션 신호 — DICE_ROLLED 마다 오른다. */
  rollNonce: number;
  lastScore: LastScore | null;
  result: MatchResult | null;
  turnClock: TurnClock | null;
  disconnectedSeats: Set<number>;
  errorMessage: string | null;
  /**
   * 이 화면의 내 좌석(관전자 −1). 비공개 뷰가 없어 게임판이 알려 준다. 내 차례의 홀드는 화면이 기준이다 — 빠르게 여러 개를 누르면
   * 서버의 `DICE_HELD` 메아리가 한 박자 늦게 와 방금 누른 것을 되돌리므로, 내 좌석의 `DICE_HELD` 는 반영하지 않는다(굴림은 홀드를 함께
   * 보내고 `DICE_ROLLED` 가 권위 값을 준다).
   */
  ownSeat: number;
  /**
   * D-200 — 마지막으로 보낸 `ROLL`·`SCORE`. 방 락 경합으로 `BUSY` 가 오면 한 번만 다시 보낸다(`retryNonce` 가 신호). 서버가 그 결과
   * 이벤트(`DICE_ROLLED`·`SCORED`·`TURN_CHANGED`)를 보내면 비운다.
   */
  lastSent: SentAction | null;
  retryNonce: number;
}

export interface SentAction {
  action: Record<string, unknown>;
  at: number;
  retried: boolean;
}

/** BUSY 를 그 액션의 것으로 보는 시간 — 그보다 늦은 BUSY 는 일반 오류로 보인다. */
export const BUSY_RETRY_WINDOW_MS = 2000;

export interface YachtActions {
  reset: (roomId: string) => void;
  applySnapshot: (snapshot: ResyncEnvelope<YachtTableView, YachtPrivateView>) => void;
  applyEvent: (envelope: { type: string; seq?: number; payload: unknown }) => ApplyEventResult;
  setError: (message: string | null) => void;
  /** 내 차례에 홀드를 미리 바꾼다(서버 확인 전) — 서버가 같은 값을 `DICE_HELD` 로 돌려준다. */
  setHeldLocal: (held: Held) => void;
  setOwnSeat: (seat: number) => void;
  noteSent: (action: Record<string, unknown>) => void;
  /** BUSY 를 받았다 — 다시 보낼 것이 있으면 신호를 올리고 true(오류를 보이지 않는다). */
  noteBusy: () => boolean;
}

const INITIAL: Omit<YachtRoomState, 'disconnectedSeats'> = {
  roomId: null,
  phase: 'PLAYING',
  seats: [],
  round: 1,
  turnSeat: -1,
  dice: null,
  held: NO_HOLD,
  rolls: 0,
  candidates: {},
  rollNonce: 0,
  lastScore: null,
  result: null,
  turnClock: null,
  errorMessage: null,
  ownSeat: -1,
  lastSent: null,
  retryNonce: 0,
};

function freshTurn(): Partial<YachtRoomState> {
  return { dice: null, held: NO_HOLD, rolls: 0, candidates: {} };
}

/**
 * 요트 게임판 스토어 — `lastSeq` 없는 순수 리듀서(D-124). 순번 판정(중복·구멍)은 `useStompRoom` 이 한다.
 *
 * <p>공개 payload 가 결과값이라(D-128 원칙) 같은 이벤트를 두 번 적용해도 같다. 비공개 상태가 없어 손패 같은 버전 관리도 없다.
 */
export const useYachtStore = create<YachtRoomState & YachtActions>((set, get) => ({
  ...INITIAL,
  disconnectedSeats: new Set(),

  reset(roomId) {
    set({ ...INITIAL, roomId, disconnectedSeats: new Set(), ownSeat: get().ownSeat });
  },

  applySnapshot(snap) {
    const t = snap.tableView;
    set({
      phase: t.phase,
      seats: t.seats,
      round: t.round,
      turnSeat: t.turnSeat,
      dice: t.dice,
      held: t.held ?? NO_HOLD,
      rolls: t.rolls,
      candidates: t.candidates ?? {},
      result: t.result,
      lastScore: null,
      disconnectedSeats: new Set(snap.disconnectedSeats ?? []),
      errorMessage: null,
      // D-183 — 차례 기준 남은 시간은 엔진이 tableView 에 싣는다(바깥 turnRemainingMs 는 마지막 액션 기준이라 쓰지 않는다).
      turnClock: typeof t.turnRemainingMs === 'number' ? { since: Date.now(), remainingMs: t.turnRemainingMs } : null,
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
        const seat = (payload as { seat: number }).seat;
        const next = new Set(state.disconnectedSeats);
        if (type === 'PLAYER_DISCONNECTED') next.add(seat);
        else next.delete(seat);
        set({ disconnectedSeats: next });
        return 'applied';
      }

      case 'TURN_CHANGED': {
        const p = payload as TurnChangedPayload;
        set({
          turnSeat: p.seat,
          round: p.round,
          lastSent: null,
          ...freshTurn(),
          turnClock: p.turnSeconds > 0 ? { since: Date.now(), remainingMs: null } : null,
        });
        return 'applied';
      }

      case 'DICE_HELD': {
        const p = payload as DiceHeldPayload;
        if (p.seat !== state.turnSeat) return 'unhandled';
        // 내 차례의 홀드는 화면이 기준이다(위 ownSeat) — 메아리가 방금 누른 것을 되돌리지 않게.
        if (p.seat !== state.ownSeat) set({ held: p.held });
        return 'applied';
      }

      case 'DICE_ROLLED': {
        const p = payload as DiceRolledPayload;
        if (p.seat !== state.turnSeat) return 'unhandled';
        set({
          dice: p.dice,
          held: p.held,
          rolls: p.rolls,
          candidates: p.candidates,
          rollNonce: state.rollNonce + 1,
          lastSent: null,
        });
        return 'applied';
      }

      case 'SCORED': {
        const p = payload as ScoredPayload;
        const seats = state.seats.map((x) =>
          x.seat === p.seat
            ? {
                ...x,
                scores: { ...x.scores, [p.category]: p.points },
                upperSubtotal: p.upperSubtotal,
                bonus: p.bonus,
                total: p.total,
              }
            : x,
        );
        set({
          seats,
          candidates: {},
          lastSent: null,
          lastScore: {
            seat: p.seat,
            category: p.category,
            points: p.points,
            auto: p.auto,
            nonce: (state.lastScore?.nonce ?? 0) + 1,
          },
        });
        return 'applied';
      }

      case 'PLAYER_ELIMINATED': {
        const p = payload as PlayerEliminatedPayload;
        const seats = state.seats.map((x) => (x.seat === p.seat ? { ...x, deserted: true, total: p.total } : x));
        set({ seats, ...(p.seat === state.turnSeat ? freshTurn() : {}) });
        return 'applied';
      }

      case 'MATCH_ENDED': {
        const p = payload as MatchEndedPayload;
        set({ phase: 'ENDED', result: p, turnSeat: -1, ...freshTurn(), turnClock: null });
        return 'applied';
      }

      default:
        return 'unhandled';
    }
  },

  setError(message) {
    set({ errorMessage: message });
  },

  setHeldLocal(held) {
    set({ held });
  },

  setOwnSeat(seat) {
    set({ ownSeat: seat });
  },

  noteSent(action) {
    set({ lastSent: { action, at: Date.now(), retried: false } });
  },

  noteBusy() {
    const { lastSent, retryNonce } = get();
    if (!lastSent || lastSent.retried || Date.now() - lastSent.at > BUSY_RETRY_WINDOW_MS) return false;
    set({ lastSent: { ...lastSent, retried: true }, retryNonce: retryNonce + 1 });
    return true;
  },
}));
