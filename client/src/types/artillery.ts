/**
 * 씨앗포 와이어 타입 (`docs/stomp-protocol.md` "씨앗포" 절, 서버 `domain.game.artillery`). 서버가 정본이고 여기는 미러다.
 * 클라는 물리를 계산하지 않는다 — 궤적·구멍·피해는 서버 대본(`ShotReport`)을 재생만 한다(D-220). 예외는 이동 미리보기 하나.
 */

export const FIELD_W = 640;
export const FIELD_H = 360;
export const FUEL = 100;
export const MAX_MOVE = 100;

export type Shell = 'PEA' | 'PUMPKIN';
export type OutCause = 'KNOCKED_OUT' | 'CLOUD_SEA' | 'DESERTED';
export type Phase = 'AIMING' | 'RESOLVING' | 'ENDED';

export interface UnitView {
  seat: number;
  x: number;
  footY: number;
  hp: number;
  pumpkins: number;
  alive: boolean;
  outCause: OutCause | null;
}

export interface Placement {
  seat: number;
  rank: number;
  hp: number;
  deserted: boolean;
}

export interface MatchResult {
  reason: 'LAST_STANDING' | 'ALL_DOWN' | 'NO_HUMANS';
  placements: Placement[];
}

export interface Crater {
  x: number;
  y: number;
  r: number;
}

export interface FallReport {
  seat: number;
  x: number;
  fromY: number;
  toY: number;
  damage: number;
  hpAfter: number;
  intoCloud: boolean;
}

export interface ShotReport {
  seat: number;
  angle: number;
  power: number;
  shell: Shell;
  wind: number;
  move: { path: number[]; toX: number; toY: number; fuelLeft: number; fall: FallReport | null };
  shot: {
    path: number[];
    end: 'TERRAIN' | 'UNIT' | 'OUT' | 'CLOUD' | 'EXPIRED';
    impactX: number;
    impactY: number;
    crater: Crater | null;
    hitSeat: number;
    ticks: number;
  } | null;
  damages: { seat: number; amount: number; hpAfter: number; direct: boolean }[];
  falls: FallReport[];
  eliminated: { seat: number; cause: OutCause }[];
  units: { seat: number; x: number; footY: number; hp: number; alive: boolean }[];
  durationMs: number;
}

export interface ArtilleryTableView {
  phase: Phase;
  seatCount: number;
  width: number;
  height: number;
  units: UnitView[];
  /** 640열 각각의 흙 구간 [시작, 끝, …] (양끝 포함). */
  terrain: number[][];
  turnSeat: number;
  turnNo: number;
  wind: number;
  cloudLevel: number;
  suddenDeathTurn: number;
  fuel: number;
  resolveRemainingMs: number | null;
  lastShot: ShotReport | null;
  result: MatchResult | null;
}

/** 비공개 뷰가 없다. */
export type ArtilleryPrivateView = never;

export interface TurnChangedPayload {
  seat: number;
  turnNo: number;
  wind: number;
  cloudLevel: number;
}

export interface TurnSkippedPayload {
  seat: number;
  reason: 'SKIP' | 'TIMEOUT';
}

export interface UnitEliminatedPayload {
  seat: number;
  cause: OutCause;
  cloudLevel: number;
}

export type MatchEndedPayload = MatchResult;
