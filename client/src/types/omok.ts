/**
 * 오목 와이어 타입 (D-175, `docs/stomp-protocol.md` "오목"). 숨길 정보가 없어 비공개 뷰가 없다 — resync 의 `privateHand` 는 늘
 * null 이고, 내 좌석은 방의 `playerIds` 에서 찾는다.
 */
export type OmokStone = 'BLACK' | 'WHITE';
export type OmokMode = 'SOLO' | 'TEAMS_2V2';
export type OmokRule = 'RENJU' | 'DOUBLE_THREE_ONLY' | 'FREESTYLE';
export type ForbiddenKind = 'DOUBLE_THREE' | 'DOUBLE_FOUR' | 'OVERLINE';
export type OmokEndReason = 'FIVE' | 'RESIGN' | 'TIMEOUT' | 'DESERTED' | 'BOARD_FULL' | 'NO_LEGAL_MOVE';

export interface OmokPoint {
  x: number;
  y: number;
}

export interface OmokMove extends OmokPoint {
  seat: number;
  timedOut: boolean;
}

export interface OmokForbiddenPoint extends OmokPoint {
  kind: ForbiddenKind;
}

export interface OmokMatchResult {
  winnerColor: OmokStone | null;
  winnerSeats: number[];
  reason: OmokEndReason;
  causeSeat: number;
  winLine: OmokPoint[];
}

/** resync `tableView`. */
export interface OmokTableView {
  mode: OmokMode;
  rule: OmokRule;
  seatColors: OmokStone[];
  moves: OmokMove[];
  turnSeat: number;
  forbidden: OmokForbiddenPoint[];
  timeouts: number[];
  result: OmokMatchResult | null;
}

export interface StonePlacedPayload extends OmokPoint {
  seat: number;
  color: OmokStone;
  moveNumber: number;
  timedOut: boolean;
}

export interface OmokTurnChangedPayload {
  seat: number;
  color: OmokStone;
  forbidden: OmokForbiddenPoint[];
}

export interface OmokMatchEndedPayload extends OmokMatchResult {
  totalMoves: number;
}

export const BOARD_SIZE = 15;
