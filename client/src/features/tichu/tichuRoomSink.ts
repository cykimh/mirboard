import type { RoomEventSink } from '@/ws/roomEventSink';
import type { StompEnvelope } from '@/types/stomp';
import type { Card, PrivateHand, TableView } from '@/types/tichu';
import { useTichuStore } from './tichuStore';

interface HandDealtPayload {
  seat: number;
  cards: Card[];
}

interface CardsReceivedPayload {
  seat: number;
  received: { card: Card; fromSeat: number }[];
}

interface ErrorPayload {
  code: string;
  message: string;
}

/**
 * 티츄용 {@link RoomEventSink} (D-103). 과거 `useStompRoom` 안에 인라인으로 있던 티츄
 * 분기를 그대로 옮긴 것이며 **동작은 비트 단위로 동일**하다.
 *
 * <p>규약대로 모듈 상수이고, 각 메서드는 호출 시점에 `useTichuStore.getState()` 를 읽는다 —
 * 훅이 sink 를 ref 에 담아 deps 에서 빼기 때문에 모듈 로드 시점 캡처는 stale 이 된다.
 */
export const tichuRoomSink: RoomEventSink<TableView, PrivateHand> = {
  reset(roomId) {
    useTichuStore.getState().reset(roomId);
  },

  applySnapshot(snap) {
    useTichuStore.getState().applySnapshot({
      // 티츄는 항상 손패가 있지만 관전자 resync 는 null 이다 — 스토어 계약(PrivateHand)에
      // 맞추기 위해 그때만 빈 손패로 정규화한다(과거 훅이 그대로 넘기던 값과 동치:
      // 관전자 화면은 privateHand 를 읽지 않는다).
      tableView: snap.tableView,
      privateHand: snap.privateHand ?? ({ seat: -1, cards: [] } as PrivateHand),
      eventSeq: snap.eventSeq,
      disconnectedSeats: snap.disconnectedSeats,
      chips: snap.chips ?? undefined,
    });
  },

  applyEvent(envelope) {
    return useTichuStore.getState().applyEvent(envelope);
  },

  applyPrivateEvent(envelope: StompEnvelope<unknown>) {
    const store = useTichuStore.getState();
    if (envelope.type === 'HAND_DEALT') {
      store.applyPrivateHand(envelope.payload as HandDealtPayload);
    } else if (envelope.type === 'CARDS_RECEIVED') {
      store.setReceived((envelope.payload as CardsReceivedPayload).received);
    } else if (envelope.type === 'ERROR') {
      const payload = envelope.payload as ErrorPayload;
      store.setError(`${payload.code}: ${payload.message}`);
    }
    // 그 외 타입은 조용히 무시 (현행 동작 보존).
  },

  setError(message) {
    useTichuStore.getState().setError(message);
  },
};
