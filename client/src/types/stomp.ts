// STOMP envelope — `docs/stomp-protocol.md` 와 일치.

export interface StompEnvelope<TPayload = unknown> {
  eventId: string;
  type: string;
  ts: number;
  seq?: number;
  payload: TPayload;
}

export interface LobbyChatPayload {
  userId: number;
  username: string;
  message: string;
}

/**
 * 게임 sink(스토어)의 반영 결과 (D-103 → D-124). 순번 판정(중복·구멍)은 훅이 sink 앞에서
 * 끝낸다 — `ws/seqGate.ts` 의 `judgeSeq`. 그래서 여기에는 순번 관련 값이 없다.
 *
 * - `applied`   — 상태에 반영함. 순번 있는 이벤트면 훅이 기준점을 전진시킨다
 * - `unhandled` — 리듀서 없는 타입(라이프사이클 등) — 훅이 /resync 로 권위 스냅샷을 받는다
 * - `ignored`   — 반영하지 않기로 한 이벤트(예: 스컬킹 매치 종료 뒤의 잔여 이벤트, D-122).
 *                 resync 도 부르지 않는다 — 다시 받아 봐야 또 버릴 것이라서다. 기준점은 전진한다
 */
export type ApplyEventResult = 'applied' | 'unhandled' | 'ignored';

/**
 * `GET /api/rooms/{id}/resync` 응답의 **게임 중립 껍데기** (D-103). 서버
 * `RoomController.ResyncResponse` 와 1:1이고, 게임별로 다른 것은 `tableView`/`privateHand`
 * 두 필드뿐이라 제네릭으로 뺐다.
 *
 * `privateHand` 가 처음부터 nullable 인 것은 서버 계약이다 — 관전자(좌석 없음)와 비공개 상태가
 * 없는 게임(요트)에 null 이 온다.
 */
export interface ResyncEnvelope<TTable = unknown, TPrivate = unknown> {
  roomId: string;
  phase: string;
  eventSeq: number;
  tableView: TTable;
  privateHand: TPrivate | null;
  disconnectedSeats?: number[];
  chips?: Record<number, number> | null;
  /**
   * D-131 — 지금 턴의 남은 시간(ms, 0 이상). 서버가 실제로 발화할 턴 데드라인 기준이라 재접속 직후에도 맞다. 턴 제한 끔·기다리는
   * 좌석 없음(경쟁 창·끝난 매치)·걸린 데드라인 없음이면 null. 게임 중립 — 쓰는 게임판만 읽는다.
   */
  turnRemainingMs?: number | null;
}
