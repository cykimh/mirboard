import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '@/features/auth/authStore';
import { useCardAnimStore } from '@/features/theme/cardAnimStore';
import { BUSY_RETRY_DELAY_MS, LOAD_RETRY_MAX, LOAD_RETRY_MS, WordTempTable } from './WordTempTable';
import { wordTempRoomSink } from './wordTempRoomSink';
import { SEND_GUARD_MS, useWordTempStore } from './wordTempStore';
import { mine, seat, seedStore } from './wordTempTestFixtures';

const sendAction = vi.fn((_: Record<string, unknown>) => true);
const requestResync = vi.fn();
let socketConnected = true;
vi.mock('@/ws/useStompRoom', () => ({
  useStompRoom: () => ({
    connected: socketConnected,
    sendAction: (a: Record<string, unknown>) => sendAction(a),
    sendChat: vi.fn(),
    sendReaction: vi.fn(),
    sendVoice: vi.fn(),
    subscribeVoice: () => () => {},
    chatPanelOpenRef: { current: false },
    requestResync,
  }),
}));

const PLAYERS = [10, 20, 30, 40];
const NAMES = { 10: '가람', 20: '나래', 30: '다온', 40: '봇 라온' };
const st = () => useWordTempStore.getState();
const priv = (type: string, payload: unknown) =>
  act(() => wordTempRoomSink.applyPrivateEvent({ eventId: 'e', type, ts: 0, payload }));
const pub = (type: string, payload: unknown) => act(() => void wordTempRoomSink.applyEvent({ type, payload }));

function renderTable(props: Partial<React.ComponentProps<typeof WordTempTable>> = {}) {
  return render(
    <WordTempTable roomId="r1" playerIds={PLAYERS} myUserId={10} usernames={NAMES} botSeats={[3]} onExit={vi.fn()} {...props} />,
  );
}

/** 출제자(좌석 1 — 나래)만 아는 것. 추리자·관전자 화면 어디에도 나오면 안 된다. */
const SETTER_SECRETS = ['바다', '여름', '갈매기', '58', '71'];

describe('WordTempTable (S3)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    sendAction.mockClear();
    sendAction.mockImplementation(() => true);
    requestResync.mockClear();
    socketConnected = true;
    useAuthStore.setState({ token: 't' } as never);
    useCardAnimStore.setState({ enabled: true, forced: false });
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  // ── 낱말 고르기 ──
  it('낱말 고르기 — 내 후보(범주·낱말)를 보여 주고, 고르면 SELECT_WORD 한 번', () => {
    seedStore(
      { phase: 'WORD_SELECT', setterSeat: -1, attempt: 0, phaseRemainingMs: 20_000, seats: [seat(0, { chosen: false }), seat(1), seat(2, { chosen: false }), seat(3, { bot: true, chosen: false })] },
      mine({ chosen: -1 }),
    );
    renderTable();
    const picker = screen.getByRole('region', { name: '낱말 고르기' });
    expect(within(picker).getByRole('button', { name: /자연\s*바다/ })).toBeInTheDocument();
    expect(screen.getByRole('timer')).toHaveTextContent('20');
    // 남의 상태 — 내용 없이 "고르는 중 / 골랐어요", 봇은 "생각 중…"
    const seats = screen.getByRole('list', { name: '좌석' });
    expect(within(seats).getByText('골랐어요')).toBeInTheDocument();
    expect(within(seats).getAllByText('고르는 중')).toHaveLength(2);
    expect(within(seats).getByText('생각 중…')).toBeInTheDocument();

    fireEvent.click(within(picker).getByRole('button', { name: /김밥/ }));
    fireEvent.click(within(picker).getByRole('button', { name: /김밥/ }));
    expect(sendAction).toHaveBeenCalledTimes(1);
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'SELECT_WORD', round: 1, index: 2 });
    pub('WORD_SELECTED', { seat: 0 });
    expect(within(picker).getByRole('button', { name: /김밥/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('낱말을 골랐어요')).toBeInTheDocument();
  });

  // ── 비밀 ──
  it('추리자 — 힌트 단계에는 개수와 시계만 보인다. 출제자의 낱말·온도는 화면 어디에도 없다', () => {
    seedStore({ hintCount: 2 });
    const { container } = renderTable();
    expect(screen.getByRole('img', { name: '힌트 2개, 최대 5개' })).toBeInTheDocument();
    expect(screen.getByText('나래 님이 힌트를 고르는 중')).toBeInTheDocument();
    expect(screen.getByRole('timer')).toHaveTextContent('1:30');
    expect(screen.queryByRole('region', { name: '내 문제' })).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(container.querySelector('.wt-gauge')).toBeNull();
    for (const secret of SETTER_SECRETS) expect(container.textContent).not.toContain(secret);
  });

  it('추리자 — 남의 좌석 앞으로 온 채점 프레임이 끼어들어도 그리지 않는다', () => {
    seedStore({ hintCount: 1 });
    const { container } = renderTable();
    priv('HINT_SCORED', { seat: 1, text: '갈매기', temp: 71, count: 1 });
    priv('HINT_PENDING', { seat: 1, text: '여름' });
    expect(st().hints).toEqual([]);
    expect(container.querySelector('.wt-gauge')).toBeNull();
    expect(container.textContent).not.toContain('갈매기');
    expect(container.textContent).not.toContain('채점 중');
  });

  it('추리자 — 스토어에 남의 힌트가 들어 있더라도(있을 수 없는 일) 출제자 칸은 내가 출제자일 때만 그린다', () => {
    seedStore({ hintCount: 2 });
    act(() =>
      useWordTempStore.setState({
        answer: '바다',
        hints: [
          { text: '여름', attempt: 1, status: 'SCORED', temp: 58 },
          { text: '갈매기', attempt: 1, status: 'SCORED', temp: 71 },
        ],
      }),
    );
    const { container } = renderTable();
    expect(screen.queryByRole('region', { name: '내 문제' })).not.toBeInTheDocument();
    for (const secret of SETTER_SECRETS) expect(container.textContent).not.toContain(secret);
  });

  it('관전자 — 공개된 것만. 입력 칸도 후보도 없다', () => {
    seedStore({ hintCount: 3, revealed: [] }, null, -1);
    const { container } = renderTable({ spectator: true, myUserId: 99 });
    expect(screen.getByText('👀 관전 중')).toBeInTheDocument();
    expect(screen.getByText(/관전 중 — 공개된 것만/)).toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(container.querySelector('.wt-setter, .wt-guess, .wt-picker')).toBeNull();
    expect(screen.getByRole('img', { name: '힌트 3개, 최대 5개' })).toBeInTheDocument();
  });

  it('공개 뒤에는 모두가 그 차수의 힌트 하나와 온도를 본다(쌓여 보인다)', () => {
    seedStore({ hintCount: 2 });
    renderTable();
    pub('HINT_REVEALED', { attempt: 1, text: '갈매기', temp: 71, floor: 50, floorMet: true });
    const board = screen.getByRole('region', { name: '공개된 힌트' });
    expect(within(board).getByText('갈매기')).toBeInTheDocument();
    expect(within(board).getByRole('img', { name: '온도 71도, 미지근' })).toBeInTheDocument();
    expect(within(board).getByText('최저 50° 넘김')).toBeInTheDocument();
    // 방금 공개된 줄만 수은주가 차오른다
    expect(board.querySelector('.wt-reveal-fresh .wt-gauge-fresh')).not.toBeNull();
    // 출제자가 낸 다른 힌트(여름 58°)는 차례 끝까지 없다
    expect(board.textContent).not.toContain('여름');
  });

  // ── 출제자 ──
  it('출제자 — 정답·입력·개수, 낸 힌트의 온도는 본인 큐로 오면 보인다', () => {
    seedStore({ setterSeat: 0 }, mine({ answer: '바다' }));
    renderTable();
    const panel = screen.getByRole('region', { name: '내 문제' });
    expect(within(panel).getByText('바다')).toBeInTheDocument();
    expect(within(panel).getByLabelText('힌트 0개, 최대 5개')).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: '힌트 끝' })).toBeDisabled();

    const input = within(panel).getByRole('textbox', { name: '힌트 낱말' });
    fireEvent.change(input, { target: { value: '여름' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'SUBMIT_HINT', turnNo: 1, attempt: 1, text: '여름' });
    // 응답 전 — 버튼은 aria-disabled, 또 눌러도 나가지 않는다
    expect(within(panel).getByRole('button', { name: '힌트 내기' })).toHaveAttribute('aria-disabled', 'true');
    fireEvent.change(input, { target: { value: '갈매기' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(sendAction).toHaveBeenCalledTimes(1);

    priv('HINT_SCORED', { seat: 0, text: '여름', temp: 58, count: 1 });
    pub('HINT_COUNT', { count: 1, max: 5 });
    expect(within(panel).getByRole('img', { name: '온도 58도, 미지근' })).toBeInTheDocument();
    expect(within(panel).getByText('공개될 힌트')).toBeInTheDocument();
    expect(within(panel).getByLabelText('힌트 1개, 최대 5개')).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: '힌트 끝' })).toBeEnabled();

    fireEvent.click(within(panel).getByRole('button', { name: '힌트 끝' }));
    expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'END_HINTS', turnNo: 1, attempt: 1 });
  });

  it('출제자 — 조합 중의 Enter 는 힌트를 보내지 않는다', () => {
    seedStore({ setterSeat: 0 }, mine({ answer: '바다' }));
    renderTable();
    const input = screen.getByRole('textbox', { name: '힌트 낱말' });
    fireEvent.compositionStart(input);
    fireEvent.change(input, { target: { value: '갈매ㄱ' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(sendAction).not.toHaveBeenCalled();
    fireEvent.change(input, { target: { value: '갈매기' } });
    fireEvent.compositionEnd(input);
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(sendAction).toHaveBeenCalledTimes(1);
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'SUBMIT_HINT', turnNo: 1, attempt: 1, text: '갈매기' });
  });

  it('출제자 — 채점 중에는 입력·[힌트 끝]이 잠기고, 무효가 되면 풀리며 이유를 알려 준다', () => {
    seedStore({ setterSeat: 0 }, mine({ answer: '바다' }));
    renderTable();
    priv('HINT_SCORED', { seat: 0, text: '여름', temp: 58, count: 1 });
    priv('HINT_PENDING', { seat: 0, text: '짠맛' });
    const panel = screen.getByRole('region', { name: '내 문제' });
    expect(within(panel).getByRole('status')).toHaveTextContent('채점 중…');
    expect(within(panel).getByRole('textbox', { name: '힌트 낱말' })).toBeDisabled();
    expect(within(panel).getByRole('button', { name: '힌트 끝' })).toBeDisabled();
    expect(screen.getByText('힌트를 채점하는 중…')).toBeInTheDocument();

    priv('HINT_VOID', { seat: 0, text: '짠맛', reason: 'NOT_A_NOUN', count: 1 });
    expect(within(panel).getByRole('textbox', { name: '힌트 낱말' })).toBeEnabled();
    expect(within(panel).queryByText('짠맛')).not.toBeInTheDocument();
    expect(screen.getByText('명사가 아니에요. 개수는 돌려드렸어요.')).toBeInTheDocument();
  });

  it('출제자 — 가장 높은 힌트가 최저 온도에 못 미치면 미리 알려 준다', () => {
    seedStore({ setterSeat: 0, hintCount: 1 }, mine({ answer: '바다', hints: [{ text: '여행', attempt: 1, status: 'SCORED', temp: 44 }] }));
    renderTable();
    expect(screen.getByRole('note')).toHaveTextContent('최저 50° 에 못 미쳐요');
  });

  it('BUSY — 보낸 힌트를 한 번 다시 보낸다. 소식이 없으면 막음을 풀고 판을 다시 받는다', () => {
    seedStore({ setterSeat: 0 }, mine({ answer: '바다' }));
    renderTable();
    const input = screen.getByRole('textbox', { name: '힌트 낱말' });
    fireEvent.change(input, { target: { value: '여름' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    priv('ERROR', { code: 'BUSY', message: 'x' });
    expect(sendAction).toHaveBeenCalledTimes(1);
    act(() => vi.advanceTimersByTime(BUSY_RETRY_DELAY_MS));
    expect(sendAction).toHaveBeenCalledTimes(2);
    expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'SUBMIT_HINT', turnNo: 1, attempt: 1, text: '여름' });
    act(() => vi.advanceTimersByTime(SEND_GUARD_MS));
    expect(st().sending).toBeNull();
    expect(requestResync).toHaveBeenCalled();
  });

  it('끊긴 사이에는 보내지 않고 알려 준다', () => {
    seedStore({ setterSeat: 0 }, mine({ answer: '바다' }));
    sendAction.mockImplementation(() => false);
    renderTable();
    const input = screen.getByRole('textbox', { name: '힌트 낱말' }) as HTMLInputElement;
    fireEvent.change(input, { target: { value: '여름' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(st().sending).toBeNull();
    expect(input.value).toBe('여름');
    expect(screen.getByText(/연결이 끊겨 보내지 못했어요/)).toBeInTheDocument();
  });

  // ── 추리 ──
  it('추리 단계 — 공개 힌트를 보고 답 하나(또는 패스). 낸 뒤에는 내 답만, 남은 "냈어요"만', () => {
    seedStore({ phase: 'GUESS', phaseRemainingMs: 30_000, revealed: [{ attempt: 1, text: '갈매기', temp: 71, floor: 50, floorMet: true }], guessedSeats: [2] });
    const { container } = renderTable();
    expect(within(screen.getByRole('list', { name: '좌석' })).getByText('냈어요')).toBeInTheDocument();
    const input = screen.getByRole('textbox', { name: '정답 낱말' });
    fireEvent.change(input, { target: { value: ' 항 구 ' } });
    fireEvent.click(screen.getByRole('button', { name: '답 내기' }));
    expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'SUBMIT_GUESS', turnNo: 1, attempt: 1, text: '항구' });
    priv('GUESS_ACCEPTED', { seat: 0, text: '항구' });
    expect(screen.getByText('답을 냈어요')).toBeInTheDocument();
    expect(screen.getByText('항구')).toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    // 남의 답은 공개 전까지 어디에도 없다
    expect(container.querySelector('.wt-guesses')).toBeNull();
  });

  it('추리 단계 — [패스]는 빈 답을 보낸다', () => {
    seedStore({ phase: 'GUESS', phaseRemainingMs: 30_000 });
    renderTable();
    fireEvent.click(screen.getByRole('button', { name: '패스' }));
    expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'SUBMIT_GUESS', turnNo: 1, attempt: 1, text: '' });
    priv('GUESS_ACCEPTED', { seat: 0, text: '' });
    expect(screen.getByText('패스했어요')).toBeInTheDocument();
  });

  it('추리 단계의 출제자 — 입력 없이 기다린다(내 힌트는 계속 본다)', () => {
    seedStore(
      { phase: 'GUESS', setterSeat: 0, phaseRemainingMs: 30_000, guessedSeats: [1] },
      mine({ answer: '바다', hints: [{ text: '갈매기', attempt: 1, status: 'SCORED', temp: 71 }] }),
    );
    renderTable();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: '내 문제' })).getByText('갈매기')).toBeInTheDocument();
    expect(screen.getByText('추리를 기다려요 (1/3)')).toBeInTheDocument();
  });

  // ── 결과 ──
  it('차수 결과 — 답을 한꺼번에 보여 준다. 가려진 답은 ···, 맞힌 사람과 점수(×라운드)', () => {
    seedStore({ phase: 'GUESS', round: 2, revealed: [{ attempt: 1, text: '갈매기', temp: 71, floor: 50, floorMet: true }] });
    renderTable();
    pub('GUESSES_REVEALED', {
      attempt: 1,
      setterSeat: 1,
      setterGain: 0,
      guesses: [
        { seat: 0, text: '바다', pass: false, correct: true, masked: false },
        { seat: 2, text: '···', pass: false, correct: false, masked: true },
        { seat: 3, text: null, pass: true, correct: false, masked: false },
      ],
      scores: [{ seat: 0, delta: 2, total: 2 }],
    });
    const result = screen.getByRole('region', { name: '1차 결과' });
    expect(within(result).getByText('정답!')).toBeInTheDocument();
    expect(within(result).getByLabelText('가려진 답')).toHaveTextContent('···');
    expect(within(result).getByText('패스')).toBeInTheDocument();
    expect(within(result).getByRole('status')).toHaveTextContent('가람 정답! +2점');
    expect(screen.getByLabelText('2점 얻음')).toBeInTheDocument();
    // 정답 축하 층은 장식(눌림 통과)
    expect(result.querySelector('.wt-fx-cheer-mine')).not.toBeNull();
  });

  it('차수 결과 — 아무도 못 맞혔는데 공개 힌트가 최저 온도에 못 미치면 출제자 0점인 이유를 말한다', () => {
    seedStore({ phase: 'GUESS', revealed: [{ attempt: 1, text: '여행', temp: 31, floor: 50, floorMet: false }] });
    renderTable();
    expect(screen.getByText('최저 50° 못 넘김')).toBeInTheDocument();
    pub('GUESSES_REVEALED', {
      attempt: 1,
      setterSeat: 1,
      setterGain: 0,
      guesses: [{ seat: 0, text: '기차', pass: false, correct: false, masked: false }],
      scores: [],
    });
    expect(screen.getByRole('region', { name: '1차 결과' })).toHaveTextContent('최저 50° 에 못 미쳐 출제자도 0점이에요');
  });

  it('차수 결과 — 아무도 못 맞히고 최저 온도를 넘겼으면 출제자 점수', () => {
    seedStore({ phase: 'GUESS', revealed: [{ attempt: 1, text: '갈매기', temp: 71, floor: 50, floorMet: true }] });
    renderTable();
    pub('GUESSES_REVEALED', {
      attempt: 1,
      setterSeat: 1,
      setterGain: 1,
      guesses: [{ seat: 0, text: '항구', pass: false, correct: false, masked: false }],
      scores: [{ seat: 1, delta: 1, total: 1 }],
    });
    expect(screen.getByRole('region', { name: '1차 결과' })).toHaveTextContent('출제자 나래 +1점');
  });

  it('96 이상 — "거의 정답" 띠와 끓는 연출', () => {
    seedStore({ hintCount: 1 });
    renderTable();
    pub('HINT_REVEALED', { attempt: 1, text: '파도', temp: 97, floor: 50, floorMet: true });
    const board = screen.getByRole('region', { name: '공개된 힌트' });
    expect(within(board).getByText('거의 정답')).toBeInTheDocument();
    expect(within(board).getByRole('img', { name: '온도 97도, 거의 정답' })).toBeInTheDocument();
    expect(board.querySelector('.wt-fx-boil')).not.toBeNull();
    expect(board.querySelector('.wt-fx-boil')).toHaveAttribute('aria-hidden', 'true');
  });

  it('차례 끝 — 정답과 출제자가 낸 힌트 전체·온도, 다음 출제자', () => {
    seedStore({ phase: 'GUESS', revealed: [{ attempt: 1, text: '갈매기', temp: 71, floor: 50, floorMet: true }] });
    renderTable();
    pub('GUESSES_REVEALED', { attempt: 1, setterSeat: 1, setterGain: 0, guesses: [{ seat: 0, text: '바다', pass: false, correct: true, masked: false }], scores: [{ seat: 0, delta: 1, total: 1 }] });
    pub('TURN_ENDED', {
      turnNo: 1,
      answer: '바다',
      reason: 'SOLVED',
      totals: [1, 0, 0, 0],
      hints: [
        { attempt: 1, text: '여름', temp: 58, revealed: false },
        { attempt: 1, text: '갈매기', temp: 71, revealed: true },
      ],
    });
    const end = screen.getByRole('region', { name: '차례 끝' });
    expect(within(end).getByText('바다')).toBeInTheDocument();
    expect(within(end).getByText('정답을 맞혔어요!')).toBeInTheDocument();
    expect(within(end).getByText('여름')).toBeInTheDocument();
    expect(within(end).getByRole('img', { name: '온도 58도, 미지근' })).toBeInTheDocument();
    expect(within(end).getByText('공개')).toBeInTheDocument();
    expect(within(end).getByText('다음 출제자는 다온 님이에요.')).toBeInTheDocument();
  });

  // ── 판 끝 ──
  it('판 끝 — 순위·출제/추리 점수·낱말 돌아보기, [대기실로]', () => {
    const onBackToRoom = vi.fn();
    seedStore({
      phase: 'FINISHED',
      history: [
        { turnNo: 1, round: 1, setterSeat: 1, answer: '바다', revealed: [{ attempt: 1, text: '갈매기', temp: 71, floor: 50, floorMet: true }], solvers: [0], setterGain: 0, reason: 'SOLVED' },
      ],
      result: {
        endReason: 'FINISHED',
        draw: false,
        standings: [
          // 서버는 좌석 순서로 보낸다 — 화면은 순위 순
          { seat: 0, rank: 2, total: 3, setterPoints: 3, guessPoints: 0, out: false },
          { seat: 1, rank: 4, total: 9, setterPoints: 9, guessPoints: 0, out: true },
          { seat: 2, rank: 2, total: 3, setterPoints: 0, guessPoints: 3, out: false },
          { seat: 3, rank: 1, total: 5, setterPoints: 2, guessPoints: 3, out: false },
        ],
      },
    });
    renderTable({ roomFinished: true, onBackToRoom });
    const end = screen.getByRole('status', { name: '판 결과' });
    expect(within(end).getByRole('heading', { name: '2위' })).toBeInTheDocument();
    expect(within(end).getByText('출제 2 · 추리 3')).toBeInTheDocument();
    expect(within(end).getAllByText('2위')).toHaveLength(3); // 제목 + 공동 2위 둘
    expect([...end.querySelectorAll('.wt-end-row .wt-end-name')].map((n) => n.textContent)).toEqual(['봇 라온', '가람 (나)', '다온', '나래']);
    expect(within(end).getByText('—')).toBeInTheDocument(); // 빠진 사람은 순위 대신 —
    expect(within(end).getByText('갈매기 71°')).toBeInTheDocument();
    expect(within(end).getByText('맞힘: 가람')).toBeInTheDocument();
    expect(screen.queryByRole('timer')).not.toBeInTheDocument();
    fireEvent.click(within(end).getByRole('button', { name: '대기실로' }));
    expect(onBackToRoom).toHaveBeenCalled();
  });

  it('방이 끝났는데 결과가 없으면(강제 종료) 끝났다고만 알린다 — 입력은 없다', () => {
    seedStore({ setterSeat: 0 }, mine({ answer: '바다' }));
    renderTable({ roomFinished: true });
    expect(screen.getAllByText('게임이 종료되었습니다.').length).toBeGreaterThan(0);
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });

  // ── 그 밖 ──
  it('연출 설정이 꺼져 있으면 wt-anim 이 붙지 않는다(CSS 연출이 돌지 않는다)', () => {
    seedStore();
    useCardAnimStore.setState({ enabled: false });
    const { container } = renderTable();
    expect(container.querySelector('.wt-table')).not.toHaveClass('wt-anim');
  });

  it('첫 판을 못 받았으면(방이 막 시작돼 상태가 아직 없다) 받을 때까지 다시 청한다 — 그 동안 오류 안내는 띄우지 않는다', () => {
    act(() => {
      st().setMySeat(0);
      st().reset('r1');
      wordTempRoomSink.setError('아직 판이 없어요');
    });
    renderTable();
    expect(screen.getByText('판을 불러오는 중…')).toBeInTheDocument();
    expect(screen.queryByText('아직 판이 없어요')).not.toBeInTheDocument();
    act(() => vi.advanceTimersByTime(LOAD_RETRY_MS));
    act(() => vi.advanceTimersByTime(LOAD_RETRY_MS));
    expect(requestResync).toHaveBeenCalledTimes(2);
    // 판이 오면 멈춘다
    seedStore({ phase: 'WORD_SELECT', setterSeat: -1 }, mine({ chosen: -1 }));
    act(() => vi.advanceTimersByTime(LOAD_RETRY_MS * 3));
    expect(requestResync).toHaveBeenCalledTimes(2);
    expect(screen.getByRole('region', { name: '낱말 고르기' })).toBeInTheDocument();
  });

  it('끝내 못 받으면 다시 청하기를 멈추고 오류를 알린다', () => {
    act(() => {
      st().setMySeat(0);
      st().reset('r1');
      wordTempRoomSink.setError('판을 받지 못했어요');
    });
    renderTable();
    for (let i = 0; i < LOAD_RETRY_MAX + 3; i++) act(() => vi.advanceTimersByTime(LOAD_RETRY_MS));
    expect(requestResync).toHaveBeenCalledTimes(LOAD_RETRY_MAX);
    expect(screen.getByText('판을 받지 못했어요')).toBeInTheDocument();
  });

  it('스토어가 다시 받기를 청하면 훅의 requestResync 를 부른다', () => {
    seedStore();
    renderTable();
    act(() => useWordTempStore.setState({ resyncNonce: 1 }));
    expect(requestResync).toHaveBeenCalledTimes(1);
  });

  it('판 도중 나가기는 확인을 받는다. 관전자는 바로 나간다', () => {
    seedStore();
    const onExit = vi.fn();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    renderTable({ onExit });
    fireEvent.click(screen.getByRole('button', { name: '게임 메뉴' }));
    fireEvent.click(screen.getByRole('menuitem', { name: /나가기/ }));
    expect(confirm).toHaveBeenCalled();
    expect(onExit).not.toHaveBeenCalled();
  });

  it('끊긴 사람 — 방장에게 [내보내기]', () => {
    seedStore();
    act(() => void wordTempRoomSink.applyEvent({ type: 'PLAYER_DISCONNECTED', payload: { seat: 2 } }));
    const onKick = vi.fn();
    renderTable({ onKick });
    expect(screen.getByText('끊김')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '내보내기' }));
    expect(onKick).toHaveBeenCalledWith(30);
  });
});
