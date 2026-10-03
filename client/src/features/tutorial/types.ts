import type { ReactNode } from 'react';

/** D-121 — 튜토리얼 한 단계. */
export interface TutorialStep {
  title: string;
  body: ReactNode;
  /**
   * 이 단계 문장의 근거(룰 문서 § 인용). 화면에 그리지 않는다 — 룰 문서와 문구가 어긋나지
   * 않게 테스트가 붙잡는 손잡이다.
   */
  source?: string;
}

/**
 * D-121 — 게임 하나의 튜토리얼 선언. 각 게임 폴더(`features/{game}/tutorial`)가 선언하고
 * `features/tutorial/gameTutorials.ts` 레지스트리가 게임 id 에 매핑한다.
 */
export interface GameTutorial {
  steps: TutorialStep[];
  /** 다이얼로그 접근성 설명(sr-only). */
  description: string;
  /** 열람 플래그 localStorage 키 — 게임마다 고유해야 한다. */
  seenKey: string;
  /**
   * 스크롤 본문에 붙일 클래스. 다이얼로그는 body 포털이라 게임판 스코프(예: `.sk-table`)
   * 밖이다 — 게임 토큰이 필요하면 여기서 푼다(스컬킹 `sk-tokens`).
   */
  bodyClassName?: string;
}
