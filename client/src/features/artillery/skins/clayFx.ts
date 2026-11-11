import { FIELD_H, FIELD_W, type Shell } from '@/types/artillery';
import { TICK_MS } from '../replay';
import type { FxEvent } from '../effects';
import { DEBRIS_TICKS, SCORCH_DIRS, debrisAt, planDebris, type DebrisPlan } from '../debris';
import type { SkinCelebration, SkinFx, SkinUnit } from './types';
import { back, CONFETTI, disc, ease, ell, FONT, hash, mod, NAVY, star, TAU } from './clayKit';

/**
 * "말랑 클레이" 연출 층 (D-254) — clay 스킨 위에 얹는 손맛·폭발감. **이 상수 하나**로 켜고 끈다({@code false} = D-252 연출 그대로).
 *
 * <ul>
 *   <li>꼬리 — 탄마다 다른 모양·색(콩알 구슬, 호박 덩이, 덩굴 리본과 잎, 알밤 가시, 홀씨 솜털). 오래된 마디일수록 바람에 밀려 휜다.</li>
 *   <li>폭발 — 섬광 → 클레이 파편(지형에 튀고 굴러 멈춘다, `debris.ts`) → 흙 튐 → 연기 고리 → 그을음 잔상. 특수탄은 고유 연출.</li>
 *   <li>피격 — 깊게 찌그러졌다 출렁이며 돌아오고, 피해 숫자가 통통 튀고, 체력바에 깎인 만큼 잔상이 남는다.</li>
 *   <li>바람 — 꽃가루·민들레 홀씨가 바람 세기만큼 많이·빨리 흐른다.</li>
 *   <li>쓰러짐 — 기우뚱 → 벌렁 → 통 → 잎이 시들고, 대본에서 사라진 뒤에도 꼬리 동안 누워 있다가 퐁.</li>
 *   <li>승리 — 세 가지 모양 불꽃과 전장 가득 색종이.</li>
 * </ul>
 *
 * 전부 화면만이다: 서버 대본·재생 길이·조작 가능 시점은 그대로고, 입자는 사건 시각의 닫힌 식(파편만 착탄 때 한 번 계산)이며 개수는
 * {@link CLAY_FX_CAPS} 로 고정이다. 움직임 줄이기면 재생·꼬리 자체가 없어 여기는 불리지 않는다.
 */
export const CLAY_FX: boolean = true;

/** 재생 뒤 꼬리(ms) — 파편이 굴러 멈추고 그을음이 옅어지고 쓰러진 포대가 퐁 사라질 때까지. 조작은 막지 않는다. */
export const CLAY_FX_TAIL_MS = 3000;
/** 쓰러진 포대가 누워 있는 시간(ms, 체력이 0 이 된 순간부터). */
export const FALLEN_MS = 1500;
/** 피격 출렁임이 이어지는 시간(ms) — 대본의 피격 창(400ms)이 끝나도 스킨이 이어 그린다. */
export const HIT_WOBBLE_MS = 1100;

/** 사건 하나(또는 장면 하나)의 입자 상한. */
export const CLAY_FX_CAPS = {
  debris: 16,
  spray: 14,
  smokeRing: 10,
  special: 14,
  motes: 30,
  bursts: 5,
  burstParticles: 28,
  rain: 40,
} as const;

/** 피격 찌그러짐(+ = 납작) — 탁 눌렸다가 출렁이며 잦아든다. */
export function hitSquash(el: number): number {
  if (el < 0 || el >= HIT_WOBBLE_MS) return 0;
  if (el < 70) return 1.25 * Math.min(1, el / 40);
  const t = el - 70;
  return 1.25 * Math.cos(t / 52) * Math.exp(-t / 210);
}

/**
 * 쓰러짐 — 기우뚱(반대쪽으로 살짝) → 벌렁 넘어감 → 통 튐 → 잎이 시든다. {@code out.tilt} 0 = 서 있음, 1 = 누움(음수 = 반대쪽 기우뚱),
 * {@code out.lift} = 튀어 오른 높이(칸), {@code out.wilt} = 잎 시듦 0~1.
 */
export function fallenPose(el: number, out: { tilt: number; lift: number; wilt: number }): void {
  out.lift = 0;
  if (el < 200) out.tilt = -0.13 * Math.sin((Math.PI * el) / 200);
  else if (el < 560) {
    const k = (el - 200) / 360;
    out.tilt = k * k;
  } else if (el < 800) {
    const b = Math.sin((Math.PI * (el - 560)) / 240);
    out.tilt = 1 - 0.1 * b;
    out.lift = 1.8 * b;
  } else if (el < 940) {
    out.tilt = 1;
    out.lift = 0.5 * Math.sin((Math.PI * (el - 800)) / 140);
  } else out.tilt = 1;
  out.wilt = ease((el - 480) / 720);
}

interface TrailStyle {
  core: string;
  edge: string;
  flecks: [string, string];
  r0: number;
  r1: number;
  /** 바람에 밀리는 정도(가벼울수록 크다). */
  drift: number;
}
const TRAILS: Record<Shell, TrailStyle> = {
  PEA: { core: '#d6f58a', edge: NAVY, flecks: ['#8fdc4a', '#ffffff'], r0: 0.5, r1: 1.5, drift: 1 },
  PUMPKIN: { core: '#ffa53a', edge: '#8a3c12', flecks: ['#ff7a2a', '#fff0c0'], r0: 0.8, r1: 2.3, drift: 0.7 },
  VINE: { core: '#58c24e', edge: '#1f6b3a', flecks: ['#b6e04a', '#7ddc4a'], r0: 0.5, r1: 1.2, drift: 1 },
  CHESTNUT: { core: '#b5753a', edge: '#4f2a12', flecks: ['#e8c9a0', '#7a4320'], r0: 0.7, r1: 2.1, drift: 0.6 },
  SPORE: { core: '#ffffff', edge: '#8fbfe0', flecks: ['#ffffff', '#e8f6ff'], r0: 0.6, r1: 1.7, drift: 1.8 },
};

interface DebrisStyle {
  bits: string[];
  flash: string;
  smoke: string;
  power: number;
}
const SOIL = ['#b0683c', '#884c2c', '#74d44a', '#d28e5e'];
const DEBRIS: Record<Shell, DebrisStyle> = {
  PEA: { bits: SOIL, flash: '#ffffff', smoke: '#e6d2ba', power: 1 },
  PUMPKIN: { bits: ['#ff8a2a', '#b0683c', '#ffb85a', '#884c2c'], flash: '#fff0c8', smoke: '#f2d2a8', power: 1.15 },
  VINE: { bits: ['#5fd15a', '#2a9a4a', '#b6e04a', '#7ddc4a'], flash: '#eaffd8', smoke: '#d8f0c8', power: 0.8 },
  CHESTNUT: { bits: ['#7a4320', '#b0683c', '#e8c9a0', '#884c2c'], flash: '#fff2d8', smoke: '#d8c2a8', power: 1.3 },
  SPORE: { bits: ['#ffffff', '#b0683c', '#e8f6ff', '#884c2c'], flash: '#ffffff', smoke: '#f0f4f8', power: 0.85 },
};
const WILTED = ['#b7a34a', '#8a7a3a', '#c9b760'];

export interface ClayFxDeps {
  /** 이름표 높이(칸) — 피해 숫자가 뜨는 자리. */
  lift: number;
  /** 스킨의 포대 배율 — `SkinFx.unitScale` 에서 이 몫을 빼면 작은 화면 보정 배율이 남는다. */
  unitScale: number;
  /** 스킨의 포대 그리기 — 쓰러진 포대를 꼬리에서 이어 그린다. */
  drawUnit(ctx: CanvasRenderingContext2D, u: SkinUnit): void;
  /** D-252 연출 — 바꾸지 않은 사건(포구·풍덩·회복 …)은 그대로 그린다. */
  baseEvent(ctx: CanvasRenderingContext2D, e: FxEvent, a: number, fx: SkinFx): void;
}

export function createClayFx(deps: ClayFxDeps) {
  const W = FIELD_W;
  const H = FIELD_H;
  const tb = new Float32Array(64);
  const P = { x: 0, y: 0 };
  const plans = new WeakMap<FxEvent, DebrisPlan>();
  /** 작은 화면 보정 — 휴대폰에서 칸 하나가 0.6px 남짓이라 입자를 포대만큼 키운다(자리·경로는 그대로). */
  let z = 1;

  // ── 꼬리 ────────────────────────────────────────────────────────────
  const drawTrail = (ctx: CanvasRenderingContext2D, trail: number[], shell: Shell = 'PEA', wind = 0, time = 0) => {
    const n = Math.min(32, trail.length >> 1);
    if (n < 1) return;
    const st = TRAILS[shell] ?? TRAILS.PEA;
    // 오래된 마디일수록 바람에 밀리고(휜다) 살짝 가라앉는다
    for (let i = 0; i < n; i++) {
      const age = (n - 1 - i) * 2;
      tb[i * 2] = trail[i * 2] + 0.5 + wind * st.drift * 0.01 * age * age;
      tb[i * 2 + 1] = trail[i * 2 + 1] + 0.5 + 0.004 * age * age;
    }
    // 떨어져 나온 알갱이 — 자리마다 정해진 어긋남, 아래로 흘러내린다
    for (let i = 0; i < n; i += 2) {
      const ox = trail[i * 2];
      const oy = trail[i * 2 + 1];
      const age = (n - 1 - i) * 2;
      const k = (i + 1) / n;
      const j = hash(ox * 3, oy * 5);
      const x = tb[i * 2] + (j - 0.5) * 6;
      const y = tb[i * 2 + 1] + (hash(oy * 3, ox * 7) - 0.5) * 5 + 0.012 * age * age;
      ctx.fillStyle = st.flecks[(i >> 1) & 1];
      ctx.globalAlpha = 0.35 + 0.65 * k;
      if (shell === 'SPORE') {
        disc(ctx, x, y - age * 0.08, 0.5 + k * 0.7);
      } else if (shell === 'CHESTNUT') star(ctx, x, y, 0.6 + k * 1.1, 5, 0.45, j * 6);
      else ell(ctx, x, y, 0.5 + k * 0.9, 0.35 + k * 0.6, j * 6 + k * 3);
    }
    ctx.globalAlpha = 1;
    if (shell === 'VINE') {
      // 덩굴 리본 — 짙은 밑줄 위에 초록 줄, 마디마다 잎이 번갈아 돋는다
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
      for (let pass = 0; pass < 2; pass++) {
        ctx.strokeStyle = pass === 0 ? st.edge : st.core;
        ctx.lineWidth = pass === 0 ? 2.5 : 1.4;
        ctx.beginPath();
        ctx.moveTo(tb[0], tb[1]);
        for (let i = 1; i < n; i++) ctx.lineTo(tb[i * 2], tb[i * 2 + 1] + Math.sin(i * 1.9 + time / 90) * 0.5);
        ctx.stroke();
      }
      ctx.fillStyle = '#8fe05a';
      for (let i = 1; i < n; i += 2) {
        const side = (i >> 1) & 1 ? 1 : -1;
        const k = (i + 1) / n;
        ell(ctx, tb[i * 2], tb[i * 2 + 1] + side * 1.7, 1.9 * k + 0.5, 0.9 * k + 0.3, side * 0.9 + i);
      }
      return;
    }
    if (shell === 'SPORE') {
      // 솜털 — 가는 자루 끝에 흰 보풀
      ctx.strokeStyle = '#ffffff';
      ctx.lineWidth = 0.4;
      ctx.globalAlpha = 0.8;
      ctx.beginPath();
      for (let i = 0; i < n; i++) {
        ctx.moveTo(tb[i * 2], tb[i * 2 + 1]);
        ctx.lineTo(tb[i * 2] - wind * 0.1, tb[i * 2 + 1] + 2.2);
      }
      ctx.stroke();
      ctx.globalAlpha = 1;
    }
    // 구슬 줄 — 테 한 번, 속 한 번(탄마다 색·굵기가 다르다)
    for (let pass = 0; pass < 2; pass++) {
      ctx.fillStyle = pass === 0 ? st.edge : st.core;
      ctx.beginPath();
      for (let i = 0; i < n; i++) {
        const r = st.r0 + ((st.r1 - st.r0) * (i + 1)) / n + (pass === 0 ? 0.38 : 0);
        ctx.moveTo(tb[i * 2] + r, tb[i * 2 + 1]);
        ctx.arc(tb[i * 2], tb[i * 2 + 1], r, 0, TAU);
      }
      ctx.fill();
    }
    if (shell === 'PUMPKIN' || shell === 'CHESTNUT') {
      // 묵직한 탄 — 구슬마다 광택 점
      ctx.fillStyle = 'rgba(255,255,255,.55)';
      ctx.beginPath();
      for (let i = n >> 1; i < n; i++) {
        const r = (st.r0 + ((st.r1 - st.r0) * (i + 1)) / n) * 0.32;
        ctx.moveTo(tb[i * 2] - r, tb[i * 2 + 1] - r * 2);
        ctx.arc(tb[i * 2] - r * 2, tb[i * 2 + 1] - r * 2, r, 0, TAU);
      }
      ctx.fill();
    }
  };

  // ── 폭발 층 ─────────────────────────────────────────────────────────
  const planFor = (e: FxEvent, fx: SkinFx): DebrisPlan => {
    let p = plans.get(e);
    if (!p) {
      const st = DEBRIS[e.shell] ?? DEBRIS.PEA;
      const n = Math.max(6, Math.min(CLAY_FX_CAPS.debris, Math.round(5 + e.r * 0.5)));
      p = planDebris(e.seed, e.x, e.y, e.r, n, fx.mask ?? null, st.power);
      plans.set(e, p);
    }
    return p;
  };

  const scorch = (ctx: CanvasRenderingContext2D, e: FxEvent, a: number, plan: DebrisPlan) => {
    const fade = a < 100 ? 1 : 1 - (a - 100) / 66;
    if (fade <= 0 || plan.scorch === 0) return;
    ctx.fillStyle = '#2e1c14';
    ctx.globalAlpha = 0.4 * fade * Math.min(1, a / 5);
    ctx.beginPath();
    for (let i = 0; i < SCORCH_DIRS; i++) {
      if ((plan.scorch & (1 << i)) === 0) continue;
      const ang = (i / SCORCH_DIRS) * TAU;
      const j = hash(e.seed, i + 301);
      const rad = e.r + 0.6 + j * 1.4;
      const x = e.x + Math.cos(ang) * rad;
      const y = e.y + Math.sin(ang) * rad;
      const s = 1.1 + e.r * 0.09 + j * 1.2;
      ctx.moveTo(x + s, y);
      ctx.arc(x, y, s, 0, TAU);
    }
    ctx.fill();
    // 남은 불씨가 깜빡인다
    if (a < 70) {
      ctx.fillStyle = '#ffb040';
      for (let i = 0; i < SCORCH_DIRS; i += 4) {
        if ((plan.scorch & (1 << i)) === 0) continue;
        const ang = (i / SCORCH_DIRS) * TAU;
        ctx.globalAlpha = (1 - a / 70) * (0.5 + 0.5 * Math.sin(a * 0.7 + i));
        disc(ctx, e.x + Math.cos(ang) * (e.r + 1), e.y + Math.sin(ang) * (e.r + 1), 0.7);
      }
    }
    ctx.globalAlpha = 1;
  };

  const flash = (ctx: CanvasRenderingContext2D, e: FxEvent, a: number, color: string) => {
    if (a >= 8) return;
    const k = 1 - a / 8;
    ctx.fillStyle = color;
    ctx.globalAlpha = 0.34 * k * k;
    disc(ctx, e.x, e.y, e.r * (2.2 + a * 0.22));
    ctx.globalAlpha = 0.9 * k * k;
    disc(ctx, e.x, e.y, e.r * (1.05 + a * 0.08));
    ctx.fillStyle = '#ffffff';
    star(ctx, e.x, e.y, e.r * (1.5 + a * 0.12), 8, 0.36, e.seed);
    ctx.globalAlpha = 1;
  };

  /** 흙 튐 — 빠르게 뻗다 공기에 잡혀 떨어지는 짧은 줄. */
  const spray = (ctx: CanvasRenderingContext2D, e: FxEvent, a: number, colors: string[]) => {
    const LIFE = 30;
    if (a >= LIFE) return;
    const k = 1 - a / LIFE;
    const reach = (1 - Math.exp(-a * 0.09)) / 0.09;
    const prev = (1 - Math.exp(-Math.max(0, a - 2.2) * 0.09)) / 0.09;
    ctx.lineCap = 'round';
    ctx.lineWidth = (0.5 + 0.7 * k) * z;
    ctx.globalAlpha = Math.min(1, k * 2.4);
    for (let c = 0; c < 2; c++) {
      ctx.strokeStyle = colors[c];
      ctx.beginPath();
      for (let i = c; i < CLAY_FX_CAPS.spray; i += 2) {
        const ang = -Math.PI * (0.04 + 0.92 * hash(e.seed, i * 3 + 201));
        const v = (0.9 + hash(e.seed, i * 3 + 202) * 1.5) * (0.6 + e.r * 0.03);
        const ox = e.x + (hash(e.seed, i * 3 + 203) - 0.5) * e.r;
        const cx = Math.cos(ang) * v;
        const cy = Math.sin(ang) * v;
        ctx.moveTo(ox + cx * prev, e.y + cy * prev + 0.03 * Math.max(0, a - 2.2) * Math.max(0, a - 2.2));
        ctx.lineTo(ox + cx * reach, e.y + cy * reach + 0.03 * a * a);
      }
      ctx.stroke();
    }
    ctx.globalAlpha = 1;
  };

  /** 클레이 파편 — 계산해 둔 경로를 읽는다. 구르는 동안 돌고, 멈추면 그대로 있다가 옅어진다. */
  const debris = (ctx: CanvasRenderingContext2D, e: FxEvent, a: number, plan: DebrisPlan, colors: string[]) => {
    const fade = a < 120 ? 1 : 1 - (a - 120) / 40;
    if (fade <= 0) return;
    for (let i = 0; i < plan.n; i++) {
      const hit = plan.firstHit[i];
      if (a >= hit && a < hit + 14 && hit < DEBRIS_TICKS) {
        // 땅에 닿는 순간 먼지 한 번
        const k = (a - hit) / 14;
        debrisAt(plan, i, hit, P);
        ctx.fillStyle = '#f0e2cc';
        ctx.globalAlpha = 0.5 * (1 - k) * fade;
        ell(ctx, P.x, P.y, 1.4 + k * 3.2, 0.7 + k * 1.2);
      }
      debrisAt(plan, i, a, P);
      if (P.y > H + 8) continue;
      const s = plan.size[i] * z * 1.1 * Math.min(1, 0.4 + a / 6);
      const t = Math.min(a, plan.rest[i]);
      // 구른 만큼 돈다(멈추면 회전도 멈춘다)
      const rot = (P.x - e.x) * 0.45 + t * (hash(e.seed, i + 240) - 0.5) * 0.12;
      ctx.globalAlpha = fade;
      ctx.fillStyle = colors[i % colors.length];
      ell(ctx, P.x, P.y - s * 0.55, s, s * 0.76, rot);
      ctx.fillStyle = 'rgba(255,255,255,.42)';
      disc(ctx, P.x - s * 0.28, P.y - s * 0.8, s * 0.26);
    }
    ctx.globalAlpha = 1;
  };

  /** 연기 고리 — 납작한 도넛이 퍼지며 떠오르고 바람에 밀린다. */
  const smokeRing = (ctx: CanvasRenderingContext2D, e: FxEvent, a: number, wind: number, tone: string) => {
    const LIFE = 70;
    if (a < 3 || a >= LIFE) return;
    const k = a / LIFE;
    const R = e.r * (0.5 + 1.7 * ease(k));
    ctx.fillStyle = tone;
    ctx.globalAlpha = 0.4 * (1 - k) * Math.min(1, (a - 3) / 4);
    const cx = e.x + wind * 0.0014 * a * a;
    const cy = e.y - e.r * 0.25 - a * 0.13;
    ctx.beginPath();
    for (let i = 0; i < CLAY_FX_CAPS.smokeRing; i++) {
      const ang = (i / CLAY_FX_CAPS.smokeRing) * TAU + hash(e.seed, 260) * TAU;
      const pr = 1.5 + e.r * 0.13 + k * e.r * 0.2 + hash(e.seed, i + 261) * 1.2;
      const x = cx + Math.cos(ang) * R;
      const y = cy + Math.sin(ang) * R * 0.36;
      ctx.moveTo(x + pr, y);
      ctx.arc(x, y, pr, 0, TAU);
    }
    ctx.fill();
    // 가운데 솟는 연기 기둥
    for (let i = 0; i < 5; i++) {
      const b = a - i * 3;
      if (b < 0 || b >= 56) continue;
      const q = b / 56;
      ctx.globalAlpha = 0.36 * (1 - q) * Math.min(1, b / 5);
      disc(ctx, e.x + (hash(e.seed, i + 280) - 0.5) * e.r + wind * 0.0016 * b * b, e.y - e.r * 0.2 - b * (0.2 + i * 0.03), 2 + e.r * 0.14 + q * (3 + e.r * 0.2));
    }
    ctx.globalAlpha = 1;
  };

  /** 색 별 조각 몇 개(장난감 느낌). */
  const stars = (ctx: CanvasRenderingContext2D, e: FxEvent, a: number, n: number) => {
    const LIFE = 50;
    if (a >= LIFE) return;
    const k = 1 - a / LIFE;
    for (let i = 0; i < n; i++) {
      const vx = (hash(e.seed, i * 3 + 291) - 0.5) * 5;
      const vy = -3 * (0.4 + hash(e.seed, i * 3 + 292));
      ctx.fillStyle = CONFETTI[i % CONFETTI.length];
      star(ctx, e.x + vx * a, e.y + vy * a + 0.045 * a * a, 1.7 * z * Math.min(1, k * 3), 4, 0.42, a * 0.3 + i);
    }
  };

  // ── 특수탄 ──────────────────────────────────────────────────────────
  /** 알밤탄 — 밤송이 가시가 사방으로 쭉 뻗었다 쏙 들어가고, 묵직한 고리가 한 번 더 퍼진다. */
  const chestnut = (ctx: CanvasRenderingContext2D, e: FxEvent, a: number) => {
    if (a < 22) {
      const grow = back(a / 7) * (a > 12 ? Math.max(0, 1 - (a - 12) / 10) : 1);
      const L = e.r * (0.55 + 1.15 * grow);
      const rot = hash(e.seed, 311) * TAU;
      ctx.fillStyle = '#6b3a1a';
      ctx.beginPath();
      for (let i = 0; i < CLAY_FX_CAPS.special; i++) {
        const ang = rot + (i / CLAY_FX_CAPS.special) * TAU;
        const len = L * (0.78 + 0.22 * hash(e.seed, i + 312));
        const wx = Math.cos(ang + Math.PI / 2) * 1.3;
        const wy = Math.sin(ang + Math.PI / 2) * 1.3;
        const bx = e.x + Math.cos(ang) * e.r * 0.3;
        const by = e.y + Math.sin(ang) * e.r * 0.3;
        ctx.moveTo(bx + wx, by + wy);
        ctx.lineTo(e.x + Math.cos(ang) * len, e.y + Math.sin(ang) * len);
        ctx.lineTo(bx - wx, by - wy);
        ctx.closePath();
      }
      ctx.fill();
      ctx.fillStyle = '#f2dcae';
      for (let i = 0; i < CLAY_FX_CAPS.special; i += 2) {
        const ang = rot + (i / CLAY_FX_CAPS.special) * TAU;
        const len = L * (0.78 + 0.22 * hash(e.seed, i + 312));
        disc(ctx, e.x + Math.cos(ang) * len, e.y + Math.sin(ang) * len, 0.75 * Math.min(1, grow));
      }
    }
    if (a >= 4 && a < 34) {
      const k = (a - 4) / 30;
      ctx.strokeStyle = '#4f2a12';
      ctx.globalAlpha = 0.75 * (1 - k);
      ctx.lineWidth = 2.6 * (1 - k) + 0.4;
      ctx.beginPath();
      ctx.ellipse(e.x, e.y, e.r * (0.6 + 2 * ease(k)), e.r * (0.3 + 0.9 * ease(k)), 0, 0, TAU);
      ctx.stroke();
      ctx.globalAlpha = 1;
    }
  };

  /** 호박탄 — 주황 속살이 길게 늘어나며 철퍼덕 튀고, 호박씨가 팔랑팔랑 떨어진다. */
  const pumpkin = (ctx: CanvasRenderingContext2D, e: FxEvent, a: number) => {
    if (a < 34) {
      const k = a / 34;
      const reach = (1 - Math.exp(-a * 0.1)) / 0.1;
      ctx.globalAlpha = Math.min(1, (1 - k) * 2.2);
      for (let i = 0; i < 9; i++) {
        const ang = -Math.PI * (0.02 + 0.96 * hash(e.seed, i * 2 + 321));
        const v = (0.7 + hash(e.seed, i * 2 + 322) * 0.9) * (0.5 + e.r * 0.035);
        ctx.fillStyle = i % 3 === 0 ? '#ffcf6a' : '#ff8a2a';
        ell(ctx, e.x + Math.cos(ang) * v * reach, e.y + Math.sin(ang) * v * reach + 0.03 * a * a, (0.9 + 2.6 * (1 - k)) * z, (1.1 + 0.5 * (1 - k)) * z, ang);
      }
      ctx.globalAlpha = 1;
    }
    if (a < 64) {
      const k = a / 64;
      ctx.fillStyle = '#fff3cf';
      ctx.globalAlpha = Math.min(1, (1 - k) * 3);
      for (let i = 0; i < CLAY_FX_CAPS.special - 4; i++) {
        const vx = (hash(e.seed, i * 3 + 331) - 0.5) * 2.6;
        const vy = -(1 + hash(e.seed, i * 3 + 332) * 1.9);
        // 가벼운 씨 — 떨어질 때 좌우로 팔랑인다
        const x = e.x + vx * a * 0.6 + Math.sin(a * 0.22 + i) * 1.6;
        const y = e.y + vy * a + 0.035 * a * a;
        ell(ctx, x, y, 1.25 * z, (0.6 + 0.3 * Math.abs(Math.sin(a * 0.3 + i))) * z, a * 0.17 + i);
      }
      ctx.globalAlpha = 1;
    }
  };

  /** 홀씨탄 — 터진 자리에서 솜털 씨앗이 느릿느릿 떠올라 바람을 탄다. */
  const spore = (ctx: CanvasRenderingContext2D, e: FxEvent, a: number, wind: number, n: number) => {
    const LIFE = 120;
    if (a >= LIFE) return;
    if (a < 26) {
      const k = a / 26;
      ctx.strokeStyle = '#ffffff';
      ctx.globalAlpha = 0.85 * (1 - k);
      ctx.lineWidth = 1.6 * (1 - k) + 0.3;
      ctx.beginPath();
      ctx.arc(e.x, e.y, 2 + e.r * 1.5 * ease(k), 0, TAU);
      ctx.stroke();
    }
    ctx.strokeStyle = '#ffffff';
    ctx.fillStyle = '#ffffff';
    ctx.lineWidth = 0.4;
    for (let i = 0; i < n; i++) {
      const delay = hash(e.seed, i * 4 + 341) * 14;
      const b = a - delay;
      if (b < 0) continue;
      const k = b / (LIFE - delay);
      const out = (1 - Math.exp(-b * 0.08)) / 0.08;
      const ang = -Math.PI * hash(e.seed, i * 4 + 342);
      const x = e.x + Math.cos(ang) * out * 0.9 + Math.sin(b / 13 + i) * 1.8 + wind * 0.03 * b;
      const y = e.y + Math.sin(ang) * out * 0.7 - b * (0.1 + hash(e.seed, i * 4 + 343) * 0.12);
      ctx.globalAlpha = 0.9 * (1 - k) * Math.min(1, b / 4);
      ctx.beginPath();
      ctx.moveTo(x, y);
      ctx.lineTo(x - wind * 0.1 + Math.sin(b / 13 + i) * 0.5, y + 2.8);
      ctx.stroke();
      disc(ctx, x, y, 1.25 * z);
      ctx.globalAlpha *= 0.35;
      disc(ctx, x, y, 2.3 * z);
    }
    ctx.globalAlpha = 1;
  };

  /** 덩굴탄 — 둔덕에서 덩굴손이 도르르 말리며 자라고, 잎이 돋고, 끝에 꽃이 핀다. */
  const vine = (ctx: CanvasRenderingContext2D, e: FxEvent, a: number) => {
    const fade = a < 110 ? 1 : 1 - (a - 110) / 40;
    if (fade <= 0) return;
    const STEPS = 12;
    const g = ease(a / 34) * STEPS;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.globalAlpha = fade;
    for (let j = 0; j < 5; j++) {
      const base = -Math.PI * (0.14 + 0.72 * ((j + 0.5) / 5)) + (hash(e.seed, j + 351) - 0.5) * 0.3;
      const curl = (j & 1 ? 1 : -1) * (1.9 + hash(e.seed, j + 356) * 1.2);
      const step = (e.r * (0.95 + 0.5 * hash(e.seed, j + 361)) * (0.7 + 0.3 * z)) / STEPS;
      for (let pass = 0; pass < 2; pass++) {
        let x = e.x + (j - 2) * e.r * 0.22;
        let y = e.y - e.r * 0.45;
        ctx.strokeStyle = pass === 0 ? '#1f6b3a' : '#4fc24e';
        ctx.lineWidth = (pass === 0 ? 2.2 : 1.2) * z;
        ctx.beginPath();
        ctx.moveTo(x, y);
        for (let s = 0; s < STEPS && s < g; s++) {
          const q = (s + 1) / STEPS;
          const ang = base + curl * q * q;
          const len = step * Math.min(1, g - s);
          x += Math.cos(ang) * len;
          y += Math.sin(ang) * len;
          ctx.lineTo(x, y);
          if (pass === 1 && (s === 3 || s === 7) && g > s + 1) {
            // 잎 — 줄기 옆으로 뿅
            const pop = back((g - s - 1) / 2.5);
            ctx.stroke();
            ctx.fillStyle = s === 3 ? '#8fe05a' : '#b6e04a';
            ell(ctx, x + Math.cos(ang + 1.4) * 1.6, y + Math.sin(ang + 1.4) * 1.6, 2.1 * pop * z, 1 * pop * z, ang + 0.9);
            ctx.beginPath();
            ctx.moveTo(x, y);
          }
        }
        ctx.stroke();
        if (pass === 1 && a > 34) {
          // 끝에 핀 꽃
          const pop = back((a - 34) / 9);
          ctx.fillStyle = j & 1 ? '#ffb7cf' : '#fff2a8';
          for (let p = 0; p < 5; p++) disc(ctx, x + Math.cos((p / 5) * TAU + j) * 1.25 * pop * z, y + Math.sin((p / 5) * TAU + j) * 1.25 * pop * z, 0.85 * pop * z);
          ctx.fillStyle = '#ffd23a';
          disc(ctx, x, y, 0.7 * pop * z);
        }
      }
    }
    ctx.globalAlpha = 1;
  };

  const impact = (ctx: CanvasRenderingContext2D, e: FxEvent, a: number, fx: SkinFx) => {
    if (e.fill) {
      // 둔덕 — 예전 흙덩이·별 위에 덩굴이 자란다
      if (a < 72) deps.baseEvent(ctx, e, a, fx);
      vine(ctx, e, a);
      return;
    }
    const st = DEBRIS[e.shell] ?? DEBRIS.PEA;
    const plan = planFor(e, fx);
    scorch(ctx, e, a, plan);
    smokeRing(ctx, e, a, fx.wind, st.smoke);
    spray(ctx, e, a, st.bits);
    if (e.shell === 'CHESTNUT') chestnut(ctx, e, a);
    else if (e.shell === 'PUMPKIN') pumpkin(ctx, e, a);
    else if (e.shell === 'SPORE') spore(ctx, e, a, fx.wind, 8);
    debris(ctx, e, a, plan, st.bits);
    stars(ctx, e, a, 6);
    flash(ctx, e, a, st.flash);
  };

  // ── 피해 숫자 ───────────────────────────────────────────────────────
  let fontKey = 0;
  let fontBig = '';
  const NUMBER_MS = 1250;
  const number = (ctx: CanvasRenderingContext2D, e: FxEvent, age: number, fs: number, heal: boolean) => {
    if (age >= NUMBER_MS) return;
    const t = age / 1000;
    if (fs !== fontKey) {
      fontKey = fs;
      fontBig = `700 ${(fs * 1.45).toFixed(2)}px ${FONT}`;
    }
    const big = heal ? 1 : 1 + Math.min(0.7, e.amount / 70);
    const pop = back(t / 0.18) * big;
    const dir = hash(e.seed, 3) < 0.5 ? -1 : 1;
    // 통 — 통 — 튀어 오른다(두 번째는 낮게)
    const hop = heal ? ease(t) * 10 : 12 * Math.abs(Math.sin((Math.PI * t) / 0.55)) * Math.exp(-t * 2.1) + ease(t) * 5;
    const x = e.x + (heal ? Math.sin(t * 9 + e.seed) * 0.8 : dir * t * 7);
    const y = e.y - deps.lift * 0.9 - fs * 1.4 - hop;
    ctx.save();
    ctx.globalAlpha = t < 0.95 ? 1 : Math.max(0, 1 - (t - 0.95) / 0.3);
    ctx.translate(x, y);
    if (!heal && e.fill && t < 0.5) {
      // 직격 — 숫자 뒤에서 별이 번쩍 돈다
      ctx.fillStyle = '#ffd23a';
      ctx.globalAlpha *= 1 - t / 0.5;
      star(ctx, 0, -fs * 0.5, fs * 1.9 * back(t / 0.2), 10, 0.55, t * 4 + e.seed);
      ctx.globalAlpha = 1;
    }
    ctx.rotate((hash(e.seed, 4) - 0.5) * 0.34 + (heal ? 0 : dir * Math.sin(t * 11) * 0.08 * Math.exp(-t * 3)));
    // 땅에 닿는 박자에 살짝 눌린다
    const sqz = heal ? 0 : 0.16 * Math.max(0, Math.cos((Math.PI * 2 * t) / 0.55)) * Math.exp(-t * 3);
    ctx.scale(pop * (1 + sqz), pop * (1 - sqz));
    ctx.font = fontBig;
    ctx.textAlign = 'center';
    ctx.lineJoin = 'round';
    const text = `${heal ? '+' : '-'}${e.amount}`;
    ctx.lineWidth = fs * 0.62;
    ctx.strokeStyle = NAVY;
    ctx.strokeText(text, 0, 0);
    ctx.lineWidth = fs * 0.34;
    ctx.strokeStyle = '#ffffff';
    ctx.strokeText(text, 0, 0);
    ctx.fillStyle = heal ? '#2fb24c' : e.amount >= 45 ? '#e01e3c' : e.amount >= 20 ? '#ff4a2c' : '#ff7a2c';
    ctx.fillText(text, 0, 0);
    ctx.restore();
  };

  // ── 쓰러진 포대 ─────────────────────────────────────────────────────
  const ghost: SkinUnit = {
    x: 0,
    footY: 0,
    seat: -1,
    kind: null,
    angle: 50,
    color: NAVY,
    isTurn: false,
    pose: { pose: 'down', elapsedMs: 0 },
    scale: 1,
    time: 1,
    team: null,
    cheer: false,
  };
  const fallen = (ctx: CanvasRenderingContext2D, e: FxEvent, fx: SkinFx) => {
    const now = fx.nowMs;
    const poofAt = Math.max(e.until, e.at + FALLEN_MS);
    if (now >= e.until && now < poofAt) {
      // 대본에서는 사라졌지만 꼬리 동안 누워 시든다. 끝에 쏙 줄어든다.
      const left = poofAt - now;
      ghost.x = e.x;
      ghost.footY = e.y;
      ghost.kind = e.unitKind;
      ghost.angle = fx.angles?.[e.seat] ?? (e.x < W / 2 ? 50 : 130);
      ghost.color = fx.seatColor ? fx.seatColor(e.seat) : NAVY;
      ghost.pose.elapsedMs = now - e.at;
      ghost.scale = (fx.unitScale ?? 1) * (left < 140 ? 0.25 + 0.75 * (left / 140) : 1);
      ghost.time = fx.time && fx.time > 0 ? fx.time : 1;
      deps.drawUnit(ctx, ghost);
    }
    const a = (now - poofAt) / TICK_MS;
    if (a < 0 || a >= 80) return;
    const x = e.x + 0.5;
    const y = e.y - 3;
    if (a < 36) {
      // 퐁 — 흰 연기 고리
      const k = a / 36;
      ctx.fillStyle = '#ffffff';
      ctx.globalAlpha = 0.85 * (1 - k);
      for (let i = 0; i < 9; i++) {
        const ang = (i / 9) * TAU + e.seed;
        disc(ctx, x + Math.cos(ang) * (3 + 10 * ease(k)), y + Math.sin(ang) * (2 + 7 * ease(k)) - k * 3, 2.8 * (1 - k * 0.5));
      }
      ctx.globalAlpha = 1;
    }
    // 시든 잎이 팔랑팔랑 떨어진다
    const k = a / 80;
    ctx.globalAlpha = Math.min(1, (1 - k) * 2.5);
    for (let i = 0; i < 6; i++) {
      const vx = (hash(e.seed, i * 2 + 371) - 0.5) * 1.2;
      const up = 1.1 + hash(e.seed, i * 2 + 372) * 1.2;
      const lx = x + vx * a + Math.sin(a * 0.16 + i * 1.7) * 2.6 + fx.wind * 0.01 * a;
      const ly = y - up * a + 0.02 * a * a;
      ctx.fillStyle = WILTED[i % WILTED.length];
      ell(ctx, lx, ly, 1.9 * z, (0.6 + 0.5 * Math.abs(Math.sin(a * 0.2 + i))) * z, Math.sin(a * 0.16 + i * 1.7) * 0.9);
    }
    ctx.globalAlpha = 1;
  };

  const drawEffects = (ctx: CanvasRenderingContext2D, fx: SkinFx) => {
    ctx.lineCap = 'round';
    z = Math.max(1, Math.min(1.7, (fx.unitScale ?? deps.unitScale) / deps.unitScale));
    for (const e of fx.events) {
      const age = fx.nowMs - e.at;
      if (age < 0) continue;
      if (e.kind === 'fallen') {
        fallen(ctx, e, fx);
        continue;
      }
      if (age > CLAY_FX_TAIL_MS) continue;
      const a = age / TICK_MS;
      if (e.kind === 'impact') impact(ctx, e, a, fx);
      else if (e.kind === 'split') {
        if (a < 22) deps.baseEvent(ctx, e, a, fx);
        spore(ctx, e, a, fx.wind, CLAY_FX_CAPS.special);
      } else if (e.kind !== 'poof' && e.kind !== 'damage' && e.kind !== 'heal' && age <= 1200) deps.baseEvent(ctx, e, a, fx);
    }
    // 숫자는 입자 위에
    for (const e of fx.events) {
      const age = fx.nowMs - e.at;
      if (age < 0) continue;
      if (e.kind === 'damage') number(ctx, e, age, fx.fontSize, false);
      else if (e.kind === 'heal') number(ctx, e, age, fx.fontSize, true);
    }
  };

  // ── 바람을 타는 꽃가루·홀씨 ─────────────────────────────────────────
  let driftAt = 0;
  let drift = 0;
  const drawMotes = (ctx: CanvasRenderingContext2D, night: boolean, time: number, wind: number) => {
    // 바람이 바뀌어도 자리가 튀지 않게 흐른 거리를 쌓는다(움직임 줄이기면 시계가 0 이라 그대로 선다)
    if (time > 0) {
      const dt = time - driftAt;
      if (dt > 0 && dt < 250) drift += wind * dt * 0.012;
      driftAt = time;
    }
    const strength = Math.abs(wind);
    const n = Math.min(CLAY_FX_CAPS.motes, 10 + Math.round(strength * 2));
    const lean = Math.max(-1, Math.min(1, wind / 8));
    ctx.lineCap = 'round';
    for (let i = 0; i < n; i++) {
      const h1 = hash(i, 11);
      const h2 = hash(i, 12);
      const x = mod(h1 * 700 + drift * (0.6 + h2 * 0.8) + time * 0.004 * (hash(i, 13) - 0.5), 700) - 30;
      const y = mod(h2 * 380 + time * (0.004 + h1 * 0.005), 380) - 10 + Math.sin(time / 700 + i * 2.1) * Math.max(2, 7 - strength * 0.4);
      const kind = i % 3;
      if (kind === 0) {
        // 민들레 홀씨 — 자루가 바람 반대쪽으로 끌린다
        ctx.globalAlpha = night ? 0.6 : 0.9;
        ctx.strokeStyle = '#ffffff';
        ctx.lineWidth = 0.4;
        ctx.beginPath();
        ctx.moveTo(x, y);
        ctx.lineTo(x - lean * 2.4, y + 2.8 - Math.abs(lean) * 1.2);
        ctx.stroke();
        ctx.fillStyle = '#ffffff';
        disc(ctx, x, y, 1.15);
        ctx.globalAlpha *= 0.3;
        disc(ctx, x, y, 2.1);
      } else if (kind === 1) {
        // 꽃가루(밤에는 반딧불) — 바람이 세면 꼬리를 끈다
        const glow = night && time > 0 ? 0.5 + 0.5 * Math.sin(time / 330 + i * 2.3) : 1;
        ctx.fillStyle = night ? '#fff6a8' : '#ffe27a';
        if (night) {
          ctx.globalAlpha = 0.16 * glow;
          disc(ctx, x, y, 3.2);
        }
        ctx.globalAlpha = night ? 0.5 + 0.5 * glow : 0.9;
        if (strength >= 4) {
          ctx.strokeStyle = night ? '#fff6a8' : '#ffe27a';
          ctx.lineWidth = 0.7;
          ctx.beginPath();
          ctx.moveTo(x, y);
          ctx.lineTo(x - wind * 0.55, y + 0.4);
          ctx.stroke();
        }
        disc(ctx, x, y, 0.85);
      } else {
        // 꽃잎 — 바람이 세면 빨리 돈다
        ctx.globalAlpha = night ? 0.55 : 0.85;
        ctx.fillStyle = i % 2 ? '#ffb7cf' : '#fff2f6';
        ell(ctx, x, y, 1.9, 0.95, time / (380 - strength * 22) + i * 1.9 + h1 * 6);
      }
    }
    ctx.globalAlpha = 1;
  };

  // ── 승리 축포 ───────────────────────────────────────────────────────
  const BURST_EVERY = 380;
  const BURST_LIFE = 1750;
  const RISE = 340;
  const drawCelebration = (ctx: CanvasRenderingContext2D, c: SkinCelebration) => {
    const el = c.elapsedMs;
    const latest = Math.floor(el / BURST_EVERY);
    const wn = c.winners.length >> 1;
    const u = Math.max(1, Math.min(1.7, c.ui ?? 1));
    ctx.lineCap = 'round';
    for (let b = Math.max(0, latest - (CLAY_FX_CAPS.bursts - 1)); b <= latest; b++) {
      const age = el - b * BURST_EVERY;
      if (age >= BURST_LIFE) continue;
      const cx = 60 + hash(b, 51) * 520;
      const cy = 34 + hash(b, 52) * 120;
      const col = CONFETTI[b % CONFETTI.length];
      const col2 = CONFETTI[(b + 2) % CONFETTI.length];
      const type = Math.floor(hash(b, 57) * 3);
      if (age < RISE) {
        // 이긴 포대 쪽에서 쏘아 올린다 — 흔들리는 불씨 줄
        const k = ease(age / RISE);
        const sx = wn > 0 ? c.winners[(b % wn) * 2] + 0.5 + (hash(b, 58) - 0.5) * 30 : cx;
        const sy = wn > 0 ? c.winners[(b % wn) * 2 + 1] - 14 : cy + 150;
        ctx.fillStyle = '#ffffff';
        for (let i = 0; i < 5; i++) {
          const q = Math.max(0, k - i * 0.05);
          ctx.globalAlpha = 0.9 - i * 0.17;
          disc(ctx, sx + (cx - sx) * q + Math.sin(q * 14 + b) * 1.4, sy + (cy - sy) * q, (1.7 - i * 0.26) * u);
        }
        ctx.fillStyle = col;
        ctx.globalAlpha = 1;
        disc(ctx, sx + (cx - sx) * k + Math.sin(k * 14 + b) * 1.4, sy + (cy - sy) * k, 1.9);
        continue;
      }
      const a = (age - RISE) / TICK_MS;
      const k = (age - RISE) / (BURST_LIFE - RISE);
      const reach = ((1 - Math.exp(-a * 0.065)) / 0.065) * (0.8 + 0.2 * u);
      if (a < 9) {
        // 터지는 순간 — 흰 별이 번쩍
        const f = 1 - a / 9;
        ctx.fillStyle = '#ffffff';
        ctx.globalAlpha = f;
        star(ctx, cx, cy, 12 * (0.4 + a / 9), 8, 0.4, b);
      }
      const alpha = k < 0.6 ? 1 : Math.max(0, 1 - (k - 0.6) / 0.4);
      ctx.globalAlpha = alpha;
      const N = CLAY_FX_CAPS.burstParticles;
      if (type === 2) {
        // 버들 — 금빛 줄이 휘어 내려온다
        const prev = (1 - Math.exp(-Math.max(0, a - 5) * 0.065)) / 0.065;
        ctx.strokeStyle = b % 2 ? '#ffd23a' : '#fff2a8';
        ctx.lineWidth = 1.1 * u;
        ctx.beginPath();
        for (let i = 0; i < N; i++) {
          const ang = (i / N) * TAU + hash(b, 53) * 3;
          const v = 1.5 + hash(b, i + 60) * 0.9;
          const pa = Math.max(0, a - 5);
          ctx.moveTo(cx + Math.cos(ang) * v * prev, cy + Math.sin(ang) * v * prev + 0.018 * pa * pa);
          ctx.lineTo(cx + Math.cos(ang) * v * reach, cy + Math.sin(ang) * v * reach + 0.018 * a * a);
        }
        ctx.stroke();
      } else {
        for (let i = 0; i < N; i++) {
          const ang = (i / N) * TAU + hash(b, 53) * 3;
          // 0 = 겹꽃(안팎 두 겹), 1 = 고리(모두 같은 빠르기) + 가운데 별
          const v = type === 0 ? (i % 2 ? 2.6 : 1.5) * (0.85 + hash(b, i + 60) * 0.3) : i % 4 === 0 ? 0.9 : 2.5;
          const x = cx + Math.cos(ang) * v * reach;
          const y = cy + Math.sin(ang) * v * reach + 0.011 * a * a;
          ctx.fillStyle = i % 2 ? col : col2;
          if (i % 4 === 0) star(ctx, x, y, 3.5 * u * (1 - k * 0.5), 4, 0.4, a * 0.2 + i);
          else disc(ctx, x, y, 1.9 * u * (1 - k * 0.5));
        }
      }
      if (k > 0.45) {
        // 타닥타닥 — 사그라지며 반짝인다
        ctx.fillStyle = '#ffffff';
        const tick = Math.floor(age / 70);
        for (let i = 0; i < 8; i++) {
          const ang = hash(b * 31 + i, tick) * TAU;
          const d = hash(b * 37 + i, tick + 1) * 2.4 * reach;
          ctx.globalAlpha = alpha * 0.9;
          star(ctx, cx + Math.cos(ang) * d, cy + Math.sin(ang) * d + 0.011 * a * a, 1.6 * u, 4, 0.3, i);
        }
      }
      ctx.globalAlpha = 1;
    }
    // 전장 가득 내리는 색종이
    for (let i = 0; i < CLAY_FX_CAPS.rain; i++) {
      const period = 2600 + hash(i, 81) * 1800;
      const t = mod(el + hash(i, 82) * period, period) / period;
      if (el < t * period) continue; // 처음에는 위에서부터 내려온다
      const x = hash(i, 83) * W + Math.sin(t * 8 + i) * 6;
      const y = -10 + t * (H * 0.86);
      ctx.globalAlpha = t < 0.85 ? 0.95 : (1 - t) / 0.15;
      ctx.fillStyle = CONFETTI[i % CONFETTI.length];
      ell(ctx, x, y, 2 * u, (0.7 + 0.9 * Math.abs(Math.sin(t * 16 + i))) * u, t * 12 + i);
    }
    // 이긴 포대 위로 쏟아지는 색종이 + 둘레를 도는 별
    for (let w = 0; w < c.winners.length; w += 2) {
      const wx = c.winners[w] + 0.5;
      const wy = c.winners[w + 1];
      for (let i = 0; i < 16; i++) {
        const period = 1500 + hash(i, 71 + w) * 900;
        const t = mod(el + hash(i, 72 + w) * period, period) / period;
        const x = wx + (hash(i, 73 + w) - 0.5) * 60 + Math.sin(t * 9 + i) * 4;
        const y = wy - 62 + t * 64;
        ctx.globalAlpha = t < 0.85 ? 1 : (1 - t) / 0.15;
        ctx.fillStyle = CONFETTI[(i + w) % CONFETTI.length];
        ell(ctx, x, y, 1.7 * u, (0.8 + 0.7 * Math.abs(Math.sin(t * 14 + i))) * u, t * 11 + i);
      }
      for (let i = 0; i < 5; i++) {
        const ang = el / 420 + (i / 5) * TAU;
        const depth = Math.sin(ang);
        ctx.globalAlpha = 0.55 + 0.45 * depth;
        ctx.fillStyle = i % 2 ? '#ffffff' : '#ffd23a';
        star(ctx, wx + Math.cos(ang) * 15 * u, wy - 9 * u + depth * 3.5, (2 + depth * 0.6 + 0.5 * Math.sin(el / 90 + i)) * u, 5, 0.46, ang * 1.4);
      }
    }
    ctx.globalAlpha = 1;
  };

  return { drawTrail, drawEffects, drawMotes, drawCelebration };
}
