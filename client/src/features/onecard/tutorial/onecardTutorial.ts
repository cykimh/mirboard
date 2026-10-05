import type { GameTutorial } from '@/features/tutorial/types';
import { ONE_CARD_TUTORIAL_STEPS } from './onecardTutorialSteps';

/**
 * D-129 — 원카드 튜토리얼 선언. 열람 키는 게임 전용이다(다른 게임 키와 격리).
 *
 * <p>`bodyClassName: 'oc-tokens'` — 다이얼로그는 body 포털이라 `.oc-table` 밖이다. 카드 칩·연습 버튼이 쓰는 색
 * 토큰을 본문 전체에 한 번에 푼다.
 */
export const ONE_CARD_TUTORIAL: GameTutorial = {
  steps: ONE_CARD_TUTORIAL_STEPS,
  description: '원카드 기본 규칙 안내 튜토리얼',
  seenKey: 'mirboard.tutorial.one_card.seen.v1',
  bodyClassName: 'oc-tokens',
};
