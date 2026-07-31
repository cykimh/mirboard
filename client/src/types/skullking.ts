/**
 * 스컬킹 서버 계약의 클라 미러 (S6/D-103). 정본은 `docs/stomp-protocol.md` 의 "스컬킹" 절과
 * 서버 `SkullKingStateMapper` / `SkullKingEvent` 다 — 어긋나면 서버가 맞다.
 *
 * 티츄 타입(`types/tichu.ts`)과 의도적으로 분리한다. 카드 모델·좌석 수·단계가 전부 달라
 * 공유하면 양쪽에 옵셔널 필드가 번진다.
 */

// ---------- 카드 ----------

export type SkullSuit = 'GREEN' | 'PURPLE' | 'YELLOW' | 'BLACK';

export type SpecialKind =
  | 'PIRATE'
  | 'MERMAID'
  | 'SKULL_KING'
  | 'TIGRESS'
  | 'ESCAPE';

/** 티그리스를 낼 때의 선언 — 그 선언값이 강약·동점·보너스 모두의 정체성이다 (§13-②③⑩). */
export type TigressMode = 'PIRATE' | 'ESCAPE';

/**
 * 서버 `SkullCard` record 와 1:1. 색상 카드는 `suit`+`rank`(1~14), 특수 카드는 `special`
 * (다른 한쪽은 null).
 *
 * 같은 값의 특수 카드가 손패에 여러 장 있을 수 있다 (해적 5장 등) — 서버가 개체를 구분하지
 * 않으므로(D-101) 클라도 "몇 번째 해적"을 왕복시키지 않는다. 리스트 렌더링 key 는 값이 아니라
 * **인덱스**를 써야 한다.
 */
export interface SkullCard {
  suit: SkullSuit | null;
  rank: number;
  special: SpecialKind | null;
}

export const isSuitCard = (c: SkullCard): boolean => c.special === null;
export const isBlack = (c: SkullCard): boolean => c.suit === 'BLACK';

/** 화면 표시용 짧은 라벨 (예: "초록 7", "해적"). */
export function cardLabel(card: SkullCard): string {
  if (card.special) return SPECIAL_LABEL[card.special];
  return `${SUIT_LABEL[card.suit as SkullSuit]} ${card.rank}`;
}

export const SUIT_LABEL: Record<SkullSuit, string> = {
  GREEN: '초록',
  PURPLE: '보라',
  YELLOW: '노랑',
  BLACK: '검정',
};

export const SPECIAL_LABEL: Record<SpecialKind, string> = {
  PIRATE: '해적',
  MERMAID: '인어',
  SKULL_KING: '스컬킹',
  TIGRESS: '티그리스',
  ESCAPE: '탈출',
};

// ---------- 공개 뷰 (resync tableView) ----------

export type SkullKingPhase = 'BIDDING' | 'PLAYING' | 'ROUND_END';

/** 좌석 하나의 공개 상태. `bid` 는 전원 제출 후에만 값이 온다 (§5 동시 공개). */
export interface SeatView {
  seat: number;
  handCount: number;
  hasBid: boolean;
  bid: number | null;
  tricksWon: number;
}

export interface PlayedCardView {
  seat: number;
  card: SkullCard;
  declaredAs: TigressMode | null;
}

export interface RoundScoreView {
  bid: number;
  won: number;
  base: number;
  bonus: number;
  total: number;
}

/** 서버 `SkullKingStateMapper.TableView` 와 1:1. */
export interface SkullKingTableView {
  phase: SkullKingPhase;
  roundNumber: number;
  /** 이 라운드의 트릭 수이자 예측 상한. 손패가 줄어도 변하지 않는다 (§4). */
  handSize: number;
  startSeat: number;
  /** PLAYING 이 아니거나 트릭이 다 찼으면 -1. */
  currentTurnSeat: number;
  seats: SeatView[];
  trick: PlayedCardView[];
  cumulativeScores: Record<number, number>;
  /** 탈주 좌석 — 유령으로 남아 자동조종된다 (D-104). */
  desertedSeats: number[];
  /** ROUND_END 에만 채워진다. */
  roundScores: Record<number, RoundScoreView>;
}

/** 서버 `SkullKingStateMapper.PrivateView` 와 1:1. `myBid` 는 공개 전 구간에만. */
export interface SkullKingPrivateView {
  seat: number;
  hand: SkullCard[];
  myBid: number | null;
}

/** `GET /api/rooms/{id}/resync` 응답 — tableView/privateHand 만 게임별로 다르다. */
export interface SkullKingResyncResponse {
  roomId: string;
  phase: SkullKingPhase;
  eventSeq: number;
  tableView: SkullKingTableView;
  privateHand: SkullKingPrivateView | null;
  disconnectedSeats: number[];
  chips: Record<number, number> | null;
}

// ---------- 이벤트 payload ----------

export interface BiddingStartedPayload {
  roundNumber: number;
  handSize: number;
}
/** 값 없음 — 제출 사실만 공개된다 (§5). */
export interface BidSubmittedPayload {
  seat: number;
}
export interface BidsRevealedPayload {
  bids: Record<number, number>;
}
export interface PlayingStartedPayload {
  leadSeat: number;
}
export interface CardPlayedPayload {
  seat: number;
  card: SkullCard;
  declaredAs: TigressMode | null;
}
export interface TurnChangedPayload {
  currentTurnSeat: number;
}
export interface TrickTakenPayload {
  winnerSeat: number;
  winningCard: SkullCard;
  trickNumber: number;
}
export interface RoundEndedPayload {
  roundNumber: number;
  scores: Record<number, RoundScoreView>;
  cumulativeScores: Record<number, number>;
}
export interface SeatDesertedPayload {
  seat: number;
}
export interface MatchEndedPayload {
  /** 공동 승리 가능, 탈주 좌석 제외 (§13-⑰⑳). */
  winners: number[];
  finalScores: Record<number, number>;
  roundsPlayed: number;
}
export interface HandDealtPayload {
  seat: number;
  cards: SkullCard[];
  roundNumber: number;
}

// ---------- 액션 ----------

export interface PlaceBidAction {
  '@action': 'PLACE_BID';
  bid: number;
}

export interface PlayCardAction {
  '@action': 'PLAY_CARD';
  card: SkullCard;
  /** 티그리스에만. 다른 카드에 실으면 서버가 INVALID_TIGRESS_DECLARATION 으로 거절. */
  declaredAs?: TigressMode;
}

export type SkullKingAction = PlaceBidAction | PlayCardAction;
