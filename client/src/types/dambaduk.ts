/**
 * 담바둑 와이어 타입 (D-274~D-278, `docs/stomp-protocol.md` "담바둑", 룰 정본 `docs/rules-dambaduk.md`). 숨길 정보가 없어 비공개 뷰가
 * 없다 — resync 의 `privateHand` 는 늘 null 이고, 내 좌석은 방의 `playerIds` 에서 찾는다. 터·점수·고를 수 있는 수는 서버가 계산해
 * 보낸다 — 클라는 판정하지 않는다.
 */
export type DambadukDir = 'UP' | 'RIGHT' | 'DOWN' | 'LEFT';
export type DambadukStoneKind = 'STRAIGHT' | 'BENT';
export type DambadukPhase = 'PLACING' | 'PLAYING' | 'ENDED';
export type DambadukRegionKind = 'HOME' | 'CONTESTED' | 'EMPTY';
export type DambadukEndReason = 'SEPARATED' | 'FROZEN' | 'LAST_STANDING' | 'TIMEOUT' | 'DESERTED';
export type DambadukExitReason = 'TIMEOUT' | 'DESERTED';

export interface DambadukCell {
  x: number;
  y: number;
}

export interface DambadukStone extends DambadukCell {
  id: number;
  seat: number;
  kind: DambadukStoneKind;
}

export interface DambadukRegion {
  /** 칸 번호(`y·7 + x`) 오름차순. */
  cells: number[];
  kind: DambadukRegionKind;
  /** 집이면 그 좌석, 아니면 −1. */
  owner: number;
  seats: number[];
}

export interface DambadukTerritory {
  regions: DambadukRegion[];
  /** 좌석별 집 칸 수 합. */
  scores: number[];
  /** 좌석별 가장 큰 집. */
  largest: number[];
}

/** 차례인 사람이 고를 수 있는 옮김 하나 — 서버가 계산한다. */
export interface DambadukMoveOption {
  stoneId: number;
  path: DambadukDir[];
  toX: number;
  toY: number;
  jump: boolean;
  /** 담 넘기로 없앨 면, 아니면 −1. */
  jumpedFace: number;
  /** 세울 수 있는 담(도착 칸 기준 방향). */
  walls: DambadukDir[];
}

export interface DambadukExit {
  seat: number;
  reason: DambadukExitReason;
}

export interface DambadukStanding {
  seat: number;
  score: number;
  largest: number;
  rank: number;
  exit: DambadukExitReason | null;
}

export interface DambadukMatchResult {
  reason: DambadukEndReason;
  standings: DambadukStanding[];
  winners: number[];
  draw: boolean;
  causeSeat: number;
}

/** resync `tableView`. */
export interface DambadukTableView {
  seatCount: number;
  firstSeat: number;
  /** 좌석별 색 번호 — 0 흑(선) · 1 백 · 2 청. */
  seatColors: number[];
  phase: DambadukPhase;
  stones: DambadukStone[];
  /** 세워진 담의 면 번호 오름차순. */
  walls: number[];
  placementQueue: number[];
  turnSeat: number;
  jumpsLeft: number[];
  timeouts: number[];
  exits: DambadukExit[];
  moveCount: number;
  territory: DambadukTerritory;
  placeKinds: DambadukStoneKind[];
  options: DambadukMoveOption[];
  result: DambadukMatchResult | null;
}

export interface StonePlacedPayload extends DambadukCell {
  seat: number;
  stoneId: number;
  kind: DambadukStoneKind;
  timedOut: boolean;
}

export interface StoneMovedPayload {
  seat: number;
  stoneId: number;
  path: DambadukDir[];
  fromX: number;
  fromY: number;
  toX: number;
  toY: number;
  wall: DambadukDir;
  wallFace: number;
  jumpedFace: number;
  timedOut: boolean;
}

export interface DambadukTurnChangedPayload {
  seat: number;
  phase: DambadukPhase;
  territory: DambadukTerritory;
  placeKinds: DambadukStoneKind[];
  options: DambadukMoveOption[];
}

export interface SeatOutPayload {
  seat: number;
  reason: DambadukExitReason;
  removedStoneIds: number[];
}

export interface DambadukMatchEndedPayload extends DambadukMatchResult {
  territory: DambadukTerritory;
  moveCount: number;
}

/** 판 크기 — 7×7 칸. */
export const BOARD_SIZE = 7;
/** 안쪽 면 수 — 가로 이웃 사이 42(0~41) + 세로 이웃 사이 42(42~83). */
export const VERTICAL_FACES = (BOARD_SIZE - 1) * BOARD_SIZE;
export const FACE_COUNT = VERTICAL_FACES * 2;
export const DIRS: readonly DambadukDir[] = ['UP', 'RIGHT', 'DOWN', 'LEFT'];
