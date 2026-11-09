import type { GameTutorial } from '@/features/tutorial/types';
import { OMOK_TUTORIAL_STEPS } from './omokTutorialSteps';

/**
 * 오목 튜토리얼 선언(D-121). 열람 키는 게임 전용이다. `bodyClassName: 'om-tokens'` — 다이얼로그는 body 포털이라 `.om-table` 밖이다.
 * 판 그림이 쓰는 색 토큰을 본문 전체에 푼다.
 */
export const OMOK_TUTORIAL: GameTutorial = {
  steps: OMOK_TUTORIAL_STEPS,
  description: '오목 기본 규칙 안내 튜토리얼',
  seenKey: 'mirboard.tutorial.omok.seen.v1',
  bodyClassName: 'om-tokens',
};
