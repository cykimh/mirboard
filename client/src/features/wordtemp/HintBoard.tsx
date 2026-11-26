import type { Revealed, WordTempPhase } from '@/types/wordtemp';
import { TempGauge } from './wordTempArt';
import { BAND_LABEL, BOIL_FROM, tempBand } from './wordTempMath';

interface Props {
  phase: WordTempPhase | null;
  attempt: number;
  revealed: Revealed[];
  hintCount: number;
  maxHints: number;
  setterName: string;
  /** 내가 출제자인가 — 기다리는 줄의 문구만 다르다. */
  mine?: boolean;
  /** 방금 공개된 차수 — 그 줄의 수은주가 차오른다. */
  freshAttempt: number | null;
  /** 연출 열쇠 — 같은 줄을 다시 그려도 연출이 처음부터 돌게. */
  fxSeq: number;
}

/**
 * 온도계 판 — <b>모두가 보는 것만</b> 그린다: 차수마다 공개된 힌트 하나와 그 온도(쌓여 보인다), 힌트 단계에는 지금까지 낸 <b>개수</b>만
 * (낱말·온도는 출제자만 안다 — 룰 §12). 96 이상이면 "거의 정답" 띠와 끓는 연출이 붙는다.
 */
export function HintBoard({ phase, attempt, revealed, hintCount, maxHints, setterName, mine = false, freshAttempt, fxSeq }: Props) {
  // 이번 차수가 이미 공개됐으면(추리 단계 시작 프레임이 곧 온다) "고르는 중" 줄은 걷는다.
  const hinting = phase === 'HINT' && !revealed.some((r) => r.attempt === attempt);
  return (
    <section className="wt-board" aria-label="공개된 힌트">
      <h3 className="wt-board-title">
        공개된 힌트 <span className="wt-board-sub">온도가 높을수록 정답에 가까워요</span>
      </h3>
      {revealed.length === 0 && !hinting && <p className="wt-board-empty">아직 공개된 힌트가 없어요.</p>}
      <ol className="wt-reveals">
        {revealed.map((r) => {
          const band = tempBand(r.temp);
          const fresh = freshAttempt === r.attempt;
          return (
            <li
              key={fresh ? `${r.attempt}-${fxSeq}` : r.attempt}
              className={`wt-reveal wt-band-${band}${fresh ? ' wt-reveal-fresh' : ''}`}
              data-attempt={r.attempt}
            >
              <span className="wt-reveal-attempt">{r.attempt}차</span>
              <span className="wt-reveal-word">{r.text}</span>
              <TempGauge temp={r.temp} floor={r.floor} fresh={fresh} />
              <span className="wt-reveal-note">
                <span className={`wt-band-chip wt-band-${band}`}>{BAND_LABEL[band]}</span>
                {r.temp < BOIL_FROM && (
                  <span className={r.floorMet ? 'wt-floor-ok' : 'wt-floor-miss'}>
                    최저 {r.floor}° {r.floorMet ? '넘김' : '못 넘김'}
                  </span>
                )}
              </span>
              {/* 96 이상 — 끓어오르는 방울(장식, 눌림 통과). 연출이 꺼져 있으면 CSS 가 그리지 않는다 */}
              {fresh && band === 'boil' && (
                <span className="wt-fx-boil" aria-hidden="true">
                  {Array.from({ length: 8 }, (_, i) => (
                    <span key={i} className="wt-fx-bubble" style={{ ['--wt-i' as string]: i }} />
                  ))}
                </span>
              )}
            </li>
          );
        })}
        {hinting && (
          <li className="wt-reveal wt-reveal-wait" data-attempt={attempt}>
            <span className="wt-reveal-attempt">{attempt}차</span>
            <span className="wt-reveal-word wt-reveal-word-wait">
              {mine ? '가장 높은 힌트 하나가 여기에 공개돼요' : `${setterName} 님이 힌트를 고르는 중`}
            </span>
            <span className="wt-pips" role="img" aria-label={`힌트 ${hintCount}개, 최대 ${maxHints}개`}>
              {Array.from({ length: maxHints }, (_, i) => (
                <span key={i} className={`wt-pip${i < hintCount ? ' wt-pip-on' : ''}`} />
              ))}
              <span className="wt-pips-text" aria-hidden="true">
                힌트 {hintCount}/{maxHints}
              </span>
            </span>
          </li>
        )}
      </ol>
    </section>
  );
}
