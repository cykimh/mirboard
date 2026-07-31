import type {
  ApplyEventResult,
  ResyncEnvelope,
  StompEnvelope,
} from '@/types/stomp';

/**
 * 방 소켓 훅({@link import('./useStompRoom').useStompRoom})과 **게임별 스토어** 사이의
 * 유일한 경계 (D-103).
 *
 * 이 계약이 생긴 이유: `useStompRoom` 이 `useTichuStore` 를 하드코딩하고 있어 두 번째 게임의
 * 이벤트를 받을 자리가 없었다. 훅은 이제 게임 스토어를 import 하지 않고, 게임별 sink 파일이
 * 스토어에 꽂는다 (`features/tichu/tichuRoomSink.ts`, `features/skullking/skullkingRoomSink.ts`).
 *
 * <h3>구현 규약 — 어기면 조용히 깨진다</h3>
 * <ol>
 *   <li><b>sink 는 모듈 상수여야 한다.</b> 렌더마다 새 객체를 만들면 안 된다. 훅이 sink 를
 *       ref 에 담아 effect 의존성에서 빼기 때문에 실제로 소켓이 재연결되지는 않지만, 그 방어에
 *       의존하지 말고 애초에 안정 참조를 넘긴다.</li>
 *   <li><b>각 메서드는 호출 시점에 `useXStore.getState()` 를 읽어야 한다.</b> 모듈 로드 시점에
 *       스토어 함수를 캡처해 담으면 stale closure 가 된다 — 위 1번(ref 로 deps 에서 뺀 것)의
 *       대가가 정확히 이 위험이다.</li>
 *   <li><b>{@link setError} 는 REST resync 실패 전용이다.</b> 서버가 보낸 `ERROR` 프레임은
 *       {@link applyPrivateEvent} 로 간다 — 게임마다 에러 코드가 달라 라벨링 위치가 게임 쪽이어야
 *       한다.</li>
 *   <li><b>{@link applyPrivateEvent} 는 미지 타입을 조용히 무시한다.</b> 현행 동작 보존.</li>
 * </ol>
 *
 * 제네릭을 미지정으로 두면 `unknown` 으로 굳어 컴파일러 도움이 사라진다 — 구현 파일은 반드시
 * 구체 타입을 명시해 선언하라 (예: `RoomEventSink<SkullKingTableView, SkullKingPrivateView>`).
 */
export interface RoomEventSink<TTable = unknown, TPrivate = unknown> {
  /** 방 진입/전환 시 게임 상태 초기화. */
  reset(roomId: string): void;

  /** REST `/resync` 응답을 권위 스냅샷으로 반영 (lastSeq 재설정 포함). */
  applySnapshot(snapshot: ResyncEnvelope<TTable, TPrivate>): void;

  /**
   * 공개 토픽 이벤트의 부분 패치. 반환값이 `'gap'`/`'unhandled'` 면 훅이 `/resync` 를 부른다.
   *
   * <p>시그니처는 티츄 스토어의 `applyEvent` 와 글자 그대로 같다 — 어댑터 없이 만족하도록.
   */
  applyEvent(envelope: {
    type: string;
    seq?: number;
    payload: unknown;
  }): ApplyEventResult;

  /** 본인 큐(`/user/queue/room/{id}`) 프레임 전량 — `HAND_DEALT`·`ERROR`·게임별 추가 타입. */
  applyPrivateEvent(envelope: StompEnvelope<unknown>): void;

  /** REST resync 실패 알림 (서버 `ERROR` 프레임 아님 — 규약 3번). */
  setError(message: string | null): void;
}
