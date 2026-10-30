import { ApiError } from './client';

/**
 * D-138 — 화면에 보일 오류 문구. 서버 오류 원문 중 영어인 것(`Room not found` 등)이 그대로 나오지 않게 코드별로 한국어로 옮긴다.
 * 서버 원문이 한국어인 코드(정지·게스트·판돈 등)는 그대로 쓰고, 모르는 코드의 영어 원문이나 네트워크 오류는 호출 측의 대체 문구로.
 * 서버에 영어 원문의 새 코드가 생기면 `errorText.test` 의 서버 소스 대조가 잡는다.
 */
const MESSAGES: Record<string, string> = {
  ALREADY_IN_ROOM: '이미 이 방에 있어요',
  BAD_CREDENTIALS: '별명 또는 비밀번호가 맞지 않아요',
  GAME_ALREADY_STARTED: '이미 시작한 게임이에요',
  GAME_NOT_AVAILABLE: '지금은 할 수 없는 게임이에요',
  GAME_NOT_IN_PROGRESS: '진행 중인 게임이 아니에요',
  HUMAN_PLAYERS_PRESENT: '사람이 함께하는 판은 방장이 끝낼 수 없어요',
  INVALID_INPUT: '입력한 값을 다시 확인해 주세요',
  NOT_ENOUGH_PLAYERS: '인원이 모자라요',
  NOT_HOST: '방장만 할 수 있어요',
  NOT_IN_ROOM: '이 방에 앉아 있지 않아요',
  RESYNC_NOT_AVAILABLE: '화면을 다시 불러오지 못했어요',
  ROOM_FULL: '자리가 다 찼어요',
  ROOM_NOT_FOUND: '방이 없어졌어요',
  UNAUTHORIZED: '다시 로그인해 주세요',
  USERNAME_TAKEN: '이미 쓰는 별명이에요',
};

/** 한국어로 옮기는 서버 오류 코드(테스트가 서버 소스와 대조한다). */
export const TRANSLATED_ERROR_CODES: readonly string[] = Object.keys(MESSAGES);

const HANGUL = /[가-힣]/;

export function errorText(err: unknown, fallback: string): string {
  if (!(err instanceof ApiError)) return fallback;
  const translated = MESSAGES[err.code];
  if (translated) return translated;
  return HANGUL.test(err.message) ? err.message : fallback;
}
