import type { Crater, Shell, ShotReport, UnitKind } from '@/types/artillery';

/**
 * 발사 대본 재생 타임라인 (순수 함수 — 테스트가 고정). 순서는 서버가 연출 길이를 센 순서와 같다(`docs/rules-artillery.md` §9.2):
 * 이동(걸음당 2틱) → 걷다 떨어짐 → 단계마다 [비행(가장 늦게 내려앉는 씨앗까지) → 폭발 36틱 → 낙하(가장 긴 낙하만큼)]. 단계는 보통 하나,
 * 겹씨앗이면 둘이고 홀씨탄은 한 단계에 씨앗이 넷(갈라지기 전 + 셋)이다(D-224). 1틱 = 1/60초. 물리는 계산하지 않는다 — 서버가 준 점열
 * 사이를 선형 보간할 뿐이다. 물뿌리개 회복은 재생 시작에 바로 보인다(서버도 연출 시간을 쓰지 않는다).
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
  kind?: UnitKind | null;
  maxHp?: number;
}

export interface FrameShell {
  x: number;
  y: number;
  shell: Shell;
}

export interface FrameExplosion {
  x: number;
  y: number;
  r: number;
  /** 진행 0~1. */
  t: number;
  /** 둔덕(덩굴탄)이면 흙이 솟는 그림. */
  fill: boolean;
}

export interface Frame {
  units: FrameUnit[];
  /** 날고 있는 씨앗들. */
  shells: FrameShell[];
  /** 씨앗마다 지나온 자리(최근 몇 점). */
  trails: number[][];
  explosions: FrameExplosion[];
  /** 지형에 반영된 연산 수 — {@link terrainOps} 의 앞에서부터. */
  opsApplied: number;
  done: boolean;
}

export interface StageTimes {
  start: number;
  flightEnd: number;
  explosionEnd: number;
  fallEnd: number;
}

export interface Timeline {
  walkEnd: number;
  walkFallEnd: number;
  stages: StageTimes[];
  end: number;
}

/** 대본의 지형 연산을 서버가 적용한 순서대로(단계 → 씨앗). */
export function terrainOps(r: ShotReport): Crater[] {
  const ops: Crater[] = [];
  for (const st of r.stages) for (const sh of st.shots) if (sh.crater) ops.push(sh.crater);
  return ops;
}

export function timeline(r: ShotReport): Timeline {
  const steps = r.move.path.length / 2;
  const walkEnd = steps * WALK_TICKS_PER_STEP;
  const walkFallEnd = walkEnd + (r.move.fall ? r.move.fall.toY - r.move.fall.fromY : 0);
  let at = walkFallEnd;
  const stages: StageTimes[] = [];
  for (const st of r.stages) {
    const flight = st.shots.reduce((m, sh) => Math.max(m, sh.startTick + sh.ticks), 0);
    const exploded = st.shots.some((sh) => sh.crater !== null);
    const maxFall = st.falls.reduce((m, f) => Math.max(m, f.toY - f.fromY), 0);
    const start = at;
    const flightEnd = start + flight;
    const explosionEnd = flightEnd + (exploded ? EXPLOSION_TICKS : 0);
    const fallEnd = explosionEnd + maxFall;
    stages.push({ start, flightEnd, explosionEnd, fallEnd });
    at = fallEnd;
  }
  return { walkEnd, walkFallEnd, stages, end: at };
}

/** 대본을 다 보여 주는 데 걸리는 시간(ms). 서버 `durationMs` 와 같거나 짧다(서버는 하한 600ms 를 둔다). */
export function replayLengthMs(r: ShotReport): number {
  return timeline(r).end * TICK_MS;
}

function pointAt(path: number[], ft: number): { x: number; y: number; i: number } {
  const points = path.length / 2;
  const idx = ft / POINT_EVERY_TICKS;
  const i = Math.min(points - 1, Math.floor(idx));
  const j = Math.min(points - 1, i + 1);
  const f = Math.min(1, idx - i);
  return {
    x: path[i * 2] + (path[j * 2] - path[i * 2]) * f,
    y: path[i * 2 + 1] + (path[j * 2 + 1] - path[i * 2 + 1]) * f,
    i,
  };
}

/**
 * {@code before} = 발사 직전 포대, {@code tMs} = 재생 시작부터 지난 시간. 끝(또는 그 뒤)이면 대본의 {@code units}(결과값)를 그대로 준다.
 */
export function frameAt(r: ShotReport, before: FrameUnit[], tMs: number): Frame {
  const tl = timeline(r);
  const t = tMs / TICK_MS;
  const ops = terrainOps(r);
  if (t >= tl.end) {
    return {
      units: r.units.map((u) => {
        const b = before.find((x) => x.seat === u.seat);
        return { ...u, kind: b?.kind, maxHp: b?.maxHp };
      }),
      shells: [],
      trails: [],
      explosions: [],
      opsApplied: ops.length,
      done: true,
    };
  }
  const units = before.map((u) => ({ ...u }));
  const shooter = units.find((u) => u.seat === r.seat);

  // 물뿌리개 · 이동
  if (shooter) {
    if (r.heal) shooter.hp = r.heal.hpAfter;
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

  const shells: FrameShell[] = [];
  const trails: number[][] = [];
  const explosions: FrameExplosion[] = [];
  let opsApplied = 0;
  let leading = true;
  r.stages.forEach((st, si) => {
    const times = tl.stages[si];
    if (t < times.start) {
      leading = false;
      return;
    }
    const local = t - times.start;
    for (const sh of st.shots) {
      const land = sh.startTick + sh.ticks;
      if (local >= sh.startTick && local < land) {
        const p = pointAt(sh.path, local - sh.startTick);
        shells.push({ x: p.x, y: p.y, shell: r.shell });
        const from = Math.max(0, p.i - 12);
        trails.push(sh.path.slice(from * 2, (p.i + 1) * 2));
      }
      if (sh.crater) {
        const landed = local >= land;
        if (landed && leading) opsApplied++;
        else leading = false;
        if (landed && local < land + EXPLOSION_TICKS) {
          explosions.push({
            x: sh.crater.x,
            y: sh.crater.y,
            r: sh.crater.r,
            t: (local - land) / EXPLOSION_TICKS,
            fill: !!sh.crater.fill,
          });
        }
      }
    }
    // 피해 — 씨앗이 다 내려앉은 뒤 한 번에
    if (t >= times.flightEnd) {
      for (const d of st.damages) {
        const u = units.find((x) => x.seat === d.seat);
        if (u) u.hp = d.hpAfter;
      }
    }
    // 낙하
    if (t >= times.explosionEnd) {
      const ft = t - times.explosionEnd;
      for (const f of st.falls) {
        const u = units.find((x) => x.seat === f.seat);
        if (!u) continue;
        u.footY = Math.min(f.toY, f.fromY + Math.floor(ft));
        if (u.footY >= f.toY) {
          u.hp = f.hpAfter;
          if (f.intoCloud) u.alive = false;
        }
      }
    }
    // 한 단계가 끝나면 그 단계에서 탈락한 포대는 사라진다(결과값 기준)
    if (t >= times.fallEnd) {
      for (const e of r.eliminated) {
        const u = units.find((x) => x.seat === e.seat);
        if (u && u.hp === 0) u.alive = false;
      }
    }
  });
  return { units, shells, trails, explosions, opsApplied, done: false };
}
