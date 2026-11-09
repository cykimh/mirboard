/**
 * 요트 와이어 타입 (`docs/stomp-protocol.md` "요트" 절, 서버 `domain.game.yacht`). 서버가 정본이고 여기는 미러다.
 * 클라는 점수를 계산하지 않는다 — 후보 점수(`candidates`)도 서버가 준다(D-182).
 */

/** 점수표 12칸 — 선언 순서가 점수표 순서다(서버 `Category` 와 같다). */
export const CATEGORIES = [
  'ONES',
  'TWOS',
  'THREES',
  'FOURS',
  'FIVES',
  'SIXES',
  'CHOICE',
  'FOUR_OF_A_KIND',
  'FULL_HOUSE',
  'SMALL_STRAIGHT',
  'LARGE_STRAIGHT',
  'YACHT',
] as const;
export type Category = (typeof CATEGORIES)[number];

export const UPPER_CATEGORIES: readonly Category[] = ['ONES', 'TWOS', 'THREES', 'FOURS', 'FIVES', 'SIXES'];

/** 주사위 5개 눈(1~6). */
export type Dice = [number, number, number, number, number];
/** 주사위 5개 홀드. */
export type Held = [boolean, boolean, boolean, boolean, boolean];

export const NO_HOLD: Held = [false, false, false, false, false];

export type EndReason = 'FINISHED' | 'NO_HUMANS' | 'ACTION_LIMIT';

export interface Placement {
  seat: number;
  rank: number;
  total: number;
  deserted: boolean;
}

export interface MatchResult {
  reason: EndReason;
  placements: Placement[];
}

export interface YachtSeatView {
  seat: number;
  /** 채운 칸만 — 칸 → 점수. */
  scores: Partial<Record<Category, number>>;
  upperSubtotal: number;
  bonus: number;
  total: number;
  deserted: boolean;
}

/** resync `tableView`. */
export interface YachtTableView {
  phase: 'PLAYING' | 'ENDED';
  seats: YachtSeatView[];
  round: number;
  turnSeat: number;
  dice: Dice | null;
  held: Held;
  rolls: number;
  candidates: Partial<Record<Category, number>>;
  /** 차례 전체 마감까지(엔진 계산, D-183). 제한 끔·끝난 판이면 null. */
  turnRemainingMs: number | null;
  result: MatchResult | null;
}

/** 요트에는 비공개 뷰가 없다(resync `privateHand` 는 늘 null). */
export type YachtPrivateView = never;

export interface TurnChangedPayload {
  seat: number;
  round: number;
  turnSeconds: number;
}

export interface DiceHeldPayload {
  seat: number;
  held: Held;
}

export interface DiceRolledPayload {
  seat: number;
  dice: Dice;
  held: Held;
  rolls: number;
  candidates: Partial<Record<Category, number>>;
}

export interface ScoredPayload {
  seat: number;
  category: Category;
  points: number;
  upperSubtotal: number;
  bonus: number;
  total: number;
  auto: boolean;
}

export interface PlayerEliminatedPayload {
  seat: number;
  total: number;
}

export type MatchEndedPayload = MatchResult;

export const MAX_ROLLS = 3;
export const BONUS_THRESHOLD = 63;
export const BONUS_POINTS = 35;
