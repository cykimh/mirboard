import { beforeEach, describe, expect, it, vi } from 'vitest';
import { BUSY_RETRY_WINDOW_MS, floorOf, iAmPlaying, iAmSetter, scoringInProgress, useWordTempStore } from './wordTempStore';
import { mine, seat, seedStore, view } from './wordTempTestFixtures';

const st = () => useWordTempStore.getState();
const ev = (type: string, payload: unknown = {}) => st().applyEvent({ type, payload });

describe('wordTempStore — 스냅샷', () => {
  beforeEach(() => seedStore());

  it('공개 뷰를 그대로 비추고 단계 시계는 phaseRemainingMs 로 건다(바깥 turnRemainingMs 는 쓰지 않는다)', () => {
    st().applySnapshot({
      roomId: 'r1',
      phase: 'HINT',
      eventSeq: 3,
      tableView: view({ phaseRemainingMs: 42_000, hintCount: 2 }),
      privateHand: mine(),
      turnRemainingMs: 999_000,
    });
    expect(st().phase).toBe('HINT');
    expect(st().clock?.remainingMs).toBe(42_000);
    expect(st().hintCount).toBe(2);
    expect(st().guessSeats).toEqual([0, 2, 3]);
    expect(st().candidates).toHaveLength(3);
    expect(st().chosen).toBe(0);
    expect(st().floors).toEqual([70, 75, 80, 85, 90]);
    expect(floorOf(st(), 2)).toBe(75);
    expect(floorOf({ floors: [] }, 1)).toBeNull(); // 서버가 주지 않으면 눈금 없음
  });

  it('관전자·봇(비공개 뷰 null)은 본인 칸이 전부 비어 있다', () => {
    seedStore({}, null, -1);
    expect(st().candidates).toEqual([]);
    expect(st().answer).toBeNull();
    expect(st().hints).toEqual([]);
    expect(st().myGuess).toBeNull();
    expect(iAmPlaying(st())).toBe(false);
  });

  it('힌트는 공개 뷰로 온다(D-320) — 채점 중 포함, 무효는 뺀다. 출제자의 비공개 뷰는 정답뿐', () => {
    seedStore(
      {
        setterSeat: 0,
        hintCount: 2,
        hints: [
          { text: '여름', attempt: 1, status: 'SCORED', temp: 58 },
          { text: '짠맛', attempt: 1, status: 'PENDING', temp: -1 },
          { text: '아야', attempt: 1, status: 'VOID', temp: -1 },
        ],
      },
      mine({ answer: '바다' }),
    );
    expect(iAmSetter(st())).toBe(true);
    expect(st().answer).toBe('바다');
    expect(st().hints.map((h) => h.text)).toEqual(['여름', '짠맛']);
    expect(scoringInProgress(st())).toBe(true);
    // 관전자도 같은 힌트를 본다(정답은 없다)
    seedStore({ hints: [{ text: '여름', attempt: 1, status: 'SCORED', temp: 58 }] }, null, -1);
    expect(st().hints).toHaveLength(1);
    expect(st().answer).toBeNull();
  });

  it('결과 보기 단계의 스냅샷은 방금 차수의 점수 증감을 다시 만든다(맞힌 사람 +1×라운드)', () => {
    seedStore({
      phase: 'ATTEMPT_RESULT',
      round: 2,
      attempts: [
        {
          attempt: 1,
          setterGain: 0,
          guesses: [
            { seat: 0, text: '바다', pass: false, correct: true, masked: false },
            { seat: 2, text: null, pass: true, correct: false, masked: false },
          ],
        },
      ],
    });
    expect(st().deltas).toEqual({ 0: 2 });
  });

  it('끝난 판은 시계가 없다', () => {
    seedStore({
      phase: 'FINISHED',
      result: { endReason: 'FINISHED', draw: false, standings: [] },
    });
    expect(st().clock).toBeNull();
    expect(ev('HINT_COUNT', { count: 1, max: 5 })).toBe('ignored');
  });
});

describe('wordTempStore — 공개 이벤트', () => {
  beforeEach(() => seedStore());

  it('첫 스냅샷 전의 프레임은 unhandled(권위 스냅샷으로)', () => {
    st().reset('r2');
    expect(ev('HINT_COUNT', { count: 1, max: 5 })).toBe('unhandled');
  });

  it('힌트 단계 — 낼 때마다 낱말과 온도가 모두에게 쌓인다(채점 중 → 온도, 무효는 사라진다)', () => {
    expect(ev('HINT_SCORED', { seat: 1, text: '여름', temp: 58, count: 1 })).toBe('applied');
    expect(ev('HINT_COUNT', { count: 1, max: 10 })).toBe('applied');
    expect(st().hints).toEqual([{ text: '여름', attempt: 1, status: 'SCORED', temp: 58 }]);
    expect(st().hintCount).toBe(1);
    expect(st().freshScored).toBe('여름');
    ev('HINT_PENDING', { seat: 1, text: '짠맛' });
    expect(scoringInProgress(st())).toBe(true);
    ev('HINT_PENDING', { seat: 1, text: '짠맛' }); // 같은 프레임이 또 와도 한 줄
    expect(st().hints).toHaveLength(2);
    ev('HINT_SCORED', { seat: 1, text: '짠맛', temp: 64, count: 2 });
    expect(st().hints.map((h) => [h.text, h.status, h.temp])).toEqual([
      ['여름', 'SCORED', 58],
      ['짠맛', 'SCORED', 64],
    ]);
    ev('HINT_PENDING', { seat: 1, text: '달려' });
    ev('HINT_VOID', { seat: 1, text: '달려', reason: 'NOT_A_NOUN', count: 2 });
    expect(st().hints.map((h) => h.text)).toEqual(['여름', '짠맛']);
    expect(st().hintCount).toBe(2);
    expect(st().revealed).toEqual([]);
  });

  it('차례가 끝나면 채점 중이던 힌트는 판에서 지운다(서버가 버린다 — 출제자 탈주 등)', () => {
    ev('HINT_SCORED', { seat: 1, text: '여름', temp: 58, count: 1 });
    ev('HINT_PENDING', { seat: 1, text: '짠맛' });
    ev('TURN_ENDED', { turnNo: 1, answer: '바다', reason: 'SETTER_OUT', totals: [0, 0, 0, 0], hints: [], remainingMs: 6_000 });
    expect(st().hints.map((h) => h.text)).toEqual(['여름']);
    expect(scoringInProgress(st())).toBe(false);
  });

  it('힌트는 차수가 지나도 쌓이고, 새 출제 차례에 비운다', () => {
    ev('HINT_SCORED', { seat: 1, text: '여름', temp: 58, count: 1 });
    ev('HINT_REVEALED', { attempt: 1, text: '여름', temp: 58, floor: 70, floorMet: false });
    ev('GUESS_PHASE_STARTED', { attempt: 1, remainingMs: 30_000, seats: [0, 2, 3] });
    ev('GUESSES_REVEALED', { attempt: 1, setterSeat: 1, setterGain: 0, guesses: [], scores: [], remainingMs: 5_000 });
    ev('HINT_PHASE_STARTED', { turnNo: 1, attempt: 2, remainingMs: 90_000 });
    expect(st().hintCount).toBe(0);
    ev('HINT_SCORED', { seat: 1, text: '갈매기', temp: 76, count: 1 });
    expect(st().hints.map((h) => [h.text, h.attempt])).toEqual([
      ['여름', 1],
      ['갈매기', 2],
    ]);
    ev('TURN_STARTED', { turnNo: 2, round: 1, setterSeat: 2 });
    expect(st().hints).toEqual([]);
  });

  it('공개 → 추리 → 동시 공개 → 차례 끝까지 한 바퀴', () => {
    ev('HINT_REVEALED', { attempt: 1, text: '갈매기', temp: 71, floor: 70, floorMet: true });
    expect(st().revealed).toEqual([{ attempt: 1, text: '갈매기', temp: 71, floor: 70, floorMet: true }]);
    expect(st().freshReveal).toBe(1);
    // 같은 차수의 공개가 또 와도 쌓이지 않는다
    ev('HINT_REVEALED', { attempt: 1, text: '갈매기', temp: 71, floor: 70, floorMet: true });
    expect(st().revealed).toHaveLength(1);

    ev('GUESS_PHASE_STARTED', { attempt: 1, remainingMs: 30_000, seats: [0, 2, 3] });
    expect(st().phase).toBe('GUESS');
    expect(st().clock?.remainingMs).toBe(30_000);
    ev('GUESS_SUBMITTED', { seat: 2 });
    ev('GUESS_SUBMITTED', { seat: 2 });
    expect(st().guessedSeats).toEqual([2]);

    const revealed = {
      attempt: 1,
      setterSeat: 1,
      setterGain: 1,
      guesses: [
        { seat: 0, text: '항구', pass: false, correct: false, masked: false },
        { seat: 2, text: '···', pass: false, correct: false, masked: true },
        { seat: 3, text: null, pass: true, correct: false, masked: false },
      ],
      scores: [
        { seat: 0, delta: 0, total: 0 },
        { seat: 1, delta: 1, total: 1 },
      ],
      remainingMs: 5_000,
    };
    ev('GUESSES_REVEALED', revealed);
    expect(st().phase).toBe('ATTEMPT_RESULT');
    expect(st().clock?.remainingMs).toBe(5_000); // D-319 — 결과 보기도 시계가 돈다
    expect(st().attempts).toHaveLength(1);
    expect(st().seats[1]).toMatchObject({ total: 1, setterPoints: 1, guessPoints: 0 });
    expect(st().deltas).toEqual({ 1: 1 });
    expect(st().freshResult).toBe(1);
    // 같은 이벤트를 두 번 받아도 점수가 두 번 오르지 않는다
    ev('GUESSES_REVEALED', revealed);
    expect(st().seats[1].setterPoints).toBe(1);
    expect(st().attempts).toHaveLength(1);

    ev('HINT_PHASE_STARTED', { turnNo: 1, attempt: 2, remainingMs: 90_000 });
    expect(st()).toMatchObject({ phase: 'HINT', attempt: 2, hintCount: 0, deltas: {}, freshReveal: null });
    ev('HINT_REVEALED', { attempt: 2, text: '짠맛', temp: 64, floor: 75, floorMet: true });
    ev('GUESS_PHASE_STARTED', { attempt: 2, remainingMs: 30_000, seats: [0, 2, 3] });
    ev('GUESSES_REVEALED', {
      attempt: 2,
      setterSeat: 1,
      setterGain: 0,
      guesses: [{ seat: 0, text: '바다', pass: false, correct: true, masked: false }],
      scores: [{ seat: 0, delta: 1, total: 1 }],
    });
    expect(st().seats[0]).toMatchObject({ total: 1, guessPoints: 1, setterPoints: 0 });

    const ended = {
      turnNo: 1,
      answer: '바다',
      reason: 'SOLVED',
      totals: [1, 1, 0, 0],
      remainingMs: 6_000,
      hints: [
        { attempt: 1, text: '여름', temp: 58, revealed: false },
        { attempt: 1, text: '갈매기', temp: 71, revealed: true },
        { attempt: 2, text: '짠맛', temp: 64, revealed: true },
      ],
    };
    ev('TURN_ENDED', ended);
    expect(st().phase).toBe('TURN_END');
    expect(st().clock?.remainingMs).toBe(6_000);
    expect(st().turnReveal?.answer).toBe('바다');
    expect(st().turnReveal?.hints).toHaveLength(3);
    expect(st().history).toEqual([
      {
        turnNo: 1,
        round: 1,
        setterSeat: 1,
        answer: '바다',
        revealed: st().revealed,
        solvers: [0],
        setterGain: 1,
        reason: 'SOLVED',
      },
    ]);
    ev('TURN_ENDED', ended);
    expect(st().history).toHaveLength(1);
  });

  it('힌트 없이 끝난 힌트 단계(text null)도 차수 요약으로 남는다 — 기준 못 넘김, 추리로 간다(D-320)', () => {
    expect(ev('HINT_REVEALED', { attempt: 1, text: null, temp: -1, floor: 70, floorMet: false })).toBe('applied');
    expect(st().revealed).toEqual([{ attempt: 1, text: null, temp: -1, floor: 70, floorMet: false }]);
    expect(st().freshReveal).toBe(1);
  });

  it('새 출제 차례 — 지난 차례의 공개 힌트·결과·내 힌트·내 답을 비운다', () => {
    ev('HINT_REVEALED', { attempt: 1, text: '갈매기', temp: 71, floor: 70, floorMet: true });
    useWordTempStore.setState({ myGuess: '항구' });
    ev('TURN_STARTED', { turnNo: 2, round: 1, setterSeat: 2 });
    expect(st()).toMatchObject({ turnNo: 2, setterSeat: 2, attempt: 1, revealed: [], attempts: [], myGuess: null, answer: null });
  });

  it('내 출제 차례가 시작되면 고른 후보가 정답이 된다', () => {
    ev('TURN_STARTED', { turnNo: 2, round: 1, setterSeat: 0 });
    expect(st().answer).toBe('바다');
    expect(st().resyncNonce).toBe(0);
  });

  it('서버가 대신 골라 무엇인지 모르면 다시 받는다', () => {
    seedStore({ phase: 'WORD_SELECT', setterSeat: -1, seats: [seat(0, { chosen: false }), seat(1), seat(2)] }, mine({ chosen: -1 }));
    ev('WORD_SELECTED', { seat: 0 });
    expect(st().seats[0].chosen).toBe(true);
    expect(st().resyncNonce).toBe(1);
  });

  it('내가 고른 낱말 — 보낸 번호를 믿지 않고 비공개 뷰로 확정한다(마감 직전 서버의 대신 고르기와 엇갈릴 수 있다)', () => {
    seedStore({ phase: 'WORD_SELECT', setterSeat: -1, seats: [seat(0, { chosen: false }), seat(1, { chosen: false })] }, mine({ chosen: -1 }));
    st().noteSent({ '@action': 'SELECT_WORD', round: 1, index: 2 });
    expect(st().sending).not.toBeNull();
    ev('WORD_SELECTED', { seat: 1 });
    expect(st().chosen).toBe(-1);
    expect(st().resyncNonce).toBe(0);
    ev('WORD_SELECTED', { seat: 0 });
    expect(st().chosen).toBe(-1); // 보낸 2 로 확정하지 않는다
    expect(st().pickSent).toBe(2); // 고른 표시는 다시 받을 때까지 이어진다
    expect(st().sending).toBeNull();
    expect(st().resyncNonce).toBe(1);
  });

  it('마감 직전 고르기가 거절되고 서버가 다른 후보를 골랐다 — 출제 차례에도 보낸 후보를 정답으로 보이지 않는다', () => {
    seedStore({ phase: 'WORD_SELECT', setterSeat: -1, seats: [seat(0, { chosen: false }), seat(1, { chosen: false })] }, mine({ chosen: -1 }));
    st().noteSent({ '@action': 'SELECT_WORD', round: 1, index: 2 });
    ev('WORD_SELECTED', { seat: 0 }); // 실제로는 서버가 0 번을 골랐다
    ev('TURN_STARTED', { turnNo: 1, round: 1, setterSeat: 0 });
    expect(st().answer).toBeNull();
    expect(st().resyncNonce).toBe(2);
    st().setError('지금은 할 수 없어요');
    // 다시 받은 비공개 뷰가 정본이다
    st().applySnapshot({
      roomId: 'r1',
      phase: 'HINT',
      eventSeq: 9,
      tableView: view({ phase: 'HINT', setterSeat: 0 }),
      privateHand: mine({ chosen: 0, answer: '바다' }),
      turnRemainingMs: 0,
    });
    expect(st().answer).toBe('바다');
    expect(st().chosen).toBe(0);
  });

  it('다음 라운드 낱말 고르기 — 고른 표시를 지우고, 방금 쓴 내 후보는 새 목록이 오기 전에도 못 고르게', () => {
    ev('WORD_SELECT_STARTED', { round: 2, remainingMs: 20_000 });
    expect(st()).toMatchObject({ phase: 'WORD_SELECT', round: 2, chosen: -1, setterSeat: -1 });
    expect(st().seats.every((s) => !s.chosen)).toBe(true);
    expect(st().candidates.find((c) => c.index === 0)?.available).toBe(false);
    st().applyPrivate('WORD_CANDIDATES', {
      seat: 0,
      round: 2,
      candidates: [
        { index: 0, word: '바다', category: '자연', gloss: '', available: false },
        { index: 1, word: '자전거', category: '탈것', gloss: '', available: true },
        { index: 2, word: '김밥', category: '음식', gloss: '', available: true },
      ],
    });
    expect(st().candidatesRound).toBe(2);
    expect(st().chosen).toBe(-1);
  });

  it('새 라운드의 후보 목록이 공개 프레임보다 먼저 와도 지난 라운드의 고름이 남지 않는다', () => {
    st().applyPrivate('WORD_CANDIDATES', { seat: 0, round: 2, candidates: mine().candidates });
    expect(st().chosen).toBe(-1);
    ev('WORD_SELECT_STARTED', { round: 2, remainingMs: 20_000 });
    expect(st().chosen).toBe(-1);
    expect(st().candidates.every((c) => c.available)).toBe(true);
  });

  it('좌석이 빠지면 추리할 좌석에서도 빠진다', () => {
    ev('SEAT_OUT', { seat: 2 });
    expect(st().seats[2].alive).toBe(false);
    expect(st().guessSeats).toEqual([0, 3]);
  });

  it('판 끝 — 결과를 받고 그 뒤 프레임은 버린다(끊김 표시는 받는다)', () => {
    const result = { endReason: 'FINISHED', draw: false, standings: [{ seat: 0, rank: 1, total: 3, setterPoints: 1, guessPoints: 2, out: false }] };
    ev('MATCH_ENDED', { result });
    expect(st().result).toEqual(result);
    expect(st().phase).toBe('FINISHED');
    expect(ev('TURN_STARTED', { turnNo: 9, round: 1, setterSeat: 0 })).toBe('ignored');
    expect(ev('PLAYER_DISCONNECTED', { seat: 2 })).toBe('applied');
    expect(st().disconnectedSeats.has(2)).toBe(true);
  });

  it('모르는 타입은 ignored', () => {
    expect(ev('SOMETHING_NEW')).toBe('ignored');
  });
});

describe('wordTempStore — 본인 큐', () => {
  beforeEach(() => seedStore({ setterSeat: 0 }, mine({ answer: '바다' })));

  it('본인 큐의 힌트 프레임은 받지 않는다 — 힌트는 공개 이벤트로만 쌓인다(D-320)', () => {
    st().applyPrivate('HINT_SCORED', { seat: 0, text: '여름', temp: 58, count: 1 });
    st().applyPrivate('HINT_PENDING', { seat: 0, text: '짠맛' });
    expect(st().hints).toEqual([]);
  });

  it('남의 좌석 앞으로 온 것은 받지 않는다(남의 비밀이 들어올 길이 없다)', () => {
    st().applyPrivate('GUESS_ACCEPTED', { seat: 2, text: '항구' });
    expect(st().hints).toEqual([]);
    expect(st().myGuess).toBeNull();
    // 관전자(좌석 −1)는 아무것도 받지 않는다
    seedStore({}, null, -1);
    st().applyPrivate('GUESS_ACCEPTED', { seat: -1, text: '항구' });
    expect(st().myGuess).toBeNull();
  });

  it('내 답이 받아들여지면 myGuess — 빈 문자열은 패스', () => {
    seedStore({ phase: 'GUESS' });
    st().noteSent({ '@action': 'SUBMIT_GUESS', turnNo: 1, attempt: 1, text: '' });
    st().applyPrivate('GUESS_ACCEPTED', { seat: 0, text: '' });
    expect(st().myGuess).toBe('');
    expect(st().sending).toBeNull();
  });
});

describe('wordTempStore — 보내기 막음·BUSY 재전송', () => {
  beforeEach(() => {
    vi.useRealTimers();
    seedStore({ setterSeat: 0 }, mine({ answer: '바다' }));
  });

  it('BUSY 는 한 번만 다시 보낸다 — 두 번째는 오류로', () => {
    st().noteSent({ '@action': 'SUBMIT_HINT', turnNo: 1, attempt: 1, text: '여름' });
    expect(st().noteBusy()).toBe(true);
    expect(st().retryNonce).toBe(1);
    expect(st().sending?.retried).toBe(true);
    expect(st().noteBusy()).toBe(false);
  });

  it('보낸 것이 없거나 너무 늦은 BUSY 는 다시 보내지 않는다', () => {
    expect(st().noteBusy()).toBe(false);
    vi.useFakeTimers();
    st().noteSent({ '@action': 'END_HINTS', turnNo: 1, attempt: 1 });
    vi.advanceTimersByTime(BUSY_RETRY_WINDOW_MS + 1);
    expect(st().noteBusy()).toBe(false);
    vi.useRealTimers();
  });

  it('오류 문구가 뜨면 막음이 풀린다. 안전 타이머는 제 보냄만 푼다', () => {
    st().noteSent({ '@action': 'SUBMIT_HINT', turnNo: 1, attempt: 1, text: '수영장' });
    const first = st().sending!.id;
    st().setError('정답이 들어간 낱말은 힌트로 낼 수 없어요.');
    expect(st().sending).toBeNull();
    expect(st().errorSeq).toBeGreaterThan(0);
    st().noteSent({ '@action': 'SUBMIT_HINT', turnNo: 1, attempt: 1, text: '여름' });
    st().clearSending(first);
    expect(st().sending).not.toBeNull();
    st().clearSending(st().sending!.id);
    expect(st().sending).toBeNull();
  });
});
