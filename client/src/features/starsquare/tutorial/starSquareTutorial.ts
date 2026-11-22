import type { GameTutorial } from '@/features/tutorial/types';
import { STAR_SQUARE_TOKENS_CLASS } from '../starSquareTheme';
import { STAR_SQUARE_TUTORIAL_STEPS } from './starSquareTutorialSteps';

/**
 * 별자리 수판 튜토리얼 선언(D-121). `bodyClassName: 'sq-tokens'` — 다이얼로그는 body 포털이라 `.sq-table` 밖이다. 판 그림이 쓰는 색
 * 토큰을 본문 전체에 푼다.
 */
export const STAR_SQUARE_TUTORIAL: GameTutorial = {
  steps: STAR_SQUARE_TUTORIAL_STEPS,
  description: '별자리 수판 기본 규칙 안내 튜토리얼',
  seenKey: 'mirboard.tutorial.starsquare.seen.v1',
  bodyClassName: STAR_SQUARE_TOKENS_CLASS,
};
