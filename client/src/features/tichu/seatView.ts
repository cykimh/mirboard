/**
 * 본인 시점 좌석 위치(S=하단/W=우측/N=상단/E=좌측). 시계 방향이라 E 가 다음 차례(+1), W 가 직전 차례(+3) 상대다
 * (`viewPosOf`, D-132). 좌석 카드 더미(`SeatCardStack`)는 이 값으로 부채를 중앙 쪽으로 돌린다.
 */
export type SeatViewPos = 's' | 'w' | 'n' | 'e';

/** 본인 시점 위치 — 0 = 나(아래 S), +1 = 다음 차례(왼쪽 E), +2 = 파트너(위 N), +3 = 직전 차례(오른쪽 W). */
const VIEW_POS: readonly SeatViewPos[] = ['s', 'e', 'n', 'w'];

/**
 * 티츄 좌석의 본인 시점 위치 (D-132 — 시계 방향). 서버는 +1 이 다음 차례이고(`TurnManager`) 룰의 패스 "좌" 도 +1 이라,
 * 위에서 내려다볼 때 다음 차례가 내 **왼쪽**(`.seat-e` — 경기장 CSS 에서 왼쪽)에 앉는다. 관전자(`mySeat` −1)도 같은 식이라
 * 좌석 3 이 아래에 온다(기존 동작).
 *
 * <p>좌석 클래스·카드 더미 부채·트릭 카드 비행·채팅 말풍선·이모지가 모두 이 함수 하나를 쓴다 — 매핑이 한 곳이라 서로
 * 다른 자리를 가리키지 않는다.
 */
export function viewPosOf(seat: number, mySeat: number): SeatViewPos {
  return VIEW_POS[(seat - mySeat + 4) % 4];
}
