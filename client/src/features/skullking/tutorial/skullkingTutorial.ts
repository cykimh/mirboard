import type { GameTutorial } from '@/features/tutorial/types';
import { SKULL_KING_TUTORIAL_STEPS } from './skullkingTutorialSteps';

/**
 * D-121 — 스컬킹 튜토리얼 선언. 열람 키는 게임 전용이다(티츄 키와 격리).
 *
 * <p>`bodyClassName: 'sk-tokens'` — 다이얼로그는 body 포털이라 `.sk-table` 밖이다. 칩 색
 * 토큰(`--sk-suit-*`)을 본문 전체(단계 칩·TrickQuiz)에 한 번에 푼다.
 */
export const SKULL_KING_TUTORIAL: GameTutorial = {
  steps: SKULL_KING_TUTORIAL_STEPS,
  description: '스컬킹 기본 규칙 안내 튜토리얼',
  seenKey: 'mirboard.tutorial.skull_king.seen.v1',
  bodyClassName: 'sk-tokens',
};
