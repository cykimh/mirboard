import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MAX_PRESS_ATTEMPTS, useOneCardStore } from './onecardStore';
import {
  cardKey,
  type OneCardCard,
  type OneCardPrivateView,
  type OneCardSeatView,
  type OneCardSuit,
  type OneCardTableView,
} from '@/types/onecard';

const card = (suit: OneCardSuit, rank: number): OneCardCard => ({ suit, rank, joker: null });
const seat = (n: number, over: Partial<OneCardSeatView> = {}): OneCardSeatView => ({
  seat: n,
  handCount: 7,
  eliminated: null,
  ...over,
});

const TABLE: OneCardTableView = {
  phase: 'PLAYING',
  seats: [seat(0), seat(1), seat(2)],
  topCard: card('HEART', 9),
  declaredSuit: null,
  attackStack: 0,
  direction: 1,
  turnSeat: 1,
  drawPileCount: 32,
  race: null,
  result: null,
};

const PRIVATE: OneCardPrivateView = {
  seat: 1,
  hand: [card('HEART', 3), card('SPADE', 9), card('CLUB', 7)],
  handVersion: 4,
};

const snapshot = (
  over: Partial<{ tableView: OneCardTableView; privateHand: OneCardPrivateView | null }> = {},
) => ({
  roomId: 'r-1',
  phase: 'PLAYING',
  eventSeq: 10,
  tableView: TABLE,
  privateHand: PRIVATE,
  disconnectedSeats: [],
  chips: null,
  ...over,
});

const ev = (type: string, payload: unknown, seq?: number) => ({ type, seq, payload });
const store = () => useOneCardStore.getState();
const NOW = 1_000_000;

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
  store().reset('r-1');
});

afterEach(() => {
  vi.useRealTimers();
});

describe('applyEvent — 반환값 계약 (순번 판정은 훅, D-124)', () => {
  beforeEach(() => store().applySnapshot(snapshot()));

  it('리듀서가 있는 타입은 applied', () => {
    expect(store().applyEvent(ev('PILE_RESHUFFLED', { drawPileCount: 40 }, 11))).toBe('applied');
  });

  it('순번을 판정하지 않는다 — 중복·구멍 판정은 훅 몫', () => {
    expect(store().applyEvent(ev('PILE_RESHUFFLED', { drawPileCount: 40 }, 99))).toBe('applied');
    expect(store().applyEvent(ev('PILE_RESHUFFLED', { drawPileCount: 41 }, 3))).toBe('applied');
    expect(store().drawPileCount).toBe(41);
  });

  it('MATCH_STARTED(서버가 보내지 않는다)·모르는 타입은 unhandled — 훅이 resync 한다', () => {
    expect(store().applyEvent(ev('MATCH_STARTED', {}, 11))).toBe('unhandled');
    expect(store().applyEvent(ev('WHO_KNOWS', {}, 11))).toBe('unhandled');
  });
});

describe('applySnapshot — resync 는 권위값', () => {
  it('공개 상태를 그대로 미러하고 내 손패·좌석·버전을 받는다', () => {
    store().applySnapshot(snapshot());

    const s = store();
    expect(s.phase).toBe('PLAYING');
    expect(s.seats).toHaveLength(3);
    expect(s.topCard).toEqual(card('HEART', 9));
    expect(s.turnSeat).toBe(1);
    expect(s.drawPileCount).toBe(32);
    expect(s.mySeat).toBe(1);
    expect(s.hand).toHaveLength(3);
    expect(s.handVersion).toBe(4);
  });

  it('관전자는 privateHand 가 null — 좌석 없음·빈 손패', () => {
    store().applySnapshot(snapshot({ privateHand: null }));

    expect(store().mySeat).toBe(-1);
    expect(store().hand).toEqual([]);
  });

  it('열린 창은 남은 시간으로 이 클라 기준 마감 시각을 잡는다 — 봇 시각은 오지 않는다', () => {
    store().applySnapshot(
      snapshot({
        tableView: {
          ...TABLE,
          phase: 'RACE',
          turnSeat: -1,
          race: {
            raceId: 7,
            ownerSeat: 0,
            slot: 3,
            jitterX: -20,
            jitterY: 40,
            windowMillis: 3000,
            remainingMillis: 1200,
          },
        },
      }),
    );

    expect(store().race).toEqual({
      raceId: 7,
      ownerSeat: 0,
      slot: 3,
      jitterX: -20,
      jitterY: 40,
      windowMillis: 3000,
      closesAt: NOW + 1200,
    });
  });

  it('더 낮은 버전의 손패는 버린다 — 늦게 도착한 resync 가 새 손패를 되돌리지 않는다', () => {
    store().applySnapshot(snapshot());
    store().applyPrivateHand({ seat: 1, hand: [card('HEART', 3)], handVersion: 6 });

    store().applySnapshot(snapshot({ tableView: { ...TABLE, drawPileCount: 30 } }));

    expect(store().hand).toEqual([card('HEART', 3)]);
    expect(store().handVersion).toBe(6);
    expect(store().drawPileCount).toBe(30);
  });

  it('끝난 판의 결과를 그대로 받는다 — 재접속해도 종료 화면이 남는다', () => {
    const result = {
      reason: 'FINISHED' as const,
      standings: [{ seat: 1, rank: 1, cardsLeft: 0, status: 'FINISHED' as const }],
    };
    store().applySnapshot(snapshot({ tableView: { ...TABLE, phase: 'ENDED', result } }));

    expect(store().result).toEqual(result);
  });

  it('같은 창을 기다리던 누름만 남기고 오류 문구는 지운다', () => {
    const race = {
      raceId: 7,
      ownerSeat: 0,
      slot: 0,
      jitterX: 0,
      jitterY: 0,
      windowMillis: 3000,
      remainingMillis: 2000,
    };
    store().applySnapshot(snapshot({ tableView: { ...TABLE, phase: 'RACE', race } }));
    store().startPress(7, 'CATCH');
    store().setError('무언가');

    store().applySnapshot(snapshot({ tableView: { ...TABLE, phase: 'RACE', race } }));
    expect(store().press?.raceId).toBe(7);
    expect(store().errorMessage).toBeNull();

    store().applySnapshot(snapshot());
    expect(store().press).toBeNull();
  });
});

describe('applyPrivateHand — 손패 전체 + handVersion', () => {
  beforeEach(() => store().applySnapshot(snapshot()));

  it('손패를 통째로 바꾸고 버전을 올린다', () => {
    store().applyPrivateHand({
      seat: 1,
      hand: [card('HEART', 3), card('SPADE', 9), card('CLUB', 7), card('DIAMOND', 1)],
      received: [card('DIAMOND', 1)],
      handVersion: 5,
    });

    expect(store().hand).toHaveLength(4);
    expect(store().handVersion).toBe(5);
  });

  it('낮은 버전은 버리고, 같은 버전은 다시 적용해도 같다', () => {
    store().applyPrivateHand({ seat: 1, hand: [card('HEART', 3)], handVersion: 3 });
    expect(store().hand).toHaveLength(3);

    store().applyPrivateHand({ seat: 1, hand: [card('HEART', 3)], handVersion: 4 });
    store().applyPrivateHand({ seat: 1, hand: [card('HEART', 3)], handVersion: 4 });
    expect(store().hand).toEqual([card('HEART', 3)]);
  });

  it('고른 카드가 새 손패에 남아 있으면 선택을 유지하고, 없으면 푼다', () => {
    store().selectCard(cardKey(card('SPADE', 9)));
    store().applyPrivateHand({
      seat: 1,
      hand: [...PRIVATE.hand, card('DIAMOND', 4)],
      handVersion: 5,
    });
    expect(store().selectedKey).toBe('SPADE-9');

    store().applyPrivateHand({ seat: 1, hand: [card('HEART', 3)], handVersion: 6 });
    expect(store().selectedKey).toBeNull();
  });

  it('매치가 끝난 뒤에도 내 손패는 받는다 — 판이 1라운드라 다음 라운드 손패가 섞일 일이 없다', () => {
    store().applyEvent(ev('MATCH_ENDED', { reason: 'FINISHED', standings: [] }, 11));
    store().applyPrivateHand({ seat: 1, hand: [], handVersion: 9 });

    expect(store().hand).toEqual([]);
  });
});

describe('공개 이벤트 — 결과값이라 두 번 적용해도 같다', () => {
  beforeEach(() => store().applySnapshot(snapshot()));

  it('CARD_PLAYED — 맨 위·지정 무늬·공격·방향·장수를 결과값으로, 차례는 다음 이벤트까지 비운다', () => {
    const played = ev(
      'CARD_PLAYED',
      { seat: 0, card: card('HEART', 7), declaredSuit: 'CLUB', handCount: 6, attackStack: 0, direction: 1 },
      11,
    );
    store().applyEvent(played);
    store().applyEvent(played);

    const s = store();
    expect(s.topCard).toEqual(card('HEART', 7));
    expect(s.declaredSuit).toBe('CLUB');
    expect(s.seats[0].handCount).toBe(6);
    expect(s.turnSeat).toBe(-1);
  });

  it('CARD_PLAYED — 지정 무늬가 없으면 이전 지정을 지운다', () => {
    store().applyEvent(
      ev('CARD_PLAYED', { seat: 0, card: card('HEART', 7), declaredSuit: 'CLUB', handCount: 6, attackStack: 0, direction: 1 }, 11),
    );
    store().applyEvent(
      ev('CARD_PLAYED', { seat: 1, card: card('CLUB', 2), declaredSuit: null, handCount: 2, attackStack: 2, direction: -1 }, 12),
    );

    expect(store().declaredSuit).toBeNull();
    expect(store().attackStack).toBe(2);
    expect(store().direction).toBe(-1);
  });

  it('내가 낸 카드면 선택을 푼다', () => {
    store().selectCard('HEART-3');
    store().applyEvent(
      ev('CARD_PLAYED', { seat: 1, card: card('HEART', 3), declaredSuit: null, handCount: 2, attackStack: 0, direction: 1 }, 11),
    );

    expect(store().selectedKey).toBeNull();
  });

  it('CARDS_DRAWN·PILE_RESHUFFLED — 장수와 뽑을 더미 장수', () => {
    store().applyEvent(
      ev('CARDS_DRAWN', { seat: 2, count: 2, reason: 'ATTACK', handCount: 9, drawPileCount: 30 }, 11),
    );
    expect(store().seats[2].handCount).toBe(9);
    expect(store().drawPileCount).toBe(30);

    store().applyEvent(ev('PILE_RESHUFFLED', { drawPileCount: 41 }, 12));
    expect(store().drawPileCount).toBe(41);
  });

  it('TURN_CHANGED — 차례·방향·공격 누적, 거절 문구를 지운다', () => {
    store().setError('지금 낼 수 없는 카드입니다.');
    store().applyEvent(ev('TURN_CHANGED', { seat: 2, direction: -1, attackStack: 5 }, 11));

    const s = store();
    expect(s.turnSeat).toBe(2);
    expect(s.direction).toBe(-1);
    expect(s.attackStack).toBe(5);
    expect(s.phase).toBe('PLAYING');
    expect(s.errorMessage).toBeNull();
  });

  it('RACE_OPENED — 창을 열고 차례를 비운다', () => {
    store().applyEvent(
      ev('RACE_OPENED', { raceId: 9, ownerSeat: 0, slot: 5, jitterX: 10, jitterY: -10, windowMillis: 3000 }, 11),
    );

    const s = store();
    expect(s.phase).toBe('RACE');
    expect(s.turnSeat).toBe(-1);
    expect(s.race).toMatchObject({ raceId: 9, ownerSeat: 0, slot: 5, closesAt: NOW + 3000 });
  });

  it('RACE_RESOLVED — 지금 창이면 닫고 결과를 남긴다, 다른 창 번호면 열린 창은 그대로', () => {
    store().applyEvent(
      ev('RACE_OPENED', { raceId: 9, ownerSeat: 0, slot: 5, jitterX: 0, jitterY: 0, windowMillis: 3000 }, 11),
    );
    store().startPress(9, 'CATCH');

    store().applyEvent(ev('RACE_RESOLVED', { raceId: 8, outcome: 'EXPIRED', bySeat: -1 }, 12));
    expect(store().race?.raceId).toBe(9);
    expect(store().phase).toBe('RACE');

    store().applyEvent(ev('RACE_RESOLVED', { raceId: 9, outcome: 'CAUGHT', bySeat: 1 }, 13));
    const s = store();
    expect(s.race).toBeNull();
    expect(s.phase).toBe('PLAYING');
    expect(s.press).toBeNull();
    expect(s.lastRace).toEqual({ raceId: 9, ownerSeat: 0, outcome: 'CAUGHT', bySeat: 1 });
  });

  it('다음 카드가 놓이면 지난 경쟁 결과 안내를 지운다', () => {
    store().applyEvent(
      ev('RACE_OPENED', { raceId: 9, ownerSeat: 0, slot: 5, jitterX: 0, jitterY: 0, windowMillis: 3000 }, 11),
    );
    store().applyEvent(ev('RACE_RESOLVED', { raceId: 9, outcome: 'CALLED', bySeat: 0 }, 12));
    store().applyEvent(
      ev('CARD_PLAYED', { seat: 1, card: card('HEART', 4), declaredSuit: null, handCount: 2, attackStack: 0, direction: 1 }, 13),
    );

    expect(store().lastRace).toBeNull();
  });

  it('PLAYER_ELIMINATED — 사유를 남기고 손패는 더미로(장수 0, 더미는 최종값)', () => {
    store().applyEvent(
      ev('PLAYER_ELIMINATED', { seat: 2, reason: 'BANKRUPT', cardsHeld: 21, drawPileCount: 25 }, 11),
    );

    expect(store().seats[2]).toEqual({ seat: 2, handCount: 0, eliminated: 'BANKRUPT' });
    expect(store().drawPileCount).toBe(25);
  });

  it('MATCH_ENDED — 결과를 남기고 창·차례·누름을 닫는다', () => {
    const result = {
      reason: 'LAST_STANDING',
      standings: [
        { seat: 0, rank: 1, cardsLeft: 3, status: 'ALIVE' },
        { seat: 1, rank: 2, cardsLeft: 0, status: 'DESERTED' },
      ],
    };
    store().applyEvent(ev('MATCH_ENDED', result, 11));

    const s = store();
    expect(s.result).toEqual(result);
    expect(s.phase).toBe('ENDED');
    expect(s.turnSeat).toBe(-1);
    expect(s.race).toBeNull();
  });

  it('끝난 뒤의 진행 이벤트는 ignored, 연결 상태 배지는 그대로 반영한다', () => {
    store().applyEvent(ev('MATCH_ENDED', { reason: 'FINISHED', standings: [] }, 11));

    expect(store().applyEvent(ev('TURN_CHANGED', { seat: 0, direction: 1, attackStack: 0 }, 12))).toBe('ignored');
    expect(store().turnSeat).toBe(-1);
    expect(store().applyEvent(ev('PLAYER_DISCONNECTED', { seat: 2 }))).toBe('applied');
    expect(store().disconnectedSeats.has(2)).toBe(true);
  });

  it('PLAYER_DISCONNECTED·PLAYER_RECONNECTED 가 배지를 켜고 끈다', () => {
    store().applyEvent(ev('PLAYER_DISCONNECTED', { seat: 0 }));
    expect(store().disconnectedSeats.has(0)).toBe(true);
    store().applyEvent(ev('PLAYER_RECONNECTED', { seat: 0 }));
    expect(store().disconnectedSeats.has(0)).toBe(false);
  });
});

describe('경쟁 누름 — 거절 처리 (설계서 §4.4)', () => {
  const open = (windowMillis = 3000) =>
    store().applyEvent(
      ev('RACE_OPENED', { raceId: 9, ownerSeat: 0, slot: 0, jitterX: 0, jitterY: 0, windowMillis }, 11),
    );

  beforeEach(() => store().applySnapshot(snapshot()));

  it('기다리는 누름이 없으면 false — 일반 오류로 보여 준다', () => {
    open();
    expect(store().notePressRejected('BUSY')).toBe(false);
    expect(store().notePressRejected('NO_RACE')).toBe(false);
  });

  it('NO_RACE 는 "늦었어요" — 누름을 내린다', () => {
    open();
    store().startPress(9, 'CATCH');

    expect(store().notePressRejected('NO_RACE')).toBe(true);
    expect(store().raceNotice).toBe('LATE');
    expect(store().press).toBeNull();

    store().clearRaceNotice();
    expect(store().raceNotice).toBeNull();
  });

  it('BUSY 는 창이 열려 있는 동안 최대 2회 재시도 신호를 올린다', () => {
    open();
    store().startPress(9, 'CALL_ONE_CARD');

    expect(store().notePressRejected('BUSY')).toBe(true);
    expect(store().notePressRejected('BUSY')).toBe(true);
    expect(store().press?.attempts).toBe(MAX_PRESS_ATTEMPTS);
    expect(store().retryNonce).toBe(2);

    expect(store().notePressRejected('BUSY')).toBe(false);
    expect(store().press).toBeNull();
  });

  it('BUSY 라도 창이 이미 닫힐 시각이 지났으면 재시도하지 않는다', () => {
    open(1000);
    store().startPress(9, 'CATCH');
    vi.setSystemTime(NOW + 1500);

    expect(store().notePressRejected('BUSY')).toBe(false);
  });

  it('창이 새로 열리면 지난 누름과 안내를 지운다', () => {
    open();
    store().startPress(9, 'CATCH');
    store().notePressRejected('NO_RACE');

    store().applyEvent(
      ev('RACE_OPENED', { raceId: 10, ownerSeat: 2, slot: 1, jitterX: 0, jitterY: 0, windowMillis: 3000 }, 12),
    );
    expect(store().press).toBeNull();
    expect(store().raceNotice).toBeNull();
  });
});

describe('선택', () => {
  it('카드를 고르면 7 의 지정 무늬를 다시 고르게 한다', () => {
    store().applySnapshot(snapshot());
    store().selectCard('CLUB-7');
    store().setSuitChoice('HEART');
    expect(store().suitChoice).toBe('HEART');

    store().selectCard('SPADE-9');
    expect(store().selectedKey).toBe('SPADE-9');
    expect(store().suitChoice).toBeNull();
  });
});
