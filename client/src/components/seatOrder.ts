/**
 * 가변 좌석 게임판의 순수 배치 계산 — 스컬킹(2~8인, D-103 Row-Flow)과 원카드(2~6인, D-129)가 같이 쓴다. DOM 을
 * 모른다 — 전수 테스트가 jsdom 없이 돈다(`features/skullking/seatLayout.test.ts`).
 */

/**
 * 상대 좌석을 화면에 늘어놓을 순서. **내 다음 차례부터 좌→우**라 진행 방향이 읽힌다.
 *
 * @param mySeat 관전자는 -1 — 그때는 내 좌석을 뺄 것이 없으므로 전 좌석을 돌려준다.
 */
export function viewOrder(seatCount: number, mySeat: number): number[] {
  if (seatCount <= 0) return [];
  if (mySeat < 0 || mySeat >= seatCount) {
    return Array.from({ length: seatCount }, (_, i) => i);
  }
  return Array.from(
    { length: seatCount - 1 },
    (_, i) => (mySeat + 1 + i) % seatCount,
  );
}

/**
 * 좌석 카드의 최소 폭. `repeat(auto-fit, minmax(이 값, 168px))` 에 꽂으면 인원이 늘수록
 * 한 행에 더 많이 들어가고, 넘치면 **폭 미디어 쿼리 없이** 자동 줄바꿈된다.
 */
export function seatMinWidth(seatCount: number): string {
  if (seatCount <= 4) return '132px';
  if (seatCount <= 6) return '116px';
  return '100px';
}
