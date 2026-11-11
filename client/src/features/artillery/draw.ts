import { FIELD_H, FIELD_W } from '@/types/artillery';
import type { Mask } from './terrain';
import type { Frame, FrameUnit } from './replay';
import { poseAt, type PoseState } from './unitPose';
import type { ArtillerySkin, Palette, SkinCelebration, SkinFx, SkinLabel, SkinUnit } from './skins/types';
import { shakeAt, type FxEvent } from './effects';
import { paintSoil, VECTOR_SOIL, vectorSkin } from './skins/vectorSkin';

export type { Palette } from './skins/types';

/**
 * 장면 그리기 (전장 640×360칸 → 화면 크기로 늘림). 무엇을 어떤 순서로 그릴지만 정하고, 그림 자체는 스킨(`skins/`, D-227)이 그린다.
 * 상태 계산은 `replay.ts`(시간 → 장면)·`unitPose.ts`(포대 상태)·`terrain.ts` 에 있다. 조준 안내·화면 밖 씨앗 표시는 스킨이 아닌 UI 라
 * 여기서 그린다.
 */
export const SEAT_COLORS = ['#e8613c', '#3f7fd9', '#9a5bd0', '#e0a323', '#2fa39a', '#d94f8a'];
export const seatColor = (seat: number) => SEAT_COLORS[((seat % SEAT_COLORS.length) + SEAT_COLORS.length) % SEAT_COLORS.length];

/** 도형 스킨의 흙 그림 — 예전 이름 그대로 둔다. */
export function terrainImage(mask: Mask): ImageData {
  return paintSoil(mask, VECTOR_SOIL);
}

export function toCanvas(img: ImageData): HTMLCanvasElement {
  const c = document.createElement('canvas');
  c.width = FIELD_W;
  c.height = FIELD_H;
  c.getContext('2d')?.putImageData(img, 0, 0);
  return c;
}

export interface Scene {
  terrain: CanvasImageSource | null;
  units: FrameUnit[];
  /** 좌석 → 조준 각도(그리는 포신 방향). */
  angles: Record<number, number>;
  turnSeat: number;
  mySeat: number;
  cloudLevel: number;
  names: Record<number, string>;
  /** 내 조준 안내(내 차례만). */
  aim: { seat: number; angle: number; power: number } | null;
  /** 이동 미리보기 — 원래 자리 유령. */
  ghost: { x: number; footY: number } | null;
  frame: Frame | null;
  palette: Palette;
  time: number;
  /**
   * 포대 상태 — {@code units} 와 같은 순서(`unitPose.poseAt` 결과). 없으면 재생 밖 규칙(차례 주인 = 조준, 나머지 대기)으로 정한다.
   */
  poses?: PoseState[];
  /** 아래는 D-252 — 쓰는 스킨만 본다. 바람(하늘·바람개비). */
  wind?: number;
  /** 2:2 팀전 — 팀 = 좌석 홀짝. */
  teams?: boolean;
  /** 대본 연출 사건과 재생 시작부터 지난 시간(재생 뒤 꼬리 포함). */
  fx?: { events: readonly FxEvent[]; nowMs: number } | null;
  /** 승리 축포 — 이긴 좌석과 시작부터 지난 시간. */
  celebrate?: { seats: readonly number[]; elapsedMs: number } | null;
}

/**
 * 작은 화면 보정 — 칸 하나가 화면에서 이 CSS 픽셀보다 작으면 글씨·포대를 키운다(휴대폰 세로에서 전장 폭이 340px 남짓이라 칸 하나가
 * 0.5px 남짓이다). 맞는 범위(서버 판정)는 그대로이고 그림만 커진다.
 */
export function uiBoost(cssPerCell: number): { label: number; unit: number } {
  return {
    label: Math.max(7, 11 / Math.max(0.01, cssPerCell)),
    unit: Math.min(1.6, Math.max(1, 0.75 / Math.max(0.01, cssPerCell))),
  };
}

/** 전장 위 이름 — 작은 화면에서는 짧게(옆 포대 이름과 겹치지 않게). 전체 이름은 좌석 띠에 있다. */
export function canvasLabel(name: string, mine: boolean, compact: boolean): string {
  const max = compact ? 6 : 12;
  const short = [...name].length > max ? `${[...name].slice(0, max - 1).join('')}…` : name;
  if (!mine) return short;
  return compact ? '나' : `${short} (나)`;
}

// 프레임마다 고쳐 쓰는 그리기 인자(할당 줄이기).
const unitArg: SkinUnit = {
  x: 0,
  footY: 0,
  seat: 0,
  kind: null,
  angle: 0,
  color: '',
  isTurn: false,
  pose: { pose: 'idle', elapsedMs: 0 },
  scale: 1,
  time: 0,
};
const labelArg: SkinLabel = { x: 0, footY: 0, text: '', hp: 0, maxHp: 100, fontSize: 11, unitScale: 1 };
const fxArg: SkinFx = { events: [], nowMs: 0, fontSize: 11, wind: 0 };
const celebrationArg: SkinCelebration = { elapsedMs: 0, winners: [] };
const shakeOut = { x: 0, y: 0 };
const fallbackPose: PoseState = { pose: 'idle', elapsedMs: 0 };

/** 한 장면을 그린다. {@code scale} = 화면 픽셀 / 칸, {@code cssPerCell} = CSS 픽셀 / 칸. */
export function drawScene(
  ctx: CanvasRenderingContext2D,
  scale: number,
  s: Scene,
  cssPerCell = 1.2,
  skin: ArtillerySkin = vectorSkin,
): void {
  const boost = uiBoost(cssPerCell);
  const W = FIELD_W;
  ctx.save();
  ctx.setTransform(scale, 0, 0, scale, 0, 0);

  skin.drawSky(ctx, s.palette, s.time, s.wind ?? 0);

  // 화면 흔들림(D-252) — 하늘·구름 바다는 그대로 두고 전장만 흔든다.
  ctx.save();
  if (skin.shakeCells && s.fx) {
    shakeAt(s.fx.events, s.fx.nowMs, skin.shakeCells, shakeOut);
    if (shakeOut.x !== 0 || shakeOut.y !== 0) ctx.translate(shakeOut.x, shakeOut.y);
  }

  // 지형
  ctx.imageSmoothingEnabled = skin.smooth === true;
  if (s.terrain) ctx.drawImage(s.terrain, 0, 0, W, FIELD_H);
  // 포대 그림 배율(D-252) — 스킨이 정하면 그림·이름표 높이·조준 안내 시작점이 같이 커진다(맞는 범위는 그대로).
  const unitScale = boost.unit * (skin.unitScale ?? 1);
  const cheering = s.celebrate ? s.celebrate.seats : null;

  // 포대
  for (let i = 0; i < s.units.length; i++) {
    const u = s.units[i];
    if (!u.alive) continue;
    unitArg.x = u.x;
    unitArg.footY = u.footY;
    unitArg.seat = u.seat;
    unitArg.kind = u.kind;
    unitArg.angle = s.angles[u.seat] ?? (u.x < W / 2 ? 50 : 130);
    unitArg.color = seatColor(u.seat);
    unitArg.isTurn = u.seat === s.turnSeat;
    unitArg.pose = s.poses?.[i] ?? poseAt(u, null, null, s.turnSeat, s.time, fallbackPose);
    unitArg.scale = unitScale;
    unitArg.time = s.time;
    unitArg.team = s.teams ? u.seat % 2 : null;
    unitArg.cheer = cheering !== null && cheering.includes(u.seat);
    skin.drawUnit(ctx, unitArg);
  }
  if (s.ghost && s.aim) {
    const aimSeat = s.aim.seat;
    const owner = s.units.find((x) => x.seat === aimSeat);
    ctx.globalAlpha = 0.35;
    skin.drawGhost(ctx, s.ghost.x, s.ghost.footY, seatColor(aimSeat), owner?.kind);
    ctx.globalAlpha = 1;
  }

  // 조준 안내 — 궤적이 아니라 방향과 힘의 크기만(D-220: 미리보기 없음)
  if (s.aim) {
    const u = s.units.find((x) => x.seat === s.aim!.seat && x.alive);
    if (u) {
      const rad = (s.aim.angle * Math.PI) / 180;
      const cx = u.x + 0.5;
      const cy = skin.unitScale ? u.footY + 1 - 5.5 * unitScale : u.footY - 5 + 0.5;
      const from = skin.unitScale ? 14.5 * unitScale : 11;
      const len = from + 8 + s.aim.power * 0.32;
      if (skin.unitScale) {
        // 밝은 하늘에서도 보이게 흰 밑줄
        ctx.strokeStyle = 'rgba(255,255,255,.85)';
        ctx.lineWidth = 2.4;
        ctx.lineCap = 'round';
        ctx.setLineDash([2.5, 2.5]);
        ctx.beginPath();
        ctx.moveTo(cx + Math.cos(rad) * from, cy - Math.sin(rad) * from);
        ctx.lineTo(cx + Math.cos(rad) * len, cy - Math.sin(rad) * len);
        ctx.stroke();
      }
      ctx.strokeStyle = seatColor(s.aim.seat);
      ctx.lineWidth = 1.2;
      ctx.setLineDash([2.5, 2.5]);
      ctx.beginPath();
      ctx.moveTo(cx + Math.cos(rad) * from, cy - Math.sin(rad) * from);
      ctx.lineTo(cx + Math.cos(rad) * len, cy - Math.sin(rad) * len);
      ctx.stroke();
      ctx.setLineDash([]);
    }
  }

  // 이름·체력
  ctx.textAlign = 'center';
  const fs = boost.label;
  ctx.font = `600 ${fs}px system-ui, sans-serif`;
  for (const u of s.units) {
    if (!u.alive) continue;
    labelArg.x = u.x;
    labelArg.footY = u.footY;
    labelArg.text = canvasLabel(s.names[u.seat] ?? '', u.seat === s.mySeat, fs > 10);
    labelArg.hp = u.hp;
    labelArg.maxHp = u.maxHp ?? 100;
    labelArg.fontSize = fs;
    labelArg.unitScale = unitScale;
    labelArg.seat = u.seat;
    labelArg.color = seatColor(u.seat);
    labelArg.team = s.teams ? u.seat % 2 : null;
    labelArg.isTurn = u.seat === s.turnSeat;
    labelArg.time = s.time;
    skin.drawLabel(ctx, labelArg, s.palette);
  }

  // 날아가는 씨앗들
  const f = s.frame;
  if (f) {
    for (const trail of f.trails) skin.drawTrail(ctx, trail);
    for (const sh of f.shells) {
      if (sh.y < 0) {
        // 하늘 위로 나간 씨앗 — 위쪽 가장자리에 위치 표시
        ctx.fillStyle = '#ffffff';
        ctx.beginPath();
        ctx.moveTo(sh.x, 2);
        ctx.lineTo(sh.x - 4, 9);
        ctx.lineTo(sh.x + 4, 9);
        ctx.closePath();
        ctx.fill();
      } else {
        skin.drawSeed(ctx, sh.x + 0.5, sh.y + 0.5, sh.shell, s.time);
      }
    }
    for (const e of f.explosions) skin.drawExplosion(ctx, e);
  }
  if (s.fx && skin.drawEffects) {
    fxArg.events = s.fx.events;
    fxArg.nowMs = s.fx.nowMs;
    fxArg.fontSize = fs;
    fxArg.wind = s.wind ?? 0;
    skin.drawEffects(ctx, fxArg);
  }
  ctx.restore();
  if (s.celebrate && skin.drawCelebration) {
    celebrationArg.elapsedMs = s.celebrate.elapsedMs;
    celebrationArg.winners.length = 0;
    for (const u of s.units) if (u.alive && s.celebrate.seats.includes(u.seat)) celebrationArg.winners.push(u.x, u.footY);
    skin.drawCelebration(ctx, celebrationArg);
  }

  // 구름 바다(맨 앞 — 빠지는 것을 덮는다)
  skin.drawClouds(ctx, s.cloudLevel, s.palette, s.time);
  if (skin.drawWind && s.wind !== undefined) skin.drawWind(ctx, s.wind, s.time, Math.max(1, 0.85 / Math.max(0.01, cssPerCell)));
  ctx.restore();
}
