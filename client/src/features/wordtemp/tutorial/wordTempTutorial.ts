import type { GameTutorial } from '@/features/tutorial/types';
import { WORD_TEMP_TOKENS_CLASS } from '../wordTempTheme';
import { WORD_TEMP_TUTORIAL_STEPS } from './wordTempTutorialSteps';

/**
 * 낱말 온도 튜토리얼 선언(D-121). `bodyClassName: 'wt-tokens'` — 다이얼로그는 body 포털이라 `.wt-table` 밖이다. 온도계 그림이 쓰는 색
 * 토큰을 본문 전체에 푼다.
 */
export const WORD_TEMP_TUTORIAL: GameTutorial = {
  steps: WORD_TEMP_TUTORIAL_STEPS,
  description: '낱말 온도 기본 규칙 안내 튜토리얼',
  seenKey: 'mirboard.tutorial.wordtemp.seen.v1',
  bodyClassName: WORD_TEMP_TOKENS_CLASS,
};
