import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SkullKingTable } from './SkullKingTable';
import { useSkullKingStore } from './skullkingStore';
import { useAuthStore } from '@/features/auth/authStore';
import type {
  SeatView,
  SkullCard,
  SkullKingPhase,
  SkullSuit,
} from '@/types/skullking';

// 소켓만 모킹하고 스토어는 실물을 seed 한다 (GameTable.test 와 같은 방식).
const sendAction = vi.fn();
vi.mock('@/ws/useStompRoom', () => ({
  useStompRoom: () => ({
    connected: true,
    sendAction: (a: Record<string, unknown>) => sendAction(a),
    sendChat: vi.fn(),
    sendReaction: vi.fn(),
    chatPanelOpenRef: { current: false },
  }),
}));

const suit = (s: SkullSuit, rank: number): SkullCard => ({
  suit: s,
  rank,
  special: null,
});
const special = (k: SkullCard['special']): SkullCard => ({
  suit: null,
  rank: 0,
  special: k,
});

const seatOf = (n: number, over: Partial<SeatView> = {}): SeatView => ({
  seat: n,
  handCount: 3,
  hasBid: false,
  bid: null,
  tricksWon: 0,
  ...over,
});

function seed(opts: {
  seatCount: number;
  mySeat: number;
  phase: SkullKingPhase;
  hand?: SkullCard[];
  currentTurnSeat?: number;
  seats?: SeatView[];
  bids?: Record<number, number>;
}) {
  const seats =
    opts.seats ??
    Array.from({ length: opts.seatCount }, (_, i) =>
      seatOf(i, opts.bids ? { hasBid: true, bid: opts.bids[i] ?? 0 } : {}),
    );
  useSkullKingStore.getState().reset('r-1');
  useSkullKingStore.getState().applySnapshot({
    roomId: 'r-1',
    phase: opts.phase,
    eventSeq: 1,
    tableView: {
      phase: opts.phase,
      roundNumber: 3,
      handSize: 3,
      startSeat: 0,
      currentTurnSeat: opts.currentTurnSeat ?? -1,
      seats,
      trick: [],
      cumulativeScores: {},
      desertedSeats: [],
      roundScores: {},
    },
    privateHand:
      opts.mySeat >= 0
        ? { seat: opts.mySeat, hand: opts.hand ?? [], myBid: null }
        : null,
    disconnectedSeats: [],
    chips: null,
  });
}

const playerIds = (n: number) => Array.from({ length: n }, (_, i) => 100 + i);

function renderTable(over: Partial<Parameters<typeof SkullKingTable>[0]> = {}) {
  const n = over.playerIds?.length ?? 4;
  return render(
    <SkullKingTable
      roomId="r-1"
      playerIds={playerIds(n)}
      myUserId={100}
      {...over}
    />,
  );
}

beforeEach(() => {
  sendAction.mockReset();
  useAuthStore.setState({ token: 'tok' } as never);
  useSkullKingStore.getState().reset('r-1');
});

describe('좌석 렌더 — 2/5/8인', () => {
  it.each([2, 5, 8])('%i인 방에서 상대 좌석이 n-1개 렌더된다', (n) => {
    seed({ seatCount: n, mySeat: 0, phase: 'PLAYING' });
    const { container } = renderTable({ playerIds: playerIds(n) });

    expect(container.querySelectorAll('.sk-seat')).toHaveLength(n - 1);
  });

  it('내 좌석은 상대 그리드에 없고 내 요약 줄에 있다', () => {
    seed({ seatCount: 4, mySeat: 2, phase: 'PLAYING' });
    const { container } = renderTable();

    const seats = [...container.querySelectorAll('.sk-seat')].map((el) =>
      el.getAttribute('data-seat'),
    );
    expect(seats).not.toContain('2');
    expect(container.querySelector('.sk-me')).not.toBeNull();
    expect(screen.getByText(/\(나\)/)).toBeInTheDocument();
  });

  it('mySeat 이 회전해도 상대 순서가 내 다음 차례부터다', () => {
    seed({ seatCount: 8, mySeat: 5, phase: 'PLAYING' });
    const { container } = renderTable({ playerIds: playerIds(8) });

    const seats = [...container.querySelectorAll('.sk-seat')].map((el) =>
      Number(el.getAttribute('data-seat')),
    );
    expect(seats).toEqual([6, 7, 0, 1, 2, 3, 4]);
  });

  it('인원별로 --sk-seat-min 이 달라진다', () => {
    seed({ seatCount: 4, mySeat: 0, phase: 'PLAYING' });
    const { container: c4 } = renderTable();
    const four = (c4.querySelector('.sk-table') as HTMLElement).style.getPropertyValue(
      '--sk-seat-min',
    );

    seed({ seatCount: 8, mySeat: 0, phase: 'PLAYING' });
    const { container: c8 } = renderTable({ playerIds: playerIds(8) });
    const eight = (c8.querySelector('.sk-table') as HTMLElement).style.getPropertyValue(
      '--sk-seat-min',
    );

    expect(four).toBe('132px');
    expect(eight).toBe('100px');
    expect(four).not.toBe(eight);
  });
});

describe('관전자', () => {
  it('전 좌석을 보고 손패·입찰 패널이 없다', () => {
    seed({ seatCount: 4, mySeat: -1, phase: 'BIDDING' });
    const { container } = renderTable({ spectator: true });

    expect(container.querySelectorAll('.sk-seat')).toHaveLength(4);
    expect(container.querySelector('.sk-bid')).toBeNull();
    expect(container.querySelector('.sk-hand')).toBeNull();
    expect(container.querySelector('.sk-me')).toBeNull();
  });
});

describe('State Hiding — 입찰 (§5)', () => {
  it('BIDDING 중에는 남의 예측값이 DOM 에 없다', () => {
    // 좌석 1·3 이 제출했지만 값은 서버가 보내지 않았다 (hasBid 만 true).
    seed({
      seatCount: 4,
      mySeat: 0,
      phase: 'BIDDING',
      seats: [
        seatOf(0),
        seatOf(1, { hasBid: true }),
        seatOf(2),
        seatOf(3, { hasBid: true }),
      ],
    });
    const { container } = renderTable();

    const seats = [...container.querySelectorAll('.sk-seat')];
    seats.forEach((el) => {
      // 제출한 좌석은 '제출', 아닌 좌석은 '—' — 숫자가 노출되면 회귀다.
      const val = within(el as HTMLElement).getAllByTitle('예측 승수')[0];
      expect(['제출', '—']).toContain(val.textContent?.replace('예측', '').trim());
    });
  });

  it('공개 후에는 예측값이 노출된다', () => {
    seed({
      seatCount: 4,
      mySeat: 0,
      phase: 'PLAYING',
      bids: { 0: 1, 1: 2, 2: 0, 3: 3 },
    });
    const { container } = renderTable();

    const text = container.querySelector('.sk-seats')!.textContent ?? '';
    expect(text).toContain('2');
    expect(text).toContain('3');
  });
});

describe('액션 payload', () => {
  it('입찰 클릭이 PLACE_BID 를 보낸다', () => {
    seed({ seatCount: 4, mySeat: 0, phase: 'BIDDING', hand: [suit('GREEN', 5)] });
    renderTable();

    fireEvent.click(screen.getByRole('button', { name: '2' }));

    expect(sendAction).toHaveBeenCalledWith({ '@action': 'PLACE_BID', bid: 2 });
  });

  it('일반 카드는 declaredAs 없이 PLAY_CARD 를 보낸다', () => {
    seed({
      seatCount: 4,
      mySeat: 0,
      phase: 'PLAYING',
      currentTurnSeat: 0,
      hand: [suit('GREEN', 5)],
    });
    renderTable();

    fireEvent.click(screen.getByRole('button', { name: '초록 5' }));
    fireEvent.click(screen.getByRole('button', { name: '카드 제출' }));

    expect(sendAction).toHaveBeenCalledWith({
      '@action': 'PLAY_CARD',
      card: suit('GREEN', 5),
    });
  });

  it('티그리스는 선언 없이 제출할 수 없고, 선언하면 declaredAs 가 실린다', () => {
    seed({
      seatCount: 4,
      mySeat: 0,
      phase: 'PLAYING',
      currentTurnSeat: 0,
      hand: [special('TIGRESS')],
    });
    renderTable();

    fireEvent.click(screen.getByRole('button', { name: '티그리스' }));
    expect(
      screen.getByRole('button', { name: '해적/탈출을 선언하세요' }),
    ).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: '해적' }));
    fireEvent.click(screen.getByRole('button', { name: '카드 제출' }));

    expect(sendAction).toHaveBeenCalledWith({
      '@action': 'PLAY_CARD',
      card: special('TIGRESS'),
      declaredAs: 'PIRATE',
    });
  });

  it('내 차례가 아니면 제출 버튼이 잠긴다', () => {
    seed({
      seatCount: 4,
      mySeat: 0,
      phase: 'PLAYING',
      currentTurnSeat: 2,
      hand: [suit('GREEN', 5)],
    });
    renderTable();

    expect(screen.getByRole('button', { name: '내 차례 아님' })).toBeDisabled();
  });

  /** 중복 특수 카드는 인덱스로 구분된다 — 값으로 고르면 두 장이 같이 선택된다. */
  it('같은 해적 2장 중 클릭한 한 장만 선택된다', () => {
    seed({
      seatCount: 4,
      mySeat: 0,
      phase: 'PLAYING',
      currentTurnSeat: 0,
      hand: [special('PIRATE'), special('PIRATE')],
    });
    const { container } = renderTable();

    const pirates = screen.getAllByRole('button', { name: '해적' });
    expect(pirates).toHaveLength(2);
    fireEvent.click(pirates[1]);

    expect(container.querySelectorAll('.sk-card-selected')).toHaveLength(1);
    expect(pirates[1]).toHaveAttribute('aria-pressed', 'true');
    expect(pirates[0]).toHaveAttribute('aria-pressed', 'false');
  });
});

describe('칩/판돈은 스컬킹에 없다', () => {
  it('칩 배지가 DOM 에 없다', () => {
    seed({ seatCount: 4, mySeat: 0, phase: 'PLAYING' });
    const { container } = renderTable();

    expect(container.textContent).not.toMatch(/칩|판돈/);
  });
});
