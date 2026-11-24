import type { GameRecord, Tier, UserStats } from '@/api/users';

/** D-117 — 게스트는 username 접두 `guest-`(하이픈은 가입 이름 규칙 밖이라 위조 불가). 서버 `GuestPolicy` 와 같은 규약. */
export function isGuestName(username: string | undefined): boolean {
  return username?.startsWith('guest-') ?? false;
}

/** 한 판도 안 한 사람의 계급 — 시작 1000점(D-164). 전적을 못 받았을 때의 폴백이기도 하다. */
export const STARTING_TIER: Tier = 'MISSILE';

/**
 * D-115 — 이 게임의 전적 줄. D-305 — **계급은 통합 레이팅 하나**(`stats.tier`, 명예 계급 D-198 포함)라 어느 게임 줄이든 그 계급을
 * 싣고, 안 해 본 게임도 0승 0패 줄을 만들어 같은 계급을 그린다(대기실 카드·게임판 좌석). 게스트는 계급이 없으니 줄이 없으면 null.
 */
export function gameRecordOf(stats: UserStats, gameType: string): GameRecord | null {
  const rec = stats.games.find((g) => g.gameType === gameType) ?? null;
  if (isGuestName(stats.username) || !stats.tier) return rec;
  if (rec) return { ...rec, tier: stats.tier };
  return { gameType, rating: 1000, tier: stats.tier, winCount: 0, loseCount: 0, desertCount: 0, bestScore: null };
}

/**
 * D-166 — 게임판 좌석에 붙일 계급(userId → 계급). 봇·게스트는 빼고, 전적을 아직 못 받은 사람도 뺀다(받으면 붙는다). 그 게임을
 * 줄이 없으면(`null`) 시작 계급이다(D-305 — 줄의 계급은 통합 계급).
 */
export function playerTiers(
  playerIds: number[],
  botSeats: number[],
  usernames: Record<number, string>,
  records: Record<number, GameRecord | null>,
): Record<number, Tier> {
  const out: Record<number, Tier> = {};
  playerIds.forEach((id, seat) => {
    if (botSeats.includes(seat) || isGuestName(usernames[id])) return;
    const rec = records[id];
    if (rec === undefined) return;
    out[id] = rec?.tier ?? STARTING_TIER;
  });
  return out;
}
