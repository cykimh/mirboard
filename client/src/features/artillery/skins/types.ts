import type { Shell, UnitKind } from '@/types/artillery';
import type { Mask } from '../terrain';
import type { FrameExplosion } from '../replay';
import type { PoseState } from '../unitPose';

/**
 * 씨앗포 스킨 계약 (D-227). 좌표는 모두 **전장 칸**(640×360) — 화면 배율은 `drawScene` 이 미리 걸어 둔다. 스킨은 그리기만 한다:
 * 무엇을 어디에·어떤 상태로 그릴지는 재생(`replay.ts`)·상태 판정(`unitPose.ts`)·장면(`draw.ts`)이 정한다.
 *
 * <p>성능 — 프레임마다 불리므로 메서드 안에서 배열·객체를 새로 만들지 않는다(그라데이션처럼 Canvas 가 요구하는 것만).
 */
export interface Palette {
  skyTop: string;
  skyBottom: string;
  cloud: string;
  cloudEdge: string;
  label: string;
}

/** 흙 단면 색(풀 → 겉흙 → 속흙, RGB). */
export interface SoilColors {
  grass: [number, number, number];
  grassDark: [number, number, number];
  topsoil: [number, number, number];
  subsoil: [number, number, number];
}

/** 포대 하나를 그리는 데 필요한 값 — 장면이 포대마다 같은 객체를 고쳐 쓴다. */
export interface SkinUnit {
  /** 발 칸(왼쪽 위 모서리 기준 정수 칸). 그림의 기준점은 그 칸의 아래 가운데 (x + 0.5, footY + 1). */
  x: number;
  footY: number;
  seat: number;
  /** 고르기 전(null)이면 새싹포로 그린다. */
  kind: UnitKind | null | undefined;
  /** 포신 각도(0 = 오른쪽, 90 = 위, 180 = 왼쪽). */
  angle: number;
  /** 좌석 색. */
  color: string;
  isTurn: boolean;
  pose: PoseState;
  /** 작은 화면 보정 배율(`uiBoost().unit`) — 기준점을 중심으로 키운다. */
  scale: number;
  /** 화면 시계(ms). */
  time: number;
}

/** 이름표·체력바. */
export interface SkinLabel {
  x: number;
  footY: number;
  text: string;
  hp: number;
  maxHp: number;
  /** 글씨 크기(칸, `uiBoost().label`). */
  fontSize: number;
  /** 포대 보정 배율 — 이름표 높이를 같이 올린다. */
  unitScale: number;
}

export interface ArtillerySkin {
  readonly id: string;
  /** 테마 CSS 변수(`--ar-sky-*` 등)가 없을 때 쓰는 색. 라이트/다크는 CSS 변수가 정한다. */
  readonly fallbackPalette: Palette;
  /** 마스크 → 640×360 흙 그림(마스크가 바뀔 때만 불린다). */
  terrainImage(mask: Mask): ImageData;
  drawSky(ctx: CanvasRenderingContext2D, p: Palette): void;
  /** 구름 바다 — 맨 앞에 그려 빠지는 포대를 덮는다. */
  drawClouds(ctx: CanvasRenderingContext2D, level: number, p: Palette, time: number): void;
  drawUnit(ctx: CanvasRenderingContext2D, u: SkinUnit): void;
  /** 이동 미리보기의 원래 자리(투명도는 장면이 건다). */
  drawGhost(ctx: CanvasRenderingContext2D, x: number, footY: number, color: string, kind: UnitKind | null | undefined): void;
  drawTrail(ctx: CanvasRenderingContext2D, trail: number[]): void;
  /** 씨앗 중심 (x, y). */
  drawSeed(ctx: CanvasRenderingContext2D, x: number, y: number, shell: Shell, time: number): void;
  /** 폭발(`e.fill` 이면 둔덕이 솟는 그림). */
  drawExplosion(ctx: CanvasRenderingContext2D, e: FrameExplosion): void;
  drawLabel(ctx: CanvasRenderingContext2D, l: SkinLabel, p: Palette): void;
}
