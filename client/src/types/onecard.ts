// 원카드 STOMP·resync 계약 — `docs/stomp-protocol.md` 원카드 절(D-128, D-129)과 일치.

export type OneCardSuit = 'SPADE' | 'HEART' | 'DIAMOND' | 'CLUB';
export type OneCardJoker = 'BLACK' | 'COLOR';

/** 서버 `PlayingCard` 직렬화 — 무늬 카드는 `joker` 가 null, 조커는 `suit` null·`rank` 0 이다. */
export interface OneCardCard {
  suit: OneCardSuit | null;
  rank: number;
  joker: OneCardJoker | null;
}

export type OneCardPhase = 'PLAYING' | 'RACE' | 'ENDED';
export type EliminationReason = 'BANKRUPT' | 'DESERTED';

export interface OneCardSeatView {
  seat: number;
  handCount: number;
  /** 탈락 사유, 살아 있으면 null (boolean 이 아니다). */
  eliminated: EliminationReason | null;
}

/** resync 의 경쟁 창 — 봇이 누를 시각은 싣지 않는다(State Hiding). */
export interface OneCardRaceView {
  raceId: number;
  ownerSeat: number;
  /** 0..7 — 슬롯 좌표는 클라 프로토콜 상수(`raceSlots.ts`). */
  slot: number;
  /** −100..100 — 슬롯 반경의 백분율. */
  jitterX: number;
  jitterY: number;
  windowMillis: number;
  /** 창 끝까지 남은 시간(서버 시계 기준). */
  remainingMillis: number;
}

export type EndReason = 'FINISHED' | 'LAST_STANDING' | 'NO_HUMANS' | 'STALEMATE';
export type StandingStatus = 'FINISHED' | 'ALIVE' | 'BANKRUPT' | 'DESERTED';

export interface OneCardStanding {
  seat: number;
  /** 1부터, 동순위 다음은 건너뛴다(1, 1, 3). */
  rank: number;
  /** 살아 있으면 남은 장수, 탈락했으면 탈락 순간의 장수. */
  cardsLeft: number;
  status: StandingStatus;
}

export interface OneCardMatchResult {
  reason: EndReason;
  standings: OneCardStanding[];
}

export interface OneCardTableView {
  phase: OneCardPhase;
  seats: OneCardSeatView[];
  topCard: OneCardCard | null;
  declaredSuit: OneCardSuit | null;
  attackStack: number;
  direction: number;
  turnSeat: number;
  drawPileCount: number;
  race: OneCardRaceView | null;
  result: OneCardMatchResult | null;
}

export interface OneCardPrivateView {
  seat: number;
  hand: OneCardCard[];
  /** 상태 버전 — 비공개 이벤트의 `handVersion` 과 같은 축. 낮은 쪽을 버린다. */
  handVersion: number;
}

// ── 이벤트 payload ────────────────────────────────────────────────────

/** `HAND_DEALT`·`HAND_UPDATED` 공통 — 손패 **전체**와 버전. */
export interface HandPayload {
  seat: number;
  hand: OneCardCard[];
  handVersion: number;
  /** `HAND_UPDATED` 만 — 이번에 새로 받은 카드. */
  received?: OneCardCard[];
}

export interface CardPlayedPayload {
  seat: number;
  card: OneCardCard;
  declaredSuit: OneCardSuit | null;
  handCount: number;
  attackStack: number;
  direction: number;
}

export type DrawReason = 'TURN' | 'ATTACK' | 'PENALTY';

export interface CardsDrawnPayload {
  seat: number;
  count: number;
  reason: DrawReason;
  handCount: number;
  drawPileCount: number;
}

export interface PileReshuffledPayload {
  drawPileCount: number;
}

export interface TurnChangedPayload {
  seat: number;
  direction: number;
  attackStack: number;
}

export interface RaceOpenedPayload {
  raceId: number;
  ownerSeat: number;
  slot: number;
  jitterX: number;
  jitterY: number;
  windowMillis: number;
}

export type RaceOutcome = 'CALLED' | 'CAUGHT' | 'EXPIRED' | 'CANCELLED';

export interface RaceResolvedPayload {
  raceId: number;
  outcome: RaceOutcome;
  /** 누른 좌석, 아무도 안 눌렀으면 −1. */
  bySeat: number;
}

export interface PlayerEliminatedPayload {
  seat: number;
  reason: EliminationReason;
  cardsHeld: number;
  /** 탈락자 손패를 더미에 넣은 뒤 장수(최종값). */
  drawPileCount: number;
}

export type MatchEndedPayload = OneCardMatchResult;

// ── 표시 라벨 ─────────────────────────────────────────────────────────

export const SUIT_SYMBOL: Record<OneCardSuit, string> = {
  SPADE: '♠',
  HEART: '♥',
  DIAMOND: '♦',
  CLUB: '♣',
};

export const SUIT_LABEL: Record<OneCardSuit, string> = {
  SPADE: '스페이드',
  HEART: '하트',
  DIAMOND: '다이아',
  CLUB: '클로버',
};

export const JOKER_LABEL: Record<OneCardJoker, string> = {
  BLACK: '흑백 조커',
  COLOR: '컬러 조커',
};

/** 1→A, 11→J, 12→Q, 13→K. */
export function rankLabel(rank: number): string {
  switch (rank) {
    case 1:
      return 'A';
    case 11:
      return 'J';
    case 12:
      return 'Q';
    case 13:
      return 'K';
    default:
      return String(rank);
  }
}

/** 접근명·해설용 이름 — "하트 7", "흑백 조커". */
export function cardLabel(card: OneCardCard): string {
  if (card.joker) return JOKER_LABEL[card.joker];
  return `${SUIT_LABEL[card.suit!]} ${rankLabel(card.rank)}`;
}

/** 값 비교용 키 — 54장이 모두 다르므로 손패 안에서 유일하다. */
export function cardKey(card: OneCardCard): string {
  return card.joker ? `JOKER-${card.joker}` : `${card.suit}-${card.rank}`;
}
