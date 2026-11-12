/**
 * D-199 — 메인 카드 안에서만 도는 스크롤 상자. 높이를 고정한 목록(랭킹·대기 중인 방)에 같이 붙인다 — 목록 길이가 바뀌어도
 * (게임 전환·5초 새로고침) 아래 카드가 밀리지 않는다. 스크롤바는 얇게, 자리는 미리 잡아(`scrollbar-gutter`) 생겼다 없어질 때
 * 폭이 흔들리지 않는다.
 */
export const HUB_SCROLL = 'overflow-y-auto overscroll-contain [scrollbar-gutter:stable] [scrollbar-width:thin]';
