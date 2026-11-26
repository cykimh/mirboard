// 낱말 온도 타입 — `docs/stomp-protocol.md` "낱말 온도" 절, 서버 `WordTempViews`·`WordTempEvent` 와 1:1.

export type WordTempPhase = 'WORD_SELECT' | 'HINT' | 'GUESS' | 'ATTEMPT_RESULT' | 'TURN_END' | 'FINISHED';
export type HintStatus = 'SCORED' | 'PENDING' | 'VOID';
export type VoidReason = 'NOT_A_NOUN' | 'SCORING_FAILED';
export type TurnEndReason = 'SOLVED' | 'THREE_ATTEMPTS' | 'NO_HINTS' | 'SETTER_OUT';
export type EndReason = 'FINISHED' | 'LAST_SEAT' | 'NO_HUMANS';

/** 차수의 공개 힌트 하나. `floor` = 그 차수의 최저 온도, `floorMet` = 넘겼는가. */
export interface Revealed {
  attempt: number;
  text: string;
  temp: number;
  floor: number;
  floorMet: boolean;
}

export interface RevealedGuess {
  seat: number;
  /** 패스면 null, 가려진 답이면 `···`. */
  text: string | null;
  pass: boolean;
  correct: boolean;
  masked: boolean;
}

export interface PublicAttempt {
  attempt: number;
  guesses: RevealedGuess[];
  setterGain: number;
}

export interface SeatView {
  seat: number;
  alive: boolean;
  bot: boolean;
  setterPoints: number;
  guessPoints: number;
  total: number;
  /** 이번 라운드 낱말을 골랐는가(무엇인지는 없다). */
  chosen: boolean;
}

export interface HintInfo {
  attempt: number;
  text: string;
  temp: number;
  revealed: boolean;
}

export interface TurnReveal {
  answer: string;
  hints: HintInfo[];
}

export interface TurnSummary {
  turnNo: number;
  round: number;
  setterSeat: number;
  answer: string;
  revealed: Revealed[];
  solvers: number[];
  setterGain: number;
  reason: TurnEndReason;
}

export interface Standing {
  seat: number;
  rank: number;
  total: number;
  setterPoints: number;
  guessPoints: number;
  out: boolean;
}

export interface MatchResult {
  endReason: EndReason;
  standings: Standing[];
  draw: boolean;
}

/** 공개 뷰(`tableView`) — 후보·진행 중인 정답·미공개 힌트·공개 전 추리는 타입에 없다. */
export interface WordTempTableView {
  phase: WordTempPhase;
  round: number;
  rounds: number;
  turnNo: number;
  setterSeat: number;
  attempt: number;
  phaseRemainingMs: number;
  hintCount: number;
  maxHints: number;
  /** 차수별 최저 온도(1·2·3차) — 공개 전에도 눈금을 그린다(D-319). */
  floors: number[];
  revealed: Revealed[];
  guessedSeats: number[];
  attempts: PublicAttempt[];
  seats: SeatView[];
  order: number[];
  history: TurnSummary[];
  turnReveal: TurnReveal | null;
  result: MatchResult | null;
}

export interface CandidateInfo {
  index: number;
  word: string;
  /** 범주의 화면 이름(한글 — 서버가 붙인다). */
  category: string;
  /** 뜻풀이 한 줄(없으면 빈 문자열). */
  gloss: string;
  available: boolean;
}

/** 출제자 본인의 힌트. `temp` 는 확정이면 0~100, 아니면 −1. */
export interface OwnHint {
  text: string;
  attempt: number;
  status: HintStatus;
  temp: number;
}

/** 비공개 뷰(`privateHand`) — 봇·빠진 좌석·관전자는 null. */
export interface WordTempPrivateView {
  seat: number;
  candidates: CandidateInfo[];
  chosen: number;
  answer: string | null;
  hints: OwnHint[];
  myGuess: string | null;
}

// ── 공개 이벤트 payload ──
export interface WordSelectStartedPayload {
  round: number;
  remainingMs: number;
}
export interface TurnStartedPayload {
  turnNo: number;
  round: number;
  setterSeat: number;
}
export interface HintPhaseStartedPayload {
  turnNo: number;
  attempt: number;
  remainingMs: number;
}
export interface HintCountPayload {
  count: number;
  max: number;
}
export interface HintRevealedPayload {
  attempt: number;
  text: string | null;
  temp: number;
  floor: number;
  floorMet: boolean;
}
export interface GuessPhaseStartedPayload {
  attempt: number;
  remainingMs: number;
  seats: number[];
}
export interface ScoreChange {
  seat: number;
  delta: number;
  total: number;
}
export interface GuessesRevealedPayload {
  attempt: number;
  guesses: RevealedGuess[];
  setterSeat: number;
  setterGain: number;
  scores: ScoreChange[];
  /** 결과 보기 단계의 남은 시간. */
  remainingMs: number;
}
export interface TurnEndedPayload {
  turnNo: number;
  answer: string;
  hints: HintInfo[];
  reason: TurnEndReason;
  totals: number[];
  /** 차례 끝 단계의 남은 시간. */
  remainingMs: number;
}
export interface MatchEndedPayload {
  result: MatchResult;
}

// ── 비공개 이벤트 payload(순번 없음) ──
export interface WordCandidatesPayload {
  seat: number;
  round: number;
  candidates: CandidateInfo[];
}
export interface HintScoredPayload {
  seat: number;
  text: string;
  temp: number;
  count: number;
}
export interface HintPendingPayload {
  seat: number;
  text: string;
}
export interface HintVoidPayload {
  seat: number;
  text: string;
  reason: VoidReason;
  count: number;
}
export interface GuessAcceptedPayload {
  seat: number;
  text: string;
}
