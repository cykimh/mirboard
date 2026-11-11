/**
 * 티츄 게임판 테마 층 (D-241, 디자인 2차 D-187). 기본 모습 위에 클래스 하나로 "옻칠 찻집"(붉은 옻칠 틀·금박 선·비단 펠트·
 * 등불 빛)을 얹는다. 꾸밈 규칙은 전부 `styles/parts/25-tichu-lacquer.css` 의 `.tc-` 접두 아래에만 있다.
 * 기본 모습으로 되돌리려면 아래 한 줄을 `'classic'` 으로.
 */
export type TichuTheme = 'lacquer' | 'classic';
export const TICHU_THEME = 'lacquer' as TichuTheme;

export const lacquerTheme = TICHU_THEME === 'lacquer';

/** 게임판 루트(`.game-table`)에 덧붙일 클래스(앞에 공백 포함). 기본 모습이면 빈 문자열. */
export const tichuThemeClass = lacquerTheme ? ' tc-theme-lacquer' : '';

/**
 * 카드 칩에 덧붙일 클래스. 카드는 게임판 밖(튜토리얼·받은 카드 창 — body 포털)에서도 그려지므로 루트 클래스에 기대지 않고
 * 카드가 스스로 든다.
 */
export const tichuCardClass = lacquerTheme ? ' tc-card' : '';

/**
 * 손패 한 줄 겹침에서 카드마다 보여야 하는 최소 폭(px). 옻칠 카드는 모서리 표기(숫자 위·무늬 아래)가 좁아 21px 이면 "10" 도
 * 읽힌다 — 375px 에서 14장이 한 줄에 든다. 기본 모습은 `useHandOverlap.MIN_VISIBLE_STEP`(30) 그대로.
 */
export const LACQUER_MIN_VISIBLE_STEP = 21;

/** OS '동작 줄이기' — JS 로 띄우는 연출(딜·쓸어 가기)을 건너뛴다. CSS 연출은 17-responsive 의 `.tc-` 블록이 끈다. */
export function prefersReducedMotion(): boolean {
  try {
    return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  } catch {
    return false;
  }
}
