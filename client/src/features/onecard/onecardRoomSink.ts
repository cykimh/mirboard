import type { RoomEventSink } from '@/ws/roomEventSink';
import { errorText } from '@/ws/errorLabels';
import type { StompEnvelope } from '@/types/stomp';
import type { HandPayload, OneCardPrivateView, OneCardTableView } from '@/types/onecard';
import { useOneCardStore } from './onecardStore';

interface ErrorPayload {
  code: string;
  message: string;
}

/**
 * 서버 거절 사유(원카드 `RejectionReason`) → 사용자 문구. 게임 중립 인프라 코드(BUSY·RATE_LIMITED·INTERNAL_ERROR 등)는
 * 공용 표(`ws/errorLabels`)가 맡는다(S5).
 */
const ERROR_LABEL: Record<string, string> = {
  MATCH_OVER: '이미 끝난 판입니다.',
  PLAYER_ELIMINATED: '탈락한 좌석은 더 할 수 없습니다.',
  RACE_IN_PROGRESS: '원카드 경쟁 중에는 내거나 먹을 수 없습니다.',
  NOT_YOUR_TURN: '아직 당신의 차례가 아닙니다.',
  CARD_NOT_OWNED: '손패에 없는 카드입니다.',
  INVALID_SUIT_DECLARATION: '7 을 낼 때는 무늬를 하나 골라야 합니다.',
  CARD_NOT_PLAYABLE: '지금 낼 수 없는 카드입니다.',
  COUNTER_REQUIRED: '공격받는 중에는 반격 카드만 낼 수 있습니다. 반격할 수 없으면 먹으세요.',
  NO_RACE: '이미 끝난 경쟁입니다.',
  NOT_RACE_OWNER: '"원카드!"는 카드가 1장 남은 사람만 누를 수 있습니다.',
  OWNER_CANNOT_CATCH: '자기 자신은 잡을 수 없습니다.',
};

/**
 * 원카드용 {@link RoomEventSink}. 규약대로 모듈 상수이고, 각 메서드는 호출 시점에 `useOneCardStore.getState()` 를
 * 읽는다(D-103).
 *
 * <p>비공개 큐에는 손패 이벤트 두 종류와 `ERROR` 가 온다. 경쟁 누름의 거절은 일반 오류와 다르게 다룬다 — 락 경합
 * `BUSY` 는 창이 열린 동안 다시 보내고, 이미 닫힌 창의 `NO_RACE` 는 오류 대신 "늦었어요"로 보여 준다(설계서 §4.4).
 * 서버는 해소를 승자 처리 락 안에서 먼저 방송하고 진 누름은 그 뒤에 거절하므로, 대개 공개 `RACE_RESOLVED` 가 이 거절보다
 * 먼저 닿는다 — 스토어가 그 순서와 반대 순서(프레임 순서는 보장되지 않는다)를 모두 받는다.
 */
export const onecardRoomSink: RoomEventSink<OneCardTableView, OneCardPrivateView> = {
  reset(roomId) {
    useOneCardStore.getState().reset(roomId);
  },

  applySnapshot(snap) {
    useOneCardStore.getState().applySnapshot(snap);
  },

  applyEvent(envelope) {
    return useOneCardStore.getState().applyEvent(envelope);
  },

  applyPrivateEvent(envelope: StompEnvelope<unknown>) {
    const store = useOneCardStore.getState();
    if (envelope.type === 'HAND_DEALT' || envelope.type === 'HAND_UPDATED') {
      store.applyPrivateHand(envelope.payload as HandPayload);
    } else if (envelope.type === 'ERROR') {
      const p = envelope.payload as ErrorPayload;
      if ((p.code === 'BUSY' || p.code === 'NO_RACE') && store.notePressRejected(p.code)) return;
      store.setError(errorText(p.code, p.message, ERROR_LABEL));
    }
    // 그 외 타입은 조용히 무시한다.
  },

  setError(message) {
    useOneCardStore.getState().setError(message);
  },
};

export { ERROR_LABEL as onecardErrorLabels };
