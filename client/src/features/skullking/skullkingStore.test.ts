import { beforeEach, describe, expect, it } from 'vitest';
import {
  bidsRevealed,
  lastRoundResult,
  useSkullKingStore,
} from './skullkingStore';
import type {
  CompletedRoundView,
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

// ---------- D-120 — 라운드 기록·매치 결과 ----------

const r = (
  roundNumber: number,
  totals: Record<number, number>,
): CompletedRoundView => ({
  roundNumber,
  scores: Object.fromEntries(
    Object.entries(totals).map(([seat, t]) => [
      seat,
      { bid: 0, won: 0, base: t, bonus: 0, total: t },
    ]),
  ),
});

const roundEnded = (roundNumber: number, seq: number) =>
  ev(
    'ROUND_ENDED',
    {
      roundNumber,
      scores: {
        0: { bid: 1, won: 1, base: 20, bonus: 10 },
        1: { bid: 0, won: 1, base: -10, bonus: 0 },
      },
      cumulativeScores: { 0: 30, 1: -10 },
    },
    seq,
  );

describe('completedRounds — 권위값 교체 + 라이브 upsert (D-120)', () => {
  it('applySnapshot 은 기록을 통째로 교체한다 — 재호출해도 중복이 없다', () => {
    const tableView = { ...TABLE, completedRounds: [r(1, { 0: 10 }), r(2, { 0: 20 })] };
    store().applySnapshot(snapshot({ tableView }));
    store().applySnapshot(snapshot({ tableView }));

    expect(store().completedRounds.map((x) => x.roundNumber)).toEqual([1, 2]);
  });

  it('필드가 없는 구 응답이면 빈 목록', () => {
    store().applySnapshot(snapshot({ tableView: { ...TABLE, completedRounds: [r(1, { 0: 1 })] } }));
    store().applySnapshot(snapshot()); // TABLE 에는 completedRounds 가 없다

    expect(store().completedRounds).toEqual([]);
  });

  it('ROUND_ENDED 는 total 을 파생해 한 건 append 하고, 같은 라운드 재수신은 덮어쓴다', () => {
    store().applySnapshot(snapshot({ tableView: { ...TABLE, completedRounds: [r(1, { 0: 5 })] } }));

    store().applyEvent(roundEnded(2, 11));
    expect(store().completedRounds.map((x) => x.roundNumber)).toEqual([1, 2]);
    expect(store().completedRounds[1].scores[0].total).toBe(30);
    expect(store().completedRounds[1].scores[1].total).toBe(-10);

    // resync 로 lastSeq 가 되감긴 뒤 같은 라운드가 다시 와도 한 건만 남는다.
    store().applySnapshot(
      snapshot({ eventSeq: 10, tableView: { ...TABLE, completedRounds: store().completedRounds } }),
    );
    store().applyEvent(roundEnded(2, 11));
    expect(store().completedRounds.map((x) => x.roundNumber)).toEqual([1, 2]);
  });

  it('번호 순서가 어긋나 와도 라운드 번호 순으로 정렬된다', () => {
    store().applySnapshot(snapshot({ tableView: { ...TABLE, completedRounds: [r(3, { 0: 1 })] } }));
    store().applyEvent(roundEnded(2, 11));

    expect(store().completedRounds.map((x) => x.roundNumber)).toEqual([2, 3]);
  });

  it('BIDDING_STARTED 스크럽 뒤에도 기록은 남는다', () => {
    store().applySnapshot(snapshot());
    store().applyEvent(roundEnded(3, 11));
    store().applyEvent(ev('BIDDING_STARTED', { roundNumber: 4, handSize: 4 }, 99));

    expect(store().roundScores).toEqual({});
    expect(store().completedRounds.map((x) => x.roundNumber)).toEqual([3]);
  });

  it('reset 은 기록을 비운다', () => {
    store().applySnapshot(snapshot({ tableView: { ...TABLE, completedRounds: [r(1, { 0: 1 })] } }));
    store().reset('r-2');

    expect(store().completedRounds).toEqual([]);
  });
});

describe('matchResult → matchEnded 복원 (D-120)', () => {
  it('resync 에 매치 결과가 있으면 종료 패널 상태를 복원한다', () => {
    const matchResult = { winners: [1], finalScores: { 0: 10, 1: 90 }, roundsPlayed: 10 };
    store().applySnapshot(snapshot({ tableView: { ...TABLE, matchResult } }));

    expect(store().matchEnded).toEqual(matchResult);
  });

  it('매치 진행 중(null)·구 응답(필드 없음)이면 null', () => {
    store().applySnapshot(snapshot({ tableView: { ...TABLE, matchResult: null } }));
    expect(store().matchEnded).toBeNull();

    store().applySnapshot(snapshot());
    expect(store().matchEnded).toBeNull();
  });

  it('라이브로 받은 종료 패널을 진행 중 resync 가 지우지 않는다 — 종료 후 resync 는 값을 싣는다', () => {
    const matchResult = { winners: [0], finalScores: { 0: 40 }, roundsPlayed: 10 };
    store().applySnapshot(snapshot());
    store().applyEvent(ev('MATCH_ENDED', matchResult, 11));
    store().applySnapshot(snapshot({ eventSeq: 11, tableView: { ...TABLE, matchResult } }));

    expect(store().matchEnded).toEqual(matchResult);
  });
});

/**
 * D-122 — 매치가 끝난 뒤에는 게임 이벤트를 반영하지 않는다. 탈주 조기 종료·강제 종료 뒤에도
 * 서버 턴 타이머·봇이 버려진 라운드를 계속 진행하던 결함이 있었고, D-120 이 유지한 게임판에
 * 그 이벤트가 그대로 보였다(종료 패널 위로 '내 차례'가 다시 뜨고 트릭 레일이 움직인다).
 * 원인은 서버에서 고치지만 클라도 심층 방어로 막는다. 권위값인 resync 만 예외다. 이 가드는
 * MATCH_ENDED 를 받은 뒤에만 걸린다 — 강제 종료는 MATCH_ENDED 를 내지 않아 서버 정지가 막는다.
 */
describe('매치 종료 뒤 잔여 이벤트 무시 (D-122)', () => {
  const RESULT = { winners: [2], finalScores: { 0: 10, 1: -20, 2: 90, 3: 40 }, roundsPlayed: 3 };

  beforeEach(() => {
    store().applySnapshot(
      snapshot({
        tableView: {
          ...TABLE,
          phase: 'PLAYING',
          currentTurnSeat: 0,
          seats: [seat(0), seat(1), seat(2), seat(3)],
          trick: [],
        },
      }),
    );
    expect(store().applyEvent(ev('MATCH_ENDED', RESULT, 11))).toBe('applied');
  });

  it('TURN_CHANGED 가 내 차례를 다시 세우지 않는다', () => {
    expect(store().applyEvent(ev('TURN_CHANGED', { currentTurnSeat: 2 }, 12))).toBe('ignored');
    expect(store().currentTurnSeat).toBe(-1);
  });

  it('CARD_PLAYED·TRICK_TAKEN 이 트릭 레일과 승수를 움직이지 않는다', () => {
    store().applyEvent(
      ev('CARD_PLAYED', { seat: 2, card: suit('GREEN', 5), declaredAs: null }, 12),
    );
    expect(store().trick).toEqual([]);
    expect(store().seats.find((s) => s.seat === 2)!.handCount).toBe(3);

    store().applyEvent(
      ev('TRICK_TAKEN', { winnerSeat: 2, winningCard: suit('GREEN', 5), trickNumber: 1 }, 13),
    );
    expect(store().settledTrick).toBeNull();
    expect(store().seats.find((s) => s.seat === 2)!.tricksWon).toBe(0);
  });

  it('PLAYING_STARTED·ROUND_ENDED 도 단계·점수를 바꾸지 않는다', () => {
    store().applyEvent(ev('PLAYING_STARTED', { leadSeat: 2 }, 12));
    expect(store().currentTurnSeat).toBe(-1);

    store().applyEvent(
      ev(
        'ROUND_ENDED',
        {
          roundNumber: 4,
          scores: { 2: { bid: 0, won: 0, base: 40, bonus: 0 } },
          cumulativeScores: { 0: 0, 1: 0, 2: 999, 3: 0 },
        },
        13,
      ),
    );
    expect(store().cumulativeScores).toEqual(RESULT.finalScores);
    expect(store().completedRounds).toEqual([]);
  });

  it('BIDDING_STARTED 가 판정과 무관한 스크럽(D-103)도 하지 않는다', () => {
    expect(
      store().applyEvent(ev('BIDDING_STARTED', { roundNumber: 4, handSize: 4 }, 20)),
    ).toBe('ignored');
    expect(store().roundNumber).toBe(3);
    expect(store().hand).toHaveLength(3);
  });

  it('잔여 이벤트는 resync 를 부르지 않는다 — gap 이어도 ignored', () => {
    expect(store().applyEvent(ev('CARD_PLAYED', {}, 99))).toBe('ignored');
    expect(store().applyEvent(ev('WHO_KNOWS', {}, 12))).toBe('ignored');
    expect(store().lastSeq).toBe(11);
  });

  it('HAND_DEALT 로 손패를 갈지 않는다', () => {
    store().applyPrivateHand({ seat: 2, cards: [suit('BLACK', 1)], roundNumber: 4 });
    expect(store().hand).toHaveLength(3);
  });

  it('연결 상태 배지는 계속 반영한다 (게임 진행이 아니다)', () => {
    expect(store().applyEvent(ev('PLAYER_DISCONNECTED', { seat: 1 }))).toBe('applied');
    expect([...store().disconnectedSeats]).toEqual([1]);
  });

  it('권위값인 resync 는 그대로 반영한다', () => {
    store().applySnapshot(
      snapshot({ eventSeq: 30, tableView: { ...TABLE, roundNumber: 3, matchResult: RESULT } }),
    );
    expect(store().lastSeq).toBe(30);
    expect(store().matchEnded).toEqual(RESULT);

    // 서버가 매치가 끝나지 않았다고 말하면(진행 중 상태) 그 말이 이긴다 — 다시 이벤트를 받는다.
    store().applySnapshot(snapshot({ eventSeq: 31, tableView: { ...TABLE, matchResult: null } }));
    expect(store().matchEnded).toBeNull();
    expect(store().applyEvent(ev('BID_SUBMITTED', { seat: 0 }, 32))).toBe('applied');
  });
});

describe('lastRoundResult — 다음 라운드 Bidding 동안의 직전 결과 (D-120)', () => {
  const base = {
    phase: 'BIDDING' as const,
    roundNumber: 4,
    completedRounds: [r(2, { 0: 1 }), r(3, { 0: 2 })],
    roundScores: {},
    matchEnded: null,
  };

  it('BIDDING N+1 이면 라운드 N 기록을 돌려준다', () => {
    expect(lastRoundResult(base)?.roundNumber).toBe(3);
  });

  it('BIDDING 인데 마지막 기록이 직전 라운드가 아니면 null (기록 누락 구간)', () => {
    expect(lastRoundResult({ ...base, roundNumber: 5 })).toBeNull();
    expect(lastRoundResult({ ...base, completedRounds: [] })).toBeNull();
  });

  it('ROUND_END 이고 마지막 기록 번호가 같으면 그 기록', () => {
    const got = lastRoundResult({ ...base, phase: 'ROUND_END', roundNumber: 3 });
    expect(got?.roundNumber).toBe(3);
    expect(got?.scores[0].total).toBe(2);
  });

  /** 잠금 없는 resync 가 RoundEnd 저장과 매치 저장 사이에 끼면 기록이 한 라운드 늦다. */
  it('ROUND_END 인데 기록이 아직 없으면 roundScores 로 합성한다', () => {
    const roundScores = { 0: { bid: 1, won: 1, base: 20, bonus: 0, total: 20 } };
    const got = lastRoundResult({
      ...base,
      phase: 'ROUND_END',
      roundNumber: 4,
      roundScores,
    });

    expect(got).toEqual({ roundNumber: 4, scores: roundScores });
  });

  it('PLAYING·매치 종료면 null', () => {
    expect(lastRoundResult({ ...base, phase: 'PLAYING' })).toBeNull();
    expect(
      lastRoundResult({
        ...base,
        matchEnded: { winners: [0], finalScores: { 0: 1 }, roundsPlayed: 10 },
      }),
    ).toBeNull();
  });
});
