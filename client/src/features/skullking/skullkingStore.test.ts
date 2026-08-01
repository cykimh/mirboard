import { beforeEach, describe, expect, it } from 'vitest';
import { bidsRevealed, useSkullKingStore } from './skullkingStore';
import type {
  SeatView,
  SkullCard,
  SkullKingPrivateView,
  SkullKingTableView,
  SkullSuit,
} from '@/types/skullking';

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

const seat = (n: number, over: Partial<SeatView> = {}): SeatView => ({
  seat: n,
  handCount: 3,
  hasBid: false,
  bid: null,
  tricksWon: 0,
  ...over,
});

const TABLE: SkullKingTableView = {
  phase: 'BIDDING',
  roundNumber: 3,
  handSize: 3,
  startSeat: 1,
  currentTurnSeat: -1,
  seats: [seat(0), seat(1), seat(2), seat(3)],
  trick: [],
  cumulativeScores: { 0: 10, 1: -20, 2: 0, 3: 40 },
  desertedSeats: [],
  roundScores: {},
};

const PRIVATE: SkullKingPrivateView = {
  seat: 2,
  hand: [suit('GREEN', 5), special('PIRATE'), special('PIRATE')],
  myBid: null,
};

const snapshot = (
  over: Partial<{
    tableView: SkullKingTableView;
    privateHand: SkullKingPrivateView | null;
    eventSeq: number;
  }> = {},
) => ({
  roomId: 'r-1',
  phase: 'BIDDING',
  eventSeq: 10,
  tableView: TABLE,
  privateHand: PRIVATE,
  disconnectedSeats: [],
  chips: null,
  ...over,
});

const ev = (type: string, payload: unknown, seq?: number) => ({
  type,
  seq,
  payload,
});

const store = () => useSkullKingStore.getState();

beforeEach(() => {
  useSkullKingStore.getState().reset('r-1');
});

describe('applyEvent — seq 4값 계약', () => {
  beforeEach(() => store().applySnapshot(snapshot()));

  it('중복 seq 는 duplicate', () => {
    expect(store().applyEvent(ev('BID_SUBMITTED', { seat: 0 }, 10))).toBe('duplicate');
    expect(store().applyEvent(ev('BID_SUBMITTED', { seat: 0 }, 5))).toBe('duplicate');
  });

  it('연속 seq 는 applied 이고 lastSeq 를 전진시킨다', () => {
    expect(store().applyEvent(ev('BID_SUBMITTED', { seat: 0 }, 11))).toBe('applied');
    expect(store().lastSeq).toBe(11);
  });

  it('구멍 난 seq 는 gap (상태 무변경)', () => {
    expect(store().applyEvent(ev('BID_SUBMITTED', { seat: 0 }, 13))).toBe('gap');
    expect(store().seats[0].hasBid).toBe(false);
    expect(store().lastSeq).toBe(10);
  });

  it('리듀서 없는 타입은 unhandled — 스컬킹에 없는 CHIPS_SETTLED 포함', () => {
    expect(store().applyEvent(ev('CHIPS_SETTLED', {}, 11))).toBe('unhandled');
    expect(store().applyEvent(ev('WHO_KNOWS', {}, 11))).toBe('unhandled');
  });
});

describe('입찰 — State Hiding (§5)', () => {
  beforeEach(() => store().applySnapshot(snapshot()));

  it('BID_SUBMITTED 는 제출 여부만 세우고 값을 담지 않는다', () => {
    store().applyEvent(ev('BID_SUBMITTED', { seat: 1 }, 11));

    const s = store().seats.find((x) => x.seat === 1)!;
    expect(s.hasBid).toBe(true);
    expect(s.bid).toBeNull(); // ← 남의 예측값이 상태에 들어오면 화면에 샌다.
    expect(bidsRevealed(store())).toBe(false);
  });

  it('BIDS_REVEALED 후에만 값이 노출된다', () => {
    store().applyEvent(ev('BID_SUBMITTED', { seat: 1 }, 11));
    store().applyEvent(ev('BIDS_REVEALED', { bids: { 0: 0, 1: 2, 2: 1, 3: 3 } }, 12));
    store().applyEvent(ev('PLAYING_STARTED', { leadSeat: 1 }, 13));

    expect(store().seats.map((s) => s.bid)).toEqual([0, 2, 1, 3]);
    expect(store().seats.every((s) => s.hasBid)).toBe(true);
    expect(bidsRevealed(store())).toBe(true);
  });

  it('PLAYING_STARTED 가 차례와 단계를 옮긴다', () => {
    store().applyEvent(ev('PLAYING_STARTED', { leadSeat: 3 }, 11));

    expect(store().phase).toBe('PLAYING');
    expect(store().currentTurnSeat).toBe(3);
  });
});

describe('BIDDING_STARTED — 판정과 무관한 라운드 스크럽 (D-103)', () => {
  beforeEach(() => {
    store().applySnapshot(
      snapshot({
        tableView: {
          ...TABLE,
          phase: 'ROUND_END',
          seats: [
            seat(0, { hasBid: true, bid: 2, tricksWon: 2 }),
            seat(1, { hasBid: true, bid: 0, tricksWon: 1 }),
            seat(2, { hasBid: true, bid: 1, tricksWon: 0 }),
            seat(3, { hasBid: true, bid: 1, tricksWon: 0 }),
          ],
          roundScores: { 0: { bid: 2, won: 2, base: 40, bonus: 10, total: 50 } },
        },
      }),
    );
  });

  it('gap 이어도 지난 라운드 예측값·승수·트릭을 즉시 비운다', () => {
    // seq 를 크게 띄워 gap 을 만든다 — 실제로 라운드 경계에서 거의 항상 이렇게 온다
    // (비공개 HAND_DEALT 가 좌석 수만큼 seq 를 태우므로).
    const verdict = store().applyEvent(
      ev('BIDDING_STARTED', { roundNumber: 4, handSize: 4 }, 99),
    );

    expect(verdict).toBe('gap'); // 훅이 resync 를 부른다
    expect(store().phase).toBe('BIDDING');
    expect(store().roundNumber).toBe(4);
    expect(store().handSize).toBe(4);
    expect(store().seats.every((s) => !s.hasBid && s.bid === null)).toBe(true);
    expect(store().seats.every((s) => s.tricksWon === 0)).toBe(true);
    expect(store().roundScores).toEqual({});
    expect(store().settledTrick).toBeNull();
    expect(store().lastSeq).toBe(10); // gap 이므로 전진하지 않는다 (resync 가 권위)
  });

  /**
   * C5 실측에서 잡은 것 — 스크럽이 handCount 를 남겨두면 지난 라운드 끝의 0 이 새 라운드
   * 화면에 그대로 보이고, 내 손패에는 이미 없는 카드가 남아 클릭하면 CARD_NOT_OWNED 를 받는다.
   */
  it('새 라운드의 손패 장수를 payload 로 즉시 맞추고 내 손패를 비운다', () => {
    store().applyPrivateHand({
      seat: 2,
      cards: [suit('GREEN', 1), suit('GREEN', 2), suit('GREEN', 3)],
      roundNumber: 3,
    });

    store().applyEvent(ev('BIDDING_STARTED', { roundNumber: 4, handSize: 4 }, 99));

    expect(store().seats.every((s) => s.handCount === 4)).toBe(true);
    expect(store().hand).toEqual([]);
    expect(store().selectedIndex).toBeNull();
  });

  it('연속 seq 면 applied 이고 lastSeq 도 전진한다', () => {
    const verdict = store().applyEvent(
      ev('BIDDING_STARTED', { roundNumber: 4, handSize: 4 }, 11),
    );

    expect(verdict).toBe('applied');
    expect(store().lastSeq).toBe(11);
    expect(store().roundNumber).toBe(4);
  });

  /** 지난 이벤트의 재생이 진행 중인 라운드를 지우면 안 된다. */
  it('duplicate 면 스크럽하지 않는다', () => {
    const verdict = store().applyEvent(
      ev('BIDDING_STARTED', { roundNumber: 1, handSize: 1 }, 4),
    );

    expect(verdict).toBe('duplicate');
    expect(store().roundNumber).toBe(3); // 그대로
    expect(store().seats[0].bid).toBe(2); // 그대로
  });
});

describe('트릭 진행', () => {
  beforeEach(() => {
    store().applySnapshot(snapshot());
    store().applyEvent(ev('BIDS_REVEALED', { bids: { 0: 1, 1: 1, 2: 1, 3: 0 } }, 11));
    store().applyEvent(ev('PLAYING_STARTED', { leadSeat: 0 }, 12));
  });

  it('CARD_PLAYED 가 트릭에 쌓이고 그 좌석 손패 수를 줄인다', () => {
    store().applyEvent(
      ev('CARD_PLAYED', { seat: 0, card: suit('GREEN', 5), declaredAs: null }, 13),
    );

    expect(store().trick).toHaveLength(1);
    expect(store().trick[0]).toEqual({
      seat: 0,
      card: suit('GREEN', 5),
      declaredAs: null,
    });
    expect(store().seats.find((s) => s.seat === 0)!.handCount).toBe(2);
  });

  it('내가 낸 카드면 선택 상태를 비운다', () => {
    store().selectCard(1);
    store().setTigressDeclaration('PIRATE');

    store().applyEvent(
      ev('CARD_PLAYED', { seat: 2, card: special('PIRATE'), declaredAs: null }, 13),
    );

    expect(store().selectedIndex).toBeNull();
    expect(store().tigressDeclaration).toBeNull();
  });

  it('남이 낸 카드는 내 선택을 건드리지 않는다', () => {
    store().selectCard(1);

    store().applyEvent(
      ev('CARD_PLAYED', { seat: 0, card: suit('GREEN', 5), declaredAs: null }, 13),
    );

    expect(store().selectedIndex).toBe(1);
  });

  it('TRICK_TAKEN 이 스냅샷을 남기고 트릭을 비우며 승수를 올린다', () => {
    store().applyEvent(
      ev('CARD_PLAYED', { seat: 0, card: suit('GREEN', 5), declaredAs: null }, 13),
    );
    store().applyEvent(
      ev('CARD_PLAYED', { seat: 1, card: special('PIRATE'), declaredAs: null }, 14),
    );
    store().applyEvent(
      ev(
        'TRICK_TAKEN',
        { winnerSeat: 1, winningCard: special('PIRATE'), trickNumber: 1 },
        15,
      ),
    );

    expect(store().trick).toEqual([]);
    expect(store().settledTrick).not.toBeNull();
    expect(store().settledTrick!.winnerSeat).toBe(1);
    expect(store().settledTrick!.cards).toHaveLength(2);
    expect(store().seats.find((s) => s.seat === 1)!.tricksWon).toBe(1);
  });

  it('다음 카드가 나오면 지난 트릭 스냅샷이 걷힌다', () => {
    store().applyEvent(
      ev('TRICK_TAKEN', { winnerSeat: 1, winningCard: special('PIRATE'), trickNumber: 1 }, 13),
    );
    expect(store().settledTrick).not.toBeNull();

    store().applyEvent(
      ev('CARD_PLAYED', { seat: 1, card: suit('BLACK', 2), declaredAs: null }, 14),
    );

    expect(store().settledTrick).toBeNull();
  });

  it('TURN_CHANGED 가 차례를 옮긴다', () => {
    store().applyEvent(ev('TURN_CHANGED', { currentTurnSeat: 3 }, 13));
    expect(store().currentTurnSeat).toBe(3);
  });
});

describe('ROUND_ENDED — total 파생', () => {
  beforeEach(() => store().applySnapshot(snapshot()));

  it('payload 에 total 이 없으면 base+bonus 로 파생한다', () => {
    store().applyEvent(
      ev(
        'ROUND_ENDED',
        {
          roundNumber: 3,
          scores: {
            0: { bid: 2, won: 2, base: 40, bonus: 20 },
            1: { bid: 0, won: 1, base: -30, bonus: 0 },
          },
          cumulativeScores: { 0: 60, 1: -50 },
        },
        11,
      ),
    );

    expect(store().roundScores[0].total).toBe(60);
    expect(store().roundScores[1].total).toBe(-30);
    expect(store().phase).toBe('ROUND_END');
    expect(store().cumulativeScores).toEqual({ 0: 60, 1: -50 });
    expect(store().currentTurnSeat).toBe(-1);
  });

  it('total 이 있으면 그 값을 쓴다', () => {
    store().applyEvent(
      ev(
        'ROUND_ENDED',
        {
          roundNumber: 3,
          scores: { 0: { bid: 1, won: 1, base: 20, bonus: 0, total: 20 } },
          cumulativeScores: { 0: 20 },
        },
        11,
      ),
    );

    expect(store().roundScores[0].total).toBe(20);
  });
});

describe('탈주 · 매치 종료', () => {
  beforeEach(() => store().applySnapshot(snapshot()));

  it('SEAT_DESERTED 가 좌석을 오름차순으로 누적한다', () => {
    store().applyEvent(ev('SEAT_DESERTED', { seat: 3 }, 11));
    store().applyEvent(ev('SEAT_DESERTED', { seat: 1 }, 12));

    expect(store().desertedSeats).toEqual([1, 3]);
  });

  it('같은 좌석 재수신은 중복 추가하지 않는다', () => {
    store().applyEvent(ev('SEAT_DESERTED', { seat: 2 }, 11));
    store().applyEvent(ev('SEAT_DESERTED', { seat: 2 }, 12));

    expect(store().desertedSeats).toEqual([2]);
  });

  it('MATCH_ENDED 는 공동 승리를 그대로 담는다', () => {
    store().applyEvent(
      ev(
        'MATCH_ENDED',
        { winners: [1, 2], finalScores: { 0: 10, 1: 90, 2: 90, 3: 5 }, roundsPlayed: 10 },
        11,
      ),
    );

    expect(store().matchEnded!.winners).toEqual([1, 2]);
    expect(store().cumulativeScores[1]).toBe(90);
    expect(store().currentTurnSeat).toBe(-1);
  });
});

describe('연결 상태 메타 (seq 없음)', () => {
  beforeEach(() => store().applySnapshot(snapshot()));

  it('끊김/재접속이 Set 을 토글하고 lastSeq 판정에 영향이 없다', () => {
    expect(store().applyEvent(ev('PLAYER_DISCONNECTED', { seat: 1 }))).toBe('applied');
    expect([...store().disconnectedSeats]).toEqual([1]);
    expect(store().lastSeq).toBe(10);

    expect(store().applyEvent(ev('PLAYER_RECONNECTED', { seat: 1 }))).toBe('applied');
    expect([...store().disconnectedSeats]).toEqual([]);
    expect(store().lastSeq).toBe(10);
  });
});

describe('applySnapshot / applyPrivateHand', () => {
  it('공개+비공개 뷰를 함께 반영하고 lastSeq 를 권위값으로 재설정한다', () => {
    store().applySnapshot(snapshot({ eventSeq: 77 }));

    const s = store();
    expect(s.lastSeq).toBe(77);
    expect(s.phase).toBe('BIDDING');
    expect(s.handSize).toBe(3);
    expect(s.mySeat).toBe(2);
    expect(s.hand).toHaveLength(3);
    expect(s.errorMessage).toBeNull();
  });

  it('관전자(privateHand=null)도 예외 없이 통과한다', () => {
    expect(() =>
      store().applySnapshot(snapshot({ privateHand: null })),
    ).not.toThrow();

    expect(store().mySeat).toBe(-1);
    expect(store().hand).toEqual([]);
    expect(store().myBid).toBeNull();
  });

  /** resync 가 승자 왕관을 지우면 방금 트릭을 누가 가져갔는지 화면에서 사라진다. */
  it('resync 는 settledTrick 을 보존한다', () => {
    store().applySnapshot(snapshot());
    store().applyEvent(
      ev('TRICK_TAKEN', { winnerSeat: 1, winningCard: special('PIRATE'), trickNumber: 1 }, 11),
    );
    expect(store().settledTrick).not.toBeNull();

    store().applySnapshot(snapshot({ eventSeq: 20 }));

    expect(store().settledTrick).not.toBeNull();
  });

  it('HAND_DEALT 는 손패를 갈고 선택 상태를 초기화한다', () => {
    store().applySnapshot(snapshot());
    store().selectCard(2);
    store().setTigressDeclaration('ESCAPE');

    store().applyPrivateHand({
      seat: 2,
      cards: [suit('BLACK', 1), suit('BLACK', 2)],
      roundNumber: 4,
    });

    expect(store().hand).toHaveLength(2);
    expect(store().selectedIndex).toBeNull();
    expect(store().tigressDeclaration).toBeNull();
  });

  it('reset 은 방을 갈아끼우고 전 상태를 비운다', () => {
    store().applySnapshot(snapshot());
    store().reset('r-2');

    expect(store().roomId).toBe('r-2');
    expect(store().lastSeq).toBe(0);
    expect(store().seats).toEqual([]);
    expect(store().hand).toEqual([]);
    expect(store().phase).toBeNull();
  });
});
