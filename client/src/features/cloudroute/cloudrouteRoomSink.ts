import type { RoomEventSink } from '@/ws/roomEventSink';
import { errorText } from '@/ws/errorLabels';
import type { StompEnvelope } from '@/types/stomp';
import type { CloudRouteTableView } from '@/types/cloudroute';
import { cloudrouteFxBus, fxViewOf } from './cloudrouteFxBus';
import { useCloudRouteStore } from './cloudrouteStore';

interface ErrorPayload {
  code: string;
  message: string;
}

/** 서버 거절 사유(구름항로 `RejectionReason`) → 사용자 문구. 게임 중립 인프라 코드는 공용 표(`ws/errorLabels`)가 맡는다. */
const ERROR_LABEL: Record<string, string> = {
  MATCH_OVER: '이미 끝난 판입니다.',
  PLAYER_ELIMINATED: '탈락한 좌석은 더 할 수 없습니다.',
  NOT_YOUR_TURN: '아직 당신의 차례가 아닙니다.',
  WRONG_DECISION: '지금은 그 선택을 할 수 없습니다.',
  INVALID_LEVEL: '고를 수 없는 건설 단계입니다.',
  INVALID_TILE: '고를 수 없는 칸입니다.',
  INSUFFICIENT_FUNDS: '돈이 모자랍니다.',
  NO_BUOY: '구명 부표가 없습니다.',
};

/**
 * 구름항로용 {@link RoomEventSink} (D-213). 규약대로 모듈 상수이고, 각 메서드는 호출 시점에 `getState()` 를 읽는다(D-103).
 * 본인 큐에는 `ERROR` 만 온다 — 숨길 손패가 없어 비공개 이벤트가 없다(D-212).
 */
export const cloudrouteRoomSink: RoomEventSink<CloudRouteTableView, unknown> = {
  reset(roomId) {
    useCloudRouteStore.getState().reset(roomId);
    cloudrouteFxBus.clear();
  },

  applySnapshot(snap) {
    useCloudRouteStore.getState().applySnapshot(snap);
    cloudrouteFxBus.clear();
  },

  // D-218 — 스토어에는 늘 바로 적용한다(진짜 값). 연출 감독이 붙어 있을 때만 적용 전·후 화면 값을 연출 버스에 넘긴다.
  applyEvent(envelope) {
    const store = useCloudRouteStore.getState();
    if (!cloudrouteFxBus.attached) return store.applyEvent(envelope);
    const before = fxViewOf(store);
    const result = store.applyEvent(envelope);
    if (result === 'applied') {
      cloudrouteFxBus.emit({
        type: envelope.type,
        payload: envelope.payload,
        before,
        after: fxViewOf(useCloudRouteStore.getState()),
      });
    }
    return result;
  },

  applyPrivateEvent(envelope: StompEnvelope<unknown>) {
    if (envelope.type === 'ERROR') {
      const p = envelope.payload as ErrorPayload;
      useCloudRouteStore.getState().setError(errorText(p.code, p.message, ERROR_LABEL));
    }
  },

  setError(message) {
    useCloudRouteStore.getState().setError(message, true); // resync 실패는 지속 상태 — 자동으로 닫지 않는다(D-269)
  },
};

export { ERROR_LABEL as cloudrouteErrorLabels };
