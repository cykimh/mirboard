/**
 * D-269 — 게임 스토어가 화면 위 오류 안내(`ErrorNotice`)를 위해 함께 두는 값.
 * `errorSeq` 는 `setError` 가 불릴 때마다 오른다 — 같은 문구가 다시 와도(같은 거절을 연달아 받음) 자동 닫힘 시간을 다시 재는 기준이다
 * (문구만 보면 두 번째 거절이 첫 번째의 남은 시간에 사라진다). `errorSticky` 는 저절로 풀리지 않는 상태(resync 실패 —
 * `RoomEventSink.setError`)라 자동으로 닫지 않는다는 표시다(다음 스냅샷이나 [닫기]가 지운다).
 */
export interface ErrorNoticeFields {
  errorMessage: string | null;
  errorSeq: number;
  errorSticky: boolean;
}

/** `setError` 의 상태 조각. */
export function errorNoticePatch(prev: { errorSeq: number }, message: string | null, sticky = false): ErrorNoticeFields {
  return { errorMessage: message, errorSeq: prev.errorSeq + 1, errorSticky: message !== null && sticky };
}
