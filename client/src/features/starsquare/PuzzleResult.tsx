import type { PuzzleOutcome } from '@/types/starsquare';
import { StarGrid } from './StarGrid';
import { clockText } from './starSquareMath';

export const REASON_LABEL: Record<PuzzleOutcome['reason'], string> = {
  ALL_SOLVED: '모두 완성',
  CLOSED: '첫 완성 뒤 마감',
  TIME_UP: '시간 끝',
};

interface Props {
  outcome: PuzzleOutcome;
  n: number;
  clues: number[];
  mySeat: number;
  nameOf: (seat: number) => string;
  /** 결과 보기 남은 시간(ms) — 다음 퍼즐까지. 마지막 퍼즐이면 없음. */
  nextInMs: number | null;
}

/**
 * 퍼즐 하나의 결과(§6.4) — 순위·점수·걸린 시간과 <b>모두의 판</b>(끝난 퍼즐은 공개)·정답 하나를 나란히 본다.
 */
export function PuzzleResult({ outcome, n, clues, mySeat, nameOf, nextInMs }: Props) {
  const ordered = [...outcome.results].sort((a, b) => a.rank - b.rank || a.seat - b.seat);
  return (
    <section className="sq-result" aria-label={`퍼즐 ${outcome.index + 1} 결과`}>
      <header className="sq-result-head">
        <h3 className="sq-result-title">퍼즐 {outcome.index + 1} 결과</h3>
        <span className="sq-result-reason">{REASON_LABEL[outcome.reason]}</span>
        {nextInMs !== null && (
          <span className="sq-result-next" aria-live="polite">
            다음 퍼즐까지 {Math.max(0, Math.ceil(nextInMs / 1000))}초
          </span>
        )}
      </header>
      <ol className="sq-result-rows">
        {ordered.map((r) => (
          <li key={r.seat} className={`sq-result-row${r.seat === mySeat ? ' sq-result-me' : ''}`} data-rank={r.rank}>
            <span className="sq-result-rank">{r.deserted ? '—' : `${r.rank}위`}</span>
            <span className="sq-result-name">
              {nameOf(r.seat)}
              {r.seat === mySeat ? ' (나)' : ''}
            </span>
            <span className="sq-result-detail">
              {r.deserted
                ? '나감'
                : r.solved
                  ? `완성 ${clockText(r.elapsedMs)}`
                  : `✦ ${r.lines}줄 · ${r.filled}칸`}
            </span>
            <span className="sq-result-points">+{r.points}</span>
          </li>
        ))}
      </ol>
      <div className="sq-result-boards">
        {ordered.map((r) => (
          <figure key={r.seat} className="sq-result-board">
            <StarGrid n={n} board={outcome.grids[r.seat] ?? []} clues={clues} selected={-1} editable={false} mini label={`${nameOf(r.seat)}의 판`} />
            <figcaption>{nameOf(r.seat)}</figcaption>
          </figure>
        ))}
        <figure className="sq-result-board sq-result-solution">
          <StarGrid n={n} board={outcome.solution} clues={clues} selected={-1} editable={false} mini label="정답 하나" />
          <figcaption>정답 하나</figcaption>
        </figure>
      </div>
    </section>
  );
}
