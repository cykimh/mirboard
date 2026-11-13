import { useEffect, useId, useRef, useState, type CSSProperties } from 'react';
import { MAX_ROLLS, type Dice, type Held } from '@/types/yacht';
import { YachtDie, YachtDieCube } from './YachtDie';
import type { MadeTier } from './yachtLabels';
import { useDeviceShake } from './useDeviceShake';

/** D-208 — 내 굴림이 족보로 멈췄다(게임판이 굴림 하나에 한 번만 넘긴다). */
export interface TrayMade {
  /** 굴림 신호 값 — 꼬리표·고리의 key(같은 굴림에는 다시 돌지 않는다). */
  nonce: number;
  tier: MadeTier;
  /** 꼬리표 글("풀 하우스!"). */
  label: string;
  /** 금빛 고리를 두를 주사위 자리. */
  indices: number[];
  /** 꼬리표가 떠 있는 시간(ms) — CSS 가 이 길이로 나타났다 사라진다. */
  ms: number;
}

/**
 * D-204 — 주사위를 쏟는 가죽 컵(직접 그린 SVG, 꾸밈). 굴리는 동안만 판 오른쪽 위에 기울어 나왔다 물러난다.
 * D-268 — 좌석 명패에도 작게 뜬다(남이 흔드는 중). 한 화면에 여럿이 떠도 그라데이션 id 가 겹치지 않게 컵마다 따로 짓는다.
 */
export function DiceCup() {
  const id = useId().replace(/[^a-zA-Z0-9_-]/g, '');
  const leather = `yt-cup-leather-${id}`;
  const brass = `yt-cup-brass-${id}`;
  return (
    <svg viewBox="0 0 64 72" focusable="false">
      <defs>
        <linearGradient id={leather} x1="0" y1="0" x2="1" y2="0">
          <stop offset="0" stopColor="#3b1820" />
          <stop offset=".35" stopColor="#7a2b3a" />
          <stop offset=".7" stopColor="#5a1f2b" />
          <stop offset="1" stopColor="#2c1118" />
        </linearGradient>
        <linearGradient id={brass} x1="0" y1="0" x2="1" y2="0">
          <stop offset="0" stopColor="#8f6f2c" />
          <stop offset=".4" stopColor="#f6e2a4" />
          <stop offset="1" stopColor="#a8842f" />
        </linearGradient>
      </defs>
      <path d="M8 12 L14 64 Q32 71 50 64 L56 12 Z" fill={`url(#${leather})`} stroke="#1d0a10" strokeWidth="1.5" />
      <path d="M12.5 50 Q32 57 51.5 50" fill="none" stroke="#e9c86a" strokeOpacity=".7" strokeWidth="1" strokeDasharray="3 3" />
      <path d="M10.5 30 Q32 36 53.5 30" fill="none" stroke="#e9c86a" strokeOpacity=".45" strokeWidth="1" strokeDasharray="3 3" />
      <ellipse cx="32" cy="12" rx="24.5" ry="7.5" fill="#1a0a0e" stroke={`url(#${brass})`} strokeWidth="3.5" />
      <path d="M17 20 Q15 40 19 58" fill="none" stroke="#fff" strokeOpacity=".22" strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
}

/** 굴림 애니메이션 길이 — 봇 템포(700ms) 안에 끝나야 한다. */
export const ROLL_ANIMATION_MS = 680;
/**
 * 주사위마다 엇갈리는 간격·한 개가 쏟아져 멈추기까지(D-203·D-204). `4 × 45 + 500 = 680` — 마지막 주사위도 `ROLL_ANIMATION_MS` 안에 멈춘다.
 * 실제 값은 CSS(`20-yacht-table.css` 의 `yt-salon-throw`·`--yt-o * 45ms`)에 있고 `yachtCssNamespace.test` 가 둘을 맞춰 본다.
 */
export const ROLL_STAGGER_MS = 45;
export const ROLL_DIE_MS = 500;
/**
 * D-204 — 착지 순서(자리 → 몇 번째로 떨어지나). 왼쪽부터 줄지어 떨어지면 기계 같아서 가운데부터 엇갈린다. 0~4 를 한 번씩 써야
 * 마지막 주사위가 `4 × ROLL_STAGGER_MS` 뒤에 출발한다.
 */
export const LAND_ORDER = [2, 0, 3, 1, 4] as const;
/**
 * D-268 — [굴리기]를 이만큼 누르고 있으면 컵을 흔들기 시작한다. 그 전에 떼면 그냥 굴린다(톡 누르기). 어느 쪽이든 떼는 순간 한 번 굴린다.
 */
export const SHAKE_HOLD_MS = 200;
/** D-268 — 누름을 뗀 직후 브라우저가 이어서 보내는 click 을 이 시간 안에서 한 번 삼킨다(같은 누름으로 두 번 굴리지 않게). */
export const CLICK_SWALLOW_MS = 350;

interface Props {
  dice: Dice | null;
  held: Held;
  rolls: number;
  /** DICE_ROLLED 마다 오르는 신호 — 바뀌면 굴림 애니메이션. */
  rollNonce: number;
  /** 내 차례면 홀드를 바꿀 수 있다(굴림 1·2번째 뒤). */
  canHold: boolean;
  canRoll: boolean;
  animate: boolean;
  onToggle?: (index: number) => void;
  onRoll?: () => void;
  /** 차례인 사람 이름(남의 차례 안내). */
  turnName: string | null;
  /** D-202 — 굴리기 버튼이 없을 때의 안내를 바꾼다(동시 진행의 기다림). */
  note?: string;
  /**
   * D-205 — 누구의 판을 비추나(남의 판 구경). 이 값이 바뀌어 `rollNonce` 가 달라진 것은 굴림이 아니다 — 구르지 않는다.
   */
  viewKey?: number;
  /** D-205 — 남의 판을 보는 중이면 안내 옆에 돌아가는 버튼. */
  onBack?: () => void;
  backLabel?: string;
  /** 아직 내 차례가 남았다 — 돌아가는 버튼을 굴리기 버튼처럼 눈에 띄게. */
  backUrgent?: boolean;
  /** D-208 — 족보로 멈춘 내 굴림(없으면 null). 고리·꼬리표는 `animate` 가 꺼져도 정지 상태로 뜬다. */
  made?: TrayMade | null;
  /** D-264 — 가운데 판이 굴림 신호를 받은 순간(굴림 하나에 한 번, 판을 바꿔 볼 때는 아님). 연출을 꺼도 부른다 — 소리용. */
  onRollSound?: () => void;
  /** D-264 — 컵을 흔들기 시작했다. 돌려준 함수를 흔들기가 끝날 때 부른다(달그락 소리 끄기). */
  onShakeSound?: () => (() => void) | void;
  /** D-264 — 휴대폰 흔들기를 켜 두었다(게임판 ⋮ 메뉴). 굴릴 수 있을 때만 움직임을 듣는다. */
  motionShake?: boolean;
  /** D-268 — 내가 흔들기 시작했다(true)·멈췄다(false). 게임판이 남들에게 알린다(미리보기). 시작마다 멈춤이 꼭 한 번 따라온다. */
  onShakeChange?: (shaking: boolean) => void;
  /** D-268 — 이 판의 주인(남)이 지금 컵을 흔드는 중이다 — 컵·주사위 떨림만 보인다(소리·버튼 없음). */
  remoteShake?: boolean;
}

/**
 * 주사위 판(설계 §7.2). 홀드한 주사위는 아래로 내려앉고 황동 테 + "고정" 명패. 내 차례면 탭으로 홀드, 남의 차례면 보기만(DICE_HELD 실시간).
 * 실제 `<button>` 이라 키보드·보조기기로도 홀드한다. `data-yt-quiet` — 게임판의 일반 버튼 딸깍(D-268)을 내지 않는 버튼(제 소리가 따로 있다). 굴리는 동안 홀드 안 한 주사위만 정육면체(`YachtDieCube`)로 컵에서 쏟아져 바닥에 두 번
 * 튀고 멈춘다(D-204) — 앞면이 서버가 준 눈이고 `aria-label` 도 늘 그 눈이다. 연출은 조작을 막지 않는다(굴러오는 중에도 눌린다).
 */
export function DiceTray({
  dice,
  held,
  rolls,
  rollNonce,
  canHold,
  canRoll,
  animate,
  onToggle,
  onRoll,
  turnName,
  note,
  viewKey = -1,
  onBack,
  backLabel = '내 판으로',
  backUrgent = false,
  made = null,
  onRollSound,
  onShakeSound,
  motionShake = false,
  onShakeChange,
  remoteShake = false,
}: Props) {
  const [rolling, setRolling] = useState(false);
  const seenView = useRef(viewKey);
  // D-264 — 굴림 소리는 신호가 바뀔 때만(처음 그릴 때·연출 설정을 바꿀 때·판을 바꿔 볼 때는 울리지 않는다).
  const soundedNonce = useRef(rollNonce);
  const rollSound = useRef(onRollSound);
  rollSound.current = onRollSound;
  useEffect(() => {
    const switched = seenView.current !== viewKey;
    seenView.current = viewKey;
    if (switched) {
      soundedNonce.current = rollNonce;
      setRolling(false);
      return;
    }
    if (rollNonce !== soundedNonce.current) {
      soundedNonce.current = rollNonce;
      // 새 굴림이 왔다 — 아직 이어지는 흔들기는 그 굴림 전에 시작한 것이라 굴리지 않고 걷는다(이중 ROLL 방지).
      // 누르고 있는 중이었으면 그 누름도 버린다 — 떼어도 굴리지 않는다.
      cancelPress(true);
      endShake(false);
      if (rollNonce > 0) rollSound.current?.();
    }
    if (rollNonce === 0 || !animate) return;
    setRolling(true);
    const timer = window.setTimeout(() => setRolling(false), ROLL_ANIMATION_MS);
    return () => window.clearTimeout(timer);
  }, [rollNonce, animate, viewKey]);

  // D-264·D-268 — 흔들어 굴리기. [굴리기]를 꾹 누르고 있는 동안(또는 휴대폰을 흔드는 동안) 컵이 제자리에서 달그락거리고, 떼면(멎으면)
  // `onRoll` 을 한 번 부른다. 톡 누르면 흔들지 않고 바로 굴린다 — 버튼은 하나다(D-268, 예전의 따로 있던 [흔들기]를 합쳤다).
  // 흔들림은 화면·소리뿐이고 눈은 서버가 정한다. `onRoll` 이 없으면(남의 차례·관전·남의 판 구경) 버튼 자체가 없고, 흔드는 중에 굴릴 수
  // 없게 되면(시간 초과·끊김) 굴리지 않고 걷는다.
  const [shake, setShake] = useState<'press' | 'motion' | null>(null);
  const shakeNow = useRef<{ source: 'press' | 'motion'; stop: (() => void) | void } | null>(null);
  const rollable = canRoll && onRoll !== undefined;
  const live = useRef({ rollable, onRoll, onShakeSound, onShakeChange });
  live.current = { rollable, onRoll, onShakeSound, onShakeChange };
  const beginShake = (source: 'press' | 'motion') => {
    if (shakeNow.current || !live.current.rollable) return;
    shakeNow.current = { source, stop: live.current.onShakeSound?.() };
    setShake(source);
    live.current.onShakeChange?.(true);
  };
  // `source` 를 주면 그 갈래가 시작한 흔들기만 끝낸다 — 버튼을 누른 채 휴대폰을 흔들다 잠깐 멎어도(움직임 갈래) 누름 흔들기를
  // 굴리지 않는다(한 번 던지는데 ROLL 이 두 번 가던 결함, 리뷰 지적).
  const endShake = (roll: boolean, source?: 'press' | 'motion') => {
    const now = shakeNow.current;
    if (!now) return;
    if (source && now.source !== source) return;
    shakeNow.current = null;
    now.stop?.();
    setShake(null);
    live.current.onShakeChange?.(false);
    if (roll && live.current.rollable) live.current.onRoll?.();
  };

  // D-268 — 버튼 누름 한 번(포인터 또는 키보드). 누른 뒤 `SHAKE_HOLD_MS` 가 지나면 흔들기가 되고, 떼는 순간 정확히 한 번 굴린다.
  // 브라우저는 떼고 나서 click 도 보낸다 — 그 click 은 삼킨다. 누름 없이 온 click(보조기기·스크립트)은 그대로 한 번 굴린다.
  // `cancelled` — 누르는 사이에 새 굴림이 오거나 굴릴 수 없게 됐다: 떼어도 굴리지 않는다(뒤따르는 click 도 삼킨다).
  const press = useRef<{ timer: number; cancelled: boolean } | null>(null);
  const swallow = useRef(0);
  const disarmSwallow = () => {
    window.clearTimeout(swallow.current);
    swallow.current = 0;
  };
  const startPress = () => {
    if (press.current && !press.current.cancelled) return;
    if (press.current) window.clearTimeout(press.current.timer);
    press.current = null;
    if (!live.current.rollable) return;
    disarmSwallow();
    const p = { cancelled: false, timer: 0 };
    p.timer = window.setTimeout(() => {
      if (press.current === p && !p.cancelled) beginShake('press');
    }, SHAKE_HOLD_MS);
    press.current = p;
  };
  /** `keep` — 손은 아직 누르고 있다: 뗄 때까지 기억해 두었다가 굴리지 않고 넘긴다. 아니면(취소·초점 잃음) 바로 잊는다. */
  function cancelPress(keep: boolean) {
    const p = press.current;
    if (!p) return;
    window.clearTimeout(p.timer);
    if (keep) p.cancelled = true;
    else press.current = null;
    endShake(false, 'press');
  }
  const finishPress = () => {
    const p = press.current;
    if (!p) return;
    press.current = null;
    window.clearTimeout(p.timer);
    disarmSwallow();
    swallow.current = window.setTimeout(() => {
      swallow.current = 0;
    }, CLICK_SWALLOW_MS);
    if (p.cancelled) return;
    // 어느 갈래로 흔들고 있었든 걷고(휴대폰 흔들기가 겹쳐 있어도 그쪽은 굴리지 않는다) 한 번만 굴린다.
    endShake(false);
    if (live.current.rollable) live.current.onRoll?.();
  };
  const clickRoll = () => {
    if (swallow.current) {
      disarmSwallow();
      return;
    }
    const p = press.current;
    // 누르는 중에 온 click(엔터는 누르는 순간·누르고 있는 동안 click 을 낸다) — 떼는 순간이 굴림이다.
    if (p && !p.cancelled) return;
    press.current = null;
    // 휴대폰을 흔드는 중에 눌렀으면 그 흔들기는 굴리지 않고 걷는다(멎으면서 한 번 더 가지 않게).
    endShake(false);
    onRoll?.();
  };
  useEffect(() => {
    if (!rollable) {
      cancelPress(true);
      endShake(false);
    }
    // cancelPress·endShake 는 ref 만 읽는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rollable]);
  useEffect(
    () => () => {
      if (press.current) window.clearTimeout(press.current.timer);
      press.current = null;
      window.clearTimeout(swallow.current);
      if (shakeNow.current) {
        shakeNow.current.stop?.();
        shakeNow.current = null;
        live.current.onShakeChange?.(false);
      }
    },
    [],
  );
  // 휴대폰 흔들기 — ⋮ 메뉴에서 켜 둔 사람만, 굴릴 수 있을 때만 듣는다.
  useDeviceShake({
    enabled: motionShake && rollable,
    onStart: () => beginShake('motion'),
    onStop: (roll) => endShake(roll, 'motion'),
  });

  const left = MAX_ROLLS - rolls;
  // D-203 — 다섯 개가 같게 멈추면 가벼운 반짝임(본 축하는 요트 칸에 기록할 때).
  const allSame = dice !== null && rolls > 0 && !rolling && dice.every((d) => d === dice[0]);
  // D-208 — 구르는 동안에는 띄우지 않는다(멈춘 뒤의 알림이다).
  const shown = made && dice !== null && !rolling ? made : null;
  // D-268 — 내가 흔드는 중이거나, 이 판의 주인(남)이 흔드는 중.
  const shaking = shake !== null || remoteShake;
  return (
    <section className="yt-tray" aria-label="주사위">
      <div
        className={`yt-dice${shaking ? ` yt-dice-shaking${animate ? ' yt-dice-shaking-live' : ''}` : ''}${allSame ? ' yt-dice-same' : ''}${shown ? ` yt-dice-made yt-dice-made-${shown.tier}${animate ? ' yt-dice-made-live' : ''}` : ''}`}
        role="group"
        aria-label={dice ? `주사위 ${dice.join(', ')}` : '아직 굴리지 않음'}
      >
        {Array.from({ length: 5 }, (_, i) => {
          const face = dice ? dice[i] : null;
          const isHeld = held[i];
          // 빈 자리는 구르지 않는다 — 굴리자마자 기록해 판이 비어도 빈 칸이 날아다니지 않게.
          const shaking = rolling && !isHeld && face !== null;
          // D-204 — 굴리는 동안 고정한 주사위는 제자리에서 금빛이 훑고 고리가 퍼진다(잠김).
          const locked = rolling && isHeld && face !== null;
          const inMade = shown !== null && shown.indices.includes(i);
          return (
            <button
              type="button"
              key={i}
              className={`yt-die${isHeld ? ' yt-die-held' : ''}${shaking ? ' yt-die-rolling' : ''}${face === null ? ' yt-die-empty' : ''}${locked ? ' yt-die-lock' : ''}${inMade ? ' yt-die-made' : ''}`}
              style={{ ['--yt-i' as string]: i, ['--yt-o' as string]: LAND_ORDER[i] } as CSSProperties}
              disabled={!canHold || face === null}
              aria-pressed={isHeld}
              data-yt-quiet=""
              aria-label={face === null ? `주사위 ${i + 1} 비어 있음` : `주사위 ${i + 1}: ${face}${isHeld ? ', 고정' : ''}`}
              onClick={() => onToggle?.(i)}
            >
              {/* key — 굴림이 겹쳐 와도(봇 연속 굴림) 연출이 처음부터 다시 돈다. */}
              <span aria-hidden="true" className="yt-die-shadow" key={`s${rollNonce}`} />
              {shaking && <span aria-hidden="true" className="yt-die-impact" />}
              <span aria-hidden="true" className="yt-die-face" key={`f${rollNonce}`}>
                {shaking && face !== null ? <YachtDieCube face={face} /> : <YachtDie face={face} />}
                {isHeld && face !== null && (
                  <>
                    <span className="yt-die-sheen" />
                    <span className="yt-die-lockring" />
                  </>
                )}
                {inMade && <span className="yt-die-madering" key={`m${shown.nonce}`} />}
              </span>
              {isHeld && <span className="yt-die-tag">고정</span>}
            </button>
          );
        })}
        {shown && (
          <span
            className={`yt-made-tag yt-made-tag-${shown.tier}`}
            key={`made${shown.nonce}`}
            aria-hidden="true"
            style={{ ['--yt-made-ms' as string]: `${shown.ms}ms` } as CSSProperties}
          >
            {shown.label}
          </span>
        )}
        {rolling && dice && (
          <span className="yt-cup" aria-hidden="true" key={rollNonce}>
            <DiceCup />
          </span>
        )}
        {/* D-264 — 흔드는 동안 컵이 제자리에서 달그락거린다(쏟는 컵과 같은 자리). */}
        {shaking && !rolling && (
          <span className="yt-cup yt-cup-shake" aria-hidden="true" key="shake">
            <DiceCup />
          </span>
        )}
      </div>
      <div className="yt-tray-actions">
        {onRoll ? (
          // D-268 — 버튼 하나: 톡 누르면 바로 굴리고, 꾹 누르면 컵을 흔들다가 떼는 순간 굴린다. 남은 굴림 수는 둥근 숫자 표로.
          // 길게 눌러도 글자 선택·길게 누르기 메뉴가 뜨지 않게 한다(CSS + contextmenu 막기).
          <button
            type="button"
            className={`yt-roll yt-roll-main${shake === 'press' ? ' yt-roll-shaking' : ''}`}
            disabled={!canRoll}
            aria-label={`굴리기, ${left}번 남음`}
            title="누르면 굴려요 · 꾹 누르면 컵을 흔들다가 떼면 굴러요"
            data-yt-quiet=""
            onPointerDown={(e) => {
              if (e.button) return;
              try {
                e.currentTarget.setPointerCapture?.(e.pointerId);
              } catch {
                // 포인터 잡기 실패 — 그대로 진행.
              }
              startPress();
            }}
            onPointerUp={finishPress}
            onPointerCancel={() => cancelPress(false)}
            onContextMenu={(e) => e.preventDefault()}
            onKeyDown={(e) => {
              if ((e.key === ' ' || e.key === 'Enter') && !e.repeat) startPress();
            }}
            onKeyUp={(e) => {
              if (e.key === ' ' || e.key === 'Enter') finishPress();
            }}
            onBlur={() => cancelPress(false)}
            onClick={clickRoll}
          >
            <span className="yt-roll-label">굴리기</span>
            <span className="yt-roll-left" aria-hidden="true">
              {left}
            </span>
          </button>
        ) : (
          <span className="yt-tray-note">
            {note ?? (turnName ? `${turnName} 차례 · 굴림 ${rolls}/${MAX_ROLLS}` : '대기 중')}
          </span>
        )}
        {onBack && (
          <button type="button" className={`yt-back${backUrgent ? ' yt-back-urgent' : ''}`} onClick={onBack}>
            {backLabel}
          </button>
        )}
      </div>
    </section>
  );
}
