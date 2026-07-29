import { t } from '@/i18n/messages';
import { Button } from '@/components/ui/button';
import { SeatAvatar } from './SeatAvatar';
import type { TichuRoomState } from './tichuStore';

interface MatchEndedPanelProps {
  matchEnded: NonNullable<TichuRoomState['matchEnded']>;
  roundHistory: TichuRoomState['roundHistory'];
  playerIds: number[];
  myUserId: number;
  /** playerIds 내 내 좌석. 관전자는 -1 → 중립 문구. */
  mySeat: number;
  myTeam: 'A' | 'B';
  usernames: Record<number, string>;
  botSeats: number[];
  /** 판돈(0=내기 없음). >0 일 때만 칩 정산 표를 렌더. */
  stake: number;
  chips: Record<number, number>;
  chipDeltas: Record<number, number>;
  spectator: boolean;
  isHost: boolean;
  onRematch: () => void;
  onExit?: () => void;
}

/**
 * 매치 종료 결과 패널 — 승패 헤드라인, 최종 점수, 칩 정산(내기 방), MVP,
 * 라운드별 점수표, 그리고 '한 판 더'/'메인으로' 액션.
 *
 * D-87 에서 GameTable 에서 분리. 마크업·클래스명·문구 모두 이동 전과 동일하다.
 */
export function MatchEndedPanel({
  matchEnded,
  roundHistory,
  playerIds,
  myUserId,
  mySeat,
  myTeam,
  usernames,
  botSeats,
  stake,
  chips,
  chipDeltas,
  spectator,
  isHost,
  onRematch,
  onExit,
}: MatchEndedPanelProps) {
  return (
    <div className="match-ended">
      <h3>
        {mySeat >= 0
          ? matchEnded.winningTeam === myTeam
            ? '🏆 승리!'
            : '패배'
          : `Team ${matchEnded.winningTeam} 승`}
        {' — '}Team {matchEnded.winningTeam} {t('match.ended.titleSuffix')}
      </h3>
      <p>
        {t('match.ended.finalScore')} A {matchEnded.finalScores.A ?? 0} : {matchEnded.finalScores.B ?? 0} B
      </p>
      {stake > 0 && (
        <div className="match-chip-board">
          {mySeat >= 0 && (chipDeltas[myUserId] ?? 0) !== 0 && (
            <p
              className={`match-chip-delta ${
                (chipDeltas[myUserId] ?? 0) >= 0 ? 'win' : 'lose'
              }`}
            >
              {(chipDeltas[myUserId] ?? 0) >= 0
                ? `💰 +${chipDeltas[myUserId]}칩`
                : `💸 ${chipDeltas[myUserId]}칩`}
            </p>
          )}
          <table className="chip-standings">
            <tbody>
              {playerIds
                .map((uid) => ({ uid, c: chips[uid] ?? 0, d: chipDeltas[uid] ?? 0 }))
                .sort((a, b) => b.c - a.c)
                .map((row, i) => (
                  <tr key={row.uid} className={row.uid === myUserId ? 'me' : ''}>
                    <td>{i + 1}</td>
                    <td>{usernames[row.uid] ?? `#${row.uid}`}</td>
                    <td>💰 {row.c.toLocaleString()}</td>
                    <td className={row.d >= 0 ? 'win' : 'lose'}>
                      {row.d > 0 ? `+${row.d}` : row.d < 0 ? `${row.d}` : '—'}
                    </td>
                  </tr>
                ))}
            </tbody>
          </table>
        </div>
      )}
      {matchEnded.mvpUserId != null && (() => {
        const mvpId = matchEnded.mvpUserId;
        const mvpSeat = playerIds.indexOf(mvpId);
        return (
          <div className="match-mvp">
            <span className="mvp-label">🏅 MVP</span>
            <SeatAvatar
              seat={mvpSeat >= 0 ? mvpSeat : 0}
              userId={mvpId}
              size={44}
              isBot={botSeats.includes(mvpSeat)}
            />
            <span className="mvp-name">
              {usernames[mvpId] ?? `#${mvpId}`}
              {mvpId === myUserId ? ' (나!)' : ''}
            </span>
            {matchEnded.mvpStat && (
              <span className="mvp-stat">{matchEnded.mvpStat}</span>
            )}
          </div>
        );
      })()}
      {roundHistory.length > 0 && (
        <table className="score-history">
          <thead>
            <tr>
              <th>R</th>
              <th>Team A</th>
              <th>Team B</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {roundHistory.map((r, i) => (
              <tr key={i}>
                <td>{i + 1}</td>
                <td>{r.teamAScore}</td>
                <td>{r.teamBScore}</td>
                <td>{r.doubleVictory ? '더블 승' : ''}</td>
              </tr>
            ))}
            <tr className="score-history-total">
              <td>합계</td>
              <td>{matchEnded.finalScores.A ?? 0}</td>
              <td>{matchEnded.finalScores.B ?? 0}</td>
              <td />
            </tr>
          </tbody>
        </table>
      )}
      <p>
        {t('match.ended.roundsPlayed')}: {matchEnded.roundsPlayed}
      </p>
      <div className="match-actions">
        {!spectator && isHost && (
          <Button type="button" onClick={onRematch}>
            🔄 한 판 더
          </Button>
        )}
        {!spectator && !isHost && (
          <p className="hint">호스트가 '한 판 더' 를 누르면 같은 테이블에서 다시 시작합니다.</p>
        )}
        {onExit && (
          <Button type="button" variant="outline" className="match-exit" onClick={onExit}>
            {stake > 0 ? '테이블 떠나기' : '메인으로'}
          </Button>
        )}
      </div>
    </div>
  );
}
