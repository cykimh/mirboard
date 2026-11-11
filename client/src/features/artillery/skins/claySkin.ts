import { FIELD_H, FIELD_W, type Shell, type UnitKind } from '@/types/artillery';
import type { Mask } from '../terrain';
import { TICK_MS, type FrameExplosion } from '../replay';
import type { FxEvent } from '../effects';
import { barrelAnim, explosionAnim, progressFrameIndex, seedAnim, unitAnim, type SpriteAnim, type SpriteAtlas } from './atlas';
import type { SpriteSource } from './spriteSkin';
import type { ArtillerySkin, Palette, SkinCelebration, SkinFx, SkinLabel, SkinUnit } from './types';
import { paintSoil, VECTOR_SOIL } from './vectorSkin';
import { back, clamp01, CONFETTI, disc, ease, ell, FONT, hash, mod, NAVY, rr, star, TAU } from './clayKit';
import { CLAY_FX, CLAY_FX_TAIL_MS, createClayFx, fallenPose, HIT_WOBBLE_MS, hitSquash } from './clayFx';

/**
 * "말랑 클레이 장난감" 스킨 (D-252, 시안 B). 화분 몸(표정)·잎·포신·씨앗·폭발·둔덕은 시트(`skins/clay/atlas.png`)에서 가져오고,
 * 눌림·튕김·넘어짐 같은 **움직임**과 하늘·구름 바다·흙 질감·궤적·이름표·입자·바람개비는 여기서 그린다.
 *
 * <p>성능(휴대폰 60fps) — 프레임마다 객체·배열을 만들지 않는다. 하늘·흙 무늬·구름·좌석 색 실루엣은 처음 한 번 캔버스에 그려 두고
 * 붙인다. 입자는 상태가 없다: 사건 시각부터 지난 시간의 닫힌 식으로 자리·크기를 구하고(사건마다 개수 고정 = 상한), 난수는
 * 사건 씨앗의 해시다. 그림은 맞는 범위·물리와 무관하다(서버 대본 그대로).
 */
export const CLAY_UNIT_SCALE = 1.25;
const CLAY_PALETTE: Palette = { skyTop: '#4fbdf0', skyBottom: '#c4ecff', cloud: '#ffffff', cloudEdge: '#9fd0ea', label: '#22304a' };
const TEAM_COLORS = ['#e0803a', '#3a86d6'];
const HP_COLORS = ['#4fd45a', '#ffc23a', '#ff5a4c'];
const SOIL_BITS = ['#b0683c', '#884c2c', '#74d44a', '#d28e5e'];
const MOUND_BITS = ['#5fd15a', '#2a9a4a', '#b6e04a', '#7ddc4a'];
const W = FIELD_W;
const H = FIELD_H;

type CanvasFactory = (w: number, h: number) => HTMLCanvasElement | null;
const defaultCanvas: CanvasFactory = (w, h) => {
  if (typeof document === 'undefined') return null;
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  return c;
};

/** 상태 → 움직임 값(고쳐 쓰는 객체 하나). */
const M = { bob: 0, sway: 0, back: 0, sq: 0, flash: 0, jx: 0, wilt: 0, blink: false, lift: 0, leaf: 0 };
const FALL = { tilt: 0, lift: 0, wilt: 0 };
function motion(pose: string, el: number, still: boolean) {
  M.bob = 0;
  M.sway = 0;
  M.back = 0;
  M.sq = 0;
  M.flash = 0;
  M.jx = 0;
  M.wilt = 0;
  M.blink = false;
  M.lift = 0;
  M.leaf = 0;
  if (pose === 'idle') {
    if (!still) {
      M.bob = Math.sin((el / 1040) * TAU);
      M.sway = Math.sin(el / 900);
      M.blink = el % 3200 < 140;
    }
  } else if (pose === 'aim') {
    if (!still) {
      M.bob = Math.sin((el / 520) * TAU) * 0.5;
      M.sway = Math.sin(el / 420) * 0.6;
    }
  } else if (pose === 'recoil') {
    const k = 1 - Math.min(1, el / 200);
    M.back = k * k;
    M.sq = k;
  } else if (pose === 'hit') {
    const k = 1 - Math.min(1, el / 400);
    M.flash = el < 110 ? 1 : el < 240 && (Math.floor(el / 55) & 1) === 1 ? 0.6 : 0;
    if (CLAY_FX) {
      // D-254 — 깊게 찌그러졌다 출렁이며 돌아온다(창이 끝난 뒤에도 `drawUnit` 이 이어 그린다)
      M.jx = Math.sin(el / 22) * 2 * Math.exp(-el / 160);
      M.sq = hitSquash(el);
    } else {
      M.jx = Math.sin(el / 22) * 1.6 * k;
      // 납작하게 눌렸다가 길쭉하게 튕겨 오른다
      M.sq = el < 90 ? 0.9 * (1 - el / 90) - 0.2 : -k * Math.cos(el / 60) * 0.9;
    }
  } else if (CLAY_FX) {
    // D-254 — 기우뚱 → 벌렁 → 통 → 잎이 시든다
    fallenPose(still ? 1e6 : el, FALL);
    M.wilt = FALL.tilt;
    M.lift = FALL.lift;
    M.leaf = FALL.wilt;
  } else {
    const k = still ? 1 : Math.min(1, el / 380);
    M.wilt = 1 - (1 - k) * (1 - k);
    M.leaf = M.wilt;
    M.lift = still ? 0 : Math.max(0, Math.sin(clamp01((el - 380) / 220) * Math.PI)) * 1.6;
  }
}

export function createClaySkin(id: string, atlas: SpriteAtlas, src: SpriteSource, makeCanvas: CanvasFactory = defaultCanvas): ArtillerySkin {
  const ppc = atlas.pxPerCell;
  const K = 1 / ppc;
  const lift = atlas.labelLift;
  /** D-254 연출 층 — 아래에서 포대·사건 그리기를 넘겨 만든다(`CLAY_FX` 가 꺼져 있으면 null = D-252 그대로). */
  let fxLayer: ReturnType<typeof createClayFx> | null = null;
  const opt = (key: string): SpriteAnim | null => atlas.anims.get(key) ?? null;
  const flashAnim = opt('flash');
  /** 큰 화면이면 흙·하늘을 2배로 그려 둔다(휴대폰은 전장 폭이 640 장치 픽셀 남짓이라 1배면 충분). */
  const S =
    typeof window !== 'undefined' && Math.min(2, window.devicePixelRatio || 1) * Math.min(960, window.innerWidth || 0) > 1000 ? 2 : 1;

  // ── 좌석 색 실루엣 — 시트에서 실루엣이 모인 띠만 색마다 한 번 칠해 둔다 ──────────────
  let bx = Infinity;
  let by = Infinity;
  let bx2 = 0;
  let by2 = 0;
  for (const a of atlas.anims.values()) {
    if (!a.tint) continue;
    for (const t of a.tint) {
      bx = Math.min(bx, t.x);
      by = Math.min(by, t.y);
      bx2 = Math.max(bx2, t.x + t.w);
      by2 = Math.max(by2, t.y + t.h);
    }
  }
  const tinted = new Map<string, CanvasImageSource | null>();
  const tintBand = (color: string): CanvasImageSource | null => {
    const hit = tinted.get(color);
    if (hit !== undefined) return hit;
    let made: CanvasImageSource | null = null;
    try {
      const c = bx2 > bx ? makeCanvas(bx2 - bx, by2 - by) : null;
      const g = c?.getContext('2d');
      if (c && g) {
        g.drawImage(src.image, bx, by, bx2 - bx, by2 - by, 0, 0, bx2 - bx, by2 - by);
        g.globalCompositeOperation = 'source-in';
        g.fillStyle = color;
        g.fillRect(0, 0, c.width, c.height);
        made = c;
      }
    } catch {
      made = null;
    }
    tinted.set(color, made);
    return made;
  };

  /** 지금 원점에 기준점을 맞춰 프레임 하나(+ 좌석 색 실루엣)를 그린다. */
  const blit = (ctx: CanvasRenderingContext2D, anim: SpriteAnim, i: number, color: string | null, k = K) => {
    const ax = -anim.anchor[0] * k;
    const ay = -anim.anchor[1] * k;
    if (anim.tint && color) {
      const t = anim.tint[i];
      const band = tintBand(color);
      if (band) ctx.drawImage(band, t.x - bx, t.y - by, t.w, t.h, ax, ay, t.w * k, t.h * k);
      else ctx.drawImage(src.image, t.x, t.y, t.w, t.h, ax, ay, t.w * k, t.h * k);
    }
    const f = anim.frames[i];
    ctx.drawImage(src.image, f.x, f.y, f.w, f.h, ax, ay, f.w * k, f.h * k);
  };

  // ── 포대 ────────────────────────────────────────────────────────────
  // 걷기·낙하 알아채기 — 좌석마다 지난 자리와 시각(할당 없음).
  const lastX = new Float64Array(8).fill(NaN);
  const lastY = new Float64Array(8).fill(NaN);
  const walkAt = new Float64Array(8).fill(-1e9);
  const walkDir = new Float64Array(8);
  const fallAt = new Float64Array(8).fill(-1e9);
  const landAt = new Float64Array(8).fill(-1e9);
  /** 마지막으로 맞은 시각(화면 시계) — 피격 창이 끝난 뒤 출렁임을 잇는다(D-254). */
  const hitAt = new Float64Array(8).fill(-1e9);

  const drawUnit = (ctx: CanvasRenderingContext2D, s: SkinUnit) => {
    const pose = s.pose.pose;
    const el = s.pose.elapsedMs;
    const still = s.time <= 0;
    motion(pose, el, still);
    let hop = 0;
    let tilt = 0;
    let sq = M.sq;
    if (s.seat >= 0 && !still) {
      const i = s.seat & 7;
      if (lastX[i] !== s.x) {
        if (Math.abs(s.x - lastX[i]) <= 4) {
          walkAt[i] = s.time;
          walkDir[i] = s.x > lastX[i] ? 1 : -1;
        }
        lastX[i] = s.x;
      }
      if (lastY[i] !== s.footY) {
        if (s.footY > lastY[i] && s.time - walkAt[i] > 80) fallAt[i] = s.time;
        lastY[i] = s.footY;
      } else if (s.time - fallAt[i] < 60 && s.time - fallAt[i] > 20) {
        landAt[i] = s.time;
        fallAt[i] = -1e9;
      }
      if (CLAY_FX) {
        if (pose === 'hit') hitAt[i] = s.time - el;
        else if (pose !== 'down' && s.time - hitAt[i] < HIT_WOBBLE_MS) sq += hitSquash(s.time - hitAt[i]);
      }
      if (pose === 'idle' || pose === 'aim') {
        if (s.time - walkAt[i] < 150) {
          // 뒤뚱뒤뚱 — 걷는 쪽으로 기울고 통통 튄다
          hop = Math.abs(Math.sin(s.time / 52)) * 1.1;
          sq += Math.sin(s.time / 26) * 0.22;
          tilt = walkDir[i] * 0.1;
        } else if (s.time - fallAt[i] < 60) {
          sq -= 0.5;
        } else if (s.time - landAt[i] < 220) {
          const k = 1 - (s.time - landAt[i]) / 220;
          sq += Math.cos((1 - k) * 9) * 0.7 * k;
        }
      }
    }
    if (s.cheer && !still && pose !== 'down') {
      const ph = s.time / 170 + s.seat * 1.3;
      hop = Math.abs(Math.sin(ph)) * 4.2;
      sq += Math.cos(ph * 2) * 0.28;
    }
    const kind = s.kind ?? 'SPROUT';
    const left = s.angle > 90;
    const breathe = pose === 'idle' ? M.bob * 0.03 : pose === 'aim' ? M.bob * 0.02 : 0;
    ctx.save();
    ctx.translate(s.x + 0.5 + M.jx, s.footY + 1);
    ctx.scale(s.scale, s.scale);
    // 팀 아우라(2:2) — 발밑에서 팀 색 고리가 숨 쉰다
    if (s.team !== null && s.team !== undefined && pose !== 'down') {
      const pulse = still ? 0 : Math.sin(s.time / 420 + s.seat);
      ctx.fillStyle = TEAM_COLORS[s.team & 1];
      ctx.globalAlpha = 0.2 + 0.06 * pulse;
      ell(ctx, 0, 0.3, 10.5 + pulse * 0.5, 2.7 + pulse * 0.12);
      ctx.globalAlpha = 0.85;
      ctx.strokeStyle = TEAM_COLORS[s.team & 1];
      ctx.lineWidth = 0.7;
      ctx.beginPath();
      ctx.ellipse(0, 0.3, 9 + pulse * 0.4, 2.2 + pulse * 0.1, 0, 0, TAU);
      ctx.stroke();
      ctx.globalAlpha = 1;
    }
    // 차례 주인 — 발밑 빛 고리가 퍼져 나간다
    if (s.isTurn && !still && pose !== 'down') {
      const k = (s.time % 1300) / 1300;
      ctx.globalAlpha = (1 - k) * 0.7;
      ctx.strokeStyle = '#ffffff';
      ctx.lineWidth = 0.8;
      ctx.beginPath();
      ctx.ellipse(0, 0.3, 6 + k * 7, 1.4 + k * 1.5, 0, 0, TAU);
      ctx.stroke();
      ctx.globalAlpha = 1;
    }
    // 그림자(뛰면 작아진다)
    ctx.fillStyle = 'rgba(0,0,0,.2)';
    ell(ctx, 0, 0.4, 6.4 - hop * 0.5, 1.3 - hop * 0.1);
    if (pose !== 'down') {
      const barrel = barrelAnim(atlas, kind);
      ctx.save();
      ctx.translate(0, -5.5 - hop + breathe * 6);
      ctx.rotate((-s.angle * Math.PI) / 180);
      if (left) ctx.scale(1, -1);
      ctx.translate(-M.back * atlas.recoilCells, 0);
      ctx.scale(1 - M.back * 0.24, 1 + M.back * 0.32);
      blit(ctx, barrel, 0, s.color);
      ctx.restore();
    }
    ctx.save();
    if (left) ctx.scale(-1, 1);
    const w = M.wilt;
    // 쓰러짐 — 옆으로 넘어가 한 번 통 튄다
    const bounce = pose === 'down' ? M.lift : 0;
    ctx.translate(4.5 * w, -bounce - hop);
    ctx.rotate(w * 1.45 + tilt * (left ? -1 : 1));
    ctx.translate(-4.5 * w, 0);
    ctx.scale(1 + sq * 0.22 - breathe, 1 - sq * 0.22 + breathe);
    const leaves = opt(`leaves.${kind}`) ?? opt('leaves.SPROUT');
    if (leaves) {
      ctx.save();
      ctx.translate(0, -7.6);
      ctx.rotate(M.sway * 0.07);
      // 잎은 귀처럼 까딱 — 시들면 아래로 접힌다
      const fold = 1 - (CLAY_FX && pose === 'down' ? 0.45 * Math.max(0, w) + 1.3 * M.leaf : 1.75 * w) + (pose === 'hit' ? -0.5 * (1 - Math.min(1, el / 400)) : 0) + (pose === 'recoil' ? M.back * 0.3 : 0);
      ctx.scale(1, fold);
      blit(ctx, leaves, (CLAY_FX ? M.leaf > 0.3 : w > 0.55) && leaves.frames.length > 1 ? 1 : 0, null);
      ctx.restore();
    }
    if (pose === 'down') {
      const droop = opt(`droop.${kind}`) ?? opt('droop.SPROUT');
      if (droop) blit(ctx, droop, 0, s.color);
    }
    const body = unitAnim(atlas, kind, pose);
    blit(ctx, body, M.blink && body.frames.length > 1 ? 1 : 0, s.color);
    if (M.flash > 0 && flashAnim) {
      ctx.globalAlpha = M.flash * 0.85;
      blit(ctx, flashAnim, 0, null);
      ctx.globalAlpha = 1;
    }
    ctx.restore();
    if (pose === 'hit' && !still && el < 170) {
      // 맞은 순간 — 사방으로 튀는 흰 선
      const k = el / 170;
      ctx.strokeStyle = '#ffffff';
      ctx.lineCap = 'round';
      ctx.lineWidth = 1 - k * 0.6;
      ctx.beginPath();
      for (let i = 0; i < 7; i++) {
        const a = -Math.PI * (0.05 + (i / 6) * 0.9);
        const r0 = 8 + k * 5;
        const r1 = r0 + 3.5 * (1 - k);
        ctx.moveTo(Math.cos(a) * r0, -4.5 + Math.sin(a) * r0);
        ctx.lineTo(Math.cos(a) * r1, -4.5 + Math.sin(a) * r1);
      }
      ctx.stroke();
    }
    if (CLAY_FX && pose === 'down' && !still && el >= 560 && el < 900) {
      // 벌렁 넘어진 자리에서 먼지가 폴싹
      const k = (el - 560) / 340;
      const side = left ? -1 : 1;
      ctx.fillStyle = '#f0e2cc';
      ctx.globalAlpha = 0.6 * (1 - k);
      for (let i = 0; i < 5; i++) {
        const d = (i - 1) * 3.2 + side * 5;
        disc(ctx, d + Math.sign(d || 1) * k * 4, 0.2 - k * (1.5 + (i % 2) * 1.6), 1.5 + k * 2.2);
      }
      ctx.globalAlpha = 1;
    }
    if (pose === 'down' && !still && el > (CLAY_FX ? 620 : 160)) {
      // 머리 위를 도는 별 셋
      const a0 = el / 190;
      for (let i = 0; i < 3; i++) {
        const a = a0 + (i * TAU) / 3;
        const depth = Math.sin(a);
        ctx.fillStyle = i === 1 ? '#ffffff' : '#ffd23a';
        ctx.globalAlpha = 0.65 + 0.35 * depth;
        star(ctx, 3 + Math.cos(a) * 6, -11.5 + depth * 1.6, 1.25 + depth * 0.3, 5, 0.48, a * 1.6);
      }
      ctx.globalAlpha = 1;
    }
    ctx.restore();
  };

  // ── 하늘 ────────────────────────────────────────────────────────────
  let skyKey = '';
  let skyCache: HTMLCanvasElement | null = null;
  let puffKey = '';
  let puffCache: HTMLCanvasElement | null = null;
  const PUFF_W = 64;
  const PUFF_H = 34;

  const paintSkyBase = (g: CanvasRenderingContext2D, p: Palette) => {
    const night = p.night === true;
    const grad = g.createLinearGradient(0, 0, 0, H);
    grad.addColorStop(0, p.skyTop);
    grad.addColorStop(1, p.skyBottom);
    g.fillStyle = grad;
    g.fillRect(0, 0, W, H);
    // 해·달
    g.fillStyle = night ? '#8f86d8' : '#ffe88a';
    g.globalAlpha = 0.3;
    disc(g, 84, 62, 46);
    g.globalAlpha = 0.5;
    disc(g, 84, 62, 34);
    g.globalAlpha = 1;
    g.fillStyle = night ? '#fff3b8' : '#ffd84a';
    disc(g, 84, 62, 23);
    g.save();
    g.beginPath();
    g.arc(84, 62, 23, 0, TAU);
    g.clip();
    g.fillStyle = night ? 'rgba(120,100,60,.18)' : 'rgba(255,150,40,.22)';
    disc(g, 92, 74, 23);
    if (night) {
      g.fillStyle = 'rgba(150,130,90,.3)';
      disc(g, 92, 56, 4.2);
      disc(g, 78, 72, 3);
      disc(g, 88, 70, 1.8);
    }
    g.restore();
    g.fillStyle = 'rgba(255,255,255,.6)';
    ell(g, 76, 52, 6.5, 3.6, -0.6);
    // 먼 섬 — 하늘빛에 잠긴 점토 덩어리
    const far = night ? 'rgba(170,160,235,' : 'rgba(255,255,255,';
    const island = (cx: number, cy: number, hw: number, a: number) => {
      g.fillStyle = `${far}${a})`;
      g.beginPath();
      g.moveTo(cx - hw, cy);
      g.quadraticCurveTo(cx - hw * 0.5, cy + hw * 0.75, cx, cy + hw * 0.8);
      g.quadraticCurveTo(cx + hw * 0.6, cy + hw * 0.7, cx + hw, cy);
      g.closePath();
      g.fill();
      rr(g, cx - hw - 2, cy - 3.5, hw * 2 + 4, 6, 3);
      g.fill();
      disc(g, cx - hw * 0.3, cy - 5, 4.5);
      disc(g, cx + hw * 0.35, cy - 4, 3.2);
    };
    island(250, 118, 34, 0.2);
    island(452, 92, 24, 0.16);
    island(585, 150, 40, 0.24);
    island(30, 172, 30, 0.2);
  };

  const skyBase = (p: Palette): HTMLCanvasElement | null => {
    const key = `${p.skyTop}|${p.skyBottom}|${p.night ? 1 : 0}`;
    if (key === skyKey) return skyCache;
    skyKey = key;
    skyCache = null;
    try {
      const c = makeCanvas(W * S, H * S);
      const g = c?.getContext('2d');
      if (c && g) {
        g.scale(S, S);
        paintSkyBase(g, p);
        skyCache = c;
      }
    } catch {
      skyCache = null;
    }
    return skyCache;
  };

  const paintPuff = (g: CanvasRenderingContext2D, dy: number) => {
    disc(g, 30, 14 + dy, 10);
    disc(g, 43, 17 + dy, 8);
    disc(g, 18, 18 + dy, 7);
    rr(g, 11, 17 + dy, 40, 8, 4);
    g.fill();
  };
  const puff = (p: Palette): HTMLCanvasElement | null => {
    const key = `${p.cloud}|${p.cloudEdge}`;
    if (key === puffKey) return puffCache;
    puffKey = key;
    puffCache = null;
    try {
      const c = makeCanvas(PUFF_W * S * 2, PUFF_H * S * 2);
      const g = c?.getContext('2d');
      if (c && g) {
        g.scale(S * 2, S * 2);
        g.fillStyle = p.cloudEdge;
        paintPuff(g, 2.6);
        g.fillStyle = p.cloud;
        paintPuff(g, 0);
        g.fillStyle = 'rgba(255,255,255,.55)';
        ell(g, 26, 9, 5, 2, -0.3);
        puffCache = c;
      }
    } catch {
      puffCache = null;
    }
    return puffCache;
  };

  const drawSky = (ctx: CanvasRenderingContext2D, p: Palette, time = 0, wind = 0) => {
    const base = skyBase(p);
    if (base) {
      ctx.imageSmoothingEnabled = true;
      ctx.drawImage(base, 0, 0, W, H);
    } else paintSkyBase(ctx, p);
    const night = p.night === true;
    if (night) {
      ctx.fillStyle = '#ffffff';
      for (let i = 0; i < 22; i++) {
        ctx.globalAlpha = time > 0 ? 0.45 + 0.5 * Math.sin(time / 520 + i * 1.7) : 0.8;
        star(ctx, hash(i, 1) * W, hash(i, 2) * 190, 1 + hash(i, 3) * 1.4, 4, 0.4, 0);
      }
      ctx.globalAlpha = 1;
    }
    // 떠가는 구름 — 바람이 세면 빨리
    const pc = puff(p);
    if (pc) {
      for (let i = 0; i < 4; i++) {
        const sc = 0.8 + (i % 2) * 0.45;
        const x = mod(i * 190 + 120 + time * 0.006 * (0.6 + wind * 0.3) * (0.7 + i * 0.15), 820) - 100;
        ctx.drawImage(pc, x, 30 + ((i * 53) % 90), PUFF_W * sc, PUFF_H * sc);
      }
    }
    // 바람 줄
    if (wind !== 0) {
      ctx.strokeStyle = '#ffffff';
      ctx.lineWidth = 1.3;
      ctx.lineCap = 'round';
      ctx.globalAlpha = 0.6;
      ctx.beginPath();
      for (let i = 0; i < 6; i++) {
        const x = mod(i * 131 + time * 0.03 * wind, 700) - 30;
        const y = 110 + ((i * 37) % 120);
        ctx.moveTo(x - 9, y);
        ctx.quadraticCurveTo(x - 4, y - 4, x, y);
        ctx.quadraticCurveTo(x + 4, y + 4, x + 9, y);
      }
      ctx.stroke();
      ctx.globalAlpha = 1;
    }
    // 흩날리는 꽃잎(밤에는 반딧불) — 바람을 탄다. D-254 — 바람 세기만큼 많이·빨리 흐른다.
    if (fxLayer) fxLayer.drawMotes(ctx, night, time, wind);
    else {
      for (let i = 0; i < 14; i++) {
        const h1 = hash(i, 11);
        const h2 = hash(i, 12);
        const x = mod(h1 * 700 + time * (0.006 + h2 * 0.008 + wind * 0.0045), 700) - 30;
        const y = mod(h2 * 380 + time * (0.005 + h1 * 0.006), 380) - 10 + Math.sin(time / 700 + i * 2.1) * 7;
        if (night) {
          const glow = time > 0 ? 0.5 + 0.5 * Math.sin(time / 330 + i * 2.3) : 0.8;
          ctx.fillStyle = '#fff6a8';
          ctx.globalAlpha = 0.16 * glow;
          disc(ctx, x, y, 3.2);
          ctx.globalAlpha = 0.5 + 0.5 * glow;
          disc(ctx, x, y, 0.9);
        } else if (i % 3 === 0) {
          // 민들레 홀씨
          ctx.globalAlpha = 0.85;
          ctx.strokeStyle = '#ffffff';
          ctx.lineWidth = 0.4;
          ctx.beginPath();
          ctx.moveTo(x, y);
          ctx.lineTo(x - wind * 0.12, y + 2.6);
          ctx.stroke();
          ctx.fillStyle = '#ffffff';
          disc(ctx, x, y, 1.15);
        } else {
          ctx.globalAlpha = 0.85;
          ctx.fillStyle = i % 3 === 1 ? '#ffb7cf' : '#fff2f6';
          ell(ctx, x, y, 1.9, 0.95, time / 380 + i * 1.9 + h1 * 6);
        }
      }
    }
    ctx.globalAlpha = 1;
  };

  /** 구름 바다 — 몽실몽실한 두 줄(한 줄이 길 하나 = fill 한 번). */
  const drawClouds = (ctx: CanvasRenderingContext2D, level: number, p: Palette, time: number) => {
    const top = Math.min(H, level);
    for (let row = 0; row < 2; row++) {
      const dy = row === 0 ? -1.5 : 3;
      const ph = row === 0 ? 7 : 0;
      ctx.fillStyle = row === 0 ? p.cloudEdge : p.cloud;
      ctx.beginPath();
      for (let i = -1; i < 47; i++) {
        const cx = i * 14 + ph;
        const cy = top + dy + Math.sin(time / 600 + i * 1.3 + ph) * 1.3;
        ctx.moveTo(cx + 9.5, cy);
        ctx.arc(cx, cy, 9.5, 0, TAU);
      }
      ctx.rect(0, top + dy + 3, W, H - top + 10);
      ctx.fill();
    }
    ctx.fillStyle = 'rgba(255,255,255,.5)';
    ctx.beginPath();
    for (let i = 0; i < 24; i++) {
      const cx = i * 28 - 3;
      const cy = top - 1.5 + Math.sin(time / 600 + i * 2.6) * 1.3;
      ctx.moveTo(cx + 3, cy);
      ctx.ellipse(cx, cy, 3, 1.2, -0.3, 0, TAU);
    }
    ctx.fill();
  };

  // ── 흙 ──────────────────────────────────────────────────────────────
  // 마스크 → 매끈한 윤곽(확대해 흐려진 가장자리를 겹쳐 또렷하게) → 미리 그린 흙 무늬를 오려 내고, 윤곽을 밀어 뺀 띠로 풀·그늘을 얹는다.
  // 전부 캔버스 합성이라 칸마다 색을 계산하는 것보다 빠르다(구멍이 날 때마다 불린다).
  let soilPattern: HTMLCanvasElement | null = null;
  let drips: HTMLCanvasElement | null = null;
  let maskCanvas: HTMLCanvasElement | null = null;
  let maskImage: ImageData | null = null;
  let shape: HTMLCanvasElement | null = null;
  let scratchA: HTMLCanvasElement | null = null;
  let scratchB: HTMLCanvasElement | null = null;

  const paintPattern = (g: CanvasRenderingContext2D) => {
    g.fillStyle = '#b0683c';
    g.fillRect(0, 0, W, H);
    const deep = g.createLinearGradient(0, 110, 0, H);
    deep.addColorStop(0, 'rgba(110,50,30,0)');
    deep.addColorStop(1, 'rgba(90,40,30,.42)');
    g.fillStyle = deep;
    g.fillRect(0, 0, W, H);
    // 주무른 자국
    for (let i = 0; i < 240; i++) {
      g.fillStyle = hash(i, 21) > 0.5 ? 'rgba(255,220,180,.07)' : 'rgba(60,20,10,.07)';
      ell(g, hash(i, 22) * W, hash(i, 23) * H, 6 + hash(i, 24) * 16, 3 + hash(i, 25) * 7, hash(i, 26) * 3);
    }
    // 켜
    g.strokeStyle = 'rgba(136,76,44,.5)';
    g.lineWidth = 1.5;
    g.lineCap = 'round';
    for (let k = 0; k < 6; k++) {
      g.beginPath();
      const y0 = 150 + k * 36;
      g.moveTo(-5, y0);
      for (let x = 0; x <= W + 20; x += 20) g.lineTo(x, y0 + Math.sin(x / 47 + k * 1.9) * 7 + Math.sin(x / 19 + k) * 2.5);
      g.stroke();
    }
    // 자갈
    for (let i = 0; i < 150; i++) {
      const x = hash(i, 31) * W;
      const y = 100 + hash(i, 32) * (H - 100);
      const r = 1.3 + hash(i, 33) * 2.2;
      g.fillStyle = 'rgba(70,30,15,.3)';
      ell(g, x + 0.5, y + 0.7, r * 1.15, r * 0.95);
      g.fillStyle = hash(i, 34) > 0.3 ? '#d28e5e' : '#e3b182';
      ell(g, x, y, r * 1.1, r * 0.9, hash(i, 35) * 3);
      g.fillStyle = 'rgba(255,255,255,.45)';
      ell(g, x - r * 0.3, y - r * 0.35, r * 0.4, r * 0.22, -0.5);
    }
    for (let i = 0; i < 420; i++) {
      g.fillStyle = hash(i, 41) > 0.5 ? 'rgba(255,225,190,.3)' : 'rgba(80,35,20,.28)';
      disc(g, hash(i, 42) * W, 90 + hash(i, 43) * (H - 90), 0.3 + hash(i, 44) * 0.5);
    }
  };

  const ensureTerrainTools = (): boolean => {
    if (soilPattern && drips && maskCanvas && maskImage && shape && scratchA && scratchB) return true;
    soilPattern = makeCanvas(W * S, H * S);
    const pg = soilPattern?.getContext('2d');
    drips = makeCanvas(W * S, 1);
    const dg = drips?.getContext('2d');
    maskCanvas = makeCanvas(W, H);
    shape = makeCanvas(W * S, H * S);
    scratchA = makeCanvas(W * S, H * S);
    scratchB = makeCanvas(W * S, H * S);
    if (!pg || !dg || !maskCanvas || !shape || !scratchA || !scratchB || typeof ImageData === 'undefined') {
      soilPattern = null;
      return false;
    }
    pg.scale(S, S);
    paintPattern(pg);
    // 풀이 흘러내리는 자리(열마다 켜짐·꺼짐)
    dg.fillStyle = '#ffffff';
    for (let x = 0; x < W * S; x++) {
      const cx = x / S;
      if (Math.sin(cx / 4.2) + Math.sin(cx / 9.5 + 1) + Math.sin(cx / 2.3 + 2) * 0.4 > 0.45) dg.fillRect(x, 0, 1, 1);
    }
    maskImage = new ImageData(W, H);
    return true;
  };

  const terrainCanvas = (mask: Mask): CanvasImageSource | null => {
    try {
      if (!ensureTerrainTools()) return null;
      const mg = maskCanvas!.getContext('2d');
      const sg = shape!.getContext('2d');
      const ag = scratchA!.getContext('2d');
      const bg = scratchB!.getContext('2d');
      const out = makeCanvas(W * S, H * S);
      const og = out?.getContext('2d');
      if (!mg || !sg || !ag || !bg || !out || !og) return null;
      const d = maskImage!.data;
      for (let i = 0, n = W * H; i < n; i++) {
        const k = i * 4;
        d[k] = d[k + 1] = d[k + 2] = 255;
        d[k + 3] = mask[i] === 1 ? 255 : 0;
      }
      mg.putImageData(maskImage!, 0, 0);
      const PW = W * S;
      const PH = H * S;
      sg.globalCompositeOperation = 'source-over';
      sg.clearRect(0, 0, PW, PH);
      sg.imageSmoothingEnabled = true;
      // 흐리게 늘린 뒤 "겹치기(진하게) → 자기 자신으로 오리기(옅게)"를 되풀이하면 가운데 문턱에서 또렷해진다 — 계단 없는 둥근 윤곽.
      const canBlur = 'filter' in sg;
      if (canBlur) sg.filter = `blur(${(0.8 * S).toFixed(1)}px)`;
      sg.drawImage(maskCanvas!, 0, 0, PW, PH);
      if (canBlur) sg.filter = 'none';
      if (S > 1 || canBlur) {
        for (let i = 0; i < 4; i++) {
          sg.globalCompositeOperation = 'source-over';
          sg.drawImage(shape!, 0, 0);
          sg.globalCompositeOperation = 'destination-in';
          sg.drawImage(shape!, 0, 0);
        }
        sg.globalCompositeOperation = 'source-over';
        sg.drawImage(shape!, 0, 0);
      }

      /** A = 윤곽 − (윤곽을 (dx, dy)칸 민 것) → 그 방향을 보는 가장자리 띠, {@code color} 로 칠한다. */
      const band = (dx: number, dy: number, color: string) => {
        ag.globalCompositeOperation = 'source-over';
        ag.clearRect(0, 0, PW, PH);
        ag.drawImage(shape!, 0, 0);
        ag.globalCompositeOperation = 'destination-out';
        ag.drawImage(shape!, dx * S, dy * S);
        ag.globalCompositeOperation = 'source-in';
        ag.fillStyle = color;
        ag.fillRect(0, 0, PW, PH);
      };
      og.drawImage(soilPattern!, 0, 0);
      og.globalCompositeOperation = 'destination-in';
      og.drawImage(shape!, 0, 0);
      og.globalCompositeOperation = 'source-over';
      // 안쪽 가장자리 그늘 — 윤곽을 사방으로 밀어 겹친 것(깎은 윤곽)을 뺀다
      ag.globalCompositeOperation = 'source-over';
      ag.clearRect(0, 0, PW, PH);
      ag.drawImage(shape!, 0, 0);
      ag.globalCompositeOperation = 'destination-in';
      const e = 1.7 * S;
      ag.drawImage(shape!, e, 0);
      ag.drawImage(shape!, -e, 0);
      ag.drawImage(shape!, 0, e);
      ag.drawImage(shape!, 0, -e);
      bg.globalCompositeOperation = 'source-over';
      bg.clearRect(0, 0, PW, PH);
      bg.drawImage(shape!, 0, 0);
      bg.globalCompositeOperation = 'destination-out';
      bg.drawImage(scratchA!, 0, 0);
      bg.globalCompositeOperation = 'source-in';
      bg.fillStyle = '#6a3520';
      bg.fillRect(0, 0, PW, PH);
      og.globalAlpha = 0.6;
      og.drawImage(scratchB!, 0, 0);
      // 아랫면 그늘
      band(0, -5, '#4a2418');
      og.globalAlpha = 0.34;
      og.drawImage(scratchA!, 0, 0);
      og.globalAlpha = 1;
      // 흘러내린 풀(짙은 테 → 풀)
      for (const [depth, color] of [
        [10.2, '#46a63a'],
        [9.2, '#74d44a'],
      ] as const) {
        band(0, depth, color);
        ag.globalCompositeOperation = 'destination-in';
        ag.imageSmoothingEnabled = true;
        ag.drawImage(drips!, 0, 0, PW, PH);
        og.drawImage(scratchA!, 0, 0);
      }
      band(0, 6.4, '#46a63a');
      og.drawImage(scratchA!, 0, 0);
      band(0, 5.4, '#74d44a');
      og.drawImage(scratchA!, 0, 0);
      band(0, 1.7, '#b0ee70');
      og.drawImage(scratchA!, 0, 0);
      return out;
    } catch {
      return null;
    }
  };

  // ── 궤적·씨앗·폭발 ──────────────────────────────────────────────────
  const drawTrail = (ctx: CanvasRenderingContext2D, trail: number[]) => {
    const n = trail.length / 2;
    if (n < 1) return;
    // 떨어져 나온 반짝이 — 자리마다 정해진 어긋남(꼬리로 갈수록 작다)
    ctx.fillStyle = '#ffe27a';
    for (let i = 0; i < n; i += 2) {
      const x = trail[i * 2];
      const y = trail[i * 2 + 1];
      const k = (i + 1) / n;
      const j = hash(x * 3, y * 5);
      star(ctx, x + 0.5 + (j - 0.5) * 7, y + 0.5 + (hash(y * 3, x * 7) - 0.5) * 7 + (1 - k) * 2.5, 0.5 + k * 1.3, 4, 0.4, j * 6 + k * 3);
    }
    // 구슬 줄 — 남색 테 한 번, 흰 구슬 한 번
    for (let pass = 0; pass < 2; pass++) {
      ctx.fillStyle = pass === 0 ? NAVY : '#ffffff';
      ctx.beginPath();
      for (let i = 0; i < n; i++) {
        const r = 0.5 + (1.15 * (i + 1)) / n + (pass === 0 ? 0.38 : 0);
        const x = trail[i * 2] + 0.5;
        const y = trail[i * 2 + 1] + 0.5;
        ctx.moveTo(x + r, y);
        ctx.arc(x, y, r, 0, TAU);
      }
      ctx.fill();
    }
  };

  const drawSeed = (ctx: CanvasRenderingContext2D, x: number, y: number, shell: Shell, time: number) => {
    const anim = seedAnim(atlas, shell);
    ctx.fillStyle = '#ffffff';
    ctx.globalAlpha = 0.28;
    disc(ctx, x, y, 5.2);
    ctx.globalAlpha = 1;
    ctx.save();
    ctx.translate(x, y);
    const beat = time > 0 ? 1 + Math.sin(time / 60) * 0.06 : 1;
    ctx.scale(1.15 * beat, 1.15 / beat);
    ctx.rotate(time * (shell === 'PUMPKIN' || shell === 'CHESTNUT' ? 0.006 : 0.011));
    blit(ctx, anim, 0, null);
    ctx.restore();
  };

  const drawExplosion = (ctx: CanvasRenderingContext2D, e: FrameExplosion) => {
    const anim = explosionAnim(atlas, e.fill);
    const t = e.t;
    ctx.save();
    ctx.translate(e.x, e.y);
    if (!e.fill && t < 0.3) {
      // 섬광
      const k = 1 - t / 0.3;
      const g = ctx.createRadialGradient(0, 0, 0, 0, 0, e.r * 2.1);
      g.addColorStop(0, `rgba(255,255,255,${0.95 * k})`);
      g.addColorStop(0.45, `rgba(255,236,150,${0.55 * k})`);
      g.addColorStop(1, 'rgba(255,220,120,0)');
      ctx.fillStyle = g;
      disc(ctx, 0, 0, e.r * 2.1);
    }
    // 충격파 고리 둘
    ctx.strokeStyle = '#ffffff';
    ctx.lineWidth = Math.max(0.01, 2.8 * (1 - t));
    ctx.beginPath();
    ctx.arc(0, 0, e.r * (0.3 + 1.25 * ease(t)), 0, TAU);
    ctx.stroke();
    if (t > 0.12) {
      const t2 = (t - 0.12) / 0.88;
      ctx.globalAlpha = 0.6 * (1 - t2);
      ctx.lineWidth = 1.1;
      ctx.beginPath();
      ctx.arc(0, 0, e.r * (0.2 + 1.7 * ease(t2)), 0, TAU);
      ctx.stroke();
      ctx.globalAlpha = 1;
    }
    const k = anim.radius ? e.r / anim.radius : K;
    if (e.fill) {
      // 둔덕 — 뿅 솟았다가 흙이 드러나며 스르르 사라진다
      const s = back(t / 0.35);
      ctx.globalAlpha = t > 0.55 ? Math.max(0, 1 - (t - 0.55) / 0.45) : 1;
      ctx.scale(s, s);
      blit(ctx, anim, Math.min(anim.frames.length - 1, Math.floor((t / 0.5) * anim.frames.length)), null, k);
    } else {
      const s = back(t / 0.4) * (t > 0.82 ? 1 - (t - 0.82) * 1.4 : 1);
      ctx.globalAlpha = t > 0.85 ? Math.max(0, 1 - (t - 0.85) / 0.15) : 1;
      ctx.rotate(t * 0.55);
      ctx.scale(s, s);
      blit(ctx, anim, progressFrameIndex(anim, t), null, k);
    }
    ctx.restore();
  };

  // ── 입자(사건 시각의 닫힌 식) ───────────────────────────────────────
  let fontKey = 0;
  let fontBig = '';
  const numberFont = (fs: number) => {
    if (fs !== fontKey) {
      fontKey = fs;
      fontBig = `700 ${(fs * 1.45).toFixed(2)}px ${FONT}`;
    }
    return fontBig;
  };

  const drawNumber = (ctx: CanvasRenderingContext2D, e: FxEvent, age: number, fs: number, heal: boolean) => {
    const t = age / 1000;
    if (t >= 1) return;
    const pop = back(t / 0.22);
    const big = !heal && e.fill ? 1.3 : 1;
    ctx.save();
    ctx.globalAlpha = t < 0.72 ? 1 : Math.max(0, 1 - (t - 0.72) / 0.28);
    ctx.translate(e.x + Math.sin(t * 9 + e.seed) * 0.8, e.y - lift * 0.9 - fs * 1.4 - ease(t) * 9);
    ctx.rotate((hash(e.seed, 3) - 0.5) * 0.3);
    ctx.scale(pop * big, pop * big);
    ctx.font = numberFont(fs);
    ctx.textAlign = 'center';
    ctx.lineJoin = 'round';
    const text = `${heal ? '+' : '-'}${e.amount}`;
    ctx.lineWidth = fs * 0.62;
    ctx.strokeStyle = NAVY;
    ctx.strokeText(text, 0, 0);
    ctx.lineWidth = fs * 0.34;
    ctx.strokeStyle = '#ffffff';
    ctx.strokeText(text, 0, 0);
    ctx.fillStyle = heal ? '#2fb24c' : '#ff4a2c';
    ctx.fillText(text, 0, 0);
    ctx.restore();
  };

  /** 흙덩이·점토 조각 — 위로 부채꼴로 튀어 포물선으로 떨어지며 돈다. */
  const clods = (ctx: CanvasRenderingContext2D, e: FxEvent, a: number, n: number, colors: string[]) => {
    for (let i = 0; i < n; i++) {
      const life = 44 + hash(e.seed, i * 5 + 1) * 28;
      if (a >= life) continue;
      const ang = -Math.PI * (0.1 + 0.8 * hash(e.seed, i * 5 + 2));
      const v = 1.3 + hash(e.seed, i * 5 + 3) * 2.9;
      const x = e.x + (hash(e.seed, i * 5 + 4) - 0.5) * e.r + Math.cos(ang) * v * a;
      const y = e.y + Math.sin(ang) * v * a + 0.055 * a * a;
      const k = 1 - a / life;
      const s = (0.9 + hash(e.seed, i * 5 + 5) * 1.5) * Math.min(1, k * 3);
      const rot = a * (hash(e.seed, i * 5 + 6) - 0.5) * 0.5;
      ctx.fillStyle = colors[i % colors.length];
      ell(ctx, x, y, s, s * 0.72, rot);
      ctx.fillStyle = 'rgba(255,255,255,.4)';
      disc(ctx, x - s * 0.25, y - s * 0.25, s * 0.26);
    }
  };

  const sparks = (ctx: CanvasRenderingContext2D, e: FxEvent, a: number, n: number, salt: number, spread: number, up: number, g: number, life: number, size: number) => {
    if (a >= life) return;
    const k = 1 - a / life;
    for (let i = 0; i < n; i++) {
      const vx = (hash(e.seed + salt, i * 3 + 1) - 0.5) * spread;
      const vy = -up * (0.4 + hash(e.seed + salt, i * 3 + 2));
      ctx.fillStyle = CONFETTI[(i + salt) % CONFETTI.length];
      star(ctx, e.x + vx * a, e.y + vy * a + 0.5 * g * a * a, size * Math.min(1, k * 3), 4, 0.42, a * 0.3 + i);
    }
  };

  /** 먼지 연기 — 천천히 올라가며 부풀고 옅어진다(바람에 밀린다). */
  const smoke = (ctx: CanvasRenderingContext2D, e: FxEvent, a: number, n: number, wind: number, tone: string, reach: number) => {
    for (let i = 0; i < n; i++) {
      const delay = hash(e.seed, i * 4 + 61) * 10;
      const b = a - delay;
      const life = 46 + hash(e.seed, i * 4 + 62) * 20;
      if (b < 0 || b >= life) continue;
      const k = b / life;
      const x = e.x + (hash(e.seed, i * 4 + 63) - 0.5) * reach * 1.5 + wind * 0.0016 * b * b;
      const y = e.y - reach * 0.15 - b * (0.16 + hash(e.seed, i * 4 + 64) * 0.2);
      ctx.globalAlpha = 0.42 * (1 - k) * Math.min(1, b / 5);
      ctx.fillStyle = tone;
      disc(ctx, x, y, 2 + reach * 0.16 + k * (3 + reach * 0.2));
    }
    ctx.globalAlpha = 1;
  };

  /** 사건 하나의 입자(숫자 빼고) — {@code a} = 사건부터 지난 틱. */
  const drawEvent = (ctx: CanvasRenderingContext2D, e: FxEvent, a: number, fx: SkinFx) => {
    {
      if (e.kind === 'impact') {
        if (e.fill) {
          clods(ctx, e, a, Math.min(14, 5 + Math.round(e.r * 0.5)), MOUND_BITS);
          sparks(ctx, e, a, 6, 7, 4, 2.6, 0.09, 44, 1.5);
        } else {
          smoke(ctx, e, a, 7, fx.wind, '#e6d2ba', e.r);
          clods(ctx, e, a, Math.min(24, 7 + Math.round(e.r * 0.6)), SOIL_BITS);
          // 구멍 가장자리 부스러기 — 테두리에서 떨어져 구른다
          for (let i = 0; i < 10; i++) {
            const life = 34 + hash(e.seed, i * 3 + 81) * 16;
            if (a >= life) continue;
            const ang = Math.PI * (1.08 + 0.84 * hash(e.seed, i * 3 + 82));
            const out = 0.25 + hash(e.seed, i * 3 + 83) * 0.5;
            const x = e.x + Math.cos(ang) * (e.r + out * a);
            const y = e.y + Math.sin(ang) * (e.r + out * a * 0.6) + 0.04 * a * a;
            ctx.fillStyle = i % 3 === 0 ? '#74d44a' : '#884c2c';
            disc(ctx, x, y, 0.9 * Math.min(1, (1 - a / life) * 3));
          }
          sparks(ctx, e, a, 9, 0, 5, 3, 0.09, 50, 1.7);
        }
      } else if (e.kind === 'muzzle') {
        const rad = (e.angle * Math.PI) / 180;
        const c = Math.cos(rad);
        const s = -Math.sin(rad);
        if (a < 9) {
          // 포구 섬광 — 별이 번쩍
          const k = 1 - a / 9;
          ctx.globalAlpha = k;
          ctx.fillStyle = '#ffe27a';
          star(ctx, e.x + c * 2, e.y + s * 2, 7.5 * (0.5 + 0.5 * k), 8, 0.45, a * 0.4);
          ctx.fillStyle = '#ffffff';
          star(ctx, e.x + c * 2, e.y + s * 2, 4.4 * (0.5 + 0.5 * k), 8, 0.5, 0.4 + a * 0.4);
          ctx.globalAlpha = 1;
        }
        // 연기 고리 — 쏜 쪽으로 밀려 나가며 커진다(쏜 방향에 수직인 타원)
        ctx.strokeStyle = '#ffffff';
        for (let i = 0; i < 3; i++) {
          const life = 18 + i * 7;
          if (a >= life) continue;
          const k = a / life;
          const dist = 2 + (5 + i * 5) * ease(k);
          ctx.globalAlpha = 0.9 * (1 - k);
          ctx.lineWidth = 1.5 * (1 - k) + 0.3;
          ctx.beginPath();
          ctx.ellipse(e.x + c * dist, e.y + s * dist, (2.6 - i * 0.5) * (0.5 + k), (5 - i) * (0.5 + k), -rad, 0, TAU);
          ctx.stroke();
        }
        ctx.globalAlpha = 1;
        // 앞으로 튀는 별 조각
        if (a < 30) {
          const k = 1 - a / 30;
          for (let i = 0; i < 7; i++) {
            const vx = c * (1.4 + hash(e.seed, i * 2 + 1) * 1.6) + (hash(e.seed, i * 2 + 2) - 0.5) * 1.6;
            const vy = s * (1.4 + hash(e.seed, i * 2 + 1) * 1.6) + (hash(e.seed, i * 2 + 3) - 0.5) * 1.6;
            ctx.fillStyle = i % 2 ? '#ffd23a' : '#ffffff';
            star(ctx, e.x + vx * a, e.y + vy * a + 0.025 * a * a, 1.6 * Math.min(1, k * 3), 4, 0.42, a * 0.3 + i);
          }
        }
        smoke(ctx, e, a, 3, fx.wind, '#ffffff', 5);
      } else if (e.kind === 'split') {
        if (a < 22) {
          const k = a / 22;
          ctx.globalAlpha = 1 - k;
          ctx.strokeStyle = '#ffffff';
          ctx.lineWidth = 1.2;
          ctx.beginPath();
          ctx.arc(e.x, e.y, 2 + 7 * ease(k), 0, TAU);
          ctx.stroke();
          ctx.fillStyle = '#ffffff';
          for (let i = 0; i < 7; i++) {
            const ang = (i / 7) * TAU + e.seed;
            star(ctx, e.x + Math.cos(ang) * a * 0.55, e.y + Math.sin(ang) * a * 0.55, 1.4 * (1 - k), 4, 0.42, a * 0.3);
          }
          ctx.globalAlpha = 1;
        }
      } else if (e.kind === 'splash') {
        // 구름 바다에 풍덩 — 구름 덩이가 솟았다 가라앉는다
        if (a < 46) {
          ctx.fillStyle = '#ffffff';
          for (let i = 0; i < 11; i++) {
            const vx = (hash(e.seed, i * 2 + 1) - 0.5) * 2.4;
            const vy = -(1.2 + hash(e.seed, i * 2 + 2) * 1.8);
            const k = 1 - a / 46;
            ctx.globalAlpha = 0.9 * k;
            disc(ctx, e.x + vx * a, e.y + vy * a + 0.05 * a * a, 1.4 + 2.4 * k);
          }
          const k = a / 46;
          ctx.globalAlpha = 0.8 * (1 - k);
          ctx.strokeStyle = '#ffffff';
          ctx.lineWidth = 1.2;
          ctx.beginPath();
          ctx.ellipse(e.x, e.y, 4 + 16 * ease(k), 1.2 + 3 * ease(k), 0, 0, TAU);
          ctx.stroke();
          ctx.globalAlpha = 1;
        }
      } else if (e.kind === 'poof') {
        if (a < 36) {
          const k = a / 36;
          ctx.fillStyle = '#ffffff';
          for (let i = 0; i < 9; i++) {
            const ang = (i / 9) * TAU + e.seed;
            ctx.globalAlpha = 0.85 * (1 - k);
            disc(ctx, e.x + Math.cos(ang) * (3 + 9 * ease(k)), e.y + Math.sin(ang) * (2 + 7 * ease(k)) - k * 3, 2.6 * (1 - k * 0.5));
          }
          ctx.globalAlpha = 1;
          sparks(ctx, e, a, 6, 3, 3, 2.2, 0.07, 36, 1.4);
        }
      }
    }
  };

  const drawEffects = (ctx: CanvasRenderingContext2D, fx: SkinFx) => {
    ctx.lineCap = 'round';
    for (const e of fx.events) {
      const age = fx.nowMs - e.at;
      if (age < 0 || age > 1200) continue;
      drawEvent(ctx, e, age / TICK_MS, fx);
    }
    // 숫자는 입자 위에
    for (const e of fx.events) {
      const age = fx.nowMs - e.at;
      if (age < 0) continue;
      if (e.kind === 'damage') drawNumber(ctx, e, age, fx.fontSize, false);
      else if (e.kind === 'heal') drawNumber(ctx, e, age, fx.fontSize, true);
    }
  };

  // ── 승리 축포 ───────────────────────────────────────────────────────
  const BURST_EVERY = 480;
  const BURST_LIFE = 1500;
  const drawCelebration = (ctx: CanvasRenderingContext2D, c: SkinCelebration) => {
    const el = c.elapsedMs;
    const latest = Math.floor(el / BURST_EVERY);
    ctx.lineCap = 'round';
    for (let b = Math.max(0, latest - 3); b <= latest; b++) {
      const age = el - b * BURST_EVERY;
      if (age >= BURST_LIFE) continue;
      const cx = 70 + hash(b, 51) * 500;
      const cy = 40 + hash(b, 52) * 110;
      const col = CONFETTI[b % CONFETTI.length];
      const col2 = CONFETTI[(b + 2) % CONFETTI.length];
      if (age < 320) {
        // 올라가는 불씨
        const k = age / 320;
        const y = cy + (1 - ease(k)) * 150;
        ctx.strokeStyle = '#ffffff';
        ctx.globalAlpha = 0.8;
        ctx.lineWidth = 1.1;
        ctx.beginPath();
        ctx.moveTo(cx, y + 14);
        ctx.lineTo(cx, y);
        ctx.stroke();
        ctx.fillStyle = col;
        disc(ctx, cx, y, 1.6);
        ctx.globalAlpha = 1;
        continue;
      }
      const a = (age - 320) / TICK_MS;
      const k = (age - 320) / (BURST_LIFE - 320);
      const reach = (1 - Math.exp(-a * 0.07)) / 0.07;
      if (a < 8) {
        ctx.globalAlpha = 1 - a / 8;
        ctx.fillStyle = '#ffffff';
        star(ctx, cx, cy, 10 * (0.4 + a / 8), 8, 0.4, b);
      }
      ctx.globalAlpha = k < 0.6 ? 1 : Math.max(0, 1 - (k - 0.6) / 0.4);
      for (let i = 0; i < 20; i++) {
        const ang = (i / 20) * TAU + hash(b, 53) * 3;
        const v = (i % 2 ? 2.5 : 1.65) * (0.85 + hash(b, i + 60) * 0.3);
        const x = cx + Math.cos(ang) * v * reach;
        const y = cy + Math.sin(ang) * v * reach + 0.011 * a * a;
        ctx.fillStyle = i % 2 ? col : col2;
        if (i % 4 === 0) star(ctx, x, y, 3.4 * (1 - k * 0.5), 4, 0.4, a * 0.2 + i);
        else disc(ctx, x, y, 1.9 * (1 - k * 0.5));
      }
      ctx.globalAlpha = 1;
    }
    // 이긴 포대 위로 쏟아지는 색종이
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
        ell(ctx, x, y, 1.7, 0.8 + 0.7 * Math.abs(Math.sin(t * 14 + i)), t * 11 + i);
      }
    }
    ctx.globalAlpha = 1;
  };

  // ── 이름표 ──────────────────────────────────────────────────────────
  const shown = new Float64Array(8).fill(-1);
  const shownAt = new Float64Array(8);
  /** D-254 — 체력이 깎인 시각과 그때의 목표 체력(잔상이 잠깐 머문 뒤 줄어든다). */
  const dropAt = new Float64Array(8).fill(-1e9);
  const dropTo = new Float64Array(8).fill(-1);
  const widths = new Map<string, number>();
  let labelFontKey = 0;
  let labelFont = '';

  const drawLabel = (ctx: CanvasRenderingContext2D, l: SkinLabel) => {
    // 작은 화면(글씨를 키운 상태)에서는 알약이 포대를 가리지 않게 조금 줄인다
    const fs = l.fontSize > 10 ? l.fontSize * 0.84 : l.fontSize;
    if (fs !== labelFontKey) {
      labelFontKey = fs;
      labelFont = `700 ${(fs * 0.92).toFixed(2)}px ${FONT}`;
      widths.clear();
    }
    ctx.font = labelFont;
    let tw = widths.get(l.text);
    if (tw === undefined) {
      tw = ctx.measureText(l.text)?.width ?? fs * l.text.length * 0.6;
      if (widths.size > 48) widths.clear();
      widths.set(l.text, tw);
    }
    const cx = l.x + 0.5;
    const top = l.footY - lift * l.unitScale - fs * 0.6;
    const color = l.color ?? NAVY;
    const edge = l.team !== null && l.team !== undefined ? TEAM_COLORS[l.team & 1] : NAVY;
    const pw = tw + fs * 1.5;
    const ph = fs * 1.25;
    const px = cx - pw / 2;
    const py = top - fs * 0.95;
    ctx.fillStyle = edge;
    rr(ctx, px - 0.7, py - 0.7, pw + 1.4, ph + 2, ph / 2 + 0.7);
    ctx.fill();
    ctx.fillStyle = '#ffffff';
    rr(ctx, px, py, pw, ph, ph / 2);
    ctx.fill();
    ctx.fillStyle = color;
    disc(ctx, px + fs * 0.5, py + ph / 2, fs * 0.27);
    ctx.fillStyle = NAVY;
    ctx.fillText(l.text, cx + fs * 0.3, top);
    // 체력 — 깎일 때 스르르 줄고, 방금 깎인 만큼은 흰색으로 남았다 사라진다
    let hp = l.hp;
    let since = 1e9;
    const seat = l.seat ?? -1;
    const time = l.time ?? 0;
    if (seat >= 0 && time > 0) {
      const i = seat & 7;
      if (shown[i] < 0 || time - shownAt[i] > 2000 || shown[i] < l.hp) {
        shown[i] = l.hp;
        dropTo[i] = l.hp;
      } else if (fxLayer) {
        if (l.hp < dropTo[i]) {
          dropAt[i] = time;
          dropTo[i] = l.hp;
        }
        since = time - dropAt[i];
        // 깎인 만큼이 잠깐 깜빡이며 머물다가 스르르 줄어든다
        if (since > 420) shown[i] += (l.hp - shown[i]) * Math.min(1, (time - shownAt[i]) / 220);
      } else shown[i] += (l.hp - shown[i]) * Math.min(1, (time - shownAt[i]) / 170);
      shownAt[i] = time;
      hp = shown[i];
    }
    const bw = fs * 2.9;
    const bh = fs * 0.52;
    // 맞은 순간 막대가 덜덜 떤다
    const bx = cx - bw / 2 + (since < 260 ? Math.sin(since / 18) * 1.1 * (1 - since / 260) : 0);
    const byy = top + fs * 0.45;
    const ratio = clamp01(l.hp / l.maxHp);
    const lag = clamp01(hp / l.maxHp);
    ctx.fillStyle = NAVY;
    rr(ctx, bx - 0.7, byy - 0.7, bw + 1.4, bh + 1.4, bh);
    ctx.fill();
    ctx.fillStyle = '#5a6788';
    rr(ctx, bx, byy, bw, bh, bh / 2);
    ctx.fill();
    if (lag > ratio + 0.005) {
      ctx.fillStyle = since < 420 && (Math.floor(since / 70) & 1) === 1 ? '#ff9a8a' : '#ffffff';
      rr(ctx, bx, byy, Math.max(bh, bw * lag), bh, bh / 2);
      ctx.fill();
    }
    if (ratio > 0.02) {
      ctx.fillStyle = ratio > 0.5 ? HP_COLORS[0] : ratio > 0.25 ? HP_COLORS[1] : HP_COLORS[2];
      rr(ctx, bx, byy, Math.max(bh, bw * ratio), bh, bh / 2);
      ctx.fill();
      ctx.fillStyle = 'rgba(255,255,255,.45)';
      rr(ctx, bx + bh * 0.3, byy + bh * 0.15, Math.max(0.1, bw * ratio - bh * 0.6), bh * 0.28, bh * 0.14);
      ctx.fill();
    }
    if (l.isTurn) {
      // 차례 화살표 — 이름표 위에서 통통 튄다
      const y = py - 6.5 - (time > 0 ? Math.abs(Math.sin(time / 220)) * 3.2 : 0);
      const squash = time > 0 ? 1 + Math.max(0, 0.25 - Math.abs(Math.sin(time / 220))) * 0.9 : 1;
      ctx.save();
      ctx.translate(cx, y + 5);
      ctx.scale(squash, 1 / squash);
      ctx.lineJoin = 'round';
      for (let pass = 0; pass < 2; pass++) {
        ctx.beginPath();
        ctx.moveTo(0, 0);
        ctx.lineTo(-4.2, -5);
        ctx.lineTo(-1.7, -5);
        ctx.lineTo(-1.7, -8.6);
        ctx.lineTo(1.7, -8.6);
        ctx.lineTo(1.7, -5);
        ctx.lineTo(4.2, -5);
        ctx.closePath();
        if (pass === 0) {
          ctx.strokeStyle = '#ffffff';
          ctx.lineWidth = 2.4;
          ctx.stroke();
        } else {
          ctx.fillStyle = color;
          ctx.fill();
          ctx.fillStyle = 'rgba(255,255,255,.4)';
          ctx.fillRect(-1.1, -8, 0.8, 4.4);
        }
      }
      ctx.restore();
    }
  };

  // ── 바람개비 ────────────────────────────────────────────────────────
  const VANES = ['#ff5a3c', '#ffd23a', '#3f7fd9', '#4fd45a'];
  const drawWind = (ctx: CanvasRenderingContext2D, wind: number, time: number, ui: number) => {
    const dir = Math.sign(wind);
    const n = Math.ceil(Math.abs(wind) / 2.5);
    ctx.save();
    ctx.translate(W / 2, 5 * ui);
    ctx.scale(ui, ui);
    ctx.fillStyle = NAVY;
    rr(ctx, -27, 0.5, 54, 13, 6.5);
    ctx.fill();
    ctx.fillStyle = '#ffffff';
    rr(ctx, -26, 1.5, 52, 11, 5.5);
    ctx.fill();
    ctx.save();
    ctx.translate(0, 7);
    ctx.rotate((time * wind * 0.0016) % TAU);
    for (let i = 0; i < 4; i++) {
      ctx.rotate(Math.PI / 2);
      ctx.fillStyle = VANES[i];
      ctx.beginPath();
      ctx.moveTo(0, 0);
      ctx.lineTo(4.6, 0);
      ctx.quadraticCurveTo(4.6, -3.4, 0.6, -3.2);
      ctx.closePath();
      ctx.fill();
    }
    ctx.fillStyle = NAVY;
    disc(ctx, 0, 0, 0.9);
    ctx.restore();
    ctx.fillStyle = '#ff5a3c';
    for (let i = 0; i < n; i++) {
      const x = dir * (8.5 + i * 4.2);
      ctx.beginPath();
      ctx.moveTo(x, 4);
      ctx.lineTo(x + dir * 3, 7);
      ctx.lineTo(x, 10);
      ctx.lineTo(x + dir * 0.9, 7);
      ctx.closePath();
      ctx.fill();
    }
    ctx.fillStyle = NAVY;
    ctx.font = `700 7.5px ${FONT}`;
    ctx.textAlign = 'center';
    ctx.fillText(String(Math.abs(wind)), -(dir || 1) * 15, 9.7);
    ctx.restore();
  };

  if (CLAY_FX) fxLayer = createClayFx({ lift, unitScale: CLAY_UNIT_SCALE, drawUnit, baseEvent: drawEvent });
  const layer = fxLayer;

  return {
    id,
    fallbackPalette: CLAY_PALETTE,
    unitScale: CLAY_UNIT_SCALE,
    smooth: true,
    // D-254 — 구멍 몫은 줄이고 피해량에 비례해 흔든다(30 피해 = 4칸). 꼬리는 파편·그을음·쓰러진 포대가 사라질 때까지.
    shakeCells: layer ? 1.4 : 3.2,
    ...(layer ? { shakeDamageCells: 4, fxTailMs: CLAY_FX_TAIL_MS } : {}),
    terrainImage: (mask) => paintSoil(mask, atlas.soil ?? VECTOR_SOIL),
    terrainCanvas,
    drawSky,
    drawClouds,
    drawUnit,
    drawGhost(ctx, x, footY, color, kind: UnitKind | null | undefined) {
      ctx.save();
      ctx.translate(x + 0.5, footY + 1);
      blit(ctx, unitAnim(atlas, kind, 'idle'), 0, color);
      ctx.restore();
    },
    drawTrail: layer ? layer.drawTrail : drawTrail,
    drawSeed,
    drawExplosion,
    drawLabel,
    drawEffects: layer ? layer.drawEffects : drawEffects,
    drawCelebration: layer ? layer.drawCelebration : drawCelebration,
    drawWind,
  };
}
