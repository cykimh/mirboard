import type { RoomEventSink } from '@/ws/roomEventSink';
import { errorText } from '@/ws/errorLabels';
import type { StompEnvelope } from '@/types/stomp';
import type { HintVoidPayload, VoidReason, WordTempPrivateView, WordTempTableView } from '@/types/wordtemp';
import { playCues } from '@/features/sound/tableSfx';
import { wordTempCues, wordTempPrivateCues } from './wordTempSfx';
import { useWordTempStore } from './wordTempStore';

interface ErrorPayload {
  code: string;
  message: string;
}

/** 서버 거절 사유(낱말 온도 `RejectionReason`, `docs/rules-wordtemp.md` §5) → 문구. 게임 중립 코드(BUSY 등)는 공용 표가 맡는다. */
const ERROR_LABEL: Record<string, string> = {
  MATCH_OVER: '이미 끝난 판이에요.',
  NOT_YOUR_PHASE: '지금은 낼 수 없어요.',
  BAD_CHOICE: '고를 수 없는 낱말이에요.',
  ALREADY_CHOSEN: '이미 낱말을 골랐어요.',
  HINT_FORMAT: '힌트는 한글 낱말 1~10자로 내 주세요.',
  HINT_IS_ANSWER: '정답이 들어간 낱말은 힌트로 낼 수 없어요.',
  HINT_INSIDE_ANSWER: '정답 안에 들어 있는 낱말은 힌트로 낼 수 없어요.',
  HINT_DUPLICATE: '이미 낸 힌트예요.',
  HINT_LIMIT: '이번 차수의 힌트를 다 냈어요.',
  SCORING_IN_PROGRESS: '앞 힌트를 채점하는 중이에요. 잠깐만요.',
  UNKNOWN_WORD: '아직 모르는 낱말이에요. 다른 낱말로 내 주세요.',
  NO_HINTS_YET: '힌트를 하나 이상 내야 끝낼 수 있어요.',
  GUESS_FORMAT: '답은 한글 낱말 1~10자로 내 주세요.',
  ALREADY_GUESSED: '이번 차수에는 이미 답을 냈어요.',
};

/** 채점 결과로 무효가 된 힌트(§6.2) — 거절이 아니라 안내다. 개수는 서버가 돌려줬다. */
const VOID_LABEL: Record<VoidReason, string> = {
  NOT_A_NOUN: '명사가 아니에요. 개수는 돌려드렸어요.',
  SCORING_FAILED: '채점이 늦어졌어요. 다시 내 주세요.',
};

/**
 * 낱말 온도용 {@link RoomEventSink} — 모듈 상수이고 각 메서드는 호출 시점에 `useWordTempStore.getState()` 를 읽는다(D-103).
 *
 * <p>본인 큐에는 내 후보(`WORD_CANDIDATES`)·내 힌트의 채점(`HINT_SCORED`·`HINT_PENDING`·`HINT_VOID`)·내 답(`GUESS_ACCEPTED`)·`ERROR` 가
 * 온다(전부 순번 없음, D-126). 방 락 경합(`BUSY`)은 보낸 것을 한 번 다시 보내고(게임판이 신호를 본다), 그 밖의 거절·무효 안내는 화면 위
 * 안내(`FloatingNotice`, D-269)로 띄운다.
 */
export const wordTempRoomSink: RoomEventSink<WordTempTableView, WordTempPrivateView> = {
  reset(roomId) {
    useWordTempStore.getState().reset(roomId);
  },

  applySnapshot(snap) {
    useWordTempStore.getState().applySnapshot(snap);
  },

  applyEvent(envelope) {
    const before = useWordTempStore.getState();
    const result = before.applyEvent(envelope);
    // D-267 — 효과음은 실제로 적용한 공개 이벤트에서만(스냅샷은 applySnapshot 길이라 울리지 않는다).
    if (result === 'applied') playCues(wordTempCues(envelope, before, useWordTempStore.getState()));
    return result;
  },

  applyPrivateEvent(envelope: StompEnvelope<unknown>) {
    const store = useWordTempStore.getState();
    if (envelope.type === 'ERROR') {
      const p = envelope.payload as ErrorPayload;
      if (p.code === 'BUSY' && store.noteBusy()) return;
      store.setError(errorText(p.code, p.message, ERROR_LABEL));
      return;
    }
    store.applyPrivate(envelope.type, envelope.payload);
    // 스토어가 내 좌석 것만 받는다 — 소리·안내도 같은 조건에서만.
    const mine = store.mySeat >= 0 && (envelope.payload as { seat?: number } | null)?.seat === store.mySeat;
    if (!mine) return;
    playCues(wordTempPrivateCues(envelope.type, envelope.payload));
    if (envelope.type === 'HINT_VOID') {
      const reason = (envelope.payload as HintVoidPayload).reason;
      useWordTempStore.setState((s) => ({
        errorMessage: VOID_LABEL[reason] ?? VOID_LABEL.SCORING_FAILED,
        errorSeq: s.errorSeq + 1,
        errorSticky: false,
      }));
    }
  },

  setError(message) {
    useWordTempStore.getState().setError(message, true); // resync 실패는 지속 상태 — 자동으로 닫지 않는다(D-269)
  },
};

export { ERROR_LABEL as wordTempErrorLabels, VOID_LABEL as wordTempVoidLabels };
