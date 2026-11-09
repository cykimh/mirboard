import type { RoomEventSink } from '@/ws/roomEventSink';
import { errorText } from '@/ws/errorLabels';
import type { StompEnvelope } from '@/types/stomp';
import type { OmokTableView } from '@/types/omok';
import { useOmokStore } from './omokStore';

interface ErrorPayload {
  code: string;
  message: string;
}

/** 서버 거절 사유(오목 `RejectionReason`) → 사용자 문구. 게임 중립 코드는 공용 표(`ws/errorLabels`)가 맡는다. */
const ERROR_LABEL: Record<string, string> = {
  MATCH_OVER: '이미 끝난 판입니다.',
  NOT_YOUR_TURN: '아직 당신의 차례가 아닙니다.',
  OUT_OF_BOARD: '판 밖에는 둘 수 없습니다.',
  OCCUPIED: '이미 돌이 있는 자리입니다.',
  FORBIDDEN_MOVE: '흑은 그 자리에 둘 수 없습니다(금수).',
};

/**
 * 오목용 {@link RoomEventSink} — 모듈 상수이고 각 메서드는 호출 시점에 `useOmokStore.getState()` 를 읽는다(D-103).
 *
 * <p>비공개 큐에는 `ERROR` 만 온다(손패가 없다). 거절이 오면 낙관적으로 그린 흐린 돌을 지운다.
 */
export const omokRoomSink: RoomEventSink<OmokTableView, unknown> = {
  reset(roomId) {
    useOmokStore.getState().reset(roomId);
  },

  applySnapshot(snap) {
    useOmokStore.getState().applySnapshot(snap);
  },

  applyEvent(envelope) {
    return useOmokStore.getState().applyEvent(envelope);
  },

  applyPrivateEvent(envelope: StompEnvelope<unknown>) {
    if (envelope.type !== 'ERROR') return;
    const store = useOmokStore.getState();
    const p = envelope.payload as ErrorPayload;
    store.clearPending();
    store.setError(errorText(p.code, p.message, ERROR_LABEL));
  },

  setError(message) {
    useOmokStore.getState().setError(message);
  },
};

export { ERROR_LABEL as omokErrorLabels };
