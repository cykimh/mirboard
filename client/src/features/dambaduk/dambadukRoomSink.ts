import type { RoomEventSink } from '@/ws/roomEventSink';
import { errorText } from '@/ws/errorLabels';
import type { StompEnvelope } from '@/types/stomp';
import type { DambadukTableView } from '@/types/dambaduk';
import { playCues } from '@/features/sound/tableSfx';
import { dambadukCues, dambadukErrorCues, dambadukSfxSeat } from './dambadukSfx';
import { useDambadukStore } from './dambadukStore';

interface ErrorPayload {
  code: string;
  message: string;
}

/** 서버 거절 사유(담바둑 `RejectionReason`, 룰 §4.5) → 사용자 문구. 게임 중립 코드는 공용 표(`ws/errorLabels`)가 맡는다. */
const ERROR_LABEL: Record<string, string> = {
  MATCH_OVER: '이미 끝난 판입니다.',
  NOT_YOUR_TURN: '아직 당신의 차례가 아닙니다.',
  WRONG_PHASE: '지금은 그 일을 할 단계가 아닙니다.',
  OUT_OF_BOARD: '판 밖에는 놓을 수 없습니다.',
  NO_STONE_LEFT: '그 돌은 더 놓을 수 없습니다.',
  OCCUPIED: '이미 돌이 있는 칸입니다.',
  NOT_YOUR_STONE: '내 돌만 옮길 수 있습니다.',
  BAD_PATH: '그 돌은 그렇게 갈 수 없습니다.',
  JUMP_UNAVAILABLE: '담 넘기를 쓸 수 없습니다.',
  PATH_BLOCKED: '가는 길이 막혀 있습니다.',
  WALL_IN_PATH: '가는 길에 담이 있습니다.',
  JUMP_WITHOUT_WALL: '넘을 담이 없는 길입니다.',
  WALL_SIDE_TAKEN: '그 면에는 담을 세울 수 없습니다.',
};

/**
 * 담바둑용 {@link RoomEventSink} — 모듈 상수이고 각 메서드는 호출 시점에 `useDambadukStore.getState()` 를 읽는다(D-103).
 *
 * <p>비공개 큐에는 `ERROR` 만 온다(손패가 없다). 거절이 오면 낙관적으로 그린 흐린 돌을 지운다.
 */
export const dambadukRoomSink: RoomEventSink<DambadukTableView, unknown> = {
  reset(roomId) {
    useDambadukStore.getState().reset(roomId);
  },

  applySnapshot(snap) {
    useDambadukStore.getState().applySnapshot(snap);
  },

  applyEvent(envelope) {
    const before = useDambadukStore.getState();
    const result = before.applyEvent(envelope);
    // D-267 — 효과음은 실제로 적용한 공개 이벤트에서만(스냅샷은 applySnapshot 길이라 울리지 않는다).
    if (result === 'applied') playCues(dambadukCues(envelope, before, useDambadukStore.getState(), dambadukSfxSeat()));
    return result;
  },

  applyPrivateEvent(envelope: StompEnvelope<unknown>) {
    if (envelope.type !== 'ERROR') return;
    const store = useDambadukStore.getState();
    const p = envelope.payload as ErrorPayload;
    store.clearPending();
    store.setError(errorText(p.code, p.message, ERROR_LABEL));
    playCues(dambadukErrorCues(p.code));
  },

  setError(message) {
    useDambadukStore.getState().setError(message, true); // resync 실패는 지속 상태 — 자동으로 닫지 않는다(D-269)
  },
};

export { ERROR_LABEL as dambadukErrorLabels };
