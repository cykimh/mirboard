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
  /** D-193 — 좌석별 남은 강하게 두기 수(0 또는 1). 옛 서버면 없다. */
  slamsLeft?: number[];
  /** D-193 — 판 줄 수(19). 옛 서버면 없다. */
  boardSize?: number;
}

export interface StonePlacedPayload extends OmokPoint {
  seat: number;
  color: OmokStone;
  moveNumber: number;
  timedOut: boolean;
  /** D-193 — 강하게 두기(연출 전용). 옛 서버면 없다. */
  slam?: boolean;
}

export interface OmokTurnChangedPayload {
  seat: number;
  color: OmokStone;
  forbidden: OmokForbiddenPoint[];
}

export interface OmokMatchEndedPayload extends OmokMatchResult {
  totalMoves: number;
}

/** D-193 — 바둑판 크기 19줄(이전 15). 서버 resync `boardSize` 와 같다. */
export const BOARD_SIZE = 19;
/** 가로 좌표 글자 — 바둑 관례대로 I 를 뺀 A~T(1·l 과 헷갈리지 않게). */
export const COLUMN_LETTERS = 'ABCDEFGHJKLMNOPQRST';
