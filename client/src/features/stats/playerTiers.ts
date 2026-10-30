import type { GameRecord, Tier } from '@/api/users';

/** D-117 — 게스트는 username 접두 `guest-`(하이픈은 가입 이름 규칙 밖이라 위조 불가). 서버 `GuestPolicy` 와 같은 규약. */
export function isGuestName(username: string | undefined): boolean {
  return username?.startsWith('guest-') ?? false;
}

/** 한 판도 안 한 사람의 계급 — 시작 1000점(D-164). */
export const STARTING_TIER: Tier = 'MISSILE';

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
