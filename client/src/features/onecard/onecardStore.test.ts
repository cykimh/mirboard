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

  /**
   * S5 — 기다리던 누름은 같은 창이라도 비운다. 끊긴 사이에 보낸 누름은 버려졌을 수 있는데(소켓이 이미 죽어 있었다) 남겨 두면
   * 그 창 동안 다시 누를 수 없어 주인이면 봇에게 잡혔다. 다시 누른 것이 늦으면 서버가 NO_RACE 로 거절할 뿐이다.
   */
  it('기다리던 누름은 같은 창이어도 비우고 오류 문구도 지운다', () => {
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
    expect(store().race?.raceId).toBe(7);
    expect(store().press).toBeNull();
    expect(store().errorMessage).toBeNull();
  });

  it('방금 닫힌 경쟁 안내는 서버 뷰에 없는 값이라 비운다 — 오래 떠난 뒤 돌아와도 낡은 줄이 남지 않는다', () => {
    store().applySnapshot(snapshot());
    store().applyEvent(
      ev('RACE_OPENED', { raceId: 9, ownerSeat: 0, slot: 5, jitterX: 0, jitterY: 0, windowMillis: 3000 }, 11),
    );
    store().applyEvent(ev('RACE_RESOLVED', { raceId: 9, outcome: 'CAUGHT', bySeat: 2 }, 12));
    expect(store().lastRace).not.toBeNull();

    store().applySnapshot(snapshot());

    expect(store().lastRace).toBeNull();
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
  // 서버가 방송하는 순서(GameStompController 의 락 안 broadcast → 락 해제 → 늦은 요청 처리): 해소는 승자를 처리한 락 안에서
  // 먼저 나가고(RACE_RESOLVED → TURN_CHANGED), 진 누름의 거절(NO_RACE)이나 락 경합 BUSY 는 그 뒤에 온다.
  const resolve = (outcome: string, bySeat: number) =>
    store().applyEvent(ev('RACE_RESOLVED', { raceId: 9, outcome, bySeat }, 12));
  const turnChanged = () =>
    store().applyEvent(ev('TURN_CHANGED', { seat: 1, direction: 1, attackStack: 0 }, 13));

  beforeEach(() => store().applySnapshot(snapshot()));

  it('창이 없을 때 기다리는 누름 없는 BUSY 는 false — 카드 내기·먹기의 락 경합이라 일반 오류로 보여 준다', () => {
    expect(store().notePressRejected('BUSY')).toBe(false);
  });

  /**
   * S5 — 누름 → (재접속·탭 복귀·구멍) resync 스냅샷이 같은 창으로 와서 표식을 비움 → 그 누름의 BUSY 가 늦게 도착. 창이 열린
   * 동안 내기·먹기는 막혀 있어 이 BUSY 는 누름의 것이다 — 일반 오류(빨간 줄)로 새지 않게 처리됐다고 답하고, 재시도·"늦었어요"도
   * 없다(버튼은 이미 다시 누를 수 있다).
   */
  it('스냅샷이 누름을 비운 뒤 온 BUSY 는 창이 열려 있으면 오류 없이 삼킨다', () => {
    const race = { raceId: 7, ownerSeat: 0, slot: 0, jitterX: 0, jitterY: 0, windowMillis: 3000, remainingMillis: 2000 };
    const racing = snapshot({ tableView: { ...TABLE, phase: 'RACE', turnSeat: -1, race } });
    store().applySnapshot(racing);
    store().startPress(7, 'CATCH');
    store().applySnapshot(racing); // 같은 창 — 누름 표식이 비었다

    expect(store().notePressRejected('BUSY')).toBe(true);
    expect(store().errorMessage).toBeNull();
    expect(store().raceNotice).toBeNull();
    expect(store().retryNonce).toBe(0);
  });

  it('내가 이긴 경쟁 뒤의 BUSY 도 일반 오류다 — 이긴 누름은 남지 않는다', () => {
    open();
    store().startPress(9, 'CATCH');
    resolve('CAUGHT', 1);

    expect(store().press).toBeNull();
    expect(store().raceNotice).toBeNull();
    expect(store().notePressRejected('BUSY')).toBe(false);
  });

  it('NO_RACE 는 기다리는 누름이 없어도 "늦었어요" — NO_RACE 는 경쟁 누름에서만 나온다', () => {
    open();

    expect(store().notePressRejected('NO_RACE')).toBe(true);
    expect(store().raceNotice).toBe('LATE');
  });

  it('NO_RACE 는 "늦었어요" — 해소 이벤트보다 먼저 와도 누름을 내린다', () => {
    open();
    store().startPress(9, 'CATCH');

    expect(store().notePressRejected('NO_RACE')).toBe(true);
    expect(store().raceNotice).toBe('LATE');
    expect(store().press).toBeNull();

    store().clearRaceNotice();
    expect(store().raceNotice).toBeNull();
  });

  it('남이 이기면 해소 이벤트에서 바로 "늦었어요" — 뒤따르는 NO_RACE 는 오류가 아니다', () => {
    open();
    store().startPress(9, 'CATCH');

    resolve('CALLED', 0); // 주인이 먼저 눌렀다
    expect(store().raceNotice).toBe('LATE'); // 진 사실은 거절을 기다리지 않고 알린다
    // 누름은 남긴다 — 이 누름의 거절이 아직 오는 중이다.
    expect(store().press).toEqual({ raceId: 9, action: 'CATCH', attempts: 1, lost: true });

    turnChanged();
    expect(store().notePressRejected('NO_RACE')).toBe(true);
    expect(store().press).toBeNull();
    expect(store().raceNotice).toBe('LATE');
  });

  it('남이 이긴 뒤에 온 BUSY 는 다시 보내지 않고 "늦었어요"로 끝낸다', () => {
    open();
    store().startPress(9, 'CATCH');
    resolve('CAUGHT', 2);
    turnChanged();

    expect(store().notePressRejected('BUSY')).toBe(true);
    expect(store().retryNonce).toBe(0);
    expect(store().press).toBeNull();
    expect(store().raceNotice).toBe('LATE');
  });

  it('아무도 못 눌러 만료된 창에 닿은 누름도 "늦었어요"', () => {
    open();
    store().startPress(9, 'CATCH');

    resolve('EXPIRED', -1);
    expect(store().raceNotice).toBe('LATE');

    expect(store().notePressRejected('NO_RACE')).toBe(true);
    expect(store().press).toBeNull();
    expect(store().raceNotice).toBe('LATE');
  });

  it('내가 이기면 안내가 없다', () => {
    open();
    store().startPress(9, 'CATCH');

    resolve('CAUGHT', 1);

    expect(store().press).toBeNull();
    expect(store().raceNotice).toBeNull();
  });

  it('다른 창의 누름은 해소 이벤트가 건드리지 않는다', () => {
    open();
    store().startPress(9, 'CATCH');

    store().applyEvent(ev('RACE_RESOLVED', { raceId: 8, outcome: 'CALLED', bySeat: 0 }, 12));

    expect(store().press).toEqual({ raceId: 9, action: 'CATCH', attempts: 1 });
    expect(store().raceNotice).toBeNull();
  });

  it('BUSY 는 창이 열려 있는 동안 최대 2회 재시도 신호를 올리고, 소진되면 "늦었어요"', () => {
    open();
    store().startPress(9, 'CALL_ONE_CARD');

    expect(store().notePressRejected('BUSY')).toBe(true);
    expect(store().notePressRejected('BUSY')).toBe(true);
    expect(store().press?.attempts).toBe(MAX_PRESS_ATTEMPTS);
    expect(store().retryNonce).toBe(2);
    expect(store().raceNotice).toBeNull();

    // 다시 시도할 수 없다 — "잠시 후 다시 시도하세요" 는 틀린 안내다.
    expect(store().notePressRejected('BUSY')).toBe(true);
    expect(store().press).toBeNull();
    expect(store().raceNotice).toBe('LATE');
    expect(store().retryNonce).toBe(2);
  });

  it('BUSY 라도 창이 이미 닫힐 시각이 지났으면 재시도하지 않고 "늦었어요"', () => {
    open(1000);
    store().startPress(9, 'CATCH');
    vi.setSystemTime(NOW + 1500);

    expect(store().notePressRejected('BUSY')).toBe(true);
    expect(store().retryNonce).toBe(0);
    expect(store().raceNotice).toBe('LATE');
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

  it('다음 카드가 놓이면 남은 진 누름을 정리한다 — 내 카드 내기의 BUSY 가 "늦었어요"가 되지 않게', () => {
    open();
    store().startPress(9, 'CATCH');
    store().notePressRejected('BUSY'); // 락 경합 — 재시도 신호가 올라간 상태에서
    resolve('CALLED', 0); // 남이 이겨 누름이 lost 로 남는다(재시도는 가드에 걸려 나가지 않는다)
    expect(store().press?.lost).toBe(true);

    store().applyEvent(
      ev('CARD_PLAYED', { seat: 2, card: card('HEART', 4), declaredSuit: null, handCount: 2, attackStack: 0, direction: 1 }, 14),
    );

    expect(store().press).toBeNull();
    expect(store().notePressRejected('BUSY')).toBe(false);
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

describe('낡은 창 복구 — 다시 받기 신호 (S5)', () => {
  // 서버가 실제로 내는 순서로 넣는다 — 창 9(주인 좌석 0, seq 11) → 해소(12) → 차례(13, 좌석 1) → 좌석 1 이 1장이 되는
  // 카드(14) → 창 10(주인 좌석 1, seq 15). 창 사이에는 늘 해소·차례·카드가 있다.
  const open = (raceId: number, seq: number, ownerSeat = 0) =>
    store().applyEvent(
      ev('RACE_OPENED', { raceId, ownerSeat, slot: 0, jitterX: 0, jitterY: 0, windowMillis: 3000 }, seq),
    );
  const closeAndPlayOn = (outcome: string, bySeat: number) => {
    store().applyEvent(ev('RACE_RESOLVED', { raceId: 9, outcome, bySeat }, 12));
    store().applyEvent(ev('TURN_CHANGED', { seat: 1, direction: 1, attackStack: 0 }, 13));
    store().applyEvent(
      ev('CARD_PLAYED', { seat: 1, card: card('HEART', 4), declaredSuit: null, handCount: 1, attackStack: 0, direction: 1 }, 14),
    );
  };

  beforeEach(() => store().applySnapshot(snapshot()));

  it('창마다 한 번만 다시 받기를 청한다', () => {
    open(9, 11);

    store().requestRaceResync(9);
    store().requestRaceResync(9);
    expect(store().resyncNonce).toBe(1);

    closeAndPlayOn('EXPIRED', -1);
    open(10, 15, 1);
    store().requestRaceResync(10);
    expect(store().resyncNonce).toBe(2);
  });

  /**
   * 서버가 창을 닫아 저장했는데 방송이 실패하면(C-I2) 해소 이벤트가 끝내 안 온다 — 내 누름은 NO_RACE 로 거절되는데 창은
   * 계속 열려 보인다. 그 창을 기다리던 누름의 NO_RACE 면 권위 스냅샷을 다시 받는다.
   */
  it('창이 열린 채 그 창을 기다리던 누름이 NO_RACE 를 받으면 다시 받기를 청한다', () => {
    open(9, 11);
    store().startPress(9, 'CATCH');

    expect(store().notePressRejected('NO_RACE')).toBe(true);

    expect(store().raceNotice).toBe('LATE');
    expect(store().resyncNonce).toBe(1);
  });

  it('해소 이벤트가 먼저 와 창이 닫혔으면 NO_RACE 가 와도 다시 받지 않는다 — 서버가 보내는 보통 순서', () => {
    open(9, 11);
    store().startPress(9, 'CATCH');
    store().applyEvent(ev('RACE_RESOLVED', { raceId: 9, outcome: 'CALLED', bySeat: 0 }, 12));
    store().applyEvent(ev('TURN_CHANGED', { seat: 1, direction: 1, attackStack: 0 }, 13));

    store().notePressRejected('NO_RACE');

    expect(store().resyncNonce).toBe(0);
  });

  it('지난 창의 누름에 늦게 온 NO_RACE 는 새 창을 의심하지 않는다', () => {
    open(9, 11);
    store().startPress(9, 'CATCH');
    closeAndPlayOn('CALLED', 0);
    open(10, 15, 1); // 새 창이 열려 누름 표식은 비었다

    store().notePressRejected('NO_RACE');

    expect(store().resyncNonce).toBe(0);
  });
});
