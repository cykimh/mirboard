import type { HintView, Revealed, WordTempPhase } from '@/types/wordtemp';
import { TempGauge } from './wordTempArt';

interface Props {
  phase: WordTempPhase | null;
  /** 지금 차수(1 부터). */
  attempt: number;
  /** 차수별 기준 온도(서버가 준 값). */
  floors: number[];
  /** 이 출제 차례의 힌트 전부 — 낸 순서. */
  hints: HintView[];
  /** 끝난 힌트 단계의 요약(기준을 넘겼는지). */
  revealed: Revealed[];
  maxHints: number;
  setterName: string;
  /** 내가 출제자인가 — 빈 판의 문구만 다르다. */
  mine?: boolean;
  /** 방금 채점된 힌트 — 그 줄의 수은주가 차오른다. */
  freshText: string | null;
  /** 연출 열쇠 — 같은 줄을 다시 그려도 연출이 처음부터 돌게. */
  fxSeq: number;
}

/** 차수 하나의 판정 문구 — 기준을 넘겼는지(D-320). */
export function verdictText(r: Revealed): string {
  return r.floorMet ? `기준 ${r.floor}° 넘김 → 추리` : '기준 못 넘김 — 맞히는 사람이 없어도 이번 차수 점수 없음';
}

/**
 * 온도계 판(D-320) — <b>힌트는 낼 때마다 모두에게 공개된다.</b> 낱말과 온도(온도계 한 줄)를 차수별로 묶어 보여 준다: 지금 차수는 펼쳐서
 * (기준 온도·개수와 함께), 지난 차수는 한 줄 요약으로 접어 둔다(누르면 펼친다 — 한 출제 차례에 힌트가 50개까지 쌓일 수 있다).
 * 온도계 관 위의 세로 선이 그 차수의 기준 온도다. 채점 중인 낱말은 "채점 중…"으로 보인다.
 */
export function HintBoard({
  phase,
  attempt,
  floors,
  hints,
  revealed,
  maxHints,
  setterName,
  mine = false,
  freshText,
  fxSeq,
}: Props) {
  const attempts = Array.from({ length: Math.max(attempt, 0) }, (_, i) => i + 1);
  return (
    <section className="wt-board" aria-label="힌트">
      <h3 className="wt-board-title">
        힌트 <span className="wt-board-sub">낼 때마다 모두에게 보여요 · 온도가 높을수록 정답에 가까워요</span>
      </h3>
      <ol className="wt-rounds">
        {attempts.map((a) => {
          const list = hints.filter((h) => h.attempt === a);
          const summary = revealed.find((r) => r.attempt === a);
          const floor = floors[a - 1] ?? summary?.floor ?? null;
          const current = a === attempt;
          const hinting = current && phase === 'HINT' && !summary;
          const rows = (
            <ol className="wt-hints" aria-label={`${a}차 힌트`}>
              {list.map((h) => {
                const fresh = current && h.status === 'SCORED' && freshText === h.text;
                const pass = h.status === 'SCORED' && floor !== null && h.temp >= floor;
                return (
                  <li
                    key={fresh ? `${h.text}-${fxSeq}` : h.text}
                    className={`wt-hint${h.status === 'PENDING' ? ' wt-hint-pending' : ''}${pass ? ' wt-hint-pass' : ''}${fresh ? ' wt-hint-fresh' : ''}`}
                  >
                    <span className="wt-hint-word">{h.text}</span>
                    {h.status === 'PENDING' ? (
                      <span className="wt-scoring" role="status">
                        <span className="wt-scoring-dots" aria-hidden="true" />
                        채점 중…
                      </span>
                    ) : (
                      <TempGauge temp={h.temp} floor={floor ?? undefined} small fresh={fresh} />
                    )}
                    {pass && <span className="wt-hint-tag">통과</span>}
                  </li>
                );
              })}
              {list.length === 0 && (
                <li className="wt-hint wt-hint-empty">
                  {hinting ? (mine ? '힌트를 내면 여기에 모두에게 보여요' : `${setterName} 님이 힌트를 고르는 중`) : '낸 힌트가 없어요'}
                </li>
              )}
            </ol>
          );
          const head = (
            <>
              <span className="wt-round-no">{a}차</span>
              {floor !== null && <span className="wt-round-floor">기준 {floor}°</span>}
              <span className="wt-round-count" aria-label={`힌트 ${list.length}개, 최대 ${maxHints}개`}>
                힌트 {list.length}/{maxHints}
              </span>
              {summary && (
                <span className={`wt-round-verdict ${summary.floorMet ? 'wt-verdict-met' : 'wt-verdict-miss'}`}>
                  {summary.floorMet ? '넘김' : '못 넘김'}
                  {summary.text !== null ? ` · 최고 ${summary.text} ${summary.temp}°` : ''}
                </span>
              )}
            </>
          );
          return (
            <li key={a} className={`wt-round${current ? ' wt-round-now' : ' wt-round-past'}`} data-attempt={a}>
              {current ? (
                <>
                  <div className="wt-round-head">{head}</div>
                  {rows}
                  {summary && (
                    <p className={`wt-verdict ${summary.floorMet ? 'wt-verdict-met' : 'wt-verdict-miss'}`} role="status">
                      {verdictText(summary)}
                    </p>
                  )}
                </>
              ) : (
                <details className="wt-round-fold">
                  <summary className="wt-round-head">{head}</summary>
                  {rows}
                </details>
              )}
            </li>
          );
        })}
      </ol>
    </section>
  );
}
