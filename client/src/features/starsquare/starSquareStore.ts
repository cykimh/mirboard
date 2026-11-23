import { create } from 'zustand';
import type { ApplyEventResult, ResyncEnvelope } from '@/types/stomp';
import type {
  ClosingPayload,
  Grid,
  MatchEndedPayload,
  MatchResult,
  ProgressPayload,
  PuzzleEndedPayload,
  PuzzleOutcome,
  PuzzleStartedPayload,
  SeatDesertedPayload,
  SeatView,
  SolvedPayload,
  StarSquarePhase,
  StarSquarePrivateBoard,
  StarSquareTableView,
} from '@/types/starsquare';
import { errorNoticePatch } from '@/components/notice/errorNotice';

/** 지금 단계의 끝 — `since` 에 `remainingMs` 가 남았다(준비 끝·마감·결과 보기 끝). */
export interface PhaseClock {
  since: number;
  remainingMs: number;
}

/** 되돌리기 기록 상한. */
export const UNDO_LIMIT = 200;

/**
 * 판 보내기(§7) 상태 — 화면을 <b>막지 않는다</b>. 내 판은 늘 로컬이 먼저고, 보내기는 뒤에서 따라간다: 바뀐 판(`dirty`)을 게임판의 보내기 훅이
 * 모아 보내고, `BUSY` 면 잠깐 뒤 다시, 응답이 없어도 입력은 그대로 된다(D-270 의 시간 제한 해제와 같은 뜻 — 기다리는 상태로 잠기지 않는다).
 *
 * <p><b>"다시 보내야 함"과 "내 입력이 서버에 없음"은 다르다</b> — `dirty` 는 앞의 것(입력이든 BUSY 뒤 재전송이든), `edited`·`editVersion` 은
 * 뒤의 것이다. 다시 받은 서버 판(resync 비공개 뷰)을 이길 수 있는 것은 뒤의 것뿐이다: 서버가 아직 모르는 내 입력이 있을 때만 로컬이 이기고, 그 밖에는
 * 서버 판을 받는다(다른 탭에서 더 진행한 판을 이 탭의 낡은 판이 덮지 않게).
 *
 * @param sentVersion   마지막으로 보낸 버전
 * @param serverVersion 서버가 기억하는 버전(재접속·다른 탭 — resync 의 비공개 뷰). 다음 버전은 둘 중 큰 쪽 + 1
 * @param dirty         보낼 것이 있다(사용자 입력, 또는 닿지 않은 판의 재전송)
 * @param edited        마지막 보내기 뒤 사용자가 판을 바꿨다(아직 어느 프레임에도 실리지 않은 입력)
 * @param editVersion   서버 확인 전인 내 입력을 실은 마지막 버전(0 = 없음). 다시 받은 서버 판의 버전이 이보다 작으면 그 프레임이 닿지 않은 것이다
 * @param retryAt       이 시각 전에는 보내지 않는다(`BUSY` 뒤 잠깐 — ms epoch, 0 이면 없음)
 * @param awaitingSolveSince 완성처럼 보이는 판을 보낸 시각 — `SOLVED` 를 기다리는 표시용(0 이면 없음). 입력은 막지 않는다
 */
export interface SyncState {
  sentVersion: number;
  serverVersion: number;
  dirty: boolean;
  edited: boolean;
  editVersion: number;
  retryAt: number;
  busyRetries: number;
  awaitingSolveSince: number;
}

const FRESH_SYNC: SyncState = {
  sentVersion: 0,
  serverVersion: 0,
  dirty: false,
  edited: false,
  editVersion: 0,
  retryAt: 0,
  busyRetries: 0,
  awaitingSolveSince: 0,
};

export interface StarSquareRoomState {
  roomId: string | null;
  /** 내 좌석(관전자 −1) — 게임판이 방의 playerIds 로 알려 준다. */
  mySeat: number;

  // ── 공개 상태(tableView 미러, 결과값) ──
  phase: StarSquarePhase | null;
  puzzleIndex: number;
  puzzleCount: number;
  size: number;
  magicSum: number;
  clues: Grid | null;
  clock: PhaseClock | null;
  closing: boolean;
  seats: SeatView[];
  lastPuzzle: PuzzleOutcome | null;
  result: MatchResult | null;

  // ── 내 판(로컬 우선) ──
  board: Grid;
  /** 이 판이 속한 퍼즐 번호(−1 = 없음). */
  boardPuzzle: number;
  history: Grid[];
  selected: number;
  mySolved: boolean;
  sync: SyncState;

  // ── 메타 ──
  disconnectedSeats: Set<number>;
  errorMessage: string | null;
  errorSeq: number;
  errorSticky: boolean;
  resyncNonce: number;
}

export interface StarSquareActions {
  reset: (roomId: string) => void;
  setMySeat: (seat: number) => void;
  applySnapshot: (snap: ResyncEnvelope<StarSquareTableView, StarSquarePrivateBoard>) => void;
  applyEvent: (envelope: { type: string; seq?: number; payload: unknown }) => ApplyEventResult;
  setError: (message: string | null, sticky?: boolean) => void;
  /** 칸 고르기(단서 칸은 못 고른다). */
  select: (cell: number) => void;
  /** 고른 칸에 숫자(0 = 지우기). 이미 쓴 숫자면 그 칸에서 이 칸으로 옮긴다. 두 자리 수의 둘째 자리는 `mergeLast` 로 첫 자리 입력을 물린다. */
  place: (value: number, mergeLast?: boolean) => boolean;
  undo: () => void;
  /** 보낼 판 — 버전을 하나 올리고 `dirty` 를 내린다. 서버 확인 전인 내 입력이 실리면 `editVersion` 을 이 버전으로. 보낼 수 없으면 null. */
  takeSync: (now: number, complete: boolean) => { '@action': 'SYNC'; puzzle: number; version: number; grid: Grid } | null;
  /** 서버 거절 — `BUSY` 면 잠깐 뒤 다시, 낡은 버전이면 다시 받아 버전을 맞춘다(누가 이길지는 받은 판이 정한다). 그 밖은 보내지 않은 것으로 되돌리지 않는다. */
  onSyncRejected: (code: string, now: number) => void;
  /** 완성처럼 보이는 판을 보냈는데 `SOLVED` 가 오지 않았다 — 표시를 풀고 한 번 더 보낸다(입력은 원래 막지 않는다). */
  solveConfirmTimedOut: () => void;
  /**
   * 보낸 줄 알았던 판이 나가지 않았다(소켓이 끊겨 있었다 — `sendAction` 이 false) — 서버 확인 전인 내 입력이 있을 때만 다시 보낼 것으로 되돌린다.
   * 입력이 없으면 아무것도 하지 않는다(재전송이 다른 탭의 판을 덮지 않게). 다시 연결될 때는 부르지 않는다 — 연결마다 오는 resync 가 정한다.
   */
  markUnsent: () => void;
  requestResync: () => void;
}

/** BUSY 뒤 다시 보내기까지(ms) — §7-2. */
export const BUSY_RETRY_MS = 150;
/** BUSY 재전송 상한 — 넘으면 문구를 보이고 다음 입력이 다시 시도한다. */
export const BUSY_RETRY_MAX = 5;

const INITIAL: StarSquareRoomState = {
  roomId: null,
  mySeat: -1,
  phase: null,
  puzzleIndex: 0,
  puzzleCount: 0,
  size: 0,
  magicSum: 0,
  clues: null,
  clock: null,
  closing: false,
  seats: [],
  lastPuzzle: null,
  result: null,
  board: [],
  boardPuzzle: -1,
  history: [],
  selected: -1,
  mySolved: false,
  sync: FRESH_SYNC,
  disconnectedSeats: new Set(),
  errorMessage: null,
  errorSeq: 0,
  errorSticky: false,
  resyncNonce: 0,
};

const firstEmpty = (clues: Grid | null) => (clues ? clues.findIndex((v) => v === 0) : -1);

const sameGrid = (a: Grid, b: Grid) => a.length === b.length && a.every((v, i) => v === b[i]);

/** 지금 내 판을 고칠 수 있는가 — 푸는 단계·내 좌석·아직 완성 전·끝나지 않음. */
export function canEditBoard(s: StarSquareRoomState): boolean {
  return s.phase === 'PLAYING' && s.mySeat >= 0 && !s.mySolved && s.result === null && s.board.length > 0
    && !(s.seats[s.mySeat]?.deserted ?? false);
}

/**
 * 별자리 수판 방 상태 — 공개 이벤트는 순수 리듀서(D-124 — 순번 판정은 훅), 내 판은 로컬이 먼저다(§7). 공개 payload 가 결과값이라 같은 이벤트를
 * 두 번 적용해도 같다. 진행 중 남의 판 숫자는 서버가 보내지 않으므로 여기에도 없다(§5.1 — 진행도 수만).
 */
export const useStarSquareStore = create<StarSquareRoomState & StarSquareActions>((set, get) => ({
  ...INITIAL,

  reset(roomId) {
    set({ ...INITIAL, roomId, mySeat: get().mySeat, disconnectedSeats: new Set() });
  },

  setMySeat(seat) {
    set({ mySeat: seat });
  },

  applySnapshot(snap) {
    const t = snap.tableView;
    const state = get();
    const mine = snap.privateHand;
    const playing = t.phase === 'PLAYING' || t.phase === 'RESULT' || t.phase === 'ENDED';
    let board = state.board;
    let boardPuzzle = state.boardPuzzle;
    let history = state.history;
    let sync = state.sync;
    let mySolved = state.mySolved;
    if (mine && playing) {
      const samePuzzle = state.boardPuzzle === mine.puzzleIndex;
      // 같은 퍼즐에 서버가 아직 모르는 내 입력이 있으면 그것이 이긴다(로컬 우선) — 아직 안 보낸 입력(`edited`), 또는 보냈지만 서버 판이
      // 그 버전에 못 미친다(죽은 소켓으로 나간 프레임 — 앱 전환 직후 등). 버전만 서버보다 크게 맞춰 다시 보낸다. 그 밖(재전송할 것만 있거나 서버가
      // 같거나 더 새 판 — 다른 탭)은 서버 판을 받는다.
      const unconfirmedInput = state.sync.edited || state.sync.editVersion > mine.version;
      if (samePuzzle && unconfirmedInput && !mine.solved && t.phase === 'PLAYING') {
        sync = { ...state.sync, serverVersion: Math.max(state.sync.serverVersion, mine.version), dirty: true };
      } else {
        board = [...mine.grid];
        boardPuzzle = mine.puzzleIndex;
        if (!samePuzzle) history = [];
        sync = {
          ...FRESH_SYNC,
          sentVersion: samePuzzle ? Math.max(state.sync.sentVersion, mine.version) : mine.version,
          serverVersion: mine.version,
        };
      }
      mySolved = mine.solved;
    } else if (t.phase === 'PLAYING' && t.clues && state.boardPuzzle !== t.puzzleIndex) {
      // 관전자 — 단서만 든 판(읽기 전용)
      board = [...t.clues];
      boardPuzzle = t.puzzleIndex;
      history = [];
      sync = FRESH_SYNC;
      mySolved = false;
    } else if (t.phase === 'INTRO') {
      board = [];
      boardPuzzle = -1;
      history = [];
      sync = FRESH_SYNC;
      mySolved = false;
    }
    const selected =
      board.length > 0 && t.clues && state.selected >= 0 && state.selected < board.length && t.clues[state.selected] === 0
        ? state.selected
        : firstEmpty(t.clues);
    set({
      phase: t.phase,
      puzzleIndex: t.puzzleIndex,
      puzzleCount: t.puzzleCount,
      size: t.size,
      magicSum: t.magicSum,
      clues: t.clues,
      clock: t.phase === 'ENDED' ? null : { since: Date.now(), remainingMs: t.remainingMs },
      closing: t.closing,
      seats: t.seats,
      lastPuzzle: t.lastPuzzle,
      result: t.result,
      board,
      boardPuzzle,
      history,
      selected,
      mySolved,
      sync,
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
    switch (type) {
      case 'PUZZLE_STARTED': {
        const p = payload as PuzzleStartedPayload;
        // 같은 퍼즐이 이미 열려 있으면(다시 받은 스냅샷 뒤 늦은 프레임) 내 판을 지우지 않는다
        if (state.phase === 'PLAYING' && state.puzzleIndex === p.index && state.boardPuzzle === p.index) return 'applied';
        set({
          phase: 'PLAYING',
          puzzleIndex: p.index,
          puzzleCount: p.count,
          size: p.size,
          magicSum: p.magicSum,
          clues: p.clues,
          clock: { since: Date.now(), remainingMs: p.limitMs },
          closing: false,
          lastPuzzle: null,
          seats: state.seats.map((s) => ({
            ...s,
            filled: p.filled,
            lines: p.lines,
            solved: false,
            solvePlace: 0,
            elapsedMs: -1,
          })),
          board: [...p.clues],
          boardPuzzle: p.index,
          history: [],
          selected: firstEmpty(p.clues),
          mySolved: false,
          sync: FRESH_SYNC,
        });
        return 'applied';
      }

      case 'PROGRESS': {
        const p = payload as ProgressPayload;
        set({ seats: patchSeat(state.seats, p.seat, { filled: p.filled, lines: p.lines }) });
        return 'applied';
      }

      case 'SOLVED': {
        const p = payload as SolvedPayload;
        const n = state.size;
        const mine = p.seat === state.mySeat;
        set({
          seats: patchSeat(state.seats, p.seat, {
            solved: true,
            solvePlace: p.place,
            elapsedMs: p.elapsedMs,
            filled: n * n,
            lines: 2 * n + 2,
          }),
          ...(mine ? { mySolved: true, sync: { ...state.sync, dirty: false, awaitingSolveSince: 0 } } : {}),
        });
        return 'applied';
      }

      case 'CLOSING': {
        const p = payload as ClosingPayload;
        set({ closing: true, clock: { since: Date.now(), remainingMs: p.remainingMs } });
        return 'applied';
      }

      case 'PUZZLE_ENDED': {
        const p = payload as PuzzleEndedPayload;
        const outcome: PuzzleOutcome = {
          index: p.index,
          reason: p.reason,
          results: p.results,
          grids: p.grids,
          solution: p.solution,
        };
        const seats = state.seats.map((s) => {
          const r = p.results.find((x) => x.seat === s.seat);
          return {
            ...s,
            points: p.totals[s.seat] ?? s.points,
            firsts: s.firsts + (r && r.rank === 1 && !r.deserted ? 1 : 0),
          };
        });
        set({
          phase: 'RESULT',
          closing: false,
          lastPuzzle: outcome,
          seats,
          clock: { since: Date.now(), remainingMs: p.nextInMs },
          sync: { ...state.sync, dirty: false, awaitingSolveSince: 0 },
        });
        return 'applied';
      }

      case 'SEAT_DESERTED': {
        const p = payload as SeatDesertedPayload;
        set({ seats: patchSeat(state.seats, p.seat, { deserted: true }) });
        return 'applied';
      }

      case 'MATCH_ENDED': {
        const p = payload as MatchEndedPayload;
        set({
          phase: 'ENDED',
          closing: false,
          clock: null,
          result: { reason: p.reason, placements: p.placements },
          sync: { ...state.sync, dirty: false, awaitingSolveSince: 0 },
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

  select(cell) {
    const s = get();
    if (!s.clues || cell < 0 || cell >= s.clues.length || s.clues[cell] !== 0) return;
    set({ selected: cell });
  },

  place(value, mergeLast = false) {
    const s = get();
    if (!canEditBoard(s) || !s.clues) return false;
    const cell = s.selected;
    if (cell < 0 || cell >= s.board.length || s.clues[cell] !== 0) return false;
    const n2 = s.board.length;
    if (value < 0 || value > n2) return false;
    let history = s.history;
    let base = s.board;
    const merged = mergeLast && history.length > 0;
    if (merged) {
      // 두 자리 수의 둘째 자리 — 첫 자리 입력을 물리고 한 번의 입력으로 친다(되돌리기는 첫 자리 전 판으로 간다)
      base = history[history.length - 1];
      history = history.slice(0, -1);
    }
    if (base[cell] === value && !merged) return false;
    const next = [...base];
    if (value !== 0) {
      const other = next.findIndex((v, i) => v === value && i !== cell && s.clues![i] === 0);
      if (other >= 0) next[other] = 0; // 이미 쓴 숫자 → 이 칸으로 옮긴다
    }
    next[cell] = value;
    if (sameGrid(next, s.board)) return false;
    const edit = { ...s.sync, dirty: true, edited: true, busyRetries: 0 };
    if (merged && sameGrid(next, base)) {
      // 첫 자리 전과 같은 판(그 칸에 이미 그 두 자리 수가 있었다) — 입력이 없던 것과 같다
      set({ board: base, history, sync: edit });
      return true;
    }
    set({
      board: next,
      history: [...history, base].slice(-UNDO_LIMIT),
      sync: edit,
    });
    return true;
  },

  undo() {
    const s = get();
    if (!canEditBoard(s) || s.history.length === 0) return;
    const prev = s.history[s.history.length - 1];
    set({ board: prev, history: s.history.slice(0, -1), sync: { ...s.sync, dirty: true, edited: true, busyRetries: 0 } });
  },

  takeSync(now, complete) {
    const s = get();
    if (!canEditBoard(s) || !s.sync.dirty || now < s.sync.retryAt) return null;
    const version = Math.max(s.sync.sentVersion, s.sync.serverVersion) + 1;
    // 서버 확인 전인 내 입력(새 입력이든 닿았는지 모르는 앞 프레임의 입력이든)이 이 판에 실린다 — 받은 서버 판이 이 버전에 못 미치면 닿지 않은 것
    const carriesInput = s.sync.edited || s.sync.editVersion > 0;
    set({
      sync: {
        ...s.sync,
        sentVersion: version,
        dirty: false,
        edited: false,
        editVersion: carriesInput ? version : 0,
        retryAt: 0,
        awaitingSolveSince: complete ? now : 0,
      },
    });
    return { '@action': 'SYNC', puzzle: s.puzzleIndex, version, grid: [...s.board] };
  },

  onSyncRejected(code, now) {
    const s = get();
    switch (code) {
      case 'BUSY': {
        if (s.sync.busyRetries >= BUSY_RETRY_MAX) {
          set({
            sync: { ...s.sync, busyRetries: 0, awaitingSolveSince: 0, dirty: true, retryAt: now + 1_000 },
            ...errorNoticePatch(s, '서버가 바빠 판을 아직 못 보냈어요. 곧 다시 보내요.'),
          });
          return;
        }
        set({ sync: { ...s.sync, dirty: true, retryAt: now + BUSY_RETRY_MS, busyRetries: s.sync.busyRetries + 1 } });
        return;
      }
      case 'STALE_SYNC':
        // 서버가 같거나 더 큰 버전을 안다(다른 탭이 더 진행했다) — 다시 받아 버전을 맞춘다. 여기서 다시 보낼 것으로 켜지 않는다: 받은 판이 이 프레임
        // 버전 이상이면 서버 판이 이기고, 그 뒤 새 입력이 있으면 그 입력이 이긴다(`applySnapshot`)
        set({ sync: { ...s.sync, awaitingSolveSince: 0 }, resyncNonce: s.resyncNonce + 1 });
        return;
      case 'PUZZLE_CLOSED':
      case 'ALREADY_SOLVED':
        set({ sync: { ...s.sync, dirty: false, awaitingSolveSince: 0 } });
        return;
      default:
        // BAD_GRID·CLUE_CHANGED 등 — 화면과 서버가 어긋났다. 권위 스냅샷으로 맞춘다(거절된 판이 다시 이기지 않게 입력 표시도 내린다)
        set({
          sync: { ...s.sync, dirty: false, edited: false, editVersion: 0, awaitingSolveSince: 0 },
          resyncNonce: s.resyncNonce + 1,
        });
    }
  },

  solveConfirmTimedOut() {
    const s = get();
    if (!s.sync.awaitingSolveSince) return;
    const unconfirmed = s.sync.edited || s.sync.editVersion > 0;
    set({ sync: { ...s.sync, awaitingSolveSince: 0, dirty: s.sync.dirty || (canEditBoard(s) && unconfirmed) } });
  },

  markUnsent() {
    const s = get();
    if (!canEditBoard(s) || (!s.sync.edited && s.sync.editVersion === 0)) return;
    set({ sync: { ...s.sync, dirty: true, awaitingSolveSince: 0 } });
  },

  requestResync() {
    set((s) => ({ resyncNonce: s.resyncNonce + 1 }));
  },
}));

function patchSeat(seats: SeatView[], seat: number, patch: Partial<SeatView>): SeatView[] {
  if (seat < 0 || seat >= seats.length) return seats;
  return seats.map((s) => (s.seat === seat ? { ...s, ...patch } : s));
}
