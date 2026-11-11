import { useEffect, useState, type CSSProperties } from 'react';
import { MAX_ROLLS, type Dice, type Held } from '@/types/yacht';
import { YachtDie, YachtDieCube } from './YachtDie';

/** 굴림 애니메이션 길이 — 봇 템포(700ms) 안에 끝나야 한다. */
export const ROLL_ANIMATION_MS = 600;
/**
 * 주사위마다 엇갈리는 간격·한 개가 굴러와 멈추기까지(D-203). `4 × 40 + 440 = 600` — 마지막 주사위도 `ROLL_ANIMATION_MS` 안에 멈춘다.
 * 실제 값은 CSS(`20-yacht-table.css` 의 `yt-salon-throw`·`--yt-i * 40ms`)에 있고 `yachtCssNamespace.test` 가 둘을 맞춰 본다.
 */
export const ROLL_STAGGER_MS = 40;
export const ROLL_DIE_MS = 440;

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
 * 실제 `<button>` 이라 키보드·보조기기로도 홀드한다. 굴리는 동안 홀드 안 한 주사위만 정육면체(`YachtDieCube`)로 굴러와 바닥에 한 번
 * 튀고 멈춘다 — 앞면이 서버가 준 눈이고 `aria-label` 도 늘 그 눈이다. 연출은 조작을 막지 않는다(굴러오는 중에도 눌린다).
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
          const shaking = rolling && !isHeld;
          return (
            <button
              type="button"
              key={i}
              className={`yt-die${isHeld ? ' yt-die-held' : ''}${shaking ? ' yt-die-rolling' : ''}${face === null ? ' yt-die-empty' : ''}`}
              style={{ ['--yt-i' as string]: i } as CSSProperties}
              disabled={!canHold || face === null}
              aria-pressed={isHeld}
              aria-label={face === null ? `주사위 ${i + 1} 비어 있음` : `주사위 ${i + 1}: ${face}${isHeld ? ', 고정' : ''}`}
              onClick={() => onToggle?.(i)}
            >
              <span aria-hidden="true" className="yt-die-shadow" />
              <span aria-hidden="true" className="yt-die-face">
                {shaking && face !== null ? <YachtDieCube face={face} /> : <YachtDie face={face} />}
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
