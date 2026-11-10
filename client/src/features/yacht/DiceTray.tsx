import { useEffect, useState } from 'react';
import { MAX_ROLLS, type Dice, type Held } from '@/types/yacht';
import { DIE_FACE } from './yachtLabels';

/** 굴림 애니메이션 길이 — 봇 템포(700ms) 안에 끝나야 한다. */
export const ROLL_ANIMATION_MS = 600;

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
 * 주사위 판(설계 §7.2). 홀드한 주사위는 아래로 내려앉고 테두리. 내 차례면 탭으로 홀드, 남의 차례면 보기만(DICE_HELD 실시간).
 * 실제 `<button>` 이라 키보드·보조기기로도 홀드한다. 굴리는 동안 홀드 안 한 주사위만 흔들린다.
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
  return (
    <section className="yt-tray" aria-label="주사위">
      <div className="yt-dice" role="group" aria-label={dice ? `주사위 ${dice.join(', ')}` : '아직 굴리지 않음'}>
        {Array.from({ length: 5 }, (_, i) => {
          const face = dice ? dice[i] : null;
          const isHeld = held[i];
          const shaking = rolling && !isHeld;
          return (
            <button
              type="button"
              key={i}
              className={`yt-die${isHeld ? ' yt-die-held' : ''}${shaking ? ' yt-die-rolling' : ''}${face === null ? ' yt-die-empty' : ''}`}
              disabled={!canHold || face === null}
              aria-pressed={isHeld}
              aria-label={face === null ? `주사위 ${i + 1} 비어 있음` : `주사위 ${i + 1}: ${face}${isHeld ? ', 고정' : ''}`}
              onClick={() => onToggle?.(i)}
            >
              <span aria-hidden="true" className="yt-die-face">
                {face === null ? '·' : DIE_FACE[face]}
              </span>
              {isHeld && <span className="yt-die-tag">고정</span>}
            </button>
          );
        })}
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
