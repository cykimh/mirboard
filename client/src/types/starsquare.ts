/**
 * 별자리 수판 와이어 타입(`docs/rules-starsquare.md` §11, 서버 `domain.game.starsquare`). 서버가 정본이고 여기는 미러다.
 * 클라는 완성·진행도·순위·점수를 판정하지 않는다 — 자기 판의 줄 합 표시만 더한다(§13).
 */

export type StarSquarePhase = 'INTRO' | 'PLAYING' | 'RESULT' | 'ENDED';
export type PuzzleEndReason = 'ALL_SOLVED' | 'CLOSED' | 'TIME_UP';
export type MatchEndReason = 'FINISHED' | 'NO_HUMANS' | 'LAST_SEAT';

/** 판 — 길이 N², 0 = 빈 칸. */
export type Grid = number[];

export interface SeatView {
  seat: number;
  filled: number;
  lines: number;
  solved: boolean;
  /** 완성 순서(1 부터), 아직이면 0. */
  solvePlace: number;
  /** 완성까지 걸린 시간(ms), 아직이면 −1. */
  elapsedMs: number;
  /** 점수 합(끝난 퍼즐들). */
  points: number;
  firsts: number;
  deserted: boolean;
}

export interface SeatResult {
  seat: number;
  rank: number;
  points: number;
  solved: boolean;
  elapsedMs: number;
  filled: number;
  lines: number;
  deserted: boolean;
}

export interface PuzzleOutcome {
  index: number;
  reason: PuzzleEndReason;
  results: SeatResult[];
  grids: Grid[];
  solution: Grid;
}

export interface Placement {
  seat: number;
  rank: number;
  points: number;
  firsts: number;
  deserted: boolean;
}

export interface MatchResult {
  reason: MatchEndReason;
  placements: Placement[];
}

/** 공개 뷰(`StarSquareStateMapper.PublicView`). 준비 단계는 퍼즐을 숨긴다(size 0, clues null). */
export interface StarSquareTableView {
  phase: StarSquarePhase;
  puzzleIndex: number;
  puzzleCount: number;
  size: number;
  magicSum: number;
  clues: Grid | null;
  remainingMs: number;
  closing: boolean;
  seats: SeatView[];
  lastPuzzle: PuzzleOutcome | null;
  result: MatchResult | null;
}

/** 비공개 뷰 — 내 판(재접속·다른 탭에서 이어 풀기). */
export interface StarSquarePrivateBoard {
  puzzleIndex: number;
  grid: Grid;
  version: number;
  solved: boolean;
}

// ── 공개 이벤트 payload ──

export interface PuzzleStartedPayload {
  index: number;
  count: number;
  size: number;
  magicSum: number;
  clues: Grid;
  limitMs: number;
  filled: number;
  lines: number;
}

export interface ProgressPayload {
  seat: number;
  filled: number;
  lines: number;
}

export interface SolvedPayload {
  seat: number;
  place: number;
  elapsedMs: number;
}

export interface ClosingPayload {
  remainingMs: number;
}

export interface PuzzleEndedPayload {
  index: number;
  reason: PuzzleEndReason;
  results: SeatResult[];
  grids: Grid[];
  solution: Grid;
  totals: number[];
  nextInMs: number;
}

export interface SeatDesertedPayload {
  seat: number;
}

export interface MatchEndedPayload {
  reason: MatchEndReason;
  placements: Placement[];
}
