/**
 * 구름항로 게임판 테마 층 (D-218, D-185). 게임판 루트에 `cr-{테마}` 클래스 하나를 얹는다.
 *
 * - `storybook` — 시안 A "솜구름 그림책"(종이 오리기 섬·열기구 말·움직임 연출). 기본값.
 * - `classic` — 꾸밈 없는 기본 모습(전역 토큰 색, 연출 없음).
 *
 * 되돌리기는 아래 한 줄이다. 연출 감독(`useCloudRouteFx`)도 이 값이 `storybook` 일 때만 붙는다.
 */
export type CloudRouteTheme = 'storybook' | 'classic';

export const CLOUD_ROUTE_THEME: CloudRouteTheme = 'storybook';
