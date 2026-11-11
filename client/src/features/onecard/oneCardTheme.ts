/**
 * 원카드 게임판 테마 층 (D-260, 디자인 2차 D-187). 게임판 루트와 튜토리얼 포털 본문에 테마 클래스 하나를 얹고, 꾸밈 규칙은 전부 그
 * 클래스 아래에만 둔다(`styles/parts/19-onecard-table.css` 끝의 "네온 파티" 구역). 카드 그림 면(`OneCardFace`)과 화면 전용 연출
 * (`useOneCardFx`)도 이 값이 `'neon'` 일 때만 켜진다.
 */
export type OneCardTheme = 'neon' | 'classic';

/** 되돌리기는 이 한 줄 — `'classic'` 이면 D-129 의 기본 모습(기호·숫자 카드, 초록 천, 연출 없음) 그대로다. */
export const ONE_CARD_THEME = 'neon' as OneCardTheme;

export const neonTheme = ONE_CARD_THEME === 'neon';

/** 게임판 루트에 덧붙일 클래스(앞에 공백 포함). 기본 모습이면 빈 문자열. */
export const oneCardThemeClass = neonTheme ? ' oc-theme-neon' : '';

/** 색 토큰 + 테마 클래스 — 튜토리얼 포털 본문(`GameTutorial.bodyClassName`)이 쓴다. */
export const ONE_CARD_TOKENS_CLASS = `oc-tokens${oneCardThemeClass}`;
