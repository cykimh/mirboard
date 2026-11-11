/**
 * 요트 게임판 테마 층(D-203, 디자인 트랙 D-185). 게임판 루트·튜토리얼 퀴즈에 테마 클래스 하나를 얹고, 꾸밈 규칙은 전부 그 클래스 아래에만
 * 둔다(`styles/parts/20-yacht-table.css` 의 "미드나잇 살롱" 구역). 기본 모습으로 되돌리려면 아래 한 줄을 `'classic'` 으로.
 */
export type YachtTheme = 'salon' | 'classic';
export const YACHT_THEME = 'salon' as YachtTheme;

/** 루트에 덧붙일 클래스(앞에 공백 포함). 기본 모습이면 빈 문자열. */
export const yachtThemeClass = YACHT_THEME === 'salon' ? ' yt-theme-salon' : '';
export const salonTheme = YACHT_THEME === 'salon';

/** 좌석 색 — 좌석 띠·점수표 열. 살롱은 펠트·황동과 어울리게 한 톤 낮춘 색. */
const SEAT_ACCENTS: Record<YachtTheme, string[]> = {
  classic: ['#5b8def', '#e85d75', '#3fa45b', '#e0b32a'],
  salon: ['#c9485e', '#5b8fc0', '#58a57c', '#c98b2b'],
};
export const seatAccent = (seat: number) => SEAT_ACCENTS[YACHT_THEME][((seat % 4) + 4) % 4];

/** OS '동작 줄이기' — JS 로 돌리는 연출(구르는 동안 눈 바뀜)을 끈다. CSS 연출은 17-responsive 의 미디어가 끈다. */
export function prefersReducedMotion(): boolean {
  try {
    return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  } catch {
    return false;
  }
}
