import type { MatchResult } from '@/types/artillery';
import { teamName } from './delay';

const REASON_TEXT: Record<MatchResult['reason'], string> = {
  LAST_STANDING: '마지막까지 남은 화분이 이겼습니다.',
  ALL_DOWN: '남은 화분이 한꺼번에 떨어졌습니다.',
  NO_HUMANS: '사람이 모두 떨어져 판이 끝났습니다.',
};

interface Props {
  result: MatchResult;
  mySeat: number;
  nameOf: (seat: number) => string;
  onExit?: () => void;
  onBackToRoom?: () => void;
}

/** D-225 — 팀전 결과 문구. */
const TEAM_REASON_TEXT: Record<MatchResult['reason'], string> = {
  LAST_STANDING: '한 팀만 남았습니다.',
  ALL_DOWN: '두 팀이 한꺼번에 떨어졌습니다 — 떨어지기 직전 체력 합으로 가렸어요.',
  NO_HUMANS: '사람이 모두 떨어져 남은 화분 수·체력으로 가렸습니다.',
};

/** 순위 카드 — 게임판을 유지한 채 보여 준다(D-120·D-153). 팀전이면 이긴 팀·무승부(D-225). */
export function ArtilleryMatchEnd({ result, mySeat, nameOf, onExit, onBackToRoom }: Props) {
  const teams = result.teams === true;
  const winnerTeam = result.winnerTeam ?? null;
  const me = result.placements.find((p) => p.seat === mySeat);
  const iWon = teams
    ? winnerTeam !== null && mySeat >= 0 && mySeat % 2 === winnerTeam && !me?.deserted
    : !!me && me.rank === 1 && !me.deserted;
  const title = iWon
    ? teams
      ? '🌻 우리 팀이 이겼어요!'
      : '🌻 이겼어요!'
    : teams
      ? winnerTeam === null
        ? '무승부'
        : `${teamName(winnerTeam)} 승리`
      : '결과';
  return (
    <section className="ar-end" aria-label="게임 결과">
      <h2 className="ar-end-title">{title}</h2>
      <p className="ar-end-reason">{(teams ? TEAM_REASON_TEXT : REASON_TEXT)[result.reason]}</p>
      <ol className="ar-end-list">
        {result.placements.map((p) => (
          <li key={p.seat} className={`ar-end-row${p.seat === mySeat ? ' ar-end-me' : ''}`}>
            <span className="ar-end-rank">
              {p.deserted
                ? '탈주'
                : teams
                  ? winnerTeam === null
                    ? '무'
                    : p.seat % 2 === winnerTeam
                      ? '승'
                      : '패'
                  : `${p.rank}위`}
            </span>
            {teams && <span className={`ar-team-chip ar-team-${p.seat % 2}`}>{teamName(p.seat % 2)}</span>}
            <span className="ar-end-name">
              {nameOf(p.seat)}
              {p.seat === mySeat ? ' (나)' : ''}
            </span>
            <span className="ar-end-hp">체력 {p.hp}</span>
          </li>
        ))}
      </ol>
      <div className="ar-end-actions">
        {onBackToRoom && (
          <button type="button" className="ar-btn ar-btn-fire" onClick={onBackToRoom}>
            대기실로
          </button>
        )}
        {onExit && (
          <button type="button" className="ar-btn" onClick={onExit}>
            메인으로
          </button>
        )}
      </div>
    </section>
  );
}
