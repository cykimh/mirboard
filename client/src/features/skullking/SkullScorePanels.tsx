import type { MatchEndedPayload, RoundScoreView } from '@/types/skullking';

interface RoundEndProps {
  roundNumber: number;
  scores: Record<number, RoundScoreView>;
  cumulativeScores: Record<number, number>;
  nameOf: (seat: number) => string;
}

/**
 * 라운드 정산 (§10, §11). 적중/실패와 **보너스가 왜 0인지**가 읽혀야 한다 — 예측을 놓치면
 * 획득 카드와 무관하게 보너스가 전부 소멸하는 게 이 게임의 핵심 긴장이다(§11).
 */
export function SkullRoundEndPanel({
  roundNumber,
  scores,
  cumulativeScores,
  nameOf,
}: RoundEndProps) {
  const seats = Object.keys(scores)
    .map(Number)
    .sort((a, b) => a - b);

  return (
    <section className="match-end sk-round-end" aria-label={`라운드 ${roundNumber} 정산`}>
      <h3>라운드 {roundNumber} 정산</h3>
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
            const s = scores[seat];
            const hit = s.bid === s.won;
            return (
              <tr key={seat} className={hit ? 'sk-hit' : 'sk-miss'}>
                <td>{nameOf(seat)}</td>
                <td>{s.bid}</td>
                <td>{s.won}</td>
                <td>{s.base > 0 ? `+${s.base}` : s.base}</td>
                <td>
                  {s.bonus > 0 ? `+${s.bonus}` : hit ? '0' : '—'}
                </td>
                <td>
                  <strong>{s.total > 0 ? `+${s.total}` : s.total}</strong>
                </td>
                <td>{cumulativeScores[seat] ?? 0}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <p className="sk-round-note">
        예측을 맞히지 못하면 보너스는 전부 소멸합니다. 다음 라운드가 곧 시작됩니다.
      </p>
    </section>
  );
}

interface MatchEndProps {
  match: MatchEndedPayload;
  mySeat: number;
  nameOf: (seat: number) => string;
  onExit?: () => void;
}

/** 매치 종료 (§12). 공동 승리가 가능하고(§13-⑰) 탈주 좌석은 후보에서 빠진다(§13-⑳). */
export function SkullMatchEndPanel({
  match,
  mySeat,
  nameOf,
  onExit,
}: MatchEndProps) {
  const ranked = Object.keys(match.finalScores)
    .map(Number)
    .sort((a, b) => match.finalScores[b] - match.finalScores[a]);
  const iWon = match.winners.includes(mySeat);

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

      {onExit && (
        <button type="button" className="sk-play" onClick={onExit}>
          메인으로
        </button>
      )}
    </section>
  );
}
