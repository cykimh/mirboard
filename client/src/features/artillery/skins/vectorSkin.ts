import { FIELD_H, FIELD_W, type Shell } from '@/types/artillery';
import type { Mask } from '../terrain';
import type { FrameExplosion } from '../replay';
import type { ArtillerySkin, Palette, SkinLabel, SkinUnit, SoilColors } from './types';

/**
 * 도형 스킨 (D-227) — D-223 부터 쓰던 그림을 그대로 옮겼다(화분·줄기·씨앗·구름, 외부 그림 없음). 그림 시트가 없거나 아직 읽는 중일 때의
 * 대체 스킨이기도 하다. 포대 상태(`pose`)는 보지 않는다 — 지금 그림과 한 픽셀도 다르지 않게(반동·피격은 스프라이트 스킨에서).
 */
export const VECTOR_PALETTE: Palette = {
  skyTop: '#9fd3f5',
  skyBottom: '#e8f5ff',
  cloud: '#ffffff',
  cloudEdge: '#d6e6f2',
  label: '#1d2a36',
};

/** 흙 단면 색 — 윗면에서의 깊이로 고른다(풀 → 겉흙 → 속흙). 테마와 무관하게 같은 흙이다. */
export const VECTOR_SOIL: SoilColors = {
  grass: [96, 168, 72],
  grassDark: [70, 132, 56],
  topsoil: [150, 102, 62],
  subsoil: [112, 74, 46],
};

/** 마스크 → 640×360 이미지. 깊이에 따라 칠하고 칸마다 살짝 얼룩을 준다(결정적). */
export function paintSoil(mask: Mask, soil: SoilColors): ImageData {
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
      const c = depth <= 2 ? soil.grass : depth <= 4 ? soil.grassDark : depth <= 14 ? soil.topsoil : soil.subsoil;
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

function drawSky(ctx: CanvasRenderingContext2D, p: Palette) {
  const sky = ctx.createLinearGradient(0, 0, 0, FIELD_H);
  sky.addColorStop(0, p.skyTop);
  sky.addColorStop(1, p.skyBottom);
  ctx.fillStyle = sky;
  ctx.fillRect(0, 0, FIELD_W, FIELD_H);
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

function drawTrail(ctx: CanvasRenderingContext2D, trail: number[]) {
  if (trail.length < 4) return;
  ctx.strokeStyle = 'rgba(255,255,255,0.6)';
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(trail[0] + 0.5, trail[1] + 0.5);
  for (let i = 2; i < trail.length; i += 2) ctx.lineTo(trail[i] + 0.5, trail[i + 1] + 0.5);
  ctx.stroke();
}

/** 탄마다 다른 씨앗 그림(직접 그린 도형). */
function drawSeed(ctx: CanvasRenderingContext2D, x: number, y: number, shell: Shell) {
  switch (shell) {
    case 'PUMPKIN':
      ctx.fillStyle = '#e9852a';
      ctx.beginPath();
      ctx.ellipse(x, y, 3.2, 2.6, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = '#4f7a2a';
      ctx.fillRect(x - 0.4, y - 3.6, 0.9, 1.4);
      return;
    case 'VINE':
      ctx.fillStyle = '#4f9a3a';
      ctx.beginPath();
      ctx.arc(x, y, 2.3, 0, Math.PI * 2);
      ctx.fill();
      ctx.strokeStyle = '#2f6b22';
      ctx.lineWidth = 0.8;
      ctx.beginPath();
      ctx.arc(x + 1.6, y - 1.6, 1.4, Math.PI * 0.6, Math.PI * 1.9);
      ctx.stroke();
      return;
    case 'CHESTNUT':
      ctx.fillStyle = '#7a4a24';
      ctx.beginPath();
      ctx.arc(x, y, 3.4, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = '#d9b48a';
      ctx.fillRect(x - 2.2, y + 1.5, 4.4, 1.2);
      return;
    case 'SPORE':
      ctx.fillStyle = 'rgba(255,255,255,0.95)';
      ctx.beginPath();
      ctx.arc(x, y, 1.9, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = '#8a6a3a';
      ctx.beginPath();
      ctx.arc(x, y + 1.2, 0.7, 0, Math.PI * 2);
      ctx.fill();
      return;
    default:
      ctx.fillStyle = '#5a3a1e';
      ctx.beginPath();
      ctx.ellipse(x, y, 2.6, 2, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = '#c99a5b';
      ctx.beginPath();
      ctx.arc(x - 0.8, y - 0.7, 0.9, 0, Math.PI * 2);
      ctx.fill();
  }
}

function drawExplosion(ctx: CanvasRenderingContext2D, e: FrameExplosion) {
  const r = e.r * (0.45 + 0.9 * e.t);
  ctx.globalAlpha = Math.max(0, 1 - e.t);
  ctx.fillStyle = e.fill ? '#9bd77a' : '#ffd166';
  ctx.beginPath();
  ctx.arc(e.x, e.y, r, 0, Math.PI * 2);
  ctx.fill();
  ctx.fillStyle = e.fill ? '#5f9e45' : '#ff8c42';
  ctx.beginPath();
  ctx.arc(e.x, e.y, r * 0.6, 0, Math.PI * 2);
  ctx.fill();
  ctx.globalAlpha = 1;
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

/** 차례 표시 — 포대 위에서 위아래로 흔들리는 세모(발 기준, 보정 배율 안에서 그린다). */
export function drawTurnMarker(ctx: CanvasRenderingContext2D, cx: number, footY: number, color: string, time: number) {
  const bob = Math.sin(time / 180) * 1.2;
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.moveTo(cx, footY - 25 + bob);
  ctx.lineTo(cx - 3, footY - 29 + bob);
  ctx.lineTo(cx + 3, footY - 29 + bob);
  ctx.closePath();
  ctx.fill();
}

function drawUnit(ctx: CanvasRenderingContext2D, s: SkinUnit) {
  const { x, footY, color } = s;
  const k = s.scale;
  // 발 칸을 기준으로 k 배 — 작은 화면에서 그림만 키운다.
  ctx.save();
  ctx.translate(x + 0.5, footY + 1);
  ctx.scale(k, k);
  ctx.translate(-(x + 0.5), -(footY + 1));
  const cx = x + 0.5;
  const cy = footY - 5 + 0.5;
  const rad = (s.angle * Math.PI) / 180;
  // 줄기 포신
  ctx.strokeStyle = '#3d8b3d';
  ctx.lineWidth = 2.2;
  ctx.lineCap = 'round';
  ctx.beginPath();
  ctx.moveTo(cx, cy + 1);
  ctx.lineTo(cx + Math.cos(rad) * 10, cy - Math.sin(rad) * 10);
  ctx.stroke();
  const tipX = cx + Math.cos(rad) * 10;
  const tipY = cy - Math.sin(rad) * 10;
  if (s.kind === 'ACORN') {
    // 도토리포 — 넓적한 잎 하나와 도토리 깍정이를 쓴 포구
    ctx.fillStyle = '#7aa83f';
    ctx.beginPath();
    ctx.ellipse(cx - 2.8, cy - 0.2, 3, 1.5, -0.4, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = '#a8703a';
    ctx.beginPath();
    ctx.arc(tipX, tipY, 2.2, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = '#6b4423';
    ctx.beginPath();
    ctx.arc(tipX, tipY - 0.7, 2.3, Math.PI, Math.PI * 2);
    ctx.fill();
  } else if (s.kind === 'DANDELION') {
    // 민들레포 — 가는 잎 셋과 하얀 솜털 포구
    ctx.strokeStyle = '#6cbf4f';
    ctx.lineWidth = 0.9;
    for (let side = -1; side <= 1; side++) {
      ctx.beginPath();
      ctx.moveTo(cx, cy + 1);
      ctx.lineTo(cx + side * 4, cy - 2 - (side === 0 ? 1 : 0));
      ctx.stroke();
    }
    ctx.fillStyle = 'rgba(255,255,255,0.92)';
    ctx.beginPath();
    ctx.arc(tipX, tipY, 2.4, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.arc(tipX, tipY, 0.9, 0, Math.PI * 2);
    ctx.fill();
  } else {
    // 새싹포 — 잎 둘과 꽃봉오리 포구
    ctx.fillStyle = '#58b05a';
    for (let side = -1; side <= 1; side += 2) {
      ctx.beginPath();
      ctx.ellipse(cx + side * 2.6, cy - 0.6, 2.4, 1.1, side * 0.5, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.arc(tipX, tipY, 1.7, 0, Math.PI * 2);
    ctx.fill();
  }
  drawPot(ctx, x, footY, color);
  if (s.isTurn) drawTurnMarker(ctx, cx, footY, color, s.time);
  ctx.restore();
}

/** 이름표 — 글꼴·정렬은 장면이 한 번 걸어 둔다. 체력바는 이름 바로 아래, 전체는 발에서 {@code lift} 칸(× 보정 배율) 위. */
export function drawLabelAt(ctx: CanvasRenderingContext2D, l: SkinLabel, p: Palette, lift: number) {
  const fs = l.fontSize;
  const barW = fs * 2.3;
  const barH = fs * 0.37;
  const top = l.footY - lift * l.unitScale - fs * 0.6;
  ctx.fillStyle = 'rgba(0,0,0,0.35)';
  ctx.fillRect(l.x - barW / 2, top + fs * 0.4, barW, barH);
  const ratio = Math.max(0, Math.min(1, l.hp / l.maxHp));
  ctx.fillStyle = ratio > 0.5 ? '#5ccf6a' : ratio > 0.25 ? '#f2c14b' : '#ef5a4c';
  ctx.fillRect(l.x - barW / 2, top + fs * 0.4, barW * ratio, barH);
  ctx.fillStyle = p.label;
  ctx.fillText(l.text, l.x, top);
}

/** 도형 포대 키(차례 세모 아래)에 맞춘 이름표 높이. */
export const VECTOR_LABEL_LIFT = 15;

export const vectorSkin: ArtillerySkin = {
  id: 'vector',
  fallbackPalette: VECTOR_PALETTE,
  terrainImage: (mask) => paintSoil(mask, VECTOR_SOIL),
  drawSky,
  drawClouds,
  drawUnit,
  drawGhost: (ctx, x, footY, color) => drawPot(ctx, x, footY, color),
  drawTrail,
  drawSeed: (ctx, x, y, shell) => drawSeed(ctx, x, y, shell),
  drawExplosion,
  drawLabel: (ctx, l, p) => drawLabelAt(ctx, l, p, VECTOR_LABEL_LIFT),
};
