import { FIELD_H, FIELD_W } from '@/types/artillery';
import type { Mask } from './terrain';
import type { Frame, FrameUnit } from './replay';

/**
 * Canvas 그리기 (전장 640×360칸 → 화면 크기로 늘림). 그림은 전부 여기서 직접 그린 도형이다(외부 그림 없음 — 화분·줄기·씨앗·구름).
 * 테스트하기 어려운 부분이라 상태 계산은 `replay.ts`·`terrain.ts` 로 빼고 여기는 그리기만 한다.
 */
export const SEAT_COLORS = ['#e8613c', '#3f7fd9', '#9a5bd0', '#e0a323', '#2fa39a', '#d94f8a'];
export const seatColor = (seat: number) => SEAT_COLORS[((seat % SEAT_COLORS.length) + SEAT_COLORS.length) % SEAT_COLORS.length];

export interface Palette {
  skyTop: string;
  skyBottom: string;
  cloud: string;
  cloudEdge: string;
  label: string;
}

/** 흙 단면 색 — 윗면에서의 깊이로 고른다(풀 → 겉흙 → 속흙). 테마와 무관하게 같은 흙이다. */
const GRASS: [number, number, number] = [96, 168, 72];
const GRASS_DARK: [number, number, number] = [70, 132, 56];
const TOPSOIL: [number, number, number] = [150, 102, 62];
const SUBSOIL: [number, number, number] = [112, 74, 46];

/** 마스크 → 640×360 이미지. 깊이에 따라 칠하고 칸마다 살짝 얼룩을 준다(결정적). */
export function terrainImage(mask: Mask): ImageData {
  const img = new ImageData(FIELD_W, FIELD_H);
  const d = img.data;
  for (let x = 0; x < FIELD_W; x++) {
    let depth = 0;
    for (let y = 0; y < FIELD_H; y++) {
      const i = y * FIELD_W + x;
      if (mask[i] !== 1) {
        depth = 0;
        continue;
      }
      depth++;
      const c = depth <= 2 ? GRASS : depth <= 4 ? GRASS_DARK : depth <= 14 ? TOPSOIL : SUBSOIL;
      const speckle = ((x * 73856093) ^ (y * 19349663)) & 15;
      const k = speckle < 3 ? -14 : speckle > 13 ? 10 : 0;
      d[i * 4] = Math.max(0, Math.min(255, c[0] + k));
      d[i * 4 + 1] = Math.max(0, Math.min(255, c[1] + k));
      d[i * 4 + 2] = Math.max(0, Math.min(255, c[2] + k));
      d[i * 4 + 3] = 255;
    }
  }
  return img;
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

/** 한 장면을 그린다. {@code scale} = 화면 픽셀 / 칸, {@code cssPerCell} = CSS 픽셀 / 칸. */
export function drawScene(ctx: CanvasRenderingContext2D, scale: number, s: Scene, cssPerCell = 1.2): void {
  const boost = uiBoost(cssPerCell);
  const W = FIELD_W;
  const H = FIELD_H;
  ctx.save();
  ctx.setTransform(scale, 0, 0, scale, 0, 0);

  // 하늘
  const sky = ctx.createLinearGradient(0, 0, 0, H);
  sky.addColorStop(0, s.palette.skyTop);
  sky.addColorStop(1, s.palette.skyBottom);
  ctx.fillStyle = sky;
  ctx.fillRect(0, 0, W, H);

  // 지형
  if (s.terrain) {
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(s.terrain, 0, 0, W, H);
  }

  // 포대
  for (const u of s.units) {
    if (!u.alive) continue;
    const angle = s.angles[u.seat] ?? (u.x < W / 2 ? 50 : 130);
    drawUnit(ctx, u, angle, seatColor(u.seat), u.seat === s.turnSeat, s.time, boost.unit);
  }
  if (s.ghost && s.aim) {
    ctx.globalAlpha = 0.35;
    drawPot(ctx, s.ghost.x, s.ghost.footY, seatColor(s.aim.seat));
    ctx.globalAlpha = 1;
  }

  // 조준 안내 — 궤적이 아니라 방향과 힘의 크기만(D-220: 미리보기 없음)
  if (s.aim) {
    const u = s.units.find((x) => x.seat === s.aim!.seat && x.alive);
    if (u) {
      const rad = (s.aim.angle * Math.PI) / 180;
      const cx = u.x + 0.5;
      const cy = u.footY - 5 + 0.5;
      const from = 11;
      const len = from + 8 + s.aim.power * 0.32;
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
    const barW = fs * 2.3;
    const barH = fs * 0.37;
    const top = u.footY - 15 * boost.unit - fs * 0.6;
    ctx.fillStyle = 'rgba(0,0,0,0.35)';
    ctx.fillRect(u.x - barW / 2, top + fs * 0.4, barW, barH);
    ctx.fillStyle = u.hp > 50 ? '#5ccf6a' : u.hp > 25 ? '#f2c14b' : '#ef5a4c';
    ctx.fillRect(u.x - barW / 2, top + fs * 0.4, (barW * Math.max(0, u.hp)) / 100, barH);
    ctx.fillStyle = s.palette.label;
    ctx.fillText(canvasLabel(s.names[u.seat] ?? '', u.seat === s.mySeat, fs > 10), u.x, top);
  }

  // 날아가는 씨앗
  const f = s.frame;
  if (f) {
    if (f.trail.length >= 4) {
      ctx.strokeStyle = 'rgba(255,255,255,0.6)';
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(f.trail[0] + 0.5, f.trail[1] + 0.5);
      for (let i = 2; i < f.trail.length; i += 2) ctx.lineTo(f.trail[i] + 0.5, f.trail[i + 1] + 0.5);
      ctx.stroke();
    }
    if (f.shell) {
      if (f.shell.y < 0) {
        // 하늘 위로 나간 씨앗 — 위쪽 가장자리에 위치 표시
        ctx.fillStyle = '#ffffff';
        ctx.beginPath();
        ctx.moveTo(f.shell.x, 2);
        ctx.lineTo(f.shell.x - 4, 9);
        ctx.lineTo(f.shell.x + 4, 9);
        ctx.closePath();
        ctx.fill();
      } else {
        ctx.fillStyle = '#5a3a1e';
        ctx.beginPath();
        ctx.ellipse(f.shell.x + 0.5, f.shell.y + 0.5, 2.6, 2, 0, 0, Math.PI * 2);
        ctx.fill();
        ctx.fillStyle = '#c99a5b';
        ctx.beginPath();
        ctx.arc(f.shell.x - 0.3, f.shell.y - 0.2, 0.9, 0, Math.PI * 2);
        ctx.fill();
      }
    }
    if (f.explosion) {
      const e = f.explosion;
      const r = e.r * (0.45 + 0.9 * e.t);
      ctx.globalAlpha = Math.max(0, 1 - e.t);
      ctx.fillStyle = '#ffd166';
      ctx.beginPath();
      ctx.arc(e.x, e.y, r, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = '#ff8c42';
      ctx.beginPath();
      ctx.arc(e.x, e.y, r * 0.6, 0, Math.PI * 2);
      ctx.fill();
      ctx.globalAlpha = 1;
    }
  }

  // 구름 바다(맨 앞 — 빠지는 것을 덮는다)
  drawClouds(ctx, s.cloudLevel, s.palette, s.time);
  ctx.restore();
}

function drawPot(ctx: CanvasRenderingContext2D, x: number, footY: number, color: string) {
  // 화분: 아래가 좁은 사다리꼴 + 테두리 띠
  const bottom = footY + 1;
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.moveTo(x - 3.2, bottom);
  ctx.lineTo(x + 4.2, bottom);
  ctx.lineTo(x + 5.4, bottom - 5);
  ctx.lineTo(x - 4.4, bottom - 5);
  ctx.closePath();
  ctx.fill();
  ctx.fillStyle = 'rgba(0,0,0,0.18)';
  ctx.fillRect(x - 5, bottom - 6.4, 11, 1.8);
}

function drawUnit(
  ctx: CanvasRenderingContext2D,
  u: FrameUnit,
  angle: number,
  color: string,
  isTurn: boolean,
  time: number,
  k: number,
) {
  // 발 칸을 기준으로 k 배 — 작은 화면에서 그림만 키운다.
  ctx.save();
  ctx.translate(u.x + 0.5, u.footY + 1);
  ctx.scale(k, k);
  ctx.translate(-(u.x + 0.5), -(u.footY + 1));
  const cx = u.x + 0.5;
  const cy = u.footY - 5 + 0.5;
  const rad = (angle * Math.PI) / 180;
  // 줄기 포신
  ctx.strokeStyle = '#3d8b3d';
  ctx.lineWidth = 2.2;
  ctx.lineCap = 'round';
  ctx.beginPath();
  ctx.moveTo(cx, cy + 1);
  ctx.lineTo(cx + Math.cos(rad) * 10, cy - Math.sin(rad) * 10);
  ctx.stroke();
  // 잎 둘
  ctx.fillStyle = '#58b05a';
  for (const side of [-1, 1]) {
    ctx.beginPath();
    ctx.ellipse(cx + side * 2.6, cy - 0.6, 2.4, 1.1, side * 0.5, 0, Math.PI * 2);
    ctx.fill();
  }
  // 꽃봉오리(포구)
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.arc(cx + Math.cos(rad) * 10, cy - Math.sin(rad) * 10, 1.7, 0, Math.PI * 2);
  ctx.fill();
  drawPot(ctx, u.x, u.footY, color);
  if (isTurn) {
    const bob = Math.sin(time / 180) * 1.2;
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.moveTo(cx, u.footY - 25 + bob);
    ctx.lineTo(cx - 3, u.footY - 29 + bob);
    ctx.lineTo(cx + 3, u.footY - 29 + bob);
    ctx.closePath();
    ctx.fill();
  }
  ctx.restore();
}

function drawClouds(ctx: CanvasRenderingContext2D, level: number, p: Palette, time: number) {
  const top = Math.min(FIELD_H, level);
  const base = FIELD_H + 2;
  ctx.fillStyle = p.cloud;
  ctx.beginPath();
  ctx.moveTo(0, base);
  for (let x = 0; x <= FIELD_W; x += 8) {
    ctx.lineTo(x, top - 4 + Math.sin(x / 23 + time / 900) * 2.5 + Math.sin(x / 9) * 1.2);
  }
  ctx.lineTo(FIELD_W, base);
  ctx.closePath();
  ctx.fill();
  ctx.strokeStyle = p.cloudEdge;
  ctx.lineWidth = 1;
  ctx.stroke();
}
