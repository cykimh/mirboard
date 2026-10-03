import { TICHU_TUTORIAL } from '@/features/tichu/tutorial/tichuTutorial';
import type { GameTutorial } from './types';

/**
 * D-121 — 게임 id → 튜토리얼 레지스트리. 클라에서 **튜토리얼용 게임 id 를 아는 유일한
 * 지점**이다(D-74 `gameWiki.ts` 와 같은 부류). 허브·대기실은 `tutorialFor` 만 본다.
 *
 * <p>새 게임은 `features/{game}/tutorial` 에 `GameTutorial` 을 선언하고 여기 한 줄을 더한다.
 * 빠뜨려도 '게임 방법' 버튼이 안 뜰 뿐 깨지지 않는다.
 */
export const GAME_TUTORIALS: Readonly<Record<string, GameTutorial>> = {
  TICHU: TICHU_TUTORIAL,
};

/** 서버 게임 id(`TICHU` 등)로 조회한다. 대소문자는 `loadGame` 과 같이 대문자로 정규화한다. */
export function tutorialFor(gameId?: string | null): GameTutorial | undefined {
  if (!gameId) return undefined;
  const key = gameId.toUpperCase();
  return Object.prototype.hasOwnProperty.call(GAME_TUTORIALS, key) ? GAME_TUTORIALS[key] : undefined;
}
