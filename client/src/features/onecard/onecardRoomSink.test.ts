import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { onecardRoomSink } from './onecardRoomSink';
import { useOneCardStore } from './onecardStore';
import { INFRA_ERROR_LABELS } from '@/ws/errorLabels';

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

  /** S5 — 인프라 거절(게임 중립)은 공용 문구로. 예전에는 `INTERNAL_ERROR: Failed to apply action` 처럼 영문 원문이 보였다. */
  it.each(['INVALID_ACTION', 'INTERNAL_ERROR', 'NOT_IN_ROOM', 'ROOM_NOT_FOUND', 'GAME_NOT_AVAILABLE', 'RATE_LIMITED'])(
    '인프라 거절 %s 도 한국어 문구로 보여 준다',
    (code) => {
      onecardRoomSink.applyPrivateEvent(errorEnvelope(code, 'English detail'));

      expect(store().errorMessage).toBe(INFRA_ERROR_LABELS[code]);
      expect(store().errorMessage).not.toContain('English detail');
    },
  );

  it('기다리는 누름이 없으면 BUSY 도 일반 오류다 — 카드를 낼 때의 락 경합', () => {
    onecardRoomSink.applyPrivateEvent(errorEnvelope('BUSY'));

    expect(store().errorMessage).toBe('다른 처리가 진행 중입니다. 잠시 후 다시 시도하세요.');
  });
});

describe('onecardRoomSink — 경쟁 누름의 거절', () => {
  const resolved = (outcome: string, bySeat: number) => ({
    type: 'RACE_RESOLVED',
    payload: { raceId: 4, outcome, bySeat },
  });
  const turnChanged = { type: 'TURN_CHANGED', payload: { seat: 1, direction: 1, attackStack: 0 } };

  beforeEach(() => {
    // 나는 1번 좌석, 창의 주인은 0번 — 나는 "잡기!" 쪽이다.
    onecardRoomSink.applyPrivateEvent(envelope('HAND_DEALT', { seat: 1, hand: [], handVersion: 1 }));
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

  it('NO_RACE 는 오류 대신 "늦었어요" — 해소 이벤트보다 먼저 닿은 프레임도 같다', () => {
    onecardRoomSink.applyPrivateEvent(errorEnvelope('NO_RACE'));

    expect(store().errorMessage).toBeNull();
    expect(store().raceNotice).toBe('LATE');
  });

  // 서버는 해소를 승자 처리 락 안에서 먼저 방송하고, 진 누름의 거절은 그 뒤에 보낸다 — 아래는 그 실제 순서다.
  it('남이 이긴 뒤 NO_RACE 가 와도 오류 배너 없이 "늦었어요"', () => {
    onecardRoomSink.applyEvent(resolved('CALLED', 0));
    onecardRoomSink.applyEvent(turnChanged);
    onecardRoomSink.applyPrivateEvent(errorEnvelope('NO_RACE'));

    expect(store().errorMessage).toBeNull();
    expect(store().raceNotice).toBe('LATE');
    expect(store().press).toBeNull();
  });

  it('남이 이긴 뒤 BUSY 가 와도 오류 배너도 재시도도 없이 "늦었어요"', () => {
    onecardRoomSink.applyEvent(resolved('CAUGHT', 2));
    onecardRoomSink.applyEvent(turnChanged);
    onecardRoomSink.applyPrivateEvent(errorEnvelope('BUSY'));

    expect(store().errorMessage).toBeNull();
    expect(store().retryNonce).toBe(0);
    expect(store().raceNotice).toBe('LATE');
    expect(store().press).toBeNull();
  });

  it('아무도 못 눌러 닫힌 창에 닿은 NO_RACE 도 "늦었어요"', () => {
    onecardRoomSink.applyEvent(resolved('EXPIRED', -1));
    onecardRoomSink.applyPrivateEvent(errorEnvelope('NO_RACE'));

    expect(store().errorMessage).toBeNull();
    expect(store().raceNotice).toBe('LATE');
  });

  it('BUSY 재시도가 소진되면 일반 오류가 아니라 "늦었어요"', () => {
    onecardRoomSink.applyPrivateEvent(errorEnvelope('BUSY'));
    onecardRoomSink.applyPrivateEvent(errorEnvelope('BUSY'));
    onecardRoomSink.applyPrivateEvent(errorEnvelope('BUSY'));

    expect(store().errorMessage).toBeNull();
    expect(store().retryNonce).toBe(2);
    expect(store().raceNotice).toBe('LATE');
  });

  it('내가 이기면 안내도 오류도 없다', () => {
    onecardRoomSink.applyEvent(resolved('CAUGHT', 1));

    expect(store().errorMessage).toBeNull();
    expect(store().raceNotice).toBeNull();
    expect(store().press).toBeNull();
  });
});
