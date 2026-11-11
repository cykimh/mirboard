/**
 * 조이스틱 판정 (D-253) — 순수 함수만. 왼손 조이스틱의 좌우는 걷기, 위아래는 포신 올리기·내리기다. 축은 더 크게 민 쪽 하나만 쓴다.
 *
 * <p>각도는 서버와 같은 0~180(0 = 오른쪽 수평, 90 = 위, 180 = 왼쪽 수평). 조이스틱은 "바라보는 쪽"을 두고 그쪽 기준 높이(0~90)를
 * 올리고 내린다 — 오른쪽을 보면 위로 밀수록 90 쪽, 왼쪽을 보면 180 → 90 쪽. 반대쪽으로 밀면 먼저 포신을 그쪽으로 돌린다(거울).
 */

/** 이 비율(반지름 대비)보다 덜 밀면 아무것도 아니다. */
export const STICK_DEAD = 0.3;
/** 대각선에서 축이 떨리지 않게 — 지금 축은 이 배만큼 우대한다. */
export const AXIS_STICKY = 1.25;
/** 방향을 돌린 뒤 이만큼(ms) 지나야 걷기 시작한다 — 짧게 톡 밀면 돌기만 한다(걸음은 바로 확정돼 연료가 나가니까). */
export const WALK_FLIP_DELAY_MS = 200;
/** 각도 — 처음 1° 뒤 이만큼(ms) 누르고 있어야 연속으로 돈다(톡 = 1°). */
export const AIM_REPEAT_DELAY_MS = 250;
/** 걷기·각도를 다시 보는 주기(ms) — 걷기는 예전 버튼과 같은 박자(한 칸 / 40ms). */
export const DRIVE_TICK_MS = 40;

export type Axis = 'none' | 'walk' | 'aim';
export type Facing = -1 | 1;

export interface StickIntent {
  axis: Axis;
  /** walk: −1 왼쪽 / 1 오른쪽. aim: 1 올리기 / −1 내리기. none: 0. */
  dir: -1 | 0 | 1;
  /** 죽은 영역 밖으로 민 정도 0~1. */
  strength: number;
}

const NONE: StickIntent = { axis: 'none', dir: 0, strength: 0 };

/** 손가락 위치(중심 기준 화면 픽셀, y 는 아래가 +) → 반지름 안으로 자른 정규 벡터(−1~1). */
export function stickVector(dx: number, dy: number, radius: number): { nx: number; ny: number } {
  const r = Math.max(1, radius);
  const len = Math.hypot(dx, dy);
  const k = len > r ? r / len : 1;
  return { nx: (dx * k) / r, ny: (dy * k) / r };
}

/** 정규 벡터 → 축·방향·세기. {@code prev} 는 지금 축(떨림 막기). y 는 화면처럼 아래가 + 라 위로 밀면 올리기(+1). */
export function stickIntent(nx: number, ny: number, prev: Axis = 'none'): StickIntent {
  const mag = Math.min(1, Math.hypot(nx, ny));
  if (mag < STICK_DEAD) return NONE;
  const ax = Math.abs(nx);
  const ay = Math.abs(ny);
  let axis: Axis;
  if (prev === 'walk') axis = ax * AXIS_STICKY >= ay ? 'walk' : 'aim';
  else if (prev === 'aim') axis = ay * AXIS_STICKY >= ax ? 'aim' : 'walk';
  else axis = ax >= ay ? 'walk' : 'aim';
  const strength = Math.min(1, (mag - STICK_DEAD) / (1 - STICK_DEAD));
  if (axis === 'walk') return { axis, dir: nx < 0 ? -1 : 1, strength };
  return { axis, dir: ny < 0 ? 1 : -1, strength };
}

/** 지금 바라보는 쪽 — 각도가 90 이면(정확히 위) 알고 있던 쪽. */
export function facingOf(angle: number, known: Facing): Facing {
  if (angle < 90) return 1;
  if (angle > 90) return -1;
  return known;
}

/** {@code dir} 쪽을 보게 — 반대쪽을 보고 있으면 거울 각도, 아니면 그대로. */
export function faceToward(angle: number, dir: Facing): number {
  if (dir === 1 && angle > 90) return 180 - angle;
  if (dir === -1 && angle < 90) return 180 - angle;
  return angle;
}

/** 바라보는 쪽 기준 높이(0 = 수평, 90 = 위). */
export function elevation(angle: number): number {
  return angle <= 90 ? angle : 180 - angle;
}

/** 포신을 {@code deg}° 올리거나(dir 1) 내린다(dir −1). 높이는 0~90 안, 바라보는 쪽은 그대로. */
export function nudgeAngle(angle: number, facing: Facing, dir: 1 | -1, deg = 1): number {
  const side = facingOf(angle, facing);
  const rel = Math.max(0, Math.min(90, elevation(angle) + dir * deg));
  return side === 1 ? rel : 180 - rel;
}

/** 연속으로 돌 때의 빠르기(°/초) — 조금 밀면 천천히(조준 다듬기), 끝까지 밀면 빠르게. */
export function aimRate(strength: number): number {
  const s = Math.max(0, Math.min(1, strength));
  return 8 + 62 * s * s;
}
