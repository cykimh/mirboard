import { create } from 'zustand';
import type { Card, Hand, PrivateHand, TableView, TichuDeclaration } from '@/types/tichu';
import { cardKey } from '@/types/tichu';
import { effectForHandType, useEffectStore } from './effectStore';

/**
 * 패스 단계에서 좌석별 슬롯. UI 가 카드를 클릭할 때 활성 슬롯에 카드 키를 저장.
 */
export type PassSlot = 'left' | 'partner' | 'right';

export interface TichuRoomState {
  roomId: string | null;
  tableView: TableView | null;
  privateHand: PrivateHand | null;
  lastSeq: number;
  selectedCardKeys: Set<string>;
  /** 패스 단계 전용: 슬롯별 카드 키. */
  passSelection: Record<PassSlot, string | null>;
  /**
   * Phase 13B(#1): 슬롯 미배정 상태로 선택된 카드 키 (카드 먼저 → 슬롯 나중).
   * null 이면 선택 대기 없음.
   */
  pendingPassCardKey: string | null;
  /** Phase 5e: 손패의 클라이언트 정렬 순서. 빈 배열이면 서버 분배 순서 사용. */
  sortOrder: string[];
  errorMessage: string | null;
  roundEnded: { teamAScore: number; teamBScore: number; firstFinisherSeat: number } | null;
  matchEnded: {
    winningTeam: 'A' | 'B';
    finalScores: { A?: number; B?: number };
    roundsPlayed: number;
    /** P5 — 승리팀 MVP userId/문구. 탈주 종료 등 미산정 시 null. */
    mvpUserId?: number | null;
    mvpStat?: string | null;
  } | null;
  /** Phase 15(#1): 매치 전체 라운드별 점수 누적 (ROUND_ENDED 누적; reset 시 비움). */
  roundHistory: {
    teamAScore: number;
    teamBScore: number;
    firstFinisherSeat: number;
    doubleVictory: boolean;
  }[];
  /** Phase 15(#6): 현재 차례 시작 시각(클라 epoch ms). 턴 카운트다운 표시용. */
  turnStartedAt: number | null;
  /** 현재 연결이 끊긴 플레이어 좌석 — PLAYER_DISCONNECTED/RECONNECTED + resync 로 동기화. */
  disconnectedSeats: Set<number>;
  /** P4(5) — 패스로 받은 3장 + 출처(본인 큐 CARDS_RECEIVED). 모달 표시용, reset/닫기 시 정리. */
  lastReceived: { card: Card; fromSeat: number }[] | null;
  /** D-82 — 방 단위 테이블 칩(userId→칩). resync + CHIPS_SETTLED 로 동기화. */
  chips: Record<number, number>;
  /** D-82 — 직전 매치 정산의 칩 증감(userId→±). 매치 종료 화면 표시용. */
  chipDeltas: Record<number, number>;
}

export interface TichuActions {
  reset: (roomId: string) => void;
  applySnapshot: (
    snapshot: {
      tableView: TableView;
      privateHand: PrivateHand;
      eventSeq: number;
      disconnectedSeats?: number[];
      chips?: Record<number, number>;
    },
  ) => void;
  applyTableView: (table: TableView, seq?: number) => void;
  applyPrivateHand: (hand: PrivateHand) => void;
  /**
   * Phase 5d: 공개/비공개 이벤트를 받아 가능한 경우 부분 패치로 store 에 반영한다.
   * 반환값:
   *   'applied'    — 패치 성공, lastSeq 가 envelope.seq 로 갱신됨.
   *   'duplicate'  — envelope.seq <= lastSeq, 이미 처리한 이벤트.
   *   'gap'        — envelope.seq > lastSeq + 1, /resync 권유.
   *   'unhandled'  — 본 이벤트 타입은 reducer 가 없음, /resync 권유.
   */
  applyEvent: (envelope: { type: string; seq?: number; payload: unknown }) => ApplyEventResult;
  setError: (message: string | null) => void;
  setRoundEnded: (info: TichuRoomState['roundEnded']) => void;
  setMatchEnded: (info: TichuRoomState['matchEnded']) => void;
  setReceived: (items: { card: Card; fromSeat: number }[]) => void;
  clearReceived: () => void;
  toggleCardSelection: (card: Card) => void;
  clearSelection: () => void;
  /** Phase 13B(#1): 카드 클릭 → 슬롯 미배정 pending 선택 (같은 카드 재클릭 시 해제). */
  selectPassCard: (card: Card) => void;
  /** Phase 13B(#1): pending 카드를 슬롯에 배정. pending 없이 채워진 슬롯 클릭 시 해제. */
  assignPassSlot: (slot: PassSlot) => void;
  clearPassSelection: () => void;
  /** Phase 5e: 드래그 종료 시 호출. fromKey 위치의 카드를 toKey 직전으로 이동. */
  reorderHand: (fromKey: string, toKey: string) => void;
}

export type ApplyEventResult = 'applied' | 'duplicate' | 'gap' | 'unhandled';

const EMPTY_PASS: Record<PassSlot, string | null> = {
  left: null,
  partner: null,
  right: null,
};

interface PlayedPayload {
  seat: number;
  hand: Hand;
}
interface PassedPayload { seat: number }
interface TurnChangedPayload { currentTurnSeat: number }
interface TrickTakenPayload { takerSeat: number; trickPoints: number }
interface PlayerFinishedPayload { seat: number; order: number }
interface TichuDeclaredPayload { seat: number; kind: TichuDeclaration }
interface WishMadePayload { rank: number }
interface DragonGivenPayload { fromSeat: number; toSeat: number }
interface PlayerReadyPayload { seat: number }
interface PassingSubmittedPayload { seat: number }
interface RoundEndedPayload {
  score: {
    teamAScore: number;
    teamBScore: number;
    firstFinisherSeat: number;
    doubleVictory?: boolean;
  };
}
interface MatchEndedPayload {
  winningTeam: 'A' | 'B';
  finalScores: { A?: number; B?: number };
  roundsPlayed: number;
  mvpUserId?: number | null;
  mvpStat?: string | null;
}

function teamOf(seat: number): 'A' | 'B' {
  return seat % 2 === 0 ? 'A' : 'B';
}

function withHandCountDelta(
  table: TableView,
  seat: number,
  delta: number,
): TableView {
  const next = { ...table.handCounts };
  next[seat] = Math.max(0, (next[seat] ?? 0) + delta);
  return { ...table, handCounts: next };
}

/** Phase 5e: 정렬용 카드 순위. Mahjong=1, 일반=rank, Dragon=15, Phoenix=16, Dog=17. */
function sortRank(c: Card): number {
  if (c.special) {
    switch (c.special) {
      case 'MAHJONG':
        return 1;
      case 'DRAGON':
        return 15;
      case 'PHOENIX':
        return 16;
      case 'DOG':
        return 17;
    }
  }
  return c.rank;
}

/** Phase 5e: 같은 rank 안에서 suit 안정 정렬 (JADE<SWORD<PAGODA<STAR). */
const SUIT_ORDER: Record<string, number> = {
  JADE: 0,
  SWORD: 1,
  PAGODA: 2,
  STAR: 3,
};

/** Phase 13A(#5): 랭크순 비교자 (sortRank → suit 안정). */
function byRank(a: Card, b: Card): number {
  const r = sortRank(a) - sortRank(b);
  if (r !== 0) return r;
  const sa = a.suit ? SUIT_ORDER[a.suit] : 999;
  const sb = b.suit ? SUIT_ORDER[b.suit] : 999;
  return sa - sb;
}

/**
 * Phase 5e/13A: 손패 표시 순서. sortOrder 가 있으면 (사용자가 드래그로 수동
 * 재배열) 그 순서, 없으면 **기본 랭크순** (#5 — 별도 정렬 버튼 없이 항상 정렬).
 * 새 손패마다 sortOrder 가 [] 로 리셋되므로 라운드마다 자동 랭크순.
 */
export function sortedHand(state: TichuRoomState): Card[] {
  const hand = state.privateHand?.cards ?? [];
  if (state.sortOrder.length === 0) return [...hand].sort(byRank);
  const byKey = new Map<string, Card>();
  for (const c of hand) byKey.set(cardKey(c), c);
  const result: Card[] = [];
  for (const key of state.sortOrder) {
    const c = byKey.get(key);
    if (c) {
      result.push(c);
      byKey.delete(key);
    }
  }
  // sortOrder 에 없던 새 카드: 원래 서버 순서대로 뒤에.
  for (const c of hand) {
    if (byKey.has(cardKey(c))) result.push(c);
  }
  return result;
}

export const useTichuStore = create<TichuRoomState & TichuActions>((set, get) => ({
  roomId: null,
  tableView: null,
  privateHand: null,
  lastSeq: 0,
  selectedCardKeys: new Set(),
  passSelection: { ...EMPTY_PASS },
  pendingPassCardKey: null,
  sortOrder: [],
  errorMessage: null,
  roundEnded: null,
  matchEnded: null,
  roundHistory: [],
  turnStartedAt: null,
  disconnectedSeats: new Set(),
  lastReceived: null,
  chips: {},
  chipDeltas: {},

  reset(roomId) {
    set({
      roomId,
      tableView: null,
      privateHand: null,
      lastSeq: 0,
      selectedCardKeys: new Set(),
      passSelection: { ...EMPTY_PASS },
      pendingPassCardKey: null,
      sortOrder: [],
      errorMessage: null,
      roundEnded: null,
      matchEnded: null,
      roundHistory: [],
      turnStartedAt: null,
      disconnectedSeats: new Set(),
      lastReceived: null,
      chips: {},
      chipDeltas: {},
    });
  },

  applySnapshot({ tableView, privateHand, eventSeq, disconnectedSeats, chips }) {
    set({
      tableView,
      privateHand,
      lastSeq: eventSeq,
      errorMessage: null,
      // 재동기화 시 카운트다운은 정확한 잔여시간을 알 수 없으므로 현재시각 기준 근사.
      turnStartedAt: Date.now(),
      disconnectedSeats: new Set(disconnectedSeats ?? []),
      chips: chips ?? {},
    });
  },

  applyTableView(table, seq) {
    if (seq !== undefined && seq <= get().lastSeq) return;
    set({ tableView: table, lastSeq: seq ?? get().lastSeq });
  },

  applyPrivateHand(hand) {
    set({ privateHand: hand });
  },

  applyEvent(envelope) {
    const seq = envelope.seq;
    const lastSeq = get().lastSeq;
    if (seq !== undefined) {
      if (seq <= lastSeq) return 'duplicate';
      if (seq > lastSeq + 1) return 'gap';
    }

    const table = get().tableView;
    const advance = (next: TableView) => set({ tableView: next, lastSeq: seq ?? lastSeq });

    switch (envelope.type) {
      case 'PLAYED': {
        if (!table) return 'unhandled';
        const p = envelope.payload as PlayedPayload;
        let next = withHandCountDelta(table, p.seat, -p.hand.cards.length);
        next = { ...next, currentTop: p.hand, currentTopSeat: p.seat };
        advance(next);
        // Phase 8G — 하이핸드 (BOMB / STRAIGHT_FLUSH_BOMB) 시 화면 이펙트 trigger.
        const fx = effectForHandType(p.hand.type);
        if (fx) useEffectStore.getState().trigger(fx);
        return 'applied';
      }
      case 'PASSED': {
        // TableView 에 passedSeats 는 노출되지 않으므로 seq 만 진행 (다음 TURN_CHANGED 가 차례 갱신).
        if (!table) return 'unhandled';
        // payload 는 검증 위해 캐스트만.
        envelope.payload as PassedPayload;
        advance(table);
        return 'applied';
      }
      case 'TURN_CHANGED': {
        if (!table) return 'unhandled';
        const p = envelope.payload as TurnChangedPayload;
        // Phase 15(#6) — 차례 전환 시각 기록 (클라 로컬 카운트다운 기준).
        set({
          tableView: { ...table, currentTurnSeat: p.currentTurnSeat },
          lastSeq: seq ?? lastSeq,
          turnStartedAt: Date.now(),
        });
        return 'applied';
      }
      case 'TRICK_TAKEN': {
        if (!table) return 'unhandled';
        const p = envelope.payload as TrickTakenPayload;
        const team = teamOf(p.takerSeat);
        const nextScores = {
          ...table.roundScores,
          [team]: (table.roundScores[team] ?? 0) + p.trickPoints,
        };
        advance({
          ...table,
          currentTop: null,
          currentTopSeat: -1,
          roundScores: nextScores,
        });
        return 'applied';
      }
      case 'PLAYER_FINISHED': {
        if (!table) return 'unhandled';
        const p = envelope.payload as PlayerFinishedPayload;
        const handCounts = { ...table.handCounts, [p.seat]: 0 };
        const finishingOrder = table.finishingOrder.includes(p.seat)
          ? table.finishingOrder
          : [...table.finishingOrder, p.seat];
        advance({ ...table, handCounts, finishingOrder });
        return 'applied';
      }
      case 'TICHU_DECLARED': {
        if (!table) return 'unhandled';
        const p = envelope.payload as TichuDeclaredPayload;
        const declarations = { ...table.declarations, [p.seat]: p.kind };
        advance({ ...table, declarations });
        // Phase 15(#4) — 모두가 인지하도록 중앙 대형 배너 이펙트.
        useEffectStore.getState().trigger(
          'TICHU_DECLARED',
          `${p.kind === 'GRAND_TICHU' ? '👑 그랜드 티츄!' : '🔔 티츄!'} · 시트 ${p.seat}`,
        );
        return 'applied';
      }
      case 'WISH_MADE': {
        if (!table) return 'unhandled';
        const p = envelope.payload as WishMadePayload;
        advance({ ...table, activeWishRank: p.rank });
        return 'applied';
      }
      case 'DRAGON_GIVEN': {
        // 발행 시점에 TRICK_TAKEN 도 같이 들어오므로 별도 패치 없음.
        if (!table) return 'unhandled';
        envelope.payload as DragonGivenPayload;
        advance(table);
        return 'applied';
      }
      case 'PLAYER_READY': {
        if (!table) return 'unhandled';
        const p = envelope.payload as PlayerReadyPayload;
        if (table.readySeats.includes(p.seat)) {
          advance(table);
          return 'applied';
        }
        advance({ ...table, readySeats: [...table.readySeats, p.seat].sort() });
        return 'applied';
      }
      case 'PASSING_SUBMITTED': {
        if (!table) return 'unhandled';
        const p = envelope.payload as PassingSubmittedPayload;
        if (table.passingSubmittedSeats.includes(p.seat)) {
          advance(table);
          return 'applied';
        }
        advance({
          ...table,
          passingSubmittedSeats: [...table.passingSubmittedSeats, p.seat].sort(),
        });
        return 'applied';
      }
      case 'ROUND_ENDED': {
        const p = envelope.payload as RoundEndedPayload;
        set((s) => ({
          roundEnded: p.score,
          roundHistory: [
            ...s.roundHistory,
            {
              teamAScore: p.score.teamAScore,
              teamBScore: p.score.teamBScore,
              firstFinisherSeat: p.score.firstFinisherSeat,
              doubleVictory: p.score.doubleVictory ?? false,
            },
          ],
          lastSeq: seq ?? lastSeq,
        }));
        return 'applied';
      }
      case 'PLAYER_DISCONNECTED': {
        const p = envelope.payload as { seat: number };
        const next = new Set(get().disconnectedSeats);
        next.add(p.seat);
        set({ disconnectedSeats: next, lastSeq: seq ?? lastSeq });
        return 'applied';
      }
      case 'PLAYER_RECONNECTED': {
        const p = envelope.payload as { seat: number };
        const next = new Set(get().disconnectedSeats);
        next.delete(p.seat);
        set({ disconnectedSeats: next, lastSeq: seq ?? lastSeq });
        return 'applied';
      }
      case 'CHIPS_SETTLED': {
        // D-82 — 방 칩 정산(공개 메타 이벤트, seq 무관). stacks/deltas 키는 userId 문자열.
        const p = envelope.payload as {
          stacks: Record<number, number>;
          deltas: Record<number, number>;
        };
        set({ chips: p.stacks ?? {}, chipDeltas: p.deltas ?? {}, lastSeq: seq ?? lastSeq });
        return 'applied';
      }
      case 'MATCH_ENDED': {
        const p = envelope.payload as MatchEndedPayload;
        set({
          matchEnded: {
            winningTeam: p.winningTeam,
            finalScores: p.finalScores,
            roundsPlayed: p.roundsPlayed,
            mvpUserId: p.mvpUserId ?? null,
            mvpStat: p.mvpStat ?? null,
          },
          lastSeq: seq ?? lastSeq,
        });
        return 'applied';
      }
      default:
        // 라이프사이클 이벤트 (DEALING_PHASE_STARTED, PASSING_STARTED, CARDS_PASSED,
        // PLAYING_STARTED, ROUND_STARTED) 와 미지 타입은 /resync 위임.
        return 'unhandled';
    }
  },

  setError(message) {
    set({ errorMessage: message });
  },

  setRoundEnded(info) {
    set({ roundEnded: info });
  },

  setMatchEnded(info) {
    set({ matchEnded: info });
  },

  setReceived(items) {
    set({ lastReceived: items });
  },

  clearReceived() {
    set({ lastReceived: null });
  },

  toggleCardSelection(card) {
    const key = cardKey(card);
    const next = new Set(get().selectedCardKeys);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    set({ selectedCardKeys: next });
  },

  clearSelection() {
    set({ selectedCardKeys: new Set() });
  },

  selectPassCard(card) {
    const key = cardKey(card);
    const { pendingPassCardKey, passSelection } = get();
    // 같은 카드 재클릭 → pending 해제.
    if (pendingPassCardKey === key) {
      set({ pendingPassCardKey: null });
      return;
    }
    // 이미 슬롯에 배정된 카드를 다시 클릭 → 슬롯에서 빼서 pending 으로.
    const next: Record<PassSlot, string | null> = { ...passSelection };
    let wasAssigned = false;
    (Object.keys(next) as PassSlot[]).forEach((s) => {
      if (next[s] === key) {
        next[s] = null;
        wasAssigned = true;
      }
    });
    set({
      pendingPassCardKey: key,
      passSelection: wasAssigned ? next : passSelection,
    });
  },

  assignPassSlot(slot) {
    const { pendingPassCardKey, passSelection } = get();
    const next: Record<PassSlot, string | null> = { ...passSelection };
    if (pendingPassCardKey === null) {
      // pending 없음 + 채워진 슬롯 클릭 → 해당 슬롯 해제 (되돌리기).
      if (next[slot] !== null) {
        next[slot] = null;
        set({ passSelection: next });
      }
      return;
    }
    // pending 카드를 slot 에 배정. 같은 카드가 다른 슬롯에 있으면 비움.
    (Object.keys(next) as PassSlot[]).forEach((s) => {
      if (next[s] === pendingPassCardKey) next[s] = null;
    });
    next[slot] = pendingPassCardKey;
    set({ passSelection: next, pendingPassCardKey: null });
  },

  clearPassSelection() {
    set({ passSelection: { ...EMPTY_PASS }, pendingPassCardKey: null });
  },

  reorderHand(fromKey, toKey) {
    const hand = get().privateHand?.cards ?? [];
    if (hand.length === 0) return;
    // 현재 표시 순서를 기준으로 재배열.
    const current = sortedHand(get()).map(cardKey);
    const fromIdx = current.indexOf(fromKey);
    const toIdx = current.indexOf(toKey);
    if (fromIdx < 0 || toIdx < 0 || fromIdx === toIdx) return;
    const next = [...current];
    next.splice(fromIdx, 1);
    next.splice(toIdx, 0, fromKey);
    set({ sortOrder: next });
  },

}));
