import { create } from 'zustand';
import type { ApplyEventResult, ResyncEnvelope } from '@/types/stomp';
import {
  NO_HOLD,
  type Category,
  type Dice,
  type DiceHeldPayload,
  type DiceRolledPayload,
  type DiceShakingPayload,
  type Held,
  type MatchEndedPayload,
  type MatchResult,
  type PlayerEliminatedPayload,
  type RoundStartedPayload,
  type ScoredPayload,
  type TurnChangedPayload,
  type YachtPrivateView,
  type YachtSeatTurn,
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
  /**
   * D-202 — 동시 진행. 이때 `turnSeat` 는 −1 이고 좌석별 주사위는 `seatTurns` 에 있다. 위의 `dice`·`held`·`rolls`·`candidates` 는 늘
   * <b>가운데 주사위 판</b>의 값이라 동시 진행이면 내 좌석(`ownSeat`) 것을 비춘다 — 게임판의 홀드·굴림 코드가 두 방식에서 같다.
   */
  simultaneous: boolean;
  seatTurns: Record<number, YachtSeatTurn>;
  /**
   * D-268 — 지금 컵을 흔드는 남의 좌석 → 마지막으로 그 소식을 받은 시각(ms). 서버의 `DICE_SHAKING`(순번 없는 미리보기)이 채우고, 멈춤
   * 프레임·그 좌석의 굴림·기록·차례 넘어감·다시 받기가 지운다. 멈춤 프레임을 놓쳤을 때는 게임판이 {@link SHAKE_STALE_MS} 뒤에 걷는다.
   * 내 좌석은 담지 않는다(내 흔들기는 내 화면이 기준).
   */
  shaking: Record<number, number>;
  /**
   * D-268 — 좌석별로 마지막에 받아들인 흔들기 프레임 번호(`n`). 서버 인바운드 채널은 수신 순서를 보장하지 않아 시작이 멈춤보다 늦게 올 수
   * 있다 — 이보다 크지 않은 번호는 버린다. 차례·라운드가 바뀌면 비운다.
   */
  shakeSeen: Record<number, number>;
  /**
   * D-268 — 좌석별 "이때까지는 흔들기 시작을 받지 않는다"(ms). 그 좌석의 굴림이 온 직후 잠깐 — 굴림보다 늦게 닿은 그 굴림 전의 시작(멈춤도
   * 아직 안 온 경우)이 컵을 다시 세우지 않게.
   */
  shakeQuietUntil: Record<number, number>;
}

/** D-268 — 그 좌석의 굴림 뒤 이만큼은 흔들기 시작을 받지 않는다(굴림 연출이 도는 동안. 진짜 새 흔들기는 다음 유지 프레임이 살린다). */
export const SHAKE_AFTER_ROLL_QUIET_MS = 800;

/** D-268 — 흔들기 소식이 이만큼 끊기면 멈춘 것으로 본다(보내는 쪽은 {@link SHAKE_KEEPALIVE_MS} 마다 다시 알린다). */
export const SHAKE_STALE_MS = 4000;
/** D-268 — 흔드는 동안 이 간격으로 다시 알린다(오래 흔들어도 남의 화면에서 걷히지 않게, 나중에 들어온 사람도 보게). */
export const SHAKE_KEEPALIVE_MS = 1500;

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
  /** D-268 — 소식이 끊긴 흔들기를 걷는다(`before` 보다 먼저 받은 것만). */
  expireShaking: (before: number) => void;
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
  simultaneous: false,
  seatTurns: {},
  shaking: {},
  shakeSeen: {},
  shakeQuietUntil: {},
};

/** 그 좌석의 흔들기 표시를 지운 새 표(없으면 같은 객체 그대로 — 쓸데없는 다시 그리기를 피한다). */
function withoutShaking(shaking: Record<number, number>, seat: number): Record<number, number> {
  if (!(seat in shaking)) return shaking;
  const next = { ...shaking };
  delete next[seat];
  return next;
}

function freshTurn(): Partial<YachtRoomState> {
  return { dice: null, held: NO_HOLD, rolls: 0, candidates: {} };
}

const emptySeatTurn = (seat: number, pending: boolean): YachtSeatTurn => ({
  seat,
  dice: null,
  held: NO_HOLD,
  rolls: 0,
  pending,
  candidates: {},
});

/** D-202 — 가운데 판에 비출 내 좌석 주사위(없으면 빈 판). */
function ownTray(seatTurns: Record<number, YachtSeatTurn>, ownSeat: number): Partial<YachtRoomState> {
  const t = seatTurns[ownSeat];
  return t ? { dice: t.dice, held: t.held, rolls: t.rolls, candidates: t.candidates } : freshTurn();
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
    const simultaneous = t.simultaneous ?? false;
    const seatTurns: Record<number, YachtSeatTurn> = {};
    for (const turn of t.turns ?? []) seatTurns[turn.seat] = { ...turn, held: turn.held ?? NO_HOLD };
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
      simultaneous,
      seatTurns,
      // 스냅샷에는 흔들기가 없다 — 흔드는 중이면 다음 유지 프레임이 다시 채운다.
      shaking: {},
      shakeQuietUntil: {},
      ...(simultaneous ? ownTray(seatTurns, get().ownSeat) : {}),
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
          shaking: {},
          shakeSeen: {},
          shakeQuietUntil: {},
          lastSent: null,
          ...freshTurn(),
          turnClock: p.turnSeconds > 0 ? { since: Date.now(), remainingMs: null } : null,
        });
        return 'applied';
      }

      case 'ROUND_STARTED': {
        const p = payload as RoundStartedPayload;
        const seatTurns: Record<number, YachtSeatTurn> = {};
        for (const x of state.seats) seatTurns[x.seat] = emptySeatTurn(x.seat, !x.deserted);
        set({
          simultaneous: true,
          turnSeat: -1,
          round: p.round,
          seatTurns,
          shaking: {},
          shakeSeen: {},
          shakeQuietUntil: {},
          lastSent: null,
          ...freshTurn(),
          turnClock: p.turnSeconds > 0 ? { since: Date.now(), remainingMs: null } : null,
        });
        return 'applied';
      }

      case 'DICE_HELD': {
        const p = payload as DiceHeldPayload;
        if (state.simultaneous) {
          const cur = state.seatTurns[p.seat];
          if (!cur) return 'unhandled';
          // 내 홀드는 화면이 기준이다(아래 순차와 같은 이유) — 남의 좌석만 반영.
          if (p.seat !== state.ownSeat) set({ seatTurns: { ...state.seatTurns, [p.seat]: { ...cur, held: p.held } } });
          return 'applied';
        }
        if (p.seat !== state.turnSeat) return 'unhandled';
        // 내 차례의 홀드는 화면이 기준이다(위 ownSeat) — 메아리가 방금 누른 것을 되돌리지 않게.
        if (p.seat !== state.ownSeat) set({ held: p.held });
        return 'applied';
      }

      case 'DICE_SHAKING': {
        // D-268 — 순번 없는 미리보기. 락 밖에서 만들어져 낡은 것이 섞일 수 있다 — 지금 굴릴 차례가 아닌 좌석의 시작은 버린다.
        const p = payload as DiceShakingPayload;
        if (p.seat === state.ownSeat) return 'ignored';
        // 수신 순서가 뒤바뀐 낡은 프레임(멈춤 뒤에 닿은 시작, 새 시작 뒤에 닿은 옛 멈춤)은 버린다.
        if (typeof p.n !== 'number' || p.n <= (state.shakeSeen[p.seat] ?? 0)) return 'ignored';
        const shakeSeen = { ...state.shakeSeen, [p.seat]: p.n };
        if (!p.shaking) {
          set({ shakeSeen, shaking: withoutShaking(state.shaking, p.seat) });
          return 'applied';
        }
        const live = state.simultaneous ? (state.seatTurns[p.seat]?.pending ?? false) : p.seat === state.turnSeat;
        const now = Date.now();
        if (!live || now < (state.shakeQuietUntil[p.seat] ?? 0)) {
          set({ shakeSeen });
          return 'ignored';
        }
        set({ shakeSeen, shaking: { ...state.shaking, [p.seat]: now } });
        return 'applied';
      }

      case 'DICE_ROLLED': {
        const p = payload as DiceRolledPayload;
        const shaking = withoutShaking(state.shaking, p.seat);
        const shakeQuietUntil = { ...state.shakeQuietUntil, [p.seat]: Date.now() + SHAKE_AFTER_ROLL_QUIET_MS };
        if (state.simultaneous) {
          const turn: YachtSeatTurn = {
            seat: p.seat,
            dice: p.dice,
            held: p.held,
            rolls: p.rolls,
            pending: true,
            candidates: p.candidates,
          };
          const seatTurns = { ...state.seatTurns, [p.seat]: turn };
          if (p.seat !== state.ownSeat) {
            set({ seatTurns, shaking, shakeQuietUntil });
            return 'applied';
          }
          set({
            seatTurns,
            shaking,
            shakeQuietUntil,
            dice: p.dice,
            held: p.held,
            rolls: p.rolls,
            candidates: p.candidates,
            rollNonce: state.rollNonce + 1,
            lastSent: null,
          });
          return 'applied';
        }
        if (p.seat !== state.turnSeat) return 'unhandled';
        set({
          shaking,
          shakeQuietUntil,
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
        const mine = !state.simultaneous || p.seat === state.ownSeat;
        set({
          seats,
          shaking: withoutShaking(state.shaking, p.seat),
          ...(state.simultaneous
            ? { seatTurns: { ...state.seatTurns, [p.seat]: emptySeatTurn(p.seat, false) } }
            : {}),
          // 동시 진행이면 남의 기록은 가운데 판(내 주사위)을 건드리지 않는다.
          ...(mine ? { candidates: {}, lastSent: null } : {}),
          ...(state.simultaneous && mine ? freshTurn() : {}),
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
        if (state.simultaneous) {
          set({
            seats,
            shaking: withoutShaking(state.shaking, p.seat),
            seatTurns: { ...state.seatTurns, [p.seat]: emptySeatTurn(p.seat, false) },
            ...(p.seat === state.ownSeat ? freshTurn() : {}),
          });
          return 'applied';
        }
        set({ seats, shaking: withoutShaking(state.shaking, p.seat), ...(p.seat === state.turnSeat ? freshTurn() : {}) });
        return 'applied';
      }

      case 'MATCH_ENDED': {
        const p = payload as MatchEndedPayload;
        set({ phase: 'ENDED', result: p, turnSeat: -1, ...freshTurn(), turnClock: null, seatTurns: {}, shaking: {} });
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
    const { simultaneous, seatTurns, ownSeat } = get();
    const cur = seatTurns[ownSeat];
    set(simultaneous && cur ? { held, seatTurns: { ...seatTurns, [ownSeat]: { ...cur, held } } } : { held });
  },

  setOwnSeat(seat) {
    const { simultaneous, seatTurns } = get();
    set({ ownSeat: seat, ...(simultaneous ? ownTray(seatTurns, seat) : {}) });
  },

  noteSent(action) {
    set({ lastSent: { action, at: Date.now(), retried: false } });
  },

  expireShaking(before) {
    const { shaking } = get();
    const kept = Object.entries(shaking).filter(([, at]) => at >= before);
    if (kept.length === Object.keys(shaking).length) return;
    set({ shaking: Object.fromEntries(kept) });
  },

  noteBusy() {
    const { lastSent, retryNonce } = get();
    if (!lastSent || lastSent.retried || Date.now() - lastSent.at > BUSY_RETRY_WINDOW_MS) return false;
    set({ lastSent: { ...lastSent, retried: true }, retryNonce: retryNonce + 1 });
    return true;
  },
}));
