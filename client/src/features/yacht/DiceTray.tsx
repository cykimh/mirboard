import { useEffect, useState, type CSSProperties } from 'react';
import { MAX_ROLLS, type Dice, type Held } from '@/types/yacht';
import { YachtDie, YachtDieCube } from './YachtDie';

/** D-204 — 주사위를 쏟는 가죽 컵(직접 그린 SVG, 꾸밈). 굴리는 동안만 판 오른쪽 위에 기울어 나왔다 물러난다. */
function DiceCup() {
  return (
    <svg viewBox="0 0 64 72" focusable="false">
      <defs>
        <linearGradient id="yt-cup-leather" x1="0" y1="0" x2="1" y2="0">
          <stop offset="0" stopColor="#3b1820" />
          <stop offset=".35" stopColor="#7a2b3a" />
          <stop offset=".7" stopColor="#5a1f2b" />
          <stop offset="1" stopColor="#2c1118" />
        </linearGradient>
        <linearGradient id="yt-cup-brass" x1="0" y1="0" x2="1" y2="0">
          <stop offset="0" stopColor="#8f6f2c" />
          <stop offset=".4" stopColor="#f6e2a4" />
          <stop offset="1" stopColor="#a8842f" />
        </linearGradient>
      </defs>
      <path d="M8 12 L14 64 Q32 71 50 64 L56 12 Z" fill="url(#yt-cup-leather)" stroke="#1d0a10" strokeWidth="1.5" />
      <path d="M12.5 50 Q32 57 51.5 50" fill="none" stroke="#e9c86a" strokeOpacity=".7" strokeWidth="1" strokeDasharray="3 3" />
      <path d="M10.5 30 Q32 36 53.5 30" fill="none" stroke="#e9c86a" strokeOpacity=".45" strokeWidth="1" strokeDasharray="3 3" />
      <ellipse cx="32" cy="12" rx="24.5" ry="7.5" fill="#1a0a0e" stroke="url(#yt-cup-brass)" strokeWidth="3.5" />
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
}

/**
 * 주사위 판(설계 §7.2). 홀드한 주사위는 아래로 내려앉고 황동 테 + "고정" 명패. 내 차례면 탭으로 홀드, 남의 차례면 보기만(DICE_HELD 실시간).
 * 실제 `<button>` 이라 키보드·보조기기로도 홀드한다. 굴리는 동안 홀드 안 한 주사위만 정육면체(`YachtDieCube`)로 컵에서 쏟아져 바닥에 두 번
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
}: Props) {
  const [rolling, setRolling] = useState(false);
  useEffect(() => {
    if (rollNonce === 0 || !animate) return;
    setRolling(true);
    const timer = window.setTimeout(() => setRolling(false), ROLL_ANIMATION_MS);
    return () => window.clearTimeout(timer);
  }, [rollNonce, animate]);

  const left = MAX_ROLLS - rolls;
  // D-203 — 다섯 개가 같게 멈추면 가벼운 반짝임(본 축하는 요트 칸에 기록할 때).
  const allSame = dice !== null && rolls > 0 && !rolling && dice.every((d) => d === dice[0]);
  return (
    <section className="yt-tray" aria-label="주사위">
      <div
        className={`yt-dice${allSame ? ' yt-dice-same' : ''}`}
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
          return (
            <button
              type="button"
              key={i}
              className={`yt-die${isHeld ? ' yt-die-held' : ''}${shaking ? ' yt-die-rolling' : ''}${face === null ? ' yt-die-empty' : ''}${locked ? ' yt-die-lock' : ''}`}
              style={{ ['--yt-i' as string]: i, ['--yt-o' as string]: LAND_ORDER[i] } as CSSProperties}
              disabled={!canHold || face === null}
              aria-pressed={isHeld}
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
              </span>
              {isHeld && <span className="yt-die-tag">고정</span>}
            </button>
          );
        })}
        {rolling && dice && (
          <span className="yt-cup" aria-hidden="true" key={rollNonce}>
            <DiceCup />
          </span>
        )}
      </div>
      <div className="yt-tray-actions">
        {onRoll ? (
          <button type="button" className="yt-roll" disabled={!canRoll} onClick={onRoll}>
            굴리기 · {left}번 남음
          </button>
        ) : (
          <span className="yt-tray-note">
            {note ?? (turnName ? `${turnName} 차례 · 굴림 ${rolls}/${MAX_ROLLS}` : '대기 중')}
          </span>
        )}
      </div>
    </section>
  );
}
