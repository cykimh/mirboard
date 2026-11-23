import type { GameTutorial } from '@/features/tutorial/types';
import { DAMBADUK_TOKENS_CLASS } from '../dambadukTheme';
import { DAMBADUK_TUTORIAL_STEPS } from './dambadukTutorialSteps';

/**
 * 담바둑 튜토리얼 선언(D-121). 열람 키는 게임 전용이다. 다이얼로그는 body 포털이라 `.db-table` 밖이다 — 판 그림이 쓰는 색 토큰을
 * 본문 전체에 푼다(테마 클래스도 같이).
 */
export const DAMBADUK_TUTORIAL: GameTutorial = {
  steps: DAMBADUK_TUTORIAL_STEPS,
  description: '담바둑 기본 규칙 안내 튜토리얼',
  seenKey: 'mirboard.tutorial.dambaduk.seen.v1',
  bodyClassName: DAMBADUK_TOKENS_CLASS,
};
