/**
 * 씨앗포 와이어 타입 (`docs/stomp-protocol.md` "씨앗포" 절, 서버 `domain.game.artillery`). 서버가 정본이고 여기는 미러다.
 * 클라는 물리를 계산하지 않는다 — 궤적·구멍·피해는 서버 대본(`ShotReport`)을 재생만 한다(D-220). 예외는 이동 미리보기 하나.
 */

export const FIELD_W = 640;
export const FIELD_H = 360;
export const FUEL = 100;
/** 이동 범위 상한 — 가장 큰 연료(민들레포 130, D-224). 실제 걸음은 유닛 연료가 정한다. */
export const MAX_MOVE = 130;
/** 판 시작 고르기 시간(ms, D-224). */
export const PICK_MS = 15_000;

export type Shell = 'PEA' | 'PUMPKIN' | 'VINE' | 'CHESTNUT' | 'SPORE';
export type UnitKind = 'SPROUT' | 'ACORN' | 'DANDELION';
export type Item = 'WATERING_CAN' | 'PINWHEEL' | 'TWIN_SEED';
export type OutCause = 'KNOCKED_OUT' | 'CLOUD_SEA' | 'DESERTED';
export type Phase = 'PICKING' | 'AIMING' | 'RESOLVING' | 'ENDED';

/** 서버 `UnitKind` 미러 — 화면 안내용(판정은 서버). */
export const UNIT_KINDS: Record<
  UnitKind,
  { name: string; hp: number; fuel: number; windPercent: number; special: Shell; blurb: string }
> = {
  SPROUT: { name: '새싹포', hp: 100, fuel: 100, windPercent: 100, special: 'VINE', blurb: '균형형 — 덩굴탄으로 흙 둔덕을 쌓는다' },
  ACORN: { name: '도토리포', hp: 120, fuel: 70, windPercent: 60, special: 'CHESTNUT', blurb: '무겁고 단단함 — 알밤탄으로 땅을 크게 꺼뜨린다' },
  DANDELION: { name: '민들레포', hp: 85, fuel: 130, windPercent: 140, special: 'SPORE', blurb: '가볍고 빠름 — 홀씨탄이 꼭대기에서 세 갈래' },
};

export const SHELL_NAMES: Record<Shell, string> = {
  PEA: '콩알',
  PUMPKIN: '호박탄',
  VINE: '덩굴탄',
  CHESTNUT: '알밤탄',
  SPORE: '홀씨탄',
};

export const ITEM_NAMES: Record<Item, string> = {
  WATERING_CAN: '물뿌리개',
  PINWHEEL: '바람개비',
  TWIN_SEED: '겹씨앗',
};

export const ITEM_HINTS: Record<Item, string> = {
  WATERING_CAN: '체력 +25 (최대 체력까지)',
  PINWHEEL: '이번 발은 바람을 받지 않는다',
  TWIN_SEED: '같은 조준으로 한 발 더',
};

export interface UnitView {
  seat: number;
  x: number;
  footY: number;
  hp: number;
  pumpkins: number;
  alive: boolean;
  outCause: OutCause | null;
  /** 고른 종류 — 고르기 단계에서는 null(다 고를 때까지 숨김). */
  kind: UnitKind | null;
  picked: boolean;
  maxHp: number;
  fuel: number;
  special: Shell | null;
  specials: number;
  items: Item[];
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

/** 지형 연산 — 구멍(지우기) 또는 덩굴탄 둔덕(fill, spare 의 몸 중심 반경 7 은 비움). */
export interface Crater {
  x: number;
  y: number;
  r: number;
  fill?: boolean;
  spare?: number[];
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

export interface ShotTrace {
  path: number[];
  end: 'TERRAIN' | 'UNIT' | 'OUT' | 'CLOUD' | 'EXPIRED' | 'SPLIT';
  impactX: number;
  impactY: number;
  crater: Crater | null;
  hitSeat: number;
  /** 단계 시작부터 센 출발 틱(갈라진 홀씨는 갈라진 틱). */
  startTick: number;
  /** 이 씨앗이 난 틱 수. */
  ticks: number;
}

/** 한 발 — 씨앗들이 날고 다 내려앉으면 구멍·둔덕 → 피해 → 낙하. 겹씨앗이면 대본에 둘. */
export interface ShotStage {
  shots: ShotTrace[];
  damages: { seat: number; amount: number; hpAfter: number; direct: boolean }[];
  falls: FallReport[];
}

export interface ShotReport {
  seat: number;
  angle: number;
  power: number;
  shell: Shell;
  item: Item | null;
  wind: number;
  heal: { amount: number; hpAfter: number } | null;
  move: { path: number[]; toX: number; toY: number; fuelLeft: number; fall: FallReport | null };
  /** 발사하지 못했으면(걷다가 탈락) 빈 목록. */
  stages: ShotStage[];
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
  /** 고르기 단계의 남은 시간(ms). */
  pickRemainingMs: number | null;
  lastShot: ShotReport | null;
  result: MatchResult | null;
}

/** 비공개 뷰 — 고르기 단계의 내 선택뿐(D-224). */
export interface ArtilleryPrivateView {
  pickedKind: UnitKind | null;
}

export interface UnitPickedPayload {
  seat: number;
}

export interface UnitsRevealedPayload {
  units: UnitView[];
}

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
