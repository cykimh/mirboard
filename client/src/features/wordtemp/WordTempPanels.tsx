import type { CandidateInfo, PublicAttempt, Revealed, TurnEndReason, TurnReveal } from '@/types/wordtemp';
import { ThermometerArt } from './wordTempArt';
import { WordInput } from './WordInput';

// ───────────────────────── 낱말 고르기 ─────────────────────────

interface PickerProps {
  round: number;
  candidates: CandidateInfo[];
  chosen: number;
  busy: boolean;
  onPick: (index: number) => void;
}

/** 낱말 고르기(§3.1) — 내 후보(본인만 본다) 가운데 이번 라운드에 낼 정답 하나. 한 번 고르면 못 바꾼다. */
export function WordPicker({ round, candidates, chosen, busy, onPick }: PickerProps) {
  const picked = chosen >= 0;
  return (
    <section className="wt-panel wt-picker" aria-label="낱말 고르기">
      <h3 className="wt-panel-title">{picked ? '낱말을 골랐어요' : `${round}라운드에 낼 낱말을 골라 주세요`}</h3>
      <p className="wt-panel-help">
        {picked ? '다른 사람이 고르기를 기다려요. 내 차례가 오면 이 낱말이 정답이 돼요.' : '나만 보여요. 고른 낱말은 내 출제 차례의 정답이 돼요.'}
      </p>
      <ul className="wt-cands">
        {candidates.map((c) => {
          const mine = c.index === chosen;
          const off = !c.available && !mine;
          return (
            <li key={c.index}>
              <button
                type="button"
                className={`wt-cand${mine ? ' wt-cand-picked' : ''}${off ? ' wt-cand-used' : ''}`}
                disabled={off || (picked && !mine)}
                aria-pressed={mine}
                aria-disabled={busy || picked || undefined}
                onClick={() => {
                  if (!picked && !busy) onPick(c.index);
                }}
              >
                <span className="wt-cand-category">{c.category}</span>
                <span className="wt-cand-word">{c.word}</span>
                {mine && <span className="wt-cand-tag">고름</span>}
                {off && <span className="wt-cand-tag">이미 냄</span>}
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

// ───────────────────────── 출제자 ─────────────────────────

interface SetterProps {
  answer: string | null;
  attempt: number;
  /** 이번 차수의 기준 온도(서버가 준 값) — 없으면 안내에서 뺀다. */
  floor: number | null;
  /** 이번 차수에 낸 힌트 개수(확정 + 채점 중). */
  hintCount: number;
  maxHints: number;
  /** 채점 중인 힌트가 있다 — 풀릴 때까지 입력을 잠근다(한 번에 하나, §6.2). */
  scoring: boolean;
  /** 힌트를 낼 수 있는 단계인가(`HINT`). 아니면 정답만 보여 준다. */
  hinting: boolean;
  busy: boolean;
  /** 남은 시간 글자 — 자판이 올라와 머리 줄이 가려져도 입력란 곁에서 보이게. */
  clock?: string | null;
  onHint: (text: string) => boolean;
  onEnd: () => void;
}

/**
 * 출제자 칸(§3.2, D-320) — <b>출제자 본인에게만 그린다.</b> 정답(나만 본다), 이번 차수의 기준 온도, 힌트 입력, [그만 내기].
 * 낸 힌트와 온도는 위 온도계 판에 모두와 같이 보인다 — 기준 온도 이상의 힌트가 나오면 그 자리에서 추리로 넘어간다.
 */
export function SetterPanel({
  answer,
  attempt,
  floor,
  hintCount,
  maxHints,
  scoring,
  hinting,
  busy,
  clock = null,
  onHint,
  onEnd,
}: SetterProps) {
  const full = hintCount >= maxHints;
  return (
    <section className="wt-panel wt-setter" aria-label="내 문제">
      <div className="wt-setter-head">
        <span className="wt-answer">
          <span className="wt-answer-label">정답</span>
          <span className="wt-answer-word">{answer ?? '…'}</span>
        </span>
        <span className="wt-setter-meta">
          <span className="wt-count" aria-label={`힌트 ${hintCount}개, 최대 ${maxHints}개`}>
            힌트 {hintCount}/{maxHints}
          </span>
          {hinting && clock && (
            <span className="wt-inline-clock" aria-hidden="true">
              {clock}
            </span>
          )}
        </span>
      </div>
      {hinting && (
        <p className="wt-panel-help">
          {floor !== null ? (
            <>
              <strong className="wt-target">
                {attempt}차 기준 {floor}°
              </strong>{' '}
              이상인 힌트가 나오면 바로 추리로 넘어가요. 힌트와 온도는 낼 때마다 모두에게 보여요.
            </>
          ) : (
            '힌트와 온도는 낼 때마다 모두에게 보여요.'
          )}
        </p>
      )}
      {hinting && (
        <WordInput
          label="힌트 낱말"
          placeholder={scoring ? '채점 중…' : full ? '힌트를 다 냈어요' : floor !== null ? `힌트 · 기준 ${floor}°` : '힌트 낱말 (한글)'}
          submitLabel="힌트 내기"
          locked={scoring || full}
          busy={busy}
          onSubmit={onHint}
        >
          <button
            type="button"
            className="wt-secondary"
            disabled={hintCount === 0 || scoring}
            aria-disabled={busy || undefined}
            title="기준을 못 넘긴 채로 추리로 넘어가요"
            onPointerDown={(e) => e.preventDefault()}
            onClick={() => {
              if (!busy) onEnd();
            }}
          >
            그만 내기
          </button>
        </WordInput>
      )}
      {hinting && (
        <p className="wt-setter-note">
          시간이 끝나거나 {maxHints}개를 다 내도록 기준을 못 넘기면, 아무도 못 맞혀도 이번 차수 점수가 없어요.
        </p>
      )}
    </section>
  );
}

// ───────────────────────── 추리자 ─────────────────────────

interface GuessProps {
  attempt: number;
  myGuess: string | null;
  busy: boolean;
  /** 남은 시간 글자 — 자판이 올라와 머리 줄이 가려져도 입력란 곁에서 보이게. */
  clock?: string | null;
  onGuess: (text: string) => boolean;
}

/** 추리 칸(§3.3) — 답 하나(또는 패스). 한 번 내면 바꿀 수 없고, 공개 전에는 내 답만 보인다. */
export function GuessPanel({ attempt, myGuess, busy, clock = null, onGuess }: GuessProps) {
  if (myGuess !== null) {
    return (
      <section className="wt-panel wt-guess" aria-label="내 답">
        <h3 className="wt-panel-title">{myGuess === '' ? '패스했어요' : '답을 냈어요'}</h3>
        {myGuess !== '' && (
          <p className="wt-my-guess">
            <span className="wt-my-guess-label">내 답</span>
            <span className="wt-my-guess-word">{myGuess}</span>
          </p>
        )}
        <p className="wt-panel-help">모두가 내면 한꺼번에 공개돼요.</p>
      </section>
    );
  }
  return (
    <section className="wt-panel wt-guess" aria-label="정답 맞히기">
      <h3 className="wt-panel-title wt-panel-title-row">
        <span>{attempt}차 추리 — 정답은 무엇일까요?</span>
        {clock && (
          <span className="wt-inline-clock" aria-hidden="true">
            {clock}
          </span>
        )}
      </h3>
      <p className="wt-panel-help">답은 한 번만 낼 수 있어요. 모르겠으면 패스!</p>
      <WordInput label="정답 낱말" placeholder="정답 낱말 (한글)" submitLabel="답 내기" busy={busy} onSubmit={onGuess}>
        <button
          type="button"
          className="wt-secondary"
          aria-disabled={busy || undefined}
          onClick={() => {
            if (!busy) onGuess('');
          }}
        >
          패스
        </button>
      </WordInput>
    </section>
  );
}

// ───────────────────────── 차수 결과 ─────────────────────────

interface ResultProps {
  attempt: PublicAttempt;
  round: number;
  setterSeat: number;
  mySeat: number;
  revealed: Revealed | undefined;
  nameOf: (seat: number) => string;
  fresh: boolean;
}

/**
 * 차수 결과(§3.4) — 모두의 답을 한꺼번에 보여 준다. 오답도 낱말 그대로(가려진 답은 서버가 `···` 로 보낸다), 맞힌 사람과 얻은 점수(×라운드),
 * 아무도 못 맞혔으면 출제자 점수(차수 번호 × 라운드) — 그 차수가 기준 온도를 못 넘겼으면 점수가 없는 이유를 말해 준다(D-320).
 */
export function AttemptResultPanel({ attempt, round, setterSeat, mySeat, revealed, nameOf, fresh }: ResultProps) {
  const solvers = attempt.guesses.filter((g) => g.correct);
  const solved = solvers.length > 0;
  const iSolved = solvers.some((g) => g.seat === mySeat);
  let summary: string;
  if (solved) {
    summary = `${solvers.map((g) => nameOf(g.seat)).join(' · ')} 정답! +${round}점${solvers.length > 1 ? '씩' : ''}`;
  } else if (attempt.setterGain > 0) {
    summary = `아무도 못 맞혔어요 — 출제자 ${nameOf(setterSeat)} +${attempt.setterGain}점`;
  } else if (revealed && !revealed.floorMet) {
    summary = '아무도 못 맞혔지만, 기준을 못 넘겨 출제자도 이번 차수 점수가 없어요';
  } else {
    summary = '아무도 못 맞혔어요';
  }
  return (
    <section
      className={`wt-panel wt-result${solved ? ' wt-result-solved' : ''}${fresh ? ' wt-result-fresh' : ''}`}
      aria-label={`${attempt.attempt}차 결과`}
    >
      <h3 className="wt-panel-title">{attempt.attempt}차 결과</h3>
      <ul className="wt-guesses">
        {attempt.guesses.map((g, i) => (
          <li
            key={g.seat}
            className={`wt-guess-row${g.correct ? ' wt-guess-correct' : ''}${g.pass ? ' wt-guess-pass' : ''}`}
            style={{ ['--wt-i' as string]: i }}
          >
            <span className="wt-guess-name">
              {nameOf(g.seat)}
              {g.seat === mySeat ? ' (나)' : ''}
            </span>
            <span className="wt-guess-word" aria-label={g.masked ? '가려진 답' : undefined}>
              {g.pass ? '패스' : g.text}
            </span>
            <span className="wt-guess-mark">{g.correct ? '정답!' : g.pass ? '' : '아쉬워요'}</span>
          </li>
        ))}
      </ul>
      <p className="wt-result-summary" role="status">
        {summary}
      </p>
      {/* 정답 축하 — 작은 불꽃 방울들(장식, 눌림 통과). 내가 맞혔으면 더 크게 */}
      {fresh && solved && (
        <span className={`wt-fx-cheer${iSolved ? ' wt-fx-cheer-mine' : ''}`} aria-hidden="true">
          {Array.from({ length: 10 }, (_, i) => (
            <span key={i} className="wt-fx-spark" style={{ ['--wt-i' as string]: i }} />
          ))}
        </span>
      )}
    </section>
  );
}

// ───────────────────────── 차례 끝 ─────────────────────────

const END_TEXT: Record<TurnEndReason, string> = {
  SOLVED: '정답을 맞혔어요!',
  ALL_ATTEMPTS: '마지막 차수까지 아무도 못 맞혔어요.',
  THREE_ATTEMPTS: '마지막 차수까지 아무도 못 맞혔어요.',
  NO_HINTS: '힌트가 없어 차례가 끝났어요.',
  SETTER_OUT: '출제자가 나가 차례가 끝났어요.',
};

interface TurnEndProps {
  reveal: TurnReveal;
  reason: TurnEndReason | null;
  setterName: string;
  /** 다음에 무슨 일이 있나(다음 출제자·다음 라운드·판 끝). */
  next: string;
}

/** 차례 끝(§3) — 정답 낱말을 공개한다. 힌트와 온도는 위 온도계 판에 그대로 남아 있다(D-320 — 낼 때부터 공개였다). */
export function TurnEndPanel({ reveal, reason, setterName, next }: TurnEndProps) {
  const top = reveal.hints.reduce((m, h) => Math.max(m, h.temp), 0);
  return (
    <section className="wt-panel wt-turn-end" aria-label="차례 끝">
      <p className="wt-turn-end-reason">{reason ? END_TEXT[reason] : '차례가 끝났어요.'}</p>
      <div className="wt-turn-answer">
        <ThermometerArt className="wt-turn-art" size={52} level={Math.max(0.15, top / 100)} />
        <span className="wt-turn-answer-text">
          <span className="wt-answer-label">{setterName} 님의 정답</span>
          <span className="wt-turn-answer-word">{reveal.answer}</span>
        </span>
      </div>
      <p className="wt-turn-next">{next}</p>
    </section>
  );
}
