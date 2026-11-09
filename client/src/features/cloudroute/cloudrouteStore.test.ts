import { beforeEach, describe, expect, it } from 'vitest';
import { LOG_LIMIT, useCloudRouteStore } from './cloudrouteStore';
import { cloudrouteRoomSink } from './cloudrouteRoomSink';
import { seatOf, snapshotOf, tableOf, tilesOf } from './testTables';

const store = () => useCloudRouteStore.getState();
const ev = (type: string, payload: unknown) => store().applyEvent({ type, payload });

beforeEach(() => {
  store().reset('r-1');
  store().applySnapshot(snapshotOf(tableOf()));
});

describe('cloudrouteStore — 결과값 이벤트를 그대로 덮는다 (D-213)', () => {
  it('스냅샷이 판 전체를 덮고 남은 턴 시간으로 시계를 맞춘다', () => {
    store().applySnapshot(snapshotOf(tableOf({ round: 7, festivalTile: 4 }), { turnRemainingMs: 12_000, disconnectedSeats: [1] }));
    expect(store().round).toBe(7);
    expect(store().festivalTile).toBe(4);
    expect(store().turnClock?.remainingMs).toBe(12_000);
    expect(store().disconnectedSeats.has(1)).toBe(true);
  });

  it('주사위·이동·구매가 좌석과 칸을 결과값으로 바꾼다', () => {
    expect(ev('DICE_ROLLED', { seat: 0, die1: 1, die2: 3, doubles: false, doublesThisTurn: 0 })).toBe('applied');
    ev('TOKEN_MOVED', { seat: 0, from: 0, to: 4, kind: 'ROLL', salary: 0, cash: 1500 });
    ev('DECISION_REQUIRED', { seat: 0, pending: { kind: 'PURCHASE', tile: 4, maxLevel: 2 } });
    expect(store().pending).toEqual({ kind: 'PURCHASE', tile: 4, maxLevel: 2 });
    ev('LAND_BOUGHT', { seat: 0, tile: 4, level: 2, paid: 160, cash: 1340 });
    expect(store().seats[0]).toMatchObject({ position: 4, cash: 1340 });
    expect(store().tiles[4]).toEqual({ tile: 4, owner: 0, level: 2 });
    expect(store().lastDice).toEqual([1, 3]);
    expect(store().pending).toBeNull();
  });

  it('같은 이벤트가 두 번 와도 상태가 같다 — 증감이 아니라 결과값이라서', () => {
    const toll = { payer: 0, owner: 1, tile: 7, amount: 140, monopoly: false, festival: false, payerCash: 1360, ownerCash: 1640 };
    ev('TOLL_PAID', toll);
    ev('TOLL_PAID', toll);
    expect(store().seats.map((s) => s.cash)).toEqual([1360, 1640]);
  });

  it('인수·청산·탈락이 주인과 단계를 옮기고 축제를 끈다', () => {
    store().applySnapshot(snapshotOf(tableOf({ tiles: tilesOf({ 7: [1, 2], 1: [0, 1] }), festivalTile: 7 })));
    ev('TAKEN_OVER', { buyer: 0, seller: 1, tile: 7, price: 300, buyerCash: 1200, sellerCash: 1800 });
    ev('FESTIVAL_CLEARED', { tile: 7 });
    expect(store().tiles[7]).toEqual({ tile: 7, owner: 0, level: 2 });
    expect(store().festivalTile).toBe(-1);
    ev('ASSETS_LIQUIDATED', {
      seat: 0,
      sales: [
        { tile: 1, land: false, levelAfter: 0, received: 15 },
        { tile: 1, land: true, levelAfter: 0, received: 30 },
      ],
      cash: 45,
    });
    expect(store().tiles[1]).toEqual({ tile: 1, owner: -1, level: 0 });
    ev('PLAYER_ELIMINATED', { seat: 0, reason: 'BANKRUPT', creditor: 1, returnedTiles: [7], cashForfeited: 0 });
    expect(store().tiles[7].owner).toBe(-1);
    expect(store().seats[0]).toMatchObject({ eliminated: 'BANKRUPT', cash: 0 });
  });

  it('편지·부표·소용돌이를 공개 정보대로 따라간다', () => {
    ev('LETTER_DRAWN', { seat: 1, letter: 'BUOY', deckCount: 9, reshuffled: false });
    expect(store().seats[1].holdsBuoy).toBe(true);
    expect(store().discard).toEqual([]);
    ev('WHIRLPOOL_ENTERED', { seat: 1 });
    ev('ESCAPE_FAILED', { seat: 1, failedEscapes: 1 });
    expect(store().seats[1]).toMatchObject({ trapped: true, failedEscapes: 1 });
    ev('WHIRLPOOL_LEFT', { seat: 1, method: 'BUOY' });
    expect(store().seats[1]).toMatchObject({ trapped: false, holdsBuoy: false });
    expect(store().discard).toEqual(['BUOY']);
    ev('LETTER_DRAWN', { seat: 0, letter: 'GUST', deckCount: 9, reshuffled: true });
    expect(store().discard).toEqual(['GUST']);
    expect(store().lastLetter).toBe('GUST');
  });

  it('결정이 바뀔 때마다 턴 시계를 다시 센다 — 서버가 결정마다 턴 데드라인을 다시 건다', () => {
    const before = store().turnEpoch;
    ev('DECISION_REQUIRED', { seat: 0, pending: { kind: 'ROLL' } });
    expect(store().turnEpoch).toBe(before + 1);
    expect(store().turnClock?.remainingMs).toBeNull();
    store().requestTurnResync(before);
    expect(store().resyncNonce).toBe(0); // 지난 차례는 무시
    store().requestTurnResync(before + 1);
    store().requestTurnResync(before + 1);
    expect(store().resyncNonce).toBe(1); // 같은 차례엔 한 번만
  });

  it('끝난 판에는 진행 이벤트를 반영하지 않고, 모르는 종류는 resync 를 부른다', () => {
    expect(ev('SOMETHING_NEW', {})).toBe('unhandled');
    ev('MATCH_ENDED', { result: { reason: 'ROUND_LIMIT', rounds: 30, standings: [] } });
    expect(store().phase).toBe('ENDED');
    expect(ev('DICE_ROLLED', { seat: 0, die1: 1, die2: 1, doubles: true, doublesThisTurn: 1 })).toBe('ignored');
    expect(ev('PLAYER_DISCONNECTED', { seat: 1 })).toBe('applied');
  });

  it(`사건 줄은 최근 ${LOG_LIMIT}줄만 남긴다`, () => {
    for (let i = 0; i < LOG_LIMIT + 3; i++) {
      ev('DICE_ROLLED', { seat: 0, die1: 1, die2: 2, doubles: false, doublesThisTurn: 0 });
    }
    expect(store().log).toHaveLength(LOG_LIMIT);
  });
});

describe('cloudrouteRoomSink', () => {
  it('ERROR 를 구름항로 문구로 바꾸고 다른 비공개 프레임은 무시한다', () => {
    cloudrouteRoomSink.applyPrivateEvent({ eventId: 'e', type: 'ERROR', ts: 0, payload: { code: 'INSUFFICIENT_FUNDS', message: 'x' } });
    expect(store().errorMessage).toBe('돈이 모자랍니다.');
    cloudrouteRoomSink.applyPrivateEvent({ eventId: 'e', type: 'HAND_DEALT', ts: 0, payload: {} });
    expect(store().errorMessage).toBe('돈이 모자랍니다.');
    cloudrouteRoomSink.reset('r-2');
    expect(store().roomId).toBe('r-2');
    expect(store().seats).toEqual([]);
    expect(seatOf(0).cash).toBe(1500);
  });
});
