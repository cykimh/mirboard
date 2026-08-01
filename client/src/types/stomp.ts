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
 * 부분 패치 결과 (D-103). `tichuStore` 의 동명 타입과 **같은 문자열 유니온**이라 구조적으로
 * 호환된다 — 티츄 스토어를 0줄도 고치지 않고 sink 계약을 만족시키기 위해 여기 새로 선언한다.
 *
 * - `applied`   — 패치 성공, lastSeq 갱신
 * - `duplicate` — 이미 처리한 이벤트
 * - `gap`       — seq 구멍, /resync 권유
 * - `unhandled` — 리듀서 없는 타입, /resync 권유
 */
export type ApplyEventResult = 'applied' | 'duplicate' | 'gap' | 'unhandled';

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
}
