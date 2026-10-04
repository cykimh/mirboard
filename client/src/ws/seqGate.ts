/**
 * 공개 토픽 이벤트의 순번 판정 (D-124). 판정은 {@link import('./useStompRoom').useStompRoom}
 * 한 곳에서만 한다 — 게임 스토어(sink)는 판정이 끝난 이벤트만 받는 순수 리듀서다. 예전에는
 * 티츄·스컬킹 스토어가 같은 판정을 각자 들고 있었다(D-103 부채).
 *
 * - `next`        바로 다음 순번(lastSeq + 1) — sink 로 넘긴다
 * - `duplicate`   이미 지난 순번 — 버린다
 * - `gap`         순번이 건너뛰었다 — 놓친 이벤트가 있으니 resync 로 권위 스냅샷을 받는다
 * - `unsequenced` 순번 없는 메타 이벤트(접속 배지·칩 정산 등) — 판정 없이 sink 로 넘긴다
 */
export type SeqVerdict = 'next' | 'duplicate' | 'gap' | 'unsequenced';

/**
 * @param lastSeq 마지막으로 반영한 순번. resync 스냅샷의 `eventSeq` 가 권위 기준점이다.
 * @param seq     envelope 의 `seq`. 서버는 메타 이벤트에서 이 필드를 생략한다(`NON_NULL`).
 */
export function judgeSeq(lastSeq: number, seq: number | null | undefined): SeqVerdict {
  if (seq === undefined || seq === null) return 'unsequenced';
  if (seq <= lastSeq) return 'duplicate';
  if (seq > lastSeq + 1) return 'gap';
  return 'next';
}
