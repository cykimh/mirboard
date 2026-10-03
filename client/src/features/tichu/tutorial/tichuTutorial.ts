import type { GameTutorial } from '@/features/tutorial/types';
import { TUTORIAL_STEPS } from './tutorialSteps';

/**
 * A2 — 티츄 튜토리얼 열람 키. **레거시 키 그대로**다(D-121): 게임별 키로 바꾸면 이미 본
 * 사람에게 다시 뜬다.
 */
export const TICHU_TUTORIAL_SEEN_KEY = 'mirboard.tutorial.seen.v1';

/** D-121 — 티츄 튜토리얼 선언. 단계 내용(`tutorialSteps.tsx`)은 A2 그대로다. */
export const TICHU_TUTORIAL: GameTutorial = {
  steps: TUTORIAL_STEPS,
  description: '티츄 기본 규칙 안내 튜토리얼',
  seenKey: TICHU_TUTORIAL_SEEN_KEY,
};
