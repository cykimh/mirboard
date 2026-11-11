import type { Shell, UnitKind } from '@/types/artillery';
import type { UnitPose } from '../unitPose';
import type { SoilColors } from './types';

/**
 * 스프라이트 시트 정의(`atlas.json`) 읽기와 프레임 고르기 — 순수 함수(D-227). 형식은 `docs/artillery-skins.md` 가 정본이다.
 * 이상한 값이면 {@link AtlasError} 를 던지고, 스킨 고르기(`skins/index.ts`)가 도형 스킨으로 대체한다.
 */
export const ATLAS_FORMAT = 'mirboard-artillery-skin';
export const ATLAS_VERSION = 1;

export interface AtlasRect {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface SpriteAnim {
  frames: AtlasRect[];
  /** 좌석 색으로 칠할 모양(흰 실루엣) — {@code frames} 와 같은 수, 그림 아래에 깐다. */
  tint: AtlasRect[] | null;
  /** 프레임 안의 기준점(시트 픽셀). 포대 = 발(아래 가운데), 포신 = 회전축, 씨앗·폭발 = 가운데. */
  anchor: [number, number];
  /** 포대 몸에서 포신 회전축이 붙는 자리(프레임 안 시트 픽셀). */
  mount: [number, number] | null;
  /** 한 프레임 길이(ms). 0 이면 첫 프레임만. 폭발·둔덕은 무시하고 진행도(0~1)로 고른다. */
  frameMs: number;
  loop: boolean;
  /** 포대 몸 — 이 상태에서 포신을 그리나(쓰러짐은 보통 false). */
  barrel: boolean;
  /** 폭발·둔덕 — 프레임에 그린 원의 반경(시트 픽셀). 서버 구멍 반경에 맞춰 늘려 그린다. 없으면 `pxPerCell` 그대로. */
  radius: number | null;
}

export interface SpriteAtlas {
  image: string;
  /** 시트 픽셀 / 전장 칸. */
  pxPerCell: number;
  /** 이름표를 발에서 몇 칸 위에 둘지. */
  labelLift: number;
  /** 발사 반동 때 포신이 뒤로 밀리는 칸. */
  recoilCells: number;
  soil: SoilColors | null;
  anims: Map<string, SpriteAnim>;
}

export class AtlasError extends Error {}

/** 없으면 시트를 쓰지 않는 최소 묶음 — 나머지는 아래 `*Anim` 의 대체 순서로 채운다. */
export const REQUIRED_ANIMS = ['unit.SPROUT.idle', 'barrel.SPROUT', 'seed.PEA', 'explosion'] as const;

const isNum = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);

function rect(v: unknown, where: string, size?: { w: number; h: number }): AtlasRect {
  if (!Array.isArray(v) || v.length !== 4 || !v.every(isNum)) throw new AtlasError(`${where}: [x, y, w, h] 가 아니다`);
  const [x, y, w, h] = v as number[];
  if (x < 0 || y < 0 || w <= 0 || h <= 0) throw new AtlasError(`${where}: 음수·0 크기`);
  if (size && (x + w > size.w || y + h > size.h)) throw new AtlasError(`${where}: 시트(${size.w}×${size.h}) 밖`);
  return { x, y, w, h };
}

function point(v: unknown, where: string): [number, number] {
  if (!Array.isArray(v) || v.length !== 2 || !v.every(isNum)) throw new AtlasError(`${where}: [x, y] 가 아니다`);
  return [v[0], v[1]];
}

function rgb(v: unknown, where: string): [number, number, number] {
  if (typeof v !== 'string' || !/^#[0-9a-fA-F]{6}$/.test(v)) throw new AtlasError(`${where}: #rrggbb 가 아니다`);
  const n = parseInt(v.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

/** {@code size} 를 주면(그림을 읽은 뒤) 프레임이 시트 안에 있는지도 본다. */
export function parseAtlas(json: unknown, size?: { w: number; h: number }): SpriteAtlas {
  if (!json || typeof json !== 'object') throw new AtlasError('객체가 아니다');
  const j = json as Record<string, unknown>;
  if (j.format !== ATLAS_FORMAT) throw new AtlasError(`format 이 ${ATLAS_FORMAT} 가 아니다`);
  if (j.version !== ATLAS_VERSION) throw new AtlasError(`모르는 version ${String(j.version)}`);
  if (typeof j.image !== 'string' || !j.image) throw new AtlasError('image 가 없다');
  const pxPerCell = j.pxPerCell ?? 1;
  if (!isNum(pxPerCell) || pxPerCell <= 0) throw new AtlasError('pxPerCell 이 양수가 아니다');
  const labelLift = j.labelLift ?? 15;
  const recoilCells = j.recoilCells ?? 2;
  if (!isNum(labelLift) || !isNum(recoilCells)) throw new AtlasError('labelLift·recoilCells 는 수');
  let soil: SoilColors | null = null;
  if (j.soil !== undefined) {
    const s = j.soil as Record<string, unknown>;
    soil = {
      grass: rgb(s?.grass, 'soil.grass'),
      grassDark: rgb(s?.grassDark, 'soil.grassDark'),
      topsoil: rgb(s?.topsoil, 'soil.topsoil'),
      subsoil: rgb(s?.subsoil, 'soil.subsoil'),
    };
  }
  if (!j.anims || typeof j.anims !== 'object') throw new AtlasError('anims 가 없다');
  const anims = new Map<string, SpriteAnim>();
  for (const [key, raw] of Object.entries(j.anims as Record<string, unknown>)) {
    if (!raw || typeof raw !== 'object') throw new AtlasError(`${key}: 객체가 아니다`);
    const a = raw as Record<string, unknown>;
    if (!Array.isArray(a.frames) || a.frames.length === 0) throw new AtlasError(`${key}: frames 가 비었다`);
    const frames = a.frames.map((f, i) => rect(f, `${key}.frames[${i}]`, size));
    let tint: AtlasRect[] | null = null;
    if (a.tint !== undefined) {
      if (!Array.isArray(a.tint) || a.tint.length !== frames.length) throw new AtlasError(`${key}: tint 수가 frames 와 다르다`);
      tint = a.tint.map((f, i) => rect(f, `${key}.tint[${i}]`, size));
    }
    const frameMs = a.frameMs ?? 0;
    if (!isNum(frameMs) || frameMs < 0) throw new AtlasError(`${key}: frameMs 는 0 이상`);
    const radius = a.radius === undefined ? null : a.radius;
    if (radius !== null && (!isNum(radius) || radius <= 0)) throw new AtlasError(`${key}: radius 는 양수`);
    anims.set(key, {
      frames,
      tint,
      anchor: a.anchor === undefined ? [frames[0].w / 2, frames[0].h / 2] : point(a.anchor, `${key}.anchor`),
      mount: a.mount === undefined ? null : point(a.mount, `${key}.mount`),
      frameMs,
      loop: a.loop !== false,
      barrel: a.barrel !== false,
      radius,
    });
  }
  for (const k of REQUIRED_ANIMS) if (!anims.has(k)) throw new AtlasError(`필수 애니메이션 ${k} 가 없다`);
  return { image: j.image, pxPerCell, labelLift, recoilCells, soil, anims };
}

/** 지난 시간 → 프레임 번호. 반복이면 돌고, 아니면 마지막 프레임에 멈춘다. */
export function animFrameIndex(anim: SpriteAnim, elapsedMs: number): number {
  const n = anim.frames.length;
  if (n <= 1 || anim.frameMs <= 0) return 0;
  const i = Math.floor(Math.max(0, elapsedMs) / anim.frameMs);
  return anim.loop ? i % n : Math.min(n - 1, i);
}

/** 진행도(0~1) → 프레임 번호 — 폭발·둔덕은 서버 대본의 폭발 길이 안에서 끝까지 돈다. */
export function progressFrameIndex(anim: SpriteAnim, t: number): number {
  const n = anim.frames.length;
  return Math.max(0, Math.min(n - 1, Math.floor(t * n)));
}

const unitKey = (kind: UnitKind, pose: UnitPose) => `unit.${kind}.${pose}`;

/** 포대 몸 — 없으면 같은 종류의 대기 → 새싹포의 같은 상태 → 새싹포 대기. */
export function unitAnim(a: SpriteAtlas, kind: UnitKind | null | undefined, pose: UnitPose): SpriteAnim {
  const k = kind ?? 'SPROUT';
  return (
    a.anims.get(unitKey(k, pose)) ??
    a.anims.get(unitKey(k, 'idle')) ??
    a.anims.get(unitKey('SPROUT', pose)) ??
    a.anims.get('unit.SPROUT.idle')!
  );
}

export function barrelAnim(a: SpriteAtlas, kind: UnitKind | null | undefined): SpriteAnim {
  return a.anims.get(`barrel.${kind ?? 'SPROUT'}`) ?? a.anims.get('barrel.SPROUT')!;
}

export function seedAnim(a: SpriteAtlas, shell: Shell): SpriteAnim {
  return a.anims.get(`seed.${shell}`) ?? a.anims.get('seed.PEA')!;
}

export function explosionAnim(a: SpriteAtlas, fill: boolean): SpriteAnim {
  return (fill ? a.anims.get('mound') : undefined) ?? a.anims.get('explosion')!;
}
