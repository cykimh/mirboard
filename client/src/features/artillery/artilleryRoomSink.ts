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
  NO_AMMO: '그 탄이 남지 않았어요.',
  NO_ITEM: '그 아이템은 이미 썼어요.',
  INVALID_ACTION: '잘못된 요청입니다.',
  CANNOT_MOVE: '더 걸어갈 수 없어요.',
};

/** 보낸 `MOVE` 가 답을 기다릴 때 띠를 띄우지 않는 거절 — 걷기 보내기가 다시 보내거나(BUSY) 서버 자리로 돌아간다(D-251). */
const QUIET_WHILE_MOVING = new Set(['BUSY', 'CANNOT_MOVE']);

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
      const st = useArtilleryStore.getState();
      st.noteError(p.code);
      if (st.moveInFlight && QUIET_WHILE_MOVING.has(p.code)) return;
      st.setError(errorText(p.code, p.message, ERROR_LABEL));
    }
  },

  setError(message) {
    useArtilleryStore.getState().setError(message, true); // resync 실패는 지속 상태 — 자동으로 닫지 않는다(D-269)
  },
};

export { ERROR_LABEL as artilleryErrorLabels };
