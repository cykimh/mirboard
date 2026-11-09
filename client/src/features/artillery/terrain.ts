import { FIELD_H, FIELD_W } from '@/types/artillery';

/**
 * 지형 마스크(640×360, 1 = 흙). 서버가 정본이다 — resync 의 열 단위 런으로 만들고, 발사 대본의 구멍을 서버와 같은 정수 원으로 지운다
 * (`docs/rules-artillery.md` §6.2). 어긋나도 다음 resync 가 고친다. 이 파일의 걷기는 <b>미리보기</b>다(서버 `Walker` 와 같은 규칙,
 * 결과는 서버 값이 이긴다).
 */
export type Mask = Uint8Array;

export function maskFromRuns(runs: number[][]): Mask {
  const mask = new Uint8Array(FIELD_W * FIELD_H);
  for (let x = 0; x < Math.min(FIELD_W, runs.length); x++) {
    const r = runs[x];
    for (let i = 0; i + 1 < r.length; i += 2) {
      for (let y = Math.max(0, r[i]); y <= Math.min(FIELD_H - 1, r[i + 1]); y++) mask[y * FIELD_W + x] = 1;
    }
  }
  return mask;
}

export function solid(mask: Mask, x: number, y: number): boolean {
  if (x < 0 || x >= FIELD_W || y < 0 || y >= FIELD_H) return false;
  return mask[y * FIELD_W + x] === 1;
}

/** `(x − cx)² + (y − cy)² ≤ r²` 를 지운다(새 마스크). */
export function carve(mask: Mask, cx: number, cy: number, r: number): Mask {
  const next = new Uint8Array(mask);
  const rr = r * r;
  for (let y = Math.max(0, cy - r); y <= Math.min(FIELD_H - 1, cy + r); y++) {
    const dy = y - cy;
    for (let x = Math.max(0, cx - r); x <= Math.min(FIELD_W - 1, cx + r); x++) {
      const dx = x - cx;
      if (dx * dx + dy * dy <= rr) next[y * FIELD_W + x] = 0;
    }
  }
  return next;
}

const FOOT_HALF = 2;
const BODY_HEIGHT = 8;
const MAX_STEP = 3;
export const WALK_MIN_X = 3;
export const WALK_MAX_X = FIELD_W - 4;

export function supported(mask: Mask, x: number, footY: number): boolean {
  for (let dx = -FOOT_HALF; dx <= FOOT_HALF; dx++) if (solid(mask, x + dx, footY + 1)) return true;
  return false;
}

function clear(mask: Mask, x: number, footY: number): boolean {
  for (let y = footY - BODY_HEIGHT + 1; y <= footY; y++) if (solid(mask, x, y)) return false;
  return true;
}

export interface WalkPreview {
  x: number;
  footY: number;
  fuelLeft: number;
  /** 실제로 걸은 걸음 수(부호 = 방향). */
  dx: number;
  /** 낭떠러지에서 떨어지거나 구름에 닿는 걸음에서 멈췄다(서버는 떨어뜨린다 — 미리보기는 그 앞에서 멈춘다). */
  edge: boolean;
}

/**
 * 한 칸씩 걷는다(서버 `Walker` 와 같은 규칙 — 오르막·내리막 3칸, 벽·연료·끝에서 멈춤). 미리보기는 **떨어지는 걸음은 걷지 않는다** —
 * 손가락이 미끄러져 낭떠러지로 가는 일을 막는다(그래도 서버에 그 거리를 보내면 서버가 떨어뜨린다).
 */
export function walk(mask: Mask, x: number, footY: number, moveDx: number, fuel: number, cloudLevel: number): WalkPreview {
  const dir = Math.sign(moveDx);
  let remaining = Math.abs(moveDx);
  let steps = 0;
  while (remaining-- > 0) {
    const nx = x + dir;
    if (nx < WALK_MIN_X || nx > WALK_MAX_X) break;
    let climb = -1;
    for (let k = 0; k <= MAX_STEP; k++) {
      if (clear(mask, nx, footY - k)) {
        climb = k;
        break;
      }
    }
    if (climb < 0) break;
    const cost = 1 + climb;
    if (cost > fuel) break;
    let ny = footY - climb;
    let down = 0;
    while (down < MAX_STEP && !supported(mask, nx, ny + down) && clear(mask, nx, ny + down + 1)) down++;
    if (supported(mask, nx, ny + down)) ny += down;
    if (!supported(mask, nx, ny) || ny >= cloudLevel) {
      return { x, footY, fuelLeft: fuel, dx: steps * dir, edge: true };
    }
    fuel -= cost;
    x = nx;
    footY = ny;
    steps++;
  }
  return { x, footY, fuelLeft: fuel, dx: steps * dir, edge: false };
}
