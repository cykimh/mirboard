import { useEffect, useRef } from 'react';
import { ITEM_HINTS, ITEM_NAMES, SHELL_NAMES, type Item, type Shell } from '@/types/artillery';

const ITEM_ICONS: Record<Item, string> = { WATERING_CAN: '💧', PINWHEEL: '🌀', TWIN_SEED: '🌱' };

/** 힘 모으기 — 누르고 있는 동안 0→100→0 을 이 주기(ms)로 오간다. */
export const CHARGE_PERIOD_MS = 2400;
/** 이동 버튼을 누르고 있으면 이 간격(ms)마다 한 칸. */
export const MOVE_STEP_MS = 40;

/** 누른 시간 → 힘(삼각파, 0~100). */
export function chargePower(heldMs: number): number {
  const phase = (heldMs % CHARGE_PERIOD_MS) / CHARGE_PERIOD_MS;
  return Math.round((phase < 0.5 ? phase * 2 : (1 - phase) * 2) * 100);
}

interface Props {
  angle: number;
  power: number;
  lastPower: number | null;
  shell: Shell;
  /** 고를 수 있는 탄과 남은 수(−1 = 무제한). 콩알·호박탄·내 유닛 특수탄. */
  shells: { shell: Shell; count: number }[];
  /** 남은 아이템과 이번 발에 끼운 것(한 차례 하나, D-224). */
  items: Item[];
  item: Item | null;
  onItem: (item: Item | null) => void;
  fuelLeft: number;
  /** 내 유닛의 연료 가득(새싹 100·도토리 70·민들레 130). */
  fuelMax?: number;
  moveDx: number;
  enabled: boolean;
  onAngle: (angle: number) => void;
  onPower: (power: number) => void;
  onShell: (shell: Shell) => void;
  /** 한 칸 이동(미리보기) — 더 못 가면 false. */
  onStep: (dir: -1 | 1) => boolean;
  onResetMove: () => void;
  onFire: (power: number) => void;
  onSkip: () => void;
}

/**
 * 조작부 (`docs/plans/artillery.md` §8.3) — 휴대폰 터치 우선. 각도는 전장을 끌어서 정하고 여기서 1°씩 다듬는다. 힘은 슬라이더로 정해
 * [발사]를 누르거나, [꾹 눌러 쏘기]를 누르고 있다가 원하는 힘에서 뗀다. 이동은 버튼을 누르고 있는 동안 걷는다(서버에는 발사와 함께
 * 한 번에 간다 — 이동만으로는 아무것도 안 보낸다).
 */
export function ArtilleryControls({
  angle,
  power,
  lastPower,
  shell,
  shells,
  items,
  item,
  onItem,
  fuelLeft,
  fuelMax = 100,
  moveDx,
  enabled,
  onAngle,
  onPower,
  onShell,
  onStep,
  onResetMove,
  onFire,
  onSkip,
}: Props) {
  // 이동 길게 누르기
  const moveTimer = useRef<number | null>(null);
  const stopMove = () => {
    if (moveTimer.current !== null) {
      window.clearInterval(moveTimer.current);
      moveTimer.current = null;
    }
  };
  const startMove = (dir: -1 | 1) => {
    stopMove();
    if (!onStep(dir)) return;
    moveTimer.current = window.setInterval(() => {
      if (!onStep(dir)) stopMove();
    }, MOVE_STEP_MS);
  };

  // 꾹 눌러 쏘기
  const chargeStart = useRef<number | null>(null);
  const chargeRaf = useRef(0);
  const stopCharge = () => {
    cancelAnimationFrame(chargeRaf.current);
    chargeStart.current = null;
  };
  const startCharge = () => {
    if (!enabled) return;
    chargeStart.current = performance.now();
    const tick = () => {
      if (chargeStart.current === null) return;
      onPower(chargePower(performance.now() - chargeStart.current));
      chargeRaf.current = requestAnimationFrame(tick);
    };
    chargeRaf.current = requestAnimationFrame(tick);
  };
  const releaseCharge = () => {
    if (chargeStart.current === null) return;
    const p = chargePower(performance.now() - chargeStart.current);
    stopCharge();
    onPower(p);
    onFire(p);
  };
  useEffect(
    () => () => {
      stopMove();
      stopCharge();
    },
    [],
  );
  useEffect(() => {
    if (!enabled) {
      stopMove();
      stopCharge();
    }
  }, [enabled]);

  const holdProps = (dir: -1 | 1) => ({
    onPointerDown: (e: React.PointerEvent) => {
      if (!enabled) return;
      e.preventDefault();
      startMove(dir);
    },
    onPointerUp: stopMove,
    onPointerLeave: stopMove,
    onPointerCancel: stopMove,
    onKeyDown: (e: React.KeyboardEvent) => {
      if (enabled && (e.key === 'Enter' || e.key === ' ')) {
        e.preventDefault();
        onStep(dir);
      }
    },
  });

  return (
    <div className="ar-controls" aria-label="조작">
      <div className="ar-row">
        <span className="ar-label">각도</span>
        <button type="button" className="ar-btn" disabled={!enabled} onClick={() => onAngle(Math.min(180, angle + 1))} aria-label="각도 1도 왼쪽으로">
          ↺
        </button>
        <output className="ar-value" aria-live="off">
          {angle}°
        </output>
        <button type="button" className="ar-btn" disabled={!enabled} onClick={() => onAngle(Math.max(0, angle - 1))} aria-label="각도 1도 오른쪽으로">
          ↻
        </button>
      </div>
      <div className="ar-row">
        <span className="ar-label">이동</span>
        <button type="button" className="ar-btn" disabled={!enabled} aria-label="왼쪽으로 걷기(누르고 있기)" {...holdProps(-1)}>
          ◀
        </button>
        <button type="button" className="ar-btn" disabled={!enabled} aria-label="오른쪽으로 걷기(누르고 있기)" {...holdProps(1)}>
          ▶
        </button>
        <span className="ar-fuel" title={`이동 연료 ${fuelLeft}/${fuelMax}`}>
          <span className="ar-fuel-bar" style={{ width: `${Math.round((100 * fuelLeft) / Math.max(1, fuelMax))}%` }} />
        </span>
        {moveDx !== 0 && (
          <button type="button" className="ar-btn ar-btn-small" disabled={!enabled} onClick={onResetMove}>
            제자리
          </button>
        )}
      </div>
      <div className="ar-row">
        <label className="ar-label" htmlFor="ar-power">
          힘
        </label>
        <span className="ar-power">
          <input
            id="ar-power"
            type="range"
            min={0}
            max={100}
            value={power}
            disabled={!enabled}
            onChange={(e) => onPower(Number(e.target.value))}
          />
          {lastPower !== null && (
            <span className="ar-power-last" style={{ left: `${lastPower}%` }} title={`지난 힘 ${lastPower}`} aria-hidden="true" />
          )}
        </span>
        <output className="ar-value">{power}</output>
      </div>
      <div className="ar-row">
        <div className="ar-shells" role="radiogroup" aria-label="탄">
          {shells.map(({ shell: sh, count }) => (
            <button
              key={sh}
              type="button"
              role="radio"
              aria-checked={shell === sh}
              className={`ar-chip${shell === sh ? ' ar-chip-on' : ''}`}
              disabled={!enabled || count === 0}
              onClick={() => onShell(sh)}
            >
              {SHELL_NAMES[sh]}
              {count >= 0 ? ` ×${count}` : ''}
            </button>
          ))}
        </div>
      </div>
      <div className="ar-row">
        <div className="ar-items" role="group" aria-label="아이템 — 한 차례 하나">
          {(['WATERING_CAN', 'PINWHEEL', 'TWIN_SEED'] as Item[]).map((it) => {
            const left = items.includes(it);
            return (
              <button
                key={it}
                type="button"
                aria-pressed={item === it}
                className={`ar-chip ar-item${item === it ? ' ar-chip-on' : ''}`}
                disabled={!enabled || !left}
                title={`${ITEM_NAMES[it]} — ${ITEM_HINTS[it]}${left ? '' : ' (씀)'}`}
                onClick={() => onItem(item === it ? null : it)}
              >
                {ITEM_ICONS[it]} {ITEM_NAMES[it]}
              </button>
            );
          })}
        </div>
        <span className="ar-actions">
          <button type="button" className="ar-btn ar-btn-ghost" disabled={!enabled} onClick={onSkip}>
            넘기기
          </button>
          <button
            type="button"
            className="ar-btn ar-btn-charge"
            disabled={!enabled}
            onPointerDown={(e) => {
              e.preventDefault();
              startCharge();
            }}
            onPointerUp={releaseCharge}
            onPointerLeave={releaseCharge}
            onPointerCancel={stopCharge}
            aria-label="꾹 눌러 힘을 모았다가 떼면 발사"
          >
            꾹 눌러 쏘기
          </button>
          <button type="button" className="ar-btn ar-btn-fire" disabled={!enabled} onClick={() => onFire(power)}>
            발사
          </button>
        </span>
      </div>
    </div>
  );
}
