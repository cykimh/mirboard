import type { EndReason, OneCardMatchResult, StandingStatus } from '@/types/onecard';

const REASON_TEXT: Record<EndReason, string> = {
  FINISHED: '마지막 카드를 낸 사람이 나와 판이 끝났습니다.',
  LAST_STANDING: '한 명만 남아 판이 끝났습니다.',
  NO_HUMANS: '남은 사람이 없어 판이 끝났습니다.',
  STALEMATE: '더 진행할 수 없어 남은 장수로 순위를 매겼습니다.',
};

const STATUS_TEXT: Record<StandingStatus, string> = {
  FINISHED: '다 냄',
  ALIVE: '',
  BANKRUPT: '파산',
  DESERTED: '탈주',
};

interface Props {
  result: OneCardMatchResult;
  mySeat: number;
  nameOf: (seat: number) => string;
  onExit?: () => void;
  /** D-153 — 판이 끝나 방이 대기실로 돌아왔다(이 세션이 전이를 봐 결과 화면을 유지하는 중). [대기실로]가 부른다. */
  onBackToRoom?: () => void;
}

/**
 * 판 종료 — 순위(1, 1, 3 식)와 남은 장수·상태. 원카드는 한 판이 한 매치다. 친구 방은 대기실로 돌아가 [대기실로]로 다음 판을
 * 기다리고(D-153), 봇과 연습 방은 이 화면에서 끝난다(방은 FINISHED — 게임판은 D-120 처럼 유지된다).
 */
export function OneCardMatchEnd({ result, mySeat, nameOf, onExit, onBackToRoom }: Props) {
  const standings = [...result.standings].sort((a, b) => a.rank - b.rank || a.seat - b.seat);
  const mine = standings.find((s) => s.seat === mySeat);
  const winners = standings.filter((s) => s.rank === 1).length;
  const title =
    mine?.rank === 1 ? (winners > 1 ? '공동 1위 🎉' : '승리 🎉') : '판 종료';

  return (
    <section className="match-end oc-match-end" aria-label="판 종료">
      <h3>{title}</h3>
      <p className="oc-match-reason">{REASON_TEXT[result.reason]}</p>
      <ol className="oc-standings">
        {standings.map((s) => (
          <li
            key={s.seat}
            className={[
              s.rank === 1 ? 'oc-standing-win' : '',
              s.seat === mySeat ? 'oc-standing-me' : '',
            ]
              .filter(Boolean)
              .join(' ')}
          >
            <span className="oc-standing-rank">{s.rank}위</span>
            <span className="oc-standing-name">
              {nameOf(s.seat)}
              {s.seat === mySeat ? ' (나)' : ''}
            </span>
            <span className="oc-standing-cards">{s.cardsLeft}장</span>
            {STATUS_TEXT[s.status] && (
              <span className="oc-standing-status">{STATUS_TEXT[s.status]}</span>
            )}
          </li>
        ))}
      </ol>
      {onBackToRoom && <p className="oc-match-next">대기실에서 방장이 시작하면 다음 판이에요.</p>}
      <div className="oc-match-actions">
        {onBackToRoom && (
          <button type="button" className="oc-play" onClick={onBackToRoom}>
            대기실로
          </button>
        )}
        {onExit && (
          <button type="button" className={onBackToRoom ? 'oc-draw' : 'oc-play'} onClick={onExit}>
            {onBackToRoom ? '나가기' : '메인으로'}
          </button>
        )}
      </div>
    </section>
  );
}
