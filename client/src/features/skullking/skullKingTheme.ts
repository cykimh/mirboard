/**
 * 스컬킹 게임판 테마 층 (D-231, 디자인 2차 D-187). 게임판 루트와 튜토리얼 포털 본문에 테마 클래스 하나를 얹고, 꾸밈 규칙은
 * 전부 그 클래스 아래에만 둔다(`styles/parts/18-skullking-table.css` 의 "촛불 선장실" 구역). 그림 조각(`.sk-card-art`)과
 * 연출 조각(`.sk-fx-*`)은 테마일 때만 그린다 — `'classic'` 이면 예전 DOM·모습 그대로다.
 */
export type SkullKingTheme = 'cabin' | 'classic';

/** 되돌리기는 이 한 줄. `cabin` = "촛불 선장실"(나무 갑판·양피지·놋쇠·촛불). */
export const SKULL_KING_THEME = 'cabin' as SkullKingTheme;

export const cabinTheme = SKULL_KING_THEME === 'cabin';

/** 루트에 덧붙일 클래스(앞에 공백 포함). 기본 모습이면 빈 문자열. */
export const skThemeClass = cabinTheme ? ' sk-theme-cabin' : '';

/** 색 토큰 + 테마 클래스 — 튜토리얼 포털 본문(`GameTutorial.bodyClassName`)이 쓴다. */
export const SK_TOKENS_CLASS = `sk-tokens${skThemeClass}`;
