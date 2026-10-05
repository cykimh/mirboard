import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { onecardRoomSink } from './onecardRoomSink';
import { useOneCardStore } from './onecardStore';

/**
 * 원카드 비공개 큐 → 스토어. 손패 이벤트 두 종류는 같은 경로(`handVersion` 가드)로, `ERROR` 는 문구로, 경쟁
 * 누름의 거절은 재시도·"늦었어요"로 간다.
 */

const envelope = (type: string, payload: unknown) => ({ eventId: 'e', type, ts: 0, payload });
const errorEnvelope = (code: string, message = 'detail') => envelope('ERROR', { code, message });
const store = () => useOneCardStore.getState();

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(1_000_000);
  store().reset('r-1');
});

afterEach(() => {
  vi.useRealTimers();
});

describe('onecardRoomSink — 손패', () => {
  it('HAND_DEALT 와 HAND_UPDATED 가 같은 버전 가드로 손패를 바꾼다', () => {
    const heart = { suit: 'HEART', rank: 5, joker: null };
    onecardRoomSink.applyPrivateEvent(envelope('HAND_DEALT', { seat: 2, hand: [heart], handVersion: 1 }));
    expect(store().mySeat).toBe(2);
    expect(store().hand).toHaveLength(1);

    onecardRoomSink.applyPrivateEvent(
      envelope('HAND_UPDATED', { seat: 2, hand: [], received: [], handVersion: 3 }),
    );
    onecardRoomSink.applyPrivateEvent(
      envelope('HAND_UPDATED', { seat: 2, hand: [heart, heart], received: [heart], handVersion: 2 }),
    );
    expect(store().hand).toEqual([]);
    expect(store().handVersion).toBe(3);
  });
});

describe('onecardRoomSink — ERROR 문구', () => {
  it('라벨이 있는 코드는 한국어 문구로 보여 준다', () => {
    onecardRoomSink.applyPrivateEvent(errorEnvelope('COUNTER_REQUIRED'));

    expect(store().errorMessage).toBe(
      '공격받는 중에는 반격 카드만 낼 수 있습니다. 반격할 수 없으면 먹으세요.',
    );
  });

  it('라벨이 없는 코드는 코드와 원문을 그대로 보여 준다', () => {
    onecardRoomSink.applyPrivateEvent(errorEnvelope('SOMETHING_NEW', 'detail'));

    expect(store().errorMessage).toBe('SOMETHING_NEW: detail');
  });

  it('기다리는 누름이 없으면 BUSY 도 일반 오류다 — 카드를 낼 때의 락 경합', () => {
    onecardRoomSink.applyPrivateEvent(errorEnvelope('BUSY'));

    expect(store().errorMessage).toBe('다른 처리가 진행 중입니다. 잠시 후 다시 시도하세요.');
  });
});

describe('onecardRoomSink — 경쟁 누름의 거절', () => {
  beforeEach(() => {
    store().applyEvent({
      type: 'RACE_OPENED',
      payload: { raceId: 4, ownerSeat: 0, slot: 0, jitterX: 0, jitterY: 0, windowMillis: 3000 },
    });
    store().startPress(4, 'CATCH');
  });

  it('BUSY 는 오류를 띄우지 않고 재시도 신호를 올린다', () => {
    onecardRoomSink.applyPrivateEvent(errorEnvelope('BUSY'));

    expect(store().errorMessage).toBeNull();
    expect(store().retryNonce).toBe(1);
  });

  it('NO_RACE 는 오류 대신 "늦었어요"', () => {
    onecardRoomSink.applyPrivateEvent(errorEnvelope('NO_RACE'));

    expect(store().errorMessage).toBeNull();
    expect(store().raceNotice).toBe('LATE');
  });
});
