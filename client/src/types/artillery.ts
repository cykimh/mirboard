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
  /** 팀(D-225) — 팀전이면 좌석 홀짝(0·1), 개인전이면 좌석. 구 서버·픽스처면 없다. */
  team?: number;
  /** 누적 딜레이(D-226) — 가장 작은 살아 있는 포대가 다음 차례. */
  delay?: number;
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
  /** 팀전 판(D-225). */
  teams?: boolean;
  /** 팀전에서 이긴 팀(0·1), 무승부·개인전이면 null. */
  winnerTeam?: number | null;
}

export type TurnOrder = 'SEAT' | 'DELAY';

/** 딜레이 표(D-226) — 서버 정본을 그대로 받는다. 화면의 "이번 차례 +N" 안내용(판정은 서버). */
export interface DelayTable {
  shells: Partial<Record<Shell, number>>;
  items: Partial<Record<Item, number>>;
  skip: number;
  fuelPerDelay: number;
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
  /** 2:2 팀전(D-225). */
  teams?: boolean;
  turnOrder?: TurnOrder;
  /** 앞으로의 차례 순서 — 살아 있는 좌석, 지금 차례 주인부터(D-226). */
  queue?: number[];
  delays?: DelayTable;
  /** AIMING 차례 전체 마감까지 남은 시간(엔진 계산, D-228) — `MOVE` 를 해도 차례 시작 기준. 구 서버·시계 없는 판이면 없음/null. */
  turnRemainingMs?: number | null;
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
  /** 좌석 순 누적 딜레이(D-226). */
  delays?: number[];
  /** 앞으로의 차례 순서(지금 차례 주인부터). */
  queue?: number[];
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

/** 낙하 한 번(대본·`UNIT_MOVED` 공통). */
export interface FallView {
  seat: number;
  x: number;
  fromY: number;
  toY: number;
  damage: number;
  hpAfter: number;
  intoCloud: boolean;
}

/** `MOVE` 확정 (D-228) — 순번 있음, 결과값. 탈락이면 그 차례가 끝난다. */
export interface UnitMovedPayload {
  seat: number;
  turn: number;
  /** 걸음마다 발 칸 [x, footY, …] (출발점 제외). */
  path: number[];
  toX: number;
  toY: number;
  fuelLeft: number;
  fall: FallView | null;
  hpAfter: number;
  eliminated: OutCause | null;
}

/** 조준 미리보기 (D-229) — 순번 없음, 힘·위치 없음. */
export interface AimPreviewPayload {
  seat: number;
  turn: number;
  angle: number;
  shell: Shell | null;
  n: number;
}
