import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SkullKingTable } from './SkullKingTable';
import { useSkullKingStore } from './skullkingStore';
import { useAuthStore } from '@/features/auth/authStore';
import type {
  CompletedRoundView,
  MatchEndedPayload,
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
  roundNumber?: number;
  completedRounds?: CompletedRoundView[];
  cumulativeScores?: Record<number, number>;
  matchResult?: MatchEndedPayload | null;
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
      roundNumber: opts.roundNumber ?? 3,
      handSize: 3,
      startSeat: 0,
      currentTurnSeat: opts.currentTurnSeat ?? -1,
      seats,
      trick: [],
      cumulativeScores: opts.cumulativeScores ?? {},
      desertedSeats: [],
      roundScores: {},
      completedRounds: opts.completedRounds,
      matchResult: opts.matchResult,
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
      const stat = within(el as HTMLElement).getAllByTitle('예측 승수')[0];
      const val = stat.querySelector('.sk-stat-val')!.textContent?.trim();
      expect(['제출', '—']).toContain(val);
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

describe('나가기 확인 (D-110)', () => {
  // 탈주는 되돌릴 수 없다(유령 좌석 자동조종, D-104) — 진행 중인 플레이어만 확인을 거친다.
  const exitButton = () => screen.getByRole('button', { name: '나가기' });

  it('진행 중인 플레이어가 확인을 취소하면 나가지 않는다', () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const onExit = vi.fn();
    seed({ seatCount: 4, mySeat: 0, phase: 'PLAYING' });
    renderTable({ onExit });

    fireEvent.click(exitButton());

    expect(confirm).toHaveBeenCalledOnce();
    expect(onExit).not.toHaveBeenCalled();
    confirm.mockRestore();
  });

  it('확인하면 나간다', () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const onExit = vi.fn();
    seed({ seatCount: 4, mySeat: 0, phase: 'PLAYING' });
    renderTable({ onExit });

    fireEvent.click(exitButton());

    expect(onExit).toHaveBeenCalledOnce();
    confirm.mockRestore();
  });

  it('관전자는 묻지 않고 바로 나간다', () => {
    const confirm = vi.spyOn(window, 'confirm');
    const onExit = vi.fn();
    seed({ seatCount: 4, mySeat: -1, phase: 'PLAYING' });
    renderTable({ onExit, spectator: true });

    fireEvent.click(exitButton());

    expect(confirm).not.toHaveBeenCalled();
    expect(onExit).toHaveBeenCalledOnce();
    confirm.mockRestore();
  });

  it('매치가 끝난 뒤에는 묻지 않는다', () => {
    const confirm = vi.spyOn(window, 'confirm');
    const onExit = vi.fn();
    seed({ seatCount: 4, mySeat: 0, phase: 'PLAYING' });
    useSkullKingStore.setState({
      matchEnded: { winners: [0], finalScores: { 0: 40 }, roundsPlayed: 10 },
    });
    renderTable({ onExit });

    fireEvent.click(exitButton());

    expect(confirm).not.toHaveBeenCalled();
    expect(onExit).toHaveBeenCalledOnce();
    confirm.mockRestore();
  });
});

// ---------- D-120 — 라운드 결과 · 점수표 · 종료 후 게임판 유지 ----------

const R3: CompletedRoundView = {
  roundNumber: 3,
  scores: {
    0: { bid: 1, won: 1, base: 20, bonus: 10, total: 30 },
    1: { bid: 2, won: 0, base: -20, bonus: 0, total: -20 },
    2: { bid: 0, won: 0, base: 30, bonus: 0, total: 30 },
    3: { bid: 0, won: 2, base: -30, bonus: 0, total: -30 },
  },
};

/** 라운드 3 이 끝나고 라운드 4 예측 중 — 서버는 라운드 사이에 멈추지 않는다. */
function seedRound4Bidding(mySeat = 0) {
  seed({
    seatCount: 4,
    mySeat,
    phase: 'BIDDING',
    roundNumber: 4,
    hand: [suit('GREEN', 5)],
    completedRounds: [R3],
    cumulativeScores: { 0: 50, 1: -10, 2: 40, 3: -30 },
  });
}

const resultPanel = () => screen.queryByRole('region', { name: '라운드 3 결과' });

describe('직전 라운드 결과 — 다음 라운드 예측 중 비차단 표시 (D-120)', () => {
  it('BIDDING R4 에서 라운드 3 결과를 좌석별로 보인다', () => {
    seedRound4Bidding();
    renderTable();

    const panel = resultPanel()!;
    expect(panel).not.toBeNull();
    const rows = within(panel).getAllByRole('row').slice(1); // 머리글 제외
    expect(rows).toHaveLength(4);
    const mine = rows.find((row) => row.textContent?.includes('(나)'))!;
    expect(mine.textContent).toContain('+30');
    expect(mine.textContent).toContain('✓');
    expect(mine.textContent).toContain('50'); // 누적
    const missed = rows.find((row) => row.textContent?.includes('#101'))!;
    expect(missed.textContent).toContain('✗');
    expect(missed.textContent).toContain('-20');
  });

  it('입력 동선이 먼저다 — 예측 패널이 결과 패널보다 DOM 상 앞에 있다', () => {
    seedRound4Bidding();
    const { container } = renderTable();

    const bid = container.querySelector('.sk-bid')!;
    expect(bid).not.toBeNull();
    expect(
      bid.compareDocumentPosition(resultPanel()!) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it('닫으면 그 라운드 결과는 다시 뜨지 않는다', () => {
    seedRound4Bidding();
    renderTable();

    fireEvent.click(within(resultPanel()!).getByRole('button', { name: '닫기' }));

    expect(resultPanel()).toBeNull();
  });

  it('PLAYING 에 들어가면 보이지 않는다', () => {
    seed({
      seatCount: 4,
      mySeat: 0,
      phase: 'PLAYING',
      roundNumber: 4,
      completedRounds: [R3],
    });
    renderTable();

    expect(resultPanel()).toBeNull();
  });

  it('관전자도 본다', () => {
    seed({
      seatCount: 4,
      mySeat: -1,
      phase: 'BIDDING',
      roundNumber: 4,
      completedRounds: [R3],
    });
    renderTable({ spectator: true });

    expect(resultPanel()).not.toBeNull();
  });

  it('내 정보줄에 직전 라운드 값이 함께 뜬다', () => {
    seedRound4Bidding();
    const { container } = renderTable();

    const me = container.querySelector('.sk-me')!;
    expect(me.textContent).toContain('직전');
    expect(me.textContent).toContain('R3');
    expect(me.textContent).toContain('+30');
  });
});

describe('점수표 모달 (D-120)', () => {
  it('헤더의 점수표 버튼이 dialog 를 연다', () => {
    seedRound4Bidding();
    renderTable();

    const button = screen.getByRole('button', { name: '점수표' });
    expect(button).toHaveAttribute('aria-haspopup', 'dialog');
    fireEvent.click(button);

    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByRole('table').textContent).toContain('R3');
  });

  it('결과 패널의 점수표 전체 버튼도 같은 dialog 를 연다', () => {
    seedRound4Bidding();
    renderTable();

    fireEvent.click(within(resultPanel()!).getByRole('button', { name: '점수표 전체' }));

    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});

describe('매치 종료 패널의 라운드 표 (D-120)', () => {
  it('접힌 상세에 라운드 표가 있고 합계는 최종 점수다', () => {
    const finalScores = { 0: 77, 1: -10, 2: 40, 3: -30 };
    seed({
      seatCount: 4,
      mySeat: 0,
      phase: 'ROUND_END',
      roundNumber: 3,
      completedRounds: [R3],
      matchResult: { winners: [0], finalScores, roundsPlayed: 10 },
    });
    const { container } = renderTable();

    const details = container.querySelector('.sk-match-end details')!;
    expect(details).not.toBeNull();
    const total = [...details.querySelectorAll('tbody tr')].at(-1)!;
    expect(total.textContent).toContain('77');
  });
});

describe('roomFinished — 종료 전이 후에도 유지된 게임판 (D-120)', () => {
  it('매치 결과가 아직 없으면 종료 안내를 보이고 입력 패널을 숨긴다', () => {
    seed({ seatCount: 4, mySeat: 0, phase: 'BIDDING', hand: [suit('GREEN', 5)] });
    const onExit = vi.fn();
    const { container } = renderTable({ roomFinished: true, onExit });

    const note = container.querySelector('.sk-finished-note')!;
    expect(note.textContent).toContain('게임이 종료되었습니다');
    expect(container.querySelector('.sk-bid')).toBeNull();
    fireEvent.click(within(note as HTMLElement).getByRole('button', { name: '메인으로' }));
    expect(onExit).toHaveBeenCalledOnce();
  });

  it('PLAYING 이어도 카드 제출 패널을 숨긴다', () => {
    seed({
      seatCount: 4,
      mySeat: 0,
      phase: 'PLAYING',
      currentTurnSeat: 0,
      hand: [suit('GREEN', 5)],
    });
    renderTable({ roomFinished: true });

    expect(screen.queryByRole('button', { name: '카드 제출' })).toBeNull();
  });

  it('매치 결과가 있으면 종료 안내 대신 매치 종료 패널', () => {
    seed({
      seatCount: 4,
      mySeat: 0,
      phase: 'ROUND_END',
      matchResult: { winners: [1], finalScores: { 0: 1, 1: 2 }, roundsPlayed: 10 },
    });
    const { container } = renderTable({ roomFinished: true });

    expect(container.querySelector('.sk-finished-note')).toBeNull();
    expect(container.querySelector('.sk-match-end')).not.toBeNull();
  });

  it('나가기에 탈주 확인을 묻지 않는다', () => {
    const confirm = vi.spyOn(window, 'confirm');
    const onExit = vi.fn();
    seed({ seatCount: 4, mySeat: 0, phase: 'PLAYING' });
    renderTable({ roomFinished: true, onExit });

    fireEvent.click(screen.getByRole('button', { name: '나가기' }));

    expect(confirm).not.toHaveBeenCalled();
    expect(onExit).toHaveBeenCalledOnce();
    confirm.mockRestore();
  });
});

describe('규칙 버튼 (D-121)', () => {
  const SK_KEY = 'mirboard.tutorial.skull_king.seen.v1';

  beforeEach(() => localStorage.clear());

  it('누르면 스컬킹 튜토리얼이 열리고, 게임 액션은 보내지 않는다', () => {
    seed({ seatCount: 4, mySeat: 0, phase: 'BIDDING', hand: [suit('GREEN', 5)] });
    renderTable();

    fireEvent.click(screen.getByRole('button', { name: '규칙' }));

    expect(
      screen.getByRole('heading', { name: '미르보드 스컬킹에 오신 걸 환영합니다' }),
    ).toBeInTheDocument();
    // 다이얼로그는 body 포털(.sk-table 밖)이라 본문이 칩 토큰 클래스를 직접 가져야 한다.
    expect(document.querySelector('.tutorial-body')).toHaveClass('sk-tokens');
    expect(sendAction).not.toHaveBeenCalled();
  });

  it('미열람이어도 게임판에서는 자동으로 뜨지 않는다 — 타이머가 흐르는 중이다', () => {
    seed({ seatCount: 4, mySeat: 0, phase: 'PLAYING' });
    renderTable();

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(localStorage.getItem(SK_KEY)).toBeNull();
  });

  it('관전자도 연다', () => {
    seed({ seatCount: 4, mySeat: -1, phase: 'PLAYING' });
    renderTable({ spectator: true });

    fireEvent.click(screen.getByRole('button', { name: '규칙' }));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});
