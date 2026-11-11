import { useEffect, useRef, useState } from 'react';
import {
  AIM_REPEAT_DELAY_MS,
  DRIVE_TICK_MS,
  WALK_FLIP_DELAY_MS,
  aimRate,
  elevation,
  faceToward,
  facingOf,
  nudgeAngle,
  stickIntent,
  stickVector,
  type Axis,
  type Facing,
} from './joystick';

/** 힘 모으기 — 누르고 있는 동안 0→100→0 을 이 주기(ms)로 오간다. */
export const CHARGE_PERIOD_MS = 2400;
/** 키보드 ↑↓ 로 돌 때의 세기(조이스틱 반쯤 민 것). */
const KEY_AIM_STRENGTH = 0.45;
/** 힘 게이지를 다시 그리는 주기(ms) — 누르고 있는 동안만. */
const CHARGE_FRAME_MS = 16;

/** 누른 시간 → 힘(삼각파, 0~100). */
export function chargePower(heldMs: number): number {
  const phase = (heldMs % CHARGE_PERIOD_MS) / CHARGE_PERIOD_MS;
  return Math.round((phase < 0.5 ? phase * 2 : (1 - phase) * 2) * 100);
}

interface Props {
  angle: number;
  power: number;
  lastPower: number | null;
  fuelLeft: number;
  /** 내 유닛의 연료 가득(새싹 100·도토리 70·민들레 130). */
  fuelMax?: number;
  enabled: boolean;
  /** 각도가 90°(정확히 위)일 때 바라볼 쪽 — 처음엔 전장 가운데 쪽. */
  initialFacing?: Facing;
  onAngle: (angle: number) => void;
  onPower: (power: number) => void;
  /** 한 칸 걷기 — 그 자리에서 확정된다(D-251, 되돌리기 없음). 더 못 가면 false. */
  onStep: (dir: -1 | 1) => boolean;
  onFire: (power: number) => void;
  onSkip: () => void;
  /** D-226 — 이번 발에 쌓일 딜레이(모르면 null — 숨김)와 넘길 때의 딜레이. */
  delayCost?: number | null;
  skipDelay?: number | null;
}

/** 손가락을 붙잡는다 — 이미 떨어진 포인터면 브라우저가 던지니 조용히 넘긴다(붙잡지 못해도 조작은 된다). */
function capture(e: React.PointerEvent<HTMLElement>) {
  try {
    e.currentTarget.setPointerCapture?.(e.pointerId);
  } catch {
    /* 없는 포인터 */
  }
}

/** 키보드 입력을 가로채지 않을 곳 — 글 쓰는 칸. */
function typingTarget(t: EventTarget | null): boolean {
  if (!(t instanceof HTMLElement)) return false;
  return t.isContentEditable || !!t.closest('input, textarea, select');
}

/**
 * 조작부 (D-253) — 왼손 조이스틱 + 오른손 큰 발사 단추. 휴대폰 터치 우선, 키보드도 같은 길(←→ 걷기·↑↓ 각도·Space 길게 = 힘·발사).
 *
 * <p>조이스틱 좌우는 걷기다 — 걸음은 그 자리에서 확정되고(D-251, `useLiveMove` 가 250ms 박자로 `MOVE`) 연료가 나간다. 반대쪽으로 밀면
 * 먼저 포신을 그쪽으로 돌리고 {@link WALK_FLIP_DELAY_MS} 뒤부터 걷는다. 위아래는 바라보는 쪽 기준으로 포신을 올리고 내린다(톡 = 1°,
 * 누르고 있으면 민 만큼 빠르게). 발사 단추는 누르는 동안 힘이 0→100→0 으로 오가고 떼면 발사, 손가락이 미끄러져 취소되면 쏘지 않는다.
 */
export function ArtilleryControls({
  angle,
  power,
  lastPower,
  fuelLeft,
  fuelMax = 100,
  enabled,
  initialFacing = 1,
  onAngle,
  onPower,
  onStep,
  onFire,
  onSkip,
  delayCost = null,
  skipDelay = null,
}: Props) {
  // 타이머는 늘 최신 핸들러·각도를 부른다(누른 순간의 것을 잡으면 낭떠러지 검사가 낡는다 — D-251 회귀).
  const latest = useRef({ angle, enabled, onAngle, onStep, onPower, onFire });
  latest.current = { angle, enabled, onAngle, onStep, onPower, onFire };
  const facing = useRef<Facing>(facingOf(angle, initialFacing));
  facing.current = facingOf(angle, facing.current);

  // ── 걷기·각도 운전 ─────────────────────────────────────────────
  const drive = useRef({
    walkDir: 0 as -1 | 0 | 1,
    walkFrom: 0,
    walkBlocked: false,
    aimDir: 0 as -1 | 0 | 1,
    aimFrom: 0,
    aimStrength: 0,
    aimAcc: 0,
    lastTick: 0,
  });
  const timer = useRef<number | null>(null);
  const stopTimer = () => {
    if (timer.current !== null) {
      window.clearInterval(timer.current);
      timer.current = null;
    }
  };
  const tick = () => {
    const d = drive.current;
    const L = latest.current;
    const now = Date.now();
    const dt = Math.min(200, now - d.lastTick);
    d.lastTick = now;
    if (!L.enabled) return;
    if (d.walkDir !== 0 && !d.walkBlocked && now >= d.walkFrom) {
      if (!L.onStep(d.walkDir)) d.walkBlocked = true;
    }
    if (d.aimDir !== 0 && now >= d.aimFrom) {
      d.aimAcc += (aimRate(d.aimStrength) * dt) / 1000;
      const whole = Math.floor(d.aimAcc);
      if (whole > 0) {
        d.aimAcc -= whole;
        const next = nudgeAngle(L.angle, facing.current, d.aimDir as 1 | -1, whole);
        if (next !== L.angle) {
          L.angle = next;
          L.onAngle(next);
        }
      }
    }
    if (d.walkDir === 0 && d.aimDir === 0) stopTimer();
  };
  const ensureTimer = () => {
    if (timer.current !== null) return;
    drive.current.lastTick = Date.now();
    timer.current = window.setInterval(tick, DRIVE_TICK_MS);
  };

  const setWalk = (dir: -1 | 0 | 1) => {
    const d = drive.current;
    if (d.walkDir === dir) return;
    d.walkDir = dir;
    d.walkBlocked = false;
    if (dir === 0) return;
    const L = latest.current;
    if (!L.enabled) return;
    const now = Date.now();
    const was = facingOf(L.angle, facing.current);
    facing.current = dir;
    if (was !== dir) {
      // 돌아서기만 — 조금 있다 걷는다(톡 밀면 돌기만). 정확히 90° 면 각도는 그대로, 바라보는 쪽만 바뀐다.
      const turned = faceToward(L.angle, dir);
      if (turned !== L.angle) {
        L.angle = turned;
        L.onAngle(turned);
      }
      d.walkFrom = now + WALK_FLIP_DELAY_MS;
    } else {
      d.walkFrom = now + DRIVE_TICK_MS;
      if (!L.onStep(dir)) d.walkBlocked = true;
    }
    ensureTimer();
  };
  const setAim = (dir: -1 | 0 | 1, strength: number) => {
    const d = drive.current;
    d.aimStrength = strength;
    if (d.aimDir === dir) return;
    d.aimDir = dir;
    d.aimAcc = 0;
    if (dir === 0) return;
    const L = latest.current;
    if (!L.enabled) return;
    const next = nudgeAngle(L.angle, facing.current, dir, 1);
    if (next !== L.angle) {
      L.angle = next;
      L.onAngle(next);
    }
    d.aimFrom = Date.now() + AIM_REPEAT_DELAY_MS;
    ensureTimer();
  };
  const releaseDrive = () => {
    setWalk(0);
    setAim(0, 0);
  };

  // ── 조이스틱 ──────────────────────────────────────────────────
  const baseRef = useRef<HTMLDivElement | null>(null);
  const knobRef = useRef<HTMLSpanElement | null>(null);
  const stickPointer = useRef<number | null>(null);
  const axisRef = useRef<Axis>('none');
  const [stickActive, setStickActive] = useState(false);
  const moveKnob = (x: number, y: number) => {
    if (knobRef.current) knobRef.current.style.transform = `translate(calc(-50% + ${x}px), calc(-50% + ${y}px))`;
  };
  const stickFrom = (e: React.PointerEvent) => {
    const base = baseRef.current;
    if (!base) return;
    const rect = base.getBoundingClientRect();
    const radius = rect.width / 2 || 60;
    const cx = rect.left + radius;
    const cy = rect.top + (rect.height / 2 || radius);
    const { nx, ny } = stickVector(e.clientX - cx, e.clientY - cy, radius);
    moveKnob(nx * radius * 0.6, ny * radius * 0.6);
    const it = stickIntent(nx, ny, axisRef.current);
    axisRef.current = it.axis;
    if (it.axis === 'walk') {
      setAim(0, 0);
      setWalk(it.dir);
    } else if (it.axis === 'aim') {
      setWalk(0);
      setAim(it.dir, it.strength);
    } else releaseDrive();
  };
  const stickEnd = () => {
    stickPointer.current = null;
    axisRef.current = 'none';
    moveKnob(0, 0);
    setStickActive(false);
    releaseDrive();
  };

  // ── 힘 모으기·발사 ────────────────────────────────────────────
  const chargeStart = useRef<number | null>(null);
  const chargeTimer = useRef<number | null>(null);
  const [charging, setCharging] = useState(false);
  /** 누르기·Space 뒤에 오는 click 은 이미 처리했다 — 보조기기·Enter 의 click 만 [발사]다. */
  const suppressClickUntil = useRef(0);
  const stopCharge = () => {
    if (chargeTimer.current !== null) {
      window.clearInterval(chargeTimer.current);
      chargeTimer.current = null;
    }
    chargeStart.current = null;
    setCharging(false);
  };
  const startCharge = () => {
    if (!latest.current.enabled || chargeStart.current !== null) return;
    chargeStart.current = Date.now();
    setCharging(true);
    latest.current.onPower(0);
    // 게이지만 그린다 — 쏘는 힘은 뗀 순간의 시각으로 다시 잰다.
    chargeTimer.current = window.setInterval(() => {
      if (chargeStart.current === null) return;
      latest.current.onPower(chargePower(Date.now() - chargeStart.current));
    }, CHARGE_FRAME_MS);
  };
  const releaseCharge = () => {
    if (chargeStart.current === null) return;
    const p = chargePower(Date.now() - chargeStart.current);
    stopCharge();
    suppressClickUntil.current = Date.now() + 400;
    latest.current.onPower(p);
    latest.current.onFire(p);
  };

  // ── 키보드 ────────────────────────────────────────────────────
  useEffect(() => {
    if (!enabled) return;
    const down = (e: KeyboardEvent) => {
      if (typingTarget(e.target) || e.altKey || e.ctrlKey || e.metaKey) return;
      const k = e.key;
      if (k === 'ArrowLeft' || k === 'ArrowRight') {
        e.preventDefault();
        if (!e.repeat) {
          setAim(0, 0);
          setWalk(k === 'ArrowLeft' ? -1 : 1);
        }
      } else if (k === 'ArrowUp' || k === 'ArrowDown') {
        e.preventDefault();
        if (!e.repeat) {
          setWalk(0);
          setAim(k === 'ArrowUp' ? 1 : -1, KEY_AIM_STRENGTH);
        }
      } else if (k === ' ' || k === 'Spacebar') {
        // 다른 단추에 초점이 있으면 그 단추를 누르게 둔다(발사 단추만 예외).
        const t = e.target instanceof HTMLElement ? e.target : null;
        if (t?.closest('button') && !t.closest('.ar-fire')) return;
        e.preventDefault();
        if (!e.repeat) startCharge();
      }
    };
    const up = (e: KeyboardEvent) => {
      const k = e.key;
      if (k === 'ArrowLeft' || k === 'ArrowRight') {
        if (drive.current.walkDir === (k === 'ArrowLeft' ? -1 : 1)) setWalk(0);
      } else if (k === 'ArrowUp' || k === 'ArrowDown') {
        if (drive.current.aimDir === (k === 'ArrowUp' ? 1 : -1)) setAim(0, 0);
      } else if (k === ' ' || k === 'Spacebar') {
        if (chargeStart.current !== null) {
          e.preventDefault();
          releaseCharge();
        }
      }
    };
    const blur = () => {
      releaseDrive();
      stopCharge();
    };
    window.addEventListener('keydown', down);
    window.addEventListener('keyup', up);
    window.addEventListener('blur', blur);
    return () => {
      window.removeEventListener('keydown', down);
      window.removeEventListener('keyup', up);
      window.removeEventListener('blur', blur);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled]);

  useEffect(
    () => () => {
      stopTimer();
      if (chargeTimer.current !== null) window.clearInterval(chargeTimer.current);
    },
    [],
  );
  useEffect(() => {
    if (!enabled) {
      releaseDrive();
      stopCharge();
      stopTimer();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled]);

  const side = facingOf(angle, facing.current);
  const rel = elevation(angle);
  const fuelPct = Math.round((100 * fuelLeft) / Math.max(1, fuelMax));

  return (
    <div className="ar-pad" aria-label="조작">
      <div className="ar-pad-left">
        <div
          ref={baseRef}
          className={`ar-stick${stickActive ? ' ar-stick-active' : ''}${enabled ? '' : ' ar-stick-off'}`}
          role="group"
          tabIndex={-1}
          aria-label="조이스틱 — 좌우로 밀면 걷기, 위아래로 밀면 포신 각도 (키보드 ← → ↑ ↓)"
          aria-disabled={!enabled}
          onPointerDown={(e) => {
            if (!enabled || stickPointer.current !== null) return;
            e.preventDefault();
            stickPointer.current = e.pointerId;
            capture(e);
            setStickActive(true);
            stickFrom(e);
          }}
          onPointerMove={(e) => {
            if (stickPointer.current === e.pointerId) stickFrom(e);
          }}
          onPointerUp={(e) => {
            if (stickPointer.current === e.pointerId) stickEnd();
          }}
          onPointerCancel={(e) => {
            if (stickPointer.current === e.pointerId) stickEnd();
          }}
          onLostPointerCapture={(e) => {
            if (stickPointer.current === e.pointerId) stickEnd();
          }}
        >
          <span className="ar-stick-arrow ar-stick-up" aria-hidden="true">
            ▲
          </span>
          <span className="ar-stick-arrow ar-stick-down" aria-hidden="true">
            ▼
          </span>
          <span className="ar-stick-arrow ar-stick-left" aria-hidden="true">
            ◀
          </span>
          <span className="ar-stick-arrow ar-stick-right" aria-hidden="true">
            ▶
          </span>
          <span ref={knobRef} className="ar-stick-knob" aria-hidden="true">
            {side === 1 ? '' : '◂'}
            {rel}°{side === 1 ? '▸' : ''}
          </span>
        </div>
        <span className="ar-fuel" role="meter" aria-valuemin={0} aria-valuemax={fuelMax} aria-valuenow={fuelLeft} aria-label="이동 연료" title={`이동 연료 ${fuelLeft}/${fuelMax}`}>
          <span className="ar-fuel-bar" style={{ width: `${fuelPct}%` }} />
        </span>
      </div>

      <div className="ar-pad-mid">
        <output className="ar-sr-only" aria-live="off">
          각도 {angle}°
        </output>
        {delayCost !== null && (
          <span className="ar-delay-cost" title="이번 차례에 쌓일 딜레이 — 적게 쌓을수록 다음 차례가 빨리 온다">
            ⏱ +{delayCost}
          </span>
        )}
        <button
          type="button"
          className="ar-btn ar-btn-ghost ar-skip"
          disabled={!enabled}
          onClick={onSkip}
          title={skipDelay !== null ? `넘기기 — 딜레이 +${skipDelay}` : undefined}
        >
          넘기기
        </button>
      </div>

      <div className="ar-pad-right">
        <button
          type="button"
          className={`ar-fire${charging ? ' ar-fire-charging' : ''}`}
          disabled={!enabled}
          style={{ ['--ar-power' as string]: `${power}` }}
          aria-label={`발사 — 누르고 있으면 힘이 오가고 떼면 쏜다 (Space). 지금 힘 ${power}`}
          onPointerDown={(e) => {
            if (!enabled) return;
            e.preventDefault();
            capture(e);
            startCharge();
          }}
          onPointerUp={releaseCharge}
          onPointerCancel={stopCharge}
          onContextMenu={(e) => e.preventDefault()}
          onClick={() => {
            // 보조기기·Enter — 지금 힘으로 바로.
            if (!enabled || Date.now() < suppressClickUntil.current || chargeStart.current !== null) return;
            onFire(power);
          }}
        >
          <span className="ar-fire-ring" aria-hidden="true" />
          {lastPower !== null && (
            <span className="ar-fire-last" style={{ ['--ar-last' as string]: `${lastPower}` }} aria-hidden="true" />
          )}
          <span className="ar-fire-label" aria-hidden="true">
            {charging ? power : '발사'}
            <small>{charging ? '떼면 발사' : `힘 ${power}`}</small>
          </span>
        </button>
      </div>
    </div>
  );
}
