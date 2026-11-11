import type { Shell, UnitKind } from '@/types/artillery';
import type { Mask } from '../terrain';
import type { FrameExplosion } from '../replay';
import type { PoseState } from '../unitPose';
import type { FxEvent } from '../effects';

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
  /** 밤 하늘(다크 테마)인가 — CSS 변수 `--ar-night`(D-252). 없으면 낮. */
  night?: boolean;
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
  /** 화면 시계(ms). 움직임 줄이기면 0 — 대기 몸짓이 멈춘다(D-252). */
  time: number;
  /** 팀전이면 팀(0·1), 아니면 null(D-252 — 팀 아우라). */
  team?: number | null;
  /** 이긴 포대 — 결과 화면에서 폴짝 뛴다(D-252). */
  cheer?: boolean;
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
  /** 아래는 D-252 — 장면이 늘 채우지만, 쓰지 않는 스킨이 많아 선택 항목이다. */
  seat?: number;
  color?: string;
  team?: number | null;
  isTurn?: boolean;
  time?: number;
}

/** 대본 연출(D-252) — 사건 목록(`effects.ts`)과 재생 시작부터 지난 시간. 재생이 끝난 뒤에도 꼬리(`FX_TAIL_MS`) 동안 불린다. */
export interface SkinFx {
  events: readonly FxEvent[];
  nowMs: number;
  /** 글씨 크기(칸) — 피해 숫자. */
  fontSize: number;
  wind: number;
  /** 아래는 D-254 — 지금 화면의 지형(파편이 부딪힐 땅, 읽기만), 좌석 색·포신 각도·포대 배율(쓰러진 포대를 꼬리에서 이어 그린다). */
  mask?: Mask | null;
  seatColor?: (seat: number) => string;
  angles?: Record<number, number>;
  unitScale?: number;
  teams?: boolean;
  /** 화면 시계(ms). */
  time?: number;
}

/** 승리 축포(D-252) — 이긴 포대들의 발 자리와 시작부터 지난 시간. */
export interface SkinCelebration {
  elapsedMs: number;
  /** [x, footY, …] */
  winners: number[];
  /** D-254 — 작은 화면 보정 배율(`uiBoost().unit`) — 불꽃·색종이를 같이 키운다. */
  ui?: number;
}

export interface ArtillerySkin {
  readonly id: string;
  /** 테마 CSS 변수(`--ar-sky-*` 등)가 없을 때 쓰는 색. 라이트/다크는 CSS 변수가 정한다. */
  readonly fallbackPalette: Palette;
  /** 마스크 → 640×360 흙 그림(마스크가 바뀔 때만 불린다). */
  terrainImage(mask: Mask): ImageData;
  /**
   * 선택(D-252) — 흙 그림을 캔버스로 직접(전장보다 촘촘해도 된다, 640×360칸에 맞춰 늘려 그린다). null 이면 {@link terrainImage}.
   */
  terrainCanvas?(mask: Mask): CanvasImageSource | null;
  /** 선택(D-252) — 포대 그림 배율(맞는 범위·물리는 그대로, 그림·이름표 높이·조준 안내 시작점만). 기본 1. */
  readonly unitScale?: number;
  /** 선택(D-252) — 흙·시트를 매끄럽게 늘려 그린다(기본은 점 그대로). */
  readonly smooth?: boolean;
  /** 선택(D-252) — 착탄 때 화면 흔들림 크기(칸, 콩알 기준). 없으면 흔들지 않는다. */
  readonly shakeCells?: number;
  /** 선택(D-254) — 피해량에 비례하는 흔들림(칸, 30 피해 기준). `shakeCells` 에 더한다. */
  readonly shakeDamageCells?: number;
  /** 선택(D-254) — 재생 뒤 연출 꼬리 길이(ms). 없으면 `FX_TAIL_MS`. 조작을 막지 않는 화면 전용 시간이다. */
  readonly fxTailMs?: number;
  /** {@code time}·{@code wind} 는 D-252 부터 — 떠가는 구름·바람 줄·꽃잎. */
  drawSky(ctx: CanvasRenderingContext2D, p: Palette, time?: number, wind?: number): void;
  /** 구름 바다 — 맨 앞에 그려 빠지는 포대를 덮는다. */
  drawClouds(ctx: CanvasRenderingContext2D, level: number, p: Palette, time: number): void;
  drawUnit(ctx: CanvasRenderingContext2D, u: SkinUnit): void;
  /** 이동 미리보기의 원래 자리(투명도는 장면이 건다). */
  drawGhost(ctx: CanvasRenderingContext2D, x: number, footY: number, color: string, kind: UnitKind | null | undefined): void;
  /** {@code shell}·{@code wind}·{@code time} 은 D-254 부터 — 탄마다 다른 꼬리, 바람에 휘는 꼬리. */
  drawTrail(ctx: CanvasRenderingContext2D, trail: number[], shell?: Shell, wind?: number, time?: number): void;
  /** 씨앗 중심 (x, y). */
  drawSeed(ctx: CanvasRenderingContext2D, x: number, y: number, shell: Shell, time: number): void;
  /** 폭발(`e.fill` 이면 둔덕이 솟는 그림). */
  drawExplosion(ctx: CanvasRenderingContext2D, e: FrameExplosion): void;
  drawLabel(ctx: CanvasRenderingContext2D, l: SkinLabel, p: Palette): void;
  /** 선택(D-252) — 입자·섬광·피해 숫자. 포대·씨앗 위, 구름 바다 아래. */
  drawEffects?(ctx: CanvasRenderingContext2D, fx: SkinFx): void;
  /** 선택(D-252) — 승리 축포. */
  drawCelebration?(ctx: CanvasRenderingContext2D, c: SkinCelebration): void;
  /** 선택(D-252) — 전장 위 바람 표시(위 가운데 원점, {@code ui} = 작은 화면 보정 배율). */
  drawWind?(ctx: CanvasRenderingContext2D, wind: number, time: number, ui: number): void;
}
