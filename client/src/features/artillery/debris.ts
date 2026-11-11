import { FIELD_H, FIELD_W } from '@/types/artillery';
import type { Mask } from './terrain';

/**
 * 폭발 파편 경로 (순수 함수 — 스킨 밖, D-254). 착탄 하나에 클레이 덩어리 몇 개가 부채꼴로 튀어 올라 **지형에 부딪혀 튀고, 굴러 멈춘다**.
 * 경로는 착탄 때 한 번만 계산해 두고(덩어리 수·틱 수 고정 = 상한), 그릴 때는 틱 사이를 보간해 읽기만 한다 — 프레임마다 물리를 돌리거나
 * 메모리를 잡지 않는다. 난수는 사건 씨앗의 해시라 같은 대본이면 같은 그림이다.
 *
 * <p>화면 연출일 뿐이다 — 서버 판정·지형과 무관하고(읽기만 한다), 마스크가 없으면(테스트·옛 환경) 착탄 높이 아래 평평한 바닥으로 친다.
 */
export const DEBRIS_MAX = 16;
/** 한 덩어리의 경로 길이(틱) — 2초. 그 전에 멈추면 멈춘 자리가 이어진다. */
export const DEBRIS_TICKS = 120;
/** 그을음 자국을 재는 테두리 방향 수. */
export const SCORCH_DIRS = 24;

const GRAVITY = 0.1;
const RESTITUTION = 0.38;

export interface DebrisPlan {
  n: number;
  /** [덩어리][틱] → x, y (n × DEBRIS_TICKS × 2). */
  path: Float32Array;
  /** 덩어리가 멈춘 틱(끝까지 안 멈췄으면 DEBRIS_TICKS). */
  rest: Uint16Array;
  /** 처음 땅에 닿은 틱(안 닿았으면 DEBRIS_TICKS) — 먼지 한 번. */
  firstHit: Uint16Array;
  /** 덩어리 크기(칸). */
  size: Float32Array;
  /** 구멍 테두리에서 흙이 남아 있는 방향(비트 i = 각도 i/SCORCH_DIRS). */
  scorch: number;
}

function rnd(a: number, b: number): number {
  let n = (Math.imul(a | 0, 374761393) + Math.imul(b | 0, 668265263)) | 0;
  n = Math.imul(n ^ (n >>> 13), 1274126177);
  return ((n ^ (n >>> 16)) >>> 0) / 4294967296;
}

function isSolid(mask: Mask | null, x: number, y: number, floorY: number): boolean {
  const xi = Math.round(x);
  const yi = Math.round(y);
  if (!mask) return yi >= floorY;
  if (xi < 0 || xi >= FIELD_W || yi < 0 || yi >= FIELD_H) return false;
  return mask[yi * FIELD_W + xi] === 1;
}

/**
 * {@code (cx, cy, r)} = 구멍, {@code n} = 덩어리 수(≤ {@link DEBRIS_MAX}), {@code mask} = 구멍이 난 **뒤**의 지형(없으면 평평한 바닥),
 * {@code power} = 튀는 세기 배율(큰 탄일수록 멀리).
 */
export function planDebris(seed: number, cx: number, cy: number, r: number, n: number, mask: Mask | null, power = 1): DebrisPlan {
  const count = Math.max(0, Math.min(DEBRIS_MAX, Math.floor(n)));
  const path = new Float32Array(count * DEBRIS_TICKS * 2);
  const rest = new Uint16Array(count);
  const firstHit = new Uint16Array(count);
  const size = new Float32Array(count);
  const floorY = Math.round(cy + r);
  for (let i = 0; i < count; i++) {
    const ang = -Math.PI * (0.12 + 0.76 * rnd(seed, i * 7 + 1));
    const v = (0.9 + rnd(seed, i * 7 + 2) * 1.5) * power;
    let x = cx + (rnd(seed, i * 7 + 3) - 0.5) * r * 0.9;
    // 구멍 안(빈 곳)에서 출발한다 — 흙에 박힌 채 시작하지 않게 위로 올린다.
    let y = cy - r * 0.35;
    for (let k = 0; k < r + 4 && isSolid(mask, x, y, floorY); k++) y -= 1;
    let vx = Math.cos(ang) * v;
    let vy = Math.sin(ang) * v;
    let bounces = 0;
    let stopped = false;
    size[i] = 1.1 + rnd(seed, i * 7 + 4) * 1.5;
    rest[i] = DEBRIS_TICKS;
    firstHit[i] = DEBRIS_TICKS;
    for (let t = 0; t < DEBRIS_TICKS; t++) {
      path[(i * DEBRIS_TICKS + t) * 2] = x;
      path[(i * DEBRIS_TICKS + t) * 2 + 1] = y;
      if (stopped) continue;
      vy += GRAVITY;
      let nx = x + vx;
      let ny = y + vy;
      if (ny > FIELD_H + 20) {
        // 전장 아래로 빠졌다(구름 바다가 덮는다)
        stopped = true;
        rest[i] = Math.min(DEBRIS_TICKS, t + 1);
        x = nx;
        y = ny;
        continue;
      }
      if (isSolid(mask, nx, ny, floorY)) {
        if (firstHit[i] === DEBRIS_TICKS) firstHit[i] = t;
        if (isSolid(mask, x, ny, floorY)) {
          // 바닥(또는 천장)에 닿았다 — 튄다
          if (vy > 0) {
            bounces++;
            vy = -vy * RESTITUTION;
            vx *= 0.62;
            if (bounces >= 3 || Math.abs(vy) < 0.32) {
              // 굴러 멈춘다 — 남은 속도만큼 바닥을 타고 조금 더 간다
              vy = 0;
              if (Math.abs(vx) < 0.06) {
                stopped = true;
                rest[i] = Math.min(DEBRIS_TICKS, t + 1);
              }
            }
          } else vy = Math.abs(vy) * 0.3;
          ny = y;
          if (isSolid(mask, nx, ny, floorY)) {
            // 구르다 턱에 걸렸다 — 한 칸 턱은 넘고, 더 높으면 선다
            if (!isSolid(mask, nx, ny - 1, floorY)) ny -= 1;
            else {
              nx = x;
              vx = -vx * 0.3;
            }
          }
        } else {
          // 벽에 부딪혔다
          vx = -vx * 0.45;
          nx = x;
        }
      }
      x = nx;
      y = ny;
      if (!stopped && vy === 0) {
        vx *= 0.86;
        if (Math.abs(vx) < 0.06) {
          stopped = true;
          rest[i] = Math.min(DEBRIS_TICKS, t + 1);
        }
      }
    }
  }
  let scorch = 0;
  for (let i = 0; i < SCORCH_DIRS; i++) {
    const a = (i / SCORCH_DIRS) * Math.PI * 2;
    if (isSolid(mask, cx + Math.cos(a) * (r + 1.6), cy + Math.sin(a) * (r + 1.6), floorY)) scorch |= 1 << i;
  }
  return { n: count, path, rest, firstHit, size, scorch };
}

/** 덩어리 {@code i} 의 {@code ticks} 시점 자리를 {@code out} 에 쓴다(할당 없음). 경로 밖이면 끝 자리. */
export function debrisAt(plan: DebrisPlan, i: number, ticks: number, out: { x: number; y: number }): void {
  const t = Math.max(0, Math.min(DEBRIS_TICKS - 1, ticks));
  const t0 = Math.floor(t);
  const t1 = Math.min(DEBRIS_TICKS - 1, t0 + 1);
  const f = t - t0;
  const b = i * DEBRIS_TICKS;
  out.x = plan.path[(b + t0) * 2] + (plan.path[(b + t1) * 2] - plan.path[(b + t0) * 2]) * f;
  out.y = plan.path[(b + t0) * 2 + 1] + (plan.path[(b + t1) * 2 + 1] - plan.path[(b + t0) * 2 + 1]) * f;
}
