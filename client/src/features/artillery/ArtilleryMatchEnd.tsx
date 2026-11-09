import type { MatchResult } from '@/types/artillery';

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

/** 순위 카드 — 게임판을 유지한 채 보여 준다(D-120·D-153). */
export function ArtilleryMatchEnd({ result, mySeat, nameOf, onExit, onBackToRoom }: Props) {
  return (
    <section className="ar-end" aria-label="게임 결과">
      <h2 className="ar-end-title">
        {result.placements.some((p) => p.seat === mySeat && p.rank === 1 && !p.deserted) ? '🌻 이겼어요!' : '결과'}
      </h2>
      <p className="ar-end-reason">{REASON_TEXT[result.reason]}</p>
      <ol className="ar-end-list">
        {result.placements.map((p) => (
          <li key={p.seat} className={`ar-end-row${p.seat === mySeat ? ' ar-end-me' : ''}`}>
            <span className="ar-end-rank">{p.deserted ? '탈주' : `${p.rank}위`}</span>
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
