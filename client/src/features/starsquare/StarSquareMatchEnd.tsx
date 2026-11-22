import type { MatchResult } from '@/types/starsquare';
import { CrownConstellationArt } from './starSquareArt';

const REASON_TEXT: Record<MatchResult['reason'], string> = {
  FINISHED: '',
  NO_HUMANS: '사람이 모두 나가 판이 끝났어요.',
  LAST_SEAT: '한 사람만 남아 판이 끝났어요.',
};

interface Props {
  result: MatchResult;
  mySeat: number;
  nameOf: (seat: number) => string;
  solo: boolean;
  onExit?: () => void;
  onBackToRoom?: () => void;
}

/** 매치 끝(§8.2) — 점수 합 → 1등 횟수 순위. 혼자 판은 순위 대신 "다 풀었어요". */
export function StarSquareMatchEnd({ result, mySeat, nameOf, solo, onExit, onBackToRoom }: Props) {
  const me = result.placements.find((p) => p.seat === mySeat);
  const won = me !== undefined && me.rank === 1 && !me.deserted;
  return (
    <section className={`sq-end${won ? ' sq-end-won' : ''}`} aria-label="판 결과">
      {won && !solo && <CrownConstellationArt className="sq-end-crown" />}
      <h2 className="sq-end-title">{solo ? '판이 끝났어요' : won ? '1위!' : me ? `${me.rank}위` : '판이 끝났어요'}</h2>
      {REASON_TEXT[result.reason] && <p className="sq-end-reason">{REASON_TEXT[result.reason]}</p>}
      {!solo && (
        <ol className="sq-end-rows">
          {result.placements.map((p) => (
            <li key={p.seat} className={`sq-end-row${p.seat === mySeat ? ' sq-end-me' : ''}`}>
              <span className="sq-end-rank">{p.deserted ? '—' : `${p.rank}위`}</span>
              <span className="sq-end-name">
                {nameOf(p.seat)}
                {p.seat === mySeat ? ' (나)' : ''}
              </span>
              <span className="sq-end-points">
                {p.points}점{p.firsts > 0 ? ` · 1등 ${p.firsts}번` : ''}
              </span>
            </li>
          ))}
        </ol>
      )}
      <div className="sq-end-actions">
        {onBackToRoom && (
          <button type="button" className="sq-primary" onClick={onBackToRoom}>
            대기실로
          </button>
        )}
        {onExit && (
          <button type="button" className="sq-secondary" onClick={onExit}>
            메인으로
          </button>
        )}
      </div>
    </section>
  );
}
