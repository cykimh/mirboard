import { useEffect, useMemo, useRef, useState } from 'react';
import { FIELD_H, FIELD_W } from '@/types/artillery';
import type { Mask } from './terrain';
import { frameAt, replayLengthMs, type FrameUnit } from './replay';
import type { Replay } from './artilleryStore';
import { drawScene, toCanvas, type Palette } from './draw';
import { poseAt, poseScript, type PoseScript, type PoseState } from './unitPose';
import { skinSource, type ArtillerySkin } from './skins';
import { FOLLOW_MS, easeAngle, followPosition, type WalkFollow } from './liveMove';

interface Props {
  mask: Mask | null;
  units: FrameUnit[];
  replay: Replay | null;
  cloudLevel: number;
  turnSeat: number;
  mySeat: number;
  names: Record<number, string>;
  /** 내 차례면 조준(각도·힘)과 이동 미리보기 자리. */
  aim: { seat: number; angle: number; power: number; x: number; footY: number } | null;
  /** 다른 사람의 마지막 각도(포신 방향 그리기용). */
  angles: Record<number, number>;
  /** 남의 걸음 따라가기(D-251) — 마지막 `UNIT_MOVED`. `units` 는 이미 도착 자리라 그 사이만 경로를 걷는다. */
  follow?: WalkFollow | null;
  /** 차례 주인의 조준 미리보기(D-251) — 포신을 이 각도로 {@link FOLLOW_MS} 동안 돌린다. */
  liveAim?: { seat: number; angle: number; at: number } | null;
  /** 끌어서 각도 정하기 — 내 차례만. */
  onAimAngle?: (angle: number) => void;
  onReplayDone: () => void;
  reducedMotion: boolean;
}

/** 테마 색(CSS 변수 — 라이트/다크)을 읽는다. 변수가 없으면 스킨의 기본 색. */
function readPalette(el: HTMLElement | null, fallback: Palette): Palette {
  if (!el || typeof getComputedStyle !== 'function') return fallback;
  const cs = getComputedStyle(el);
  const v = (name: string, fb: string) => cs.getPropertyValue(name).trim() || fb;
  return {
    skyTop: v('--ar-sky-top', fallback.skyTop),
    skyBottom: v('--ar-sky-bottom', fallback.skyBottom),
    cloud: v('--ar-cloud', fallback.cloud),
    cloudEdge: v('--ar-cloud-edge', fallback.cloudEdge),
    label: v('--ar-label', fallback.label),
  };
}

/** 각도 0~180 — 포대 몸 중심에서 손가락 쪽. 아래쪽을 가리키면 가까운 끝(0 또는 180)으로. */
export function angleToward(cx: number, cy: number, px: number, py: number): number {
  const deg = (Math.atan2(cy - py, px - cx) * 180) / Math.PI;
  if (deg >= 0) return Math.round(deg);
  return px >= cx ? 0 : 180;
}

/**
 * 전장 Canvas. 그림은 스킨(`skins/`, D-227)이 그리고, 시트 스킨을 다 받으면 다시 그린다(그 전에는 도형 스킨). 지형 이미지는 마스크가 바뀔 때만 만들고(대본 재생 중에는 폭발 전까지 이전 지형), 재생 중에만 rAF 를 돌린다. 숨은 탭·움직임
 * 줄이기면 재생을 끝 장면으로 건너뛴다.
 */
export function ArtilleryField({
  mask,
  units,
  replay,
  cloudLevel,
  turnSeat,
  mySeat,
  names,
  aim,
  angles,
  follow = null,
  liveAim = null,
  onAimAngle,
  onReplayDone,
  reducedMotion,
}: Props) {
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const sizeRef = useRef({ w: 0, dpr: 1 });
  const hasCanvas = typeof document !== 'undefined' && typeof ImageData !== 'undefined';

  const [skin, setSkin] = useState<ArtillerySkin>(() => skinSource.current());
  useEffect(() => skinSource.subscribe(() => setSkin(skinSource.current())), []);

  const terrainNow = useMemo(() => (mask && hasCanvas ? safeCanvas(mask, skin) : null), [mask, hasCanvas, skin]);
  // 재생 중 지형 — 연산 k 개를 적용한 그림을 필요할 때 한 번씩 만든다(홀씨·겹씨앗은 구멍이 여럿, D-224).
  const replayTerrains = useRef<{ nonce: number; skin: ArtillerySkin | null; images: (CanvasImageSource | null | undefined)[] }>({
    nonce: -1,
    skin: null,
    images: [],
  });
  const terrainAt = (r: Replay, k: number, sk: ArtillerySkin): CanvasImageSource | null => {
    if (!hasCanvas) return null;
    if (replayTerrains.current.nonce !== r.nonce || replayTerrains.current.skin !== sk) {
      replayTerrains.current = { nonce: r.nonce, skin: sk, images: [] };
    }
    const idx = Math.min(k, r.masks.length - 1);
    if (idx < 0) return null;
    const cached = replayTerrains.current.images[idx];
    if (cached !== undefined) return cached;
    const made = safeCanvas(r.masks[idx], sk);
    replayTerrains.current.images[idx] = made;
    return made;
  };
  // 포대 상태 — 대본마다 창을 한 번 만들고, 상태 객체는 포대 수만큼 만들어 두고 고쳐 쓴다.
  const poseScriptRef = useRef<{ nonce: number; script: PoseScript | null }>({ nonce: -1, script: null });
  const posesRef = useRef<PoseState[]>([]);

  // 그리기에 필요한 최신 값 — rAF 콜백이 읽는다.
  const latest = useRef({ units, replay, cloudLevel, turnSeat, mySeat, names, aim, angles, terrainNow, skin, follow, liveAim, reducedMotion });
  latest.current = { units, replay, cloudLevel, turnSeat, mySeat, names, aim, angles, terrainNow, skin, follow, liveAim, reducedMotion };
  // 남의 포신 보간 — 새 미리보기가 오면 지금 그리던 각도에서 출발한다.
  const shownAngle = useRef<{ seat: number; from: number; to: number; at: number } | null>(null);
  const animatingRef = useRef(false);

  const render = (now: number) => {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext?.('2d');
    if (!canvas || !ctx) return true;
    const { w, dpr } = sizeRef.current;
    if (w <= 0) return true;
    const L = latest.current;
    let frame = null;
    let done = true;
    if (L.replay && replayStart.current !== null) {
      frame = frameAt(L.replay.report, L.replay.before, now - replayStart.current);
      done = frame.done;
    }
    const aimAngles = { ...L.angles };
    let animating = false;
    if (L.liveAim && !frame) {
      const la = L.liveAim;
      const cur = shownAngle.current;
      if (!cur || cur.seat !== la.seat || cur.at !== la.at) {
        const now0 = cur && cur.seat === la.seat ? easeAngle(cur.from, cur.to, cur.at, now) : (aimAngles[la.seat] ?? la.angle);
        shownAngle.current = { seat: la.seat, from: L.reducedMotion ? la.angle : now0, to: la.angle, at: now };
      }
      const sa = shownAngle.current!;
      aimAngles[la.seat] = easeAngle(sa.from, sa.to, sa.at, now);
      animating ||= now - sa.at < FOLLOW_MS;
    } else if (!L.liveAim) {
      shownAngle.current = null;
    }
    if (L.aim) aimAngles[L.aim.seat] = L.aim.angle;
    if (L.replay) aimAngles[L.replay.report.seat] = L.replay.report.angle;
    // 남의 걸음 — 받은 시각부터 경로를 따라 걷는다(움직임 줄이기면 바로 도착 자리).
    const walking = !frame && !L.reducedMotion ? followPosition(L.follow, Date.now()) : null;
    animating ||= walking !== null;
    animatingRef.current = animating;
    const drawUnits = frame
      ? frame.units
      : L.units.map((u) =>
          L.aim && u.seat === L.aim.seat
            ? { ...u, x: L.aim.x, footY: L.aim.footY }
            : walking && L.follow && u.seat === L.follow.seat
              ? { ...u, x: walking.x, footY: walking.footY, alive: true }
              : u,
        );
    // 포대 상태(D-227) — 재생 중이면 대본의 창, 아니면 차례 주인 = 조준.
    let script: PoseScript | null = null;
    if (frame && !frame.done && L.replay) {
      if (poseScriptRef.current.nonce !== L.replay.nonce) {
        poseScriptRef.current = { nonce: L.replay.nonce, script: poseScript(L.replay.report) };
      }
      script = poseScriptRef.current.script;
    }
    const replayMs = script && replayStart.current !== null ? now - replayStart.current : null;
    const poses = posesRef.current;
    while (poses.length < drawUnits.length) poses.push({ pose: 'idle', elapsedMs: 0 });
    for (let i = 0; i < drawUnits.length; i++) poseAt(drawUnits[i], script, replayMs, L.turnSeat, now, poses[i]);
    drawScene(ctx, (w * dpr) / FIELD_W, {
      terrain: frame && L.replay && !frame.done ? terrainAt(L.replay, frame.opsApplied, L.skin) : L.terrainNow,
      units: drawUnits,
      angles: aimAngles,
      turnSeat: L.turnSeat,
      mySeat: L.mySeat,
      cloudLevel: L.cloudLevel,
      names: L.names,
      aim: L.aim && !frame ? { seat: L.aim.seat, angle: L.aim.angle, power: L.aim.power } : null,
      // D-251 — 걸음은 바로 확정되니 원래 자리 유령은 그리지 않는다(되돌리기 없음).
      ghost: null,
      frame,
      palette: readPalette(wrapRef.current, L.skin.fallbackPalette),
      time: now,
      poses,
    }, w / FIELD_W, L.skin);
    return done;
  };

  // 크기
  useEffect(() => {
    const wrap = wrapRef.current;
    const canvas = canvasRef.current;
    if (!wrap || !canvas) return;
    const resize = () => {
      const w = wrap.clientWidth;
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      sizeRef.current = { w, dpr };
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(((w * FIELD_H) / FIELD_W) * dpr);
      render(performance.now());
    };
    resize();
    if (typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(resize);
    ro.observe(wrap);
    return () => ro.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 재생 루프 — 대본이 오면 시작, 끝나면 멈춘다. 차례 표시 화살표도 움직이게 늘 한 장씩은 그린다.
  const replayStart = useRef<number | null>(null);
  const doneRef = useRef(onReplayDone);
  doneRef.current = onReplayDone;
  useEffect(() => {
    if (!replay) {
      replayStart.current = null;
      return;
    }
    const hidden = typeof document !== 'undefined' && document.hidden;
    if (reducedMotion || hidden || replayLengthMs(replay.report) <= 0) {
      doneRef.current();
      return;
    }
    replayStart.current = performance.now();
    let raf = 0;
    const step = (now: number) => {
      if (render(now)) {
        replayStart.current = null;
        doneRef.current();
        return;
      }
      raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
    const onVisibility = () => {
      if (document.hidden) {
        cancelAnimationFrame(raf);
        replayStart.current = null;
        doneRef.current();
      }
    };
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      cancelAnimationFrame(raf);
      document.removeEventListener('visibilitychange', onVisibility);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [replay?.nonce, reducedMotion]);

  // 재생이 아닐 때 — 값이 바뀌면 다시 그리고, 차례 화살표를 위해 느린 루프. 남의 걸음·포신을 따라가는 동안은 매 프레임(D-251).
  useEffect(() => {
    if (replay) return;
    render(performance.now());
    const followLeft = follow && !reducedMotion ? FOLLOW_MS - (Date.now() - follow.startedAt) : 0;
    if ((turnSeat < 0 && followLeft <= 0) || reducedMotion) return;
    let raf = 0;
    let last = 0;
    const loop = (now: number) => {
      if (animatingRef.current || now - last > 50) {
        last = now;
        render(now);
      }
      // 차례가 없으면(걷다 떨어진 뒤 대기) 따라가기가 끝나는 대로 멈춘다.
      if (turnSeat < 0 && !animatingRef.current) return;
      raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(raf);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [replay, units, mask, aim, turnSeat, cloudLevel, reducedMotion, terrainNow, skin, follow, liveAim]);

  // 끌어서 조준
  const dragging = useRef(false);
  const toField = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    return {
      x: ((e.clientX - rect.left) / rect.width) * FIELD_W,
      y: ((e.clientY - rect.top) / rect.height) * FIELD_H,
    };
  };
  const aimFrom = (e: React.PointerEvent<HTMLCanvasElement>) => {
    if (!aim || !onAimAngle) return;
    const p = toField(e);
    onAimAngle(angleToward(aim.x + 0.5, aim.footY - 5 + 0.5, p.x, p.y));
  };

  return (
    <div className="ar-field" ref={wrapRef}>
      <canvas
        ref={canvasRef}
        className={`ar-canvas${aim && onAimAngle ? ' ar-canvas-aiming' : ''}`}
        role="img"
        aria-label="전장 — 흙섬과 화분 포대. 내 차례에는 끌어서 각도를 정한다"
        onPointerDown={(e) => {
          if (!aim || !onAimAngle) return;
          dragging.current = true;
          e.currentTarget.setPointerCapture?.(e.pointerId);
          aimFrom(e);
        }}
        onPointerMove={(e) => {
          if (dragging.current) aimFrom(e);
        }}
        onPointerUp={() => {
          dragging.current = false;
        }}
        onPointerCancel={() => {
          dragging.current = false;
        }}
      />
    </div>
  );
}

function safeCanvas(mask: Mask, skin: ArtillerySkin): CanvasImageSource | null {
  try {
    return toCanvas(skin.terrainImage(mask));
  } catch {
    return null;
  }
}
