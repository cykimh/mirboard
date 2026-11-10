import {
  FIELD_W,
  type ArtilleryPrivateView,
  type ArtilleryTableView,
  type DelayTable,
  type ShotReport,
  type ShotStage,
  type ShotTrace,
  type UnitView,
} from '@/types/artillery';
import { useArtilleryStore } from './artilleryStore';

/** 평평한 바닥(y ≥ floor 흙) 런. */
export function flatRuns(floor = 300): number[][] {
  return Array.from({ length: FIELD_W }, () => [floor, 359]);
}

export const unit = (seat: number, x: number, over: Partial<UnitView> = {}): UnitView => ({
  seat,
  x,
  footY: 299,
  hp: 100,
  pumpkins: 2,
  alive: true,
  outCause: null,
  kind: 'SPROUT',
  picked: true,
  maxHp: 100,
  fuel: 100,
  special: 'VINE',
  specials: 2,
  items: ['WATERING_CAN', 'PINWHEEL', 'TWIN_SEED'],
  ...over,
});

/** 서버 딜레이 표(D-226) 그대로. */
export const DELAY_TABLE: DelayTable = {
  shells: { PEA: 100, PUMPKIN: 130, VINE: 140, CHESTNUT: 140, SPORE: 140 },
  items: { WATERING_CAN: 30, PINWHEEL: 30, TWIN_SEED: 60 },
  skip: 80,
  fuelPerDelay: 2,
};

export function view(over: Partial<ArtilleryTableView> = {}): ArtilleryTableView {
  return {
    phase: 'AIMING',
    seatCount: 2,
    width: 640,
    height: 360,
    units: [unit(0, 100), unit(1, 500)],
    terrain: flatRuns(),
    turnSeat: 0,
    turnNo: 0,
    wind: 3,
    cloudLevel: 360,
    suddenDeathTurn: 20,
    fuel: 100,
    resolveRemainingMs: null,
    pickRemainingMs: null,
    lastShot: null,
    result: null,
    ...over,
  };
}

export function seedStore(
  over: Partial<ArtilleryTableView> = {},
  turnRemainingMs: number | null = null,
  privateHand: ArtilleryPrivateView | null = null,
) {
  const t = view(over);
  useArtilleryStore.getState().reset('r-1');
  useArtilleryStore.getState().applySnapshot({
    roomId: 'r-1',
    phase: t.phase,
    eventSeq: 1,
    tableView: t,
    privateHand,
    disconnectedSeats: [],
    turnRemainingMs,
  });
}

/** 직격 씨앗 하나 — 구멍 (500, 294, 14), 비행 8틱. */
export function hitShot(over: Partial<ShotTrace> = {}): ShotTrace {
  return {
    path: [109, 287, 200, 200, 300, 150, 400, 200, 500, 294],
    end: 'UNIT',
    impactX: 500,
    impactY: 294,
    crater: { x: 500, y: 294, r: 14 },
    hitSeat: 1,
    startTick: 0,
    ticks: 8,
    ...over,
  };
}

export function hitStage(over: Partial<ShotStage> = {}): ShotStage {
  return {
    shots: [hitShot()],
    damages: [{ seat: 1, amount: 30, hpAfter: 70, direct: true }],
    falls: [{ seat: 1, x: 500, fromY: 299, toY: 307, damage: 0, hpAfter: 70, intoCloud: false }],
    ...over,
  };
}

/** 좌석 0 이 좌석 1 을 직격 — 30 피해, 구멍 (500, 294, 14), 걸음 2, 비행 8틱, 낙하 8칸. */
export function hitReport(over: Partial<ShotReport> = {}): ShotReport {
  return {
    seat: 0,
    angle: 45,
    power: 60,
    shell: 'PEA',
    item: null,
    wind: 3,
    heal: null,
    move: { path: [101, 299, 102, 299], toX: 102, toY: 299, fuelLeft: 98, fall: null },
    stages: [hitStage()],
    eliminated: [],
    units: [
      { seat: 0, x: 102, footY: 299, hp: 100, alive: true },
      { seat: 1, x: 500, footY: 307, hp: 70, alive: true },
    ],
    durationMs: 1000,
    ...over,
  };
}
