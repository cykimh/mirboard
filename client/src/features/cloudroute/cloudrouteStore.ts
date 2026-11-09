import { create } from 'zustand';
import type { ApplyEventResult, ResyncEnvelope } from '@/types/stomp';
import type {
  AssetsLiquidated,
  Built,
  CloudRouteLetter,
  CloudRouteMatchResult,
  CloudRoutePending,
  CloudRouteSeatView,
  CloudRouteTableView,
  CloudRouteTileView,
  DecisionRequired,
  EscapeFailed,
  FestivalCleared,
  FestivalHosted,
  LandBought,
  LetterDrawn,
  MoneyChanged,
  MoneyTransferred,
  PlayerEliminated,
  SeatOnly,
  TakenOver,
  TokenMoved,
  TollPaid,
  TurnChanged,
  WhirlpoolLeft,
  DiceRolled,
} from '@/types/cloudroute';

/** "방금 일어난 일" 한 줄의 원본 — 글자는 게임판이 이름을 붙여 만든다(`describe`). */
export interface LogEntry {
  id: number;
  type: string;
  payload: unknown;
}

/** D-131 과 같은 턴 시계 — 서버가 턴 데드라인을 다시 거는 순간부터 센다. */
export interface TurnClock {
  since: number;
  remainingMs: number | null;
}

/** 사건 줄에 남기는 최대 줄 수. */
export const LOG_LIMIT = 6;

export interface CloudRouteRoomState {
  roomId: string | null;
  phase: string;
  seats: CloudRouteSeatView[];
  tiles: CloudRouteTileView[];
  festivalTile: number;
  round: number;
  roundLimit: number;
  turnSeat: number;
  doublesThisTurn: number;
  pending: CloudRoutePending | null;
  lastDice: number[];
  deckCount: number;
  discard: CloudRouteLetter[];
  result: CloudRouteMatchResult | null;
  /** 마지막으로 뽑힌 편지(가운데 칸에 잠깐 보인다). */
  lastLetter: CloudRouteLetter | null;
  log: LogEntry[];
  disconnectedSeats: Set<number>;
  errorMessage: string | null;
  turnClock: TurnClock | null;
  /** 턴 시계를 다시 셀 때마다 오른다 — 멈춘 차례 복구(D-140 방식)가 같은 차례인지 본다. */
  turnEpoch: number;
  /** 게임판이 권위 스냅샷을 다시 청하게 하는 신호(값이 바뀔 때만). */
  resyncNonce: number;
  /** 같은 차례에 한 번만 다시 청한다. */
  resyncedEpoch: number;
}

export interface CloudRouteActions {
  reset: (roomId: string) => void;
  applySnapshot: (snapshot: ResyncEnvelope<CloudRouteTableView, unknown>) => void;
  applyEvent: (envelope: { type: string; seq?: number; payload: unknown }) => ApplyEventResult;
  setError: (message: string | null) => void;
  /** 멈춘 차례 — 이 차례에서 처음이면 resync 신호를 올린다. */
  requestTurnResync: (epoch: number) => void;
}

const INITIAL: Omit<CloudRouteRoomState, 'disconnectedSeats'> = {
  roomId: null,
  phase: '',
  seats: [],
  tiles: [],
  festivalTile: -1,
  round: 0,
  roundLimit: 0,
  turnSeat: -1,
  doublesThisTurn: 0,
  pending: null,
  lastDice: [],
  deckCount: 0,
  discard: [],
  result: null,
  lastLetter: null,
  log: [],
  errorMessage: null,
  turnClock: null,
  turnEpoch: 0,
  resyncNonce: 0,
  resyncedEpoch: -1,
};

let logId = 0;

/**
 * 구름항로 게임판 스토어 (D-213). <b>순수 리듀서</b>다 — 순번 판정은 훅이 끝내고 여기는 결과값을 그대로 덮는다(D-124). 이벤트가
 * 증감이 아니라 결과값(뒤 현금·칸 주인·단계)을 싣기 때문에 같은 이벤트가 두 번 와도 상태가 같다.
 */
export const useCloudRouteStore = create<CloudRouteRoomState & CloudRouteActions>((set, get) => {
  const patchSeat = (seat: number, patch: Partial<CloudRouteSeatView>) => {
    set({ seats: get().seats.map((s) => (s.seat === seat ? { ...s, ...patch } : s)) });
  };
  const patchTile = (tile: number, patch: Partial<CloudRouteTileView>) => {
    set({ tiles: get().tiles.map((t) => (t.tile === tile ? { ...t, ...patch } : t)) });
  };
  const restartClock = () => {
    set({ turnClock: { since: Date.now(), remainingMs: null }, turnEpoch: get().turnEpoch + 1 });
  };
  const log = (type: string, payload: unknown) => {
    const next = [...get().log, { id: ++logId, type, payload }];
    set({ log: next.slice(-LOG_LIMIT) });
  };

  return {
    ...INITIAL,
    disconnectedSeats: new Set(),

    reset(roomId) {
      set({ ...INITIAL, roomId, disconnectedSeats: new Set() });
    },

    applySnapshot(snap) {
      const t = snap.tableView;
      set({
        phase: t.phase,
        seats: t.seats,
        tiles: t.tiles,
        festivalTile: t.festivalTile,
        round: t.round,
        roundLimit: t.roundLimit,
        turnSeat: t.turnSeat,
        doublesThisTurn: t.doublesThisTurn,
        pending: t.pending,
        lastDice: t.lastDice,
        deckCount: t.deckCount,
        discard: t.discard,
        result: t.result,
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
          const { seat } = payload as SeatOnly;
          const next = new Set(state.disconnectedSeats);
          if (type === 'PLAYER_DISCONNECTED') next.add(seat);
          else next.delete(seat);
          set({ disconnectedSeats: next });
          return 'applied';
        }
        case 'DICE_ROLLED': {
          const p = payload as DiceRolled;
          set({ lastDice: [p.die1, p.die2], doublesThisTurn: p.doublesThisTurn, pending: null });
          log(type, p);
          return 'applied';
        }
        case 'TOKEN_MOVED': {
          const p = payload as TokenMoved;
          patchSeat(p.seat, { position: p.to, cash: p.cash });
          if (p.salary > 0 || p.kind !== 'ROLL') log(type, p);
          return 'applied';
        }
        case 'LAND_BOUGHT':
        case 'BUILT': {
          const p = payload as LandBought | Built;
          patchTile(p.tile, { owner: p.seat, level: p.level });
          patchSeat(p.seat, { cash: p.cash });
          set({ pending: null });
          log(type, p);
          return 'applied';
        }
        case 'TOLL_PAID': {
          const p = payload as TollPaid;
          patchSeat(p.payer, { cash: p.payerCash });
          patchSeat(p.owner, { cash: p.ownerCash });
          log(type, p);
          return 'applied';
        }
        case 'TAKEN_OVER': {
          const p = payload as TakenOver;
          patchTile(p.tile, { owner: p.buyer });
          patchSeat(p.buyer, { cash: p.buyerCash });
          patchSeat(p.seller, { cash: p.sellerCash });
          set({ pending: null });
          log(type, p);
          return 'applied';
        }
        case 'LETTER_DRAWN': {
          const p = payload as LetterDrawn;
          const discard = p.reshuffled ? [] : state.discard;
          set({
            deckCount: p.deckCount,
            lastLetter: p.letter,
            discard: p.letter === 'BUOY' ? discard : [...discard, p.letter],
            seats:
              p.letter === 'BUOY'
                ? state.seats.map((s) => ({ ...s, holdsBuoy: s.seat === p.seat }))
                : state.seats,
          });
          log(type, p);
          return 'applied';
        }
        case 'MONEY_CHANGED': {
          const p = payload as MoneyChanged;
          patchSeat(p.seat, { cash: p.cash });
          log(type, p);
          return 'applied';
        }
        case 'MONEY_TRANSFERRED': {
          const p = payload as MoneyTransferred;
          patchSeat(p.from, { cash: p.fromCash });
          patchSeat(p.to, { cash: p.toCash });
          log(type, p);
          return 'applied';
        }
        case 'WHIRLPOOL_ENTERED': {
          const p = payload as SeatOnly;
          patchSeat(p.seat, { trapped: true, failedEscapes: 0 });
          log(type, p);
          return 'applied';
        }
        case 'ESCAPE_FAILED': {
          const p = payload as EscapeFailed;
          patchSeat(p.seat, { failedEscapes: p.failedEscapes });
          log(type, p);
          return 'applied';
        }
        case 'WHIRLPOOL_LEFT': {
          const p = payload as WhirlpoolLeft;
          patchSeat(p.seat, { trapped: false, failedEscapes: 0, ...(p.method === 'BUOY' ? { holdsBuoy: false } : {}) });
          if (p.method === 'BUOY') set({ discard: [...get().discard, 'BUOY'] });
          log(type, p);
          return 'applied';
        }
        case 'FESTIVAL_HOSTED': {
          const p = payload as FestivalHosted;
          set({ festivalTile: p.tile, pending: null });
          log(type, p);
          return 'applied';
        }
        case 'FESTIVAL_CLEARED': {
          const p = payload as FestivalCleared;
          if (get().festivalTile === p.tile) set({ festivalTile: -1 });
          return 'applied';
        }
        case 'ASSETS_LIQUIDATED': {
          const p = payload as AssetsLiquidated;
          for (const sale of p.sales) {
            patchTile(sale.tile, sale.land ? { owner: -1, level: 0 } : { level: sale.levelAfter });
          }
          patchSeat(p.seat, { cash: p.cash });
          log(type, p);
          return 'applied';
        }
        case 'PLAYER_ELIMINATED': {
          const p = payload as PlayerEliminated;
          for (const tile of p.returnedTiles) patchTile(tile, { owner: -1, level: 0 });
          const holder = get().seats.find((s) => s.seat === p.seat)?.holdsBuoy ?? false;
          patchSeat(p.seat, {
            eliminated: p.reason,
            cash: 0,
            trapped: false,
            failedEscapes: 0,
            holdsBuoy: false,
            totalAssets: 0,
          });
          if (holder) set({ discard: [...get().discard, 'BUOY'] });
          if (p.seat === get().turnSeat) set({ pending: null });
          // 서버는 탈락 뒤에도 턴 데드라인을 다시 건다(탈주 경로) — 시계도 처음부터.
          restartClock();
          log(type, p);
          return 'applied';
        }
        case 'TURN_CHANGED': {
          const p = payload as TurnChanged;
          set({ turnSeat: p.seat, round: p.round, doublesThisTurn: 0, pending: null });
          return 'applied';
        }
        case 'DECISION_REQUIRED': {
          const p = payload as DecisionRequired;
          set({ pending: p.pending, turnSeat: p.seat });
          // 서버는 성공한 전이마다 턴 데드라인을 처음부터 다시 건다 — 결정이 바뀌면 다시 센다.
          restartClock();
          return 'applied';
        }
        case 'MATCH_ENDED': {
          const p = payload as { result: CloudRouteMatchResult };
          set({ phase: 'ENDED', result: p.result, turnSeat: -1, pending: null, turnClock: null });
          return 'applied';
        }
        default:
          return 'unhandled';
      }
    },

    setError(message) {
      set({ errorMessage: message });
    },

    requestTurnResync(epoch) {
      const state = get();
      if (state.turnEpoch !== epoch || state.resyncedEpoch === epoch) return;
      set({ resyncedEpoch: epoch, resyncNonce: state.resyncNonce + 1 });
    },
  };
});
