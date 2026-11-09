// 구름항로 와이어 타입 — 서버 `domain.game.cloudroute`(D-211·D-212)와 1:1. 룰 정본 `docs/rules-cloudroute.md`.

export type CloudRouteLetter =
  | 'TAILWIND'
  | 'SAIL_REPAIR'
  | 'STAR_CHEST'
  | 'HEADWIND'
  | 'WHIRLPOOL_ALERT'
  | 'BUOY'
  | 'INSPECTION'
  | 'ISLAND_FEAST'
  | 'WIND_SHARE'
  | 'GUST'
  | 'LANTERN_GIFT'
  | 'FREE_FLIGHT';

export type EliminationReason = 'BANKRUPT' | 'DESERTED';
export type EndReason = 'LAST_STANDING' | 'NO_HUMANS' | 'ROUND_LIMIT';
export type StandingStatus = 'ALIVE' | 'BANKRUPT' | 'DESERTED';

/** 차례 좌석이 기다리는 결정 하나(서버 `Pending`, 판별자 `kind`). */
export type CloudRoutePending =
  | { kind: 'ROLL' }
  | { kind: 'WHIRLPOOL'; canPay: boolean; canUseBuoy: boolean }
  | { kind: 'PURCHASE'; tile: number; maxLevel: number }
  | { kind: 'BUILD'; tile: number; minLevel: number; maxLevel: number }
  | { kind: 'TAKEOVER'; tile: number; owner: number; price: number }
  | { kind: 'FESTIVAL'; candidates: number[] }
  | { kind: 'FLIGHT'; candidates: number[]; free?: boolean }
  | { kind: 'GIFT'; candidates: number[] }
  | {
      kind: 'SALE';
      debt: number;
      creditor: number;
      reason: string;
      tile: number;
      monopoly: boolean;
      festival: boolean;
      candidates: number[];
    };

export interface CloudRouteSeatView {
  seat: number;
  cash: number;
  position: number;
  trapped: boolean;
  failedEscapes: number;
  holdsBuoy: boolean;
  eliminated: EliminationReason | null;
  totalAssets: number;
}

export interface CloudRouteTileView {
  tile: number;
  owner: number;
  level: number;
}

export interface CloudRouteStanding {
  seat: number;
  rank: number;
  totalAssets: number;
  cash: number;
  status: StandingStatus;
}

export interface CloudRouteMatchResult {
  reason: EndReason;
  rounds: number;
  standings: CloudRouteStanding[];
}

/** 공개 뷰 — 편지 더미 순서만 빠진다(장수만, 룰 §13). 본인 전용 뷰는 없다. */
export interface CloudRouteTableView {
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
}

/** 클라 → 서버 액션(`@action` 판별자). 클라가 보내는 것은 선택뿐이다 — 주사위·가격은 서버가 정한다. */
export type CloudRouteAction =
  | { '@action': 'ROLL' }
  | { '@action': 'PAY_ESCAPE' }
  | { '@action': 'USE_BUOY' }
  | { '@action': 'BUY'; buildTo: number }
  | { '@action': 'DECLINE' }
  | { '@action': 'BUILD'; buildTo: number }
  | { '@action': 'SKIP' }
  | { '@action': 'TAKEOVER' }
  | { '@action': 'HOST_FESTIVAL'; tile: number }
  | { '@action': 'FLY'; tile: number }
  | { '@action': 'GIFT_BUILD'; tile: number }
  | { '@action': 'SELL'; tile: number };

// ---------- 공개 이벤트 payload (결과값) ----------

export type MoveKind = 'ROLL' | 'FLY' | 'LETTER' | 'WHIRLPOOL';
export type MoneyReason = 'CUSTOMS' | 'LETTER' | 'ESCAPE' | 'FLIGHT';
export type TransferReason = 'ISLAND_FEAST' | 'WIND_SHARE';
export type LeaveMethod = 'DOUBLES' | 'PAID' | 'BUOY' | 'SERVED';

export interface DiceRolled {
  seat: number;
  die1: number;
  die2: number;
  doubles: boolean;
  doublesThisTurn: number;
}
export interface TokenMoved {
  seat: number;
  from: number;
  to: number;
  kind: MoveKind;
  salary: number;
  cash: number;
}
export interface LandBought {
  seat: number;
  tile: number;
  level: number;
  paid: number;
  cash: number;
}
export type Built = LandBought;
export interface TollPaid {
  payer: number;
  owner: number;
  tile: number;
  amount: number;
  monopoly: boolean;
  festival: boolean;
  payerCash: number;
  ownerCash: number;
}
export interface TakenOver {
  buyer: number;
  seller: number;
  tile: number;
  price: number;
  buyerCash: number;
  sellerCash: number;
}
export interface LetterDrawn {
  seat: number;
  letter: CloudRouteLetter;
  deckCount: number;
  reshuffled: boolean;
}
export interface MoneyChanged {
  seat: number;
  delta: number;
  reason: MoneyReason;
  cash: number;
}
export interface MoneyTransferred {
  from: number;
  to: number;
  amount: number;
  reason: TransferReason;
  fromCash: number;
  toCash: number;
}
export interface SeatOnly {
  seat: number;
}
export interface EscapeFailed {
  seat: number;
  failedEscapes: number;
}
export interface WhirlpoolLeft {
  seat: number;
  method: LeaveMethod;
}
export interface FestivalHosted {
  seat: number;
  tile: number;
}
export interface FestivalCleared {
  tile: number;
}
export interface Sale {
  tile: number;
  land: boolean;
  levelAfter: number;
  received: number;
}
export interface AssetsLiquidated {
  seat: number;
  sales: Sale[];
  cash: number;
}
export interface PlayerEliminated {
  seat: number;
  reason: EliminationReason;
  creditor: number;
  returnedTiles: number[];
  cashForfeited: number;
}
export interface TurnChanged {
  seat: number;
  round: number;
}
export interface DecisionRequired {
  seat: number;
  pending: CloudRoutePending;
}
