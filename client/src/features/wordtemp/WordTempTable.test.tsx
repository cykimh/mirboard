import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '@/features/auth/authStore';
import { useToastStore } from '@/components/toast/toastStore';
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

/** 출제자(좌석 1 — 나래)만 아는 것 — D-320 뒤로는 정답 낱말뿐이다(힌트와 온도는 공개). 추리자·관전자 화면에 나오면 안 된다. */
const ANSWER = '바다';
/** 공개 힌트 둘 — 여름 58°(기준 70 아래)·갈매기 71°(기준 넘김). */
const HINTS = [
  { text: '여름', attempt: 1, status: 'SCORED' as const, temp: 58 },
  { text: '갈매기', attempt: 1, status: 'SCORED' as const, temp: 71 },
];

describe('WordTempTable (S3)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    sendAction.mockClear();
    sendAction.mockImplementation(() => true);
    requestResync.mockClear();
    socketConnected = true;
    useAuthStore.setState({ token: 't' } as never);
    useCardAnimStore.setState({ enabled: true, forced: false });
    useToastStore.setState({ toasts: [] });
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
    // 범주 이름은 서버가 준 그대로. 뜻풀이는 보이지 않는다(D-320)
    expect(within(picker).queryByText('소금물이 넓게 고여 있는 곳')).not.toBeInTheDocument();
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
  it('힌트는 모두가 본다(D-320) — 추리자에게도 낱말과 온도가 그대로, 정답과 입력 칸은 없다', () => {
    seedStore({ hintCount: 1, hints: [HINTS[0]] });
    const { container } = renderTable();
    const board = screen.getByRole('region', { name: '힌트' });
    expect(within(board).getByText('여름')).toBeInTheDocument();
    expect(within(board).getByRole('img', { name: '온도 58도, 미지근' })).toBeInTheDocument();
    expect(within(board).getByLabelText('힌트 1개, 최대 10개')).toBeInTheDocument();
    expect(within(board).getByText('기준 70°')).toBeInTheDocument();
    expect(screen.getByRole('timer')).toHaveTextContent('1:30');
    expect(screen.getByText(/나래 님이 힌트를 내고 있어요 — 기준 70° 를 넘기면 추리 시작/)).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: '내 문제' })).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(container.textContent).not.toContain(ANSWER);
  });

  it('힌트가 공개 이벤트로 오면 모두의 판에 쌓인다 — 채점 중 → 온도, 무효는 줄이 사라진다', () => {
    seedStore();
    renderTable();
    const board = screen.getByRole('region', { name: '힌트' });
    expect(within(board).getByText('나래 님이 힌트를 고르는 중')).toBeInTheDocument();
    pub('HINT_SCORED', { seat: 1, text: '여름', temp: 58, count: 1 });
    pub('HINT_COUNT', { count: 1, max: 10 });
    expect(within(board).getByRole('img', { name: '온도 58도, 미지근' })).toBeInTheDocument();
    pub('HINT_PENDING', { seat: 1, text: '짠맛' });
    expect(within(board).getByRole('status')).toHaveTextContent('채점 중…');
    pub('HINT_VOID', { seat: 1, text: '짠맛', reason: 'NOT_A_NOUN', count: 1 });
    expect(within(board).queryByText('짠맛')).not.toBeInTheDocument();
    // 무효 안내는 낸 사람(출제자)에게만 — 추리자 화면에는 뜨지 않는다
    expect(screen.queryByText(/힌트로 쓸 수 없다고 봤어요/)).not.toBeInTheDocument();
    expect(within(board).getByLabelText('힌트 1개, 최대 10개')).toBeInTheDocument();
  });

  it('정답은 출제자만 — 스토어에 정답이 들어 있더라도(있을 수 없는 일) 출제자 칸은 내가 출제자일 때만 그린다', () => {
    seedStore({ hintCount: 2, hints: HINTS });
    act(() => useWordTempStore.setState({ answer: ANSWER }));
    const { container } = renderTable();
    expect(screen.queryByRole('region', { name: '내 문제' })).not.toBeInTheDocument();
    expect(container.textContent).not.toContain(ANSWER);
  });

  it('관전자 — 힌트와 온도는 보고, 입력 칸·후보·정답은 없다', () => {
    seedStore({ hintCount: 2, hints: HINTS }, null, -1);
    const { container } = renderTable({ spectator: true, myUserId: 99 });
    expect(screen.getByText('👀 관전 중')).toBeInTheDocument();
    expect(screen.getByText(/관전 중 — 공개된 것만/)).toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(container.querySelector('.wt-setter, .wt-guess, .wt-picker')).toBeNull();
    expect(within(screen.getByRole('region', { name: '힌트' })).getByText('갈매기')).toBeInTheDocument();
    expect(container.textContent).not.toContain(ANSWER);
  });

  it('기준 온도를 넘긴 힌트 — "통과" 표시와 "기준 70° 넘김 → 추리"', () => {
    seedStore({ hintCount: 1, hints: [HINTS[0]] });
    renderTable();
    pub('HINT_SCORED', { seat: 1, text: '갈매기', temp: 71, count: 2 });
    pub('HINT_REVEALED', { attempt: 1, text: '갈매기', temp: 71, floor: 70, floorMet: true });
    const board = screen.getByRole('region', { name: '힌트' });
    expect(within(board).getByText('통과')).toBeInTheDocument();
    expect(board.querySelector('.wt-hint-pass')).toHaveTextContent('갈매기');
    expect(within(board).getByText('기준 70° 넘김 → 추리')).toBeInTheDocument();
    // 방금 온 줄만 수은주가 차오른다
    expect(board.querySelectorAll('.wt-gauge-fresh')).toHaveLength(1);
    pub('GUESS_PHASE_STARTED', { attempt: 1, remainingMs: 30_000, seats: [0, 2, 3] });
    expect(screen.getByText(/기준 70° 넘김 → 추리 · 힌트와 온도를 보고 정답을 맞혀 보세요/)).toBeInTheDocument();
  });

  it('기준을 못 넘긴 채 힌트 단계가 끝나면 — "기준 못 넘김 — 맞히는 사람이 없어도 이번 차수 점수 없음"(힌트가 없어도 추리로)', () => {
    seedStore();
    renderTable();
    pub('HINT_REVEALED', { attempt: 1, text: null, temp: -1, floor: 70, floorMet: false });
    pub('GUESS_PHASE_STARTED', { attempt: 1, remainingMs: 30_000, seats: [0, 2, 3] });
    const board = screen.getByRole('region', { name: '힌트' });
    expect(within(board).getByText('기준 못 넘김 — 맞히는 사람이 없어도 이번 차수 점수 없음')).toBeInTheDocument();
    expect(within(board).getByText('낸 힌트가 없어요')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: '정답 낱말' })).toBeInTheDocument();
  });

  it('지난 차수는 한 줄 요약으로 접히고(누르면 펼친다) 지금 차수만 펼쳐 있다 — 힌트가 많아도 읽힌다', () => {
    const many = Array.from({ length: 10 }, (_, i) => ({ text: `낱말${'가나다라마바사아자차'[i]}`, attempt: 1, status: 'SCORED' as const, temp: 40 + i }));
    seedStore({
      attempt: 2,
      hintCount: 1,
      hints: [...many, { text: '갈매기', attempt: 2, status: 'SCORED', temp: 71 }],
      revealed: [{ attempt: 1, text: '낱말차', temp: 49, floor: 70, floorMet: false }],
    });
    renderTable();
    const board = screen.getByRole('region', { name: '힌트' });
    const past = board.querySelector('[data-attempt="1"] details') as HTMLDetailsElement;
    expect(past).not.toBeNull();
    expect(past.open).toBe(false);
    expect(past.querySelector('summary')).toHaveTextContent('1차기준 70°힌트 10/10못 넘김 · 최고 낱말차 49°');
    expect(past.querySelectorAll('.wt-hint')).toHaveLength(10);
    const now = board.querySelector('[data-attempt="2"]') as HTMLElement;
    expect(now.querySelector('details')).toBeNull();
    expect(now).toHaveTextContent('2차기준 75°힌트 1/10');
    // 지금 차수의 온도계는 이번 기준(75°)에 선이 그어진다
    expect((now.querySelector('.wt-gauge') as HTMLElement).style.getPropertyValue('--wt-floor')).toBe('0.75');
  });

  // ── 출제자 ──
  it('출제자 — 정답·기준 온도·입력·개수. 낸 힌트는 위 판에 모두와 같이 보인다', () => {
    seedStore({ setterSeat: 0 }, mine({ answer: '바다' }));
    renderTable();
    const panel = screen.getByRole('region', { name: '내 문제' });
    expect(within(panel).getByText('바다')).toBeInTheDocument();
    expect(panel).toHaveTextContent('1차 기준 70° 이상인 힌트가 나오면 바로 추리로 넘어가요');
    expect(within(panel).getByLabelText('힌트 0개, 최대 10개')).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: '그만 내기' })).toBeDisabled();

    const input = within(panel).getByRole('textbox', { name: '힌트 낱말' });
    expect(input).toHaveAttribute('placeholder', '한글 낱말 · 기준 70°');
    // D-323 — 빈 입력란에서는 [힌트 내기]가 꺼져 있다
    expect(within(panel).getByRole('button', { name: '힌트 내기' })).toBeDisabled();
    fireEvent.change(input, { target: { value: '여름' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'SUBMIT_HINT', turnNo: 1, attempt: 1, text: '여름' });
    // 응답 전 — 버튼은 aria-disabled, 또 눌러도 나가지 않는다
    fireEvent.change(input, { target: { value: '갈매기' } });
    expect(within(panel).getByRole('button', { name: '힌트 내기' })).toHaveAttribute('aria-disabled', 'true');
    expect(within(panel).getByText('보내는 중…')).toBeInTheDocument();
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(sendAction).toHaveBeenCalledTimes(1);

    pub('HINT_SCORED', { seat: 0, text: '여름', temp: 58, count: 1 });
    pub('HINT_COUNT', { count: 1, max: 10 });
    expect(within(screen.getByRole('region', { name: '힌트' })).getByRole('img', { name: '온도 58도, 미지근' })).toBeInTheDocument();
    expect(within(panel).getByLabelText('힌트 1개, 최대 10개')).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: '힌트 내기' })).not.toHaveAttribute('aria-disabled');
    expect(within(panel).getByRole('button', { name: '힌트 내기' })).toBeEnabled(); // 써 둔 '갈매기' 를 이제 낼 수 있다
    expect(within(panel).getByRole('button', { name: '그만 내기' })).toBeEnabled();

    fireEvent.click(within(panel).getByRole('button', { name: '그만 내기' }));
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

  it('출제자 — 채점 중에는 입력·[그만 내기]가 잠기고, 무효가 되면 풀리며 이유를 알려 준다', () => {
    seedStore({ setterSeat: 0 }, mine({ answer: '바다' }));
    renderTable();
    pub('HINT_SCORED', { seat: 0, text: '여름', temp: 58, count: 1 });
    pub('HINT_PENDING', { seat: 0, text: '짠맛' });
    const panel = screen.getByRole('region', { name: '내 문제' });
    expect(within(panel).getByRole('textbox', { name: '힌트 낱말' })).toBeDisabled();
    expect(within(panel).getByRole('button', { name: '그만 내기' })).toBeDisabled();
    expect(screen.getByText('힌트를 채점하는 중…')).toBeInTheDocument();
    // D-323 — 잠긴 까닭이 입력란 아래에 보인다
    expect(within(panel).getByText('채점 중… 온도가 나오면 다음 힌트를 낼 수 있어요')).toBeInTheDocument();

    pub('HINT_VOID', { seat: 0, text: '짠맛', reason: 'NOT_A_NOUN', count: 1 });
    expect(within(panel).getByRole('textbox', { name: '힌트 낱말' })).toBeEnabled();
    // D-323 — "명사가 아니에요"가 아니다(명사도, 고유 명사도 무효가 될 수 있고 그 반대도 있다)
    expect(screen.getByText('채점기가 이 낱말은 힌트로 쓸 수 없다고 봤어요. 개수는 돌려드렸으니 다른 낱말로 내 주세요.')).toBeInTheDocument();
    expect(document.body.textContent).not.toContain('명사');
  });

  it('출제자 — 10개를 다 내면 입력이 잠긴다', () => {
    seedStore({ setterSeat: 0, hintCount: 10 }, mine({ answer: '바다' }));
    renderTable();
    expect(screen.getByRole('textbox', { name: '힌트 낱말' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '힌트 내기' })).toBeDisabled();
    expect(screen.getByText('힌트 10개를 다 냈어요')).toBeInTheDocument();
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
    seedStore({ phase: 'GUESS', phaseRemainingMs: 30_000, revealed: [{ attempt: 1, text: '갈매기', temp: 71, floor: 70, floorMet: true }], guessedSeats: [2] });
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

  it('추리 단계의 출제자 — 입력 없이 기다린다(정답은 계속 본다)', () => {
    seedStore(
      { phase: 'GUESS', setterSeat: 0, phaseRemainingMs: 30_000, guessedSeats: [1], hints: [HINTS[1]], revealed: [{ attempt: 1, text: '갈매기', temp: 71, floor: 70, floorMet: true }] },
      mine({ answer: '바다' }),
    );
    renderTable();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: '내 문제' })).getByText('바다')).toBeInTheDocument();
    expect(screen.getByText('기준 70° 넘김 → 추리 · 추리를 기다려요 (1/3)')).toBeInTheDocument();
  });

  // ── 결과 ──
  it('차수 결과 — 답을 한꺼번에 보여 준다. 가려진 답은 ···, 맞힌 사람과 점수(×라운드)', () => {
    seedStore({ phase: 'GUESS', round: 2, revealed: [{ attempt: 1, text: '갈매기', temp: 71, floor: 70, floorMet: true }] });
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

  it('차수 결과 — 아무도 못 맞혔고 기준도 못 넘겼으면 출제자도 점수가 없는 이유를 말한다', () => {
    seedStore({ phase: 'GUESS', hints: [HINTS[0]], revealed: [{ attempt: 1, text: '여름', temp: 58, floor: 70, floorMet: false }] });
    renderTable();
    pub('GUESSES_REVEALED', {
      attempt: 1,
      setterSeat: 1,
      setterGain: 0,
      guesses: [{ seat: 0, text: '기차', pass: false, correct: false, masked: false }],
      scores: [],
    });
    expect(screen.getByRole('region', { name: '1차 결과' })).toHaveTextContent('기준을 못 넘겨 출제자도 이번 차수 점수가 없어요');
  });

  it('차수 결과 — 아무도 못 맞히고 기준을 넘겼으면 출제자 점수(차수 번호 × 라운드)', () => {
    seedStore({ phase: 'GUESS', revealed: [{ attempt: 1, text: '갈매기', temp: 71, floor: 70, floorMet: true }] });
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

  it('결과 보기·차례 끝에도 시계가 돈다 — 이벤트가 남은 시간을 싣는다(D-319)', () => {
    seedStore({ phase: 'GUESS', revealed: [{ attempt: 1, text: '갈매기', temp: 71, floor: 70, floorMet: true }] });
    renderTable();
    pub('GUESSES_REVEALED', { attempt: 1, setterSeat: 1, setterGain: 1, guesses: [], scores: [], remainingMs: 5_000 });
    expect(screen.getByRole('timer', { name: '결과 남은 시간' })).toHaveTextContent('5');
    pub('TURN_ENDED', { turnNo: 1, answer: '바다', reason: 'THREE_ATTEMPTS', totals: [0, 1, 0, 0], hints: [], remainingMs: 6_000 });
    expect(screen.getByRole('timer', { name: '다음 남은 시간' })).toHaveTextContent('6');
  });

  it('기준 온도는 서버가 준 값으로 그린다 — 값이 다르면 그대로, 없으면 눈금·안내 없이', () => {
    seedStore({ setterSeat: 0, floors: [60, 65, 70], hintCount: 0 }, mine({ answer: '바다' }));
    const { unmount } = renderTable();
    const pills = screen.getByRole('list', { name: '차수 1 / 3' });
    expect(pills).toHaveTextContent('1차60°2차65°3차70°');
    expect(screen.getByRole('region', { name: '내 문제' })).toHaveTextContent('1차 기준 60°');
    expect(screen.getByRole('heading', { name: '내 문제 · 1/3차 힌트' })).toBeInTheDocument();
    unmount();
    seedStore({ setterSeat: 0, floors: [], hintCount: 0 }, mine({ answer: '바다' }));
    renderTable();
    expect(screen.getByRole('region', { name: '내 문제' })).not.toHaveTextContent(/기준 \d/);
    expect(screen.getByRole('textbox', { name: '힌트 낱말' })).toHaveAttribute('placeholder', '한글 낱말');
  });

  it('차례 끝 — 정답이 공개되고 힌트는 판에 그대로 남는다, 다음 출제자', () => {
    seedStore({ phase: 'GUESS', hints: HINTS, revealed: [{ attempt: 1, text: '갈매기', temp: 71, floor: 70, floorMet: true }] });
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
    expect(within(end).getByText('다음 출제자는 다온 님이에요.')).toBeInTheDocument();
    const board = screen.getByRole('region', { name: '힌트' });
    expect(within(board).getByText('여름')).toBeInTheDocument();
    expect(within(board).getByText('갈매기')).toBeInTheDocument();
  });

  // ── 판 끝 ──
  it('판 끝 — 순위·출제/추리 점수·낱말 돌아보기, [대기실로]', () => {
    const onBackToRoom = vi.fn();
    seedStore({
      phase: 'FINISHED',
      history: [
        { turnNo: 1, round: 1, setterSeat: 1, answer: '바다', revealed: [{ attempt: 1, text: null, temp: -1, floor: 70, floorMet: false }, { attempt: 2, text: '갈매기', temp: 76, floor: 75, floorMet: true }], solvers: [0], setterGain: 0, reason: 'SOLVED' },
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
    expect(within(end).getByText('2차 갈매기 76°')).toBeInTheDocument();
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

  // ── D-323: [힌트 내기] 버튼이 켜지는 때 ──
  it('출제자 — 기준을 넘긴 힌트가 나오면 입력·버튼이 잠기고 까닭을 말한다. 추리 단계에서도 까닭이 보인다', () => {
    seedStore({ setterSeat: 0 }, mine({ answer: '바다' }));
    renderTable();
    const panel = screen.getByRole('region', { name: '내 문제' });
    const input = within(panel).getByRole('textbox', { name: '힌트 낱말' });
    fireEvent.change(input, { target: { value: '갈매기' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    pub('HINT_SCORED', { seat: 0, text: '갈매기', temp: 71, count: 1 });
    // 단계 전환 프레임이 오기 전이라도 더 낼 수 없다 — 버튼이 켜져 보이지 않는다
    expect(within(panel).getByRole('textbox', { name: '힌트 낱말' })).toBeDisabled();
    expect(within(panel).getByRole('button', { name: '힌트 내기' })).toBeDisabled();
    expect(within(panel).getByRole('button', { name: '그만 내기' })).toBeDisabled();
    expect(within(panel).getByText('기준을 넘겼어요 — 추리 차례')).toBeInTheDocument();
    pub('HINT_REVEALED', { attempt: 1, text: '갈매기', temp: 71, floor: 70, floorMet: true });
    pub('GUESS_PHASE_STARTED', { attempt: 1, remainingMs: 30_000, seats: [1, 2, 3] });
    expect(within(panel).queryByRole('textbox')).not.toBeInTheDocument();
    expect(within(panel).getByText('기준을 넘겼어요 — 지금은 추리 차례예요.')).toBeInTheDocument();
  });

  it('추리자 — [답 내기]는 쓴 낱말이 낼 수 있는 꼴일 때만 켜진다. [패스]는 늘 켜져 있다', () => {
    seedStore({ phase: 'GUESS', hintCount: 2, hints: HINTS, revealed: [{ attempt: 1, text: '갈매기', temp: 71, floor: 70, floorMet: true }] });
    renderTable();
    const panel = screen.getByRole('region', { name: '정답 맞히기' });
    const button = within(panel).getByRole('button', { name: '답 내기' });
    expect(button).toBeDisabled();
    expect(within(panel).getByRole('button', { name: '패스' })).toBeEnabled();
    fireEvent.change(within(panel).getByRole('textbox', { name: '정답 낱말' }), { target: { value: 'sea' } });
    expect(button).toBeDisabled();
    expect(within(panel).getByText('한글만 쓸 수 있어요')).toBeInTheDocument();
    fireEvent.change(within(panel).getByRole('textbox', { name: '정답 낱말' }), { target: { value: '바다' } });
    expect(button).toBeEnabled();
    fireEvent.click(button);
    expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'SUBMIT_GUESS', turnNo: 1, attempt: 1, text: '바다' });
  });

  // ── D-323: "온도가 이상해요" ──
  it('온도가 나온 힌트에 [이상해요] — 방향을 골라 FLAG_TEMP 를 보내고, 받으면 토스트 + "알림 ✓"', () => {
    seedStore({ hintCount: 2, hints: [...HINTS, { text: '짠맛', attempt: 1, status: 'PENDING' as const, temp: -1 }] });
    renderTable();
    const board = screen.getByRole('region', { name: '힌트' });
    // 채점 중인 줄에는 없다
    expect(within(board).queryByRole('button', { name: '짠맛 온도가 이상해요' })).not.toBeInTheDocument();
    const flag = within(board).getByRole('button', { name: '여름 온도가 이상해요' });
    expect(flag).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(flag);
    const ask = within(board).getByRole('group', { name: '여름 58도가 어떻게 이상한가요' });
    expect(within(ask).getAllByRole('button').map((b) => b.textContent)).toEqual(['너무 낮아요', '너무 높아요', '그냥 이상해요']);
    fireEvent.click(within(ask).getByRole('button', { name: '너무 낮아요' }));
    expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'FLAG_TEMP', text: '여름', direction: 'LOW' });
    expect(within(board).queryByRole('group')).not.toBeInTheDocument();

    priv('FLAG_ACCEPTED', { seat: 0, text: '여름', temp: 58, direction: 'LOW', left: 9 });
    expect(useToastStore.getState().toasts.map((t) => t.message)).toEqual(["'여름' 온도가 이상하다고 알렸어요. 고마워요!"]);
    expect(within(board).queryByRole('button', { name: '여름 온도가 이상해요' })).not.toBeInTheDocument();
    expect(within(board).getByLabelText('여름 온도가 이상하다고 알렸어요')).toHaveTextContent('알림 ✓');
    expect(st().sending).toBeNull();
    // 다른 줄은 그대로 알릴 수 있다
    expect(within(board).getByRole('button', { name: '갈매기 온도가 이상해요' })).toBeEnabled();
  });

  it('[이상해요] — 횟수를 다 쓰면 꺼지고, 관전자·빠진 좌석에는 버튼이 없다', () => {
    seedStore({ hintCount: 2, hints: HINTS });
    const { unmount } = renderTable();
    priv('FLAG_ACCEPTED', { seat: 0, text: '여름', temp: 58, direction: 'UNSURE', left: 0 });
    expect(useToastStore.getState().toasts.at(-1)?.message).toContain('(이번 판 0번 남음)');
    expect(screen.getByRole('button', { name: '갈매기 온도가 이상해요' })).toBeDisabled();
    unmount();

    seedStore({ hintCount: 2, hints: HINTS }, null, -1);
    renderTable({ spectator: true, myUserId: 99 });
    expect(screen.queryByRole('button', { name: /온도가 이상해요/ })).not.toBeInTheDocument();
  });
});
