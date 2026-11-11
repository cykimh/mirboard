import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { MAX_ROLLS, type YachtSeatTurn } from '@/types/yacht';
import { YachtDie } from './YachtDie';

/** D-204 — 좌석 띠 작은 주사위가 따라 구르는 길이(가볍게 — 가운데 판 굴림보다 짧다). CSS `yt-salon-mini-roll` + 엇갈림 4 × 30ms. */
export const SEAT_ROLL_MS = 460;

interface Props {
  turn: YachtSeatTurn;
  /** 연출 켬(카드 애니메이션 설정). 끄면 눈만 바로 바뀐다. */
  animate?: boolean;
}

/**
 * D-202 — 동시 진행에서 남의 주사위를 좌석 띠에 작게(보기만). 고정한 주사위는 황동 테(D-203 — 가운데 판과 같은 그림).
 * D-204 — 그 사람의 굴림 수가 오르면 고정 안 한 작은 주사위가 한 번 통 튀며 돈다(눈은 처음부터 서버 값). 처음 그릴 때는 구르지 않는다.
 */
export function SeatDice({ turn, animate = true }: Props) {
  const label = turn.dice
    ? `주사위 ${turn.dice.join(', ')} · 굴림 ${turn.rolls}/${MAX_ROLLS}`
    : '아직 굴리지 않음';
  const seenRolls = useRef(turn.rolls);
  const [rollKey, setRollKey] = useState(0);
  const [rolling, setRolling] = useState(false);
  useEffect(() => {
    const before = seenRolls.current;
    seenRolls.current = turn.rolls;
    if (!animate || turn.rolls <= before) return;
    setRollKey((k) => k + 1);
    setRolling(true);
    const timer = window.setTimeout(() => setRolling(false), SEAT_ROLL_MS);
    return () => window.clearTimeout(timer);
  }, [turn.rolls, animate]);
  return (
    <span className="yt-seat-dice" role="img" aria-label={label}>
      {Array.from({ length: 5 }, (_, i) => {
        const face = turn.dice ? turn.dice[i] : null;
        const spin = rolling && !turn.held[i] && face !== null;
        return (
          <span
            key={spin ? `${i}:${rollKey}` : i}
            className={`yt-mini-die${turn.held[i] ? ' yt-mini-die-held' : ''}${face === null ? ' yt-mini-die-empty' : ''}${spin ? ' yt-mini-die-rolling' : ''}`}
            style={{ ['--yt-i' as string]: i } as CSSProperties}
            aria-hidden="true"
          >
            <YachtDie face={face} mini />
          </span>
        );
      })}
    </span>
  );
}
