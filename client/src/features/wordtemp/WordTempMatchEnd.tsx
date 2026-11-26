import type { MatchResult, TurnSummary } from '@/types/wordtemp';
import { ThermometerArt } from './wordTempArt';
import { tempBand } from './wordTempMath';

const REASON_TEXT: Record<MatchResult['endReason'], string> = {
  FINISHED: '',
  NO_HUMANS: '사람이 모두 나가 판이 끝났어요.',
  LAST_SEAT: '한 사람만 남아 판이 끝났어요.',
};

interface Props {
  result: MatchResult;
  history: TurnSummary[];
  mySeat: number;
  nameOf: (seat: number) => string;
  onExit?: () => void;
  onBackToRoom?: () => void;
}

/**
 * 판 끝(§8) — 총점 순위(같으면 공동 순위), 출제·추리 점수를 나눠 보여 주고, 아래에 출제 차례마다의 정답과 공개 힌트·맞힌 사람을 돌아본다.
 * 안 쓴 후보는 끝까지 공개되지 않는다(서버가 보내지 않는다).
 */
export function WordTempMatchEnd({ result, history, mySeat, nameOf, onExit, onBackToRoom }: Props) {
  const me = result.standings.find((s) => s.seat === mySeat);
  const won = me !== undefined && me.rank === 1 && !me.out && !result.draw;
  // 서버는 좌석 순서로 보낸다 — 순위 순으로(빠진 사람은 맨 아래, 같은 순위는 좌석 순) 그린다.
  const rows = [...result.standings].sort((x, y) => Number(x.out) - Number(y.out) || x.rank - y.rank || x.seat - y.seat);
  const title = result.draw ? '모두 비겼어요' : won ? '1위!' : me && !me.out ? `${me.rank}위` : '판이 끝났어요';
  return (
    <section className={`wt-end${won ? ' wt-end-won' : ''}`} role="status" aria-label="판 결과">
      {won && <ThermometerArt className="wt-end-art" size={56} level={1} />}
      <h2 className="wt-end-title">{title}</h2>
      {REASON_TEXT[result.endReason] && <p className="wt-end-reason">{REASON_TEXT[result.endReason]}</p>}
      <ol className="wt-end-rows">
        {rows.map((p) => (
          <li key={p.seat} className={`wt-end-row${p.seat === mySeat ? ' wt-end-me' : ''}${p.out ? ' wt-end-out' : ''}`}>
            <span className="wt-end-rank">{p.out ? '—' : `${p.rank}위`}</span>
            <span className="wt-end-name">
              {nameOf(p.seat)}
              {p.seat === mySeat ? ' (나)' : ''}
            </span>
            <span className="wt-end-split">
              출제 {p.setterPoints} · 추리 {p.guessPoints}
            </span>
            <span className="wt-end-points">{p.total}점</span>
          </li>
        ))}
      </ol>
      {history.length > 0 && (
        <details className="wt-end-history" open={history.length <= 4}>
          <summary>낱말 돌아보기 ({history.length})</summary>
          <ol className="wt-end-turns">
            {history.map((h) => (
              <li key={h.turnNo} className="wt-end-turn">
                <span className="wt-end-turn-head">
                  <span className="wt-end-turn-round">{h.round}R</span>
                  <span className="wt-end-turn-setter">{nameOf(h.setterSeat)}</span>
                  <strong className="wt-end-turn-answer">{h.answer}</strong>
                </span>
                <span className="wt-end-turn-hints">
                  {h.revealed.length === 0
                    ? '공개 힌트 없음'
                    : h.revealed.map((r) => (
                        <span key={r.attempt} className={`wt-band-chip wt-band-${tempBand(r.temp)}`}>
                          {r.text} {r.temp}°
                        </span>
                      ))}
                </span>
                <span className="wt-end-turn-solvers">
                  {h.solvers.length > 0
                    ? `맞힘: ${h.solvers.map(nameOf).join(' · ')}`
                    : h.setterGain > 0
                      ? `못 맞힘 · 출제자 +${h.setterGain}`
                      : '못 맞힘'}
                </span>
              </li>
            ))}
          </ol>
        </details>
      )}
      <div className="wt-end-actions">
        {onBackToRoom && (
          <button type="button" className="wt-primary" onClick={onBackToRoom}>
            대기실로
          </button>
        )}
        {onExit && (
          <button type="button" className="wt-secondary" onClick={onExit}>
            메인으로
          </button>
        )}
      </div>
    </section>
  );
}
