import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LATE_NOTICE_MS, OneCardTable, PRESS_RETRY_DELAY_MS, STALE_RACE_GRACE_MS } from './OneCardTable';
import { onecardRoomSink } from './onecardRoomSink';
import { useOneCardStore } from './onecardStore';
import { useAuthStore } from '@/features/auth/authStore';
import type {
  OneCardCard,
  OneCardMatchResult,
  OneCardRaceView,
  OneCardSeatView,
  OneCardSuit,
  OneCardTableView,
} from '@/types/onecard';

// 소켓만 모킹하고 스토어는 실물을 seed 한다 (스컬킹 게임판 테스트와 같은 방식).
const sendAction = vi.fn();
/** S5 — 훅의 권위 스냅샷 재요청. 훅이 주는 것처럼 렌더마다 같은 참조다. */
const requestResync = vi.fn();
let socketConnected = true;
vi.mock('@/ws/useStompRoom', () => ({
  useStompRoom: () => ({
    connected: socketConnected,
    sendAction: (a: Record<string, unknown>) => sendAction(a),
    sendChat: vi.fn(),
    sendReaction: vi.fn(),
    chatPanelOpenRef: { current: false },
    requestResync,
  }),
}));

const c = (suit: OneCardSuit, rank: number): OneCardCard => ({ suit, rank, joker: null });
const seatOf = (n: number, over: Partial<OneCardSeatView> = {}): OneCardSeatView => ({
  seat: n,
  handCount: 5,
  eliminated: null,
  ...over,
});

const NOW = 1_000_000;

interface SeedOptions {
  seatCount: number;
  mySeat: number;
  hand?: OneCardCard[];
  turnSeat?: number;
  topCard?: OneCardCard | null;
  declaredSuit?: OneCardSuit | null;
  attackStack?: number;
  seats?: OneCardSeatView[];
  race?: OneCardRaceView | null;
  result?: OneCardMatchResult | null;
}

function seed(opts: SeedOptions) {
  useOneCardStore.getState().reset('r-1');
  useOneCardStore.getState().applySnapshot(snapshotOf(opts));
}

/** resync 응답 모양 — 훅이 sink 로 넘기는 그대로. */
function snapshotOf(opts: SeedOptions) {
  const table: OneCardTableView = {
    phase: opts.result ? 'ENDED' : opts.race ? 'RACE' : 'PLAYING',
    seats: opts.seats ?? Array.from({ length: opts.seatCount }, (_, i) => seatOf(i)),
    topCard: opts.topCard === undefined ? c('HEART', 9) : opts.topCard,
    declaredSuit: opts.declaredSuit ?? null,
    attackStack: opts.attackStack ?? 0,
    direction: 1,
    turnSeat: opts.turnSeat ?? -1,
    drawPileCount: 30,
    race: opts.race ?? null,
    result: opts.result ?? null,
  };
  return {
    roomId: 'r-1',
    phase: table.phase,
    eventSeq: 1,
    tableView: table,
    privateHand:
      opts.mySeat >= 0 ? { seat: opts.mySeat, hand: opts.hand ?? [], handVersion: 1 } : null,
    disconnectedSeats: [],
    chips: null,
  };
}

const RACE: OneCardRaceView = {
  raceId: 7,
  ownerSeat: 1,
  slot: 2,
  jitterX: 30,
  jitterY: -40,
  windowMillis: 3000,
  remainingMillis: 3000,
};

const playerIds = (n: number) => Array.from({ length: n }, (_, i) => 100 + i);

function renderTable(over: Partial<Parameters<typeof OneCardTable>[0]> = {}) {
  const n = over.playerIds?.length ?? 4;
  return render(<OneCardTable roomId="r-1" playerIds={playerIds(n)} myUserId={100} {...over} />);
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
  vi.setSystemTime(NOW);
  sendAction.mockReset();
  requestResync.mockReset();
  socketConnected = true;
  useAuthStore.setState({ token: 'tok' } as never);
  useOneCardStore.getState().reset('r-1');
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('OneCardTable — 좌석', () => {
  it.each([2, 4, 6])('%i인이면 상대 좌석을 (인원 - 1)개, 내 정보줄을 하나 그린다', (n) => {
    seed({ seatCount: n, mySeat: 0 });
    const { container } = renderTable({ playerIds: playerIds(n) });

    expect(container.querySelectorAll('.oc-seat')).toHaveLength(n - 1);
    expect(container.querySelector('.oc-me')).not.toBeNull();
  });

  it('상대 좌석은 이름과 장수만 보여 주고, 1장이면 표시를 세운다', () => {
    seed({ seatCount: 3, mySeat: 0, seats: [seatOf(0), seatOf(1, { handCount: 1 }), seatOf(2)] });
    const { container } = renderTable({ playerIds: [100, 101, 102] });

    const one = container.querySelector('[data-seat="1"]') as HTMLElement;
    expect(within(one).getByText('1장!')).toBeInTheDocument();
    expect(container.querySelector('[data-seat="2"]')!.textContent).not.toContain('1장!');
  });

  it('탈락한 좌석에는 사유를 단다', () => {
    seed({
      seatCount: 3,
      mySeat: 0,
      seats: [seatOf(0), seatOf(1, { eliminated: 'BANKRUPT', handCount: 0 }), seatOf(2)],
    });
    const { container } = renderTable({ playerIds: [100, 101, 102] });

    expect(within(container.querySelector('[data-seat="1"]') as HTMLElement).getByText('파산')).toBeInTheDocument();
  });

  it('관전자는 모든 좌석을 보고 손패·버튼이 없다', () => {
    seed({ seatCount: 4, mySeat: -1, turnSeat: 2 });
    const { container } = renderTable({ spectator: true });

    expect(container.querySelectorAll('.oc-seat')).toHaveLength(4);
    expect(screen.queryByRole('region', { name: '내 손패' })).toBeNull();
    expect(screen.getByText('관전 모드')).toBeInTheDocument();
  });
});

describe('OneCardTable — 내기·먹기', () => {
  const HAND = [c('CLUB', 4), c('HEART', 3), c('SPADE', 9), c('CLUB', 7)];

  it('손패를 무늬·숫자 순으로 보여 주고, 내 차례면 낼 수 없는 카드를 흐리게 한다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 0, hand: HAND });
    renderTable({ playerIds: [100, 101, 102] });

    const hand = screen.getByRole('region', { name: '내 손패' });
    const names = within(hand)
      .getAllByRole('button', { pressed: false })
      .map((b) => b.getAttribute('aria-label'));
    expect(names).toEqual(['스페이드 9', '하트 3', '클로버 4', '클로버 7']);
    expect(within(hand).getByRole('button', { name: '클로버 4' })).toHaveClass('oc-card-dimmed');
    expect(within(hand).getByRole('button', { name: '하트 3' })).not.toHaveClass('oc-card-dimmed');
  });

  it('카드를 고르고 내면 그 카드를 보낸다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 0, hand: HAND });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.click(screen.getByRole('button', { name: '하트 3' }));
    fireEvent.click(screen.getByRole('button', { name: '카드 내기' }));

    expect(sendAction).toHaveBeenCalledWith({ '@action': 'PLAY_CARD', card: c('HEART', 3) });
  });

  it('7 은 무늬를 골라야 낼 수 있고, 고른 무늬를 함께 보낸다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 0, hand: HAND, topCard: c('CLUB', 9) });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.click(screen.getByRole('button', { name: '클로버 7' }));
    expect(screen.getByRole('button', { name: '무늬를 고르세요' })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: '하트' }));
    fireEvent.click(screen.getByRole('button', { name: '카드 내기' }));

    expect(sendAction).toHaveBeenCalledWith({
      '@action': 'PLAY_CARD',
      card: c('CLUB', 7),
      declaredSuit: 'HEART',
    });
  });

  it('먹기 버튼은 공격받는 중이면 누적 장수를 보여 주고 DRAW 를 보낸다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 0, hand: HAND, topCard: c('HEART', 2), attackStack: 4 });
    renderTable({ playerIds: [100, 101, 102] });

    expect(screen.getByText('공격받는 중 +4')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '먹기 (4장)' }));

    expect(sendAction).toHaveBeenCalledWith({ '@action': 'DRAW' });
  });

  it('내 차례가 아니면 내기·먹기를 막는다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, hand: HAND });
    renderTable({ playerIds: [100, 101, 102] });

    expect(screen.getByRole('button', { name: '내 차례 아님' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '먹기 (1장)' })).toBeDisabled();
  });

  it('가운데에 맨 위 카드·지정 무늬·더미 장수·차례를 보여 준다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, topCard: c('HEART', 7), declaredSuit: 'CLUB' });
    renderTable({ playerIds: [100, 101, 102] });

    const center = screen.getByRole('region', { name: '테이블' });
    expect(within(center).getByRole('img', { name: '하트 7' })).toBeInTheDocument();
    expect(within(center).getByText('지정 ♣ 클로버')).toBeInTheDocument();
    expect(within(center).getByText('뽑을 더미 30장')).toBeInTheDocument();
    expect(within(center).getByText('#101 차례')).toBeInTheDocument();
  });

  it('거절 문구를 보여 주고 닫을 수 있다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 0, hand: HAND });
    renderTable({ playerIds: [100, 101, 102] });

    act(() => useOneCardStore.getState().setError('지금 낼 수 없는 카드입니다.'));
    expect(screen.getByRole('alert')).toHaveTextContent('지금 낼 수 없는 카드입니다.');

    fireEvent.click(screen.getByRole('button', { name: '닫기' }));
    expect(screen.queryByRole('alert')).toBeNull();
  });
});

describe('OneCardTable — 원카드 경쟁', () => {
  // 서버가 보내는 것과 같은 모양·순서로 넣는다 — 해소는 승자를 처리한 락 안에서 방송되고, 진 누름의 거절(NO_RACE·BUSY)은 그 뒤에
  // 본인 큐로 온다(GameStompController).
  const errorEnvelope = (code: string) => ({
    eventId: 'e',
    type: 'ERROR',
    ts: 0,
    payload: { code, message: 'detail' },
  });
  const resolved = (outcome: string, bySeat: number) => ({
    type: 'RACE_RESOLVED',
    payload: { raceId: 7, outcome, bySeat },
  });
  const turnChanged = { type: 'TURN_CHANGED', payload: { seat: 0, direction: 1, attackStack: 0 } };
  const bar = (container: HTMLElement) => container.querySelector('.oc-race-timer') as HTMLElement;

  it('주인에게는 "원카드!" — 누르면 창 번호와 함께 CALL_ONE_CARD 를 보내고 다시 누를 수 없다', () => {
    seed({ seatCount: 3, mySeat: 1, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    const button = screen.getByRole('button', { name: '원카드!' });
    fireEvent.click(button);

    expect(sendAction).toHaveBeenCalledWith({ '@action': 'CALL_ONE_CARD', raceId: 7 });
    expect(screen.getByRole('button', { name: '원카드!' })).toBeDisabled();
  });

  it('다른 사람에게는 "잡기!" — CATCH 를 보낸다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3), c('CLUB', 5)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.click(screen.getByRole('button', { name: '잡기!' }));

    expect(sendAction).toHaveBeenCalledWith({ '@action': 'CATCH', raceId: 7 });
  });

  it('버튼은 서버가 고른 슬롯·지터 자리에 뜬다 — 자동 포커스는 주지 않는다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    const button = screen.getByRole('button', { name: '잡기!' });
    // 슬롯 2(78%, 18%) + 지터(+30% × 10, −40% × 6) = (81%, 15.6%)
    expect(button.style.left).toContain('81%');
    expect(button.style.top).toBe('15.6%');
    expect(document.activeElement).not.toBe(button);
  });

  it('창이 열린 동안 내기·먹기 버튼을 막고 "경쟁 중"으로 알린다', () => {
    // 서버 불변식 — 경쟁 중에는 turnSeat 가 −1 이다(seed 의 기본값).
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    expect(screen.getByRole('button', { name: '경쟁 중' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '먹기 (1장)' })).toBeDisabled();
  });

  it('관전자와 탈락자에게는 버튼이 없고, 창이 열린 사실은 알림으로 읽힌다', () => {
    seed({ seatCount: 3, mySeat: -1, race: RACE });
    const { unmount } = renderTable({ playerIds: [100, 101, 102], spectator: true });
    expect(screen.queryByRole('button', { name: /원카드!|잡기!/ })).toBeNull();
    expect(screen.getByText('#101 카드 1장! 경쟁 중')).toBeInTheDocument();
    unmount();

    seed({
      seatCount: 3,
      mySeat: 2,
      seats: [seatOf(0), seatOf(1), seatOf(2, { eliminated: 'BANKRUPT', handCount: 0 })],
      race: RACE,
    });
    renderTable({ playerIds: [100, 101, 102] });
    expect(screen.queryByRole('button', { name: /원카드!|잡기!/ })).toBeNull();
  });

  it('연결이 끊긴 동안은 누를 수 없다 — 보내지 못한 누름이 대기로 굳지 않는다', () => {
    socketConnected = false;
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    const button = screen.getByRole('button', { name: '잡기!' });
    expect(button).toBeDisabled();
    fireEvent.click(button);

    expect(sendAction).not.toHaveBeenCalled();
    expect(useOneCardStore.getState().press).toBeNull();
  });

  it('연결이 돌아오면 같은 창을 바로 누를 수 있다', () => {
    socketConnected = false;
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    const { rerender } = renderTable({ playerIds: [100, 101, 102] });
    expect(screen.getByRole('button', { name: '잡기!' })).toBeDisabled();

    socketConnected = true;
    rerender(<OneCardTable roomId="r-1" playerIds={playerIds(3)} myUserId={100} />);
    fireEvent.click(screen.getByRole('button', { name: '잡기!' }));

    expect(sendAction).toHaveBeenCalledWith({ '@action': 'CATCH', raceId: 7 });
  });

  it('BUSY 로 거절되면 잠시 뒤 같은 누름을 다시 보낸다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.click(screen.getByRole('button', { name: '잡기!' }));
    act(() => {
      useOneCardStore.getState().notePressRejected('BUSY');
    });
    expect(sendAction).toHaveBeenCalledTimes(1);

    act(() => {
      vi.advanceTimersByTime(PRESS_RETRY_DELAY_MS);
    });
    expect(sendAction).toHaveBeenCalledTimes(2);
    expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'CATCH', raceId: 7 });
  });

  it('창이 열린 동안 BUSY 가 계속되면 두 번 다시 보내고, 그래도 안 되면 "늦었어요"로 끝낸다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.click(screen.getByRole('button', { name: '잡기!' }));
    for (let retry = 1; retry <= 2; retry++) {
      act(() => onecardRoomSink.applyPrivateEvent(errorEnvelope('BUSY')));
      act(() => {
        vi.advanceTimersByTime(PRESS_RETRY_DELAY_MS);
      });
      expect(sendAction).toHaveBeenCalledTimes(1 + retry);
    }

    act(() => onecardRoomSink.applyPrivateEvent(errorEnvelope('BUSY')));
    act(() => {
      vi.advanceTimersByTime(PRESS_RETRY_DELAY_MS);
    });
    expect(sendAction).toHaveBeenCalledTimes(3);
    expect(screen.queryByRole('alert')).toBeNull(); // "잠시 후 다시 시도하세요" 가 아니다
    expect(screen.getAllByText(/늦었어요/)).toHaveLength(2);
  });

  it('남이 먼저 이기면 오류 배너 없이 "늦었어요"를 보여 주고, 보조기기 알림에도 싣는다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.click(screen.getByRole('button', { name: '잡기!' }));
    // 주인이 먼저 눌렀다 → 해소와 차례가 먼저 오고, 진 누름의 NO_RACE 는 그 뒤에 온다.
    act(() => {
      onecardRoomSink.applyEvent(resolved('CALLED', 1));
      onecardRoomSink.applyEvent(turnChanged);
      onecardRoomSink.applyPrivateEvent(errorEnvelope('NO_RACE'));
    });

    expect(screen.queryByRole('alert')).toBeNull();
    // 화면 한 줄 + 알림 영역 한 줄
    expect(screen.getAllByText(/늦었어요/)).toHaveLength(2);
    expect(screen.getByRole('button', { name: '카드를 고르세요' })).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(LATE_NOTICE_MS);
    });
    expect(screen.queryByText(/늦었어요/)).toBeNull();
  });

  it('BUSY 를 받은 뒤 남이 이기면 진 누름을 다시 보내지 않는다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.click(screen.getByRole('button', { name: '잡기!' }));
    expect(sendAction).toHaveBeenCalledTimes(1);

    act(() => {
      onecardRoomSink.applyPrivateEvent(errorEnvelope('BUSY')); // 락 경합 — 재시도 신호가 선다
      onecardRoomSink.applyEvent(resolved('CAUGHT', 2)); // 재시도 전에 다른 사람이 이겼다
    });
    act(() => {
      vi.advanceTimersByTime(PRESS_RETRY_DELAY_MS);
    });

    expect(sendAction).toHaveBeenCalledTimes(1); // 첫 누름뿐 — 닫힌 창에 다시 보내지 않는다
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getAllByText(/늦었어요/)).toHaveLength(2);
  });

  it('남이 이긴 뒤 BUSY 가 와도 오류 배너 없이 "늦었어요"', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.click(screen.getByRole('button', { name: '잡기!' }));
    act(() => {
      onecardRoomSink.applyEvent(resolved('CAUGHT', 2));
      onecardRoomSink.applyEvent(turnChanged);
      onecardRoomSink.applyPrivateEvent(errorEnvelope('BUSY'));
    });
    act(() => {
      vi.advanceTimersByTime(PRESS_RETRY_DELAY_MS);
    });

    expect(sendAction).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getAllByText(/늦었어요/)).toHaveLength(2);
  });

  it('거절이 해소 이벤트보다 먼저 닿아도 "늦었어요" — 이미 닫힌 창이다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.click(screen.getByRole('button', { name: '잡기!' }));
    act(() => {
      useOneCardStore.getState().notePressRejected('NO_RACE');
    });
    expect(screen.getAllByText(/늦었어요/)).toHaveLength(2);
    expect(screen.queryByRole('alert')).toBeNull();

    act(() => {
      vi.advanceTimersByTime(LATE_NOTICE_MS);
    });
    expect(screen.queryByText(/늦었어요/)).toBeNull();
  });

  it('내가 이기면 안내도 오류도 없다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.click(screen.getByRole('button', { name: '잡기!' }));
    act(() => {
      onecardRoomSink.applyEvent(resolved('CAUGHT', 0));
    });

    expect(screen.queryByText(/늦었어요/)).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByRole('button', { name: '잡기!' })).toBeNull();
  });

  it('남은 시간 막대는 재렌더로 다시 계산되지 않는다 — 진행 중인 막대가 튀지 않는다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    const { container } = renderTable({ playerIds: [100, 101, 102] });
    expect(bar(container).style.animationDuration).toBe('3000ms');

    act(() => {
      vi.advanceTimersByTime(1000);
    });
    // 스토어 전체를 구독하므로 오류 문구 하나만 바뀌어도 게임판이 다시 그려진다.
    act(() => useOneCardStore.getState().setError('다른 처리'));

    expect(bar(container).style.animationDuration).toBe('3000ms');
  });

  it('resync 가 마감 시각을 바꾸면 막대를 그 시각에 다시 맞춘다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    const { container } = renderTable({ playerIds: [100, 101, 102] });

    act(() => {
      vi.advanceTimersByTime(1000);
    });
    // 서버가 다시 알려 준 남은 시간 1500ms → 마감 시각이 (원래 +3000 이 아닌) +2500 으로 바뀐다.
    act(() => seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: { ...RACE, remainingMillis: 1500 } }));

    expect(bar(container).style.animationDuration).toBe('1500ms');
  });

  it('창이 닫히면 결과를 한 줄로 보여 주고 알림도 같은 문장이다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    act(() => {
      useOneCardStore.getState().applyEvent({
        type: 'RACE_RESOLVED',
        payload: { raceId: 7, outcome: 'CAUGHT', bySeat: 2 },
      });
    });

    expect(screen.getAllByText('잡기 성공: #102 → #101 벌칙 1장')).toHaveLength(2);
    expect(screen.queryByRole('button', { name: '잡기!' })).toBeNull();
  });
});

describe('OneCardTable — 낡은 창 복구 (S5)', () => {
  // 서버가 실제로 만드는 순서로 넣는다. 해소 이벤트가 끝내 안 오는 경우는 둘이다 — 엔진 타이머가 사라져 서버에서도 창이
  // 열린 채 멈췄거나(C-I2 경로 1·4: 서버 resync 가 진행 킥으로 타이머를 다시 건다), 서버는 창을 닫아 저장했는데 방송이
  // 실패했다(경로 3: 누름은 NO_RACE 로만 돌아온다).
  const errorEnvelope = (code: string) => ({ eventId: 'e', type: 'ERROR', ts: 0, payload: { code, message: 'detail' } });

  it('창이 마감 + 1.5초가 지나도 열려 있으면 권위 스냅샷을 창마다 한 번 다시 청한다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    act(() => {
      vi.advanceTimersByTime(RACE.remainingMillis + STALE_RACE_GRACE_MS - 1);
    });
    expect(requestResync).not.toHaveBeenCalled();
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(requestResync).toHaveBeenCalledTimes(1);

    // 서버도 창을 아직 들고 있다(타이머 유실) — 스냅샷은 남은 시간 0 인 같은 창. 같은 창으로는 더 청하지 않는다.
    act(() =>
      onecardRoomSink.applySnapshot(
        snapshotOf({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: { ...RACE, remainingMillis: 0 } }),
      ),
    );
    act(() => {
      vi.advanceTimersByTime(STALE_RACE_GRACE_MS * 3);
    });
    expect(requestResync).toHaveBeenCalledTimes(1);

    // 서버 resync 의 진행 킥이 다시 건 타이머가 발화해 창이 닫힌다.
    act(() => {
      onecardRoomSink.applyEvent({ type: 'RACE_RESOLVED', payload: { raceId: 7, outcome: 'EXPIRED', bySeat: -1 } });
    });
    expect(screen.queryByRole('button', { name: '잡기!' })).toBeNull();
  });

  it('제때 닫힌 창은 다시 청하지 않는다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    act(() => {
      vi.advanceTimersByTime(2_000);
      onecardRoomSink.applyEvent({ type: 'RACE_RESOLVED', payload: { raceId: 7, outcome: 'CAUGHT', bySeat: 2 } });
      vi.advanceTimersByTime(STALE_RACE_GRACE_MS * 3);
    });

    expect(requestResync).not.toHaveBeenCalled();
  });

  it('해소 이벤트 없이 내 누름이 NO_RACE 로 돌아오면 한 번 다시 청하고, 스냅샷이 창을 닫는다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.click(screen.getByRole('button', { name: '잡기!' }));
    act(() => onecardRoomSink.applyPrivateEvent(errorEnvelope('NO_RACE')));

    expect(requestResync).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('alert')).toBeNull();

    // 스냅샷 — 서버는 이미 창을 닫고 좌석 1 차례로 넘겼다.
    act(() => onecardRoomSink.applySnapshot(snapshotOf({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], turnSeat: 1 })));
    expect(screen.queryByRole('button', { name: '잡기!' })).toBeNull();
  });

  it('해소 → 차례 → NO_RACE 의 보통 순서에서는 다시 청하지 않는다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.click(screen.getByRole('button', { name: '잡기!' }));
    act(() => {
      onecardRoomSink.applyEvent({ type: 'RACE_RESOLVED', payload: { raceId: 7, outcome: 'CALLED', bySeat: 1 } });
      onecardRoomSink.applyEvent({ type: 'TURN_CHANGED', payload: { seat: 0, direction: 1, attackStack: 0 } });
      onecardRoomSink.applyPrivateEvent(errorEnvelope('NO_RACE'));
    });

    expect(requestResync).not.toHaveBeenCalled();
  });

  /**
   * 끊긴 줄 모르고 누른 경우(F2) — 누름은 버려졌는데 표식만 남아 그 창 동안 버튼이 잠겼다. 재접속 resync 의 스냅샷이 오면
   * 같은 창이라도 다시 누를 수 있다.
   */
  it('resync 스냅샷이 오면 같은 창을 다시 누를 수 있다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.click(screen.getByRole('button', { name: '잡기!' }));
    expect(screen.getByRole('button', { name: '잡기!' })).toBeDisabled();

    act(() =>
      onecardRoomSink.applySnapshot(
        snapshotOf({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: { ...RACE, remainingMillis: 2_000 } }),
      ),
    );
    fireEvent.click(screen.getByRole('button', { name: '잡기!' }));

    expect(sendAction).toHaveBeenCalledTimes(2);
    expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'CATCH', raceId: 7 });
  });
});

describe('OneCardTable — 종료·나가기', () => {
  const RESULT: OneCardMatchResult = {
    reason: 'FINISHED',
    standings: [
      { seat: 1, rank: 1, cardsLeft: 0, status: 'FINISHED' },
      { seat: 0, rank: 2, cardsLeft: 3, status: 'ALIVE' },
      { seat: 2, rank: 3, cardsLeft: 21, status: 'BANKRUPT' },
    ],
  };

  it('결과가 오면 순위를 보여 주고 손패 입력을 내린다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], result: RESULT });
    renderTable({ playerIds: [100, 101, 102] });

    const end = screen.getByRole('region', { name: '판 종료' });
    expect(within(end).getByRole('heading')).toHaveTextContent('판 종료');
    const rows = within(end).getAllByRole('listitem').map((li) => li.textContent);
    expect(rows).toEqual(['1위#1010장다 냄', '2위#100 (나)3장', '3위#10221장파산']);
    expect(screen.queryByRole('region', { name: '내 손패' })).toBeNull();
  });

  it('내가 1등이면 승리 표시', () => {
    seed({ seatCount: 3, mySeat: 1, result: RESULT });
    renderTable({ playerIds: [100, 101, 102] });

    expect(screen.getByRole('heading')).toHaveTextContent('승리 🎉');
  });

  it('게임 중 나가기는 탈주 확인을 묻고, 취소하면 나가지 않는다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, hand: [c('HEART', 3)] });
    const onExit = vi.fn();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    renderTable({ playerIds: [100, 101, 102], onExit });

    fireEvent.click(screen.getByRole('button', { name: '나가기' }));
    expect(confirm).toHaveBeenCalled();
    expect(onExit).not.toHaveBeenCalled();
  });

  it('끝난 판이나 이미 탈락한 좌석은 묻지 않고 나간다', () => {
    seed({ seatCount: 3, mySeat: 0, result: RESULT });
    const onExit = vi.fn();
    const confirm = vi.spyOn(window, 'confirm');
    renderTable({ playerIds: [100, 101, 102], onExit });

    fireEvent.click(screen.getByRole('button', { name: '나가기' }));
    expect(confirm).not.toHaveBeenCalled();
    expect(onExit).toHaveBeenCalledTimes(1);
  });

  it('방은 끝났는데 결과가 없으면 종료 안내와 메인으로 버튼', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)] });
    const onExit = vi.fn();
    renderTable({ playerIds: [100, 101, 102], onExit, roomFinished: true });

    expect(screen.getByText('게임이 종료되었습니다.')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: '내 손패' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '메인으로' }));
    expect(onExit).toHaveBeenCalledTimes(1);
  });

  it('규칙 버튼은 원카드 튜토리얼을 연다', () => {
    seed({ seatCount: 3, mySeat: 0 });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.click(screen.getByRole('button', { name: '규칙' }));
    expect(screen.getByText('미르보드 원카드에 오신 걸 환영합니다')).toBeInTheDocument();
  });
});
