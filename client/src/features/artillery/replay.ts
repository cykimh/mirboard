import type { ShotReport } from '@/types/artillery';

/**
 * 발사 대본 재생 타임라인 (순수 함수 — 테스트가 고정). 순서는 서버가 연출 길이를 센 순서와 같다(`docs/rules-artillery.md` §9.2):
 * 이동(걸음당 2틱) → 걷다 떨어짐 → 비행(틱) → 폭발 36틱 → 낙하(가장 긴 낙하만큼). 1틱 = 1/60초. 물리는 계산하지 않는다 — 서버가 준
 * 점열 사이를 선형 보간할 뿐이다.
 */
export const TICK_MS = 1000 / 60;
export const WALK_TICKS_PER_STEP = 2;
export const EXPLOSION_TICKS = 36;
export const POINT_EVERY_TICKS = 2;

export interface FrameUnit {
  seat: number;
  x: number;
  footY: number;
  hp: number;
  alive: boolean;
}

export interface Frame {
  units: FrameUnit[];
  /** 날아가는 씨앗(없으면 null). */
  shell: { x: number; y: number } | null;
  /** 지나온 자리(최근 몇 점). */
  trail: number[];
  /** 폭발 진행 0~1(없으면 null). */
  explosion: { x: number; y: number; r: number; t: number } | null;
  /** 구멍을 지형에 반영할 때가 지났다. */
  craterApplied: boolean;
  done: boolean;
}

export interface Timeline {
  walkEnd: number;
  walkFallEnd: number;
  flightEnd: number;
  explosionEnd: number;
  fallEnd: number;
}

export function timeline(r: ShotReport): Timeline {
  const steps = r.move.path.length / 2;
  const walkEnd = steps * WALK_TICKS_PER_STEP;
  const walkFallEnd = walkEnd + (r.move.fall ? r.move.fall.toY - r.move.fall.fromY : 0);
  const flightEnd = walkFallEnd + (r.shot ? r.shot.ticks : 0);
  const explosionEnd = flightEnd + (r.shot?.crater ? EXPLOSION_TICKS : 0);
  const maxFall = r.falls.reduce((m, f) => Math.max(m, f.toY - f.fromY), 0);
  return { walkEnd, walkFallEnd, flightEnd, explosionEnd, fallEnd: explosionEnd + maxFall };
}

/** 대본을 다 보여 주는 데 걸리는 시간(ms). 서버 `durationMs` 와 같거나 짧다(서버는 하한 600ms 를 둔다). */
export function replayLengthMs(r: ShotReport): number {
  return timeline(r).fallEnd * TICK_MS;
}

/**
 * {@code before} = 발사 직전 포대, {@code tMs} = 재생 시작부터 지난 시간. 끝(또는 그 뒤)이면 대본의 {@code units}(결과값)를 그대로 준다.
 */
export function frameAt(r: ShotReport, before: FrameUnit[], tMs: number): Frame {
  const tl = timeline(r);
  const t = tMs / TICK_MS;
  if (t >= tl.fallEnd) {
    return {
      units: r.units.map((u) => ({ ...u })),
      shell: null,
      trail: [],
      explosion: null,
      craterApplied: true,
      done: true,
    };
  }
  const units = before.map((u) => ({ ...u }));
  const shooter = units.find((u) => u.seat === r.seat);

  // 이동
  if (shooter) {
    const steps = r.move.path.length / 2;
    const stepsDone = Math.min(steps, Math.floor(t / WALK_TICKS_PER_STEP));
    if (stepsDone > 0) {
      shooter.x = r.move.path[(stepsDone - 1) * 2];
      shooter.footY = r.move.path[(stepsDone - 1) * 2 + 1];
    }
    const fall = r.move.fall;
    if (fall && t >= tl.walkEnd) {
      shooter.footY = Math.min(fall.toY, fall.fromY + Math.floor(t - tl.walkEnd));
      if (t >= tl.walkFallEnd) {
        shooter.hp = fall.hpAfter;
        if (fall.intoCloud) shooter.alive = false;
      }
    }
  }

  // 비행
  let shell: Frame['shell'] = null;
  let trail: number[] = [];
  if (r.shot && t >= tl.walkFallEnd && t < tl.flightEnd) {
    const ft = t - tl.walkFallEnd;
    const p = r.shot.path;
    const points = p.length / 2;
    const idx = ft / POINT_EVERY_TICKS;
    const i = Math.min(points - 1, Math.floor(idx));
    const j = Math.min(points - 1, i + 1);
    const f = Math.min(1, idx - i);
    shell = { x: p[i * 2] + (p[j * 2] - p[i * 2]) * f, y: p[i * 2 + 1] + (p[j * 2 + 1] - p[i * 2 + 1]) * f };
    const from = Math.max(0, i - 12);
    trail = p.slice(from * 2, (i + 1) * 2);
  }

  // 폭발 + 피해
  const crater = r.shot?.crater ?? null;
  let explosion: Frame['explosion'] = null;
  const exploded = crater !== null && t >= tl.flightEnd;
  if (crater && exploded && t < tl.explosionEnd) {
    explosion = { x: crater.x, y: crater.y, r: crater.r, t: (t - tl.flightEnd) / EXPLOSION_TICKS };
  }
  if (exploded) {
    for (const d of r.damages) {
      const u = units.find((x) => x.seat === d.seat);
      if (u) u.hp = d.hpAfter;
    }
  }
  // 낙하
  if (t >= tl.explosionEnd) {
    const ft = t - tl.explosionEnd;
    for (const f of r.falls) {
      const u = units.find((x) => x.seat === f.seat);
      if (!u) continue;
      u.footY = Math.min(f.toY, f.fromY + Math.floor(ft));
      if (u.footY >= f.toY) {
        u.hp = f.hpAfter;
        if (f.intoCloud) u.alive = false;
      }
    }
  }
  return { units, shell, trail, explosion, craterApplied: exploded, done: false };
}
