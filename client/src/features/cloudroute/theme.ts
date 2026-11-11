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

/**
 * 그림책 테마의 연출 단계 (D-219). `2` 는 2차 연출(솜구름 꼬리·동전 비행·하늘 시간대·바람 빠지는 파산·무지개 결과),
 * `1` 은 D-218 연출 그대로. 연출 큐 따라잡기(`paceFor`)는 단계와 무관하게 늘 켜져 있다.
 */
export type CloudRouteFxLevel = 1 | 2;

export const CLOUD_ROUTE_FX_LEVEL: CloudRouteFxLevel = 2;
