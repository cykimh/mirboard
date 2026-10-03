import { beforeEach, describe, expect, it } from 'vitest';
import { skullkingRoomSink } from './skullkingRoomSink';
import { useSkullKingStore } from './skullkingStore';

/**
 * 스컬킹 비공개 큐 ERROR → 사용자 문구. 서버 코드에 라벨이 없으면 `CODE: message` 원문이
 * 그대로 뜬다.
 */

const errorEnvelope = (code: string, message: string) => ({
  eventId: 'e',
  type: 'ERROR',
  ts: 0,
  payload: { code, message },
});

beforeEach(() => {
  useSkullKingStore.getState().reset('r-1');
});

describe('skullkingRoomSink — ERROR 문구', () => {
  it('라벨이 있는 코드는 한국어 문구로 보여 준다', () => {
    skullkingRoomSink.applyPrivateEvent(errorEnvelope('NOT_YOUR_TURN', 'Not your turn'));

    expect(useSkullKingStore.getState().errorMessage).toBe('아직 당신의 차례가 아닙니다.');
  });

  // D-122 — 강제 종료·탈주 조기 종료 뒤(FINISHED) 늦게 낸 액션은 서버가 이 코드로 거절한다.
  it('끝난 게임의 액션 거절(GAME_NOT_IN_PROGRESS)도 원문 대신 문구로 보여 준다', () => {
    skullkingRoomSink.applyPrivateEvent(
      errorEnvelope('GAME_NOT_IN_PROGRESS', 'Game is not in progress'),
    );

    const shown = useSkullKingStore.getState().errorMessage;
    expect(shown).not.toContain('GAME_NOT_IN_PROGRESS');
    expect(shown).toBe('이미 끝난 게임입니다.');
  });

  it('라벨이 없는 코드는 코드와 원문을 그대로 보여 준다', () => {
    skullkingRoomSink.applyPrivateEvent(errorEnvelope('SOMETHING_NEW', 'detail'));

    expect(useSkullKingStore.getState().errorMessage).toBe('SOMETHING_NEW: detail');
  });
});
