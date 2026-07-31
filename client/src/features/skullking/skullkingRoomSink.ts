import type { RoomEventSink } from '@/ws/roomEventSink';
import type { StompEnvelope } from '@/types/stomp';
import type {
  HandDealtPayload,
  SkullKingPrivateView,
  SkullKingTableView,
} from '@/types/skullking';
import { useSkullKingStore } from './skullkingStore';

interface ErrorPayload {
  code: string;
  message: string;
}

/**
 * 서버 거절 사유(스컬킹 `RejectionReason`) → 사용자 문구. 훅이 아니라 여기 있는 이유는
 * 게임마다 코드가 다르기 때문이다 (D-103 — 비공개 큐를 ERROR 까지 sink 로 위임한 목적).
 */
const ERROR_LABEL: Record<string, string> = {
  NOT_IN_BIDDING_PHASE: '지금은 예측 단계가 아닙니다.',
  NOT_IN_PLAYING_PHASE: '지금은 카드를 낼 단계가 아닙니다.',
  ALREADY_BID: '이미 예측을 제출했습니다. 변경할 수 없습니다.',
  BID_OUT_OF_RANGE: '예측은 0부터 손패 장수까지만 가능합니다.',
  NOT_YOUR_TURN: '아직 당신의 차례가 아닙니다.',
  CARD_NOT_OWNED: '손패에 없는 카드입니다.',
  INVALID_TIGRESS_DECLARATION: '티그리스는 해적/탈출 중 하나를 선언해야 합니다.',
  MUST_FOLLOW_LEAD_SUIT: '리드 수트를 가지고 있으면 그 색을 따라야 합니다.',
  INVALID_STATE_FOR_ACTION: '지금 처리할 수 없는 요청입니다.',
  SEAT_DESERTED: '탈주 처리된 좌석입니다. 관전으로만 참여할 수 있습니다.',
  // 인프라 공통 코드도 여기서 받는다.
  BUSY: '다른 처리가 진행 중입니다. 잠시 후 다시 시도하세요.',
  GAME_NOT_STARTED: '아직 게임이 시작되지 않았습니다.',
};

/**
 * 스컬킹용 {@link RoomEventSink} (D-103). 규약대로 모듈 상수이고, 각 메서드는 호출 시점에
 * `useSkullKingStore.getState()` 를 읽는다.
 */
export const skullkingRoomSink: RoomEventSink<
  SkullKingTableView,
  SkullKingPrivateView
> = {
  reset(roomId) {
    useSkullKingStore.getState().reset(roomId);
  },

  applySnapshot(snap) {
    useSkullKingStore.getState().applySnapshot(snap);
  },

  applyEvent(envelope) {
    return useSkullKingStore.getState().applyEvent(envelope);
  },

  applyPrivateEvent(envelope: StompEnvelope<unknown>) {
    const store = useSkullKingStore.getState();
    if (envelope.type === 'HAND_DEALT') {
      store.applyPrivateHand(envelope.payload as HandDealtPayload);
    } else if (envelope.type === 'ERROR') {
      const p = envelope.payload as ErrorPayload;
      store.setError(ERROR_LABEL[p.code] ?? `${p.code}: ${p.message}`);
    }
    // 그 외 타입은 조용히 무시 (스컬킹 본인 큐에는 HAND_DEALT·ERROR 만 온다).
  },

  setError(message) {
    useSkullKingStore.getState().setError(message);
  },
};

export { ERROR_LABEL as skullkingErrorLabels };
