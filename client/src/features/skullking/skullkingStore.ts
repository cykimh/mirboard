import { create } from 'zustand';
import type { ApplyEventResult, ResyncEnvelope } from '@/types/stomp';
import type {
  BidsRevealedPayload,
  BiddingStartedPayload,
  CardPlayedPayload,
  HandDealtPayload,
  MatchEndedPayload,
  PlayedCardView,
  PlayingStartedPayload,
  RoundEndedPayload,
  RoundScoreView,
  SeatDesertedPayload,
  SeatView,
  SkullCard,
  SkullKingPhase,
  SkullKingPrivateView,
  SkullKingTableView,
  TigressMode,
  TrickTakenPayload,
  TurnChangedPayload,
} from '@/types/skullking';

/** 방금 정산된 트릭 — 승자 왕관을 잠시 유지하기 위한 스냅샷. */
export interface SettledTrick {
  cards: PlayedCardView[];
  winnerSeat: number;
  winningCard: SkullCard;
}

export interface SkullKingRoomState {
  roomId: string | null;
  lastSeq: number;

  // ── 공개 상태 (tableView 미러) ──
  phase: SkullKingPhase | null;
  roundNumber: number;
  /** 이 라운드의 트릭 수이자 예측 상한. 손패가 줄어도 불변 (§4). */
  handSize: number;
  startSeat: number;
  currentTurnSeat: number;
  seats: SeatView[];
  trick: PlayedCardView[];
  settledTrick: SettledTrick | null;
  cumulativeScores: Record<number, number>;
  desertedSeats: number[];
  roundScores: Record<number, RoundScoreView>;

  // ── 본인 전용 ──
  mySeat: number;
  hand: SkullCard[];
  /** 전원 제출 전 구간의 내 예측값 (공개 후에는 seats[].bid 로 읽는다). */
  myBid: number | null;

  // ── UI 로컬 ──
  /** 손패 인덱스. 중복 특수 카드(해적 5장) 때문에 값이 아니라 인덱스로 고른다. */
  selectedIndex: number | null;
  tigressDeclaration: TigressMode | null;

  // ── 메타 ──
  disconnectedSeats: Set<number>;
  errorMessage: string | null;
  roundEnded: RoundEndedPayload | null;
  matchEnded: MatchEndedPayload | null;
  turnStartedAt: number;
}

export interface SkullKingActions {
  reset: (roomId: string) => void;
  applySnapshot: (
    snapshot: ResyncEnvelope<SkullKingTableView, SkullKingPrivateView>,
  ) => void;
  applyPrivateHand: (payload: HandDealtPayload) => void;
  applyEvent: (envelope: {
    type: string;
    seq?: number;
    payload: unknown;
  }) => ApplyEventResult;
  setError: (message: string | null) => void;
  selectCard: (index: number | null) => void;
  setTigressDeclaration: (mode: TigressMode | null) => void;
}

const INITIAL: SkullKingRoomState = {
  roomId: null,
  lastSeq: 0,
  phase: null,
  roundNumber: 0,
  handSize: 0,
  startSeat: 0,
  currentTurnSeat: -1,
  seats: [],
  trick: [],
  settledTrick: null,
  cumulativeScores: {},
  desertedSeats: [],
  roundScores: {},
  mySeat: -1,
  hand: [],
  myBid: null,
  selectedIndex: null,
  tigressDeclaration: null,
  disconnectedSeats: new Set(),
  errorMessage: null,
  roundEnded: null,
  matchEnded: null,
  turnStartedAt: 0,
};

/** 예측값이 공개된 구간인가 (§5) — BIDDING 이면 아직 비공개. */
export function bidsRevealed(state: SkullKingRoomState): boolean {
  return state.phase !== null && state.phase !== 'BIDDING';
}

/** 좌석 하나만 갱신한 새 배열. */
function patchSeat(
  seats: SeatView[],
  seat: number,
  patch: Partial<SeatView>,
): SeatView[] {
  return seats.map((s) => (s.seat === seat ? { ...s, ...patch } : s));
}

export const useSkullKingStore = create<SkullKingRoomState & SkullKingActions>(
  (set, get) => ({
    ...INITIAL,

    reset(roomId) {
      set({ ...INITIAL, roomId, disconnectedSeats: new Set() });
    },

    applySnapshot(snap) {
      const t = snap.tableView;
      set({
        phase: t.phase,
        roundNumber: t.roundNumber,
        handSize: t.handSize,
        startSeat: t.startSeat,
        currentTurnSeat: t.currentTurnSeat,
        seats: t.seats,
        trick: t.trick,
        cumulativeScores: t.cumulativeScores ?? {},
        desertedSeats: t.desertedSeats ?? [],
        roundScores: t.roundScores ?? {},
        lastSeq: snap.eventSeq,
        // 관전자는 privateHand 가 null 이다 (서버 계약).
        mySeat: snap.privateHand?.seat ?? -1,
        hand: snap.privateHand?.hand ?? [],
        myBid: snap.privateHand?.myBid ?? null,
        disconnectedSeats: new Set(snap.disconnectedSeats ?? []),
        errorMessage: null,
        turnStartedAt: Date.now(),
        // settledTrick 은 보존한다 — resync 가 승자 왕관을 지우면 방금 트릭을 누가
        // 가져갔는지 화면에서 사라진다.
        matchEnded: null,
        roundEnded: null,
      });
    },

    applyPrivateHand(payload) {
      set({
        mySeat: payload.seat,
        hand: payload.cards,
        // 새 손패가 왔으면 이전 선택은 무의미하다 (인덱스가 다른 카드를 가리킨다).
        selectedIndex: null,
        tigressDeclaration: null,
      });
    },

    applyEvent(envelope) {
      const { type, seq, payload } = envelope;
      const state = get();

      let verdict: ApplyEventResult = 'applied';
      if (seq !== undefined) {
        if (seq <= state.lastSeq) verdict = 'duplicate';
        else if (seq > state.lastSeq + 1) verdict = 'gap';
      }

      // 라운드 시작은 **seq 판정과 무관하게** 라운드 로컬 상태를 즉시 비운다 (D-103).
      // 스컬킹은 라운드마다 재분배하므로 비공개 HAND_DEALT 가 좌석 수만큼 seq 를 태우고
      // (본인 큐 핸들러는 lastSeq 를 전진시키지 않는다) 뒤따르는 BIDDING_STARTED 는 거의
      // 항상 gap 이다. resync 도착 전까지 지난 라운드 예측값·트릭이 남으면 혼동이므로
      // 화면만 먼저 비우고 판정값은 그대로 돌려준다(훅이 resync 를 부른다).
      //
      // duplicate 는 예외 — 이미 지난 이벤트의 재생이 진행 중인 라운드를 지워선 안 된다.
      if (type === 'BIDDING_STARTED' && verdict !== 'duplicate') {
        const p = payload as BiddingStartedPayload;
        set({
          phase: 'BIDDING',
          roundNumber: p.roundNumber,
          handSize: p.handSize,
          trick: [],
          settledTrick: null,
          roundScores: {},
          roundEnded: null,
          myBid: null,
          selectedIndex: null,
          tigressDeclaration: null,
          // 손패도 비운다 — 이전 라운드 카드를 남기면 그 사이 클릭해서 CARD_NOT_OWNED 를
          // 받는다. HAND_DEALT(또는 resync)가 곧 새 손패를 채운다.
          hand: [],
          seats: state.seats.map((s) => ({
            ...s,
            hasBid: false,
            bid: null,
            tricksWon: 0,
            // handSize 는 payload 로 이미 알고 있으므로 과도기에도 정확한 값을 보여준다
            // (남겨두면 지난 라운드 끝의 0 이 그대로 보인다 — C5 실측).
            handCount: p.handSize,
          })),
          ...(verdict === 'applied' && seq !== undefined ? { lastSeq: seq } : {}),
        });
        return verdict;
      }

      // seq 없는 메타 이벤트는 lastSeq 판정 밖에서 처리한다 (연결 상태 배지).
      if (type === 'PLAYER_DISCONNECTED' || type === 'PLAYER_RECONNECTED') {
        const { seat } = payload as { seat: number };
        const next = new Set(state.disconnectedSeats);
        if (type === 'PLAYER_DISCONNECTED') next.add(seat);
        else next.delete(seat);
        set({ disconnectedSeats: next });
        return 'applied';
      }

      if (verdict !== 'applied') return verdict;
      const advance = seq !== undefined ? { lastSeq: seq } : {};

      switch (type) {
        case 'BID_SUBMITTED': {
          // 값은 담지 않는다 — 전원 제출 전까지 남의 예측은 비공개다 (§5).
          const { seat } = payload as { seat: number };
          set({ seats: patchSeat(state.seats, seat, { hasBid: true }), ...advance });
          return 'applied';
        }

        case 'BIDS_REVEALED': {
          const { bids } = payload as BidsRevealedPayload;
          set({
            seats: state.seats.map((s) => ({
              ...s,
              hasBid: true,
              bid: bids[s.seat] ?? s.bid,
            })),
            ...advance,
          });
          return 'applied';
        }

        case 'PLAYING_STARTED': {
          const { leadSeat } = payload as PlayingStartedPayload;
          set({
            phase: 'PLAYING',
            currentTurnSeat: leadSeat,
            turnStartedAt: Date.now(),
            ...advance,
          });
          return 'applied';
        }

        case 'CARD_PLAYED': {
          const p = payload as CardPlayedPayload;
          set({
            trick: [
              ...state.trick,
              { seat: p.seat, card: p.card, declaredAs: p.declaredAs },
            ],
            settledTrick: null,
            seats: patchSeat(state.seats, p.seat, {
              handCount: Math.max(
                0,
                (state.seats.find((s) => s.seat === p.seat)?.handCount ?? 0) - 1,
              ),
            }),
            ...(p.seat === state.mySeat
              ? { selectedIndex: null, tigressDeclaration: null }
              : {}),
            ...advance,
          });
          return 'applied';
        }

        case 'TURN_CHANGED': {
          const { currentTurnSeat } = payload as TurnChangedPayload;
          set({ currentTurnSeat, turnStartedAt: Date.now(), ...advance });
          return 'applied';
        }

        case 'TRICK_TAKEN': {
          const p = payload as TrickTakenPayload;
          const won =
            (state.seats.find((s) => s.seat === p.winnerSeat)?.tricksWon ?? 0) + 1;
          set({
            settledTrick: {
              cards: state.trick,
              winnerSeat: p.winnerSeat,
              winningCard: p.winningCard,
            },
            trick: [],
            seats: patchSeat(state.seats, p.winnerSeat, { tricksWon: won }),
            ...advance,
          });
          return 'applied';
        }

        case 'ROUND_ENDED': {
          const p = payload as RoundEndedPayload;
          // 이벤트 payload 에는 total 이 없다(서버 RoundScore 컴포넌트 4개) — 파생한다.
          const scores: Record<number, RoundScoreView> = {};
          Object.entries(p.scores).forEach(([seat, s]) => {
            scores[Number(seat)] = {
              bid: s.bid,
              won: s.won,
              base: s.base,
              bonus: s.bonus,
              total: s.total ?? s.base + s.bonus,
            };
          });
          set({
            phase: 'ROUND_END',
            roundScores: scores,
            cumulativeScores: p.cumulativeScores,
            roundEnded: p,
            currentTurnSeat: -1,
            ...advance,
          });
          return 'applied';
        }

        case 'SEAT_DESERTED': {
          const { seat } = payload as SeatDesertedPayload;
          set({
            desertedSeats: state.desertedSeats.includes(seat)
              ? state.desertedSeats
              : [...state.desertedSeats, seat].sort((a, b) => a - b),
            ...advance,
          });
          return 'applied';
        }

        case 'MATCH_ENDED': {
          const p = payload as MatchEndedPayload;
          set({
            matchEnded: p,
            cumulativeScores: p.finalScores,
            currentTurnSeat: -1,
            ...advance,
          });
          return 'applied';
        }

        default:
          // CHIPS_SETTLED 등 스컬킹에 없는 이벤트 — 훅이 resync 로 자기치유한다.
          return 'unhandled';
      }
    },

    setError(message) {
      set({ errorMessage: message });
    },

    selectCard(index) {
      set({ selectedIndex: index, tigressDeclaration: null });
    },

    setTigressDeclaration(mode) {
      set({ tigressDeclaration: mode });
    },
  }),
);
