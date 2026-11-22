import type { RoomEventSink } from '@/ws/roomEventSink';
import { errorText } from '@/ws/errorLabels';
import type { StompEnvelope } from '@/types/stomp';
import type { StarSquarePrivateBoard, StarSquareTableView } from '@/types/starsquare';
import { playCues } from '@/features/sound/tableSfx';
import { starSquareCues } from './starSquareSfx';
import { useStarSquareStore } from './starSquareStore';

interface ErrorPayload {
  code: string;
  message: string;
}

/**
 * 서버 거절 사유(별자리 수판 `RejectionReason`, `docs/rules-starsquare.md` §7.1) → 문구. *조용히* 셋(`PUZZLE_CLOSED`·`STALE_SYNC`·
 * `ALREADY_SOLVED`)과 `BUSY` 는 문구 없이 보내기가 알아서 처리한다.
 */
const ERROR_LABEL: Record<string, string> = {
  MATCH_OVER: '이미 끝난 판입니다.',
  NOT_IN_PLAY: '이 판에서 빠진 자리예요.',
  BAD_GRID: '판을 보내지 못했어요. 화면을 다시 맞춥니다.',
  CLUE_CHANGED: '단서 칸은 바꿀 수 없어요. 화면을 다시 맞춥니다.',
};

/** 문구 없이 넘기는 사유 — 보내기 상태만 고친다. */
export const QUIET_CODES: ReadonlySet<string> = new Set(['PUZZLE_CLOSED', 'STALE_SYNC', 'ALREADY_SOLVED', 'BUSY']);

/**
 * 별자리 수판용 {@link RoomEventSink} — 모듈 상수이고 각 메서드는 호출 시점에 `useStarSquareStore.getState()` 를 읽는다(D-103).
 *
 * <p>비공개 큐에는 `ERROR` 만 온다(받아들인 SYNC 의 메아리는 없다 — 내 판은 로컬이 먼저). 거절은 보내기 상태로 넘기고, 문구가 있는 사유만
 * 화면 위 안내(`FloatingNotice`, D-269)로 띄운다.
 */
export const starSquareRoomSink: RoomEventSink<StarSquareTableView, StarSquarePrivateBoard> = {
  reset(roomId) {
    useStarSquareStore.getState().reset(roomId);
  },

  applySnapshot(snap) {
    useStarSquareStore.getState().applySnapshot(snap);
  },

  applyEvent(envelope) {
    const before = useStarSquareStore.getState();
    const result = before.applyEvent(envelope);
    // D-267 — 효과음은 실제로 적용한 공개 이벤트에서만(스냅샷은 applySnapshot 길이라 울리지 않는다).
    if (result === 'applied') playCues(starSquareCues(envelope, before, useStarSquareStore.getState()));
    return result;
  },

  applyPrivateEvent(envelope: StompEnvelope<unknown>) {
    if (envelope.type !== 'ERROR') return;
    const store = useStarSquareStore.getState();
    const p = envelope.payload as ErrorPayload;
    store.onSyncRejected(p.code, Date.now());
    if (!QUIET_CODES.has(p.code)) store.setError(errorText(p.code, p.message, ERROR_LABEL));
  },

  setError(message) {
    useStarSquareStore.getState().setError(message, true); // resync 실패는 지속 상태 — 자동으로 닫지 않는다(D-269)
  },
};

export { ERROR_LABEL as starSquareErrorLabels };
