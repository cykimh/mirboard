import type { CompletedRoundView, MatchEndedPayload } from '@/types/skullking';
import { SkullRoundHistoryTable } from './SkullRoundHistoryTable';

const signed = (n: number) => (n > 0 ? `+${n}` : `${n}`);

interface RoundResultProps {
  round: CompletedRoundView;
  /** 이 라운드까지의 누적 — 다음 라운드 예측 중이면 스토어의 누적이 곧 그 값이다. */
  cumulativeScores: Record<number, number>;
  mySeat: number;
  nameOf: (seat: number) => string;
  onOpenHistory: () => void;
  onDismiss: () => void;
}

/**
 * 직전 라운드 결과 (§10, §11, D-120). 적중/실패와 **보너스가 왜 0인지**가 읽혀야 한다 —
 * 예측을 놓치면 획득 카드와 무관하게 보너스가 전부 소멸하는 게 이 게임의 핵심 긴장이다(§11).
 *
 * 서버는 라운드 사이에 멈추지 않으므로 이 패널은 **다음 라운드 예측 중**에 뜬다. 그래서
 * 입력을 막지 않는다 — 예측 패널·손패 아래에 놓이고, 닫을 수 있다.
 */
export function SkullRoundResultPanel({
  round,
  cumulativeScores,
  mySeat,
  nameOf,
  onOpenHistory,
  onDismiss,
}: RoundResultProps) {
  const seats = Object.keys(round.scores)
    .map(Number)
    .sort((a, b) => a - b);
  const title = `라운드 ${round.roundNumber} 결과`;

  return (
    <section className="sk-round-result" aria-label={title}>
      <h3>{title}</h3>
      <table className="sk-score-table">
        <thead>
          <tr>
            <th>플레이어</th>
            <th>예측</th>
            <th>획득</th>
            <th>기본</th>
            <th>보너스</th>
            <th>합계</th>
            <th>누적</th>
          </tr>
        </thead>
        <tbody>
          {seats.map((seat) => {
            const s = round.scores[seat];
            const hit = s.bid === s.won;
            const mine = seat === mySeat;
            return (
              <tr
                key={seat}
                className={`${hit ? 'sk-history-hit' : 'sk-history-miss'}${
                  mine ? ' sk-round-result-me' : ''
                }`}
              >
                <td>
                  {nameOf(seat)}
                  {mine && ' (나)'}
                </td>
                <td>{s.bid}</td>
                <td>{s.won}</td>
                <td>{signed(s.base)}</td>
                <td>{s.bonus > 0 ? `+${s.bonus}` : hit ? '0' : '—'}</td>
                <td>
                  <strong>
                    {signed(s.total)} {hit ? '✓' : '✗'}
                  </strong>
                </td>
                <td>{cumulativeScores[seat] ?? 0}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <p className="sk-round-note">예측을 맞히지 못하면 보너스는 전부 소멸합니다.</p>
      <div className="sk-round-result-actions">
        <button
          type="button"
          className="sk-round-result-btn"
          aria-haspopup="dialog"
          onClick={onOpenHistory}
        >
          점수표 전체
        </button>
        <button type="button" className="sk-round-result-btn" onClick={onDismiss}>
          닫기
        </button>
      </div>
    </section>
  );
}

interface MatchEndProps {
  match: MatchEndedPayload;
  mySeat: number;
  nameOf: (seat: number) => string;
  onExit?: () => void;
  /** 라운드 표용 (D-120). 합계는 `match.finalScores` 를 쓴다. */
  completedRounds?: CompletedRoundView[];
  seats?: number[];
  desertedSeats?: number[];
}

/** 매치 종료 (§12). 공동 승리가 가능하고(§13-⑰) 탈주 좌석은 후보에서 빠진다(§13-⑳). */
export function SkullMatchEndPanel({
  match,
  mySeat,
  nameOf,
  onExit,
  completedRounds = [],
  seats = [],
  desertedSeats = [],
}: MatchEndProps) {
  const ranked = Object.keys(match.finalScores)
    .map(Number)
    .sort((a, b) => match.finalScores[b] - match.finalScores[a]);
  const iWon = match.winners.includes(mySeat);
  const tableSeats =
    seats.length > 0 ? seats : Object.keys(match.finalScores).map(Number).sort((a, b) => a - b);

  return (
    <section className="match-end sk-match-end" aria-label="매치 종료">
      <h3>
        {match.winners.length === 0
          ? '매치 종료'
          : match.winners.length > 1
            ? '공동 승리'
            : '승리'}
        {iWon && ' 🎉'}
      </h3>
      <p className="sk-match-winners">
        {match.winners.length === 0
          ? '승자 없음'
          : `${match.winners.map(nameOf).join(', ')} — ${match.roundsPlayed}라운드`}
      </p>

      <ol className="sk-final-scores">
        {ranked.map((seat) => (
          <li key={seat} className={match.winners.includes(seat) ? 'sk-winner' : ''}>
            <span>{nameOf(seat)}</span>
            <strong>{match.finalScores[seat]}</strong>
          </li>
        ))}
      </ol>

      {match.roundsPlayed < 10 && (
        <p className="sk-round-note">
          탈주로 조기 종료된 매치입니다 ({match.roundsPlayed}라운드 완주).
        </p>
      )}

      {completedRounds.length > 0 && (
        <details className="sk-match-history">
          <summary>라운드별 점수</summary>
          <SkullRoundHistoryTable
            rounds={completedRounds}
            seats={tableSeats}
            totals={match.finalScores}
            mySeat={mySeat}
            nameOf={nameOf}
            desertedSeats={desertedSeats}
          />
        </details>
      )}

      {onExit && (
        <button type="button" className="sk-play" onClick={onExit}>
          메인으로
        </button>
      )}
    </section>
  );
}
