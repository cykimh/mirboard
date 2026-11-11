import { TICK_MS, type FrameExplosion } from '../replay';
import { RECOIL_TICKS } from '../unitPose';
import {
  animFrameIndex,
  barrelAnim,
  explosionAnim,
  parseAtlas,
  progressFrameIndex,
  seedAnim,
  unitAnim,
  type SpriteAnim,
  type SpriteAtlas,
} from './atlas';
import type { ArtillerySkin, SkinUnit } from './types';
import { drawLabelAt, drawTurnMarker, paintSoil, VECTOR_SOIL, vectorSkin } from './vectorSkin';

/**
 * 스프라이트 시트 스킨 (D-227) — PNG 한 장(`atlas.png`)과 프레임 정의(`atlas.json`)로 포대·씨앗·폭발을 그린다. 하늘·구름·이름표·자취는
 * 테마(라이트/다크 CSS 변수)를 따라야 해서 도형 스킨 것을 그대로 쓰고, 흙 색만 시트 정의(`soil`)로 바꿀 수 있다.
 *
 * <p>좌석 색은 시트의 흰 실루엣(`tint` 프레임)을 좌석 색으로 칠해 그림 아래에 깐다 — 색마다 시트 크기 캔버스를 처음 쓸 때 한 번 만든다
 * (좌석 색은 6가지뿐). 포대 그림은 오른쪽을 보고 그리고, 포신이 왼쪽(90° 넘음)을 보면 좌우로 뒤집는다.
 */
export interface SpriteSource {
  image: CanvasImageSource;
  width: number;
  height: number;
}

type CanvasFactory = (w: number, h: number) => HTMLCanvasElement | null;

const defaultCanvas: CanvasFactory = (w, h) => {
  if (typeof document === 'undefined') return null;
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  return c;
};

const RECOIL_MS = RECOIL_TICKS * TICK_MS;

export function createSpriteSkin(id: string, atlas: SpriteAtlas, src: SpriteSource, makeCanvas: CanvasFactory = defaultCanvas): ArtillerySkin {
  const ppc = atlas.pxPerCell;
  const tinted = new Map<string, CanvasImageSource>();

  /** 좌석 색으로 칠한 시트 — 만들 수 없으면(캔버스 없음) 흰 실루엣 그대로. */
  const tintSheet = (color: string): CanvasImageSource => {
    const hit = tinted.get(color);
    if (hit) return hit;
    let made: CanvasImageSource = src.image;
    try {
      const c = makeCanvas(src.width, src.height);
      const g = c?.getContext('2d');
      if (c && g) {
        g.drawImage(src.image, 0, 0);
        g.globalCompositeOperation = 'source-in';
        g.fillStyle = color;
        g.fillRect(0, 0, src.width, src.height);
        made = c;
      }
    } catch {
      // 그대로 흰 실루엣
    }
    tinted.set(color, made);
    return made;
  };

  /** 지금 원점에 기준점을 맞춰 프레임 하나(+ 좌석 색 실루엣)를 그린다. {@code k} = 칸 / 시트 픽셀. */
  const blit = (ctx: CanvasRenderingContext2D, anim: SpriteAnim, i: number, color: string | null, k = 1 / ppc) => {
    const ax = -anim.anchor[0] * k;
    const ay = -anim.anchor[1] * k;
    if (anim.tint && color) {
      const t = anim.tint[i];
      ctx.drawImage(tintSheet(color), t.x, t.y, t.w, t.h, ax, ay, t.w * k, t.h * k);
    }
    const f = anim.frames[i];
    ctx.drawImage(src.image, f.x, f.y, f.w, f.h, ax, ay, f.w * k, f.h * k);
  };

  const drawUnit = (ctx: CanvasRenderingContext2D, s: SkinUnit) => {
    const body = unitAnim(atlas, s.kind, s.pose.pose);
    const fi = animFrameIndex(body, s.pose.elapsedMs);
    const left = s.angle > 90;
    ctx.save();
    ctx.translate(s.x + 0.5, s.footY + 1);
    ctx.scale(s.scale, s.scale);
    if (body.barrel) {
      const barrel = barrelAnim(atlas, s.kind);
      const mx = body.mount ? (body.mount[0] - body.anchor[0]) / ppc : 0;
      const my = body.mount ? (body.mount[1] - body.anchor[1]) / ppc : -5.5;
      ctx.save();
      ctx.translate(left ? -mx : mx, my);
      ctx.rotate((-s.angle * Math.PI) / 180);
      // 왼쪽을 볼 때 포신 그림이 뒤집혀 보이지 않게(잎이 늘 위)
      if (left) ctx.scale(1, -1);
      if (s.pose.pose === 'recoil') ctx.translate(-atlas.recoilCells * Math.max(0, 1 - s.pose.elapsedMs / RECOIL_MS), 0);
      blit(ctx, barrel, animFrameIndex(barrel, s.time), s.color);
      ctx.restore();
    }
    if (s.isTurn) drawTurnMarker(ctx, 0, -1, s.color, s.time);
    if (left) ctx.scale(-1, 1);
    blit(ctx, body, fi, s.color);
    ctx.restore();
  };

  const drawExplosion = (ctx: CanvasRenderingContext2D, e: FrameExplosion) => {
    const anim = explosionAnim(atlas, e.fill);
    ctx.save();
    ctx.translate(e.x, e.y);
    blit(ctx, anim, progressFrameIndex(anim, e.t), null, anim.radius ? e.r / anim.radius : 1 / ppc);
    ctx.restore();
  };

  return {
    id,
    fallbackPalette: vectorSkin.fallbackPalette,
    terrainImage: (mask) => paintSoil(mask, atlas.soil ?? VECTOR_SOIL),
    drawSky: vectorSkin.drawSky,
    drawClouds: vectorSkin.drawClouds,
    drawUnit,
    drawGhost(ctx, x, footY, color, kind) {
      const body = unitAnim(atlas, kind, 'idle');
      ctx.save();
      ctx.translate(x + 0.5, footY + 1);
      blit(ctx, body, 0, color);
      ctx.restore();
    },
    drawTrail: vectorSkin.drawTrail,
    drawSeed(ctx, x, y, shell, time) {
      const anim = seedAnim(atlas, shell);
      ctx.save();
      ctx.translate(x, y);
      blit(ctx, anim, animFrameIndex(anim, time), null);
      ctx.restore();
    },
    drawExplosion,
    drawLabel: (ctx, l, p) => drawLabelAt(ctx, l, p, atlas.labelLift),
  };
}

/**
 * 시트 읽기 — `{base}atlas.json` → 그 안의 `image` → 프레임이 시트 안에 있는지 확인. 어느 단계든 실패하면 거절(부르는 쪽이 도형 스킨으로
 * 대체한다).
 */
export async function loadSpriteSkin(id: string, base: string): Promise<ArtillerySkin> {
  const res = await fetch(`${base}atlas.json`, { cache: 'force-cache' });
  if (!res.ok) throw new Error(`atlas.json ${res.status}`);
  const json: unknown = await res.json();
  const first = parseAtlas(json);
  const img = new Image();
  img.decoding = 'async';
  await new Promise<void>((resolve, reject) => {
    img.onload = () => resolve();
    img.onerror = () => reject(new Error(`${first.image} 를 읽지 못했다`));
    img.src = `${base}${first.image}`;
  });
  const atlas = parseAtlas(json, { w: img.naturalWidth, h: img.naturalHeight });
  return createSpriteSkin(id, atlas, { image: img, width: img.naturalWidth, height: img.naturalHeight });
}
