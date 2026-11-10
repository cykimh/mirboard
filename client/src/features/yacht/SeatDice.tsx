import { MAX_ROLLS, type YachtSeatTurn } from '@/types/yacht';
import { DIE_FACE } from './yachtLabels';

/** D-202 — 동시 진행에서 남의 주사위를 좌석 띠에 작게(보기만). 고정한 주사위는 밑줄 색. */
export function SeatDice({ turn }: { turn: YachtSeatTurn }) {
  const label = turn.dice
    ? `주사위 ${turn.dice.join(', ')} · 굴림 ${turn.rolls}/${MAX_ROLLS}`
    : '아직 굴리지 않음';
  return (
    <span className="yt-seat-dice" role="img" aria-label={label}>
      {Array.from({ length: 5 }, (_, i) => {
        const face = turn.dice ? turn.dice[i] : null;
        return (
          <span key={i} className={`yt-mini-die${turn.held[i] ? ' yt-mini-die-held' : ''}`} aria-hidden="true">
            {face === null ? '·' : DIE_FACE[face]}
          </span>
        );
      })}
    </span>
  );
}
