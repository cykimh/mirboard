/**
 * 오목 게임판 테마 층 (D-192). 기본 모습(납작한 판·돌) 위에 클래스 하나로 얹는다 — `'plain'` 으로 바꾸면 예전 모습 그대로다.
 * 그림 조각(`.om-art`)과 납작한 원(`.om-flat`)은 늘 같이 그리고 CSS(`21-omok-table.css`)가 테마 클래스로 고른다.
 */
export type OmokTheme = 'grain' | 'plain';

/** 되돌리기는 이 한 줄. `grain` = 시안 A "결"(등불 아래 나무 판, 흑석·조개 백돌). */
export const OMOK_THEME: OmokTheme = 'grain';

const THEME_CLASS: Record<OmokTheme, string> = { grain: ' om-theme-grain', plain: '' };

/** 색 토큰 + 테마 클래스 — 게임판 루트와 튜토리얼 포털 본문이 같이 쓴다. */
export const OMOK_TOKENS_CLASS = `om-tokens${THEME_CLASS[OMOK_THEME]}`;
