/**
 * 게임 중립 거절 코드 → 사용자 문구 (S5). 서버 인프라가 어느 게임에서나 같은 코드로 본인 큐 `ERROR` 를 보낸다 —
 * 인게임 컨트롤러(`GameStompController`: 방·좌석·진행 상태·역직렬화·락 경합·예기치 못한 실패)와 레이트 리미터
 * (`StompRateLimitInterceptor`). 게임 sink 는 자기 거절 사유 표를 먼저 보고 없으면 여기를 본다({@link errorText}).
 *
 * <p>지금은 원카드 sink 만 쓴다. 티츄·스컬킹 sink 는 이 코드 일부를 자기 표에 들고 있다(옮기는 것은 후속).
 */
export const INFRA_ERROR_LABELS: Readonly<Record<string, string>> = {
  BUSY: '다른 처리가 진행 중입니다. 잠시 후 다시 시도하세요.',
  GAME_NOT_STARTED: '아직 게임이 시작되지 않았습니다.',
  // D-122 — 강제 종료·탈주 조기 종료 뒤(FINISHED) 늦게 낸 액션의 거절.
  GAME_NOT_IN_PROGRESS: '이미 끝난 게임입니다.',
  INVALID_ACTION: '이 게임에서 처리할 수 없는 요청입니다. 화면을 새로 고쳐 주세요.',
  INTERNAL_ERROR: '서버에서 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.',
  NOT_IN_ROOM: '이 방의 참가자가 아닙니다.',
  ROOM_NOT_FOUND: '방을 찾을 수 없습니다. 이미 끝났거나 사라진 방입니다.',
  GAME_NOT_AVAILABLE: '지금은 이 게임을 할 수 없습니다.',
  RATE_LIMITED: '요청이 너무 빠릅니다. 잠시 후 다시 시도하세요.',
};

/** 거절 코드 → 문구. 게임 라벨이 먼저, 다음이 인프라 라벨, 둘 다 없으면 `CODE: 원문`(새 코드를 놓치지 않게). */
export function errorText(
  code: string,
  message: string,
  gameLabels: Readonly<Record<string, string>> = {},
): string {
  return gameLabels[code] ?? INFRA_ERROR_LABELS[code] ?? `${code}: ${message}`;
}
