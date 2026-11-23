/**
 * 담바둑 게임판 테마 층 (D-274 클래식 — 나무 바둑판·좌석색 돌·먹선과 돌담 결의 담). `'plain'` 으로 바꾸면 그림 없이 납작한 기본 모습이다.
 * 그림 조각과 납작한 모양은 늘 같이 그리고 CSS(`26-dambaduk-table.css`)가 테마 클래스로 고른다.
 */
export type DambadukTheme = 'classic' | 'plain';

/** 되돌리기는 이 한 줄. */
export const DAMBADUK_THEME: DambadukTheme = 'classic';

/** 연출 단계 — `rich` = 돌 미끄러짐·담 쌓임·집 번짐·종료 배너. `'basic'` 이면 연출 없이 바로 바뀐다. */
export type DambadukFx = 'rich' | 'basic';

/** 연출 되돌리기는 이 한 줄. */
export const DAMBADUK_FX: DambadukFx = 'rich';

const THEME_CLASS: Record<DambadukTheme, string> = { classic: ' db-theme-classic', plain: '' };
const FX_CLASS: Record<DambadukFx, string> = { rich: ' db-fx-rich', basic: '' };

/** 색 토큰 + 테마·연출 클래스 — 게임판 루트와 튜토리얼 포털 본문이 같이 쓴다. */
export const DAMBADUK_TOKENS_CLASS = `db-tokens${THEME_CLASS[DAMBADUK_THEME]}${FX_CLASS[DAMBADUK_FX]}`;

/** 좌석 색 번호 → 이름(0 흑 · 1 백 · 2 청). */
export const COLOR_NAME = ['흑', '백', '청'] as const;
/** 좌석 색 번호 → CSS 이름(`db-c-black` 등). */
export const COLOR_KEY = ['black', 'white', 'blue'] as const;
