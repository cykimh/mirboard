import type { RoomEventSink } from '@/ws/roomEventSink';
import { errorText } from '@/ws/errorLabels';
import type { StompEnvelope } from '@/types/stomp';
import type { YachtPrivateView, YachtTableView } from '@/types/yacht';
import { useYachtStore } from './yachtStore';

interface ErrorPayload {
  code: string;
  message: string;
}

/** 서버 거절 사유(요트 `RejectionReason`) → 사용자 문구. 게임 중립 코드(BUSY 등)는 공용 표가 맡는다. */
const ERROR_LABEL: Record<string, string> = {
  NOT_YOUR_TURN: '아직 당신의 차례가 아닙니다.',
  MATCH_OVER: '이미 끝난 판입니다.',
  NO_ROLLS_LEFT: '세 번 다 굴렸어요. 칸을 골라 기록하세요.',
  NOTHING_TO_ROLL: '다섯 개를 모두 고정했어요. 하나 이상 풀어야 굴릴 수 있습니다.',
  HOLD_NOT_ALLOWED: '지금은 고정을 바꿀 수 없습니다.',
  HOLD_UNCHANGED: '고정이 그대로입니다.',
  NOT_ROLLED_YET: '먼저 주사위를 굴리세요.',
  CATEGORY_FILLED: '이미 기록한 칸입니다.',
  INVALID_ACTION: '잘못된 요청입니다.',
};

/**
 * 요트용 {@link RoomEventSink}. 모듈 상수이고 각 메서드는 호출 시점에 `useYachtStore.getState()` 를 읽는다(D-103).
 * 비공개 큐에는 `ERROR` 만 온다(엔진의 비공개 이벤트가 없다).
 */
export const yachtRoomSink: RoomEventSink<YachtTableView, YachtPrivateView> = {
  reset(roomId) {
    useYachtStore.getState().reset(roomId);
  },

  applySnapshot(snap) {
    useYachtStore.getState().applySnapshot(snap);
  },

  applyEvent(envelope) {
    return useYachtStore.getState().applyEvent(envelope);
  },

  applyPrivateEvent(envelope: StompEnvelope<unknown>) {
    if (envelope.type === 'ERROR') {
      const p = envelope.payload as ErrorPayload;
      // 홀드 토글이 빠를 때 서버와 한 박자 어긋나 나는 거절은 조용히 넘긴다 — 다음 DICE_HELD·DICE_ROLLED 가 맞춘다.
      if (p.code === 'HOLD_UNCHANGED') return;
      // D-200 — 굴리기·기록이 방 락 경합으로 거절되면 한 번 다시 보낸다(게임판이 신호를 보고 보낸다). 두 번째도 BUSY 면 문구를 보인다.
      if (p.code === 'BUSY' && useYachtStore.getState().noteBusy()) return;
      useYachtStore.getState().setError(errorText(p.code, p.message, ERROR_LABEL));
    }
  },

  setError(message) {
    useYachtStore.getState().setError(message, true); // resync 실패는 지속 상태 — 자동으로 닫지 않는다(D-269)
  },
};

export { ERROR_LABEL as yachtErrorLabels };
