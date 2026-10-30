import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  LATE_NOTICE_MS,
  OneCardTable,
  PRESS_RETRY_DELAY_MS,
  STALE_RACE_GRACE_MS,
  STALE_TURN_GRACE_MS,
  STALE_TURN_JITTER_MS,
} from './OneCardTable';
import { TURN_URGENT_SECONDS } from './OneCardTurnCountdown';
import { onecardRoomSink } from './onecardRoomSink';
import { useReactionStore } from '@/features/chat/reactionStore';
import { useOneCardStore } from './onecardStore';
import { useAuthStore } from '@/features/auth/authStore';
import { useCardAnimStore } from '@/features/theme/cardAnimStore';
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
/** D-130 — 훅의 권위 스냅샷 재요청. 훅이 주는 것처럼 렌더마다 같은 참조다. */
const requestResync = vi.fn();
const sendReaction = vi.fn();
const sendVoice = vi.fn();
let voiceHandler: ((m: unknown) => void) | null = null;
let socketConnected = true;
// D-159 — 음성 대화는 스위치로 꺼 두었다. 여기선 켜진 흐름(D-155·D-156)을 계속 본다 — 꺼진 모양은 VoiceControls.test.
vi.mock('@/features/voice/voiceSwitch', () => ({ VOICE_ENABLED: true }));
vi.mock('@/ws/useStompRoom', () => ({
  useStompRoom: () => ({
    connected: socketConnected,
    sendAction: (a: Record<string, unknown>) => sendAction(a),
    sendChat: vi.fn(),
    sendReaction: (e: string) => sendReaction(e),
    sendVoice: (v: unknown) => sendVoice(v),
    subscribeVoice: (h: (m: unknown) => void) => {
      voiceHandler = h;
      return () => {
        voiceHandler = null;
      };
    },
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
  /** D-131 — resync 의 남은 턴 시간(ms). */
  turnRemainingMs?: number | null;
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
    turnRemainingMs: opts.turnRemainingMs ?? null,
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

  it('상대 손패는 숫자 대신 장수만큼 작은 카드 뒷면으로 보이고, 장수는 보조기기로 읽힌다 (D-144)', () => {
    seed({ seatCount: 3, mySeat: 0, seats: [seatOf(0), seatOf(1, { handCount: 7 }), seatOf(2, { handCount: 19 })] });
    const { container } = renderTable({ playerIds: [100, 101, 102] });

    const seven = container.querySelector('[data-seat="1"]') as HTMLElement;
    expect(seven.querySelectorAll('.oc-seat-hand-card')).toHaveLength(7);
    expect(within(seven).getByRole('img', { name: '남은 손패 7장' })).toBeInTheDocument();
    expect(seven.textContent).not.toMatch(/7\s*장/);
    // D-151 — 10장을 넘으면 뒷면 10장 + 줄임표(…). 정확한 장수는 보조기기·마우스 오버로.
    const nineteen = container.querySelector('[data-seat="2"]') as HTMLElement;
    expect(nineteen.querySelectorAll('.oc-seat-hand-card')).toHaveLength(10);
    expect(nineteen.querySelector('.oc-seat-hand-more')).toHaveTextContent('…');
    expect(within(nineteen).getByRole('img', { name: '남은 손패 19장' })).toBeInTheDocument();
    expect(seven.querySelector('.oc-seat-hand-more')).toBeNull();
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
    // D-151 — 더미는 작은 뒷면 위에 장수만 적는다. 전체 문장은 보조기기·마우스 오버로.
    const pile = within(center).getByRole('img', { name: '뽑을 더미 30장' });
    expect(pile).toHaveTextContent(/^30$/);
    expect(within(center).queryByText('뽑을 더미 30장')).toBeNull();
    expect(within(center).getByText('#101 차례')).toBeInTheDocument();
  });

  it('맨 위 카드 옆에 직전 카드 3장을 작게 겹쳐 숫자·무늬를 보인다 — 보조기기에는 한 줄로 읽힌다 (D-150)', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, topCard: c('HEART', 9) });
    const { container } = renderTable({ playerIds: [100, 101, 102] });
    const play = (card: ReturnType<typeof c>, seq: number, inFlight?: () => void) => {
      act(() => {
        useOneCardStore.getState().applyEvent({
          type: 'CARD_PLAYED',
          seq,
          payload: { seat: 1, card, declaredSuit: null, handCount: 6, attackStack: 0, direction: 1 },
        });
      });
      inFlight?.();
      // 비행이 끝나 내려앉을 때까지.
      act(() => {
        vi.advanceTimersByTime(2000);
      });
    };
    const minis = () => [...container.querySelectorAll('.oc-trail-card')].map((e) => e.textContent);
    play(c('HEART', 4), 2);
    play(c('SPADE', 4), 3);
    play(c('CLUB', 13), 4);
    // 날아오는 동안 맨 위 자리는 이전 카드다 — 그 카드를 옆 줄에 또 두지 않는다.
    play(c('SPADE', 6), 5, () => {
      expect(minis()).toEqual(['♥4', '♠4']);
    });

    // 위에서부터 오래된 순, 맨 아래(맨 앞)가 바로 전 카드.
    expect(minis()).toEqual(['♥4', '♠4', '♣K']);
    const center = screen.getByRole('region', { name: '테이블' });
    expect(within(center).getByRole('img', { name: '이전에 낸 카드: 클로버 K, 스페이드 4, 하트 4' })).toBeInTheDocument();
    expect(within(center).getByRole('img', { name: '스페이드 6' })).toBeInTheDocument();
    expect(container.querySelector('.oc-top-trail')).toBeNull();
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
    expect(button.style.top).toContain('15.6%'); // D-154 — 세로도 가장자리 보정(clamp).
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

    expect(screen.getAllByText('잡기 성공: #102')).toHaveLength(2);
    expect(screen.queryByRole('button', { name: '잡기!' })).toBeNull();
  });
});

describe('OneCardTable — 낡은 창 복구 (D-130)', () => {
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

  /** 떠난 게임판의 타이머가 전역 스토어에 신호를 올리면, 다음에 마운트하는 원카드 방 게임판이 그 신호로 쓸데없이 다시 받는다. */
  it('언마운트하면 낡은 창 타이머를 거둔다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    const { unmount } = renderTable({ playerIds: [100, 101, 102] });

    unmount();
    act(() => {
      vi.advanceTimersByTime(RACE.remainingMillis + STALE_RACE_GRACE_MS * 2);
    });

    expect(useOneCardStore.getState().resyncNonce).toBe(0);
  });

  /** 스냅샷이 마감을 다시 맞추면(남은 시간이 늘어남) 낡은 창 확인도 새 마감 기준으로 다시 선다. */
  it('스냅샷이 마감을 늦추면 낡은 창 확인도 그만큼 늦어진다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE }); // 마감 = NOW + 3000
    renderTable({ playerIds: [100, 101, 102] });

    act(() => {
      vi.advanceTimersByTime(2_000);
    });
    act(() =>
      onecardRoomSink.applySnapshot(
        snapshotOf({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: { ...RACE, remainingMillis: 3_000 } }),
      ),
    ); // 새 마감 = NOW + 5000
    act(() => {
      vi.advanceTimersByTime(2_500); // NOW + 4500 = 옛 마감 + 1.5초, 새 마감 + 1.5초(NOW + 6500) 전
    });
    expect(requestResync).not.toHaveBeenCalled();
    act(() => {
      vi.advanceTimersByTime(2_000); // NOW + 6500
    });
    expect(requestResync).toHaveBeenCalledTimes(1);
  });

  /**
   * 스토어는 모듈 전역이고 reset 은 훅 effect(첫 렌더 뒤)에서만 불린다 — 이전 방 세션이 다시 받기 신호를 올린 채 끝났다면
   * 다음 원카드 방의 첫 렌더가 그 값을 읽는다. 값이 아니라 **변화**에 반응해야 마운트 직후 REST resync 가 훅 자신의 것에
   * 더해 한 번 더 나가지 않는다.
   */
  it('이전 세션이 남긴 다시 받기 신호로는 마운트 직후 다시 청하지 않고, 새 신호는 그대로 통한다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    useOneCardStore.setState({ resyncNonce: 2 }); // 이전 방 세션이 남긴 값
    renderTable({ playerIds: [100, 101, 102] });

    expect(requestResync).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(RACE.remainingMillis + STALE_RACE_GRACE_MS); // 이 창의 낡은 창 신호
    });
    expect(requestResync).toHaveBeenCalledTimes(1);
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
    expect(screen.getByText('원카드에 오신 걸 환영합니다')).toBeInTheDocument();
  });
});

describe('OneCardTable — 턴 카운트다운 (D-131)', () => {
  const countdown = () => document.querySelector('.oc-countdown');

  it('resync 의 남은 시간으로 맞춰 보이고, 시간이 흐르면 줄어든다(올림 초)', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, turnRemainingMs: 12_345 });
    renderTable({ playerIds: [100, 101, 102], turnSeconds: 30 });

    expect(countdown()).toHaveTextContent('13초');
    act(() => {
      vi.advanceTimersByTime(345);
    });
    expect(countdown()).toHaveTextContent('12초');
    act(() => {
      vi.advanceTimersByTime(5_000);
    });
    expect(countdown()).toHaveTextContent('7초');
  });

  it('TURN_CHANGED 가 오면 한 턴 전체(방의 턴 제한)부터 다시 센다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, turnRemainingMs: 4_000 });
    renderTable({ playerIds: [100, 101, 102], turnSeconds: 30 });
    act(() => {
      vi.advanceTimersByTime(3_000);
    });
    expect(countdown()).toHaveTextContent('1초');

    act(() => {
      onecardRoomSink.applyEvent({ type: 'TURN_CHANGED', seq: 2, payload: { seat: 2, direction: 1, attackStack: 0 } } as never);
    });

    expect(countdown()).toHaveTextContent('30초');
  });

  it('먹기 뒤 TURN_CHANGED 로 기준이 바뀌는 순간에도 방의 턴 제한보다 큰 값을 그리지 않는다', () => {
    // 먹기(CARDS_DRAWN)는 차례를 지우지 않아 카운트다운이 마운트된 채 기준만 바뀐다. 첫 그리기가 지난 틱의 시각으로 세면
    // '31초'가 한 프레임 보였다. act 는 effect 까지 비운 뒤의 DOM 만 보여 주므로 텍스트 노드의 변화를 직접 기록한다.
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, turnRemainingMs: 4_000 });
    renderTable({ playerIds: [100, 101, 102], turnSeconds: 30 });
    act(() => {
      vi.advanceTimersByTime(3_400);
    });
    expect(countdown()).toHaveTextContent('1초');
    const records: MutationRecord[] = [];
    const observer = new MutationObserver((batch) => records.push(...batch));
    observer.observe(countdown()!, { characterData: true, characterDataOldValue: true, subtree: true });

    act(() => {
      onecardRoomSink.applyEvent({
        type: 'CARDS_DRAWN',
        seq: 2,
        payload: { seat: 1, count: 1, reason: 'TURN', handCount: 8, drawPileCount: 29 },
      } as never);
      onecardRoomSink.applyEvent({ type: 'TURN_CHANGED', seq: 3, payload: { seat: 2, direction: 1, attackStack: 0 } } as never);
    });
    records.push(...observer.takeRecords());
    observer.disconnect();

    const shown = records
      .flatMap((r) => [r.oldValue, r.target.nodeValue])
      .filter((v): v is string => v !== null && /^\d+$/.test(v))
      .map(Number);
    expect(countdown()).toHaveTextContent('30초');
    expect(shown.length).toBeGreaterThan(0);
    expect(Math.max(...shown)).toBeLessThanOrEqual(30);
  });

  it('resync 의 남은 시간이 방의 턴 제한보다 커도 턴 제한으로 자른다', () => {
    // 무장한 인스턴스와 resync 를 읽은 인스턴스의 시계 차이 등으로 서버 값이 턴 제한을 넘으면 '31초'가 보였다(최종 리뷰 F-client-1).
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, turnRemainingMs: 30_500 });
    renderTable({ playerIds: [100, 101, 102], turnSeconds: 30 });

    expect(countdown()).toHaveTextContent('30초');
    act(() => {
      vi.advanceTimersByTime(1_000);
    });
    expect(countdown()).toHaveTextContent('29초');
  });

  it('턴 제한이 꺼진 방은 보이지 않는다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, turnRemainingMs: 12_345 });
    renderTable({ playerIds: [100, 101, 102], turnSeconds: 0 });

    expect(countdown()).toBeNull();
  });

  it('서버가 남은 시간을 주지 않으면(데드라인 없음) 보이지 않는다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, turnRemainingMs: null });
    renderTable({ playerIds: [100, 101, 102], turnSeconds: 30 });

    expect(countdown()).toBeNull();
  });

  it('경쟁 창이 열린 동안은 보이지 않고, 창이 닫힌 뒤 다음 차례부터 다시 센다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, turnRemainingMs: 20_000 });
    renderTable({ playerIds: [100, 101, 102], turnSeconds: 30 });
    expect(countdown()).not.toBeNull();

    act(() => {
      onecardRoomSink.applyEvent({
        type: 'CARD_PLAYED',
        seq: 2,
        payload: { seat: 1, card: c('HEART', 5), handCount: 1, attackStack: 0, direction: 1 },
      } as never);
      onecardRoomSink.applyEvent({ type: 'RACE_OPENED', seq: 3, payload: { ...RACE, raceId: 8 } } as never);
    });
    expect(countdown()).toBeNull();

    act(() => {
      vi.advanceTimersByTime(1_500);
      onecardRoomSink.applyEvent({ type: 'RACE_RESOLVED', seq: 4, payload: { raceId: 8, outcome: 'EXPIRED', bySeat: -1 } } as never);
      onecardRoomSink.applyEvent({ type: 'TURN_CHANGED', seq: 5, payload: { seat: 2, direction: 1, attackStack: 0 } } as never);
    });
    expect(countdown()).toHaveTextContent('30초');
  });

  it(`${TURN_URGENT_SECONDS}초 이하면 강조하고, 0 에서 멈춘다 — 매초 낭독하지 않는다`, () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 0, turnRemainingMs: 6_000 });
    renderTable({ playerIds: [100, 101, 102], turnSeconds: 30 });

    expect(countdown()).not.toHaveClass('oc-countdown-urgent');
    expect(countdown()).toHaveAttribute('aria-live', 'off');
    act(() => {
      vi.advanceTimersByTime(1_000);
    });
    expect(countdown()).toHaveClass('oc-countdown-urgent');
    act(() => {
      vi.advanceTimersByTime(10_000);
    });
    expect(countdown()).toHaveTextContent('0초');
  });

  it('방은 끝났는데 결과가 없으면(강제 종료) 세지 않는다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, turnRemainingMs: 20_000 });
    renderTable({ playerIds: [100, 101, 102], turnSeconds: 30, roomFinished: true });

    expect(countdown()).toBeNull();
  });
});

describe('OneCardTable — 멈춘 차례 복구 (D-140)', () => {
  it('0초가 되고 유예가 지나도 같은 차례면 그 차례에 한 번 다시 받는다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, turnRemainingMs: 4_000 });
    renderTable({ playerIds: [100, 101, 102], turnSeconds: 30 });

    act(() => {
      vi.advanceTimersByTime(4_000 + STALE_TURN_GRACE_MS - 1);
    });
    expect(requestResync).not.toHaveBeenCalled();
    act(() => {
      vi.advanceTimersByTime(STALE_TURN_JITTER_MS + 1);
    });
    expect(requestResync).toHaveBeenCalledTimes(1);

    // 응답이 또 0 을 줘도(폴러가 밀렸다) 같은 차례로는 다시 청하지 않는다 — 모든 클라가 몇 초마다 resync 하지 않게.
    act(() => {
      useOneCardStore.getState().applySnapshot(snapshotOf({ seatCount: 3, mySeat: 0, turnSeat: 1, turnRemainingMs: 0 }));
    });
    act(() => {
      vi.advanceTimersByTime((STALE_TURN_GRACE_MS + STALE_TURN_JITTER_MS) * 3);
    });
    expect(requestResync).toHaveBeenCalledTimes(1);
  });

  it('제때 다음 차례가 오면 다시 받지 않는다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, turnRemainingMs: 4_000 });
    renderTable({ playerIds: [100, 101, 102], turnSeconds: 30 });

    act(() => {
      vi.advanceTimersByTime(4_100);
      onecardRoomSink.applyEvent({ type: 'TURN_CHANGED', seq: 2, payload: { seat: 2, direction: 1, attackStack: 0 } } as never);
    });
    act(() => {
      vi.advanceTimersByTime(STALE_TURN_GRACE_MS + STALE_TURN_JITTER_MS + 1);
    });

    expect(requestResync).not.toHaveBeenCalled();
  });

  it('새 차례가 또 멈추면 그 차례에도 한 번 다시 받는다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, turnRemainingMs: 1_000 });
    renderTable({ playerIds: [100, 101, 102], turnSeconds: 30 });
    act(() => {
      vi.advanceTimersByTime(1_000 + STALE_TURN_GRACE_MS + STALE_TURN_JITTER_MS);
    });
    expect(requestResync).toHaveBeenCalledTimes(1);

    act(() => {
      onecardRoomSink.applyEvent({ type: 'TURN_CHANGED', seq: 2, payload: { seat: 2, direction: 1, attackStack: 0 } } as never);
    });
    act(() => {
      vi.advanceTimersByTime(30_000 + STALE_TURN_GRACE_MS + STALE_TURN_JITTER_MS);
    });
    expect(requestResync).toHaveBeenCalledTimes(2);
  });

  it('카운트다운이 없으면(턴 제한 끔·경쟁 창·끝난 방) 다시 받지 않는다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, turnRemainingMs: 1_000 });
    renderTable({ playerIds: [100, 101, 102], turnSeconds: 0 });
    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    expect(requestResync).not.toHaveBeenCalled();
  });
});

/** 좌석 배치 안 좌석들의 [좌석, 옆줄, 옆줄 안 순서] — DOM 순서 그대로. */
function layoutOf(): [number, string | null, string | null][] {
  const felt = screen.getByRole('group', { name: '좌석 배치' });
  return [...felt.querySelectorAll('.oc-seat')].map((el) => [
    Number(el.getAttribute('data-seat')),
    el.getAttribute('data-side'),
    el.getAttribute('data-slot'),
  ]);
}

describe('OneCardTable — 테이블 좌석 배치 (D-132)', () => {
  it.each([
    [2, [[1, 'top', '0']]],
    [
      4,
      [
        [1, 'left', '0'],
        [2, 'top', '0'],
        [3, 'right', '0'],
      ],
    ],
    [
      6,
      [
        [1, 'left', '0'],
        [2, 'left', '1'],
        [3, 'top', '0'],
        [4, 'right', '0'],
        [5, 'right', '1'],
      ],
    ],
  ])('%i인: 내 다음 좌석부터 왼쪽 옆줄 → 윗줄 → 오른쪽 옆줄에 시계 방향으로 앉는다', (n, expected) => {
    seed({ seatCount: n, mySeat: 0 });
    renderTable({ playerIds: playerIds(n) });

    expect(layoutOf()).toEqual(expected);
  });

  it('내 좌석 기준으로 돈다 — 4인 내 좌석 2 면 3 이 왼쪽, 0 이 위, 1 이 오른쪽', () => {
    seed({ seatCount: 4, mySeat: 2 });
    renderTable();

    expect(layoutOf()).toEqual([
      [3, 'left', '0'],
      [0, 'top', '0'],
      [1, 'right', '0'],
    ]);
  });

  it('가운데(region "테이블")가 좌석 배치 안에 있고, 내 정보줄은 그 밖 아래다', () => {
    seed({ seatCount: 4, mySeat: 0 });
    const { container } = renderTable();

    const felt = screen.getByRole('group', { name: '좌석 배치' });
    expect(within(felt).getByRole('region', { name: '테이블' })).toBeInTheDocument();
    expect(felt.querySelector('.oc-me')).toBeNull();
    expect(container.querySelector('.oc-me')).not.toBeNull();
  });

  it('관전자는 좌석 0 을 아래에 일반 좌석으로 두고 나머지를 0 기준으로 앉힌다 — "나" 줄은 없다', () => {
    seed({ seatCount: 4, mySeat: -1 });
    const { container } = renderTable({ spectator: true });

    expect(layoutOf()).toEqual([
      [1, 'left', '0'],
      [2, 'top', '0'],
      [3, 'right', '0'],
      [0, 'bottom', '0'],
    ]);
    expect(container.querySelector('.oc-me')).toBeNull();
    expect(screen.queryByText(/\(나\)/)).toBeNull();
  });

  it('좁은 옆줄 좌석의 긴 이름은 말줄임 대상이고 title 로 전체 이름을 보인다', () => {
    const long = '아주아주긴이름을가진플레이어';
    seed({ seatCount: 4, mySeat: 0 });
    const { container } = renderTable({ usernames: { 101: long } });

    const seat = container.querySelector('[data-seat="1"]') as HTMLElement;
    expect(seat).toHaveAttribute('data-side', 'left');
    expect(seat.querySelector('.oc-seat-name')).toHaveAttribute('title', long);
  });
});

describe('OneCardTable — 다음 차례 표시 (D-132)', () => {
  const seatEl = (seat: number) => document.querySelector(`[data-seat="${seat}"]`) as HTMLElement;

  it('지금 차례 좌석은 테두리, 진행 방향의 다음 좌석에는 "다음"', () => {
    seed({ seatCount: 4, mySeat: 0, turnSeat: 1 });
    renderTable();

    expect(seatEl(1)).toHaveClass('oc-seat-turn');
    expect(within(seatEl(2)).getByText('다음')).toBeInTheDocument();
    expect(screen.getAllByText('다음')).toHaveLength(1);
  });

  it('다음이 나면 "나" 줄에 표시한다', () => {
    seed({ seatCount: 4, mySeat: 0, turnSeat: 3 });
    const { container } = renderTable();

    expect(within(container.querySelector('.oc-me') as HTMLElement).getByText('다음')).toBeInTheDocument();
    expect(screen.getAllByText('다음')).toHaveLength(1);
  });

  it('다음 사람이 탈락했으면 건너뛴다', () => {
    seed({
      seatCount: 4,
      mySeat: 0,
      turnSeat: 1,
      seats: [seatOf(0), seatOf(1), seatOf(2, { eliminated: 'BANKRUPT', handCount: 0 }), seatOf(3)],
    });
    renderTable();

    expect(within(seatEl(3)).getByText('다음')).toBeInTheDocument();
    expect(within(seatEl(2)).queryByText('다음')).toBeNull();
  });

  it('경기장 오른쪽 위에 진행 방향을 화살표와 글자로 보인다 — 화살표 글리프는 읽지 않는다', () => {
    seed({ seatCount: 4, mySeat: 0, turnSeat: 1 });
    renderTable();

    // D-139 — 방향 배지는 가운데 칸이 아니라 경기장 상태 줄 오른쪽.
    const dir = within(screen.getByRole('group', { name: '판 상태' })).getByText('시계 방향', { exact: false });
    expect(dir).toHaveTextContent('↻ 시계 방향');
    expect(dir.querySelector('[aria-hidden="true"]')).toHaveTextContent('↻');
  });

  it('Q 로 방향이 뒤집히면 화살표와 "다음"이 함께 반대로 — 차례가 비는 동안은 "다음"이 없다', () => {
    seed({ seatCount: 4, mySeat: 0, turnSeat: 1 });
    renderTable();
    const status = () => screen.getByRole('group', { name: '판 상태' });
    expect(within(seatEl(2)).getByText('다음')).toBeInTheDocument();

    act(() => {
      onecardRoomSink.applyEvent({
        type: 'CARD_PLAYED',
        seq: 2,
        payload: { seat: 1, card: c('HEART', 12), declaredSuit: null, handCount: 4, attackStack: 0, direction: -1 },
      } as never);
    });
    expect(status()).toHaveTextContent('↺ 반시계 방향');
    expect(screen.queryByText('다음')).toBeNull();

    act(() => {
      onecardRoomSink.applyEvent({ type: 'TURN_CHANGED', seq: 3, payload: { seat: 0, direction: -1, attackStack: 0 } } as never);
    });
    expect(within(seatEl(3)).getByText('다음')).toBeInTheDocument();
    expect(screen.getAllByText('다음')).toHaveLength(1);
  });

  it('관전자에게는 "다음"이 아래 좌석(0)에 붙고 "나" 줄로 가지 않는다', () => {
    seed({ seatCount: 4, mySeat: -1, turnSeat: 3 });
    const { container } = renderTable({ spectator: true });

    expect(seatEl(0)).toHaveAttribute('data-side', 'bottom');
    expect(within(seatEl(0)).getByText('다음')).toBeInTheDocument();
    expect(container.querySelector('.oc-me')).toBeNull();
  });

  // D-132 후속 m2 — 강제 종료는 결과(MATCH_ENDED) 없이 방만 끝나 스토어의 차례가 그대로 남는다. 끝난 판에 차례를 보이지 않는다.
  it('방이 결과 없이 끝나면(강제 종료) 차례 테두리·"다음"·가운데 차례 이름이 사라진다', () => {
    seed({ seatCount: 4, mySeat: 0, turnSeat: 1 });
    const { container } = renderTable({ roomFinished: true });

    expect(container.querySelector('.oc-seat-turn')).toBeNull();
    expect(screen.queryByText('다음')).toBeNull();
    expect(screen.getByRole('region', { name: '테이블' })).not.toHaveTextContent('차례');
  });

  it('방이 결과 없이 끝나면 내 차례였어도 "내 차례"를 보이지 않는다', () => {
    seed({ seatCount: 4, mySeat: 0, turnSeat: 0 });
    const { container } = renderTable({ roomFinished: true });

    expect(screen.queryByText('내 차례')).toBeNull();
    expect(container.querySelector('.oc-me')).not.toHaveClass('oc-me-turn');
  });
});

/** D-135 — 방장 강제 종료는 게임판 머리 줄에. RoomPage 가 방장이고 나머지가 모두 봇일 때만 onAbort 를 넘긴다. */
describe('OneCardTable — 강제 종료 (D-135)', () => {
  it('onAbort 가 있으면 머리 줄에 "게임 종료" — 누르면 부른다', () => {
    seed({ seatCount: 4, mySeat: 0, turnSeat: 1 });
    const onAbort = vi.fn();
    renderTable({ onAbort });

    fireEvent.click(screen.getByRole('button', { name: '게임 종료' }));
    expect(onAbort).toHaveBeenCalledTimes(1);
  });

  it('onAbort 가 없으면(방장 아님·사람 판) 버튼이 없다', () => {
    seed({ seatCount: 4, mySeat: 0, turnSeat: 1 });
    renderTable();

    expect(screen.queryByRole('button', { name: '게임 종료' })).toBeNull();
  });

  it('방이 이미 끝났으면 버튼이 없다', () => {
    seed({ seatCount: 4, mySeat: 0, turnSeat: 1 });
    renderTable({ onAbort: vi.fn(), roomFinished: true });

    expect(screen.queryByRole('button', { name: '게임 종료' })).toBeNull();
  });
});

/** D-136 — 봇과 연습 방은 머리 줄에 "🤖 혼자 연습"(티츄 배너와 같은 문구). RoomPage 가 방의 fillWithBots 로 넘긴다. */
describe('OneCardTable — 혼자 연습 표시 (D-136)', () => {
  it('practice 면 머리 줄에 "🤖 혼자 연습"', () => {
    seed({ seatCount: 4, mySeat: 0, turnSeat: 1 });
    renderTable({ practice: true });

    expect(screen.getByText('🤖 혼자 연습')).toBeInTheDocument();
  });

  it('친구와 하기 방에는 없다', () => {
    seed({ seatCount: 4, mySeat: 0, turnSeat: 1 });
    renderTable();

    expect(screen.queryByText(/혼자 연습/)).toBeNull();
  });
});

/** D-139 — 경기장 맨 위 상태 줄: 왼쪽 위 남은 턴 시간, 오른쪽 위 진행 방향. 가운데 칸에는 더미·맨 위 카드·공격·차례만. */
describe('OneCardTable — 경기장 상태 줄 (D-139)', () => {
  const status = () => screen.getByRole('group', { name: '판 상태' });
  const center = () => screen.getByRole('region', { name: '테이블' });

  it('왼쪽 위에 남은 시간, 오른쪽 위에 방향 — 가운데 칸에는 둘 다 없다', () => {
    seed({ seatCount: 4, mySeat: 0, turnSeat: 1, turnRemainingMs: 12_000 });
    renderTable({ turnSeconds: 30 });

    const [left, right] = Array.from(status().children);
    expect(within(left as HTMLElement).getByRole('timer')).toHaveTextContent('12초');
    expect(right).toHaveTextContent('↻ 시계 방향');
    expect(within(center()).queryByRole('timer')).toBeNull();
    expect(center()).not.toHaveTextContent('시계 방향');
    expect(center()).toHaveTextContent('차례');
  });

  it('턴 제한이 꺼져 있으면 왼쪽은 비고 방향은 그대로 오른쪽', () => {
    seed({ seatCount: 4, mySeat: 0, turnSeat: 1 });
    renderTable({ turnSeconds: 0 });

    const [left, right] = Array.from(status().children);
    expect(left).toBeEmptyDOMElement();
    expect(right).toHaveTextContent('↻ 시계 방향');
  });

  it('상태 줄은 경기장(좌석 배치) 안 맨 앞이다', () => {
    seed({ seatCount: 4, mySeat: 0, turnSeat: 1 });
    renderTable();

    const felt = screen.getByRole('group', { name: '좌석 배치' });
    expect(felt.firstElementChild).toBe(status());
  });
});

/**
 * D-139 — 카드를 내면 낸 사람의 좌석 패널(내가 냈으면 내 줄)에서 가운데 맨 위 카드 자리로 카드가 날아간다(티츄 D-78 FLIP 과 같은 방식).
 * 날아오는 동안 맨 위 카드는 숨겨 두 장으로 보이지 않게 하고, 내려앉으면 걷는다. 카드 애니메이션 설정·resync 는 날리지 않는다.
 */
describe('OneCardTable — 카드 내기 비행 효과 (D-139)', () => {
  const fly = () => document.querySelector('.oc-fly');
  const top = () => document.querySelector('.oc-top');
  const play = (seat: number, card: OneCardCard, seq: number) =>
    act(() => {
      onecardRoomSink.applyEvent({
        type: 'CARD_PLAYED',
        seq,
        payload: { seat, card, declaredSuit: null, handCount: 4, attackStack: 0, direction: 1 },
      } as never);
    });

  beforeEach(() => {
    useCardAnimStore.setState({ enabled: true });
  });

  // D-139 후속(사용자) — 날아온 카드가 다 덮을 때까지 가운데는 이전 맨 위 카드를 그대로 보이고, 내려앉은 뒤에 새 카드로 바꾼다.
  it('남이 내면 그 좌석 패널에서 날아오고, 다 덮을 때까지 가운데는 이전 카드 — 내려앉으면 새 카드', () => {
    seed({ seatCount: 4, mySeat: 0, turnSeat: 2 });
    renderTable();
    const before = top()!.textContent;

    play(2, c('SPADE', 9), 2);
    expect(fly()).toHaveAttribute('data-from-seat', '2');
    expect(fly()).toHaveAttribute('aria-hidden', 'true');
    expect(top()!.textContent).toBe(before);
    expect(top()!.querySelector('.oc-card')).toBeVisible();

    act(() => {
      vi.advanceTimersByTime(600);
    });
    expect(fly()).toBeNull();
    expect(top()!.textContent).not.toBe(before);
  });

  it('내가 내면 내 줄에서 날아간다', () => {
    seed({ seatCount: 4, mySeat: 0, turnSeat: 0 });
    renderTable();

    play(0, c('HEART', 9), 2);
    expect(fly()).toHaveAttribute('data-from-seat', '0');
  });

  it('카드 애니메이션을 끄면 날리지 않는다', () => {
    useCardAnimStore.setState({ enabled: false });
    seed({ seatCount: 4, mySeat: 0, turnSeat: 2 });
    renderTable();

    const before = top()!.textContent;
    play(2, c('SPADE', 9), 2);
    expect(fly()).toBeNull();
    expect(top()!.textContent).not.toBe(before); // 날지 않으면 곧바로 새 카드.
  });

  it('다시 받기(resync)로 맨 위 카드가 바뀌어도 날리지 않는다', () => {
    seed({ seatCount: 4, mySeat: 0, turnSeat: 2 });
    renderTable();

    act(() => {
      seed({ seatCount: 4, mySeat: 0, turnSeat: 3, topCard: c('CLUB', 5) });
    });
    expect(fly()).toBeNull();
  });
});

/** D-152 — 내 차례에는 가운데 더미를 눌러도 먹는다(아래 "먹기" 버튼과 같은 일). */
describe('OneCardTable — 더미 눌러 먹기 (D-152)', () => {
  it('내 차례면 더미가 먹기 버튼이 되고, 누르면 DRAW 를 보낸다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 0, hand: [c('HEART', 3)], topCard: c('HEART', 2), attackStack: 4 });
    renderTable({ playerIds: [100, 101, 102] });

    const pile = screen.getByRole('button', { name: '먹기 (4장) — 뽑을 더미 30장' });
    fireEvent.click(pile);
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'DRAW' });
  });

  it('내 차례가 아니면 더미는 누를 수 없는 그림이다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, hand: [c('HEART', 3)] });
    renderTable({ playerIds: [100, 101, 102] });

    expect(screen.queryByRole('button', { name: /뽑을 더미/ })).toBeNull();
    expect(screen.getByRole('img', { name: '뽑을 더미 30장' })).toBeInTheDocument();
  });

  it('관전자에게는 버튼이 아니다', () => {
    seed({ seatCount: 3, mySeat: -1, turnSeat: 0 });
    renderTable({ playerIds: [100, 101, 102], myUserId: 999 });

    expect(screen.queryByRole('button', { name: /뽑을 더미/ })).toBeNull();
  });
});

/** D-152 — 누가 카드를 먹으면 가운데 더미에서 그 사람 자리로 뒷면이 날아간다(카드 내기 비행의 반대 방향). */
describe('OneCardTable — 먹기 비행 효과 (D-152)', () => {
  const fly = () => document.querySelector('.oc-fly-draw');
  const drawn = (seat: number, count: number, seq: number) =>
    act(() => {
      onecardRoomSink.applyEvent({
        type: 'CARDS_DRAWN',
        seq,
        payload: { seat, count, reason: 'TURN', handCount: 8, drawPileCount: 29 },
      } as never);
    });

  beforeEach(() => {
    useCardAnimStore.setState({ enabled: true });
  });

  it('남이 먹으면 더미에서 그 좌석으로 날아가고, 내려앉으면 걷는다 — 여러 장이면 장수를 단다', () => {
    seed({ seatCount: 4, mySeat: 0, turnSeat: 2 });
    renderTable();

    drawn(2, 3, 2);
    expect(fly()).toHaveAttribute('data-to-seat', '2');
    expect(fly()).toHaveAttribute('aria-hidden', 'true');
    expect(fly()).toHaveTextContent('+3');

    act(() => {
      vi.advanceTimersByTime(600);
    });
    expect(fly()).toBeNull();
  });

  it('한 장이면 장수를 달지 않는다 — 내가 먹으면 내 줄로', () => {
    seed({ seatCount: 4, mySeat: 0, turnSeat: 0 });
    renderTable();

    drawn(0, 1, 2);
    expect(fly()).toHaveAttribute('data-to-seat', '0');
    expect(fly()!.textContent).toBe('');
  });

  it('카드 애니메이션을 끄면 날리지 않는다', () => {
    useCardAnimStore.setState({ enabled: false });
    seed({ seatCount: 4, mySeat: 0, turnSeat: 2 });
    renderTable();

    drawn(2, 1, 2);
    expect(fly()).toBeNull();
  });
});

/**
 * D-154 — 카드를 내고 먹을 때 가운데 칸 크기가 바뀌어 게임판이 흔들렸다(지정 무늬 배지·이전 카드 줄·경쟁 결과 줄이 생겼다 사라짐).
 * 그 자리들을 늘 두고 비었을 때는 보이지만 않게 한다 — 내용이 바뀌어도 칸 크기가 같다.
 */
describe('OneCardTable — 가운데 칸 자리 고정 (D-154)', () => {
  it('지정 무늬·이전 카드·경쟁 결과 자리는 비어도 있다 — 보조기기에는 숨긴다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, topCard: c('HEART', 9) });
    const { container } = renderTable({ playerIds: [100, 101, 102] });

    const declared = container.querySelector('.oc-declared') as HTMLElement;
    expect(declared).not.toBeNull();
    expect(declared).toHaveAttribute('data-empty');
    expect(declared).toHaveAttribute('aria-hidden', 'true');

    const trail = container.querySelector('.oc-trail') as HTMLElement;
    expect(trail).not.toBeNull();
    expect(trail).toHaveAttribute('aria-hidden', 'true');
    expect(trail.children).toHaveLength(0);

    expect(container.querySelector('.oc-race-line')).not.toBeNull();
  });

  it('지정 무늬가 있으면 같은 자리에 보인다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, topCard: c('HEART', 7), declaredSuit: 'CLUB' });
    const { container } = renderTable({ playerIds: [100, 101, 102] });

    const declared = container.querySelector('.oc-declared') as HTMLElement;
    expect(declared).not.toHaveAttribute('data-empty');
    expect(declared).toHaveTextContent('지정 ♣ 클로버');
  });
});

/** D-154 — 손패 카드를 두 번 누르면(더블클릭) 바로 낸다. 7 은 무늬를 골라야 해서 고르기만 하고, 낼 수 없는 카드·내 차례 아님은 보내지 않는다. */
describe('OneCardTable — 더블클릭으로 내기 (D-154)', () => {
  const HAND = [c('CLUB', 4), c('HEART', 3), c('SPADE', 9), c('CLUB', 7)];

  it('낼 수 있는 카드를 더블클릭하면 그 카드를 보낸다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 0, hand: HAND });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.doubleClick(screen.getByRole('button', { name: '하트 3' }));

    expect(sendAction).toHaveBeenCalledWith({ '@action': 'PLAY_CARD', card: c('HEART', 3) });
  });

  it('7 은 더블클릭해도 보내지 않고 골라 둔다(무늬를 골라야 한다)', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 0, hand: HAND, topCard: c('CLUB', 9) });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.doubleClick(screen.getByRole('button', { name: '클로버 7' }));

    expect(sendAction).not.toHaveBeenCalled();
    expect(screen.getByRole('group', { name: '7 로 지정할 무늬' })).toBeInTheDocument();
  });

  it('낼 수 없는 카드·내 차례가 아니면 보내지 않는다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 0, hand: HAND, topCard: c('HEART', 9) });
    const view = renderTable({ playerIds: [100, 101, 102] });
    fireEvent.doubleClick(screen.getByRole('button', { name: '클로버 4' }));
    expect(sendAction).not.toHaveBeenCalled();
    view.unmount();

    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, hand: HAND });
    renderTable({ playerIds: [100, 101, 102] });
    fireEvent.doubleClick(screen.getByRole('button', { name: '하트 3' }));
    expect(sendAction).not.toHaveBeenCalled();
  });
});


/** D-154 — 원카드에도 티츄처럼 효과음 켜기/끄기와 이모지 반응. 반응은 보낸 사람 좌석 위에 잠깐 뜬다. */
describe('OneCardTable — 효과음·이모지 (D-154)', () => {
  beforeEach(() => {
    sendReaction.mockReset();
    useReactionStore.getState().reset();
    localStorage.removeItem('mirboard.sfxMuted');
  });

  it('😀 로 고른 이모지를 보낸다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1 });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.click(screen.getByRole('button', { name: '이모지 반응' }));
    fireEvent.click(screen.getByRole('button', { name: '👍' }));

    expect(sendReaction).toHaveBeenCalledWith('👍');
  });

  it('받은 반응은 보낸 사람 좌석 위에 뜬다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1 });
    const { container } = renderTable({ playerIds: [100, 101, 102] });

    act(() => useReactionStore.getState().add(2, '🔥'));

    expect(container.querySelector('[data-seat="2"] .oc-seat-reaction')).toHaveTextContent('🔥');
  });

  it('효과음 버튼으로 켜고 끈다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1 });
    renderTable({ playerIds: [100, 101, 102] });

    const sound = screen.getByRole('button', { name: '효과음' });
    expect(sound).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(sound);
    expect(screen.getByRole('button', { name: '효과음' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('관전자에게는 이모지 버튼이 없다', () => {
    seed({ seatCount: 3, mySeat: -1, turnSeat: 1 });
    renderTable({ playerIds: [100, 101, 102], myUserId: 999, spectator: true });

    expect(screen.queryByRole('button', { name: '이모지 반응' })).toBeNull();
  });
});


/** D-155 — 음성 대화: 🎧 로 참여(듣기, 마이크는 묻지 않음), 🎙 는 참여 중에만. 참여한 사람 좌석에 🎧. */
describe('OneCardTable — 음성 대화 (D-155)', () => {
  beforeEach(() => {
    sendVoice.mockReset();
    vi.stubGlobal('RTCPeerConnection', class {
      addTransceiver() {
        return { direction: 'sendrecv', sender: { replaceTrack: async () => {} } };
      }
      getTransceivers() {
        return [];
      }
      async createOffer() {
        return { type: 'offer', sdp: '' };
      }
      async setLocalDescription() {}
      close() {}
    });
  });

  afterEach(() => vi.unstubAllGlobals());

  it('🎧 로 참여하면 방에 알리고, 🎙 는 참여 전엔 잠겨 있다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1 });
    renderTable({ playerIds: [100, 101, 102] });

    expect(screen.getByRole('button', { name: '마이크' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '음성 대화' }));

    expect(sendVoice).toHaveBeenCalledWith({ type: 'JOIN' });
    expect(screen.getByRole('button', { name: '음성 대화' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: '마이크' })).toBeEnabled();
  });

  it('참여한 사람의 좌석에 🎧 가 붙는다', async () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1 });
    const { container } = renderTable({ playerIds: [100, 101, 102] });
    fireEvent.click(screen.getByRole('button', { name: '음성 대화' }));

    await act(async () => {
      voiceHandler!({ type: 'JOIN', from: 102, data: null });
      await Promise.resolve();
    });

    expect(container.querySelector('[data-seat="2"] .oc-seat-voice')).toHaveTextContent('🎧');
    expect(container.querySelector('[data-seat="1"] .oc-seat-voice')).toBeNull();
  });
});
