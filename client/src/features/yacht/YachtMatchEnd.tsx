import type { MatchResult } from '@/types/yacht';

const REASON_TEXT: Record<MatchResult['reason'], string> = {
  FINISHED: '12칸을 모두 채웠습니다.',
  NO_HUMANS: '사람이 모두 나가 판이 끝났습니다.',
  ACTION_LIMIT: '진행이 너무 길어져 판을 끝냈습니다.',
};

interface Props {
  result: MatchResult;
  mySeat: number;
  nameOf: (seat: number) => string;
  onExit?: () => void;
  onBackToRoom?: () => void;
}

/** 순위 카드 — 게임판을 유지한 채 보여 준다(D-120·D-153). 1인 판은 내 총점만. */
export function YachtMatchEnd({ result, mySeat, nameOf, onExit, onBackToRoom }: Props) {
  const solo = result.placements.length === 1;
  return (
    <section className="yt-end" aria-label="게임 결과">
      <h2 className="yt-end-title">{solo ? `총점 ${result.placements[0].total}점` : '결과'}</h2>
      <p className="yt-end-reason">{REASON_TEXT[result.reason]}</p>
      {!solo && (
        <ol className="yt-end-list">
          {result.placements.map((p) => (
            <li key={p.seat} className={`yt-end-row${p.seat === mySeat ? ' yt-end-me' : ''}`}>
              <span className="yt-end-rank">{p.deserted ? '탈주' : `${p.rank}위`}</span>
              <span className="yt-end-name">
                {nameOf(p.seat)}
                {p.seat === mySeat ? ' (나)' : ''}
              </span>
              <span className="yt-end-total">{p.total}점</span>
            </li>
          ))}
        </ol>
      )}
      {solo && <p className="yt-end-note">혼자 한 판은 전적에 남지 않습니다.</p>}
      <div className="yt-end-actions">
        {onBackToRoom && (
          <button type="button" className="yt-roll" onClick={onBackToRoom}>
            대기실로
          </button>
        )}
        {onExit && (
          <button type="button" className="yt-badge" onClick={onExit}>
            메인으로
          </button>
        )}
      </div>
    </section>
  );
}
