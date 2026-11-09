import type { RoomEventSink } from '@/ws/roomEventSink';
import { errorText } from '@/ws/errorLabels';
import type { StompEnvelope } from '@/types/stomp';
import type { ArtilleryPrivateView, ArtilleryTableView } from '@/types/artillery';
import { useArtilleryStore } from './artilleryStore';

interface ErrorPayload {
  code: string;
  message: string;
}

/** 서버 거절 사유(씨앗포 `RejectionReason`) → 사용자 문구. 게임 중립 코드(BUSY 등)는 공용 표가 맡는다. */
const ERROR_LABEL: Record<string, string> = {
  NOT_YOUR_TURN: '아직 당신의 차례가 아닙니다.',
  MATCH_OVER: '이미 끝난 판입니다.',
  OUT_OF_RANGE: '각도·힘·이동 거리가 범위를 벗어났습니다.',
  NO_AMMO: '호박탄이 남지 않았어요.',
  INVALID_ACTION: '잘못된 요청입니다.',
};

/**
 * 씨앗포용 {@link RoomEventSink}. 모듈 상수이고 각 메서드는 호출 시점에 `useArtilleryStore.getState()` 를 읽는다(D-103).
 * 비공개 큐에는 `ERROR` 만 온다(엔진의 비공개 이벤트가 없다).
 */
export const artilleryRoomSink: RoomEventSink<ArtilleryTableView, ArtilleryPrivateView> = {
  reset(roomId) {
    useArtilleryStore.getState().reset(roomId);
  },

  applySnapshot(snap) {
    useArtilleryStore.getState().applySnapshot(snap);
  },

  applyEvent(envelope) {
    return useArtilleryStore.getState().applyEvent(envelope);
  },

  applyPrivateEvent(envelope: StompEnvelope<unknown>) {
    if (envelope.type === 'ERROR') {
      const p = envelope.payload as ErrorPayload;
      useArtilleryStore.getState().setError(errorText(p.code, p.message, ERROR_LABEL));
    }
  },

  setError(message) {
    useArtilleryStore.getState().setError(message);
  },
};

export { ERROR_LABEL as artilleryErrorLabels };
