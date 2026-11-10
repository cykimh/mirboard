import type { GameRecord } from '@/api/users';

/**
 * D-115 — 헤더 티어 배지의 기준 게임: 가장 많이 한 게임(동률이면 목록 앞쪽).
 * 레이팅이 게임별로 갈라진 뒤에도 "내 티어"를 한 칸에 보여 주기 위한 규칙이다.
 */
export function primaryGame(records: GameRecord[]): GameRecord | null {
  let best: GameRecord | null = null;
  for (const r of records) {
    // D-201 — 최고 점수만 있는 줄(혼자만 한 요트, 0승 0패)은 계급 기준이 되지 않는다.
    if (r.winCount + r.loseCount === 0) continue;
    if (!best || r.winCount + r.loseCount > best.winCount + best.loseCount) best = r;
  }
  return best;
}
