import type { GameRecord, Tier, UserStats } from '@/api/users';

/** D-117 — 게스트는 username 접두 `guest-`(하이픈은 가입 이름 규칙 밖이라 위조 불가). 서버 `GuestPolicy` 와 같은 규약. */
export function isGuestName(username: string | undefined): boolean {
  return username?.startsWith('guest-') ?? false;
}

/** 한 판도 안 한 사람의 계급 — 시작 1000점(D-164). */
export const STARTING_TIER: Tier = 'MISSILE';

/**
 * D-115 — 이 게임의 전적 줄(안 해 봤으면 null). D-198 — 명예 계정(`fixedTier`)은 안 해 본 게임도 0승 0패 줄을 만들어 그 계급을
 * 싣는다(대기실 카드·게임판 좌석이 같은 계급을 그리게). 서버가 이미 `games[]` 의 계급을 바꿔 주지만 여기서도 맞춘다.
 */
export function gameRecordOf(stats: UserStats, gameType: string): GameRecord | null {
  const rec = stats.games.find((g) => g.gameType === gameType) ?? null;
  if (!stats.fixedTier) return rec;
  if (rec) return { ...rec, tier: stats.fixedTier };
  return { gameType, rating: 1000, tier: stats.fixedTier, winCount: 0, loseCount: 0, desertCount: 0, bestScore: null };
}

/**
 * D-166 — 게임판 좌석에 붙일 계급(userId → 계급). 봇·게스트는 빼고, 전적을 아직 못 받은 사람도 뺀다(받으면 붙는다). 그 게임을
 * 한 판도 안 했으면(`null`) 시작 계급이다.
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
