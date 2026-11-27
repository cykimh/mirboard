import { create } from 'zustand';
import { errorNoticePatch } from '@/components/notice/errorNotice';
import type { ApplyEventResult, ResyncEnvelope } from '@/types/stomp';
import type {
  CandidateInfo,
  FlagAcceptedPayload,
  GuessAcceptedPayload,
  GuessPhaseStartedPayload,
  GuessesRevealedPayload,
  HintCountPayload,
  HintPendingPayload,
  HintPhaseStartedPayload,
  HintRevealedPayload,
  HintScoredPayload,
  HintVoidPayload,
  MatchEndedPayload,
  MatchResult,
  HintView,
  PublicAttempt,
  Revealed,
  SeatView,
  TurnEndedPayload,
  TurnReveal,
  TurnStartedPayload,
  TurnSummary,
  WordCandidatesPayload,
  WordSelectStartedPayload,
  WordTempPhase,
  WordTempPrivateView,
  WordTempTableView,
} from '@/types/wordtemp';
import { attemptDeltas } from './wordTempMath';

/** 단계 시계 — 서버가 준 남은 ms 를 받은 때부터 줄인다(공개 뷰 `phaseRemainingMs`, 바깥 `turnRemainingMs` 는 쓰지 않는다 — 룰 §9). */
export interface PhaseClock {
  since: number;
  remainingMs: number;
}

/** 보낸 액션 — 응답(본인 큐·공개 이벤트·`ERROR`) 전까지 같은 것을 또 보내지 않는다. `BUSY` 면 한 번만 다시 보낸다. */
export interface SentAction {
  id: number;
  action: Record<string, unknown>;
  at: number;
  retried: boolean;
}

/** 응답을 기다리는 상한(ms) — 소식이 없으면 게임판이 막음을 푼다(얏추 D-270 과 같은 생각). */
export const SEND_GUARD_MS = 3000;
/** `BUSY` 를 그 액션의 것으로 보는 시간. */
export const BUSY_RETRY_WINDOW_MS = 2000;

export interface WordTempRoomState {
  roomId: string | null;
  /** 내 좌석(관전자 −1) — 게임판이 방의 `playerIds` 로 정해 알려 준다. */
  mySeat: number;

  // ── 공개 상태(tableView 미러) ──
  phase: WordTempPhase | null;
  round: number;
  rounds: number;
  turnNo: number;
  setterSeat: number;
  attempt: number;
  clock: PhaseClock | null;
  hintCount: number;
  maxHints: number;
  /** 차수별 최저 온도(서버가 준다 — 판정도 서버). */
  floors: number[];
  /** 이 출제 차례의 힌트 전부 — 낼 때마다 모두에게 공개된다(D-320). 차수가 지나도 쌓인다. */
  hints: HintView[];
  /** 끝난 힌트 단계마다 하나 — 가장 높은 힌트와 기준을 넘겼는지. */
  revealed: Revealed[];
  /** 이번 차수에 추리할 좌석들. */
  guessSeats: number[];
  guessedSeats: number[];
  attempts: PublicAttempt[];
  seats: SeatView[];
  order: number[];
  history: TurnSummary[];
  turnReveal: TurnReveal | null;
  result: MatchResult | null;
  /** 방금 끝난 차수에 좌석마다 얻은 점수(결과 보기 동안). */
  deltas: Record<number, number>;

  // ── 본인만(privateHand 미러) — 관전자·봇은 늘 비어 있다 ──
  candidates: CandidateInfo[];
  /** 후보를 받은 라운드(늦게 온 `WORD_CANDIDATES` 를 가린다). */
  candidatesRound: number;
  /** 이번 라운드에 고른 후보 번호, 아직이면 −1. */
  chosen: number;
  /** (출제자면) 이번 정답. */
  answer: string | null;
  /** (추리자면) 이번 차수에 낸 내 답. 빈 문자열 = 패스, 아직이면 null. */
  myGuess: string | null;

  // ── 연출용(이 세션이 받은 이벤트만 — 스냅샷으로 받은 판은 다시 움직이지 않는다) ──
  /** 방금 공개된 차수(수은주가 차오른다). */
  freshReveal: number | null;
  /** 방금 동시 공개된 차수(답이 뒤집힌다). */
  freshResult: number | null;
  /** 방금 채점된 내 힌트. */
  freshScored: string | null;
  fxSeq: number;

  // ── "온도가 이상해요"(D-323) — 이 세션이 알린 것만 기억한다(다시 받기로 지워지지 않는다. 서버가 정본 — 겹치면 거절 문구) ──
  /** 내가 알린 힌트(`출제 차례 번호:낱말`). */
  flagged: string[];
  /** 이 판에서 더 알릴 수 있는 횟수 — 한 번도 안 알렸으면 null(모름). */
  flagsLeft: number | null;

  // ── 보내기 ──
  sending: SentAction | null;
  retryNonce: number;
  /** 보낸 후보 번호 — 내 `WORD_SELECTED` 가 오면 `chosen` 이 된다. */
  pickSent: number | null;
  /** 다시 받기 신호 — 게임판이 이 값의 변화를 보고 훅의 `requestResync` 를 부른다. */
  resyncNonce: number;

  // ── 메타 ──
  disconnectedSeats: Set<number>;
  errorMessage: string | null;
  errorSeq: number;
  errorSticky: boolean;
}

export interface WordTempActions {
  reset: (roomId: string) => void;
  setMySeat: (seat: number) => void;
  applySnapshot: (snapshot: ResyncEnvelope<WordTempTableView, WordTempPrivateView>) => void;
  applyEvent: (envelope: { type: string; seq?: number; payload: unknown }) => ApplyEventResult;
  applyPrivate: (type: string, payload: unknown) => void;
  setError: (message: string | null, sticky?: boolean) => void;
  /** 액션을 보냈다 — 응답 전까지 입력을 막는다. */
  noteSent: (action: Record<string, unknown>) => void;
  /** `BUSY` 를 받았다 — 다시 보낼 것이 있으면 신호를 올리고 true(오류를 보이지 않는다). */
  noteBusy: () => boolean;
  /** 보내기 막음을 푼다 — `id` 를 주면 그 보냄만(안전 타이머). */
  clearSending: (id?: number) => void;
}

const NO_PRIVATE = {
  candidates: [] as CandidateInfo[],
  candidatesRound: 0,
  chosen: -1,
  answer: null as string | null,
  myGuess: null as string | null,
};

const NO_FX = { freshReveal: null, freshResult: null, freshScored: null } as const;

const INITIAL: Omit<WordTempRoomState, 'disconnectedSeats' | 'mySeat'> = {
  roomId: null,
  phase: null,
  round: 1,
  rounds: 1,
  turnNo: 0,
  setterSeat: -1,
  attempt: 0,
  clock: null,
  hintCount: 0,
  maxHints: 5,
  floors: [],
  hints: [],
  revealed: [],
  guessSeats: [],
  guessedSeats: [],
  attempts: [],
  seats: [],
  order: [],
  history: [],
  turnReveal: null,
  result: null,
  deltas: {},
  ...NO_PRIVATE,
  ...NO_FX,
  fxSeq: 0,
  flagged: [],
  flagsLeft: null,
  sending: null,
  retryNonce: 0,
  pickSent: null,
  resyncNonce: 0,
  errorMessage: null,
  errorSeq: 0,
  errorSticky: false,
};

let sendSeq = 0;

/** 단계 시작 이벤트가 실어 온 남은 시간 → 시계. */
const clockOf = (remainingMs: number | undefined): PhaseClock | null =>
  typeof remainingMs === 'number' ? { since: Date.now(), remainingMs } : null;

const guessersOf = (seats: SeatView[], setterSeat: number) =>
  seats.filter((s) => s.alive && s.seat !== setterSeat).map((s) => s.seat);

/**
 * 낱말 온도 방 상태 — 순수 리듀서(D-124: 순번 판정은 `useStompRoom` 이 sink 앞에서 끝낸다, `lastSeq` 없음). 공개 payload 가 결과값이라
 * 같은 이벤트를 두 번 적용해도 같다(차수·출제 차례 번호로 이미 받은 것을 거른다).
 *
 * <p><b>비밀은 서버가 보내지 않는다</b> — 공개 뷰·공개 이벤트에는 후보·진행 중인 정답·미공개 힌트·공개 전 추리가 없고, 본인 것만 비공개
 * 뷰·본인 큐로 온다(룰 §12). 그래서 추리자·관전자의 스토어에는 출제자의 힌트·온도가 <b>들어올 길이 없다</b>({@link applyPrivate} 도 내 좌석
 * 것만 받는다). D-320 뒤로 힌트와 온도는 비밀이 아니다 — 공개 뷰·공개 이벤트로 모두에게 온다.
 */
export const useWordTempStore = create<WordTempRoomState & WordTempActions>((set, get) => ({
  ...INITIAL,
  mySeat: -1,
  disconnectedSeats: new Set(),

  reset(roomId) {
    set({ ...INITIAL, roomId, disconnectedSeats: new Set() });
  },

  setMySeat(seat) {
    if (get().mySeat !== seat) set({ mySeat: seat });
  },

  applySnapshot(snap) {
    const t = snap.tableView;
    const p = snap.privateHand;
    const over = t.result !== null || t.phase === 'FINISHED';
    const last = t.attempts.length ? t.attempts[t.attempts.length - 1] : null;
    set({
      phase: t.phase,
      round: t.round,
      rounds: t.rounds,
      turnNo: t.turnNo,
      setterSeat: t.setterSeat,
      attempt: t.attempt,
      clock: over ? null : { since: Date.now(), remainingMs: t.phaseRemainingMs },
      hintCount: t.hintCount,
      maxHints: t.maxHints,
      floors: t.floors ?? [],
      hints: (t.hints ?? []).filter((h) => h.status !== 'VOID'),
      revealed: t.revealed ?? [],
      guessSeats: guessersOf(t.seats, t.setterSeat),
      guessedSeats: t.guessedSeats ?? [],
      attempts: t.attempts ?? [],
      seats: t.seats,
      order: t.order ?? [],
      history: t.history ?? [],
      turnReveal: t.turnReveal ?? null,
      result: t.result ?? null,
      deltas: t.phase === 'ATTEMPT_RESULT' && last ? attemptDeltas(last, t.setterSeat, t.round) : {},
      // 본인 것 — 관전자·봇·빠진 좌석은 null 이라 전부 비운다.
      candidates: p?.candidates ?? [],
      candidatesRound: p ? t.round : 0,
      chosen: p?.chosen ?? -1,
      answer: p?.answer ?? null,
      myGuess: p?.myGuess ?? null,
      ...NO_FX,
      sending: null,
      pickSent: null,
      disconnectedSeats: new Set(snap.disconnectedSeats ?? []),
      errorMessage: null,
    });
  },

  applyEvent(envelope) {
    const { type, payload } = envelope;
    const state = get();
    if (type === 'PLAYER_DISCONNECTED' || type === 'PLAYER_RECONNECTED') {
      const { seat } = payload as { seat: number };
      const next = new Set(state.disconnectedSeats);
      if (type === 'PLAYER_DISCONNECTED') next.add(seat);
      else next.delete(seat);
      set({ disconnectedSeats: next });
      return 'applied';
    }
    if (state.result) return 'ignored';
    // 첫 스냅샷 전에 온 프레임 — 좌석을 모르니 권위 스냅샷으로.
    if (state.phase === null) return 'unhandled';

    switch (type) {
      case 'WORD_SELECT_STARTED': {
        const p = payload as WordSelectStartedPayload;
        // 내 후보가 아직 지난 라운드 것이면(본인 큐가 늦다) 방금 쓴 낱말만 먼저 지운다 — 새 목록이 오면 그것으로 바뀐다.
        const stale = state.candidatesRound < p.round;
        set({
          phase: 'WORD_SELECT',
          round: p.round,
          setterSeat: -1,
          attempt: 0,
          clock: { since: Date.now(), remainingMs: p.remainingMs },
          seats: state.seats.map((s) => ({ ...s, chosen: false })),
          revealed: [],
          attempts: [],
          guessSeats: [],
          guessedSeats: [],
          hintCount: 0,
          turnReveal: null,
          deltas: {},
          candidates: stale
            ? state.candidates.map((c) => (c.index === state.chosen ? { ...c, available: false } : c))
            : state.candidates,
          chosen: -1,
          answer: null,
          hints: [],
          myGuess: null,
          ...NO_FX,
          sending: null,
          pickSent: null,
        });
        return 'applied';
      }

      case 'WORD_SELECTED': {
        const { seat } = payload as { seat: number };
        const mine = seat === state.mySeat;
        // 무엇이 골라졌는지는 비공개 뷰에만 있다. 마감 직전에 보낸 고르기는 서버의 대신 고르기와 엇갈릴 수 있으므로(보낸 것이
        // 거절되고 무작위가 골라짐 — 리뷰 지적) 보낸 후보를 믿지 않고, 아직 확정을 모르면 늘 다시 받는다. 그동안 고른 표시는
        // `pickSent` 가 이어 준다(스냅샷이 비운다).
        const unknown = mine && state.chosen < 0;
        set({
          seats: state.seats.map((s) => (s.seat === seat ? { ...s, chosen: true } : s)),
          ...(mine
            ? {
                sending: null,
                resyncNonce: unknown ? state.resyncNonce + 1 : state.resyncNonce,
              }
            : {}),
        });
        return 'applied';
      }

      case 'TURN_STARTED': {
        const p = payload as TurnStartedPayload;
        const mine = p.setterSeat === state.mySeat && state.mySeat >= 0;
        const answer = mine ? (state.candidates.find((c) => c.index === state.chosen)?.word ?? null) : null;
        set({
          turnNo: p.turnNo,
          round: p.round,
          setterSeat: p.setterSeat,
          attempt: 1,
          revealed: [],
          attempts: [],
          guessSeats: [],
          guessedSeats: [],
          hintCount: 0,
          turnReveal: null,
          deltas: {},
          clock: null,
          answer,
          hints: [],
          myGuess: null,
          ...NO_FX,
          sending: null,
          // 내가 출제자인데 정답을 모른다(서버가 대신 고른 판을 아직 못 받았다) — 다시 받는다.
          resyncNonce: mine && answer === null ? state.resyncNonce + 1 : state.resyncNonce,
        });
        return 'applied';
      }

      case 'HINT_PHASE_STARTED': {
        const p = payload as HintPhaseStartedPayload;
        set({
          phase: 'HINT',
          turnNo: p.turnNo,
          attempt: p.attempt,
          clock: { since: Date.now(), remainingMs: p.remainingMs },
          hintCount: 0,
          guessSeats: [],
          guessedSeats: [],
          deltas: {},
          myGuess: null,
          ...NO_FX,
          sending: null,
        });
        return 'applied';
      }

      case 'HINT_COUNT': {
        const p = payload as HintCountPayload;
        set({ hintCount: p.count, maxHints: p.max });
        return 'applied';
      }

      // ── 힌트(D-320) — 낼 때마다 모두에게 공개된다. 낱말과 온도가 그대로 온다 ──
      case 'HINT_PENDING': {
        const p = payload as HintPendingPayload;
        if (state.hints.some((h) => h.text === p.text && h.attempt === state.attempt)) return 'applied';
        set({
          hints: [...state.hints, { text: p.text, attempt: state.attempt, status: 'PENDING', temp: -1 }],
          sending: null,
        });
        return 'applied';
      }

      case 'HINT_SCORED': {
        const p = payload as HintScoredPayload;
        const scored: HintView = { text: p.text, attempt: state.attempt, status: 'SCORED', temp: p.temp };
        const at = state.hints.findIndex((h) => h.text === p.text && h.attempt === state.attempt);
        set({
          hints: at >= 0 ? state.hints.map((h, i) => (i === at ? scored : h)) : [...state.hints, scored],
          hintCount: p.count,
          freshScored: p.text,
          fxSeq: state.fxSeq + 1,
          sending: null,
        });
        return 'applied';
      }

      case 'HINT_VOID': {
        // 채점 결과로 무효(명사 아님·시간 초과) — 채점 중이던 줄을 지우고 개수를 돌려받는다.
        const p = payload as HintVoidPayload;
        set({
          hints: state.hints.filter((h) => !(h.text === p.text && h.attempt === state.attempt && h.status === 'PENDING')),
          hintCount: p.count,
          sending: null,
        });
        return 'applied';
      }

      case 'HINT_REVEALED': {
        // 힌트 단계 끝 — 이 차수가 기준 온도를 넘겼는지(가장 높은 힌트). 힌트가 없었으면 낱말 null·못 넘김. 곧 추리 단계가 온다.
        const p = payload as HintRevealedPayload;
        if (state.revealed.some((r) => r.attempt === p.attempt)) return 'applied';
        set({
          revealed: [
            ...state.revealed,
            { attempt: p.attempt, text: p.text, temp: p.temp, floor: p.floor, floorMet: p.floorMet },
          ],
          freshReveal: p.attempt,
          fxSeq: state.fxSeq + 1,
          clock: null,
          sending: null,
        });
        return 'applied';
      }

      case 'GUESS_PHASE_STARTED': {
        const p = payload as GuessPhaseStartedPayload;
        set({
          phase: 'GUESS',
          attempt: p.attempt,
          clock: { since: Date.now(), remainingMs: p.remainingMs },
          guessSeats: p.seats,
          guessedSeats: [],
          myGuess: null,
          sending: null,
        });
        return 'applied';
      }

      case 'GUESS_SUBMITTED': {
        const { seat } = payload as { seat: number };
        if (state.guessedSeats.includes(seat)) return 'applied';
        set({ guessedSeats: [...state.guessedSeats, seat].sort((a, b) => a - b) });
        return 'applied';
      }

      case 'GUESSES_REVEALED': {
        const p = payload as GuessesRevealedPayload;
        if (state.attempts.some((a) => a.attempt === p.attempt)) return 'applied';
        const delta = new Map(p.scores.map((sc) => [sc.seat, sc]));
        const deltas: Record<number, number> = {};
        for (const sc of p.scores) if (sc.delta > 0) deltas[sc.seat] = sc.delta;
        set({
          phase: 'ATTEMPT_RESULT',
          attempts: [...state.attempts, { attempt: p.attempt, guesses: p.guesses, setterGain: p.setterGain }],
          seats: state.seats.map((s) => {
            const sc = delta.get(s.seat);
            if (!sc) return s;
            return s.seat === p.setterSeat
              ? { ...s, total: sc.total, setterPoints: s.setterPoints + sc.delta }
              : { ...s, total: sc.total, guessPoints: s.guessPoints + sc.delta };
          }),
          deltas,
          freshResult: p.attempt,
          freshReveal: null,
          fxSeq: state.fxSeq + 1,
          clock: clockOf(p.remainingMs),
          sending: null,
        });
        return 'applied';
      }

      case 'TURN_ENDED': {
        const p = payload as TurnEndedPayload;
        if (state.history.some((h) => h.turnNo === p.turnNo)) {
          set({ phase: 'TURN_END', turnReveal: { answer: p.answer, hints: p.hints } });
          return 'applied';
        }
        const last = state.attempts.length ? state.attempts[state.attempts.length - 1] : null;
        const summary: TurnSummary = {
          turnNo: p.turnNo,
          round: state.round,
          setterSeat: state.setterSeat,
          answer: p.answer,
          revealed: state.revealed,
          solvers: p.reason === 'SOLVED' && last ? last.guesses.filter((g) => g.correct).map((g) => g.seat) : [],
          setterGain: state.attempts.reduce((sum, a) => sum + a.setterGain, 0),
          reason: p.reason,
        };
        set({
          phase: 'TURN_END',
          turnReveal: { answer: p.answer, hints: p.hints },
          // 채점 중이던 힌트는 차례가 끝나면 버려진다(서버가 따로 알리지 않는다 — 출제자 탈주 등) — 판에서도 지운다.
          hints: state.hints.filter((h) => h.status !== 'PENDING'),
          history: [...state.history, summary],
          seats: state.seats.map((s) => (p.totals[s.seat] === undefined ? s : { ...s, total: p.totals[s.seat] })),
          deltas: {},
          clock: clockOf(p.remainingMs),
          freshReveal: null,
          freshResult: null,
          fxSeq: state.fxSeq + 1,
          sending: null,
        });
        return 'applied';
      }

      case 'SEAT_OUT': {
        const { seat } = payload as { seat: number };
        set({
          seats: state.seats.map((s) => (s.seat === seat ? { ...s, alive: false } : s)),
          guessSeats: state.guessSeats.filter((x) => x !== seat),
        });
        return 'applied';
      }

      case 'MATCH_ENDED': {
        const p = payload as MatchEndedPayload;
        set({ result: p.result, phase: 'FINISHED', clock: null, deltas: {}, sending: null, ...NO_FX });
        return 'applied';
      }

      default:
        return 'ignored';
    }
  },

  applyPrivate(type, payload) {
    const state = get();
    const seat = (payload as { seat?: number } | null)?.seat;
    // 본인 큐에는 내 것만 온다 — 그래도 좌석이 다르면 받지 않는다(남의 비밀이 스토어에 들어올 길을 한 번 더 막는다).
    if (state.mySeat < 0 || seat !== state.mySeat) return;
    switch (type) {
      case 'WORD_CANDIDATES': {
        const p = payload as WordCandidatesPayload;
        if (p.round < state.candidatesRound) return;
        // 새 라운드의 목록이 공개 프레임보다 먼저 왔으면 고른 것을 비운다. 같은 라운드면(늦게 온 목록) 이미 고른 것을 지킨다.
        set({ candidates: p.candidates, candidatesRound: p.round, chosen: p.round > state.round ? -1 : state.chosen });
        return;
      }
      case 'GUESS_ACCEPTED': {
        const p = payload as GuessAcceptedPayload;
        set({ myGuess: p.text, sending: null });
        return;
      }
      case 'FLAG_ACCEPTED': {
        const p = payload as FlagAcceptedPayload;
        const key = flagKey(state.turnNo, p.text);
        set({
          flagged: state.flagged.includes(key) ? state.flagged : [...state.flagged, key],
          flagsLeft: p.left,
          sending: null,
        });
        return;
      }
      default:
        return;
    }
  },

  setError(message, sticky) {
    // 오류 문구가 뜬다는 것은 보낸 것이 거절됐다는 뜻이다(다시 보내는 BUSY 는 여기 오지 않는다) — 보내기 막음을 푼다.
    set((s) => ({ ...errorNoticePatch(s, message, sticky), ...(message !== null ? { sending: null, pickSent: null } : {}) }));
  },

  noteSent(action) {
    set({
      sending: { id: ++sendSeq, action, at: Date.now(), retried: false },
      ...(action['@action'] === 'SELECT_WORD' ? { pickSent: action.index as number } : {}),
    });
  },

  noteBusy() {
    const { sending, retryNonce } = get();
    if (!sending || sending.retried || Date.now() - sending.at > BUSY_RETRY_WINDOW_MS) return false;
    set({ sending: { ...sending, retried: true }, retryNonce: retryNonce + 1 });
    return true;
  },

  clearSending(id) {
    const { sending } = get();
    if (!sending || (id !== undefined && sending.id !== id)) return;
    set({ sending: null, pickSent: null });
  },
}));

/** 알린 힌트의 열쇠 — 같은 낱말이 다른 출제 차례에 또 나올 수 있어 차례 번호를 붙인다. */
export function flagKey(turnNo: number, text: string): string {
  return `${turnNo}:${text}`;
}

/** 살아 있는 내 좌석인가(입력할 수 있는 사람). */
export function iAmPlaying(s: Pick<WordTempRoomState, 'mySeat' | 'seats' | 'result'>): boolean {
  return s.mySeat >= 0 && s.result === null && s.seats[s.mySeat]?.alive === true;
}

/** 이번 출제 차례의 출제자가 나인가. */
export function iAmSetter(s: Pick<WordTempRoomState, 'mySeat' | 'setterSeat'>): boolean {
  return s.mySeat >= 0 && s.mySeat === s.setterSeat;
}

/** 채점 중인 힌트가 있는가 — 풀릴 때까지 새 힌트·[힌트 끝]을 받지 않는다(§6.2). */
/** 그 차수의 기준 온도 — 서버가 주지 않았으면 null(눈금을 그리지 않는다). */
export function floorOf(s: Pick<WordTempRoomState, 'floors'>, attempt: number): number | null {
  return s.floors[attempt - 1] ?? null;
}

export function scoringInProgress(s: Pick<WordTempRoomState, 'hints'>): boolean {
  return s.hints.some((h) => h.status === 'PENDING');
}
