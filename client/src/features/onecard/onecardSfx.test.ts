import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { TableCueCall } from '@/features/sound/tableSfx';
import { onecardRoomSink } from './onecardRoomSink';
import { OTHERS_GAIN, attackSfxLevel } from './onecardSfx';
import type {
  OneCardCard,
  OneCardPrivateView,
  OneCardSeatView,
  OneCardSuit,
  OneCardTableView,
} from '@/types/onecard';

/**
 * D-267 — 원카드 일반 동작 효과음. sink 에 이벤트를 넣고 어떤 소리를 청했는지 본다(합성·재생 규칙은 `tableSfx.test`).
 * 내 차례 알림음은 게임판의 `playChime` 이라 여기 없다 — `TURN_CHANGED` 가 조용한지만 본다.
 */
const played: TableCueCall[] = [];
vi.mock('@/features/sound/tableSfx', () => ({
  playCues: (cues: TableCueCall[]) => {
    played.push(...cues);
  },
}));

const card = (suit: OneCardSuit, rank: number): OneCardCard => ({ suit, rank, joker: null });
const JOKER: OneCardCard = { suit: null, rank: 0, joker: 'COLOR' };
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
const PRIVATE: OneCardPrivateView = { seat: 1, hand: [card('HEART', 3)], handVersion: 4 };

const snapshot = (over: Partial<{ tableView: OneCardTableView; privateHand: OneCardPrivateView | null }> = {}) => ({
  roomId: 'r-1',
  phase: 'PLAYING',
  eventSeq: 10,
  tableView: TABLE,
  privateHand: PRIVATE as OneCardPrivateView | null,
  disconnectedSeats: [],
  chips: null,
  ...over,
});

let seq = 10;
const send = (type: string, payload: unknown) => onecardRoomSink.applyEvent({ type, seq: ++seq, payload });
const playedCard = (c: OneCardCard, over: Record<string, unknown> = {}) => ({
  seat: 0,
  card: c,
  declaredSuit: null,
  handCount: 6,
  attackStack: 0,
  direction: 1,
  ...over,
});
const cues = () => played.map((c) => c.cue);

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(1_000_000);
  played.length = 0;
  seq = 10;
  onecardRoomSink.reset('r-1');
  onecardRoomSink.applySnapshot(snapshot());
});

afterEach(() => {
  vi.useRealTimers();
});

describe('원카드 효과음 — 이벤트마다 맞는 소리', () => {
  it('보통 카드는 내기 소리 — 남의 것은 조금 작게, 내 것은 그대로', () => {
    send('CARD_PLAYED', playedCard(card('HEART', 5)));
    expect(played).toEqual([{ cue: 'card.play', gain: OTHERS_GAIN }]);
    played.length = 0;
    send('CARD_PLAYED', playedCard(card('HEART', 6), { seat: 1 }));
    expect(played).toEqual([{ cue: 'card.play', gain: 1 }]);
  });

  it('공격 카드(2·A·조커)는 누적 세기에 맞춘 공격 소리', () => {
    send('CARD_PLAYED', playedCard(card('HEART', 2), { attackStack: 2 }));
    send('CARD_PLAYED', playedCard(card('SPADE', 1), { seat: 1, attackStack: 5 }));
    send('CARD_PLAYED', playedCard(JOKER, { seat: 2, attackStack: 12 }));
    expect(played.map((c) => [c.cue, c.level])).toEqual([
      ['card.attack', 1],
      ['card.attack', 2],
      ['card.attack', 3],
    ]);
    expect([attackSfxLevel(3), attackSfxLevel(4), attackSfxLevel(7), attackSfxLevel(8)]).toEqual([1, 2, 2, 3]);
  });

  it('7 은 무늬 바꾸기, Q 는 방향 바뀜, J 는 건너뛰기, K 는 한 번 더', () => {
    send('CARD_PLAYED', playedCard(card('HEART', 7), { declaredSuit: 'SPADE' }));
    send('CARD_PLAYED', playedCard(card('SPADE', 12), { direction: -1 }));
    send('CARD_PLAYED', playedCard(card('SPADE', 11)));
    send('CARD_PLAYED', playedCard(card('SPADE', 13)));
    expect(cues()).toEqual(['card.suit', 'card.reverse', 'card.skip', 'card.again']);
  });

  it('둘만 남으면 J 는 한 번 더, Q 는 보통 내기 (D-173)', () => {
    onecardRoomSink.applySnapshot(
      snapshot({ tableView: { ...TABLE, seats: [seat(0), seat(1), seat(2, { eliminated: 'BANKRUPT' })] } }),
    );
    send('CARD_PLAYED', playedCard(card('HEART', 11)));
    send('CARD_PLAYED', playedCard(card('HEART', 12)));
    expect(cues()).toEqual(['card.again', 'card.play']);
  });

  it('먹기는 장수를 싣고, 더미 섞기·탈락도 소리가 난다', () => {
    send('CARDS_DRAWN', { seat: 2, count: 5, reason: 'ATTACK', handCount: 12, drawPileCount: 20 });
    send('PILE_RESHUFFLED', { drawPileCount: 30 });
    send('PLAYER_ELIMINATED', { seat: 2, reason: 'BANKRUPT', cardsHeld: 20, drawPileCount: 50 });
    expect(played).toEqual([
      { cue: 'card.draw', count: 5, gain: OTHERS_GAIN },
      { cue: 'deck.shuffle' },
      { cue: 'seat.out' },
    ]);
  });

  it('경쟁 창 — 열림·원카드!·잡힘은 짧은 신호, 만료·취소는 조용하다', () => {
    const open = (raceId: number) =>
      send('RACE_OPENED', { raceId, ownerSeat: 0, slot: 1, jitterX: 0, jitterY: 0, windowMillis: 3000 });
    open(1);
    send('RACE_RESOLVED', { raceId: 1, outcome: 'CALLED', bySeat: 0 });
    open(2);
    send('RACE_RESOLVED', { raceId: 2, outcome: 'CAUGHT', bySeat: 1 });
    open(3);
    send('RACE_RESOLVED', { raceId: 3, outcome: 'EXPIRED', bySeat: -1 });
    open(4);
    send('RACE_RESOLVED', { raceId: 4, outcome: 'CANCELLED', bySeat: -1 });
    expect(cues()).toEqual([
      'race.open',
      'race.called',
      'race.open',
      'race.caught',
      'race.open',
      'race.open',
    ]);
  });

  it('판 종료 — 내가 1등이면 승리음, 아니면 패배음', () => {
    const standings = [
      { seat: 1, rank: 1, cardsLeft: 0, status: 'FINISHED' },
      { seat: 0, rank: 2, cardsLeft: 3, status: 'ALIVE' },
    ];
    send('MATCH_ENDED', { reason: 'FINISHED', standings });
    expect(played).toEqual([{ cue: 'match.win' }]);

    played.length = 0;
    onecardRoomSink.reset('r-1');
    onecardRoomSink.applySnapshot(snapshot({ privateHand: { ...PRIVATE, seat: 0 } }));
    send('MATCH_ENDED', { reason: 'FINISHED', standings });
    expect(played).toEqual([{ cue: 'match.lose' }]);
  });
});

describe('원카드 효과음 — 실제 이벤트에서만', () => {
  it('resync 스냅샷·첫 상태는 소리를 내지 않는다(진행 중·경쟁 창·끝난 판이어도)', () => {
    onecardRoomSink.applySnapshot(
      snapshot({
        tableView: {
          ...TABLE,
          topCard: card('SPADE', 2),
          attackStack: 4,
          phase: 'RACE',
          race: { raceId: 3, ownerSeat: 0, slot: 1, jitterX: 0, jitterY: 0, windowMillis: 3000, remainingMillis: 2000 },
        },
      }),
    );
    onecardRoomSink.applySnapshot(
      snapshot({ tableView: { ...TABLE, phase: 'ENDED', result: { reason: 'FINISHED', standings: [] } } }),
    );
    expect(played).toEqual([]);
  });

  it('같은 이벤트가 다시 와도 한 번만 울린다', () => {
    const play = { type: 'CARD_PLAYED', seq: 11, payload: playedCard(card('HEART', 5)) };
    onecardRoomSink.applyEvent(play);
    onecardRoomSink.applyEvent(play);
    const draw = { type: 'CARDS_DRAWN', seq: 12, payload: { seat: 0, count: 1, reason: 'TURN', handCount: 7, drawPileCount: 31 } };
    onecardRoomSink.applyEvent(draw);
    onecardRoomSink.applyEvent(draw);
    expect(cues()).toEqual(['card.play', 'card.draw']);
  });

  it('순번 없는 이벤트·연결 표시·차례 바뀜은 조용하다', () => {
    onecardRoomSink.applyEvent({ type: 'CARD_PLAYED', payload: playedCard(card('HEART', 5)) });
    onecardRoomSink.applyEvent({ type: 'PLAYER_DISCONNECTED', payload: { seat: 2 } });
    send('TURN_CHANGED', { seat: 1, direction: 1, attackStack: 0 });
    expect(played).toEqual([]);
  });

  it('끝난 판에 늦게 온 이벤트는 조용하다', () => {
    send('MATCH_ENDED', { reason: 'FINISHED', standings: [] });
    played.length = 0;
    send('CARD_PLAYED', playedCard(card('HEART', 5)));
    send('MATCH_ENDED', { reason: 'FINISHED', standings: [] });
    expect(played).toEqual([]);
  });
});

describe('원카드 효과음 — 관전자', () => {
  beforeEach(() => {
    onecardRoomSink.reset('r-1');
    onecardRoomSink.applySnapshot(snapshot({ privateHand: null }));
  });

  it('관전자도 판의 소리를 듣는다(모두 남의 동작 크기)', () => {
    send('CARD_PLAYED', playedCard(card('HEART', 5), { seat: 1 }));
    send('CARDS_DRAWN', { seat: 0, count: 1, reason: 'TURN', handCount: 8, drawPileCount: 31 });
    expect(played).toEqual([
      { cue: 'card.play', gain: OTHERS_GAIN },
      { cue: 'card.draw', count: 1, gain: OTHERS_GAIN },
    ]);
  });

  it('관전자는 판이 끝나면 작은 승리음 — 패배음은 없다', () => {
    send('MATCH_ENDED', { reason: 'FINISHED', standings: [{ seat: 1, rank: 1, cardsLeft: 0, status: 'FINISHED' }] });
    expect(played).toEqual([{ cue: 'match.win', gain: OTHERS_GAIN }]);
  });
});
